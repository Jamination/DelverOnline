package com.delveronline.proto;

import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;

/** Per-frame presentation and gameplay-relevant state of one player, sent client -> server -> other clients. */
public final class PlayerState {
    public static final int F_DEAD = 1;
    public static final int F_HOLDING_ORB = 2;
    public static final int F_INVISIBLE = 4;
    public static final int F_ON_FLOOR = 8;
    public static final int F_SPECTATING = 16;

    public int id;
    public int seq;
    public float x, y, z, rot, yrot, xa, ya, za;
    public int hp, maxHp, level, keys, gold;
    public int flags;
    public float visibility;
    /** Incremented each time the player attacks; remote viewers play a swing when it changes. */
    public int attackCount;
    /** Held item sprite (atlas + tex); atlas null when empty-handed. */
    public String heldAtlas;
    public int heldTex;
    public String offAtlas;
    public int offTex;
    public int levelSerial;

    public void write(ByteWriter w) {
        w.writeVarInt(id);
        w.writeVarInt(seq);
        w.writeVarInt(levelSerial);
        w.writeFloat(x).writeFloat(y).writeFloat(z).writeFloat(rot).writeFloat(yrot);
        w.writeFloat(xa).writeFloat(ya).writeFloat(za);
        w.writeSVarInt(hp).writeSVarInt(maxHp).writeVarInt(level).writeVarInt(keys).writeVarInt(gold);
        w.writeVarInt(flags);
        w.writeFloat(visibility);
        w.writeVarInt(attackCount);
        w.writeString(heldAtlas);
        w.writeSVarInt(heldTex);
        w.writeString(offAtlas);
        w.writeSVarInt(offTex);
    }

    public PlayerState read(ByteReader r) {
        id = r.readVarInt();
        seq = r.readVarInt();
        levelSerial = r.readVarInt();
        x = r.readFloat();
        y = r.readFloat();
        z = r.readFloat();
        rot = r.readFloat();
        yrot = r.readFloat();
        xa = r.readFloat();
        ya = r.readFloat();
        za = r.readFloat();
        hp = r.readSVarInt();
        maxHp = r.readSVarInt();
        level = r.readVarInt();
        keys = r.readVarInt();
        gold = r.readVarInt();
        flags = r.readVarInt();
        visibility = r.readFloat();
        attackCount = r.readVarInt();
        heldAtlas = r.readString();
        heldTex = r.readSVarInt();
        offAtlas = r.readString();
        offTex = r.readSVarInt();
        return this;
    }

    public PlayerState copyFrom(PlayerState o) {
        id = o.id;
        seq = o.seq;
        levelSerial = o.levelSerial;
        x = o.x;
        y = o.y;
        z = o.z;
        rot = o.rot;
        yrot = o.yrot;
        xa = o.xa;
        ya = o.ya;
        za = o.za;
        hp = o.hp;
        maxHp = o.maxHp;
        level = o.level;
        keys = o.keys;
        gold = o.gold;
        flags = o.flags;
        visibility = o.visibility;
        attackCount = o.attackCount;
        heldAtlas = o.heldAtlas;
        heldTex = o.heldTex;
        offAtlas = o.offAtlas;
        offTex = o.offTex;
        return this;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}
