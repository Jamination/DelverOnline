#!/bin/sh
# Starts Delver with the Delver Online multiplayer mod (Linux).
# The Steam installation is only read from: the mod is loaded as a Java agent and all
# settings/saves of the modded game live in DELVERONLINE_DATA.
#
#   DELVER_DIR         Delver install (default: Steam library location)
#   DELVERONLINE_DATA  where the modded game keeps options, saves and the last server
#   DELVERONLINE_JAR   path to delveronline.jar (default: next to this script)
#   DELVERONLINE_NO_STEAM=1  don't sign in to Steam (lets a second copy run next to a Steam-started one)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR="${DELVERONLINE_JAR:-$HERE/delveronline.jar}"
[ -f "$JAR" ] || JAR="$HERE/../build/libs/delveronline.jar"
DELVER_DIR="${DELVER_DIR:-$HOME/.local/share/Steam/steamapps/common/Delver}"
DATA_DIR="${DELVERONLINE_DATA:-${XDG_DATA_HOME:-$HOME/.local/share}/DelverOnline}"

if [ ! -f "$DELVER_DIR/delver.jar" ]; then
    echo "Delver not found in $DELVER_DIR (set DELVER_DIR)" >&2
    exit 1
fi
if [ ! -f "$JAR" ]; then
    echo "delveronline.jar not found (set DELVERONLINE_JAR)" >&2
    exit 1
fi
JAVA="$DELVER_DIR/jre-linux/linux64/bin/java"
[ -x "$JAVA" ] || JAVA=java

mkdir -p "$DATA_DIR"
if [ -n "$DELVERONLINE_NO_STEAM" ] && [ "$DELVERONLINE_NO_STEAM" != 0 ]; then
    # without steam_appid.txt the Steam API stays offline; the game runs normally
    rm -f "$DATA_DIR/steam_appid.txt"
elif [ ! -f "$DATA_DIR/steam_appid.txt" ]; then
    # lets the Steam API identify the game when started outside of Steam
    cp "$DELVER_DIR/steam_appid.txt" "$DATA_DIR/" 2>/dev/null || true
fi
cd "$DATA_DIR"
# DELVERONLINE_JVM_OPTS: extra JVM options, e.g. -Ddelveronline.fpslog=true to log frames per second
# shellcheck disable=SC2086
exec "$JAVA" $DELVERONLINE_JVM_OPTS -javaagent:"$JAR" -cp "$DELVER_DIR/delver.jar:$JAR" \
    -Duser.country=US -Duser.language=en \
    com.interrupt.dungeoneer.DesktopStarter "$@"
