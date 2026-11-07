# Phase 7 — Blockers

Date: 2026-11-07. Phase 7 targets the audit findings in `docs/ARENA_INDEPENDENT_AUDIT.md`
(AUDIT-01…17). This document lists **what is blocked, what remains unverified, and why** —
before the deliverable claims in `docs/PHASE_7_IMPLEMENTATION.md`.

`IMPLEMENTED` means the code is in this repository — it does **not** mean the
code compiled or behaved. Nothing here is `CI_VERIFIED` (no CI run for these
heads yet) or `REAL_CLIENT_VERIFIED` (impossible here).

## 1. Build & test environment — BLOCKED

- This environment ships **no JDK** and has no reachable network for a JDK or
  Gradle toolchain download. `./gradlew` was **not** executed for Phase 7.
- Consequently: **zero Phase 7 file has been compiled, and zero Phase 7 test
  has been executed.** All edits are compile-UNVERIFIED. CI (`Build and test`
  matrix + 15 compat legs) is the only compile/test gate.
- The new/modified unit tests were written to the existing JUnit 5 idioms
  (compile-checked by eye and by a structural brace/paren count only).

**To unblock:** push `arena/a7154a72-vigilac` (authorization required — see §5),
let CI run `Build and test` + the 15 compat legs, fix fallout in follow-up
commits. Until then, every Phase 7 artifact stays UNVERIFIED.

## 2. Real-client validation — BLOCKED (unchanged)

`REAL_CLIENT_VALIDATION = BLOCKED` for all checks, for the same reasons as
Phase 6 (`docs/REAL_CLIENT_VALIDATION.md`): no display/GPU, no Minecraft
launcher, no Mojang account, no reachable client artifacts.

Mandatory acceptance items that therefore **cannot be evidenced in this repo**:

- Reach detection against a real client escape at >3 blocks and against a real
  1.21.11+ spear (attack_range component, radius > 3.0) on 1.21.11 and 26.2.
- Behavior of the new attack-attempt pipeline on a live server with
  packetevents installed (queue throughput, drain latency, overflow behavior
  under packet floods).
- No-fall correlation timing, jesus riptide/wind-burst windows, step
  slippery-bounce exemption, auto-clicker/kill-aura/aim-assist thresholds on
  real PvP cadence (all frequency claims remain UNVERIFIED).
- FP rate of the FastBreak tool-tier model on real mining with real tools
  (iron-on-stone at 400 ms intervals is implemented as the legal reference —
  it has not been replayed on a live client).
- Fold Craft Launcher (mobile/touch) FP sweep for the raised 25 CPS cap.

No Phase 7 document may claim any of the above.

## 3. Unverified component & data claims

| Claim / input | Status | Consequence |
|---|---|---|
| `attack_range` data-component value of any 1.21.11+ weapon (incl. spears) | **UNVERIFIED** — no value read from a real item; component read is attempted at runtime with safe fallback | Reach falls back to configured 3.0 cap (+ tolerance). Spear reach detection is inert-by-construction for now; never guessed, never 4.5-by-default |
| Copper-tier tools (introduced 1.21.x, supported since 1.21.5 era) | **UNMAPPED deliberately** | `BreakTimeModel.ToolTier.forMaterial` returns `null` → timing verdict suppressed for correct-tool digs (never flagged). Wrong-tool digs still decidable |
| Enchantment max levels (Haste IV / Mining Fatigue IV supported; Sap without tool) | **UNVERIFIED against a live 26.2 client** | Caps applied vanilla-idiomatically from known behavior (≥1.16); real mismatches only change how far off-cycle timing evidence may land, never direction (suppression remains safe) |
| Aqua Affinity / 13-tick Haste guard | Not modelled | Conservative by omission: water + no Aqua ⇒ ÷5 applies and digs still accrue *credit* the usual way; vanilla Aqua Affinity removes only the water penalty on recognized tools |
| `canHarvest` semantics (correct family ⇒ fast divisor, else slow divisor) | Modelled as `correctTool ? 1.5 : 5.0` (= vanilla 30/100 × 20 ticks) | Approximation of vanilla's real canHarvest (item vs block hardness table); wrong-family-but-correct-stone-style exceptions (e.g. axe on logs) behave correctly because family match == effective tool in vanilla for every vanilla pair |
| packetevents ATTACK/INTERACT_ENTITY/PUNCH packets on 26.x | API pinned to v2.14.0 (unchanged from Phase 6); runtime **NOT_VERIFIED** | Same degradation story as Phase 6: telemetry inert without the plugin; reach attempt pipeline drains on the main thread |
| Mace Wind Burst / Riptide triple-velocity arcs in 26.2 | Physics constants from vanilla source knowledge, not re-derived on 26.2 build | Windows (3 s grace, vertical-dominant dy>0.45 & >60% axis share) are bounded and revert under no circumstance-never-permanent; misfires can only extend grace, never hide VL evidence (grace only suppresses fall/lift checks) |

## 4. Environment blockers affecting docs

- No `mmo items`/attack-range introspection was possible (no server, no client).
- `CHECKS.md` updates were written from the source, not from live runs.
- `PERFORMANCE_BENCHMARK.md` §Phase 7 states **no measurements exist** for the
  Phase 7 hot-path additions (attack-attempt queue is O(1) amortized; no soak
  test has ever run). Engineering bounds are stated as design intent, not
  results.

## 5. Branch & push status

- Work is confined to `arena/a7154a72-vigilac` (HEAD: `0d9b94c`).
- **No push has been made for Phase 7** and none will be without explicit
  user authorization. `git ls-remote origin arena/a7154a72-vigilac` returns
  empty — the branch exists locally only. No PR is open for Phase 7.
- The Arena session is bound to this branch; `main` is untouched. History has
  **not** been rewritten; no force-push, no `reset --hard`, no dropped commits.

## 6. Severity assessment vs Phase 7 acceptance criteria

| Phase 7 acceptance item | Status |
|---|---|
| Reach: packet-level attempt pipeline distinct from damage; thread-safe hand-off | IMPLEMENTED on this branch; CI + real-client verification pending |
| Reach: item-specific range, unknown ⇒ conservative | IMPLEMENTED (attack_range component read; unknown ⇒ 3.0 + tolerance; no invented values) |
| Reach: not convicted on single hit; EMA + streak gates | IMPLEMENTED (carried from Phase 6, range source added to evidence) |
| Reach: regression tests | IMPLEMENTED (ReachCheckTest additions: vanilla-3.0 cap, jitter, attempt evidence, component-preferred cap) |
| Step: bounded bounce state machine, not a global exemption | IMPLEMENTED (commit `1ca599b`) |
| Jesus: riptide/wind-burst bounded evidence-based exemption | IMPLEMENTED (resets streak; riptide isRiptiding + vertical-dominant impulse ≤3 s) |
| AimAssist: angular-step max per sample; Summing rejected | IMPLEMENTED (RotationTracker.maxDeltaInWindow + test) |
| AutoClicker: rate alone never convicts | IMPLEMENTED (rate ⇒ requires regularity corroboration; cap 25) |
| KillAura: anchor + attempt-rate alone never convicts | IMPLEMENTED (gate = anchor ∧ (metronomic ∨ swingless); attempt rate only scales VL) |
| FastBreak: full vanilla tool-tier model; NOT weakened; regression tests | IMPLEMENTED (BreakTimeModel + 14 formula-contract tests + 7 tier regression tests; wrong-tool/unbreakable/instant paths untouched) |
| NoFall: honest framing; correlation advisory only; per-factor windows | IMPLEMENTED (2/1.5/1.5/2 s) |
| ML: advisory only; no invented metrics; human-CV prior deleted (AUDIT-17) | IMPLEMENTED |
| Docs: strict status labels, blockers list, honest ML scope | IMPLEMENTED (this file + PHASE_7_IMPLEMENTATION.md + matrix/benchmark updates) |
| Real-client verification | **BLOCKED** — see §2 |
| 26.2 / 1.21.11+ spear ranges validated | **BLOCKED / UNVERIFIED** — conservative by construction |
| Commits reach → step → P1 → docs; no force-push | reach `a4c732d`, step `1ca599b`, P1 `0d9b94c`, docs (this commit) |
