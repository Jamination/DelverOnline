package com.delveronline.server;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessApplication;
import com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration;
import com.delveronline.Mp;
import com.delveronline.agent.DelverOnlineAgent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated server entry point. Must be started with this jar as a Java agent and the game's delver.jar on the
 * classpath (see the run-server scripts).
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        final ServerConfig config;
        try {
            config = ServerConfig.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        if (!DelverOnlineAgent.isInstalled()) {
            System.err.println("The server must be started with -javaagent:delveronline.jar (use run-server.sh / run-server.bat).");
            System.exit(2);
        }
        final DelverServer server = new DelverServer(config);
        HeadlessApplicationConfiguration hc = new HeadlessApplicationConfiguration();
        hc.renderInterval = 0f;
        new HeadlessApplication(new ApplicationAdapter() {
            final long period = 1_000_000_000L / DelverServer.TICK_RATE;
            long next;
            boolean stopped;

            @Override
            public void create() {
                try {
                    server.start();
                } catch (IllegalArgumentException e) {
                    System.err.println("Server failed to start: " + e.getMessage());
                    System.exit(2);
                } catch (Exception e) {
                    System.err.println("Server failed to start: " + e);
                    e.printStackTrace();
                    System.exit(1);
                }
                if (config.console) startConsole(server);
                next = System.nanoTime();
            }

            @Override
            public void render() {
                if (stopped) return;
                if (!server.running) {
                    stopped = true;
                    server.shutdownNow();
                    Gdx.app.exit();
                    System.exit(0);
                    return;
                }
                try {
                    server.step();
                } catch (RuntimeException e) {
                    Mp.log("tick failed: " + e);
                    e.printStackTrace();
                }
                next += period;
                long now = System.nanoTime();
                if (next - now > 0) {
                    LockSupport.parkNanos(next - now);
                } else if (now - next > period * 120) {
                    next = now;
                }
            }
        }, hc);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (server.running) {
                server.running = false;
                server.writeStatsOnly();
            }
        }));
    }

    private static void startConsole(DelverServer server) {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    server.commands.add(line);
                }
            } catch (Exception ignored) {
                // stdin closed: keep running without a console
            }
        }, "DelverOnline-console");
        t.setDaemon(true);
        t.start();
    }
}
