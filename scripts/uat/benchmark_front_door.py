#!/usr/bin/env python3
"""Harness versus raw adb, on the emulator, six errands each way (#177, founder ask 2026-09-20).

    WISPR_SERIAL=emulator-5554 python3 -u scripts/uat/benchmark_front_door.py

Per errand, per way: wall time from the first command to the reported answer, commands sent, whether
the answer was CORRECT by an INDEPENDENT read (the accessibility tree dumped by a separate raw command,
the microphone switch read over gRPC, the log), how many characters the caller had to READ (the
harness CLI's default line, the raw script's output; tokens are about a quarter of that), and what was
left DIRTY afterwards (microphone, switch, live take, restore book). The harness may lose on wall time; it must win or tie on correct answers and
on dirty state, or #177 does not merge (plan §13).

"Raw" is what a session did by hand before #177: the deleted scripts `grpc-take.sh` and
`debug-insert.sh` verbatim (read out of git at the commit before their deletion), the five unlock lines
the skill carried, and `uiautomator dump` + grep + `input tap` for a switch. Raw commands are counted as
the adb / grpcurl invocations they make.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
os.environ.setdefault("WISPR_SERIAL", "emulator-5554")
import wispr_eyes as w  # noqa: E402

SERIAL = w.EMULATOR_SERIAL
ADB = w.ADB
GM = "com.google.android.gm"
SWITCH, SWITCH_TAB = "Spoken emoji", "Transcription"
SENTENCE = "and I will send the deck tomorrow"
INSERT = "The quarterly report is ready for review."
RAW_SCRIPTS_COMMIT = "HEAD"  # the commit that still holds the deleted scripts, see main()
EXTRA_COMMANDS = [0]  # adb / grpcurl processes launched inside a raw shell script, from its trace


# ------------------------------------------------------------------------------------------------
# Counting and timing
# ------------------------------------------------------------------------------------------------

class Meter:
    """Counts every adb / grpcurl process either way launches, and times the errand."""

    def __init__(self):
        self.commands = 0
        self._real_run = subprocess.run
        self._real_popen = subprocess.Popen

    def __enter__(self):
        meter = self

        def counting_run(args, *a, **k):
            if isinstance(args, (list, tuple)) and args and os.path.basename(str(args[0])) in ("adb", "grpcurl"):
                meter.commands += 1
            return meter._real_run(args, *a, **k)

        def counting_popen(args, *a, **k):
            if isinstance(args, (list, tuple)) and args and os.path.basename(str(args[0])) in ("adb", "grpcurl"):
                meter.commands += 1
            return meter._real_popen(args, *a, **k)

        subprocess.run = counting_run
        subprocess.Popen = counting_popen
        self.started = time.monotonic()
        return self

    def __exit__(self, *_):
        self.seconds = round(time.monotonic() - self.started, 1)
        subprocess.run = self._real_run
        subprocess.Popen = self._real_popen


# ------------------------------------------------------------------------------------------------
# Independent reads (never through the harness's own verdict)
# ------------------------------------------------------------------------------------------------

def raw(cmd, timeout=60):
    return subprocess.run([ADB, "-s", SERIAL, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def tree_raw():
    path = "/sdcard/bench-dump.xml"
    for _ in range(3):
        raw(f"uiautomator dump {path}")
        xml = raw(f"cat {path}")
        if "<hierarchy" in xml:
            return ET.fromstring(xml)
        time.sleep(1)
    raise RuntimeError("no dump")


def editor_text_raw(package=GM):
    for node in tree_raw().iter("node"):
        if node.get("class", "").endswith("EditText") and node.get("package") == package and node.get("focused") == "true":
            return node.get("text", "")
    return None


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds"))))


def switch_raw(label):
    """The checkable ROW that holds the label (Compose puts `checkable` on the row, not on a child)."""
    root = tree_raw()
    nodes = list(root.iter("node"))
    for node in nodes:
        if node.get("text") == label:
            lx0, ly0, lx1, ly1 = bounds(node)
            rows = [n for n in nodes if n.get("checkable") == "true"
                    and bounds(n)[0] <= lx0 and bounds(n)[1] <= ly0 and bounds(n)[2] >= lx1 and bounds(n)[3] >= ly1]
            if rows:
                row = min(rows, key=lambda n: (bounds(n)[2] - bounds(n)[0]) * (bounds(n)[3] - bounds(n)[1]))
                return row.get("checked") == "true", row
    return None, None


def centre(node):
    x0, y0, x1, y1 = bounds(node)
    return (x0 + x1) // 2, (y0 + y1) // 2


def keyguard_raw():
    return "isKeyguardShowing=true" in raw("dumpsys activity activities | grep -i isKeyguardShowing")


def mic_raw():
    out = subprocess.run(["grpcurl", "-plaintext", w.GRPC_ENDPOINT, f"{w.GRPC_SERVICE}/getMicrophoneState"],
                         capture_output=True, text=True).stdout
    return "true" in out


def take_live_raw():
    # `grep -v adbd`: adbd logs the shell command it ran, pattern included, which would read as a start.
    log = raw("logcat -d | grep -E 'recording_start|recording_stop' | grep -v adbd | tail -1")
    return "recording_start" in log


def printed(answer):
    """What the calling agent would READ: the harness CLI's line(s) for a report, the raw script's output."""
    if isinstance(answer, list):
        import contextlib, io
        buffer = io.StringIO()
        with contextlib.redirect_stdout(buffer):
            w._print_report("bench", answer, verbose=False)
        return buffer.getvalue()
    return str(answer)


def dirty(before_mic):
    """What was left behind, as a list of names; empty is clean."""
    left = []
    if mic_raw() != before_mic:
        left.append("microphone")
    if take_live_raw():
        left.append("live take")
    if w._owed(SERIAL):
        left.append("restore book")
    return left


# ------------------------------------------------------------------------------------------------
# The errands, raw
# ------------------------------------------------------------------------------------------------

def raw_unlock():
    raw("input keyevent KEYCODE_WAKEUP")
    raw("input swipe 540 1800 540 600")
    raw("input text 1234")
    raw("input keyevent KEYCODE_ENTER")
    time.sleep(2)
    return "unlocked" if not keyguard_raw() else "still locked"


def raw_read_switch():
    value, _ = switch_raw(SWITCH)
    return value


def raw_flip_and_back():
    value, node = switch_raw(SWITCH)
    x, y = centre(node)
    raw(f"input tap {x} {y}")
    time.sleep(1)
    flipped, node = switch_raw(SWITCH)
    x, y = centre(node)
    raw(f"input tap {x} {y}")
    time.sleep(1)
    back, _ = switch_raw(SWITCH)
    return flipped != value and back == value


def raw_script(name, *args):
    script = subprocess.run(["git", "show", f"{RAW_SCRIPTS_COMMIT}:scripts/uat/{name}"], capture_output=True,
                            text=True, cwd=str(HERE.parent.parent), check=True).stdout
    path = Path(os.environ.get("TMPDIR", "/tmp")) / f"bench-{name}"
    path.write_text(script)
    path.chmod(0o755)
    # Traced, so the adb / grpcurl processes the script launches can be COUNTED; a script run as one
    # `bash` process would otherwise read as a single command. The trace is dropped from the output.
    done = subprocess.run(["bash", "-x", str(path), *args], capture_output=True, text=True, timeout=180,
                          cwd=str(HERE.parent.parent))
    EXTRA_COMMANDS[0] += sum(1 for line in done.stderr.splitlines()
                             if line.startswith("+") and re.search(r"(^|[\s/])(adb|grpcurl)(\s|$)", line))
    return done.stdout + "\n".join(l for l in done.stderr.splitlines() if not l.startswith("+"))


# ------------------------------------------------------------------------------------------------
# The errands, harness
# ------------------------------------------------------------------------------------------------

def harness_unlock():
    return "unlocked" if w.ready() else "blocked"


def harness_read_switch():
    return w.switch(SWITCH)


def harness_flip_and_back():
    value = w.switch(SWITCH)
    w.set_switch(SWITCH, not value, where=SWITCH_TAB)
    flipped = w.switch(SWITCH)
    w.restore()
    return flipped != value and w.switch(SWITCH) == value


# ------------------------------------------------------------------------------------------------

def stage_compose():
    """A focused Gmail compose body, through the harness; not timed, the same for both ways.

    Whatever screen Gmail was left on: an open draft's body is reused (the verdict compares the body
    before and after, so earlier text does not matter), otherwise Compose is opened and the body focused.
    """
    w.restore()
    w.open_app(GM)
    for _ in range(4):
        body = [n for n in w.tree() if n["kind"] == "EditText" and n["package"] == GM and n["id"] == "editor"]
        if body:
            if not body[0]["focused"]:
                w.focus_field(body[0]["text"] or "Compose email", GM)
            break
        try:
            w.find("Compose", package=GM)
        except w.Blocked:
            w.back()
            time.sleep(1.2)
            continue
        w.tap("Compose", package=GM)
        time.sleep(2.5)
    text = editor_text_raw()
    if text is None:
        raise RuntimeError("no focused compose body")
    return text


def stage_settings():
    w.restore()
    w.open_settings()
    w.open_tab(SWITCH_TAB)


def lock():
    raw("input keyevent KEYCODE_SLEEP")
    time.sleep(2)


def run(name, way, setup, errand, correct):
    setup()
    mic_before = mic_raw()
    EXTRA_COMMANDS[0] = 0
    with Meter() as m:
        try:
            answer = errand()
            error = ""
        except Exception as why:  # noqa: BLE001 - the benchmark records, it does not judge
            answer, error = None, f"{type(why).__name__}: {str(why)[:120]}"
    ok = False
    try:
        ok = bool(correct(answer))
    except Exception as why:  # noqa: BLE001
        error = error or f"oracle: {why}"
    left = dirty(mic_before)
    m.commands += EXTRA_COMMANDS[0]
    shown = printed(answer) if answer is not None else ""
    row = {"errand": name, "way": way, "seconds": m.seconds, "commands": m.commands, "correct": ok,
           "dirty": ", ".join(left) or "clean", "chars": len(shown),
           "note": error or " ".join(shown.split())[:80]}
    print(f"  {way:8} {name:22} {m.seconds:6.1f}s {m.commands:4} cmds {row['chars']:5} chars  correct={ok!s:5} dirty={row['dirty']}  {row['note']}")
    # put the emulator back between rows whichever way ran, so a dirty raw row does not poison the next
    w.restore()
    if mic_raw() != mic_before:
        w.set_host_mic(mic_before)
        w.restore()
    return row


def main():
    global RAW_SCRIPTS_COMMIT
    RAW_SCRIPTS_COMMIT = subprocess.run(
        ["git", "log", "-1", "--format=%H", "--diff-filter=D", "--", "scripts/uat/grpc-take.sh"],
        capture_output=True, text=True, cwd=str(HERE.parent.parent)).stdout.strip() + "^"
    if RAW_SCRIPTS_COMMIT == "^":
        RAW_SCRIPTS_COMMIT = "HEAD"  # the scripts are still in the tree
    if w.device() != SERIAL or not w.is_emulator():
        print(f"BLOCKED: {w.device()} is not the emulator", file=sys.stderr)
        return 2
    print(f"raw scripts read from {RAW_SCRIPTS_COMMIT}")
    rows = []
    # 1. unlock
    rows.append(run("unlock", "raw", lock, raw_unlock, lambda a: not keyguard_raw()))
    rows.append(run("unlock", "harness", lock, harness_unlock, lambda a: not keyguard_raw()))
    # 2. read one switch
    rows.append(run("read switch", "raw", stage_settings, raw_read_switch, lambda a: a == switch_raw(SWITCH)[0]))
    rows.append(run("read switch", "harness", stage_settings, harness_read_switch, lambda a: a == switch_raw(SWITCH)[0]))
    # 3. flip and put back
    start = switch_raw(SWITCH)[0]
    rows.append(run("flip and back", "raw", stage_settings, raw_flip_and_back, lambda a: a and switch_raw(SWITCH)[0] == start))
    rows.append(run("flip and back", "harness", stage_settings, harness_flip_and_back, lambda a: a and switch_raw(SWITCH)[0] == start))
    # 4. one spoken take into Gmail compose
    before = {}

    def stage_take():
        # THE MICROPHONE RESTS OFF (#181: a switch right before a capture dropped the injected audio 4
        # times in 25). Both ways start from the resting state; the #177 table already recorded that
        # the raw script never puts the mic back.
        w._rest_host_mic_off()
        before["text"] = stage_compose()

    def plain(text):
        import unicodedata
        folded = unicodedata.normalize("NFKC", text).casefold()
        return " ".join("".join(c for c in folded if not unicodedata.category(c).startswith("P")).split())

    def take_landed(_):
        # An oracle of its own (never the harness's): the sentence, exactly, once case, punctuation and
        # spacing are aside.
        after = editor_text_raw() or ""
        got = plain(after)
        return after != before["text"] and any(plain(v) in got for v in (SENTENCE, SENTENCE[:1].upper() + SENTENCE[1:]))

    rows.append(run("spoken take", "raw", stage_take, lambda: raw_script("grpc-take.sh", SENTENCE, "bench"), take_landed))
    rows.append(run("spoken take", "harness", stage_take, lambda: w.dictate_emulator(SENTENCE), take_landed))
    # 5. audio-free insert

    def insert_landed(_):
        after = editor_text_raw() or ""
        return after != before["text"] and INSERT in after

    rows.append(run("audio-free insert", "raw", stage_take, lambda: raw_script("debug-insert.sh", INSERT, "bench"), insert_landed))
    rows.append(run("audio-free insert", "harness", stage_take, lambda: w.debug_insert(INSERT), insert_landed))
    # 6. leave-clean is the `dirty` column of every row above; summarised below.
    out = Path(os.environ.get("BENCH_OUT", "docs/benchmark-results/2026-09-20-issue-181-harness-fast-eye.md"))
    out.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Harness versus raw adb on the emulator, #177", "",
             f"Run {time.strftime('%Y-%m-%d %H:%M')} on {SERIAL} ({w.PLAY_AVD}), raw scripts from `{RAW_SCRIPTS_COMMIT}`.",
             "Wall time is first command to reported answer; commands are adb + grpcurl processes; correct is an",
             "independent read (a separate `uiautomator dump`, gRPC `getMicrophoneState`, the log); dirty is what was",
             "left behind before the benchmark itself cleaned up.", "",
             "| Errand | Way | Seconds | Commands | Output chars | Correct | Dirty | Note |", "|---|---|---|---|---|---|---|---|"]
    for r in rows:
        lines.append(f"| {r['errand']} | {r['way']} | {r['seconds']} | {r['commands']} | {r['chars']} | {r['correct']} | {r['dirty']} | {r['note']} |")
    for way in ("raw", "harness"):
        mine = [r for r in rows if r["way"] == way]
        lines.append("")
        lines.append(f"**{way}:** {sum(r['correct'] for r in mine)}/{len(mine)} correct, "
                     f"{sum(r['dirty'] != 'clean' for r in mine)} dirty, {sum(r['seconds'] for r in mine):.1f} s total, "
                     f"{sum(r['commands'] for r in mine)} commands, {sum(r['chars'] for r in mine)} output chars "
                     f"(about {sum(r['chars'] for r in mine) // 4} tokens).")
    out.write_text("\n".join(lines) + "\n")
    print(f"\nwritten to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
