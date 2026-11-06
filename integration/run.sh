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
SCENARIOS=$(node -e "console.log(Object.keys(require('$ROOT/integration/bot/scenarios.js').scenarios).join(' '))")
if [ -z "$SCENARIOS" ]; then
  echo '::error title=Harness::could not enumerate scenarios from scenarios.js'
  exit 1
fi
echo "scenarios: $SCENARIOS"

echo "== running scenarios =="
pass=0; fail=0; unable=0
: > "$RESULTS/scenario-names.txt"
for sc in $SCENARIOS; do
  # Minecraft usernames are limited to 16 chars: B + hash(scenario) + hash(pid+random)
  botname="B$(printf '%s' "$sc" | md5sum | cut -c1-4)$(printf '%s' "$$$RANDOM$sc" | md5sum | cut -c1-6)"
  echo "$sc $botname" >> "$RESULTS/scenario-names.txt"
  echo "--- $sc as $botname ---"
  (cd "$ROOT/integration/bot" && node bot.js "$botname" "$PORT" "$sc" "$RESULTS/$sc.json" > "$RESULTS/$sc.console.log" 2>&1)
  rc=$?
  if [ $rc -ne 0 ]; then
    TAIL=$(tail -c 1100 "$RESULTS/$sc.console.log" | tr '\n' '|')
    echo "::notice title=Console-$sc::rc=$rc :: $TAIL"
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
      botname="BL$(printf '%s' "$sc" | md5sum | cut -c1-4)lat$(printf '%s' "$$$RANDOM" | md5sum | cut -c1-3)"
      echo "--- $lsc as $botname ---"
      (cd "$ROOT/integration/bot" && node bot.js "$botname" "$PORT" "$lsc" "$RESULTS/$lsc.json" > "$RESULTS/$lsc.console.log" 2>&1) || true
    done
    sudo tc qdisc del dev lo root || true
  fi
fi

echo "RCON tps: $(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1)"
echo "== stopping server =="
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci stop > /dev/null 2>&1 || true
for i in $(seq 1 30); do kill -0 "$(cat "$ROOT/integration/live.pid")" 2>/dev/null || break; sleep 2; done
tail -n 15 "$SRV/server.log"

echo "== raw results inspection =="
python3 - "$RESULTS" <<'PY'
import json, glob, os, sys
vdir = sys.argv[1]
files = sorted(f for f in glob.glob(os.path.join(vdir, '*.json')) if not f.endswith('manifest.json'))
print('result files:', len(files))
tot = {'ok': 0, 'err': 0, 'ev_zero': 0, 'ev_some': 0}
sample = None
for f in files:
    d = json.load(open(f))
    if d.get('harnessError'): tot['err'] += 1
    else: tot['ok'] += 1
    if len(d.get('evidence', [])) == 0: tot['ev_zero'] += 1
    else: tot['ev_some'] += 1
    if os.path.basename(f) == 'illegal-timer.json': sample = d
print('ok:', tot['ok'], 'err:', tot['err'], 'evidence empty:', tot['ev_zero'], 'evidence present:', tot['ev_some'])
if sample:
    print('::notice title=RawSample::' + json.dumps(sample)[:1500])
PY
echo "== VigilAC debug digest from server log =="
python3 - "$RESULTS" "$SRV/server.log" <<'PY'
import re, sys, os, json, collections
vdir, log_path = sys.argv[1], sys.argv[2]
names = {}
try:
    with open(os.path.join(vdir, 'scenario-names.txt')) as fh:
        for line in fh:
            parts = line.split()
            if len(parts) == 2:
                names[parts[1]] = parts[0]
except FileNotFoundError:
    pass
by_sc = collections.defaultdict(list)
counts = collections.Counter()
with open(log_path, errors='replace') as fh:
    for line in fh:
        m = re.search(r'\[VigilAC debug\] \[([a-z-]+)\] ([A-Za-z0-9_]+): (.{0,180})', line)
        if m:
            check, player, detail = m.group(1), m.group(2), m.group(3)
            sc = names.get(player, '?')
            by_sc[sc].append(f'[{check}] {detail}'[:150])
            counts[(sc, check)] += 1
parts = [f'{sc}:{chk}={n}' for (sc, chk), n in counts.most_common(40)]
digest = ' '.join(parts)
print('::notice title=DebugDigest::' + (digest[:1300] if digest else 'NO vigilac debug lines found in server.log'))
with open(os.path.join(vdir, 'debug-by-scenario.json'), 'w') as out:
    json.dump(by_sc, out)
PY

echo "== scoring =="
python3 "$ROOT/integration/score.py" "$RESULTS"

echo "== server log error scan =="
if grep -Ei 'exception|severe|error occurred' "$SRV/server.log" | grep -i vigilac; then
  echo '::notice title=ServerLog::VigilAC logged errors (see above)'
else
  echo '::notice title=ServerLog::no VigilAC exception/severe lines'
fi
echo "RCON tps: $(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1)"

