package com.delveronline.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.delveronline.Mp;
import com.delveronline.headless.HeadlessBoot;
import com.delveronline.net.ByteWriter;
import com.delveronline.proto.Msg;
import com.delveronline.repl.NetKryo;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.entities.PathNode;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.items.QuestItem;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.CachePools;
import com.interrupt.dungeoneer.game.DelverOnlineAccess;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.dungeoneer.entities.Particle;
import com.interrupt.dungeoneer.tiles.Tile;
import com.interrupt.managers.StringManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The authoritative Delver simulation. Runs the real game code headlessly; remote players are {@link PlayerProxy}
 * entities in the level and {@code Game.instance.player} is swapped to the relevant player while each entity
 * ticks (monsters chase the nearest player, triggers react to whoever touches them, and so on).
 */
public final class ServerWorld {
    /** Delver's simulation runs in 1/60 s units. */
    public static final float TICK_DELTA = 1f;
    private static final float PARK = -10000f;

    final DelverServer server;
    private final NetKryo kryo = new NetKryo();
    private final ServerReplicator repl;
    Game game;
    /** Owner-less player used as Game.instance.player for level transitions and when nobody is nearby. */
    PlayerProxy party;

    private int levelSerial;
    private int deepestLevel;
    private float spawnX, spawnY, spawnZ, spawnRot;
    private String levelName = "";
    int tick;
    boolean gameOver;
    long gameOverAtMs;

    /** Player whose request is being executed (null during world ticks). */
    ServerPlayer requester;
    /** Entity currently ticking or being interacted with. */
    Entity currentEntity;
    /** Set while the server itself invokes a native level transition so the hooks let it through. */
    boolean nativeTransition;

    private Method spawnMonstersIfNeeded;
    private final ArrayList<Entity> toDelete = new ArrayList<>();

    ServerWorld(DelverServer server) {
        this.server = server;
        this.repl = new ServerReplicator(this, kryo);
    }

    public ServerReplicator replicator() {
        return repl;
    }

    public NetKryo kryo() {
        return kryo;
    }

    public int levelSerial() {
        return levelSerial;
    }

    public int levelNum() {
        return game == null ? -1 : DelverOnlineAccess.getLevelNum(game);
    }

    public String levelName() {
        return levelName;
    }

    public Level level() {
        return game == null ? null : game.level;
    }

    // ------------------------------------------------------------------ boot

    void boot() {
        HeadlessBoot.initDelverRuntime();
        game = HeadlessBoot.createBareGame();
        party = new PlayerProxy();
        game.player = party;
        try {
            spawnMonstersIfNeeded = Level.class.getDeclaredMethod("spawnMonstersIfNeeded", Player.class, float.class);
            spawnMonstersIfNeeded.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Mp.log("warning: no Level.spawnMonstersIfNeeded; monster respawning disabled");
        }
        newRun();
    }

    /** Starts a fresh dungeon run at the first level (the camp). */
    void newRun() {
        FileHandle saves = Game.getFile("save/" + game.getSaveSlot() + "/");
        if (saves.exists()) saves.deleteDirectory();
        gameOver = false;
        game.gameOver = false;
        game.progression = new Progression();
        party = new PlayerProxy();
        game.player = party;
        party.levelNum = 0;
        DelverOnlineAccess.setLevelNum(game, 0);
        deepestLevel = 0;

        Array<Level> layout = Game.buildLevelLayout();
        Level level = layout.get(0);
        game.level = level;
        level.load();
        if (level.playerStartX != null && level.playerStartY != null) {
            party.x = level.playerStartX + 0.5f;
            party.y = level.playerStartY + 0.5f;
        }
        party.z = level.getTile((int) party.x, (int) party.y).getFloorHeight() + 0.5f;
        if (level.playerStartRot != null) party.rot = (float) Math.toRadians(-(level.playerStartRot + 180f));
        level.setPlayer(party);
        for (ServerPlayer p : server.players()) {
            p.dead = false;
        }
        afterLevelEntered(true);
        Mp.log("new run started on " + levelName);
    }

    // ------------------------------------------------------------------ level transitions

    /** Called after Game has a new current level and the party proxy stands at the arrival point. */
    private void afterLevelEntered(boolean reviveAll) {
        Level level = game.level;
        levelSerial++;
        repl.reset();
        for (ServerPlayer p : server.players()) {
            p.proxy.inLevel = false;
            p.loadedLevelSerial = 0;
            p.view.clear();
        }
        GameManager.renderer.setLevelToRender(level);
        level.isDirty = true;
        GameManager.renderer.Tesselate(level); // collision triangles for projectiles and picking
        levelName = level.levelName == null ? ("Level " + levelNum()) : StringManager.get(level.levelName).replace("\n", " ");
        spawnX = party.x;
        spawnY = party.y;
        spawnZ = party.z;
        spawnRot = party.rot;
        parkParty();
        int num = levelNum();
        boolean newDepth = num > deepestLevel;
        if (newDepth) deepestLevel = num;
        for (ServerPlayer p : server.players()) {
            if ((newDepth || reviveAll) && p.dead) {
                p.dead = false;
                server.broadcastChat(null, p.name + " has been revived.");
            }
            p.proxy.isDead = p.dead;
            p.proxy.x = spawnX;
            p.proxy.y = spawnY;
            p.proxy.z = spawnZ;
        }
        broadcastLevel();
        server.broadcastPlayers();
    }

    private void parkParty() {
        party.x = PARK;
        party.y = PARK;
        party.z = 0f;
        party.isDead = false;
    }

    private void prepareTransition(Entity at) {
        removeProxiesFromLevel();
        game.player = party;
        if (at != null) {
            party.x = at.x;
            party.y = at.y;
            party.z = at.z;
        } else if (requester != null) {
            party.x = requester.proxy.x;
            party.y = requester.proxy.y;
            party.z = requester.proxy.z;
        }
        currentEntity = null;
    }

    /** Party walks through stairs (any player using them moves everyone, as in a co-op party). */
    boolean onStairs(Stairs stairs) {
        if (nativeTransition) return false;
        ServerPlayer by = requester;
        if (stairs.direction == Stairs.StairDirection.up && party.getCurrentTravelKey() != null) {
            levelExit(null);
            return true;
        }
        if (stairs.direction == Stairs.StairDirection.up && levelNum() == 1) {
            if (by != null && hasOrb(by)) {
                escape();
            } else if (by != null) {
                by.sendMessage(StringManager.get("game.Game.cannotLeaveText"), 4f, 1f);
            }
            return true;
        }
        if (by != null) server.broadcastChat(null, by.name + " took the stairs " + stairs.direction + ".");
        prepareTransition(stairs);
        nativeTransition = true;
        try {
            game.doLevelChange(stairs);
        } finally {
            nativeTransition = false;
        }
        afterLevelEntered(false);
        return true;
    }

    /** Moves the party to an explicit level number (server command). */
    void gotoLevel(int num) {
        Array<Level> layout = Game.buildLevelLayout();
        if (num < 0 || num >= layout.size) throw new IllegalArgumentException("level must be 0.." + (layout.size - 1));
        int cur = levelNum();
        if (num == cur) return;
        prepareTransition(null);
        Stairs fake = new Stairs();
        fake.direction = num > cur ? Stairs.StairDirection.down : Stairs.StairDirection.up;
        // doLevelChange moves exactly one level; set the number so the step lands on the requested level
        DelverOnlineAccess.setLevelNum(game, num > cur ? num - 1 : num + 1);
        nativeTransition = true;
        try {
            game.doLevelChange(fake);
        } finally {
            nativeTransition = false;
        }
        afterLevelEntered(false);
    }

    boolean warp(TriggeredWarp warp) {
        if (nativeTransition) return false;
        Player near = game.player;
        Mp.log(String.format(java.util.Locale.ROOT, "warp '%s' (%s, %s) at %.1f,%.1f triggered by %s near player at %.1f,%.1f type=%s",
                warp.id, warp.levelToLoad, warp.travelPath, warp.x, warp.y,
                requester != null ? requester.name : String.valueOf(currentEntity), near.x, near.y, warp.triggerType));
        if (warp.isExit) {
            levelExit(warp);
            return true;
        }
        prepareTransition(warp);
        nativeTransition = true;
        try {
            game.warpToLevel(warp.travelPath, warp);
        } finally {
            nativeTransition = false;
        }
        afterLevelEntered(false);
        return true;
    }

    boolean levelExit(TriggeredWarp warp) {
        if (nativeTransition) return false;
        prepareTransition(warp);
        nativeTransition = true;
        try {
            game.doLevelExit(warp);
        } finally {
            nativeTransition = false;
        }
        afterLevelEntered(false);
        return true;
    }

    /** GameApplication.ShowGameOverScreen on the server: escape (win path) or scripted defeat. */
    boolean gameOverScreen(boolean escaped) {
        if (nativeTransition) return false;
        if (!escaped) {
            endRun(false);
            return true;
        }
        escape();
        return true;
    }

    private void escape() {
        Level endingLevel = Game.gameData != null ? Game.gameData.endingLevel : null;
        String current = game.level.levelFileName;
        if (endingLevel != null && (current == null || !current.equals(endingLevel.levelFileName))) {
            prepareTransition(null);
            nativeTransition = true;
            try {
                GameApplication.ShowGameOverScreen(true); // warps the party to the ending level
            } finally {
                nativeTransition = false;
            }
            server.broadcastChat(null, "The party escaped the dungeon!");
            afterLevelEntered(true);
        } else {
            endRun(true);
        }
    }

    /** Ends the run for everyone: victory or defeat. Clients show the native win/game-over screens. */
    void endRun(boolean won) {
        if (gameOver) return;
        gameOver = true;
        game.gameOver = true;
        gameOverAtMs = System.currentTimeMillis();
        Mp.log(won ? "the party won" : "game over: all players are dead");
        server.broadcastReliable(new ByteWriter(4).writeByte(won ? Msg.S_WIN : Msg.S_GAME_OVER));
    }

    private boolean hasOrb(ServerPlayer p) {
        if (p.state.has(com.delveronline.proto.PlayerState.F_HOLDING_ORB)) return true;
        for (Item i : p.proxy.inventory) {
            if (i instanceof QuestItem) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ level snapshot

    void broadcastLevel() {
        byte[] msg = buildLevelMessage();
        for (ServerPlayer p : server.players()) {
            sendLevelBytes(p, msg);
        }
    }

    void sendLevelTo(ServerPlayer p) {
        sendLevelBytes(p, buildLevelMessage());
    }

    private void sendLevelBytes(ServerPlayer p, byte[] msg) {
        p.view.clear();
        p.view.levelSent = true;
        p.loadedLevelSerial = 0;
        p.proxy.x = spawnX;
        p.proxy.y = spawnY;
        p.proxy.z = spawnZ;
        p.conn.sendReliable(msg);
    }

    private byte[] buildLevelMessage() {
        Level level = game.level;
        removeProxiesFromLevel();
        repl.registerAllSilently(level);
        ByteWriter ids = new ByteWriter(4096);
        Array<Entity> e0 = repl.snapshotList(level.entities, ids);
        Array<Entity> e1 = repl.snapshotList(level.non_collidable_entities, ids);
        Array<Entity> e2 = repl.snapshotList(level.static_entities, ids);
        Array<Entity> o0 = level.entities, o1 = level.non_collidable_entities, o2 = level.static_entities;
        byte[] raw;
        try {
            level.entities = e0;
            level.non_collidable_entities = e1;
            level.static_entities = e2;
            for (int i = 0; i < level.tiles.length; i++) {
                level.tileMaterials[i] = level.tiles[i] == null ? null : level.tiles[i].materials;
            }
            raw = NetKryo.levelToBytes(level);
        } finally {
            level.entities = o0;
            level.non_collidable_entities = o1;
            level.static_entities = o2;
        }
        if (raw == null) throw new IllegalStateException("level serialization failed");
        byte[] packed = NetKryo.deflate(raw);
        ByteWriter w = new ByteWriter(packed.length + ids.size() + 128);
        w.writeByte(Msg.S_LEVEL);
        w.writeVarInt(levelSerial);
        w.writeSVarInt(levelNum());
        w.writeString(levelName);
        w.writeFloat(spawnX).writeFloat(spawnY).writeFloat(spawnZ).writeFloat(spawnRot);
        w.writeVarInt(tick);
        w.writeRaw(ids.array(), 0, ids.size());
        w.writeBytes(packed);
        Mp.log("level snapshot " + levelSerial + ": " + raw.length / 1024 + " KiB raw, " + packed.length / 1024 + " KiB sent, "
                + repl.trackedCount() + " entities");
        return w.toByteArray();
    }

    // ------------------------------------------------------------------ simulation

    void tick() {
        tick++;
        Level level = game.level;
        if (level == null || gameOver) return;
        List<ServerPlayer> alive = new ArrayList<>(8);
        for (ServerPlayer p : server.players()) {
            if (p.inWorld()) alive.add(p);
        }
        syncProxies(level);
        if (alive.isEmpty()) return; // nobody in the world: freeze it

        GlRenderer.time += 1f / 60f;
        game.time += TICK_DELTA;
        level.updateSpatialHash(null);
        tickList(level, level.entities, alive);
        tickList(level, level.non_collidable_entities, alive);
        spawnMonsters(level, alive);
        if (tick % 35 == 0) updatePathing(alive);
        game.player = party;
        currentEntity = null;
    }

    private void tickList(Level level, Array<Entity> list, List<ServerPlayer> alive) {
        for (int i = 0; i < list.size; i++) {
            Entity e = list.get(i);
            if (e instanceof PlayerProxy) continue;
            if (e.skipTick) {
                e.skipTick = false;
                continue;
            }
            if (e instanceof Particle) continue; // shipped to clients and removed by the replicator
            game.player = nearest(alive, e.x, e.y);
            currentEntity = e;
            try {
                e.tick(level, TICK_DELTA);
            } catch (RuntimeException ex) {
                onEntityFailure(e, ex);
            }
            if (!e.isActive) toDelete.add(e);
        }
        if (!toDelete.isEmpty()) {
            for (Entity e : toDelete) {
                e.onDispose();
                list.removeValue(e, true);
                if (e instanceof Particle) CachePools.freeParticle((Particle) e);
            }
            toDelete.clear();
        }
    }

    private final java.util.IdentityHashMap<Entity, Integer> failures = new java.util.IdentityHashMap<>();

    /** One misbehaving entity must not stop the world: log it, and drop it if it keeps failing. */
    private void onEntityFailure(Entity e, RuntimeException ex) {
        int n = failures.merge(e, 1, Integer::sum);
        if (n == 1) {
            Mp.log("entity " + e.getClass().getSimpleName() + " failed to tick: " + ex);
            ex.printStackTrace();
        }
        if (n >= 30) {
            Mp.log("removing " + e.getClass().getSimpleName() + " after repeated failures");
            e.isActive = false;
            failures.remove(e);
        }
    }

    Player nearest(List<ServerPlayer> alive, float x, float y) {
        Player best = party;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < alive.size(); i++) {
            PlayerProxy p = alive.get(i).proxy;
            float dx = p.x - x, dy = p.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    List<ServerPlayer> alivePlayers() {
        List<ServerPlayer> alive = new ArrayList<>(8);
        for (ServerPlayer p : server.players()) {
            if (p.inWorld()) alive.add(p);
        }
        return alive;
    }

    private void spawnMonsters(Level level, List<ServerPlayer> alive) {
        if (spawnMonstersIfNeeded == null) return;
        // Vanilla spawn rules are evaluated against one player; run them for a random player, then reject monsters
        // that would pop into view of (or right next to) anybody else.
        ServerPlayer chosen = alive.get(Game.rand.nextInt(alive.size()));
        game.player = chosen.proxy;
        int before = level.entities.size;
        try {
            spawnMonstersIfNeeded.invoke(level, chosen.proxy, TICK_DELTA);
        } catch (ReflectiveOperationException e) {
            Mp.log("monster spawn failed: " + e);
            spawnMonstersIfNeeded = null;
        }
        for (int i = before; i < level.entities.size; i++) {
            Entity e = level.entities.get(i);
            if (!(e instanceof Monster)) continue;
            for (ServerPlayer p : alive) {
                PlayerProxy pp = p.proxy;
                boolean tooClose = Math.abs(e.x - pp.x) <= 4f && Math.abs(e.y - pp.y) <= 4f;
                if (p != chosen && !pp.isHoldingOrb && (tooClose || level.canSee(e.x, e.y, pp.x, pp.y))) {
                    level.entities.removeIndex(i--);
                    break;
                }
            }
        }
    }

    /** Multi-source version of Delver's "player smell" flood fill used by monster pathing. */
    private void updatePathing(List<ServerPlayer> alive) {
        Array<PathNode> nodes = Game.pathfinding.GetNodes();
        if (nodes == null) return;
        for (int i = 0; i < nodes.size; i++) {
            PathNode n = nodes.get(i);
            if (n != null) n.reset();
        }
        for (ServerPlayer p : alive) {
            PathNode n = Game.pathfinding.GetNodeAt(p.proxy.x, p.proxy.y, p.proxy.z);
            if (n != null) n.walk(0, null);
        }
    }

    // ------------------------------------------------------------------ proxies

    private void syncProxies(Level level) {
        for (ServerPlayer p : server.players()) {
            PlayerProxy proxy = p.proxy;
            boolean should = p.inWorld();
            if (should && !proxy.inLevel) {
                level.entities.add(proxy);
                proxy.inLevel = true;
            } else if (!should && proxy.inLevel) {
                level.entities.removeValue(proxy, true);
                proxy.inLevel = false;
            }
            proxy.isDead = p.dead;
        }
    }

    void removeProxiesFromLevel() {
        Level level = game.level;
        for (ServerPlayer p : server.players()) {
            if (p.proxy.inLevel && level != null) level.entities.removeValue(p.proxy, true);
            p.proxy.inLevel = false;
        }
    }

    void onPlayerLeft(ServerPlayer p) {
        Level level = game.level;
        if (p.proxy.inLevel && level != null) level.entities.removeValue(p.proxy, true);
        p.proxy.inLevel = false;
    }

    // ------------------------------------------------------------------ request execution

    interface Action {
        void run();
    }

    /** Runs {@code a} as if {@code p} were the single-player hero. */
    void asPlayer(ServerPlayer p, Entity target, Action a) {
        Player prevPlayer = game.player;
        ServerPlayer prevReq = requester;
        Entity prevEntity = currentEntity;
        game.player = p.proxy;
        requester = p;
        currentEntity = target;
        int keys = p.proxy.keys, gold = p.proxy.gold;
        try {
            a.run();
        } finally {
            game.player = prevPlayer;
            requester = prevReq;
            currentEntity = prevEntity;
        }
        int dk = p.proxy.keys - keys, dg = p.proxy.gold - gold;
        if (dk != 0 || dg != 0) {
            p.send(new ByteWriter(8).writeByte(Msg.S_PLAYER_DELTA).writeSVarInt(dk).writeSVarInt(dg));
        }
    }

    void useTile(ServerPlayer p, int tx, int ty) {
        Level level = game.level;
        Tile t = level.getTileOrNull(tx, ty);
        if (t == null) return;
        if (Math.abs(tx + 0.5f - p.proxy.x) > 2.5f || Math.abs(ty + 0.5f - p.proxy.y) > 2.5f) return;
        asPlayer(p, null, t::use);
    }

    /** Places an entity the player created (thrown item, projectile, explosion...). */
    void spawnFromPlayer(ServerPlayer p, Entity e, int list) {
        Level level = game.level;
        e.owner = p.proxy;
        e.spawnChance = 1f;
        if (Math.abs(e.x - p.proxy.x) > 4f || Math.abs(e.y - p.proxy.y) > 4f) {
            // never trust far-away spawns: put them at the player
            e.x = p.proxy.x;
            e.y = p.proxy.y;
        }
        asPlayer(p, e, () -> {
            if (list == 1) {
                level.non_collidable_entities.add(e);
                e.init(level, Level.Source.SPAWNED);
            } else {
                level.SpawnEntity(e);
            }
        });
    }

    String describe() {
        return String.format("level %d '%s' (serial %d), tick %d, %d tracked entities, players: %d", levelNum(), levelName,
                levelSerial, tick, repl.trackedCount(), server.players().size());
    }

    static void log(String s) {
        Gdx.app.log("DelverOnline", s);
    }
}
