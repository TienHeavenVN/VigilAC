# VigilAC false-positive analysis (Phase 3)

Phase 3 hard rule honored throughout: **no check was weakened to silence a false positive.** All tuning described below is harness-side or defect repair. Detection thresholds are unchanged relative to Phase 2.

## 1. Legit baseline result

The legit baseline is 16 `legit-*` scenarios run on both PacketEvents legs plus a 120 ms ± 30 ms netem latency pass. Baseline scenarios: stand, sprint-jump, place-bridge, break-stone, attack-zombie, knockback, respawn, teleport (+ every one of them under latency).

**Residual flags (final runs):**

| Scenario | VL observed | Alert fired? | Root cause | Class |
|---|---|---|---|---|
| legit-sprint-jump | 3.7–3.9 (reference run 42255620165, both legs) | **No** (always < alert-vl 5.0) | mineflayer physics model exceeds vanilla sprint speed; sprint flag never registers server-side so walk cap (4.4) applies instead of sprint cap (5.8) | Harness physics-model fidelity |
| legit-sprint-jump (-lat leg) | 1.23 (one leg) | **No** | dt≈47 ms coalesced backlog burst → instant speed 11.0 EMA spike | Plugin defect — **FIXED** (dt<45 ms EMA guard) |

**No other legit scenario ever accrued VL in any run.** 15–16/16 clean per leg depending on the run; the only flagged leg was the same mineflayer artifact.

## 2. FP root causes (in order of impact)

### FP-1 — Mineflayer physics overshoot (sprint-jump trace VL)
Mineflayer `5.3–7.7 b/s` during sprint-jumps vs vanilla `5.612`. The server reports `sprint=false` in every debug line because the bot's server-side sprint flag never sticks (the bot never cleanly transitions into sprinting server-side), so the walk cap applies. The speed check therefore sees sustained >cap movement and accrues VL at `excessVlPerSecond × ratio` ≈ 0.2–0.9 VL/s — all below alert-vl, zero alerts, zero enforcement actions.

**Why not "fix" the check:** lowering caps or raising them would change detection semantics for real servers (Phase 2 froze detection behavior). The correct fix is higher-fidelity legit clients (see §Real clients), which is out of scope here. The residual VL is documented, bounded, and never actionable.

**Classification:** Not a plugin defect. Trace-level evidence only.

### FP-2 — Backlog-burst EMA spike (legit -lat leg)
At 120 ms ± 30 ms netem jitter, Paper drained a queued backlog in one tick; consecutive move events carried ~2 ms wall-clock deltas while carrying real ~0.3-block deltas → instant speeds of 150+ b/s → EMA spiked to 11.0 → ratio 1.5 → 1.23 VL.

**Fix (plugin, smallest component):** `SpeedCheck.evaluate` skips samples with `dt < 45 ms` (event stream below 20 Hz is coalesced by Paper; a sub-45 ms dt is a scheduling artifact, never the player's travel rate). The sample never accrues excess and never touches the EMA. The dt is advanced forward to avoid re-judging the same wall time. Verified: latency leg ema=11.0 spike eliminated (`legit-sprint-jump-lat` clean in subsequent runs); `SpeedCheckTest#backlogBurstDoesNotFabricateSpeed` pins it; `sustainedSprintCheatStillFlags` pins that real cheating still flags.

**FP eliminated:** yes (0 VL post-fix in CI).

### FP-3 — Knockback/teleport/velocity confounders (verified working)
`legit-knockback` (2 zombie hits): velocity grace (base 800 ms + impulse-scaled, ≤2 s) suppresses movement checks around the hit; zero VL in every run. `legit-teleport` / `legit-respawn`: teleport grace (2 s) suppresses speed/fly around server-side tp; zero VL. These gates work as designed; no regressions.

### FP-4 — Combat checks on legit mob hits
`legit-attack-zombie`: aimed single attacks, 400+ ms apart; no aim-assist snap, no auto-clicker regularity, no kill-aura multi-target; zero VL every run. `legit-knockback` also accrued no combat VL.

### FP-5 — Timer/fly on legit gameplay
No legit scenario ever accrued timer, fly, no-fall or reach VL. Timer fires only ≥ ~25 position packets/s (vanilla 20/s is far below); fly requires sustained hover evidence; reach requires >3.03+tolerance distance.

## 3. FPs caused by harness defects (not plugin, not gameplay)

| Defect | FP/FN impact | Fix |
|---|---|---|
| Unaimed attacks dealt 0 damage (1.21.2+ server-side look validation) | Combat checks starved (kill-aura/aim-assist/reach saw nothing; scenario FAIL) | Harness aims before each attack (`lookAndAttack`) |
| Pitch sign inverted in aim-assist scenario | angleToTarget ≈ 2× depression > 6° gate → never a snap | Sign fixed (MC: positive pitch = down) |
| Snap+attack spaced by round-trip + 60–90 ms sleeps | server's `snap-max-interval: 120 ms` broke the chain | back-to-back snap and attack |
| Random world spawn buried bots (mesa cliffs) | fast-place/scaffold/combat evidence impossible on half the legs | deterministic flat pad + tp + grace wait |
| `setworldspawn` doesn't move first-join position | pad was bypassed for joins | bot.js tps to pad after spawn |

## 4. FP rate summary (final CI)

- Legit scenarios with any VL: **1 of 16** (legit-sprint-jump; artifact, sub-alert, no action) — **≈6.3% trace rate, 0% actionable FP rate** (reference run: 15/16 clean on both legs).
- Legit scenarios with alerts/warnings: **0**.
- Cross-leg reproducibility of the sprint-jump trace: consistent (both legs, every run).

## 5. Hard rules honored

- No detection threshold was changed.
- No check output is gated on anything new.
- The dt<45 ms guard discards sub-frame scheduling artifacts; it cannot hide sustained cheats (pinned by `sustainedSprintCheatStillFlags`).
- Every remaining anomaly is reported raw (VL values, debug EMA lines) rather than suppressed.

## 6. Open items (honest)

- The `sprint=false` server flag mystery (mineflayer sprint never registers) is unresolved; it only affects walk-vs-sprint cap selection on the legit baseline. If resolved, trace VL would halve; alerts are zero either way.
- Latency-leg behavior at extreme jitter (>300 ms) is not characterized beyond the 120±30 ms pass.

---

# Phase 4 update (2026-11-07)

## FP-6 (root-caused): combat checks reading as 0 VL — the damage-event funnel, not VL decay

**Symptom.** In the live legs, the auto-clicker/kill-aura/aim-assist scenarios showed debug
evidence in the console digest yet `/vigilac check <bot>` read **0.0** at read time.

**Investigation.**

1. VL decay was the wrong suspect and is now disproven by construction: decay cannot begin until
   `decay-delay-seconds` (5 s) after the LAST violation — i.e., after the scenario has ended, while
   the harness reads VL ~1.5 s after the last attack. Decay physically cannot have erased anything
   in that window. `ViolationDecayTest` pins the whole lifecycle with a controlled clock.
2. The real funnel is `AttackListener`, which keys off `EntityDamageByEntityEvent` — attacks only
   count if they **deal damage**. A 20 HP NoAI zombie dies in 2–4 swings; every subsequent swing in
   the 25-CPS barrage lands on a corpse and emits nothing. The auto-clicker's rolling 20-sample
   interval window never reaches `min-samples = 10`, the rate gate and the robotic-streak gate can
   never engage, and VL converges to 0 regardless of click rate. Aim-assist likewise starves
   (`min-snap-streak = 4`, whiff-starved stream).
   → This is a **detection-availability property** of damage-keyed melee accounting on dying
   targets, not a false positive and not a bug in VL accounting; but it made the live scenarios
   unable to evidence the checks.

**Fix (harness, not checks).** The live scenarios for `auto-clicker`, `kill-aura` and `aim-assist`
now summon **immortal** zombies (`Health:10000f` + `Attributes:[{id:"minecraft:max_health",base:10000}]`)
so every attack deals damage for the full window. No check logic, threshold, gate or VL weight was
changed. Results of the rerun are pinned in LIVE_TEST_RESULTS.md.

**Unit-test pin.** `AutoClickerCheckTest#fewerThanMinSamplesNeverFlags` documents the gate;
`ViolationDecayTest#transientEvidenceIsJudgedAtEventTimeNotAtReadTime` proves event-time evaluation.

## FP-1 status — sprint-jump / server-side sprint flag (open, narrowed)

- mineflayer sends `entity_action start_sprinting` on `setControlState('sprint', true)`
  (verified in mineflayer 4.39.0 source: `plugins/physics.js`).
- Yet the server has reported `sprint=false` for these bots — the reason is under investigation
  (1.21.6+ protocol rework routes player input via a `player_input` packet; whether the server
  honors `entity_action start_sprinting` on 1.21.8 is the open question).
- The harness now probes the flag authoritatively per run
  (`data get entity <bot> Sprinting`, logged as raw evidence in the scenario output).
- Either way the plugin's behavior is unchanged and correct: VL accrues only below alert-vl and
  never fires an alert (alert-only mode; no action). No threshold has been raised.

## FP hardening carried over (unchanged from Phase 3)

- `SpeedCheck` dt<45 ms backlog-burst guard (FP-2 fix), pinned by
  `SpeedCheckTest#backlogBurstDoesNotFabricateSpeed` / `sustainedSprintCheatStillFlags`.
- Alert-only default, no auto-kicks, no check weakened for convenience.

## FP-7 (root-caused, 2026-11-07 run 42334359016): 1.21.2+ server-side attack-cooldown drop — the second damage-event funnel

**Symptom.** Even with immortal zombies (FP-6 fix), the combat scenarios in CI run
`42334359016` delivered 396 (leg 0) / 397 (leg 1) `use_entity` attack packets over 12 s
(~33 CPS) yet the plugin's attack listener saw only **~24–25 damage events per leg**
(~2/s). The combat-cadence checks (`auto-clicker`, `aim-assist`) therefore never accrued
VL: landed CPS ≈ 2 « cap 20.5; landed inter-arrival ≈ 500 ms ≫ snap-max-interval 120 ms,
so the snap gate (`min-snap-streak = 4`) can never engage. VL readouts read `{}`.

**Evidence (DIAG traces, `debug` gated, vl=0.0 — zero detection-logic impact):**

```
[auto-clicker] DIAG cps=1.00 samples=0 mean=NaN std=NaN yaw=0.0
[auto-clicker] DIAG cps=3.90 samples=1 mean=513.0 std=0.0 yaw=-90.0
[auto-clicker] DIAG cps=3.01 samples=2 mean=498.0 std=15.0 yaw=-90.0
[aim-assist]  DIAG swing=-1.0 int=0 yaw=0.0 streak=0
[aim-assist]  DIAG swing=95.8 int=0 yaw=-90.0 streak=0
```

Per-attack damage ≈ 1.0 (fist, full cooldown): 24 damage dealt / ~25 landed attacks.
The zombie HP probe corroborated (1024.0 → ~1000.4 in 12 s). `kill-aura` and `reach`
still fire — their gates (≥3 distinct targets in 1 s; excess distance) are not
interval-capped the same way and accrue from the landed stream (observed callbacks:
kill-aura 6–9/leg, reach leg 0 = 3/leg, leg 1 = 0/leg).

**Mechanism.** Since 1.21.2 vanilla enforces the player's attack cooldown server-side:
sub-cooldown attacks are silently dropped (no `EntityDamageByEntityEvent`, no damage).
Bare-fist attack speed (4.0 → 0.25 s) and every sword (< 0.625 s) cap landed melee at
≈ 2–4 hits/s for *any* player, honest or cheating.

**Consequence for the checks (honest classification).** On 1.21.2+ stock servers, the
`auto-clicker` cadence gate (20.5 CPS) and the `aim-assist` snap-interval gate (120 ms)
are **unreachable via server-validated melee**. This is a design-era calibration finding,
not a detection-logic defect introduced by Phase 4; detection logic was **not** changed.
Recalibration against 1.21.2+ cooldown reality (e.g. snap-interval derivation, cadence
gate re-derivation) is flagged as a Phase 5+ design input — see ROADMAP.md. The
`kill-aura` check remains effective against multi-target rushes within the landed stream.

**What was NOT done:** no threshold, gate, VL weight or decay was changed; no check was
removed. The scenarios remain in the harness and honestly report FAIL ("expect:
auto-clicker/aim-assist VL > 0" unmet) because the environment cannot deliver the
required event stream. They are reclassified below as **Unable-to-trigger in CI**, not
as broken detections.

## Phase 6 note — multi-signal combat (2026-11-07)

The Phase 5 finding below — KillAura's verdict resting solely on "3 distinct
damaged entities / 1 s" — is now closed by the multi-signal redesign documented in
`CHECKS.md` and `PHASE_6_IMPLEMENTATION.md`: the anchor never flags alone; a second,
independent stream (metronomic cadence / packetevents attempt rate / swingless-attack
ratio) is required. AutoClicker's rate gate now also considers the attempt stream
(misses), and the metronome evidence now reports CV and Shannon entropy in debug
lines for post-mortem analysis. These are detection-logic changes, validated by new
unit tests in CI; they were not & are not covered by real hack-client runs (BLOCKED —
see `DETECTION_VALIDATION.md`).
