# VigilAC Dataset Engineering — Capture Contract & Pipeline

**Status: NO DATASET EXISTS.** This document defines how one will be built and what the
tooling under `tools/dataset/` does and does not prove. Nothing in this repository —
a fixture, a smoke run, a benchmark — is evidence about detection of real cheats.

## 1. Corpus format (JSONL, one object per line)

Each line is one observation **window** of one player in one session:

```json
{
  "player_pseudonym": "0123456789abcdef",
  "session_pseudonym": "0123456789abcdef0123456789abcdef",
  "mc_version": "1.21.8",
  "client_category": "VANILLA",
  "input_category": "MOUSE",
  "tps_1m": 19.9,
  "ping_ms": 31.0,
  "feature_schema_hash": "2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688",
  "label": "LEGITIMATE",
  "label_source": "SERVER_TELEMETRY_CURATION",
  "label_confidence": 0.9,
  "collected_utc": "2026-10-31T12:00:00Z",
  "scenario": "ranked-duel-lab",
  "features": [9.38, 7.28, "... 22 floats in AiFeatureSchema order ..."],
  "provenance_note": "optional free text"
}
```

Hard rules enforced by `vigilac_dataset.Sample.from_row` (reject ≠ repair, never imputed):

- pseudonyms must match `^[0-9a-f]{16,64}$` (opaque HMAC/SHA-256 style — no usernames, UUIDs or IPs);
- `mc_version` must match `^\d+\.\d+(\.\d+)?$`; `client_category` free text ≤ 64 chars;
  `input_category` one of `MOUSE|KEYBOARD|MIXED|CONTROLLER|UNKNOWN`;
- `feature_schema_hash` must equal the current schema's SHA-256 (schema bump ⇒ old rows rejected, not migrated silently);
- 22 finite features, each within the clipped range of `AiFeatureSchema`;
- an **all-zero feature vector is rejected as empty signal**, never silently kept;
- `collected_utc` must be `YYYY-MM-DDTHH:MM:SSZ`;
- `CHEAT_VERIFIED` requires `label_source` ∈ {`CONTROLLED_LAB`, `MODERATOR_REVIEW`} **and**
  `label_confidence` ≥ 0.8 — a `HEURISTIC_FLAG` row can at best be `SUSPICIOUS`, and
  `SUSPICIOUS` rows are kept for triage but **excluded from evaluation**;
- duplicates (same session + same timestamp + identical features) are dropped and counted.

## 2. Ground truth fields — and what "verified" means

`label` ∈ {`LEGITIMATE`, `SUSPICIOUS`, `CHEAT_VERIFIED`} and every row carries `label_source`,
`label_confidence`, `collected_utc`, `scenario`, `client_category`, `input_category`, server
`tps_1m` and `ping_ms` context. The only sources that may mint `CHEAT_VERIFIED`:

1. `CONTROLLED_LAB` — a cheat was installed on a known-good client in a controlled lab match
   (e.g. AutoClicker 20 CPS, reach 4.5, ESP) and the windows were captured then. Detection
   settings and server config must be recorded; TPS/ping within production-representative bounds.
2. `MODERATOR_REVIEW` — a violation was reviewed by ≥ 2 independent moderators with replay
   evidence; confidence recorded; the review packet (replay IDs, screenshots) is stored **outside
   this repository** (not committed, not public).

`SERVER_TELEMETRY_CURATION` may mint `LEGITIMATE` for long-lived accounts with zero flags and
normal distributions; it may never mint `CHEAT_VERIFIED`.

## 3. Capture contract (privacy)

Collect **only** the fields in §1. Never collect: player usernames, UUIDs, IPv4/IPv6, chat
content, inventories, positions beyond what the 22 features encode, world seed, OS, or hardware.
Pseudonymization happens **before** rows reach the corpus directory (the capture build holds the
HMAC key; the corpus does not). Rotation of the key makes old IDs unlinkable — plan ≤ 90 days retention.

The capture build lives in this repository: `docs/CAPTURE.md` (disabled by default, owner
declaration + explicit player opt-in required — `/vigilac capture consent`). Any capture's output
must pass `validate.py` with zero rejects on a sampled audit, exactly as if it had been produced
externally.

## 4. Versioning & reproducibility

`validate.py --write-manifest` emits `dataset-manifest.json`:

```json
{
  "dataset_version": "2026.11.0",
  "created_utc": "...",
  "schema_hash": "...",
  "corpus_digest": "<sha256 of sorted per-file sha256s>",
  "files": [{"name": "...", "sha256": "...", "bytes": 123}],
  "accepted": 0, "rejected": 0, "by_label": {...}
}
```

`corpus_digest` pins the corpus by content; `split.py` pins the split by `--seed`
(`sha256(seed:player_pseudonym)` group assignment). Together: any reported metric can be
re-derived from (corpus digest, split seed, model artifact digest, threshold). The exact
artifact used must be committed or externally referenced by SHA-256 — never
"the latest file in the directory".

## 5. Split protocol

Splits are **group assignments by player**, 70/15/15 (train/val/test). Windows of one player
can never straddle sets; `assert_no_leakage` enforces this, and `split.py --check` re-verifies
a `splits.json`. Evaluation uses `LEGITIMATE` vs `CHEAT_VERIFIED` rows only.

## 6. Minimum-data gates (hard)

`corpus_gates()` refuses to produce evaluation/training unless:

- ≥ **200** usable rows (LEGITIMATE + CHEAT_VERIFIED),
- ≥ **50** `CHEAT_VERIFIED`, ≥ **50** `LEGITIMATE`.

Below that, `evaluate.py` and `train_supervised.py` exit `INSUFFICIENT_DATA` — no numbers.
`--allow-tiny-fixture` exists solely for the committed SYNTHETIC test fixture and stamps every
output `FIXTURE_ONLY`; a fixture-trained artifact is not a detection model.

## 7. Tooling map

See `tools/dataset/README.md`. In short: `ingest.py` (raw → corpus) · `validate.py`
(health/report/manifest) · `split.py` (deterministic group split) · `evaluate.py`
(model-agnostic metrics report) · `train_supervised.py` (XGBoost + ONNX export + parity,
optional deps via `requirements.txt`). CI runs only the stdlib unittest suite.

## 8. Honesty status of everything in this repository

- The only corpus in-repo is `tools/dataset/tests/fixtures/synthetic_corpus/sample.jsonl`,
  which is **generated test data** (see its `README.md`). It exists to exercise the tooling.
- No real capture has ever been ingested; no detection metric exists; `REAL_CLIENT_VALIDATION`
  remains **BLOCKED**.
- All training/export/parity results recorded so far were produced on the synthetic fixture
  and are labeled as such.
