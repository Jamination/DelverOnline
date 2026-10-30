package com.interrupt.dungeoneer;

import com.interrupt.dungeoneer.game.Game;

/** Sets GameManager's protected current-game reference (split package on the classpath). */
public final class DelverOnlineManagerAccess {
    private DelverOnlineManagerAccess() {}

    public static void setGame(Game game) {
        GameManager.game = game;
    }
}
