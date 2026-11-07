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
echo "== building deterministic test pad =="
FL=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "forceload add -24 -24 24 24")
echo "forceload -> $FL"
case "$FL" in *forceload*|*loaded*) ;; *) echo "::error title=PadForceload::forceload did not succeed: '$FL'"; exit 1;; esac
# Natural spawns must not attack the deterministic pad; /summon still works
# under this gamerule, unlike spawn-monsters=false which also suppresses them.
GM=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "gamerule doMobSpawning false")
echo "gamerule -> $GM"
case "$GM" in *doMobSpawning*false*) ;; *) echo "::error title=MobSpawning::gamerule doMobSpawning false did not succeed: '$GM'"; exit 1;; esac
PAD_STONE=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "fill -24 63 -24 24 63 24 minecraft:stone")
echo "pad stone fill -> $PAD_STONE"
PAD_AIR=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "fill -24 64 -24 24 76 24 minecraft:air")
echo "pad air fill -> $PAD_AIR"
case "$PAD_STONE" in *"Successfully filled"*|*"No blocks were filled"*) ;; *) echo "::error title=PadFillFailed::stone fill did not succeed: '$PAD_STONE'"; exit 1;; esac
case "$PAD_AIR" in *"Successfully filled"*|*"No blocks were filled"*) ;; *) echo "::error title=PadFillFailed::air fill did not succeed: '$PAD_AIR'"; exit 1;; esac
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "setworldspawn 0 64 0" || true
python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "tp @s 0 64 0" || true
echo "== pad ready =="
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
      echo "$lsc $botname" >> "$RESULTS/scenario-names.txt"
      echo "--- $lsc as $botname ---"
      (cd "$ROOT/integration/bot" && node bot.js "$botname" "$PORT" "$lsc" "$RESULTS/$lsc.json" > "$RESULTS/$lsc.console.log" 2>&1) || true
    done
    sudo tc qdisc del dev lo root || true
  fi
fi

echo "RCON tps: $(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci tps | tail -1)"
CAPTURE_OK=1
if [ "${CAPTURE_CI:-0}" = "1" ]; then
  echo "== CAPTURE_CI: verifying consent corpus on disk =="
  RESULTS_DIR="$RESULTS" SERVER_DIR="$SRV" python3 - <<'PY'
import glob, json, os, re, sys

results = os.environ['RESULTS_DIR']
server_dir = os.environ['SERVER_DIR']
corpus = glob.glob(os.path.join(server_dir, 'plugins', 'VigilAC', 'capture', 'corpus', 'capture-*.jsonl'))
fail = []
if not corpus:
    fail.append('no corpus files under plugins/VigilAC/capture/corpus/')

required = {'player_pseudonym', 'session_pseudonym', 'mc_version', 'client_category', 'input_category',
            'tps_1m', 'ping_ms', 'feature_schema_hash', 'label', 'label_source', 'label_confidence',
            'collected_utc', 'scenario', 'features'}
allowed = required | {'provenance_note'}
golden = '2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688'
botname = None
try:
    with open(os.path.join(results, 'scenario-names.txt'), encoding='utf-8') as fh:
        for line in fh:
            parts = line.split()
            if len(parts) == 2 and parts[0] == 'capture-consent-smoke':
                botname = parts[1]
except OSError:
    pass
if not botname:
    fail.append('capture-consent-smoke bot name not found in scenario-names.txt')

total = 0
for path in corpus:
    with open(path, encoding='utf-8') as fh:
        for raw in fh:
            line = raw.strip()
            if not line:
                continue
            total += 1
            try:
                row = json.loads(line)
            except Exception as exc:
                fail.append('unparseable line in %s: %s' % (os.path.basename(path), exc))
                continue
            keys = set(row)
            missing, extra = required - keys, keys - allowed
            if missing:
                fail.append('missing keys %s' % sorted(missing))
            if extra:
                fail.append('unexpected keys %s' % sorted(extra))
            if not re.fullmatch(r'[0-9a-f]{32}', str(row.get('player_pseudonym', ''))):
                fail.append('player_pseudonym is not 32-hex')
            if not re.fullmatch(r'[0-9a-f]{32}', str(row.get('session_pseudonym', ''))):
                fail.append('session_pseudonym is not 32-hex')
            if row.get('feature_schema_hash') != golden:
                fail.append('feature_schema_hash mismatch')
            if row.get('label') != 'LEGITIMATE':
                fail.append('label must be owner-declared LEGITIMATE, got: %r' % row.get('label'))
            if row.get('label_source') != 'SERVER_TELEMETRY_CURATION':
                fail.append('label_source mismatch')
            if row.get('scenario') != 'ci-capture-consent':
                fail.append('scenario mismatch')
            if row.get('client_category') != 'VANILLA':
                fail.append('client_category mismatch')
            if not re.fullmatch(r'\d+\.\d+(\.\d+)?', str(row.get('mc_version', ''))):
                fail.append('mc_version not X.Y[.Z]')
            feats = row.get('features')
            if not isinstance(feats, list) or len(feats) != 22:
                fail.append('features must be a 22-element array')
            elif not all(isinstance(x, (int, float)) and x == x and abs(x) != float('inf') for x in feats):
                fail.append('features contain NaN/inf or non-numeric')
            if botname and botname in json.dumps(row):
                fail.append('row contains the consenting bot username!')

if total == 0 and not fail:
    fail.append('corpus files exist but contain zero rows')

# consent store must exist and hold only opaque tokens
consent_path = os.path.join(server_dir, 'plugins', 'VigilAC', 'capture', 'consent.json')
if not os.path.isfile(consent_path):
    fail.append('consent.json missing (consent was granted in the scenario)')
else:
    consent_text = open(consent_path, encoding='utf-8').read()
    if botname and botname in consent_text:
        fail.append('consent store contains the bot username')
    if not re.fullmatch(r'(?s)\{.*[0-9a-f]{32}.*\}', consent_text.strip()):
        fail.append('consent store does not look like the documented token file')

if fail:
    print('CAPTURE_CI FAIL:')
    for item in fail[:20]:
        print(' -', item)
    sys.exit(1)
print('CAPTURE_CI OK: %d rows across %d corpus file(s); schema hash, key set, pseudonyms and labels verified' % (total, len(corpus)))
sys.exit(0)
PY
  cap_rc=$?
  if [ $cap_rc -ne 0 ]; then
    CAPTURE_OK=0
    echo "::error title=CaptureCorpus::consent corpus verification failed (see above)"
  else
    echo "::notice title=CaptureCorpus::consent capture corpus verified (rows parse, 32-hex pseudonyms, schema hash, owner-declared label, no identifiers)"
  fi
  echo "== CAPTURE_CI: admin delete-all over RCON =="
  DEL=$(python3 "$ROOT/tools/rcon.py" 127.0.0.1 25575 vigilac-ci "vigilac capture delete-all confirm" | tail -1)
  echo "delete-all -> $DEL"
  if ls "$SRV"/plugins/VigilAC/capture/corpus/*.jsonl >/dev/null 2>&1; then
    CAPTURE_OK=0
    echo "::error title=CaptureDeleteAll::corpus files remain after 'vigilac capture delete-all confirm'"
  elif [ -f "$SRV/plugins/VigilAC/capture/consent.json" ]; then
    CAPTURE_OK=0
    echo "::error title=CaptureDeleteAll::consent store remains after 'vigilac capture delete-all confirm'"
  else
    echo "::notice title=CaptureDeleteAll::corpus and consent store wiped by admin command"
  fi
  if [ "$CAPTURE_OK" = "1" ]; then
    echo "::notice title=CaptureCI::consent capture lifecycle verified end-to-end on a real Paper server"
  fi
else
  echo "== CAPTURE_CI not set — capture corpus verification skipped =="
fi
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

if [ "${CAPTURE_CI:-0}" = "1" ] && [ "$CAPTURE_OK" = "0" ]; then
  echo "::error title=CaptureCI::consent capture verification FAILED — see annotations above"
  exit 1
fi
