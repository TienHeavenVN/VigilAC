# Phase 6D — Detection validation

Date: 2026-11-07. This document states **what was validated, how, and what remains
unvalidated**. It does not claim detection accuracy. Minecraft compatibility and
build success are never used as a substitute for detection verification.

## 0. Status legend

`IMPLEMENTED` = code exists · `UNIT_TESTED` = JUnit-passed in CI ·
`CI_VERIFIED` = full CI matrix (compile × 15 compat legs + runtime smoke) green ·
`REAL_CLIENT_VERIFIED` = run against a real (modified or vanilla) game client ·
`PROTOCOL_BOT` = mineflayer protocol bot (not a client) ·
`BLOCKED` = cannot be performed in this environment ·
`NOT_IMPLEMENTED` = does not exist.

## 1. Real-client validation verdict

**REAL_CLIENT_VALIDATION = BLOCKED.**

Reason (unchanged from Phase 4, re-confirmed 2026-11-07): this environment has no
display/GPU stack, no Minecraft launcher and no Mojang account; the sandbox only
reaches `github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`,
`pypi.org` and `files.pythonhosted.org`. Neither Meteor, LiquidBounce nor Wurst
publishes launchable client artifacts through those hosts, and headless Java
Minecraft clients are not runnable here. The user's brief lists real-client testing
(vanilla + hack clients on MC 1.21.8 and MC 26.2, plus mobile/touch Java 26.2) as a
required gate — **that gate is NOT passed and no Phase 6 detection claim may be built
on it.** No detection result in this repository is labelled REAL_CLIENT_VERIFIED.

What real clients WOULD be required for, per check — see
[REAL_CLIENT_VALIDATION.md](REAL_CLIENT_VALIDATION.md) for the full matrix (all
unable-to-test there, kept up to date separately).

**Mineflayer is not accepted as hack-client evidence.** The CI live harness and the
protocol bot only prove packets reach the server and VL accrues on a synthetic
physics model — they are recorded as `PROTOCOL_BOT`, never as real-client proof.

## 2. What IS validated and how

| Evidence layer | Scope | Phase 6 status |
|---|---|---|
| JUnit suite (count reported by CI `Build and test`; 129+ tests before Phase 6, grown by the Phase 6 additions) | every new algorithm: penalty bucket, dig lifecycle, low-gradient sink, KillAura multi-signal, correlation tri-state, attempt ledgers, geometry | UNIT_TESTED via CI (`Build and test` + 15 compat legs) |
| CI compile matrix × 15 compat targets | the packetevents 2.14.0 API usage compiles against 1.21.x (Java 21) and 26.x (JDK 25) API surfaces | CI_VERIFIED (after fix commit `7e8150f`; earlier legs failed on a wrong `InteractAction` import — recorded honestly) |
| CI runtime smoke (Paper 1.21.8, 26.1.2, 26.2, 26.3) | plugin load/enable/disable, commands, storage init | CI_VERIFIED (prior green runs; re-verified on this branch when green) |
| Live protocol-bot harness (Paper 1.21.8) | vanilla bot joins, moves, fights; checks stay silent on legit play | PROTOCOL_BOT (CI, if green on this branch — see §4) |
| Real modified client vs MC 1.21.8 / MC 26.2 | all Phase 6 checks | **BLOCKED** |
| Real vanilla FP sweep on 26.2 + mobile (Fold Craft Launcher, Java 26.2) | FP behavior on touch input | **BLOCKED** |
| Latency / low-TPS / potion-effect sweeps | degrade behavior | **BLOCKED** (unit-tested where the logic is pure — e.g. TPS guard disables under 10 TPS, potion caps in Speed) |

## 3. Per-check validation matrix (Phase 6)

Legend: ✅ verified via unit tests · ◐ partial (logic pure & tested; live behavior unproven) · ✗ not verified end-to-end.

| Check | Algorithm unit tests | Live/CI (vanilla) | Real client | Notes |
|---|---|---|---|---|
| `kill-aura` | ✅ multi-signal incl. corroboration requirement | CI vanilla should stay silent | ✗ | Anchor + 1 corroborating signal required; attempt rate & swingless attacks need packetevents at runtime (CI has none → PE path runtime-unverified) |
| `auto-clicker` | ✅ (damage path); attempt-rate path ◐ (needs PE) | CI vanilla silent | ✗ | 1.21.2+ server cooldown makes damage-CPS evidence weak; documented (FP-7) |
| `aim-assist` | ✅ | CI vanilla silent | ✗ | Closest-point bearing; streak gate; smooth aim never flags by construction |
| `reach` | ✅ (existing) | CI vanilla silent | ✗ | unchanged algorithm; still box-closest distance |
| `fast-break` | ✅ dig lifecycle math + penalty bucket | CI vanilla silent | ✗ | PE path (dig START/FINISH) not runtime-verifiable in CI (no PE plugin) — **the packetevents runtime path is CI-unverified everywhere**, only compiled |
| `no-fall` | ✅ incl. probe-failure safety | CI vanilla silent | ✗ | Server-collision probe runtime-unverified in CI (no world in unit tests); Bukkit impl is thin and reviewed |
| `speed` | ✅ incl. web/slow-block caps | CI vanilla silent | ✗ | friction multipliers unit-tested via context injection |
| `fly` | ✅ incl. low-gradient sink | CI vanilla silent | ✗ | |
| `jesus` | ✅ streak gate, Frost Walker exempt | n/a (no harness water pad) | ✗ | v1; waterlogged-slab note in CHECKS.md; NOT_REAL_CLIENT_VERIFIED |
| `step` | ✅ streak gate, piston grace | n/a | ✗ | v1; JB VI+ opening-tick ceiling documented; NOT_REAL_CLIENT_VERIFIED |
| `timer` | ✅ (existing) | CI vanilla silent | ✗ | rate still wall-clock based; client-tick accounting (Grim-style) NOT_IMPLEMENTED |
| packet telemetry | ✅ tracker unit tests | n/a (no PE in CI) | ✗ | packetevents present at runtime → engages; absent → all attempt paths degrade silently, checks keep event-driven behavior |

## 4. CI live harness results on this branch

Authoritative run `42355594181` (head `90312e9`) — **all jobs green**, both live
bot harness legs:

- leg (0): legit 15/16 clean, illegal 7/10 detected, unable 2, non-pass 6
- leg (1): legit 15/16 clean, illegal 9/10 detected, unable 2, non-pass 4
- VL totals (leg 1): timer 54, illegal-autoclick 48, illegal-fly 3, illegal-killaura 4,
  legit-sprint-jump:speed 93–94 (***known harness artifact*** — the bot never sends
  sprint state, so the server reads sprint=false and the sprint-jump EMA sits over the
  walk cap; documented since Phase 4, unchanged by Phase 6).
- Vanilla FP profile is identical to Phase 5: same 15/16, same known artifact, no new
  FP on `legit-attack-zombie` (auto-clicker=1 / aim-assist=1 v2 corroboration floors),
  no detection on `legit-mine-stone`, `legit-bridge`, `legit-fall-nodamage`.
- Detection deltas vs Phase 5 on the synthetic illegal set (timer 53→54,
  illegal-fastplace 9/23, illegal-killaura 0/4 — leg-dependent, bounded by the 1.21.2+
  server-side cooldown limiting combat damage evidence).
- no-fall and fast-break remain `UNABLE` for the protocol-bot harness (it cannot break
  blocks at survival rates or produce real falls; the no-fall scenario verifies the
  probe logs but cannot convict) — unchanged from Phase 4/5.

Interpretation bound: the live harness is a **protocol bot**, not a real client. VL on
an "illegal" scenario means the check fired on synthetic bot behavior, and silence on
a "legit" scenario bounds false positives only for bot-shaped vanilla behavior.

See [PERFORMANCE_BENCHMARK.md](PERFORMANCE_BENCHMARK.md) for load figures.

## 5. Manual test protocol (for a human with a real client)

Do this on a **private test server** you own — never on shared servers. Each run records:
client+version, MC version, loader, module+settings, ping, TPS, seconds-to-first-VL,
VL at 5 minutes, and whether `/vigilac monitor <player>` shows the expected signals.

Setup: Paper 1.21.8 (Java 21) and Paper 26.2 (Java 25), one vanilla client each, one
hack client (e.g. Meteor) if legally available to you.

| # | Scenario | Procedure | Pass criteria (vanilla) | Detection expected (hack) |
|---|---|---|---|---|
| 1 | Vanilla sprint-jump circuit | 60 s parkour, no attacks | zero VL on `speed`/`fly`/`timer` | n/a |
| 2 | Vanilla 1.21.2+ combat spam | hit a mob as fast as cooldown allows | no `kill-aura`/`auto-clicker` VL at ≥3 targets | n/a (hard case) |
| 3 | Vanilla mining | Efficiency V pickaxe + Haste II on stone | no `fast-break` VL or monitor evidence | n/a |
| 4 | Vanilla mining with tool swap mid-dig | swap tools during a dig | no `fast-break` VL (inconclusive path) | n/a |
| 5 | Meteor `Flight` default settings | enable flight in survival, fly 30 s | n/a | `fly` VL; ideally monitor `netY≈0` evidence |
| 6 | Meteor `Speed` default | sprint-speedhack 30 s | n/a | `speed` VL |
| 7 | Meteor `KillAura` default | fight 3+ mobs 60 s | n/a | `kill-aura` VL (anchor+corroboration) |
| 8 | Meteor `Scaffold` | auto-build bridge 60 s | n/a | `scaffold` (existing check) |
| 9 | `Timer` 1.1× | enable timer, 60 s | n/a | `timer` VL via sustained rate (needs packetevents installed) |
| 10 | `NoFall` | fall 10+ blocks repeatedly with NoFall | n/a | `no-fall` VL; `/vigilac monitor` shows `probe=unsupported` or streak evidence |
| 11 | High latency (300 ms artificial lag) | tc qdisc/netem 300 ms, repeat #1–4 | zero or near-zero VL | a check that fires on vanilla here is a bug — file it |
| 12 | Low TPS (<10) | `/tick sprint` or heavy lag source | checks self-disable; zero FP storm | advisory only |
| 13 | Mobile client (Java 26.2 on Fold Craft Launcher) | vanilla touch input #1–4 | no FP from touch cadence | n/a |
| 14 | Potion sweep | Speed II, Haste II, Slow Falling, Levitation | no FP (slow-fall/levitation/speed caps) | n/a |
| 15 | Starvation click spam on 1.21.8 | uncharged attacks ~10 CPS | no `auto-clicker` VL | n/a |

Recording format: append a row to `docs/REAL_CLIENT_VALIDATION.md` per run — never edit
past entries; add follow-up entries.

## 6. Verdict

`REAL_CLIENT_VERIFIED`: **none**. `PROTOCOL_BOT`: CI-governed. `BLOCKED`: everything in §1.
Detection effectiveness claims for Phase 6 checks are therefore **not made**; the checks
are *implemented, unit-tested analyzers with documented signal logic*, and must be
considered unproven against real hack clients until the §5 protocol is executed with a
real client by the maintainer.

---

## 7. Phase 7 appendix (AUDIT-01…17 remediation, 2026-11-07)

Status vocabulary unchanged. Every Phase 7 commit head
(`a4c732d`, `1ca599b`, `0d9b94c`) is **compile-UNVERIFIED locally** — no JDK in
this environment; CI (`Build and test` + 15 compat legs) is the gate. No Phase 7
unit test has been executed.

| Phase 7 change | Layer | Status |
|---|---|---|
| Reach attack-attempt pipeline (packet layer, bounded netty→main queue, attempt evidence only — never VL) | IMPLEMENTED | UNIT_TESTED_SOURCE (not executed; PE-runtime path NOT_VERIFIED in CI) |
| Item-specific reach cap via `attack_range` component; unknown ⇒ configured 3.0 cap; **no invented component values** (1.21.11+ spear ranges targetable but unverified) | IMPLEMENTED | UNVERIFIED live |
| Step bounded slippery-bounce state machine (no global exemption, no unbounded grace) | IMPLEMENTED | UNIT_TESTED_SOURCE (physics constants vanilla-known, 26.2-build-unverified) |
| Jesus: riptide + vertical-dominant wind-burst streak resets (bounded, interruptible) | IMPLEMENTED | UNIT_TESTED_SOURCE |
| AimAssist: snap = max single-sample angular burst; interval read pre-record | IMPLEMENTED | UNIT_TESTED_SOURCE |
| AutoClicker: cap 25.0; rate path requires rhythm corroboration | IMPLEMENTED | UNIT_TESTED_SOURCE |
| KillAura: gate = anchor ∧ (metronomic ∨ swingless); attempt rate scales VL only | IMPLEMENTED | UNIT_TESTED_SOURCE |
| NoFall: per-factor correlation windows 2/1.5/1.5/2 s | IMPLEMENTED | UNIT_TESTED_SOURCE |
| FastBreak: `BreakTimeModel` vanilla tool-tier model; unknown tier ⇒ timing verdict suppressed; creative/spectator skipped; NOT weakened | IMPLEMENTED | UNIT_TESTED_SOURCE (22 new tests, not executed) |
| AUDIT-12 atomic warn flags; AUDIT-17 CV javadoc demoted to debug-only | IMPLEMENTED | — |

**Detection claims remain: none.** No Phase 7 number in this repository is a
measurement of detection rate, precision, recall, or load. The manual protocol
above stays BLOCKED; the spear/attack-range items join it as explicit
1.21.11-specific rows to run on a real client.
