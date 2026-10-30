package com.delveronline.net;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A UDP socket hosting reliable-UDP {@link Connection}s, either as a server (accepts many) or a client (one).
 * A daemon thread only receives datagrams; all protocol work happens in {@link #update()}.
 */
public final class NetEndpoint implements Closeable {
    static final int MAGIC = 0x444F; // "DO"
    static final int TRANSPORT_VERSION = 1;
    static final byte TYPE_CONNECT = 1;
    static final byte TYPE_ACCEPT = 2;
    static final byte TYPE_DENY = 3;
    static final byte TYPE_DATA = 4;
    static final byte TYPE_DISCONNECT = 5;

    private static final long HANDSHAKE_RETRY_MS = 250;
    private static final int HANDSHAKE_MAX_ATTEMPTS = 20;

    private final DatagramSocket socket;
    final NetListener listener;
    private final boolean isServer;
    private final int maxConnections;
    private final Map<SocketAddress, Connection> connections = new HashMap<>();
    private final ConcurrentLinkedQueue<DatagramPacket> inbox = new ConcurrentLinkedQueue<>();
    private final Thread receiver;
    private volatile boolean closed;
    private final ByteWriter scratch = new ByteWriter(Connection.MTU_PAYLOAD + 64);
    private final Random random = new SecureRandom();
    private long timeoutMs = 10_000;

    // test-only network simulation
    private double simulatedLoss;
    private int simulatedLatencyMs;
    private final Random simRandom = new Random(1234);
    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>((a, b) -> Long.compare(a.releaseMs, b.releaseMs));

    private static final class Delayed {
        long releaseMs;
        byte[] data;
        SocketAddress to;
    }

    private NetEndpoint(DatagramSocket socket, NetListener listener, boolean isServer, int maxConnections) {
        this.socket = socket;
        this.listener = listener;
        this.isServer = isServer;
        this.maxConnections = maxConnections;
        this.receiver = new Thread(this::receiveLoop, isServer ? "DelverOnline-net-server" : "DelverOnline-net-client");
        this.receiver.setDaemon(true);
        this.receiver.start();
    }

    public static NetEndpoint server(int port, int maxConnections, NetListener listener) throws SocketException {
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(false);
        s.bind(new InetSocketAddress(port));
        configure(s);
        return new NetEndpoint(s, listener, true, maxConnections);
    }

    public static NetEndpoint client(NetListener listener) throws SocketException {
        DatagramSocket s = new DatagramSocket();
        configure(s);
        return new NetEndpoint(s, listener, false, 1);
    }

    private static void configure(DatagramSocket s) throws SocketException {
        s.setSoTimeout(250);
        try {
            s.setReceiveBufferSize(4 * 1024 * 1024);
            s.setSendBufferSize(4 * 1024 * 1024);
        } catch (SocketException ignored) {
            // best effort
        }
    }

    public int getLocalPort() {
        return socket.getLocalPort();
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /** Test helper: drop this fraction of outgoing datagrams and delay the rest. */
    public void simulate(double loss, int latencyMs) {
        this.simulatedLoss = loss;
        this.simulatedLatencyMs = latencyMs;
    }

    public Collection<Connection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    /** Client: start connecting. Completion is reported via {@link NetListener#onConnected}. */
    public Connection connect(InetSocketAddress address) {
        if (isServer) throw new IllegalStateException("server endpoint cannot connect");
        long now = System.currentTimeMillis();
        Connection c = new Connection(this, address, random.nextLong(), now);
        connections.put(address, c);
        sendConnect(c, now);
        return c;
    }

    private void sendConnect(Connection c, long now) {
        scratch.reset();
        writeHeader(scratch, TYPE_CONNECT, 0);
        scratch.writeLong(c.nonce);
        // pad so handshake packets are not an amplification vector
        scratch.writeRaw(new byte[64], 0, 64);
        sendRaw(scratch, c.address);
        c.lastHandshakeMs = now;
        c.handshakeAttempts++;
    }

    void writeHeader(ByteWriter w, byte type, int token) {
        w.writeShort(MAGIC);
        w.writeByte(TRANSPORT_VERSION);
        w.writeByte(type);
        w.writeInt(token);
    }

    void sendRaw(ByteWriter w, SocketAddress to) {
        if (closed) return;
        byte[] data = Arrays.copyOf(w.array(), w.size());
        if (simulatedLoss > 0 && simRandom.nextDouble() < simulatedLoss) return;
        if (simulatedLatencyMs > 0) {
            Delayed d = new Delayed();
            d.releaseMs = System.currentTimeMillis() + simulatedLatencyMs;
            d.data = data;
            d.to = to;
            delayed.add(d);
            return;
        }
        transmit(data, to);
    }

    private void transmit(byte[] data, SocketAddress to) {
        try {
            socket.send(new DatagramPacket(data, data.length, to));
        } catch (IOException e) {
            // transient send failures (e.g. ICMP unreachable) are handled by timeouts
        }
    }

    private void receiveLoop() {
        byte[] buf = new byte[2048];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
                byte[] copy = Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
                inbox.add(new DatagramPacket(copy, copy.length, p.getSocketAddress()));
            } catch (SocketTimeoutException e) {
                // loop to check closed flag
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    /** Processes received datagrams, handshakes, timeouts, loss detection and sends pending data. */
    public void update() {
        long now = System.currentTimeMillis();
        while (!delayed.isEmpty() && delayed.peek().releaseMs <= now) {
            Delayed d = delayed.poll();
            transmit(d.data, d.to);
        }
        DatagramPacket p;
        while ((p = inbox.poll()) != null) {
            try {
                handleDatagram(p, now);
            } catch (ProtocolException | IndexOutOfBoundsException e) {
                Connection c = connections.get(p.getSocketAddress());
                if (c != null && c.state == Connection.State.CONNECTED) closeConnection(c, "protocol error: " + e.getMessage(), true);
            }
        }
        for (Connection c : new ArrayList<>(connections.values())) {
            if (c.state == Connection.State.CONNECTING) {
                if (!isServer && now - c.lastHandshakeMs >= HANDSHAKE_RETRY_MS) {
                    if (c.handshakeAttempts >= HANDSHAKE_MAX_ATTEMPTS) {
                        closeConnection(c, "no response from server", false);
                    } else {
                        sendConnect(c, now);
                    }
                }
                continue;
            }
            if (c.state != Connection.State.CONNECTED) continue;
            if (now - c.lastReceiveMs > timeoutMs) {
                closeConnection(c, "timed out", false);
                continue;
            }
            c.checkLoss(now);
            c.flush(now, scratch);
        }
    }

    private void handleDatagram(DatagramPacket p, long now) {
        ByteReader r = new ByteReader(p.getData(), p.getOffset(), p.getLength());
        if (r.remaining() < 8) return;
        if (r.readUShort() != MAGIC) return;
        if (r.readUByte() != TRANSPORT_VERSION) return;
        byte type = (byte) r.readByte();
        int token = r.readInt();
        SocketAddress from = p.getSocketAddress();
        Connection c = connections.get(from);
        switch (type) {
            case TYPE_CONNECT: {
                if (!isServer) return;
                long nonce = r.readLong();
                if (c != null && c.nonce != nonce) {
                    // same address reconnecting with a new session: drop the old one
                    closeConnection(c, "reconnected", false);
                    c = null;
                }
                if (c == null) {
                    if (countConnected() >= maxConnections) {
                        scratch.reset();
                        writeHeader(scratch, TYPE_DENY, 0);
                        scratch.writeLong(nonce);
                        scratch.writeString("server full");
                        sendRaw(scratch, from);
                        return;
                    }
                    c = new Connection(this, (InetSocketAddress) from, nonce, now);
                    c.token = random.nextInt() | 1;
                    c.state = Connection.State.CONNECTED;
                    connections.put(from, c);
                    sendAccept(c);
                    listener.onConnected(c);
                } else {
                    sendAccept(c);
                }
                return;
            }
            case TYPE_ACCEPT: {
                if (isServer || c == null) return;
                long nonce = r.readLong();
                if (nonce != c.nonce || c.state != Connection.State.CONNECTING) return;
                c.token = token;
                c.state = Connection.State.CONNECTED;
                c.lastReceiveMs = now;
                listener.onConnected(c);
                return;
            }
            case TYPE_DENY: {
                if (isServer || c == null) return;
                long nonce = r.readLong();
                if (nonce != c.nonce) return;
                String reason = r.readString();
                closeConnection(c, reason == null ? "denied" : reason, false);
                return;
            }
            case TYPE_DATA: {
                if (c == null || c.state != Connection.State.CONNECTED || token != c.token) return;
                c.onDataPacket(r, now);
                return;
            }
            case TYPE_DISCONNECT: {
                if (c == null || token != c.token) return;
                String reason = r.remaining() > 0 ? r.readString() : null;
                closeConnection(c, reason == null ? "closed by remote" : reason, false);
                return;
            }
            default:
        }
    }

    private void sendAccept(Connection c) {
        scratch.reset();
        writeHeader(scratch, TYPE_ACCEPT, c.token);
        scratch.writeLong(c.nonce);
        sendRaw(scratch, c.address);
    }

    private int countConnected() {
        int n = 0;
        for (Connection c : connections.values()) {
            if (c.state == Connection.State.CONNECTED) n++;
        }
        return n;
    }

    void closeConnection(Connection c, String reason, boolean notifyRemote) {
        if (c.state == Connection.State.DISCONNECTED) return;
        boolean wasConnected = c.state == Connection.State.CONNECTED;
        if (notifyRemote && wasConnected) {
            // flush whatever is pending once, then send the disconnect notice a few times
            c.flush(System.currentTimeMillis(), scratch);
            for (int i = 0; i < 3; i++) {
                scratch.reset();
                writeHeader(scratch, TYPE_DISCONNECT, c.token);
                scratch.writeString(reason);
                sendRaw(scratch, c.address);
            }
        }
        c.markDisconnected(reason);
        connections.remove(c.address);
        listener.onDisconnected(c, reason);
    }

    @Override
    public void close() {
        for (Connection c : new ArrayList<>(connections.values())) {
            closeConnection(c, "shutdown", true);
        }
        long now = System.currentTimeMillis();
        while (!delayed.isEmpty()) {
            Delayed d = delayed.poll();
            if (d.releaseMs >= now - 1000) transmit(d.data, d.to);
        }
        closed = true;
        socket.close();
        try {
            receiver.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
