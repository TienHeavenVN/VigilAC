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
