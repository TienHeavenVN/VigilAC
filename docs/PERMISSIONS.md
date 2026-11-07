# VigilAC permissions

All permissions default to **op**.

| Permission | Description |
|---|---|
| `vigilac.command` | Entry point for `/vigilac`. |
| `vigilac.info` | See version, TPS, check list, storage status, packet rate. |
| `vigilac.alerts` | Receive live violation alerts; can toggle them with `/vigilac alerts`. |
| `vigilac.check` | View per-player current violation levels (`/vigilac check`). |
| `vigilac.violations` | View violation overviews (`/vigilac violations`). |
| `vigilac.debug` | Toggle per-player debug output; receive debug broadcasts. |
| `vigilac.reload` | Reload `config.yml`. |
| `vigilac.exempt` | **Bypass all checks.** Also grants the exemption toggle subcommand. Grant sparingly (staff, known-good builders). |
| `vigilac.monitor` | View continuous monitoring state and per-check detection status for a player (`/vigilac monitor <player>`). |
| `vigilac.ai` | View AI-assist reports, assistant narratives and module status (`/vigilac ai ...`). Advisory only; never triggers action. |

Notes:

- A player with `vigilac.exempt` performs movement/attacks that are never evaluated.
- Per-check exemptions (without global bypass) are granted with `/vigilac exempt <player> <check>`
  and are session-only (cleared on disconnect and restart).
- Alert mute (`/vigilac alerts`) is per-player and session-only; the console always receives alerts.

## Server-side break/insta-break plugins (AUDIT-11)

Server-side vein-miner, insta-break or WorldEdit-like plugins remove blocks without
`START_DIGGING` packets; a survival fast-break streak can accrete on their victims even
though no cheat exists. If your server uses such plugins, grant the affected users or the
plugin's pseudo-players the standard exemption (`/vigilac exempt <player> fast-break`,
or `vigilac.exempt` for trusted operators/builders). This is an operator
configuration choice — VigilAC intentionally does **not** try to auto-detect
plugin-invoked breaks in code, since they are not reliably distinguishable from a
mass-break cheat from the event stream alone.
