package com.delveronline.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.delveronline.proto.PlayerState;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.gfx.drawables.DrawableSprite;

/**
 * How other players look: one of Delver's bandits (see {@link BanditSprites}), playing the bandit's own walk,
 * attack, hurt and death animations. The player's real held item is drawn in the bandit's hand and follows the
 * hand and the weapon direction of every frame, so it swings with the attack animation; the off-hand item sits in
 * the other hand.
 */
public class RemoteAvatar extends Entity {
    /** Remote movement is shown slightly in the past so it is always between two received states. */
    private static final long RENDER_DELAY_MS = 66;
    /** Held item size (item atlas scale 0.5): roughly the size of the weapon the bandit sprite carried. */
    private static final float ITEM_SCALE = 0.85f;
    /** Animation lengths in game ticks (from data/monsters.dat); attacks are a little quicker than a bandit's. */
    private static final float WALK_TIME = 30f, ATTACK_TIME = 24f, HURT_TIME = 22f, DIE_TIME = 22f;

    public final int playerId;
    public String playerName;

    private enum Anim { IDLE, WALK, ATTACK, HURT, DIE }

    // two most recent states for interpolation
    private final PlayerState prev = new PlayerState();
    private final PlayerState next = new PlayerState();
    private long prevMs, nextMs;
    private boolean hasState;

    private BanditSprites.Look look = BanditSprites.LOOKS[0];
    private Anim anim = Anim.IDLE;
    private float animTime;
    private int lastAttackCount = -1;
    private int lastHp = Integer.MIN_VALUE;
    private float hurtTimer;
    private boolean wasDead;
    private float viewYaw;

    final Part held = new Part();
    final Part offhand = new Part();

    /** Item sprite carried by an avatar; drawn just in front of it. */
    public static class Part extends Entity {
        String atlas;

        Part() {
            this.artType = ArtType.item;
            this.isSolid = false;
            this.isDynamic = true;
            this.hidden = true;
            this.scale = ITEM_SCALE;
            this.shadowType = ShadowType.NONE;
            this.floating = true;
        }

        void show(String atlas, int tex) {
            if (atlas == null) {
                hidden = true;
                return;
            }
            if (!atlas.equals(this.atlas) || this.drawable == null) {
                this.atlas = atlas;
                this.spriteAtlas = atlas;
                if (this.drawable != null) this.drawable.refresh();
            }
            this.tex = tex;
            this.hidden = false;
        }

        @Override
        public void updateDrawable() {
            super.updateDrawable();
            // pull the item a little toward the viewer so it is never hidden inside the body sprite
            if (drawable instanceof DrawableSprite) ((DrawableSprite) drawable).cameraPull = 0.04f;
        }

        @Override
        public void tick(Level level, float delta) {
            // positioned by the avatar
        }
    }

    public RemoteAvatar(int playerId, String name) {
        this.playerId = playerId;
        this.playerName = name;
        this.artType = ArtType.entity;
        this.spriteAtlas = BanditSprites.ATLAS;
        this.tex = 0;
        this.isSolid = false;
        this.isDynamic = true;
        this.shadowType = ShadowType.BLOB;
        this.collision.set(0.2f, 0.2f, 0.5f);
        this.floating = true;
        this.color = new Color(Color.WHITE);
    }

    void setLook(int index) {
        look = BanditSprites.LOOKS[Math.floorMod(index, BanditSprites.LOOKS.length)];
    }

    @Override
    public void updateDrawable() {
        // the bandit atlas is built from the game's sprite sheet the first time an avatar is drawn
        BanditSprites.ensureBuilt();
        super.updateDrawable();
    }

    /** Called when a new state for this player arrives. */
    void onState(PlayerState s) {
        long now = System.currentTimeMillis();
        if (!hasState) {
            prev.copyFrom(s);
            next.copyFrom(s);
            prevMs = now - 33;
            nextMs = now;
            hasState = true;
            x = s.x;
            y = s.y;
            z = s.z;
            lastAttackCount = s.attackCount;
            lastHp = s.hp;
            return;
        }
        if (s.seq <= next.seq) return;
        prev.copyFrom(next);
        prevMs = nextMs;
        next.copyFrom(s);
        nextMs = now;
        // big jumps (teleports, level entrance) are not interpolated
        if (Math.abs(prev.x - next.x) > 3f || Math.abs(prev.y - next.y) > 3f) prev.copyFrom(next);
    }

    boolean isDead() {
        return hasState && next.has(PlayerState.F_DEAD);
    }

    boolean hasState() {
        return hasState;
    }

    /** Interpolated eye position of this player (used for spectating). */
    float viewYaw() {
        return viewYaw;
    }

    float viewPitch() {
        return next.yrot;
    }

    @Override
    public void tick(Level level, float delta) {
        if (!hasState) {
            hidden = true;
            held.hidden = true;
            offhand.hidden = true;
            return;
        }
        hidden = false;
        long renderAt = System.currentTimeMillis() - RENDER_DELAY_MS;
        float span = Math.max(1f, nextMs - prevMs);
        float t = Math.max(0f, Math.min(1.25f, (renderAt - prevMs) / span));
        x = prev.x + (next.x - prev.x) * t;
        y = prev.y + (next.y - prev.y) * t;
        z = prev.z + (next.z - prev.z) * t;
        viewYaw = lerpAngle(prev.rot, next.rot, Math.min(1f, t));

        boolean dead = next.has(PlayerState.F_DEAD);
        float speed = (float) Math.sqrt(next.xa * next.xa + next.ya * next.ya);
        boolean walking = speed > 0.004f;

        // --- pick the animation: death > attack > hurt > walk > idle
        if (dead) {
            if (!wasDead) start(Anim.DIE);
        } else {
            if (wasDead) start(Anim.IDLE); // revived
            if (next.attackCount != lastAttackCount) start(Anim.ATTACK);
            else if (lastHp != Integer.MIN_VALUE && next.hp < lastHp && anim != Anim.ATTACK) start(Anim.HURT);
        }
        if (next.hp < lastHp && !dead) hurtTimer = 18f;
        lastAttackCount = next.attackCount;
        lastHp = next.hp;
        wasDead = dead;

        animTime += anim == Anim.WALK ? delta * Math.max(0.6f, Math.min(1.8f, speed / 0.04f)) : delta;
        if ((anim == Anim.ATTACK && animTime >= ATTACK_TIME) || (anim == Anim.HURT && animTime >= HURT_TIME)) {
            start(walking ? Anim.WALK : Anim.IDLE);
        } else if (anim == Anim.IDLE && walking) {
            start(Anim.WALK);
        } else if (anim == Anim.WALK && !walking) {
            start(Anim.IDLE);
        }
        tex = currentFrame();
        yOffset = 0f;
        roll = 0f;

        // --- hurt flash
        hurtTimer = Math.max(0f, hurtTimer - delta);
        float hurt = hurtTimer / 18f;
        color.set(1f, 1f - 0.65f * hurt, 1f - 0.65f * hurt, 1f);

        placeItems(dead);
    }

    private void start(Anim a) {
        anim = a;
        animTime = 0f;
    }

    private int currentFrame() {
        switch (anim) {
            case WALK:
                return look.frame(cycle(look.first, look.walkEnd, animTime / WALK_TIME, true));
            case ATTACK:
                return look.frame(cycle(look.attackStart, look.attackEnd, animTime / ATTACK_TIME, false));
            case HURT:
                return look.frame(cycle(look.hurtStart, look.hurtEnd, animTime / HURT_TIME, false));
            case DIE:
                return look.frame(cycle(look.dieStart, look.dieEnd, animTime / DIE_TIME, false));
            default:
                return look.frame(look.first);
        }
    }

    /** Frame of an animation running from {@code start} to {@code end} at progress {@code pos} (1 = one run). */
    private static int cycle(int start, int end, float pos, boolean loop) {
        int frames = end - start + 1;
        if (loop) pos -= (float) Math.floor(pos);
        return start + Math.min(frames - 1, Math.max(0, (int) (pos * frames)));
    }

    private void placeItems(boolean dead) {
        held.show(dead ? null : next.heldAtlas, next.heldTex);
        offhand.show(dead ? null : next.offAtlas, next.offTex);
        PerspectiveCamera cam = GameManager.renderer != null ? GameManager.renderer.camera : null;
        if (cam == null || (held.hidden && offhand.hidden)) return;
        // screen-right in the world (sprites always face the viewer)
        float dx = cam.direction.x, dy = cam.direction.z;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.0001f) return;
        float rx = -dy / len, ry = dx / len;

        int frame = tex < BanditSprites.hasHand.length && BanditSprites.hasHand[tex] ? tex : look.base; // idle hand
        if (!held.hidden) {
            hold(held, rx, ry, BanditSprites.handX[frame], BanditSprites.handY[frame], BanditSprites.handAngle[frame], true);
        }
        if (!offhand.hidden) hold(offhand, rx, ry, look.offhandX, look.offhandY, 0f, false);
    }

    /**
     * Puts an item sprite into the hand at frame pixel (hx, hy). With {@code byHandle} the item's handle is at the
     * hand and its blade points along {@code angle}; otherwise the item is centred on the hand, upright.
     */
    private void hold(Part part, float rx, float ry, float hx, float hy, float angle, boolean byHandle) {
        // the hand in the world: body frames are 1 unit per 32 px, frame centre at the avatar position
        float handRight = hx / BanditSprites.SIZE - 0.5f;
        float handUp = z + yOffset - (hy / BanditSprites.SIZE - 0.5f);
        BanditSprites.Grip g = BanditSprites.grip(part.atlas, part.tex);
        com.interrupt.dungeoneer.gfx.TextureAtlas atlas = com.interrupt.dungeoneer.gfx.TextureAtlas.getCachedRegion(part.atlas);
        if (g == null || atlas == null) {
            part.hidden = true;
            return;
        }
        float px = atlas.scale * part.scale / g.size; // world size of one item pixel
        // weapons turn with the hand; potions, food and the like stay upright
        float roll = byHandle && g.elongated ? angle - g.angle : 0f;
        // from the sprite's drawn centre to the point that must sit in the hand, in (right, up)
        float gx = byHandle ? (g.gripX - g.centerX) * px : 0f;
        float gy = byHandle ? -(g.gripY - g.centerY) * px : 0f;
        double r = Math.toRadians(roll);
        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
        float rotX = gx * cos - gy * sin;
        float rotY = gx * sin + gy * cos;
        float centerRight = handRight - rotX;
        float centerUp = handUp - rotY;
        part.x = x + rx * centerRight;
        part.y = y + ry * centerRight;
        part.z = z;
        // the renderer draws an item's centre at z + yOffset - (offset.y * scale + y_offset)
        part.yOffset = centerUp - z + g.offsetY * atlas.scale * part.scale + atlas.y_offset;
        part.roll = roll;
        part.color = color;
    }

    private static float lerpAngle(float a, float b, float t) {
        float d = b - a;
        while (d > Math.PI) d -= (float) (Math.PI * 2);
        while (d < -Math.PI) d += (float) (Math.PI * 2);
        return a + d * t;
    }

    /** Adds the avatar and its item sprites to a level. */
    void addTo(Level level) {
        level.non_collidable_entities.add(this);
        level.non_collidable_entities.add(held);
        level.non_collidable_entities.add(offhand);
    }

    void removeFrom(Level level) {
        if (level == null) return;
        level.non_collidable_entities.removeValue(this, true);
        level.non_collidable_entities.removeValue(held, true);
        level.non_collidable_entities.removeValue(offhand, true);
    }
}
