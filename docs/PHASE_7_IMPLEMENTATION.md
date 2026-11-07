# Phase 7 — Implementation report (reach, step, P1 remediation)

Date: 2026-11-07. Scope: the Phase 7 remediation directive — reimplement the
reach path at packet level, bound the step bounce state machine, remediate the
P1 audit findings, and deliver honest documentation. This document records what
changed, what was assumed, what was measured (nothing new was measured), and
exactly what remains unverified. Companion: [PHASE_7_BLOCKERS.md](PHASE_7_BLOCKERS.md).

## 0. How to read the status labels

`IMPLEMENTED` = code on disk on this branch. `UNIT_TESTED_SOURCE` = unit tests
written to the JUnit 5 idiom, **not executed** (no JDK in this environment;
CI is the gate). `UNVERIFIED` = no run of any kind. `BLOCKED` = impossible in
this environment. Nothing in this report is `CI_VERIFIED` or
`REAL_CLIENT_VERIFIED`. No performance or detection-rate figures below are
measurements; the only numbers with sources are the vanilla-math derivations,
which are checked against the vanilla formula and existing Phase 6 unit
expectations.

## 1. Commits (all on `arena/a7154a72-vigilac`; nothing pushed)

| Commit | Phase | Contents |
|---|---|---|
| `a4c732d` | Reach | packet-attempt pipeline, item-range, geometry resolver, hand-off |
| `1ca599b` | Step | bounded slippery-bounce state machine for step evidence |
| `0d9b94c` | P1 | AimAssist / Jesus / AutoClicker / KillAura / NoFall / FastBreak / AUDIT-12/17 |
| this commit | Docs | CHECKS.md corrections, validation/matrix/benchmark appendices, this file |

## 2. Reach (AUDIT-02, AUDIT-07-adjacent, AUDIT-08)

**Problem.** Reach was evaluated only on damage events: (a) vanilla cap assumed
3.0 for every item (wrong for 1.21.11+ weapons with an `attack_range` component,
e.g. spears); (b) a client that swings and misses left no trace; (c) javadoc
claimed latency can never inflate reach, which is backwards for a strafing
target; (d) `previousAttackAt` was always 0 inside checks (AUDIT-07).

**What was built (`a4c732d`).**

- `PacketEventsTelemetry` records raw attack packets (26.1+ `ATTACK`; ≤1.21.x
  `INTERACT_ENTITY` with `ATTACK`) into an `AttackAttemptSnapshot` carrying
  process-monotonic sequence, attacker UUID, target entity id, timestamp.
- `AttackAttemptDispatcher` performs the **netty → main-thread hand-off**:
  bounded `ArrayBlockingQueue` (capacity 256; non-blocking `offer` from the
  netty thread — overflow drops the *new* attempt and increments a counter,
  it never convicts), drained once per tick on the main thread (max 64
  resolutions/tick), one-second sweep for attempts with no paired damage
  (400 ms pair window). Over-range rejected attempts are written to the
  monitor evidence (`noteCheckEvidence("reach-attempt", …)`); **they never
  add VL**. Damage events remain the sole conviction path.
- `AttackGeometryResolver` resolves the target entity server-side, computes
  eye→closest-box distance on the main thread, and reads the weapon
  `attack_range` component via the registry-key path (`WeaponRangeReader`).
  Unknown or unreadable component ⇒ `attackRangeSource=unknown`, effective
  cap = configured 3.0 (+0.03 tolerance). **No component value was invented.**
- `ReachCheck` uses `ctx.getAttackRangeMax()` when present; the debug line
  records `range=` and `source=`. The old latency javadoc was rewritten
  honestly; an opt-in `checks.reach.lag-compensation` (target-velocity
  backward projection over ping, capped by `lag-compensation-max-blocks`)
  narrows—but never extends—detection; default off.
- `AttackContext` gains `previousAttackAtMillis`, `attackRangeMax`,
  `attackRangeSource`, `targetVelocity`, `pingMillis`, `lagCompensation*`.
  `AttackListener` captures `previousAttackAt` **before** `recordAttack` and
  passes it into the context (AUDIT-07).

**Thresholds.** `min-attacks-over` consecutive over-reach hits AND windowed
EMA > cap required for VL; single hits never convict. Tolerance 0.03.

**Assumptions / limitations.** packetevents present at runtime is required for
the attempt pipeline (inert without it; damage path unchanged). Thread-safety:
netty path touches only the bounded queue + atomic counters; all Bukkit state
is read on the main thread (queue hand-off, not direct access). Component
stability across 26.x is UNVERIFIED; conservative fallback is intentional.

**Tests (not executed).** `ReachCheckTest` additions: item range present ⇒
component widening is honored; unknown component ⇒ configured 3.0 cap retained;
streak/EMA gate intact; creative/spectator/vehicle exemptions unchanged.
`AttackAttemptDispatcherTest`: enqueue/overflow behavior (bounded drop, no
convict), drain budget, invalid-entity path. Note honestly: the dispatcher and
resolver tests exist; the PE-runtime path itself cannot run in CI.

## 3. Step (AUDIT-05) — bounded bounce state machine

**Problem.** Step's `minStreak=2` let one sneak-charged slime bounce convict:
launch ticks dy≈1.13/1.05/0.97 all exceed 0.95. The old CHECKS.md claim
("one streak tick per bounce") was arithmetically wrong.

**What was built (`1ca599b`).** On slippery ground (slime/honey —
`DetectionContext.belowFeetSlippery`, injected from the move listener) the
check enters a **bounded evidence machine** instead of any global exemption:
first qualifying ticks accrue bounce evidence with the vanilla decay pattern
(launch 0.42 + jump boost … primary reference: h-block fall onto slime returns
`sqrt(2·0.08·h)` upward velocity, decelerating 0.08/tick); the streak can only
grow while decaying bounce motion continues; the 4th qualifying tick without an
observed fall resets to zero and counts normally. A machine repeating full
0.95+ ticks without fall/decay context still convicts. No global exemption; no
unbounded grace; non-slippery ground keeps the original gate untouched.

**Limitations.** Pattern constants (0.08/tick decel, jump-boost 0.1/level,
0.42 base jump velocity) are well-established vanilla physics, not
26.2-build-verified. The state machine is unit-tested at the logic level;
real parkour FP rates are UNVERIFIED.

## 4. Jesus (AUDIT-04) — riptide & wind-burst

**What was built.** Two bounded, evidence-based exemptions replace the 2 s
cap overreach problem:

- `isRiptiding` (server-side) ⇒ streak resets while the state holds.
- Vertical-dominant velocity grace: the `PlayerVelocityEvent` handler
  (`ConfounderListener`) sets `windBurstGraceUntil` ≤ 3 s **only** for
  |vy| > 0.45 blocks/tick **and** |vy| > 60 % of total speed. Lateral-dominant
  impulses (knockback) never arm the grace.
- Both paths revert through the existing streak-reset; no general water
  exemption was introduced; Frost Walker, vehicles, elytra behave as before.

## 5. AimAssist (AUDIT-03, AUDIT-07)

**What was built.** The snap metric is now the **single largest rotation
burst** in the trailing window: `RotationTracker.maxDeltaInWindow()` takes
consecutive-sample angular deltas (hypot of yaw/pitch change) and returns the
max, never the sum. A flick-and-return yields two bursts but never accumulates
toward `snap-angular-delta`. The attack-spacing value used by the fallback path
and debug output reads `previousAttackAtMillis` (captured pre-record); the
tracker is retained solely for population statistics.

**Tests (not executed).** `RotationTrackerMaxDeltaTest` (burst isolation,
window eviction, empty-input zero) + updated `AimAssistCheckTest` expectations.

## 6. AutoClicker (AUDIT-06)

**What was built.** The ceiling gate (rate > cap) now **requires**
machine-like rhythm corroboration — stddev < `robotic-stddev-max` (2× robotic
boundary at the rate gate) — so a documented 20–30 CPS human butterfly/touch
tapper can no longer be convicted on rate alone. Default `cps-cap` raised
20.5 → **25.0** with the Android/touch rationale recorded in `config.yml`.
Out-of-order `CombatTracker` observations (now < last) are skipped entirely —
no fabricated zero-interval samples. `intervalCv()` / `intervalEntropyBits()`
remain debug-only; their doc no longer cites the unvalidated "~0.2 humans"
constant (AUDIT-17).

## 7. KillAura (AUDIT-16)

**What was built.** Conviction gate is now `anchor ∧ (metronomic ∨ swingless)`.
The packetevents attempt rate (≥ 8/s) only scales `vl-per-trigger`
(`min(2, 1 + 0.5·(signals−2))`); it can never satisfy the gate by itself.
`minSignals` removed. Farm-corridor three-target cadence with clean arm swings
cannot flag regardless of rate; a metronomic 15-ms-stddev aura and a swingless
aura flag as before (plus anchor).

**Tests (not executed).** `KillAuraCheckTest`: `anchorPlusAttemptRateWith
SwingsDoesNotFlag` (rate 12/s + swings ⇒ silent), plus the existing
metronomic-flags and swingless-flags cases.

## 8. NoFall (AUDIT-09)

Correlation suppression windows are per-factor: teleport ≤ 2 s, velocity ≤
1.5 s, fall damage ≤ 1.5 s, world change ≤ 2 s (was uniform 15 s). Advisory
only; never gates VL. Regression tests added in `CorrelationEngineTest`.
AUDIT-10 (entity-supported landings) was reviewed and closed-in-analysis:
fall-on-mob exculpates through vanilla fall-damage grace before the probe can
convict; no code change.

## 9. FastBreak (AUDIT-01, AUDIT-14, AUDIT-15) — not weakened

**New model (`BreakTimeModel`, pure Java, no Bukkit).**

- Tool tiers by material substring: WOODEN 2.0 / STONE 4.0 / IRON 6.0 /
  GOLDEN 12.0 / DIAMOND 8.0 / NETHERITE 9.0. Hand & unmapped ⇒ no tier.
- Correct tool + known tier:
  `destroySpeed = tier + (eff>0 ? eff² + 1 : 0)` (eff cap 5),
  × `(1 + 0.2·Haste)` (cap IV), × `0.3^Fatigue` (cap IV),
  × 0.2 underwater without Aqua Affinity, × 0.2 off-ground.
  `expectedMillis = hardness × 1.5 / destroySpeed`,
  `minimumMillis  = same, capped at Haste IV and full Efficiency V` — an
  optimistic-but-never-beyond-vanilla floor.
- Wrong family (or tool not effective for the block): fixed 5× divisor
  (`hardness × 5.0 / globalSpeed`); never suppressed, never needs a tier.
- `hardness ≤ 0` ⇒ 0 ms (instant). Unknown correct-tool tier (hand/modded/
  copper on the right block) ⇒ **NaN budgets**: Path A records evidence and
  returns without accruing penalty; Path B resets the impossible streak and
  returns; no verdict is ever taken — never an invented speed.
- Haste cap is now a single constant (IV) for both paths — AUDIT-14 gone.
  canHarvest modeled as the 30/100 divisor via the `correctTool ? 1.5 : 5.0`
  split — AUDIT-15 done by construction.

**Call sites.** `BlockBreakListener` maps the held item to a tier
(`toolSpeedOrUnknown`), skips **creative/spectator** breaks (instant in
vanilla), and uses one `nowMillis` for both the break builder timestamp and
the dig-lifecycle completion. `FastBreakCheck` unchanged except the two
NaN-suppression branches. `BreakContext` defaults `toolSpeed=1.0` (bare-hand),
preserving pre-existing test math. `FastBreakToolTierTest` adds iron-on-stone
(375 ms legality), wooden-tier flags at iron-cadence, unknown-tier suppression
(interval + dig path, streak stays 0), obsidian eff5 netherite (~2.14 s
legality, ~1.4 s under-budget convicts), wrong-tool 250 s bound. FastBreak was
**not** weakened anywhere: wrong-tool, unbreakable (`hardness<0` ⇒
unconditional VL), and instant paths are untouched; the tool-tier change only
makes correct-tool budgets exact instead of systematically 2× too tight.

**Audit-logged item AUDIT-11** (server-side vein-miner plugins mass-breaking)
is documented for operators (OPERATOR docs / CHECKS.md fast-break note) —
permission-based bypass is the recommended remedy; intentionally **not**
implemented in code.

**Tests (not executed).** 15 formula-contract tests (`BreakTimeModelTest`) +
7 regression tests (`FastBreakToolTierTest`) + existing
`FastBreakCheckTest`/`FastBreakDigLifecycleTest` kept behavior-green by the
1.0 default.

## 10. Packet telemetry hygiene (AUDIT-12) & combat tracker (AUDIT-17)

`PacketEventsTelemetry`/`PacketEventsMetrics` warn-once flags are now
`AtomicBoolean` compare-and-set. `CombatTracker` CV/entropy remain debug-only;
unvalidated "~0.2 humans" claim deleted from javadoc. Out-of-order attack
intervals are skipped, not recorded as zero.

## 11. Rejected/deferred items (with reasons)

- **AUDIT-08 full compensation on by default** — rejected: would silently
  weaken conviction for slow-target fights; it is opt-in only.
- **AUDIT-11 code bypass** — rejected: plugin-break detection is not reliably
  signalable from an event; permission-based bypass is the audit's own
  recommendation and is documented instead.
- **AUDIT-13 / high-CPS touch preset key** — rejected as a new config surface;
  raising `cps-cap` (documented, incl. `touch-friendly: 26` guidance in
  config comments is not added; the audit's option (a)+(b) is implemented
  instead). No code.
- **AUDIT-17 constant kept** — rejected: deleted/demoted to debug telemetry.
- **ML runtime anything** — none added (see §12).

## 12. ML scope (unchanged, honest)

Phase 7 introduces **no ML**. The Phase 6 position stands: the attack-rate,
cadence, stddev and CV signals are thresholds and statistics, advisory
`/vigilac monitor` telemetry only, never conviction on their own. No metric is
invented; no labeled dataset exists; human-cadence ground truth is UNVERIFIED.
A future advisory model must remain evidence-refinement only and must not gate
VL without human review.

## 13. Commands executed vs not executed

Executed (this sandbox): file edits; read-only `git` status/log/diff;
`git ls-remote` (empty); structural brace/parens count over changed files.
**Not executed:** `./gradlew` in any form; any test; any server; any client;
`git push`; `gh pr create`. CI will be the first compile/test gate.

## 14. What to verify on the next authorized push

1. `Build and test` + 15 compat legs green on `0d9b94c` (or fix fallout).
2. JUnit reports contain the new tests (BreakTimeModelTest = 15,
   FastBreakToolTierTest = 7, RotationTrackerMaxDeltaTest = 4,
   KillAura/Jesus/Correlation additions) and they pass.
3. Live harness vanilla FP: expect identical legit-sprint-jump artifact, no new
   FP on `legit-mine-stone`, `legit-bridge`, `legit-fall-nodamage`.
4. Manual protocol (DETECTION_VALIDATION.md §5) still marks all real-client
   cells BLOCKED until a maintainer runs it; the new spear-range cell
   (attack_range component) is added there as 1.21.11-specific.
5. Load legs: re-run; expected no regression (added hot-path cost is a few
   comparisons per move/attack event), but **no number exists yet**.
