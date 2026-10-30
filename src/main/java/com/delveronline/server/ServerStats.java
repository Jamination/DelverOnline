package com.delveronline.server;

import com.delveronline.Mp;
import com.delveronline.net.ByteReader;
import com.delveronline.net.Connection;
import com.interrupt.dungeoneer.entities.Actor;
import com.interrupt.dungeoneer.entities.Entity;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server performance and consistency accounting: tick durations, ticks per wall-clock second, bandwidth, and
 * the replica-consistency reports sent by clients running with telemetry enabled.
 */
public final class ServerStats {
    private static final int HIST = 256;

    private long startNs;
    private long secondStartNs;
    private int ticksThisSecond;
    final List<Integer> ticksPerSecond = new ArrayList<>();
    private final List<Long> tickNanos = new ArrayList<>();
    private long lastLogMs = System.currentTimeMillis();

    // consistency reports
    boolean reportsEnabled;
    private final HashMap<Integer, float[]> history = new HashMap<>();
    private final HashMap<Integer, int[]> historyTicks = new HashMap<>();
    long samples, posDesync, hpDesync, activeDesync, reports, countMismatches;
    double maxPosError;
    private final Map<String, double[]> clientFps = new HashMap<>();

    void start() {
        startNs = System.nanoTime();
        secondStartNs = startNs;
    }

    void tick(long workNanos, DelverServer server) {
        long now = System.nanoTime();
        tickNanos.add(workNanos);
        if (tickNanos.size() > 3_600_000) tickNanos.subList(0, 1_800_000).clear();
        ticksThisSecond++;
        while (now - secondStartNs >= 1_000_000_000L) {
            ticksPerSecond.add(ticksThisSecond);
            ticksThisSecond = 0;
            secondStartNs += 1_000_000_000L;
        }
        if (reportsEnabled && server.world.tick % DelverServer.SNAPSHOT_EVERY == 0) record(server.world);
        if (System.currentTimeMillis() - lastLogMs > 60_000) {
            lastLogMs = System.currentTimeMillis();
            Mp.log(summaryLine(server));
        }
    }

    /** Remembers dynamic entity positions per snapshot tick so client reports can be checked against them. */
    private void record(ServerWorld world) {
        int slot = (world.tick / DelverServer.SNAPSHOT_EVERY) % HIST;
        if (world.level() == null) return;
        for (int id : world.replicator().trackedIds()) {
            Entity e = world.replicator().entity(id);
            if (e == null || !e.isDynamic) continue;
            float[] h = history.get(id);
            int[] t = historyTicks.get(id);
            if (h == null) {
                h = new float[HIST * 5];
                t = new int[HIST];
                history.put(id, h);
                historyTicks.put(id, t);
            }
            t[slot] = world.tick;
            h[slot * 5] = e.x;
            h[slot * 5 + 1] = e.y;
            h[slot * 5 + 2] = e.z;
            h[slot * 5 + 3] = e instanceof Actor ? ((Actor) e).hp : Integer.MIN_VALUE;
            h[slot * 5 + 4] = e.isActive ? 1 : 0;
        }
    }

    void onReport(ServerPlayer p, ByteReader r, ServerWorld world) {
        reportsEnabled = true;
        int serial = r.readVarInt();
        int tick = r.readVarInt();
        float fps = r.readFloat();
        int clientTracked = r.readVarInt();
        int n = r.readVarInt();
        boolean usable = serial == world.levelSerial() && tick > 0 && System.currentTimeMillis() - p.joinedMs > 3000;
        int slot = (tick / DelverServer.SNAPSHOT_EVERY) % HIST;
        for (int i = 0; i < n; i++) {
            int id = r.readVarInt();
            float x = r.readFloat(), y = r.readFloat(), z = r.readFloat();
            int hp = r.readSVarInt();
            boolean active = r.readBool();
            if (!usable) continue;
            float[] h = history.get(id);
            int[] t = historyTicks.get(id);
            if (h == null || t[slot] != tick) continue;
            samples++;
            double dx = x - h[slot * 5], dy = y - h[slot * 5 + 1], dz = z - h[slot * 5 + 2];
            double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (err > maxPosError) maxPosError = err;
            if (err > 0.1) posDesync++;
            if ((int) h[slot * 5 + 3] != Integer.MIN_VALUE && hp != (int) h[slot * 5 + 3]) hpDesync++;
            if (active != (h[slot * 5 + 4] != 0)) activeDesync++;
        }
        if (usable) {
            reports++;
            if (Math.abs(clientTracked - world.replicator().trackedCount()) > 2) countMismatches++;
            double[] f = clientFps.computeIfAbsent(p.name, k -> new double[2]);
            f[0] += fps;
            f[1] += 1;
        }
    }

    /** Ticks completed in the lowest 5% of one-second windows (the 95th percentile guarantee). */
    int ticksPerSecondP5() {
        if (ticksPerSecond.size() < 2) return -1;
        List<Integer> sorted = new ArrayList<>(ticksPerSecond.subList(1, ticksPerSecond.size())); // skip warm-up second
        Collections.sort(sorted);
        return sorted.get((int) Math.floor(sorted.size() * 0.05));
    }

    double tickMillisP95() {
        if (tickNanos.isEmpty()) return 0;
        List<Long> sorted = new ArrayList<>(tickNanos);
        Collections.sort(sorted);
        return sorted.get((int) Math.min(sorted.size() - 1, Math.floor(sorted.size() * 0.95))) / 1e6;
    }

    String summaryLine(DelverServer server) {
        long sent = 0, recv = 0;
        for (ServerPlayer p : server.players()) {
            Connection c = p.conn;
            sent += c.bytesSent;
            recv += c.bytesReceived;
        }
        return String.format(Locale.ROOT,
                "tick p95 %.2f ms, ticks/s p5 %d, players %d, tracked %d, sent %d KiB, desync %d/%d samples",
                tickMillisP95(), ticksPerSecondP5(), server.players().size(), server.world.replicator().trackedCount(),
                sent / 1024, posDesync + hpDesync + activeDesync, samples);
    }

    void writeSummary(DelverServer server) {
        String path = server.config.statsFile;
        if (path == null) return;
        StringBuilder sb = new StringBuilder("{\n");
        sb.append(String.format(Locale.ROOT, "  \"seconds\": %d,\n", ticksPerSecond.size()));
        sb.append(String.format(Locale.ROOT, "  \"ticksPerSecondP5\": %d,\n", ticksPerSecondP5()));
        int min = Integer.MAX_VALUE;
        for (int i = 1; i < ticksPerSecond.size(); i++) min = Math.min(min, ticksPerSecond.get(i));
        sb.append(String.format(Locale.ROOT, "  \"ticksPerSecondMin\": %d,\n", min == Integer.MAX_VALUE ? -1 : min));
        sb.append(String.format(Locale.ROOT, "  \"tickMillisP95\": %.3f,\n", tickMillisP95()));
        sb.append(String.format(Locale.ROOT, "  \"reports\": %d,\n", reports));
        sb.append(String.format(Locale.ROOT, "  \"samples\": %d,\n", samples));
        sb.append(String.format(Locale.ROOT, "  \"posDesync\": %d,\n", posDesync));
        sb.append(String.format(Locale.ROOT, "  \"hpDesync\": %d,\n", hpDesync));
        sb.append(String.format(Locale.ROOT, "  \"activeDesync\": %d,\n", activeDesync));
        sb.append(String.format(Locale.ROOT, "  \"countMismatches\": %d,\n", countMismatches));
        sb.append(String.format(Locale.ROOT, "  \"maxPosError\": %.4f,\n", maxPosError));
        sb.append(String.format(Locale.ROOT, "  \"levelSerial\": %d,\n", server.world.levelSerial()));
        sb.append("  \"clientFps\": {");
        boolean first = true;
        for (Map.Entry<String, double[]> e : clientFps.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            sb.append(String.format(Locale.ROOT, "\"%s\": %.2f", e.getKey(), e.getValue()[0] / Math.max(1, e.getValue()[1])));
        }
        sb.append("},\n");
        sb.append(String.format(Locale.ROOT, "  \"bytesUpdates\": %d,\n", server.world.replicator().bytesUpdates));
        sb.append(String.format(Locale.ROOT, "  \"bytesSpawns\": %d,\n", server.world.replicator().bytesSpawns));
        sb.append(String.format(Locale.ROOT, "  \"bytesEffects\": %d\n", server.world.replicator().bytesEffects));
        sb.append("}\n");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(path), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        } catch (java.io.IOException e) {
            Mp.log("could not write stats file " + path + ": " + e);
        }
    }
}
