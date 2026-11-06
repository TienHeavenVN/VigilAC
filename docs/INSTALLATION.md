# Installing VigilAC

## Prerequisites

- A **Paper 1.21.x** server (1.21.2 has no published `paper-api` and is unsupported; see
  [COMPATIBILITY.md](COMPATIBILITY.md)), running **Java 21**. Runtime-validated in CI on **1.21.8**;
  other 1.21.x versions pass compile + unit tests but are not runtime-validated.
- Optional: the **packetevents** plugin (2.x) for packet throughput metrics. Without it, VigilAC
  logs a one-line info message and continues; every check works.

## Steps

1. Build or download `VigilAC.jar`:
   - from CI artifacts (Actions → CI → *VigilAC* — includes `VigilAC.jar.sha256`), or
   - `./gradlew build` (requires JDK 21; resolves Paper API from papermc.io).
   - Verify the download: `sha256sum -c VigilAC.jar.sha256`.
2. Copy the jar into the server's `plugins/` folder.
3. Start the server once; VigilAC writes `plugins/VigilAC/config.yml`.
4. **Leave `mode: alert-only` for at least a week.** Watch `[VigilAC] ...` alerts and tune
   thresholds in `config.yml` for your playerbase.
5. When you are confident, either:
   - keep alert-only and act on alerts manually, or
   - set `mode: punish` so thresholds at `kick-vl` trigger kicks, and (optionally) configure
     `punishments.commands-on-warn` / `commands-on-kick` (console commands with `%player%`, `%check%`).

## Verifying the installation

The same verification a human does is automated in CI (see the `integration` job in
`.github/workflows/ci.yml`): a headless Paper 1.21.8 server is booted with the plugin installed,
`Enabling VigilAC` is asserted in the log, and `/plugins`, `/vigilac info` and
`/vigilac violations` are driven over RCON.

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
