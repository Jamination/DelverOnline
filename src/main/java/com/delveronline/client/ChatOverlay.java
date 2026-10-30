package com.delveronline.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.viewport.ScreenViewport;
import com.interrupt.dungeoneer.input.Actions;
import com.interrupt.dungeoneer.ui.UiSkin;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-left chat. New lines are visible to everyone and fade out after a few seconds; pressing the chat key
 * (Y unless already bound to a game action) opens an input line and shows recent history.
 */
public final class ChatOverlay {
    static final float VISIBLE_SECONDS = 8f;
    static final float FADE_SECONDS = 2f;
    static final int MAX_LINES = 8;
    static final int MAX_INPUT = 120;

    static final class Line {
        final String from;
        final String text;
        final long atMs;

        Line(String from, String text, long atMs) {
            this.from = from;
            this.text = text;
            this.atMs = atMs;
        }
    }

    private final List<Line> lines = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private boolean open;
    private boolean swallowNextTyped;
    private String status;
    private Stage stage;
    private Table table;
    private int builtWidth, builtHeight;

    /** Chat key: Y, or the first of a few fallbacks if the player bound Y to a game action. */
    public static int chatKey() {
        int[] candidates = {Input.Keys.Y, Input.Keys.T, Input.Keys.U, Input.Keys.ENTER};
        for (int k : candidates) {
            if (!Actions.keyBindings.containsValue(k)) return k;
        }
        return Input.Keys.ENTER;
    }

    public synchronized void add(String from, String text) {
        lines.add(new Line(from, text, System.currentTimeMillis()));
        while (lines.size() > 50) lines.remove(0);
    }

    public synchronized List<Line> snapshot() {
        return new ArrayList<>(lines);
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        open = true;
        swallowNextTyped = true;
        input.setLength(0);
    }

    public void close() {
        open = false;
        input.setLength(0);
    }

    /** Spectator / status text shown above the chat ("Spectating X"). */
    public void setStatus(String status) {
        this.status = status;
    }

    /** @return the message to send when Enter was pressed, otherwise null. */
    public String keyDown(int keycode) {
        if (!open) return null;
        if (keycode == Input.Keys.ENTER) {
            String msg = input.toString().trim();
            close();
            return msg.isEmpty() ? null : msg;
        }
        if (keycode == Input.Keys.ESCAPE) {
            close();
        } else if (keycode == Input.Keys.BACKSPACE && input.length() > 0) {
            input.setLength(input.length() - 1);
        }
        return null;
    }

    public void keyTyped(char c) {
        if (!open) return;
        if (swallowNextTyped) {
            swallowNextTyped = false;
            return;
        }
        if (c >= 32 && c != 127 && input.length() < MAX_INPUT) input.append(c);
    }

    /** Draws the chat on top of everything. Must be called on the GL thread. */
    public void draw() {
        int w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (w <= 0 || h <= 0) return;
        Skin skin;
        try {
            skin = UiSkin.getSkin();
        } catch (RuntimeException e) {
            return;
        }
        if (stage == null || w != builtWidth || h != builtHeight) {
            if (stage != null) stage.dispose();
            ScreenViewport vp = new ScreenViewport();
            vp.setUnitsPerPixel(1f / Math.max(1f, Math.round(h / 360f)));
            stage = new Stage(vp);
            table = new Table();
            table.setFillParent(true);
            table.align(Align.bottomLeft);
            stage.addActor(table);
            builtWidth = w;
            builtHeight = h;
        }
        stage.getViewport().update(w, h, true);
        table.clearChildren();
        table.pad(6f, 8f, 64f, 8f);
        long now = System.currentTimeMillis();
        List<Line> shown = snapshot();
        int from = Math.max(0, shown.size() - MAX_LINES);
        if (status != null) {
            Label s = new Label(status, skin);
            s.setColor(new Color(1f, 0.85f, 0.4f, 1f));
            table.add(s).left().padBottom(6f);
            table.row();
        }
        for (int i = from; i < shown.size(); i++) {
            Line l = shown.get(i);
            float age = (now - l.atMs) / 1000f;
            float alpha = open ? 1f : (age < VISIBLE_SECONDS ? 1f : Math.max(0f, 1f - (age - VISIBLE_SECONDS) / FADE_SECONDS));
            if (alpha <= 0f) continue;
            Label label = new Label(l.from == null ? l.text : l.from + ": " + l.text, skin);
            if (l.from == null) {
                label.setColor(0.75f, 0.75f, 0.85f, alpha);
            } else {
                label.setColor(1f, 1f, 1f, alpha);
            }
            label.setWrap(true);
            table.add(label).left().width(Math.min(stage.getWidth() * 0.5f, 360f));
            table.row();
        }
        if (open) {
            boolean blink = (now / 500) % 2 == 0;
            Label in = new Label("Say: " + input + (blink ? "_" : " "), skin);
            in.setColor(1f, 0.9f, 0.5f, 1f);
            table.add(in).left().padTop(2f);
            table.row();
        }
        stage.act();
        stage.draw();
    }

    /** Lines currently visible without opening the chat (for tests: messages must fade out). */
    public synchronized int visibleLineCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Line l : lines) {
            if ((now - l.atMs) / 1000f < VISIBLE_SECONDS + FADE_SECONDS) n++;
        }
        return Math.min(n, MAX_LINES);
    }

    public String inputText() {
        return input.toString();
    }
}
