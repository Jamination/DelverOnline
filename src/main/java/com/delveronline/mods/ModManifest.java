package com.delveronline.mods;

import com.badlogic.gdx.files.FileHandle;
import com.delveronline.net.ByteReader;
import com.delveronline.net.ByteWriter;
import com.delveronline.net.ProtocolException;
import com.interrupt.api.steam.workshop.WorkshopModData;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.utils.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The mods a game is running, in load order: identity, title and a fingerprint of every file. A server only lets in
 * players whose manifest equals its own, so everyone loads the same items, monsters, levels and art.
 */
public final class ModManifest {
    /** One mod: Workshop ID (or "local:" + folder name), title, and a 64-bit hash of its files. */
    public static final class Entry {
        public final String id;
        public final String title;
        public final long hash;

        Entry(String id, String title, long hash) {
            this.id = id;
            this.title = title;
            this.hash = hash;
        }

        @Override
        public String toString() {
            return title + " (" + id + ", " + String.format("%016x", hash).substring(0, 8) + ")";
        }
    }

    public final List<Entry> entries;

    private ModManifest(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    private static final Map<String, Long> hashCache = new HashMap<>();

    /** The mods Delver is running now (its ModManager's enabled list), in load order. */
    public static ModManifest current() {
        List<Entry> list = new ArrayList<>();
        for (String path : Game.getModManager().modsFound) {
            if (".".equals(path)) continue;
            File dir = new File(path);
            String id = ModDiscovery.isWorkshopFolder(path) ? dir.getName() : "local:" + dir.getName();
            String title = titleOf(path);
            list.add(new Entry(id, title != null ? title : dir.getName(), hashOf(dir)));
        }
        return new ModManifest(list);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void write(ByteWriter w) {
        w.writeVarInt(entries.size());
        for (Entry e : entries) w.writeString(e.id).writeString(e.title).writeLong(e.hash);
    }

    public static ModManifest read(ByteReader r) {
        int n = r.readVarInt();
        if (n < 0 || n > 1000) throw new ProtocolException("bad mod count " + n);
        List<Entry> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(new Entry(r.readString(), r.readString(), r.readLong()));
        return new ModManifest(list);
    }

    /**
     * Why a player with {@code client} mods cannot join a server running these ({@code this}), worded for the
     * player; null when they match.
     */
    public String mismatch(ModManifest client) {
        if (sameAs(client)) return null;
        Map<String, Entry> mine = byId(entries);
        Map<String, Entry> theirs = byId(client.entries);
        List<String> missing = new ArrayList<>(), extra = new ArrayList<>(), different = new ArrayList<>();
        for (Entry e : entries) {
            Entry t = theirs.get(e.id);
            if (t == null) missing.add(e.title);
            else if (t.hash != e.hash) different.add(e.title);
        }
        for (Entry t : client.entries) if (!mine.containsKey(t.id)) extra.add(t.title);
        StringBuilder sb = new StringBuilder("Your mods don't match the server's.");
        if (!missing.isEmpty()) sb.append(" Install or enable: ").append(names(missing)).append('.');
        if (!extra.isEmpty()) sb.append(" Disable: ").append(names(extra)).append('.');
        if (!different.isEmpty()) sb.append(" Different version: ").append(names(different)).append('.');
        if (missing.isEmpty() && extra.isEmpty() && different.isEmpty()) sb.append(" They load in a different order.");
        return sb.toString();
    }

    private boolean sameAs(ModManifest other) {
        if (other.entries.size() != entries.size()) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry a = entries.get(i), b = other.entries.get(i);
            if (!a.id.equals(b.id) || a.hash != b.hash) return false;
        }
        return true;
    }

    private static Map<String, Entry> byId(List<Entry> list) {
        Map<String, Entry> m = new LinkedHashMap<>();
        for (Entry e : list) m.put(e.id, e);
        return m;
    }

    private static String names(List<String> titles) {
        int shown = Math.min(4, titles.size());
        String s = String.join(", ", titles.subList(0, shown));
        return titles.size() > shown ? s + " and " + (titles.size() - shown) + " more" : s;
    }

    /** One line per mod, for logs and the server console. */
    public String describe() {
        if (entries.isEmpty()) return "no mods";
        return entries.size() + " mod" + (entries.size() == 1 ? "" : "s") + ": "
                + entries.stream().map(Entry::toString).collect(Collectors.joining(", "));
    }

    /** The title from a mod's modInfo.json (Workshop mods), read the way Delver's Mods menu reads it; or null. */
    static String titleOf(String path) {
        File info = new File(path, "modInfo.json");
        if (!info.isFile()) return null;
        try {
            WorkshopModData data = JsonUtil.fromJson(WorkshopModData.class, new FileHandle(info));
            return data != null && data.title != null && !data.title.trim().isEmpty() ? data.title.trim() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** First 64 bits of a SHA-256 over every file's relative path and contents, in path order. Cached per folder. */
    static synchronized long hashOf(File dir) {
        String key = ModDiscovery.canonical(dir.getPath());
        Long cached = hashCache.get(key);
        if (cached != null) return cached;
        long h;
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            Path root = dir.toPath();
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                        .sorted((a, b) -> relative(root, a).compareTo(relative(root, b)))
                        .collect(Collectors.toList());
            }
            byte[] buf = new byte[65536];
            for (Path f : files) {
                sha.update(relative(root, f).getBytes(StandardCharsets.UTF_8));
                sha.update((byte) 0);
                try (InputStream in = Files.newInputStream(f)) {
                    for (int n; (n = in.read(buf)) > 0; ) sha.update(buf, 0, n);
                }
                sha.update((byte) 0);
            }
            byte[] d = sha.digest();
            h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
        } catch (IOException | NoSuchAlgorithmException e) {
            h = 0; // unreadable: compares equal only to another unreadable copy, which then fails on load anyway
        }
        hashCache.put(key, h);
        return h;
    }

    private static String relative(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }
}
