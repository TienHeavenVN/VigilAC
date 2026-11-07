# Real-client validation (Phase 4)

This document records the state of validation against **real modified Minecraft clients** —
Meteor Client, LiquidBounce (nextgen) and Wurst. It is deliberately kept separate from the
protocol-bot results in [LIVE_TEST_RESULTS.md](LIVE_TEST_RESULTS.md), which are **not** a real
client: the protocol bot is a Node.js `mineflayer` process that speaks the protocol with a custom
physics model, and it is only evidence that packets reach the server and checks accrue VL.

## Methodology (what is required for a check to be marked VERIFIED_ON_REAL_CLIENT)

For each client × check pair:

1. A controlled server booted from the official Paper build with VigilAC installed (CI boot test).
2. The client launches against that server with the cheat's default-shipped module (e.g. in Meteor:
   `Flight`, `Speed`, `KillAura`, …) at **default settings** unless the module has no sensible
   defaults, in which case the settings are recorded exactly as used.
3. Recorded per run: client name + version, Minecraft version, loader (Fabric/NeoForge/Forge),
   module + settings, ping, server TPS, VL accrued, seconds-to-first-VL, seconds-to-alert-VL.
4. The cheat must first be verified *active*: e.g. visible displacement for `Speed`/`Flight`, visible
   rotations for aim-assist/KillAura modules, server-observed packet deltas for timer.
5. Result recorded as **VERIFIED_ON_REAL_CLIENT** (VL accrues + alert fires) or
   **NOT_DETECTED** (VL never accrues — a finding, never hidden).

## Environment availability (2026-11-07)

| Client | Available in this environment? | Why |
|---|---|---|
| Meteor Client | **No** | Requires a Fabric instance + game display/GPU stack; the CI runner is headless Linux with no display server, no GPU, no Minecraft launcher and no Mojang account. The sandbox additionally has no access to the Minecraft CDN (`piston-data.mojang.com`) so the client jar itself cannot be downloaded here. |
| LiquidBounce (nextgen) | **No** | Same reasons; additionally LiquidBounce publishes through Maven/Modrinth metadata that is not reachable from this sandbox (only `github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org` are reachable). |
| Wurst | **No** | Same reasons; Wurst additionally requires its own loader distribution channel. |

GitHub is reachable, and release *binaries* could in principle be fetched from a GitHub release —
but none of these clients publish complete, launchable artifacts on GitHub releases (they publish
via their own launchers/maven/modrinth), and launching a game client headlessly is out of reach
regardless (no display, no GPU, no Java runtime here for Minecraft's GUI stack is irrelevant since
the client cannot start at all).

**Therefore every client × check entry below is UNABLE-TO-TEST. No PASS, FAIL, or "works on Meteor"
claim is made anywhere in this repository.** The protocol-bot live results
([LIVE_TEST_RESULTS.md](LIVE_TEST_RESULTS.md)) remain the only end-to-end behavioral evidence and
are reported separately.

## Per-check status matrix

Legend: **UNABLE-TO-TEST** (environment) — not evaluated; no claim made.

| Check | Meteor Client | LiquidBounce (nextgen) | Wurst | Protocol-bot (separate) |
|---|---|---|---|---|
| `speed` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT (see LIVE_TEST_RESULTS §6) |
| `fly` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `timer` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `reach` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `kill-aura` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `auto-clicker` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | UNIT_TESTED_ONLY (server-side damage-event funnel; see FALSE_POSITIVE_ANALYSIS.md) |
| `aim-assist` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | UNIT_TESTED_ONLY (same funnel) |
| `scaffold` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT / behavior covered |
| `no-fall` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST (protocol-legal fall damage cannot be cancelled client-side in vanilla tooling) |
| `invalid-movement` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `fast-place` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | DETECTED_ON_PROTOCOL_BOT |
| `fast-break` | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST | UNABLE-TO-TEST (see FALSE_POSITIVE_ANALYSIS.md) |

## What would be needed to complete this matrix

1. A machine with a display (or headless-GL stack), a Minecraft installation for the target
   version, a launcher, and each client's current release.
2. A pinned, reproducible server build (the CI smoke test already pins one via Fill v3).
3. A scripted driver per client (e.g. via their scripting APIs) or a human operator following a
   written protocol, capturing the same fields listed under Methodology.
4. At minimum, this matrix must be executed for `speed`, `fly`, `timer`, `reach`, `kill-aura` and
   `aim-assist` before any "real-client verified" claim can be attached to a check.

## Phase 7 note (2026-11-07)

Unchanged and reaffirmed: **REAL_CLIENT_VALIDATION = BLOCKED** for every check.
Phase 7 adds these items to the manual matrix once a real client is available:

- 1.21.11+ spear reach: verify the `attack_range` component is read (debug
  `source=component`) and the spear at its true range does not accrue VL;
  attack attempts on a held, mis-aimed spear produce `reach-attempt` evidence
  without damage neither accrues nor exonerates by itself.
- 26.2: rerun the full Phase 6 manual protocol plus mace Wind Burst and
  riptide-trident water arcs against `jesus`, `step`, `no-fall`.
- High-ping (300 ms) PvP vs strafing target: confirm no `reach` VL accrues
  (or evaluate the opt-in `lag-compensation` calibration).

No environment change removes these cells from UNABLE-TO-TEST in this sandbox.
