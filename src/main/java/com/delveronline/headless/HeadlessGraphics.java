package com.delveronline.headless;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.mock.graphics.MockGraphics;

/**
 * Headless graphics with a real screen size (the stock mock reports 0x0, which breaks cameras and picking).
 * Timing is delegated to the backend's own instance, which the headless loop keeps updated.
 */
public final class HeadlessGraphics extends MockGraphics {
    private final MockGraphics timing;
    private final int width, height;

    private HeadlessGraphics(MockGraphics timing, int width, int height) {
        this.timing = timing;
        this.width = width;
        this.height = height;
    }

    /** Replaces Gdx.graphics; call from the application's create(). */
    public static void install(int width, int height) {
        if (Gdx.graphics instanceof HeadlessGraphics) return;
        Gdx.graphics = new HeadlessGraphics((MockGraphics) Gdx.graphics, width, height);
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getBackBufferWidth() {
        return width;
    }

    @Override
    public int getBackBufferHeight() {
        return height;
    }

    @Override
    public long getFrameId() {
        return timing.getFrameId();
    }

    @Override
    public float getDeltaTime() {
        return timing.getDeltaTime();
    }

    @Override
    public float getRawDeltaTime() {
        return timing.getRawDeltaTime();
    }

    @Override
    public int getFramesPerSecond() {
        return timing.getFramesPerSecond();
    }
}
