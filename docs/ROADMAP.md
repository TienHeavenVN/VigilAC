# VigilAC roadmap

Shipped today (all with real detection logic, tunables and unit tests):

- **Framework**: VL accumulation/decay, async SQLite audit, alerts/warnings/kicks (default
  alert-only), `/vigilac` command tree, per-check enable/disable, boot-time config validation.
- **Movement**: `speed`, `fly`, `timer`, `no-fall`, `invalid-movement`.
- **Combat**: `reach`, `kill-aura`, `auto-clicker`, `aim-assist`.
- **Player**: `scaffold`, `fast-place`, `fast-break`.
- **Compatibility CI**: per-version `paper-api` compile + unit-test matrix (see
  [COMPATIBILITY.md](COMPATIBILITY.md)); runtime smoke test booting real Paper 1.21.8 in CI.

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
  real server (current CI verifies boot/load/commands/shutdown only; in-game behavior is untested).
- **Folia**: requires regionized-scheduler threading of all listeners and `PlayerData` lifecycle.
- **1.21.2 support**: blocked upstream — `paper-api` is not published for that version.
- **Performance profiling**: no CPU/heap baselines exist yet (allocation-free hot path is claimed
  from code inspection only).
- **Web dashboard / violation export API**: not started.

## Not planned
- Heuristic "JavaScript engine" config checks, or any detection shipped as a stub.
- Offline deobfuscation tooling for anticheat client mods.
