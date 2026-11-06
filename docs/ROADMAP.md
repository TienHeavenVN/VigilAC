# VigilAC roadmap

Shipped today: framework, VL/decay, async SQLite audit, alerts/warnings/kicks, SpeedCheck,
ReachCheck, `/vigilac` command tree. Everything below is **not implemented**; no stubs are present
in the codebase.

## Movement
Fly, AirWalk, NoFall, Jesus, Spider, Step, Timer, invalid-movement (prediction-vs-simulation).
Groundwork that already exists: `DetectionContext` (ground/liquid/vehicle/elytra/slippery),
`MovementTracker`-equivalent per-player state in `PlayerData`, teleport/velocity grace gates,
low-TPS gate. A proper Fly check will compare vertical motion against expected jump/fall physics
with the same sustain machinery as SpeedCheck.

## Combat
KillAura, AimAssist, AutoClicker, Criticals, AntiKnockback, Velocity. ReachCheck's attack-AABB
distance and streak machinery generalizes (AttackContext is reused).

## Player
Scaffold, FastBreak, FastPlace, InventoryMove.

## Packet
Timer (packet-rate analysis), packet-spam, invalid sequences. packetevents is already a
compile-time/soft dependency and packet-rate counters surface in `/vigilac info`; a real Timer
check will use those statistics with a sustained-excess gate like the movement checks.

## Compatibility notes
- Folia: unsupported (legacy scheduler; no region threading implemented or tested).
- Purpur: untested; expected compatible.
