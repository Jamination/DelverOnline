package com.delveronline.hooks;

import com.badlogic.gdx.math.Vector3;
import com.delveronline.Mp;
import com.delveronline.client.ClientHooks;
import com.delveronline.server.ServerHooks;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.tiles.Tile;

/**
 * Static entry points called from the woven advice. Every method returns quickly when multiplayer is off, so the
 * vanilla single-player game behaves exactly as before. Returning true skips the original method.
 */
public final class Hooks {
    private Hooks() {}

    public static void modsFound(Object modManager) {
        com.delveronline.mods.ModDiscovery.afterFindMods((com.interrupt.dungeoneer.game.ModManager) modManager);
    }

    public static boolean headlessSkip() {
        return Mp.headless;
    }

    public static boolean sound(String file, float volume, float pitch) {
        if (Mp.isServer()) {
            ServerHooks.sound(file, null, volume, pitch, 0f);
            return true;
        }
        return Mp.headless;
    }

    public static boolean positionedSound(String file, Vector3 pos, float volume, float pitch, float range) {
        if (Mp.isServer()) {
            ServerHooks.sound(file, pos, volume, pitch, range);
            return true;
        }
        return Mp.headless;
    }

    public static boolean showMessage(String msg, float seconds, float scale) {
        if (Mp.isServer()) {
            ServerHooks.showMessage(msg, seconds, scale);
            return true;
        }
        return false;
    }

    public static boolean pathfindingTick() {
        return Mp.isServer();
    }

    public static boolean overlayPush(Object overlay) {
        if (Mp.isServer()) return ServerHooks.overlay(overlay);
        return false;
    }

    public static boolean neverPause() {
        return Mp.isClient() && ClientHooks.inMultiplayerGame();
    }

    public static boolean gameManagerTick(Object manager, float delta) {
        return Mp.isClient() && ClientHooks.gameManagerTick((com.interrupt.dungeoneer.GameManager) manager, delta);
    }

    public static boolean itemPickup(Item item, Player player) {
        if (Mp.isServer()) return ServerHooks.itemPickup(item, player);
        if (Mp.isClient()) return ClientHooks.itemPickup(item, player);
        return false;
    }

    public static boolean entityHit(Entity self, float projx, float projy, int damage, float knockback,
                                    Weapon.DamageType damageType, Entity instigator) {
        if (Mp.isClient()) return ClientHooks.entityHit(self, projx, projy, damage, knockback, damageType, instigator);
        return false;
    }

    public static boolean entityUse(Entity self, Player player, float projx, float projy) {
        if (Mp.isClient()) return ClientHooks.entityUse(self, player, projx, projy);
        return false;
    }

    public static boolean entityTouchedByLocalPlayer(Entity self, Player player) {
        if (Mp.isClient()) return ClientHooks.isReplicated(self);
        return false;
    }

    public static boolean tileUse(Tile tile) {
        if (Mp.isClient()) return ClientHooks.tileUse(tile);
        return false;
    }

    public static boolean changeLevel(Game game, Stairs stairs) {
        if (Mp.isServer()) return ServerHooks.changeLevel(stairs);
        if (Mp.isClient()) return ClientHooks.changeLevel(stairs);
        return false;
    }

    public static boolean warpToLevel(Game game, TriggeredWarp warp) {
        if (Mp.isServer()) return ServerHooks.warpToLevel(warp);
        return Mp.isClient() && ClientHooks.inMultiplayerGame();
    }

    public static boolean levelExit(Game game, TriggeredWarp warp) {
        if (Mp.isServer()) return ServerHooks.levelExit(warp);
        return Mp.isClient() && ClientHooks.inMultiplayerGame();
    }

    public static boolean gameOverScreen(boolean escaped) {
        if (Mp.isServer()) return ServerHooks.gameOverScreen(escaped);
        if (Mp.isClient()) return ClientHooks.gameOverScreen(escaped);
        return false;
    }

    public static boolean levelChangeScreenWarp(TriggeredWarp warp) {
        if (Mp.isServer()) return ServerHooks.warpToLevel(warp);
        return Mp.isClient() && ClientHooks.inMultiplayerGame();
    }

    public static boolean gameStart(Game game) {
        if (Mp.isClient()) return ClientHooks.gameStart(game);
        return false;
    }

    public static boolean skipSave() {
        return Mp.isClient() && ClientHooks.inMultiplayerGame();
    }

    public static boolean onGameOver(Game game) {
        if (Mp.isClient()) return ClientHooks.onGameOver(game);
        return false;
    }

    public static boolean levelTrigger(Entity instigator, String triggersId, String value) {
        if (Mp.isClient()) return ClientHooks.levelTrigger(instigator, triggersId, value);
        return false;
    }

    public static boolean levelTick(Level level, float delta) {
        if (Mp.isClient()) return ClientHooks.levelTick(level, delta);
        return false;
    }

    public static void playerAttacked(Player player) {
        if (Mp.isClient()) ClientHooks.playerAttacked(player);
    }

    public static void playerDied(Player player) {
        if (Mp.isClient()) ClientHooks.playerDied(player);
    }

    public static boolean skipPlayerTick(Player player) {
        if (Mp.isClient()) return ClientHooks.skipPlayerTick(player);
        return false;
    }

    public static void frameStart() {
        ClientHooks.frameStart();
    }

    public static void frameEnd() {
        ClientHooks.frameEnd();
    }

    public static boolean keyDown(int keycode) {
        return ClientHooks.keyDown(keycode);
    }

    public static boolean keyUp(int keycode) {
        return ClientHooks.keyUp(keycode);
    }

    public static boolean keyTyped(char c) {
        return ClientHooks.keyTyped(c);
    }

    public static void mainMenuContent(Object screen) {
        ClientHooks.mainMenuContent(screen);
    }
}
