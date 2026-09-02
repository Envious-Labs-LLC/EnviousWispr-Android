#!/usr/bin/env python3
"""Lists every model id each provider key returns and reports which carry a dated snapshot suffix.

The regeneration command behind `ModelNotes.withoutSnapshot`'s measured counts, so that comment names a
command rather than publishing numbers nothing keeps correct
(workflow-process.md RULE: prose-carries-the-same-evidence-burden-as-code).

    ~/.claude/bin/get-key launch openai-api-key OPENAI_API_KEY -- \
    ~/.claude/bin/get-key launch anthropic-api-key ANTHROPIC_API_KEY -- \
    ~/.claude/bin/get-key launch gemini-api-key GEMINI_API_KEY -- \
    python3 scripts/model-id-shapes.py

Prints ids only. No key material is read, printed, or written.
"""
import json, os, re, urllib.request, urllib.error
def get(url, headers):
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r: return json.loads(r.read().decode())
    except Exception as e: return {"err": type(e).__name__}
ids = {}
d = get("https://api.openai.com/v1/models", {"Authorization": f"Bearer {os.environ['OPENAI_API_KEY']}"})
ids["OPENAI"] = sorted(m["id"] for m in d.get("data", []))
d = get("https://api.anthropic.com/v1/models?limit=100", {"x-api-key": os.environ["ANTHROPIC_API_KEY"], "anthropic-version": "2023-06-01"})
ids["CLAUDE"] = sorted(m["id"] for m in d.get("data", []))
d = get("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200", {"x-goog-api-key": os.environ["GEMINI_API_KEY"]})
ids["GEMINI"] = sorted(m["name"].replace("models/", "") for m in d.get("models", []))

SNAP = re.compile(r"-(\d{4})-?(\d{2})-?(\d{2})$")
for prov, lst in ids.items():
    print(f"\n== {prov}: {len(lst)} ids ==")
    dated = [i for i in lst if SNAP.search(i)]
    print(f"  ids ending in a date-shaped suffix: {len(dated)}")
    for i in dated[:12]: print(f"    {i}  ->  {SNAP.sub('', i)}")
    # Every OTHER trailing-number shape, which is what a normaliser must NOT touch.
    trailing = sorted({re.sub(r'.*?(-[\d.]+)$', r'\1', i) for i in lst if re.search(r"-[\d.]+$", i) and not SNAP.search(i)})
    print(f"  other trailing-number shapes that must survive: {trailing[:20]}")
