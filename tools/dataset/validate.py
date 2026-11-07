#!/usr/bin/env python3
"""Validate a corpus directory. Exit 0 = clean, 1 = invalid rows found, 2 = usage error.

  python3 tools/dataset/validate.py /path/to/corpus [--emit-keys keys.jsonl] [--write-manifest m.json]
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timezone
import hashlib
from pathlib import Path

import vigilac_dataset as vd


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("corpus_dir", type=Path)
    parser.add_argument("--emit-keys", type=Path, default=None,
                        help="write sample_key lines for scorers (JSONL: {sample_key, label, mc_version, scenario, client_category})")
    parser.add_argument("--write-manifest", type=Path, default=None,
                        help="write dataset-manifest.json describing the corpus (versioning/reproducibility)")
    parser.add_argument("--dataset-version", default=None, help="semver for the manifest, e.g. 2026.11.0")
    args = parser.parse_args()

    if not args.corpus_dir.is_dir():
        print(f"error: {args.corpus_dir} is not a directory", file=sys.stderr)
        return 2

    report = vd.load_corpus(args.corpus_dir)
    reason_counts = Counter(reason for _, _, reason in report.rejected)

    summary = {
        "corpus_dir": str(args.corpus_dir),
        "files": [p.name for p in vd.corpus_files(args.corpus_dir)],
        "accepted": len(report.accepted),
        "rejected": len(report.rejected),
        "duplicates_dropped": report.duplicates,
        "rejection_reasons": dict(sorted(reason_counts.items())),
        "by_label": report.counts_by("label"),
        "by_label_source": report.counts_by("label_source"),
        "by_mc_version": report.counts_by("mc_version"),
        "by_input_category": report.counts_by("input_category"),
        "by_scenario": report.counts_by("scenario"),
        "unique_players": len({s.player_pseudonym for s in report.accepted}),
        "unique_sessions": len({s.session_pseudonym for s in report.accepted}),
        "schema_hash": vd.SCHEMA_HASH,
        "corpus_digest": vd.corpus_digest(args.corpus_dir),
    }
    print(json.dumps(summary, indent=2, sort_keys=True))

    if args.emit_keys:
        with args.emit_keys.open("w", encoding="utf-8") as handle:
            for sample in report.accepted:
                handle.write(json.dumps({
                    "sample_key": sample.sample_key(),
                    "label": sample.label,
                    "mc_version": sample.mc_version,
                    "scenario": sample.scenario,
                    "client_category": sample.client_category,
                    "input_category": sample.input_category,
                }, sort_keys=True) + "\n")

    if args.write_manifest:
        version = args.dataset_version or "0.0.0-unversioned"
        manifest = {
            "dataset_version": version,
            "created_utc": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "schema_hash": vd.SCHEMA_HASH,
            "corpus_digest": vd.corpus_digest(args.corpus_dir),
            "files": [{"name": p.name,
                       "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                       "bytes": p.stat().st_size} for p in vd.corpus_files(args.corpus_dir)],
            "accepted": len(report.accepted),
            "rejected": len(report.rejected),
            "duplicates_dropped": report.duplicates,
            "by_label": report.counts_by("label"),
        }
        args.write_manifest.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    return 1 if report.rejected else 0


if __name__ == "__main__":
    sys.exit(main())
