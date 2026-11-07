# Consent-Based Gameplay Telemetry Capture

**Status: NO REAL DATASET EXISTS.** The capture module described here is implemented and
tested, but it has never recorded a single real player. It exists so that a future dataset
(`docs/DATASET.md`) can be built lawfully. Until then there is no accuracy, precision,
recall or false-positive claim to make, and this document makes none.

Stage 3 defined the corpus contract; this module is the only supported way to produce
corpus rows from a live VigilAC deployment without writing one by hand. Capture is
**disabled by default**, inert when misconfigured, and cannot be armed without the server
owner's explicit declarations **and** each player's explicit opt-in.

## 1. What this module is — and is not

| It is | It is not |
|---|---|
| Opt-in feature-vector capture in the Stage 3 JSONL format | A username/UUID/IP/chat logger — none are ever read, stored or emitted |
| Server-owner attested ground truth (`scenario`, `label`, `label-source`, `label-confidence` in config) | A detection feed — a check firing **never** labels a row |
| Bounded, quota-limited, retention-limited local storage | Anything that uploads telemetry |
| Consent revocable at any time, effective on the next capture tick | A hidden background process — disabled unless explicitly enabled; join notice for unconsented players |
| A way to *build* a dataset | A model, an evaluation, or evidence about real cheats |

## 2. Enabling (owner checklist)

All keys live under `capture:` in `config.yml`. Every default ships inert:

```yaml
capture:
  enabled: false                  # master switch; keep false unless running a consented research program
  scenario: ''                    # REQUIRED: what this capture contains, e.g. 'lab-autoclicker-20cps'
  label: ''                       # REQUIRED: LEGITIMATE | SUSPICIOUS | CHEAT_VERIFIED
  label-source: ''                # REQUIRED: SERVER_TELEMETRY_CURATION | CONTROLLED_LAB | MODERATOR_REVIEW | HEURISTIC_FLAG
  label-confidence: -1.0          # REQUIRED: owner attestation in [0,1]; -1 (default) = unset
  client-category: 'UNKNOWN'      # free text <= 64 (e.g. VANILLA, LAB_CHEAT_AUTOCLICK)
  input-category: 'UNKNOWN'       # MOUSE|KEYBOARD|MIXED|CONTROLLER|UNKNOWN — never inferred
  pseudonym-key: ''               # REQUIRED: base64 HMAC key (openssl rand -base64 32); decoded length >= 16 bytes
  pseudonym-rotation-hours: 24    # 0 disables rotation (not recommended)
  interval-seconds: 10            # capture cadence per consented tracked player
  buffer-capacity: 1024           # bounded buffer between scheduler and writer thread (drop-newest + counter)
  max-total-bytes: 268435456      # hard cap on the whole corpus directory; capture HALTS when exceeded
  retention-days: 90              # day-files older than this are purged at startup and via purge-old
  notify-on-join: true            # one-line join notice for players who have not consented
  provenance-note: 'consent-capture/1'
```

Enable only when you can answer **all** of these honestly:

1. `scenario` names a real, isolated collection effort (a lab, a curated farm, a study) — not "the general server".
2. `label`/`label-source`/`label-confidence` are **your** attestation of what the rows contain.
   The plugin never guesses, never upgrades, never downgrades.
3. `CHEAT_VERIFIED` is accepted only with `label-source` ∈ {`CONTROLLED_LAB`, `MODERATOR_REVIEW`}
   **and** `label-confidence >= 0.8`; anything else refuses to arm. `SUSPICIOUS` is valid but is
   excluded from Stage 3 evaluation by design (see `AI_DATASET_EVALUATION.md`).
4. `pseudonym-key` is randomly generated and kept private. Losing it makes pseudonyms rotate
   ungracefully; leaking it de-anonymizes the corpus.

If anything is missing or inconsistent, the module starts **inert**: it logs every problem,
`/vigilac capture status` shows them, and zero rows are written. This is deliberate fail-safe
behaviour, not a bug.

## 3. Player consent flow

- Players run `/vigilac capture consent` to opt in, `/vigilac capture revoke` to opt out.
  `vigilac.capture` permission (default: everyone).
- Unconsented players see a one-line join notice (`capture.notify-on-join: true`).
- Consent is stored **server-side** in `<dataDir>/capture/consent.json` as opaque HMAC-SHA256
  tokens — never a UUID or username. The file contains `{"version":1,"tokens":[...]}` and nothing
  else. If the file is corrupted it is quarantined to `consent.json.corrupt-<timestamp>` and consent
  starts **empty** (fail closed; nobody is silently recorded).
- Consent is per-install, not per-session: it survives relogs until revoked.
- Session pseudonyms are drawn fresh on every login (CSPRNG) and dropped on quit/revoke.
- Player pseudonyms rotate per bucket (`pseudonym-rotation-hours`): rows across buckets are
  unlinkable by construction, which also bounds how long long-term tracking is possible even for consenting players.
- `/vigilac capture delete-all confirm` (admin, `vigilac.capture.admin`, default op) wipes the
  entire corpus and the consent store. `/vigilac capture purge-old` deletes day-files older than
  `retention-days`.

## 4. Data format

Rows are **exactly** the Stage 3 contract (`docs/DATASET.md` §1) — the same key set, order, ranges
and `feature_schema_hash` (`2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688`).
The only additions allowed: an optional `provenance_note` (default `consent-capture/1`).

```json
{"player_pseudonym":"a1b2...32hex","session_pseudonym":"c3d4...32hex","mc_version":"1.21.8",
 "client_category":"VANILLA","input_category":"MOUSE","tps_1m":20.0,"ping_ms":31.0,
 "feature_schema_hash":"2f2b0a3e...","label":"LEGITIMATE","label_source":"SERVER_TELEMETRY_CURATION",
 "label_confidence":0.9,"collected_utc":"2026-11-08T10:15:30Z","scenario":"ci-capture-consent",
 "features":[...22 finite floats...],"provenance_note":"consent-capture/1"}
```

Details:

- `mc_version` is normalized to `X.Y[.Z]` from whatever the server reports
  (e.g. `1.21.8-R0.1-SNAPSHOT` → `1.21.8`, `26.1.2.build.74-stable` → `26.1.2`).
  If no version-shaped token exists, the capture refuses to arm — it will not write rows the
  Stage 3 pipeline would reject.
- `tps_1m` and `ping_ms` are clipped server-side into `[0,20]` / `[0,5000]`; NaN/Inf become 0.0.
- Rows whose feature vector fails `FeatureValidator` (including the all-zero "empty signal" case) are
  **skipped and counted**, never repaired or imputed.
- Timestamps are UTC, `YYYY-MM-DDTHH:MM:SSZ`.

Ingestion: the corpus directory `<dataDir>/capture/corpus/` is the export — `tools/dataset/import_export.py`
et al. operate on these files directly. Nothing else needs to move, and nothing moves automatically.

## 5. Storage, boundedness, shutdown

- Rows are serialized on a dedicated daemon writer thread (`VigilAC-CaptureWriter`), one JSON object
  per line, flushed line-by-line to `capture-YYYYMMDD.jsonl` (**UTC day**, derived from the row's
  `collected_utc` at write time).
- Producer → buffer → writer: the buffer is a fixed-capacity blocking queue drained by the writer.
  When full, new rows are **dropped and counted** (`dropped-full`) — the scheduler never blocks on
  disk and never grows memory unboundedly.
- `max-total-bytes` is a hard cap on the whole corpus directory, recomputed **from disk at startup**
  (a crash cannot reset the accounting). When the next row would exceed it, the writer halts
  fail-safe: rows are dropped and counted (`dropped-quota`), the halt is visible in `/vigilac capture status`,
  and capture resumes only after an admin purges (`purge-old`), raises the cap, or `delete-all`s.
- Retention: `capture-YYYYMMDD.jsonl` files whose embedded date is older than `retention-days` are
  deleted at capture startup and via `/vigilac capture purge-old`. Only files matching
  `capture-\d{8}.jsonl` are ever candidates; anything else is left alone.
- Disable/crash: on shutdown the writer drains the buffer (bounded wait), flushes and closes. A crash
  can truncate at most the in-flight line, which the Stage 3 validator rejects individually.
- Quota and IO problems are surfaced in `/vigilac capture status` and the log. The module never
  throws into the game loop.

## 6. Privacy guarantees (and what they rest on)

- **Never collected:** usernames, UUIDs, IP addresses, chat messages, inventories, skins, account
  identifiers of any kind. The code has no call site that reads them for capture rows; the row
  schema makes them unrepresentable. Unit tests assert the raw UUID never appears in corpus,
  consent store or status output.
- **Pseudonyms:** player = HMAC-SHA256(`player/<bucket>/<uuid>`) truncated to 32 hex; session =
  CSPRNG 32 hex, fresh per login; consent token = HMAC-SHA256(`consent/<uuid>`), stable across
  rotation (otherwise revocation would silently de-consent or re-consent the wrong pseudonym).
- **Rotation:** player pseudonyms change every `pseudonym-rotation-hours`; across buckets rows are
  unlinkable. This is a privacy property, not a dataset ergonomics feature.
- **Consent store:** contains opaque tokens only. Quarantine-on-corruption (never silent reset of consent to "everyone consented").
- **Egress:** none. The capture module performs no network I/O of any kind; there is no upload
  path, no telemetry beacon, no analytics SDK.
- **Regulation posture:** feature-only data can still be personal data in some jurisdictions when
  combined with other context; run a consented program, document it, and honour revocations. The
  plugin's technical guarantees do not replace your legal obligations.

## 7. Commands and permissions

| Command | Permission | Purpose |
|---|---|---|
| `/vigilac capture` | `vigilac.capture` (default: all) | Usage summary |
| `/vigilac capture consent` | `vigilac.capture` | Opt in (player-only) |
| `/vigilac capture revoke` | `vigilac.capture` | Opt out (player-only), effective next tick |
| `/vigilac capture status` | `vigilac.capture` | Flags and counters — never identifiers |
| `/vigilac capture purge-old` | `vigilac.capture.admin` (default: op) | Delete day-files older than retention |
| `/vigilac capture delete-all confirm` | `vigilac.capture.admin` | Wipe corpus **and** consent store |

## 8. Capture never labels

The configured `label` is the owner's attested provenance for the whole scenario: a cheat-lab
capture writes `CHEAT_VERIFIED` because *the lab director says the client cheats*, not because
VigilAC flagged anything. VigilAC's deterministic engine and ML modules are never consulted by the
capture path — there is no code path from a violation to a row's label. Consequence: captured rows
are as good (and as honest) as the owner's declaration. Misdeclare a scenario and the pipeline will
happily ingest labelled garbage — garbage the Stage 3 tooling cannot detect.

## 9. Verification

- Unit tests: `src/test/java/ac/vigil/capture/*` — config gating (CHEAT_VERIFIED source/confidence
  rule, key checks, disabled-by-default), pseudonym rotation/uniqueness/token stability, consent
  store persistence + corruption quarantine, JSON escaping/non-finite rejection, buffer drop-newest
  overflow, quota halt, startup quota re-scan from disk, retention purge, delete-all, full
  consent → tick → row → revoke lifecycle, mc_version normalization, inert failure modes.
- Integration (real Paper server, CI): `integration/bot/scenarios.js` `capture-consent-smoke`
  (`kind: probe`) — a mineflayer bot opts in, generates feature-nonzero combat telemetry at 1-second
  cadence, opts out. With `CAPTURE_CI=1` the live harness hard-verifies the on-disk corpus (parse,
  exact key set, 32-hex pseudonyms, golden schema hash, owner-declared label, 22 finite features,
  no bot username), checks `consent.json` holds tokens only, then runs
  `vigilac capture delete-all confirm` over RCON and asserts the corpus and consent store are wiped.
  Any failure fails the CI job.

## 10. Limitations (read before trusting any row)

- **No real corpus exists.** Nothing here is evidence that capture worked for real players at
  scale; the CI evidence is a synthetic offline bot on an ephemeral server.
- The consent command is exercised through `bot.chat('/vigilac capture consent')` **and**
  `execute as <bot> run vigilac capture consent` (rcon fallback) because mineflayer chat-command
  delivery was not independently verified on this stack; both paths execute as the player and
  exercise the same permission check.
- Capture verifies **plumbing** (consent, rows, schema, bounds, deletion), not the *validity* of any
  model or the *representativeness* of the sample. `AI_DATASET_EVALUATION.md` gates evaluation; real
  validation is `REAL_CLIENT_VALIDATION`, still BLOCKED.
- Corpus is unencrypted at rest. Protect `plugins/VigilAC/capture/` like you protect any dataset
  containing pseudonymous behavioral data.
- Retention is per-file-date, not per-row; `delete-all` is the only row-level erasure mechanism.
- Rotation bucket boundary: a row collected just before a bucket flip and one just after share no
  pseudonym, but both are attributable to the same consent — by design.
- Deterministic detection behavior is unchanged; this module never feeds the engine and is never fed by it.
