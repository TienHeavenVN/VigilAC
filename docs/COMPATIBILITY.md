# Minecraft / Paper compatibility

VigilAC targets **Java 21** and Paper's `paper-api` for the versions below. Paper API is a
compile-time dependency only; at runtime the plugin runs against the server's bundled API, so the
version rules are as documented by Paper (`api-version: 1.21` in `plugin.yml`).

## Tested / supported versions

Every version in the CI matrix is **compiled against its own `paper-api` artifact and runs the
full unit-test suite** on every push (`.github/workflows/ci.yml`, job `compat`):

| Version | Compile + unit tests (CI) | Runtime smoke test (CI) | Notes |
|---|---|---|---|
| 1.21 | ✓ | n/t | paper-api 1.21-R0.1-SNAPSHOT resolves and passes |
| 1.21.1 | ✓ | n/t | paper-api 1.21.1-R0.1-SNAPSHOT resolves and passes |
| 1.21.2 | ✗ — unsupported | n/t | `io.papermc.paper:paper-api:1.21.2-R0.1-SNAPSHOT` is **not published** on `repo.papermc.io` (HTTP 404, confirmed by the CI probe). The plugin cannot be compiled or CI-verified for this version; do not advertise it as supported. |
| 1.21.3 | ✓ | n/t | |
| 1.21.4 | ✓ | n/t | |
| 1.21.5 | ✓ | n/t | |
| 1.21.6 | ✓ | n/t | |
| 1.21.7 | ✓ | n/t | |
| 1.21.8 | ✓ | ✓ **PASSED** (2026-11-06, CI run 42216394282, latest stable Fill v3 build; plugin enabled cleanly, `/plugins` showed VigilAC (green), `/vigilac info` reported all 12 checks + storage enabled, zero ERROR lines, clean disable, SQLite audit file created) | Primary target; CI boots a real Paper 1.21.8 server with the plugin installed |
| 1.21.9 | ✓ | n/t | |
| 1.21.10 | ✓ | n/t | |
| 1.21.11 | ✓ | n/t | |

Legend: ✓ = verified green in CI · n/t = not runtime-tested (compile + unit tests only) — do not
treat runtime behavior on these versions as validated.

## Runtime validation

The `integration` job in CI downloads the official Paper 1.21.8 build from `fill.papermc.io` (Fill v3 API),
boots a headless server in CI (offline mode, RCON enabled), loads VigilAC, and verifies:

- `Enabling VigilAC` and `Disabling VigilAC` both appear in `server.log`,
- `/plugins` and `/vigilac info` respond over RCON and report VigilAC,
- `plugins/VigilAC/vigilac-violations.db` is created (async SQLite storage initializes at boot),
- no line mentioning `VigilAC` with `Exception` or `SEVERE` is logged,
- the full server log is uploaded as a workflow artifact (`integration-server-log`).

Runtime validation on 1.21.8 covers: plugin load/enable, PacketEvents-absent path, commands over
RCON, join/quit lifecycle is exercised implicitly (no players join in CI), storage initialization
and clean shutdown. It does **not** cover: in-game movement/combat detection behavior against a
live client, join/quit with real players, TPS under load, or memory profiling. Performance
characteristics under load (TPS impact, GC pressure) have **not** been measured; see
[SECURITY_REVIEW.md](SECURITY_REVIEW.md) and the roadmap for validation gaps.

### Incidents found and fixed by the runtime test
- First boots disabled VigilAC with `NoClassDefFoundError: com/github/retrooper/packetevents/...`
  because class resolution at the `PacketEventsHook.tryRegister` call site loaded the optional
  API even when packetevents is absent. Fixed by splitting the implementation into
  `PacketEventsMetrics`, resolved only past the presence gate (commit `a026158`). The runtime
  smoke test now fails the build on any `Error occurred while enabling VigilAC` line.

## Paper-specific API surface in use

- `Server#getTPS()` — Paper-only (Spigot unsupported).
- `Player#getPing()` — Paper-only.
- `LivingEntity#isGliding()`, `Entity#isOnGround()`, `Entity#isInsideVehicle()`,
  `Entity#getWidth()/getHeight()`, `Material#getHardness()`, `PotionEffectType.*` — verified
  present with identical signatures on 1.21.4 through 1.21.11 (spot-checked via the PaperMC/Paper
  source at each tag; CI compile is the authoritative check per version).
- `BlockPlaceEvent#getBlockPlaced()`, `BlockPistonExtendEvent#getBlocks()`,
  `BlockFace#getDirection()` — same verification basis.

No hardcoded protocol numbers, no NMS, no reflection, no version string comparisons anywhere in
the codebase; compatibility rests solely on the Paper API surface above plus packetevents 2.x
(soft dependency, 2.11.2, for `/vigilac info` packet metrics only).

## Unsupported / untested

- **1.21.2** — see table above (artifact unpublished; cannot build).
- **Spigot** — not supported (uses Paper-only APIs).
- **Folia** — not supported; the plugin uses the legacy scheduler API and `PlayerData` lifecycle
  hooks assume the main-thread player event model.
- **Purpur** — expected to work (Paper-compatible) but not CI-tested.
- **1.20.x and older** — not supported; `api-version: 1.21` gates classloading to 1.21+.

## Updating the matrix

When a new 1.21.x releases:

1. Add its version to the `compat` matrix in `.github/workflows/ci.yml`.
2. If the `paper-api` artifact 404s, the probe step fails with `::error title=PaperApiMissing`;
   remove the version and document it here instead of claiming support.
3. If it compiles and passes unit tests, record it in the table above.
