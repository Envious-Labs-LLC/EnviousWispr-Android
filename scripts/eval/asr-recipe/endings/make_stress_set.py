#!/usr/bin/env python3
"""Lost-endings stress set (#453), rebuilt from public LibriSpeech speech after the #374/#379 set was lost.

Builds takes whose LAST words land on the places the recipe can drop them: very short takes, just under / at / just
over the 15 s window, the two-window seam (about 28 s), long multi-window takes, a short fast clause after a pause, and
speech that runs right to the last sample. Each take records the reference words of its final utterance so
`score_endings.py` can check they came out. Deterministic: same corpus in, same set out.

Usage: make_stress_set.py <corpus-dir> <out-dir>
  <corpus-dir> holds development-complete.jsonl and audio/<id>.pcm (16 kHz mono s16le), the #433 corpus.
Writes <out-dir>/wav/*.wav, <out-dir>/list.tsv (Golden.kt input) and <out-dir>/expected.jsonl.
No audio is committed; regenerate it from the corpus.
"""
import json
import random
import struct
import sys
import wave
from pathlib import Path

RATE = 16_000
TARGET_SECONDS = [1.2, 2.0, 3.0, 5.0, 8.0, 12.0, 14.0, 14.6, 14.9, 15.0, 15.1, 15.4, 16.0, 20.0, 26.0, 27.5, 28.0,
                  28.5, 30.0, 40.0, 42.0, 45.0, 55.0, 60.0]
TAILS_MS = [0, 120, 400]          # silence after the last word; 0 means speech runs to the final sample
LEADS_MS = [0, 700]               # silence before the first word, shifting where the ending falls
GAP_MS = 350                      # pause between utterances


def read_pcm(path: Path) -> list[int]:
    data = path.read_bytes()
    return list(struct.unpack(f"<{len(data) // 2}h", data))


def trim(samples: list[int], threshold: int = 300, pad: int = 160) -> list[int]:
    """Drop leading and trailing near-silence so a tail of 0 ms really ends on speech."""
    loud = [i for i, s in enumerate(samples) if abs(s) > threshold]
    if not loud:
        return samples
    return samples[max(0, loud[0] - pad): min(len(samples), loud[-1] + pad)]


NOISE = random.Random(379)


def silence(ms: int, floor: int = 24) -> list[int]:
    """A quiet room, not digital zero: a real microphone never returns exact zeros, and exact zeros change the
    preprocessor's normalisation. `floor=0` gives true digital silence for the silence-only takes."""
    return [int(NOISE.gauss(0, floor)) if floor else 0 for _ in range(RATE * ms // 1000)]


def write_wav(path: Path, samples: list[int]) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(struct.pack(f"<{len(samples)}h", *samples))


def main() -> None:
    corpus, out = Path(sys.argv[1]), Path(sys.argv[2])
    rows = [json.loads(l) for l in (corpus / "development-complete.jsonl").read_text().splitlines() if l.strip()]
    # Only public human speech with an independent reference; founder audio and noise controls have none.
    rows = [r for r in rows if r.get("gold") and r["source"].startswith("LibriSpeech")]
    audio = {r["id"]: trim(read_pcm(corpus / "audio" / f"{r['id']}.pcm")) for r in rows}
    shorts = sorted(rows, key=lambda r: len(audio[r["id"]]))[:20]   # quick final clauses
    (out / "wav").mkdir(parents=True, exist_ok=True)
    rng = random.Random(453)
    listing, expected = [], []

    def emit(name: str, parts: list[dict], lead: int, tail: int, kind: str) -> None:
        samples = silence(lead)
        for i, r in enumerate(parts):
            if i:
                samples += silence(GAP_MS)
            samples += audio[r["id"]]
        samples += silence(tail)
        path = out / "wav" / f"{name}.wav"
        write_wav(path, samples)
        listing.append(f"{kind}\t{name}\t{path}")
        expected.append({"name": name, "kind": kind, "seconds": round(len(samples) / RATE, 3), "leadMs": lead,
                         "tailMs": tail, "first": parts[0]["gold"], "last": parts[-1]["gold"],
                         "reference": " ".join(p["gold"] for p in parts), "sources": [p["id"] for p in parts]})

    for target in TARGET_SECONDS:
        for lead in LEADS_MS:
            for tail in TAILS_MS:
                for variant in ("plain", "fastclause"):
                    want = int(target * RATE) - RATE * (lead + tail) // 1000
                    final = rng.choice(shorts) if variant == "fastclause" else rng.choice(rows)
                    body, length = [], len(audio[final["id"]])
                    pool = [r for r in rows if r["id"] != final["id"]]
                    rng.shuffle(pool)
                    for r in pool:
                        extra = len(audio[r["id"]]) + RATE * GAP_MS // 1000
                        if length + extra > want:
                            continue
                        body.append(r)
                        length += extra
                    parts = body + [final]
                    emit(f"t{target:05.1f}-l{lead}-e{tail}-{variant}", parts, lead, tail, "ending")

    # Silence and near-silence must produce no words.
    for seconds in (1.0, 5.0, 16.0):
        path = out / "wav" / f"silence-{seconds:04.1f}.wav"
        write_wav(path, silence(int(seconds * 1000), floor=0 if seconds < 2 else 24))
        listing.append(f"silence\tsilence-{seconds:04.1f}\t{path}")
        expected.append({"name": f"silence-{seconds:04.1f}", "kind": "silence", "seconds": seconds, "reference": ""})

    (out / "list.tsv").write_text("\n".join(listing) + "\n")
    (out / "expected.jsonl").write_text("\n".join(json.dumps(e) for e in expected) + "\n")
    print(f"{len(listing)} takes ({sum(1 for e in expected if e['kind'] == 'ending')} ending, "
          f"{sum(1 for e in expected if e['kind'] == 'silence')} silence) -> {out}")


if __name__ == "__main__":
    main()
