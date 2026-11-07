# SYNTHETIC — NOT GAMEPLAY DATA

`synthetic_corpus/sample.jsonl` is a hand-generated, deterministic fixture used only to
test the dataset pipeline (ingest/validate/split/evaluate/manifest mechanics). Names,
pings, CPS values and labels are invented by a generator script; they describe no
server, no player and no cheat. Any metric computed against this fixture says nothing
about detection of real cheats. Do not ship this file's contents as a corpus; do not
cite numbers derived from it as detection quality.
