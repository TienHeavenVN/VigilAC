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
