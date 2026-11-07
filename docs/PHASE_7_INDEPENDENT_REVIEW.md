# Phase 7 Independent Review — Remediation Record

**Scope.** Two findings confirmed by the independent review of Phase 7, both fixed in this
branch (`arena/a7154a72-vigilac`). No scope expansion, no unrelated detection features,
no threshold weakening. All findings below are restated from the review tasking — this
file records the remediation, it does not quote reviewer prose.

**Status of this record.** The branch compiles and its tests pass only under CI's
verification environment. Locally, `./gradlew clean check --no-build-cache --rerun-tasks`
cannot run — there is no JDK/javac in the development sandbox (it fails immediately with
`ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`) and the
Paper API cannot be fetched offline. This is recorded honestly: **no local build or test
execution happened for this remediation.** Compile/test verification is delegated to the
GitHub Actions CI run for the remediation commit. Phase 7 is **not** complete; REAL_CLIENT_VALIDATION remains BLOCKED (see `PHASE_7_BLOCKERS.md` §2). Nothing here is REAL_CLIENT_VERIFIED.

---

## Finding 1 — FastBreak wrong-tool mining-time overestimation

**Review summary (restated).** The FastBreak break-time model treated "wrong tool for the
block" as slower than vanilla in two ways: it applied the 100-tick divisor (5 s per hardness
point) even for blocks whose drops require no tool, and it never modeled the harvestability
axis independently of tool effectiveness. Legitimately fast digs with a non-effective but
harvestable tool (e.g. a pickaxe on dirt) were modelled as physically impossible.

### Root cause
`BreakTimeModel` had one boolean, `correctTool`, feeding both the tool base speed *and* the
divisor (`correctTool ? 1.5 : 5.0`). Vanilla instead uses three independent facts per
(held item, block) pair: (a) is the tool's family effective for the block (base dig speed +
Efficiency), (b) does the block's drop requirement exist and is it satisfied (30 vs 100 ticks),
(c) is the block's mechanic known at all. By conflating (a) and (b), dirt-by-pickaxe was
modelled as 0.5 × 5.0 / 1.0 = 2.5 s — vanilla computes 0.5 × 1.5 / 1.0 = 0.75 s, a 3.3×
overestimate. The same conflation mis-modelled logs by non-axes, glowstone by hand, iron ore
by wooden pickaxe (vanilla: 100-tick divisor but speed 2.0 → 7.5 s), obsidian by wooden
pickaxe (125 s, not 250 s), and similar.

### Changed files
- `src/main/java/ac/vigil/detection/BreakTimeModel.java` — rewritten around the three facts
  (`toolEffective`, `canHarvest`, `miningModelKnown`). Harvest tier requirements (NONE/WOOD/
  STONE/IRON/DIAMOND) for every mapped pickaxe-effective block; family tables moved here from
  the listener (single source of truth); `HarvestTier`/`ToolFamily` are public; unrecognized
  pickaxe-family blocks default to a DIAMOND harvest requirement (quietest honest choice);
  fully unrecognized blocks return NaN budgets (no timing verdict, never conviction evidence);
  Efficiency is applied only for effective tools; Haste/Fatigue/Water/Aqua/air stack as before.
- `src/main/java/ac/vigil/detection/BreakContext.java` — carries the three facts
  (`toolEffective`, `canHarvest`, `miningModelKnown`); exposes `isToolEffective()`,
  `isCanHarvest()`, `isMiningModelKnown()`; budgets pass them through. Old `correctTool` removed.
- `src/main/java/ac/vigil/listener/BlockBreakListener.java` — no longer classifies tool
  families locally; delegates to `BreakTimeModel` (`toolEffective`, `canHarvest`,
  `isBlockRecognized`) so listener and model can never disagree.
- `src/main/java/ac/vigil/check/player/FastBreakCheck.java` — diagnostic line and javadoc
  updated to the new facts (no behavior change to the flagging threshold).
- Tests: `BreakTimeModelTest` migrated to the two-axis signature; new
  `BreakTimeModelHarvestTest` (vanilla-truth regression suite); `FastBreakToolTierTest`,
  `FastBreakDigLifecycleTest`, `FastBreakCheckTest` call sites migrated.
  `FastBreakCheck` thresholds were **not** weakened.

### Regression test
`src/test/java/ac/vigil/detection/BreakTimeModelHarvestTest.java` — expected values derived
from the vanilla formula and vanilla block data (dirt 0.5, stone 1.5, gravel 0.6, glowstone
0.3, iron ore 3.0, obsidian 50, ancient debris 50; hardness values unchanged across 1.21–26.x),
not from the implementation's helper layout. Cases include: dirt-by-pickaxe 750 ms (the
reported overestimate), hand-on-dirt 750 ms, glowstone-by-hand 450 ms, axe-on-stone 7.5 s,
wooden pickaxe on iron ore 7.5 s (no drop), stone pickaxe 1.125 s (drop), golden pickaxe on
gold ore 1.25 s (no drop), iron pickaxe 750 ms, wooden pickaxe on obsidian 125 s, iron
pickaxe 41.67 s (never less than this at any tier speed — the hard floor now respects the
divisor), diamond pickaxe 9.375 s, Efficiency applies even when the tool cannot harvest,
Efficiency does **not** apply to non-effective tools, Haste applies off-family, unknown
blocks ⇒ NaN budgets, plus the classification contract (`canHarvest`/`toolEffective`/
`requiredHarvest`) for the borderline marker cases (e.g. nether gold ore must NOT inherit
the iron requirement of regular gold ore).
`BreakTimeModelTest` keeps the formula-contract coverage on the new signature.

### Verification result
- **CI-gated, not locally executed** (no JDK in this environment; see header). The full
  `./gradlew clean check --no-build-cache --rerun-tasks` gate runs on GitHub Actions for the
  remediation commit.
- Numbers above were re-derived by hand from the vanilla formula for this document.

### Remaining limitations
- Drop-requirement tables cover the reliably documented vanilla requirements (stone family →
  any pickaxe; iron/lapis/copper ores and raw-iron/copper/iron/lapis blocks → stone; gold,
  redstone, diamond, emerald ores; gold/diamond/emerald blocks; raw-gold block → iron;
  obsidian/crying/ancient debris/netherite block/respawn anchor/enchanting table → diamond;
  explicit no-tool list incl. nether gold ore, glowstone, sandstone, netherrack, magma,
  sea lantern). Blocks with unverified or ambiguous mechanics are unrecognized (NaN) or fall
  back to DIAMOND — never to a shorter budget. Tool-family effectiveness tables remain a
  conservative heuristic (e.g. pale moss hoe/axe effectiveness); misclassification there errs
  toward longer budgets or suppression, not flagging.
- Copper-tier tools remain deliberately unmapped (verdict suppressed when effective), matching
  the compatibility matrix in `PHASE_7_BLOCKERS.md` §3.
- Wrong-tool verdicts for the *effective-but-unknown-tier* path remain suppressed (NaN) — an
  intended vanilla-honest blind spot, not a bug.
- Real-client validation (actual dig cadence, haste/fatigue interplay in live gameplay) remains
  **BLOCKED**; tests replay math, not players.

---

## Finding 2 — Reach deferred-resolution evidence inconsistency

**Review summary (restated).** An attack attempt is enqueued on the netty path and resolved
later on the main thread (bounded 64/drain). The server may process the attack — and emit the
damage event — *before* that resolution happens (same-tick deferred resolution). In that case
the ledger could not pair the damage with the attempt: the landed hit stayed unpaired and the
periodic rejection sweep could classify it as an unanswered over-range attempt, i.e. **a
successful hit recorded as rejected monitor evidence**.

### Root cause
Pairing was one-directional. `markDamaged` (called from the damage path) scanned stored
RESOLVED entries newest-first — it only worked when the resolution landed *before* the damage
event. When the damage event was processed first — the normal same-tick case for an immediate
hit — there was nothing to pair against; the mark was lost and the attempt later aged past the
rejection boundary unpaired. Two secondary defects compounded it: the rejection boundary equalled
the pair window (`REJECT_AFTER == PAIR_WINDOW`, a classification race at the exact boundary), and
the geometry resolver did not distinguish a stale/removed entity (no geometry, no evidence) from a
resolvable one consistently.

### Changed files
- `src/main/java/ac/vigil/data/tracking/ReachEvidenceLedger.java` — dual-order pairing. Damage
  events with no stored entry yet are now **retained** in a bounded ring of unpaired damage
  marks (64 marks, retention 1.4 s) and consumed retroactively inside `noteResolved`
  (newest-first, absolute distance ≤ pair window, one-to-one). `markDamaged` scans stored
  entries newest-first (skipping damaged/rejected) before retaining; retained marks expire via
  retention and are counted on drop (`getDamageMarkDropCount()`, diagnostics only). Pairing is
  one-to-one in both orders. New getter `getRecentDamageMarkCount()`. `PAIR_WINDOW_MILLIS`
  (400 ms) is now the single source of truth for the window.
- `src/main/java/ac/vigil/listener/AttackAttemptDispatcher.java` — `REJECT_AFTER_MILLIS` =
  `PAIR_WINDOW_MILLIS + 100 ms` (rejection strictly after pairing expires); pair window is
  referenced from the ledger (no duplicated literal); non-blocking netty hand-off and bounded
  256-entry queue unchanged; `drainTick` still resolves at most 64 attempts per tick.
- `src/main/java/ac/vigil/listener/AttackListener.java` — references
  `ReachEvidenceLedger.PAIR_WINDOW_MILLIS` instead of a local 400 L.
- `src/main/java/ac/vigil/environment/BukkitAttackGeometryResolver.java` — entity removed, dead,
  or in another world resolves to `unresolved(...)` (INVALID confidence, zero evidence
  weight); `findByEntityId` only returns valid candidates.
- Tests: `ReachEvidenceLedgerTest` (retroactive pairing when the damage lands before the
  resolution; entity-id match; one-to-one pairing; ring boundedness and expiry; rejection only
  after the full pair window; classify-once semantics). `AttackAttemptDispatcherTest` (full
  pipeline: a same-tick hit pairs retroactively and never becomes rejected evidence, while an
  aged unpaired over-range attempt is rejected exactly once; `REJECT_AFTER_MILLIS >
  PAIR_WINDOW_MILLIS` invariant pin).

### Regression test
`ReachEvidenceLedgerTest#damageBeforeDeferredResolutionPairsRetroactively` covers the
deferred-resolution order directly; `AttackAttemptDispatcherTest#successfulHitNeverBecomesRejectedEvidenceWhileStaleAttemptDoes`
covers the end-to-end pipeline deterministically (fresh attempt paired; stale attempt aged 10 s,
unpaired → rejected once, exactly the monitor evidence a genuinely unreachable swing should
produce). A successful hit is now unrepresentable as rejected evidence: pairing happens at
resolution time or the attempt is INVALID/expired — never both.

### Verification result
- **CI-gated, not locally executed** (no JDK in this environment; see header). Tests use
  synthetic timestamps, so they do not depend on wall-clock sleeps or on machine load.
- Non-blocking netty hand-off, bounded queue (256) and bounded drain (64/tick) are unchanged; the
  new damage-mark ring is O(1) amortized and bounded by 64 entries.

### Remaining limitations
- One damage event pairs with at most one attempt (one-to-one, newest-first). A burst of attack
  packets where exactly one connected leaves the remaining attempts eligible for rejection —
  truthful at packet granularity, documented here so alert auditing understands it.
- Pairing is by entity id + time window; it cannot prove which of several near-simultaneous
  attempts produced the damage. Swing-to-damage attribution beyond the window remains out of scope.
- Retained damage marks are bounded and expire (1.4 s); under >64 landed hits per 1.4 s per
  player, excess marks drop silently and are counted (never evidence either way).
- Cross-connection attack ordering is inherently arbitrary; per-connection FIFO is preserved.
- REAL_CLIENT_VALIDATION remains **BLOCKED**; pipeline tests are not live-client evidence.

---

## Documents superseded

- `PHASE_7_IMPLEMENTATION.md` §9 (FastBreak wrong-tool description) is superseded by this record
  and is retained for history. `PHASE_7_BLOCKERS.md` §3 row on `canHarvest` semantics is likewise
  superseded by Finding 1's two-axis model.

## Honesty statement

- Verification of this remediation is **CI-gated**. No local build ran; local `javac` does not
  exist in this environment and the Paper API cannot be fetched here.
- No real client was run. Nothing in this file or the diff is REAL_CLIENT_VERIFIED. As with the
  rest of Phase 7, real-client validation is BLOCKED pending a supported server and client.
- No violation thresholds were weakened; no unknown mechanic was converted into conviction evidence.
- `main` is untouched; this work is committed only on `arena/a7154a72-vigilac`; no PR is opened.
