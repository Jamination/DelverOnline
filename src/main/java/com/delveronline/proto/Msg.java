package com.delveronline.proto;

/** Application message type ids (first byte of every message). */
public final class Msg {
    private Msg() {}

    // ---------------------------------------------------------------- client -> server
    /** reliable: protocolVersion, name */
    public static final int C_HELLO = 1;
    /** unreliable: player movement/presentation state, ~30 Hz */
    public static final int C_STATE = 2;
    /** reliable: kryo snapshot of inventory/equipment/keys/gold (for server-side checks and visuals) */
    public static final int C_INVENTORY = 3;
    /** reliable: netId, projx, projy, damage, knockback, damageType */
    public static final int C_HIT = 4;
    /** reliable: netId, projx, projy */
    public static final int C_USE = 5;
    /** reliable: kryo entity created by the player (thrown item, projectile, explosion, ...) */
    public static final int C_SPAWN = 6;
    /** reliable: netId, accepted */
    public static final int C_PICKUP_RESULT = 7;
    /** reliable: text */
    public static final int C_CHAT = 8;
    /** reliable: player died */
    public static final int C_DIED = 9;
    /** reliable: levelSerial fully loaded */
    public static final int C_LEVEL_LOADED = 10;
    /** reliable: stairs direction ordinal (debug/keyboard level change) */
    public static final int C_STAIRS = 11;
    /** reliable: test telemetry, replica digest */
    public static final int C_REPORT = 12;
    /** reliable: tile x, y used (secret walls etc.) */
    public static final int C_TILE_USE = 13;
    /** reliable: netId, value - client ran a local-only trigger (shop/dialogue) and the world effect must happen */
    public static final int C_TRIGGER = 14;
    /** reliable: won - client-side UI (e.g. the ending dialogue) asked to end the run */
    public static final int C_GAME_END = 15;

    // ---------------------------------------------------------------- server -> client
    /** reliable: playerId, serverName, tickRate */
    public static final int S_WELCOME = 64;
    /** reliable: reason */
    public static final int S_KICK = 65;
    /** reliable: list of (id, name, alive) */
    public static final int S_PLAYERS = 66;
    /** reliable: full level transfer */
    public static final int S_LEVEL = 67;
    /** reliable: netId, list, kryo entity */
    public static final int S_SPAWN = 68;
    /** reliable: netIds removed */
    public static final int S_REMOVE = 69;
    /** unreliable: field deltas */
    public static final int S_UPDATE = 70;
    /** unreliable: other players' states */
    public static final int S_PLAYER_STATES = 71;
    /** unreliable: one-shot effect entities (particles, decals) */
    public static final int S_EFFECTS = 72;
    /** unreliable: sound */
    public static final int S_SOUND = 73;
    /** reliable: damage to the local player */
    public static final int S_HIT = 74;
    /** reliable: experience */
    public static final int S_XP = 75;
    /** reliable: kryo status effect */
    public static final int S_STATUS = 76;
    /** reliable: pickup granted for netId */
    public static final int S_PICKUP = 77;
    /** reliable: on-screen message */
    public static final int S_MESSAGE = 78;
    /** reliable: chat line */
    public static final int S_CHAT = 79;
    /** reliable: game over (all dead) */
    public static final int S_GAME_OVER = 80;
    /** reliable: party escaped (won) */
    public static final int S_WIN = 81;
    /** reliable: revive local player */
    public static final int S_REVIVE = 82;
    /** reliable: teleport local player x,y,z */
    public static final int S_SET_POS = 83;
    /** reliable: run a local-only trigger (shop, dialogue, message overlay) on the client's replica */
    public static final int S_LOCAL_TRIGGER = 84;
    /** reliable: kryo item to add to the local inventory */
    public static final int S_GIVE_ITEM = 85;
    /** reliable: inventory edit from server logic (keys consumed, gold, ...): keysDelta, goldDelta */
    public static final int S_PLAYER_DELTA = 86;
    /** reliable: kill local player (server command) */
    public static final int S_KILL = 87;
    /** reliable: server tick and time sync */
    public static final int S_TIME = 88;
    /** reliable: knockback push to local player */
    public static final int S_PUSH = 89;
}
