# Minecraft / Paper compatibility

VigilAC ships **two artifacts**:

- **`VigilAC.jar`** — the 1.21.x line, built against Paper's `paper-api`, Java **21** bytecode. For servers on Minecraft 1.21.x.
- **`VigilAC-26x.jar`** — the 26.x line, built against `paper-api 26.1.2.build.74-stable` (lowest supported 26.x API) with JDK **25** bytecode. For servers on Minecraft 26.x on Java 25. It is verified in CI on 26.1.2, 26.2 and 26.3 (compile + unit tests + runtime smoke on all three).

Paper API is a compile-time dependency only; at runtime the plugin runs against the server's bundled API, so the version rules are as documented by Paper (`api-version: 1.21` in `plugin.yml` for the 1.21.x artifact; the 26.x artifact is built with `-PpaperApiVersion=26.1.2.build.74-stable -PjavaRelease=25`).

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
| **26.1.2** | ✓ (JDK 25 toolchain) | ✓ **PASSED** (boots on JDK 25; plugin enable marker + RCON + clean disable; CI run 42335380663) | `VigilAC-26x.jar`, built against `26.1.2.build.74-stable` |
| **26.2** | ✓ (JDK 25 toolchain) | ✓ **PASSED** (same artifact boot-verified; cross-minor binary compat; CI run 42335380663) | `VigilAC-26x.jar` |
| **26.3** | ✓ (JDK 25 toolchain) | ✓ **PASSED** (same artifact boot-verified; CI run 42335380663) | `VigilAC-26x.jar` |

Legend: ✓ = verified green in CI · n/t = not runtime-tested (compile + unit tests only) — do not
treat runtime behavior on these versions as validated.

**Artifact provenance for 26.x:** the SHA-256 published with the artifact is from CI run
`42335380663` (job `Build VigilAC-26x artifact (JDK 25)`), which also asserts that all
`ac/vigil/**` classes carry Java 25 bytecode (major 69) and that the bundle contains no
class file older than Java 8 (52). The 1.21.x artifact (`VigilAC.jar`) continues to target
Java 21 and must **not** be installed on 26.x servers — class version 65. The 26.x artifact
must **not** be installed on 1.21.x servers — class version 69 requires Java 25.

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

- **1.21.2** — `paper-api` not published (HTTP 404); cannot build.
- **26.1 and 26.1.1** — no resolvable `paper-api` coordinates on `repo.papermc.io` as probed in CI (neither `-R0.1-SNAPSHOT`-style nor `<version>.build.<n>-stable` names; see the `Authoritative version inventory` job output, runs 42334359016 / 42335380663). Cannot compile; **do not advertise as supported.**
- **Any 26.x other than 26.1.2 / 26.2 / 26.3** — not compile/runtime verified; unsupported.
- **Spigot** — not supported (uses Paper-only APIs).
- **Folia** — not supported; the plugin uses the legacy scheduler API and `PlayerData` lifecycle
  hooks assume the main-thread player event model.
- **Purpur** — expected to work (Paper-compatible) but not CI-tested.
- **1.20.x and older** — not supported; `api-version: 1.21` gates classloading to 1.21+.
- **Mixing artifacts** — `VigilAC.jar` on 26.x fails with `UnsupportedClassVersionError` (65 < 69); `VigilAC-26x.jar` on 1.21.x/Java 21 fails the same check (69 > 65). Use the artifact matching your server.

## Updating the matrices

**1.21.x:**

1. Add its version to the `compat` matrix in `.github/workflows/ci.yml`.
2. If the `paper-api` artifact 404s, the probe step fails with `::error title=PaperApiMissing`;
   remove the version and document it here instead of claiming support.
3. If it compiles and passes unit tests, record it in the table above.

**26.x:**

1. 26.x versions publish `<version>.build.<n>-stable` (not `-R0.1-SNAPSHOT`) — probe for the exact stable build first (the `Authoritative version inventory` job lists resolvable coordinates).
2. Update the `compat` matrix entry (`jdk: 25`, `javaRelease: 25`), the smoke matrix, and the `build-26x` job's probe + `paperApiVersion` pin together — the artifact is always built against the **lowest** supported 26.x API and boot-verified on every supported runtime.
3. If a version's artifacts do not resolve, document it as unsupported here — never claim support without a green compat + smoke leg.
