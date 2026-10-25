#!/usr/bin/env bash
# Installs BlueMap for Bedrock next to a BDS running in Docker (container started from
# mc-bedrock-server/docker-compose.yml, BDS inside "screen -S mc-server").
#
#   sudo ./install.sh
#
# The jar is taken next to this script (bluemap-bedrock.jar) or downloaded from the latest release
# of JAR_REPO on GitHub. The behavior pack is extracted from the jar.
#
# What it does (each step is skipped when already done):
#   1. Java 25 runtime in /opt/bluemap/jre (downloaded from Adoptium) if no java >= 25 is installed
#   2. BlueMap behavior pack (from the jar) copied into the server data (development_behavior_packs/bluemap),
#      added to the world's world_behavior_packs.json (existing packs are kept),
#      content-log-console-output-enabled=true in server.properties
#      (both files are backed up as *.bluemap-backup first)
#   3. BlueMap in /opt/bluemap with its config (console read through docker logs / docker exec)
#   4. systemd service "bluemap" (map on port 8100), low CPU priority so BDS always comes first
#   5. Optionally restarts BDS (clean "stop" first) so it loads the add-on
#
# Variables: CONTAINER (mc-bedrock-server), SCREEN (mc-server), INSTALL_DIR (/opt/bluemap),
#            HEAP (1G), RENDER_THREADS (1), RENDER_CAVES (no), JAR_REPO (polatrk/BlueMap)
set -euo pipefail

CONTAINER=${CONTAINER:-mc-bedrock-server}
SCREEN=${SCREEN:-mc-server}
INSTALL_DIR=${INSTALL_DIR:-/opt/bluemap}
HEAP=${HEAP:-1G}
RENDER_THREADS=${RENDER_THREADS:-1}
RENDER_CAVES=${RENDER_CAVES:-no}
JAR_REPO=${JAR_REPO:-polatrk/BlueMap}
HERE=$(cd "$(dirname "$0")" && pwd)
PACK_ID=6f1d3a52-9c4e-4b8a-bb0e-1d2a7c5e9f01

say() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root (sudo ./install.sh): the Docker volume is only readable by root"
command -v docker >/dev/null || die "docker not found"
docker inspect "$CONTAINER" >/dev/null 2>&1 || die "container '$CONTAINER' not found (set CONTAINER=...)"
command -v python3 >/dev/null || die "python3 is needed to edit JSON files (apt install python3)"
command -v curl >/dev/null || die "curl not found"

# ---- locate the server data ---------------------------------------------------------------------
DATA=$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/server-data"}}{{.Source}}{{end}}{{end}}' "$CONTAINER")
[ -n "$DATA" ] && [ -f "$DATA/server.properties" ] || die "cannot find the /server-data mount of $CONTAINER"
LEVEL=$(grep '^level-name=' "$DATA/server.properties" | cut -d= -f2- | tr -d '\r')
WORLD="$DATA/worlds/$LEVEL"
[ -d "$WORLD/db" ] || die "world not found: $WORLD"
say "Server data: $DATA"
say "World: $WORLD"

# ---- 1. Java 25 ---------------------------------------------------------------------------------
JAVA=""
if command -v java >/dev/null; then
  v=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
  [ "${v:-0}" -ge 25 ] && JAVA=$(command -v java)
fi
if [ -z "$JAVA" ] && [ -x "$INSTALL_DIR/jre/bin/java" ]; then JAVA="$INSTALL_DIR/jre/bin/java"; fi
if [ -z "$JAVA" ]; then
  case "$(uname -m)" in x86_64) ARCH=x64 ;; aarch64|arm64) ARCH=aarch64 ;; *) die "unsupported CPU $(uname -m)" ;; esac
  say "Downloading a Java 25 runtime (Eclipse Temurin) to $INSTALL_DIR/jre"
  mkdir -p "$INSTALL_DIR/jre"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/25/ga/linux/$ARCH/jre/hotspot/normal/eclipse" \
    | tar xz -C "$INSTALL_DIR/jre" --strip-components=1
  JAVA="$INSTALL_DIR/jre/bin/java"
fi
say "Java: $JAVA"

# ---- BlueMap jar -------------------------------------------------------------------------------------
mkdir -p "$INSTALL_DIR/bluemap"
if [ -f "$HERE/bluemap-bedrock.jar" ]; then
  cp "$HERE/bluemap-bedrock.jar" "$INSTALL_DIR/"
else
  say "Downloading bluemap-bedrock.jar from the latest release of $JAR_REPO"
  curl -fsSL -o "$INSTALL_DIR/bluemap-bedrock.jar.tmp" "https://github.com/$JAR_REPO/releases/latest/download/bluemap-bedrock.jar"
  mv "$INSTALL_DIR/bluemap-bedrock.jar.tmp" "$INSTALL_DIR/bluemap-bedrock.jar"
fi

# ---- 2. add-on in the server --------------------------------------------------------------------
say "Installing the BlueMap behavior pack"
TMP=$(mktemp -d)
"$JAVA" -jar "$INSTALL_DIR/bluemap-bedrock.jar" --export-addon "$TMP" >/dev/null
rm -rf "$DATA/development_behavior_packs/bluemap"
mkdir -p "$DATA/development_behavior_packs/bluemap"
python3 -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TMP/bluemap-console.mcpack" "$DATA/development_behavior_packs/bluemap"
rm -rf "$TMP"

WBP="$WORLD/world_behavior_packs.json"
[ -f "$WBP" ] && [ ! -f "$WBP.bluemap-backup" ] && cp -p "$WBP" "$WBP.bluemap-backup"
python3 - "$WBP" "$PACK_ID" <<'EOF'
import json, os, sys
path, pack = sys.argv[1], sys.argv[2]
packs = json.load(open(path)) if os.path.exists(path) else []
packs = [p for p in packs if p.get("pack_id") != pack] + [{"pack_id": pack, "version": [1, 0, 0]}]
json.dump(packs, open(path, "w"), indent=2)
print("   world behavior packs:", len(packs))
EOF

PROPS="$DATA/server.properties"
[ -f "$PROPS.bluemap-backup" ] || cp -p "$PROPS" "$PROPS.bluemap-backup"
if grep -q '^content-log-console-output-enabled=' "$PROPS"; then
  sed -i 's/^content-log-console-output-enabled=.*/content-log-console-output-enabled=true/' "$PROPS"
else
  echo 'content-log-console-output-enabled=true' >> "$PROPS"
fi

# ---- 3. BlueMap -------------------------------------------------------------------------------------
say "Configuring BlueMap in $INSTALL_DIR"
if [ ! -f "$INSTALL_DIR/bluemap/bedrock.conf" ]; then
  cat > "$INSTALL_DIR/bluemap/bedrock.conf" <<EOF
bds-folder: "$DATA"
console-mode: "external"
console-command: "docker logs -f --tail 0 $CONTAINER"
command-template: "docker exec $CONTAINER screen -S $SCREEN -p 0 -X stuff \"{cmd}\\\\r\""
save-interval: 30
addon-port: 0
addon-token: ""
EOF
fi
# First start: BlueMap writes its default configs, then waits for the resource download to be accepted.
if [ ! -f "$INSTALL_DIR/bluemap/core.conf" ]; then
  say "Generating BlueMap configs"
  (cd "$INSTALL_DIR" && timeout 60 "$JAVA" -jar bluemap-bedrock.jar </dev/null >/dev/null 2>&1 || true)
  [ -f "$INSTALL_DIR/bluemap/core.conf" ] || die "BlueMap did not create its configs, run it by hand: cd $INSTALL_DIR && $JAVA -jar bluemap-bedrock.jar"
  sed -i 's/^accept-download: .*/accept-download: true/' "$INSTALL_DIR/bluemap/core.conf"
  sed -i "s/^render-thread-count: .*/render-thread-count: $RENDER_THREADS/" "$INSTALL_DIR/bluemap/core.conf"
  sed -i 's/^hires-slider-max: .*/hires-slider-max: 1500/; s/^hires-slider-default: .*/hires-slider-default: 350/; s/^lowres-slider-max: .*/lowres-slider-max: 10000/; s/^lowres-slider-default: .*/lowres-slider-default: 3000/' "$INSTALL_DIR/bluemap/webapp.conf"
  if [ "$RENDER_CAVES" = "yes" ]; then
    sed -i 's/^remove-caves-below-y: .*/remove-caves-below-y: -10000/' "$INSTALL_DIR"/bluemap/maps/*.conf
  fi
fi

# ---- 4. systemd service -------------------------------------------------------------------------------
say "Creating the systemd service 'bluemap'"
cat > /etc/systemd/system/bluemap.service <<EOF
[Unit]
Description=BlueMap for Bedrock Dedicated Server
After=docker.service network-online.target
Wants=docker.service

[Service]
WorkingDirectory=$INSTALL_DIR
ExecStart=$JAVA -Xmx$HEAP -jar $INSTALL_DIR/bluemap-bedrock.jar
Restart=on-failure
RestartSec=10
# Rendering is CPU heavy: let BDS always come first.
Nice=10
CPUWeight=20
IOWeight=20

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable bluemap >/dev/null

# ---- 5. restart BDS ---------------------------------------------------------------------------------------
read -r -p "Restart BDS now so it loads the add-on (players get disconnected)? [y/N] " answer
if [ "${answer,,}" = "y" ]; then
  say "Stopping BDS cleanly"
  docker exec "$CONTAINER" screen -S "$SCREEN" -p 0 -X stuff $'say BlueMap install: restart in 10s\r' || true
  sleep 10
  docker exec "$CONTAINER" screen -S "$SCREEN" -p 0 -X stuff $'stop\r' || true
  for _ in $(seq 1 60); do docker exec "$CONTAINER" pgrep bedrock_server >/dev/null 2>&1 || break; sleep 1; done
  docker restart "$CONTAINER" >/dev/null
  say "BDS restarted, check that the logs contain 'Pack Stack ... BlueMap': docker logs $CONTAINER | grep -i bluemap"
else
  say "Restart BDS later (stop in its console, then: docker restart $CONTAINER) to load the add-on."
fi

systemctl restart bluemap
say "Done. Map: http://$(hostname -I | awk '{print $1}'):8100/  -  logs: journalctl -u bluemap -f"
