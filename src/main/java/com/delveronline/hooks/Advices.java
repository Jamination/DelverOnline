package com.delveronline.hooks;

import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.tiles.Tile;
import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice inlined into Delver methods (see {@link HookRegistry}). Each advice is a thin trampoline into
 * {@link Hooks}; returning true from an enter advice skips the original method body.
 */
public final class Advices {
    private Advices() {}

    public static final class SkipWhenHeadless {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            return Hooks.headlessSkip();
        }
    }

    public static final class Sound2 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String file, @Advice.Argument(1) float volume) {
            return Hooks.sound(file, volume, 1f);
        }
    }

    public static final class Sound3 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String file, @Advice.Argument(1) float volume, @Advice.Argument(2) float pitch) {
            return Hooks.sound(file, volume, pitch);
        }
    }

    public static final class PosSound4 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String file, @Advice.Argument(1) Vector3 pos, @Advice.Argument(2) float volume,
                             @Advice.Argument(3) float range) {
            return Hooks.positionedSound(file, pos, volume, 1f, range);
        }
    }

    public static final class PosSound5 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String file, @Advice.Argument(1) Vector3 pos, @Advice.Argument(2) float volume,
                             @Advice.Argument(3) float pitch, @Advice.Argument(4) float range) {
            return Hooks.positionedSound(file, pos, volume, pitch, range);
        }
    }

    public static final class ShowMessage2 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String msg, @Advice.Argument(1) float seconds) {
            return Hooks.showMessage(msg, seconds, 1f);
        }
    }

    public static final class ShowMessage3 {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) String msg, @Advice.Argument(1) float seconds, @Advice.Argument(2) float scale) {
            return Hooks.showMessage(msg, seconds, scale);
        }
    }

    public static final class PathfindingTick {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            return Hooks.pathfindingTick();
        }
    }

    public static final class OverlayPush {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) Object overlay) {
            return Hooks.overlayPush(overlay);
        }
    }

    public static final class OverlayReplace {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(1) Object overlay) {
            return Hooks.overlayPush(overlay);
        }
    }

    public static final class ShouldPauseGame {
        /** Skipping returns the default {@code false}: the world never pauses in multiplayer. */
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            return Hooks.neverPause();
        }
    }

    public static final class ItemPickup {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Item item, @Advice.Argument(0) Player player) {
            return Hooks.itemPickup(item, player);
        }
    }

    public static final class EntityHit {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Entity self, @Advice.Argument(0) float projx, @Advice.Argument(1) float projy,
                             @Advice.Argument(2) int damage, @Advice.Argument(3) float knockback,
                             @Advice.Argument(4) Weapon.DamageType damageType, @Advice.Argument(5) Entity instigator) {
            return Hooks.entityHit(self, projx, projy, damage, knockback, damageType, instigator);
        }
    }

    public static final class EntityUse {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Entity self, @Advice.Argument(0) Player player, @Advice.Argument(1) float projx,
                             @Advice.Argument(2) float projy) {
            return Hooks.entityUse(self, player, projx, projy);
        }
    }

    public static final class EntityEncroached {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Entity self, @Advice.Argument(0) Player player) {
            return Hooks.entityTouchedByLocalPlayer(self, player);
        }
    }

    public static final class EntityPush {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Entity self, @Advice.Argument(0) Player player) {
            return Hooks.entityTouchedByLocalPlayer(self, player);
        }
    }

    public static final class TileUse {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Tile tile) {
            return Hooks.tileUse(tile);
        }
    }

    public static final class ChangeLevel {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Game game, @Advice.Argument(0) Stairs stairs) {
            return Hooks.changeLevel(game, stairs);
        }
    }

    public static final class WarpToLevel {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Game game, @Advice.Argument(1) TriggeredWarp warp) {
            return Hooks.warpToLevel(game, warp);
        }
    }

    public static final class LevelExit {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Game game, @Advice.Argument(0) TriggeredWarp warp) {
            return Hooks.levelExit(game, warp);
        }
    }

    public static final class GameOverScreen {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) boolean escaped) {
            return Hooks.gameOverScreen(escaped);
        }
    }

    public static final class LevelChangeScreenWarp {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) TriggeredWarp warp) {
            return Hooks.levelChangeScreenWarp(warp);
        }
    }

    public static final class GameStart {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Game game) {
            return Hooks.gameStart(game);
        }
    }

    public static final class GameSave {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter() {
            return Hooks.skipSave();
        }
    }

    public static final class OnGameOver {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Game game) {
            return Hooks.onGameOver(game);
        }
    }

    public static final class LevelTick {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Level level, @Advice.Argument(0) float delta) {
            return Hooks.levelTick(level, delta);
        }
    }

    public static final class PlayerDie {
        @Advice.OnMethodExit
        static void exit(@Advice.This Player player) {
            Hooks.playerDied(player);
        }
    }

    public static final class LevelTrigger {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) Entity instigator, @Advice.Argument(1) String triggersId,
                             @Advice.Argument(2) String value) {
            return Hooks.levelTrigger(instigator, triggersId, value);
        }
    }

    public static final class PlayerAttack {
        @Advice.OnMethodExit
        static void exit(@Advice.This Player player) {
            Hooks.playerAttacked(player);
        }
    }

    public static final class PlayerTick {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Player player) {
            return Hooks.skipPlayerTick(player);
        }
    }

    public static final class AppRender {
        @Advice.OnMethodEnter
        static void enter() {
            Hooks.frameStart();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        static void exit() {
            Hooks.frameEnd();
        }
    }

    public static final class GameManagerTick {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.This Object manager, @Advice.Argument(0) float delta) {
            return Hooks.gameManagerTick(manager, delta);
        }
    }

    public static final class KeyDown {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) int keycode) {
            return Hooks.keyDown(keycode);
        }
    }

    public static final class KeyUp {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) int keycode) {
            return Hooks.keyUp(keycode);
        }
    }

    public static final class KeyTyped {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        static boolean enter(@Advice.Argument(0) char c) {
            return Hooks.keyTyped(c);
        }
    }

    public static final class MainMenuContent {
        @Advice.OnMethodExit
        static void exit(@Advice.This Object screen) {
            Hooks.mainMenuContent(screen);
        }
    }
}
