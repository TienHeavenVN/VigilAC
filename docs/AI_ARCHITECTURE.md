# VigilAC AI-Assist Architecture

Status legend used across this document and `AI_VERIFICATION.md`:
`IMPLEMENTED` (code exists) · `TESTED` (covered by unit tests) · `CI_VERIFIED` (green on the pushed HEAD) · `REAL_CLIENT_VERIFIED` (never — requires real clients) · `BLOCKED` · `NOT_IMPLEMENTED`.

## 1. What this module is — and is not

The AI-assist module is an **independent, advisory** layer on top of the deterministic anti-cheat. It:

- analyzes behavioral telemetry VigilAC already collects (combat cadence, rotation dynamics, reach statistics, movement residuals, mining, packet timing, ping/TPS context);
- correlates deterministic evidence with (optional) ML anomaly scores;
- renders admin-facing reports through `/vigilac ai ...`;

It never:

- writes violation levels, raises alerts, or triggers warn/kick/punish;
- blocks, cancels or modifies any packet, event or check;
- runs inference on Netty threads or on the main server thread;
- sends telemetry anywhere without an explicit owner configuration (and VigilAC ships **no** LLM provider);

**The deterministic engine is fully authoritative and equally functional with the AI module disabled, misconfigured or 100% broken.** There is intentionally no code path from an ML score to a punishment.

## 2. Hard rules (load-bearing)

1. ML anomaly scores alone can never raise the advisory tier above `LOW` (`AiEvidenceCorrelator`, rules (1)–(4)).
2. `ai.require-deterministic-corroboration: false` is accepted into the parser but **ignored** — corroboration requirement is enforced in code, not config.
3. `ai.shadow-mode: false` is likewise **ignored**; only shadow mode exists.
4. Shadow outputs land in the evidence store under check-name `ai-shadow` and are **excluded** from deterministic evidence counts (no self-corroboration).
5. A model that fails any load check (schema hash mismatch, artifact SHA-256 mismatch, format-version mismatch, normalization mismatch, feature-count mismatch) is marked `unavailable` for that model only; all other models and the deterministic engine continue unaffected.
6. AI never claims a player is cheating. Reports always end with limitations + the advisory disclaimer.
7. No trained model ships with VigilAC. `REAL_CLIENT_VALIDATION` remains **BLOCKED**.

## 3. Module layout (`ac.vigil.ai`)

```
telemetry/   AiFeatureSchema (22 ordered features + schema SHA-256) · AiFeatureSnapshot ·
             FeatureExtractor (pure, from PlayerData trackers) · FeatureValidator ·
             AiContext (clock/ping/TPS/exemption flags)
ml/          ModelAlgorithm · AnomalyModel (manifest()/anomalyScore()/status()) · ModelManifest
             (magic, strict parse, artifact verify, schema match) · ModelRegistry (per-model
             all-or-nothing load; XGBOOST=train-time only) · ModelException
ml/isolationforest/   TrainedForest (VIGILIF1 format, Liu c(n)) · IsolationForestTrainer
             (seeded Random, subsample ≤ 256, maxDepth=ceil(log2 n), MinSamples=8) ·
             IsolationForestModel (AnomalyModel; enforces schema+checksum+normalization consistency)
ml/onnx/     OnnxRuntimeAnomalyModel (reflection-guarded optional adapter;
             UNVERIFIED happy path; documented I/O contract: float[1,22] in, float in [0,1] out)
runtime/     AiShadowWorkerPool (bounded ArrayBlockingQueue, drop-newest + counter,
             per-task timeout watchdog, latency EMA, dedicated daemon workers, stop()) ·
             AiInferenceSink · AiStatus
evidence/    AiEvidenceCorrelator · AiAssessment · AiRiskTier (NONE<LOW<MEDIUM<HIGH) ·
             FalsePositiveRisk (NONE|ELEVATED)
report/      PlayerProfile (+Trend) · AiReportBuilder (limitations footer embedded) · TelemetryRedactor
assistant/   AdminAssistant (offline templates; LLM optional) · LlmProvider · NoopLlmProvider (default, shipped)
ai/          AiConfig (+ ConfigSource/BukkitConfigSource) · AiServices (lifecycle: load → scan tick →
             assessment/report on demand → stop) · services wired in VigilAC.java
```

## 4. Feature schema (v1, 22 features — fixed order)

| # | feature | source | range (clip) |
|---|---------|--------|--------------|
| 0 | cps_1s | CombatTracker.cps(1s) | 0–50 |
| 1 | cps_5s | CombatTracker.cps(5s) | 0–50 |
| 2 | click_interval_cv | CombatTracker.intervalCv | 0–5 |
| 3 | click_interval_mean_ms | CombatTracker.intervalMean | 0–10000 |
| 4 | click_interval_stddev_ms | CombatTracker.intervalStddev | 0–10000 |
| 5 | click_interval_entropy_bits | CombatTracker.intervalEntropyBits(100) | 0–16 |
| 6 | distinct_targets_10s | CombatTracker.distinctTargets(10s) | 0–32 |
| 7 | swing_rate_1s | SwingTracker.swingRate(1s) | 0–50 |
| 8 | rotation_angular_distance_1s | RotationTracker.angularDistanceCovered(1s) | 0–5000 |
| 9 | rotation_max_delta_1s | RotationTracker.maxDeltaInWindow(1s) | 0–180 |
| 10 | rotation_samples_1s | RotationTracker.size(1s) | 0–64 |
| 11 | reach_ema | PlayerData.reachEma | 0–8 |
| 12 | reach_sample_count | PlayerData.getReachSampleCount | 0–100000 |
| 13 | packets_per_second | PlayerData.getPacketsPerSecond | 0–400 |
| 14 | raw_move_packets_per_second | MovementTracker.rawMovePacketsPerSecond | 0–400 |
| 15 | move_packets_per_second | MovementTracker.movePacketsPerSecond | 0–400 |
| 16 | airborne_millis | MovementTracker.airborneMillis | 0–3.6M |
| 17 | break_interval_ms (0 ⇒ unknown) | BlockActionTracker.breakInterval | 0–60000 |
| 18 | impossible_break_streak | BlockActionTracker.getImpossibleBreakStreak | 0–100 |
| 19 | ping_ms | monitor.pingEma | 0–5000 |
| 20 | tps_1m | Server.getTPS()[0] | 0–20 |
| 21 | on_ground | monitor.isOnGround | 0/1 |

NaN/∞ map to the documented range floor (never a guess). Snapshots failing `FeatureValidator` (wrong count, non-finite, out-of-range, all-zero "empty signal") are rejected before inference and counted.

## 5. Runtime contract

- Construction: after the violation pipeline and listeners, before the maintenance timer (`VigilAC.onEnable`); stop: first in `onDisable` (pool interrupt + history clear) before scheduler cancellation and DB close.
- Scan tick: every `ai.inference-interval-seconds` (default 5), ALL tracked players; extract → validate → enqueue. O(bounded windows) per player, no packet-path work.
- Queue: `ArrayBlockingQueue` (default 128), `offer()` only — full ⇒ drop + `droppedFull` counter. Workers: dedicated daemon threads (default 1, max 4), each job timed; over `ai.inference-timeout-millis` (default 250 ms) the result is discarded and counted (`timedOut`) — this is a watchdog + accounting boundary, not a preemption guarantee for native code.
- Every sink invocation is wrapped in try/catch; any `Throwable` from a model becomes `failed` + log, never a crashed worker.
- Latency EMA (α=0.2) + counters exposed via `/vigilac ai status`.
- `AiServices.stop()` interrupts workers and discards pending jobs; no inference outlives the plugin.

## 6. Model interface & manifests

`AnomalyModel`: `manifest()`, `modelId()`, `anomalyScore(snapshot)`, `available()`, `status()`. Implementations apply their own normalization (models are self-contained).

`ModelManifest` (`VigilAC-ModelManifest/1`): modelId, algorithm, trainedUtc, featureCount (must equal schema), featureSchemaHash (must equal `AiFeatureSchema.schemaHash()`), artifact, sha256 (of artifact bytes), normalization `NONE|ZSCORE` (+22 finite means/stddevs for ZSCORE), license, trainingSummary (**required non-empty** — provenance is mandatory), threshold. Parser rejects paths (`/`, `\`, `..`), non-hex/sha256, unknown algorithms, format-version ≠ 1. `verifyArtifact()` hashes exactly the bytes that will be loaded.

`TrainedForest` format (`VIGILIF1`): population mean/stddev (for ZSCORE), per-tree node arrays, depth limit `ceil(log2(subsample))`, Liu's `c(n) = 2(ln(n−1)+γ) − 2(n−1)/n` with `c(≤1)=1.0`; anomaly score `2^(−E(h)/c(n))` in [0,1]. Loader validates counts/bounds/ordering; round-trip (train → write → read → score) is bit-exact and covered by tests.

ONNX: optional `onnxruntime` must be present on the server classpath (reflection-guarded); model contract = one float tensor `[1,22]` named `input`, first output float in [0,1]. **Happy path UNVERIFIED — no model exists in this repo.**

## 7. Evidence correlator (Stage 5)

Inputs: bounded 90 s deterministic evidence window (evidence store, `ai-shadow` entries excluded), optional ML score + model id, ping EMA, last velocity/teleport exemption timestamps, combat-interval sample count. Rules (exhaustive):

1. `det == 0`, ML < T → **NONE**.
2. `det == 0`, ML ≥ T → **LOW** (hard cap; ML alone never exceeds LOW).
3. `det > 0`, ML < T or absent → **LOW** (deterministic engine carries the case).
4. `det > 0`, ML ≥ T → **MEDIUM**; **HIGH** only when distinct checks ≥ 2 or signals ≥ 3. Any elevated false-positive signal (ping > 300 ms, fresh velocity/teleport exemption ≤ 5 s, samples < 20) downgrades one tier and says so in the reasons.

Every assessment carries ≥ 1 concrete, traceable reason with the actual numbers; `AiAssessment.DISCLAIMER` is always included; suggested actions are investigations, never punishments.

## 8. Admin assistant (Stage 6)

Offline templates always available when `ai.admin-assistant.enabled=true` (default false): summarize player, explain assessment, false-positive reading, recommended investigation. LLM path is optional, **disabled by default**, redacts identifiers (UUID/IPv4/target name) when `llm.redact-identifiers=true` (default), and ships with **no provider** (`NoopLlmProvider` → offline fallback). There is no shipped code that can ban or kick.

## 9. Failure matrix (all degrade to "deterministic engine continues")

| Failure | Result |
|---|---|
| `ai.enabled=false` (default) | No model load, no inference, zero overhead; `/vigilac ai` shows disabled status |
| Models dir missing/empty | Pool idles; correlator operates on deterministic evidence only |
| Model unreadable/corrupt/schema drift/checksum fail | That model unavailable; logged; others continue |
| ONNX Runtime absent | ONNX models unavailable with explicit reason |
| Inference throws/timeout | Job counted `failed`/`timedOut`; worker survives |
| Queue full | Drop + counter; producer (scheduler) never blocks |
| Player data unavailable at sink time | Shadow evidence skipped silently; score still recorded in history? (No — skipped entirely) |

## 10. What is deliberately NOT here

- No punishment path, no VL writes, no ban/kick logic (by design, enforced by code absence).
- No training dataset, no trained production model, no detection-accuracy claims.
- No LLM provider implementation, no API keys, no network egress.
- No Netty-thread inference, no main-thread blocking, no unbounded queues.
- No fabricated telemetry: unknown values are clipped floors, never interpolations.
