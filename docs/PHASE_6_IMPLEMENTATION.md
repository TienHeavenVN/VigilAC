# Phase 6 — Detection engine overhaul (6A → 6E)

Date: 2026-11-07. Audience: maintainers. Every claim below is tagged with a status tag
and the phase that produced it. **REAL_CLIENT_VERIFIED: none, anywhere in this
document** — see [DETECTION_VALIDATION.md](DETECTION_VALIDATION.md).

Tags: `IMPLEMENTED` · `UNIT_TESTED` (CI green) · `CI_VERIFIED` (full matrix + smoke) ·
`REAL_CLIENT_VERIFIED` · `BLOCKED` · `NOT_IMPLEMENTED`.

## 0. What changed at a glance

| Area | Before (end of Phase 5) | After |
|---|---|---|
| Packet telemetry | counters only (`PacketEventsMetrics`) | structured telemetry: attack attempts, swings, rotation stream, digging lifecycle — `PacketEventsTelemetry` + fixed-capacity trackers in `ac.vigil.data.tracking` |
| KillAura | "3 distinct damaged targets / 1 s" single signal | multi-signal: anchor (distinct targets) + corroboration (metronomic cadence, packetevents attempt rate, swingless attacks); ≥2 signals required |
| AutoClicker | damage-stream CPS + stddev streak | rate gate accepts the stronger of damage-CPS and attempt rate (misses included); debug now carries CV + Shannon entropy; logic gates unchanged |
| AimAssist | centroid bearing, swing between attacks | closest-point-on-box bearing; rotation-stream swing over the pre-attack window when packetevents present (attack-anchored fallback identical to before) |
| Reach | unchanged | unchanged (documented reasons) |
| FastBreak | completion-interval heuristic only | Path A: real dig lifecycle (START/CANCELLED/FINISHED) with full vanilla modifier stack (hardness, tool, Efficiency, Haste, Mining Fatigue, Aqua Affinity, underwater, off-ground) + decaying penalty bucket; Path B: fallback unchanged |
| NoFall | descent + client onGround flag only | + server-collision probe (`BlockSupportProbe`/`BukkitBlockSupportProbe`) verifying the ground claim; unsupported claim flags immediately; probe failure can never convict |
| Fly | hover / rising signatures | + low-gradient sink signature (under 0.04 blocks per airborne tick for 40+ ticks — impossible under vanilla gravity); cobweb/web excluded |
| Speed | caps for sprint/potion/ice | + web (×0.35) and soul-sand/honey/berry-bush (×0.40) cap multipliers via new `DetectionContext` flags |
| Correlation | display-only confidence | tri-state CONFIRMED/SUSPECTED/INCONCLUSIVE (discipline label, never a probability), wired into `DetectionStatus` and `/vigilac monitor` |
| Monitor | — | `ATTACK_ATTEMPT` evidence type, `noteAttackAttempt`, packet source label |

## 1. Phase 6A — Research (done, `docs/ANTI_CHEAT_RESEARCH.md`)

Grim (movement prediction, collision-probe NoFall, transaction ordering), NoCheatPlus
(penalty buckets, duration-based breaking), Negativity (modular registry; CPS threshold
explicitly rejected), Themis (**BLOCKED — 404**, no claim made). License rule enforced:
all three are GPL-3.0, VigilAC stays MIT; algorithms reimplemented, never copied.
VigilAC audit (16 components) with the 7 mandated questions answered in §7 of the
research doc. Status: DONE. Commit `226d6bf`.

## 2. Phase 6B — Packet telemetry (done, CI pending marker)

New, all fixed-capacity and netty-safe, registered as `PacketEventsTelemetry`
alongside metrics behind the same `PacketEventsHook` gate (absent packetevents ⇒
trackers empty, everything degrades to event-driven paths):

| Component | Feeding | Purpose | Status |
|---|---|---|---|
| `AttackAttemptLedger` | `ATTACK` (26.1+), `INTERACT_ENTITY`(ATTACK) ≤1.21.x | attempts ≠ damage; 8+/s corroboration for KillAura; rate gate for AutoClicker | IMPLEMENTED, UNIT_TESTED |
| `SwingTracker` | `ANIMATION` (≤26.2), `PUNCH` (26.3+) | click cadence, swingless-attack detection | IMPLEMENTED, UNIT_TESTED |
| `RotationTracker` | `PLAYER_ROTATION` / `PLAYER_POSITION_AND_ROTATION` / `PLAYER_POSITION` | pre-attack angular motion, aim correction | IMPLEMENTED, UNIT_TESTED |
| `DiggingTracker` | `PLAYER_DIGGING` START/CANCELLED/FINISHED (+CHANGE_DIRECTION) | dig-duration evidence for FastBreak; Abort/tool-swap inconclusiveness | IMPLEMENTED, UNIT_TESTED |
| `HeldItemListener` | Bukkit `PlayerItemHeldEvent` | marks in-dig tool change → dig inconclusive | IMPLEMENTED (main thread) |

Verified against **packetevents 2.14.0** sources (`git show v2.14.0:<path>`), never
master. Packet-ID truth table lives in
[COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md). Lifecycle: listeners unregistered
on plugin disable (no reload leaks). No Bukkit API is called on the netty thread; no
I/O on the packet path; O(1) synchronized updates only.

Known gap (honest): the packetevents runtime path is **CI-unverified** — CI has no
packetevents plugin installed. Only compilation + tracker unit tests cover it. The
listener pattern mirrors the Phase 4/5 metrics listener (proven pattern), but real
runtime behavior on a live server **not verified**. Commits `97f6961`, fix `7e8150f`.

## 3. Phase 6C — Detection engine (done; CI gate after every push)

### Combat (6C-A) — commit `3e930e3`

* **KillAura** (`multi-signal`): anchor `distinctTargets ≥ minTargets` (default 3, 1 s)
  AND at least one independent corroborator: metronomic cadence (stddev < 15 ms over ≥ 6
  intervals), packetevents attempt rate ≥ 8/s, or swingless attacks (attempts ≥ 6 and
  attempts > 2 × swings). Amount scales with signal count (1–2 VL). The 3-target rule
  **can no longer flag alone** — the Phase 5 FP-analysis weakness is closed.
* **AimAssist**: bearing = angle to the nearest point of the target AABB (not centroid;
  enderman legs count). Swing = total angular distance over the 120 ms pre-attack window
  from the rotation stream when packetevents is present (packetevents-absent fallback:
  consecutive-attack delta, identical to v1). `snapAngleToTarget` (6°), streak ≥ 4.
* **AutoClicker**: rate gate = `max(damageCPS, attemptRate)`; min-samples applies to
  either stream; debug output now reports interval CV and Shannon entropy. The 1.21.2+
  cooldown interaction (damage events vanish) is documented in the class javadoc; the
  attempt-rate gate is the honest replacement signal. No threshold was loosened.
* **Reach** unchanged; existing gates and EMA logic retained.

### Movement (6C-B) — commit `53dc362`; jesus/step follow-up commit

* **Fly** v2: low-gradient sink signature added.
* **Jesus** v1 (NEW, follow-up commit): dry-feet-above-water with 14-tick streak
  gate and Frost Walker exemption. UNIT_TESTED; NOT REAL_CLIENT_VERIFIED.
* **Step** v1 (NEW, follow-up commit): single-tick rise > 0.95, streak of 2 gate.
  Piston/velocity grace covered. UNIT_TESTED; NOT REAL_CLIENT_VERIFIED.
* **Spider** v1: NOT_IMPLEMENTED — needs an adjacent-solid-block probe and
  climbable-material awareness (ladders, vines, scaffolding, waterlogged decor);
  shipping it blind would manufacture FPs on every climbable-adjacent wall climb.
* **InventoryMove**: NOT_IMPLEMENTED — no packet-level GUI-open telemetry; and
  vanilla permits full movement with the inventory screen open, so it is weak
  evidence at best ("if data allows" was explicitly not met).

* **Fly**: new low-gradient sink signature — descent < 0.04 blocks/tick across ≥ 40
  airborne observations (≈ vanilla-gravity impossible). Hover/rising logic unchanged.
  Cobwebs exempt (heavy vertical drag mimics the sink signature).
* **Speed**: web cap ×0.35, soul sand/soil/honey/berry bush cap ×0.40. Friction-aware
  caps remove a class of slow-block FPs.
* **Timer** unchanged (wall-clock 1 s ring, 21.5/s gate, 3 s sustain). Grim-style
  client-tick accounting is NOT_IMPLEMENTED (needs transaction pipeline — §7).
* **MovementTracker**: `airborneTicks` counter added (O(1)).

### FastBreak (6C-C) — commit `53dc362` (+ fix `90312e9`)

* Path A (packetevents): expected duration from the full vanilla stack —
  hardness × (30 or 100) / (1 + efficiency²-or-0) × haste × 0.3^fatigue × (0.2 if
  underwater w/o Aqua Affinity) × (0.2 if off-ground). Observed **dig** duration
  (not completion interval) is compared; deficits credit a decaying
  `FastBreakPenalty` bucket (half-life 3 s). VL needs bucket > 1500 ms grace.
  Tool/destroy-direction swap mid-dig ⇒ dig inconclusive ⇒ no timing verdict.
* Path B (fallback, no packetevents): completion-interval heuristic, unchanged.
* `BreakContext` gained `miningFatigueLevel`, `inWater`, `aquaAffinity`, `onGround`,
  `digDurationMillis`, `digInconclusive` — all with safe defaults; `minimumBreakMillis()`
  semantics untouched (existing unit tests green).

### NoFall (6C-D) — commit `53dc362`

* Server-collision probe: `BlockSupportProbe` (interface, unit-testable) +
  `BukkitBlockSupportProbe` (main-thread; corner-column solidity at the feet block and
  the layer below; chunk-unload ⇒ cannot judge ⇒ supported; any exception ⇒ supported).
  An **unsupported** ground claim on a ≥ 3.5-block descent flags immediately with a
  collision-verified debug line; a probe failure never convicts.
* Registration passes the Bukkit probe in production; unit tests inject fakes.
* Unrelated-damage behavior unchanged (Phase 5 fix retained).

### Correlation (6C-E) — commit `8854345`

* `CorrelationStatus` enum (CONFIRMED/SUSPECTED/INCONCLUSIVE) — discipline labels, never
  probabilities: ≥ 2 independent supporting signals with no suppression ⇒ CONFIRMED;
  exactly 1 ⇒ SUSPECTED; otherwise (including any suppression) ⇒ INCONCLUSIVE.
* `DetectionStatus.correlationStatus()` exposes the latest verdict per check;
  `/vigilac monitor <player>` prints `corr=<state>`.
* Wired into NoFall (both paths). Combat checks record VL-only evidence; they do not
  call the correlation engine (documented; multi-signal KillAura is the corroboration).

## 4. Phase 6D — Validation (done — see DETECTION_VALIDATION.md)

`REAL_CLIENT_VERIFIED`: none. Real-client validation **BLOCKED** (environment).
PROTOCOL_BOT: CI-governed. Manual test protocol written (§5 there).

## 5. Phase 6E — Final audit (this document + the doc set)

Changed docs: `ANTI_CHEAT_RESEARCH.md` (6A), this file, `DETECTION_VALIDATION.md`,
`PERFORMANCE_BENCHMARK.md`, `COMPATIBILITY_MATRIX.md`. Updated references:
`MONITORING.md`, `CHECKS.md` (check tables, new signals), `REAL_CLIENT_VALIDATION.md`
unchanged (its UNABLE-TO-TEST stands), `FALSE_POSITIVE_ANALYSIS.md` gains the KillAura
v2 note.

## 6. Explicit test inventory (what CI must keep green)

* `PacketTelemetryTrackersTest` — ledgers, rotation stream, dig FSM.
* `FastBreakDigLifecycleTest` — dig-path verdicts, fatigue/water/aqua/affinity-exemptions,
  burst vs spaced marginal deficits.
* `FastBreakPenaltyTest` — decay math (fixed sentinel bug found by CI, `90312e9`).
* `FastBreakCheckTest` — legacy Path B unchanged.
* `KillAuraCheckTest` — anchor+corroboration semantics (single-signal verdict test removed).
* `FlyLowGradientSinkTest` — new signature incl. tick-floor and spacing.
* `NoFallProbeTest` — unsupported claim flags; probe exception never convicts.
* `CorrelationStatusTest`, `CorrelationMonitorPropagationTest` — tri-state + propagation.
* `AttackGeometry` is exercised via the combat check tests.
* `JesusCheckTest` — streak gate (>= 14 ticks), Frost Walker exemption, liquid-dip
  reset, arc-vs-streak, grace/TPS suppression.
* `StepCheckTest` — streak gate (2 consecutive), vanilla auto-step, piston grace,
  Jump Boost VI arc limitation pinned by test.

## 7. NOT_IMPLEMENTED / out of scope (explicit)

* Grim-style full movement simulation + compensated world + setback teleport.
* Transaction-ordered latency compensation for verdicts.
* Timer client-tick accounting.
* Spider / InventoryMove new detectors.
* Themis-derived algorithms (repository does not exist).
* Signal-to-action automation for combat (KillAura/AimAssist remain alert-only by default;
  punishment mode unchanged and explicitly never automatic from this phase).
* Ping-compensated reach geometry (documented in research §6.4).
* Runtime verification of the packetevents path in CI (no PE plugin in the harness).

## 8. Compatibility changes

None — all artifacts, 15 CI compat legs, artifact split (Java 21 / JDK 25) unchanged;
see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md).
