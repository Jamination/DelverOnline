package com.delveronline.client;

import com.badlogic.gdx.utils.Array;
import com.delveronline.Mp;
import com.delveronline.net.ByteReader;
import com.delveronline.net.ProtocolException;
import com.delveronline.repl.EffectCodec;
import com.delveronline.repl.FieldSchema;
import com.delveronline.repl.NetKryo;
import com.delveronline.repl.Visuals;
import com.interrupt.dungeoneer.entities.AmbientSound;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Light;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.entities.ParticleEmitter;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.PositionedSound;
import com.interrupt.dungeoneer.entities.ProjectedDecal;
import com.interrupt.dungeoneer.entities.SpriteDecal;
import com.interrupt.dungeoneer.entities.DynamicLight;
import com.interrupt.dungeoneer.game.CachePools;
import com.interrupt.dungeoneer.game.DelverOnlineAccess;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Client half of world replication: maps server network ids to the entities of the locally loaded level,
 * applies spawns/removals/field deltas, interpolates float fields between snapshots, and ticks only what is
 * purely presentational. Entities the local player creates (thrown items, projectiles) are handed to the server.
 */
public final class ClientReplicator {
    /** Interpolation window for float fields; matches the 30 Hz snapshot rate with a little slack. */
    private static final float INTERP_MS = 45f;
    private static final float SNAP_DISTANCE = 3f;

    static final class Rep {
        final int netId;
        final Entity e;
        final FieldSchema schema;
        final boolean visual;
        int[] fieldSeq;
        // float interpolation state, indexed by field
        float[] from, to;
        long[] startMs;
        boolean interpolating;
        /** Latest raw (non-interpolated) values of x, y, z for consistency reports. */
        float rx, ry, rz;

        Rep(int netId, Entity e) {
            this.netId = netId;
            this.e = e;
            this.schema = FieldSchema.of(e.getClass());
            this.visual = Visuals.isClientSimulated(e);
            this.rx = e.x;
            this.ry = e.y;
            this.rz = e.z;
        }
    }

    private final HashMap<Integer, Rep> byId = new HashMap<>();
    private final IdentityHashMap<Entity, Rep> byEntity = new IdentityHashMap<>();
    /** Entities created locally that stay local (effects, avatars, emitter output). */
    private final IdentityHashMap<Entity, Boolean> local = new IdentityHashMap<>();
    private final ArrayList<Rep> interpolating = new ArrayList<>();
    private final HashMap<Integer, FieldSchema> tombstones = new HashMap<>();
    private final NetKryo kryo;
    private final MpClient client;
    private final float[] floatOut = new float[1];
    private final ArrayList<Entity> toDelete = new ArrayList<>();
    int levelSerial;
    int lastAppliedTick;

    ClientReplicator(MpClient client, NetKryo kryo) {
        this.client = client;
        this.kryo = kryo;
    }

    public boolean isReplicated(Entity e) {
        return byEntity.containsKey(e);
    }

    public int netIdOf(Entity e) {
        Rep r = e == null ? null : byEntity.get(e);
        return r == null ? 0 : r.netId;
    }

    public Entity entity(int netId) {
        Rep r = byId.get(netId);
        return r == null ? null : r.e;
    }

    int trackedCount() {
        return byId.size();
    }

    void markLocal(Entity e) {
        local.put(e, Boolean.TRUE);
    }

    /** Detaches an entity from replication (e.g. it was picked up into the inventory). */
    void forget(Entity e) {
        Rep r = byEntity.remove(e);
        if (r != null) {
            byId.remove(r.netId);
            tombstones.put(r.netId, r.schema);
            interpolating.remove(r);
        }
    }

    // ------------------------------------------------------------------ level load

    /** Deserializes a level snapshot, binds network ids and initializes it like a loaded save. */
    Level buildLevel(int serial, int[][] ids, byte[] packed) {
        byId.clear();
        byEntity.clear();
        tombstones.clear();
        local.clear();
        interpolating.clear();
        levelSerial = serial;
        lastAppliedTick = 0;
        byte[] raw = NetKryo.inflate(packed);
        Level level = NetKryo.levelFromBytes(raw);
        if (level == null) throw new ProtocolException("level data could not be read");
        bind(level.entities, ids[0]);
        bind(level.non_collidable_entities, ids[1]);
        bind(level.static_entities, ids[2]);
        // chase encounters and other spawns are the server's business
        level.spawnEncounterDuringChase = false;
        level.init(Level.Source.LEVEL_LOAD);
        // anything the local init created (emitters, sounds, lights) is local presentation
        markUnknownLocal(level.entities);
        markUnknownLocal(level.non_collidable_entities);
        markUnknownLocal(level.static_entities);
        return level;
    }

    private void bind(Array<Entity> list, int[] ids) {
        int n = list == null ? 0 : list.size;
        if (n != ids.length) throw new ProtocolException("level entity count mismatch: " + n + " vs " + ids.length);
        for (int i = 0; i < n; i++) {
            Rep r = new Rep(ids[i], list.get(i));
            byId.put(r.netId, r);
            byEntity.put(r.e, r);
        }
    }

    private void markUnknownLocal(Array<Entity> list) {
        if (list == null) return;
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            if (!byEntity.containsKey(e)) local.put(e, Boolean.TRUE);
        }
    }

    // ------------------------------------------------------------------ messages

    void applySpawn(Level level, ByteReader r) {
        int serial = r.readVarInt();
        int netId = r.readVarInt();
        int list = r.readUByte();
        byte[] bytes = r.readBytes();
        if (serial != levelSerial || level == null || byId.containsKey(netId)) return;
        Object o = kryo.read(bytes);
        if (!(o instanceof Entity)) throw new ProtocolException("spawn is not an entity");
        Entity e = (Entity) o;
        Rep rep = new Rep(netId, e);
        byId.put(netId, rep);
        byEntity.put(e, rep);
        Array<Entity> target = list == 0 ? level.entities : list == 1 ? level.non_collidable_entities : level.static_entities;
        target.add(e);
        if (list == 2) level.addEntityToStaticSpatialHash(e);
        e.init(level, Level.Source.LEVEL_LOAD);
        if (e.drawable != null) e.drawable.refresh();
        e.updateDrawable();
    }

    void applyRemove(Level level, ByteReader r) {
        int serial = r.readVarInt();
        int n = r.readVarInt();
        for (int i = 0; i < n; i++) {
            int netId = r.readVarInt();
            if (serial != levelSerial) continue;
            Rep rep = byId.remove(netId);
            if (rep == null) continue;
            byEntity.remove(rep.e);
            tombstones.put(netId, rep.schema);
            interpolating.remove(rep);
            if (level == null) continue;
            rep.e.onDispose();
            if (!level.entities.removeValue(rep.e, true) && !level.non_collidable_entities.removeValue(rep.e, true)) {
                level.static_entities.removeValue(rep.e, true);
            }
        }
    }

    void applyUpdate(ByteReader r, int packetSeq) {
        int serial = r.readVarInt();
        int tick = r.readVarInt();
        if (serial != levelSerial) return;
        if (tick > lastAppliedTick) lastAppliedTick = tick;
        long now = System.currentTimeMillis();
        while (r.remaining() > 0) {
            int netId = r.readVarInt();
            Rep rep = byId.get(netId);
            // updates may trail a removal: parse with the old schema and discard
            FieldSchema s = rep != null ? rep.schema : tombstones.get(netId);
            if (s == null) throw new SkipEntity(); // unknown id: the rest of this message cannot be parsed
            int idx = -1;
            while (true) {
                int d = r.readVarInt();
                if (d == 0) break;
                idx += d;
                if (idx >= s.size()) throw new ProtocolException("field index out of range");
                Object v = s.read(r, idx, floatOut);
                if (rep == null) continue;
                if (rep.fieldSeq == null) {
                    rep.fieldSeq = new int[s.size()];
                    java.util.Arrays.fill(rep.fieldSeq, Integer.MIN_VALUE);
                }
                if (packetSeq < rep.fieldSeq[idx]) continue; // older than what we already applied
                rep.fieldSeq[idx] = packetSeq;
                if (v == null) {
                    applyFloat(rep, idx, floatOut[0], now);
                } else {
                    s.apply(rep.e, idx, v);
                }
            }
        }
    }

    /** Thrown when an update references an entity we no longer have; the rest of that message is dropped. */
    static final class SkipEntity extends RuntimeException {
        SkipEntity() {
            super(null, null, false, false);
        }
    }

    private void applyFloat(Rep rep, int idx, float value, long now) {
        FieldSchema s = rep.schema;
        if (idx == s.ix) rep.rx = value;
        else if (idx == s.iy) rep.ry = value;
        else if (idx == s.iz) rep.rz = value;
        if (!s.interpolate[idx] || rep.visual) {
            s.applyFloat(rep.e, idx, value);
            return;
        }
        float current = s.getFloat(rep.e, idx);
        boolean positional = idx == s.ix || idx == s.iy || idx == s.iz;
        if (Float.isNaN(current) || Float.isNaN(value) || (positional && Math.abs(value - current) > SNAP_DISTANCE)) {
            s.applyFloat(rep.e, idx, value);
            if (rep.to != null) {
                rep.to[idx] = value;
                rep.startMs[idx] = 0;
            }
            return;
        }
        if (rep.from == null) {
            rep.from = new float[s.size()];
            rep.to = new float[s.size()];
            rep.startMs = new long[s.size()];
        }
        rep.from[idx] = current;
        rep.to[idx] = value;
        rep.startMs[idx] = now;
        if (!rep.interpolating) {
            rep.interpolating = true;
            interpolating.add(rep);
        }
    }

    void applyEffects(Level level, ByteReader r) {
        int serial = r.readVarInt();
        if (serial != levelSerial || level == null) return;
        while (r.remaining() > 0) {
            Entity e = EffectCodec.read(r);
            local.put(e, Boolean.TRUE);
            level.SpawnNonCollidingEntity(e);
        }
    }

    // ------------------------------------------------------------------ per-frame

    private void interpolate(long now) {
        for (int i = interpolating.size() - 1; i >= 0; i--) {
            Rep rep = interpolating.get(i);
            boolean active = false;
            FieldSchema s = rep.schema;
            for (int f = 0; f < s.size(); f++) {
                long start = rep.startMs[f];
                if (start == 0) continue;
                float t = (now - start) / INTERP_MS;
                if (t >= 1f) {
                    s.applyFloat(rep.e, f, rep.to[f]);
                    rep.startMs[f] = 0;
                } else {
                    s.applyFloat(rep.e, f, rep.from[f] + (rep.to[f] - rep.from[f]) * t);
                    active = true;
                }
            }
            if (!active) {
                rep.interpolating = false;
                interpolating.remove(i);
            }
        }
    }

    /** Replacement for Level.tick on multiplayer clients. */
    void tick(Level level, float delta, Player player) {
        interpolate(System.currentTimeMillis());
        if (level.mapIsDirty || level.lastPlayerTileX != (int) player.x || level.lastPlayerTileY != (int) player.y) {
            DelverOnlineAccess.updateSeenTiles(level, player);
        }
        level.updateSpatialHash(player);
        tickList(level, level.entities, delta);
        tickList(level, level.non_collidable_entities, delta);
        level.lastPlayerTileX = (int) player.x;
        level.lastPlayerTileY = (int) player.y;
    }

    private void tickList(Level level, Array<Entity> list, float delta) {
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            Rep rep = byEntity.get(e);
            if (rep == null || rep.visual) {
                if (e.skipTick) {
                    e.skipTick = false;
                    continue;
                }
                try {
                    e.tick(level, delta);
                } catch (RuntimeException ex) {
                    // a purely local/presentational entity misbehaved: drop it rather than crash the game
                    Mp.log("local " + e.getClass().getSimpleName() + " failed: " + ex);
                    if (rep == null) e.isActive = false;
                }
                if (!e.isActive) toDelete.add(e);
            } else {
                // server-simulated entity: keep attachments (lights, emitters) following it
                try {
                    e.tickAttached(level, delta);
                } catch (RuntimeException ex) {
                    Mp.log("attachment of " + e.getClass().getSimpleName() + " failed: " + ex);
                }
            }
        }
        if (!toDelete.isEmpty()) {
            for (Entity e : toDelete) {
                if (byEntity.containsKey(e)) continue; // replicated: the server decides when it goes away
                e.onDispose();
                list.removeValue(e, true);
                local.remove(e);
                if (e instanceof Particle) CachePools.freeParticle((Particle) e);
            }
            toDelete.clear();
        }
    }

    /**
     * Finds entities the local player added to the level since the last frame. Gameplay entities are sent to the
     * server (which simulates and replicates them back); purely visual ones stay local.
     */
    void forwardLocalSpawns(Level level) {
        forwardFrom(level, level.entities, 0);
        forwardFrom(level, level.non_collidable_entities, 1);
    }

    private void forwardFrom(Level level, Array<Entity> list, int which) {
        List<Entity> forwarded = null;
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            if (byEntity.containsKey(e) || local.containsKey(e)) continue;
            if (!isGameplaySpawn(e)) {
                local.put(e, Boolean.TRUE);
                continue;
            }
            if (forwarded == null) forwarded = new ArrayList<>();
            forwarded.add(e);
        }
        if (forwarded == null) return;
        for (Entity e : forwarded) {
            list.removeValue(e, true);
            Entity owner = e.owner;
            e.owner = null;
            try {
                client.sendSpawn(e, which);
            } catch (RuntimeException ex) {
                Mp.log("could not forward " + e.getClass().getSimpleName() + ": " + ex);
            } finally {
                e.owner = owner;
            }
        }
    }

    private static boolean isGameplaySpawn(Entity e) {
        if (!e.persists) return false;
        if (e instanceof RemoteAvatar || e instanceof RemoteAvatar.Part) return false;
        return !(e instanceof Particle || e instanceof ParticleEmitter || e instanceof Light || e instanceof DynamicLight
                || e instanceof PositionedSound || e instanceof AmbientSound || e instanceof ProjectedDecal
                || e instanceof SpriteDecal);
    }

    // ------------------------------------------------------------------ telemetry

    /** Writes up to {@code max} (netId, raw x, y, z, hp, active) samples of dynamic entities. */
    void writeReport(com.delveronline.net.ByteWriter w, int max) {
        List<Rep> dyn = new ArrayList<>();
        for (Rep r : byId.values()) {
            if (r.e.isDynamic && !r.visual) dyn.add(r);
        }
        int n = Math.min(max, dyn.size());
        w.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Rep r = dyn.get(i);
            w.writeVarInt(r.netId);
            w.writeFloat(r.rx).writeFloat(r.ry).writeFloat(r.rz);
            w.writeSVarInt(r.e instanceof com.interrupt.dungeoneer.entities.Actor ? ((com.interrupt.dungeoneer.entities.Actor) r.e).hp : Integer.MIN_VALUE);
            w.writeBool(r.e.isActive);
        }
    }

    static Level currentLevel() {
        return Game.instance == null ? null : Game.instance.level;
    }
}
