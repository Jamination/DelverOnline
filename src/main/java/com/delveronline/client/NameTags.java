package com.delveronline.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.interrupt.dungeoneer.GameManager;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;
import com.interrupt.dungeoneer.ui.UiSkin;

import java.util.Collection;

/** Small names floating above other players that are in sight. */
final class NameTags {
    private static final float MAX_DISTANCE = 10f;
    private SpriteBatch batch;
    private final Matrix4 projection = new Matrix4();
    private final Vector3 tmp = new Vector3();
    private final GlyphLayout layout = new GlyphLayout();

    void draw(Collection<RemoteAvatar> avatars) {
        if (avatars.isEmpty() || GameManager.renderer == null || Game.instance == null) return;
        PerspectiveCamera cam = GameManager.renderer.camera;
        Level level = Game.instance.level;
        Player me = Game.instance.player;
        if (cam == null || level == null || me == null) return;
        BitmapFont font = UiSkin.getFont();
        if (font == null) return;
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        float scale = Math.max(1f, Math.round(h / 360f));
        boolean begun = false;
        for (RemoteAvatar a : avatars) {
            if (!a.hasState() || a.hidden) continue;
            float dx = a.x - me.x, dy = a.y - me.y;
            if (dx * dx + dy * dy > MAX_DISTANCE * MAX_DISTANCE) continue;
            // world (x, y, z) is GL (x, z, y); a little above the head
            tmp.set(a.x, a.z + 0.38f + a.yOffset, a.y);
            if (!cam.frustum.pointInFrustum(tmp)) continue;
            if (!level.canSee(me.x, me.y, a.x, a.y)) continue;
            cam.project(tmp, 0, 0, w, h);
            if (!begun) {
                if (batch == null) batch = new SpriteBatch(64);
                // the skin font is y-down; draw it in a y-down projection
                projection.setToOrtho(0, w, h, 0, -1, 1);
                batch.setProjectionMatrix(projection);
                batch.begin();
                begun = true;
            }
            float oldScaleX = font.getData().scaleX, oldScaleY = font.getData().scaleY;
            // the skin draws this font at 0.1x in its UI units; a bit smaller than chat text
            font.getData().setScale(0.085f * scale);
            String text = a.isDead() ? a.playerName + " (dead)" : a.playerName;
            layout.setText(font, text);
            float x = tmp.x - layout.width / 2f;
            float y = h - tmp.y - layout.height;
            font.setColor(0f, 0f, 0f, 0.7f);
            font.draw(batch, text, x + scale, y + scale);
            font.setColor(a.isDead() ? 0.7f : 1f, a.isDead() ? 0.7f : 0.95f, a.isDead() ? 0.7f : 0.75f, 1f);
            font.draw(batch, text, x, y);
            font.getData().setScale(oldScaleX, oldScaleY);
        }
        if (begun) {
            batch.end();
            font.setColor(1f, 1f, 1f, 1f);
        }
    }
}
