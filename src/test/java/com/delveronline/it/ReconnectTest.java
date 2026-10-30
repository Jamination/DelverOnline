package com.delveronline.it;

import org.junit.jupiter.api.Test;

/**
 * A player who drops out and rejoins with the same name during a run gets their character back: items and, if they
 * were dead, their death. A new run forgets departed players.
 */
class ReconnectTest {
    @Test
    void rejoiningPlayersGetTheirCharacterBack() throws Exception {
        try (Harness h = new Harness("reconnect")) {
            Harness.Proc server = h.server();
            Harness.Proc alice = h.bot("Alice", "--seed", "21");
            Harness.Proc bob = h.bot("Bob", "--seed", "22");
            for (Harness.Proc b : new Harness.Proc[]{alice, bob}) {
                b.send("wander off");
                b.await("EVENT level \\d+", 120);
            }

            // Alice picks up something a new character never has, then drops out
            server.ask("give Alice Boss/Orb", "gave .* to Alice", 10);
            Harness.sleep(4000); // the character report goes out every 3 s
            alice.send("quit");
            server.await("Alice left", 20);

            // ... and comes back with it
            alice = h.bot("Alice", "--seed", "23");
            alice.send("wander off");
            alice.await("EVENT restored \\d+ items", 120);
            alice.await("EVENT level \\d+", 60);
            server.await("Alice returned: character restored", 10);
            alice.ask("inv", "EVENT inv .*QuestItem", 10);

            // a player who leaves dead comes back dead and spectates
            int m = alice.mark();
            server.send("kill Alice");
            alice.awaitFrom(m, "EVENT died", 30);
            Harness.sleep(4000);
            alice.send("quit");
            server.await("Alice left", 20);
            alice = h.bot("Alice", "--seed", "24");
            alice.send("wander off");
            alice.await("EVENT restored \\d+ items hp 0", 120);
            alice.await("EVENT spectating", 60);
            server.await("Alice returned: character restored \\(still dead\\)", 10);

            // a new run starts everyone from scratch
            alice.send("quit");
            server.await("Alice left", 20);
            server.send("newrun");
            bob.await("EVENT level \\d+ 0 ", 90);
            Harness.sleep(9000); // the server only accepts players a few seconds after a run starts
            alice = h.bot("Alice", "--seed", "25");
            alice.send("wander off");
            alice.await("EVENT level \\d+", 120);
            alice.ask("inv", "EVENT inv ", 10);
            if (alice.since(0).stream().anyMatch(l -> l.contains("EVENT restored") || l.matches(".*EVENT inv .*QuestItem.*")))
                throw new AssertionError("Alice kept her old character after a new run");
        }
    }
}
