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


def _adb(command, timeout=60, check=True):
    """One `adb shell` command, with the REMOTE status read and, by default, enforced.

    Two separate traps, and this function is where both are closed.

    `adb shell` returns adb's OWN status, never the command's, so a check written without a sentinel
    reports success whatever happened (`android-tooling.md` RULE: adb-shell-needs-a-sentinel). And a
    sentinel that is read but ignored is the same bug one step later: `input tap` failing while `tap()`
    returned "pressed ..." is a false pass about an action that did not happen.

    `check=False` is for commands whose non-zero status is an ANSWER rather than a failure, such as a
    `grep` that legitimately finds nothing. It is never a way to ignore an error.

    The command is quoted into a single `sh -c` argument so that no caller-supplied text can be read as
    shell syntax.
    """
    target = device()
    code, out, err = _run(
        [ADB, "-s", target, "shell", f"sh -c {shlex.quote(command)}; echo __RC=$?"], timeout=timeout
    )
    if code != 0:
        raise Blocked(f"adb could not reach {target}: {err.strip() or 'no message'}")
    match = re.search(r"__RC=(\d+)\s*$", out)
    if match is None:
        raise Blocked(f"the phone returned no status for {command!r}, so nothing here can be believed")
    remote = int(match.group(1))
    body = out[: match.start()]
    if check and remote != 0:
        raise Blocked(f"the phone refused {command!r} (status {remote}): {body.strip() or 'no message'}")
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


# What the founder's phone answers to. A device that is not this is a stranger, and a harness that taps
# and deletes must not pick one on its own (`tools-and-apps.md` RULE: a-harness-that-acts-must-refuse-not-choose).
KNOWN_PHONE_MODELS = ("SM_S948U1",)


def device(serial=None):
    """The phone to drive, refusing rather than guessing.

    Auto-selection is allowed ONLY for a device whose model this project knows. Any other physical
    device has to be named, because "the attached device" is a question an emulator answers too, and a
    stranger's phone answers it as readily as the founder's.
    """
    if serial is not None:
        if not isinstance(serial, str) or not serial.strip():
            raise Blocked("a device serial must be a non-empty string")
        attached = {s for s, _ in devices()}
        if serial not in attached:
            raise Blocked(f"{serial!r} is not attached. Attached: {', '.join(sorted(attached)) or 'nothing'}")
        _switch_to(serial)
        return serial
    if _STATE["serial"]:
        return _STATE["serial"]
    env = os.environ.get("WISPR_SERIAL")
    if env:
        return device(env)

    physical = [(s, m) for s, m in devices() if not s.startswith("emulator-")]
    if not physical:
        raise Blocked(
            "no physical phone attached. Attached: "
            + (", ".join(f"{s} ({m})" for s, m in devices()) or "nothing")
            + ". Turn on Wireless debugging, or name a device."
        )
    known = [(s, m) for s, m in physical if m in KNOWN_PHONE_MODELS]
    if not known:
        raise Blocked(
            "the attached phone is not one this project knows, so it will not be driven unasked: "
            + ", ".join(f"{s} ({m})" for s, m in physical)
            + ". Name it explicitly with device(serial) or WISPR_SERIAL if it really is the target."
        )
    # ONE phone can appear TWICE, once as an address and once as an mDNS TLS name, and calling that two
    # devices refuses a run for no reason. Ask each entry what hardware it is; a property read that
    # FAILS is not an answer and keeps the entry separate rather than merging it away.
    by_hardware = {}
    for candidate, model in known:
        code, out, _ = _run([ADB, "-s", candidate, "shell", "getprop ro.serialno"], timeout=20)
        hardware = out.strip() if code == 0 and out.strip() else f"unknown:{candidate}"
        by_hardware.setdefault(hardware, []).append((candidate, model))
    if len(by_hardware) > 1:
        raise Blocked(
            "more than one known phone is attached, so which to drive is a guess: "
            + "; ".join(f"{h}: {[s for s, _ in v]}" for h, v in by_hardware.items())
            + ". Name one with device(serial) or WISPR_SERIAL."
        )
    candidates = next(iter(by_hardware.values()))
    candidates.sort(key=lambda pair: ("_adb-tls" in pair[0], len(pair[0])))
    _switch_to(candidates[0][0])
    return _STATE["serial"]


def _switch_to(serial):
    """Point at a device, refusing to abandon changes owed to the previous one."""
    if _STATE["serial"] == serial:
        return
    if _STATE["restore"]:
        raise Blocked(
            "there are changes still owed to "
            f"{_STATE['serial']}; call restore() before driving another device."
        )
    _STATE["serial"] = serial
    _STATE["tree"] = None


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
    # Read the whole dump and decide here. `grep -c` exits non-zero when it finds nothing, which is the
    # ANSWER, not a failure, and a checked runner cannot tell those apart.
    _, out = _adb("dumpsys accessibility", timeout=90)
    return bool(re.search(r"Bound services:\{Service\[label=EnviousWispr", out))


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


def find(text, exact=True, clickable=None, package=PACKAGE):
    """The ONE node matching, or a refusal naming every candidate.

    Three rules, and each one closes a way this could press the wrong thing.

    **The screen is read fresh, every time.** A cached tree holds coordinates from a screen that is no
    longer there, and a tap sends coordinates, not a node. Reusing one is how a harness presses whatever
    now occupies that spot.

    **Ambiguity is an error, never a pick.** On 2026-09-06 two "Remove" buttons were on screen and one
    of them deletes a 484 MB model the founder would have to download again.

    **The default is EXACT and scoped to our app.** A substring search across every app on screen is a
    wide net over things nobody meant to press, and an empty query would match the first labelled node
    on the phone.
    """
    if not isinstance(text, str) or not text.strip():
        raise Blocked("a non-empty control name is required; an empty one matches whatever is first")
    matches = []
    for node in tree(refresh=True):
        if package is not None and node["package"] != package:
            continue
        labels = {node["text"], node["desc"]} - {""}
        hit = any(label == text for label in labels) if exact else any(
            text.lower() in label.lower() for label in labels
        )
        if hit:
            matches.append(node)
    if not matches:
        raise Blocked(f"nothing on screen matches {text!r}. What is there:\n{look(only_ours=True)}")
    if len(matches) > 1:
        where = "; ".join(f"{_label(n)!r} at {n['centre']}" for n in matches)
        raise Blocked(
            f"{len(matches)} nodes match {text!r}, so which one to press is a guess: {where}. "
            "Narrow it with a longer phrase, or clickable=True."
        )
    node = matches[0]
    # Checked AFTER ambiguity, so a caller is told "there are two of these" rather than "there are none",
    # which is the answer that sends somebody looking in the wrong place.
    if clickable is not None and node["clickable"] != clickable:
        raise Blocked(f"{text!r} is on screen but is not the kind of control that was asked for")
    return node


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
    """The recorder pill, or None. A COMPLETE signature or nothing.

    An earlier version called any uppercase word a recorder state, so an ordinary page with a heading
    like STORAGE read as a recorder that was not there. The pill is recognised by its TIMER, which
    nothing else on this app's screens carries, and a duplicate of any part is refused rather than
    resolved by taking the first, because its controls get pressed.
    """
    nodes = [n for n in tree() if n["package"] == PACKAGE]
    timers = [n for n in nodes if re.fullmatch(r"\d+:\d\d", _label(n))]
    if not timers:
        return None
    if len(timers) > 1:
        raise Blocked(f"{len(timers)} things look like the recorder's timer, so the pill is ambiguous")

    def one(description, predicate, required=True):
        found = [n for n in nodes if predicate(n)]
        if len(found) > 1:
            raise Blocked(f"{len(found)} nodes look like the recorder's {description}")
        if not found and required:
            raise Blocked(f"the recorder has a timer but no {description}, so it is not fully drawn")
        return found[0] if found else None

    state = one("state label", lambda n: _label(n).isupper() and len(_label(n)) > 3, required=False)
    cancel = one("cancel control", lambda n: _label(n).lower() in ("cancel", "×", "x"))
    stop = one("stop control", lambda n: _label(n).lower() in ("stop", "done", "✓"))
    for node, name in ((cancel, "cancel"), (stop, "stop")):
        if not node["enabled"] or not node["clickable"]:
            raise Blocked(f"the recorder's {name} control cannot be pressed")
    return {
        "timer": _label(timers[0]),
        "state": _label(state) if state else None,
        "labels": [_label(n) for n in nodes if _label(n)],
        "cancel": _label(cancel),
        "stop": _label(stop),
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

def _enclosing_control(node, package):
    """The one clickable control that CONTAINS this label, or a refusal.

    Ambiguity is refused here too: if two clickable controls enclose the label, pressing either is a
    guess, and the smallest is not obviously the right answer.
    """
    x0, y0, x1, y1 = node["bounds"]
    holders = [
        other for other in tree(refresh=False)
        if other["clickable"] and other["package"] == package
        and other["bounds"][0] <= x0 and other["bounds"][1] <= y0
        and other["bounds"][2] >= x1 and other["bounds"][3] >= y1
    ]
    if not holders:
        raise Blocked(f"{_label(node)!r} is on screen but nothing around it can be pressed")
    holders.sort(key=lambda n: (n["bounds"][2] - n["bounds"][0]) * (n["bounds"][3] - n["bounds"][1]))
    smallest = holders[0]
    same_size = [h for h in holders if
                 (h["bounds"][2] - h["bounds"][0]) * (h["bounds"][3] - h["bounds"][1])
                 == (smallest["bounds"][2] - smallest["bounds"][0]) * (smallest["bounds"][3] - smallest["bounds"][1])]
    if len(same_size) > 1:
        raise Blocked(f"{len(same_size)} equally sized controls enclose {_label(node)!r}, so pressing one is a guess")
    return smallest


def tap(text, exact=True, clickable=True, package=PACKAGE):
    """Find one control by its words and press it. Never a coordinate typed by hand.

    Refuses a control that is present but disabled, and refuses when the phone cannot receive input at
    all, because both of those return success and prove nothing.
    """
    ready()
    # Ask for the LABEL, not for a clickable node. In Compose the words and the touch target are usually
    # different nodes: the drawer row carries "Storage" on a non-clickable child inside a clickable
    # parent, so demanding a clickable match refuses a control that is plainly there.
    node = find(text, exact=exact, clickable=None, package=package)
    if clickable and not node["clickable"]:
        holder = _enclosing_control(node, package)
        # PRESS WITHIN THE LABEL'S OWN ROW. A clickable ancestor can be the whole drawer, and its centre
        # is then some other row entirely: tapping it opened "Open Source Licenses" when asked for
        # "Appearance" (2026-09-06). The holder proves something here is pressable; the label says WHERE.
        top, bottom = node["bounds"][1], node["bounds"][3]
        if not (top <= holder["centre"][1] <= bottom):
            holder = dict(holder, centre=(holder["centre"][0], node["centre"][1]))
        node = dict(holder, text=node["text"], desc=node["desc"])
    x0, y0, x1, y1 = node["bounds"]
    if not node["enabled"] or x1 <= x0 or y1 <= y0:
        raise Blocked(f"{text!r} is on screen but disabled or has no area, so pressing it proves nothing")
    x, y = node["centre"]
    _STATE["tree"] = None
    _adb(f"input tap {x} {y}")
    time.sleep(1.2)
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
    remote, out = _adb(f"am start -n {shlex.quote(component)}", check=False)
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
    # The known place is "the menu button is reachable", not "no page is open". A previous errand can
    # leave a settings page open OR the drawer open, and only the first shows a back arrow. Aiming at
    # the state the caller needs covers both, and covers a third nobody has met yet.
    for _ in range(4):
        if present("Open settings menu"):
            break
        back()
        note = "closed what was already open"
    if not present("Open settings menu"):
        raise Blocked(
            "could not get back to a screen with the settings menu on it. What is there:\n"
            + look(only_ours=True)
        )
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
    remote, out = _adb(f"am start -n {shlex.quote(RECORDER_ACTIVITY)}", check=False)
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
    audio test runs at LOW VOLUME.

    The volume is put back in a `finally` and then READ BACK. Restoring in the happy path only is how a
    timeout leaves a machine loud, and trusting a restore nobody checked is how it stays loud quietly.

    **The phone has to be near the Mac.** That is the one precondition this cannot check, so a report
    says it rather than assuming it.
    """
    if not isinstance(volume, int) or isinstance(volume, bool) or not 0 <= volume <= 40:
        raise Blocked("volume must be a whole number from 0 to 40; this is a low-volume tool")
    previous = _checked(["osascript", "-e", "output volume of (get volume settings)"]).strip()
    if not previous.isdigit():
        raise Blocked("could not read the Mac's current volume, so it could not be safely lowered")
    entry = ("mac-volume", previous)
    _STATE["restore"].append(entry)
    try:
        _checked(["osascript", "-e", f"set volume output volume {volume}"])
        started = time.monotonic()
        _checked(["say", "--", sentence], timeout=180)
        return time.monotonic() - started
    finally:
        _restore_one(entry)
        if entry in _STATE["restore"]:
            _STATE["restore"].remove(entry)


# --------------------------------------------------------------------------------------------------
# Reading what happened
# --------------------------------------------------------------------------------------------------

def clear_log():
    """Clear the ring buffer and widen it, so an absent line means absent rather than evicted.

    The widening is journaled, because it is a change to the founder's phone that outlives this run.
    """
    _adb("logcat -c")
    _, current = _adb("logcat -g | head -1", check=False)
    size = re.search(r"(\d+)Kb", current or "")
    if size and ("log-buffer", f"{size.group(1)}K") not in _STATE["restore"]:
        _STATE["restore"].append(("log-buffer", f"{size.group(1)}K"))
    _adb("logcat -G 16M")


def logs(pattern=None, lines=200):
    """The app's own log lines, newest last.

    `lines` is validated rather than interpolated: it reaches a shell command, and a string there would
    carry whatever the caller put in it.
    """
    if not isinstance(lines, int) or isinstance(lines, bool) or not 1 <= lines <= 10000:
        raise Blocked("lines must be a whole number from 1 to 10000")
    tags = "|".join(APP_TAGS)
    _, out = _adb(f"logcat -d -v time | grep -E '{tags}' | tail -{lines}", timeout=90, check=False)
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

def _checked(args, timeout=60):
    """Run a local command and refuse to continue when it failed."""
    code, out, err = _run(args, timeout=timeout)
    if code != 0:
        raise Blocked(f"{args[0]} failed: {err.strip() or f'status {code}'}")
    return out


def _restore_one(entry):
    """Put ONE change back, and read it back to prove it took.

    A restore that returned zero is not a restore that happened.
    """
    what, previous = entry
    if what == "mac-volume":
        _checked(["osascript", "-e", f"set volume output volume {int(previous)}"])
        now = _checked(["osascript", "-e", "output volume of (get volume settings)"]).strip()
        if now != str(int(previous)):
            raise Blocked(f"the Mac's volume did not go back to {previous}; it reads {now}")
    elif what == "log-buffer":
        _adb(f"logcat -G {previous}")
    else:
        raise Blocked(f"there is no verified way to put {what!r} back, so it must not have been journaled")


def restore():
    """Put back everything this session changed, and say what.

    An entry is removed ONLY after its restore was read back. A failure leaves it in the journal and
    raises, so the next call tries again rather than reporting a rollback that did not happen.

    **What this does NOT cover, said plainly rather than implied:** a dictation this tool started and
    the History row it produced, the app's foreground state, and onboarding once dismissed. Those are
    dev state on a phone with no users (`CLAUDE.md`: a wiped phone is cheap), but they are not restored
    and no report should say they were.
    """
    done = []
    while _STATE["restore"]:
        entry = _STATE["restore"][-1]
        _restore_one(entry)
        _STATE["restore"].pop()
        done.append(f"{entry[0]} back to {entry[1]}")
    _adb("rm -f /sdcard/wispr-eyes.xml", check=False)
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
    report.append("NOTE: this did not dictate anything; use `dictate` for the whole path")
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
    if pill is None:
        return ["ISSUE: the recorder disappeared before it could be stopped"]
    # By LABEL, not by a coordinate read a moment ago, so the same refusal rules apply to the control
    # this presses as to every other.
    tap(pill["stop"])
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
        elif command in ("recorder", "dictate"):
            report = check_recorder() if command == "recorder" else test_dictation(*(rest or []))
            print("\n".join(report))
            # A findings report that exits 0 is a green light over a red result.
            return 1 if any(line.startswith(("ISSUE", "BLOCKED")) for line in report) else 0
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
