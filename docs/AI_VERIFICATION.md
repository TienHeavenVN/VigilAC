# VigilAC AI-Assist — Verification Report

**Date:** 2026-11-07 · **Branch:** `arena/a7154a72-vigilac` · **Base:** `main` @ `70b8853` (Phase 7 tip `631888a`)

Honesty contract: this document contains only what was actually measured. Where something was not measured, it says NOT MEASURED. `REAL_CLIENT_VERIFIED` is never claimed.

## Per-stage status

| Item | Status | Notes |
|---|---|---|
| Feature schema + extraction from existing trackers | IMPLEMENTED, TESTED, CI_VERIFIED* | *CI status of the push containing this doc — see "CI runs" below |
| Feature validation (count/finite/range/empty-signal) | IMPLEMENTED, TESTED | `FeatureValidatorTest` |
| Isolation Forest trainer (seeded, deterministic) | IMPLEMENTED, TESTED | `IsolationForestTrainerTest` |
| Model artifact round-trip (train→write→read→score equality) | IMPLEMENTED, TESTED | `IsolationForestModelTest` |
| Manifest parsing/checksum/schema-hash/plain-name rules | IMPLEMENTED, TESTED | `ModelManifestTest` |
| Shadow worker pool (bounds/drop/timeout/stop/latency EMA) | IMPLEMENTED, TESTED | `AiShadowWorkerPoolTest` |
| Evidence correlator (tiers, ML-only cap, FP downgrade, `ai-shadow` exclusion) | IMPLEMENTED, TESTED | `AiEvidenceCorrelatorTest` |
| Admin assistant (offline templates; LLM optional, no provider shipped) | IMPLEMENTED, TESTED | `AdminAssistantTest` |
| AI report rendering (limitations footer mandatory) | IMPLEMENTED, TESTED | `AiReportBuilderTest` |
| `/vigilac ai` command + `vigilac.ai` permission | IMPLEMENTED, NOT RUNTIME-TESTED | Requires a live server; no server was available in this environment |
| ONNX adapter load with runtime absent | IMPLEMENTED, TESTED (absence path) | `OnnxRuntimeAnomalyModelTest` |
| ONNX end-to-end inference with a real model | **UNVERIFIED** | No `.onnx` artifact exists; never executed |
| Trained model quality (any metric) | **NOT MEASURED — no dataset** | Stage 3 PARTIAL; zero accuracy claims made |
| Production/real-client validation | **BLOCKED** | Requires live servers + consenting clients |
| Performance on production hardware | **NOT MEASURED** | Micro-bounds are by construction (bounded queue/pool), not benchmarked |

## What was verified how

- **Unit tests** (pure Java JUnit5; Paper classes never loaded): schema stability/hash, extractor clip behaviour incl. NaN/Inf, validator rejections incl. all-zero snapshot, trainer determinism (same seed ⇒ identical forest bytes) and subsample behaviour at the 8-sample floor, artifact load failures (checksum, schema, normalization mismatch), ONNX absence ⇒ `available()==false` + reason, pool saturation/drop/timeout/stop, correlator tier matrix incl. boundary pings and fresh exemptions, assistant disclaimer/redaction, report limitations footer, `AiConfig` defaults all OFF.
- **Split protocol for any future evaluation:** group split by player, then by session within player (no adjacent windows across train/test); metrics reported only from that protocol. None performed — no dataset.
- **CI:** the push that introduced this document triggers the standard CI workflow (`.github/workflows/ci.yml`: compile + `./gradlew test`, Ubuntu runner, Java toolchain per repo config). Exact SHA + run URL are recorded under "CI runs" after completion; until then no claim of CI-greenness is made.

## Known limitations (explicit, load-bearing)

1. No trained model exists ⇒ ML shadow score is never present out-of-the-box ⇒ correlator runs deterministic-only until a model drop-in is performed.
2. ONNX I/O path (`input`/`float[1,22]`/first output ∈ [0,1]) is documented but never executed; a mismatching model degrades to `UNAVAILABLE` or inference failure, not to a verdict.
3. `Task-watchdog` timeout cannot preempt native code mid-call; it bounds accounting, and pure-Java forest inference is microsecond-scale.
4. The command/report path was compile-checked via CI only; no in-game execution was performed.
5. Redaction covers target name/UUID/IPv4; usernames of *other* players in free text are not enumerated — LLM egress stays off by default for this reason among others.
6. AI evidence (`ai-shadow`) is deliberately excluded from deterministic corroboration (no self-corroboration loops).
7. REAL_CLIENT_VALIDATION is **BLOCKED**: nothing in this repository evidences behaviour against real clients, cheat or otherwise.

## CI runs

Recorded after the push completes (docs-sync commit):

- (pending — filled by the docs-sync commit; see `git log` and the Actions tab)

## Commands used

```
./gradlew test          # CI only (no JDK in this workspace)
gh run list --branch arena/a7154a72-vigilac
gh run view <id> --json status,conclusion,jobs
```
