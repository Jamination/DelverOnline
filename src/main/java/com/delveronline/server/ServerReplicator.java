package com.delveronline.server;

import com.badlogic.gdx.utils.Array;
import com.delveronline.net.ByteWriter;
import com.delveronline.net.DeliveryCallback;
import com.delveronline.proto.Msg;
import com.delveronline.repl.EffectCodec;
import com.delveronline.repl.FieldSchema;
import com.delveronline.repl.NetKryo;
import com.delveronline.repl.Visuals;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.game.CachePools;
import com.interrupt.dungeoneer.game.Level;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tracks every persistent entity of the authoritative level, assigns network ids, and streams spawns, removals
 * and per-field deltas to each client. Field deltas travel on the unreliable channel; when a packet is lost its
 * fields are marked dirty again so the latest value is resent (never stale data).
 */
public final class ServerReplicator {
    static final int UPDATE_MSG_BYTES = 1000;
    static final int MAX_UPDATE_MSGS = 40;
    static final int EFFECT_MSG_BYTES = 1000;

    static final class Tracked {
        final int netId;
        final Entity e;
        final FieldSchema schema;
        final FieldSchema.Values cur;
        final int list;
        final boolean visual;
        final boolean isStatic;
        int stamp;

        Tracked(int netId, Entity e, int list) {
            this.netId = netId;
            this.e = e;
            this.schema = FieldSchema.of(e.getClass());
            this.cur = schema.newValues();
            this.list = list;
            this.visual = Visuals.isClientSimulated(e);
            this.isStatic = list == 2;
        }
    }

    /** Per-client replication state. */
    public static final class View {
        final ServerPlayer player;
        final HashMap<Integer, long[]> dirty = new HashMap<>();
        final HashSet<Integer> pendingSpawn = new HashSet<>();
        boolean ready;
        /** The current level snapshot has been sent, so spawns/removals must follow it. */
        boolean levelSent;

        View(ServerPlayer player) {
            this.player = player;
        }

        void markDirty(Tracked t, long[] mask) {
            long[] d = dirty.get(t.netId);
            if (d == null) {
                dirty.put(t.netId, mask.clone());
            } else {
                for (int i = 0; i < d.length; i++) d[i] |= mask[i];
            }
        }

        void clear() {
            dirty.clear();
            pendingSpawn.clear();
            ready = false;
            levelSent = false;
        }
    }

    private final ServerWorld world;
    private final NetKryo kryo;
    private final IdentityHashMap<Entity, Tracked> byEntity = new IdentityHashMap<>();
    private final HashMap<Integer, Tracked> byId = new HashMap<>();
    private final HashMap<Class<?>, long[]> transientMasks = new HashMap<>();
    private final HashMap<Class<?>, long[]> fullMasks = new HashMap<>();
    private int nextNetId = 1;
    private int stamp;
    private int scans;

    private final ArrayList<Entity> effectEntities = new ArrayList<>();
    private final ByteWriter effects = new ByteWriter(2048);
    private final ArrayList<byte[]> effectMessages = new ArrayList<>();
    public long bytesUpdates, bytesSpawns, bytesEffects;

    ServerReplicator(ServerWorld world, NetKryo kryo) {
        this.world = world;
        this.kryo = kryo;
    }

    public void reset() {
        byEntity.clear();
        byId.clear();
        effectEntities.clear();
        effects.reset();
        effectMessages.clear();
    }

    public int netIdOf(Entity e) {
        if (e == null) return 0;
        Tracked t = byEntity.get(e);
        return t == null ? 0 : t.netId;
    }

    public Entity entity(int netId) {
        Tracked t = byId.get(netId);
        return t == null ? null : t.e;
    }

    public int trackedCount() {
        return byId.size();
    }

    /** True for entities that are part of the replicated world. */
    static boolean replicable(Entity e) {
        return e != null && e.persists && !(e instanceof PlayerProxy) && !(e instanceof Particle);
    }

    // ------------------------------------------------------------------ scanning

    /**
     * Registers all current entities without announcing them (used right before a level snapshot, which carries
     * them to clients).
     */
    void registerAllSilently(Level level) {
        stamp++;
        registerList(level.entities, 0);
        registerList(level.non_collidable_entities, 1);
        registerList(level.static_entities, 2);
    }

    private void registerList(Array<Entity> list, int which) {
        if (list == null) return;
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            if (!replicable(e)) continue;
            Tracked t = byEntity.get(e);
            if (t == null) {
                t = new Tracked(nextNetId++, e, which);
                byEntity.put(e, t);
                byId.put(t.netId, t);
            }
            t.stamp = stamp;
            captureAll(t);
        }
    }

    private void captureAll(Tracked t) {
        for (int i = 0; i < t.schema.size(); i++) t.schema.capture(t.e, i, t.cur);
    }

    /**
     * Finds spawned/removed entities and changed fields, informing every view. Must run on the server thread
     * after the world tick.
     */
    void scan(Level level, Collection<ServerPlayer> players) {
        stamp++;
        scans++;
        boolean scanStatics = scans % 10 == 0;
        scanList(level.entities, 0, players, true);
        scanList(level.non_collidable_entities, 1, players, true);
        scanList(level.static_entities, 2, players, scanStatics);

        // removals
        ArrayList<Integer> removed = null;
        for (Iterator<Map.Entry<Integer, Tracked>> it = byId.entrySet().iterator(); it.hasNext(); ) {
            Tracked t = it.next().getValue();
            if (t.stamp != stamp) {
                it.remove();
                byEntity.remove(t.e);
                if (removed == null) removed = new ArrayList<>();
                removed.add(t.netId);
            }
        }
        if (removed != null) {
            ByteWriter w = new ByteWriter(8 + removed.size() * 3).writeByte(Msg.S_REMOVE);
            w.writeVarInt(world.levelSerial());
            w.writeVarInt(removed.size());
            for (int id : removed) w.writeVarInt(id);
            byte[] msg = w.toByteArray();
            for (ServerPlayer p : players) {
                if (!p.view.levelSent) continue;
                for (int id : removed) {
                    p.view.dirty.remove(id);
                    p.view.pendingSpawn.remove(id);
                }
                p.conn.sendReliable(msg);
            }
        }

        // particle effects created by server-side logic: ship once, never simulate here
        if (!effectEntities.isEmpty()) {
            for (Entity e : effectEntities) {
                appendEffect(e);
                level.non_collidable_entities.removeValue(e, true);
                level.entities.removeValue(e, true);
                if (e instanceof Particle) CachePools.freeParticle((Particle) e);
            }
            effectEntities.clear();
        }
    }

    private void scanList(Array<Entity> list, int which, Collection<ServerPlayer> players, boolean captureFields) {
        if (list == null) return;
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            if (e == null) continue;
            if (e instanceof Particle) {
                effectEntities.add(e);
                continue;
            }
            if (!replicable(e)) continue;
            Tracked t = byEntity.get(e);
            if (t == null) {
                t = new Tracked(nextNetId++, e, which);
                byEntity.put(e, t);
                byId.put(t.netId, t);
                t.stamp = stamp;
                captureAll(t);
                announceSpawn(t, players);
                continue;
            }
            t.stamp = stamp;
            if (!captureFields) continue;
            long[] changed = null;
            FieldSchema s = t.schema;
            for (int f = 0; f < s.size(); f++) {
                if (s.capture(e, f, t.cur)) {
                    if (t.visual && !visualFieldReplicated(s, f)) continue;
                    if (changed == null) changed = new long[(s.size() + 63) >> 6];
                    changed[f >> 6] |= 1L << (f & 63);
                }
            }
            if (changed != null) {
                for (ServerPlayer p : players) {
                    if (p.view.ready) p.view.markDirty(t, changed);
                }
            }
        }
    }

    /** Client-simulated (visual) entities only receive discrete toggles, never their animation state. */
    private static boolean visualFieldReplicated(FieldSchema s, int f) {
        FieldSchema.Kind k = s.kinds[f];
        return k == FieldSchema.Kind.BOOL || k == FieldSchema.Kind.STRING || k == FieldSchema.Kind.ENUM;
    }

    private void announceSpawn(Tracked t, Collection<ServerPlayer> players) {
        byte[] bytes;
        try {
            bytes = kryo.write(t.e);
        } catch (RuntimeException ex) {
            com.delveronline.Mp.log("could not serialize spawned " + t.e.getClass().getName() + ": " + ex);
            return;
        }
        ByteWriter w = new ByteWriter(bytes.length + 16).writeByte(Msg.S_SPAWN);
        w.writeVarInt(world.levelSerial());
        w.writeVarInt(t.netId);
        w.writeByte(t.list);
        w.writeBytes(bytes);
        byte[] msg = w.toByteArray();
        for (ServerPlayer p : players) {
            if (!p.view.levelSent) continue; // will get this entity inside its level snapshot
            sendSpawn(p, t, msg);
        }
    }

    private void sendSpawn(final ServerPlayer p, final Tracked t, byte[] msg) {
        final View v = p.view;
        v.pendingSpawn.add(t.netId);
        bytesSpawns += msg.length;
        p.conn.sendReliable(msg, new DeliveryCallback() {
            @Override
            public void delivered() {
                if (!v.pendingSpawn.remove(t.netId)) return;
                if (byId.get(t.netId) == t && v.ready) v.markDirty(t, transientMask(t.schema));
            }

            @Override
            public void lost() {
                v.pendingSpawn.remove(t.netId);
            }
        });
    }

    /** Bits of transient fields: they are not part of Kryo data so must be sent as deltas after a spawn/snapshot. */
    private long[] transientMask(FieldSchema s) {
        long[] m = transientMasks.get(s.type);
        if (m == null) {
            m = new long[(s.size() + 63) >> 6];
            for (int i = 0; i < s.size(); i++) {
                if (Modifier.isTransient(s.fields[i].getModifiers())) m[i >> 6] |= 1L << (i & 63);
            }
            transientMasks.put(s.type, m);
        }
        return m;
    }

    private long[] fullMask(Tracked t) {
        long[] m = fullMasks.get(t.schema.type);
        if (m == null) {
            m = new long[(t.schema.size() + 63) >> 6];
            for (int i = 0; i < t.schema.size(); i++) {
                if (t.visual && !visualFieldReplicated(t.schema, i)) continue;
                m[i >> 6] |= 1L << (i & 63);
            }
            fullMasks.put(t.schema.type, m);
        }
        return m;
    }

    /** The client finished loading the level: send every field once so it matches the server exactly. */
    void onClientLevelReady(ServerPlayer p) {
        View v = p.view;
        v.ready = true;
        for (Tracked t : byId.values()) {
            if (!v.pendingSpawn.contains(t.netId)) v.markDirty(t, fullMask(t));
        }
    }

    /** Builds the netId table for a snapshot of {@code list} restricted to replicable entities. */
    Array<Entity> snapshotList(Array<Entity> list, ByteWriter ids) {
        Array<Entity> out = new Array<>(list == null ? 0 : list.size);
        if (list != null) {
            for (int i = 0; i < list.size; i++) {
                Entity e = list.get(i);
                Tracked t = byEntity.get(e);
                if (t == null) continue;
                out.add(e);
            }
        }
        ids.writeVarInt(out.size);
        for (int i = 0; i < out.size; i++) ids.writeVarInt(byEntity.get(out.get(i)).netId);
        return out;
    }

    // ------------------------------------------------------------------ sending

    void sendUpdates(ServerPlayer p, int serverTick) {
        final View v = p.view;
        if (!v.ready || v.dirty.isEmpty()) return;
        // nearest entities first, so what the player sees is never starved by a backlog
        final float px = p.proxy.x, py = p.proxy.y;
        List<Tracked> order = new ArrayList<>(v.dirty.size());
        for (Iterator<Map.Entry<Integer, long[]>> it = v.dirty.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, long[]> en = it.next();
            Tracked t = byId.get(en.getKey());
            if (t == null) {
                it.remove();
                continue;
            }
            if (v.pendingSpawn.contains(t.netId)) continue;
            order.add(t);
        }
        order.sort((a, b) -> Float.compare(dist2(a.e, px, py), dist2(b.e, px, py)));

        ByteWriter w = null;
        ArrayList<Object[]> contents = null;
        ByteWriter fieldBuf = new ByteWriter(256);
        int msgs = 0;
        outer:
        for (Tracked t : order) {
            long[] mask = v.dirty.remove(t.netId);
            if (mask == null) continue;
            int size = t.schema.size();
            int f = 0;
            while (f < size) {
                if (w == null) {
                    w = new ByteWriter(UPDATE_MSG_BYTES + 64);
                    startUpdate(w, serverTick);
                    contents = new ArrayList<>();
                }
                long[] sub = new long[mask.length];
                w.writeVarInt(t.netId);
                int prev = -1;
                boolean any = false;
                for (; f < size; f++) {
                    if ((mask[f >> 6] & (1L << (f & 63))) == 0) continue;
                    fieldBuf.reset();
                    t.schema.write(fieldBuf, f, t.cur);
                    int need = 5 + fieldBuf.size() + 1;
                    if (need > UPDATE_MSG_BYTES - 32) continue; // absurdly large value: never replicated
                    if (w.size() + need > UPDATE_MSG_BYTES && any) break;
                    w.writeVarInt(f - prev);
                    prev = f;
                    w.writeRaw(fieldBuf.array(), 0, fieldBuf.size());
                    sub[f >> 6] |= 1L << (f & 63);
                    any = true;
                }
                w.writeVarInt(0);
                contents.add(new Object[]{t, sub});
                if (f < size || w.size() > UPDATE_MSG_BYTES * 9 / 10) {
                    flushUpdate(p, w, contents);
                    w = null;
                    contents = null;
                    if (++msgs >= MAX_UPDATE_MSGS) {
                        if (f < size) {
                            long[] rest = new long[mask.length];
                            for (int g = f; g < size; g++) {
                                if ((mask[g >> 6] & (1L << (g & 63))) != 0) rest[g >> 6] |= 1L << (g & 63);
                            }
                            v.markDirty(t, rest);
                        }
                        break outer;
                    }
                }
            }
        }
        if (w != null && contents != null && !contents.isEmpty()) {
            flushUpdate(p, w, contents);
        }
    }

    private void startUpdate(ByteWriter w, int serverTick) {
        w.writeByte(Msg.S_UPDATE);
        w.writeVarInt(world.levelSerial());
        w.writeVarInt(serverTick);
    }

    private static float dist2(Entity e, float x, float y) {
        float dx = e.x - x, dy = e.y - y;
        return dx * dx + dy * dy;
    }

    private void flushUpdate(ServerPlayer p, ByteWriter w, ArrayList<Object[]> contents) {
        final View v = p.view;
        final Object[][] entries = contents.toArray(new Object[0][]);
        final int serial = world.levelSerial();
        byte[] msg = w.toByteArray();
        bytesUpdates += msg.length;
        p.conn.sendUnreliable(msg, new DeliveryCallback() {
            @Override
            public void delivered() {
            }

            @Override
            public void lost() {
                if (serial != world.levelSerial() || !v.ready) return;
                for (Object[] en : entries) {
                    Tracked t = (Tracked) en[0];
                    if (byId.get(t.netId) == t) v.markDirty(t, (long[]) en[1]);
                }
            }
        });
    }

    // ------------------------------------------------------------------ effects

    private void appendEffect(Entity e) {
        int before = effects.size();
        if (before == 0) {
            effects.writeByte(Msg.S_EFFECTS);
            effects.writeVarInt(world.levelSerial());
        }
        int mark = effects.size();
        EffectCodec.write(effects, e);
        if (effects.size() > EFFECT_MSG_BYTES) {
            byte[] all = effects.toByteArray();
            if (mark > 3) {
                // flush everything before this effect, keep this one for the next message
                byte[] head = new byte[mark];
                System.arraycopy(all, 0, head, 0, mark);
                effectMessages.add(head);
                effects.reset();
                effects.writeByte(Msg.S_EFFECTS);
                effects.writeVarInt(world.levelSerial());
                effects.writeRaw(all, mark, all.length - mark);
            } else {
                effectMessages.add(all);
                effects.reset();
            }
        }
    }

    /** Sends accumulated one-shot effects to every client that has the level loaded. */
    void flushEffects(Collection<ServerPlayer> players) {
        if (effects.size() > 0) {
            effectMessages.add(effects.toByteArray());
            effects.reset();
        }
        if (effectMessages.isEmpty()) return;
        for (byte[] m : effectMessages) {
            bytesEffects += m.length * (long) players.size();
            for (ServerPlayer p : players) {
                if (p.view.ready) p.conn.sendUnreliable(m, null);
            }
        }
        effectMessages.clear();
    }

    /** Debug/telemetry: current authoritative values of a tracked entity. */
    FieldSchema.Values valuesOf(int netId) {
        Tracked t = byId.get(netId);
        return t == null ? null : t.cur;
    }

    Collection<Integer> trackedIds() {
        return byId.keySet();
    }
}
