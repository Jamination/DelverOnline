package com.delveronline;

/** Global multiplayer mode. Hooks consult this to decide whether to alter vanilla behaviour. */
public final class Mp {
    public enum Mode { OFF, CLIENT, SERVER }

    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_PORT = 27960;
    public static final int MAX_PLAYERS = 8;

    private static volatile Mode mode = Mode.OFF;
    /** True when running without a display (dedicated server or headless bot client). */
    public static volatile boolean headless;

    private Mp() {}

    public static Mode mode() {
        return mode;
    }

    public static void setMode(Mode m) {
        mode = m;
    }

    public static boolean isServer() {
        return mode == Mode.SERVER;
    }

    public static boolean isClient() {
        return mode == Mode.CLIENT;
    }

    public static boolean isOff() {
        return mode == Mode.OFF;
    }

    public static void log(String msg) {
        System.out.println("[DelverOnline] " + msg);
    }
}
