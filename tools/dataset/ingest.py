#!/usr/bin/env python3
"""Ingest a raw capture file into a corpus directory.

Raw capture = JSONL where each line is one observation WINDOW produced by a
capture build (see docs/DATASET.md for the capture contract). Every row carries
its own ground-truth fields — the ingester NEVER assigns a default label and
NEVER fabricates a field. Anything invalid is rejected with a line-numbered
reason; nothing is repaired or imputed.

  python3 tools/dataset/ingest.py --corpus CORPUS_DIR --input raw.jsonl --dataset-version 2026.11.0
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
import hashlib
from pathlib import Path

import vigilac_dataset as vd


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", type=Path, required=True, help="corpus directory (created if missing)")
    parser.add_argument("--input", type=Path, required=True, help="raw capture JSONL")
    parser.add_argument("--dataset-version", required=True, help="semver tag recorded in the ingest log")
    parser.add_argument("--note", default="", help="free-text provenance note for the ingest log")
    args = parser.parse_args()

    if not args.input.is_file():
        print(f"error: {args.input} not found", file=sys.stderr)
        return 2
    args.corpus.mkdir(parents=True, exist_ok=True)

    accepted, rejected = [], []
    for lineno, row in vd.iter_jsonl(args.input):
        if isinstance(row, vd.SampleError):
            rejected.append({"line": lineno, "reason": row.reason})
            continue
        try:
            accepted.append(vd.Sample.from_row(row))
        except vd.SampleError as exc:
            rejected.append({"line": lineno, "reason": exc.reason})

    # dedup against existing corpus + within the new batch
    existing = vd.load_corpus(args.corpus) if vd.corpus_files(args.corpus) else None
    seen = {s.dedup_key() for s in (existing.accepted if existing else [])}
    written = 0
    out_path = args.corpus / f"ingest-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{args.dataset_version.replace('.', '_')}.jsonl"
    with out_path.open("w", encoding="utf-8") as handle:
        for sample in accepted:
            key = sample.dedup_key()
            if key in seen:
                rejected.append({"line": None, "reason": "duplicate_of_existing_or_batch"})
                continue
            seen.add(key)
            handle.write(json.dumps(sample_to_row(sample), sort_keys=True) + "\n")
            written += 1

    ingest_log = {
        "dataset_version": args.dataset_version,
        "input": str(args.input),
        "output_file": out_path.name,
        "accepted": len(accepted),
        "written": written,
        "rejected": len(rejected),
        "rejection_sample": rejected[:50],
        "note": args.note,
        "schema_hash": vd.SCHEMA_HASH,
        "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    log_path = args.corpus / "ingest-log.jsonl"
    with log_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(ingest_log, sort_keys=True) + "\n")

    print(json.dumps({"written": written, "rejected": len(rejected), "output": str(out_path)}, indent=2))
    return 1 if rejected else 0


def sample_to_row(sample: vd.Sample) -> dict:
    row = {
        "player_pseudonym": sample.player_pseudonym,
        "session_pseudonym": sample.session_pseudonym,
        "mc_version": sample.mc_version,
        "client_category": sample.client_category,
        "input_category": sample.input_category,
        "tps_1m": sample.tps_1m,
        "ping_ms": sample.ping_ms,
        "feature_schema_hash": sample.feature_schema_hash,
        "label": sample.label,
        "label_source": sample.label_source,
        "label_confidence": sample.label_confidence,
        "collected_utc": sample.collected_utc,
        "scenario": sample.scenario,
        "features": sample.features,
    }
    if sample.provenance_note:
        row["provenance_note"] = sample.provenance_note
    return row


if __name__ == "__main__":
    sys.exit(main())
