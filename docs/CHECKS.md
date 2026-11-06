# VigilAC detection reference

This document describes exactly what the built-in checks measure, how VL is computed, and why a
false positive should not occur in vanilla gameplay. Any change to detection logic must update
this file.

## Shared machinery

### Violation levels (VL)

Every check adds VL through the same pipeline. VL accumulates per (player, check), is capped at
`checks.<name>.max-vl`, and decays by `violations.decay-per-second` per second once
`violations.decay-delay-seconds` has elapsed since the last violation.

Thresholds per check:

| Key | Meaning |
|---|---|
| `alert-vl` | staff alerts begin |
| `warn-vl` | staff alert + in-game warning to the player |
| `kick-vl` | kick-level verdict (only acted on with `mode: punish`); `<= 0` disables |
| `max-vl` | hard cap on accumulated VL |

A check can therefore never produce an action from a single event: evidence must accumulate and
outpace decay.

### Confounder gates (movement)

`PlayerMoveListener` and `ConfounderListener` cooperate:

- **Teleports** (`PlayerTeleportEvent`, or a single move event > `confounders.teleport-threshold`
  blocks) suspend speed detection for `teleport-grace-seconds`.
- **Velocity / knockback** (`PlayerVelocityEvent`) suspends it for `velocity-grace-millis` scaled
  by impulse strength (capped at 2s).
- **Vehicles** and **elytra** are exempt unconditionally (movement speed is not the player's own).
- **Liquids** are exempt (swimming movement differs).
- **Low TPS**: below 10 TPS the check disables itself rather than blame players for server lag.
- **Rotation-only events** (no position change) skip all work — zero cost.

## `speed` — sustained horizontal speed

**Signal.** For each move event with a position change:
`instant = |Δxz| / Δt`. Frame-to-frame jitter is smoothed with an exponential moving average
(`α = 0.35`): `ema ← ema + 0.35·(instant − ema)`.

**Allowed speed.**

```
cap = (sprinting ? sprint-cap : walk-cap)      # default 5.8 / 4.4 blocks/s
cap *= 1 + speed-potion-bonus · speedLevel      # Speed I..X, +20% each by default
if on ice/slime family: cap *= slippery-multiplier   # default 1.6
```

Vanilla reference points: walking 4.317, sprinting 5.612 blocks/s. On a fast server the defaults
leave ~2-4% headroom plus potion/ice compensation.

**Evidence accumulation.** While `ema > cap`, the check adds the event's `Δt` to a *sustained
excess* accumulator; otherwise the accumulator drains twice as fast as it fills. VL is only added
once the accumulator exceeds `min-sustain-seconds` (default 1.2s), and the amount per event is:

```
add = excess-vl-per-second · Δt · min(2, (ema − cap)/cap)
```

With defaults, a blatant flyer at +50% over cap accrues ~2 VL/s after the sustain gate opens.
A 1-tick rubber-band or a single fast packet adds **zero** VL.

**Known limits.** This check covers horizontal speed only. Hovering, vertical fly, and timer
(fast packets) are separate modules (roadmap). Water velocities, riptide tridents, and levitation
are not yet compensated and can share the liquid exemption path.

## `reach` — melee attack distance

**Signal.** On every `EntityDamageByEntityEvent` (main thread, authoritative), distance from the
attacker's eye position to the closest point on the target's axis-aligned bounding box
(derived from `Entity#getWidth()/getHeight()`), the exact quantity the vanilla client uses for
hit validation:

```
distance = |clamp(eye, box) − eye|  (component-wise max(0, boxMin−e) ∪ (e−boxMax))
```

**Evidence gates.** An attack is *over-reach* when `distance > max-reach + tolerance`
(default 3.0 + 0.03). VL is added only when **both** hold:

1. at least `min-attacks-over` (default 4) **consecutive** over-reach attacks, and
2. the linearly-weighted average of the last `ema-samples` (default 6) attacks exceeds the allowed reach.

Then `add = min(2, vl-per-attack-over · (1 + excess))`.

Any attack inside the cap resets the consecutive counter.

**False-positive analysis.**

- Latency does **not** raise reach: the server evaluates positions at packet receipt time, and a
  latency-lagged attacker is processed at a position no closer than when they swung; the client-side
  advantage is self-defeating here. No ping-based slack is added (0.03 covers float error only).
- Creative/spectator are exempt (flight makes 3.0 meaningless); attackers in vehicles are exempt.
- High-latency attacks teleport-perceived *closer*, so latency exonerates rather than incriminates.
- Entity boxes scale by type (a zombie cube is bigger than a player's), so small mobs are still
  correctly measurable against their own box.

**Known limits.** Hitbox interactions for very small entities (e.g. baby zombies at range) sit
close to the tolerance band on low TPS — watch `vigilac.debug` output before lowering `tolerance`.
