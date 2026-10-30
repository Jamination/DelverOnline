package com.delveronline.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end multiplayer behaviour with the real server and three real (headless) game clients:
 * shared world, chat, shared interactions, party level changes, spectating, revival and game over.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MultiplayerScenarioTest {
    private Harness h;
    private Harness.Proc server, alice, bob, cara;
    private List<Harness.Proc> bots;
    private int levelNum;

    @BeforeAll
    void startServerAndClients() throws Exception {
        h = new Harness("scenarios");
        server = h.server();
        alice = h.bot("Alice", "--seed", "1");
        bob = h.bot("Bob", "--seed", "2");
        cara = h.bot("Cara", "--seed", "3");
        bots = Arrays.asList(alice, bob, cara);
        for (Harness.Proc b : bots) {
            b.send("wander off");
            b.await("EVENT level \\d+", 120);
        }
        for (Harness.Proc b : bots) server.send("god " + b.name);
        // leave the intro sequence and start in the first dungeon level
        int[] marks = marks();
        server.send("goto 1");
        awaitLevel(marks, 1);
    }

    @AfterAll
    void stop() {
        if (h != null) h.close();
    }

    private int[] marks() {
        int[] m = new int[bots.size()];
        for (int i = 0; i < m.length; i++) m[i] = bots.get(i).mark();
        return m;
    }

    private void awaitLevel(int[] marks, int expectedNum) {
        for (int i = 0; i < bots.size(); i++) {
            bots.get(i).awaitFrom(marks[i], "EVENT level \\d+ " + expectedNum + " ", 90);
        }
        levelNum = expectedNum;
        Harness.sleep(1500); // let replication settle
    }

    @Test
    @Order(1)
    void everyPlayerSeesTheOthers() {
        for (Harness.Proc b : bots) {
            String others = b.ask("others", "EVENT others (.*)", 10).group(1);
            for (Harness.Proc o : bots) {
                if (o == b) continue;
                assertTrue(others.contains(o.name + " "), b.name + " should see " + o.name + ": " + others);
            }
        }
    }

    @Test
    @Order(2)
    void chatReachesEveryoneThenFadesOut() {
        int[] marks = marks();
        alice.send("chat hello party");
        for (int i = 0; i < bots.size(); i++) bots.get(i).awaitFrom(marks[i], "EVENT chat Alice: hello party", 15);
        int visible = Integer.parseInt(bob.ask("chatlines", "EVENT chatlines (\\d+)", 10).group(1));
        assertTrue(visible >= 1, "the new message is shown");
        Harness.sleep(11_000);
        int later = Integer.parseInt(bob.ask("chatlines", "EVENT chatlines (\\d+)", 10).group(1));
        assertEquals(0, later, "messages fade out after a while");
    }

    @Test
    @Order(3)
    void pickingUpAnItemIsSharedWithEveryone() {
        String id = null;
        for (String cls : new String[]{"Gold", "Potion", "Food", "Scroll", "Wand", "Armor", "Sword"}) {
            Matcher m = alice.ask("interact " + cls + " report", "EVENT interact (?:report \\S+ (\\d+)|none)", 10);
            if (m.group(1) != null) {
                id = m.group(1);
                break;
            }
        }
        assertTrue(id != null, "level has a pickable item");
        String before = alice.ask("inv", "EVENT inv (.*)", 10).group(1);
        alice.ask("interact " + id + " use", "EVENT interact use", 10);
        Harness.sleep(2500);
        String after = alice.ask("inv", "EVENT inv (.*)", 10).group(1);
        assertTrue(!before.equals(after), "Alice's inventory changed: " + before + " -> " + after);
        bob.ask("interact " + id + " report", "EVENT interact none " + id, 10);
        cara.ask("interact " + id + " report", "EVENT interact none " + id, 10);
    }

    @Test
    @Order(4)
    void hittingAMonsterIsSharedWithEveryone() {
        Matcher m = alice.ask("interact Monster report", "EVENT interact (?:report \\S+ (\\d+) .* hp=(-?\\d+)|none)", 10);
        assertTrue(m.group(1) != null, "level has a monster");
        String id = m.group(1);
        int hp = Integer.parseInt(m.group(2));
        alice.ask("interact " + id + " hit", "EVENT interact hit", 10);
        Harness.sleep(2000);
        Matcher seen = bob.ask("interact " + id + " report", "EVENT interact (?:report \\S+ \\d+ .* hp=(-?\\d+)|none)", 10);
        // either the monster is hurt or it died and was removed for everyone
        assertTrue(seen.group(1) == null || Integer.parseInt(seen.group(1)) < hp,
                "Bob sees the damage Alice dealt (hp before " + hp + ", now " + seen.group(0) + ")");
    }

    @Test
    @Order(5)
    void usingADoorIsSharedWithEveryone() {
        Matcher m = alice.ask("interact Door report", "EVENT interact (?:report \\S+ (\\d+) .* door=(\\w+)|none)", 10);
        if (m.group(1) == null) return; // some layouts have no doors
        String id = m.group(1);
        String state = m.group(2);
        alice.ask("interact " + id + " use", "EVENT interact use", 10);
        Harness.sleep(2500);
        String now = bob.ask("interact " + id + " report", "EVENT interact report \\S+ \\d+ .* door=(\\w+)", 10).group(1);
        String alicesView = alice.ask("interact " + id + " report", "EVENT interact report \\S+ \\d+ .* door=(\\w+)", 10).group(1);
        assertEquals(alicesView, now, "everyone sees the same door state");
    }

    @Test
    @Order(6)
    void stairsMoveTheWholeParty() {
        int[] marks = marks();
        alice.send("stairs down");
        awaitLevel(marks, levelNum + 1);
    }

    @Test
    @Order(7)
    void aDeadPlayerSpectatesTheOthers() {
        int mark = cara.mark();
        server.send("kill Cara");
        cara.awaitFrom(mark, "EVENT died", 20);
        cara.awaitFrom(mark, "EVENT spectating (Alice|Bob)", 20);
        alice.await("EVENT chat \\*: Cara has died", 10);
    }

    @Test
    @Order(8)
    void reachingANewLevelRevivesTheDead() {
        int mark = cara.mark();
        int[] marks = marks();
        bob.send("stairs down");
        awaitLevel(marks, levelNum + 1);
        cara.awaitFrom(mark, "EVENT revived", 30);
        String others = alice.ask("others", "EVENT others (.*)", 10).group(1);
        assertTrue(others.contains("Cara ") && others.contains("alive;"), "Cara is back: " + others);
    }

    @Test
    @Order(9)
    void whenEveryoneDiesTheGameEnds() {
        int[] marks = marks();
        for (Harness.Proc b : bots) server.send("kill " + b.name);
        for (int i = 0; i < bots.size(); i++) bots.get(i).awaitFrom(marks[i], "EVENT gameover", 30);
        server.await("game over: all players are dead", 10);
    }

    @Test
    @Order(10)
    void clientRemembersTheLastServer() throws Exception {
        Path file = h.workDir.resolve("Alice").resolve("save").resolve("delveronline.properties");
        assertTrue(Files.exists(file), "settings written to the client's data dir");
        Properties p = new Properties();
        p.load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        assertEquals("127.0.0.1:" + h.port, p.getProperty("lastServer"));
        assertEquals("Alice", p.getProperty("playerName"));
    }

    @Test
    @Order(11)
    void noErrorsWereLogged() {
        for (Harness.Proc p : Arrays.asList(server, alice, bob, cara)) {
            assertTrue(p.errors().isEmpty(), p.name + " logged errors:\n" + String.join("\n", p.errors()));
        }
    }
}
