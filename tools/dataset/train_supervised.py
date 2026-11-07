#!/usr/bin/env python3
"""Supervised (XGBoost) training + ONNX export + numerical parity check.

Requires: pip install -r tools/dataset/requirements.txt

Hard honesty gates:
  * refuses to train unless the corpus passes the minimum gates
    (200 usable rows / 50 CHEAT_VERIFIED / 50 LEGITIMATE);
    --allow-tiny-fixture bypasses for SYNTHETIC test corpora only, and stamps
    every artifact with notes=FIXTURE_ONLY. A FIXTURE_ONLY model must never be
    loaded by a production server.
  * trains with the config given in --config (seed fixed there) and records the
    full config + corpus digest in the manifest notes;
  * after export, ONNX outputs are compared against XGBoost probabilities on the
    validation split; max |Δ| is printed and written to parity.json.

  python3 tools/dataset/train_supervised.py --config train.config.json --corpus CORPUS --out TRAIN_OUT
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import vigilac_dataset as vd
import vigilac_manifest as vm

DEFAULT_CONFIG = {
    "seed": 1337,
    "split_seed": 1337,
    "train_ratio": 0.70,
    "val_ratio": 0.15,
    "n_estimators": 300,
    "max_depth": 6,
    "learning_rate": 0.1,
    "subsample": 0.9,
    "colsample_bytree": 0.9,
    "tree_method": "hist",
    "threshold": 0.6,
}


def require_dependencies():
    try:
        import xgboost  # noqa: F401
        import onnx  # noqa: F401
        import onnxmltools  # noqa: F401
        import onnxruntime  # noqa: F401
    except ImportError as exc:
        print(f"error: missing optional dependency ({exc.name}). "
              f"Install with: pip install -r tools/dataset/requirements.txt", file=sys.stderr)
        sys.exit(2)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="output directory for artifact/manifest/reports")
    parser.add_argument("--model-id", required=True, help="e.g. vigilac-xgb-2026.11.0+abcd1234")
    parser.add_argument("--allow-tiny-fixture", action="store_true")
    args = parser.parse_args()

    require_dependencies()
    import numpy as np
    import xgboost as xgb
    import onnxmltools
    import onnxruntime as ort

    if not args.corpus.is_dir():
        print(f"error: {args.corpus} is not a directory", file=sys.stderr)
        return 2

    config_path = args.corpus / "train.config.json"
    # config is REQUIRED and reproducible: the exact file used is copied into --out
    if not config_path.exists():
        print(f"error: {config_path} not found — write your training config there (see DEFAULT_CONFIG keys in this script)",
              file=sys.stderr)
        return 2
    config = dict(DEFAULT_CONFIG)
    config.update(json.loads(config_path.read_text(encoding="utf-8")))

    report = vd.load_corpus(args.corpus)
    ok, gate_detail = vd.corpus_gates(report.accepted)
    if not ok and not args.allow_tiny_fixture:
        print(f"error: corpus gates failed — {gate_detail}", file=sys.stderr)
        return 2
    fixture_only = not ok

    usable = vd.evaluation_rows(report.accepted)
    splits = vd.split_corpus(usable, config["split_seed"], config["train_ratio"], config["val_ratio"])
    vd.assert_no_leakage(splits)

    def matrix(rows):
        if not rows:
            # honest empty: keep 2-D shape so downstream code degrades, never misparses
            return (np.zeros((0, vd.FEATURE_COUNT), dtype=np.float32),
                    np.zeros((0,), dtype=np.int32))
        x = np.array([s.features for s in rows], dtype=np.float32)
        y = np.array([1 if s.label == "CHEAT_VERIFIED" else 0 for s in rows], dtype=np.int32)
        return x, y

    x_train, y_train = matrix(splits["train"])
    x_val, y_val = matrix(splits["val"])
    x_test, y_test = matrix(splits["test"])
    if len(x_train) == 0:
        print("error: training split is empty after group splitting — corpus too small for a group split", file=sys.stderr)
        return 2
    if len(set(y_train.tolist())) < 2:
        print("error: training split contains a single class — need both LEGITIMATE and CHEAT_VERIFIED rows", file=sys.stderr)
        return 2


    pos = int((y_train == 1).sum())
    neg = int((y_train == 0).sum())
    scale_pos_weight = (neg / pos) if pos else 1.0

    model = xgb.XGBClassifier(
        n_estimators=int(config["n_estimators"]),
        max_depth=int(config["max_depth"]),
        learning_rate=float(config["learning_rate"]),
        subsample=float(config["subsample"]),
        colsample_bytree=float(config["colsample_bytree"]),
        tree_method=str(config["tree_method"]),
        eval_metric="aucpr",
        scale_pos_weight=scale_pos_weight,
        random_state=int(config["seed"]),
    )
    model.fit(x_train, y_train, eval_set=[(x_val, y_val)], verbose=False)

    threshold = float(config["threshold"])
    y_val_score = model.predict_proba(x_val)[:, 1] if len(x_val) else None
    y_test_score = model.predict_proba(x_test)[:, 1] if len(x_test) else None

    metrics = {
        "fixture_only": fixture_only,
        "fixture_only_notice": ("SYNTHETIC FIXTURE training run — artifact MUST NOT be loaded in production; "
                                "metrics below are meaningless for detection quality" if fixture_only else None),
        "gate_detail": gate_detail,
        "split_seed": config["split_seed"],
        "n_train": len(splits["train"]),
        "n_val": len(splits["val"]),
        "n_test": len(splits["test"]),
        "val": (vd.compute_metrics(y_val.tolist(), y_val_score.tolist(), threshold)
                if y_val_score is not None else {"error": "empty split after group-by-player assignment", "n": 0}),
        "test": (vd.compute_metrics(y_test.tolist(), y_test_score.tolist(), threshold)
                 if y_test_score is not None else {"error": "empty split after group-by-player assignment", "n": 0}),
    }

    args.out.mkdir(parents=True, exist_ok=True)
    import shutil
    shutil.copy(config_path, args.out / "train.config.json")
    (args.out / "metrics.json").write_text(json.dumps(metrics, indent=2, sort_keys=True) + "\n",
                                           encoding="utf-8")

    # ---- ONNX export + numerical parity ------------------------------------
    from onnxmltools.convert.common.data_types import FloatTensorType
    import onnx
    from onnx import TensorProto, helper

    raw_model = onnxmltools.convert_xgboost(
        model,
        initial_types=[("input", FloatTensorType([None, vd.FEATURE_COUNT]))])

    # Wrap the classifier output down to a SINGLE tensor "anomaly_score" = class-1
    # probability. The VigilAC ONNX runtime contract (ac.vigil.ai.ml.onnx) is exactly
    # this: one input float[1,22], one output whose first float is in [0,1]. A
    # multi-output classifier would otherwise be at the mercy of map iteration order.
    raw_graph = raw_model.graph
    opset = next((int(o.version) for o in raw_model.opset_import if o.domain in ("", "ai.onnx")), 15)
    starts = helper.make_tensor("starts", TensorProto.INT64, [1], [1])
    ends = helper.make_tensor("ends", TensorProto.INT64, [1], [2])
    axes = helper.make_tensor("axes", TensorProto.INT64, [1], [1])
    if opset >= 10:
        slice_node = helper.make_node("Slice", ["probabilities", "starts", "ends", "axes"],
                                      ["anomaly_score"], name="prob_slice")
        initializers = list(raw_graph.initializer) + [starts, ends, axes]
    else:
        slice_node = helper.make_node("Slice", ["probabilities"], ["anomaly_score"],
                                      name="prob_slice", starts=[1], ends=[2], axes=[1])
        initializers = list(raw_graph.initializer)
    wrapped_graph = helper.make_graph(
        list(raw_graph.node) + [slice_node],
        raw_graph.name + "+anomaly_score",
        list(raw_graph.input),
        [helper.make_tensor_value_info("anomaly_score", TensorProto.FLOAT, [None, 1])],
        initializers)
    opset_imports = list(raw_model.opset_import)
    if not any(o.domain in ("", "ai.onnx") for o in opset_imports):
        opset_imports.append(helper.make_opsetid("", max(opset, 15)))
    onnx_model = helper.make_model(wrapped_graph, opset_imports=opset_imports,
                                   producer_name="vigilac-dataset-pipeline")
    # onnxruntime 1.31 supports IR <= 13; the graph only uses basic Slice + TreeEnsemble
    onnx_model.ir_version = 13
    onnx.checker.check_model(onnx_model)
    onnx_path = args.out / "model.onnx"
    onnx.save(onnx_model, onnx_path)

    if y_val_score is not None:
        session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
        input_name = session.get_inputs()[0].name
        onnx_scores = session.run(["anomaly_score"], {input_name: x_val.astype(np.float32)})[0].astype(np.float64).reshape(-1)
        delta = float(np.max(np.abs(onnx_scores - y_val_score)))
        parity = {"max_abs_probability_delta": delta,
                  "tolerance": 1e-4,
                  "within_tolerance": delta <= 1e-4,
                  "input_name": input_name,
                  "output_name": "anomaly_score",
                  "note": "onnxruntime CPU vs xgboost predict_proba on the val split (same features, same weights)"}
    else:
        parity = {"error": "val split empty after group-by-player assignment; parity not measurable on this fixture",
                  "within_tolerance": None}
    (args.out / "parity.json").write_text(json.dumps(parity, indent=2, sort_keys=True) + "\n",
                                          encoding="utf-8")

    # ---- manifest (runtime contract: algorithm=ONNX, normalization=none) ----
    notes_parts = [
        "TRAINED MODEL — provenance below; quality claims only via docs/AI_DATASET_EVALUATION.md",
        f"dataset_digest={vd.corpus_digest(args.corpus)}",
        f"split_seed={config['split_seed']} n_train={len(splits['train'])} n_val={len(splits['val'])} n_test={len(splits['test'])}",
        f"fixture_only={'yes — SYNTHETIC, MUST NOT BE LOADED IN PRODUCTION' if fixture_only else 'no'}",
        f"normalization=none; input=float[1,22] raw (as clipped at extraction); single output anomaly_score=float[1,1] (class-1 probability); seed={config['seed']}",
    ]
    created = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    vm.write_manifest(args.out / "model.manifest",
                      algorithm="ONNX",
                      model_id=args.model_id,
                      artifact_name="model.onnx",
                      artifact_path=onnx_path,
                      feature_schema_hash=vd.SCHEMA_HASH,
                      normalization="none",
                      feature_count=vd.FEATURE_COUNT,
                      created_utc=created,
                      notes="; ".join(notes_parts))

    print(json.dumps({
        "status": "fixture_only_trained" if fixture_only else "trained",
        "out": str(args.out),
        "metrics": metrics,
        "parity": parity,
        "manifest": str(args.out / "model.manifest"),
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
