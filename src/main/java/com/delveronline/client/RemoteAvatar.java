package com.delveronline.client;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.delveronline.proto.PlayerState;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Entity;
import com.interrupt.dungeoneer.game.Level;

/**
 * How other players look: the game's hooded adventurer NPC ("Hoody", npcs atlas frames 96-116) with walk bob,
 * idle animation, an attack swing of whatever they hold, a hurt flash, and a death fall. Their held and offhand
 * items are separate item sprites carried at their sides.
 */
public class RemoteAvatar extends Entity {
    static final String ATLAS = "npcs";
    static final int FRAME_FIRST = 96;
    static final int FRAME_LAST = 116;
    /** Remote movement is shown slightly in the past so it is always between two received states. */
    private static final long RENDER_DELAY_MS = 66;

    public final int playerId;
    public String playerName;

    // two most recent states for interpolation
    private final PlayerState prev = new PlayerState();
    private final PlayerState next = new PlayerState();
    private long prevMs, nextMs;
    private boolean hasState;

    private float walkPhase;
    private float idleTimer;
    private float frameTimer;
    private boolean idlePlaying;
    private int lastAttackCount = -1;
    private float attackAnim;
    private int lastHp = Integer.MIN_VALUE;
    private float hurtTimer;
    private float deathAnim;
    private float viewYaw;

    final Part held = new Part();
    final Part offhand = new Part();

    /** Item sprite carried by an avatar. */
    public static class Part extends Entity {
        String atlas;

        Part() {
            this.artType = ArtType.item;
            this.isSolid = false;
            this.isDynamic = true;
            this.hidden = true;
            this.scale = 0.55f;
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
        public void tick(Level level, float delta) {
            // positioned by the avatar
        }
    }

    public RemoteAvatar(int playerId, String name) {
        this.playerId = playerId;
        this.playerName = name;
        this.artType = ArtType.entity;
        this.spriteAtlas = ATLAS;
        this.tex = FRAME_FIRST;
        this.isSolid = false;
        this.isDynamic = true;
        this.shadowType = ShadowType.BLOB;
        this.collision.set(0.2f, 0.2f, 0.5f);
        this.floating = true;
        this.color = new Color(Color.WHITE);
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
        boolean walking = speed > 0.004f && !dead;

        // --- body animation
        if (walking) {
            walkPhase += delta * (0.25f + speed * 6f);
            yOffset = Math.abs((float) Math.sin(walkPhase)) * 0.045f;
            frameTimer += delta;
            if (frameTimer > 7f) {
                frameTimer = 0f;
                tex = tex >= FRAME_LAST ? FRAME_FIRST : tex + 1;
            }
            idlePlaying = false;
        } else if (!dead) {
            yOffset *= 0.8f;
            idleTimer += delta;
            if (!idlePlaying && idleTimer > 240f) {
                idlePlaying = true;
                idleTimer = 0f;
                tex = FRAME_FIRST;
            }
            if (idlePlaying) {
                frameTimer += delta;
                if (frameTimer > 160f / (FRAME_LAST - FRAME_FIRST + 1)) {
                    frameTimer = 0f;
                    if (tex >= FRAME_LAST) {
                        idlePlaying = false;
                        tex = FRAME_FIRST;
                    } else {
                        tex++;
                    }
                }
            } else {
                tex = FRAME_FIRST;
            }
        }

        // --- attack swing
        if (next.attackCount != lastAttackCount) {
            lastAttackCount = next.attackCount;
            attackAnim = 1f;
        }
        attackAnim = Math.max(0f, attackAnim - delta * 0.06f);

        // --- hurt flash
        if (lastHp != Integer.MIN_VALUE && next.hp < lastHp && !dead) hurtTimer = 18f;
        lastHp = next.hp;
        hurtTimer = Math.max(0f, hurtTimer - delta);
        float hurt = hurtTimer / 18f;
        color.set(1f, 1f - 0.65f * hurt, 1f - 0.65f * hurt, 1f);

        // --- death fall / revive
        if (dead) {
            deathAnim = Math.min(1f, deathAnim + delta * 0.035f);
        } else {
            deathAnim = Math.max(0f, deathAnim - delta * 0.08f);
        }
        if (deathAnim > 0f) {
            roll = 90f * deathAnim * deathAnim;
            yOffset = -0.32f * deathAnim;
        } else {
            roll = walking ? (float) Math.sin(walkPhase * 0.5f) * 3f : 0f;
        }

        placeItems(level, dead);
    }

    private void placeItems(Level level, boolean dead) {
        held.show(dead ? null : next.heldAtlas, next.heldTex);
        offhand.show(dead ? null : next.offAtlas, next.offTex);
        // carry items on the avatar's sides as seen from the local camera (sprites always face the viewer)
        float rx = 1f, ry = 0f;
        PerspectiveCamera cam = GameManager.renderer != null ? GameManager.renderer.camera : null;
        if (cam != null) {
            float dx = cam.direction.x, dy = cam.direction.z;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0.0001f) {
                rx = -dy / len;
                ry = dx / len;
            }
        }
        float swing = attackAnim > 0f ? (float) Math.sin(attackAnim * Math.PI) : 0f;
        held.x = x + rx * (0.17f + 0.08f * swing);
        held.y = y + ry * (0.17f + 0.08f * swing);
        held.z = z;
        held.yOffset = yOffset + 0.12f + 0.12f * swing;
        held.roll = -35f + 110f * swing;
        held.color = color;
        offhand.x = x - rx * 0.17f;
        offhand.y = y - ry * 0.17f;
        offhand.z = z;
        offhand.yOffset = yOffset + 0.1f;
        offhand.roll = 10f;
        offhand.color = color;
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
