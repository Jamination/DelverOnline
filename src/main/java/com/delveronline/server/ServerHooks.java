package com.delveronline.server;

import com.badlogic.gdx.math.Vector3;
import com.delveronline.net.ByteWriter;
import com.delveronline.proto.Msg;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.Stairs;
import com.interrupt.dungeoneer.entities.triggers.Trigger;
import com.interrupt.dungeoneer.entities.triggers.TriggeredWarp;

/** Server-side implementations of the woven hooks. */
public final class ServerHooks {
    private ServerHooks() {}

    private static DelverServer server() {
        return DelverServer.INSTANCE;
    }

    public static void sound(String file, Vector3 pos, float volume, float pitch, float range) {
        DelverServer s = server();
        if (s == null || file == null || file.isEmpty() || volume < 0.005f) return;
        if (pos != null) {
            s.broadcastSound(file, pos.x, pos.y, pos.z, true, volume, pitch, range, null);
        } else {
            s.broadcastSound(file, 0, 0, 0, false, volume, pitch, 0, s.world.requester);
        }
    }

    public static void showMessage(String msg, float seconds, float scale) {
        DelverServer s = server();
        if (s == null || msg == null || msg.isEmpty()) return;
        if (s.world.requester != null) {
            s.world.requester.sendMessage(msg, seconds, scale);
        } else {
            s.broadcastMessage(msg, seconds, scale);
        }
    }

    /**
     * Overlays (shops, dialogue, message screens) are UI for one player. The trigger that opened it is replayed on
     * that player's client, which shows the overlay locally.
     */
    public static boolean overlay(Object overlay) {
        DelverServer s = server();
        if (s == null) return true;
        ServerWorld w = s.world;
        Entity e = w.currentEntity;
        ServerPlayer target = w.requester;
        if (target == null && e != null) {
            Player n = w.nearest(w.alivePlayers(), e.x, e.y);
            if (n instanceof PlayerProxy) target = ((PlayerProxy) n).owner;
        }
        if (target == null || !(e instanceof Trigger)) return true;
        int netId = w.replicator().netIdOf(e);
        if (netId == 0) return true;
        target.send(new ByteWriter(32).writeByte(Msg.S_LOCAL_TRIGGER).writeVarInt(w.levelSerial()).writeVarInt(netId)
                .writeString(((Trigger) e).triggerValue));
        return true;
    }

    public static boolean itemPickup(Item item, Player player) {
        DelverServer s = server();
        if (s == null) return true;
        if (player instanceof PlayerProxy && ((PlayerProxy) player).owner != null) {
            return s.offerPickup(item, (PlayerProxy) player);
        }
        return true; // nobody real: nothing happens
    }

    public static boolean changeLevel(Stairs stairs) {
        DelverServer s = server();
        return s == null || s.world.onStairs(stairs);
    }

    public static boolean warpToLevel(TriggeredWarp warp) {
        DelverServer s = server();
        return s == null || s.world.warp(warp);
    }

    public static boolean levelExit(TriggeredWarp warp) {
        DelverServer s = server();
        return s == null || s.world.levelExit(warp);
    }

    public static boolean gameOverScreen(boolean escaped) {
        DelverServer s = server();
        return s == null || s.world.gameOverScreen(escaped);
    }
}
