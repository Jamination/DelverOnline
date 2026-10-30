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
            builtLines = new ArrayList<>();
            builtStatus = null;
            labels.clear();
            builtOpen = !open; // force a layout pass
        }
        long now = System.currentTimeMillis();
        List<Line> shown = snapshot();
        int from = Math.max(0, shown.size() - MAX_LINES);
        // what is on screen: status, the visible lines, the input line
        List<Line> visible = new ArrayList<>(MAX_LINES);
        for (int i = from; i < shown.size(); i++) {
            Line l = shown.get(i);
            if (open || (now - l.atMs) / 1000f < VISIBLE_SECONDS + FADE_SECONDS) visible.add(l);
        }
        if (!open && status == null && visible.isEmpty()) return; // nothing to draw: no cost while chat is idle
        stage.getViewport().update(w, h, true);
        String inputText = open ? "Say: " + input + ((now / 500) % 2 == 0 ? "_" : " ") : null;
        if (!visible.equals(builtLines) || open != builtOpen || !java.util.Objects.equals(status, builtStatus)) {
            rebuild(skin, visible);
        }
        for (Line l : visible) {
            float age = (now - l.atMs) / 1000f;
            float alpha = open ? 1f : (age < VISIBLE_SECONDS ? 1f : Math.max(0f, 1f - (age - VISIBLE_SECONDS) / FADE_SECONDS));
            Label label = labels.get(l);
            if (label != null) label.getColor().a = alpha;
        }
        if (inputLabel != null && inputText != null && !inputText.contentEquals(inputLabel.getText())) inputLabel.setText(inputText);
        stage.act();
        stage.draw();
    }

    private final java.util.IdentityHashMap<Line, Label> labels = new java.util.IdentityHashMap<>();
    private List<Line> builtLines = new ArrayList<>();
    private boolean builtOpen;
    private String builtStatus;
    private Label inputLabel;

    /** Lays the table out again; only when the set of shown lines, the status or the open state changed. */
    private void rebuild(Skin skin, List<Line> visible) {
        table.clearChildren();
        table.pad(6f, 8f, 64f, 8f);
        if (status != null) {
            Label s = new Label(status, skin);
            s.setColor(new Color(1f, 0.85f, 0.4f, 1f));
            table.add(s).left().padBottom(6f);
            table.row();
        }
        labels.keySet().retainAll(new java.util.HashSet<>(visible));
        for (Line l : visible) {
            Label label = labels.get(l);
            if (label == null) {
                label = new Label(l.from == null ? l.text : l.from + ": " + l.text, skin);
                if (l.from == null) {
                    label.setColor(0.75f, 0.75f, 0.85f, 1f);
                } else {
                    label.setColor(1f, 1f, 1f, 1f);
                }
                label.setWrap(true);
                labels.put(l, label);
            }
            table.add(label).left().width(Math.min(stage.getWidth() * 0.5f, 360f));
            table.row();
        }
        inputLabel = null;
        if (open) {
            inputLabel = new Label("Say: ", skin);
            inputLabel.setColor(1f, 0.9f, 0.5f, 1f);
            table.add(inputLabel).left().padTop(2f);
            table.row();
        }
        builtLines = new ArrayList<>(visible);
        builtOpen = open;
        builtStatus = status;
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
