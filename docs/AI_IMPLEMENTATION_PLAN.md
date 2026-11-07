# VigilAC AI-Assist Implementation Plan

Standing mandate: implement in stages, smallest honest slice first; never fabricate datasets, metrics or results; deterministic engine untouched; ML alone never punishes.

Status as of 2026-11-07 (HEAD pending CI — see `AI_VERIFICATION.md`):

| Stage | Scope | Status | Evidence |
|---|---|---|---|
| 1 | AI architecture + telemetry interfaces (features, schema, extractor, validator) | **IMPLEMENTED + TESTED** | `telemetry/` package; `FeatureExtractorTest`, `FeatureValidatorTest`, `AiFeatureSchemaTest`; CI pending |
| 2 | Shadow-mode anomaly detection (bounded pool, IF trainer/loader, shadow evidence) | **IMPLEMENTED + TESTED (shadow only)** | `runtime/`, `ml/isolationforest/`, `AiShadowWorkerPoolTest`, `IsolationForest*Test`; default OFF: no inference until `ai.enabled=true` |
| 3 | Training/evaluation pipeline | **PARTIAL** | Trainer + deterministic seeded isolation forest + split-by-player/session protocol documented in `AI_VERIFICATION.md`. **No dataset exists; therefore NO trained model, NO accuracy/precision/recall claims, NO production metrics.** Non-goals remain explicit. |
| 4 | ONNX runtime integration | **IMPLEMENTED (adapter) + UNVERIFIED (end-to-end)** | Reflection-guarded adapter; absent runtime ⇒ `UNAVAILABLE`, never throws; no `.onnx` model exists in repo; happy path never executed |
| 5 | Evidence correlation | **IMPLEMENTED + TESTED** | `AiEvidenceCorrelator` (NONE/LOW/MEDIUM/HIGH; ML-only ⇒ ≤ LOW; FP downgrade); `AiEvidenceCorrelatorTest`; `ai-shadow` self-corroboration excluded |
| 6 | Admin assistant | **IMPLEMENTED + TESTED (offline templates; LLM optional/disabled)** | `assistant/`; ships NoopLlmProvider only; `AdminAssistantTest`; reports carry limitations + disclaimer |
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
4. Report measured metrics only (perplexity-free: PR-AUC/ROC-AUC/precision@k + false-positive rate
   on held-out players; confidence intervals). Accuracy numbers may not be stated before this exists.
5. Model packaging: `*.manifest` + artifact with SHA-256, schema hash, trainingSummary provenance,
   license; drop into `plugins/VigilAC/ai-models/`.
