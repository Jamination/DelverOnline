package com.delveronline.hooks;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.List;

import static net.bytebuddy.matcher.ElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Every method Delver Online weaves advice into. Each entry maps to a nested class of {@link Advices}. */
public final class HookRegistry {
    public static final class Hook {
        public final ElementMatcher<? super TypeDescription> type;
        public final ElementMatcher<? super MethodDescription> method;
        public final String adviceClass;

        Hook(ElementMatcher<? super TypeDescription> type, ElementMatcher<? super MethodDescription> method, String advice) {
            this.type = type;
            this.method = method;
            this.adviceClass = Advices.class.getName() + "$" + advice;
        }
    }

    private static final String D = "com.interrupt.dungeoneer.";
    private static final String ENTITY = D + "entities.Entity";
    private static final String PLAYER = D + "entities.Player";
    private static final String ITEM = D + "entities.Item";
    private static final String GAME = D + "game.Game";
    private static final String LEVEL = D + "game.Level";
    private static final String AUDIO = D + "Audio";

    private HookRegistry() {}

    private static ElementMatcher.Junction<TypeDescription> type(String name) {
        return named(name);
    }

    private static ElementMatcher.Junction<TypeDescription> typeOrSub(String name) {
        return named(name).or(hasSuperType(named(name)));
    }

    public static List<Hook> hooks() {
        List<Hook> h = new ArrayList<>();
        // --- mods: same list, same order on server and clients
        h.add(new Hook(type(D + "game.ModManager"), named("findMods"), "ModsFound"));
        // --- headless / server environment
        h.add(new Hook(type(D + "gfx.GlRenderer"), named("makeMapTextureForLevel"), "SkipWhenHeadless"));
        h.add(new Hook(type(AUDIO), named("playSound").and(takesArguments(String.class, float.class)), "Sound2"));
        h.add(new Hook(type(AUDIO), named("playSound").and(takesArguments(String.class, float.class, float.class)), "Sound3"));
        h.add(new Hook(type(AUDIO), named("playPositionedSound").and(takesArguments(4)), "PosSound4"));
        h.add(new Hook(type(AUDIO), named("playPositionedSound").and(takesArguments(5)), "PosSound5"));
        h.add(new Hook(type(AUDIO), named("playMusic").or(named("playAmbientSound")).or(named("playNextTrack")), "SkipWhenHeadless"));
        h.add(new Hook(type(GAME), named("ShowMessage").and(takesArguments(String.class, float.class)), "ShowMessage2"));
        h.add(new Hook(type(GAME), named("ShowMessage").and(takesArguments(String.class, float.class, float.class)), "ShowMessage3"));
        h.add(new Hook(type(D + "game.Pathfinding"), named("tick"), "PathfindingTick"));
        h.add(new Hook(type(D + "overlays.OverlayManager"), named("push").and(takesArguments(1)), "OverlayPush"));
        h.add(new Hook(type(D + "overlays.OverlayManager"), named("replace").and(takesArguments(2)), "OverlayReplace"));
        h.add(new Hook(type(D + "overlays.OverlayManager"), named("shouldPauseGame"), "ShouldPauseGame"));

        // --- world interactions
        h.add(new Hook(typeOrSub(ITEM), named("pickup").and(takesArguments(1)).and(not(isAbstract())), "ItemPickup"));
        h.add(new Hook(typeOrSub(ENTITY), named("hit").and(takesArguments(6)).and(not(isAbstract())), "EntityHit"));
        h.add(new Hook(typeOrSub(ENTITY), named("use").and(takesArguments(3)).and(takesArgument(0, named(PLAYER))), "EntityUse"));
        h.add(new Hook(typeOrSub(ENTITY), named("encroached").and(takesArguments(1)).and(takesArgument(0, named(PLAYER))), "EntityEncroached"));
        h.add(new Hook(typeOrSub(ENTITY), named("push").and(takesArguments(4)), "EntityPush"));
        h.add(new Hook(typeOrSub(D + "tiles.Tile"), named("use").and(takesArguments(0)), "TileUse"));

        // --- level flow
        h.add(new Hook(type(GAME), named("changeLevel").and(takesArguments(1)), "ChangeLevel"));
        h.add(new Hook(type(GAME), named("warpToLevel"), "WarpToLevel"));
        h.add(new Hook(type(GAME), named("doLevelExit"), "LevelExit"));
        h.add(new Hook(type(D + "GameApplication"), named("ShowGameOverScreen"), "GameOverScreen"));
        h.add(new Hook(type(D + "GameApplication"), named("ShowLevelChangeScreen").and(takesArgument(0, named(D + "entities.triggers.TriggeredWarp"))), "LevelChangeScreenWarp"));
        h.add(new Hook(type(GAME), named("Start").and(takesArguments(int.class)), "GameStart"));
        h.add(new Hook(type(GAME), named("save"), "GameSave"));
        h.add(new Hook(type(GAME), named("saveProgression"), "GameSave"));
        h.add(new Hook(type(GAME), named("OnGameOver"), "OnGameOver"));
        h.add(new Hook(type(LEVEL), named("trigger").and(takesArguments(3)), "LevelTrigger"));
        h.add(new Hook(type(LEVEL), named("tick").and(takesArguments(float.class)), "LevelTick"));

        // --- player
        h.add(new Hook(type(PLAYER), named("die").and(takesArguments(0)), "PlayerDie"));
        h.add(new Hook(type(PLAYER), named("tick").and(takesArguments(3)), "PlayerTick"));
        h.add(new Hook(type(PLAYER), named("playAttackAnimation").and(takesArguments(3)), "PlayerAttack"));

        // --- frame, input and UI
        h.add(new Hook(type("com.badlogic.gdx.Game"), named("render").and(takesArguments(0)), "AppRender"));
        h.add(new Hook(type(D + "GameManager"), named("tick").and(takesArguments(float.class)), "GameManagerTick"));
        h.add(new Hook(type(D + "GameInput"), named("keyDown"), "KeyDown"));
        h.add(new Hook(type(D + "GameInput"), named("keyUp"), "KeyUp"));
        h.add(new Hook(type(D + "GameInput"), named("keyTyped"), "KeyTyped"));
        h.add(new Hook(type(D + "screens.MainMenuScreen"), named("makeContent"), "MainMenuContent"));
        return h;
    }
}
