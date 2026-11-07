# VigilAC performance results (Phase 3)

**Scope note:** numbers come from GitHub-hosted CI runners (ubuntu-latest, unknown CPU model, shared). Treat absolute values as order-of-magnitude, not benchmarks. What matters here is order-of-magnitude health, regression safety, and absence of crashes/leaks across sustained CI soak.

## 1. Server health during live tests

- **TPS:** debug lines emitted every movement sample show `tps=20.00–20.02` across all scenarios and both PacketEvents legs — the Paper server never degraded below the check's 10 TPS floor in any run.
- **Ping (bot→server, loopback):** 2–13 ms typically; 563 ms in the injected latency pass (cc. the netem configuration).
- **Scenario cadence:** 20 scenarios × 2 legs per CI run, ~9–10 minutes per leg end-to-end including boot, forced-load pad build (49×49, stone y=63 / air 64–76), all scenarios, latency pass, per-bot RCON VL reads, scoring.
- **Memory:** server `-Xmx1G`; no OOM or long-GC pauses observed in any run (no `Done`-marker stalls; scenario wall-clock matched expectations).

## 2. Plugin internals relevant to performance

- **Async SQLite:** violations persist via `DatabaseManager` queue + background executor; the main thread never blocks on SQLite (Phase 2 verification; exercised here by every alert/warn recorded in CI).
- **VL decay:** maintenance task runs every 1 s (`decayPerSecond`); O(tracked players × constant); decays only entries past the 5 s delay.
- **Movement tracker:** event ring (256) + raw PacketEvents ring (256) per player; O(1) record, O(256) worst-case read once per move event; negligible.
- **Rotation-only events:** previously early-returned with zero cost; since the zero-delta fix, grounded claims from airborne players flow through the full pipeline (delta-relative checks behave correctly; cost identical to a small move event). Rotation-only no-motion events are skipped.
- **PacketEvents metrics:** netty-thread atomic counter + two int rings; no Bukkit API touched off the main thread (verified by review; PacketEventsMetrics catches Throwable once and suppresses).
- **Leaks:** PlayerDataManager.unload on quit disposes per-player state; rings are fixed-size; no unbounded collections introduced by Phase 3.

## 3. Concurrency / stability

- Backend unit suite: **92 tests** (Phase 2 baseline 86 + 6 new: 3 `MovementTrackerTest`, 3 `SpeedCheckTest`) — green in CI on every push (`Build and test` job).
- **PacketEvents absent vs present:** both legs run the full scenario set cleanly; the PE-absent leg logs "packetevents not found; packet throughput metrics are disabled (all checks work regardless)" and remains fully functional.
- **Compat suite:** 12 MC versions compiled + unit-tested on every push (1.21, .1, .3–.11); runtime smoke on 1.21.8 (paper.sh boot + plugin enable marker + `/vigilac` command works).
- **Crash/leak:** zero plugin enable failures, zero server crashes, zero datapoint of "Could not pass event" in any CI log (checked via annotations; raw server.log accessible to CI authors on demand). No mob interference: `gamerule doMobSpawning false` on the pad (summons still work).
- **Determinism:** every bot is teleported to (0.5, 64.0, 0.5) on the forced-load flat pad and verified (`/data get entity <bot> Pos` → `[0.5d, 64.0d, 0.5d]`) before scenarios run — eliminating the random-world-spawn non-determinism that previously buried bots in mesa terrain and made combat/place scenarios seed-dependent.

## 4. What was NOT measured (honest)

- CPU/RSS of the plugin process under sustained load (needs a dedicated benchmark VM; CI runners are too noisy).
- TPS under 20+ concurrent players.
- Database flush latency under alert storms (alerts in CI are rate-limited by design; punish mode never enabled).
- Long-duration leak soak (> 1 h). Phase 2 covered the unit paths; a 1 h stability soak is recommended before production sign-off.

## 5. Regression safety net (Phase 3 additions)

- `SpeedCheckTest#backlogBurstDoesNotFabricateSpeed` — backlog samples cannot inflate EMA (exact FP).
- `SpeedCheckTest#sustainedSprintCheatStillFlags` — over-suppression guard.
- `SpeedCheckTest#legitimateSprintJumpNeverFlags` — vanilla-legal sprint-jump under cap stays clean.
- `MovementTrackerTest#rawRingCountsOnlyTrailingSecond` / `rawRingIndependentOfEventRing` / `maxPicksRawOverEventRate` — raw rate evidence path.

## 6. Phase 4 — concurrent-bot load legs (2026-11-07)

The `load` job (integration/load.sh; isolated Paper 1.21.8 + VigilAC, offline-mode sprint-jumping bots, RCON TPS/MSPT + `ps` RSS/CPU sampling, GC logging, annotations capped at 10/step) was executed at 10/25/50 requested bots. **Honest caveat:** the stock `server.properties` written by `paper.sh` did not set `max-players`, so the default 20 silently capped the 25-bot leg at 20 online (annotations show "There are 20 of a max of 20 players online" from the 10s sample). The harness was fixed (commit `2b3213c`) to raise `max-players` to `max(20, BOTS)` and to consolidate the post-run annotations under the 10-per-step cap. Numbers below are CI-runner order-of-magnitude, not benchmarks.

| Leg (run) | Head | Bots joined | RSS baseline → under load | TPS (1m) | Notes |
|---|---|---|---|---|---|
| load=10 (`42333288012`) | `7d77c04` | 10 | ~1561 → ~1718 MB | 20.0 | Pre-fix; SUCCESS (shutdown flush, DB present, no VigilAC exceptions) |
| load=25, pre-fix cap (`42336398295`) | `d20aeca` | 25 attempted → **20 online (cap)** | 1611 → ~1716 MB (+~5 MB/player) | 17.98 → 18.75 by 45s | TPS never below 17.98 with 20 sprint-jumping players; MSPT avg peaked 13.9 ms; load job SUCCESS criteria met. **This is a 20-player datapoint, not a 25-player one.** |
| load=25, max-players fixed, crashed on report bug (`42337117150`) | `2b3213c` | **25 of max 25 online** | 1595 → ~1696 MB (+98 MB, ≈3 MB/player) | 18.84 → 19.17 (never < 18.84) | Measurements valid (failed_bots=0, MSPT avg peak 26.4 ms); job FAILED only in the post-measurement report (nounset bug, fixed in `3856899`). Green re-run pending. |
| load=50, pre-fix cap (`42336477682`) | `8dca66d` | 50 attempted → **20 online (cap)** | 1601 → ~1747 MB (+~7 MB/player) | 18.1 | Same stock-cap artifact; load job SUCCESS. **20-player datapoint, not 50.** |
| load=50, max-players fixed, crashed on report bug (`42337769033`) | `0e72bc8` | **50 of max 50 online** | 1620 → ~1738 MB (+114 MB, ≈2.3 MB/player) | **dipped to 15.5–15.7** (5m 18.9; 16.05 at post-sample, recovers) | Measurements valid (failed_bots=0, MSPT avg peak ~30.8 ms). Job FAILED only in the same report bug. Green re-run pending. |
| load=25, fixed harness (`42338344033`) | `3856899` | 25 of max 25 online | 1643 → ~1726 MB (+81 MB, ≈3 MB/player) | 19.14–20.0 (never dipped with 25 online) | **SUCCESS** — failed_bots=0, MSPT avg peak 13.6 ms, GC 92 pauses ≈ 758 ms / 45 s, stop→exit 2537 ms, DB present, no VigilAC exceptions |
| load=50, fixed harness (`42338347092`) | `7b4a03a` | 50 of max 50 online | 1571 → ~1683 MB (+110 MB, ≈2 MB/player) | dipped (1m 11.7 avg across the peak; 5m 17.5); recovered after bots left | **SUCCESS** — failed_bots=0, MSPT avg peak 41.2 ms, GC 100 pauses ≈ 1116 ms / 45 s, stop→exit 2552 ms, DB present, no VigilAC exceptions. **50 concurrent sprint-jumpers transiently halve TPS on shared CI hardware** — sizing guidance: this is a CI-box ceiling marker, not a plugin overhead measurement (world tick dominates). |

Section 4 above still applies for the "not measured" list; the load legs bound, but do not eliminate, the need for a dedicated benchmark before production sign-off. Two caveats are load-leg specific: (1) they run on shared GitHub-hosted runners — absolute TPS/MSPT reflect runner CPU, not a sizing promise; (2) the stock pre-fix harness silently capped at 20 players (see table), so only the post-fix rows are true 25/50-player results.
