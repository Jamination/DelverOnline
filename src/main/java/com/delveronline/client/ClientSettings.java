package com.delveronline.client;

import com.delveronline.Mp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Options;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/** Remembers the last server joined and the player name (stored next to Delver's options in the data dir). */
public final class ClientSettings {
    static final String FILE_NAME = "delveronline.properties";

    public String lastServer = "";
    public String playerName = "";

    public static File file() {
        return Game.getFile(Options.getOptionsDir() + FILE_NAME).file();
    }

    public static ClientSettings load() {
        ClientSettings s = new ClientSettings();
        File f = file();
        if (!f.exists()) return s;
        Properties p = new Properties();
        try (InputStream in = new FileInputStream(f)) {
            p.load(in);
            s.lastServer = p.getProperty("lastServer", "");
            s.playerName = p.getProperty("playerName", "");
        } catch (Exception e) {
            Mp.log("could not read " + f + ": " + e);
        }
        return s;
    }

    public void save() {
        File f = file();
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.exists()) dir.mkdirs();
        Properties p = new Properties();
        p.setProperty("lastServer", lastServer == null ? "" : lastServer);
        p.setProperty("playerName", playerName == null ? "" : playerName);
        try (OutputStream out = new FileOutputStream(f)) {
            p.store(out, "Delver Online");
        } catch (Exception e) {
            Mp.log("could not write " + f + ": " + e);
        }
    }

    /** Parses "host", "host:port" or "[ipv6]:port". */
    public static String[] splitAddress(String address) {
        String a = address.trim();
        int port = Mp.DEFAULT_PORT;
        String host = a;
        if (a.startsWith("[")) {
            int end = a.indexOf(']');
            if (end > 0) {
                host = a.substring(1, end);
                if (a.length() > end + 2 && a.charAt(end + 1) == ':') port = Integer.parseInt(a.substring(end + 2));
            }
        } else {
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) {
                host = a.substring(0, colon);
                port = Integer.parseInt(a.substring(colon + 1));
            }
        }
        return new String[]{host, String.valueOf(port)};
    }
}
