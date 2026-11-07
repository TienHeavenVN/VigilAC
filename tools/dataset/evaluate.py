#!/usr/bin/env python3
"""Model-agnostic evaluation of anomaly scores against the corpus.

Inputs:
  --corpus    corpus directory (LEGITIMATE vs CHEAT_VERIFIED rows are used; SUSPICIOUS excluded)
  --scores    JSONL from a scorer: {"sample_key": ..., "score": 0..1} for EVERY row
  --threshold score >= threshold ⇒ flagged as cheat (default 0.6)

Outputs a JSON report: confusion matrix, precision/recall/F1/FPR with Wilson 95% CIs,
per-scenario / per-client-category / per-input-category / per-mc_version breakdowns.

Honesty gates: refuses to emit metrics unless the corpus passes the minimum gates
(200/50/50) unless --allow-tiny-fixture is passed, in which case every report is
stamped FIXTURE_ONLY. No real corpus exists in this repository — see docs/AI_DATASET_EVALUATION.md.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

import vigilac_dataset as vd


def load_scores(path: Path) -> dict[str, float]:
    scores: dict[str, float] = {}
    with path.open("r", encoding="utf-8") as handle:
        for lineno, line in enumerate(handle, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
                key = row["sample_key"]
                score = float(row["score"])
            except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
                raise ValueError(f"{path.name}:{lineno}: bad score row: {exc}")
            if not (0.0 <= score <= 1.0):
                raise ValueError(f"{path.name}:{lineno}: score out of [0,1]")
            scores[key] = score
    return scores


def breakdown(samples: list[vd.Sample], scores: dict[str, float], threshold: float, key: str) -> dict:
    groups: dict[str, list[tuple[vd.Sample, float]]] = defaultdict(list)
    for sample in samples:
        score = scores.get(sample.sample_key())
        if score is not None:
            groups[getattr(sample, key)].append((sample, score))
    out = {}
    for name, rows in sorted(groups.items()):
        y_true = [1 if s.label == "CHEAT_VERIFIED" else 0 for s, _ in rows]
        y_score = [score for _, score in rows]
        plain = vd.compute_metrics(y_true, y_score, threshold)
        out[name] = {
            "n": len(rows),
            "precision": plain["precision"],
            "recall": plain["recall"],
            "false_positive_rate": plain["false_positive_rate"],
        }
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--scores", type=Path, required=True)
    parser.add_argument("--threshold", type=float, default=0.6)
    parser.add_argument("--allow-tiny-fixture", action="store_true",
                        help="bypass the minimum-data gates; every report is stamped FIXTURE_ONLY")
    parser.add_argument("--out", type=Path, default=None)
    args = parser.parse_args()

    if not (0.0 <= args.threshold <= 1.0):
        print("error: --threshold must be in [0,1]", file=sys.stderr)
        return 2
    if not args.corpus.is_dir():
        print(f"error: {args.corpus} is not a directory", file=sys.stderr)
        return 2

    report = vd.load_corpus(args.corpus)
    usable = vd.evaluation_rows(report.accepted)
    scores = load_scores(args.scores)

    gated_ok, gate_detail = vd.corpus_gates(report.accepted)
    verdict = "OK" if gated_ok else ("FIXTURE_ONLY" if args.allow_tiny_fixture else "INSUFFICIENT_DATA")
    if verdict == "INSUFFICIENT_DATA":
        print(json.dumps({"status": "INSUFFICIENT_DATA", "detail": gate_detail,
                          "usable_rows": len(usable), "excluded_suspicious":
                          len(report.accepted) - len(usable)}, indent=2))
        return 2

    matched = [(s, scores[s.sample_key()]) for s in usable if s.sample_key() in scores]
    missing = len(usable) - len(matched)
    y_true = [1 if s.label == "CHEAT_VERIFIED" else 0 for s, _ in matched]
    y_score = [score for _, score in matched]

    result = {
        "status": verdict,
        "gate_detail": gate_detail,
        "fixture_only_notice": ("SYNTHETIC FIXTURE — these numbers describe a toy corpus and say NOTHING "
                                "about detection of real cheats" if verdict == "FIXTURE_ONLY" else None),
        "corpus": str(args.corpus),
        "corpus_digest": vd.corpus_digest(args.corpus),
        "schema_hash": vd.SCHEMA_HASH,
        "rows_total": len(report.accepted),
        "rows_scored": len(matched),
        "rows_missing_scores": missing,
        "excluded_suspicious": len(report.accepted) - len(usable),
        "label_counts": dict(sorted(Counter(s.label for s, _ in matched).items())),
        "overall": vd.compute_metrics(y_true, y_score, args.threshold),
        "by_scenario": breakdown([s for s, _ in matched], scores, args.threshold, "scenario"),
        "by_client_category": breakdown([s for s, _ in matched], scores, args.threshold, "client_category"),
        "by_input_category": breakdown([s for s, _ in matched], scores, args.threshold, "input_category"),
        "by_mc_version": breakdown([s for s, _ in matched], scores, args.threshold, "mc_version"),
    }
    text = json.dumps(result, indent=2, sort_keys=True)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
