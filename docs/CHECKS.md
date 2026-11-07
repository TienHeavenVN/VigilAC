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

- Latency neither exonerates nor convicts by fiat. For a *stationary* target, a latency-lagged
  attacker is evaluated at the position the server last knew, which is generally not closer than at
  swing time. For a **moving** target the receipt-time distance can be *larger* than the swing-time
  distance (e.g. the target strafing away at 5.6 b/s adds ~1.7 apparent blocks at 300 ms ping). The
  symmetric case shrinks apparent distance, comfortably clearing the attacker. The old claim
  "latency can only shrink reach" was backwards and is corrected here (Phase 7 / AUDIT-08).
- Phase 7 adds packet-level evidence separate from damage events: the reach check sees
  **attack attempts** (INTERACT_ENTITY ATTACK on ≤1.21.x, ATTACK on 26.1+) alongside damage events,
  so a lagged/held attack that never registers damage no longer grants a silent free pass and is
  recorded as attempt evidence. Damage-event reach remains authoritative for VL; attempts are
  evidence + sustain the streak, and unknown reach items never convict (conservative).
- Reach limit is **item-specific** on 1.21.11+ (weapon attack-range component): unknown or
  unexpected component values fall back to the configured cap (default 3.0), never above it —
  no component value is ever invented. Spears on 1.21.11+ are therefore NOT automatically given
  4.5 blocks; until the component value is read from the real item, spear reach stays conservative.
  Real 1.21.11 component values are UNVERIFIED in this environment.
- Creative/spectator are exempt (flight makes 3.0 meaningless); attackers in vehicles are exempt.
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

**Correlation notes (Phase 7 / AUDIT-09).** The advisory `corr=` label now uses per-factor
suppression windows — teleport ≤ 2 s, velocity ≤ 1.5 s, fall damage ≤ 1.5 s, world change ≤ 2 s
(was a uniform 15 s). Advisory only; it never gates VL.

**Server-collision probe (v2, Phase 6).** On a suspicious landing the feet columns are probed for
solid blocks at the feet layer and the layer below (`BlockSupportProbe`;
`BukkitBlockSupportProbe` in production). If **no** corner column has support, the client claimed
ground over air — an unconditional `vl-per-landing`-scaled violation with collision-verified debug
evidence (`/vigilac monitor`: `probe=unsupported`), independent of the streak. A probe failure
(chunk unloaded, world error) can never convict — support is assumed instead.

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

## `jesus` — walking or standing on water (Phase 6)

**Signal.** Dry feet while the block directly below is water — vanilla-impossible
without Frost Walker (vanilla sinks the player or the feet enter the liquid).
Consecutive qualifying observations are streak-counted; ≥ `min-streak` (default 14,
i.e. ~0.7 s) ticks opens evidence, after which every qualifying observation adds
`vl-per-tick` (default 0.5). Any disqualifying tick (entering liquid, Frost Walker,
vehicle, gliding, Levitation/Slow Falling, teleport/velocity grace, TPS < 10) resets
the streak.

**False-positive considerations.** Frost Walker (any level) is a hard exemption — it
is vanilla water walking. Boats, elytra, Levitation and Slow Falling are exempt.
Swimming resets the streak (feet enter `inLiquid`), so water travel never accumulates.
Jump arcs over water: feet dip into liquid between arcs, resetting the streak —
repeated arcs cannot reach 14. Standing still above water is equally impossible in
vanilla and is flagged by the same rule.

**Riptide / Wind Burst (Phase 7 / AUDIT-04).** Bounded, evidence-based exemptions:
the check resets (not just suspends) its streak while the server reports the player is
`isRiptiding`, and while a **vertical-dominant** impulse (|vy| > 0.45 blocks/tick and
> 60 % of horizontal speed) is fresh — set from the velocity listener, window ≤ 3 s,
and **never** set from lateral knockback. Both revert exactly like entering liquid; no
general water bypass was added. Wind-burst grace carries into Jesus, NoFall streak
logic, and Step, mirroring the mace 1.21+ mechanics.

**Known limits.** Waterlogged top slabs read as WATER material at the
below-block lookup; standing on one can supply surface ticks. The 14-tick gate keeps
this from firing on its own. A frost-walker switch-off (boots removed mid-stream)
starts counting from zero — harmless.

## `step` — silent full-block climb (Phase 6)

**Signal.** A single-tick upward delta above `max-legal-delta` (default 0.95 —
vanilla auto-step is 0.6; 0.95 clears jitter and Jump Boost ≤ IV). Two CONSECUTIVE
impossibilities (`min-streak`, default 2) are required before VL accrues, each adding
`vl-per-step` (default 2.5). A single legal/ambiguous tick resets the streak.

**False-positive considerations.** Pistons are covered by the piston confounder
(velocity grace resets the streak every tick a piston corridor player is displaced).
Levitation, vehicles, elytra and teleports are exempt. Stairs/half slabs (0.5),
carpet (0.125) are far under the threshold.

**Slime / honey bounces (Phase 7 / AUDIT-05).** Vanilla slime-block bounces exceed
0.95 for three consecutive ticks (`v = sqrt(2·0.08·h)`, e.g. h = 6 ⇒ 0.98 ≈ 1.13, 1.05,
0.97), so the old "one streak tick per bounce" claim was wrong. Instead of exempting
all slippery movement forever, the check runs a **bounded evidence state machine** on
slippery ground: the first three qualifying ticks accumulate bounce evidence (dy
decaying 1.13 → 1.05 → 0.97), a 4th qualifying tick without another fall resets and
counts as normal evidence; sustained upward motion on slippery ground beyond the decay
pattern is machine-like and still accrues VL. Non-slippery full blocks are unaffected.
The JB VI ceiling is likewise pinned by a unit test (single arc ticks reset at the apex).


## `kill-aura` — multi-target attacks (v2, Phase 6)

**Signal.** Independent evidence streams are combined (never a single trigger):

1. **Anchor** — on each player-damaged entity event, target UUIDs inside `window-millis`
   (default 1000) are counted; ≥ `min-targets` (default 3) distinct entities is the anchor.
2. **Metronomic cadence** — ≥ 6 attack intervals on record with stddev < `metronomic-stddev-max`
   (default 15 ms). Independent of who the targets are.
3. **Attempt rate** (packetevents) — attack packets per second over the trailing 1 s, ≥
   `min-attempt-rate` (default 8.0/s). Includes misses — the damage stream never sees them.
   Inert when packetevents is absent.
4. **Swingless attacks** (packetevents) — ≥ 6 attempts and attempts > 2× arm swings (a client
   attacking without swinging). Inert when packetevents is absent.

**Evidence (v3, Phase 7 / AUDIT-16).** VL accrues only when the anchor holds AND
(`metronomic` OR `swingless`). Signal 3 (attempt rate, ≥ 8/s) contributes to the
`vl-per-trigger` multiplier (`min(2, 1 + 0.5·(signals−2))`) but **never** satisfies the
gate by itself — anchor + high-rate damage to farm-style partners cannot convict.
Anchor alone never flags; anchor + attempt rate alone never flags (pinned by test
`anchorPlusAttemptRateWithSwingsDoesNotFlag`). The plain "3 distinct entities in 1 s"
verdict from Phase 5 **no longer exists**.

**False-positive analysis.** Mob farms cannot supply regularity (human farm clicks are noisy) nor
8+ attacks/s sustained against distinct entities at cooldown-limited damage rates. Sweep-edge
multiswings produce 2 distinct entities per swing; an anchor requires ≥ 3. Legit rapid single-target
DPS never accrues an anchor.

**Known limits.** Attempt-rate and swingless signals need packetevents installed at runtime (not
present in CI). Without it, only cadence corroborates the anchor — slightly weaker, and still
multi-signal. 3-target anchors with deliberately varied human-like cadence defeat the cadence
corroborator (by design).

## `auto-clicker` — inhuman CPS or rhythm

**Signal.** The combat tracker records every attack with timestamps. CPS is computed over the
trailing `window` (default 20) attacks as `count / span` (span-capped at the window), which is
meaningful at any attack rate.

**Evidence (Phase 7 / AUDIT-06).** Two gates:

1. **Ceiling (machine-corroborated)**: the stronger of damage-CPS and packetevents attempt-rate
   above `cps-cap` (default **25.0** — butterfly/jitter and Android multi-finger tapping are
   documented at 20–30 CPS, well above the old 20.5 default) **and** stddev below
   `robotic-stddev-max` (documented human tapping is noisy; a flat cap never convicts on rate).
   The attempt-rate half covers uncharged spam whose damage events vanish under the 1.21.2+
   server cooldown.
2. **Metronome**: mean interval below `robotic-mean-max` (default 150 ms) with standard deviation
   below `robotic-stddev-max` (default 12 ms) sustained for `robotic-min-streak` (default 5)
   qualifying windows of `robotic-min-samples` (default 10). Debug output includes interval CV and
   Shannon entropy (telemetry only, never gates).

**False-positive analysis.** Butterfly clicking and jitter clicking rarely exceed ~18 CPS and
never hold stddev < 12 ms over 50+ clicks; the gates are stacked precisely so legitimate burst
spam is invisible and only sustained inhuman rhythm accrues VL.

## `aim-assist` — rotation teleports snapping to targets (v2, Phase 6)

**Signal (Phase 7 / AUDIT-03/07).** Each attack records the attacker's yaw/pitch. A **snap**
is the single **largest rotation burst** (hypot of consecutive-samples' angular delta — the
sum is deliberately no longer used) above `snap-angular-delta` (default 45°) within the last
`snap-max-interval-millis` (default 120 ms), where the attack's aim lands within
`snap-angle-to-target` (default 6°) of the **nearest point of the target's bounding box**
(not its centroid — enderman legs qualify). The attack-rate interval in debug output and the
fallback path are computed from `previousAttackAtMillis` captured **before** the attack is
recorded — the old `lastAttackAgeMillis(now)` read was always ~0 (AUDIT-07). Flick-and-return
motion yields 2× burst evidence but never accumulates toward the gate (pinned by
`RotationTrackerMaxDeltaTest#flickAndReturnDoesNotAccumulate`).

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

## `fast-break` — breaking below the vanilla hardness bound (v2, Phase 6)

**Signal (Phase 7 / AUDIT-01/14/15).** Two paths, both now using a shared pure vanilla
break-time model (`BreakTimeModel`):

* Tool mining speeds are modelled: wood 2 / stone 4 / iron 6 / gold 12 / diamond 8 /
  netherite 9. `destroySpeed = toolSpeed + (eff>0 ? eff² + 1 : 0)` (Efficiency bonus only
  with a correct/effective tool, cap eff V), then × `(1 + 0.2·Haste)` (cap IV),
  × `0.3^Fatigue` (cap IV), × 0.2 underwater without Aqua Affinity, × 0.2 off ground.
  Wrong family ⇒ fixed 5× divisor (never suppressed). Cost: `millis = hardness ×
  (canHarvest ? 1.5 : 5.0) / destroySpeed`.
* `BreakTimeModel.forMaterial` maps held-item names to tiers; **unmapped/unknown correct tools
  (hand, modded, copper-era) suppress the timing verdict entirely** — no bucket credit, no
  streak, no VL (never an invented speed). Wrong-tool and unknown **family-for-block** cases
  never suppress.
* **Path A — digging lifecycle (packetevents installed).** Break matched to the server-observed
  dig start→finish. Deficits credit a decaying penalty bucket (half-life 3 s); VL needs the
  bucket to exceed `penalty-grace-millis` (default 1500 ms). Tool/destroy-direction changes ⇒
  inconclusive, no timing verdict, debug evidence only.
* **Path B — fallback (no packetevents).** Completion-interval heuristic with the same model:
  completions faster than the floor are *impossible*; `min-impossible-streak` (default 3) before
  VL (`vl-per-trigger` default 1.0).
* Creative/spectator breaks are skipped (instant in vanilla — no survival evidence exists).

**Evidence.** Server-reported unbreakable material (hardness < 0, e.g. bedrock) completing is
unconditionally `unbreakable-vl` (default 6.0).

**False-positive analysis.** Efficiency V + Haste II on stone is ~46 ms (speed 35 × 1.4,
t = 2.25/49 s) — inside the leeway band, never flagged. Wrong tool on stone (hand) is
`hardness × 5.0 / 1.0` = 7.5 s — easily diagnosable, never suppressed. Instant-break materials
(hardness 0) carry no timing signal and are skipped. Piston-broken blocks emit no BreakEvent from the player
(piston-caused drops do not fire PlayerInteractEvent/BlockBreakEvent for the player), so
ace-of-spam is not a false trigger.
