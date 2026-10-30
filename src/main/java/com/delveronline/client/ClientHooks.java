package com.delveronline.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.delveronline.Mp;
import com.interrupt.api.steam.SteamApi;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.input.Actions;
import com.interrupt.dungeoneer.overlays.OverlayManager;
import com.interrupt.dungeoneer.overlays.PauseOverlay;
import com.interrupt.dungeoneer.screens.GameScreen;
import com.interrupt.dungeoneer.tiles.Tile;
import com.interrupt.utils.Logger;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Client-side implementations of the woven hooks. */
public final class ClientHooks {
    private ClientHooks() {}

    private static MpClient session() {
        return MpClient.current;
    }

    /** A multiplayer game has been started (until we are back at the main menu). */
    public static boolean inMultiplayerGame() {
        MpClient c = MpClient.current;
        return Mp.isClient() && c != null && c.gameStarted;
    }

    private static MpClient playing() {
        MpClient c = MpClient.current;
        return c != null && c.inGame() ? c : null;
    }

    public static boolean isReplicated(Entity e) {
        MpClient c = playing();
        return c != null && c.repl.isReplicated(e);
    }

    public static boolean itemPickup(Item item, Player player) {
        MpClient c = playing();
        if (c == null) return false;
        if (c.applyingPickup) return false;
        // pickups are granted by the server (S_PICKUP); never take a replicated item on our own
        return c.repl.isReplicated(item);
    }

    public static boolean entityHit(Entity self, float projx, float projy, int damage, float knockback,
                                    Weapon.DamageType damageType, Entity instigator) {
        MpClient c = playing();
        if (c == null) return false;
        int netId = c.repl.netIdOf(self);
        if (netId == 0) return false;
        c.sendHit(netId, projx, projy, damage, knockback, damageType);
        return true;
    }

    public static boolean entityUse(Entity self, Player player, float projx, float projy) {
        MpClient c = playing();
        if (c == null) return false;
        int netId = c.repl.netIdOf(self);
        if (netId == 0) return false;
        c.sendUse(netId, projx, projy);
        return true;
    }

    public static boolean tileUse(Tile tile) {
        MpClient c = playing();
        if (c == null) return false;
        Level level = Game.instance.level;
        if (level == null || level.tiles == null) return true;
        for (int i = 0; i < level.tiles.length; i++) {
            if (level.tiles[i] == tile) {
                c.sendTileUse(i % level.width, i / level.width);
                return true;
            }
        }
        return true;
    }

    public static boolean changeLevel(Stairs stairs) {
        MpClient c = playing();
        if (c == null) return false;
        c.sendStairs(stairs == null || stairs.direction == Stairs.StairDirection.down);
        return true;
    }

    public static boolean levelTrigger(Entity instigator, String triggersId, String value) {
        MpClient c = playing();
        if (c == null) return false;
        if (!c.replayingTrigger && triggersId != null && !triggersId.isEmpty()) c.sendTrigger(instigator, triggersId, value);
        return true;
    }

    public static boolean gameOverScreen(boolean escaped) {
        MpClient c = MpClient.current;
        if (c == null || !c.gameStarted) return false;
        if (escaped ? c.allowWin : c.allowGameOver) return false; // the server ended the run: show the native screen
        // only the server ends a multiplayer run: tell it (e.g. the ending dialogue finished) and wait for its verdict
        if (c.inGame()) c.sendGameEnd(escaped);
        return true;
    }

    public static boolean gameStart(Game game) {
        MpClient c = MpClient.current;
        if (c == null || c.gameStarted) return false;
        return c.setupGame(game);
    }

    public static boolean onGameOver(Game game) {
        MpClient c = MpClient.current;
        if (c == null || !c.gameStarted) return false;
        if (c.allowGameOver) return false;
        // dead but the party lives on: watch the others until revived
        c.startSpectating(game.player);
        return true;
    }

    public static boolean levelTick(Level level, float delta) {
        MpClient c = playing();
        if (c == null) return false;
        Player p = Game.instance.player;
        c.repl.tick(level, delta, p);
        c.updateSpectator(p);
        return true;
    }

    public static void playerDied(Player player) {
        MpClient c = playing();
        if (c != null && Game.instance != null && player == Game.instance.player) c.onLocalDeath(player);
    }

    public static void playerAttacked(Player player) {
        MpClient c = playing();
        if (c != null && Game.instance != null && player == Game.instance.player) c.attackCount++;
    }

    public static boolean skipPlayerTick(Player player) {
        MpClient c = playing();
        return c != null && c.isSpectating() && Game.instance != null && player == Game.instance.player;
    }

    /** Vanilla's 1/3-frame tick accumulator (private float GameManager.time_since_last_tick). */
    private static Field tickTimer;

    /**
     * Replaces GameManager.tick while an overlay is open or the local player is spectating. The shared world never
     * pauses in multiplayer, so this method keeps running under overlays, which never happens in single player:
     * with Escape still held on the frame after the pause menu opened, vanilla's Escape handling popped it again at
     * once. Here an open overlay handles Escape itself (as in single player), and a spectator, whom vanilla treats as
     * dead and denies the pause menu, can open it to leave the game.
     */
    public static boolean gameManagerTick(GameManager manager, float delta) {
        if (!inMultiplayerGame()) return false;
        Game game = GameManager.getGame();
        if (game == null || game.player == null) return false;
        OverlayManager overlays = OverlayManager.instance;
        boolean overlayOpen = overlays != null && overlays.current() != null;
        if (!overlayOpen && !game.player.isDead) return false; // vanilla behaves exactly as in single player
        try {
            if (tickTimer == null) {
                Field f = GameManager.class.getDeclaredField("time_since_last_tick");
                f.setAccessible(true);
                tickTimer = f;
            }
            float elapsed = tickTimer.getFloat(manager) + delta;
            if (elapsed < 0.3333333f) {
                tickTimer.setFloat(manager, elapsed);
                return true;
            }
            tickTimer.setFloat(manager, 0f);
            GameManager.renderer.clearLights();
            if (!overlayOpen && overlays != null) {
                boolean pause = Gdx.input.isKeyPressed(Input.Keys.ESCAPE)
                        || GameApplication.instance.input.gamepadManager.controllerState.buttonEvents.contains(Actions.Action.PAUSE, true);
                if (!pause) Game.ignoreEscape = false;
                else if (!Game.ignoreEscape) overlays.push(new PauseOverlay()); // push sets ignoreEscape until released
            }
            if (manager.running) game.tick(elapsed);
            SteamApi.api.runCallbacks();
        } catch (Exception e) {
            // same failure handling as the vanilla method
            Logger.logExceptionToFile(e);
            Gdx.app.exit();
        }
        return true;
    }

    public static void frameStart() {
        MpClient c = MpClient.current;
        if (c == null) return;
        try {
            c.update();
        } catch (RuntimeException e) {
            Mp.log("network update failed: " + e);
            e.printStackTrace();
        }
    }

    public static void frameEnd() {
        if (FPS_LOG) logFps();
        MpClient c = playing();
        if (c == null || Mp.headless || GameApplication.instance == null) return;
        if (!(GameApplication.instance.getScreen() instanceof GameScreen)) return;
        c.nameTags.draw(c.avatars.values());
        c.chat.draw();
    }

    /** -Ddelveronline.fpslog=true: log in-game frames per second every 10 s (single player and multiplayer alike). */
    private static final boolean FPS_LOG = Boolean.getBoolean("delveronline.fpslog");
    private static long fpsWindowStart;
    private static int fpsFrames;

    private static void logFps() {
        boolean inGame = GameApplication.instance != null && GameApplication.instance.getScreen() instanceof GameScreen;
        long now = System.nanoTime();
        if (!inGame) {
            fpsWindowStart = 0;
            return;
        }
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
            fpsFrames = 0;
            return;
        }
        fpsFrames++;
        if (now - fpsWindowStart >= 10_000_000_000L) {
            double fps = fpsFrames * 1e9 / (now - fpsWindowStart);
            String level = Game.instance != null && Game.instance.level != null ? String.valueOf(Game.instance.level.levelName) : "?";
            Mp.log(String.format(java.util.Locale.ROOT, "EVENT fps %.2f mode=%s level=%s", fps,
                    inMultiplayerGame() ? "multiplayer" : "singleplayer", level));
            fpsWindowStart = now;
            fpsFrames = 0;
        }
    }

    public static boolean keyDown(int keycode) {
        MpClient c = playing();
        if (c == null) return false;
        ChatOverlay chat = c.chat;
        if (chat.isOpen()) {
            if (keycode == Input.Keys.ESCAPE) Game.ignoreEscape = true;
            String msg = chat.keyDown(keycode);
            if (msg != null) c.sendChat(msg);
            return true;
        }
        if (keycode == ChatOverlay.chatKey() && !overlayCapturesInput()) {
            chat.open();
            if (GameApplication.instance != null) Arrays.fill(GameApplication.instance.input.keysDown, false);
            return true;
        }
        return false;
    }

    public static boolean keyUp(int keycode) {
        return false; // releases always reach the game so no key stays stuck down
    }

    public static boolean keyTyped(char ch) {
        MpClient c = playing();
        if (c == null || !c.chat.isOpen()) return false;
        c.chat.keyTyped(ch);
        return true;
    }

    private static boolean overlayCapturesInput() {
        OverlayManager om = OverlayManager.instance;
        return om != null && om.current() != null && om.current().catchInput;
    }

    public static void mainMenuContent(Object screen) {
        MpClient c = MpClient.current;
        if (c != null && c.gameStarted) {
            // back at the menu after a multiplayer game: leave the server and restore single-player behaviour
            c.disconnect("Left the game");
            c.finishSession();
        }
        MultiplayerMenu.install(screen);
    }
}
