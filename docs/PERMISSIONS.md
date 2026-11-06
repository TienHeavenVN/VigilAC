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

Notes:

- A player with `vigilac.exempt` performs movement/attacks that are never evaluated.
- Per-check exemptions (without global bypass) are granted with `/vigilac exempt <player> <check>`
  and are session-only (cleared on disconnect and restart).
- Alert mute (`/vigilac alerts`) is per-player and session-only; the console always receives alerts.
