package com.delveronline.headless;

import com.badlogic.gdx.Gdx;
import com.delveronline.Mp;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.game.DelverOnlineAccess;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;
import com.interrupt.dungeoneer.game.Progression;
import com.interrupt.dungeoneer.gfx.GlRenderer;
import com.interrupt.managers.StringManager;
import org.objenesis.ObjenesisStd;

/** Brings up Delver's runtime (data, managers, renderer state) without a window, for the dedicated server. */
public final class HeadlessBoot {
    private HeadlessBoot() {}

    /** Installs the no-op GL and a sized screen; call from inside a libGDX headless application's create(). */
    public static void installNullGl() {
        Mp.headless = true;
        Gdx.gl = Gdx.gl20 = NullGL.create();
        HeadlessGraphics.install(640, 360);
    }

    /** Loads options, strings, mods, game data and a renderer with collision support; no audio, no UI. */
    public static void initDelverRuntime() {
        installNullGl();
        Options.loadOptions();
        Options.instance.graphicsDetailLevel = 4; // keep every entity: clients decide what to draw
        Options.instance.enableMusic = false;
        StringManager.init();
        Game.getModManager();
        GameManager.renderer = new GlRenderer();
        GameManager.renderer.init();
        GameManager.renderer.setSize(320, 240);
        // a camera nobody looks through: particle emitters and other view-dependent effects stay idle
        GameManager.renderer.camera.position.set(-100000f, -100000f, -100000f);
        GameManager.renderer.camera.update();
        Game.camera = GameManager.renderer.camera;
        Game.gameData = Game.modManager.loadGameData();
        Game.isMobile = false;
    }

    /** A Game without the single-player constructor (which loads saves and builds a HUD). */
    public static Game createBareGame() {
        Game game = new ObjenesisStd().newInstance(Game.class);
        game.menuMode = Game.MenuMode.Hidden;
        DelverOnlineAccess.setTimeScale(game, 1f);
        DelverOnlineAccess.setSaveLoc(game, 0);
        Game.instance = game;
        com.interrupt.dungeoneer.DelverOnlineManagerAccess.setGame(game);
        game.loadManagers();
        game.progression = new Progression();
        return game;
    }
}
