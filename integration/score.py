#!/usr/bin/env python3
"""Scores bot-harness results: parses '/vigilac violations' text captured per
bot (color codes stripped), applies the per-scenario expectations from the bot
result JSONs, prints ::notice annotations and a manifest.json + summary.txt.

Always exits 0: a failing expectation is a finding, not a CI failure.
"""
import json, glob, os, re, sys

VDIR = sys.argv[1]
section = re.compile(r'([a-z][a-z-]+):\s+([0-9]+(?:\.[0-9]+)?)')

def parse_vl(text):
    text = re.sub(r'\xA7[0-9a-fA-FRK-OR]', '', text)  # strip § codes
    pairs = dict(section.findall(text))
    out = {}
    for k, v in pairs.items():
        try:
            out[k] = float(v)
        except ValueError:
            pass
    return out

totals = {'legit_pass': 0, 'legit_fail': 0, 'illegal_pass': 0, 'illegal_fail': 0, 'unable': 0}
manifest = []
for f in sorted(glob.glob(os.path.join(VDIR, '*.json'))):
    with open(f) as fh:
        d = json.load(fh)
    if d.get('scenario') == 'manifest':
        continue
    entry = dict(d)
    vl = parse_vl(d.get('vlText', '') or '')
    entry['vl'] = vl
    if d.get('harnessError'):
        entry['verdict'] = 'UNABLE'
        totals['unable'] += 1
    elif d.get('kind') == 'legit':
        offenders = {k: v for k, v in vl.items() if v > 0}
        entry['verdict'] = 'PASS' if not offenders else 'FAIL'
        entry['offenders'] = offenders
        totals['legit_pass' if not offenders else 'legit_fail'] += 1
    else:
        expected = d.get('expect', [])
        hits = {k: v for k, v in vl.items() if k in expected and v > 0}
        entry['verdict'] = 'PASS' if hits else 'FAIL'
        entry['hits'] = hits
        totals['illegal_pass' if hits else 'illegal_fail'] += 1
    manifest.append(entry)
    msg = json.dumps({'verdict': entry['verdict'], 'vl': vl, 'debug': entry.get('debugLines', [])[:6]})[:900]
    print(f'::notice title=LiveTest-{entry["scenario"]}::{msg}')

with open(os.path.join(VDIR, 'manifest.json'), 'w') as fh:
    json.dump(manifest, fh, indent=1)
summary = (f"legit {totals['legit_pass']}/{totals['legit_pass']+totals['legit_fail']} clean, "
           f"illegal {totals['illegal_pass']}/{totals['illegal_pass']+totals['illegal_fail']} detected, "
           f"unable {totals['unable']}")
with open(os.path.join(VDIR, 'summary.txt'), 'w') as fh:
    fh.write(summary + '\n')
print(f'::notice title=LiveBotSummary::{summary}')
