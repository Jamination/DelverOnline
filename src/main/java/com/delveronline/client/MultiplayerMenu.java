package com.delveronline.client;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.scenes.scene2d.Action;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.scenes.scene2d.utils.TextureRegionDrawable;
import com.delveronline.Mp;
import com.interrupt.dungeoneer.Audio;
import com.interrupt.dungeoneer.GameApplication;
import com.interrupt.dungeoneer.screens.MainMenuScreen;
import com.interrupt.dungeoneer.ui.UiSkin;

import java.lang.reflect.Field;

/**
 * Adds a "Multiplayer" button to Delver's main menu and a join panel drawn with the game's own UI skin. The last
 * server and player name are remembered between launches.
 */
public final class MultiplayerMenu {
    /** Reason to show the next time the menu opens (e.g. "Disconnected: timed out"). */
    static volatile String pendingMessage;

    private static TextField.TextFieldStyle fieldStyle;
    private static Field fullTableField, buttonTableField, refreshOnEscapeField, uiField;

    private MultiplayerMenu() {}

    private static Object get(Field f, Object o) {
        try {
            return f.get(o);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void resolveFields() throws NoSuchFieldException {
        if (fullTableField != null) return;
        fullTableField = MainMenuScreen.class.getDeclaredField("fullTable");
        buttonTableField = MainMenuScreen.class.getDeclaredField("buttonTable");
        refreshOnEscapeField = MainMenuScreen.class.getDeclaredField("refreshOnEscape");
        uiField = com.interrupt.dungeoneer.screens.BaseScreen.class.getDeclaredField("ui");
        fullTableField.setAccessible(true);
        buttonTableField.setAccessible(true);
        refreshOnEscapeField.setAccessible(true);
        uiField.setAccessible(true);
    }

    /** Hook: MainMenuScreen.makeContent() finished. */
    public static void install(Object screenObj) {
        if (!(screenObj instanceof MainMenuScreen)) return;
        final MainMenuScreen screen = (MainMenuScreen) screenObj;
        try {
            resolveFields();
            Table buttonTable = (Table) get(buttonTableField, screen);
            Skin skin = UiSkin.getSkin();
            TextButton mp = new TextButton(" Multiplayer ", skin);
            mp.setColor(new Color(0.55f, 0.85f, 1f, 1f));
            mp.setName("delveronline-multiplayer");
            mp.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    click();
                    final Table full = (Table) get(fullTableField, screen);
                    fadeOutThen(full, () -> showPanel(screen, null));
                }
            });
            buttonTable.add(mp).align(16).height(20f).padLeft(4f);
            buttonTable.pack();
        } catch (Exception e) {
            Mp.log("could not add the Multiplayer button: " + e);
            return;
        }
        String msg = pendingMessage;
        if (msg != null) {
            pendingMessage = null;
            showPanel(screen, msg);
        }
    }

    /** The button sound of Delver's own main menu (Play, save slots). */
    private static void click() {
        Audio.playSound("/ui/ui_button_click.mp3", 0.3f);
    }

    private static TextField.TextFieldStyle fieldStyle(Skin skin) {
        if (fieldStyle == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            Texture white = new Texture(pm);
            pm.dispose();
            TextField.TextFieldStyle s = new TextField.TextFieldStyle();
            s.font = skin.getFont("default-font");
            s.fontColor = Color.WHITE;
            s.focusedFontColor = Color.WHITE;
            s.messageFontColor = Color.GRAY;
            NinePatchDrawable bg = new NinePatchDrawable(new NinePatch(skin.getRegion("save-select"), 6, 6, 6, 6));
            bg.setLeftWidth(6f);
            bg.setRightWidth(6f);
            bg.setTopHeight(4f);
            bg.setBottomHeight(4f);
            s.background = bg;
            s.focusedBackground = bg;
            TextureRegionDrawable cursor = new TextureRegionDrawable(new com.badlogic.gdx.graphics.g2d.TextureRegion(white));
            cursor.setMinWidth(1f);
            s.cursor = cursor.tint(new Color(1f, 0.9f, 0.5f, 1f));
            s.selection = cursor.tint(new Color(0.4f, 0.6f, 1f, 0.6f));
            fieldStyle = s;
        }
        return fieldStyle;
    }

    /** The main menu's own timings: content fades in over 0.2 s, save slots follow each other 0.1 s apart. */
    private static final float FADE_IN = 0.2f, FADE_OUT = 0.2f, ROW_STAGGER = 0.1f;

    /** The panel currently on screen, for the fade into the game. Render thread only. */
    private static MainMenuScreen activeScreen;
    private static Table activeTable;

    /** Fades the menu content out (ignoring clicks meanwhile), then runs {@code next}. */
    private static void fadeOutThen(Table full, Runnable next) {
        full.setTouchable(Touchable.disabled);
        full.clearActions();
        full.addAction(Actions.sequence(Actions.fadeOut(FADE_OUT), Actions.run(next)));
    }

    /**
     * The server sent the level: leave the join panel the way Delver leaves the menu for a new game (panel fades
     * out, the screen and music fade to black) and then run {@code start}. Returns false when no panel is showing;
     * the caller then starts right away.
     */
    static boolean transitionToGame(Runnable start) {
        MainMenuScreen screen = activeScreen;
        Table full = activeTable;
        activeScreen = null;
        activeTable = null;
        if (screen == null || full == null || GameApplication.instance == null
                || GameApplication.instance.getScreen() != screen) {
            return false;
        }
        full.setTouchable(Touchable.disabled);
        full.clearActions();
        full.addAction(Actions.sequence(
                Actions.fadeOut(0.3f),
                Actions.delay(0.2f),
                Actions.run(() -> {
                    try {
                        Field f = MainMenuScreen.class.getDeclaredField("fadingOut");
                        f.setAccessible(true);
                        f.setBoolean(screen, true);
                    } catch (ReflectiveOperationException e) {
                        // no fade to black: the game still starts
                    }
                }),
                Actions.delay(1.75f), // as long as the main menu's own fade into a new game
                Actions.run(start)));
        return true;
    }

    /** Replaces the menu content with the join panel. */
    static void showPanel(final MainMenuScreen screen, String message) {
        try {
            resolveFields();
            final Table full = (Table) get(fullTableField, screen);
            final Stage stage = (Stage) get(uiField, screen);
            refreshOnEscapeField.setBoolean(screen, true); // Escape goes back to the main menu
            final Skin skin = UiSkin.getSkin();
            ClientSettings settings = ClientSettings.load();

            full.clearActions();
            full.padTop(0f);
            full.clear();
            Table t = new Table(skin);
            t.setBackground(new NinePatchDrawable(new NinePatch(skin.getRegion("window"), 11, 11, 11, 11)));
            t.pad(14f);

            Label title = new Label("Multiplayer", skin);
            title.setColor(new Color(0.55f, 0.85f, 1f, 1f));
            t.add(title).colspan(2).left().padBottom(8f);
            t.row();

            final TextField address = new TextField(settings.lastServer, fieldStyle(skin));
            address.setMessageText("host or host:port");
            address.setName("delveronline-address");
            final TextField name = new TextField(settings.playerName.isEmpty() ? defaultName() : settings.playerName, fieldStyle(skin));
            name.setMaxLength(16);
            name.setName("delveronline-name");

            Label serverLabel = new Label("Server", skin);
            t.add(serverLabel).left().padRight(8f);
            t.add(address).width(220f).height(22f).left();
            t.row().padTop(4f);
            Label nameLabel = new Label("Name", skin);
            t.add(nameLabel).left().padRight(8f);
            t.add(name).width(220f).height(22f).left();
            t.row();

            final Label status = new Label(message == null ? "" : message, skin);
            status.setWrap(true);
            status.setName("delveronline-status");
            if (message != null) status.setColor(new Color(1f, 0.55f, 0.45f, 1f));
            t.add(status).colspan(2).width(280f).left().padTop(8f);
            t.row();

            TextButton back = new TextButton(" Back ", skin);
            final TextButton join = new TextButton(" Join ", skin);
            join.setColor(new Color(0.55f, 1f, 0.6f, 1f));
            join.setName("delveronline-join");
            t.add(back).left().padTop(10f).height(20f);
            t.add(join).right().padTop(10f).height(20f);
            t.row();
            full.add(t);
            full.pack();

            final Runnable doJoin = () -> {
                click();
                MpClient existing = MpClient.current();
                if (existing != null && existing.phase != MpClient.Phase.CLOSED) return;
                String addr = address.getText().trim();
                if (addr.isEmpty()) {
                    status.setColor(new Color(1f, 0.55f, 0.45f, 1f));
                    status.setText("Enter a server address.");
                    return;
                }
                String host;
                int port;
                try {
                    String[] hp = ClientSettings.splitAddress(addr);
                    host = hp[0];
                    port = Integer.parseInt(hp[1]);
                } catch (RuntimeException ex) {
                    status.setColor(new Color(1f, 0.55f, 0.45f, 1f));
                    status.setText("Invalid address.");
                    return;
                }
                ClientSettings s = ClientSettings.load();
                s.lastServer = addr;
                s.playerName = name.getText().trim();
                s.save();
                status.setColor(Color.WHITE);
                MpClient c = MpClient.connect(host, port, name.getText().trim());
                watch(stage, status, c);
            };
            join.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    doJoin.run();
                }
            });
            back.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    click();
                    MpClient c = MpClient.current();
                    if (c != null && !c.gameStarted) c.disconnect("cancelled");
                    activeScreen = null;
                    activeTable = null;
                    fadeOutThen(full, () -> {
                        full.setTouchable(Touchable.enabled);
                        screen.makeContent(); // fades the main menu back in
                    });
                }
            });
            TextField.TextFieldListener enter = (field, c) -> {
                if (c == '\r' || c == '\n') doJoin.run();
            };
            address.setTextFieldListener(enter);
            name.setTextFieldListener(enter);
            stage.setKeyboardFocus(address.getText().isEmpty() ? address : name);
            address.setCursorPosition(address.getText().length());

            // fade in like Delver's own menu: the panel, then its rows one after another like the save slots
            full.getColor().a = 1f;
            full.setTouchable(Touchable.enabled);
            full.addAction(Actions.sequence(Actions.fadeOut(1.0E-4f), Actions.fadeIn(FADE_IN)));
            Actor[][] rows = {{title}, {serverLabel, address}, {nameLabel, name}, {status}, {back, join}};
            for (int i = 0; i < rows.length; i++) {
                for (Actor a : rows[i]) {
                    a.addAction(Actions.sequence(Actions.fadeOut(1.0E-4f), Actions.delay((i + 1) * ROW_STAGGER), Actions.fadeIn(FADE_IN)));
                }
            }
            activeScreen = screen;
            activeTable = full;
        } catch (Exception e) {
            Mp.log("could not show the multiplayer panel: " + e);
            e.printStackTrace();
        }
    }

    /** Mirrors the connection status into the panel until the game starts or the attempt fails. */
    private static void watch(Stage stage, final Label status, final MpClient c) {
        stage.addAction(new Action() {
            @Override
            public boolean act(float delta) {
                status.setText(c.status);
                if (c.phase == MpClient.Phase.CLOSED) {
                    status.setColor(new Color(1f, 0.55f, 0.45f, 1f));
                    return true;
                }
                return c.gameStarted;
            }
        });
    }

    private static String defaultName() {
        String user = System.getProperty("user.name", "Adventurer");
        return user.length() > 16 ? user.substring(0, 16) : user;
    }

    /** True when the panel input fields should keep keyboard focus (used by tests). */
    static boolean isFocusable() {
        return Gdx.input != null;
    }
}
