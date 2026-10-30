package com.delveronline.server;

import com.delveronline.Mp;
import com.delveronline.net.ByteWriter;
import com.delveronline.proto.Msg;
import com.interrupt.dungeoneer.entities.Item;
import com.interrupt.dungeoneer.entities.Monster;
import com.interrupt.dungeoneer.game.Game;
import com.interrupt.dungeoneer.game.Level;

import java.util.Locale;

/** Server console commands (read from stdin, executed on the server thread). */
final class Commands {
    private final DelverServer server;

    Commands(DelverServer server) {
        this.server = server;
    }

    void execute(String line) {
        line = line.trim();
        if (line.isEmpty()) return;
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String a1 = parts.length > 1 ? parts[1] : null;
        String rest = parts.length > 1 ? line.substring(parts[0].length()).trim() : "";
        try {
            switch (cmd) {
                case "help":
                case "?":
                    out("Commands:\n"
                            + "  status                 server, level and performance summary\n"
                            + "  players | list         connected players\n"
                            + "  say <message>          chat as the server\n"
                            + "  kick <player> [reason] disconnect a player\n"
                            + "  kill <player>          kill a player\n"
                            + "  revive <player|all>    bring dead players back at the level entrance\n"
                            + "  goto <level number>    move the whole party to a dungeon level\n"
                            + "  spawn <monster> [player] spawn a monster next to a player\n"
                            + "  give <player> <item>   give an item by name\n"
                            + "  newrun                 restart the dungeon from the beginning\n"
                            + "  stop | quit            shut the server down");
                    break;
                case "status":
                    out(server.world.describe());
                    out(server.stats.summaryLine(server));
                    break;
                case "players":
                case "list":
                    if (server.players().isEmpty()) out("no players connected");
                    for (ServerPlayer p : server.players()) {
                        out(String.format(Locale.ROOT, "  #%d %-16s %s hp %d/%d  at (%.1f, %.1f)  rtt %d ms", p.id, p.name,
                                p.dead ? "DEAD " : "alive", p.proxy.hp, p.proxy.maxHp, p.proxy.x, p.proxy.y,
                                (int) p.conn.getRttMs()));
                    }
                    break;
                case "say":
                    if (rest.isEmpty()) {
                        out("usage: say <message>");
                        break;
                    }
                    server.broadcastChat("Server", rest);
                    out("[Server] " + rest);
                    break;
                case "kick": {
                    ServerPlayer p = player(a1);
                    if (p == null) break;
                    String reason = parts.length > 2 ? parts[2] : "Kicked by the server.";
                    server.kick(p, reason);
                    out("kicked " + p.name);
                    break;
                }
                case "kill": {
                    ServerPlayer p = player(a1);
                    if (p == null) break;
                    p.send(new ByteWriter(2).writeByte(Msg.S_KILL));
                    out("killed " + p.name);
                    break;
                }
                case "revive":
                    if ("all".equalsIgnoreCase(a1)) {
                        for (ServerPlayer p : server.players()) revive(p);
                    } else {
                        ServerPlayer p = player(a1);
                        if (p != null) revive(p);
                    }
                    break;
                case "goto":
                    if (a1 == null) {
                        out("usage: goto <level number>");
                        break;
                    }
                    server.world.gotoLevel(Integer.parseInt(a1));
                    out("party moved to level " + server.world.levelNum() + " (" + server.world.levelName() + ")");
                    break;
                case "spawn":
                    spawn(a1, parts.length > 2 ? parts[2] : null);
                    break;
                case "give":
                    give(a1, parts.length > 2 ? parts[2] : null);
                    break;
                case "newrun":
                case "restart":
                    for (ServerPlayer p : server.players()) p.dead = false;
                    server.world.newRun();
                    out("new run started");
                    break;
                case "stop":
                case "quit":
                case "exit":
                    server.broadcastChat("Server", "Server is shutting down.");
                    server.stop();
                    break;
                default:
                    out("unknown command '" + cmd + "' (type 'help')");
            }
        } catch (RuntimeException e) {
            out("command failed: " + e.getMessage());
        }
    }

    private void revive(ServerPlayer p) {
        if (!p.dead) {
            out(p.name + " is alive");
            return;
        }
        p.dead = false;
        p.proxy.isDead = false;
        server.world.sendLevelTo(p); // re-enter the current level at its entrance, alive
        server.broadcastChat(null, p.name + " has been revived.");
        server.broadcastPlayers();
        out("revived " + p.name);
    }

    private void spawn(String monsterName, String playerName) {
        if (monsterName == null) {
            out("usage: spawn <monster name> [player]");
            return;
        }
        ServerPlayer target = playerName != null ? player(playerName) : firstAlive();
        if (target == null) {
            out("no living player to spawn next to");
            return;
        }
        Level level = server.world.level();
        Monster m = Game.GetMonsterManager().GetMonster(level.theme, monsterName);
        if (m == null) m = Game.GetMonsterManager().GetMonster("DUNGEON", monsterName);
        if (m == null) {
            out("unknown monster '" + monsterName + "'");
            return;
        }
        float rot = target.proxy.rot;
        m.x = target.proxy.x + (float) Math.sin(rot) * 1.5f;
        m.y = target.proxy.y + (float) Math.cos(rot) * 1.5f;
        m.z = target.proxy.z;
        m.Init(level, target.proxy.level);
        level.SpawnEntity(m);
        out("spawned " + m.name + " next to " + target.name);
    }

    private void give(String playerName, String itemName) {
        if (playerName == null || itemName == null) {
            out("usage: give <player> <item name>");
            return;
        }
        ServerPlayer p = player(playerName);
        if (p == null) return;
        Item item = Game.GetItemManager().FindItem(itemName, Item.ItemCondition.normal);
        if (item == null) {
            out("unknown item '" + itemName + "'");
            return;
        }
        p.sendGiveItem(item);
        out("gave " + item.GetName() + " to " + p.name);
    }

    private ServerPlayer firstAlive() {
        for (ServerPlayer p : server.players()) {
            if (p.inWorld()) return p;
        }
        return null;
    }

    private ServerPlayer player(String nameOrId) {
        if (nameOrId == null) {
            out("missing player name");
            return null;
        }
        ServerPlayer p = server.findPlayer(nameOrId);
        if (p == null) out("no player '" + nameOrId + "'");
        return p;
    }

    private static void out(String s) {
        Mp.log(s);
    }
}
