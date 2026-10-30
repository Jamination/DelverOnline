package com.delveronline.net;

import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransportTest {

    static final class Recorder implements NetListener {
        final List<Connection> connected = new ArrayList<>();
        final List<String> disconnects = new ArrayList<>();
        final List<byte[]> reliable = new ArrayList<>();
        final List<byte[]> unreliable = new ArrayList<>();

        @Override
        public void onConnected(Connection connection) {
            connected.add(connection);
        }

        @Override
        public void onDisconnected(Connection connection, String reason) {
            disconnects.add(reason);
        }

        @Override
        public void onMessage(Connection connection, byte[] data, boolean rel, int packetSeq) {
            (rel ? reliable : unreliable).add(data);
        }
    }

    interface Cond {
        boolean done();
    }

    static void pump(NetEndpoint a, NetEndpoint b, long timeoutMs, Cond cond) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (!cond.done()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for condition");
            a.update();
            b.update();
            Thread.sleep(2);
        }
    }

    private static byte[] msg(int i, int size) {
        byte[] b = new byte[size];
        new Random(i).nextBytes(b);
        b[0] = (byte) (i >>> 24);
        b[1] = (byte) (i >>> 16);
        b[2] = (byte) (i >>> 8);
        b[3] = (byte) i;
        return b;
    }

    private static int id(byte[] b) {
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
    }

    @Test
    void reliableMessagesArriveInOrderIntactUnderLossAndLatency() throws Exception {
        Recorder sr = new Recorder(), cr = new Recorder();
        try (NetEndpoint server = NetEndpoint.server(0, 8, sr); NetEndpoint client = NetEndpoint.client(cr)) {
            server.simulate(0.2, 15);
            client.simulate(0.2, 15);
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            pump(server, client, 10_000, () -> !cr.connected.isEmpty() && !sr.connected.isEmpty());

            Connection toServer = cr.connected.get(0);
            Random sizes = new Random(7);
            int count = 600;
            List<byte[]> sent = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                // mix of tiny, packet-sized and multi-fragment messages
                int size = i % 50 == 0 ? 20_000 + sizes.nextInt(30_000) : 4 + sizes.nextInt(1500);
                byte[] m = msg(i, size);
                sent.add(m);
                toServer.sendReliable(m);
            }
            pump(server, client, 60_000, () -> sr.reliable.size() == count);
            for (int i = 0; i < count; i++) {
                assertEquals(i, id(sr.reliable.get(i)), "order");
                assertArrayEquals(sent.get(i), sr.reliable.get(i), "content of message " + i);
            }
            assertTrue(toServer.packetsLost > 0, "loss simulation should have caused losses");
        }
    }

    @Test
    void largeTransferCompletesQuicklyOnLoopback() throws Exception {
        Recorder sr = new Recorder(), cr = new Recorder();
        try (NetEndpoint server = NetEndpoint.server(0, 8, sr); NetEndpoint client = NetEndpoint.client(cr)) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            pump(server, client, 5_000, () -> !sr.connected.isEmpty() && !cr.connected.isEmpty());
            byte[] big = msg(42, 3 * 1024 * 1024);
            sr.connected.get(0).sendReliable(big);
            long t = System.currentTimeMillis();
            pump(server, client, 20_000, () -> cr.reliable.size() == 1);
            long took = System.currentTimeMillis() - t;
            assertArrayEquals(big, cr.reliable.get(0));
            assertTrue(took < 5_000, "3MB over loopback took " + took + "ms");
        }
    }

    @Test
    void unreliableCallbacksAreResolvedExactlyOnce() throws Exception {
        Recorder sr = new Recorder(), cr = new Recorder();
        try (NetEndpoint server = NetEndpoint.server(0, 8, sr); NetEndpoint client = NetEndpoint.client(cr)) {
            client.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
            pump(server, client, 5_000, () -> !sr.connected.isEmpty() && !cr.connected.isEmpty());
            server.simulate(0.3, 5);
            AtomicInteger delivered = new AtomicInteger(), lost = new AtomicInteger(), doubles = new AtomicInteger();
            int n = 2000;
            Connection c = sr.connected.get(0);
            for (int i = 0; i < n; i++) {
                final boolean[] seen = {false};
                c.sendUnreliable(msg(i, 40), new DeliveryCallback() {
                    @Override
                    public void delivered() {
                        if (seen[0]) doubles.incrementAndGet();
                        seen[0] = true;
                        delivered.incrementAndGet();
                    }

                    @Override
                    public void lost() {
                        if (seen[0]) doubles.incrementAndGet();
                        seen[0] = true;
                        lost.incrementAndGet();
                    }
                });
                if (i % 20 == 0) {
                    server.update();
                    client.update();
                }
            }
            pump(server, client, 10_000, () -> delivered.get() + lost.get() == n);
            assertEquals(0, doubles.get());
            assertTrue(lost.get() > 0 && delivered.get() > 0);
            // the receiver got at least everything reported delivered
            assertTrue(cr.unreliable.size() >= delivered.get(), cr.unreliable.size() + " < " + delivered.get());
        }
    }

    @Test
    void serverRejectsWhenFullAndDetectsDisconnect() throws Exception {
        Recorder sr = new Recorder(), c1r = new Recorder(), c2r = new Recorder();
        try (NetEndpoint server = NetEndpoint.server(0, 1, sr);
             NetEndpoint c1 = NetEndpoint.client(c1r);
             NetEndpoint c2 = NetEndpoint.client(c2r)) {
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", server.getLocalPort());
            c1.connect(addr);
            pump(server, c1, 5_000, () -> !c1r.connected.isEmpty());
            c2.connect(addr);
            long end = System.currentTimeMillis() + 5_000;
            while (c2r.disconnects.isEmpty() && System.currentTimeMillis() < end) {
                server.update();
                c2.update();
                c1.update();
                Thread.sleep(2);
            }
            assertEquals(java.util.Arrays.asList("server full"), c2r.disconnects);
            c1r.connected.get(0).close("bye");
            pump(server, c1, 5_000, () -> !sr.disconnects.isEmpty());
            assertEquals("bye", sr.disconnects.get(0));
        }
    }

    @Test
    void sequenceExpansionHandlesWrap() {
        assertEquals(65536, Connection.expand(65530, 0));
        assertEquals(65529, Connection.expand(65540, 65529));
        assertEquals(10, Connection.expand(5, 10));
    }
}
