#!/usr/bin/env python3
"""Play known sounds into the EMULATOR and measure what the recorder's rail drew, frame by frame.

The rail is a picture of the voice: each bar a pitch band, lowest in the middle, highest at the edges
(#151). A spoken sentence proves it moves; it cannot prove it moves RIGHT, because nobody knows what
the sentence's bars should be. A pure tone can: its band is known, so the bar that must light is known,
and so is every bar that must not. This plays a set of such sounds into the emulator's microphone
over the emulator's gRPC `injectAudio` channel (`device-testing.md` RULE:
feed-emulator-audio-over-grpc-injectaudio-not-blackhole: the cable is the fallback, and touching the
Mac's input devices while the emulator runs can kill its microphone for the session), screen-records
the pill for each (RULE: record-the-screen-through-a-uat-take), cuts the recording into frames,
measures every bar's height by its pixels, and judges each sound against what the rail should have
drawn.

    scripts/uat/rail_signals.py                # every signal, then a verdict per signal
    scripts/uat/rail_signals.py tone1k noise   # a subset, by name

Emulator only, launched with `-grpc 8554` (scripts/uat/launch-grpc.sh). The take is driven through the
same exported trampoline the side button uses and ENDED in a finally whatever happens in between. The
host microphone is turned off once, up front, because the injected stream IS the microphone. Needs
grpcurl, ffmpeg, numpy and Pillow on the Mac. Nothing plays out of the speakers.

Proven by hand first (founder 2026-09-13: scripts only after the pathway is proven): a 1 kHz tone
injected during a take arrived in the app's own capture file as a clean 1.7 s tone at -17 dBFS
(2026-09-15), and the published picture read 0.88 in its band for the whole tone.
"""
import base64
import json
import math
import os
import subprocess
import sys
import time

import numpy as np
from PIL import Image

os.environ["WISPR_SERIAL"] = "emulator-5554"
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import wispr_eyes as w  # noqa: E402

SERIAL = "emulator-5554"
ADB = ["adb", "-s", SERIAL, "shell"]
RATE = 48_000  # the injection channel takes 48 kHz S16 mono; the app resamples to 16 kHz
GRPC = "localhost:8554"
PROTO_DIR = os.path.expanduser("~/Android/sdk/emulator/lib")
OUT = os.path.join(os.environ.get("RAIL_SIGNALS_OUT", "/tmp/rail-signals"))
FPS = 20

# The bands the analyser uses, mirrored from SpectrumAnalyzer: band 0 is 85-170 Hz, then ten log bands
# to 6400 Hz. Written out rather than imported so the oracle is not the subject
# (validation-discipline.md RULE: an-expectation-built-with-the-mechanism-under-test-cannot-fail).
BAND_COUNT = 11
EDGES = [85.0, 170.0] + [170.0 * (6400.0 / 170.0) ** (k / 10) for k in range(1, 11)]
TAP_PILL_BARS = 11

# The rail's resting dot and full bar as shares of the pill's height, measured 2026-09-15 on the
# 720x1600 emulator recording (96 px pill: 6 px at rest, 36 px full). The rail draws SILENCE_FRACTION
# (0.14) of its height at rest, so these two pin the rail at 3/8 of the pill.
REST_SHARE = 6 / 96
PEAK_SHARE = 36 / 96


def band_of(hz):
    for b in range(BAND_COUNT):
        if EDGES[b] <= hz < EDGES[b + 1]:
            return b
    raise ValueError(hz)


def bar_for_band(band, count):
    """The rail's own mapping, restated: the right-half bar that shows `band` on a rail of `count` bars."""
    if count <= 2:
        return count - 1
    if count % 2 == 1:
        mid = (count - 1) // 2
        return mid + round_half_up(band / (BAND_COUNT - 1) * mid)
    side = count // 2 - 1
    return count // 2 + round_half_up(band / (BAND_COUNT - 1) * side)


def round_half_up(x):
    return int(math.floor(x + 0.5))


def bars_for_band(band, count):
    right = bar_for_band(band, count)
    return sorted({right, count - 1 - right})


def bands_of_bar(index, count):
    mirrored = count - 1 - index if index < count / 2 else index
    return [b for b in range(BAND_COUNT) if bar_for_band(b, count) == mirrored]


# ------------------------------------------------------------------------------------------------
# Signals
# ------------------------------------------------------------------------------------------------

def tone(hz, seconds, amplitude=0.1):
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    return amplitude * np.sin(2 * math.pi * hz * t)


def silence(seconds):
    return np.zeros(int(RATE * seconds))


def sweep(lo, hi, seconds, amplitude=0.1):
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    # Exponential sweep: equal time per octave, so it crosses the log bands at an even pace.
    k = math.log(hi / lo)
    phase = 2 * math.pi * lo * seconds / k * (np.exp(t / seconds * k) - 1)
    return amplitude * np.sin(phase)


def noise(seconds, amplitude=0.05, seed=7):
    rng = np.random.default_rng(seed)
    return amplitude * rng.standard_normal(int(RATE * seconds))


def bursts(hz, seconds, per_second=4, amplitude=0.1):
    """A tone switched on and off: half a period on, half off."""
    n = int(RATE * seconds)
    t = np.arange(n) / RATE
    gate = ((t * per_second) % 1.0) < 0.5
    return amplitude * np.sin(2 * math.pi * hz * t) * gate


SIGNALS = {
    "silence": ("two seconds of nothing", lambda: silence(2.0)),
    "tone120": ("120 Hz, a low voice's fundamental", lambda: tone(120, 2.0)),
    "tone1k": ("1 kHz", lambda: tone(1000, 2.0)),
    "tone5k": ("5 kHz, the sibilant band", lambda: tone(5000, 2.0)),
    "sweep": ("100 Hz to 6 kHz in three seconds", lambda: sweep(100, 6000, 3.0)),
    "noise": ("flat hiss that starts mid-take, four seconds", lambda: noise(4.0)),
    "bursts": ("300 Hz switched on and off four times a second", lambda: bursts(300, 2.5)),
}


def write_packets(path, samples):
    """The signal as newline-delimited AudioPackets for `injectAudio`: ~100 ms each, timestamped."""
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    chunk = 9600  # 100 ms at 48 kHz S16 mono
    t0 = int(time.time() * 1_000_000)
    with open(path, "w") as f:
        for n, i in enumerate(range(0, len(pcm), chunk)):
            f.write(json.dumps({
                "format": {"samplingRate": RATE, "channels": "Mono", "format": "AUD_FMT_S16"},
                "timestamp": t0 + n * 100_000,
                "audio": base64.b64encode(pcm[i:i + chunk]).decode(),
            }) + "\n")
    return len(pcm) / (RATE * 2)


# ------------------------------------------------------------------------------------------------
# Driving the emulator
# ------------------------------------------------------------------------------------------------

def inject(packets):
    """Stream the packets into the emulator's microphone. The take must already be open."""
    with open(packets) as f:
        done = subprocess.run(["grpcurl", "-plaintext", "-import-path", PROTO_DIR, "-proto", "emulator_controller.proto",
                               "-d", "@", GRPC, "android.emulation.control.EmulatorController/injectAudio"],
                              stdin=f, capture_output=True, text=True, timeout=120)
    if done.returncode != 0:
        raise RuntimeError(f"injectAudio failed: {done.stderr.strip()[:200]}")


def record_take(name, packets, seconds, lead=1.5, tail=1.5):
    """One take: the pill up, the sound into the microphone, the pill down. Returns the video path."""
    video = f"/sdcard/rail-{name}.mp4"
    limit = int(math.ceil(lead + seconds + tail + 2.5))
    w.clear_log()
    rec = subprocess.Popen(ADB + [f"screenrecord --time-limit {limit} --size 720x1600 --bit-rate 6000000 {video}"])
    time.sleep(1.0)
    try:
        subprocess.run(ADB + ["am start -n com.envi.wispr/.ui.VoiceInputActivity --ez toggle true"], check=True, capture_output=True)
        time.sleep(lead)
        inject(packets)
        time.sleep(tail)
    finally:
        subprocess.run(ADB + ["am start -n com.envi.wispr/.ui.VoiceInputActivity --ez stop true"], check=False, capture_output=True)
    rec.wait(timeout=limit + 15)
    local = os.path.join(OUT, f"{name}.mp4")
    subprocess.run(["adb", "-s", SERIAL, "pull", video, local], check=True, capture_output=True)
    return local


# ------------------------------------------------------------------------------------------------
# Reading the frames
# ------------------------------------------------------------------------------------------------

def frames_of(video, name):
    folder = os.path.join(OUT, f"{name}-frames")
    os.makedirs(folder, exist_ok=True)
    for old in os.listdir(folder):
        os.remove(os.path.join(folder, old))
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", video, "-vf", f"fps={FPS}", os.path.join(folder, "f%04d.png")], check=True)
    return [os.path.join(folder, f) for f in sorted(os.listdir(folder))]


def find_pill(image):
    """The pill's rail: the dark rounded pill on a light page, then the bars inside it.

    Returns (x0, y0, x1, y1) of the rail's bounding box, or None when no pill is drawn.
    """
    a = np.asarray(image.convert("RGB")).astype(int)
    h, wdt, _ = a.shape
    # The pill ground is a dark grey; the page behind it is near white. Rows and columns that are mostly
    # dark in the middle band of the screen belong to the pill.
    dark = (a.sum(axis=2) < 3 * 110)
    # Only the middle of the screen: the status bar and the navigation bar are dark too.
    top, bottom = int(h * 0.15), int(h * 0.85)
    rowfrac = dark[top:bottom, wdt // 4: 3 * wdt // 4].mean(axis=1) > 0.3
    # The longest contiguous run of dark rows is the pill.
    best, start = (0, 0, 0), None
    for y, on in enumerate(list(rowfrac) + [False]):
        if on and start is None:
            start = y
        elif not on and start is not None:
            if y - start > best[0]:
                best = (y - start, start, y)
            start = None
    if best[0] < 40 or best[0] > 200:
        return None
    y0, y1 = top + best[1], top + best[2]
    # Likewise across: the longest run of dark columns, so a dark screen edge cannot widen the box.
    colon = dark[y0:y1].mean(axis=0) > 0.5
    bestx, start = (0, 0, 0), None
    for x, on in enumerate(list(colon) + [False]):
        if on and start is None:
            start = x
        elif not on and start is not None:
            if x - start > bestx[0]:
                bestx = (x - start, start, x)
            start = None
    if bestx[0] < 100:
        return None
    return int(bestx[1]), int(y0), int(bestx[2]), int(y1)


def _ink(image, box):
    x0, y0, x1, y1 = box
    a = np.asarray(image.convert("RGB")).astype(int)[y0:y1, x0:x1]
    ground = np.median(a.reshape(-1, 3), axis=0)
    return np.abs(a - ground).sum(axis=2) > 90


def locate_bars(image, box):
    """The x-span of each bar of the rail, found once from a frame where the bars are tall.

    A bar is drawn in rainbow (lit) or resting grey; both differ from the pill ground. Columns are
    grouped by runs of non-ground pixels; the rail is the group of TAP_PILL_BARS narrow runs with the
    most even spacing (the timer's glyphs and the round controls are wider or unevenly spaced).
    Returns [] when no such group exists in this frame.
    """
    ink = _ink(image, box)
    col = ink.mean(axis=0)
    runs, inside = [], False
    # A resting bar is a six-pixel dot in a pill about a hundred pixels tall, so the threshold is low
    # enough to see the dots: the rail must be locatable from a silent frame too.
    for x, v in enumerate(col):
        if v > 0.04 and not inside:
            inside, start = True, x
        elif v <= 0.04 and inside:
            inside = False
            runs.append((start, x))
    if inside:
        runs.append((start, len(col)))
    narrow = [(s, e) for s, e in runs if 2 <= e - s <= 14]
    if len(narrow) < TAP_PILL_BARS:
        return []
    best = None
    for i in range(len(narrow) - TAP_PILL_BARS + 1):
        group = narrow[i:i + TAP_PILL_BARS]
        pitches = [group[k + 1][0] - group[k][0] for k in range(len(group) - 1)]
        var = max(pitches) - min(pitches)
        if best is None or var < best[0]:
            best = (var, group)
    return best[1] if best[0] <= 5 else []


def bar_heights(image, box, bars):
    """Each bar's drawn height in pixels at the x-spans [bars], from a frame's pill [box]."""
    ink = _ink(image, box)
    heights = []
    for s, e in bars:
        rows = np.where(ink[:, s:e].mean(axis=1) > 0.5)[0]
        heights.append(int(rows.max() - rows.min() + 1) if len(rows) else 0)
    return heights


def measure(video, name):
    """Per frame: the bar heights, normalised so the resting height is 0 and the tallest seen is 1."""
    paths = frames_of(video, name)
    # The bars do not move sideways, so their x-spans are found once, from the frame where they are
    # tallest, and every frame is then read at those spans.
    bars, box = [], None
    for path in paths:
        img = Image.open(path)
        candidate = find_pill(img)
        if not candidate:
            continue
        found = locate_bars(img, candidate)
        if found:
            bars, box = found, candidate
            break
    series = []
    for path in paths:
        img = Image.open(path)
        here = find_pill(img)
        series.append(bar_heights(img, here, bars) if (here and bars and here[1] == box[1]) else [])
    drawn = [h for h in series if h]
    if not drawn:
        return series, 0, 0
    # The resting height and the full height come from the PILL'S GEOMETRY, never from the recording
    # under test: a recording whose bars all sat at the same wrong height would otherwise calibrate
    # itself to "nothing lit" (Codex review, 2026-09-15). The rail fills SILENCE_FRACTION of its height
    # at rest and all of it at full level; its height is a fixed share of the pill's, measured once on
    # the 720x1600 emulator recording: a 96 px pill draws a 6 px resting dot and a 36 px full bar.
    pill_height = box[3] - box[1]
    rest = round(pill_height * REST_SHARE)
    peak = round(pill_height * PEAK_SHARE)
    return series, rest, peak


def norm(h, rest, peak):
    span = max(peak - rest, 1)
    return [(x - rest) / span for x in h]


# ------------------------------------------------------------------------------------------------
# Judging
# ------------------------------------------------------------------------------------------------

def expected_bars(hz):
    """The pair of bars a tone at `hz` must light on the tap pill."""
    return bars_for_band(band_of(hz), TAP_PILL_BARS)


def judge(name, series, rest, peak):
    drawn = [norm(h, rest, peak) for h in series if h]
    lines = [f"{name}: {len(series)} frames, {len(drawn)} with the rail readable, rest {rest}px peak {peak}px"]
    if len(drawn) < 10:
        return lines + ["ISSUE: the rail was readable in too few frames"]
    active = [h for h in drawn if max(h) > 0.25]
    quiet = [h for h in drawn if max(h) <= 0.1]
    lines.append(f"  active frames {len(active)}, quiet frames {len(quiet)}")

    def loudest_bars(h):
        top = max(h)
        return [i for i, v in enumerate(h) if v >= top - 0.15]

    if name == "silence":
        lines.append("PASS: nothing lit" if not active else f"ISSUE: {len(active)} frames lit during silence")
    elif name in ("tone120", "tone1k", "tone5k"):
        hz = {"tone120": 120, "tone1k": 1000, "tone5k": 5000}[name]
        want_band = band_of(hz)
        want = expected_bars(hz)
        # EVERY expected bar must be among the loudest, and nothing outside the pair may join them: a
        # rail with one dead half would otherwise pass on the other (Codex review, 2026-09-15).
        hits = sum(1 for h in active if set(loudest_bars(h)) == set(want))
        lines.append(f"  expected bars {want} (band {want_band}); {hits}/{len(active)} active frames agree")
        far = 0
        for h in active:
            others = [v for i, v in enumerate(h) if i not in want and all(abs(b - want_band) > 1 for b in bands_of_bar(i, TAP_PILL_BARS))]
            if others and max(others) > 0.35:
                far += 1
        lines.append(f"  frames with a far-off bar above a third: {far}")
        ok = active and hits >= 0.8 * len(active) and far <= 0.1 * len(active)
        lines.append("PASS: the tone lit its own bars and nothing far from them" if ok else "ISSUE: the tone lit the wrong bars")
    elif name == "sweep":
        # The lit bars should travel from the centre outward over the sweep.
        centres = []
        for h in active:
            bars = loudest_bars(h)
            centres.append(sum(abs(i - (TAP_PILL_BARS - 1) / 2) for i in bars) / len(bars))
        if len(centres) >= 10:
            first, last = sum(centres[:5]) / 5, sum(centres[-5:]) / 5
            lines.append(f"  distance from centre: first frames {first:.1f}, last frames {last:.1f}")
            steps = sum(1 for a, b in zip(centres, centres[1:]) if b < a - 1.0)
            lines.append(f"  frames where the lit bars jumped back toward the centre: {steps}")
            ok = last > first + 2.5 and steps <= 0.15 * len(centres)
            lines.append("PASS: the lit bars travelled from the centre to the edges" if ok else "ISSUE: the sweep did not travel outward")
        else:
            lines.append("ISSUE: too few active frames to read the sweep")
    elif name == "noise":
        # A steady sound that starts mid-take: the bars light, then each band's floor climbs to it and
        # the rail goes dark within about three seconds (the analyser's adaptive floor). A phone's own
        # hiss is there from the first chunk and never lights at all; a fan switched on is this case.
        lit = [i for i, h in enumerate(drawn) if max(h) > 0.25]
        if not lit:
            lines.append("ISSUE: the hiss did not light the rail at all")
        else:
            # The first lit stretch is the hiss arriving; its end is the floor having learned it. The
            # hiss ending, later, is its own small event (the room's own source returns) and not this.
            first_lit = lit[0]
            end = first_lit
            while end + 1 < len(drawn) and max(drawn[end + 1]) > 0.25:
                end += 1
            span = (end - first_lit + 1) / FPS
            dark_after = sum(1 for h in drawn[end + 1:end + 1 + FPS] if max(h) <= 0.25)
            lines.append(f"  hiss lit the rail for {span:.1f} s from its start, then {dark_after} of the next {FPS} frames were dark")
            # The floor's window is 1.5 s of chunks; allow the view's own settle on top.
            ok = span < 2.2 and dark_after >= FPS * 0.8
            lines.append("PASS: a steady hiss is learned and goes dark" if ok else "ISSUE: the hiss stayed lit")
    elif name == "bursts":
        # Four bursts a second, each 125 ms on: the centre-band bars should rise and fall with them.
        # A beat is a peak above 0.85 followed by a dip below 0.6 before the next peak. The window and
        # the release keep the bar off the floor between 125 ms bursts on purpose; what must show is
        # the beat itself.
        bars = expected_bars(300)
        trace = [max(h[i] for i in bars) for h in drawn]
        beats, armed, low = 0, True, 1.0
        # Twenty frames a second against four bursts a second is five frames a beat, so a peak or a
        # dip can fall between frames; the thresholds are loose enough to read a beat from the frames
        # that did land on it.
        for v in trace:
            if v > 0.8:
                if armed and low < 0.7:
                    beats += 1
                armed, low = True, 1.0
            elif armed:
                low = min(low, v)
        lines.append(f"  beats seen {beats} (about 9 expected for 2.5 s at 4 per second); trace {[round(v, 2) for v in trace if v > 0][:40]}")
        ok = 6 <= beats <= 12
        lines.append("PASS: the bars pulse with the bursts" if ok else "ISSUE: the bars do not follow the bursts")
    return lines


# ------------------------------------------------------------------------------------------------

def main(argv):
    names = argv or list(SIGNALS)
    unknown = [n for n in names if n not in SIGNALS]
    if unknown:
        print(f"unknown signals: {unknown}; known: {list(SIGNALS)}", file=sys.stderr)
        return 2
    if w.device() != SERIAL:
        print(f"BLOCKED: {w.device()} is not the emulator", file=sys.stderr)
        return 2
    os.makedirs(OUT, exist_ok=True)
    w.restore()
    # The injected stream is the microphone: host audio off, once, for the whole run.
    off = subprocess.run(["adb", "-s", SERIAL, "emu", "avd", "hostmicoff"], capture_output=True, text=True).stdout
    if not off.startswith("OK"):
        print(f"BLOCKED: could not turn the host microphone off: {off.strip()}", file=sys.stderr)
        return 2
    subprocess.run(ADB + ["am start -a android.intent.action.VIEW -d about:blank com.android.chrome"], check=False, capture_output=True)
    time.sleep(2)
    report = []
    try:
        for name in names:
            label, make = SIGNALS[name]
            packets = os.path.join(OUT, f"{name}.jsonl")
            seconds = write_packets(packets, make())
            print(f"== {name}: {label}")
            video = record_take(name, packets, seconds)
            series, rest, peak = measure(video, name)
            lines = judge(name, series, rest, peak)
            report.extend(lines)
            print("\n".join(lines))
            with open(os.path.join(OUT, f"{name}.tsv"), "w") as f:
                for h in series:
                    f.write("\t".join(str(x) for x in h) + "\n")
    finally:
        w.restore()
    with open(os.path.join(OUT, "report.txt"), "w") as f:
        f.write("\n".join(report) + "\n")
    return 1 if any(l.startswith("ISSUE") for l in report) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
