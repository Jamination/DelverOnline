package com.interrupt.dungeoneer.game;

import com.interrupt.dungeoneer.entities.Player;

/**
 * Lives in Delver's own package (split package on the classpath) so Delver Online can reach protected members
 * without reflection.
 */
public final class DelverOnlineAccess {
    private DelverOnlineAccess() {}

    public static int getLevelNum(Game g) {
        return g.levelNum;
    }

    public static void setLevelNum(Game g, int n) {
        g.levelNum = n;
    }

    public static void setSaveLoc(Game g, int slot) {
        g.saveLoc = slot;
    }

    public static void setTimeScale(Game g, float scale) {
        g.gameTimeScale = scale;
    }

    public static void updateSeenTiles(Level level, Player player) {
        level.updateSeenTiles(player);
    }
}
