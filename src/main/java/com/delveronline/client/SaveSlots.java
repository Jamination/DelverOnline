package com.delveronline.client;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.delveronline.Mp;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Player;
import com.interrupt.dungeoneer.entities.items.QuestItem;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.utils.JsonUtil;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Single-player save slots as multiplayer characters. A player can join with the character of one of Delver's three
 * save slots; while playing (and when leaving) the character's items, gold, keys, health, experience and stats are
 * written back into that slot. Where the character is in its single-player dungeon (level, position, potions seen in
 * that run) stays as it was, and quest items (the orb) never cross between a slot's run and a server's run.
 *
 * <p>These are Delver Online's own save slots in its data folder, never the Steam game's saves.
 */
final class SaveSlots {
    static final int COUNT = 3;

    /**
     * Player fields that make up the character, written back after multiplayer. Everything else in the save
     * (position, level number, travel path, potion colours of that run, messages read there) is left alone.
     */
    private static final String[] CHARACTER_FIELDS = {
            "hp", "maxHp", "mp", "maxMp", "level", "exp", "ac", "atk", "STR", "DEF", "DEX", "SPD", "INT", "stats",
            "statusEffects", "calculatedStats", "gold", "keys", "inventory", "equippedItems", "selectedBarItem",
            "heldItem", "hotbarSize", "inventorySize", "history",
    };

    private SaveSlots() {
    }

    /** One slot holding a saved character. */
    static final class Slot {
        final int index;
        final String summary;

        Slot(int index, String summary) {
            this.index = index;
            this.summary = summary;
        }
    }

    private static FileHandle file(int slot) {
        return Game.getFile("save/" + slot + "/player.dat");
    }

    /** The slots that hold a character, with a short description for the join panel. */
    static List<Slot> list() {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            FileHandle f = file(i);
            if (!f.exists()) continue;
            try {
                JsonValue p = new JsonReader().parse(f);
                StringBuilder sb = new StringBuilder("Slot ").append(i + 1).append(" - Lv ").append(p.getInt("level", 1))
                        .append(", ").append(p.getInt("gold", 0)).append("g");
                slots.add(new Slot(i, sb.toString()));
            } catch (RuntimeException e) {
                // unreadable slot: not offered
            }
        }
        return slots;
    }

    /** A slot's character, ready to join with (Delver's player JSON without quest items), or null. */
    static String characterForJoining(int slot) {
        FileHandle f = file(slot);
        if (!f.exists()) return null;
        try {
            return JsonUtil.toJson(withoutQuestItems(JsonUtil.fromJson(Player.class, f)), Player.class);
        } catch (RuntimeException e) {
            Mp.log("could not read the character in slot " + (slot + 1) + ": " + e);
            return null;
        }
    }

    /**
     * Writes a multiplayer character ({@code characterJson}, Delver's player JSON) back into a slot's save, keeping
     * the slot's own single-player state. Returns false when the slot or the character cannot be used.
     */
    static boolean writeBack(int slot, String characterJson) {
        FileHandle f = file(slot);
        if (!f.exists() || characterJson == null) return false;
        try {
            // Delver saves and loads player.dat with these same calls, so the round trip keeps everything else
            Player save = JsonUtil.fromJson(Player.class, f);
            Player character = withoutQuestItems(JsonUtil.fromJson(Player.class, characterJson));
            List<Item> keptQuestItems = new ArrayList<>();
            for (Item it : save.inventory) if (it instanceof QuestItem) keptQuestItems.add(it);
            for (String name : CHARACTER_FIELDS) {
                Field field = field(name);
                field.set(save, field.get(character));
            }
            // the single-player character keeps the quest items it found in its own run
            for (Item it : keptQuestItems) {
                int free = save.inventory.indexOf(null, true);
                if (free >= 0) save.inventory.set(free, it);
                else save.inventory.add(it);
            }
            // write next to it, then replace: a crash mid-write never leaves a broken save
            FileHandle tmp = f.sibling("player.dat.tmp");
            tmp.writeString(JsonUtil.toJson(save, Player.class), false, "UTF-8");
            tmp.moveTo(f);
            return true;
        } catch (RuntimeException | ReflectiveOperationException e) {
            Mp.log("could not save the character to slot " + (slot + 1) + ": " + e);
            return false;
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        for (Class<?> k = Player.class; k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // declared further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Player withoutQuestItems(Player p) {
        for (int i = 0; i < p.inventory.size; i++) {
            if (p.inventory.get(i) instanceof QuestItem) p.inventory.set(i, null);
        }
        for (Map.Entry<String, Item> e : p.equippedItems.entrySet()) {
            if (e.getValue() instanceof QuestItem) e.setValue(null);
        }
        p.isHoldingOrb = false;
        return p;
    }
}
