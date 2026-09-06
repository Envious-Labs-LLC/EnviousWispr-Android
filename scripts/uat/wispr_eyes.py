#!/usr/bin/env python3
"""Wispr Eyes for Android: see and drive the running app, and report in plain English.

The macOS twin is `Tests/RuntimeUAT/wispr_eyes.py`, which talks to the app through AX APIs. Android has
no AX API, so this talks to the phone through `adb` and the accessibility tree. Same promise: ONE call
answers a question, nothing is driven by hand-computed coordinates, and every answer is a sentence a
person can read.

    python3 scripts/uat/wispr_eyes.py look
    python3 scripts/uat/wispr_eyes.py tap "Storage"
    python3 scripts/uat/wispr_eyes.py recorder
    python3 scripts/uat/wispr_eyes.py dictate "the quick brown fox"

Or from Python, chaining in ONE call the way the macOS one does:

    python3 -c "import sys; sys.path.insert(0, 'scripts/uat'); from wispr_eyes import *; nav('Storage'); print(look())"

EVERY DESIGN RULE HERE WAS PAID FOR ON 2026-09-06, driving the founder's phone by hand:

* `look()` reads the ACCESSIBILITY TREE, and `shot()` reads PIXELS, and they see different things. The
  floating recorder is a `TYPE_ACCESSIBILITY_OVERLAY`; `screencap` composited it on one attempt and
  omitted it on another, and reading a screenshot that lacked it nearly produced "the recorder did not
  open" about a recorder that was open. Ask the tree first, always.
* `find()` REFUSES when a query matches more than one node. Two "Remove" buttons were on screen, one of
  which deletes a 484 MB model the founder would have to download again. Coordinates were computed by
  hand twice to tell them apart.
* Every device change is journaled and `restore()` puts it back. Two volume settings were left changed.
* A locked phone is refused, not driven. Taps land silently on the keyguard.
* The phone is chosen by SERIAL and never by "the attached device". An emulator answers that question
  too, and it is not the phone.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

ADB = os.path.expanduser("~/Android/sdk/platform-tools/adb")
PACKAGE = "com.envi.wispr"
SETTINGS_ACTIVITY = f"{PACKAGE}/.ui.SettingsActivity"
RECORDER_ACTIVITY = f"{PACKAGE}/.ui.VoiceInputActivity"

# The app's own log tags, read off `TAG = "..."` in app/src/main. A take writes lines under several.
APP_TAGS = (
    "AudioCapture", "DictationSession", "AsrService", "PolishService",
    "PasteService", "RecordingOverlay", "SilenceVad", "DebugLogger",
)

_STATE = {"serial": None, "restore": [], "tree": None}


class Blocked(RuntimeError):
    """The question cannot be answered right now, and driving on would produce a wrong answer."""


# --------------------------------------------------------------------------------------------------
# The phone
# --------------------------------------------------------------------------------------------------

def _run(args, timeout=60):
    done = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return done.returncode, done.stdout, done.stderr


def _adb(command, timeout=60, serial=None):
    """One `adb shell` command, with the sentinel that makes the REMOTE status readable.

    `adb shell` returns adb's own status, never the command's, so every check written without a
    sentinel reports success (`android-tooling.md` RULE: adb-shell-needs-a-sentinel).
    """
    target = serial or device()
    code, out, err = _run([ADB, "-s", target, "shell", f"{command}; echo __RC=$?"], timeout=timeout)
    if code != 0:
        raise Blocked(f"adb could not reach {target}: {err.strip() or 'no message'}")
    match = re.search(r"__RC=(\d+)\s*$", out)
    remote = int(match.group(1)) if match else None
    body = out[: match.start()] if match else out
    return remote, body


def devices():
    """Every attached device, as (serial, model). Emulators included, and labelled as themselves."""
    code, out, _ = _run([ADB, "devices", "-l"])
    if code != 0:
        raise Blocked("adb devices failed; is the server running?")
    found = []
    for line in out.splitlines()[1:]:
        if not line.strip() or "\tdevice" not in line and " device " not in line:
            continue
        serial = line.split()[0]
        model = re.search(r"model:(\S+)", line)
        found.append((serial, model.group(1) if model else "unknown"))
    return found


def device(serial=None):
    """The PHONE's serial, refusing rather than guessing.

    An emulator answers "is a device attached" with yes. A watcher once reported the founder's phone
    online because an emulator had booted, and a UAT that picks the wrong one silently measures nothing
    (`device-testing.md` RULE: a-watcher-for-the-phone-names-the-phone).
    """
    if serial:
        _STATE["serial"] = serial
        return serial
    if _STATE["serial"]:
        return _STATE["serial"]
    env = os.environ.get("WISPR_SERIAL")
    if env:
        _STATE["serial"] = env
        return env
    physical = [(s, m) for s, m in devices() if not s.startswith("emulator-")]
    if not physical:
        raise Blocked(
            "no physical phone attached. Attached: "
            + (", ".join(f"{s} ({m})" for s, m in devices()) or "nothing")
            + ". Turn on Wireless debugging, or pass a serial."
        )
    # ONE phone can appear TWICE, once as an address and once as an mDNS TLS name, and treating that as
    # two devices refuses a run for no reason. Ask each entry what hardware it is; identical hardware is
    # one phone (observed 2026-09-06). Different hardware stays ambiguous and is still refused.
    by_hardware = {}
    for serial, model in physical:
        code, out, _ = _run([ADB, "-s", serial, "shell", "getprop ro.serialno"], timeout=20)
        hardware = out.strip() or serial
        by_hardware.setdefault(hardware, []).append((serial, model))
    if len(by_hardware) > 1:
        raise Blocked(
            "more than one physical phone is attached, so which one to drive is a guess: "
            + "; ".join(f"{h}: {[s for s, _ in v]}" for h, v in by_hardware.items())
            + ". Pass a serial or set WISPR_SERIAL."
        )
    candidates = next(iter(by_hardware.values()))
    # Prefer the plain address form: it is the one the knowledge files name and it survives longer.
    candidates.sort(key=lambda pair: ("_adb-tls" in pair[0], len(pair[0])))
    _STATE["serial"] = candidates[0][0]
    return _STATE["serial"]


def ready():
    """Refuse to drive a phone that cannot receive input, and say which reason.

    A tap on a locked phone lands on the keyguard and returns success, so the run reads as done and
    proves nothing. Measured 2026-08-31.
    """
    _, power = _adb("dumpsys power | grep -m1 mWakefulness")
    _, locked = _adb("dumpsys deviceidle | grep -m1 mScreenLocked")
    awake = "Awake" in power
    unlocked = "mScreenLocked=false" in locked
    if not awake:
        _adb("input keyevent KEYCODE_WAKEUP")
        time.sleep(1)
        _, power = _adb("dumpsys power | grep -m1 mWakefulness")
        awake = "Awake" in power
    if not awake:
        raise Blocked("the phone will not wake. Nothing can be driven.")
    if not unlocked:
        raise Blocked(
            "the phone is locked and only its owner can unlock it. Waking it does not bypass a secure "
            "lock, and every tap from here would land on the keyguard."
        )
    return True


def bound():
    """Whether OUR accessibility service is bound, which is what draws the recorder and inserts text.

    The enabled SETTING is not the answer: it still names a crashed service, and `install -r` unbinds
    without changing it (`code-gotchas.md` RULE: auto-paste-readiness-is-liveness-not-the-setting-string).
    """
    _, out = _adb('dumpsys accessibility | grep -c "Bound services:{Service\\[label=EnviousWispr"')
    return out.strip().endswith("1")


# --------------------------------------------------------------------------------------------------
# Seeing
# --------------------------------------------------------------------------------------------------

def tree(refresh=True):
    """Every node on screen, from the ACCESSIBILITY tree.

    This is the eye that SEES THE FLOATING RECORDER. `shot()` does not reliably: the recorder is an
    accessibility overlay and a screenshot omitted it on 2026-09-06, which read as the recorder never
    having opened.

    `uiautomator dump` can crash on this phone and write no XML, so it is retried once before failing
    (`device-testing.md` FACT: uiautomator-dump-crashes-on-this-phone-INTERMITTENTLY).
    """
    if not refresh and _STATE["tree"] is not None:
        return _STATE["tree"]
    xml = None
    for attempt in (1, 2):
        remote, _ = _adb("uiautomator dump /sdcard/wispr-eyes.xml")
        if remote == 0:
            _, xml = _adb("cat /sdcard/wispr-eyes.xml")
            if xml and "<hierarchy" in xml:
                break
            xml = None
        if attempt == 1:
            time.sleep(1)
    if not xml:
        raise Blocked("uiautomator could not read the screen, twice. It fails intermittently on this phone.")
    nodes = []
    for chunk in re.finditer(r"<node ([^>]*?)/?>", xml):
        attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', chunk.group(1)))
        bounds = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attrs.get("bounds", ""))
        if not bounds:
            continue
        x0, y0, x1, y1 = (int(v) for v in bounds.groups())
        nodes.append({
            "text": attrs.get("text", ""),
            "desc": attrs.get("content-desc", ""),
            "package": attrs.get("package", ""),
            "clickable": attrs.get("clickable") == "true",
            "enabled": attrs.get("enabled") == "true",
            "selected": attrs.get("selected") == "true",
            "bounds": (x0, y0, x1, y1),
            "centre": ((x0 + x1) // 2, (y0 + y1) // 2),
        })
    _STATE["tree"] = nodes
    return nodes


def _label(node):
    return node["text"] or node["desc"]


def look(only_ours=False):
    """What is on screen, as plain lines, top to bottom.

    Pass `only_ours=True` to drop the system chrome and other apps, which is usually what a UAT wants.
    """
    lines = []
    for node in sorted(tree(), key=lambda n: (n["bounds"][1], n["bounds"][0])):
        if only_ours and node["package"] != PACKAGE:
            continue
        text = _label(node)
        if not text:
            continue
        mark = " [tap]" if node["clickable"] else ""
        lines.append(f"{text}{mark}")
    # Adjacent duplicates are the same label carried by a node and its merged parent.
    unique = [line for index, line in enumerate(lines) if index == 0 or line != lines[index - 1]]
    if not unique:
        # NEVER return an empty string. Silence is the one answer a reader turns into "nothing is
        # wrong", and here it usually means the app is not on screen at all.
        showing = sorted({n["package"] for n in tree(refresh=False) if n["package"]})
        scope = "from EnviousWispr" if only_ours else "with any words"
        return f"(nothing on screen {scope}. Showing instead: {', '.join(showing) or 'nothing'})"
    return "\n".join(unique)


def find(text, exact=False, clickable=None):
    """The ONE node matching, or a refusal naming every candidate.

    **Ambiguity is an error, never a pick.** On 2026-09-06 two "Remove" buttons were on screen and one
    of them deletes a 484 MB model. Choosing the first, or the topmost, is how a UAT deletes the
    founder's data (`tools-and-apps.md` RULE: a-harness-that-acts-must-refuse-not-choose).
    """
    matches = []
    for node in tree(refresh=_STATE["tree"] is None):
        label = _label(node)
        if not label:
            continue
        hit = label == text if exact else text.lower() in label.lower()
        if not hit:
            continue
        if clickable is not None and node["clickable"] != clickable:
            continue
        matches.append(node)
    if not matches:
        raise Blocked(f"nothing on screen matches {text!r}. What is there:\n{look(only_ours=True)}")
    if len(matches) > 1:
        where = "; ".join(f"{_label(n)!r} at {n['centre']}" for n in matches)
        raise Blocked(
            f"{len(matches)} nodes match {text!r}, so which one to press is a guess: {where}. "
            "Narrow it with exact=True, clickable=True, or a longer phrase."
        )
    return matches[0]


def present(text, exact=False):
    """Whether exactly one node matches, WITHOUT swallowing an ambiguity as a no."""
    try:
        find(text, exact=exact)
        return True
    except Blocked as refusal:
        if "nothing on screen matches" in str(refusal):
            return False
        raise


def shot(path=None):
    """A screenshot, for PIXELS: colour, layout, whether a meter is lit.

    **It is not the way to ask whether something EXISTS.** Use `tree()` for that. A screenshot omitted
    the floating recorder entirely on 2026-09-06.
    """
    path = path or f"/tmp/wispr-eyes-{int(time.time())}.png"
    with open(path, "wb") as handle:
        done = subprocess.run([ADB, "-s", device(), "exec-out", "screencap", "-p"],
                              stdout=handle, stderr=subprocess.PIPE)
    if done.returncode != 0 or not Path(path).stat().st_size:
        raise Blocked("screencap produced nothing")
    return path


# --------------------------------------------------------------------------------------------------
# The floating recorder
# --------------------------------------------------------------------------------------------------

def overlay():
    """The recorder pill's state, or None when it is not on screen.

    The pill is the surface the user sees most and the one hardest to observe: it belongs to the
    accessibility service, so a screenshot may not contain it and an Appium session SUPPRESSES it
    outright (Android unbinds every other accessibility service while UiAutomator2 holds one).
    """
    nodes = [n for n in tree() if n["package"] == PACKAGE]
    if not nodes:
        return None
    labels = [_label(n) for n in nodes if _label(n)]
    timer = next((l for l in labels if re.fullmatch(r"\d+:\d\d", l)), None)
    state = next((l for l in labels if l.isupper() and len(l) > 3), None)
    if timer is None and state is None:
        return None
    return {
        "timer": timer,
        "state": state,
        "labels": labels,
        "cancel": next((n["centre"] for n in nodes if _label(n).lower() in ("cancel", "×", "x")), None),
        "stop": next((n["centre"] for n in nodes if _label(n).lower() in ("stop", "done", "✓")), None),
    }


def meter_lit(box=(290, 190, 570, 240), threshold=260):
    """How many pixels of the level meter are brightly lit, from a screenshot.

    A COUNT rather than a verdict, because one reading proves nothing: a quiet room and a broken meter
    both read low. Compare two readings across a sound to get an answer.
    """
    from PIL import Image  # imported here so the module works without Pillow for everything else

    image = Image.open(shot()).convert("RGB").crop(box)
    pixels = list(image.getdata())
    return sum(1 for r, g, b in pixels if r + g + b > threshold)


# --------------------------------------------------------------------------------------------------
# Doing
# --------------------------------------------------------------------------------------------------

def tap(text, exact=False, clickable=None):
    """Find one node by its words and press its centre. Never a coordinate typed by hand."""
    node = find(text, exact=exact, clickable=clickable)
    x, y = node["centre"]
    _adb(f"input tap {x} {y}")
    time.sleep(1.2)
    _STATE["tree"] = None
    return f"pressed {_label(node)!r} at ({x}, {y})"


def back():
    _adb("input keyevent KEYCODE_BACK")
    time.sleep(0.8)
    _STATE["tree"] = None


def home():
    _adb("input keyevent KEYCODE_HOME")
    time.sleep(0.8)
    _STATE["tree"] = None


def scroll(direction="down", amount=1):
    for _ in range(amount):
        if direction == "down":
            _adb("input swipe 540 1700 540 900 300")
        else:
            _adb("input swipe 540 900 540 1700 300")
        time.sleep(0.6)
    _STATE["tree"] = None


def _start(component, what, settle):
    """Launch a component and ASSERT it landed, rather than reporting the launch.

    `am start` printing "Starting:" is not the app being on screen: it prints that for an activity that
    does not exist, and for one that starts and immediately finishes. On 2026-09-06 a navigation ran its
    whole sequence against a launcher because nothing checked, and every later step failed for a reason
    that had nothing to do with its own subject.
    """
    remote, out = _adb(f"am start -n {component}")
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"{what} would not start: {detail}. Is the app installed on {device()}?")
    time.sleep(settle)
    _STATE["tree"] = None
    showing = {n["package"] for n in tree()}
    if PACKAGE not in showing:
        raise Blocked(
            f"{what} was started but EnviousWispr is not on screen; showing "
            f"{', '.join(sorted(p for p in showing if p)) or 'nothing'} instead."
        )


def open_settings(dismiss_onboarding=True):
    """Open the app, and get past onboarding so the caller reaches the thing it asked for.

    A fresh install lands on WELCOME, which replaces the whole shell, so every later step fails for a
    reason that has nothing to do with its own subject. Reinstalling and running the connected test task
    both produce that state, so a UAT meets it often.
    """
    _start(SETTINGS_ACTIVITY, "the app", 2.5)
    note = "opened"
    if dismiss_onboarding and present("Set up later"):
        tap("Set up later")
        if present("Set up later"):
            raise Blocked("onboarding would not dismiss; 'Set up later' is still on screen.")
        note = "dismissed onboarding first"
    # A settings page left open by the PREVIOUS errand hides the drawer behind a back arrow, and every
    # step after that fails for a reason that has nothing to do with its own subject. Found by using
    # this tool twice in a row on 2026-09-06. Getting to a known place is the caller's right, not its
    # job. Bounded, so a screen that will not leave says so instead of looping.
    for _ in range(3):
        if not present("Back"):
            break
        tap("Back")
        note = "closed a page that was already open"
    if present("Back"):
        raise Blocked("a settings page will not close; the app is stuck on one.")
    return note


def open_recorder():
    """Start the floating recorder by COMPONENT.

    `am start -a android.intent.action.ASSIST` shows a chooser on this phone, because more than one app
    answers the action (`device-testing.md` FACT: the-ASSIST-action-opens-a-CHOOSER-on-this-phone).
    """
    if not bound():
        raise Blocked("our accessibility service is not bound, so no recorder will be drawn.")
    # The recorder activity is transient BY DESIGN: it starts the session and finishes, and the pill is
    # drawn by the accessibility service. So the landing check is the PILL, not the activity.
    remote, out = _adb(f"am start -n {RECORDER_ACTIVITY}")
    if remote != 0 or "Error" in out:
        raise Blocked(f"the recorder would not start: {out.strip().splitlines()[-1] if out.strip() else 'no message'}")
    time.sleep(3)
    _STATE["tree"] = None
    if overlay() is None:
        raise Blocked(
            "the recorder was started but no pill is in the accessibility tree. The service is bound, "
            "so check the microphone permission and the log."
        )


def nav(page):
    """Open a settings page from the drawer, by name."""
    open_settings()
    tap("Open settings menu")
    tap(page, exact=True)
    return look(only_ours=True)


def stop_app():
    _adb(f"am force-stop {PACKAGE}")
    time.sleep(1)
    _STATE["tree"] = None


# --------------------------------------------------------------------------------------------------
# Audio in, at low volume, from the Mac
# --------------------------------------------------------------------------------------------------

def say(sentence, volume=25):
    """Speak a sentence out of the MAC's speakers, into the phone's microphone, quietly.

    The Mac speaks rather than the phone, so the phone's own audio settings are never touched and the
    sound arrives through the air like a person talking. The founder's standing instruction is that any
    audio test runs at LOW VOLUME, so this sets a low output level and puts the previous one back.

    **The phone has to be near the Mac.** That is the one thing this cannot check.
    """
    code, before, _ = _run(["osascript", "-e", "output volume of (get volume settings)"])
    previous = before.strip() if code == 0 else None
    if previous and previous.isdigit():
        _journal("mac-volume", previous)
        _run(["osascript", "-e", f"set volume output volume {volume}"])
    started = time.time()
    _run(["say", sentence], timeout=120)
    spoken = time.time() - started
    if previous and previous.isdigit():
        _run(["osascript", "-e", f"set volume output volume {previous}"])
        _unjournal("mac-volume")
    return spoken


# --------------------------------------------------------------------------------------------------
# Reading what happened
# --------------------------------------------------------------------------------------------------

def clear_log():
    """Clear the ring buffer and widen it, so an absent line means absent rather than evicted."""
    _adb("logcat -c")
    _adb("logcat -G 16M")


def logs(pattern=None, lines=200):
    """The app's own log lines, newest last."""
    tags = "|".join(APP_TAGS)
    _, out = _adb(f"logcat -d -v time | grep -E '{tags}' | tail -{lines}", timeout=90)
    if pattern:
        out = "\n".join(line for line in out.splitlines() if re.search(pattern, line))
    return out.strip()


def last_take():
    """What the most recent dictation did, as a small dictionary a person can read.

    Every field comes from a line the app itself wrote. A field is None when the app never said it,
    which is different from zero and is left different.
    """
    text = logs(lines=400)
    def first(pattern, cast=str):
        match = re.search(pattern, text)
        if not match:
            return None
        try:
            return cast(match.group(1))
        except (ValueError, IndexError):
            return None
    return {
        "started": "recording_start" in text,
        "ended_by": first(r"Stopped by ([^.]+)\."),
        "bytes": first(r"Stopped by [^.]+\. (\d+) bytes", int),
        "seconds": first(r"\((\d+\.\d+)s\)", float),
        "warned_at_ms": first(r"Duration warning shown at (\d+)ms", int),
        "hit_the_cap": "Take ended at the duration cap" in text,
        "transcribed_chars": first(r"Transcription result received \(chars=(\d+)\)", int),
        "handoff": first(r"handoff=(\w+)"),
        "insertion": first(r"Insertion completed via (\w+)"),
    }


# --------------------------------------------------------------------------------------------------
# Putting the phone back
# --------------------------------------------------------------------------------------------------

def _journal(what, previous):
    _STATE["restore"].append((what, previous))


def _unjournal(what):
    _STATE["restore"] = [entry for entry in _STATE["restore"] if entry[0] != what]


def restore():
    """Put back everything this session changed, and say what was put back.

    The phone is the founder's daily driver, not a lab device. Two volume settings were left changed on
    2026-09-06 (`session-behavior.md` RULE: revert-the-phone-after-a-session).
    """
    done = []
    for what, previous in reversed(_STATE["restore"]):
        if what == "mac-volume":
            _run(["osascript", "-e", f"set volume output volume {previous}"])
        elif what.startswith("stream-"):
            _adb(f"cmd media_session volume --stream {what.split('-')[1]} --set {previous}")
        done.append(f"{what} back to {previous}")
    _STATE["restore"] = []
    _adb("rm -f /sdcard/wispr-eyes.xml")
    return done or ["nothing was changed"]


# --------------------------------------------------------------------------------------------------
# Suites, each answering one question end to end
# --------------------------------------------------------------------------------------------------

def check_recorder():
    """Does the floating recorder open, and does it show its parts? Read from the tree, not pixels."""
    ready()
    report = []
    clear_log()
    open_recorder()
    pill = overlay()
    if pill is None:
        return ["ISSUE: the recorder did not appear in the accessibility tree"]
    report.append(f"VERIFIED: the recorder is up, showing {pill['state'] or 'no state label'} at {pill['timer']}")
    for part, name in ((pill["cancel"], "cancel"), (pill["stop"], "stop")):
        report.append(f"VERIFIED: a {name} control is present" if part else f"ISSUE: no {name} control")
    return report


def test_dictation(sentence="The quick brown fox jumps over the lazy dog", volume=25):
    """Speak into the phone and report what came back. The full heart path, in one call."""
    ready()
    if not bound():
        return ["BLOCKED: our accessibility service is not bound; insertion cannot happen"]
    clear_log()
    open_recorder()
    if overlay() is None:
        return ["ISSUE: the recorder did not open, so nothing was dictated"]
    say(sentence, volume=volume)
    time.sleep(1)
    pill = overlay()
    if pill and pill["stop"]:
        x, y = pill["stop"]
        _adb(f"input tap {x} {y}")
    time.sleep(8)
    take = last_take()
    report = []
    report.append(f"VERIFIED: a take ran, ended by {take['ended_by']}" if take["ended_by"]
                  else "ISSUE: no take ending was logged")
    if take["transcribed_chars"]:
        report.append(f"VERIFIED: {take['transcribed_chars']} characters came back from the speech model")
    else:
        report.append("ISSUE: nothing was transcribed")
    report.append(f"handoff was {take['handoff']}" if take["handoff"] else "ISSUE: no handoff was logged")
    return report


# --------------------------------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------------------------------

def _main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        print("commands: look tree find tap nav recorder dictate logs take shot devices restore")
        return 0
    command, rest = argv[0], argv[1:]
    try:
        if command == "devices":
            for serial, model in devices():
                print(f"{serial}\t{model}")
        elif command == "look":
            print(look(only_ours="--all" not in rest))
        elif command == "tree":
            print(json.dumps(tree(), indent=2))
        elif command == "find":
            print(json.dumps(find(rest[0]), indent=2))
        elif command == "tap":
            print(tap(rest[0]))
        elif command == "nav":
            print(nav(rest[0]))
        elif command == "recorder":
            print("\n".join(check_recorder()))
        elif command == "dictate":
            print("\n".join(test_dictation(*(rest or []))))
        elif command == "logs":
            print(logs(rest[0] if rest else None))
        elif command == "take":
            print(json.dumps(last_take(), indent=2))
        elif command == "shot":
            print(shot(rest[0] if rest else None))
        elif command == "restore":
            print("\n".join(restore()))
        else:
            print(f"unknown command {command!r}", file=sys.stderr)
            return 2
    except Blocked as refusal:
        print(f"BLOCKED: {refusal}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
