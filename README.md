# VigilAC

A production-oriented anti-cheat plugin for **Paper 1.21.8** servers.

VigilAC detects cheating with **real detection logic** — no placeholder checks, no fake thresholds,
no suppression flags. Every implemented check documents its algorithm, its false-positive analysis
and its tunables, and is covered by unit tests.

> **Default mode is `alert-only`.** VigilAC will never kick, ban or punish anyone until you
> explicitly set `mode: punish` in `config.yml` *and* tune thresholds for your server.

## Status

| Feature | State |
|---|---|
| Check framework (`Check`, `CheckManager`, `DetectionContext`, `AttackContext`) | Implemented |
| Violation pipeline (VL accumulation, decay, verdicts, async SQLite audit log) | Implemented |
| **SpeedCheck** (movement) | Implemented + unit tested |
| **ReachCheck** (combat) | Implemented + unit tested |
| Fly, KillAura, Timer and further modules | Roadmap (not implemented — see [docs/ROADMAP.md](docs/ROADMAP.md)) |
| Folia support | **Not supported** (not threaded for the regionized scheduler) |

Only SpeedCheck and ReachCheck ship today. No detection ships as a stub.

## Requirements

- Paper **1.21.8** (or a Paper fork for the same version), Java **21**
- Optional: [packetevents](https://github.com/retrooper/packetevents) plugin — enables per-player packet throughput metrics (`/vigilac info`). All checks work without it.

## Installation

1. Download `VigilAC.jar` (build it yourself, see below, or from CI artifacts).
2. Put it in your server's `plugins/` directory.
3. Restart the server.
4. Edit `plugins/VigilAC/config.yml`. Start with defaults (`mode: alert-only`) and watch alerts for a few days.
5. Optionally enable punishments with `mode: punish` once thresholds are tuned.

See [docs/INSTALLATION.md](docs/INSTALLATION.md) for the full guide.

## Building

```bash
./gradlew build
```

Produces `build/libs/VigilAC.jar` (shaded, with the SQLite driver bundled) and runs the unit test
suite. Requires JDK 21; dependencies resolve from Maven Central, papermc.io and codemc (see
`settings.gradle.kts`).

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

## What the built-in checks do

### Speed (`speed`, movement)
Sustained horizontal speed above the vanilla-derived cap (walk 4.317 / sprint 5.612 blocks/s,
+20% per Speed potion level, 1.6× on ice/slime), tracked with an exponential moving average and a
min-sustain gate — a single laggy burst can never flag. Teleports, knockback, vehicles, elytra,
liquids and <10 TPS suppress the check; teleport/velocity events install grace windows.

### Reach (`reach`, combat)
Attack distance from the attacker's eye position to the target's axis-aligned bounding box
(the same quantity vanilla uses). An attack counts as over-reach above `max-reach + tolerance`
(default 3.03); **four consecutive** over-reach attacks *and* a windowed average above the cap are
required before VL is added. Creative/spectator and attacks from vehicles are exempt.

Algorithm details, thresholds and false-positive analysis: [docs/CHECKS.md](docs/CHECKS.md).

## Architecture

```
Listener (main thread, O(1)) ──► DetectionContext/AttackContext (immutable, server-agnostic)
        │
        ▼
CheckManager ──► SpeedCheck / ReachCheck ──► ViolationCallback
        │
        ▼
ViolationPipeline (exemptions, debug) ──► ViolationManager (VL, decay)
        │
        ▼
PunishmentManager ──► AlertManager (staff)  +  DatabaseManager (async SQLite audit)
```

- All domain classes (`ac.vigil.check.*`, `ac.vigil.data.*`, `ac.vigil.detection.*`,
  `ac.vigil.violation.*`) are **server-agnostic** — unit-testable without a live server.
- Per-player state lives in `PlayerData` (thread-safe VL maps, trackers, grace windows) and is
  released on quit.
- Violations persist to SQLite asynchronously (batched, WAL mode).

## Configuration

See the annotated [`config.yml`](src/main/resources/config.yml). Default mode is `alert-only`;
punishments (kicks at `kick-vl`) require `mode: punish`.

## Testing

```bash
./gradlew test
```

Unit tests cover both checks (flag/no-flag matrices incl. vehicle/elytra/ice/TPS/knockback cases),
the violation pipeline (VL accumulation, cap, verdict escalation, decay) and the packaging contract
of `config.yml`/`plugin.yml`.

## Compatibility

- **Paper 1.21.8**: target platform.
- **Purpur**: expected to work (Paper-compatible fork) but **not tested** by CI.
- **Spigot**: not supported (uses Paper APIs such as `Server#getTPS()`).
- **Folia**: **not supported**; the plugin uses the legacy scheduler API.

## License

MIT — see [LICENSE](LICENSE).
