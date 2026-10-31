package com.delveronline.it;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Joining with a save slot's character: the server gets that character, and what happens on the server is written
 * back into the slot without touching where the character is in its own single-player run. Quest items stay in the
 * run they belong to.
 */
class SaveSlotTest {
    private static final String SAVE = "{\n"
            + "\"x\": 10.5, \"y\": 20.5, \"z\": 0.5,\n"
            + "\"hp\": 7, \"maxHp\": 15, \"level\": 4, \"exp\": 120,\n"
            + "\"gold\": 77, \"keys\": 2,\n"
            + "\"levelNum\": 3, \"levelName\": \"Dungeon 3\",\n"
            + "\"inventory\": [\n"
            + "  {\"class\": \"com.interrupt.dungeoneer.entities.items.Sword\", \"name\": \"Slot Sword\", \"tex\": 8, \"spriteAtlas\": \"melee\", \"baseDamage\": 2},\n"
            + "  null, null, null, null, null,\n"
            + "  {\"class\": \"com.interrupt.dungeoneer.entities.items.QuestItem\", \"name\": \"Slot Orb\"},\n"
            + "  null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null\n"
            + "]\n"
            + "}\n";

    @Test
    void aSaveSlotCharacterJoinsAndKeepsWhatItGetsOnTheServer() throws Exception {
        try (Harness h = new Harness("saveslot")) {
            Path slot = h.workDir.resolve("Alice").resolve("save").resolve("1").resolve("player.dat");
            Files.createDirectories(slot.getParent());
            Files.write(slot, SAVE.getBytes(StandardCharsets.UTF_8));

            Harness.Proc server = h.server();
            Harness.Proc alice = h.bot("Alice", "--slot", "2");
            alice.send("wander off");
            // the slot's character, not a new one: its health, gold and experience level
            alice.await("EVENT restored \\d+ items hp 7 gold 77 level 4", 120);
            alice.await("EVENT level \\d+ 0 \\?\\?\\?", 120);
            server.send("god Alice");
            Harness.sleep(1000);
            Matcher inv = alice.ask("inv", "EVENT inv (.*)", 10);
            assertTrue(inv.group(1).contains("Sword(Slot Sword)"), "joined with the slot's items: " + inv.group(1));
            assertTrue(!inv.group(1).contains("QuestItem"), "the slot's orb stays in its own run: " + inv.group(1));

            // things gained on the server, including this run's orb
            server.ask("give Alice Iron dagger", "gave Iron dagger to Alice", 10);
            server.ask("give Alice Boss/Orb", "gave .* to Alice", 10);
            Harness.sleep(4500); // the character is reported every 3 s
            alice.ask("quit", "EVENT saved character to slot 2", 15);

            String saved = new String(Files.readAllBytes(slot), StandardCharsets.UTF_8);
            assertTrue(saved.contains("Iron dagger"), "the server's item was saved to the slot");
            assertTrue(saved.contains("Slot Sword"), "the slot's own item is still there");
            assertEquals(1, count(saved, "QuestItem"), "only the slot's own orb is in the save");
            assertTrue(saved.contains("Slot Orb"), "the slot's own orb is still there");
            // where the character is in its single-player run is unchanged
            assertTrue(Pattern.compile("\"levelNum\"\\s*:\\s*3\\b").matcher(saved).find(), "single-player level kept: " + saved);
            assertTrue(Pattern.compile("\"x\"\\s*:\\s*10\\.5\\b").matcher(saved).find(), "single-player position kept");
            assertTrue(Pattern.compile("\"gold\"\\s*:\\s*77\\b").matcher(saved).find(), "gold saved");
        }
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
