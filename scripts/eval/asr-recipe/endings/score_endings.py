#!/usr/bin/env python3
"""Scores Golden.kt output against make_stress_set.py's expected.jsonl (#453). Fails closed.

A take LOST ITS ENDING when, after aligning output words to reference words (Levenshtein, lowest cost, deletions
preferred last), the reference ends in a run of two or more deleted words, or its final word is deleted with nothing
after it, or half or more of the final utterance's words are deleted. A substitution is a recognition error, not
clipping, and is reported only through the word error rate. A lost START is the same test on the first words.
Silence takes must produce no words.

Usage: score_endings.py <expected.jsonl> <golden-out.jsonl> [--json <summary.json>]
Exit 0 only when every expected take has an output line, no ending or start is lost, and silence stays empty.
"""
import json
import re
import sys


def words(text: str) -> list[str]:
    return [w for w in re.sub(r"[^a-z0-9' ]+", " ", text.lower().replace("-", " ")).split() if w.strip("'")]


def join_compounds(ref: list[str], hyp: list[str]) -> list[str]:
    """Two reference words the output wrote as one ("boar hound" / "boarhound") count as one word, not a deletion."""
    written = set(hyp)
    out, i = [], 0
    while i < len(ref):
        if i + 1 < len(ref) and ref[i] + ref[i + 1] in written:
            out.append(ref[i] + ref[i + 1]); i += 2
        else:
            out.append(ref[i]); i += 1
    return out


def align(ref: list[str], hyp: list[str]) -> list[str]:
    """Edit operations over the reference: 'ok', 'sub', 'del' per reference word, plus 'ins' entries."""
    n, m = len(ref), len(hyp)
    d = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        d[i][0] = i
    for j in range(m + 1):
        d[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            d[i][j] = min(d[i - 1][j - 1] + (ref[i - 1] != hyp[j - 1]), d[i - 1][j] + 1, d[i][j - 1] + 1)
    ops, i, j = [], n, m
    # Walking back from the end, an exact match wins, then a deletion: on a tie, missing words at the end are
    # reported as missing, never as substitutions, so a clipped ending cannot hide inside the error rate.
    while i or j:
        if i and j and ref[i - 1] == hyp[j - 1] and d[i][j] == d[i - 1][j - 1]:
            ops.append("ok"); i -= 1; j -= 1
        elif i and d[i][j] == d[i - 1][j] + 1:
            ops.append("del"); i -= 1
        elif j and d[i][j] == d[i][j - 1] + 1:
            ops.append("ins"); j -= 1
        else:
            ops.append("sub"); i -= 1; j -= 1
    return ops[::-1]


def edge_loss(ops: list[str], final_len: int) -> tuple[bool, int]:
    ref_ops = [o for o in ops if o != "ins"]
    run = 0
    for o in reversed(ref_ops):
        if o != "del":
            break
        run += 1
    trailing_ins = 0
    for o in reversed(ops):
        if o != "ins":
            break
        trailing_ins += 1
    final_deleted = sum(1 for o in ref_ops[-final_len:] if o == "del") if final_len else 0
    lost = run >= 2 or (run >= 1 and trailing_ins == 0) or (final_len and final_deleted * 2 >= final_len)
    return bool(lost), run


def main() -> int:
    expected = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
    outputs = {}
    for l in open(sys.argv[2]):
        if l.strip():
            o = json.loads(l)
            outputs[o["name"]] = o
    missing, lost_end, lost_start, noisy, rows = [], [], [], [], []
    total_ref = total_err = 0
    for e in expected:
        o = outputs.get(e["name"])
        if o is None:
            missing.append(e["name"])
            continue
        hyp = words(o["text"])
        if e["kind"] == "silence":
            if hyp:
                noisy.append(e["name"])
            continue
        ref = join_compounds(words(e["reference"]), hyp)
        ops = align(ref, hyp)
        errors = sum(1 for x in ops if x != "ok")
        total_ref += len(ref); total_err += errors
        end_lost, end_run = edge_loss(ops, len(join_compounds(words(e["last"]), hyp)))
        start_lost, _ = edge_loss(ops[::-1], len(join_compounds(words(e["first"]), hyp)))
        if end_lost:
            lost_end.append({"name": e["name"], "deletedAtEnd": end_run, "expectedEnd": " ".join(ref[-6:]),
                             "gotEnd": " ".join(hyp[-6:])})
        if start_lost:
            lost_start.append(e["name"])
        rows.append({"name": e["name"], "seconds": e["seconds"], "ms": o.get("ms"), "errors": errors, "refWords": len(ref)})
    endings = sum(1 for e in expected if e["kind"] == "ending")
    summary = {"takes": len(expected), "endingTakes": endings, "missingOutput": missing, "lostEndings": lost_end,
               "lostStarts": lost_start, "silenceWithWords": noisy,
               "wer": round(total_err / total_ref, 4) if total_ref else None}
    print(f"endings lost {len(lost_end)}/{endings}, starts lost {len(lost_start)}/{endings}, "
          f"silence with words {len(noisy)}, missing output {len(missing)}, WER {summary['wer']}")
    for x in lost_end:
        print(f"  LOST END {x['name']}: expected ...{x['expectedEnd']} | got ...{x['gotEnd']}")
    if "--json" in sys.argv:
        json.dump({**summary, "rows": rows}, open(sys.argv[sys.argv.index("--json") + 1], "w"), indent=1)
    ok = not (missing or lost_end or lost_start or noisy) and endings > 0
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
