# Delver Online

Client/server multiplayer for [Delver](https://store.steampowered.com/app/249630/Delver/) (up to 8 players) over a
reliable-UDP protocol. It is a mod for the Steam version of the game: the original `delver.jar` is loaded unchanged
and the multiplayer code is woven in at start-up by a Java agent, so the Steam installation is never modified.

## Features

- **Dedicated server** running the real Delver simulation headlessly: level generation, monsters, traps, doors,
  triggers, loot and the whole run from the camp to the ending. It also has a console with admin commands.
- **Joining from the main menu**: a native *Multiplayer* button next to *Options* opens a join panel drawn in Delver's own
  UI style. The last server and your name are remembered.
- **Everything synchronized**: levels are streamed from the server, world entities are replicated field by field and
  interpolated, and every interaction runs on the server for everyone (hits, door/lever/stairs use, pickups, thrown
  items, projectiles, spells, shop/dialogue triggers). Sounds and particle effects are replicated too.
- **The party travels together**: when anyone takes stairs or a warp, everyone moves to the new level.
- **Death and spectating**: a dead player watches the others (click or ←/→ switches who; Escape still opens the pause
  menu). When the party reaches a dungeon level it has never been to, everyone dead is revived. When every player is
  dead, the run ends with Delver's own game-over screen, exactly like single player.
- **Rejoining keeps your character**: if you drop out and join again with the same name during the same run, you get
  your items, equipment, gold, keys, health, experience and look back (as of a few seconds before you left). If you
  left dead you come back dead and spectate, unless the party reached a new level meanwhile. A new run starts
  everyone fresh. Names are not protected: whoever joins with your name gets your character.
- **Other players** look like one of Delver's humanoid characters, picked at random when they join: the dagger
  thief, the eyepatch brute, the skeleton, the skeleton mage, the goblin warrior, the hooded mage or the zombie (all
  seven before any repeats). They use that character's own walk, attack, hurt and death animations. A weapon painted
  into the character is replaced by the item the player really holds, which sits in the hand and follows the swing;
  empty-handed characters swing it through an arc during their attack. The off-hand item is in the other hand. They
  flash red when hurt, and their name floats above them while they are in sight.
- **Chat** in the bottom-left corner. Press **Y** to type and **Enter** to send. New lines are shown to everyone and fade out
  after a few seconds. If you have bound Y to a game action, the chat key falls back to T, U or Enter, whichever is
  free.
- Linux and Windows launch scripts. The protocol, files and paths are platform independent.

## Requirements

- Delver from Steam (`delver.jar` and its bundled Java 8 runtime are used as-is).
- To build: any JDK 8+ (the Gradle wrapper falls back to a JDK in `.tools/jdk-*` when none is installed). The release
  executables also need Go 1.21+ (`go` on `PATH`, or `GO=/path/to/go`).

## Build

```sh
./gradlew build        # compiles, runs the unit + multiplayer scenario tests, produces build/libs/delveronline.jar
./gradlew dist         # build/dist/DelverOnline/ (+ .zip): jar, launch scripts, this README
./gradlew release      # build/release/: single-file executables + one .zip per platform, see below
```

If Delver is not in the default Steam location, pass `-PdelverDir=/path/to/Delver` (or set `DELVER_DIR`).

## Play

Start the game with the mod:

| | Linux | Windows |
|---|---|---|
| client | `scripts/run-client.sh` | `scripts\run-client.bat` |
| server | `scripts/run-server.sh [--port 27960] [--name ...]` | `scripts\run-server.bat ...` |

Or use the single-file executables from `./gradlew release` instead of the scripts. They need no Java and no other
files: each one carries the mod and starts it with the Java runtime that comes with Delver.

| | Linux (x64) | Windows (x64) |
|---|---|---|
| client | `DelverOnline-linux-x64` | `DelverOnline-windows-x64.exe` |
| server | `DelverOnline-Server-linux-x64 [--port 27960] ...` | `DelverOnline-Server-windows-x64.exe ...` |

They find Delver through `DELVER_DIR`, then the folder they are in (or its parent), then the Steam libraries
(including extra library folders). The mod is unpacked once per version into the user cache folder
(`~/.cache/DelverOnline`, `%LOCALAPPDATA%\DelverOnline`). The Windows game executable has no console; it logs to
`client.log` in its data folder and shows a message box if it cannot start. The same environment variables as the
scripts apply (except `DELVERONLINE_JAR`).

In the main menu, click **Multiplayer**, enter `host` or `host:port` (default UDP port **27960**) and a name, then **Join**.
Single player is unaffected. The modded game keeps its own options and saves in `~/.local/share/DelverOnline`
(`%APPDATA%\DelverOnline` on Windows), so it never touches your Steam saves.

Environment variables used by the scripts: `DELVER_DIR` (game install), `DELVERONLINE_DATA` (client data dir),
`DELVERONLINE_SERVER_DATA` (server working dir), `DELVERONLINE_JAR`, and `DELVERONLINE_JVM_OPTS` (extra JVM options;
`-Ddelveronline.fpslog=true` logs in-game frames per second every 10 s).

**Two game windows on one PC.** Steam closes a second running copy of Delver. Start the extra copy without the Steam
sign-in and with its own data folder:

```sh
DELVERONLINE_NO_STEAM=1 DELVERONLINE_DATA=~/.local/share/DelverOnline-2 scripts/run-client.sh
```

On Windows: `set DELVERONLINE_NO_STEAM=1`, `set DELVERONLINE_DATA=%APPDATA%\DelverOnline-2`, then `scripts\run-client.bat`.
Without the sign-in, Steam features such as achievements are off for that copy.

The server needs UDP port 27960 (or `--port`) reachable by the players.

### Server console

| command | effect |
|---|---|
| `status` | level, tick time, bandwidth, consistency counters |
| `players` / `list` | connected players with health, position, ping |
| `say <message>` | chat as the server |
| `kick <player> [reason]` | disconnect a player |
| `kill <player>` / `revive <player\|all>` | kill or revive a player |
| `god <player>` | toggle invulnerability |
| `goto <level>` | move the whole party to a dungeon level |
| `spawn <monster> [player]` | spawn a monster next to a player |
| `give <player> <item>` | give an item by name |
| `butcher` | kill every monster on the level |
| `newrun` | restart the dungeon from the camp |
| `fieldstats` | which replicated fields use the most bandwidth |
| `stop` | shut down |

Server options: `--port`, `--name`, `--max-players` (1-8), `--stats-file <json>` (performance and consistency summary written on
exit), `--allow-cheats` (lets clients change level without standing on stairs; for testing), `--no-console`.

## How it works

- `agent` / `hooks`: a Byte Buddy agent weaves small advices into Delver methods (level tick, entity hit/use,
  pickups, level transitions, audio, messages, overlays, input, main menu). Every advice is a no-op unless a
  multiplayer session is active.
- `net`: reliable UDP. Each datagram carries a reliable ordered channel (fragmentation, ordered delivery,
  delivery callbacks) and an unreliable channel with per-message delivered/lost notification. Acknowledgements
  use the latest sequence plus a 64-bit history.
- `server`: `ServerWorld` runs Delver's own game code on a no-op GL. Players are `PlayerProxy` entities in the level.
  While each entity ticks, `Game.instance.player` is the nearest living player, so monsters, traps and triggers react
  to whoever is closest. Effects aimed at a player (damage, experience, status effects, items) are forwarded to that
  player's client.
- `repl`: entities are serialized with Delver's Kryo configuration for spawns and level snapshots. Afterwards only
  changed fields travel: a reflective schema per class, per-client dirty masks, and the latest value resent when a
  packet is lost. Particles go out as compact one-shot effects.
- `client`: replaces the level tick with interpolation of server state plus local simulation of purely visual
  entities. The local player stays client-side (smooth movement) and anything it does to the world becomes a
  request to the server.

## Tests

```sh
./gradlew test       # transport tests + MultiplayerScenarioTest + EndingTest (real server + headless game clients)
./gradlew soakTest   # 8 headless clients for 10 minutes (-PsoakSeconds=N to change)
```

The scenario test starts the real server and real game clients (headless, no-op GL). It checks that players see
each other, chat reaches everyone and fades, pickups, hits and doors are shared, stairs move the party, the dead
spectate, a new level revives them, a party wipe ends the game, and the client remembers the last server. The
ending test gives a player the orb, has the party escape from the first dungeon level, and checks that everyone
reaches the ending and sees the victory screen.

The soak test runs eight clients with deaths, revives, level changes and chat. It asserts that:

- under 1% of position/health/existence samples differ from the server;
- the server keeps at least 60 ticks per second in 95% of seconds;
- every client keeps at least 95% of its single-player frame rate.
