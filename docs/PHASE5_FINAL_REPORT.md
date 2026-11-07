# Phase 5 — Continuous Player Monitoring: final report

Branch `arena/9ae3a67f-vigilac`, PR #1 (unmerged). Date: 2026-11-07.
Commits: `4ba5349` (feat: monitor package), `86bab8d` (docs), `fc03573` (fix + inventory
per-check evidence), `c010cde` ([load=10]), `deb4594` ([load=25]), `e19989d` ([load=50]).

## What was delivered

VigilAC now maintains a per-player, continuously updated monitoring state and a bounded
behavioral history, correlatable over time — not just isolated packet-triggered checks:

- **Continuous state block** per player (fixed-size, O(1)): position/rotation, ground flag,
  sprint/sneak/glide/swim/vehicle flags, potion effects (Speed/Levitation/Slow Falling),
  game mode, world (with change detection), EMA ping, EMA horizontal/vertical velocity,
  ground transitions, last-landing descent, damage timestamps **separated by cause**,
  teleport / velocity-impulse / world-change timestamps, packet rate (via existing counter),
  total observations. Stored on `PlayerData`; freed on disconnect.
- **Bounded evidence history**: shared `Evidence` model (timestamp, type, source
  server/client/monitor, confidence, detail, optional producing check) in a per-player
  `EvidenceStore` ring — 256 entries max, 90 s TTL, O(1) amortized add, bounded queries.
  Client-reported bits are recorded as `client`-source observations, never as ground truth.
- **Behavior analysis over sequences**: ground-transition tracking, accumulated descent at
  landing, suspicious-landing streak (NoFall), velocity EMAs, evidence-window counts
  (`countByTypeSince`), `CorrelationEngine` (supporting/suppressing facts, confidence).
- **Unrelated damage no longer suppresses NoFall evidence** — this was a real defect:
  `ConfounderListener` reset the suspicious-landing streak on ANY `EntityDamageEvent`.
  Now only FALL-cause damage exculpates (listener backstop + check grace window);
  non-FALL damage is recorded for the monitor and has zero weight for NoFall.
  Regression test: `NoFallCheckTest.unrelatedDamageDoesNotSuppressNoFallEvidence`.
- **False-positive control without weakening checks**: no check disabled, no threshold
  raised. VL math in every check is unchanged. NoFall's exculpation now uses server facts
  only (fall-cause damage + pre-existing grace windows); correlation output is
  *informational* (confidence in debug output and evidence trail) and never gates.
- **Detection engine exposure**: per check, per player — state
  (CLEAR/SUSPECT/VIOLATION/EXEMPT/DISABLED), VL, confidence (EMA of VL/alert ratio,
  documented heuristic), evidence count, last-detection time, exemption reason (fresh ≤ 5 s),
  last evidence detail/time. Rendered by `/vigilac monitor <player>` (`vigilac.monitor`
  permission, op default) with recent evidence and raw packet rate.
- **Inventory observation**: bounded O(1) `INVENTORY_CLICK` evidence (view type only);
  no check consumes it yet — context for future inventory-related analysis.
- **Debug/inspection**: existing `/vigilac debug` unchanged; monitor command added;
  exemption reasons are recorded by checks (fast-break instant-break materials, no-fall
  environmental exemptions) and surfaced.

Architecture and semantics: [MONITORING.md](MONITORING.md).

## Verification

Local sandbox has no JDK; all verification ran on CI (same methodology as Phases 3–4).

Unit tests (Build and test job, run 42344555194, green):
**tests=129, skipped=0, failures=0, errors=0** — includes 27 new `ac.vigil.monitor` tests and
the updated NoFall suite (fall-cause vs non-fall-cause damage). One earlier push (`86bab8d`)
**failed CI** with a single test error (`fallingWithDamageNeverFlags`) — root cause was the
test using synthetic timestamps against the monitor's wall-clock default, fixed in `fc03573`
(explicit timestamps, injected clocks); the failure and fix are reported here for honesty.

Compatibility (run 42344555194, all green):
- Compile + unit tests on 1.21, 1.21.1, 1.21.3–1.21.11 (JDK 21) — 10 targets
- Compile + unit tests on 26.1.2, 26.2, 26.3 (JDK 25) — 3 targets
- Artifact builds: VigilAC (1.21.x) and VigilAC-26x (JDK 25) — both built
- Runtime smoke (real Paper boot): 1.21.8, 26.1.2, 26.2, 26.3 — all green
- Live bot harness (scripted mineflayer bots, Paper 1.21.8): both legs green (not real clients)
- 26.x artifacts remain boot-verified only — no 26.x live harness legs (known gap, see ROADMAP)

Load legs (45 s, isolated CI Paper 1.21.8 server, monitoring included):

| Leg | Bots | failed_bots | RSS growth | per-player RSS | TPS (1m, mid-run → post) | GC pauses / total | stop→exit |
|-----|------|-------------|------------|----------------|--------------------------|-------------------|-----------|
| 10  | 10   | 0           | +76 MB     | ~7 MB          | 20.0 → 20.0              | 94 / 762 ms       | 2.54 s    |
| 25  | 25   | 0           | +117 MB    | ~4 MB          | 19.0 → 19.0 (recovering) | 92 / 1.0 s        | 3.56 s    |
| 50  | 50   | 0           | +123 MB    | ~2 MB          | ~12.8 → 18.07/19.31      | 112 / 1.2 s       | 3.56 s    |

50-bot mid-run TPS dip matches the pre-monitoring Phase 4 baseline (dipped to ~11.7 there);
monitoring adds no measurable cost at this scale (RSS growth comparable, per-player cost flat).
Job display name reads "Load test (10 bots)" for marker-triggered legs; the resolved bot count
is printed in the log (`BOTS=25`/`BOTS=50`, verified by the 25/50-online `list` output).

CI runs (branch `arena/9ae3a67f-vigilac`):
- fc03573 — push 42344555194 **success**; PR 42344560163 **success**
- c010cde — push 42345485824 **success** (load 10 leg green); PR 42345490166 **success**
- deb4594 — push 42345952231 **success** (load 25 leg green); PR 42345954949 **success**
- e19989d — push 42346727257 **success** (load 50 leg green); PR 42346731359 **success**

## False-positive control statement

No check was disabled and no threshold was raised or lowered. NoFall's VL gate (3 consecutive
suspicious landings), damage grace (500 ms), min fall distance (3.5) and per-landing VL are all
unchanged. The behavioral changes are (a) unrelated damage no longer excuses a landing streak
(spec-mandated), (b) environmental exemptions are explicitly recorded with reasons, (c)
correlation contributes display-only confidence.

## Honest limitations

- **Meteor Client: UNABLE-TO-TEST** in this environment (no real client available). Nothing
  here demonstrates detection against real cheat clients; behavioral claims rest on unit tests
  and the scripted CI harness only.
- Live bot harness = scripted mineflayer bots, not real clients; 26.x artifact is boot-verified
  but has no live harness legs.
- Confidence values are heuristics (EMA of VL/alert ratio), not calibrated probabilities.
- Suppressing correlation facts are informational; they never gate VL.
- Packetevents is absent in CI (server coalesces move events to ~20/s); packetevents path gets
  the same monitor coverage at integration time but is untested here.
- Inventory observation is not consumed by any check; it is bounded context, not detection.
- Load legs were single runs on shared CI runners (TPS dips vary run to run; Phase 4 vs Phase 5
  50-bot dips are consistent).
- Alert-only remains the default; automatic punishment remains disabled.
- PR #1 is open and unmerged; `main` is untouched.

## What is NOT claimed

No production sign-off (real-client validation required), no calibrated false-positive rate, no
detection for checks that remain VLS-only by design, no 26.x live-harness coverage.
