"""Stdlib unittest suite for the dataset pipeline. Fixtures are SYNTHETIC only."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
DATASET = REPO_ROOT / "tools" / "dataset"
FIXTURE = DATASET / "tests" / "fixtures" / "synthetic_corpus"
sys.path.insert(0, str(DATASET))

import vigilac_dataset as vd  # noqa: E402
import vigilac_manifest as vm  # noqa: E402


class SchemaCrossCheckTest(unittest.TestCase):
    def test_schema_hash_matches_java_golden_constant(self):
        # must equal AiFeatureSchema.schemaHash() (locked in SchemaHashGoldenTest)
        self.assertEqual("2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688", vd.SCHEMA_HASH)

    def test_schema_names_and_ranges_shape(self):
        self.assertEqual(22, vd.FEATURE_COUNT)
        self.assertEqual(22, len(vd.RANGES))
        self.assertEqual(len(vd.SCHEMA_NAMES), len(set(vd.SCHEMA_NAMES)))


class ValidationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="vigilac-corpus-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def _write_rows(self, rows) -> Path:
        path = self.tmp / "rows.jsonl"
        with path.open("w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row) + "\n")
        return path

    def _valid_row(self, **overrides):
        row = {
            "player_pseudonym": "a" * 16,
            "session_pseudonym": "b" * 24,
            "mc_version": "1.21.8",
            "client_category": "VANILLA",
            "input_category": "MOUSE",
            "tps_1m": 19.9,
            "ping_ms": 31.0,
            "feature_schema_hash": vd.SCHEMA_HASH,
            "label": "LEGITIMATE",
            "label_source": "SERVER_TELEMETRY_CURATION",
            "label_confidence": 0.9,
            "collected_utc": "2026-10-31T12:00:00Z",
            "scenario": "unit-test",
            "features": [1.0] * 22,
        }
        row.update(overrides)
        return row

    def test_accepts_valid_row(self):
        path = self._write_rows([self._valid_row()])
        report = vd.load_corpus(self.tmp)
        self.assertEqual(1, len(report.accepted))
        self.assertEqual([], report.rejected)

    def test_rejects_schema_hash_mismatch(self):
        path = self._write_rows([self._valid_row(feature_schema_hash="f" * 64)])
        report = vd.load_corpus(self.tmp)
        self.assertEqual(0, len(report.accepted))
        self.assertEqual("schema_hash_mismatch", report.rejected[0][2])

    def test_rejects_missing_fields_without_imputing(self):
        row = self._valid_row()
        del row["features"]
        self._write_rows([row])
        report = vd.load_corpus(self.tmp)
        self.assertEqual(0, len(report.accepted))
        self.assertEqual("missing_fields", report.rejected[0][2])

    def test_rejects_out_of_range_and_non_finite_features(self):
        features = [1.0] * 22
        features[0] = 900.0
        self._write_rows([self._valid_row(features=features)])
        self.assertEqual("out_of_range_feature", vd.load_corpus(self.tmp).rejected[0][2])
        shutil.rmtree(self.tmp)
        self.tmp = Path(tempfile.mkdtemp(prefix="vigilac-corpus-"))
        features = [1.0] * 22
        features[3] = float("nan")
        self._write_rows([self._valid_row(features=features)])
        self.assertEqual("non_finite_feature", vd.load_corpus(self.tmp).rejected[0][2])

    def test_rejects_all_zero_empty_signal(self):
        self._write_rows([self._valid_row(features=[0.0] * 22)])
        self.assertEqual("empty_signal", vd.load_corpus(self.tmp).rejected[0][2])

    def test_cheat_verified_requires_lab_source_and_confidence(self):
        self._write_rows([self._valid_row(label="CHEAT_VERIFIED", label_source="HEURISTIC_FLAG")])
        self.assertEqual("cheat_verified_weak_source", vd.load_corpus(self.tmp).rejected[0][2])
        shutil.rmtree(self.tmp)
        self.tmp = Path(tempfile.mkdtemp(prefix="vigilac-corpus-"))
        self._write_rows([self._valid_row(label="CHEAT_VERIFIED", label_source="CONTROLLED_LAB",
                                          label_confidence=0.5)])
        self.assertEqual("cheat_verified_low_confidence", vd.load_corpus(self.tmp).rejected[0][2])

    def test_suspicious_rows_are_kept_but_excluded_from_evaluation(self):
        self._write_rows([self._valid_row(label="SUSPICIOUS", label_source="HEURISTIC_FLAG")])
        report = vd.load_corpus(self.tmp)
        self.assertEqual(1, len(report.accepted))
        self.assertEqual([], vd.evaluation_rows(report.accepted))

    def test_duplicate_rows_are_dropped_and_counted(self):
        row = self._valid_row()
        self._write_rows([row, row, row])
        report = vd.load_corpus(self.tmp)
        self.assertEqual(1, len(report.accepted))
        self.assertEqual(2, report.duplicates)

    def test_sample_key_is_stable(self):
        row = self._valid_row()
        path = self._write_rows([row])
        self.assertEqual(path.exists(), True)
        report = vd.load_corpus(self.tmp)
        self.assertEqual(report.accepted[0].sample_key(), report.accepted[0].sample_key())
        self.assertEqual(64, len(report.accepted[0].sample_key()))


class SplitTest(unittest.TestCase):
    def test_split_is_deterministic_and_leakage_free(self):
        report = vd.load_corpus(FIXTURE)
        a = vd.split_corpus(report.accepted, 1337, 0.70, 0.15)
        b = vd.split_corpus(report.accepted, 1337, 0.70, 0.15)
        self.assertEqual({k: sorted(s.player_pseudonym for s in v) for k, v in a.items()},
                         {k: sorted(s.player_pseudonym for s in v) for k, v in b.items()})
        vd.assert_no_leakage(a)
        self.assertEqual(24, sum(len(v) for v in a.values()))

    def test_every_player_stays_in_one_split(self):
        report = vd.load_corpus(FIXTURE)
        splits = vd.split_corpus(report.accepted, 1337, 0.70, 0.15)
        placement = {}
        for name, rows in splits.items():
            for s in rows:
                placement.setdefault(s.player_pseudonym, set()).add(name)
        for player, names in placement.items():
            self.assertEqual(1, len(names), f"player {player} appears in {names}")

    def test_split_check_detects_leakage(self):
        with tempfile.TemporaryDirectory() as tmp:
            splits_file = Path(tmp) / "splits.json"
            assignments = {"train": ["p1", "p2"], "val": ["p2", "p3"], "test": ["p4"]}
            splits_file.write_text(json.dumps({"assignments": assignments}), encoding="utf-8")
            rc = subprocess.run([sys.executable, str(DATASET / "split.py"), "--check",
                                 "--splits", str(splits_file)], capture_output=True, text=True).returncode
            self.assertEqual(1, rc)


class MetricsTest(unittest.TestCase):
    def test_hand_computed_confusion(self):
        metrics = vd.compute_metrics([1, 1, 0, 0], [0.9, 0.4, 0.8, 0.1], 0.6)
        self.assertEqual({"tn": 1, "fp": 1, "fn": 1, "tp": 1}, metrics["confusion_matrix"])
        self.assertAlmostEqual(0.5, metrics["precision"])
        self.assertAlmostEqual(0.5, metrics["recall"])
        self.assertAlmostEqual(0.5, metrics["f1"])
        self.assertAlmostEqual(0.5, metrics["false_positive_rate"])

    def test_perfect_scores(self):
        metrics = vd.compute_metrics([1, 1, 0, 0], [0.9, 0.8, 0.1, 0.2], 0.6)
        self.assertEqual(1.0, metrics["precision"])
        self.assertEqual(1.0, metrics["recall"])
        self.assertEqual(0.0, metrics["false_positive_rate"])

    def test_gate_rejects_tiny_corpus(self):
        report = vd.load_corpus(FIXTURE)
        ok, detail = vd.corpus_gates(report.accepted)
        self.assertFalse(ok)
        self.assertIn("INSUFFICIENT_DATA", detail)


class CliTest(unittest.TestCase):
    def _run(self, script, *args, cwd=REPO_ROOT):
        return subprocess.run([sys.executable, str(DATASET / script), *args],
                              capture_output=True, text=True, cwd=cwd)

    def test_validate_fixture_is_clean(self):
        result = self._run("validate.py", str(FIXTURE))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        summary = json.loads(result.stdout)
        self.assertEqual(24, summary["accepted"])
        self.assertEqual(0, summary["rejected"])
        self.assertEqual(6, summary["unique_players"])

    def test_evaluate_refuses_tiny_corpus_without_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            scores = Path(tmp) / "scores.jsonl"
            keys = self._run("validate.py", str(FIXTURE), "--emit-keys", str(scores))
            self.assertEqual(0, keys.returncode)
            # rewrite as score rows (score 0.5 constant — fixture only)
            rows = [json.loads(line) for line in Path(scores).read_text().splitlines()]
            score_rows = [{"sample_key": r["sample_key"], "score": 0.5} for r in rows]
            scores.write_text("\n".join(json.dumps(r) for r in score_rows) + "\n")
            result = self._run("evaluate.py", "--corpus", str(FIXTURE), "--scores", str(scores))
            self.assertEqual(2, result.returncode)
            self.assertIn("INSUFFICIENT_DATA", result.stdout)
            result = self._run("evaluate.py", "--corpus", str(FIXTURE), "--scores", str(scores),
                               "--allow-tiny-fixture")
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            report = json.loads(result.stdout)
            self.assertEqual("FIXTURE_ONLY", report["status"])
            self.assertIn("SYNTHETIC", report["fixture_only_notice"])

    def test_ingest_never_assigns_default_labels(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw = Path(tmp) / "raw.jsonl"
            row = json.loads(Path(FIXTURE / "sample.jsonl").read_text().splitlines()[0])
            del row["label"]  # capture missing ground truth — must be refused, never defaulted
            raw.write_text(json.dumps(row) + "\n")
            corpus = Path(tmp) / "corpus"
            result = self._run("ingest.py", "--corpus", str(corpus), "--input", str(raw),
                               "--dataset-version", "0.0.0-test")
            self.assertEqual(1, result.returncode)
            self.assertEqual(0, json.loads(result.stdout)["written"])
            self.assertTrue((corpus / "ingest-log.jsonl").exists())

    def test_ingest_roundtrip_and_dedup(self):
        with tempfile.TemporaryDirectory() as tmp:
            corpus = Path(tmp) / "corpus"
            first = self._run("ingest.py", "--corpus", str(corpus), "--input",
                              str(FIXTURE / "sample.jsonl"), "--dataset-version", "0.0.0-test")
            self.assertEqual(0, first.returncode, first.stdout + first.stderr)
            self.assertEqual(24, json.loads(first.stdout)["written"])
            second = self._run("ingest.py", "--corpus", str(corpus), "--input",
                               str(FIXTURE / "sample.jsonl"), "--dataset-version", "0.0.0-test")
            self.assertEqual(0, json.loads(second.stdout)["written"])


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="vigilac-manifest-"))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.artifact = self.tmp / "model.onnx"
        self.artifact.write_bytes(b"fake-onnx-bytes-for-manifest-tests")

    def test_roundtrip_and_verify(self):
        manifest = self.tmp / "model.manifest"
        vm.write_manifest(manifest, algorithm="ONNX", model_id="m1", artifact_name="model.onnx",
                          artifact_path=self.artifact, feature_schema_hash=vd.SCHEMA_HASH,
                          normalization="none", feature_count=22,
                          created_utc="2026-11-07T00:00:00Z",
                          notes="synthetic fixture test")
        parsed = vm.parse_manifest(manifest)
        self.assertEqual("ONNX", parsed["algorithm"])
        self.assertTrue(vm.verify_artifact(manifest, self.artifact))
        self.artifact.write_bytes(b"tampered")
        self.assertFalse(vm.verify_artifact(manifest, self.artifact))

    def test_rejects_path_traversal_artifact(self):
        with self.assertRaises(vm.ManifestError):
            vm.write_manifest(self.tmp / "m.manifest", algorithm="ONNX", model_id="m1",
                              artifact_name="../escape.onnx", artifact_path=self.artifact,
                              feature_schema_hash=vd.SCHEMA_HASH, normalization="none",
                              feature_count=22, created_utc="2026-11-07T00:00:00Z", notes="x")

    def test_zscore_requires_statistics(self):
        with self.assertRaises(vm.ManifestError):
            vm.write_manifest(self.tmp / "m.manifest", algorithm="ISOLATION_FOREST", model_id="m1",
                              artifact_name="m.vif", artifact_path=self.artifact,
                              feature_schema_hash=vd.SCHEMA_HASH, normalization="zscore",
                              feature_count=22, created_utc="2026-11-07T00:00:00Z", notes="x")


if __name__ == "__main__":
    unittest.main()
