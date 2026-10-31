package com.delveronline.server;

import com.badlogic.gdx.utils.Array;
import com.delveronline.Mp;
import com.delveronline.mods.ModDiscovery;
import com.delveronline.mods.ModManifest;
import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;
import com.delveronline.net.Connection;
import com.delveronline.net.NetEndpoint;
import com.delveronline.net.NetListener;
import com.delveronline.net.ProtocolException;
import com.delveronline.proto.Msg;
import com.delveronline.proto.PlayerState;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.game.Level;

import java.io.IOException;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/** Dedicated server: networking, sessions and the fixed 60 Hz loop around {@link ServerWorld}. */
public final class DelverServer implements NetListener {
    public static final int TICK_RATE = 60;
    /** Field deltas and player states go out every SNAPSHOT_EVERY ticks (30 Hz). */
    static final int SNAPSHOT_EVERY = 2;
    private static final long RUN_RESET_DELAY_MS = 8000;

    static volatile DelverServer INSTANCE;

    final ServerWorld world;
    final ServerConfig config;
    private NetEndpoint net;
    private final LinkedHashMap<Connection, ServerPlayer> players = new LinkedHashMap<>();
    private final Collection<ServerPlayer> playersView = Collections.unmodifiableCollection(players.values());
    private int nextPlayerId = 1;

    /** A player who left during this run: their character comes back when they rejoin with the same name. */
    private static final class Departed {
        final byte[] character;
        final int look;
        boolean dead;

        Departed(byte[] character, int look, boolean dead) {
            this.character = character;
            this.look = look;
            this.dead = dead;
        }
    }

    private final HashMap<String, Departed> departed = new HashMap<>();

    /**
     * A new run starts everyone from scratch: forget players who left, and the previous run's characters of those
     * still here (so leaving before their next report does not carry the old run's items over).
     */
    void forgetDeparted() {
        departed.clear();
        for (ServerPlayer p : players.values()) p.character = null;
    }

    /** The party reached a new depth: players who left while dead come back alive, like everyone else. */
    void reviveDeparted() {
        for (Departed d : departed.values()) d.dead = false;
    }
    final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private final Commands commandHandler;
    volatile boolean running = true;
    final ServerStats stats = new ServerStats();
    /** Items offered to a client for pickup, awaiting its answer. */
    final IdentityHashMap<Item, ServerPlayer> pendingPickups = new IdentityHashMap<>();
    private final Map<ServerPlayer, int[]> spawnBudget = new HashMap<>();

    public DelverServer(ServerConfig config) {
        this.config = config;
        this.world = new ServerWorld(this);
        this.commandHandler = new Commands(this);
        INSTANCE = this;
    }

    /** The mods this server runs; players must have exactly these. */
    ModManifest mods;

    public Collection<ServerPlayer> players() {
        return playersView;
    }

    /** Called once on the server thread after libGDX is up. */
    public void start() throws SocketException {
        Mp.setMode(Mp.Mode.SERVER);
        ModDiscovery.select(config.mods);
        world.boot();
        List<String> unknown = ModDiscovery.unmatchedSelections();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("mod not found: " + String.join(", ", unknown)
                    + " (give installed Workshop IDs, mod titles, folder names or folder paths)");
        }
        mods = ModManifest.current();
        Mp.log("running " + mods.describe());
        net = NetEndpoint.server(config.port, config.maxPlayers, this);
        Mp.log("Delver Online server '" + config.name + "' listening on UDP port " + net.getLocalPort()
                + " (max " + config.maxPlayers + " players). Type 'help' for commands.");
        stats.start();
    }

    public int port() {
        return net.getLocalPort();
    }

    void step() {
        long t0 = System.nanoTime();
        net.update();
        String cmd;
        while ((cmd = commands.poll()) != null) commandHandler.execute(cmd);
        if (world.gameOver && System.currentTimeMillis() - world.gameOverAtMs > RUN_RESET_DELAY_MS) {
            for (ServerPlayer p : new ArrayList<>(players.values())) p.conn.close("The run has ended.");
            world.newRun();
        }
        world.tick();
        if (world.tick % SNAPSHOT_EVERY == 0) {
            // replication runs at the snapshot rate: discover spawns/removals/changes, then stream them
            world.replicator().scan(world.level(), playersView);
            world.replicator().flushEffects(playersView);
            for (ServerPlayer p : playersView) world.replicator().sendUpdates(p, world.tick);
            sendPlayerStates();
        }
        spawnBudget.clear();
        net.update();
        stats.tick(System.nanoTime() - t0, this);
    }

    void stop() {
        running = false;
    }

    /** Flushes pending messages, closes every connection and writes the stats summary. */
    void shutdownNow() {
        Mp.log("shutting down");
        stats.writeSummary(this);
        if (net != null) {
            for (ServerPlayer p : new ArrayList<>(players.values())) p.conn.close("Server shut down.");
            net.close();
        }
    }

    void writeStatsOnly() {
        stats.writeSummary(this);
    }

    // ------------------------------------------------------------------ connection events

    @Override
    public void onConnected(Connection connection) {
        // wait for HELLO
    }

    @Override
    public void onDisconnected(Connection connection, String reason) {
        ServerPlayer p = players.remove(connection);
        if (p == null) return;
        world.onPlayerLeft(p);
        if (p.character != null) {
            departed.put(p.name.toLowerCase(java.util.Locale.ROOT), new Departed(p.character, p.look, p.dead));
        }
        pendingPickups.values().removeIf(x -> x == p);
        Mp.log(p.name + " left (" + reason + ")");
        broadcastChat(null, p.name + " left the game.");
        broadcastPlayers();
        checkAllDead();
    }

    @Override
    public void onMessage(Connection conn, byte[] data, boolean reliable, int packetSeq) {
        ByteReader r = new ByteReader(data);
        int type = r.readUByte();
        ServerPlayer p = players.get(conn);
        try {
            if (p == null) {
                if (type == Msg.C_HELLO) handleHello(conn, r);
                return;
            }
            handle(p, type, r);
        } catch (ProtocolException e) {
            conn.close("protocol error: " + e.getMessage());
        } catch (RuntimeException e) {
            // a game-logic failure while executing a player's request must not take the server down
            Mp.log("error handling message " + type + " from " + p + ": " + e);
            e.printStackTrace();
        }
    }

    private void handleHello(Connection conn, ByteReader r) {
        int version = r.readVarInt();
        String name = sanitizeName(r.readString());
        if (version != Mp.PROTOCOL_VERSION) {
            kick(conn, "Version mismatch: server protocol " + Mp.PROTOCOL_VERSION + ", client " + version + ".");
            return;
        }
        String modProblem = mods.mismatch(ModManifest.read(r));
        if (modProblem != null) {
            Mp.log("refused " + name + " from " + conn.getAddress() + ": mods differ");
            kick(conn, modProblem);
            return;
        }
        if (players.size() >= config.maxPlayers) {
            kick(conn, "Server is full.");
            return;
        }
        if (world.gameOver) {
            kick(conn, "The run just ended. Try again in a few seconds.");
            return;
        }
        name = uniqueName(name);
        ServerPlayer p = new ServerPlayer(this, nextPlayerId++, name, conn);
        Departed back = departed.remove(name.toLowerCase(java.util.Locale.ROOT));
        p.look = back != null ? back.look : pickLook();
        players.put(conn, p);
        conn.setUserData(p);
        ByteWriter w = new ByteWriter(64).writeByte(Msg.S_WELCOME).writeVarInt(p.id).writeString(config.name).writeVarInt(TICK_RATE);
        w.writeString(name);
        p.send(w);
        Mp.log(name + " joined from " + conn.getAddress());
        broadcastPlayers();
        if (back != null) {
            // the character goes first: the client builds its player from it when the level arrives
            p.send(new ByteWriter(back.character.length + 16).writeByte(Msg.S_CHARACTER).writeBool(back.dead).writeBytes(back.character));
            p.character = back.character;
            p.dead = back.dead;
            Mp.log(name + " returned: character restored" + (back.dead ? " (still dead)" : ""));
            if (back.dead) broadcastPlayers();
        }
        world.sendLevelTo(p);
        broadcastChat(null, name + " joined the game.");
    }

    private void kick(Connection conn, String reason) {
        conn.sendReliable(new ByteWriter(64).writeByte(Msg.S_KICK).writeString(reason).toByteArray());
        conn.close(reason);
    }

    void kick(ServerPlayer p, String reason) {
        kick(p.conn, reason);
    }

    private static String sanitizeName(String n) {
        if (n == null) n = "";
        StringBuilder sb = new StringBuilder();
        for (char c : n.trim().toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '_' || c == '-') sb.append(c);
            if (sb.length() >= 16) break;
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? "Adventurer" : s;
    }

    private String uniqueName(String name) {
        String candidate = name;
        int n = 2;
        while (findPlayer(candidate) != null) candidate = name + n++;
        return candidate;
    }

    ServerPlayer findPlayer(String nameOrId) {
        for (ServerPlayer p : players.values()) {
            if (p.name.equalsIgnoreCase(nameOrId) || String.valueOf(p.id).equals(nameOrId)) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------ gameplay messages

    private void handle(ServerPlayer p, int type, ByteReader r) {
        switch (type) {
            case Msg.C_STATE: handleState(p, r); break;
            case Msg.C_INVENTORY: handleInventory(p, r); break;
            case Msg.C_CHARACTER: {
                byte[] c = r.readBytes();
                if (c.length <= 512 * 1024) p.character = c;
                break;
            }
            case Msg.C_HIT: handleHit(p, r); break;
            case Msg.C_USE: handleUse(p, r); break;
            case Msg.C_SPAWN: handleSpawn(p, r); break;
            case Msg.C_PICKUP_RESULT: handlePickupResult(p, r); break;
            case Msg.C_CHAT: handleChat(p, r); break;
            case Msg.C_DIED: handleDied(p); break;
            case Msg.C_LEVEL_LOADED: handleLevelLoaded(p, r); break;
            case Msg.C_STAIRS: handleStairs(p, r); break;
            case Msg.C_TILE_USE: {
                int tx = r.readSVarInt(), ty = r.readSVarInt();
                readRequesterPosition(p, r);
                world.useTile(p, tx, ty);
                break;
            }
            case Msg.C_TRIGGER: handleTrigger(p, r); break;
            case Msg.C_GAME_END: {
                boolean won = r.readBool();
                if (p.inWorld()) world.asPlayer(p, null, () -> world.gameOverScreen(won));
                break;
            }
            case Msg.C_REPORT: stats.onReport(p, r, world); break;
            default: throw new ProtocolException("unknown message " + type);
        }
    }

    private void handleState(ServerPlayer p, ByteReader r) {
        PlayerState s = new PlayerState().read(r);
        if (s.seq <= p.lastStateSeq) return;
        p.lastStateSeq = s.seq;
        s.id = p.id;
        p.state.copyFrom(s);
        if (s.levelSerial != world.levelSerial() || p.loadedLevelSerial != world.levelSerial()) return;
        PlayerProxy proxy = p.proxy;
        proxy.x = s.x;
        proxy.y = s.y;
        proxy.z = s.z;
        proxy.rot = s.rot;
        proxy.yrot = s.yrot;
        proxy.xa = s.xa;
        proxy.ya = s.ya;
        proxy.za = s.za;
        proxy.hp = s.hp;
        proxy.maxHp = Math.max(1, s.maxHp);
        proxy.level = Math.max(1, s.level);
        proxy.keys = s.keys;
        proxy.gold = s.gold;
        proxy.isHoldingOrb = s.has(PlayerState.F_HOLDING_ORB);
        proxy.invisible = s.has(PlayerState.F_INVISIBLE);
        proxy.visiblityMod = s.visibility;
    }

    @SuppressWarnings("unchecked")
    private void handleInventory(ServerPlayer p, ByteReader r) {
        byte[] bytes = r.readBytes();
        Object o = world.kryo().read(bytes);
        if (!(o instanceof Object[])) {
            Mp.log("ignored inventory from " + p + ": " + (o == null ? "null" : o.getClass().getName()));
            return;
        }
        Object[] parts = (Object[]) o;
        if (parts.length >= 1 && parts[0] instanceof Array) p.proxy.inventory = (Array<Item>) parts[0];
        if (parts.length >= 2 && parts[1] instanceof java.util.HashMap) p.proxy.equippedItems = (java.util.HashMap<String, Item>) parts[1];
    }

    private Entity reachable(ServerPlayer p, int netId, float range) {
        if (!p.inWorld()) return null;
        Entity e = world.replicator().entity(netId);
        if (e == null || !e.isActive) return null;
        if (Math.abs(e.x - p.proxy.x) > range || Math.abs(e.y - p.proxy.y) > range) return null;
        return e;
    }

    private void handleHit(ServerPlayer p, ByteReader r) {
        int netId = r.readVarInt();
        float projx = r.readFloat(), projy = r.readFloat();
        int damage = r.readSVarInt();
        float knockback = r.readFloat();
        int dt = r.readVarInt();
        Weapon.DamageType type = dt == 0 ? Weapon.DamageType.PHYSICAL : Weapon.DamageType.values()[Math.min(dt - 1, Weapon.DamageType.values().length - 1)];
        readRequesterPosition(p, r);
        Entity e = reachable(p, netId, 8f);
        if (e == null) return;
        world.asPlayer(p, e, () -> e.hit(projx, projy, damage, knockback, type, p.proxy));
    }

    private void handleUse(ServerPlayer p, ByteReader r) {
        int netId = r.readVarInt();
        float projx = r.readFloat(), projy = r.readFloat();
        readRequesterPosition(p, r);
        Entity e = reachable(p, netId, 3f);
        if (e == null) return;
        world.asPlayer(p, e, () -> e.use(p.proxy, projx, projy));
    }

    /** Movement is client-authoritative; a request's own position supersedes a possibly late state update. */
    private void readRequesterPosition(ServerPlayer p, ByteReader r) {
        int serial = r.readVarInt();
        float x = r.readFloat(), y = r.readFloat(), z = r.readFloat();
        if (serial != world.levelSerial() || p.loadedLevelSerial != serial) return;
        p.proxy.x = x;
        p.proxy.y = y;
        p.proxy.z = z;
    }

    private void handleSpawn(ServerPlayer p, ByteReader r) {
        int list = r.readUByte();
        byte[] bytes = r.readBytes();
        if (!p.inWorld()) return;
        int[] budget = spawnBudget.computeIfAbsent(p, k -> new int[1]);
        if (++budget[0] > 20) return; // flood guard (per tick)
        Object o = world.kryo().read(bytes);
        if (!(o instanceof Entity) || o instanceof Monster || o instanceof PlayerProxy) return;
        world.spawnFromPlayer(p, (Entity) o, list);
    }

    private void handlePickupResult(ServerPlayer p, ByteReader r) {
        int netId = r.readVarInt();
        boolean accepted = r.readBool();
        Entity e = world.replicator().entity(netId);
        if (!(e instanceof Item)) return;
        Item item = (Item) e;
        if (pendingPickups.get(item) != p) return;
        pendingPickups.remove(item);
        if (accepted) {
            item.isActive = false;
        }
    }

    private void handleChat(ServerPlayer p, ByteReader r) {
        String text = r.readString();
        if (text == null) return;
        text = text.replace('\n', ' ').trim();
        if (text.isEmpty()) return;
        if (text.length() > 160) text = text.substring(0, 160);
        Mp.log("<" + p.name + "> " + text);
        broadcastChat(p.name, text);
    }

    private void handleDied(ServerPlayer p) {
        if (p.dead) return;
        p.dead = true;
        world.onPlayerLeft(p);
        Mp.log(p.name + " died");
        broadcastChat(null, p.name + " has died.");
        broadcastPlayers();
        checkAllDead();
    }

    void checkAllDead() {
        if (world.gameOver || players.isEmpty()) return;
        for (ServerPlayer p : players.values()) {
            if (!p.dead) return;
        }
        world.endRun(false);
    }

    private void handleLevelLoaded(ServerPlayer p, ByteReader r) {
        int serial = r.readVarInt();
        if (serial != world.levelSerial() || p.loadedLevelSerial == serial) return;
        p.loadedLevelSerial = serial;
        p.lastStateSeq = -1;
        world.replicator().onClientLevelReady(p);
    }

    private void handleStairs(ServerPlayer p, ByteReader r) {
        boolean down = r.readBool();
        if (!p.inWorld()) return;
        Level level = world.level();
        Stairs s = down ? level.down : level.up;
        if (s == null) {
            Mp.log(p.name + " asked for stairs " + (down ? "down" : "up") + " but this level has none");
            return;
        }
        boolean near = Math.abs(s.x - p.proxy.x) < 2f && Math.abs(s.y - p.proxy.y) < 2f;
        if (!near && !config.allowCheats) return;
        world.asPlayer(p, s, () -> world.onStairs(s));
    }

    private void handleTrigger(ServerPlayer p, ByteReader r) {
        int instigatorId = r.readVarInt();
        String triggersId = r.readString();
        String value = r.readString();
        if (!p.inWorld() || triggersId == null || triggersId.isEmpty()) return;
        Entity instigator = world.replicator().entity(instigatorId);
        world.asPlayer(p, instigator, () -> world.level().trigger(instigator != null ? instigator : p.proxy, triggersId, value));
    }

    // ------------------------------------------------------------------ broadcasts

    void broadcastReliable(ByteWriter w) {
        byte[] msg = w.toByteArray();
        for (ServerPlayer p : players.values()) p.conn.sendReliable(msg);
    }

    void broadcastChat(String from, String text) {
        ByteWriter w = new ByteWriter(text.length() + 32).writeByte(Msg.S_CHAT).writeString(from).writeString(text);
        broadcastReliable(w);
    }

    void broadcastPlayers() {
        ByteWriter w = new ByteWriter(128).writeByte(Msg.S_PLAYERS).writeVarInt(players.size());
        for (ServerPlayer p : players.values()) {
            w.writeVarInt(p.id).writeString(p.name).writeBool(!p.dead).writeVarInt(p.look);
        }
        broadcastReliable(w);
    }

    /** A random character, preferring the ones fewest players already look like. */
    private int pickLook() {
        int[] used = new int[Msg.PLAYER_LOOKS];
        for (ServerPlayer p : players.values()) used[p.look]++;
        int least = Integer.MAX_VALUE;
        for (int u : used) least = Math.min(least, u);
        java.util.List<Integer> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < used.length; i++) if (used[i] == least) candidates.add(i);
        return candidates.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    void broadcastMessage(String text, float seconds, float scale) {
        for (ServerPlayer p : players.values()) {
            if (p.view.levelSent) p.sendMessage(text, seconds, scale);
        }
    }

    void broadcastSound(String file, float x, float y, float z, boolean positioned, float volume, float pitch, float range,
                        ServerPlayer only) {
        ByteWriter w = new ByteWriter(64).writeByte(Msg.S_SOUND).writeVarInt(world.levelSerial()).writeString(file).writeBool(positioned);
        if (positioned) w.writeFloat(x).writeFloat(y).writeFloat(z).writeFloat(range);
        w.writeFloat(volume).writeFloat(pitch);
        byte[] msg = w.toByteArray();
        for (ServerPlayer p : players.values()) {
            if (!p.view.ready || (only != null && p != only)) continue;
            if (positioned && (Math.abs(p.proxy.x - x) > range + 4f || Math.abs(p.proxy.y - y) > range + 4f)) continue;
            p.conn.sendUnreliable(msg, null);
        }
    }

    private void sendPlayerStates() {
        int n = 0;
        for (ServerPlayer p : players.values()) {
            if (p.lastStateSeq >= 0) n++;
        }
        if (n == 0) return;
        for (ServerPlayer target : players.values()) {
            if (!target.view.ready) continue;
            ByteWriter w = new ByteWriter(64 + n * 96).writeByte(Msg.S_PLAYER_STATES).writeVarInt(world.levelSerial());
            int count = 0;
            for (ServerPlayer p : players.values()) {
                if (p == target || p.lastStateSeq < 0 || p.state.levelSerial != world.levelSerial()) continue;
                count++;
            }
            w.writeVarInt(count);
            for (ServerPlayer p : players.values()) {
                if (p == target || p.lastStateSeq < 0 || p.state.levelSerial != world.levelSerial()) continue;
                PlayerState s = p.state;
                int flags = s.flags;
                if (p.dead) flags |= PlayerState.F_DEAD;
                int saved = s.flags;
                s.flags = flags;
                s.write(w);
                s.flags = saved;
            }
            if (count > 0) target.conn.sendUnreliable(w.toByteArray(), null);
        }
    }

    // ------------------------------------------------------------------ pickups

    /** Server-side Item.pickup by a proxy: offer the item to its owner, who answers with C_PICKUP_RESULT. */
    boolean offerPickup(Item item, PlayerProxy proxy) {
        ServerPlayer owner = proxy.owner;
        if (owner == null || !owner.inWorld()) return true;
        if (Math.abs(item.xa) >= 0.01f || Math.abs(item.ya) >= 0.01f || Math.abs(item.za) >= 0.01f) return true;
        if (pendingPickups.containsKey(item)) return true;
        int netId = world.replicator().netIdOf(item);
        if (netId == 0) return true;
        pendingPickups.put(item, owner);
        owner.send(new ByteWriter(8).writeByte(Msg.S_PICKUP).writeVarInt(world.levelSerial()).writeVarInt(netId));
        return true;
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
        }
    }
}
