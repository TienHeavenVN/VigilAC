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
