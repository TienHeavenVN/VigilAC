#!/usr/bin/env python3
"""Deterministic group split by player (70/15/15 default) — splits.json.

  python3 tools/dataset/split.py --corpus CORPUS --seed 1337 [--train 0.70 --val 0.15]
  python3 tools/dataset/split.py --check --splits splits.json

Grouping by player_pseudonym is mandatory: windows of one player can never
straddle train/val/test (that would leak). --check re-verifies disjointness.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import vigilac_dataset as vd


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", type=Path, help="corpus directory (required unless --check)")
    parser.add_argument("--seed", type=int, default=1337)
    parser.add_argument("--train", type=float, default=0.70)
    parser.add_argument("--val", type=float, default=0.15)
    parser.add_argument("--out", type=Path, default=None, help="splits.json path (default: CORPUS/splits.json)")
    parser.add_argument("--check", action="store_true", help="verify an existing splits.json instead of creating one")
    parser.add_argument("--splits", type=Path, help="splits.json to verify with --check")
    args = parser.parse_args()

    if args.check:
        if not args.splits:
            print("error: --check requires --splits", file=sys.stderr)
            return 2
        return check_splits(args.splits)

    if not args.corpus or not args.corpus.is_dir():
        print("error: --corpus must be an existing directory", file=sys.stderr)
        return 2
    if not (0.0 < args.train < 1.0 and 0.0 < args.val < 1.0 and args.train + args.val < 1.0):
        print("error: require 0 < train, 0 < val, train+val < 1", file=sys.stderr)
        return 2

    report = vd.load_corpus(args.corpus)
    splits = vd.split_corpus(report.accepted, args.seed, args.train, args.val)
    vd.assert_no_leakage(splits)

    out = args.out or (args.corpus / "splits.json")
    payload = {
        "seed": args.seed,
        "train_ratio": args.train,
        "val_ratio": args.val,
        "test_ratio": round(1.0 - args.train - args.val, 10),
        "split_by": "player_pseudonym (group)",
        "schema_hash": vd.SCHEMA_HASH,
        "counts": {name: len(rows) for name, rows in splits.items()},
        "players": {name: len({s.player_pseudonym for s in rows}) for name, rows in splits.items()},
        "labels": {name: dict(sorted(Counter(s.label for s in rows).items())) for name, rows in splits.items()},
        "assignments": {name: sorted({s.player_pseudonym for s in rows}) for name, rows in splits.items()},
    }
    out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({k: payload[k] for k in ("seed", "counts", "players", "labels")}, indent=2, sort_keys=True))
    return 0


def check_splits(splits_path: Path) -> int:
    data = json.loads(splits_path.read_text(encoding="utf-8"))
    assignments = data.get("assignments")
    if not isinstance(assignments, dict):
        print("error: splits.json has no 'assignments'", file=sys.stderr)
        return 2
    seen: dict[str, str] = {}
    leaked = []
    for split, players in assignments.items():
        for player in players:
            other = seen.get(player)
            if other and other != split:
                leaked.append(player)
            seen[player] = split
    if leaked:
        print(f"LEAKAGE: players in multiple splits: {leaked[:10]}")
        return 1
    print("splits.json OK: no player appears in more than one split")
    return 0


if __name__ == "__main__":
    sys.exit(main())
