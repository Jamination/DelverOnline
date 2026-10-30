package com.delveronline.it;

import org.junit.jupiter.api.Test;

/**
 * The end of a run: the orb carrier leaves the dungeon through the first level's exit, everyone is taken to the
 * ending level together, and the final trigger shows every player the victory screen.
 */
class EndingTest {
    @Test
    void escapingWithTheOrbWinsTheGameForEveryone() throws Exception {
        try (Harness h = new Harness("ending")) {
            Harness.Proc server = h.server();
            Harness.Proc alice = h.bot("Alice", "--seed", "11");
            Harness.Proc bob = h.bot("Bob", "--seed", "12");
            for (Harness.Proc b : new Harness.Proc[]{alice, bob}) {
                b.send("wander off");
                b.await("EVENT level \\d+", 120);
                server.send("god " + b.name);
            }

            int am = alice.mark(), bm = bob.mark();
            server.send("goto 1");
            alice.awaitFrom(am, "EVENT level \\d+ 1 Dungeon", 90);
            bob.awaitFrom(bm, "EVENT level \\d+ 1 Dungeon", 90);

            server.ask("give Alice Boss/Orb", "gave .* to Alice", 10);
            Harness.sleep(1500); // inventory sync to the server
            alice.ask("inv", "EVENT inv .*QuestItem", 10);

            am = alice.mark();
            bm = bob.mark();
            alice.ask("escape", "EVENT escape via", 10);
            alice.awaitFrom(am, "EVENT chat \\*: The party escaped the dungeon!", 60);
            alice.awaitFrom(am, "EVENT level \\d+ 1 \\?\\?\\?", 90);
            bob.awaitFrom(bm, "EVENT level \\d+ 1 \\?\\?\\?", 90);

            // the ending's final trigger
            am = alice.mark();
            bm = bob.mark();
            alice.ask("interact TriggeredGameOver use", "EVENT interact use TriggeredGameOver", 15);
            alice.awaitFrom(am, "EVENT win", 30);
            bob.awaitFrom(bm, "EVENT win", 30);
            server.await("the party won", 10);
        }
    }
}
