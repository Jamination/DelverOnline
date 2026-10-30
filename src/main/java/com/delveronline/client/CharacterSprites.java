package com.delveronline.client;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.utils.ObjectMap;
import com.delveronline.Mp;
import com.delveronline.proto.Msg;
import com.interrupt.dungeoneer.Art;
import com.interrupt.dungeoneer.gfx.TextureAtlas;

/**
 * Player bodies: Delver's humanoid monsters that have full walk, attack, hurt and death animations (bandits,
 * skeletons, a goblin warrior, the hooded mage and the zombie). The weapon painted into a character's frames is
 * removed at load time so the player's real equipped item can be drawn in the hand instead; for every frame the
 * hand position and the direction the character's weapon pointed are kept, so the real item follows the
 * character's own swing. Empty-handed characters hold the item at the tip of their arm, swung through an arc
 * during their attack animation.
 *
 * <p>Built from the installed game's sprite sheets at runtime; no game art ships with the mod.
 */
final class CharacterSprites {
    static final String ATLAS = "delveronline_players";
    static final int SIZE = 32;
    /** frames per character row in the generated atlas */
    private static final int COLUMNS = 20;

    /** One character: its sprite sheet, frame ranges (data/monsters.dat) and the colours of its painted weapon. */
    static final class Look {
        final String source;
        final int first, walkEnd, attackStart, attackEnd, hurtStart, hurtEnd, dieStart, dieEnd;
        /** colours only the weapon uses, keyed by RGB's red and green bytes; empty for empty-handed characters */
        final int[] weapon;
        /** colours shared with the clothes: removed only where they touch the weapon */
        final int[] hilt;
        /** weapon-coloured clumps smaller than this are kept (teeth and eyes in the same colours) */
        final int minClump;
        /** atlas index of the first frame of this look */
        int base;
        /** off hand, in frame pixels, measured on the idle frame */
        float offhandX, offhandY;

        Look(String source, int walkStart, int walkEnd, int attackStart, int attackEnd, int hurtStart, int hurtEnd,
             int dieStart, int dieEnd, int minClump, int[] weapon, int[] hilt) {
            this.source = source;
            this.first = walkStart;
            this.walkEnd = walkEnd;
            this.attackStart = attackStart;
            this.attackEnd = attackEnd;
            this.hurtStart = hurtStart;
            this.hurtEnd = hurtEnd;
            this.dieStart = dieStart;
            this.dieEnd = dieEnd;
            this.minClump = minClump;
            this.weapon = weapon;
            this.hilt = hilt;
        }

        int frame(int sourceFrame) {
            return base + sourceFrame - first;
        }
    }

    private static final int[] NONE = {};
    private static final int[] BANDIT_BLADE = {0x422f, 0x432f, 0x422e, 0x4859, 0x495a, 0x9cc2, 0x9bc2, 0x9bc1, 0xc6e0,
            0xc6df, 0xc7e0, 0xe7f3, 0xe7f2, 0xe8f3, 0x5639, 0x5739, 0x6a83, 0x6a84};
    private static final int[] BANDIT_HILT = {0xdc8c, 0xe7ff, 0xe6ff, 0xdc8b};

    static final Look[] LOOKS = {
            // THIEF: dagger, red mohawk
            new Look("entities_dungeon", 48, 51, 52, 55, 56, 59, 60, 62, 5, BANDIT_BLADE, BANDIT_HILT),
            // THIEF2: eyepatch, sword
            new Look("entities_dungeon", 80, 83, 84, 88, 89, 92, 93, 95, 5, BANDIT_BLADE, BANDIT_HILT),
            // SKELETON: dagger
            new Look("entities_undead", 0, 3, 4, 8, 9, 12, 13, 15, 2,
                    new int[]{0x4039, 0x403a, 0x3f39, 0x7e74, 0x7f74, 0x7f75, 0xa89e, 0xa79e, 0xa89f, 0xdfdb, 0x605b, 0x615b}, NONE),
            // SKELETON MAGE: torch staff
            new Look("entities_undead", 16, 19, 20, 27, 28, 31, 32, 34, 2,
                    new int[]{0x5039, 0x5139, 0x3515, 0x3516, 0x3616, 0xdecb, 0xddcb, 0x6c2a, 0x6b29, 0x9b73, 0x9c73, 0x513a,
                            0xca8c, 0xcb8c, 0xcb8d}, NONE),
            // GOBLIN WARRIOR: sword
            new Look("entities_sewer", 32, 35, 36, 39, 40, 43, 44, 46, 2,
                    new int[]{0x4230, 0xe8f3, 0x9dc2, 0x9dc1, 0x495a, 0x6b84, 0x6b83}, NONE),
            // MAGE: hooded, empty-handed
            new Look("entities_dungeon", 128, 131, 132, 138, 139, 142, 143, 145, 0, NONE, NONE),
            // ZOMBIE: empty-handed
            new Look("entities_dungeon", 160, 163, 164, 169, 170, 173, 174, 176, 0, NONE, NONE),
    };

    static {
        if (LOOKS.length != Msg.PLAYER_LOOKS) throw new IllegalStateException("look count differs from protocol");
        for (Look l : LOOKS) if (l.dieEnd - l.first >= COLUMNS) throw new IllegalStateException("too many frames");
    }

    // per atlas frame: hand position (frame pixels) and weapon direction (degrees, counter-clockwise from screen right)
    static final float[] handX = new float[LOOKS.length * COLUMNS];
    static final float[] handY = new float[LOOKS.length * COLUMNS];
    static final float[] handAngle = new float[LOOKS.length * COLUMNS];
    static final boolean[] hasHand = new boolean[LOOKS.length * COLUMNS];

    private static boolean built;
    private static boolean failed;

    private CharacterSprites() {
    }

    /** Builds the atlas once (render thread). Returns false when it is not available (headless, missing art). */
    static boolean ensureBuilt() {
        if (built) return true;
        if (failed || Mp.headless) return false;
        try {
            build();
            built = true;
        } catch (RuntimeException e) {
            failed = true;
            Mp.log("could not build player sprites: " + e);
        }
        return built;
    }

    private static void build() {
        Pixmap out = new Pixmap(COLUMNS * SIZE, LOOKS.length * SIZE, Pixmap.Format.RGBA8888);
        ObjectMap<String, Pixmap> sheets = new ObjectMap<>();
        try {
            TextureAtlas first = null;
            for (int l = 0; l < LOOKS.length; l++) {
                Look look = LOOKS[l];
                TextureAtlas source = TextureAtlas.getCachedRegion(look.source);
                if (source == null) throw new IllegalStateException(look.source + " atlas missing");
                if (first == null) first = source;
                Pixmap sheet = sheets.get(look.source);
                if (sheet == null) {
                    sheet = Art.loadPixmap(source.filename);
                    sheets.put(look.source, sheet);
                }
                int columns = sheet.getWidth() / SIZE;
                look.base = l * COLUMNS;
                for (int f = look.first; f <= look.dieEnd; f++) {
                    int index = look.frame(f);
                    cleanFrame(look, f, sheet, (f % columns) * SIZE, (f / columns) * SIZE, out,
                            (index % COLUMNS) * SIZE, (index / COLUMNS) * SIZE, index);
                }
                int idle = look.base;
                float cx = bodyCenterX(out, 0, l * SIZE);
                look.offhandX = 2f * cx - handX[idle];
                look.offhandY = handY[idle] + 1f;
            }
            Texture texture = new Texture(out);
            TextureAtlas atlas = new TextureAtlas(COLUMNS, texture, out);
            atlas.name = ATLAS;
            atlas.scale = first.scale;
            atlas.y_offset = first.y_offset;
            atlas.didLoad = true;
            TextureAtlas.cacheAtlas(atlas, ATLAS);
        } finally {
            for (Pixmap p : sheets.values()) p.dispose();
            out.dispose();
        }
    }

    /** Copies one frame without its painted weapon and records where the hand and weapon were. */
    private static void cleanFrame(Look look, int sourceFrame, Pixmap sheet, int sx, int sy, Pixmap out, int dx, int dy, int index) {
        int[] px = new int[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++) px[x + y * SIZE] = sheet.getPixel(sx + x, sy + y);

        // 1. weapon-coloured pixels in clumps big enough not to be teeth or eyes
        boolean[] weapon = new boolean[px.length];
        boolean[] seen = new boolean[px.length];
        int[] stack = new int[px.length];
        int[] component = new int[px.length];
        for (int i = 0; i < px.length; i++) {
            if (seen[i] || !in(look.weapon, px[i])) continue;
            int n = 0, top = 0;
            stack[top++] = i;
            seen[i] = true;
            while (top > 0) {
                int c = stack[--top];
                component[n++] = c;
                for (int nb : neighbours(c)) {
                    if (nb >= 0 && !seen[nb] && in(look.weapon, px[nb])) {
                        seen[nb] = true;
                        stack[top++] = nb;
                    }
                }
            }
            if (n >= look.minClump) for (int k = 0; k < n; k++) weapon[component[k]] = true;
        }
        // 2. hilt colours where they touch the blade
        boolean grew = look.hilt.length > 0;
        while (grew) {
            grew = false;
            for (int i = 0; i < px.length; i++) {
                if (weapon[i] || !in(look.hilt, px[i])) continue;
                for (int nb : neighbours(i)) {
                    if (nb >= 0 && weapon[nb]) {
                        weapon[i] = true;
                        grew = true;
                        break;
                    }
                }
            }
        }
        // 3. single pixels left floating next to the removed weapon
        boolean[] erase = weapon.clone();
        for (int i = 0; i < px.length; i++) {
            if (weapon[i] || alpha(px[i]) == 0) continue;
            int body = 0;
            boolean nearWeapon = false;
            for (int nb : neighbours(i)) {
                if (nb < 0) continue;
                if (weapon[nb]) nearWeapon = true;
                else if (alpha(px[nb]) != 0) body++;
            }
            if (nearWeapon && body <= 1) erase[i] = true;
        }

        float cx = 0, cy = 0;
        int bodyCount = 0;
        for (int i = 0; i < px.length; i++) {
            if (erase[i] || alpha(px[i]) == 0) continue;
            out.drawPixel(dx + i % SIZE, dy + i / SIZE, px[i]);
            cx += i % SIZE;
            cy += i / SIZE;
            bodyCount++;
        }
        if (bodyCount == 0) return;
        cx /= bodyCount;
        cy /= bodyCount;

        int weaponCount = 0;
        for (boolean w : weapon) if (w) weaponCount++;
        if (weaponCount < 4) {
            if (look.weapon.length == 0) emptyHand(look, sourceFrame, px, index);
            return;
        }

        // 4. the hand: the weapon pixels closest to the body; the weapon points to its farthest pixel within reach
        int[] nearest = {-1, -1, -1};
        float[] nearestD = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        for (int i = 0; i < px.length; i++) {
            if (!weapon[i]) continue;
            float d = dist(i % SIZE, i / SIZE, cx, cy);
            for (int k = 0; k < 3; k++) {
                if (d < nearestD[k]) {
                    for (int m = 2; m > k; m--) {
                        nearestD[m] = nearestD[m - 1];
                        nearest[m] = nearest[m - 1];
                    }
                    nearestD[k] = d;
                    nearest[k] = i;
                    break;
                }
            }
        }
        float hx = 0, hy = 0;
        for (int k = 0; k < 3; k++) {
            hx += nearest[k] % SIZE;
            hy += nearest[k] / SIZE;
        }
        hx /= 3f;
        hy /= 3f;
        float best = -1f, tx = hx, ty = hy;
        for (int i = 0; i < px.length; i++) {
            if (!weapon[i]) continue;
            float d = dist(i % SIZE, i / SIZE, hx, hy);
            if (d <= 11f && d > best) {
                best = d;
                tx = i % SIZE;
                ty = i / SIZE;
            }
        }
        handX[index] = hx + 0.5f;
        handY[index] = hy + 0.5f;
        handAngle[index] = (float) Math.toDegrees(Math.atan2(-(ty - hy), tx - hx));
        hasHand[index] = true;
    }

    /**
     * Empty-handed characters: the hand is the tip of the arm on the viewer's right (the right-most pixels between
     * shoulder and hip height). Held items point up, and swing from raised to low through the attack animation.
     */
    private static void emptyHand(Look look, int sourceFrame, int[] px, int index) {
        int maxX = -1;
        for (int i = 0; i < px.length; i++) {
            int y = i / SIZE;
            if (y >= 12 && y <= 27 && alpha(px[i]) != 0) maxX = Math.max(maxX, i % SIZE);
        }
        if (maxX < 0) return;
        float sumY = 0;
        int n = 0;
        for (int y = 12; y <= 27; y++)
            if (alpha(px[maxX + y * SIZE]) != 0) {
                sumY += y;
                n++;
            }
        handX[index] = maxX - 0.5f;
        handY[index] = sumY / n + 0.5f;
        if (sourceFrame >= look.attackStart && sourceFrame <= look.attackEnd) {
            float t = (sourceFrame - look.attackStart) / (float) Math.max(1, look.attackEnd - look.attackStart);
            handAngle[index] = 110f - 150f * t;
        } else {
            handAngle[index] = 70f;
        }
        hasHand[index] = true;
    }

    private static float bodyCenterX(Pixmap p, int x0, int y0) {
        float sum = 0;
        int n = 0;
        for (int y = 0; y < SIZE; y++)
            for (int x = 0; x < SIZE; x++)
                if (alpha(p.getPixel(x0 + x, y0 + y)) != 0) {
                    sum += x + 0.5f;
                    n++;
                }
        return n == 0 ? SIZE / 2f : sum / n;
    }

    private static final int[] NB = new int[8];

    private static int[] neighbours(int i) {
        int x = i % SIZE, y = i / SIZE, k = 0;
        for (int oy = -1; oy <= 1; oy++)
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) continue;
                int nx = x + ox, ny = y + oy;
                NB[k++] = nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE ? -1 : nx + ny * SIZE;
            }
        return NB;
    }

    private static boolean in(int[] palette, int rgba) {
        if (alpha(rgba) == 0) return false;
        int key = rgba >>> 16;
        for (int c : palette) if (c == key) return true;
        return false;
    }

    private static int alpha(int rgba) {
        return rgba & 0xff;
    }

    private static float dist(float ax, float ay, float bx, float by) {
        return (float) Math.sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by));
    }

    // ------------------------------------------------------------------ held items

    /** Where an item sprite is held: grip point and blade direction in its frame, and its clipped-sprite centre. */
    static final class Grip {
        float gripX, gripY, angle, centerX, centerY, offsetY;
        /** a weapon-like item that turns with the hand; others stay upright */
        boolean elongated;
        int size;
    }

    private static final ObjectMap<String, Grip[]> grips = new ObjectMap<>();

    /** Grip of item sprite {@code tex} in atlas {@code atlasName}, or null when unknown. */
    static Grip grip(String atlasName, int tex) {
        if (Mp.headless || atlasName == null) return null;
        Grip[] all = grips.get(atlasName);
        if (all == null) {
            all = computeGrips(atlasName);
            grips.put(atlasName, all);
        }
        return tex >= 0 && tex < all.length ? all[tex] : null;
    }

    private static Grip[] computeGrips(String atlasName) {
        TextureAtlas atlas = TextureAtlas.getCachedRegion(atlasName);
        if (atlas == null) return new Grip[0];
        TextureRegion[] clipped = atlas.getClippedRegions();
        com.badlogic.gdx.math.Vector2[] offsets = atlas.getSpriteOffsets();
        Pixmap p;
        try {
            p = Art.loadPixmap(atlas.filename);
        } catch (RuntimeException e) {
            return new Grip[0];
        }
        try {
            int size = atlas.spriteSize;
            Grip[] result = new Grip[clipped.length];
            for (int t = 0; t < clipped.length; t++) {
                int fx = (t % atlas.columns) * size, fy = (t / atlas.columns) * size;
                if (fy + size > p.getHeight()) break;
                // handle end: the bottom-right-most pixel (Delver draws held items diagonally, handle bottom-right)
                int gx = -1, gy = -1, bestScore = Integer.MIN_VALUE;
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        if (alpha(p.getPixel(fx + x, fy + y)) != 0 && x + y > bestScore) {
                            bestScore = x + y;
                            gx = x;
                            gy = y;
                        }
                if (gx < 0) continue;
                float tipD = -1f;
                int tx = gx, ty = gy;
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        if (alpha(p.getPixel(fx + x, fy + y)) != 0) {
                            float d = dist(x, y, gx, gy);
                            if (d > tipD) {
                                tipD = d;
                                tx = x;
                                ty = y;
                            }
                        }
                // weapon-like (long, along the diagonal) or a compact thing (potion, bomb, key) held upright?
                int opaque = 0, onLine = 0, bottom = 0;
                float lx = tx - gx, ly = ty - gy, ll = Math.max(1f, (float) Math.sqrt(lx * lx + ly * ly));
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++)
                        if (alpha(p.getPixel(fx + x, fy + y)) != 0) {
                            opaque++;
                            bottom = Math.max(bottom, y);
                            float across = Math.abs((x - gx) * ly - (y - gy) * lx) / ll;
                            if (across <= 3f) onLine++;
                        }
                Grip g = new Grip();
                g.size = size;
                g.elongated = tipD >= size * 0.45f && onLine >= opaque * 0.7f;
                TextureRegion r = clipped[t];
                g.centerX = r.getRegionX() % size + r.getRegionWidth() / 2f;
                g.centerY = r.getRegionY() % size + r.getRegionHeight() / 2f;
                g.offsetY = offsets[t].y;
                if (g.elongated) {
                    // the hand closes a little way up the handle
                    g.gripX = gx + 0.5f + (tx - gx) * 0.18f;
                    g.gripY = gy + 0.5f + (ty - gy) * 0.18f;
                    g.angle = (float) Math.toDegrees(Math.atan2(-(ty - gy), tx - gx));
                } else {
                    // held from underneath, upright
                    g.gripX = g.centerX;
                    g.gripY = bottom + 1f - size * 0.12f;
                    g.angle = 90f;
                }
                result[t] = g;
            }
            return result;
        } finally {
            p.dispose();
        }
    }
}
