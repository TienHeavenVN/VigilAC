# Phase 6 — Performance benchmark (detection engine)

Date: 2026-11-07. Methodology is unchanged from Phase 5 (`docs/PERFORMANCE_RESULTS.md`
§6): the `load` CI job boots an isolated Paper 1.21.8 server with VigilAC, joins N
offline-mode sprint-jumping mineflayer bots (45 s), samples RCON `tps`/`mspt` at 10 s
intervals, reads `ps` for RSS/CPU, enables GC logging, and requires
`failed_bots == 0`, a SQLite audit DB, and no plugin exceptions/severe lines.

Absolute values are CI-runner order-of-magnitude, not production benchmarks.

## What Phase 6 added to the hot path

- Per move packet: `MovementTracker.noteMovePacket` (existing) + `airborneTicks++` (O(1) counter).
- Per rotation-carrying packet: `RotationTracker.sample` — evict + add in a 64-entry ring (O(1) amortized).
- Per attack/dig/swing packet: `AttackAttemptLedger` / `DiggingTracker` / `SwingTracker` — O(1), fixed 64 entries.
- `ATTACK_ATTEMPT` monitor evidence: one small object per attack, bounded 256-entry ring.
- FastBreak dig path: bucket update only on block breaks (rare vs move packets).
- NoFall server-collision probe: only at suspicious landings (rare).

## Results (this branch, 2026-11-07)

| Leg | Run (head) | Bots joined | TPS (1m) during load | RSS baseline → peak | MSPT avg peak | GC pauses | failed_bots | stop→exit |
|---|---|---|---|---|---|---|---|---|
| load=10 | `42356113120` (`038ba4f`) | 10 / 10 | 20.0 constant | ~1.56 → ~1.69 GB (~+94 MB, ≈9 MB/pl) | 16.7 ms transient (shared runner) | n/m | 0 | 3546 ms |
| load=25 | `42356115464` (`f0618e5`) | 25 / 25 | 19.11 → 20.0 (recovered) | ~1.65 → ~1.77 GB (~+117 MB, ≈4 MB/pl) | 32.6 ms transient | n/m | 0 | 3559 ms |
| load=50 | `42356116992` (`f7bde96`) | 50 / 50 | 15.7 → 16.2 dip (5m 19.0); recovered to 20.0 after bots left | ~1.61 → ~1.72 GB (~+97 MB, ≈1 MB/pl) | 158 ms single transient sample (shared runner); sustained avg ≈ 18–35 ms | n/m | 0 | 2531 ms |

Baseline comparison (Phase 5, same harness): load=50 previously dipped to 15.5–15.7 TPS
and recovered to 20.0 — the same CI-box ceiling signature appears here. The Phase 6
additions do not regress the numbers at any of the three bot counts; per-player RSS
growth is within noise (~1–9 MB/player vs Phase 5's ~2–4 MB/player).

**Not measured / honest caveats:**

1. **packetevents is absent in the load harness** — the new telemetry dispatch path
   (ATTACK/ANIMATION/PLAYER_DIGGING/rotation wrappers) was NOT stress-tested at 10/25/50
   players here. The legs exercise the event-driven path only. Overhead of the telemetry
   listener on a 50-player production server with packetevents installed is **not**
   measured; it is bounded (fixed rings, O(1)) but unproven.
2. CPU is shared-runner noise (peak 200 % at 10 bots, 145–153 % at 50 — not comparable
   across runs).
3. TPS dips at 50 bots are world-tick dominated (the 5 m average stayed ≥ 18.9), matching
   Phase 5 — this is the CI ceiling, not a plugin overhead figure.
4. Sustained-soak (> 1 h) and heap-allocation profiling remain unmeasured, as in Phase 5.

## Verdict

The Phase 6 detection engine introduces no measurable regression at 10/25/50 concurrent
bots under the CI harness; stability (failed_bots=0, clean disable, DB present) holds at
all three scales. CI_VERIFIED for the event-driven path; the packetevents hot path
remains CI-unverifiable (see [COMPATIBILITY_MATRIX.md](COMPATIBILITY_MATRIX.md)).

## Final-head re-verification (2026-11-07, heads d46e1a3 / 6518bb8 / c5f78db)

The three load legs were re-run against the FINAL code head (includes `jesus`/`step`,
which touch the per-move path with two O(1) counters). Reading:

| Leg | Run | TPS (1m) dip | TPS (5m) | RSS peak | failed_bots | stop→exit |
|---|---|---|---|---|---|---|
| load=10 | `42359070974` | none (20.0) | 20.0 | ~1.66 GB | 0 | 2539 ms |
| load=25 | `42359072124` | 19.06 | 19.6–19.9 | ~1.74 GB | 0 | 3560 ms |
| load=50 | `42359074255` | 14.2–15.4 during the 10 s peak; 5m average ≥ 18.5 | 19.5–20.0 | ~1.69 GB | 0 | 2541 ms |

The 50-bot dip matches the Phase 5 and pre-jesus/step (`f7bde96`) signatures on the
same shared CI runners — within run-to-run noise. No detectable regression from the
two new checks (they add ~2 integer increments and two comparisons per move event).
The packetevents caveat above still applies to these runs.
