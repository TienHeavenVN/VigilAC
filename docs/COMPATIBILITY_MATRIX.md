# Phase 6 — Compatibility matrix (detection engine + packetevents)

Date: 2026-11-07. This is the Phase 6 view of compatibility. The canonical
artifact-level matrix (provenance, SHA-256, runtime-smoke status, per-version CI)
lives in [COMPATIBILITY.md](COMPATIBILITY.md) and is **not** duplicated here.

## 1. Artifacts & Java (unchanged)

| Artifact | JDK | paper-api | Targets |
|---|---|---|---|
| `VigilAC.jar` | 21 | `1.21.x` per compat leg | 1.21, 1.21.1, 1.21.3–1.21.11 (1.21.2 = NOT published upstream, unsupported) |
| `VigilAC-26x.jar` | 25 | `26.1.2.build.74-stable` | 26.1.2, 26.2, 26.3 |

## 2. packetevents (softdepend `softdepend: [packetevents]`)

Pinned compile version: **2.14.0** (`build.gradle.kts`: `compileOnly`). Not bundled.
All telemetry is inert without the plugin; checks fall back to event-driven paths.

### Packet truth table (verified against tag `v2.14.0`, never master)

| Packet | Version gate | Wrapper | Used by | Status in code |
|---|---|---|---|---|
| `ATTACK` | **26.1+ only** | `WrapperPlayClientAttack#getEntityId()` | attack attempts → KillAura signal 3/4, AutoClicker rate, `ATTACK_ATTEMPT` evidence | IMPLEMENTED |
| `INTERACT_ENTITY` | ≤1.21.x (26.1+ sends no attack here) | `WrapperPlayClientInteractEntity#getAction()` → `InteractAction.ATTACK` (nested enum) | same as above | IMPLEMENTED (2.14.0 nested-enum API) |
| `INTERACT` / `INTERACT_AT` (26.1+) | all | interaction (right-click) only | **not** treated as attack | IMPLEMENTED (ignored) |
| `ANIMATION` | ≤26.2 | `WrapperPlayClientAnimation#getHand()` | swing cadence | IMPLEMENTED |
| `PUNCH` | 26.3+ | swing successor | swing cadence | IMPLEMENTED (defensive; same handling) |
| `PLAYER_DIGGING` | all | `WrapperPlayClientPlayerDigging#getAction()` → `DiggingAction`: `START_DIGGING` / `CANCELLED_DIGGING` / `FINISHED_DIGGING` / `CHANGE_DESTROY_DIRECTION` / `STAB` | FastBreak digging lifecycle; `STAB` ignored | IMPLEMENTED |
| `PLAYER_FLYING` / `PLAYER_POSITION` / `PLAYER_ROTATION` / `PLAYER_POSITION_AND_ROTATION` | all | `WrapperPlayClientPlayerFlying#getLocation()` (yaw/pitch via `Location`) | rotation stream (Combat), raw move rate (Timer) | IMPLEMENTED |
| `PLAYER_POSITION_AND_LOOK`, `PUNCH` wrappers etc. | n/a | — | — | NOT USED |

**No packet IDs were invented.** Every constant above was read from
`git show v2.14.0:<file>` on 2026-11-07. `PUNCH` is 26.3+ and is handled
defensively (it does not exist ≤26.2; the reference comparison simply never
matches there).

### Version behavior notes

* On ≤1.21.x attacks arrive as `INTERACT_ENTITY` with action `ATTACK`; on 26.1+ Mojang
  split attacks into a dedicated `ATTACK` packet and repurposed `INTERACT_ENTITY` for
  right-click interactions. VigilAC listens to both and treats only ATTACK as an
  attack attempt, so combat telemetry works without version branching.
* `PLAYER_DIGGING` actions: `STAB` (1.21.11+) is ignored; `CHANGE_DESTROY_DIRECTION`
  (26.3+) marks the dig as direction-changed (→ inconclusive) instead of resetting it.
* 26.1+ `INTERACT_ENTITY` forces `INTERACT_AT` semantics server-side for the wrapper's
  action field; we never branch on it for damage detection.

### Runtime verification status

| Claim | Status |
|---|---|
| packetevents 2.14.0 API compiles against both artifacts | CI_VERIFIED (after `7e8150f`) |
| Telemetry trackers behave correctly | UNIT_TESTED |
| Telemetry listener engages on a live server | **NOT_VERIFIED** (no PE plugin in CI harness) |
| Packet ordering on real 26.x servers | **NOT_VERIFIED** |

## 3. Version support policy (unchanged)

* 1.21.2 remains **not supported** (paper-api never published). Any other version in the
  matrix must keep compiling + pass unit tests on every push or the push is red.
* No version is silently dropped; adding a version = new compat leg + `paper-api` probe
  (see `Authoritative version inventory` job, which passes).
* No unverified packet IDs, ever. A packet that cannot be verified against a pinned
  packetevents tag is not used.
* `api-version: 1.21` in plugin.yml for the 1.21.x artifact; 26.x artifact built with
  `-PpaperApiVersion=26.1.2.build.74-stable -PjavaRelease=25`.

## 4. Check-level compatibility caveats introduced in Phase 6

* `kill-aura` attempt-rate corroboration is inert without packetevents; anchor +
  cadence corroboration still works (unit-tested).
* `fast-break` Path A inert without packetevents; Path B identical to 1.21-era heuristic.
* `no-fall` collision probe needs the main thread (probe is main-thread-only); on a
  world/chunk failure it assumes support (never convicts).
* Multi-signal combat telemetry has no version-specific branches — packet-type
  reference equality only.
