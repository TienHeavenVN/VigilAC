#!/usr/bin/env bash
# Load-test orchestrator for Phase 4 §6: boots isolated Paper + VigilAC,
# joins N concurrent offline-mode bots that sprint-jump for a fixed duration,
# samples TPS/MSPT (RCON), process CPU/RSS (ps) and GC pauses (gc.log), and
# reports everything as ::notice annotations. Never exposed to the network.
#
# Usage: BOTS=10 DURATION_SECONDS=45 integration/load.sh [results-dir]
# Requires: java 21 on PATH, python3, node 22+, npm, jq, curl, sha256sum, ps.
# NOT part of the blocking CI path; run via workflow_dispatch (see ci.yml).
set -uo pipefail
ROOT="$(git rev-parse --show-toplevel)"
BOTS="${BOTS:-10}"
DURATION_SECONDS="${DURATION_SECONDS:-45}"
RESULTS="${1:-$ROOT/integration/out-load}"
PORT=25577
SRV="$ROOT/integration/load-server"
rm -rf "$SRV" "$RESULTS"
mkdir -p "$RESULTS"

echo "== load test: BOTS=$BOTS DURATION=${DURATION_SECONDS}s =="
(cd "$ROOT" && ./gradlew shadowJar --no-daemon -q) || { echo '::error title=Build::shadowJar failed'; exit 1; }
PAPER_VERSION="${PAPER_VERSION:-1.21.8}" "$ROOT/integration/paper.sh" "$SRV" "$PORT" 0 || { echo '::error title=Paper::setup failed'; exit 1; }

echo "== booting server (with GC logging) =="
cd "$SRV"
nohup java -Xms1G -Xmx2G -Xlog:gc*:file=gc.log:time,uptime,tags -jar paper.jar nogui > server.log 2>&1 &
JPID=$!
echo $JPID > ../load.pid
for i in $(seq 1 120); do
  grep -q 'Done (' server.log && break
  grep -q 'Error occurred while enabling VigilAC' server.log && { echo '::error title=PluginEnableFailed::vigilac threw during enable'; tail -n 40 server.log; exit 1; }
  kill -0 "$JPID" 2>/dev/null || { echo '::error title=ServerDied::server exited during boot'; tail -n 40 server.log; exit 1; }
  sleep 2
done
grep -q 'Done (' server.log || { echo '::error title=ServerTimeout::no Done in 240s'; tail -n 60 server.log; exit 1; }
grep -q 'VigilAC enabled:' server.log || { echo '::error title=PluginNotEnabled::success marker missing'; exit 1; }

echo "== deterministic pad =="
FL=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "forceload add -24 -24 24 24"); echo "forceload -> $FL"
case "$FL" in *forceload*|*loaded*) ;; *) echo "::error title=PadForceload::forceload failed: '$FL'"; exit 1;; esac
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "gamerule doMobSpawning false" > /dev/null
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "fill -24 63 -24 24 63 24 minecraft:stone" > /dev/null
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "fill -24 64 -24 24 76 24 minecraft:air" > /dev/null

echo "== installing bot deps =="
(cd "$ROOT/integration/bot" && npm ci --no-audit --no-fund -q) || { echo '::error title=NpmCi::bot deps failed'; exit 1; }

echo "== baseline sample (before bots) =="
RSS0=$(ps -o rss= -p "$JPID" | tr -d ' ')
TPS0=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1 | tr '\n' ' ')
MSPT0=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci mspt | tail -1 | tr '\n' ' ')
echo "::notice title=LoadBaseline::rss_kb=$RSS0 tps=[$TPS0] mspt=[$MSPT0]"

echo "== joining $BOTS bots =="
for i in $(seq 1 "$BOTS"); do
  botname="LB$(printf '%03d' "$i")$(printf '%s' "$$$RANDOM$i" | md5sum | cut -c1-3)"
  (cd "$ROOT/integration/bot" && node loadbot.js "$botname" "$PORT" "$((DURATION_SECONDS * 1000))" "$RESULTS/$botname.json" > "$RESULTS/$botname.console.log" 2>&1) &
  echo "$! $botname" > "$RESULTS/pid-$i"
done

# sample CPU/RSS + RCON tps/mspt every 5s while the bots are in
for t in 5 10 15 20 25 30 35 40 45; do
  [ "$t" -gt "$DURATION_SECONDS" ] && break
  sleep 5
  RSS=$(ps -o rss= -p "$JPID" | tr -d ' ')
  CPU=$(ps -o %cpu= -p "$JPID" | tr -d ' ')
  ONLINE=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "list" | tail -1 | tr '\n' ' ')
  TPS=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1 | tr '\n' ' ')
  MSPT=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci mspt | tail -1 | tr '\n' ' ')
  echo "::notice title=LoadSample-${t}s::rss_kb=$RSS cpu=$CPU tps=[$TPS] mspt=[$MSPT] list=[$ONLINE]"
done

echo "== waiting for bots to finish =="
FAILED_BOTS=0
for i in $(seq 1 "$BOTS"); do
  read -r pid botname < "$RESULTS/pid-$i"
  if ! wait "$pid"; then FAILED_BOTS=$((FAILED_BOTS + 1)); fi
done

echo "== post-run sample =="
RSS1=$(ps -o rss= -p "$JPID" | tr -d ' ')
TPS1=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1 | tr '\n' ' ')
MSPT1=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci mspt | tail -1 | tr '\n' ' ')
VI=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "vigilac info" | tr '\n' '|')

echo "::notice title=LoadPost::rss_kb=$RSS1 tps=[$TPS1] mspt=[$MSPT1] failed_bots=$FAILED_BOTS info=[${VI:0:400}]"
DELTA_RSS=$(( (RSS1 - RSS0) / 1024 ))
PER_PLAYER=$(( BOTS > 0 ? (RSS1 - RSS0) / BOTS / 1024 : 0 ))
echo "::notice title=LoadMemory::total_growth_mb=$DELTA_RSS approx_per_player_mb=$PER_PLAYER"

echo "== GC summary =="
GCLINES=$(wc -l < gc.log 2>/dev/null || echo 0)
GC_PAUSES=$(grep -c 'Pause' gc.log 2>/dev/null || echo 0)
GC_TIME=$(awk '/Pause/ { for (i=1;i<=NF;i++) if ($i ~ /^[0-9.]+ms$/) { gsub("ms","",$i); s+=$i; n++ } } END { if (n>0) printf "%.1f", s; else print 0 }' gc.log 2>/dev/null)
echo "::notice title=LoadGC::gc_log_lines=$GCLINES pauses=$GC_PAUSES approx_total_pause_ms=$GC_TIME"

echo "== shutdown flush timing =="
STOP_START=$(date +%s%N)
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci stop > /dev/null 2>&1 || true
for i in $(seq 1 60); do kill -0 "$JPID" 2>/dev/null || break; sleep 1; done
STOP_END=$(date +%s%N)
echo "::notice title=LoadShutdown::stop_to_exit_ms=$(( (STOP_END - STOP_START) / 1000000 ))"
grep -q 'Disabling VigilAC' server.log || { echo '::error title=PluginNotDisabled::Disabling VigilAC missing after stop'; exit 1; }
ls -la plugins/VigilAC/vigilac-violations.db || { echo '::error title=StorageMissing::db not created'; exit 1; }
grep -Ei 'exception|severe' server.log | grep -i vigilac && { echo '::error title=PluginException::vigilac logged exceptions'; exit 1; }

echo "::notice title=LoadResult::BOTS=$BOTS duration=${DURATION_SECONDS}s failed_bots=$FAILED_BOTS — done"
