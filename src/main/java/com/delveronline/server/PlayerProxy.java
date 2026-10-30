package com.delveronline.server;

import com.interrupt.dungeoneer.GameInput;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.Weapon;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.statuseffects.StatusEffect;

/**
 * Server-side stand-in for a remote player. It lives in the authoritative level so monsters, traps, triggers and
 * projectiles interact with it exactly like the single-player {@link Player}; its transform comes from the owning
 * client, and anything that would change player-owned state (damage, experience, status effects, inventory) is
 * forwarded to that client instead of being applied here.
 *
 * <p>An owner-less proxy acts as the "party" player used for level transitions.
 */
public class PlayerProxy extends Player {
    public transient ServerPlayer owner;
    /** Whether the proxy is currently in the authoritative level's entity list. */
    public transient boolean inLevel;

    public PlayerProxy() {
        super();
        this.hp = 8;
        this.maxHp = 8;
        this.hidden = true;
        this.isSolid = true;
    }

    public PlayerProxy(ServerPlayer owner) {
        this();
        this.owner = owner;
    }

    @Override
    public void tick(Level level, float delta) {
        // transform is driven by the owning client
    }

    @Override
    public void tick(Level level, float delta, GameInput input) {
    }

    @Override
    public void init() {
    }

    @Override
    public void hit(float projx, float projy, int damage, float knockback, Weapon.DamageType damageType, Entity instigator) {
        if (owner != null && !isDead) owner.sendHit(projx, projy, damage, knockback, damageType, instigator);
    }

    @Override
    public int takeDamage(int damage, Weapon.DamageType damageType, Entity instigator) {
        if (owner != null && !isDead) owner.sendHit(0f, 0f, damage, 0f, damageType, instigator);
        return damage;
    }

    @Override
    public void addExperience(int e) {
        if (owner != null) owner.sendExperience(e);
    }

    @Override
    public void addStatusEffect(StatusEffect effect) {
        if (owner != null && !isDead) owner.sendStatusEffect(effect);
    }

    @Override
    public void shake(float amount) {
    }

    @Override
    public void shake(float amount, float range, com.badlogic.gdx.math.Vector3 position) {
    }

    @Override
    public void die() {
    }

    @Override
    public boolean addToInventory(Item item) {
        return addToInventory(item, true);
    }

    @Override
    public boolean addToInventory(Item item, boolean autoEquip) {
        if (owner == null) return false;
        owner.sendGiveItem(item);
        return true;
    }

    @Override
    public void removeFromInventory(Item item) {
        // keys/gold changes are reconciled through state deltas; inventory items are owned by the client
    }

    @Override
    public void hitEffect(Level level, Weapon.DamageType inDamageType) {
        // the owning client shows its own hit effects
    }

    @Override
    public void updateDrawable() {
    }
}
