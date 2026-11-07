# VigilAC dataset tooling

Everything here is **tooling for a dataset that does not exist yet**. No corpus ships
with this repository, no detection-quality metric has been measured on real data, and
nothing in this directory may be cited as evidence that VigilAC (or any model) can
detect real cheats. See `docs/AI_DATASET_EVALUATION.md` for the honest ledger.

## Layout

- `vigilac_dataset.py` — shared library: feature schema (mirror of `AiFeatureSchema`,
  locked by a golden hash test in both languages), sample validation, dedup, group
  splits by player, leakage checks, Wilson 95% intervals, precision/recall/F1/FPR,
  minimum-data gates (200 usable / 50 cheat / 50 legit).
- `vigilac_manifest.py` — read/write/verify `VigilAC-ModelManifest/1` files
  (byte-compatible with the Java runtime contract).
- `ingest.py` — raw JSONL capture → corpus directory. Never assigns default labels,
  never imputes, never repairs. Rejects with line-numbered reasons.
- `validate.py` — corpus health report (JSON). Optional `--emit-keys`,
  `--write-manifest` (dataset versioning + SHA-256 of inputs).
- `split.py` — deterministic group split by player (default 70/15/15, seed 1337);
  `--check` re-verifies an existing split does not leak players across sets.
- `evaluate.py` — model-agnostic scoring report (confusion matrix, Wilson CIs,
  per-scenario / client / input / mc_version breakdowns). Refuses to emit metrics
  below the data gates unless `--allow-tiny-fixture` is passed, which stamps every
  report `FIXTURE_ONLY`. SUSPICIOUS rows are never part of the evaluation.
- `train_supervised.py` — XGBoost training with a required, copied `train.config.json`;
  exports a single-output ONNX model (`anomaly_score` ∈ [0,1]) wrapped for the
  VigilAC runtime contract; verifies onnxruntime parity vs xgboost
  (`parity.json`); writes a manifest with `fixture_only=yes` when trained below the gates.
- `tests/` — stdlib unittest suite + a SYNTHETIC fixture corpus
  (`tests/fixtures/synthetic_corpus/sample.jsonl`, generated, labeled as such).
  The fixture is not gameplay and its metrics are meaningless for detection quality.

## Typical flow (once a controlled capture exists)

```sh
python3 tools/dataset/ingest.py --corpus /data/vigilac-corpus --input capture.jsonl --dataset-version 2026.11.0
python3 tools/dataset/validate.py /data/vigilac-corpus --write-manifest /data/vigilac-corpus/dataset-manifest.json
python3 tools/dataset/split.py --corpus /data/vigilac-corpus --seed 1337
# score with any model that consumes the 22-feature schema, one JSON line per row:
#   {"sample_key": "...", "score": 0.87}
python3 tools/dataset/evaluate.py --corpus /data/vigilac-corpus --scores scores.jsonl
```

`train_supervised.py` additionally requires `pip install -r tools/dataset/requirements.txt`
and a `train.config.json` in the corpus directory (all knobs + fixed seed). It will
refuse to train below the data gates unless `--allow-tiny-fixture` is passed, and every
fixture-trained artifact is marked `fixture_only=yes` in its manifest notes.

## Ground rules

1. Missing features are rejected, never imputed.
2. All-zero feature rows are rejected (no signal).
3. `CHEAT_VERIFIED` requires a lab/moderator source and confidence ≥ 0.8.
   `SUSPICIOUS` is kept for triage but excluded from evaluation.
4. Duplicate capture windows (same session + timestamp + features) are dropped and counted.
5. Splits are by **player**, never by row — windows of one player never straddle sets.
6. Never feed synthetic fixtures to a production server; the Java registry tests use a
   synthetic fixture only to prove the ONNX runtime contract.
