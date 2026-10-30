package com.delveronline.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.Array;
import com.delveronline.Mp;
import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;
import com.delveronline.net.Connection;
import com.delveronline.net.NetEndpoint;
import com.delveronline.net.NetListener;
import com.delveronline.net.ProtocolException;
import com.delveronline.proto.Msg;
import com.delveronline.proto.PlayerState;
import com.delveronline.repl.NetKryo;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.game.DelverOnlineAccess;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.gfx.DecalManager;
import com.interrupt.dungeoneer.screens.GameScreen;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;
import com.interrupt.dungeoneer.ui.Hud;
import com.interrupt.utils.JsonUtil;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One multiplayer session on the client: connection, world replication, players, chat and spectating. */
public final class MpClient implements NetListener {
    public enum Phase { CONNECTING, JOINING, PLAYING, CLOSED }

    static volatile MpClient current;

    final String host;
    final int port;
    final String requestedName;
    private NetEndpoint net;
    private Connection conn;
    volatile Phase phase = Phase.CONNECTING;
    volatile String status = "Connecting...";
    String closeReason;
    int myId;
    String myName;
    String serverName;

    final NetKryo kryo = new NetKryo();
    final ClientReplicator repl = new ClientReplicator(this, kryo);
    final ChatOverlay chat = new ChatOverlay();
    final NameTags nameTags = new NameTags();
    private final ArrayDeque<Object[]> inbox = new ArrayDeque<>();
    private LevelData pendingLevel;
    boolean gameStarted;

    final LinkedHashMap<Integer, String> names = new LinkedHashMap<>();
    final HashMap<Integer, Boolean> alive = new HashMap<>();
    final HashMap<Integer, RemoteAvatar> avatars = new HashMap<>();

    // local player
    boolean dead;
    boolean spectating;
    int spectateId;
    boolean allowGameOver;
    boolean allowWin;
    /** Set while running a granted pickup locally so the pickup hook lets it through. */
    boolean applyingPickup;
    /** Set while replaying a server trigger locally (its world side effects already happened on the server). */
    boolean replayingTrigger;
    int attackCount;
    private int stateSeq;
    private float stateAccumulator;
    private long lastInventoryCheck;
    private int lastInventorySignature;
    private long lastReportMs;
    private int framesSinceReport;
    final boolean telemetry = Boolean.getBoolean("delveronline.telemetry");

    static final class LevelData {
        int serial, levelNum, tick;
        int[] exitTiles;
        String name;
        float x, y, z, rot;
        int[][] ids;
        byte[] packed;
    }

    private MpClient(String host, int port, String name) {
        this.host = host;
        this.port = port;
        this.requestedName = name;
    }

    public static MpClient current() {
        return current;
    }

    /** Starts connecting. Progress is visible through {@link #status} and {@link #phase}. */
    public static MpClient connect(String host, int port, String name) {
        MpClient old = current;
        if (old != null) old.disconnect("reconnecting");
        MpClient c = new MpClient(host, port, name);
        current = c;
        Mp.setMode(Mp.Mode.CLIENT);
        try {
            c.net = NetEndpoint.client(c);
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (addr.isUnresolved()) throw new IllegalArgumentException("unknown host " + host);
            c.conn = c.net.connect(addr);
        } catch (Exception e) {
            c.fail("Could not connect: " + e.getMessage());
        }
        return c;
    }

    /** Network id of a replicated entity, 0 for local-only entities. */
    public int netIdOf(Entity e) {
        return repl.netIdOf(e);
    }

    public Entity entityById(int netId) {
        return repl.entity(netId);
    }

    public ChatOverlay chat() {
        return chat;
    }

    /** "name x y z yaw dead" for every remote player we currently see (diagnostics and tests). */
    public String describeRemotePlayers() {
        StringBuilder sb = new StringBuilder();
        for (RemoteAvatar a : avatars.values()) {
            if (!a.hasState()) continue;
            sb.append(String.format(java.util.Locale.ROOT, "%s %.2f %.2f %.2f %.2f %s;", a.playerName, a.x, a.y, a.z, a.viewYaw(),
                    a.isDead() ? "dead" : "alive"));
        }
        return sb.toString();
    }

    public boolean inGame() {
        return gameStarted && phase == Phase.PLAYING;
    }

    // ------------------------------------------------------------------ frame pump

    /** Called at the start of every frame on the GL thread. */
    void update() {
        if (net == null) return;
        net.update();
        processInbox();
        if (phase == Phase.CLOSED) return;
        if (inGame()) {
            Game game = Game.instance;
            if (game != null && game.level != null) {
                repl.forwardLocalSpawns(game.level);
                stateAccumulator += Gdx.graphics.getDeltaTime();
                if (stateAccumulator >= 1f / 30f) {
                    stateAccumulator = 0f;
                    sendState(game.player);
                }
                sendInventoryIfChanged(game.player);
                if (telemetry) sendReport();
            }
        }
        net.update();
    }

    private void processInbox() {
        while (!inbox.isEmpty()) {
            if (pendingLevel != null && !gameStarted) return; // wait until the game screen has loaded it
            Object[] m = inbox.poll();
            byte[] data = (byte[]) m[0];
            try {
                handle(new ByteReader(data), (Boolean) m[1], (Integer) m[2]);
            } catch (ClientReplicator.SkipEntity e) {
                // update referenced an unknown entity: rest of that packet dropped
            } catch (ProtocolException e) {
                Mp.log("protocol error: " + e.getMessage());
                disconnect("Protocol error: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                Mp.log("error handling server message: " + e);
                e.printStackTrace();
            }
            if (phase == Phase.CLOSED) return;
        }
    }

    // ------------------------------------------------------------------ NetListener

    @Override
    public void onConnected(Connection connection) {
        status = "Joining...";
        String name = requestedName == null || requestedName.trim().isEmpty() ? "Adventurer" : requestedName.trim();
        connection.sendReliable(new ByteWriter(64).writeByte(Msg.C_HELLO).writeVarInt(Mp.PROTOCOL_VERSION).writeString(name).toByteArray());
    }

    @Override
    public void onDisconnected(Connection connection, String reason) {
        if (phase == Phase.CLOSED) return;
        fail(closeReason != null ? closeReason : "Disconnected: " + reason);
    }

    @Override
    public void onMessage(Connection connection, byte[] data, boolean reliable, int packetSeq) {
        inbox.add(new Object[]{data, reliable, packetSeq});
    }

    // ------------------------------------------------------------------ messages

    private void handle(ByteReader r, boolean reliable, int packetSeq) {
        int type = r.readUByte();
        Game game = Game.instance;
        Level level = gameStarted && game != null ? game.level : null;
        switch (type) {
            case Msg.S_WELCOME:
                myId = r.readVarInt();
                serverName = r.readString();
                r.readVarInt();
                myName = r.readString();
                phase = Phase.JOINING;
                status = "Loading level...";
                rememberServer();
                break;
            case Msg.S_KICK:
                closeReason = r.readString();
                break;
            case Msg.S_PLAYERS:
                readPlayers(r);
                break;
            case Msg.S_LEVEL:
                onLevel(readLevel(r));
                break;
            case Msg.S_SPAWN:
                repl.applySpawn(level, r);
                break;
            case Msg.S_REMOVE:
                repl.applyRemove(level, r);
                break;
            case Msg.S_UPDATE:
                if (level != null) repl.applyUpdate(r, packetSeq);
                break;
            case Msg.S_PLAYER_STATES:
                readPlayerStates(r);
                break;
            case Msg.S_EFFECTS:
                repl.applyEffects(level, r);
                break;
            case Msg.S_SOUND:
                playSound(r);
                break;
            case Msg.S_HIT:
                onHit(r);
                break;
            case Msg.S_XP:
                if (inGame() && !dead) game.player.addExperience(r.readSVarInt());
                break;
            case Msg.S_STATUS: {
                Object o = kryo.read(r.readBytes());
                if (inGame() && !dead && o instanceof StatusEffect) game.player.addStatusEffect((StatusEffect) o);
                break;
            }
            case Msg.S_PICKUP:
                onPickupOffer(r);
                break;
            case Msg.S_GIVE_ITEM: {
                Object o = kryo.read(r.readBytes());
                if (inGame() && o instanceof Item) giveItem(game.player, (Item) o);
                break;
            }
            case Msg.S_PLAYER_DELTA: {
                int dk = r.readSVarInt(), dg = r.readSVarInt();
                if (inGame()) {
                    game.player.keys = Math.max(0, game.player.keys + dk);
                    game.player.gold = Math.max(0, game.player.gold + dg);
                }
                break;
            }
            case Msg.S_MESSAGE: {
                String text = r.readString();
                float seconds = r.readFloat(), scale = r.readFloat();
                if (inGame() && text != null) Game.ShowMessage(text, seconds, scale);
                break;
            }
            case Msg.S_CHAT: {
                String from = r.readString();
                String text = r.readString();
                chat.add(from, text);
                event("chat " + (from == null ? "*" : from) + ": " + text);
                break;
            }
            case Msg.S_LOCAL_TRIGGER:
                onLocalTrigger(r);
                break;
            case Msg.S_KILL:
                if (inGame() && !dead) {
                    game.player.hp = 0;
                }
                break;
            case Msg.S_GAME_OVER:
                allowGameOver = true;
                status = "Game over";
                event("gameover");
                if (inGame()) game.OnGameOver();
                break;
            case Msg.S_WIN:
                allowWin = true;
                status = "Victory";
                event("win");
                if (inGame()) GameApplication.ShowGameOverScreen(true);
                break;
            default:
                throw new ProtocolException("unknown message type " + type);
        }
    }

    private void readPlayers(ByteReader r) {
        int n = r.readVarInt();
        LinkedHashMap<Integer, String> seen = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int id = r.readVarInt();
            String name = r.readString();
            boolean isAlive = r.readBool();
            seen.put(id, name);
            alive.put(id, isAlive);
        }
        names.clear();
        names.putAll(seen);
        Level level = gameStarted && Game.instance != null ? Game.instance.level : null;
        for (Integer id : new ArrayList<>(avatars.keySet())) {
            if (!seen.containsKey(id)) {
                RemoteAvatar a = avatars.remove(id);
                a.removeFrom(level);
            }
        }
        Boolean me = alive.get(myId);
        if (me != null && me && dead && inGame()) revive();
    }

    private LevelData readLevel(ByteReader r) {
        LevelData d = new LevelData();
        d.serial = r.readVarInt();
        d.levelNum = r.readSVarInt();
        d.name = r.readString();
        d.x = r.readFloat();
        d.y = r.readFloat();
        d.z = r.readFloat();
        d.rot = r.readFloat();
        d.tick = r.readVarInt();
        d.ids = new int[3][];
        for (int l = 0; l < 3; l++) {
            int n = r.readVarInt();
            int[] ids = new int[n];
            for (int i = 0; i < n; i++) ids[i] = r.readVarInt();
            d.ids[l] = ids;
        }
        d.packed = r.readBytes();
        d.exitTiles = new int[r.readVarInt()];
        for (int i = 0; i < d.exitTiles.length; i++) d.exitTiles[i] = r.readVarInt();
        return d;
    }

    private void onLevel(LevelData d) {
        if (!gameStarted) {
            pendingLevel = d;
            status = "Entering " + d.name + "...";
            // GameScreen.show -> GameManager.startGame -> new Game -> Game.Start (hooked) -> setupGame
            GameApplication.instance.mainScreen.didStart = false;
            GameApplication.ShowMainScreen();
            return;
        }
        switchLevel(d);
    }

    // ------------------------------------------------------------------ game setup

    /** Replaces Game.Start for a multiplayer session. */
    boolean setupGame(Game game) {
        LevelData d = pendingLevel;
        if (d == null) return false;
        pendingLevel = null;
        game.loadManagers();
        Game.flashTimer = 0f;
        Game.flashColor.set(Color.BLACK);
        Game.message.clear();
        Game.useMessage.clear();
        Game.useMessageColor = Color.WHITE;
        Game.messageScale = 1f;
        DelverOnlineAccess.setSaveLoc(game, 0);
        DecalManager.setQuality(Options.instance.gfxQuality);
        Game.hudManager.backpack.visible = false;
        game.progression = new Progression();
        Game.isMobile = false;
        Gdx.input.setCursorCatched(true);
        Game.hud = new Hud();
        if (Game.gameData == null) Game.gameData = Game.getModManager().loadGameData();
        Player player;
        try {
            player = JsonUtil.fromJson(Player.class, Game.findInternalFileInMods("data/" + Game.gameData.playerDataFile));
        } catch (Exception e) {
            player = new Player(game);
        }
        if (player == null) player = new Player(game);
        player.randomSeed = (float) Game.rand.nextInt();
        player.startPlaytime();
        player.saveVersion = Game.SAVE_VERSION;
        game.player = player;
        enterLevel(game, d);
        player.init();
        if (GameApplication.instance != null) GameApplication.instance.input.caughtCursor = true;
        GameScreen.resetDelta = true;
        gameStarted = true;
        phase = Phase.PLAYING;
        status = "Playing";
        chat.add(null, "Joined " + (serverName == null ? "server" : serverName) + ". Press "
                + com.badlogic.gdx.Input.Keys.toString(ChatOverlay.chatKey()) + " to chat.");
        return true;
    }

    private void enterLevel(Game game, LevelData d) {
        Player player = game.player;
        for (RemoteAvatar a : avatars.values()) a.removeFrom(game.level);
        Level level = repl.buildLevel(d.serial, d.ids, d.packed);
        for (int idx : d.exitTiles) {
            if (idx < 0 || idx >= level.tiles.length || level.tiles[idx] == null) continue;
            com.interrupt.dungeoneer.tiles.ExitTile exit = new com.interrupt.dungeoneer.tiles.ExitTile();
            com.interrupt.dungeoneer.tiles.Tile.copy(level.tiles[idx], exit);
            level.tiles[idx] = exit;
        }
        game.level = level;
        DelverOnlineAccess.setLevelNum(game, d.levelNum);
        player.x = d.x;
        player.y = d.y;
        player.z = d.z;
        player.rot = d.rot;
        player.xa = player.ya = player.za = 0f;
        player.spawnX = player.x;
        player.spawnY = player.y;
        player.ignoreStairs = true;
        player.levelNum = d.levelNum;
        level.setPlayer(player);
        for (RemoteAvatar a : avatars.values()) {
            a.addTo(level);
            repl.markLocal(a);
            repl.markLocal(a.held);
            repl.markLocal(a.offhand);
        }
        send(new ByteWriter(8).writeByte(Msg.C_LEVEL_LOADED).writeVarInt(d.serial));
        Mp.log("entered " + d.name + " (level " + d.levelNum + ", " + repl.trackedCount() + " entities)");
        event("level " + d.serial + " " + d.levelNum + " " + d.name);
    }

    private void switchLevel(LevelData d) {
        Game game = Game.instance;
        GameManager.renderer.freeLoadedLevel();
        enterLevel(game, d);
        Level level = game.level;
        GameManager.renderer.setLevelToRender(level);
        level.rendererDirty = true;
        GameScreen.resetDelta = true;
        level.initAudio();
        if (Options.instance.enableMusic && !Mp.headless) {
            Audio.playMusic(game.player.isHoldingOrb ? level.actionMusic : level.music, level.loopMusic);
        }
        Game.flash(Color.BLACK, 40);
        Game.ShowMessage(d.name, 3f, 1f);
    }

    // ------------------------------------------------------------------ players

    private void readPlayerStates(ByteReader r) {
        int serial = r.readVarInt();
        int n = r.readVarInt();
        Level level = inGame() ? Game.instance.level : null;
        for (int i = 0; i < n; i++) {
            PlayerState s = new PlayerState().read(r);
            if (s.id == myId || serial != repl.levelSerial || level == null) continue;
            RemoteAvatar a = avatars.get(s.id);
            if (a == null) {
                a = new RemoteAvatar(s.id, names.getOrDefault(s.id, "?"));
                avatars.put(s.id, a);
                a.addTo(level);
                repl.markLocal(a);
                repl.markLocal(a.held);
                repl.markLocal(a.offhand);
            }
            a.playerName = names.getOrDefault(s.id, a.playerName);
            a.onState(s);
        }
    }

    private void sendState(Player p) {
        PlayerState s = new PlayerState();
        s.seq = ++stateSeq;
        s.levelSerial = repl.levelSerial;
        s.x = p.x;
        s.y = p.y;
        s.z = p.z;
        s.rot = p.rot;
        s.yrot = p.yrot;
        s.xa = p.xa;
        s.ya = p.ya;
        s.za = p.za;
        s.hp = p.hp;
        s.maxHp = p.getMaxHp();
        s.level = p.level;
        s.keys = p.keys;
        s.gold = p.gold;
        int flags = 0;
        if (p.isDead || dead) flags |= PlayerState.F_DEAD;
        if (p.isHoldingOrb) flags |= PlayerState.F_HOLDING_ORB;
        if (p.invisible) flags |= PlayerState.F_INVISIBLE;
        if (p.isOnFloor) flags |= PlayerState.F_ON_FLOOR;
        if (spectating) flags |= PlayerState.F_SPECTATING;
        s.flags = flags;
        s.visibility = p.visiblityMod;
        s.attackCount = attackCount;
        Item held = p.GetHeldItem();
        if (held != null) {
            s.heldAtlas = atlasOf(held);
            s.heldTex = held.tex;
        }
        Item off = p.GetHeldOffhandItem();
        if (off != null) {
            s.offAtlas = atlasOf(off);
            s.offTex = off.tex;
        }
        if (spectating) {
            // our camera rides on someone else: do not report their position as ours
            s.x = deathX;
            s.y = deathY;
            s.z = deathZ;
            s.xa = s.ya = s.za = 0f;
        }
        ByteWriter w = new ByteWriter(96).writeByte(Msg.C_STATE);
        s.write(w);
        conn.sendUnreliable(w.toByteArray(), null);
    }

    private static String atlasOf(Item i) {
        if (i.spriteAtlas != null) return i.spriteAtlas;
        return i.artType != null ? i.artType.toString() : "item";
    }

    private void sendInventoryIfChanged(Player p) {
        long now = System.currentTimeMillis();
        if (now - lastInventoryCheck < 500) return;
        lastInventoryCheck = now;
        int sig = 1;
        for (Item i : p.inventory) sig = sig * 31 + System.identityHashCode(i);
        for (Map.Entry<String, Item> e : p.equippedItems.entrySet()) sig = sig * 31 + System.identityHashCode(e.getValue());
        if (sig == lastInventorySignature) return;
        lastInventorySignature = sig;
        Object[] payload = {new Array<>(p.inventory), new HashMap<>(p.equippedItems)};
        byte[] bytes;
        try {
            bytes = kryo.write(payload);
        } catch (RuntimeException e) {
            return;
        }
        send(new ByteWriter(bytes.length + 8).writeByte(Msg.C_INVENTORY).writeBytes(bytes));
    }

    // ------------------------------------------------------------------ server-driven player events

    private void onHit(ByteReader r) {
        float projx = r.readFloat(), projy = r.readFloat();
        int damage = r.readSVarInt();
        float knockback = r.readFloat();
        int dt = r.readVarInt();
        int instigatorId = r.readVarInt();
        float ix = r.readFloat(), iy = r.readFloat();
        Game game = Game.instance;
        if (!inGame() || dead || game.player.isDead) return;
        Weapon.DamageType type = dt == 0 ? null : Weapon.DamageType.values()[Math.min(dt - 1, Weapon.DamageType.values().length - 1)];
        Entity instigator = repl.entity(instigatorId);
        Player p = game.player;
        p.hit(projx, projy, damage, knockback, type, instigator);
        // monsters shove the player back (Monster.attack applies this to its target)
        if (knockback > 0f) {
            float dx = p.x - ix, dy = p.y - iy;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0.001f) {
                p.xa += dx / len * knockback * 0.25f;
                p.ya += dy / len * knockback * 0.25f;
            }
        }
    }

    private void onPickupOffer(ByteReader r) {
        int serial = r.readVarInt();
        int netId = r.readVarInt();
        Entity e = repl.entity(netId);
        boolean accepted = false;
        Game game = Game.instance;
        if (serial == repl.levelSerial && inGame() && !dead && e instanceof Item && e.isActive) {
            Item item = (Item) e;
            applyingPickup = true;
            try {
                item.xa = item.ya = item.za = 0f;
                com.interrupt.dungeoneer.entities.DelverOnlineEntityAccess.pickup(item, game.player);
            } finally {
                applyingPickup = false;
            }
            accepted = !item.isActive;
            if (accepted) {
                repl.forget(item);
                game.level.entities.removeValue(item, true);
                game.level.non_collidable_entities.removeValue(item, true);
            }
        }
        send(new ByteWriter(8).writeByte(Msg.C_PICKUP_RESULT).writeVarInt(netId).writeBool(accepted));
    }

    private void giveItem(Player p, Item item) {
        if (!p.addToInventory(item)) {
            // no room: drop it at our feet (the drop is forwarded to the server like any other)
            p.dropItem(item, Game.instance.level, 0f);
        }
    }

    private void onLocalTrigger(ByteReader r) {
        int serial = r.readVarInt();
        int netId = r.readVarInt();
        String value = r.readString();
        Entity e = repl.entity(netId);
        if (serial != repl.levelSerial || !(e instanceof Trigger) || !inGame()) return;
        replayingTrigger = true;
        try {
            ((Trigger) e).doTriggerEvent(value);
        } finally {
            replayingTrigger = false;
        }
    }

    private void playSound(ByteReader r) {
        int serial = r.readVarInt();
        String file = r.readString();
        boolean positioned = r.readBool();
        float x = 0, y = 0, z = 0, range = 0;
        if (positioned) {
            x = r.readFloat();
            y = r.readFloat();
            z = r.readFloat();
            range = r.readFloat();
        }
        float volume = r.readFloat(), pitch = r.readFloat();
        if (!inGame() || serial != repl.levelSerial || Mp.headless) return;
        if (positioned) {
            Audio.playPositionedSound(file, new Vector3(x, y, z), volume, pitch, range);
        } else {
            Audio.playSound(file, volume, pitch);
        }
    }

    // ------------------------------------------------------------------ death, spectating, revival

    private float deathX, deathY, deathZ;

    void onLocalDeath(Player p) {
        if (dead) return;
        dead = true;
        deathX = p.x;
        deathY = p.y;
        deathZ = p.z;
        send(new ByteWriter(2).writeByte(Msg.C_DIED));
        event("died");
    }

    /** The death animation finished: watch the other players. */
    void startSpectating(Player p) {
        if (spectating) return;
        spectating = true;
        p.dyingAnimation = null;
        spectateId = 0;
        nextSpectateTarget(1);
    }

    void nextSpectateTarget(int dir) {
        List<Integer> ids = new ArrayList<>();
        for (Map.Entry<Integer, RemoteAvatar> e : avatars.entrySet()) {
            if (e.getValue().hasState() && !e.getValue().isDead()) ids.add(e.getKey());
        }
        if (ids.isEmpty()) {
            spectateId = 0;
            return;
        }
        java.util.Collections.sort(ids);
        int idx = ids.indexOf(spectateId);
        idx = idx < 0 ? 0 : Math.floorMod(idx + dir, ids.size());
        int previous = spectateId;
        spectateId = ids.get(idx);
        if (spectateId != previous) event("spectating " + names.getOrDefault(spectateId, "?"));
    }

    private boolean spectateLeftWasDown = true, spectateRightWasDown = true;

    /** Puts the (dead) local player's camera on the spectated player. Called after the level tick. */
    void updateSpectator(Player p) {
        if (!spectating) {
            chat.setStatus(null);
            return;
        }
        RemoteAvatar a = avatars.get(spectateId);
        if (a == null || !a.hasState() || a.isDead()) {
            nextSpectateTarget(1);
            a = avatars.get(spectateId);
        }
        boolean left = Gdx.input.isButtonPressed(com.badlogic.gdx.Input.Buttons.LEFT);
        boolean right = Gdx.input.isButtonPressed(com.badlogic.gdx.Input.Buttons.RIGHT);
        boolean nextPressed = (left && !spectateLeftWasDown) || Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.RIGHT);
        boolean prevPressed = (right && !spectateRightWasDown) || Gdx.input.isKeyJustPressed(com.badlogic.gdx.Input.Keys.LEFT);
        spectateLeftWasDown = left;
        spectateRightWasDown = right;
        if (nextPressed) {
            nextSpectateTarget(1);
            a = avatars.get(spectateId);
        } else if (prevPressed) {
            nextSpectateTarget(-1);
            a = avatars.get(spectateId);
        }
        if (a == null) {
            chat.setStatus("You are dead. Waiting for the others...");
            return;
        }
        p.x = a.x;
        p.y = a.y;
        p.z = a.z;
        p.rot = a.viewYaw();
        p.yrot = a.viewPitch();
        p.xa = p.ya = p.za = 0f;
        chat.setStatus("Spectating " + a.playerName + "  (click to switch)");
    }

    boolean isSpectating() {
        return spectating;
    }

    private void revive() {
        Player p = Game.instance.player;
        dead = false;
        spectating = false;
        chat.setStatus(null);
        p.isDead = false;
        p.dyingAnimation = null;
        p.hp = p.getMaxHp();
        p.xa = p.ya = p.za = 0f;
        p.statusEffects = null;
        Game.flash(Color.WHITE, 30);
        Game.ShowMessage("You have been revived!", 3f, 1f);
        event("revived");
    }

    static void event(String s) {
        Mp.log("EVENT " + s);
    }

    // ------------------------------------------------------------------ outgoing

    void send(ByteWriter w) {
        if (conn != null && conn.isConnected()) conn.sendReliable(w.toByteArray());
    }

    void sendHit(int netId, float projx, float projy, int damage, float knockback, Weapon.DamageType type) {
        ByteWriter w = new ByteWriter(48).writeByte(Msg.C_HIT).writeVarInt(netId).writeFloat(projx).writeFloat(projy).writeSVarInt(damage)
                .writeFloat(knockback).writeVarInt(type == null ? 0 : type.ordinal() + 1);
        writePosition(w);
        send(w);
    }

    void sendUse(int netId, float projx, float projy) {
        ByteWriter w = new ByteWriter(32).writeByte(Msg.C_USE).writeVarInt(netId).writeFloat(projx).writeFloat(projy);
        writePosition(w);
        send(w);
    }

    /** Requests carry where we stand, so they never race the (unreliable) movement updates. */
    private void writePosition(ByteWriter w) {
        Player p = Game.instance != null ? Game.instance.player : null;
        w.writeVarInt(repl.levelSerial);
        w.writeFloat(p != null ? p.x : 0f).writeFloat(p != null ? p.y : 0f).writeFloat(p != null ? p.z : 0f);
    }

    void sendSpawn(Entity e, int list) {
        byte[] bytes = kryo.write(e);
        send(new ByteWriter(bytes.length + 8).writeByte(Msg.C_SPAWN).writeByte(list).writeBytes(bytes));
    }

    void sendTrigger(Entity instigator, String triggersId, String value) {
        send(new ByteWriter(64).writeByte(Msg.C_TRIGGER).writeVarInt(repl.netIdOf(instigator)).writeString(triggersId).writeString(value));
    }

    void sendTileUse(int x, int y) {
        ByteWriter w = new ByteWriter(24).writeByte(Msg.C_TILE_USE).writeSVarInt(x).writeSVarInt(y);
        writePosition(w);
        send(w);
    }

    void sendGameEnd(boolean won) {
        send(new ByteWriter(4).writeByte(Msg.C_GAME_END).writeBool(won));
    }

    void sendStairs(boolean down) {
        send(new ByteWriter(4).writeByte(Msg.C_STAIRS).writeBool(down));
    }

    public void sendChat(String text) {
        send(new ByteWriter(text.length() + 8).writeByte(Msg.C_CHAT).writeString(text));
    }

    private void sendReport() {
        framesSinceReport++;
        long now = System.currentTimeMillis();
        if (lastReportMs == 0) {
            lastReportMs = now;
            framesSinceReport = 0;
            return;
        }
        if (now - lastReportMs < 2000) return;
        float fps = framesSinceReport * 1000f / (now - lastReportMs);
        lastReportMs = now;
        framesSinceReport = 0;
        ByteWriter w = new ByteWriter(4096).writeByte(Msg.C_REPORT).writeVarInt(repl.levelSerial).writeVarInt(repl.lastAppliedTick);
        w.writeFloat(fps);
        w.writeVarInt(repl.trackedCount());
        repl.writeReport(w, 250);
        send(w);
    }

    private void rememberServer() {
        try {
            ClientSettings s = ClientSettings.load();
            s.lastServer = port == Mp.DEFAULT_PORT ? host : host + ":" + port;
            s.playerName = requestedName == null ? "" : requestedName;
            s.save();
        } catch (RuntimeException e) {
            Mp.log("could not remember server: " + e);
        }
    }

    // ------------------------------------------------------------------ teardown

    private void fail(String reason) {
        if (phase == Phase.CLOSED && net == null) return;
        boolean wasPlaying = gameStarted;
        status = reason;
        closeReason = reason;
        phase = Phase.CLOSED;
        Mp.log("session closed: " + reason);
        closeNetwork();
        if (!wasPlaying) {
            finishSession();
        } else if (GameApplication.instance != null && !allowGameOver && !allowWin) {
            // back to the menu; the menu hook ends the session once the game screen is gone
            MultiplayerMenu.pendingMessage = reason;
            GameApplication.ShowMainMenuScreen();
        }
    }

    /** Leave the server voluntarily. */
    public void disconnect(String reason) {
        if (phase != Phase.CLOSED) {
            phase = Phase.CLOSED;
            status = reason;
            if (conn != null && conn.isConnected()) conn.close(reason);
        }
        closeNetwork();
        if (!gameStarted) finishSession();
    }

    private void closeNetwork() {
        if (net != null) {
            NetEndpoint n = net;
            net = null;
            n.close();
        }
    }

    /** Multiplayer is fully over (we are back at the main menu): restore single-player behaviour. */
    void finishSession() {
        closeNetwork();
        phase = Phase.CLOSED;
        if (current == this) {
            current = null;
            Mp.setMode(Mp.Mode.OFF);
        }
    }
}
