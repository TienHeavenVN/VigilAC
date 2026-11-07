"""Read/write/verify VigilAC-ModelManifest/1 files (Python side of the contract).

The Java side (ac.vigil.ai.ml.ModelManifest) is authoritative for runtime loading;
this module exists so the training pipeline can EMIT manifests that the plugin
accepts byte-for-byte, and so tooling can pre-verify them. Format (UTF-8,
line-based, key=value):

    VigilAC-ModelManifest/1
    algorithm=ISOLATION_FOREST|ONNX|XGBOOST
    formatVersion=1
    modelId=...
    artifact=<plain file name, no path separators, no '..'>
    sha256=<64 lowercase hex of artifact bytes>
    featureSchemaHash=<64 hex of AiFeatureSchema names>
    featureCount=22
    normalization=none|zscore
    means=<22 csv doubles>   (zscore only)
    stddevs=<22 csv doubles> (zscore only)
    createdUtc=<ISO-8601>
    notes=<free text; MUST state validation status and dataset provenance>
"""

from __future__ import annotations

import hashlib
from pathlib import Path

MAGIC = "VigilAC-ModelManifest/1"
SUPPORTED_FORMAT_VERSION = 1
ALGORITHMS = ("ISOLATION_FOREST", "ONNX", "XGBOOST")


class ManifestError(ValueError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(path: Path, *, algorithm: str, model_id: str, artifact_name: str,
                   artifact_path: Path, feature_schema_hash: str,
                   normalization: str, feature_count: int,
                   created_utc: str, notes: str,
                   means: list[float] | None = None, stddevs: list[float] | None = None) -> dict:
    if algorithm not in ALGORITHMS:
        raise ManifestError(f"unknown algorithm {algorithm!r}")
    if "/" in artifact_name or "\\" in artifact_name or ".." in artifact_name:
        raise ManifestError("artifact must be a plain file name")
    normalization = normalization.lower()
    if normalization not in ("none", "zscore"):
        raise ManifestError("normalization must be none|zscore")
    if normalization == "zscore":
        if not means or not stddevs or len(means) != feature_count or len(stddevs) != feature_count:
            raise ManifestError("zscore requires means/stddevs of feature_count entries")
    lines = [
        MAGIC,
        f"algorithm={algorithm}",
        f"formatVersion={SUPPORTED_FORMAT_VERSION}",
        f"modelId={model_id}",
        f"artifact={artifact_name}",
        f"sha256={sha256_file(artifact_path)}",
        f"featureSchemaHash={feature_schema_hash.lower()}",
        f"featureCount={feature_count}",
        f"normalization={normalization}",
    ]
    if normalization == "zscore":
        lines.append("means=" + ",".join(repr(float(v)) for v in means))
        lines.append("stddevs=" + ",".join(repr(float(v)) for v in stddevs))
    lines.append(f"createdUtc={created_utc}")
    lines.append(f"notes={notes}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return {"manifest": str(path), "artifact_sha256": sha256_file(artifact_path)}


def parse_manifest(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != MAGIC:
        raise ManifestError(f"{path}: missing magic line")
    out: dict[str, str] = {}
    for lineno, raw in enumerate(lines[1:], start=2):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        eq = line.find("=")
        if eq <= 0:
            raise ManifestError(f"{path}:{lineno}: malformed line {line!r}")
        key = line[:eq].strip().lower()
        if key in out:
            raise ManifestError(f"{path}:{lineno}: duplicate key {key!r}")
        out[key] = line[eq + 1:].strip()
    for required in ("algorithm", "formatversion", "modelid", "artifact", "sha256",
                     "featureschemahash", "featurecount", "normalization"):
        if required not in out:
            raise ManifestError(f"{path}: missing key {required!r}")
    if out["algorithm"] not in ALGORITHMS:
        raise ManifestError(f"{path}: unknown algorithm {out['algorithm']!r}")
    if out["formatversion"] != str(SUPPORTED_FORMAT_VERSION):
        raise ManifestError(f"{path}: unsupported formatVersion {out['formatversion']!r}")
    artifact = out["artifact"]
    if "/" in artifact or "\\" in artifact or ".." in artifact:
        raise ManifestError(f"{path}: artifact is not a plain file name")
    for key in ("sha256", "featureschemahash"):
        value = out[key].lower()
        if len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
            raise ManifestError(f"{path}: key {key!r} is not 64 hex chars")
    return out


def verify_artifact(manifest_path: Path, artifact_path: Path) -> bool:
    manifest = parse_manifest(manifest_path)
    return sha256_file(artifact_path).lower() == manifest["sha256"].lower()
