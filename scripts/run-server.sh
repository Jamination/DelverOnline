#!/bin/sh
# Starts a Delver Online dedicated server (Linux). Type 'help' in the console for commands.
#
#   DELVER_DIR               Delver install (default: Steam library location)
#   DELVERONLINE_SERVER_DATA working directory for the server's own level saves
#   DELVERONLINE_JAR         path to delveronline.jar (default: next to this script)
# Options: --port <udp port> --name <name> --max-players <1-8>  (see --help)
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
JAR="${DELVERONLINE_JAR:-$HERE/delveronline.jar}"
[ -f "$JAR" ] || JAR="$HERE/../build/libs/delveronline.jar"
DELVER_DIR="${DELVER_DIR:-$HOME/.local/share/Steam/steamapps/common/Delver}"
DATA_DIR="${DELVERONLINE_SERVER_DATA:-${XDG_DATA_HOME:-$HOME/.local/share}/DelverOnline/server}"

if [ ! -f "$DELVER_DIR/delver.jar" ]; then
    echo "Delver not found in $DELVER_DIR (set DELVER_DIR)" >&2
    exit 1
fi
JAVA="$DELVER_DIR/jre-linux/linux64/bin/java"
[ -x "$JAVA" ] || JAVA=java

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"
exec "$JAVA" -Xmx1g -javaagent:"$JAR" -cp "$DELVER_DIR/delver.jar:$JAR" \
    -Duser.country=US -Duser.language=en \
    com.delveronline.server.ServerMain "$@"
