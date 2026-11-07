# VigilAC AI-Assist Implementation Plan

Standing mandate: implement in stages, smallest honest slice first; never fabricate datasets, metrics or results; deterministic engine untouched; ML alone never punishes.

Status as of 2026-11-08 (branch `arena/a7154a72-vigilac`; dataset tooling commit `a75294f`, rollback/registry/ONNX-contract commit `ca9607a`; see `AI_VERIFICATION.md`):

| Stage | Scope | Status | Evidence |
|---|---|---|---|
| 1 | AI architecture + telemetry interfaces (features, schema, extractor, validator) | **IMPLEMENTED + TESTED + CI_VERIFIED** | `telemetry/` package; `FeatureExtractorTest`, `FeatureValidatorTest`, `AiFeatureSchemaTest` |
| 2 | Shadow-mode anomaly detection (bounded pool, IF trainer/loader, shadow evidence) | **IMPLEMENTED + TESTED + CI_VERIFIED (shadow only)** | `runtime/`, `ml/isolationforest/`, `AiShadowWorkerPoolTest`, `IsolationForest*Test`; default OFF: no inference until `ai.enabled=true` |
| 3 | Training/evaluation pipeline | **PARTIAL (tooling implemented + tested; dataset absent)** | `tools/dataset/` (validation/dedup/group-split/metrics+Wilson CIs/insufficient-data gates; Python pipeline mirrors the Java schema behind a golden SHA-256); XGBoost→ONNX export + onnxruntime parity smoke on a committed SYNTHETIC fixture only (max |Δ| = 2.98e-08). **No dataset exists; therefore NO trained production model, NO accuracy/precision/recall claims, NO production metrics.** See `DATASET.md` and `AI_DATASET_EVALUATION.md`. Non-goals remain explicit. |
| 4 | ONNX runtime integration | **IMPLEMENTED (adapter) + TESTED end-to-end on SYNTHETIC fixture** | Reflection-guarded adapter; absent runtime ⇒ `UNAVAILABLE`, never throws. End-to-end contract test with real onnxruntime (test-only dependency, never shipped) against a committed synthetic fixture model: load + score + determinism + checksum gate, Java score == Python onnxruntime reference within 1e-4. **Still: no real model, no production-shaped validation; says nothing about cheat detection.** |
| 5 | Evidence correlation | **IMPLEMENTED + TESTED + CI_VERIFIED** | `AiEvidenceCorrelator` (NONE/LOW/MEDIUM/HIGH; ML-only ⇒ ≤ LOW; FP downgrade); `AiEvidenceCorrelatorTest`; `ai-shadow` self-corroboration excluded |
| 6 | Admin assistant | **IMPLEMENTED + TESTED + CI_VERIFIED (offline templates; LLM optional/disabled)** | `assistant/`; ships NoopLlmProvider only; `AdminAssistantTest`; reports carry limitations + disclaimer |
| 7 | Performance + real-client validation | **BLOCKED** | Requires real production server + consenting client builds; `REAL_CLIENT_VALIDATION` stays BLOCKED |

## Order of work actually followed

1. Repository inspection; tracker APIs verified against source before any AI code was written.
2. Stage 1 (schema/snapshot/validator/extractor) — pure Java, no Bukkit.
3. Stage 2 ML (manifest parser with artifact verification; trainer; loader; ONNX adapter) + bounded runtime pool.
4. Stage 5 correlator (rules exhaustive; hard cap; FP downgrade) + Stage 6 assistant/report + config with everything OFF.
5. Wiring: `plugin.yml` (`vigilac.ai`), `config.yml` (`ai.*` defaults OFF/shadow), `/vigilac ai` command, lifecycle in `VigilAC.java`.
6. Tests (pure JUnit5) → CI. Docs (this file, `AI_ARCHITECTURE.md`, `AI_VERIFICATION.md`).

## Explicit non-goals (unchanged)

- No automatic or assisted punishment from AI output.
- No claim of detection accuracy without a real dataset and a measured evaluation split by player and session.
- No external telemetry egress by default; no bundled LLM provider.
- No weakening of deterministic thresholds to "make room" for ML.
- Stage 7 (real-client validation) cannot be completed in any sandbox or CI environment.

## What a future training run would require

1. A consented, licensed dataset of player sessions (honest-player and known-cheat samples),
   with split **by player and by session** (never adjacent windows of the same player across train/test).
2. Preprocessing: the exact `AiFeatureSchema` v1 (22 features, ranges as documented).
3. Train `IsolationForestTrainer` (unsupervised) and/or export XGBoost→ONNX for the supervised path.
4. Report measured metrics only (precision/recall/F1/FPR with Wilson 95% CIs on held-out players;
   per-cheat-category / per-input-category / cross-version breakdowns via `evaluate.py`).
   Accuracy numbers may not be stated before this exists.
5. Model packaging: `*.manifest` + artifact with SHA-256, schema hash, training provenance,
   license; drop into `plugins/VigilAC/ai-models/`. Rollback: point `ai.active-model-id` at a
   previously known-good modelId (no file deletion); unset to load everything again.
