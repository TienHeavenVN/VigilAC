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

if [ "${CAPTURE_CI:-0}" = "1" ]; then
  # Consent-capture CI: boot the server with a capture-enabled config so the live
  # harness can verify the corpus end-to-end. Generated fresh each run; the committed
  # config.yml (capture disabled) is untouched. The pseudonym key is random per run.
  mkdir -p plugins/VigilAC
  TOPLEVEL="$(git rev-parse --show-toplevel)"
  KEY=$(python3 -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())")
  KEY="$KEY" TOPLEVEL="$TOPLEVEL" python3 - <<'PY'
import os, re
src = os.path.join(os.environ['TOPLEVEL'], 'src/main/resources/config.yml')
dst = 'plugins/VigilAC/config.yml'
text = open(src, encoding='utf-8').read()
block = (
    "capture:\n"
    "  enabled: true\n"
    "  scenario: ci-capture-consent\n"
    "  label: LEGITIMATE\n"
    "  label-source: SERVER_TELEMETRY_CURATION\n"
    "  label-confidence: 0.9\n"
    "  client-category: VANILLA\n"
    "  input-category: MOUSE\n"
    "  pseudonym-key: '" + os.environ['KEY'] + "'\n"
    "  pseudonym-rotation-hours: 24\n"
    "  interval-seconds: 1\n"
    "  buffer-capacity: 64\n"
    "  max-total-bytes: 268435456\n"
    "  retention-days: 90\n"
    "  notify-on-join: true\n"
    "  provenance-note: 'ci-capture/1'\n"
)
pattern = re.compile(r'(?ms)^capture:.*?(?=^\S|\Z)')
text2, n = pattern.subn(block, text, count=1)
assert n == 1, 'capture section not found in committed config.yml'
with open(dst, 'w', encoding='utf-8') as fh:
    fh.write(text2)
print('CAPTURE_CI=1 → capture-enabled config written to', dst)
PY
fi

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
