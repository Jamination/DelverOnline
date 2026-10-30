package com.delveronline.net;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * One reliable-UDP peer. Two logical channels share each datagram:
 * <ul>
 *   <li>reliable ordered: messages are split into units (fragments), resent when the carrying packet is lost,
 *       and delivered strictly in order;</li>
 *   <li>unreliable: delivered at most once, with per-message delivered/lost notification.</li>
 * </ul>
 * Packet acknowledgement uses (latest seq, 64-bit history) on every outgoing packet.
 * Not thread-safe: use from the thread that calls {@link NetEndpoint#update()}.
 */
public final class Connection {
    public enum State { CONNECTING, CONNECTED, DISCONNECTED }

    static final int MTU_PAYLOAD = 1200;
    static final int MAX_UNIT_DATA = 1100;
    static final int UNIT_WINDOW = 1024;
    static final int PACKET_RING = 1024;
    static final int MAX_PACKETS_PER_FLUSH = 48;
    static final long KEEPALIVE_MS = 100;

    static final byte BLOCK_RELIABLE = 1;
    static final byte BLOCK_UNRELIABLE = 2;
    static final byte UNIT_WHOLE = 0;
    static final byte UNIT_FRAG_START = 1;
    static final byte UNIT_FRAG_CONT = 2;

    final NetEndpoint endpoint;
    final InetSocketAddress address;
    final long nonce;
    int token;
    State state = State.CONNECTING;
    String closeReason;
    private Object userData;

    // --- packet level ---
    private int nextSendSeq = 0;
    private int remoteLatestSeq = -1;
    private long remoteAckBits = 0;
    private boolean ackPending;
    private final PacketRecord[] sentRing = new PacketRecord[PACKET_RING];
    private int oldestUnresolvedSeq = 0;
    long lastReceiveMs;
    long lastSendMs;
    long createdMs;
    long lastHandshakeMs;
    int handshakeAttempts;

    // --- reliable send ---
    private final ArrayDeque<byte[]> reliableQueue = new ArrayDeque<>();
    private final ArrayDeque<DeliveryCallback> reliableCallbackQueue = new ArrayDeque<>();
    private byte[] splitting;
    private DeliveryCallback splittingCallback;
    private int splittingOffset;
    private final Unit[] sendUnits = new Unit[UNIT_WINDOW];
    private int nextUnitSeq = 0;
    private int oldestUnackedUnit = 0;
    private int resendCursorHint = 0;

    // --- reliable receive ---
    private final RecvUnit[] recvUnits = new RecvUnit[UNIT_WINDOW];
    private int nextExpectedUnit = 0;
    private ByteWriter assembling;
    private int assemblingRemaining;

    // --- unreliable send ---
    private final ArrayList<byte[]> unreliableQueue = new ArrayList<>();
    private final ArrayList<DeliveryCallback> unreliableCallbacks = new ArrayList<>();

    // --- stats ---
    private float rttMs = 100f;
    private boolean rttSampled;
    public long bytesSent, bytesReceived, packetsSent, packetsReceived, packetsLost, reliableResends;

    Connection(NetEndpoint endpoint, InetSocketAddress address, long nonce, long nowMs) {
        this.endpoint = endpoint;
        this.address = address;
        this.nonce = nonce;
        this.createdMs = nowMs;
        this.lastReceiveMs = nowMs;
        this.lastSendMs = nowMs;
    }

    private static final class PacketRecord {
        int seq;
        long sentMs;
        boolean resolved;
        final ArrayList<Unit> units = new ArrayList<>(4);
        final ArrayList<DeliveryCallback> callbacks = new ArrayList<>(4);
    }

    private static final class Unit {
        int seq;
        byte kind;
        int fragCount;
        byte[] data;
        boolean acked;
        boolean needsSend = true;
        /** Set on the last unit of a message sent with a callback. */
        DeliveryCallback callback;
    }

    private static final class RecvUnit {
        int seq;
        byte kind;
        int fragCount;
        byte[] data;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public State getState() {
        return state;
    }

    public boolean isConnected() {
        return state == State.CONNECTED;
    }

    public float getRttMs() {
        return rttMs;
    }

    public String getCloseReason() {
        return closeReason;
    }

    @SuppressWarnings("unchecked")
    public <T> T getUserData() {
        return (T) userData;
    }

    public void setUserData(Object userData) {
        this.userData = userData;
    }

    /** Number of reliable bytes queued or in flight but not yet acknowledged (approximate). */
    public int pendingReliableUnits() {
        return (nextUnitSeq - oldestUnackedUnit) + reliableQueue.size() + (splitting != null ? 1 : 0);
    }

    /** Queue a message on the reliable ordered channel. */
    public void sendReliable(byte[] message) {
        sendReliable(message, null);
    }

    /**
     * Queue a message on the reliable ordered channel; {@code callback.delivered()} runs once the peer has received
     * it and everything sent before it ({@code lost()} only if the connection closes first).
     */
    public void sendReliable(byte[] message, DeliveryCallback callback) {
        if (state == State.DISCONNECTED) {
            if (callback != null) callback.lost();
            return;
        }
        reliableQueue.add(message);
        reliableCallbackQueue.add(callback == null ? NO_CALLBACK : callback);
    }

    private static final DeliveryCallback NO_CALLBACK = new DeliveryCallback() {
        @Override
        public void delivered() {}

        @Override
        public void lost() {}
    };

    /** Queue a message on the unreliable channel. {@code callback} may be null. Messages must fit one packet. */
    public void sendUnreliable(byte[] message, DeliveryCallback callback) {
        if (state == State.DISCONNECTED) {
            if (callback != null) callback.lost();
            return;
        }
        if (message.length > MAX_UNIT_DATA) throw new IllegalArgumentException("unreliable message too large: " + message.length);
        unreliableQueue.add(message);
        unreliableCallbacks.add(callback);
    }

    /** Gracefully close; the remote side is notified. */
    public void close(String reason) {
        endpoint.closeConnection(this, reason, true);
    }

    // ------------------------------------------------------------------ receive

    void onDataPacket(ByteReader r, long now) {
        int wireSeq = r.readUShort();
        int wireAck = r.readUShort();
        long ackBits = r.readLong();
        lastReceiveMs = now;
        packetsReceived++;

        int seq = remoteLatestSeq < 0 ? wireSeq : expand(remoteLatestSeq, wireSeq);
        boolean duplicate = false;
        if (remoteLatestSeq < 0) {
            remoteLatestSeq = seq;
            remoteAckBits = 0;
        } else if (seq > remoteLatestSeq) {
            int shift = seq - remoteLatestSeq;
            remoteAckBits = shift >= 64 ? 0 : (remoteAckBits << shift);
            if (shift <= 64) remoteAckBits |= 1L << (shift - 1);
            remoteLatestSeq = seq;
        } else if (seq == remoteLatestSeq) {
            duplicate = true;
        } else {
            int back = remoteLatestSeq - seq;
            if (back > 64) {
                duplicate = true; // too old to track; ignore payload
            } else {
                long bit = 1L << (back - 1);
                if ((remoteAckBits & bit) != 0) duplicate = true;
                remoteAckBits |= bit;
            }
        }
        ackPending = true;

        processAcks(wireAck, ackBits, now);
        if (duplicate) return;

        while (r.remaining() > 0) {
            int block = r.readByte();
            if (block == BLOCK_RELIABLE) {
                int wireUnit = r.readUShort();
                byte kind = (byte) r.readByte();
                int fragCount = kind == UNIT_FRAG_START ? r.readVarInt() : 0;
                byte[] data = r.readBytes();
                if (data == null) data = new byte[0];
                onReliableUnit(expand(nextExpectedUnit, wireUnit), kind, fragCount, data);
            } else if (block == BLOCK_UNRELIABLE) {
                byte[] data = r.readBytes();
                if (data == null) data = new byte[0];
                if (state == State.CONNECTED) endpoint.listener.onMessage(this, data, false, seq);
            } else {
                throw new ProtocolException("unknown block " + block);
            }
            if (state == State.DISCONNECTED) return;
        }
    }

    /** Reconstructs a full sequence number from its low 16 bits, choosing the value closest to {@code reference}. */
    static int expand(int reference, int wire16) {
        int diff = (short) (wire16 - (reference & 0xFFFF));
        return reference + diff;
    }

    private void processAcks(int wireAck, long bits, long now) {
        if (nextSendSeq == 0) return;
        int ack = expand(nextSendSeq - 1, wireAck);
        if (ack >= nextSendSeq || ack < nextSendSeq - PACKET_RING) return;
        ackPacket(ack, now);
        for (int i = 0; i < 64; i++) {
            if ((bits & (1L << i)) != 0) ackPacket(ack - 1 - i, now);
        }
        // Anything older than the 64-packet ack window that is still unresolved was not acknowledged in time.
        int floor = ack - 64;
        while (oldestUnresolvedSeq < floor) {
            PacketRecord rec = sentRing[oldestUnresolvedSeq & (PACKET_RING - 1)];
            if (rec != null && rec.seq == oldestUnresolvedSeq && !rec.resolved) resolveLost(rec);
            oldestUnresolvedSeq++;
        }
    }

    private void ackPacket(int seq, long now) {
        if (seq < 0) return;
        PacketRecord rec = sentRing[seq & (PACKET_RING - 1)];
        if (rec == null || rec.seq != seq || rec.resolved) return;
        rec.resolved = true;
        float sample = now - rec.sentMs;
        if (!rttSampled) {
            rttMs = sample;
            rttSampled = true;
        } else {
            rttMs += (sample - rttMs) * 0.1f;
        }
        for (Unit u : rec.units) {
            u.acked = true;
        }
        for (DeliveryCallback cb : rec.callbacks) {
            if (cb != null) cb.delivered();
        }
        rec.units.clear();
        rec.callbacks.clear();
        advanceUnitWindow();
    }

    private void resolveLost(PacketRecord rec) {
        rec.resolved = true;
        packetsLost++;
        for (Unit u : rec.units) {
            if (!u.acked && !u.needsSend) {
                u.needsSend = true;
                reliableResends++;
            }
        }
        for (DeliveryCallback cb : rec.callbacks) {
            if (cb != null) cb.lost();
        }
        rec.units.clear();
        rec.callbacks.clear();
        resendCursorHint = oldestUnackedUnit;
    }

    private void advanceUnitWindow() {
        while (oldestUnackedUnit < nextUnitSeq) {
            Unit u = sendUnits[oldestUnackedUnit & (UNIT_WINDOW - 1)];
            if (u == null || !u.acked) break;
            sendUnits[oldestUnackedUnit & (UNIT_WINDOW - 1)] = null;
            oldestUnackedUnit++;
            if (u.callback != null) {
                DeliveryCallback cb = u.callback;
                u.callback = null;
                cb.delivered();
            }
        }
        if (resendCursorHint < oldestUnackedUnit) resendCursorHint = oldestUnackedUnit;
    }

    private void onReliableUnit(int seq, byte kind, int fragCount, byte[] data) {
        if (seq < nextExpectedUnit || seq >= nextExpectedUnit + UNIT_WINDOW) return;
        int idx = seq & (UNIT_WINDOW - 1);
        if (recvUnits[idx] != null && recvUnits[idx].seq == seq) return;
        RecvUnit ru = new RecvUnit();
        ru.seq = seq;
        ru.kind = kind;
        ru.fragCount = fragCount;
        ru.data = data;
        recvUnits[idx] = ru;
        // deliver in order
        while (true) {
            int i = nextExpectedUnit & (UNIT_WINDOW - 1);
            RecvUnit next = recvUnits[i];
            if (next == null || next.seq != nextExpectedUnit) break;
            recvUnits[i] = null;
            nextExpectedUnit++;
            deliverUnit(next);
            if (state == State.DISCONNECTED) return;
        }
    }

    private void deliverUnit(RecvUnit u) {
        switch (u.kind) {
            case UNIT_WHOLE:
                if (assembling != null) throw new ProtocolException("whole unit inside fragmented message");
                if (state == State.CONNECTED) endpoint.listener.onMessage(this, u.data, true, remoteLatestSeq);
                break;
            case UNIT_FRAG_START:
                if (assembling != null) throw new ProtocolException("nested fragment start");
                if (u.fragCount < 2) throw new ProtocolException("bad fragment count");
                assembling = new ByteWriter(u.fragCount * MAX_UNIT_DATA);
                assembling.writeRaw(u.data, 0, u.data.length);
                assemblingRemaining = u.fragCount - 1;
                break;
            case UNIT_FRAG_CONT:
                if (assembling == null) throw new ProtocolException("orphan fragment");
                assembling.writeRaw(u.data, 0, u.data.length);
                if (--assemblingRemaining == 0) {
                    byte[] whole = assembling.toByteArray();
                    assembling = null;
                    if (state == State.CONNECTED) endpoint.listener.onMessage(this, whole, true, remoteLatestSeq);
                }
                break;
            default:
                throw new ProtocolException("bad unit kind " + u.kind);
        }
    }

    // ------------------------------------------------------------------ send

    /** Times out stale packets (declares them lost) so reliable data is resent promptly. */
    void checkLoss(long now) {
        long lossTimeout = (long) Math.min(1000f, Math.max(60f, rttMs * 2f + 30f));
        while (oldestUnresolvedSeq < nextSendSeq) {
            PacketRecord rec = sentRing[oldestUnresolvedSeq & (PACKET_RING - 1)];
            if (rec == null || rec.seq != oldestUnresolvedSeq || rec.resolved) {
                oldestUnresolvedSeq++;
                continue;
            }
            if (now - rec.sentMs < lossTimeout) break;
            resolveLost(rec);
            oldestUnresolvedSeq++;
        }
    }

    private void fillUnitWindow() {
        while (nextUnitSeq - oldestUnackedUnit < UNIT_WINDOW) {
            if (splitting == null) {
                byte[] msg = reliableQueue.poll();
                if (msg == null) return;
                DeliveryCallback cb = reliableCallbackQueue.poll();
                if (cb == NO_CALLBACK) cb = null;
                if (msg.length <= MAX_UNIT_DATA) {
                    addUnit(UNIT_WHOLE, 0, msg).callback = cb;
                    continue;
                }
                splitting = msg;
                splittingCallback = cb;
                splittingOffset = 0;
                int frags = (msg.length + MAX_UNIT_DATA - 1) / MAX_UNIT_DATA;
                addUnit(UNIT_FRAG_START, frags, slice(msg, 0));
                splittingOffset = MAX_UNIT_DATA;
            } else {
                Unit u = addUnit(UNIT_FRAG_CONT, 0, slice(splitting, splittingOffset));
                splittingOffset += MAX_UNIT_DATA;
                if (splittingOffset >= splitting.length) {
                    u.callback = splittingCallback;
                    splitting = null;
                    splittingCallback = null;
                }
            }
        }
    }

    private static byte[] slice(byte[] src, int off) {
        int len = Math.min(MAX_UNIT_DATA, src.length - off);
        byte[] b = new byte[len];
        System.arraycopy(src, off, b, 0, len);
        return b;
    }

    private Unit addUnit(byte kind, int fragCount, byte[] data) {
        Unit u = new Unit();
        u.seq = nextUnitSeq++;
        u.kind = kind;
        u.fragCount = fragCount;
        u.data = data;
        sendUnits[u.seq & (UNIT_WINDOW - 1)] = u;
        return u;
    }

    private Unit nextUnitToSend(int from) {
        for (int s = from; s < nextUnitSeq; s++) {
            Unit u = sendUnits[s & (UNIT_WINDOW - 1)];
            if (u != null && u.seq == s && !u.acked && u.needsSend) return u;
        }
        return null;
    }

    private static int unitSize(Unit u) {
        return 1 + 2 + 1 + (u.kind == UNIT_FRAG_START ? 5 : 0) + 5 + u.data.length;
    }

    /** Sends queued data, acks, and keepalives. */
    void flush(long now, ByteWriter w) {
        if (state != State.CONNECTED) return;
        fillUnitWindow();
        int packets = 0;
        int unreliableIdx = 0;
        int cursor = Math.max(resendCursorHint, oldestUnackedUnit);
        while (packets < MAX_PACKETS_PER_FLUSH) {
            Unit nextUnit = nextUnitToSend(cursor);
            boolean haveUnreliable = unreliableIdx < unreliableQueue.size();
            boolean keepalive = now - lastSendMs >= KEEPALIVE_MS;
            if (nextUnit == null && !haveUnreliable && !ackPending && !keepalive) break;

            int seq = nextSendSeq++;
            PacketRecord rec = sentRing[seq & (PACKET_RING - 1)];
            if (rec != null && !rec.resolved) resolveLost(rec); // ring wrap: treat as lost
            if (rec == null) {
                rec = new PacketRecord();
                sentRing[seq & (PACKET_RING - 1)] = rec;
            }
            rec.seq = seq;
            rec.sentMs = now;
            rec.resolved = false;

            w.reset();
            endpoint.writeHeader(w, NetEndpoint.TYPE_DATA, token);
            w.writeShort(seq);
            w.writeShort(remoteLatestSeq < 0 ? 0 : remoteLatestSeq);
            w.writeLong(remoteLatestSeq < 0 ? 0 : remoteAckBits);
            // reliable units first (they are the ones that need ordering)
            while (nextUnit != null && w.size() + unitSize(nextUnit) <= MTU_PAYLOAD) {
                w.writeByte(BLOCK_RELIABLE);
                w.writeShort(nextUnit.seq);
                w.writeByte(nextUnit.kind);
                if (nextUnit.kind == UNIT_FRAG_START) w.writeVarInt(nextUnit.fragCount);
                w.writeBytes(nextUnit.data);
                nextUnit.needsSend = false;
                rec.units.add(nextUnit);
                cursor = nextUnit.seq + 1;
                nextUnit = nextUnitToSend(cursor);
            }
            while (unreliableIdx < unreliableQueue.size()) {
                byte[] m = unreliableQueue.get(unreliableIdx);
                if (w.size() + 1 + 5 + m.length > MTU_PAYLOAD) break;
                w.writeByte(BLOCK_UNRELIABLE);
                w.writeBytes(m);
                rec.callbacks.add(unreliableCallbacks.get(unreliableIdx));
                unreliableIdx++;
            }
            endpoint.sendRaw(w, address);
            bytesSent += w.size();
            packetsSent++;
            lastSendMs = now;
            ackPending = false;
            packets++;
            if (nextUnit == null && unreliableIdx >= unreliableQueue.size()) break;
        }
        resendCursorHint = Math.max(oldestUnackedUnit, cursor);
        if (unreliableIdx > 0) {
            // anything not sent this flush stays queued (rare: only when packet budget is exhausted)
            unreliableQueue.subList(0, unreliableIdx).clear();
            unreliableCallbacks.subList(0, unreliableIdx).clear();
        }
        // Drop a backlog of unreliable data rather than letting it grow without bound.
        if (unreliableQueue.size() > 512) {
            for (DeliveryCallback cb : unreliableCallbacks) {
                if (cb != null) cb.lost();
            }
            unreliableQueue.clear();
            unreliableCallbacks.clear();
        }
    }

    void markDisconnected(String reason) {
        if (state == State.DISCONNECTED) return;
        state = State.DISCONNECTED;
        closeReason = reason;
        for (PacketRecord rec : sentRing) {
            if (rec != null && !rec.resolved) {
                rec.resolved = true;
                for (DeliveryCallback cb : rec.callbacks) {
                    if (cb != null) cb.lost();
                }
            }
        }
        for (DeliveryCallback cb : unreliableCallbacks) {
            if (cb != null) cb.lost();
        }
        unreliableQueue.clear();
        unreliableCallbacks.clear();
        for (Unit u : sendUnits) {
            if (u != null && u.callback != null) {
                DeliveryCallback cb = u.callback;
                u.callback = null;
                cb.lost();
            }
        }
        if (splittingCallback != null) splittingCallback.lost();
        splittingCallback = null;
        for (DeliveryCallback cb : reliableCallbackQueue) {
            if (cb != NO_CALLBACK) cb.lost();
        }
        reliableCallbackQueue.clear();
        reliableQueue.clear();
    }

    @Override
    public String toString() {
        return "Connection[" + address + ", " + state + ", rtt=" + (int) rttMs + "ms]";
    }
}
