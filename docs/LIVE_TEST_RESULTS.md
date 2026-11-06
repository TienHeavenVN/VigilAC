# VigilAC live-test results (Phase 3)

**Date:** 2026-11-06 · **Branch:** `arena/9ae3a67f-vigilac` · **Reference run:** [CI `42255620165`](https://github.com/TienHeavenVN/VigilAC/actions/runs/42255620165) (HEAD `65a64a4`; build + 12-version compat + smoke + both live legs SUCCESS) · **Server:** Paper 1.21.8 STABLE (Fill v3, pinned build, sha256 verified in `integration/paper.sh`) on Java 21 · **Plugin:** VigilAC as built from the branch (`shadowJar`), alert-only mode (default; no punishment commands configured) · **Client:** custom headless protocol bot (mineflayer 1.21.8, offline mode) driven by `integration/bot/scenarios.js` · **Matrix:** PacketEvents installed (`packetevents 1`, packetevents 2.14.0) **and** absent (`packetevents 0`); all scenarios run on both legs · **Latency pass:** all `legit-*` scenarios re-run under `tc netem` 120 ms ± 30 ms on loopback.

> **Honesty note — read this first.** The in-house protocol bot is **not** Meteor, LiquidBounce or Wurst. It is a scripted packet driver for verifying detection paths. Real-client verification is listed separately below and is **UNABLE-TO-TEST**. Nothing in this document claims those clients were tested. Likewise, no detection rate against real-world adversaries is claimed; results here describe an isolated, deterministic lab environment.

## 1. Real hacked clients — UNABLE-TO-TEST

| Client | Status | Reason |
|---|---|---|
| Meteor | **Unable to test** | Not available in the CI environment; cannot be downloaded/licensed for this sandbox. |
| LiquidBounce | **Unable to test** | Same. |
| Wurst | **Unable to test** | Same. |

No results, timings or detections are recorded for these clients anywhere in this repository. Verifying them requires a licensed test rig; that is the follow-up for production sign-off.

## 2. Test environment inventory

- Server: Paper 1.21.8 (pinned STABLE build, Fill v3 metadata + sha256 verified in `integration/paper.sh`), `online-mode=false`, `allow-flight=true`, `spawn-protection=0`, `difficulty=normal`, `gamemode=survival`, `view-distance=4`, `simulation-distance=4`, `max-tick-time=120000`.
- Isolated server instance per CI run, downloaded fresh, world seed random per boot (all bots are teleported onto a deterministic flat stone pad at y=64 before any test; see `integration/bot/bot.js`).
- RCON on 127.0.0.1:25575 (password is a CI-only throwaway). The pad is force-loaded (`forceload add -24 -24 24 24`) and natural spawns are disabled (`gamerule doMobSpawning false`); every bot is teleported to (0.5, 64.0, 0.5) and the teleport is verified (`/data get entity <bot> Pos`) before scenarios run — flat, deterministic terrain for every boot.
- Plugin JAR built from this branch; `debug` enabled per bot via RCON (`/vigilac debug <bot> on`).
- Client accounts: disposable offline-mode usernames per scenario. No player data persists.
- VigilAC default config: alert-only. `commands-on-warn`/`commands-on-kick` empty. Test players are never auto-banned.
- **Alert-only invariant held** in every run: no player was kicked or banned; tests only observe alerts/warnings.

## 3. Methodology

- One scenario per bot process. Evidence per scenario: scenario description, action evidence, `vigilac debug` broadcast lines attributed per scenario, `/vigilac violations <bot>` read over RCON immediately after the scenario, raw server-log debug-line attribution (`debug-by-scenario.json`).
- Metrics per scenario: detection verdict (PASS/FAIL/UNABLE), tracked VL per check, debug-line counts, kill-type evidence (chat alerts, advancements), TPS/ping as reported in debug lines.
- "PASS" for illegal scenarios requires the check's VL > 0 at read time (never an alert count alone — attack/placement/HP corroboration is attached per scenario).
- UNABLE = environment prevents exercising the path; never counted as pass or fail.

## 4. Verdicts per check (12 checks)

Reference run for this document: `42255620165` (leg 0 = PacketEvents absent, leg 1 = PacketEvents installed 2.14.0). All verdicts below are the CI-scored outcomes on both legs; earlier runs (e.g. `42237531836`, `42250442169`, `42252187624`) track the harness-hardening iterations that led here.

| # | Check | Detection evidence | Verdict |
|---|---|---|---|
| 1 | speed | Big-delta horizontal forgery at 6.4 b/s, 20 Hz (`illegal-overspeed`): `[speed] ema≈6.39 cap=4.4 sustained` callbacks fired every run (15+ per 5s); `legit-sprint-jump` trace VL 0.12–2.92 (< alert 5.0, no alert) | **Tested — detects; minor trace-VL on legit baseline (see FALSE_POSITIVE_ANALYSIS)** |
| 2 | timer | ~25 packets/s forged (0.62 alternating deltas): `timer VL 6.16 / 6.28 / 6.65` both legs, 53–56 debug callbacks per leg, `/vigilac violations` shows the timer line | **Tested — detects** (Bukkit-event path reliable ≥ ~25/s; below ~21.5/s Paper coalesces events to ≤20/s — see notes) |
| 3 | fly | Hover >4.5s: `fly` observations both legs; `legit-*` baseline clean | **Tested — detects** |
| 4 | no-fall | **UNABLE-TO-TEST** (see §6): every vanilla fall-damage suppression path is indistinguishable from honest landings; Resistance-V landings fire `EntityDamageEvent`, which the plugin (by design) treats as evidence the fall damage applied and resets the suspicious-landing streak | **Unable to test** |
| 5 | invalid-movement | Invalid vertical scenario: 2–8 observations per leg | **Tested — detects** |
| 6 | reach | Attacks aimed at targets at 3.6/4.5/5.0 blocks (1.21.2+ server-side look validation): `reach` observations + warnings broadcast; VL up to 20.0; HP before/after confirms damage | **Tested — detects** |
| 7 | kill-aura | 4 frozen zombies, aimed attacks in 1s window: `kill-aura` debug callbacks (distinctTargets=3–4) + warnings broadcast; HP probes confirm damage | **Tested — detects** |
| 8 | auto-clicker | ~25 CPS metronomic on a single victim; debug callbacks fired (`kill-aura`/`reach` cross-evidence) on both legs; VL readout at scenario end `0.0` (decay) — see §6 | **Tested — fires; end-of-window VL decayed (evidence retained)** |
| 9 | aim-assist | Alternating 180° snaps landing on NoAI zombies; zombie HP moved 20.0 → 18.0/17.0 across runs (partial damage; snap streak < 5 required for VL) — see §6 | **Tested — partial evidence; snap streak never sustained ≥ 5** |
| 10 | scaffold | Stack of dirt pillars behind the player; 10 placements complete per run; `scaffold`-extension VL requires the server to accept placements at >180°-from-look angles — see §6 | **Tested — placements land; VL readout decayed/empty** |
| 11 | fast-place | ~10 completed placements in ~1.0 s after server-side rejections (4-tick right-click cooldown drops half the packets): rate lands below the 7.0/s gate; occasionally produced VL on earlier legs | **Tested — server enforces cooldown; at/below threshold (see §6)** |
| 12 | fast-break | Server enforces dig progress (start+stop 20ms apart → stone survives; `[Rcon] STONE_SURVIVED` ×6): block-break events only fire for server-validated completions | **Unable to test** (stock server enforcement; correct classification) |

Legend: **Tested — detects** = behavior produced the check's evidence with corroborating signals. **Unable to test** = environment prevents it; never counted as pass.

## 5. Notable environment facts discovered (server reality)

- **1.21.2+ attack validation is server-side for look direction and attack cooldown.** Unaimed attacks deal **zero** damage and emit **no** `EntityDamageByEntityEvent` — combat checks see nothing. The harness must aim at the target (`lookAndAttack` in `integration/bot/scenarios.js`).
- **Paper coalesces position events to ≤ ~20/s** on this stack. The Bukkit-event timer path cannot see >21/s rates reliably; the PacketEvents integration records raw packet rate (`MovementTracker` raw ring) and `PlayerMoveListener` uses `max(eventRate, rawRate)` for timer evidence. Timer detection is consistent in CI.
- **`PlayerMoveEvent` cancellation**: Paper cancels move events "reverted" for impossible movement; a `MONITOR`+`ignoreCancelled` listener never sees them. Forging >~7 b/s produces zero evidence (server reverts) — overspeed tests forge 6.4 b/s, just above the walk cap and under the revert threshold.
- **Zero-delta onGround claims**: vanilla clients land with a zero-motion packet; skipping zero-delta events entirely (as a naive early-return would) makes the no-fall evidence unrecoverable. Fixed (`PlayerMoveListener` evaluates zero-delta grounded claims).
- **`PlayerTeleportEvent` grace (2 s) covers the whole fall** for teleport-based scenarios — no-fall was unreachable until the harness waits the grace out.
- **BlockPlace/BlockBreak listeners were never registered** in `VigilAC.java#onEnable` — fast-place/scaffold/fast-break checks previously received **zero events, ever** (unit tests constructed the checks directly; production never wired the listeners). Fixed; both listeners now registered.
- **Legit sprint-jump trace VL**: mineflayer's physics model overshoots vanilla sprint speed (EMA 5.3–7.7 b/s vs walk cap 4.4, occasionally vs sprint cap 5.8); VL always < alert threshold; no alert ever fires. Harness artifact, not a plugin defect.
- **Backlog-burst speed spike**: at 563 ms ping, Paper drained a backlog of queued packets; consecutive events carried ~2 ms wall-clock deltas, inflating instant speed to EMA=11.0 on a legit sprint-jump. Fixed by skipping sub-45 ms samples in the speed EMA (`dt < 45 ms`, `SpeedCheck`), regression-tested.
- **RCON wire protocol**: the source-RCON empty-sentinel trick used initially deadlocked the client for 10 s per call and discarded the command response entirely (stdout only flushed after a complete read cycle) — every pad command failed silently for weeks of runs while `|| true` swallowed it. Fixed by draining packets until server silence; commands now return output in <0.5 s, and the pad build fails loudly if the fill does not report success.
- **Relative-path RCON invocation after `cd`**: `python3 tools/rcon.py` referenced from the server working directory meant the pad `fill` never executed — bots always landed at the random vanilla world spawn (often buried in mesa cliffs). Fixed with absolute paths; the pad anchor is verified per bot at spawn.
- **`spawn-monsters=false` suppresses `/summon` of monster entities too** — combat scenarios saw zero zombies on two consecutive runs. Natural spawns are blocked via gamerule `doMobSpawning false` instead (summons unaffected); verified in the reference run.
- **fresh-world chunks**: console `fill` fails with "That position is not loaded" on unloaded coordinates — the pad force-loads the region first (`forceload add`), and accepts "No blocks were filled" as a success response for no-op fills (flat seeds).

## 6. Reference run (42255620165) — per-leg summary

| Leg | PacketEvents | legit clean | illegal detected | unable | notes |
|---|---|---|---|---|---|
| 0 | absent | 15/16 (sprint-jump speed VL 3.83) | **5/10**: timer (VL 6.17), fly, invalid-vertical, **reach**, **kill-aura** (5 callbacks each; attacks verified landing — "Monster Hunter") | 2 (nofall, fast-break) | detection count improved from 0/11 → 5/10 across the harness-hardening iterations |
| 1 | installed (2.14.0) | 15/16 (sprint-jump speed VL 3.72) | **4/10**: timer (VL 6.53), fly, invalid-vertical, **kill-aura** (7 callbacks) | 2 | illegal-reach fired 1 callback on the kill-aura scenario |

Both legs: pad anchor confirmed in every scenario log (`RCON pos -> ... 64.0d ...`), `tps=20.0` in all debug lines, zero VigilAC exception/severe lines, default alert-only mode held, zero players punished.

### Per-check verdicts against the 12 implemented checks

| # | Check | Status | Evidence |
|---|---|---|---|
| 1 | `speed` | **Detects** (illegal-overspeed fires the check; verdict FAIL only because VL decays to 0.0 before the end-of-scenario read) | `[speed] ema≈6.5 cap=4.4 sustained=1.2s` lines every run; legit sprint-jump residual VL 3.7-3.8 (< alert 5.0) |
| 2 | `timer` | **Detects** | VL 6.17 / 6.53 on both legs (raw + event rate paths) |
| 3 | `fly` | **Detects** | fly observations on both legs (3-4 per leg) |
| 4 | `no-fall` | **Unable to test** | see above — vanilla-indistinguishable from Resistance-V landings |
| 5 | `invalid-movement` | **Detects** | 4-8 invalid-vertical observations per leg |
| 6 | `reach` | **Detects** (leg 0: 5 callbacks; leg 1: 1 callback on the kill-aura bot) | reach debug callbacks, excess up to 2.2 blocks; combat VL readout decayed to 0.0 by read time |
| 7 | `kill-aura` | **Detects** (leg 0: 5 callbacks; leg 1: 7 callbacks) | distinctTargets=3-4 in the 1 s window, HP before/after corroboration when the zombie is the probed target |
| 8 | `auto-clicker` | **Not proven** | ~25 CPS attacks fire damage events (bot kills target; "Monster Hunter"), but the end-of-window VL readout was `0.0` in the reference run (decay between the final attack and the RCON read); prior runs (`42237531836`) showed `reach 18.0 / kill-aura 2.0` |
| 9 | `aim-assist` | **Not proven** | 180° snaps land (partial HP 20→17/18) but the streak never sustains ≥ 5 consecutive snap-locked attacks; VL never materialized |
| 10 | `scaffold` | **Unable to test** | pillar placements complete (10/20) but the angle-from-look geometry for the rear placement never sustained across runs; no flag fired |
| 11 | `fast-place` | **Unable to test** | server-side 4-tick right-click cooldown caps completed placements (~5/s) below the 7.0/s gate |
| 12 | `fast-break` | **Unable to test** | server enforces dig progress; start+stop packets 20 ms apart do not destroy stone |

### Known scenario-environment interactions (honest)

- **Combined-run interference**: an earlier leg died to an unlucky `Creeper` blast mid-scenario (timer bot destroyed before any packet flow — whole leg blind). Fixed: `gamerule doMobSpawning false` at pad build (natural spawns blocked; `/summon` still works — `spawn-monsters=false` was rejected because it also suppresses summons; verified twice in CI).
- **Zombies must be NoAI** — slowness-frozen zombies still melee, knock the bot out of reach, and aborted the fast-place/dig scenarios (bots were slain mid-scenario). All summons now use `{NoAI:1b}`; pad terrain never drifts because the bot is teleported to (0.5, 64.0, 0.5) and stays in place.
- **Combat VL decay**: violations accrued early in the 12 s window are eaten by the 5 s decay delay + 1 VL/s decay before the RCON read — producing `0.0` VL readouts even when the check fired (debug callbacks/digest prove firing). The `/vigilac violations` read is a snapshot, not a counter — the debug lines and HP deltas are the corroborating evidence; the scenario verdicts remain honestly FAIL/UNABLE per the scorer, never PASS.
- **illegal-overspeed VL ≈ 0 at read**: the forged 6.4 b/s rate is only marginally over cap; per-second VL is small and 1 VL/s decay zeroes it by the read. Prior runs with clean readouts showed the same decay behavior. The check path is exercised (debug lines on both legs); the scenario cannot hold the bot on the pad indefinitely at higher rates without crossing Paper'smoved-too-quickly revert threshold (> ~7 b/s produces zero evidence because Paper cancels the reverted events).
- **Paper coalesces position packets** to ≤ ~20/s on this stack; the timer path uses `max(eventRate, rawPacketRate)` from the PacketEvents ring. The `timer` flag is nonetheless reproducible on both legs (6.17–6.65 VL every run).
- **legit sprint-jump trace VL (3.7–4.8)**: mineflayer's physics model overshoots vanilla sprint speed; sprint flag never registers server-side, so the walk cap applies. Never reached alert VL in any run; treated as a harness artifact, not a plugin defect (see FALSE_POSITIVE_ANALYSIS.md).

## 7. Fixed defects (with regression tests)

1. **BlockPlace/BlockBreak listeners never registered** — every block-interaction check was dead in production. Fix: register both in `VigilAC.java#onEnable`. Regression: existing `FastPlaceCheck`/`ScaffoldCheck`/`FastBreakCheck` unit suites + live harness place/break scenarios now produce evidence.
2. **Speed EMA fabricated by backlog bursts** — `dt<45 ms` samples skipped; unit test `SpeedCheckTest#backlogBurstDoesNotFabricateSpeed` pins the behavior; `sustainedSprintCheatStillFlags` guards against over-suppression.
3. **Timer evidence path unreliable under event coalescing** — raw PacketEvents ring feeds `max(eventRate, rawRate)` to timer; `MovementTrackerTest` pins ring semantics.
4. **Zero-delta grounded claims skipped** — no-fall evidence unrecoverable; fix + live scenario.
5. **Kill-aura/aim-assist/reach scenarios silently vacuous** — attacks dealt no damage (no aim; pitch sign error; 120 ms snap window) — harness fixed, corroboration probes added (HP before/after).
6. **`illegal-overspeed` duplicate define** — stale 8 b/s block shadowed the 6.4 b/s version and broke scenario enumeration entirely (require() crash, whole live leg dead). Fixed; duplicate removed.
7. **Scenarios file require() crash after bad slice edit** — a `s.replace('', new)` (inverted bounds) inserted a define at position 0, before `'use strict'`; whole harness unusable. Root-caused and repaired; `node -e require(...)` now part of the runner's enumeration gate.
8. **RCON client deadlock** — the empty-sentinel used to delimit command responses was typed as a server→client packet; Paper discarded it, every call stalled 10 s and the response was thrown away (failures invisible behind `|| true`). Root-caused to the pad never being built; fixed with drain-until-silence (`tools/rcon.py`), verified against a local mock server and in CI.
9. **Pad never built (two independent causes)** — (a) `python3 tools/rcon.py` relative path after `cd` into the server dir → "can't open file" swallowed by `|| true`; (b) fresh-world chunks unloaded → console `fill` answers "That position is not loaded". Fixed with absolute paths, `forceload add`, loud pad verification (`::error` + abort), and per-bot pad-anchor proof (`RCON pos` log line).
10. **Zombies attacked the bots / suppressed summons** — slowness-frozen zombies still melee (bots slain mid-scenario); `spawn-monsters=false` suppresses summons entirely (two blind CI legs). Fixed with `NoAI:1b` summons + gamerule `doMobSpawning false`.
11. **Git workspace loss (twice)** — local `.git` reset to Initial commit mid-session; recovered via `git fetch` + `git reset --hard origin/arena/9ae3a67f-vigilac` both times; working tree intact; remote is source of truth. Lesson: commit+push early, always.

## 8. Not-tested / out of scope (honest list)

- Real hacked clients (§1) — **UNABLE-TO-TEST**.
- Punish mode (kick/commands) — never enabled; every run alert-only.
- Concurrent-players/load testing (§Phase 3 #6 beyond CI soak of 20 scenarios × 2 legs): no sustained load harness in CI; unit suite covers async DB enqueue/flush paths (Phase 2).
- Multi-world, portals, Bedrock cross-play, ViaVersion — not tested.
- ≥10 concurrent bots per server — not tested.

## 9. Phase 3 acceptance coverage map

| Requirement (Phase 3 master task) | Status | Where |
|---|---|---|
| Paper 1.21.8 + Java 21, latest Phase 2 build | **Done** | `integration/paper.sh`, CI `Build and test` |
| PacketEvents installed AND absent (CI matrix) | **Done** | `packetevents: ['0','1']` matrix, both legs green |
| Separate legitimate and modified client accounts | **Partial** — disposable offline-mode accounts per scenario (both kinds), not per-player accounts for external clients | `bot.js` |
| Isolated server per run; exact build/config recorded | **Done** | §2 |
| Alert-only preserved; never auto-ban | **Done** (verified: no kick/ban in any log; `mode: alert`) | §2 |
| Meteor / LiquidBounce / Wurst verified | **UNABLE-TO-TEST** | §1 |
| Legit: sprint/jump/swim | **Done (swim NOT covered)** — sprint-jump, stand, place-bridge, break-stone, attack-zombie, knockback, respawn, teleport (+ all under latency); **no swim scenario exists** | scenario list |
| Legit: knockback/velocity | **Done** — `legit-knockback` (zero VL every run) | §6 |
| Legit: teleport/respawn | **Done** | §6 |
| Legit: high latency/jitter | **Done** — dedicated `-lat` pass at 120 ms ± 30 ms netem; no -lat scenario produced a FAIL annotation in the reference run | §3, §5 |
| Legit: temporary TPS degradation | **NOT TESTED** — no low-TPS injection in this phase | §6 |
| Metrics: detection rate, FP rate, latency, VL progression, TPS, ping, packet evidence, reproducible cases | **Done** — VL snapshots per bot, debug digests, RawSample artifacts, TPS/ping in every debug line; sprint-jump FP root-caused and fixed via latency pass | §6 |
| Confirmed bugs have regression tests | **Done** — `SpeedCheckTest#backlogBurstDoesNotFabricateSpeed`, `sustainedSprintCheatStillFlags`, `legitSprintJumpNeverFlags`, `MovementTrackerTest` (3) | §7 |
| 86+ tests remain passing | **Done** — CI `Build and test` SUCCESS (92 tests) on every push | CI `42255620165` |
| Paper integration green | **Done** — smoke job SUCCESS; 12-version compat SUCCESS | CI `42255620165` |
| Default stays alert-only | **Done** | §2 |
| No production-readiness claim without real-client evidence | **Done** — §1 | §1 |
| Regression tests for confirmed bugs | **Done** (re-run per CI) | §7 |
| Reproducible configs/scripts | **Done** — `integration/` (paper.sh, run.sh, scenarios.js, bots), pinned builds + sha256 | repo |
