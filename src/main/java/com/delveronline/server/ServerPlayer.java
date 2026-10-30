package com.delveronline.server;

import com.delveronline.net.ByteWriter;
import com.delveronline.net.Connection;
import com.delveronline.proto.Msg;
import com.delveronline.proto.PlayerState;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;

/** A connected player on the server: connection, proxy entity and replication view. */
public final class ServerPlayer {
    public final int id;
    public final String name;
    public final Connection conn;
    public final PlayerProxy proxy;
    public final PlayerState state = new PlayerState();
    public final ServerReplicator.View view;
    final DelverServer server;

    /** Level serial the client has finished loading (0 = none yet). */
    public int loadedLevelSerial;
    public boolean dead;
    /** Admin toggle: damage aimed at this player is ignored. */
    public boolean god;
    public int lastStateSeq = -1;
    public long joinedMs = System.currentTimeMillis();
    /** Which character this player looks like to the others (0 .. Msg.PLAYER_LOOKS-1). */
    public int look;

    ServerPlayer(DelverServer server, int id, String name, Connection conn) {
        this.server = server;
        this.id = id;
        this.name = name;
        this.conn = conn;
        this.proxy = new PlayerProxy(this);
        this.view = new ServerReplicator.View(this);
        this.state.id = id;
    }

    /** True when the player is alive in the current level and therefore part of the simulation. */
    public boolean inWorld() {
        return !dead && loadedLevelSerial == server.world.levelSerial() && server.world.levelSerial() != 0;
    }

    public void send(ByteWriter w) {
        conn.sendReliable(w.toByteArray());
    }

    void sendHit(float projx, float projy, int damage, float knockback, Weapon.DamageType type, Entity instigator) {
        ByteWriter w = new ByteWriter(32).writeByte(Msg.S_HIT);
        w.writeFloat(projx).writeFloat(projy).writeSVarInt(damage).writeFloat(knockback);
        w.writeVarInt(type == null ? 0 : type.ordinal() + 1);
        w.writeVarInt(server.world.replicator().netIdOf(instigator));
        // instigator position lets the client compute knockback direction even for untracked attackers
        w.writeFloat(instigator != null ? instigator.x : proxy.x).writeFloat(instigator != null ? instigator.y : proxy.y);
        send(w);
    }

    void sendExperience(int amount) {
        send(new ByteWriter(8).writeByte(Msg.S_XP).writeSVarInt(amount));
    }

    void sendStatusEffect(StatusEffect effect) {
        byte[] bytes = server.world.kryo().write(effect);
        send(new ByteWriter(bytes.length + 8).writeByte(Msg.S_STATUS).writeBytes(bytes));
    }

    void sendGiveItem(Item item) {
        byte[] bytes = server.world.kryo().write(item);
        send(new ByteWriter(bytes.length + 8).writeByte(Msg.S_GIVE_ITEM).writeBytes(bytes));
    }

    public void sendMessage(String text, float seconds, float scale) {
        send(new ByteWriter(text.length() + 16).writeByte(Msg.S_MESSAGE).writeString(text).writeFloat(seconds).writeFloat(scale));
    }

    @Override
    public String toString() {
        return name + "#" + id;
    }
}
