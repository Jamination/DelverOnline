package com.delveronline.it;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

/**
 * A modded server: players need exactly its mods to join (and are told what to change), and the mod's items and
 * monsters are shared like the rest of the game. Uses the small mod in src/test/mods/testmod.
 */
class ModsTest {
    @Test
    void playersNeedTheServersModsAndModdedContentIsShared() throws Exception {
        Path mod = Paths.get(Harness.PROJECT_DIR, "src", "test", "mods", "testmod").toAbsolutePath();
        try (Harness h = new Harness("mods")) {
            Harness.Proc server = h.server("--mods", mod.toString());
            server.await("running 1 mod: Delver Online Test Mod", 5);

            // without the mod: refused, and told which one to install
            Harness.Proc plain = h.bot("Plain", "--mods", "none");
            plain.await("session closed: Your mods don't match the server's\\. Install or enable: Delver Online Test Mod\\.", 120);

            // an edited copy of the mod is a different version
            Path changed = h.workDir.resolve("changed").resolve("testmod");
            copy(mod, changed);
            Files.write(changed.resolve("data").resolve("items.dat"), "\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            Harness.Proc edited = h.bot("Edited", "--mods", changed.toString());
            edited.await("session closed: Your mods don't match the server's\\. Different version: Delver Online Test Mod\\.", 120);
            server.await("refused Edited .*: mods differ", 10);

            // with the same mod: in, and the mod's content is the same for everyone
            Harness.Proc alice = h.bot("Alice", "--mods", mod.toString());
            Harness.Proc bob = h.bot("Bob", "--mods", mod.toString());
            for (Harness.Proc b : new Harness.Proc[]{alice, bob}) {
                b.send("wander off");
                b.await("EVENT level \\d+ 0 \\?\\?\\?", 120);
                server.send("god " + b.name);
            }
            Harness.sleep(2000);
            server.ask("give Alice Testing Blade", "gave Testing Blade to Alice", 10);
            Harness.sleep(1500);
            alice.ask("inv", "EVENT inv .*Sword\\(Testing Blade\\)", 10);
            alice.ask("throw Testing Blade", "EVENT threw Sword", 10);
            Harness.sleep(1500);
            bob.ask("interact Sword report", "EVENT interact report Sword \\d+ .*name=\"Testing Blade\"", 10);

            server.ask("spawn TESTSLIME Bob", "spawned TESTSLIME next to Bob", 10);
            Harness.sleep(1500);
            // the mod's monster, with the mod's stats (a plain slime has 4 hp)
            alice.ask("interact Monster report", "EVENT interact report Monster \\d+ .*hp=9 name=\"TESTSLIME\"", 10);
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
    }
}
