package com.delveronline.mods;

import com.badlogic.gdx.utils.Array;
import com.delveronline.Mp;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.ModManager;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which mods Delver loads, made the same everywhere so a server and its players can agree on one modlist.
 *
 * <p>Delver finds mods in its working directory's {@code mods/} folder and asks Steam for the subscribed Workshop
 * items. Delver Online runs the game from its own data folder and the server has no Steam at all, so after Delver's
 * search this also adds the game folder's {@code mods/} and, when Steam reported nothing, the Workshop items Steam
 * has installed on disk. The list is then put in a fixed order (local mods by folder name, then Workshop items by
 * ID) because later mods override earlier ones and Steam's own order differs between machines.
 *
 * <p>Delver's per-mod on/off switches (Options, Mods) still apply afterwards. The server and bots can narrow the
 * list with {@link #select}.
 */
public final class ModDiscovery {
    /** Delver's Steam app ID: the Workshop folder name. */
    static final String APP_ID = "249630";

    /** null: every mod found; empty: none; otherwise Workshop IDs, folder names, mod titles or folder paths */
    private static volatile List<String> selection;
    private static final List<String> unmatched = Collections.synchronizedList(new ArrayList<>());

    private ModDiscovery() {
    }

    /** Applies a {@code --mods} value: {@code all} (default), {@code none}, or a comma-separated list. */
    public static void select(String spec) {
        if (spec == null || spec.trim().isEmpty() || spec.trim().equalsIgnoreCase("all")) {
            selection = null;
            return;
        }
        List<String> items = new ArrayList<>();
        if (!spec.trim().equalsIgnoreCase("none")) {
            for (String s : spec.split(",")) if (!s.trim().isEmpty()) items.add(s.trim());
        }
        selection = items;
    }

    /** Entries of the last {@link #select} list that matched no mod (checked after Delver's mod search). */
    public static List<String> unmatchedSelections() {
        synchronized (unmatched) {
            return new ArrayList<>(unmatched);
        }
    }

    /** Called after {@code ModManager.findMods()}: completes, narrows and orders the list Delver found. */
    public static void afterFindMods(ModManager manager) {
        Array<String> all = allMods(manager);
        if (all == null) return;
        Set<String> seen = new HashSet<>();
        List<String> found = new ArrayList<>();
        boolean steamReportedWorkshop = false;
        for (String path : all) {
            if (".".equals(path)) continue;
            if (isWorkshopFolder(path)) steamReportedWorkshop = true;
            if (seen.add(canonical(path))) found.add(path);
        }
        File install = installDir();
        if (install != null) {
            addFolders(new File(install, "mods").listFiles(), found, seen);
            if (!steamReportedWorkshop) addInstalledWorkshopItems(install, found, seen);
        }
        List<String> chosen = applySelection(found, seen);
        chosen.sort((a, b) -> {
            boolean wa = isWorkshopFolder(a), wb = isWorkshopFolder(b);
            if (wa != wb) return wa ? 1 : -1;
            String na = new File(a).getName(), nb = new File(b).getName();
            if (wa) return Long.compare(Long.parseLong(na), Long.parseLong(nb));
            int c = na.compareToIgnoreCase(nb);
            return c != 0 ? c : a.compareTo(b);
        });
        all.clear();
        all.add(".");
        for (String p : chosen) all.add(p);
    }

    private static List<String> applySelection(List<String> found, Set<String> seen) {
        List<String> sel = selection;
        synchronized (unmatched) {
            unmatched.clear();
        }
        if (sel == null) return found;
        List<String> chosen = new ArrayList<>();
        for (String want : sel) {
            String match = null;
            for (String path : found) {
                if (matches(path, want)) {
                    match = path;
                    break;
                }
            }
            if (match == null) {
                // a folder given by path: loaded even if no search found it
                File dir = new File(want);
                if (dir.isDirectory()) {
                    match = dir.getAbsolutePath();
                    seen.add(canonical(match));
                }
            }
            if (match == null) unmatched.add(want);
            else if (!chosen.contains(match)) chosen.add(match);
        }
        return chosen;
    }

    private static boolean matches(String path, String want) {
        File f = new File(path);
        if (f.getName().equalsIgnoreCase(want)) return true;
        if (canonical(path).equals(canonical(want))) return true;
        String title = ModManifest.titleOf(path);
        return title != null && title.equalsIgnoreCase(want);
    }

    /** The Workshop items Steam lists as installed for Delver in the game's Steam library. */
    private static void addInstalledWorkshopItems(File install, List<String> found, Set<String> seen) {
        // <library>/steamapps/common/Delver -> <library>/steamapps/workshop
        File steamapps = install.getParentFile() != null ? install.getParentFile().getParentFile() : null;
        if (steamapps == null) return;
        File workshop = new File(steamapps, "workshop");
        File[] items = new File(workshop, "content" + File.separator + APP_ID).listFiles();
        if (items == null) return;
        Set<String> installed = installedIds(new File(workshop, "appworkshop_" + APP_ID + ".acf"));
        List<File> keep = new ArrayList<>();
        for (File item : items) {
            if (installed == null || installed.contains(item.getName())) keep.add(item);
        }
        addFolders(keep.toArray(new File[0]), found, seen);
    }

    private static final Pattern ITEM_KEY = Pattern.compile("\"(\\d+)\"\\s*\\{");

    /** IDs in the WorkshopItemsInstalled block of Steam's Workshop manifest, or null when it can't be read. */
    static Set<String> installedIds(File acf) {
        try {
            String text = new String(Files.readAllBytes(acf.toPath()), StandardCharsets.UTF_8);
            int start = text.indexOf("\"WorkshopItemsInstalled\"");
            if (start < 0) return null;
            int end = text.indexOf("\"WorkshopItemDetails\"", start);
            Matcher m = ITEM_KEY.matcher(end < 0 ? text.substring(start) : text.substring(start, end));
            Set<String> ids = new LinkedHashSet<>();
            while (m.find()) ids.add(m.group(1));
            return ids;
        } catch (IOException e) {
            return null;
        }
    }

    private static void addFolders(File[] dirs, List<String> found, Set<String> seen) {
        if (dirs == null) return;
        for (File d : dirs) {
            if (d.isDirectory() && seen.add(canonical(d.getPath()))) found.add(d.getAbsolutePath());
        }
    }

    /** A Workshop item folder: numeric name inside the Delver Workshop content folder. */
    static boolean isWorkshopFolder(String path) {
        File f = new File(path);
        File parent = f.getAbsoluteFile().getParentFile();
        return f.getName().matches("\\d+") && parent != null && parent.getName().equals(APP_ID);
    }

    /** The Delver install: the folder holding delver.jar. */
    static File installDir() {
        try {
            File jar = new File(Game.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? jar.getParentFile() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String canonical(String path) {
        try {
            return new File(path).getCanonicalPath().toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return new File(path).getAbsolutePath().toLowerCase(Locale.ROOT);
        }
    }

    private static Field allModsField;

    @SuppressWarnings("unchecked")
    private static Array<String> allMods(ModManager manager) {
        try {
            if (allModsField == null) {
                Field f = ModManager.class.getDeclaredField("allMods");
                f.setAccessible(true);
                allModsField = f;
            }
            return (Array<String>) allModsField.get(manager);
        } catch (ReflectiveOperationException e) {
            Mp.log("cannot read Delver's mod list: " + e);
            return null;
        }
    }
}
