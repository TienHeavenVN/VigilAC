# Installing VigilAC

## Prerequisites

- A **Paper 1.21.x** server (1.21.2 has no published `paper-api` and is unsupported; see
  [COMPATIBILITY.md](COMPATIBILITY.md)), running **Java 21** — **or** a **Paper 26.x**
  (26.1.2 / 26.2 / 26.3) server running **Java 25**. The two lines ship **separate artifacts**:

  | Your server | Install | Bytecode |
  |---|---|---|
  | Paper 1.21.x on Java 21 | `VigilAC.jar` | Java 21 |
  | Paper 26.x on Java 25 | `VigilAC-26x.jar` | Java 25 |

  Cross-installing either artifact fails fast with `UnsupportedClassVersionError` — no silent
  half-loads, no exceptions inside gameplay.
- Optional: the **packetevents** plugin (2.x) for packet throughput metrics. Without it, VigilAC
  logs a one-line info message and continues; every check works.

## Steps

1. Build or download the artifact for your server line:
   - 1.21.x: `VigilAC.jar` from CI artifacts (Actions → CI → the artifact is uploaded with
     `VigilAC.jar.sha256`), or `./gradlew build` (JDK 21).
   - 26.x: `VigilAC-26x.jar` from the `VigilAC-26x` CI artifact (uploaded with
     `VigilAC-26x.jar.sha256`), or
     `./gradlew shadowJar -PpaperApiVersion=26.1.2.build.74-stable -PjavaRelease=25 -PartifactName=VigilAC-26x`
     (daemon on JDK 21, toolchain on JDK 25 — see the `build-26x` job in `ci.yml`).
   - Verify the download: `sha256sum -c <artifact>.jar.sha256`.
2. Copy the jar into the server's `plugins/` folder.
3. Start the server once; VigilAC writes `plugins/VigilAC/config.yml`.
4. **Leave `mode: alert-only` for at least a week.** Watch `[VigilAC] ...` alerts and tune
   thresholds in `config.yml` for your playerbase.
5. When you are confident, either:
   - keep alert-only and act on alerts manually, or
   - set `mode: punish` so thresholds at `kick-vl` trigger kicks, and (optionally) configure
     `punishments.commands-on-warn` / `commands-on-kick` (console commands with `%player%`, `%check%`).

## Verifying the installation

The same verification a human does is automated in CI (see the `smoke` job in
`.github/workflows/ci.yml`): headless Paper 1.21.8 (Java 21) and headless Paper 26.1.2 / 26.2 / 26.3
(Java 25, using `VigilAC-26x.jar`) servers are booted with the plugin installed, `Enabling VigilAC`
is asserted in the log, and `/plugins`, `/vigilac info` and `/vigilac violations` are driven over RCON.

```
/vigilac info
```

You should see the version, TPS, registered checks and `Violation storage: enabled`.

```
/vigilac debug <yourname> on
```

All detection observations now print to console/`vigilac.debug` holders — use this to verify
that honest play is not flagged before enabling punishments.

## Storage

Violations at or above the alert threshold are written asynchronously to
`plugins/VigilAC/vigilac-violations.db` (SQLite, WAL mode, batched, 5s flush). If the driver is
unavailable or the disk fails, VigilAC logs one `severe` message and disables storage rather than
degrade gameplay; the in-memory VL system keeps working.

## Upgrading

1. Stop the server, back up `plugins/VigilAC/config.yml`.
2. Replace the jar, start again; new default keys are *not* merged automatically — re-apply your
   values onto the shipped default `config.yml` when upgrading across major versions.
3. `/vigilac reload` re-reads `config.yml` without a restart (thresholds and mode apply immediately;
   detection tuning for check internals applies on restart).
