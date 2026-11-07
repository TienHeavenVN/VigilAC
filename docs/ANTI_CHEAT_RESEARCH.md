# Phase 6A — Anti-cheat research and VigilAC audit

Date: 2026-11-07. Method: direct source review of cloned repositories (GitHub, depth-1
clones on 2026-11-07), not README-level review. All algorithm notes cite files in the
cloned trees. **No code was copied into VigilAC.** VigilAC is MIT-licensed; Grim,
NoCheatPlus and Negativity are GPL-3.0 — every reused idea below was reimplemented from
the described algorithm, never from transcribed source. Themis was unreachable (see §4).

## 0. Status legend

IMPLEMENTED = code exists and is unit-tested. TESTED = unit tests prove the behaviour.
CI_VERIFIED = full CI matrix green. REAL_CLIENT_VERIFIED = validated against a real
(hack/vanilla) client in a controlled environment. BLOCKED = cannot be performed here.
NOT_IMPLEMENTED = does not exist (stated, not faked).

## 1. Grim (GrimAnticheat/Grim) — cloned; GPL-3.0

The reference architecture for movement/packet processing. Source roots:
`common/src/main/java/ac/grim/grimac/` (prediction engine, checks), `bukkit/`, `fabric/`.

### 1.1 Packet-level pipeline and prediction engine

- Every client position/rotation packet (`WrapperPlayClientPlayerFlying` and subclasses)
  is wrapped in a `PositionUpdate` and routed through
  `predictionengine/MovementCheckRunner.java` → `checks/impl/movement/PredictionRunner.java`.
- **Full client physics simulation**: `predictionengine/movementtick/MovementTickerPlayer.java`
  replays the vanilla movement physics tick-by-tick given the client's velocity and the
  compensated world, including gravity, jump, water/lava, sneak backoff
  (`maybeBackOffFromEdge` — the vanilla 0.05 sneak-step), fluid friction, and entity
  collisions. The prediction is compared against the client's reported packet; deviation
  beyond an uncertainty bound (`uncertaintyHandler`) flags and triggers **setback**
  (server teleport back) via `SetbackTeleportUtil`. This is the correct way to defeat
  speed/fly — detection by *simulation*, not by thresholds.
- **Compensated world**: `utils/collisions/` (BoundingBox, SimpleCollisionBox,
  `CollisionFactory`, block-specific dynamic boxes for stairs/slabs/fences/panes/doors/
  pistons/chests) — server-side collision resolution over blocks the client *should* see,
  tracking client-side block updates to defeat ghost blocks (`GhostBlockMitigation.java`).
- **Latency compensation**: every position packet is transaction-ordered
  (`packetorder/TransactionOrder.java`, `PacketOrder*Processor`); the server only judges a
  position after preceding transactions resolve, matching client ticks to server ticks.
  This is Grim's FP defense for high ping — VigilAC has no equivalent.
- **Version-specific mechanics**: `MovementTickerCamel/Horse/Pig/Strider/Nautilus/...` per
  entity, plus `PredictionEngineNormal`/`PredictionEngineBoat`. Prediction is per-version
  (packetevents `ServerVersion`), not one fixed model.
- Check base: `checks/Check.java` + `@CheckData(name=..., setback=...)`; checks are
  `PositionListener` / `RotationListener` / `PacketReceiveListener` / `PostFlying...Listener`
  — input is always *packet state in prediction order*, never raw Bukkit events alone.

### 1.2 NoFall (`checks/impl/groundspoof/NoFall.java`)

Runs after prediction. If the client claims `onGround=true` in a flying/rotation packet
with **no position change**, it probes a 0.6×0.001 feet bounding box expanded by the
movement threshold for vertical collisions (`Collisions.getCollisionBoxes` +
`collidesVertically`). No collision → `flagWithSetback()` + packet is re-encoded with
`onGround=false`. Key point: **client onGround is never trusted**; the server verifies the
claim against collision geometry at the claimed position. This is exactly the model VigilAC
is missing (VigilAC NoFall trusts the client flag for streak mechanics).

### 1.3 Combat

- **Reach** (`checks/impl/combat/Reach.java`, 404 lines): eye-to-entity-box distance with
  transaction-resolved ping compensation and entity-specific box handling; VL and setback
  through `@CheckData`.
- **Combat validation**: `checks/impl/combat/` (Hitboxes, MultiInteract, InvalidInteract*,
  SelfInteract) — attack packets are validated for target existence, duplication and
  interaction cursor/target mismatch, not just distance.
- **Knockback validation** (`checks/impl/velocity/KnockbackHandler.java`): after the server
  applies knockback, client velocity is *predicted* from the knockback vector + physics and
  compared to subsequent packets; attenuation below expectation = anti-knockback.
  `ExplosionHandler` does the same for explosions.
- **Aim checks** (`checks/impl/aim/AimDuplicateLook`, `AimModulo360`,
  `checks/impl/aim/processor/AimProcessor.java`): aim is validated on the rotation stream
  (mod-360 spikes, duplicate-look patterns), not on attack snapshots alone.

### 1.4 Timer

`checks/impl/timer/Timer.java`, `TickTimer.java`, `NegativeTimer.java`, `TimerLimit.java`:
timer detection is grounded in transaction-resolved client tick accounting (packets per
client tick, not per wall-clock second) — the rate signal is measured against the client's
own tick budget, which is why latency cannot inflate it.

### 1.5 What Grim teaches VigilAC (adopt, don't copy)

1. Simulate, don't threshold: predict and compare; VL emerges from deviation, not from a cap.
2. Client claims (onGround, sprint, velocity) are hypotheses; server collision/world state
   is the arbiter.
3. Transaction-ordered latency compensation before any movement verdict.
4. Setback only after verified deviation — never from a single packet.
5. Version-aware physics, per entity, per server version.

Cost for VigilAC to fully adopt 1+3: **not achievable in this phase** (needs compensated
world + transaction pipeline — a rewrite of the movement core). This phase implements the
cheaper 80%: packet telemetry, collision-probing helpers with an adapter, cause-separated
evidence, attempt/damage split, and physics-informed checks that don't need simulation.
Honest scope statement.

## 2. NoCheatPlus (NoCheatPlus/NoCheatPlus) — cloned; GPL-3.0

Legacy project; algorithms still valuable. Key files:
`NCPCore/src/main/java/fr/neatmonster/nocheatplus/checks/moving/` (`MovingListener`,
`player/NoFall.java`, `Magic.java`, `MovingData.java`), `blockbreak/FastBreak.java`,
`fight/`, `CheckType`, `ViolationData`, `workaround/`.

### 2.1 Violation level model (worth keeping)

- `ViolationData`/`ViolationHistory`: per-check VL with **decay over time**, action
  execution only past VL thresholds. VigilAC already has equivalent (VL + decay); NCP's
  contribution is the *penalty-bucket* pattern (below).
- **FastBreak penalty bucket** (`blockbreak/FastBreak.java`): instead of flagging on one
  fast completion, timestamps and missing milliseconds are added to a decaying bucket;
  VL is raised only when the bucket score exceeds a grace period. This converts bursty
  false positives (mcMMO, treecapitator, ping) into trend VL. Directly applicable to
  VigilAC's FastBreak (currently: streak of impossible completions).
- Expected breaking time uses **block hardness × tool multiplier × Efficiency × Haste ×
  modal lag adjustment** (`BlockProperties.getBreakingDuration` + `TickTask.getLag`).
  VigilAC already uses hardness/Efficiency/Haste (vanilla lower bound) but has **no
  penalty bucket**, **no lag normalization**, and reads only completion events — it never
  sees START/ABORT, so it cannot distinguish "broke it in 200 ms after 1.5 s of digging"
  from "broke it in 200 ms flat".

### 2.2 SurvivalFly (`moving/magic/Magic.java`, `model/LiftOffEnvelope.java`)

- Magic constants: `GRAVITY_MIN 0.0624 / GRAVITY_MAX 0.0834`, `WALK_SPEED 0.221`,
  water/web/fence modifiers (`modSwim`, `modWeb`, `modBlock`), `Y_ON_GROUND_*` bounds for
  grounded-vertical-motion, `FALL_DAMAGE_DIST 3.0`.
- **LiftOffEnvelope**: the check classifies motion into discrete envelopes (jump, fall,
  glide, swim, climb) and validates the y-component per envelope — an approximation of
  per-tick simulation via hand-derived envelopes. It is the ancestor of Grim's prediction
  engine. VigilAC should adopt the *idea of envelope classification* for fly/jump
  discrimination, not the constants (they encode 1.7–1.12 physics; 1.21 gravity is
  unchanged ≈ 0.08/0.02 per tick, but swim/elytra/levitation changed).
- **Exemption/modifier system**: permission-based (`Permissions`), environmental flags
  (ice/slime/web/water/lava), velocity/teleport exemptions — same shape as VigilAC's, but
  NCP also *generates* the modifiers from a `WorkaroundSet` per server — overkill for us.

### 2.3 What NoCheatPlus teaches VigilAC

1. Penalty-bucket accumulation for fast actions (fast-break, fast-place) — better FP
   behavior than raw streaks. Adopt in 6C.
2. Envelope classification for airborne motion — informs Fly upgrade (not copied constants).
3. Time-to-break must be measured from START (dig start), not from completion — 6B/6C.
4. VL decay always; never hard-ban on one observation.

## 3. Negativity (Elikill58/Negativity) — cloned; GPL-3.0

Plugin architecture reference. Source root: `src/com/elikill58/negativity/`.

### 3.1 Modular check architecture

- Checks are `universal/Cheat` subclasses ("protocols", e.g. `spigot/protocols/FlyProtocol.java`,
  `AutoClickProtocol.java`) registered globally in `Cheat.CHEATS`; each declares category,
  required permission, config key, and whether it needs packets. Spigot and Velocity
  adapters share the universal core — **platform adapter isolation** is the real lesson:
  detection logic in a platform-free module, wiring per platform.
- **Verification system** (`universal/verif/VerificationManager.java`, `VerifData.java`,
  `data/DataCounter.java`): per-player rolling statistical counters (mean/max/average) per
  check, stored to DB for admin review; alerts carry a computed reliability percentage
  (`parseInPorcent`). Violation accumulation = counter thresholds, not VL streaks.
- Config per cheat (`cheats.<key>.isActive`, `reliability_alert`, `alert_to_kick`,
  `setBack`), ban storage (`universal/ban/`) with DB backends, floodgate (Bedrock) support.
- **AutoClick**: counts `PlayerInteractEvent` AIR clicks per second with a ping-adjusted
  threshold (`click > CLICK_ALERT` where `click = ACTUAL_CLICK - ping/9`) and alerts.
  No interval distribution, no stddev, no cooldown awareness — **this is exactly the weak
  design VigilAC must not copy.** Its only interesting touch is ping compensation in the
  rate estimate.
- **Fly** (`spigot/protocols/FlyProtocol.java`): block-context probes around feet/head,
  swimming/elytra/vehicle/levitation/potion exclusions — same idea as VigilAC's FlyCheck,
  weaker signal (no velocity tracking).

### 3.2 What Negativity teaches VigilAC

1. Platform-free detection core + thin platform adapters — VigilAC already follows this
   (checks are Bukkit-free POJOs); keep it.
2. Per-check config: enable flag, alert threshold, action — VigilAC has this.
3. Statistical counters for admin review — VigilAC has ViolationManager + monitor command;
   not adding a new stats DB in 6C.
4. Do NOT copy CPS-threshold detection. The ping-adjusted rate is a minor reference only.

## 4. Themis (ThemisAntiCheat/Themis) — **BLOCKED: repository unavailable**

- `git clone https://github.com/ThemisAntiCheat/Themis` → `repository not found` (2026-11-07).
- `https://github.com/ThemisAntiCheat/Themis` → HTTP 404.
- `api.github.com/orgs/ThemisAntiCheat/repos` → empty/invalid; search `Themis anticheat`
  → `total_count: 0`.

**No Themis source was read; no Themis algorithm claims are made.** Any Themis-derived
behavior must be obtained from an authoritative copy or treated as NOT_RESEARCHED.
Phase 6 scope is unaffected because Grim/NoCheatPlus/Negativity cover the required ground.

## 5. Cross-project comparison (algorithm × project)

| Concern | Grim | NoCheatPlus | Negativity | VigilAC today |
|---|---|---|---|---|
| Movement core | per-tick physics simulation + setback | magic envelopes | context heuristics | EMA/thresholds + streak gates |
| Collision world | compensated world + block factories | Bukkit blocks | Bukkit blocks | feet-block flags only |
| Latency comp | transaction-ordered | lag-adjust buckets | ping-adjusted CPS only | none on verdict path |
| NoFall | feet-box vertical collision probe | damage-cause tracking | damage-cause tracking | descent + damage grace (Phase 5: cause-separated) |
| Reach | distance + transaction ping | — | ray trace helpers | eye→AABB distance + EMA gates |
| FastBreak | START/FINISHED digging lifecycle | hardness/duration + penalty bucket | — | completion event only |
| Timer | client-tick accounting (transactions) | — | — | 1 s ring rate vs 21.5/s |
| AutoClick | rotation/aim module | — | raw CPS | CPS + interval stddev |
| VL model | per-check + setback | decay + penalty buckets | counters + reliability % | decay + warn/alert/kick |
| Architecture | prediction-first, listeners post-prediction | monolithic listeners | plugin/adapter modules | listeners → checks → pipeline |

## 6. VigilAC audit — per component

### 6.1 KillAuraCheck (`check/combat/KillAuraCheck.java`)

| Attribute | Content |
|---|---|
| Check | `kill-aura` (combat) |
| Data source | `AttackContext` from `AttackListener` (EntityDamageByEntityEvent only), `CombatTracker.recordTarget` |
| Algorithm | distinct damaged entity ids in trailing 1 s ≥ 3 → VL scaled by excess |
| Weakness | only *successful damage* packets count; attack attempts (misses) invisible; no rotation, cadence, LOS, or target-geometry analysis; 3-distinct-in-1s is a single signal |
| False positives | mob-farm multi-target swings; high-density PvP cleave |
| Missing telemetry | attack attempts, swing cadence, target switching arc, eye-to-box geometry per attempt, rotation delta pre-attack |
| Reference | Grim `checks/impl/combat/*`, `AimProcessor`; NoCheatPlus `fight/` |
| Improvement | packet-level attempts (6B), rotation-delta + max-angle-to-target per attempt, cadence stddev, multi-signal scoring (distinct targets ∧ cadence ∧ angular sweep) — 6C-A |
| Tests | multi-signal unit tests; vanilla mob-cleave FP test; attempt-vs-damage accounting test |

### 6.2 AutoClickerCheck (`check/combat/AutoClickerCheck.java`)

| Attribute | Content |
|---|---|
| Check | `auto-clicker` (combat) |
| Data source | interval ring (20 samples) + CPS over 1 s, fed by damage events only |
| Algorithm | CPS > 20.5 (min samples) OR metronomic window (mean<150 ms, stddev<12 ms, streak ≥ 5) |
| Weakness | **damage-event stream only**; 1.21.2+ server cooldown makes the 20.5 CPS gate unreachable (FP-7); no entropy/spread analysis; no burst detection; cannot see misses |
| False positives | metronomic external clickers? (rare); uncharged-spam legit macro-ish play is by design borderline |
| Missing telemetry | attack attempts (misses), arm-swing cadence, swing-to-hit ratio, interval distribution shape |
| Reference | Negativity `AutoClickProtocol` (rejected design); Grim aim/click cadence work |
| Improvement | attempt-driven CPS + stddev + entropy; separate attempt rate from hit rate; keep cooldown awareness in docs — 6C-A |
| Tests | distribution tests (uniform vs human-noisy), entropy test, attempt-count test, cooldown-doc regression |

### 6.3 AimAssistCheck (`check/combat/AimAssistCheck.java`)

| Attribute | Content |
|---|---|
| Check | `aim-assist` (combat) |
| Data source | attack context yaw/pitch + previous attack rotation snapshot |
| Algorithm | snap = swing>Δ ∧ interval≤120 ms ∧ bearing-to-box-center <6°; streak ≥ 4 → VL |
| Weakness | judges only *attack instants*; smooth corrections between attacks invisible; no tracking consistency, no prediction/aim-rotation correlation over time; bearing uses box center (unfair for tall entities: enderman) |
| False positives | flick-shots onto mob spawners; turning exactly when attacking a stationary mob |
| Missing telemetry | rotation stream between attacks, per-tick angular velocity, aim-correction sign changes, target-switch cadence |
| Reference | Grim `checks/impl/aim/*` (stream-based), `AimProcessor` |
| Improvement | consume per-attack rotation context + monitor rotation stream (Phase 5 monitor), correction-consistency + switch-arc analysis — 6C-A (bounded) |
| Tests | snap vs smooth-aim synthetic tests; tall-entity bearing test; correction-streak test |

### 6.4 ReachCheck (`check/combat/ReachCheck.java`)

| Attribute | Content |
|---|---|
| Check | `reach` (combat) |
| Data source | eye→AABB distance per damage event |
| Algorithm | distance > 3.0+0.03 → consecutive ≥ N and EMA > cap → VL × excess |
| Weakness | no latency compensation in verdict (by design, documented); uses attacker→target geometry at receipt (safe); consecutive-window logic can be gamed by alternating in/out; no distinction box-to-box vs eye-to-box |
| False positives | knockback-closing-distance attacks (position lag), high-ping edge cases (documented) |
| Missing telemetry | ping-aware position reconstruction, distance at swing time (packet rotation time), attempt geometry (misses) |
| Reference | Grim `Reach.java`; NoCheatPlus fight |
| Improvement | keep distance model; add swing-time sampling via monitor + document ping semantics — 6C-A (limited) |
| Tests | box-edge distance tests; tall/wide entities; regression of VL ladder |

### 6.5 FastBreakCheck (`check/player/FastBreakCheck.java`)

| Attribute | Content |
|---|---|
| Check | `fast-break` (player) |
| Data source | `BlockBreakEvent` (completion only) + hardness/Efficiency/Haste |
| Algorithm | inter-break interval < vanilla lower bound (hardness/tool) minus 50 ms leeway; impossible-streak ≥ 3 → VL; instant materials exempt |
| Weakness | **no START/ABORT/STOP digging lifecycle**; measures completion-to-completion, not dig duration; no mining fatigue/water/aqua-affinity/ground-state inputs; penalty VL vs grace not bucketed; no lag adjustment |
| False positives | mcMMO/treecapitator (exempt via permissions or config), efficiency mis-detection on wooden tools, lag bursts |
| Missing telemetry | dig start, dig abort, target block change, tool swap mid-dig, hold-item Efficiency at swap, mining fatigue level, in-water/aqua-affinity, on-ground, block-broken-by-other-player |
| Reference | NoCheatPlus `blockbreak/FastBreak.java` (penalty bucket + lag adjust); Grim `checks/impl/breaking/*` |
| Improvement | packet digging lifecycle (6B: START_DIGGING/CANCELLED_DIGGING/FINISHED_DIGGING) → real dig-duration vs vanilla break-time (hardness, best tool, correct-tool bit, Efficiency, Haste, mining fatigue, water+aqua affinity, ground state, ping, TPS) with **penalty bucket**; instant-break exempt; config opt-out — 6C-C |
| Tests | vanilla break-time formula tests (oak log/wood pickaxe vs diamond+Eff V), abort-handling, target-change reset, fatigue multiplier, unbreakable path, penalty-bucket FP behavior |

### 6.6 NoFallCheck (`check/movement/NoFallCheck.java`)

| Attribute | Content |
|---|---|
| Check | `no-fall` (movement) |
| Data source | `DetectionContext` onGround (client flag), accumulated descent (MovementTracker), fall-cause damage (Phase 5, cause-separated) |
| Algorithm | descent ≥ 3.5 at landing w/o FALL-cause damage in 500 ms → suspicious; streak ≥ 3 → VL |
| Weakness | client onGround is the input; no server-side collision probe → can't catch "claimed ground in air"; damage-ordering skew handled only by listener backstop; no trajectory/velocity-model cross-check |
| False positives | slime/honey bounces (exempt), levitation/slow-falling (exempt), velocity from explosion (grace) — acceptable |
| Missing telemetry | feet-box collision probe at claimed ground, vertical velocity at landing, server block-below state, fall-trajectory consistency |
| Reference | Grim `groundspoof/NoFall.java` (collision probe); NoCheatPlus `NoFall.java` (damage model) |
| Improvement | server-collision adapter: on ground claim, probe feet block-below solidity (via block-source interface, testable); combine with descent + FALL-cause damage + trajectory (phase-5 evidence) — 6C-D |
| Tests | collision-probe unit tests (solid/air/water below), trajectory-vs-claim test, unrelated-damage regression (existing), slime/fly interaction test |

### 6.7 SpeedCheck (`check/movement/SpeedCheck.java`)

| Attribute | Content |
|---|---|
| Check | `speed` (movement) |
| Data source | `DetectionContext` horizontal distance + dt; sprint/potion/slippery flags |
| Algorithm | EMA (α=0.35) vs vanilla caps (walk 4.317 / sprint 5.612 / +20% Speed / ice ×1.6); sustained excess ≥ 1.5 s → VL |
| Weakness | no acceleration model (instant-speed burst after knockback exempt, but acceleration cheats ride the same EMA); no per-version attribute caps (fixed constants); no backlog-burst discrimination beyond dt<45 ms skip |
| False positives | speed potions read correctly; ice multiplier coarse (all "slippery" equal); sprint-jump jitter (mitigated by 45 ms skip) |
| Missing telemetry | vertical-vs-horizontal decomposition per tick, block friction at feet (soul sand/honey/web), dolphin's grace, sprint-toggle state |
| Reference | Grim prediction engine (full), NoCheatPlus Magic/SurvivalFly (partial) |
| Improvement | keep caps (documented as vanilla attributes, per-version constant table); add friction-aware cap context (web/soulsand/honey/slowdowns) via block probe — bounded 6C-B; document acceleration gap honestly |
| Tests | EMA tests, potion ladder tests, friction-context tests, burst-window regression |

### 6.8 FlyCheck (`check/movement/FlyCheck.java`)

| Attribute | Content |
|---|---|
| Check | `fly` (movement) |
| Data source | airborne accumulator (MovementTracker), client onGround flag, levitation/slow-fall/elytra flags |
| Algorithm | hover (net≈0 & descent small for 3 s) or rising (upTicks ≥ N) while airborne → VL |
| Weakness | judges only vertical net — horizontal-only fly (constant y, or slow descent at 0.04 b/t… wait, slow descent ≈ fall, but tiny descent 0.02 b/tick sustained is classic fly) — currently falls under "falling" path? No: hover requires |net| ≤ tol; 0.02 sustained passes hover → caught. True gap: *gradual spiral descent* & water-float; no trajectory-invalidity (too-fast fall without ground contact) case; no collision cross-check |
| False positives | slime/honey bounces (exempt), explosions (velocity grace), levitation (exempt) |
| Missing telemetry | trajectory-validity at landing, collision state at apex, per-tick gravity consistency |
| Reference | Grim `FlightA`, NoCheatPlus SurvivalFly envelopes |
| Improvement | trajectory-consistency: per-tick Δy must match gravity integral ± envelope for jump/fall; sustained 0.02 descent over ≥20 ticks counts as hover regardless of net — tighten with unit tests — 6C-B |
| Tests | envelope tests (jump arc vs straight-hover vs slow-descend-fly), slime bounce FP, spiral case |

### 6.9 TimerCheck (`check/movement/TimerCheck.java`)

| Attribute | Content |
|---|---|
| Check | `timer` (movement) |
| Data source | trailing 1 s movement-packet ring (Bukkit events, or max(event, raw packetevents position-packet rate when present)) |
| Algorithm | rate > 21.5/s sustained ≥ 3 s → small VL |
| Weakness | measured per **wall-clock second**, not client tick; client FPS spoof/burst can inflate briefly (mitigated by 3 s gate); without packetevents, Bukkit coalescing (≈20/s) caps signal; 1.21.2+ packet behavior unverified in CI |
| False positives | backlog drains after tick lag (paper emits bursts) — mitigated by sustained gate |
| Missing telemetry | client tick budget (transactions), per-tick packet count, ANIMATION/INTERACT interleaving |
| Reference | Grim `checks/impl/timer/*` (transaction-accounted), packetorder |
| Improvement | 6B telemetry records per-second move+action packet histogram; keep threshold (no raising); document client-tick accounting as NOT_IMPLEMENTED |
| Tests | ring-window tests (already), pre-backlog regression kept green |

### 6.10 AttackListener (`listener/AttackListener.java`)

| Attribute | Content |
|---|---|
| Data source | EntityDamageByEntityEvent only |
| Weakness | **misses all non-damaging attack attempts** (misses, blocked hits, out-of-cooldown hits dropped server-side — the 1.21.2+ problem is doubly invisible); entity AABB from Bukkit is main-thread OK but geometry uses getWidth()/2 only for target box (fine) |
| Improvement | packet attempt ledger (6B) feeding AttackContext as `attemptSource`; damage events and attempts both feed combat trackers with an `isAttempt` distinction |
| Tests | attempt-count vs damage-count accounting, miss-recording, dedupe (same swing = 1 attempt) |

### 6.11 BlockBreakListener (`listener/BlockBreakListener.java`)

| Attribute | Content |
|---|---|
| Data source | BlockBreakEvent (completion only) |
| Weakness | **no dig lifecycle**; tool facts read at completion (item may have changed during dig); no fatigue/water context |
| Improvement | DiggingTracker via packets (6B); BlockBreakEvent only finalizes verification (6C-C) |
| Tests | lifecycle integration tests (START→STOP, START→ABORT, tool swap) |

### 6.12 PlayerMoveListener (`listener/PlayerMoveListener.java`)

| Attribute | Content |
|---|---|
| Data source | MONITOR PlayerMoveEvent, try/catch around everything |
| Weakness | Bukkit move events are **coalesced to ≤20/s** by Paper; the true packet stream is coarser signal here; onGround = client claim (now partly mitigated by 6C-D probe); per-tick context built for every packet (allocation-heavy but bounded) |
| Missing telemetry | rotation-only vs position+rotation packets distinction at packet level; client tick index |
| Improvement | 6B: rotation delta from raw packets feeds combat aim; position telemetry stays main-thread; no extra Bukkit calls on netty |
| Tests | context-builder tests existing; packet-order tests new (6B) |

### 6.13 PacketEventsHook (`packet/PacketEventsHook.java`, `packet/PacketEventsMetrics.java`)

| Attribute | Content |
|---|---|
| Status | optional softdepend; implementation class loaded only when PE present; atomics on netty thread; clean unregister on disable |
| Weakness | only *counts* packets today — no digging/attack/rotation telemetry; `noteRawMovePacket` ring is the only structured signal |
| Improvement | 6B: `PacketEventsTelemetry` sister class with the same isolation pattern, feeding DiggingTracker + AttackAttemptLedger + rotation delta ring |
| Tests | unit tests for trackers (plain Java); packet-shape tests via synthetic packet objects decoupled from PE where possible |

### 6.14 PlayerMonitor (`monitor/PlayerMonitor.java`, Phase 5)

| Attribute | Content |
|---|---|
| Algorithm | fixed-size state block + junction events + evidence ring (256/90 s) |
| Weakness | records *transitions and events*, not per-tick cadence; no rotation stream sampling; confidence heuristic not calibrated |
| Improvement | feed from 6B telemetry (attempt/damage events stamped monitor evidence); add `ATTACK_ATTEMPT` reason via existing noteCheckEvidence; no change to ring semantics |
| Tests | existing + new attempt-evidence test |

### 6.15 EvidenceStore (`monitor/EvidenceStore.java`, Phase 5)

| Attribute | Content |
|---|---|
| Algorithm | bounded deque ring, TTL eviction, synchronized |
| Weakness | no per-check indexing (documented O(256) scans at render); no related-evidence linking |
| Improvement | add optional `related` list? — **deferred**: violates alloc-free hot path; keep 256-cap scans; per-check history via `Evidence.check()` (Phase 5) |
| Tests | existing + concurrency bound test green |

### 6.16 CorrelationEngine (`monitor/CorrelationEngine.java`, Phase 5)

| Attribute | Content |
|---|---|
| Algorithm | stateless; NoFall corroboration → confidence + supporting/suppressing lists |
| Weakness | **informational only — never gates**; only NoFall correlated; unrelated-damage fix (Phase 5) not yet propagated as engine-level rule for other checks |
| Improvement | 6C-E: extend to cross-check correlation (Reach∧KillAura, FastBreak∧dig-duration, Fly∧trajectory) where both signals exist; add CONFIRMED/SUSPECTED/INCONCLUSIVE tri-state output to DetectionStatus; still never auto-bans |
| Tests | tri-state tests, suppression precedence tests |

## 7. Verified audit questions (§3 of the task)

1. **KillAura only counts entities damaged in a window — confirmed.** It never sees attempts.
2. **AttackListener misses non-damaging attacks — confirmed.** Input is EntityDamageByEntityEvent only.
3. **FastBreak has no START/ABORT/STOP digging tracking — confirmed.** Completion event only.
4. **NoFall does not distinguish client onGround from server collision — confirmed.**
5. **Timer does not receive the true movement-packet rate unless packetevents is installed** (max(event, raw PE rate) when present; event-only otherwise — partially confirmed).
6. **CorrelationEngine does not affect detection decisions today — confirmed** (display-only confidence).
7. **Vanilla vs hack discrimination is partial at best**: strong in NoFall/Speed gates, weak in KillAura (3-target rule) and unreachable-on-1.21.2+ in auto-clicker CPS gate (FP-7).

None of the above were assumed; all were read in source.

## 8. Priorities from the audit (drives 6B/6C)

**P0 — correctness defects to repair**
- FastBreak digging lifecycle missing entirely → 6B + 6C-C.
- Attack attempts invisible → 6B + 6C-A.
- KillAura single-signal detection → 6C-A.
- NoFall no collision probe → 6C-D.
- Timer blind w/o packetevents → keep; 6B improves raw feed; document.

**P1 — detection quality**
- Aim/rotation stream analysis (attempt + rotation delta) → 6C-A.
- Penalty-bucket for fast actions → 6C-C.
- Fly trajectory envelope consistency → 6C-B.
- Speed friction context → 6C-B (bounded).

**P2 — honesty/exposure**
- CONFIRMED/SUSPECTED/INCONCLUSIVE status tri-state → 6C-E.
- Correlation wired as advisory into DetectionStatus; never gates VL → 6C-E.
- Doc rewrites: REAL_CLIENT_VALIDATION = BLOCKED; manual test protocol → 6D/6E.

**Explicitly NOT planned in Phase 6** (documented NOT_IMPLEMENTED):
- Full Grim-style movement simulation + setback (needs compensated world; rewrite).
- Transaction-ordered latency compensation for verdicts.
- Client tick accounting for Timer (needs transaction pipeline).
- Jesus/Step new detectors — initially listed here; later implemented in a follow-up commit (v1, UNIT_TESTED only, REAL_CLIENT_VALIDATION=BLOCKED). Spider and InventoryMove remain NOT_IMPLEMENTED.
- Themis-derived algorithms (repo unavailable).

## 9. License and attribution

- Grim — GPL-3.0. Methods studied: prediction engine concept, collision-probe NoFall,
  transaction ordering. No code copied.
- NoCheatPlus — GPL-3.0. Methods studied: penalty buckets, magic constants as references,
  duration-based fast-break validation. No code copied.
- Negativity — GPL-3.0. Methods studied: modular cheat registry + per-check config;
  AutoClick design rejected deliberately. No code copied.
- VigilAC remains MIT. All Phase 6 code is original implementation.
