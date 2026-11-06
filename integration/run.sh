#!/usr/bin/env bash
# Live-test orchestrator: boots isolated Paper + VigilAC (+ optional PacketEvents),
# runs every scenario with a fresh offline-mode bot account, then scores
# /vigilac violations per bot over RCON and prints ::notice annotations.
#
# Usage: integration/run.sh <build-libs-VigilAC.jar-dir> [results-dir]
# Requires: java 21 on PATH, python3, node 22+, npm, jq, curl, sha256sum.
set -uo pipefail
ROOT="$(git rev-parse --show-toplevel)"
RESULTS="${2:-$ROOT/integration/out}"
PORT=25567
SRV="$ROOT/integration/server"
PACKETEVENTS="${PACKETEVENTS:-0}"

rm -rf "$SRV" "$RESULTS"
mkdir -p "$RESULTS"

echo "== building plugin =="
(cd "$ROOT" && ./gradlew shadowJar --no-daemon -q) || { echo '::error title=Build::shadowJar failed'; exit 1; }
echo "== preparing server (packetevents=$PACKETEVENTS) =="
"$ROOT/integration/paper.sh" "$SRV" "$PORT" "$PACKETEVENTS" || { echo '::error title=Paper::setup failed'; exit 1; }

echo "== booting server =="
cd "$SRV"
nohup java -Xms512M -Xmx1G -jar paper.jar nogui > server.log 2>&1 &
echo $! > ../live.pid
for i in $(seq 1 120); do
  grep -q 'Done (' server.log && break
  grep -q 'Error occurred while enabling VigilAC' server.log && { echo '::error title=PluginEnableFailed::VigilAC threw during enable'; tail -n 40 server.log; exit 1; }
  sleep 2
done
grep -q 'Done (' server.log || { echo '::error title=ServerTimeout::no Done in 240s'; tail -n 40 server.log; exit 1; }
grep -q 'VigilAC enabled:' server.log || { echo '::error title=PluginNotEnabled::success marker missing'; exit 1; }
echo "server up; installing node deps"
(cd "$ROOT/integration/bot" && npm ci --no-audit --no-fund -q) || { echo '::error title=NpmCi::bot deps failed'; exit 1; }
echo "== starting staff observer bot =="
: > "$RESULTS/staff-events.jsonl"
(cd "$ROOT/integration/bot" && nohup node bot.js StaffObserver "$PORT" __staff__ "$RESULTS/staff-events.jsonl" > /dev/null 2>&1 & echo $! > "$ROOT/integration/staff.pid")
sleep 4
SCENARIOS=$(node -e "console.log(Object.keys(require('$ROOT/integration/bot/scenarios.js').scenarios).join(' '))")
if [ -z "$SCENARIOS" ]; then
  echo '::error title=Harness::could not enumerate scenarios from scenarios.js'
  exit 1
fi
echo "scenarios: $SCENARIOS"

echo "== running scenarios =="
pass=0; fail=0; unable=0
for sc in $SCENARIOS; do
  botname="BotP3$(echo "$sc" | tr -cd 'a-z' | cut -c1-12)"
  # unique-ish account per scenario for clean VL attribution
  botname="${botname}$$"
  echo "--- $sc as $botname ---"
  (cd "$ROOT/integration/bot" && node bot.js "$botname" "$PORT" "$sc" "$RESULTS/$sc.json")
  rc=$?
  if [ $rc -ne 0 ]; then
    echo "::notice title=Scenario-$sc::HARNESS-ERROR (exit $rc) — see integration-server-log artifact / local results"
    unable=$((unable + 1))
  else
    pass=$((pass + 1))
  fi
done

if [ "${RUN_LATENCY_PASS:-0}" = "1" ] && sudo -n true 2>/dev/null; then
  echo "== latency pass: 120ms ± 30ms jitter on loopback =="
  sudo tc qdisc add dev lo root netem delay 120ms 30ms distribution normal || echo "tc netem unavailable — latency pass skipped"
  if tc qdisc show dev lo | grep -q netem; then
    for sc in $SCENARIOS; do
      case "$sc" in legit-*) ;; *) continue;; esac
      lsc="${sc}-lat"
      botname="BotP3$(echo "$sc" | tr -cd 'a-z' | cut -c1-10)lat$$"
      echo "--- $lsc as $botname ---"
      (cd "$ROOT/integration/bot" && node bot.js "$botname" "$PORT" "$lsc" "$RESULTS/$lsc.json") || true
    done
    sudo tc qdisc del dev lo root || true
  fi
fi

echo "RCON tps: $(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1)"
echo "== stopping staff observer =="
if [ -f "$ROOT/integration/staff.pid" ]; then
  kill "$(cat "$ROOT/integration/staff.pid")" 2>/dev/null || true
  sleep 1
fi
echo "staff observer lines: $(wc -l < "$RESULTS/staff-events.jsonl" 2>/dev/null || echo 0)"

echo "== stopping server =="
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci stop > /dev/null 2>&1 || true
for i in $(seq 1 30); do kill -0 "$(cat "$ROOT/integration/live.pid")" 2>/dev/null || break; sleep 2; done
tail -n 15 "$SRV/server.log"

echo "== scoring =="
python3 "$ROOT/integration/score.py" "$RESULTS"

echo "== server log error scan =="
if grep -Ei 'exception|severe|error occurred' "$SRV/server.log" | grep -i vigilac; then
  echo '::notice title=ServerLog::VigilAC logged errors (see above)'
else
  echo '::notice title=ServerLog::no VigilAC exception/severe lines'
fi
echo "RCON tps: $(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1)"

