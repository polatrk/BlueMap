#!/bin/sh
# Runs on the BDS machine, as the ssh "forced command" of BlueMap's key: the key can only
# read the BDS console or send one console line, nothing else.
#
# Install (BDS machine):
#   sudo install -m 755 bluemap-bridge.sh /usr/local/bin/bluemap-bridge.sh
#   then add the line printed by the BlueMap container to ~/.ssh/authorized_keys of a user
#   allowed to run docker (member of the "docker" group).
#
# Settings: BDS_CONTAINER (mc-bedrock-server), BDS_SCREEN (mc-server), BDS_LOG (/var/log/server.log,
# console log file inside the container), in /etc/default/bluemap-bridge.
set -eu
[ -f /etc/default/bluemap-bridge ] && . /etc/default/bluemap-bridge
C="${BDS_CONTAINER:-mc-bedrock-server}"
S="${BDS_SCREEN:-mc-server}"
L="${BDS_LOG:-/var/log/server.log}"

case "${SSH_ORIGINAL_COMMAND:-}" in
  logs)
    # Live console output (the add-on's [BEDMAP] lines, save query answers, player connections).
    # Read from the console log file inside the container when there is one, so "docker logs" of
    # BDS can filter these lines out without hiding them from BlueMap.
    if docker exec "$C" test -f "$L"; then
      exec docker exec "$C" tail -F -n 0 "$L" 2>&1
    fi
    exec docker logs -f --tail 0 "$C" 2>&1
    ;;
  cmd)
    # One console command on stdin. "stuff" types it into the screen session running BDS.
    IFS= read -r line || true
    [ -n "$line" ] || exit 0
    # screen's "stuff" interprets backslashes and ^X sequences: escape them so the line arrives as is.
    line=$(printf '%s' "$line" | sed -e 's/\\/\\\\/g' -e 's/\^/\\^/g')
    exec docker exec "$C" screen -S "$S" -p 0 -X stuff "$line$(printf '\r')"
    ;;
  *)
    echo "bluemap-bridge: only 'logs' and 'cmd' are allowed" >&2
    exit 1
    ;;
esac
