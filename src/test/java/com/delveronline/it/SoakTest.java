package com.delveronline.it;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eight headless clients play together for ten minutes ({@code -PsoakSeconds} to change) while players die,
 * get revived and the party changes level. Checks replica consistency, the server tick rate and that clients keep
 * their single-player frame rate. Run with {@code ./gradlew soakTest}.
 */
@Tag("soak")
class SoakTest {
    private static final int PLAYERS = 8;

    @Test
    void eightPlayersStayInSyncWithoutSlowingDown() throws Exception {
        int seconds = Integer.getInteger("delveronline.soakSeconds", 600);
        try (Harness h = new Harness("soak")) {
            // --- single-player baseline: the same headless client, playing alone without a server
            Harness.Proc solo = h.bot("Solo", "--singleplayer", "--seed", "99");
            solo.await("EVENT singleplayer started", 120);
            int soloMark = solo.mark();
            Harness.sleep(65_000);
            double baseline = averageFps(solo.since(soloMark), 1);
            solo.destroy();
            System.out.printf(Locale.ROOT, "[soak] single-player baseline: %.2f fps%n", baseline);

            // --- the multiplayer session
            Harness.Proc server = h.server();
            List<Harness.Proc> bots = new ArrayList<>();
            for (int i = 1; i <= PLAYERS; i++) bots.add(h.bot("P" + i, "--seed", String.valueOf(i)));
            for (Harness.Proc b : bots) b.await("EVENT level \\d+", 180);
            for (Harness.Proc b : bots) server.send("god " + b.name);
            server.send("goto 1");
            for (Harness.Proc b : bots) b.await("EVENT level \\d+ 1 ", 120);
            int[] marks = new int[PLAYERS];
            for (int i = 0; i < PLAYERS; i++) marks[i] = bots.get(i).mark();

            long start = System.currentTimeMillis();
            long end = start + seconds * 1000L;
            Random rnd = new Random(42);
            int round = 0;
            int levelChanges = 0, deaths = 0, revives = 0;
            while (System.currentTimeMillis() < end) {
                Harness.sleep(Math.min(60_000, Math.max(0, end - System.currentTimeMillis())));
                if (System.currentTimeMillis() >= end - 5_000) break;
                round++;
                if (round % 2 == 1) {
                    // somebody dies (and spectates) ...
                    Harness.Proc victim = bots.get(rnd.nextInt(PLAYERS));
                    int m = victim.mark();
                    server.send("kill " + victim.name);
                    victim.awaitFrom(m, "EVENT spectating", 30);
                    deaths++;
                    // ... and comes back when the party reaches a level it has never been to. Wandering bots
                    // also take stairs (up, too), so keep heading down until that happens.
                    Harness.sleep(20_000);
                    Harness.Proc leader = bots.get((bots.indexOf(victim) + 1) % PLAYERS);
                    int vm = victim.mark();
                    boolean revived = false;
                    for (int step = 0; step < 6 && !revived; step++) {
                        leader.send("stairs down");
                        levelChanges++;
                        try {
                            victim.awaitFrom(vm, "EVENT revived", 20);
                            revived = true;
                        } catch (AssertionError notYet) {
                            // the party descended into a level it already knew
                        }
                    }
                    if (!revived) victim.awaitFrom(vm, "EVENT revived", 1);
                    revives++;
                } else {
                    for (Harness.Proc b : bots) b.send("chat round " + round + " from " + b.name);
                }
            }
            String status = server.ask("status", "tick p95.*", 10).group(0);
            List<String> dropped = new ArrayList<>();
            for (Harness.Proc b : bots) {
                if (!b.process.isAlive() || b.since(0).stream().anyMatch(l -> l.contains("EVENT disconnected"))) dropped.add(b.name);
            }
            for (Harness.Proc b : bots) b.send("quit");
            Harness.sleep(3000);
            server.send("stop");
            server.process.waitFor();

            // --- evaluation
            String json = new String(Files.readAllBytes(h.workDir.resolve("server-stats.json")), StandardCharsets.UTF_8);
            long samples = num(json, "samples");
            long reports = num(json, "reports");
            long pos = num(json, "posDesync"), hp = num(json, "hpDesync"), active = num(json, "activeDesync");
            long countMismatches = num(json, "countMismatches");
            long p5 = num(json, "ticksPerSecondP5");
            StringBuilder fpsReport = new StringBuilder();
            double worstRatio = Double.MAX_VALUE;
            for (int i = 0; i < PLAYERS; i++) {
                double fps = averageFps(bots.get(i).since(marks[i]), 1);
                worstRatio = Math.min(worstRatio, fps / baseline);
                fpsReport.append(String.format(Locale.ROOT, " %s=%.2f", bots.get(i).name, fps));
            }
            System.out.println("[soak] " + status);
            System.out.println("[soak] server stats: " + json.replaceAll("\\s+", " "));
            System.out.printf(Locale.ROOT, "[soak] %d s, %d deaths, %d revives, %d level changes%n", seconds, deaths, revives, levelChanges);
            System.out.printf(Locale.ROOT, "[soak] client fps:%s (worst %.1f%% of single-player %.2f)%n", fpsReport, worstRatio * 100, baseline);

            assertTrue(samples > 1000, "enough consistency samples: " + samples);
            assertTrue(pos * 100 <= samples, "position desync <= 1%: " + pos + "/" + samples);
            assertTrue(hp * 100 <= samples, "health desync <= 1%: " + hp + "/" + samples);
            assertTrue(active * 100 <= samples, "existence desync <= 1%: " + active + "/" + samples);
            assertTrue(countMismatches * 20 <= reports, "entity set mismatch <= 5% of reports: " + countMismatches + "/" + reports);
            assertTrue(p5 >= 60, "server ran >= 60 ticks/s in 95% of seconds (p5 = " + p5 + ")");
            assertTrue(worstRatio >= 0.95, "every client kept >= 95% of its single-player frame rate (worst " + worstRatio + ")");
            assertTrue(dropped.isEmpty(), "players disconnected during the session: " + dropped);
            for (Harness.Proc b : bots) {
                assertTrue(b.errors().isEmpty(), b.name + " logged errors: " + b.errors());
            }
            assertTrue(server.errors().isEmpty(), "server logged errors: " + server.errors());
        }
    }

    private static final Pattern FPS = Pattern.compile("EVENT fps ([0-9.]+)");

    /** Mean of the 10-second fps windows, skipping the first {@code skip} (warm-up / level load). */
    static double averageFps(List<String> lines, int skip) {
        List<Double> v = new ArrayList<>();
        for (String l : lines) {
            Matcher m = FPS.matcher(l);
            if (m.find()) v.add(Double.parseDouble(m.group(1)));
        }
        if (v.size() <= skip) return v.isEmpty() ? 0 : v.get(v.size() - 1);
        double sum = 0;
        for (int i = skip; i < v.size(); i++) sum += v.get(i);
        return sum / (v.size() - skip);
    }

    private static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\": (-?[0-9]+)").matcher(json);
        if (!m.find()) throw new AssertionError("no " + key + " in " + json);
        return Long.parseLong(m.group(1));
    }
}
