# VigilAC AI-Assist — Verification Report

**Date:** 2026-11-08 · **Branch:** `arena/a7154a72-vigilac` · **Base:** `main` @ `70b8853` (Phase 7 tip `631888a`)

Honesty contract: this document contains only what was actually measured. Where something was not measured, it says NOT MEASURED. `REAL_CLIENT_VERIFIED` is never claimed.

## Per-stage status

| Item | Status | Notes |
|---|---|---|
| Feature schema + extraction from existing trackers | IMPLEMENTED, TESTED, CI_VERIFIED | CI run 42390581454 (`3ad0bae`), 366 tests green |
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
| ONNX end-to-end inference (real onnxruntime, synthetic fixture model) | IMPLEMENTED, TESTED, CI_VERIFIED | `OnnxRuntimeEndToEndTest` — Java score == Python onnxruntime reference within 1e-4; fixture says nothing about real cheats. First execution surfaced and fixed two adapter API mismatches (Buffer-based tensor factory; `Result` is an Iterable, not a Map) |
| XGBoost → ONNX export + onnxruntime parity (synthetic) | EXECUTED, NOT COMMITTED | max \|Δ\| = 2.98e-08 on a 5-tree/depth-2 toy model; empty test split honestly reported |
| Dataset tooling (ingest/validate/split/evaluate; gates) | IMPLEMENTED, TESTED | `tools/dataset/` + 24 stdlib tests, wired into CI |
| Model rollback (`ai.active-model-id`) | IMPLEMENTED, TESTED | `ModelRegistryTest`, `AiServicesLifecycleTest`; artifact of skipped models never read |
| Schema hash cross-language lock | TESTED | `SchemaHashGoldenTest` (Java) + `test_pipeline.py` (Python); golden `2f2b0a3eb456…` |
| Inference performance benchmark (tripwire only) | TESTED (bound), NOT A PRODUCTION BENCHMARK | `AiInferenceBenchmarkTest` — 10k scorings, < 10 s bound, stats printed |
| Trained model quality (any metric) | **NOT MEASURED — no dataset** | Zero accuracy claims made; `AI_DATASET_EVALUATION.md` |
| Production/real-client validation | **BLOCKED** | Requires live servers + consenting clients |
| Performance on production hardware | **NOT MEASURED** | The Java benchmark covers unit-test bounds only |

## What was verified how

- **Unit tests** (pure Java JUnit5; Paper classes never loaded): schema stability/hash, extractor clip behaviour incl. NaN/Inf, validator rejections incl. all-zero snapshot, trainer determinism (same seed ⇒ identical forest bytes) and subsample behaviour at the 8-sample floor, artifact load failures (checksum, schema, normalization mismatch), ONNX absence ⇒ `available()==false` + reason, pool saturation/drop/timeout/stop, correlator tier matrix incl. boundary pings and fresh exemptions, assistant disclaimer/redaction, report limitations footer, `AiConfig` defaults all OFF.
- **Split protocol:** group split by player (deterministic sha256-based, seed recorded) — implemented in `tools/dataset/split.py` and enforced at evaluation time by `assert_no_leakage`; `split --check` re-verifies. Never performed on real data — no dataset.
- **ONNX export contract:** the supervised exporter wraps the classifier to a single output `anomaly_score` (class-1 probability), matching the documented Java adapter contract exactly; verified with onnxruntime CPU.
- **CI:** the push that introduced this document triggers the standard CI workflow (`.github/workflows/ci.yml`: compile + `./gradlew test`, Ubuntu runner, Java toolchain per repo config). Exact SHA + run URL are recorded under "CI runs" after completion; until then no claim of CI-greenness is made.

## Known limitations (explicit, load-bearing)

1. No trained model exists ⇒ ML shadow score is never present out-of-the-box ⇒ correlator runs deterministic-only until a model drop-in is performed.
2. ONNX happy path has been executed only against a committed SYNTHETIC fixture model (5 trees). A mismatching model degrades to `UNAVAILABLE` or inference failure, not to a verdict; nothing about real-world model behaviour is evidenced.
3. `Task-watchdog` timeout cannot preempt native code mid-call; it bounds accounting, and pure-Java forest inference is microsecond-scale **as bounded by construction, not by a production measurement**.
4. The command/report path was compile-checked via CI only; no in-game execution was performed.
5. Redaction covers target name/UUID/IPv4; usernames of *other* players in free text are not enumerated — LLM egress stays off by default for this reason among others.
6. AI evidence (`ai-shadow`) is deliberately excluded from deterministic corroboration (no self-corroboration loops).
7. REAL_CLIENT_VALIDATION is **BLOCKED**: nothing in this repository evidences behaviour against real clients, cheat or otherwise.

## CI runs

- `ca9607a`, `a75294f`, `b6025d4` — stage 3 commits; CI run [42394435172](https://github.com/TienHeavenVN/VigilAC/actions/runs/42394435172): **failure**.
  Root causes: (1) my lifecycle test asserted `submitted()` after `stop()` nulls the pool (assertion bug — expected 0 to be ≥ 1, by construction impossible);
  (2) the ONNX adapter probed a non-existent `createTensor(env, float[], long[])` overload;
  (3) rollback-skipped models reported their manifest *file name* instead of the peeked modelId (test `orElseThrow` empty ⇒ NoSuchElementException).
  Fixed in `2b61e9d` (1+3) and `e007125` (2, against the v1.31.1 onnxruntime sources: Buffer-based factories, `Result` is an Iterable).
- `2b61e9d` — CI run [42394650646](https://github.com/TienHeavenVN/VigilAC/actions/runs/42394650646): **failure** —
  the remaining single failure was the same createTensor probe (2), fixed in `e007125`.
- `e007125` — CI run [42395430862](https://github.com/TienHeavenVN/VigilAC/actions/runs/42395430862): **success**.
  All 22 jobs green (build; 16 compatibility matrix cells incl. 1.21–1.21.11, 26.1.2–26.3;
  authoritative version inventory; 26x artifact build; runtime smoke tests on Paper 1.21.8/26.1.2/26.2/26.3;
  live bot harness). Primary job annotation: `tests=380 skipped=0 failures=0 errors=0`.
  Note: load-test job remained skipped (gated); no runtime smoke of the AI command on a live server was performed.
- Earlier AI-stage runs (for context): `3ad0bae` — [42390581454](https://github.com/TienHeavenVN/VigilAC/actions/runs/42390581454) success (366 tests);
  `e2eb7ed` — [42390460389](https://github.com/TienHeavenVN/VigilAC/actions/runs/42390460389) failure (breakInterval sentinel clip, fixed by `3ad0bae`);
  `48a3664`/`77cef4b` — [42391382642](https://github.com/TienHeavenVN/VigilAC/actions/runs/42391382642)/[42392219487](https://github.com/TienHeavenVN/VigilAC/actions/runs/42392219487) success (docs).

- `3ad0bae` — CI run [42390581454](https://github.com/TienHeavenVN/VigilAC/actions/runs/42390581454): **success**.
  All 19 jobs green (build, `Build and test` on the primary toolchain, 16 compatibility matrix
  cells incl. 1.21-1.21.11 and 26.x, authoritative version inventory). Primary job annotation:
  `tests=366 skipped=0 failures=0 errors=0` (Phase 7 base was 280; +86 AI tests).
- `e2eb7ed` — CI run [42390460389](https://github.com/TienHeavenVN/VigilAC/actions/runs/42390460389): **failure**.
  Root cause: `FeatureExtractorTest.breakIntervalWithoutHistoryIsZeroNotNegative` —
  expected 0.0, was 60000.0. `BlockActionTracker.breakInterval(now)` returns `Long.MAX_VALUE`
  for "no break on record"; the extractor clipped it to the schema max (a fabricated
  measurement). Fixed in `3ad0bae`; no thresholds, no test expectations weakened — the test
  expectation (0 = unknown floor) was correct and is now satisfied by the code.
- Load test / runtime smoke / live bot harness jobs were `skipped` in these runs (they are
  gated); no runtime smoke of the AI command has been performed by CI either.
- `48a3664` — CI run [42391382642](https://github.com/TienHeavenVN/VigilAC/actions/runs/42391382642): **success** (docs-only sync of this section).

## Commands used

```
./gradlew test          # CI only (no JDK in this workspace)
gh run list --branch arena/a7154a72-vigilac
gh run view <id> --json status,conclusion,jobs
```
