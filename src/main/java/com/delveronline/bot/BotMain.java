package com.delveronline.bot;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.delveronline.Mp;
import com.delveronline.agent.DelverOnlineAgent;
import com.delveronline.client.ClientHooks;
import com.delveronline.client.MpClient;
import com.delveronline.headless.HeadlessBoot;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.input.Actions;
import com.interrupt.dungeoneer.input.ControllerState;
import com.interrupt.dungeoneer.input.GamepadManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Headless test client: the real Delver client (GameApplication, Game, Player, renderer on a no-op GL) driven by a
 * simple AI instead of a keyboard. Used by the automated soak and scenario tests.
 *
 * <p>stdin commands: {@code chat <text>}, {@code wander on|off}, {@code stairs down|up}, {@code quit}.
 * Notable happenings are printed as {@code [DelverOnline] EVENT ...} lines.
 */
public final class BotMain {
    private BotMain() {}

    public static void main(String[] args) {
        if (!DelverOnlineAgent.isInstalled()) {
            System.err.println("run with -javaagent:delveronline.jar");
            System.exit(2);
        }
        String host = "127.0.0.1";
        int port = Mp.DEFAULT_PORT;
        String name = "Bot";
        boolean singleplayer = false;
        long seed = System.nanoTime();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host": host = args[++i]; break;
                case "--port": port = Integer.parseInt(args[++i]); break;
                case "--name": name = args[++i]; break;
                case "--singleplayer": singleplayer = true; break;
                case "--seed": seed = Long.parseLong(args[++i]); break;
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        System.setProperty("delveronline.telemetry", "true");
        HeadlessApplicationConfiguration cfg = new HeadlessApplicationConfiguration();
        cfg.renderInterval = 1f / 60f;
        new HeadlessApplication(new BotApp(host, port, name, singleplayer, seed), cfg);
    }

    static final class BotApp extends GameApplication {
        final String host;
        final int port;
        final String name;
        final boolean singleplayer;
        final Random rnd;
        final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
        boolean wander = true;
        int swingFrames;
        boolean connected;
        float turnTimer, attackTimer, useTimer, jumpTimer;
        float turnRate;
        long frames;
        long fpsWindowStart = System.currentTimeMillis();
        long fpsWindowFrames;

        BotApp(String host, int port, String name, boolean singleplayer, long seed) {
            this.host = host;
            this.port = port;
            this.name = name;
            this.singleplayer = singleplayer;
            this.rnd = new Random(seed);
        }

        @Override
        public void create() {
            HeadlessBoot.installNullGl();
            Options.loadOptions();
            Options.instance.enableMusic = false;
            super.create();
            try {
                Game.init();
            } catch (Throwable t) {
                Game.gamepadManager = new GamepadManager(new ControllerState());
                if (Game.gameData == null) Game.gameData = Game.getModManager().loadGameData();
            }
            if (input.gamepadManager == null) input.setGamepadManager(Game.gamepadManager);
            Thread t = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) commands.add(line.trim());
                } catch (Exception ignored) {
                }
            }, "bot-stdin");
            t.setDaemon(true);
            t.start();
            if (singleplayer) {
                GameApplication.SetSaveLocation(2);
                mainScreen.didStart = false;
                GameApplication.ShowMainScreen();
                event("singleplayer started");
            } else {
                MpClient.connect(host, port, name);
            }
        }

        @Override
        public void render() {
            String cmd;
            while ((cmd = commands.poll()) != null) command(cmd);
            Game game = Game.instance;
            if (!singleplayer) {
                MpClient c = MpClient.current();
                if (c == null && connected) {
                    event("disconnected");
                    Gdx.app.exit();
                    System.exit(0);
                }
                if (c != null && c.inGame()) connected = true;
            }
            if (game != null && game.player != null && game.level != null && getScreen() == mainScreen) {
                drive(game.player);
                if (swingFrames > 0) {
                    // hold attack long enough for a charged swing, then release
                    swingFrames--;
                    press(Actions.Action.ATTACK, swingFrames > 5);
                }
            }
            super.render();
            frames++;
            fpsWindowFrames++;
            long now = System.currentTimeMillis();
            if (now - fpsWindowStart >= 10_000) {
                event(String.format(java.util.Locale.ROOT, "fps %.2f", fpsWindowFrames * 1000.0 / (now - fpsWindowStart)));
                fpsWindowStart = now;
                fpsWindowFrames = 0;
            }
        }

        private void command(String cmd) {
            if (cmd.isEmpty()) return;
            String[] p = cmd.split("\\s+", 2);
            switch (p[0]) {
                case "chat":
                    MpClient c = MpClient.current();
                    if (c != null && p.length > 1) c.sendChat(p[1]);
                    break;
                case "wander":
                    wander = p.length > 1 && p[1].equals("on");
                    if (!wander) release();
                    break;
                case "escape": {
                    // leave the dungeon the way a player would: Delver's exit tile, else the up stairs
                    if (!ClientHooks.inMultiplayerGame() || Game.instance == null || Game.instance.level == null) break;
                    com.interrupt.dungeoneer.game.Level lv = Game.instance.level;
                    Player pl = Game.instance.player;
                    boolean used = false;
                    for (int i = 0; i < lv.tiles.length && !used; i++) {
                        if (!(lv.tiles[i] instanceof com.interrupt.dungeoneer.tiles.ExitTile)) continue;
                        int tx = i % lv.width, ty = i / lv.width;
                        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                        for (int[] dd : dirs) {
                            com.interrupt.dungeoneer.tiles.Tile n = lv.getTileOrNull(tx + dd[0], ty + dd[1]);
                            if (n != null && !n.blockMotion) {
                                pl.x = tx + dd[0] + 0.5f;
                                pl.y = ty + dd[1] + 0.5f;
                                pl.z = n.getFloorHeight() + 0.5f;
                                break;
                            }
                        }
                        lv.tiles[i].use();
                        used = true;
                        event("escape via exit tile");
                    }
                    if (!used && lv.up != null) {
                        pl.x = lv.up.x;
                        pl.y = lv.up.y;
                        Game.instance.changeLevel(lv.up);
                        event("escape via stairs");
                    } else if (!used) {
                        event("escape impossible: no exit");
                    }
                    break;
                }
                case "stairs":
                    if (ClientHooks.inMultiplayerGame() && Game.instance != null) {
                        Game.instance.changeLevel(stairs(p.length > 1 && p[1].equals("up")));
                    }
                    break;
                case "tp": {
                    Player pl = Game.instance == null ? null : Game.instance.player;
                    String[] xyz = p.length > 1 ? p[1].split("\\s+") : new String[0];
                    if (pl == null || xyz.length < 3) break;
                    pl.x = Float.parseFloat(xyz[0]);
                    pl.y = Float.parseFloat(xyz[1]);
                    pl.z = Float.parseFloat(xyz[2]);
                    if (xyz.length > 3) pl.rot = Float.parseFloat(xyz[3]);
                    pl.xa = pl.ya = pl.za = 0f;
                    event(String.format(java.util.Locale.ROOT, "pos %.2f %.2f %.2f %.2f", pl.x, pl.y, pl.z, pl.rot));
                    break;
                }
                case "others": {
                    MpClient mc = MpClient.current();
                    if (mc != null) event("others " + mc.describeRemotePlayers());
                    break;
                }
                case "chatlines": {
                    MpClient mc = MpClient.current();
                    if (mc != null) event("chatlines " + mc.chat().visibleLineCount());
                    break;
                }
                case "swing":
                    swingFrames = 30;
                    break;
                case "pos": {
                    Player pl = Game.instance == null ? null : Game.instance.player;
                    if (pl != null) event(String.format(java.util.Locale.ROOT, "pos %.2f %.2f %.2f %.2f", pl.x, pl.y, pl.z, pl.rot));
                    break;
                }
                case "equip": {
                    // wield the first inventory item of the given class (e.g. Wand, Sword)
                    Player pl = Game.instance == null ? null : Game.instance.player;
                    if (pl == null || p.length < 2) break;
                    for (int i = 0; i < pl.inventory.size; i++) {
                        com.interrupt.dungeoneer.entities.Item it = pl.inventory.get(i);
                        if (it != null && isA(it, p[1])) {
                            pl.equip(it);
                            event("equipped " + it.getClass().getSimpleName());
                            break;
                        }
                    }
                    break;
                }
                case "interact":
                    interact(p.length > 1 ? p[1] : "");
                    break;
                case "inv": {
                    Player pl = Game.instance == null ? null : Game.instance.player;
                    if (pl == null) break;
                    StringBuilder sb = new StringBuilder();
                    int n = 0;
                    for (com.interrupt.dungeoneer.entities.Item i : pl.inventory) {
                        if (i == null) continue;
                        n++;
                        sb.append(i.getClass().getSimpleName()).append(' ');
                    }
                    event("inv " + n + " gold " + pl.gold + " keys " + pl.keys + " hp " + pl.hp + " : " + sb.toString().trim());
                    break;
                }
                case "throw": {
                    Player pl = Game.instance == null ? null : Game.instance.player;
                    if (pl == null) break;
                    for (int i = 0; i < pl.inventory.size; i++) {
                        com.interrupt.dungeoneer.entities.Item it = pl.inventory.get(i);
                        if (it == null) continue;
                        pl.dropItem(it, Game.instance.level, 0.5f);
                        event("threw " + it.getClass().getSimpleName());
                        break;
                    }
                    break;
                }
                case "quit":
                    MpClient cur = MpClient.current();
                    if (cur != null) cur.disconnect("bot quit");
                    Gdx.app.exit();
                    System.exit(0);
                    break;
                default:
                    event("unknown command " + cmd);
            }
        }

        /**
         * {@code interact <Class> <use|hit|report>}: walks (teleports) next to the nearest replicated entity whose class
         * or superclass has that simple name and uses/hits it through the normal client code path.
         */
        private void interact(String args) {
            String[] a = args.split("\\s+");
            if (a.length < 2 || Game.instance == null || Game.instance.level == null) {
                event("interact usage: interact <Class> <use|hit|report>");
                return;
            }
            MpClient c = MpClient.current();
            Player pl = Game.instance.player;
            com.interrupt.dungeoneer.game.Level level = Game.instance.level;
            com.interrupt.dungeoneer.entities.Entity best = null;
            float bestD = Float.MAX_VALUE;
            for (com.badlogic.gdx.utils.Array<com.interrupt.dungeoneer.entities.Entity> list :
                    java.util.Arrays.asList(level.entities, level.non_collidable_entities, level.static_entities)) {
                for (com.interrupt.dungeoneer.entities.Entity e : list) {
                    if (c == null || c.netIdOf(e) == 0 || !e.isActive || !isA(e, a[0])) continue;
                    float d = (e.x - pl.x) * (e.x - pl.x) + (e.y - pl.y) * (e.y - pl.y);
                    if (d < bestD) {
                        bestD = d;
                        best = e;
                    }
                }
            }
            if (a[0].matches("\\d+") && c != null) best = c.entityById(Integer.parseInt(a[0]));

            if (best == null) {
                event("interact none " + a[0]);
                return;
            }
            int id = c.netIdOf(best);
            if (!a[1].equals("report")) {
                // stand next to it on its tile, facing it
                pl.x = best.x + 0.45f;
                pl.y = best.y;
                pl.z = best.z;
                pl.rot = (float) Math.atan2(best.x - pl.x, best.y - pl.y);
            }
            float projx = (float) Math.sin(pl.rot), projy = (float) Math.cos(pl.rot);
            switch (a[1]) {
                case "use":
                    best.use(pl, projx, projy);
                    break;
                case "hit":
                    best.hit(projx, projy, 2, 0.05f, com.interrupt.dungeoneer.entities.items.Weapon.DamageType.PHYSICAL, pl);
                    break;
                default:
                    break;
            }
            event("interact " + a[1] + " " + best.getClass().getSimpleName() + " " + id + " " + describe(best));
        }

        private static boolean isA(Object o, String simpleName) {
            for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
                if (k.getSimpleName().equals(simpleName)) return true;
            }
            return false;
        }

        static String describe(com.interrupt.dungeoneer.entities.Entity e) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(java.util.Locale.ROOT, "at %.2f,%.2f active=%s", e.x, e.y, e.isActive));
            if (e instanceof com.interrupt.dungeoneer.entities.Actor) sb.append(" hp=").append(((com.interrupt.dungeoneer.entities.Actor) e).hp);
            if (e instanceof com.interrupt.dungeoneer.entities.Door) sb.append(" door=").append(((com.interrupt.dungeoneer.entities.Door) e).doorState);
            if (e instanceof com.interrupt.dungeoneer.entities.Stairs) sb.append(" dir=").append(((com.interrupt.dungeoneer.entities.Stairs) e).direction);
            return sb.toString();
        }

        private static com.interrupt.dungeoneer.entities.Stairs stairs(boolean up) {
            com.interrupt.dungeoneer.entities.Stairs s = new com.interrupt.dungeoneer.entities.Stairs();
            s.direction = up ? com.interrupt.dungeoneer.entities.Stairs.StairDirection.up : com.interrupt.dungeoneer.entities.Stairs.StairDirection.down;
            return s;
        }

        private void press(Actions.Action a, boolean down) {
            Integer key = Actions.keyBindings.get(a);
            if (key != null && key > 0 && key < input.keysDown.length) input.keysDown[key] = down;
        }

        private void release() {
            java.util.Arrays.fill(input.keysDown, false);
        }

        /** A wandering adventurer: walks, turns away from walls, swings at things, opens doors. */
        private void drive(Player p) {
            if (!wander || p.isDead) {
                release();
                return;
            }
            float dt = Gdx.graphics.getDeltaTime() * 60f;
            press(Actions.Action.FORWARD, true);
            turnTimer -= dt;
            float speed = (float) Math.sqrt(p.xa * p.xa + p.ya * p.ya);
            if (turnTimer <= 0f || (speed < 0.003f && rnd.nextFloat() < 0.05f)) {
                turnTimer = 40f + rnd.nextFloat() * 120f;
                turnRate = (rnd.nextFloat() - 0.5f) * 0.08f;
                if (speed < 0.003f) p.rot += (float) Math.PI * (0.5f + rnd.nextFloat());
            }
            p.rot += turnRate * dt;
            attackTimer -= dt;
            press(Actions.Action.ATTACK, attackTimer > 0f && attackTimer < 20f);
            if (attackTimer <= -30f) attackTimer = 60f + rnd.nextFloat() * 120f;
            useTimer -= dt;
            if (useTimer <= 0f) {
                useTimer = 90f + rnd.nextFloat() * 90f;
                press(Actions.Action.USE, true);
                if (Game.instance != null) p.Use(Game.instance.level);
            } else {
                press(Actions.Action.USE, false);
            }
        }

        static void event(String s) {
            System.out.println("[DelverOnline] EVENT " + s);
        }
    }
}
