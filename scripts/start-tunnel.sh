#!/bin/sh
# Starts the playit.gg tunnel agent for this PC (friends connect to the tunnel address; it forwards to UDP 27960 here).
# The agent and its secret key live in .tools/playit (not in git). Stop it with Ctrl+C.
cd "$(dirname "$0")/../.tools/playit" || exit 1
exec ./playit --secret-path secret.key --socket-path /tmp/delveronline-playit.sock -l playit.log
