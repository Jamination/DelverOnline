package com.delveronline.it;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Starts the real dedicated server and headless bot clients as separate JVMs (the game's own Java runtime, with the
 * built mod jar as agent) and lets tests talk to them over stdin/stdout.
 */
final class Harness implements AutoCloseable {
    static final String AGENT_JAR = System.getProperty("delveronline.jar");
    static final String DELVER_JAR = System.getProperty("delveronline.delverJar");
    static final String DELVER_DIR = System.getProperty("delveronline.delverDir");
    static final String PROJECT_DIR = System.getProperty("delveronline.projectDir", ".");
    /**
     * Mods for servers and bots (later {@code --mods} arguments override it): none by default, so tests see the plain
     * game whatever is installed; {@code -PtestMods=all} runs them with the installed mods.
     */
    static final String MODS = System.getProperty("delveronline.mods", "none");

    final Path workDir;
    final int port;
    final List<Proc> procs = new ArrayList<>();
    private final Path stagedJar;

    Harness(String name) throws IOException {
        if (AGENT_JAR == null || DELVER_JAR == null) fail("run through gradle (jar paths are passed as system properties)");
        workDir = Paths.get(PROJECT_DIR, "build", "it", name);
        deleteRecursively(workDir);
        Files.createDirectories(workDir);
        // processes load classes lazily from the jar: give them a private copy that a rebuild cannot change
        stagedJar = workDir.resolve("delveronline.jar");
        Files.copy(Paths.get(AGENT_JAR), stagedJar);
        try (DatagramSocket s = new DatagramSocket(0)) {
            port = s.getLocalPort();
        }
    }

    static String java() {
        File bundled = new File(DELVER_DIR, "jre-linux/linux64/bin/java");
        if (bundled.canExecute()) return bundled.getAbsolutePath();
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    Proc start(String name, String mainClass, List<String> jvmArgs, String... args) throws IOException {
        Path dir = workDir.resolve(name);
        Files.createDirectories(dir);
        List<String> cmd = new ArrayList<>();
        cmd.add(java());
        cmd.addAll(jvmArgs);
        cmd.add("-javaagent:" + stagedJar.toAbsolutePath());
        cmd.add("-cp");
        cmd.add(DELVER_JAR + File.pathSeparator + stagedJar.toAbsolutePath());
        cmd.add(mainClass);
        cmd.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Proc p = new Proc(name, pb.start(), dir.resolve("output.log"));
        procs.add(p);
        return p;
    }

    Proc server(String... extra) throws IOException {
        List<String> args = new ArrayList<>(Arrays.asList("--port", String.valueOf(port), "--allow-cheats", "--stats-file",
                workDir.resolve("server-stats.json").toAbsolutePath().toString(), "--mods", MODS));
        args.addAll(Arrays.asList(extra));
        Proc p = start("server", "com.delveronline.server.ServerMain", Arrays.asList("-Xmx1g"), args.toArray(new String[0]));
        p.await("listening on UDP", 90);
        return p;
    }

    Proc bot(String name, String... extra) throws IOException {
        List<String> args = new ArrayList<>(Arrays.asList("--port", String.valueOf(port), "--name", name, "--mods", MODS));
        args.addAll(Arrays.asList(extra));
        return start(name, "com.delveronline.bot.BotMain", Arrays.asList("-Xmx384m"), args.toArray(new String[0]));
    }

    @Override
    public void close() {
        for (Proc p : procs) p.destroy();
    }

    static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(x -> x.toFile().delete());
        }
    }

    /** A running JVM whose output is collected line by line. */
    static final class Proc {
        final String name;
        final Process process;
        private final List<String> lines = new ArrayList<>();
        private final Writer stdin;
        private final Writer log;

        Proc(String name, Process process, Path logFile) throws IOException {
            this.name = name;
            this.process = process;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            this.log = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8);
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                        synchronized (log) {
                            log.write(line);
                            log.write('\n');
                            log.flush();
                        }
                    }
                } catch (IOException ignored) {
                }
            }, "harness-" + name);
            t.setDaemon(true);
            t.start();
        }

        int mark() {
            synchronized (lines) {
                return lines.size();
            }
        }

        List<String> since(int mark) {
            synchronized (lines) {
                return new ArrayList<>(lines.subList(Math.min(mark, lines.size()), lines.size()));
            }
        }

        void send(String command) {
            try {
                stdin.write(command);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                fail(name + " is not accepting input: " + e);
            }
        }

        /** Waits for a line matching {@code regex} (searched from the beginning) and returns the match. */
        Matcher await(String regex, int seconds) {
            return awaitFrom(0, regex, seconds);
        }

        Matcher awaitFrom(int mark, String regex, int seconds) {
            Pattern p = Pattern.compile(regex);
            long end = System.currentTimeMillis() + seconds * 1000L;
            int scanned = mark;
            while (true) {
                List<String> fresh;
                synchronized (lines) {
                    fresh = new ArrayList<>(lines.subList(Math.min(scanned, lines.size()), lines.size()));
                }
                for (String l : fresh) {
                    Matcher m = p.matcher(l);
                    if (m.find()) return m;
                }
                scanned += fresh.size();
                if (System.currentTimeMillis() > end) {
                    fail(name + ": no line matching /" + regex + "/ within " + seconds + "s. Last output:\n" + tail(25));
                }
                if (!process.isAlive() && fresh.isEmpty()) {
                    fail(name + " exited (" + process.exitValue() + ") before /" + regex + "/. Last output:\n" + tail(25));
                }
                sleep(100);
            }
        }

        /** Sends a command and waits for the answering line. */
        Matcher ask(String command, String regex, int seconds) {
            int m = mark();
            send(command);
            return awaitFrom(m, regex, seconds);
        }

        String tail(int n) {
            synchronized (lines) {
                return String.join("\n", lines.subList(Math.max(0, lines.size() - n), lines.size()));
            }
        }

        List<String> errors() {
            List<String> out = new ArrayList<>();
            synchronized (lines) {
                for (String l : lines) {
                    if (l.contains("Exception") || l.contains("failed to tick") || l.contains("error handling")) out.add(l);
                }
            }
            return out;
        }

        void destroy() {
            if (process.isAlive()) {
                send("quit");
                send("stop");
                try {
                    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
            try {
                synchronized (log) {
                    log.close();
                }
            } catch (IOException ignored) {
            }
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
