package com.interrupt.dungeoneer.entities;

/** Reaches protected entity members from Delver Online (split package on the classpath). */
public final class DelverOnlineEntityAccess {
    private DelverOnlineEntityAccess() {}

    public static void pickup(Item item, Player player) {
        item.pickup(player);
    }
}
