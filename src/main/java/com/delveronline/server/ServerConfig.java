package com.delveronline.server;

import com.delveronline.Mp;

/** Command-line options of the dedicated server. */
public final class ServerConfig {
    public String name = "Delver Online Server";
    public int port = Mp.DEFAULT_PORT;
    public int maxPlayers = Mp.MAX_PLAYERS;
    /** Lets clients change level without standing on stairs (automated tests, debugging). */
    public boolean allowCheats = false;
    /** Optional path for a JSON performance/consistency summary written on shutdown. */
    public String statsFile = null;
    /** Read commands from standard input. */
    public boolean console = true;
    /** {@code --mods}: "all" (default), "none", or a comma-separated list; see {@code ModDiscovery.select}. */
    public String mods = "all";

    public static ServerConfig parse(String[] args) {
        ServerConfig c = new ServerConfig();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--port": c.port = Integer.parseInt(args[++i]); break;
                case "--name": c.name = args[++i]; break;
                case "--max-players":
                    c.maxPlayers = Math.max(1, Math.min(Mp.MAX_PLAYERS, Integer.parseInt(args[++i])));
                    break;
                case "--allow-cheats": c.allowCheats = true; break;
                case "--stats-file": c.statsFile = args[++i]; break;
                case "--no-console": c.console = false; break;
                case "--mods": c.mods = args[++i]; break;
                case "--help":
                case "-h":
                    System.out.println("Delver Online dedicated server\n"
                            + "  --port <udp port>        (default " + Mp.DEFAULT_PORT + ")\n"
                            + "  --name <server name>\n"
                            + "  --max-players <1-8>      (default 8)\n"
                            + "  --allow-cheats           let clients jump between levels (testing)\n"
                            + "  --stats-file <path>      write a JSON performance summary on exit\n"
                            + "  --mods all|none|<list>   mods to run (default all: Delver's installed Workshop\n"
                            + "                           mods and its mods/ folder). A list holds Workshop IDs,\n"
                            + "                           mod titles, folder names or folder paths, comma-separated.\n"
                            + "                           Players need exactly the same mods to join.\n"
                            + "  --no-console             do not read commands from stdin");
                    System.exit(0);
                    break;
                default:
                    throw new IllegalArgumentException("unknown option " + a + " (try --help)");
            }
        }
        return c;
    }
}
