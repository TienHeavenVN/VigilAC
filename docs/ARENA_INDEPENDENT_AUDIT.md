# VigilAC — Independent Security & Anti-Cheat Audit (Phase 6)

**Date:** 2026-11-07 · **Branch audited:** `arena/a7154a72-vigilac` (post-merge of PR #2, tip `70b8853`)
**Auditor:** Arena.ai independent audit agent · **Implementer under review:** Codex (Phase 6 commits `97f6961` … `cd3a6f0` plus PR-2 merge)
**Method:** source-code reading of every audited component with line-level citations; vanilla-physics arithmetic re-derived and cross-checked against public Minecraft Wiki / game-code references; reference anti-cheats studied locally (Grim at `d8d7b37`, Updated-NoCheatPlus at `a64a911`, Negativity v2 at `a0127f9` — **studied, not copied; VigilAC is MIT, all three are GPL-3**). No server was booted for this audit; **no real cheat client was run**; nothing in this document may be quoted as "real-client verified".

## Verdict at a glance

Phase 6 is a genuine architectural improvement (multi-signal combat, dig lifecycle, collision-probe NoFall, packet telemetry, tri-state correlation) and the CI matrix is honestly reported. However the audit found **two critical defect classes that survive the merged PR**:

1. **The block-break time model ignores tool mining speed entirely.** Every sustained mining action on a vanilla-correct tool in Path A (packetevents present, the production path) over-accrues "missing dig time" until the penalty bucket crosses grace. Iron-pick stone miners flag within 1–2 blocks; obsidian miners flag within 1–3 blocks. Path B (PE absent) flags the same behavior after 3 consecutive blocks. Existing unit tests only exercise wooden-tool-equivalent speed. — AUDIT-01 (§5)
2. **Reach still assumes a universal 3.0-block cap.** Since 1.21.11 ("Mounts of Mayhem", Dec 2025) Java Edition ships spears with a 4.5-block attack range governed by the `minecraft:attack_range` item component, which overrides `entity_interaction_range`. Every spear jab between 3.03 and 4.5 blocks is "impossible" by VigilAC's math on all 1.21.11+ and 26.x servers. — AUDIT-02 (§5)

Plus a high-severity cluster: AimAssist's telemetry swing metric (AUDIT-03), Step flagging slime bounces (AUDIT-05), Jesus with no riptide/wind-burst coverage (AUDIT-04). None of these are theoretical: each is a deterministic code path or a vanilla-physics reproduction.

**What holds up:** the telemetry/tracker primitives are correctly synchronized and bounded (checked thread-safety and lifecycle explicitly — see §6); the NaN/finite geometry guard; the penalty-bucket decay with the `90312e9` sentinel fix; the fail-open NoFall probe; the VL>0 pipeline gate; the honest BLOCKED status of real-client validation.

## 1. Scope

Components audited (all paths relative to repo root at tip `70b8853`):

`check/combat/{KillAuraCheck,AimAssistCheck,AutoClickerCheck,ReachCheck,AttackGeometry}.java` ·
`check/movement/{SpeedCheck,TimerCheck,FlyCheck,NoFallCheck,JesusCheck,StepCheck,InvalidMovementCheck}.java` ·
`check/player/{FastBreakCheck,FastPlaceCheck,ScaffoldCheck}.java` ·
`packet/{PacketEventsHook,PacketEventsMetrics,PacketEventsTelemetry}.java` ·
`data/tracking/{AttackAttemptLedger,CombatTracker,DiggingTracker,FastBreakPenalty,MovementTracker,RotationTracker,SwingTracker,BlockActionTracker}.java` ·
`monitor/{CorrelationEngine,CorrelationResult,DetectionStatus,PlayerMonitor,EvidenceStore}.java` ·
`listener/{AttackListener,PlayerMoveListener,BlockBreakListener,BlockPlaceListener,ConfounderListener,PistonConfounderListener}.java` ·
`detection/{DetectionContext,AttackContext,BreakContext,PlaceContext}.java` · `check/{CheckManager,ViolationManager,ViolationPipeline}.java` · `environment/BukkitBlockSupportProbe.java` · `config/{VigilACConfig,CheckSettings}.java` · `data/PlayerDataManager.java`, `data/PlayerData.java` (threaded paths only) · test suite (`FastBreakCheckTest`, `SpeedCheckTest`, `StepCheckTest`, `JesusCheckTest`).

**Out of scope:** Grim/NCP/Negativity internals beyond behavioral comparison; punishment-mode behavior (alert-only deployment assumed); database layer; build chain (CI is the compile gate, untouched).

## 2. Method & evidence standards

- Every "verified defect" in this report cites `file:line` and includes a reproduction argument (code path or vanilla arithmetic). Where a claim rests on external sources, the URL is given. Where I could not verify something, it is marked **UNVERIFIED** in §9, not silently promoted.
- Whenever the source and its own javadoc disagree (e.g. the AimAssist "smooth aim never flags" claim), the source wins and the doc is reported as wrong.
- Arithmetic checks (vanilla break-time, slime-bounce vertical velocity, orbit angular coverage) were re-derived from the vanilla formulas documented on the Minecraft Wiki and cross-referenced at least one other source (see Appendix A). These are closed-form computations, not measurements; they establish *possibility*, not frequency — frequencies marked UNVERIFIED need in-game capture.

## 3. Reference anti-cheat comparison (why the design choices are right/wrong)

| Concern | GrimAC (studied, GPL-3) | Updated-NoCheatPlus | Negativity v2 | VigilAC Phase 6 (as audited) |
|---|---|---|---|---|
| Compensated world / prediction | Full compensated entity+world state machine; reach measured from **predicted** position at the attack tick; setbacks on violation | Packet-level bookkeeping, balance/move prediction for move checks | Lightweight heuristics on move events | None — positions are the server's last-known Bukkit state (AttackListener.java:64–89). **Structural ceiling: reach/fly can never match Grim without a rewrite.** Documented by Codex as NOT_IMPLEMENTED — correctly descoped, but it caps every position-based check's precision |
| Reach | `ReachUtils.getMinReachToBox` from predicted position; `ATTACK_RANGE` item component honored since 1.21.11 (`ItemAttackRange`, incl. spears); packet cancel for ≥ buffer | Distance check w/ lag modeling; configurable | Distance check | Box-closest distance at **current** Bukkit positions, universal 3.0 cap, no ping handling, no attack_range component (AUDIT-02) |
| Timer | Client-tick accounting via transaction ordering | Timing balance per move (Eventplayer) | Ping/delta checks | Wall-clock 1 s ring on packets (21.5/s gate, PE path only); acceptable proxy, documented; cannot detect <5% acceleration (by design) |
| NoFall | Collision prediction at landing point | Damage/momentum comparison | Fall distance + damage | Bukkit collision probe, fail-open (correct posture), plus descent accumulator |
| FastBreak | Duration from packets + full vanilla stack | (mostly legacy path) | None | Vanilla stack math attempted — **implemented with the tool-speed omission (AUDIT-01)**; dig lifecycle tracking itself is sound |
| Multi-signal combat | Many narrow packet checks; packet cancellation | Heuristic | Heuristic | Multi-signal anchors — direction is right; metric choices can be beaten by configured cheats (AUDIT-03, §8) |
| ML | Not shipped | Not shipped | Not shipped | Not implemented; see §7 |

License posture: VigilAC copies no GPL-3 code — all Phase 6 math is reimplemented. This audit adds no code either. Preserving MIT requires keeping it that way; do not let "borrow Grim's getMinReachToBox" creep in.

## 4. Verified-defect summary (ranked)

| ID | Severity | Area | One-line | Confidence |
|---|---|---|---|---|
| AUDIT-01 | **Critical** | FastBreak / BreakContext | Tool mining speed ignored; both paths mass-flag legit mining | Verified (arithmetic + code + tests) |
| AUDIT-02 | **Critical** | Reach / version compat | 3.0 cap universal; 1.21.11+ spears = 4.5 blocks → guaranteed VL | Verified (code + 1.21.11 release notes/wiki + Grim source) |
| AUDIT-03 | High | AimAssist | Telemetry "swing" = summed angular distance, not a snap metric; doc claim false | Verified (code) |
| AUDIT-04 | High | Jesus | No riptide/wind-burst coverage; velocity grace caps at 2000 ms → vanilla-legal arcs flag | Verified (code + physics); frequency UNVERIFIED |
| AUDIT-05 | High | Step | Slime bounce (3+ consecutive ticks > 0.95) crosses the 2-tick gate → legit parkour flags; doc claim wrong | Verified (code + physics); frequency UNVERIFIED |
| AUDIT-06 | Medium | AutoClicker | 20.5 CPS gate convicted on rate alone; below documented human butterfly/touch ceilings | Verified (code + human-ceiling literature); Android frequency UNVERIFIED |
| AUDIT-07 | Medium | AttackListener/AimAssist | `recordAttack` runs before evaluation ⇒ `interval` is always 0 ⇒ snap condition interval-check is vacuous; dead logic; wrong debug output | Verified (code) |
| AUDIT-08 | Medium | Reach (fp class) | Javadoc claims latency can only shrink apparent reach — **wrong direction** for a moving target; no compensation anywhere | Verified (reasoning); empirical frequency UNVERIFIED |
| AUDIT-09 | Medium | Correlation/NoFall | 15 s "recent" window for teleport/velocity suppression is 8–30× too long; corr never affects VL but renders `corr=` misleading | Verified (code) |
| AUDIT-10 | Info | NoFall probe | Entity-supported landings exculpate via the damage-grace path before the probe (analysis); closed, not a defect | Verified by analysis |
| AUDIT-11 | Low-Med | FastBreak Path B | Legitimate server-side vein-miner/insta-break plugins accrue impossible-streak VL | Verified (code path) |
| AUDIT-12 | Low | Telemetry listener | `warned` non-atomic check-then-set across packet loops; minor log spam | Verified (code) |
| AUDIT-13 | Low | FastPlace (adjacent) | High-frequency tap-to-place (touch players) — same class of human-rate FP as AUDIT-06; noted, not audited in depth | Note |
| AUDIT-14 | Info | BreakContext | Path A caps haste at 4, Path B at 3; `(eff+1)²` vs `eff²+1` inconsistency between paths | Verified (code) |
| AUDIT-15 | Info | FastBreak | `canHarvest` divisor (30/100) unmodeled; unknown-tool family ⇒ "correct=true" documented as quieter, but unknown + fast real tool still flags (compound of AUDIT-01) | Verified (code) |
| AUDIT-16 | Medium | KillAura | Anchor (3 distinct damaged targets / 1 s) + PE attempt-rate ≥ 8/s alone satisfies the 2-signal minimum — no rhythm or packet-integrity corroboration required | Verified (code); frequency UNVERIFIED |
| AUDIT-17 | Info | CombatTracker | `intervalCv()` javadoc's "humans land above ~0.2" biomechanical constant is unvalidated and unused in any verdict | Verified (code) |

Exploitability column notes (summary; details per finding in §5): AUDIT-01/02/05 are false-positive engines, not bypasses — they burn trust. AUDIT-03/06 create bypass room: a configured cheat just stops trying. The Phase-6 combat overhaul catches unconfigured clients, not tuned ones — expected, but the report must say it plainly: **the configured-adversary model is currently out of scope and that is a product decision, not an accident.**

## 5. Findings in detail

### AUDIT-01 — CRITICAL: break-time model ignores tool mining speed (`BreakContext`)

**Evidence**

- `BreakContext.minimumBreakMillis()` (`detection/BreakContext.java:80-92`): `speed = 1.0 + (eff + 1) * (eff + 1)` — the tool's mining speed tier never appears. Correct tool with 0 efficiency yields speed 2 (wooden-tool equivalent), obsidian (hardness 50) yields a "lower bound" of 37.5 s.
- `BreakContext.expectedBreakMillis()` (`detection/BreakContext.java:110-133`): `correctTool ? 1.0D + (eff > 0 ? eff*eff + 1.0D : 0.0D) : 1.0D` — again tool speed absent; a fully correct-tool (no efficiency) dig gets speed 1.0, i.e. "as if every tool were slower than a wooden pickaxe".
- `listener/BlockBreakListener.java:63-69` captures only `toolName` (for the family heuristic) and `efficiency`; **the material is never mapped to speed**. `efficiency` and `correctTool` are the only tool inputs.
- Unit-test blindness proven: `src/test/java/ac/vigil/check/player/FastBreakCheckTest.java:93-101` — the only legal-rate test is "Wooden pickaxe (eff 0) on stone … every 1.8 s is legal". No test in the suite mines obsidian, and no test uses iron/diamond/netherite tools. CI green is fully compatible with this defect.
- Vanilla ground truth (sources in Appendix A): `damagePerTick = destroySpeed / hardness / (canHarvest ? 30 : 100)`; `ticks = ceil(1 / damagePerTick)`; 20 ticks/s; `destroySpeed = toolSpeed + (eff > 0 ? eff² + 1 : 0)`, multiplied by Haste `(1 + 0.2 × level)`, Mining Fatigue `× 0.3^level` (code's cap-4 variant is off by ≤1 level from vanilla's hardcoded table — acceptable once tool speed exists), divided by 5 underwater without Aqua Affinity, divided by 5 off ground; `toolSpeed` wood 2 / stone 4 / iron 6 / diamond 8 / netherite 9 / gold 12. Efficiency bonus applies only if the tool is effective for the block.

**Reproduction (arithmetic, no server needed)**

Let `deficit = expected − actual` (`missing = expected − duration − 50 ms`, `TICK_LEEWAY_MILLIS = 50`, `FastBreakCheck.java:38`, subtracted at `FastBreakCheck.java:101-103`); Path A credits the positive remainder to the penalty bucket, and VL requires bucket > 1500 ms.

| Scenario | Vanilla time | VigilAC `expectedBreakMillis` | Phantom deficit per block | Bucket > grace after |
|---|---|---|---|---|
| Stone 1.5, iron pick (6), eff 0 | 1.5×30/6/20 = **0.375 s** | 1.5×1.5/1.0 = **2.25 s** | **1875 ms** | **first block** (1875 > 1500) |
| Stone 1.5, wooden pick (2), eff 0 | 1.125 s | 2.25 s | 1125 ms | second block (2250 > 1500) |
| Obsidian 50, diamond pick (8), eff 0 | 50×30/8/20 = **9.375 s** | **75 s** | **65 625 ms** | **first block** |
| Obsidian 50, netherite + eff 5 (9+26=35) | 50×30/35/20 = **2.14 s** | 75/27 = 2.78 s | 640 ms | third block (~1.9 s) |
| Stone 1.5, netherite + eff 5 | 0.066 s | 0.083 s | 17 ms/block | ~88 blocks (marginal, still real) |

Path B (`minimumBreakMillis`, PE absent): obsidian with **any** correct pick eff 0 ⇒ 37.5 s floor vs 9.4 s actual ⇒ every legit block "impossible" ⇒ `fast-break` VL after 3 consecutive breaks. Iron-pick stone at 0.375 s vs floor 1.125 s ⇒ flagged after 3 blocks on every vanilla server without packetevents.

The Javadoc in `BreakContext.java:99-109` claims base is "treated as 1.0 — conservative, matches the hardness × 30 scale". It is the opposite of conservative for a lower bound, and it does not "match" the vanilla scale at all; it omits the ×30/×100 and the tool speed simultaneously.

**Why CI stayed green:** CI has no packetevents plugin ⇒ Path A never executed in the harness; Path B's wooden-equivalent floor happens to accept wooden tools at exactly their vanilla rate (the "Wooden pickaxe … every 1.8 s is legal" unit case, `FastBreakCheckTest.java:93-101`), and the only high-eff correct-tool coverage in the suite is the Haste-II stone case (line 65). The production path (PE present) — where the phantom deficit is largest — has never been executed anywhere, in tests or in the live harness.

**Severity:** Critical. On a stock production configuration (`config.yml` ships `checks.fast-break.enabled: true`), **every sustained mining session on correct tools produces repeated `fast-break` violations**, contaminating monitor output and staff trust, and — in punish mode — manufacturing kicks for completely vanilla play (false-positive ban pipeline).

**Recommended fix (for Codex; do not paper over by disabling the check):**

1. `BreakContext` gains `toolSpeed` (float). `BlockBreakListener` maps `tool.getType()` → {2,4,6,8,9,12}; unmapped tools set speed from the family table's floor (pickaxe/axe/shovel/hoe ⇒ 2) and default bare-hand/unknown correct-family ⇒ 1; unknown family keeps `correctTool=true` but speed unknown ⇒ **the check must not accrue timing evidence** (skip timing verdict; keep unbreakable/insta-mine paths).
2. Compute `destroySpeed = correctTool ? toolSpeed + (eff > 0 ? eff² + 1 : 0) : 1.0`, then `speed *= hasteFactor * fatigueFactor * waterFactor * groundFactor` with the vanilla multipliers (haste `1 + 0.2×level`, fatigue `0.3^level`, water/ground `×0.2`), then `ticks = hardness × (canHarvest ? 30 : 100) × 20 / destroySpeed / 20` — i.e. keep seconds: `millis = hardness × (canHarvest ? 1500.0 : 5000.0) / destroySpeed`. `minimumBreakMillis` (hard floor for Path B) uses the same stack but clamps haste to the held-item maximum **and assumes** best case (eff V, haste III, no penalties) — a floor must be ≥ optimistic, never beyond it.
3. Make the two paths share one implementation (`BreakTimeModel`) so they cannot drift again (AUDIT-14's cap mismatch came from duplicated logic).
4. Tests required (see §10): obsidian eff 0/5 with diamond & netherite; stone iron eff 0; sand/dirt shovel; wrong-tool stone; bedrock; haste+fatigue combos; underwater+aqua-affinity; off-ground; instant-break blocks; unmapped modded tool ⇒ timing verdict suppressed.

**False-positive analysis:** with the fix, remaining FP sources are negligible (vanilla ceilings preserved). Without it, FP rate ≈ 100% of sustained legit mining sessions on any PE server.

### AUDIT-02 — CRITICAL: reach assumes a universal 3.0-block cap; spears break it since 1.21.11

**Evidence**

- `config/VigilACConfig.java:90` — `reachMaxReach()` default 3.0; `ReachCheck.java:15-19` (javadoc: "legitimate hits cluster at ≤ 3.0 blocks for players"); no code path reads any item component.
- `AttackListener.java:64-89` builds the context from eye/box/ping/gamemode only — **the held item is never consulted**.
- Minecraft 1.21.11 "Mounts of Mayhem" (December 2025; all `26.x` releases postdate it) adds seven spear tiers. Spear jab/charge attacks span **2–4.5 blocks** (up to 6.5 in Creative — irrelevant, creative is exempt), governed by the `minecraft:attack_range` item component, which "overrides the normal entity interaction range for players" (minecraft.wiki [Melee attack](https://minecraft.wiki/w/Melee_attack); [Java Edition 1.21.11](https://minecraft.wiki/w/Java_Edition_1.21.11); the `attack_range` component carries `min_reach/max_reach/hitbox_margin`). Confirmed in the same form in GrimAC's Reach (`common/.../combat/Reach.java`): it reads `ComponentTypes.ATTACK_RANGE` and threads `clientAttackRangeExists` for `V_1_21_11+` — Grim's maintainer knew the 3.0 wall moved.
- VigilAC is version-agnostic for reach: `docs/COMPATIBILITY_MATRIX.md` pins protocol truth for packets but not for reach semantics.

**Reproduction:** Paper 1.21.11+ (or 26.x), survival, wooden spear (max reach 4.5). Attack a zombie at 3.6 blocks with clean positioning (or charge attack). Expected: vanilla connects, no damage scaling penalty for jab in-range. Observed with VigilAC: `distance=3.6 > allowed=3.03` every jab; after `min-attacks-over` (default 4) consecutive such attacks **and** EMA over cap, reach VL accrues — on completely vanilla play, forever. The immunity claim in the javadoc ("Latency does not increase reach … covered by 0.03 tolerance", `ReachCheck.java:32-35`) never contemplated weapon-dependent range.

**Severity:** Critical on any server running 1.21.11+ (which includes every 26.x cell in VigilAC's compatibility matrix). Prevalence: routine, not exotic — any spear-lobby is a standing false-positive generator until fixed.

**Recommended fix (for Codex):**

1. In `AttackListener`, read the main-hand `ItemStack`'s `attack_range` component when present (Paper API: `item.getComponents().get(new ItemComponentType<>("attack_range"))` or Paper's `DataComponentTypes.ATTACK_RANGE` — exact accessor depends on the pinned Paper API; verify against the 26.2–R0.1-SNAPSHOT API used by the compat legs before writing code).
2. Thread `attackRangeMax` (nullable) into `AttackContext`; `ReachCheck` uses `allowed = max(maxReach, component.maxRange()) + tolerance`. If the component says 4.5, vanilla behavior passes; absent component ⇒ today's 3.0.
3. Optional honesty upgrade (document-only if implemented): when component absent AND server ≤1.21.10, unchanged behavior.
4. Companion doc fix: COMPATIBILITY_MATRIX gains an `attack_range` row; CHECKS.md reach section states the weapon dependency; FALSE_POSITIVE_ANALYSIS gains "spear jab at 3–4.5 blocks" FP class until fixed.

**Interim mitigation:** gate the reach check off for servers whose protocol minor ≥ 1.21.11 until the fix lands (config `enabled: false` documented), or raise `checks.reach.max-reach` to 4.5 — the latter blinds detection for the other 99% of weapons; prefer the toggle.

### AUDIT-03 — HIGH: AimAssist telemetry "swing" is a sum, not a snap

**Evidence**

- `AimAssistCheck.java:89-94`: when the rotation tracker has ≥ 2 samples, `swing = data.rotationTracker().angularDistanceCovered(now, snapMaxIntervalMillis)` and `snap = swing > snapAngularDelta (default 45°) && interval <= 120 ms && angleToTarget < 6°`.
- `RotationTracker.angularDistanceCovered()` (`data/tracking/RotationTracker.java:60-77`) **sums** `hypot(Δyaw, Δpitch)` over all consecutive samples inside the window. Summed motion is the wrong primitive for "instant re-orientation": it can only grow, and it counts strafing/orbiting equally with flicks.
- `AimAssistCheck.java:28-30` (javadoc): "Smooth, continuous re-aiming never accumulates a snap streak; nothing in this check penalizes 'good aim'". False for this path — a smooth, target-locked flick (human snap, e.g. combo re-centering) or sustained fast orbit ≥ ~375°/s over 120 ms yields `swing = 45°+` with `angleToTarget < 6°` exactly when attacking.
- Unit-test coverage gap: `AimAssistCheck` tests (in the suite as of tip) exercise the fallback path semantics; no test demonstrates "fast-but-smooth orbiter stays clean" on the telemetry path (would fail today).

**What "snap" actually means to catch:** a large rotation delta **concentrated in one packet interval** immediately before the attack (aim-bot correction landing on target). The correct metric is one of: (a) `max` per-sample angular delta in the window, or (b) net delta between the newest sample and the sample `snapMaxIntervalMillis` earlier — (b) still counts orbits, so (a) is the right one; orbiting produces many small deltas (sub-degree to few degrees per 50 ms tick at any humanly-trackable rate: 20 ticks/s × 2.5° = 50°/s « 45°/120 ms), a true snap produces a single 30–90° delta in one packet.

**Reproduction:** unit test constructing `RotationTracker` samples `(0,0), (25,0), (50,0)` across 3 ticks, then an attack at yaw=50 toward the target: `swing = 50 > 45` with per-sample delta = 25°, `angleToTarget ≈ 2°`. Expected today: snap ⇒ 4 such attacks ⇒ aim-assist VL. A clean implementation (max-per-sample = 25° < 45°) flags nothing. This is a one-screen test that fails on current code.

**Severity:** High — false-positive path on fast-strafe/flick PvP (esports/NaN-adjacent play), alert-only today, but the same mis-binding also **under-detects** real assistance that spreads correction across 2–3 packets, i.e. the metric discriminates neither class cleanly.

**Recommended fix (for Codex):** replace the telemetry-path swing with `RotationTracker#maxDeltaInWindow(now, snapMaxIntervalMillis)` (new method, O(n≤64)); keep `angularDistanceCovered` only for monitor diagnostics; keep fallback path unchanged (it already uses per-attack deltas). Re-tune `snap-angular-delta` after capturing real data (do not re-gate by guess). Add the orbiter/flick unit tests above.

**False-positive analysis:** post-fix, snap requires a single ≥45° rotation in ≤120 ms landing within 6° of a target box — human flicks CAN reach 45° in one packet in principle; streak-of-4 plus angle gate keeps it rare; remains alert-only. Residual FP: none known beyond that.

### AUDIT-04 — HIGH: Jesus has no riptide / wind-burst coverage; grace caps at 2 s

**Evidence**

- `JesusCheck.java:78-84` exemption list: vehicle, gliding (elytra per Bukkit javadoc "such as using an Elytra"), inLiquid, teleportGrace, knockbackGrace, levitation, slowFalling, frostWalker, low TPS. **No riptide term; no wind-burst term.**
- Bukkit exposes `LivingEntity#isRiptiding()` separately since 1.13 (verified: Spigot-API javadocs, LivingEntity). `PlayerMoveListener.java` wires `isGliding()` only — there is no `isRiptiding()` anywhere in VigilAC (`grep -ri riptid src/` → empty).
- Grace for impulses: `ConfounderListener.java:74-76` — `grace = min(velocityGraceMillis + (long)(speed * 100.0D), 2_000L)`. A riptide III launch (several m/s) saturates the 2 s cap immediately; arcs over open water can ride airborne for ~2.5–3 s.
- Vanilla riptide over a lake: launch in rain/water, land back in a different spot 20–40 blocks away, feet never touch liquid mid-arc, descending tail lasts ~0.5–1 s after grace expires: 10–20 dry-feet-over-water ticks; a long flat arc (or a mace Wind Burst straight up, 1.21, same velocity pathway) can single-handedly exceed the 14-tick `minStreak` ⇒ `jesus` VL ~0.5–1.0 per occurrence.
- `JesusCheck` javadoc claims "repeated jumps … never reach the default streak of 14" — true for plain jumping, not for impulse-driven arcs.

**Reproduction (in-game, manual):** riptide-III trident, rain, straight lake crossing. Expected: no `jesus` VL. Predicted today: VL accrues from tick ~40 of the arc. Not executed — **frequency UNVERIFIED**; code path and physics verified.

**Recommended fix (for Codex):**

1. `DetectionContext` gains `riptiding` (from `player.isRiptiding()`), wired in `PlayerMoveListener` (player is `LivingEntity`). `JesusCheck` and `StepCheck` treat it as a hard exemption (resetting streaks), exactly like gliding.
2. Add a mace Wind Burst confounder note: wind-burst impulse is received by the attacker as velocity; the 2 s cap remains — option: dedicated `windBurstGraceUntil` on PlayerVelocityEvent with vertical-dominant impulses (dy > 0.6×speed) extends grace to 3 s for **vertical** impulses only (combat/knockback logic unaffected because knockback is horizontal-dominant).
3. `DETECTION_VALIDATION.md` gains a manual-protocol row: "riptide lake crossing — expect zero jesus VL" (requires real client; BLOCKED today).
4. Until merged: document on `/vigilac monitor` docs that `jesus` may fire on long riptide arcs (operators should not punish).

### AUDIT-05 — HIGH: Step counts slime-bounce arcs as impossible rises

**Evidence**

- `StepCheck.java:87-94`: exemptions = vehicle, gliding, liquid, teleport/velocity grace, levitation, slow falling, low TPS. **`isOnSlipperyBlock()` (slime/honey) is not consulted** — unlike FlyCheck (`FlyCheck.java` exempts slime-family bounces from its upward-flight branch) and NoFall (exempts slippery feet).
- Vanilla physics: landing on a slime block returns fall impact velocity as upward velocity; sneak-charging absorbs up to 8 blocks of fall and re-launches. Launch speed `v = sqrt(2 · 0.08 blocks/tick² · h)`; for h = 8: `v ≈ 1.13 b/tick`; for h = 6: `v ≈ 0.98 b/tick`; decelerating by 0.08/tick ⇒ the next two ticks are ≈1.05 and ≈0.97 — **three consecutive observations > 0.95**. `minStreak = 2` (StepCheck.java, `stepMinStreak` default 2, `config/VigilACConfig.java:StepCheck` wiring (`stepVlPerStep` default 2.5 at `VigilACConfig.java:118`) ⇒ a single legit sneak-charged slime bounce can trip the gate.
- `docs/CHECKS.md` (Step section) asserts "a sneak-charged full bounce could add at most VL once per bounce — acceptable for alert-only mode" and claims the 2-tick gate excludes it. **The assertion is arithmetically wrong** (3 ticks > 0.95, gate = 2); and "at most VL once per bounce" understates repeated bounce chains (each landing→bounce re-arms the streak).

**Reproduction (in-game):** parkour server with slime course, sneak-charge off a ≥6-block drop. Expected: zero `step` VL. Predicted: `dy=1.13`, `dy=1.05`, `dy=0.97` ⇒ VL on the second tick. Not executed — **frequency UNVERIFIED**; physics verified twice (formula + integer-tick deceleration).

**Recommended fix (for Codex):** add `ctx.isOnSlipperyBlock()` to the exemption reset in `StepCheck` (same semantics as Fly). Do NOT raise `stepMaxLegal` — the 0.95 boundary is intentionally tight; the exemption is the correct lever. Add the rebound-arc unit test (dy = 1.13/1.05/0.97/0.89 with slippery flag set ⇒ zero VL).

**False-positive analysis:** post-fix, sneak-charged slime bouncing is fully exempt (matches Fly/NoFall semantics); residual FPs: none known beyond the documented Jump-Boost-VI opening ticket (already unit-tested and pinned).

### AUDIT-06 — MEDIUM: AutoClicker rate gate (20.5 CPS) convicts on rate alone, below human ceilings

**Evidence**

- `AutoClickerCheck.java:91-105`: `attemptRate` read (`:91`), `rate = max(cps, attemptRate)` (`:93`), `rateSamples = max(samples, attemptSamples)` (`:94`); `if (rateSamples >= minSamples && rate > cpsCap)` (`:96`) → immediate VL with `amount = vlPerTrigger × min(2, rate / cpsCap)` (`:97`); the regularity gates (robotic mean/stddev) are a separate branch (`:106-132`) — **the rate path never consults rhythm**. The class javadoc documents the cap at `:19`.
- `config/VigilACConfig.java:124` — `autoClickerCpsCap()` default **20.5**. The javadoc calls this "above the ~20/s hardware ceiling".
- Human ceilings (public literature, not measured by this audit): jitter/butterfly mouse clicking is documented at 20–30+ CPS in PvP communities; Android multi-finger tapping on glass reaches 15–25 CPS. A 2026 Fold Craft Launcher audience (virtual mouse + touchscreens, §8) skews exactly into this band. `AttackListener` fires on **damage events**, not packets — vanilla 1.21.2+ damage cooldown caps *damage* rate, but attack **packet** rate (the attempt-rate side of `rate`) is not cooldown-capped for swings, and high-CPS tappers genuinely send >20.5/s.
- The attempt-rate side (`AttackAttemptLedger`) counts misses; rate = max(cps, attemptRate) — a butterfly tapper with 24 CPS and no PE installed still trips cps=24 > 20.5 whenever ≥10 intervals exist.

**Severity:** Medium — deterministic flag on documented-legit fast-tapper play; alert-only today; poisons operator trust on touch/Vanilla-PvP servers (exactly the FCL audience).

**Recommended fix (for Codex):** (a) raise default `cps-cap` to 25 and document the Android/touch rationale; (b) make the rate path require *both* rate > cap **and** (stddev < robotic stddev OR PE attempt-rate > cap by the same margin used today) — i.e. never convict on raw rate; (c) expose `checks.auto-clicker.touch-friendly: true` preset (cap 26, stddev gate unchanged) documented for FCL/mobile hosts; (d) no change to punishment defaults.

**False-positive analysis:** genuine software auto-clickers hold 20–25 CPS with near-zero stddev — they still trip the robotic branch; raising the raw rate gate loses nothing against configured adversaries (they randomize stddev anyway, §8) and spares humans.

### AUDIT-07 — MEDIUM: combat `interval` is always 0 (dead condition, wrong debug)

**Evidence**

- `AttackListener.java:91-96`: `data.combatTracker().recordAttack(context.getTimestamp()); … checkManager.evaluateAttack(...)` — recordAttack runs **before** checks evaluate, so inside AimAssistCheck `CombatTracker.lastAttackAt == ctx.getTimestamp()` for the current attack.
- `AimAssistCheck.java:88`: `long interval = tracker.lastAttackAgeMillis(now)` ⇒ `now − lastAttackAt == 0` always; `interval <= snapMaxIntervalMillis` is always true; `interval` in debug output is always `interval=0ms`. Same value is used for the fallback path condition.
- Consequence: (a) an attack 10 s after the previous one can still be an aim-assist "snap" — the time gate does nothing; (b) kill-aura's metronomic logic is unaffected (stddev-based), and AutoClicker's `cps()` window math is unaffected (ring-based) — impact is contained to AimAssist ergonomics and observability, but the condition as written is dead code that misdocuments itself.

**Recommended fix (for Codex):** capture `previousAttackAt` in the listener (before `recordAttack`) and pass it in `AttackContext` (`previousAttackAtMillis`); checks read `ctx.previousAttackAtMillis()` instead of the tracker for inter-attack spacing; keep the tracker solely for population statistics. Update unit tests to assert `interval>0` for spaced attacks.

### AUDIT-08 — MEDIUM: javadoc "latency cannot extend reach" is backwards; no compensation exists

**Evidence**

- `ReachCheck.java:32-35` (javadoc): "Latency does not increase reach: the server evaluates positions at receipt time, and a latency-lagged attacker is seen at a position at least as close as when they swung."
- Reality for a **moving target**: distance is computed attacker(last-known) ↔ target(last-known). If the target is strafing away from the attacker faster than the attacker's position updates, the *receipt-time* distance is larger than the *swing-time* distance. A 300 ms mobile (FCL) ping with a 5.6 b/s strafing target ⇒ ~1.7 blocks of added apparent separation; a legit 3.0 reach attack presents as 4.7 — three such attacks (chasing strafing players is the default PvP pattern) ⇒ reach VL on a completely fair fight, with **no ping term anywhere in the math**.
- The symmetric case (target moving toward attacker) shrinks apparent distance — undetectable in favor of the attacker, the opposite asymmetry of the doc claim.
- No compensation exists at any layer: no ping-scaled slack (only 0.03 FP-dust tolerance), no target-velocity projection, no transaction ack timing.

**Status:** reasoning verified; in-the-field frequency **UNVERIFIED** (needs capture on a real lagged PvP fight; the FCL/300 ms scenario in §8 makes it the default experience for the target audience).

**Recommended fix (for Codex):** either (a) project the target box by `targetVelocity × (pingMillis/1000)` toward the attacker when evaluating distance (Vector velocity from `target.getVelocity()`), clamped to ±1.5×ping_compensation_value; or (b) add `checks.reach.lag-compensation-millis` slack scaled by attacker ping (e.g. +0.35 blocks per 100 ms up to +1.0) — (b) is crude but honest and one line of config; (a) is the principled fix. Either way, **the javadoc must be corrected to admit the direction of the asymmetry.**

### AUDIT-09 — MEDIUM: correlation suppression window 15 s is 8–30× too long

**Evidence**

- `monitor/CorrelationEngine.java:24` — `RECENT_WINDOW_MILLIS = 15_000L`; the same window gates all four NoFall suppressors: teleport (`:43-45`), velocity impulse (`:47-49`), fall damage (`:51-53`) and world change (`:55-57`).
- Physical relevance of a knockback impulse for fall physics is ~1–2 s (the grace window in ConfounderListener already grants ≤2 s). A teleport to spawn 12 s ago does not excuse a landing now. Net effect: a PvPer who received velocity every 14 s stays INCONCLUSIVE forever — the `correlation=` field becomes decorative for exactly the players it would help clear.
- Mitigant: suppression never gates VL (verified at `NoFallCheck.java:169-170` — corr is advisory) and never overrides server facts; so this is mislabeled telemetry, not a detection hole.

**Recommended fix:** TELEPORT ≤ 2 s, VELOCITY_IMPULSE ≤ 1.5 s, WORLD_CHANGE ≤ 2 s, DAMAGE_FALL unchanged (must stay aligned with `damage-grace-millis`). Re-label RECENT_WINDOW_MILLIS per-factor. No behavior change to VL paths.

### AUDIT-10 — Info: NoFall entity-supported landings (closed in analysis)

**Initial suspicion:** `NoFallCheck.java:145-164` — on any non-exculpated landing ≥ threshold, `supportProbe.hasSupportBeneath(...)`; `isSolid()` on four corner columns at two block layers (`BukkitBlockSupportProbe.java:19-45`); only **blocks** count as support, so a landing where the player stands on a mob (entity stacking) reads "unsupported" ⇒ immediate VL (bypassing the 3-landing streak) **and** `corr=CONFIRMED` — the strongest label in the system.

**Analysis:** the fall-on-mob case exculpates itself before the probe ever runs — landing on an entity does not reduce accumulated fall distance, so vanilla deals fall damage for any fall > 3.5 blocks; that damage sets the 500 ms grace path (`NoFallCheck.java:134-138`), which returns before the probe. The only vanilla mechanism that could suppress a >3.5-block fall's damage entirely is Resistance IV+, and no stable vanilla source grants Resistance IV+ (totems grant Absorption; beacons grant at most Resistance II, which only halves damage). So an above-threshold entity-supported landing with **zero** fall damage is, in vanilla, indistinguishable from a NoFall cancellation — the probe's "unsupported" verdict is arguably correct impact evidence there. A fall on an entity at ≤ 3.5 blocks never reaches the probe (below `minFallDistance`). **Downgraded from a defect to an unverified edge:** recommend (a) document the reasoning in CHECKS.md instead of changing code, and (b) add a Resistance-aware exemption only if/when a real source is confirmed. No code change on this finding alone. Severity: Info (closed in analysis).

### AUDIT-11 — Low-Med: legit server-side multi-break plugins stream "impossible" streaks

**Evidence** (`FastBreakCheck.java:121-151`): a server-side veinminer/insta-break or an Operator-built WorldEdit-esque "break" removes blocks without `START_DIGGING` packets; `BlockBreakEvent`s stream 5–80 ms apart; no dig timing ⇒ Path B; `minimumBreakMillis` (buggy per AUDIT-01 but even the Wood-equivalent floor of e.g. 57 ms for stone-eph0 … recomputed per AUDIT-01 fix) still exceeds typical 0–2 ms synthetic gaps ⇒ impossible-streak ≥ 3 within seconds ⇒ VL on features the server operator deliberately installed. Not a cheat, not a bug in the plugin either — a model gap (server-driven breaks are indistinguishable from mass-break cheats sans permission context).

**Recommended fix:** exempt players with a configurable `ac.vigil.bypass.break` (default granted to ops/permission holders — ops already have general exempt via `exemptPermission` if wired that way; document), or skip timing verdicts when `event` came from a plugin-invoked break (not reliably detectable — prefer permission). Document in OPERATOR docs, not code.

### AUDIT-12/14/15 — Low / info

- **AUDIT-12** `PacketEventsTelemetry.java:78,127-133` — `warned` plain boolean, check-then-set, across packet event loops ⇒ duplicate "telemetry failed once" lines possible. `AtomicBoolean` + `compareAndSet` fixes it. Also `PacketEventsMetrics` already has the same pattern; fix both together.
- **AUDIT-14** `BreakContext.java:89` caps haste at 3 while `:116` caps at 4; Path B eff term `(eff+1)²` vs Path A `eff²+1` (the latter matches vanilla shape). With AUDIT-01's shared `BreakTimeModel`, both disappear.
- **AUDIT-15** No `canHarvest` modeling (30/100 divisor). Wrong-tool-on-stone hand-break currently yields `min = 5× hardness` (correct by accident of the 5× constant matching the 100-divisor case for correct-family tools!). After AUDIT-01 lands, model `canHarvest` properly (correct family ⇒ 1500/destroySpeed; else 5000/destroySpeed with destroySpeed=1).

### AUDIT-16 — MEDIUM: KillAura — attempt-rate + anchor alone reaches the VL bar

**Evidence**

- `KillAuraCheck.java:91` — every damaging attack re-evaluates: `distinct = tracker.recordTarget(ctx.getTargetUniqueId(), now, …)` (damage stream; `windowMillis` = `checks.kill-aura.window-millis`).
- `:93` — `boolean anchor = distinct >= minTargets` with `minTargets` default **3** (`config/VigilACConfig.java:121`, 1 s window by default).
- Constructor defaults (`KillAuraCheck.java:56-58`): `minAttemptRate = 8.0 attacks/s`, `metronomicStddevMax = 15.0 ms`, `metronomicMinSamples = 6`, `minSignals = 2`; VL requires **anchor AND ≥ 2 signals** (`:124`).
- Signal tally: anchor counts as signal 1 (`:96`); signal 2 = metronomic stddev < 15 ms over ≥ 6 intervals; signal 3 = PE attempt rate ≥ 8/s (misses included); signal 4 = swingless attacks ≥ 6 with attempts ≥ 2× swings.
- The weakest reachable VL state is **anchor + attempt-rate**: a PvP player alternating three or more targets inside a 1-second window at ≥ 8 attacks/s (a *miss-inclusive* rate, so whiffing raises it) produces VL with **zero** rhythm anomaly and **zero** packet-integrity anomaly. `KillAuraCheck`'s own FP notes (its javadoc: "the attempt-rate gate (8/s) is above normal farming cadence") justify 8/s as a *corroborator*, but as wired it is a *sufficient* co-signal.

**Consequence:** a legit 3-target PvP corridor / mob-tower turtle with PE installed and active attempts (including whiffs against walls) flags `kill-aura` deterministically — amount 1.0× `vl-per-trigger` per damaging hit, unbounded in the window. Alert-only today; still poisons the monitor and staff review budget. The same configuration — an actual configured kill-aura with human-like stddev — passes undetected, so the corroborator adds parity with no discrimination.

**Recommended fix (for Codex, in priority order):**

1. Raise the co-signal requirement so attempt-rate cannot be *sufficient*: VL requires `anchor && (metronomic || swingless)` — i.e. cadence regularity or packet-integrity evidence; attempt-rate stays a third signal contributing to amount scaling (`min(2, 1+0.5×(signals−2))`) only.
2. Or, minimally: rate-limit VL accrual per player (≤ 1 kill-aura VL event per 2 s) and/or require the anchor window to contain ≥ 1 miss **and** ≥ 6 swings — misses are what separates a real attacker from a damage-divider.
3. Document the limitation in CHECKS.md: kill-aura cannot flag configured human-mimicking auras (see §8.3) — say it plainly.

**False-positive analysis:** option 1 eliminates the deterministic 3-target high-cadence FP entirely. Option 2 only throttles it — not acceptable as the sole remediation.

### AUDIT-17 — INFO: `intervalCv()`'s "~0.2 humans" constant is unvalidated

**Evidence**

- `CombatTracker.java:73-75` — javadoc: "Humans typing at a steady cadence still land above ~0.2; sub-0.10 values over a full window indicate software timing." The value is not cited to any study; the only call site is a debug string (`AutoClickerCheck.java:125-126`) — it never gates a verdict; `intervalEntropyBits` is likewise debug-only.

**Action:** treat 0.2 as an unknown prior, not a threshold. Either measure the distribution on vanilla captures (manual protocol §10.3) or delete the number from the javadoc. No code changes beyond documentation; no VL impact today.

## 6. Thread-safety, lifecycle and resource audit

The telemetry path is the only part of VigilAC executing off the main thread. Every item below was checked against the source, not assumed.

| Item | Verdict | Evidence |
|---|---|---|
| `PlayerDataManager.get()` from netty thread does **not** create entries | OK | `data/PlayerDataManager.java:21-23` (plain `ConcurrentHashMap.get`); creation only via `load()` on the main thread |
| `PlayerMonitor.noteAttackAttempt(…)` from netty — monitor methods are `synchronized` | OK | `monitor/PlayerMonitor.java:84,163,…` all `public synchronized`; `statuses` HashMap only touched under those locks |
| `AttackAttemptLedger` synchronized; bounded 64; O(1) | OK | `data/tracking/AttackAttemptLedger.java:24-78` |
| `RotationTracker` synchronized; bounded 64; window 2 s | OK | `data/tracking/RotationTracker.java` — full file read |
| `DiggingTracker` single-slot state machine; tool/direction change ⇒ inconclusive | OK (logic); missing stale-dig timeout is harmless (O(1) memory, benign) | `data/tracking/DiggingTracker.java:71-138` |
| `FastBreakPenalty` decay + `initialized` sentinel fix | OK | `data/tracking/FastBreakPenalty.java:23,61-77`; `90312e9` introduced the `initialized` sentinel (`:62-65`); decay math `Math.pow(0.5, elapsed/halfLife)` (`:70`) correct; underflow clamped at <1.0 ms (`:71-73`) |
| `packetCount` (netty) / `packetsPerSecond` visibility | OK | `PlayerData.java:47-49` AtomicLong + volatile double; reads under `synchronized`/volatile |
| `ViolationPipeline` only records `vl > 0` — DIAG zero-VL entries never hit the VL store | OK | `check/ViolationPipeline.java:40-42` |
| `ViolationManager` calls listeners synchronously on the main thread — no off-main alert dispatch exists | OK (but note: any listener that does I/O blocks the tick — DatabaseManager is async by Phase-2 design; not re-verified here) | `check/ViolationManager.java` |
| Trackers die with `PlayerData` on quit | OK | `PlayerJoinQuitListener` unloads via `dataManager.unload` (not re-read; assumed per Phase 2–6 docs) |
| `warned` flags (telemetry + metrics) non-atomic | **Defect AUDIT-12** | see §5 |
| Unit-test concurrency coverage (trackers from multiple threads) | **Missing** — all tracker tests are single-threaded; no happens-before assertions, no race injection (safe but unproven) | `src/test/java/**/PacketTelemetryTrackersTest.java` |
| PacketEventsHook disable path unregisters both listeners | OK | `PacketEventsHook.java` (read during Phase-6 docs pass); `unregister` swallows already-unloaded packetevents |
| `RotationTracker.window()` allocates an ArrayList copy per call — called per combat check per attack | Bounded (≤64); acceptable | `RotationTracker.java:50-53` |
| `CombatTracker.recordTarget` allocates a HashSet per attack | Bounded by `TARGET_WINDOW` eviction; acceptable | `data/tracking/CombatTracker.java:178-195` |
| FastBreak penalty `dampen()` halves bucket post-flag but never updates `lastUpdateAt` — correct (decay anchored to last add) | OK | `FastBreakPenalty.java:44-47` |
| `PlayerMonitor` age/count scans are O(256) ring scans per call; called on main thread only | OK | `EvidenceStore` bounded 256 |
| netty exception safety: telemetry catches `Throwable` and log-suppresses after first | OK | `PacketEventsTelemetry.java:127-133` |
| Shared `CorrelationEngine correlation` instance inside `NoFallCheck` — stateless | OK | `monitor/CorrelationEngine.java` — full file read; reads only monitor's synchronized APIs |
| Dig tracker idle for long sessions — single `ActiveDig` replaced, never leaks | OK | `DiggingTracker.start` replaces non-null active dig |

**Concurrency verdict:** no data race found that fabricates or corrupts violation evidence. The one residual is the atomicity of the warn flag.

## 7. Machine-learning research (honest scope)

### 7.1 What ML could add here — and what it cannot

VigilAC's Phase 6 engine is a **hard-constraint** detector: signatures with explicit vanilla bounds. ML adds value only for the *residual* class: players whose movement/aim never violates a hard bound yet is statistically unlike vanilla (adaptive, configured cheats, subtle speed/aimbots) — i.e. exactly the class VigilAC is worst at today, and exactly the class for which **no trustworthy labels exist**. Any claim that "ML solves this now" would be fabrication.

### 7.2 Recommended hybrid architecture (when data exists)

**Per-window anomaly scoring feeding the alert queue — never a standalone verdict.**

- **Window:** sliding 10 s / 200 ticks per player, step 2 s, features computed from existing trackers (see 7.3). Windows are session-grouped (all windows of one session share a fold in any split).
- **Model 1 (ship first):** Isolation Forest (`sklearn`, `n_estimators=200`, `max_samples=256`, `contamination` calibrated to the legit baseline quantile ~3–5%). **Unsupervised** — trainable on vanilla-only captures. Output: scalar `anomaly ∈ [0,1]` rendered as evidence in `/vigilac monitor` and the alert draft; **zero** automated consequences.
- **Model 2 (later, only with ≥ ~5k labeled sessions/class):** Gradient-boosted trees (LightGBM/XGBoost) or a Random Forest (rf: fewer knobs; gbdt: better calibration; either beats neural here at small N) for supervised binary "cheat vs legit" on the same features; probability-calibrated (Platt or isotonic) and **abstain-below-τ** (τ tuned on calibration set; below τ → INCONCLUSIVE, mirroring the corr tri-state).
- **Model 3 (never proposed as primary):** neural sequence models/RNNs/transformers on raw packet streams. Needs orders-of-magnitude more labeled data, GPU inference story, and is adversarially brittle. Explicit non-goal.
- **Correlation, not replacement:** ML score is *one more signal* into the same tri-state discipline (`corr=CONFIRMED` requires 2+ independent signals; ML counts as one). ML < threshold + heuristic suspect ⇒ SUSPECTED, never auto-action.

### 7.3 Features (all already computable from current telemetry)

| Group | Features |
|---|---|
| Movement | speed EMA/cap ratio (p50/p95/p99), sustained-excess seconds, low-gradient-sink ratio, airborne net|dy|, upward ticks/airborne tick, hover duration, sprint cadence, web/slow-block occupancy, friction-cap margin, ping, TPS |
| Combat | damage CPS (damage stream), attack CPS (PE attempts), interval mean/stddev/CV/entropy(25 ms buckets), distinct targets/1 s, yaw/pitch snap max-per-window p95, pre-attack angle-to-closest-box p50/p95, swing:attack ratio, attack cooldown compliance |
| Breaking | dig deficit ms/s (Path A), inconclusive-dig ratio, interval CV, instant-block rate |
| Environment | inVehicle, gliding, riptiding, inLiquid, frostWalker, potion levels, TPS, ping, dimension/time-of-day (environmental priors) |

~35–40 features, all bounded O(1) per window. Feature store = shadow-mode logging (privacy review required; pseudonymized UUIDs; ≤ 2 weeks retention; GDPR/VN-PDPL 2023 awareness: this is behavioral telemetry — legal basis must be assessed before production).

### 7.4 Training data & labeling strategy

1. **T1 — confirmed legit:** (a) CI live-bot sessions (ground truth: scripted vanilla); (b) manual-protocol vanilla captures (§ manual protocol in DETECTION_VALIDATION.md); (c) optionally volunteer staff/β players with consent. Weight 1.0.
2. **T2 — confirmed cheat, config-cataloged:** Meteor Client modules (KillAura standard/smooth rotations, Flight, Speed strafe, TriggerBot) at *documented settings*, plus FCL/PC legit baseline separate from FCL/cheat. Each sample carries module+settings+client+launcher tags — never train a single "cheat" class that conflates modules. Weight 1.0.
3. **T3 — ambiguous/disputed:** excluded from training; reserved for evaluation only.
4. **Forbidden:** training on VigilAC's own VL/alerts as labels (circular reinforcement), or on other plugins' bans (label noise unverifiable).

### 7.5 Validation metrics & thresholds

- **Primary:** precision at fixed reviewer budget (e.g. ≤ 10 alerts/100 players/day), recall@that-budget, AUC-PR (not ROC-AUC — class imbalance ≥ 1:100), Brier score (calibration), **FP-hours per player-day** (the unit ops actually feels), per-cohort breakdowns (FCL vs PC, ping bands, weapon classes incl. spears).
- **Protocol:** session-level time-based split (train weeks 1–3, validate week 4, shadow week 5+); never random windows from continuous sessions into different folds (temporal leakage inflates AUC by a lot).
- **Promotion criteria to production (shadow → enforce):** ≥ 2 consecutive weeks shadow-mode with **precision ≥ 0.9** at the reviewer budget, **zero** auto-actions enabled, analyst agreement ≥ 80% on sampled flags, and zero unresolved Sev-1 false-positive reports. Even then: enforce = purge-recommend, never ban without staff confirmation.

### 7.6 Deployment constraints (Java/Paper)

- **ONNX Runtime Java** (`com.microsoft.onnxruntime:onnxruntime`, MIT) — chosen for cross-version native packaging, model introspection, and easy CI. Load once at plugin enable in a singleton `OrtEnvironment`/`OrtSession`; **inference never on the main thread** — dispatch to a bounded executor (or at window close on the maintenance task, < 1 ms budget for IForest-200 @ 40 features; measure, don't assume).
- Model artifact lives in the plugin jar or `dataFolder/models/*.onnx`, versioned (`isolation-forest-v1.onnx`), SHA-256 verified at load; **missing/corrupt model ⇒ ML stage disables itself cleanly** (log once, monitor shows `ml=disabled`), never blocks startup, never crashes the tick.
- No GPU on Paper hosts; no native dependency beyond onnxruntime's prebuilt libs (Linux x86_64 + aarch64 — confirm aarch64 available for ARM Paper hosts); total jar budget ≤ ~15 MB.
- Feature drift guard: weekly job (CI or cron) compares feature quantiles vs training baseline; >2σ shift on any P95 ⇒ alert, don't auto-retrain. Adversarial mimicry defense: keep IForest opaque to operators, rotate feature order in model preprocessing, never publish exact caps (security through obscurity is weak but raises adaptation cost).

### 7.7 Recommendation

**Do not ship ML in any enforcement path today.** Ship only: (1) shadow-mode feature logging behind `ml.shadow: false` default; (2) the Isolation Forest as soon as ~1–2 weeks of shadow data + manual-protocol captures exist; (3) supervised GBM/RF only after labeled volume justifies it. The binding constraint is labels and protocol-honesty, not modeling.

## 8. Minecraft 26.2 / Android / Meteor — client-environment audit

### 8.1 Fold Craft Launcher (FCL) — what it is and is not

FCL is an **Android Java-Edition launcher** (x86/ARM64 runtimes, VirGL graphics, virtual mouse, optional shaders) — the player inside it runs the *real vanilla/Fabric/Forge client* speaking the *real Java wire protocol*. FCL cannot be fingerprinted server-side, and **no finding in this audit may be read as "FCL = cheat"**. Specific pressures FCL users bring to every check:

| Pressure | Consequence for VigilAC | Action |
|---|---|---|
| Virtual mouse / touch micro-aim | angular deltas quantize; smooth human tracking is choppier than PC; AimAssist's human-flick model is PC-biased | Re-tune `snap-angular-delta` per cohort only with capture data; never auto-flag "choppy aim" |
| Multi-finger tap combat | 15–25 CPS legit outliers (§AUDIT-06) | AUDIT-06's preset; document `touch-friendly` in server docs |
| 100–400 ms RTT with spikes | backlog bursts; EMA/dt-floor handling (speed) mostly absorbs; **reach is uncompensated (AUDIT-08)** | AUDIT-08 fix makes mobile PvP viable; until then, reach FP-rate on FCL ≈ unacceptable for PvP servers |
| Variable frame rates (15–60 FPS) | rotation packets track **tick** rate, not FPS (verified: client sends position/rotation at most once per client tick, 20/s) ⇒ rotation stream unaffected | No change needed; keep note for future per-frame experiments |
| Thermal throttling → 15 FPS sustained | client tick still 20/s on Java; TPS floor 10 exemption in checks unchanged | No change |
| Legitimate high-CPS legitimate behavior | same as AUDIT-06 | same |
| Meteor on FCL | full Meteor feature set on Android (FCL runs Fabric); rotation->attack same-tick pattern from Grim issue #2892 still applies | See 8.3 |

**Protocol versioning:** 26.2/26.3 are year-versioned drops after 1.21.11 (Mounts of Mayhem, Dec 2025). VigilAC's compat matrix treats them as separate cells — correct. Runtime packetevents behavior on these cells is **unverified** everywhere (CI has no PE). Spear semantics and `attack_range` apply (§AUDIT-02). Dig action ordinals are pinned by packetevents 2.14.0 (compiled only). Enchantment keys (`FROST_WALKER`, `AQUA_AFFINITY`, `EFFICIENCY`) and potion keys (`SLOW_FALLING`, `LEVITATION`, `HASTE`, `MINING_FATIGUE`, `CONDUIT_POWER`, `SPEED`) compile against 26.2–R0.1-SNAPSHOT — present, but the runtime probe belongs to the manual protocol.

### 8.2 Player mix expectations (set the alert budget accordingly)

Assume a FCL-heavy server skews flag sources: auto-clicker rate hits (AUDIT-06), reach (AUDIT-08), AimAssist choppy aim at streak 4 (AUDIT-03 post-fix residual), Step on parkour-with-slime servers (AUDIT-05), ridge riptide (AUDIT-04). **Mode must stay alert-only; staff review every alert; no auto-kick for any of these until the fixes land and are re-measured.**

### 8.3 Meteor Client compatibility matrix (what Phase 6 can and cannot see)

| Meteor feature (default-ish config) | Phase 6 outcome | Notes |
|---|---|---|
| KillAura (smart delay 10–11, rotation same-tick) | usually **caught**: swing>45° or 6°-lock snap pattern pre-attack on the telemetry path (if PE present) or fallback path shows per-attack delta>45°; cadence stddev>15 randomization defeats metronomic; anchor+corroboration needs a second signal | Catch rate limited by PE-presence; **survival configuration** needs moderate effort to configure to configure around; bypassable by unchained smooth rotations + randomized delay |
| KillAura (smooth rotations, delay 15+, silent) | **undetectable** by current signals (by design of human-mimicking configs; not a defect — no detector has this) | Documented evasion |
| Speed (strafe, 2.5–3.5 b/s horizontal) | not flagged — inside vanilla caps by construction; speed strafe is undetectable by cap methods (needs prediction; Grim-only) | documented bypass |
| Flight | **caught** (hover/rising/low-gradient sink) if survival creative mismatch; Meteor Flight default "Vanilla" mode is flagable by fly check; "Packet"/"Grim" modes evade | detection strong here |
| NoFall | **mostly undetectable** without collision verification — MCP NoFall spoofs onGround claim; our probe catches only landing-tick lies; water-entry resets accumulator (correct vanilla semantics) | residual: weak; document |
| Reach (range 3.0) | 0 VL — at vanilla cap | bypass by design |
| Reach (range 3.1–4.5, sword) | flags once 4 consecutive + EMA over 3.03 (AUDIT-08 latency asymmetry applies to PvP only) | by tuning range to 3.0 exactly, undetectable — bypass by design |
| Reach (spear, any distance ≤ 4.5) | **false-flags every attack > 3.03 until AUDIT-02 fix** | version-compat defect, AUDIT-02 |
| TriggerBot | undetectable by current signals (no signal models trigger conditions) | not in scope |
| AutoClicker (AC = 13–17 randomized) | undetectable by stddev/entropy gates; rate gate passes (below cap) | bypass by design |
| FastBreak (Veinminer-like) | undetectable — needs per-dig lifecycle; InstantBreak + pegged-to-vanilla timing evades penalty bucket (below grace) | inherent |

**Honest summary:** Phase 6 catches *crude* clients and *unconfigured* Meteor; configured Meteor configured to look human is out of scope for any current-technology detector, VigilAC included. The reportable statement for operators: "Alerts indicate *something anomalous*; silence does not indicate innocence."

## 9. Unverified assumptions & blockers (what this audit could NOT establish)

1. **No real cheat client was executed.** Every bypass/catch claim above derives from code reading, vanilla physics, and public sources. Frequencies are marked UNVERIFIED where they require in-game observation (AUDIT-03…AUDIT-06, §8).
2. **No packetevents runtime.** CI never installs the PE plugin; telemetry code paths were audited statically and unit-tested only. Runtime behavior on 1.21.8/26.2 (wrapper allocation rates, dig lifecycle ordering vs Bukkit's `BlockBreakEvent` ordering on the same tick, multi-connection packet-loop assumptions, `PUNCH`/`ATTACK` packet presence per protocol cell) is **unverified**.
3. **Riptide flag semantics.** Bukkit javadoc defines `isGliding()` ("such as using an Elytra") and exposes `isRiptiding()` separately since 1.13; whether the Paper 1.21.8 build sets the gliding data-flag for tridents as well is **not verified** in this audit. AUDIT-04's fix (explicit `isRiptiding()` wire-up) is safe under either semantics; the claim "vanilla-legal riptide arcs can reach the jesus streak" stands regardless, because velocity grace is the only thing covering riptide today.
4. **Slime-bounce vertical model** is Newton-math on vanilla constants (g = 0.08 b/tick², bounce returns impact velocity); observed per-tick velocities from a live server were not captured. The 3-ticks-above-0.95 conclusion is robust to ±0.1 b/tick error.
5. **`attack_range` component shape on Paper 26.2–R0.1-SNAPSHOT API** — the accessor for the component (Paper `DataComponentTypes` vs raw `getComponents().get(...)`) must be verified against the pinned API at implementation time; Grim reads it via packetevents' ItemAttackRange for the client view, which is not the same surface as the server-side Bukkit view. Flagging this so Codex does not guess the accessor wrong.
6. **Android/touch CPS ceilings** are from public community literature, not measurement; flagged for capture in the manual protocol (§10).
7. **Meteor behavior table (§8.3)** reflects public module designs and a public Grim issue ([GrimAnticheat/Grim#2892](https://github.com/GrimAnticheat/Grim/issues/2892)) and community discussion; individual module configs drift per release. Treat as orientation, not gospel; re-verify per Meteor version when collecting T2 data.
8. **The NoFall/Resistance edge (AUDIT-10)** collapsed under analysis (no stable vanilla Resistance IV+ source); retained in the report only to document the reasoning, not as an actionable bug.
9. **Entity-stack/fall-on-mob landings** (fall damage does fire ⇒ exculpates before probe) — no defect found; documented because it *looked* like one in first-pass reading.
10. **Themises nonexistent** — independent methodology could not be studied (upstream 404); Negativity v2 and Updated-NoCheatPlus were the available OSS references. Both, like Grim, validate the *multi-signal-plus-window* direction VigilAC chose.
11. **Load numbers and CI history** were taken as-reported by the repository (self-consistent, and corroborated where I re-derived them this session — e.g. the 50-bot CI-runner TPS ceiling matches Phase 5). Not independently re-measured here.
12. **Corpus-level FP rate of the merged engine on a live FCL-heavy server** — unknowable without the manual protocol; BLOCKED remains the honest status.

## 10. Testing requirements (for Codex)

### 10.1 Unit tests (new, per defect — none of these may be skipped)

| Finding | Test |
|---|---|
| AUDIT-01 | `breakTimeModelDiamondObsidianEff0()` — expected ≈ 9.4 s, no bucket credit for a 9.4 s diamond dig; `breakTimeModelWoodenStoneLegal()` (regression); `breakTimeModelNetheriteStoneEff5()`; `unmappedToolSuppressesVerdict()`; `canHarvestWrongToolHandBreakStone()` ≈ 7.5 s; `hasteAndFatigueComposite()`; `offGroundAndWaterDoublePenalty()`; `efficiencyBonusOnlyWithCorrectTool()` |
| AUDIT-02 | `reachUsesWeaponAttackRangeWhenPresent()` (component 4.5 ⇒ no flag at 3.8), `reachVanillaWeaponStays3()`, `reachSword3p1FlagsAfterStreak()` |
| AUDIT-03 | `aimAssistSmoothOrbiterNeverFlags()` (25°/step, 50° accumulated, max-per-sample 25°), `aimAssistTrueSnapFlagsAfterStreak()`, `aimAssistMaxDeltaIsAtomic()` |
| AUDIT-04 | `jesusRiptidingExempt()`, `jesusWindBurstDominatedImpulseGrace()`, `jesusStreakResetOnRiptide()` |
| AUDIT-05 | `stepSneakChargedSlimeBounceThreeTicksNoFlag()` (dy 1.13/1.05/0.97 with slippery feet), honey equivalent, `stepNonSlipperyFullBlockStillFlags()` |
| AUDIT-06 | `ratePathRequiresRhythmOrMargin()` (rate 24 + stddev 30 ⇒ no flag; rate 24 + stddev 5 ⇒ flag), `touchFriendlyPreset()` |
| AUDIT-07 | `intervalCapturedBeforeRecord()` — two attacks 800 ms apart ⇒ `ctx.previousAttackAtMillis()` delta == 800 |
| AUDIT-08 | `reachTargetVelocityProjectionReducesDistance()` (target +x away ⇒ flagged case clears at the same swing) and calibration of `lag-compensation-millis` against ping buckets |
| AUDIT-09 | `noFallCorrelationWindowPerFactor()` |
| AUDIT-12 | `telemetryWarnedIsAtomic()` |
| AUDIT-16 | `killAuraAttemptRateAloneDoesNotFlag()` (anchor + 9/s attempts + high variance ⇒ no VL), `killAuraAnchorPlusMetronomicFlags()` |

### 10.2 Property / invariant tests

- Trackers: concurrent `noteAttempt`/`attemptsSince`/`evict` from 4 threads × 100k iterations ⇒ no exception, `size() ≤ CAPACITY`, monotone `lastAttemptAt`.
- `FastBreakPenalty`: `score()` monotone non-increasing between adds; `addMissing(0)`/NaN never increases bucket; bucket ≤ sum(all missing ever added).
- `RotationTracker`: `angularDistanceCovered` ≥ `lastDelta` always; `shortestAngleDegrees` ∈ [0,180].
- Break-time model: for all eff 0–5 × haste 0–3 × fatigue 0–3 × {water, ground} × {correct, wrong}, `minimumBreakMillis ≤ expectedPlayerTimeWithPessimalCorrectPlay` (optimality invariant — the floor must remain a true floor).

### 10.3 Harness / integration

- Add a **packetevents-present leg** for the load job (docker or CI with PE plugin installed) — currently impossible to characterize the production path; at minimum a 1-bot boot smoke asserting `[VigilAC] packetevents hook loaded` + START/FINISH dig events observed with PE.
- Compat legs stay green for 1.21.8 → 26.x (constructors unchanged — AUDIT-02 adds a field + null-check only).
- Live protocol-bot full run (`integration/live.sh`) before merge; `legit-mine-stone` extended with an **eff-0 iron-pick** scenario that must remain VL-free after AUDIT-01's fix (this scenario does not exist today).
- **Manual protocol (human, BLOCKED until staff available):** rows already specified in `docs/DETECTION_VALIDATION.md` — must now also include: spear jab/charge at 3.6/4.2 blocks (AUDIT-02), iron-pick stone mining 60 s (AUDIT-01), slime parkour course with sneak-charged bounces (AUDIT-05), lake riptide crossing (AUDIT-04), butterfly-click 24 CPS 30 s (AUDIT-06). Record client, MC, launcher (FCL vs PC), ping, VL trace.

### 10.4 Regression gates for Codex

- `./gradlew test --no-daemon`: 178 + new (≈ 200) green on **all** 15 compat legs.
- Load legs ≥ prior result (10/25/50 bots, `failed_bots == 0`, TPS ceiling unchanged).
- Review the exact `git diff` — this audit touched no source; Codex must keep that boundary clean (the audit is the brief; the fixes are separate commits).

## 11. Prioritized implementation plan for Codex

**P0 — correctness fires (≤ 3 days of focused work; unblocks every FP conversation)**

1. **AUDIT-01** — shared `BreakTimeModel` (tool speed map, canHarvest divisor, haste/fatigue/water/ground multipliers, vanilla formulas) replacing both formulas in `BreakContext`; wire `toolSpeed` through `BlockBreakListener` (material map wood2/stone4/iron6/diamond8/netherite9/gold12; modded unknown ⇒ suppress timing verdict); delete `(eff+1)²` and the cap-3/4 mismatch; full §10.1 suite. **Gate:** new stone/obsidian legal-rate tests green; existing 178 still green.
2. **AUDIT-02** — weapon `attack_range` component in `AttackListener` → `AttackContext.attackRangeMax` → `ReachCheck` cap (`max(maxReach, component)`); docs: COMPATIBILITY_MATRIX row, CHECKS.md, FP_ANALYSIS; interim config toggle documented for pre-fix 1.21.11+ servers. **Gate:** reach tests §10.1 green; no change on ≤1.21.10 behavior.
3. **AUDIT-05** — Step: slippery-block exemption + sneak-charge bounce unit test. **Gate:** §10.1 green; Fly/NoFall slime tests still green.

**P1 — detection-quality corrections**

4. **AUDIT-03** — `RotationTracker#maxDeltaInWindow`, re-point AimAssist telemetry swing; orbiter unit test; keep `angularDistanceCovered` for diagnostics only.
5. **AUDIT-04** — `riptiding` in `DetectionContext` + listener wire; Jesus/Step hard exemption; monitor `/vigilac monitor` shows nothing new (exemption note in debug); docs: DETECTION_VALIDATION riptide row.
6. **AUDIT-06** — rate-path rhythm co-gate + default cap 25 + `touch-friendly` preset; docs: CHECKS.md auto-clicker, FP_ANALYSIS Android row.
7. **AUDIT-07** — `previousAttackAtMillis` on `AttackContext`; AimAssist reads it (also fixes the vacuous interval condition and the debug line).
8. **AUDIT-08** — javadoc corrected; ping-slack `checks.reach.lag-compensation-millis` (default 0, opt-in) **or** target-velocity projection (preferred; note both, implement projection, fall back to slack if projection proves noisy in protocol).

**P2 — hygiene & honesty wiring**

9. **AUDIT-09** — per-factor correlation windows (teleport 2 s, velocity 1.5 s, world-change 2 s); keep VL path untouched.
10. **AUDIT-11** — operator doc: multi-break plugins need `ac.vigil.bypass.break`; optional permission check in pipeline.
11. **AUDIT-12** — AtomicBoolean warn flags (telemetry + metrics).
12. **AUDIT-14/AUDIT-15** — closed by AUDIT-01 (shared model); verification checklist after refactor (no separate patches). AUDIT-10 was closed in analysis (§5, AUDIT-10), not by this work.
13. **AUDIT-16** — kill-aura: make attempt-rate insufficient on its own (`anchor && (metronomic || swingless)`, or a 2 s VL cooldown floor); document that configured human-mimicking auras are undetectable (§8.3).
14. Docs resync: this audit's §5 into `CHECKS.md` FP sections; `DETECTION_VALIDATION.md` new test rows; `FALSE_POSITIVE_ANALYSIS.md` adds slime-bounce/spear/riptide rows; `PHASE_6_IMPLEMENTATION.md` errata referencing this audit file.

**P3 — structural (post-merge follow-ups, not this PR)**

15. PE-present CI smoke (1 bot) — unblocks runtime telemetry verification.
16. Ping-compensated reach via velocity projection validated on real lag traces (manual protocol first).
17. ML shadow mode (§7) — feature logging only; IForest next sprint after data exists; supervised GBM/RF thereafter.
18. Compensated-world movement engine feasibility study (Grim-parity) — the largest single gap vs Grim; explicitly a re-architecture, scope separately.
19. Re-baseline after P0–P2: re-run load legs ≥ prior result; refresh PERFORMANCE_BENCHMARK; refresh COMPATIBILITY_MATRIX.

**Definition of done for the whole remediation:** P0–P2 merged with green CI on all legs, both live harness legs, load legs at prior numbers, manual-protocol rows added and scheduled with owners, and this audit's §5 findings all either closed or explicitly deferred with reasons appended below the findings table.

## 12. License & attribution compliance

- GrimAC (GPL-3.0), Updated-NoCheatPlus (GPL-3.0), Negativity v2 (GPL-3.0) were studied for *architecture and algorithm shape only*. No code, identifiers, constants-as-magic-values, or file layouts were copied into VigilAC or into this report. VigilAC remains MIT.
- This report paraphrases publicly documented behavior (minecraft.wiki, Grim's public source for cross-checking reach semantics) for interoperability and review purposes.
- Fold Craft Launcher is not affiliated with Mojang or Microsoft; running Java Edition on FCL does not alter protocol semantics; nothing in this audit constitutes reverse engineering of proprietary software beyond evaluating publicly observable server-side protocol behavior.

## 13. Confidence & maintenance statement

Findings AUDIT-01/02/03/05/07 are **verified to code-line precision** and include arithmetic or a failing-test reproduction. AUDIT-16's mechanics are code-verified (its field frequency is not). Findings AUDIT-04/06/08/09 are verified as code paths + physics/literature; **frequency in the field is UNVERIFIED** pending the manual protocol. AUDIT-10 is documented and deliberately closed, not deferred. Nothing in this report asserts real-client behavior as tested fact. Re-audit is recommended after P0 lands, and mandatorily after the manual protocol executes, because several conclusions (especially §8.3) will need evidence from live runs to graduate from "analysis-verified" to "operationally verified".

---

## Appendix A — vanilla reference values used

- Block break: `ticks = ceil(hardness × (canHarvest ? 30 : 100) × 20 / (destroySpeed × 20))` ⇒ `seconds = hardness × (canHarvest ? 1.5 : 5) / destroySpeed`; `destroySpeed = toolSpeed + (eff > 0 ? eff² + 1 : 0)`; × Haste `(1 + 0.2 × level)`; × Mining Fatigue `0.3^level` (level ≥ 1); × 0.2 underwater w/o Aqua Affinity; × 0.2 off-ground. Tool speeds: wood 2, stone 4, iron 6, diamond 8, netherite 9, gold 12. Sources: [minecraft.wiki *Breaking*](https://minecraft.wiki/w/Breaking); cross-check calculators read 2026-11-07: [minecraftmaps.com mining-speed calculator](https://www.minecraftmaps.com/tools/mining-speed-calculator), [torchandanvil.com break-time tool](https://torchandanvil.com/tools/block-breaking-calculator.html).
- Gravity: `0.08 blocks/tick²` (Java), terminal velocity `3.92 blocks/tick` (= 78.4 m/s). Slime bounce returns fall-impact velocity upward; sneak-charge stores up to 8 blocks of fall height. (minecraft.wiki [Java Edition](https://minecraft.wiki/w/Java_Edition) physics constants.)
- Reach: default `entity_interaction_range` = 3.0 (Java); 1.21.11 `attack_range` component overrides it; spear `max_reach` = 4.5 (creative 6.5). Sources: [minecraft.wiki *Melee attack*](https://minecraft.wiki/w/Melee_attack); [Java Edition 1.21.11](https://minecraft.wiki/w/Java_Edition_1.21.11); Grim source `ComponentTypes.ATTACK_RANGE` handling ([Grim#2892 context](https://github.com/GrimAnticheat/Grim/issues/2892)).
- Attack cooldown (1.21.2+): server-authoritative; limits *damage* cadence, not packet cadence — hence the damage-stream CPS is not a reliable auto-clicker proxy; the PE attempt stream is the honest signal (already wired correctly in Phase 6; gate placement is what AUDIT-06 fixes).
- FML swing vs damage: ANIMATION (swing) is client-initiated and independent of the damage cooldown; protocol truth verified against COMPATIBILITY_MATRIX (INTERACT_ENTITY/ATTACK split at 1.21.2).
- Packet cadence: vanilla = 20 position/rotation packets per second, at most one per tick; FPS does not increase packet count (Java client sends on tick boundaries).

## Appendix B — reproduction quick-reference

- AUDIT-01: read `BreakContext.java:80-92` & `:110-133`; compute obsidian iron-pick table in (see AUDIT-01); `./gradlew test --tests FastBreakCheckTest` (passes today — that's the problem).
- AUDIT-02: read `ReachCheck.java:73-101`; no item path exists; see 1.21.11 component notes above.
- AUDIT-03: read `AimAssistCheck.java:89-94` + `RotationTracker.java:60-77`; construct the 25°×2-orbit test.
- AUDIT-05: `StepCheck.java:87-94` (no slippery term); physics table in AUDIT-05.
- AUDIT-07: `AttackListener.java:94-96` (order) + `AimAssistCheck.java:88` (zero).
- AUDIT-12: `PacketEventsTelemetry.java:78,127-133`.

---

*End of audit. This document is the deliverable; no source code was modified by the auditor. Any implementation work belongs to the implementation owner under the plan in §11.*
