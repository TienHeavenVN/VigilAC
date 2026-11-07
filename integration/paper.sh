#!/usr/bin/env bash
# Downloads and boots an isolated Paper test server for live VigilAC testing.
# The server runs OFFLINE mode, allow-flight=true and RCON on 127.0.0.1:25575.
# It is never exposed to the network. Usage:
#   PAPER_VERSION=1.21.8 integration/paper.sh <server-dir> <port> <packetevents:0|1>
set -euo pipefail
DIR="${1:?server dir}"; PORT="${2:?port}"; PACKETEVENTS="${3:-0}"
PAPER_VERSION="${PAPER_VERSION:-1.21.8}"
API="https://fill.papermc.io/v3/projects/paper"
UA="VigilAC-CI/1.0.0 (github.com/TienHeavenVN/VigilAC)"
mkdir -p "$DIR/plugins"
cd "$DIR"

if [ ! -s paper.jar ]; then
  echo "== resolving Paper $PAPER_VERSION STABLE build from Fill v3 =="
  CODE=$(curl -sS --retry 3 --retry-delay 2 -H "User-Agent: $UA" -w '%{http_code}' -o builds.json "$API/versions/$PAPER_VERSION/builds") || CODE="curl-failed"
  echo "Fill v3 builds endpoint HTTP $CODE (version $PAPER_VERSION)"
  BUILDID=$(jq -r 'map(select(.channel=="STABLE")) | (.[0].id // "")' builds.json 2>/dev/null || true)
  NAME=$(jq -r 'map(select(.channel=="STABLE")) | (.[0].downloads["server:default"].name // "")' builds.json 2>/dev/null || true)
  URL=$(jq -r 'map(select(.channel=="STABLE")) | (.[0].downloads["server:default"].url // "")' builds.json 2>/dev/null || true)
  SHA=$(jq -r 'map(select(.channel=="STABLE")) | (.[0].downloads["server:default"].checksums.sha256 // "")' builds.json 2>/dev/null || true)
  if [ -z "$BUILDID" ] || [ -z "$URL" ] || [ -z "$SHA" ]; then
    echo "::error title=PaperMeta::HTTP $CODE id='$BUILDID' url='$URL' sha='$SHA'"; exit 1
  fi
  echo "Paper $PAPER_VERSION STABLE build: $BUILDID ($NAME)"
  CODE2=$(curl -sSL --retry 3 --retry-delay 2 -H "User-Agent: $UA" -w '%{http_code}' -o paper.jar "$URL") || CODE2="curl-failed"
  echo "download HTTP $CODE2"
  echo "$SHA  paper.jar" | sha256sum -c -
fi

cp "$(git rev-parse --show-toplevel)/build/libs/VigilAC.jar" plugins/VigilAC.jar

if [ "$PACKETEVENTS" = "1" ] && [ ! -s plugins/packetevents-spigot.jar ]; then
  PE_URL="https://github.com/retrooper/packetevents/releases/download/v2.14.0/packetevents-spigot-2.14.0.jar"
  echo "downloading packetevents from $PE_URL"
  curl -sSL --retry 3 --retry-delay 2 -o plugins/packetevents-spigot.jar "$PE_URL"
fi

printf 'eula=true\n' > eula.txt
{
  echo 'online-mode=false'
  echo 'sync-chunk-writes=false'
  echo 'enable-rcon=true'
  echo 'rcon.password=vigilac-ci'
  echo 'rcon.port=25575'
  echo "server-port=$PORT"
  echo 'motd=VigilAC live test'
  echo 'view-distance=4'
  echo 'simulation-distance=4'
  echo 'spawn-protection=0'
  echo 'difficulty=normal'
  echo 'gamemode=survival'
  echo 'spawn-monsters=true'
  echo 'allow-flight=true'
  echo 'max-tick-time=120000'
} > server.properties

echo "paper test server prepared in $DIR"
