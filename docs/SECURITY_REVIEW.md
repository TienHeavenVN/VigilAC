# VigilAC security & performance review (v1.0.0)

Reviewed: 2026-11-06. Scope: every file under `src/main` and the build files. This review covers
the threat model that matters for a server plugin: resource exhaustion, privilege escalation,
unsafe I/O on hot paths, and third-party code execution.

## Attack surface

| Area | Assessment |
|---|---|
| Command injection via `commands-on-warn` / `commands-on-kick` | **High if misconfigured; documented.** Commands are dispatched by the console with `%player%`/`%check%` substitution. Player names cannot inject further commands because the value is a raw name (validated by the server's by-name lookup at join; offline targets fall back to UUID) — but an operator-written template is trusted code by definition. Templates are not player-controlled. |
| SQL injection | **Not possible**: all queries are parameterized (`PreparedStatement`), and table name is fixed. |
| Path traversal | Storage path comes from `config.yml` (`storage.file`), resolved relative to the plugin data folder. Operator-controlled; not player-controlled. |
| Unsafe deserialization | None (no Java serialization anywhere). |
| Log injection / spam | Violation storage disables itself after one severe log line on persistent failure — a broken disk cannot flood the log or lag the server. |
| Off-thread Bukkit API calls | **None.** All event handlers run on the main thread and build immutable snapshots. The only background threads: the storage executor (JDBC only), the packet listener (pure `AtomicLong`/`volatile` counters — no Bukkit objects touched), and daemon cleanup. VL decay iterates a concurrent map on the main-thread scheduler. |
| Permission model | Subcommands each check their own permission; `vigilac.exempt` bypasses detection and is op-only by default. Exemption lists are cleared on quit/reload — no permanent privilege creep. |

## Performance review

| Path | Cost |
|---|---|
| `PlayerMoveEvent` | Rotation-only events exit before any work (position unchanged). One feet-block lookup per movement event; potion effects iterated (small set); TPS array read once. No allocation beyond the snapshot builder and block view. |
| `EntityDamageByEntityEvent` | O(1) — primitives and one entity AABB computation. |
| Violation pipeline | Two permission checks worst case, a map lookup for VL, list broadcast to staff on verdicts only (verdict path is not hot: it requires evidence). |
| VL decay task | Runs once per second; iterates tracked players with per-check map lookups — O(players × checks). At 200 players × 2 checks this is negligible. |
| Storage | Batched (25 rows / 5s) in a single transaction on a daemon thread. WAL mode + busy_timeout prevent reader stalls. Queue is bounded implicitly by flush cadence; drops happen (logged once) rather than blocking. |
| Memory | `PlayerData` per online player only; removed on quit along with exemptions. No global unbounded collections (alert mute set is per-session online UUIDs). No listener leaks: everything is registered once on the plugin. |

## Findings

1. `PlayerMoveListener` still allocates the snapshot builder per position event — expected cost at
   vanilla move rates (~10–20/s/player). Revisit only with profiler evidence.
2. `PlayerData.notePacket()` uses a 1s sliding window with a race on `packetWindowStart`; the race
   is benign (worst case a 2× window merge) and bounded.
3. Exemptions are session-scoped by design; operators wanting persistent exemptions must use the
   `vigilac.exempt` permission.
4. Alert mute is per-session; restarts re-enable alerts — documented in PERMISSIONS.md.

## Response checklist for reports

Report suspected vulnerabilities to the repository issue tracker with reproduction steps. Do not
post working kick-evasion configs publicly ahead of a fix.

## Addendum: Phase 2 hardening (2026-11-06)

Additional review scope: the ten new detection checks, their listeners, `ConfigValidator`,
`BreakContext`/`PlaceContext`/`AttackContext`, `BlockActionTracker`/`MovementTracker`/
`CombatTracker`, `tools/rcon.py` (CI-only), and the new CI jobs.

| Area | Assessment |
|---|---|
| New player-facing input | None. No new commands, permissions or placeholders were added; `commands-on-*` semantics unchanged. |
| Detection state | All per-player detection state lives in `PlayerData` trackers and is cleared on quit (and on reload for exemptions). Check singletons are stateless. |
| Config validation | `ConfigValidator` runs at enable and on `/vigilac reload`; incoherent VL ladders (alert > warn > kick ordering) and non-finite thresholds log `warning` lines and never crash the plugin. Invalid values cannot escape as NaN/Infinity into detection math because contexts and config reads are finite-checked before use. |
| Resource usage of new checks | All O(1) per event; the only O(n) scans are bounded combat-target windows (≤ tens of entries) and the auto-clicker sample ring (configurable, default ≤ 60 samples). |
| Block hardness data | `fast-break` reads `Material#getHardness()` (static, server-controlled). No client-influenced data enters the calculation. |
| Optional dependency isolation | Compilation against packetevents is `compileOnly`; the runtime integration is isolated into `PacketEventsMetrics`, loaded only when the packetevents plugin is present (verified by the CI boot test on a server without packetevents — before the fix, class resolution at the call site disabled the plugin via `NoClassDefFoundError`). |
| CI integration tooling | `tools/rcon.py` speaks Source RCON to `127.0.0.1` in CI only, with an ephemeral password (`vigilac-ci`) scoped to the ephemeral CI runner. It is never loaded or executed by the plugin. The CI smoke test runs `online-mode=false` on an isolated runner — no real accounts, no production data. |
| Dependency hygiene | Dependencies resolve from `repo.papermc.io`, CodeMC and Maven Central only; no mirrors, no TLS bypass flags, no unverified artifacts. CI performs a per-version existence probe (HTTP 404 ⇒ job fails fast before building). |
| Unverified areas (honest) | No profiler has been run; hot-path zero-allocation claims are from code inspection. No fuzzing of config inputs beyond the validator's finite checks. No runtime exploit testing of detection bypasses has been performed. |

## Addendum: Phase 4 lifecycle & concurrency audit (2026-11-07)

Scope: every path where threads, reload/disable boundaries, or shared state interact —
PacketEvents registration, main-thread restrictions, async SQLite, disconnect cleanup,
reload/disable behavior, concurrent VL accounting, and collection bounds.

| # | Finding | Severity | Fix (commit) |
|---|---|---|---|
| 1 | **packetevents listener never unregistered on plugin disable.** After `/reload`, each registration kept firing `notePacket()` for the same players, inflating the raw client-packet rate input to the timer check by N× (N = reloads) and leaking the old listener graph per reload. | Medium (detection integrity) | `PacketEventsHook` now keeps the object packetevents actually registered (its `PacketListenerAbstract` wrapper — registering a `PacketListener` returns the wrapper, see PE `EventManager.registerListener`) and unregisters it in `onDisable`, ordered **before** storage close and **after** scheduler cancel. (`04cebbc`, `450ca99`) |
| 2 | **Race: maintenance tick vs. storage close.** `onDisable` closed the database (executor shutdown) while a queued `maintenanceTick` could still call `flushIfDue()` → `executor.execute` threw `RejectedExecutionException` into the main scheduler. | Medium (availability/log spam) | `onDisable` now cancels all scheduled tasks **first**; `enqueue`/`flushIfDue` are reject-safe no-ops with a one-time warning; `close()` sets `closed=true` before draining (double-close safe, pinned by `DatabaseManagerTest#enqueueAndFlushAfterCloseAreSafeNoOps`). (`04cebbc`) |
| 3 | **`PlayerData.notePacket()` unsynchronized window rotation** on `packetWindowStart` (noted in the v1.0.0 review as "benign"; strictly, two packet threads could interleave a window merge). `packetsPerSecond` was a non-volatile `double` read from the main thread (no atomicity guarantee). | Low | `notePacket()` is now `synchronized`; `packetsPerSecond` is `volatile`. (`04cebbc`) |
| 4 | **VL timestamps not injectable** (`System.currentTimeMillis()` scattered through `PlayerData`), making VL lifecycle determinism untestable. | Low (testability) | `PlayerData` accepts an optional `LongSupplier` clock; all internal timestamps (VL ledger, grace windows, packet window) derive from it. Production behavior unchanged (defaults to wall clock). Pinned by `ViolationDecayTest`. (`04cebbc`) |
| 5 | **Storage observability gap**: the async flusher gave no signal about batch latency or drained row counts — load testing could not quantify SQLite write latency. | Low (operability) | `DatabaseManager` records `lastFlushNanos`, `lastBatchRows`, `totalFlushedRows` (written on the storage thread, read lock-free); `/vigilac info` prints them. No new I/O, no API exposure. (`04cebbc`) |
| 6 | **PacketEvents availability was invisible to operators.** PE-dependent visibility (packet-rate metrics, and thereby the strongest timer signal) could silently vanish on misconfigured servers. | Low (operability) | `/vigilac info` now prints the packetevents status (`hooked packetevents <version>` / `absent` / `failed: <reason>`), server version and Java version. All checks remain fully operational without PE; the timer check degrades to the Bukkit move-event stream by design. (`04cebbc`) |
| 7 | **Unbounded-collection re-audit**: all per-player trackers bounded (`reachHistory` by `ema-samples`, `attackTimes`/`placeTimes`/`recentTargets`/rings fixed-size or window-trimmed; `violationLevels` bounded by registered check count; alert-mute set per-session). No new collections introduced. | Informational | No change required. |
| 8 | **DB shutdown ordering vs. check listeners**: on disable, `Disabling VigilAC` now always follows a fully quiescent storage (tasks cancelled → PE unregistered → DB flush+close). The CI smoke test fails on any `VigilAC` exception/severe, so regressions surface as CI failures. | Informational | Verified green (smoke 1.21.8, CI runs `42329324192`, `42329090084`-fix iteration). |

### Threat-model deltas

None. No new commands, permissions, message formats or storage paths were added. The new
`/vigilac info` lines expose only non-secret runtime state to holders of `vigilac.info`
(op default). The load harness is CI-only and never ships in the artifact.

### Regression tests added

- `ViolationDecayTest` — deterministic VL lifecycle (event-time visibility, decay delay,
  no negative VL, fresh-violation delay re-arm).
- `DatabaseManagerTest#enqueueAndFlushAfterCloseAreSafeNoOps` — post-close enqueue/flush are safe no-ops.
- `DatabaseManagerTest#flushDiagnosticsAreExposed` — batch flush accounting.
- `AutoClickerCheckTest#fewerThanMinSamplesNeverFlags` — pins the live-harness minimum-sample gate.
