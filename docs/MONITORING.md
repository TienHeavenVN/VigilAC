# VigilAC continuous monitoring

VigilAC observes players continuously, not just when a packet triggers a check. This document
describes the monitoring architecture, its cost model, the correlation semantics and the honest
limits of what is (and is not) proven.

## Architecture

```
move/attack/block events ──► listeners ──► PlayerMonitor (per player, owned by PlayerData)
                                               │  latest continuous state (fixed-size fields)
                                               │  bounded EvidenceStore (ring, 256 cap, 90 s TTL)
                                               │  per-check detection status inputs
                                               ▼
                                        CorrelationEngine (stateless)
                                               ▼
                              checks (VL math unchanged) + ViolationManager (status/VL)
                                               ▼
                              /vigilac monitor <player> (ops, vigilac.monitor)
```

### Continuous state (latest value, no history growth)

Position, yaw/pitch, ground flag, sprint/sneak/glide/swim/vehicle flags, potion levels
(Speed/Levitation/Slow Falling), game mode, world, ping (EMA α=0.3), horizontal and vertical
velocity (block/s EMAs α=0.4), ground-transition timestamps, last-landing descent, damage
timestamps **separated by cause** (`lastFallDamageAt` / `lastGenericDamageAt`), teleport /
velocity-impulse / world-change timestamps, and a total-observations counter. All fields are
fixed-size scalars or short strings; updating the block is O(1) with one allocation-free pass.

### Evidence (discrete events, bounded ring)

The monitor records **events**, never per-tick snapshots: ground transitions, landings with
descent, suspicious landings, teleports, velocity impulses, world changes, damage by cause,
attacks, block place/break, impossible breaks, inventory clicks (view type only, bounded,
O(1) per click) and check evidence. Each entry carries a
timestamp, a type, a confidence in [0, 1], a source (`server` / `client` / `monitor`), a detail
string and — for check-scoped entries — the producing check's name. Storage is a per-player `ArrayDeque` ring — at most **256 entries** with a
**90 s TTL** — so memory is O(capacity), queries are O(capacity) worst-case, and the store is
freed with the player (`PlayerData` is discarded on quit).

### Per-check evidence history

`DetectionStatus` exposes, per check: live VL (supplied at render time), confidence, total
evidence count, last-detection timestamp, and the detail/timestamp of the most recent
`CHECK_EVIDENCE` entry for that check (read from the bounded ring at render time — O(capacity),
only when the command runs). `/vigilac monitor <player>` renders all of it plus the raw packet
rate and the six newest evidence entries.

### Detection status (per check, per player)

`ViolationManager.record` (the only place VL is born) feeds the monitor: evidence count,
last-detection timestamp and an EMA of the VL/alert-threshold ratio (α=0.5, an honest heuristic
for "how strongly this check is firing lately", documented as such). Checks additionally record
**exemption reasons** when they abstain (e.g. fast-break on instant-break materials, no-fall
under levitation). The `/vigilac monitor <player>` renderer combines those with the live VL and
config (alert threshold, enabled) into a state: `CLEAR` (VL 0), `SUSPECT` (VL > 0), `VIOLATION`
(VL ≥ alert threshold), `EXEMPT` (fresh exemption, VL 0) or `DISABLED` (check off in config).
State is computed at render time from live VL, so it can never go stale; evidence counts decay
naturally with the ring's TTL.

### Correlation (NoFall today) and the tri-state verdict

`CorrelationEngine.correlateNoFall` answers one question: *does this landing look like cancelled
fall damage, or is there exculpatory context?* Supporting facts: descent above threshold, recent
suspicious landings. Suppressing facts (**informational only**): recent teleport / velocity /
fall-damage / world-change. The result's `confidence` is surfaced in debug output and the
suspicion evidence entry; it **never** overrides a check's gates.

Phase 6 adds `CorrelationStatus` — a three-level discipline label, never a probability:
`CONFIRMED` (≥ 2 independent supporting signals, none suppressed), `SUSPECTED` (exactly one),
`INCONCLUSIVE` (zero, or any suppression). The verdict is stored per check on the monitor
(`noteCorrelation`), carried into `DetectionStatus.correlationStatus()` and printed by
`/vigilac monitor <player>` as `corr=<state>`. The NoFall collision probe path records
`CONFIRMED` immediately (collision-verified over-air ground claim); the streak path records the
engine's tri-state. Suppression always degrades to `INCONCLUSIVE`, by construction.

A collision-verified unsupported landing flags immediately (still one landing = one violation);
the legacy streak path is unchanged when the probe is absent or fails.

**Critical semantics:** unrelated damage (zombies, lava, drowning…) is recorded but is *never*
exculpatory for NoFall. Before this change, `ConfounderListener` reset the no-fall streak on ANY
`EntityDamageEvent`, silently suppressing fall evidence whenever a player was hurt. Now only
`DAMAGE_CAUSE == FALL` resets the streak (in both the listener and the check's grace window);
non-fall damage carries zero weight for NoFall and is covered by a dedicated regression test
(`NoFallCheckTest.unrelatedDamageDoesNotSuppressNoFallEvidence`).

## Cost model

- Move packet path: ~30 fixed-size field writes, ≤ 2 EMA updates — O(1), no allocation.
- Events that allocate: ground transitions, damage, teleports, velocities, attacks, block
  actions, violations (each ≤ 1 small `Evidence` object, ring capacity bounds the total).
- No background threads; everything runs on the event thread or the existing 1 s maintenance
  tick; the ring and all monitor methods are synchronized, matching the existing tracker model.
- Disconnect cleanup: monitor dies with `PlayerData` (map removal in `PlayerDataManager`).

## Validation performed

- Unit tests: `src/test/java/ac/vigil/monitor/` — `EvidenceStoreTest` (capacity, TTL,
  type/window filtering, latest-of-type, concurrent-writer bound), `PlayerMonitorTest`
  (ground transitions, cause-separated damage, EMA behavior, world-change detection, status
  signals), `DetectionStatusTest` (CLEAR→SUSPECT→VIOLATION ladder, DISABLED override, exemption
  freshness, confidence monotonicity), `CorrelationEngineTest` (teleport/velocity/fall-damage
  suppression, supporting facts, unrelated-damage non-suppression), plus updated `NoFallCheckTest`
  (FALL vs non-FALL damage) — all run in the existing CI matrix (1.21.8 + 26.1.2/26.2/26.3).
- Compile matrix, unit tests, compat matrix and load legs (10/25/50) are CI-verified; see
  PERFORMANCE_RESULTS.md for the load methodology.
- Real-client validation (Meteor Client): **UNABLE-TO-TEST** in this environment — do not claim
  otherwise. Behavioral claims above are backed by unit tests and the CI harness only.

## Honest limitations

- **Confidence is a heuristic**, not a probability; it reflects sustained evidence rate vs the
  alert threshold and nothing more.
- **Suppression is informational for NoFall's VL path**: velocity/teleport/world-change context
  is displayed, but only fall-damage and the pre-existing grace windows excuse a landing. This
  is deliberate false-positive control with server-authoritative facts; it does not weaken or
  strengthen the detector's gates.
- **Client-reported bits are untrusted**: onGround/sprint/etc. flows in as `client` source
  evidence; checks must (and do) rely on corroborating server facts before flagging.
- Monitoring does not yet feed other checks (Phase 5 scope: evidence model + NoFall semantics +
  fast-break exemption exposure + status/command). Other checks may adopt the evidence API later.
- Alert-only remains the default; automatic punishment stays disabled.
