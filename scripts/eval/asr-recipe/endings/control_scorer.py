#!/usr/bin/env python3
"""Two-way control for score_endings.py (#453): proves the scorer can see a lost ending before its silence is trusted.

From a real Golden.kt output it writes two variants and scores each: the last two words removed from every ending
take (every one must be flagged), and the first two removed (every one must be flagged as a lost start). Silence
takes are given a word (all must be flagged). Exit 0 only when each injected defect is caught on every take.

Usage: control_scorer.py <expected.jsonl> <golden-out.jsonl> <scratch-dir>
"""
import json
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def variant(outputs, edit, path):
    with open(path, "w") as f:
        for o in outputs:
            f.write(json.dumps({**o, "text": edit(o)}) + "\n")


def score(expected, path):
    out = Path(str(path) + ".score.json")
    subprocess.run([sys.executable, str(HERE / "score_endings.py"), expected, str(path), "--json", str(out)],
                   capture_output=True, text=True)
    return json.load(open(out))


def main() -> int:
    expected, golden, scratch = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    scratch.mkdir(parents=True, exist_ok=True)
    outputs = [json.loads(l) for l in open(golden) if l.strip()]
    kinds = {json.loads(l)["name"]: json.loads(l)["kind"] for l in open(expected) if l.strip()}
    endings = sum(1 for k in kinds.values() if k == "ending")
    silences = sum(1 for k in kinds.values() if k == "silence")

    def cut_end(o):
        return " ".join(o["text"].split()[:-2]) if kinds[o["name"]] == "ending" else "word"

    def cut_start(o):
        return " ".join(o["text"].split()[2:]) if kinds[o["name"]] == "ending" else "word"

    # A take whose edge the engine already lost cannot be lost again, so the denominator is the intact takes.
    base = score(expected, Path(golden))
    intact_end = {n for n, k in kinds.items() if k == "ending"} - {x["name"] for x in base["lostEndings"]}
    intact_start = {n for n, k in kinds.items() if k == "ending"} - set(base["lostStarts"])
    variant(outputs, cut_end, scratch / "control-end.jsonl")
    variant(outputs, cut_start, scratch / "control-start.jsonl")
    end, start = score(expected, scratch / "control-end.jsonl"), score(expected, scratch / "control-start.jsonl")
    caught_end = len(intact_end & {x["name"] for x in end["lostEndings"]})
    caught_start = len(intact_start & set(start["lostStarts"]))
    caught_silence = len(end["silenceWithWords"])
    print(f"injected lost endings caught {caught_end}/{len(intact_end)}; lost starts caught "
          f"{caught_start}/{len(intact_start)}; silence-with-words caught {caught_silence}/{silences} "
          f"({endings} ending takes)")
    ok = caught_end == len(intact_end) > 0 and caught_start == len(intact_start) > 0 and caught_silence == silences
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
