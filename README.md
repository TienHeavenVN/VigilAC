# VigilAC

A production-oriented anti-cheat plugin for **Paper** servers — **Minecraft 1.21.x** (Java **21**, `VigilAC.jar`) and **Minecraft 26.x** (Java **25**, `VigilAC-26x.jar`).

VigilAC detects cheating with **real detection logic** — no placeholder checks, no fake thresholds,
no suppression flags. Every check documents its algorithm, its false-positive analysis and its
tunables, and is covered by unit tests.

> **Default mode is `alert-only`.** VigilAC will never kick, ban or punish anyone until you
> explicitly set `mode: punish` in `config.yml` *and* tune thresholds for your server.

## Status

| Level | Checks | State |
|---|---|---|
| Framework | `Check`, `CheckManager`, contexts, `PlayerData`, `ViolationManager`, async SQLite audit | Implemented + unit tested |
| Movement | `speed`, `fly`, `timer`, `no-fall`, `invalid-movement` | Implemented + unit tested |
| Combat | `reach`, `kill-aura`, `auto-clicker`, `aim-assist` | Implemented + unit tested |
| Player | `scaffold`, `fast-place`, `fast-break` | Implemented + unit tested |

All twelve checks ship today; no detection ships as a stub. See
[docs/CHECKS.md](docs/CHECKS.md) for algorithms and [docs/ROADMAP.md](docs/ROADMAP.md) for what is
intentionally not implemented.

## Requirements

- **1.21.x line:** Paper 1.21.x (see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the tested matrix — 1.21.2 unsupported), Java **21**, `VigilAC.jar`
- **26.x line:** Paper 26.1.2 / 26.2 / 26.3 (26.1 and 26.1.1 unsupported), Java **25**, `VigilAC-26x.jar`
- The two artifacts are not interchangeable (bytecode major 65 vs 69).
- Optional: [packetevents](https://github.com/retrooper/packetevents) plugin — enables `/vigilac info` packet throughput metrics. All checks work without it.

## Installation

1. Download the artifact matching your server (build it yourself, see below, or from CI artifacts — each includes a `.sha256` checksum): `VigilAC.jar` for 1.21.x, `VigilAC-26x.jar` for 26.x.
2. Put it in your server's `plugins/` directory.
3. Restart the server; watch your console for `VigilAC enabled: 12 checks registered`.
4. Edit `plugins/VigilAC/config.yml`. Start with defaults (`mode: alert-only`) and watch alerts for a few days.
5. Optionally switch to `mode: punish` once thresholds are tuned for your server.

See [docs/INSTALLATION.md](docs/INSTALLATION.md) for the full guide and the headless CI smoke-test procedure.

## Building

```bash
./gradlew build            # unit tests + shaded JAR with the SQLite driver (JDK 21)
./gradlew build -PpaperApiVersion=1.21.4-R0.1-SNAPSHOT   # build against a specific 1.21.x API
# 26.x artifact (Gradle daemon on JDK 21, toolchain on JDK 25):
./gradlew shadowJar -PpaperApiVersion=26.1.2.build.74-stable -PjavaRelease=25 -PartifactName=VigilAC-26x
```

Produces `build/libs/VigilAC.jar` (26.x: `build/libs/VigilAC-26x.jar`). Dependencies resolve from repositories declared in
`settings.gradle.kts` (papermc.io, CodeMC, Maven Central) — no mirrors, no TLS bypasses.

## Commands

Base permission: `vigilac.command` (default: op). Every subcommand checks its own permission.

| Command | Description | Permission |
|---|---|---|
| `/vigilac info` | version, TPS, check list, storage status, your packet rate | `vigilac.info` |
| `/vigilac alerts` | toggle alerts for yourself | `vigilac.alerts` |
| `/vigilac check [player] [check]` | show current violation levels | `vigilac.check` |
| `/vigilac violations [player]` | violation overview (all tracked checks) | `vigilac.violations` |
| `/vigilac debug [player] [on\|off]` | toggle per-player detection debug output | `vigilac.debug` |
| `/vigilac reload` | reload `config.yml` | `vigilac.reload` |
| `/vigilac exempt <player> [check\|*]` | toggle exemptions | `vigilac.exempt` |

Full reference: [docs/PERMISSIONS.md](docs/PERMISSIONS.md).

## The built-in checks

| Check | Category | Evidence |
|---|---|---|
| `speed` | movement | EMA of horizontal speed vs vanilla cap (walk 4.317 / sprint 5.612, +20%/Speed potion, slippery multiplier), min-sustain gate |
| `reach` | combat | eye-to-AABB attack distance vs 3.0 (+tolerance), consecutive + windowed average gates |
| `fly` | movement | sustained airborne time without descent (hover) or upward motion (rising flight) |
| `timer` | movement | movement-packet rate sustained above 21.5/s for 3s |
| `no-fall` | movement | accumulated descent at landing vs damage-event signal; streak of 3 required |
| `invalid-movement` | movement | non-finite geometry; per-tick vertical travel above terminal velocity |
| `kill-aura` | combat | ≥3 distinct damaged entities within 1s (attack-cooldown-limited swings) |
| `auto-clicker` | combat | CPS > 20.5, or metronomic intervals (stddev < 12 ms) sustained |
| `aim-assist` | combat | rotation teleports landing on new targets; ≥4 consecutive snaps |
| `scaffold` | player | ≥6 consecutive placements at >80° from the look vector |
| `fast-place` | player | sustained placement rate above the 4-tick cooldown ceiling (7/s) |
| `fast-break` | player | breaks faster than the vanilla hardness/Efficiency/Haste lower bound |

Algorithm details, thresholds and false-positive analysis: [docs/CHECKS.md](docs/CHECKS.md).

## Architecture

```
Listener (main thread, O(1)) ──► DetectionContext / AttackContext / PlaceContext / BreakContext
        │                          (immutable, server-agnostic)
        ▼
CheckManager ──► 12 checks ──► ViolationCallback
        ▼
ViolationPipeline (exemptions, debug) ──► ViolationManager (VL, decay)
        ▼
PunishmentManager ──► AlertManager (staff)  +  DatabaseManager (async SQLite audit)
```

- All domain classes (`ac.vigil.check.*`, `ac.vigil.data.*`, `ac.vigil.detection.*`,
  `ac.vigil.violation.*`) are **server-agnostic** — unit-tested without a live server.
- Per-player state lives in `PlayerData` (thread-safe VL maps, `MovementTracker`,
  `CombatTracker`, `BlockActionTracker`, grace windows) and is released on quit.
- Violations persist to SQLite asynchronously (batched, WAL mode).

## Configuration

See the annotated [`config.yml`](src/main/resources/config.yml). Default mode is `alert-only`;
punishments (kicks at `kick-vl`) require `mode: punish`. A boot-time `ConfigValidator` logs any
incoherent VL ladder (alert ≤ warn ≤ kick ≤ max) as a warning.

## Testing

```bash
./gradlew test
```

Unit tests cover detection matrices for all twelve checks (flag/no-flag scenarios incl.
vehicle/elytra/liquid/TPS/knockback/potion cases), the violation pipeline (VL accumulation, cap,
verdict escalation, decay), the config validator, the SQLite storage layer (round-trip on a real
file) and the packaging contract of `config.yml`/`plugin.yml`.

CI also runs **runtime smoke tests**: headless Paper 1.21.8 (Java 21, `VigilAC.jar`) and headless Paper
26.1.2 / 26.2 / 26.3 (Java 25, `VigilAC-26x.jar`) servers are booted, `/plugins` and `/vigilac info` are
driven over RCON, and clean enable/disable plus the SQLite audit file are verified — see
`.github/workflows/ci.yml`.

## Compatibility

- **Paper 1.21.x**: see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) — each version is compiled
  and unit-tested in CI's compatibility matrix; runtime validation is performed on 1.21.8.
- **Paper 26.1.2 / 26.2 / 26.3 (Java 25, `VigilAC-26x.jar`)**: compiled, unit-tested and
  boot-verified in CI; 26.1 and 26.1.1 are unsupported (unresolvable `paper-api`).
- **Purpur**: expected to work (Paper-compatible fork) but **not tested**.
- **Spigot**: not supported (uses Paper APIs such as `Server#getTPS()`).
- **Folia**: **not supported**; the plugin uses the legacy scheduler API.

## License

MIT — see [LICENSE](LICENSE).
