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

**Known limits.** This check covers horizontal speed only. Hovering, vertical fly (`fly`) and
fast packets (`timer`) ship as separate modules; see below. Water velocities, riptide tridents,
and levitation are not yet compensated and can share the liquid exemption path.

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

## `fly` — airborne flight and hover

**Signal.** Airborne periods open on the first move observation with `onGround=false` and close on
ground contact or any movement-modifier state. Two signatures accrue over the period:

- **Hover**: `|net vertical displacement| <= hover-tolerance` (default 0.6 blocks) accumulated
  over at least `min-hover-millis` (default 3000) of continuous airtime. Vanilla cannot produce
  this without Slow Falling or Levitation (both exempt).
- **Rising flight**: `min-upward-ticks` (default 10) consecutive positive-vertical move
  observations with net upward displacement. Jumps reset on any downward tick and never sustain
  10 consecutive upward ticks.

**Evidence.** VL accrues only past the airborne window: `vl-per-observation` scaled by up to 2×
for stronger patterns. Ground contact, vehicle/elytra/liquid entry, teleport, velocity or potion
resets the accumulator entirely — an exempted period can never leak into a flag.

**False-positive analysis.** Jumps peak at ~1.3 s (< 3 s window). Terminal falls never flag
(descent exceeds the tolerance band). Slime/honey bounces travel upward but are exempted via the
slippery feet-block flag. Knockback trajectories look like hover/rise for 1-2 s — knockback grace
suppresses the check for its full duration.

**Known limits.** Elytra rockets are exempt; a hovering *elytra* with rockets off gliding mid-air
is not distinguished from creative fly. Firework-boosting elytra in survival can match the rising
signature once INSIDE the elytra grace — flight with rockets in short bursts between packet
batches may evade both fly and speed checks. Water columns and bubble columns read as liquid and
are exempt.

## `timer` — movement-packet rate

**Signal.** Movement observations per second computed over the last `window` of move observations
tracked per player (`timer-window-millis` windowing in the tracker). Vanilla sends position packets
at 20/s combined with rotation; the check engages only when the sustained rate exceeds
`threshold-per-second` (default 21.5) for `min-excess-millis` (default 3000) continuously — brief
bursts (use-item packets, vehicle spawn batches) never accumulate.

**Evidence.** While the rate stays above threshold, `excess-vl-per-second` (default 2.0) accrues
scaled by the excess fraction. Dropping below the threshold drains the accumulator.

**False-positive analysis.** High-TPS servers (40/60) send proportionally more movement packets —
the threshold is absolute packets/second so the check only engages on bona fide packet-rate
multiplication (1.05×–2× timer). Anticheat-aware obfuscation sending 1.04× timer under a 20.8/s
observation average is indistinguishable from jitter; that band is intentionally unflagged.

## `no-fall` — landing damage forgery

**Signal.** Each airborne period accumulates descent (`Σ -Δy` across downward observations).
On the landing observation, descent ≥ `min-fall-distance` (default 3.5, above vanilla's 3-block
damage line) is evaluated as *evidence*. The evidence is void when any damage event landed within
`damage-grace-millis` (default 500) before the landing (fall-damage suppresses streaks entirely).

**Evidence.** A landing with descent ≥ 3.5 and no damage in grace adds one to a suspicious-landing
streak; vl-per-landing (default 1.5) accrues once the streak reaches `min-suspicious-landings`
(default 3). Any legal landing or damage event resets the streak. Descent accumulated during
Slow Falling / Levitation / other exempt states is discarded, never attributed.

**False-positive analysis.** Webs, hay bales, slime (reduced/zero fall damage) and water landing
are *liquid/exempt adjacent* but note: vanilla deals no damage in webs while the anti-cheat still
sees a fast landing — web landings are indistinguishable from forged no-fall landings without
block-context; this is a known limitation (below). Slow Falling / Levitation are fully exempt.

**Known limits.** Webs, hay bales, honey and slime-block landings inflict little or no fall
damage in vanilla while showing full descent — the check flags after 3 such landings even for
honest players. Servers using these blocks heavily should raise `min-suspicious-landings` or lower
`vl-per-landing`. Full block-context exemption is roadmap.

## `invalid-movement` — physics impossibilities

**Signal.** Two unconditional-impossibility signatures per move observation:

1. **Non-finite geometry** — any NaN/Infinity coordinate or delta. Never legitimate.
2. **Vertical travel above terminal velocity** — `|Δy|` per observation exceeding
   `max-vertical-delta` (default 4.25 blocks/tick; vanilla terminal velocity is 3.92).

**Evidence.** Each occurrence adds `vl-per-impossibility` (default 4.0). No sustain required —
an impossibility IS the evidence. Teleport grace short-circuits all evaluation.

**Known limits.** Max fall distance per tick rises with potion jump boost? No — vertical *upward*
travel is what jump-boost raises (max ~1.36 blocks/tick with Jump Boost X, far under 4.25). The
4.25 bound is safe. Pistons and explosions displace entities horizontally/vertically at up to
~1 block/tick — still under the bound.

## `kill-aura` — multi-target attacks

**Signal.** On each player-damaged entity event, the target UUID and timestamp are pushed into the
combat tracker. Distinct target UUIDs inside `window-millis` (default 1000) are counted.

**Evidence.** `min-targets` (default 3) distinct entities damaged inside the window produce a
trigger worth `vl-per-trigger` (default 1.0) VL. Arcs and splash-potion damage are excluded
(only EntityDamageByEntityEvent with a player attacker counts).

**False-positive analysis.** Legit PvP cannot swing more than ~1.6×/s at full attack damage; even
cooldown-free spam cannot split aim across 3 separate entities within 1 s without the client
seeing each distinct target — 1.21 combat spam-clicking one target registers 1 distinct entity,
not 3. Leaked multi-click on the SAME entity never counts.

**Known limits.** Multi-mob farming in the same 1 s window with sweeping-edge swords can approach
2 distinct nearby entities (sweep hits up to 1 target beyond the main one) — default `min-targets: 3`
remains clear of this. Logs of sweeps mark multiple entities damaged by the SAME swing; server-side
the sweep is one EntityDamageByEntityEvent per victim, hence distinct targets — raising
`min-targets` to 4 is the safe tuning for heavy sweep builds.

## `auto-clicker` — inhuman CPS or rhythm

**Signal.** The combat tracker records every attack with timestamps. CPS is computed over the
trailing `window` (default 20) attacks as `count / span` (span-capped at the window), which is
meaningful at any attack rate.

**Evidence.** Two gates, either sufficient for VL:

1. **Ceiling**: CPS above `cps-cap` (default 20.5, hardware ceiling ~20) with at least
   `min-samples` (default 10) recorded attacks.
2. **Metronome**: mean interval below `robotic-mean-max` (default 150 ms) with standard deviation
   below `robotic-stddev-max` (default 12 ms) sustained for `robotic-min-streak` (default 5)
   qualifying windows of `robotic-min-samples` (default 10). Human variance is 40-120 ms stddev;
   clickers converge on 0-10 ms.

**False-positive analysis.** Butterfly clicking and jitter clicking rarely exceed ~18 CPS and
never hold stddev < 12 ms over 50+ clicks; the gates are stacked precisely so legitimate burst
spam is invisible and only sustained inhuman rhythm accrues VL.

## `aim-assist` — rotation teleports snapping to targets

**Signal.** Each attack records the attacker's yaw/pitch. On consecutive attacks, the angular
rotation delta and the target bearing are compared: a **snap** is a rotation delta above
`snap-angular-delta` (default 45°) where the second attack's aim lands within
`snap-angle-to-target` (default 6°) of the target's center, and the interval between the two
attacks is below `snap-max-interval-millis` (default 120 ms).

**Evidence.** `min-snap-streak` (default 4) consecutive snaps each add `vl-per-snap` (default 1.0)
VL. A swing that resets onto a target after a legitimate look-turn (>120 ms, or attack after a real
mouse sweep across the screen) never qualifies.

**False-positive analysis.** Human players produce occasional 45°+ flicks — the streak gate exists
for them. Flick-aiming at exactly one new subject per swing with no decay is the cheat pattern.

## `scaffold` — bridging blocks out of view

**Signal.** On block placement, the angle between the player's look vector and the direction from
the eye to the placed block is computed. Placements above `max-angle-degrees` (default 80°, i.e.
behind/beside the player) are *out-of-view* bridges. Consecutive out-of-view placements increment a
streak tracked in the block-action tracker.

**Evidence.** `min-streak` (default 6) consecutive out-of-view placements add `vl-per-placement`
(default 1.0) VL per placement beyond the streak.

**False-positive analysis.** Legit bridges look *down* (+90° pitch) — but a placement 80° off the
look vector while staring at the horizon or straight up is not a bridge any vanilla player can
reliably produce. Splash-potion bridging is unaffected (no placement occurs). ML scaffold disengage
patterns typically alternate look direction; the streak requirement filters single assisted
placements.

## `fast-place` — inhuman block placement rate

**Signal.** Placement timestamps in a trailing `window-millis` (default 2000) window; rate above
`max-per-second` (default 7.0) while vanilla's 1.18+ block-use cooldown (4 ticks = 5/s) caps honest
placement at 5/s — 7 tolerates seed-batching and lag bursts.

**Evidence.** Every placement above the ceiling adds `vl-per-placement` (default 1.0) VL.

**False-positive analysis.** Piston-feed block placement, dispensers and legitimate inventory
management placements are below 5/s. Bridge speedrunners peak ~6-7 placements/s with practice —
the 7.0 ceiling flags only bursts past it, so borderline speedrunners may occasionally accrue VL
(and will see it decay); tune `max-per-second` up for speedrun servers.

## `fast-break` — breaking below the vanilla hardness bound

**Signal.** Each completed break carries the vanilla lower-bound duration computed from block
hardness, held-item correctness, Efficiency level and Haste amplifier (the same inputs vanilla
uses): `t = hardness × 1.5 ÷ speedMultiplier` with speedMultiplier = 1 for bare hands against
non-matching blocks (×5 penalty), `1 + (eff+1)²` with the right tool, `×(1 + 0.2·haste)`.

**Evidence.** Completions faster than the bound (one tick leeway, 50 ms) are *impossible*.
`min-impossible-streak` (default 3) consecutive impossible breaks before VL accrues
(`vl-per-trigger` default 1.0, scaled by how far below the bound). Server-reported unbreakable
material (hardness < 0, e.g. bedrock) completing is unconditionally `unbreakable-vl`
(default 6.0).

**False-positive analysis.** Efficiency V + Haste II on stone is ~33 ms — inside the leeway band,
never flagged. Wrong tool on stone is ~1725 ms. Instant-break materials (hardness 0) carry no
timing signal and are skipped. Piston-broken blocks emit no BreakEvent from the player
(piston-caused drops do not fire PlayerInteractEvent/BlockBreakEvent for the player), so
ace-of-spam is not a false trigger.
