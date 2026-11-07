# VigilAC roadmap

Shipped today (all with real detection logic, tunables and unit tests):

- **Framework**: VL accumulation/decay, async SQLite audit, alerts/warnings/kicks (default
  alert-only), `/vigilac` command tree, per-check enable/disable, boot-time config validation.
- **Movement**: `speed`, `fly`, `timer`, `no-fall`, `invalid-movement`.
- **Combat**: `reach`, `kill-aura`, `auto-clicker`, `aim-assist`.
- **Player**: `scaffold`, `fast-place`, `fast-break`.
- **Compatibility CI**: per-version `paper-api` compile + unit-test matrix (see
  [COMPATIBILITY.md](COMPATIBILITY.md)); runtime smoke test booting real Paper 1.21.8 in CI.
- **Continuous monitoring**: per-player state machine + bounded evidence ring (256 entries / 90 s TTL),
  cause-separated damage signals, correlation engine (informational confidence), per-check detection
  status (state/VL/confidence/evidence/exemption reason), `/vigilac monitor <player>`. See
  [MONITORING.md](MONITORING.md).

## Next (not implemented — no stubs exist)

### Detection
- **Velocity / AntiKnockback**: knockback-resistance inference from velocity events (needs per-player
  velocity-response tracking).
- **Criticals**: jump-crit damage pattern analysis (needs a jump-state model).
- **Inventory move**: item movement outside the inventory screen.
- **Spider / Step / Jesus**: wall-climbing, stair-stepping and water-walking (require block-context
  resolution at move time).

### Platform
- **Integration test depth**: scripted join/quit, movement simulation and combat simulation against a
  real server (see LIVE_TEST_RESULTS.md — a scripted bot harness exists and runs in CI legs; the
  remaining gap is external real clients, which are UNABLE-TO-TEST and required for production sign-off).
- **Combat-check recalibration for 1.21.2+**: `auto-clicker`'s 20.5 CPS cap and `aim-assist`'s
  120 ms snap-max-interval are unreachable via server-validated melee on 1.21.2+ (vanilla drops
  sub-cooldown attacks server-side; landed rate ≈ 2–4/s for every player). See FALSE_POSITIVE_ANALYSIS.md
  FP-7. The checks remain unit-tested and harmless on legacy/modded stacks; their calibration needs
  re-derivation against the 1.21.2+ attack-cooldown model before they can produce live signal.
- **26.x live-harness legs**: the Paper 1.21.8 live legs do not yet cover the 26.x artifact;
  boot-verified only (Runtime smoke legs for 26.1.2/26.2/26.3).
- **Folia**: requires regionized-scheduler threading of all listeners and `PlayerData` lifecycle.
- **1.21.2 support**: blocked upstream — `paper-api` is not published for that version.
- **Performance profiling**: no CPU/heap baselines exist yet (allocation-free hot path is claimed
  from code inspection only).
- **Web dashboard / violation export API**: not started.

## Not planned
- Heuristic "JavaScript engine" config checks, or any detection shipped as a stub.
- Offline deobfuscation tooling for anticheat client mods.
