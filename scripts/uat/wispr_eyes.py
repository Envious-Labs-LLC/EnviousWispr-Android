#!/usr/bin/env python3
"""Wispr Eyes for Android: see and drive the running app, and report in plain English.

The macOS twin is `Tests/RuntimeUAT/wispr_eyes.py`, which talks to the app through AX APIs. Android has
no AX API, so this talks to the phone through `adb` and the accessibility tree. Same promise: ONE call
answers a question, nothing is driven by hand-computed coordinates, and every answer is a sentence a
person can read.

    python3 scripts/uat/wispr_eyes.py look
    python3 scripts/uat/wispr_eyes.py scan
    python3 scripts/uat/wispr_eyes.py quiet
    python3 scripts/uat/wispr_eyes.py dictate
    python3 scripts/uat/wispr_eyes.py restore

Or from Python, chaining in ONE call the way the macOS one does:

    python3 -c "import sys; sys.path.insert(0, 'scripts/uat'); from wispr_eyes import *; nav('Storage'); print(look())"

EVERY RULE HERE WAS PAID FOR ON 2026-09-06, driving the founder's phone by hand through all twelve
screens, the recorder and a real dictation.

* **THREE EYES, AND THE RECORDER IS VISIBLE TO EXACTLY ONE OF THEM.** `look()` reads the accessibility
  tree and sees every PAGE; `shot()` reads pixels; and neither can see the floating recorder at all,
  because an accessibility overlay is excluded from screenshots and absent from the node tree. Only
  `overlay()`, which reads `dumpsys window windows`, can. Measured with the service bound and a take
  running: two confident "no recorder" answers about a recorder plainly on screen.
* **`find()` REFUSES when a query matches more than one node.** Two "Remove" buttons were on screen, one
  of which deletes a 484 MB model the founder would have to download again.
* **A REFUSAL MUST NOT LEAVE THE MICROPHONE OPEN.** Every path out of `open_recorder()` that is not a
  healthy pill cancels the take first. An earlier version raised and returned with it still running.
* **Every device change is written to disk BEFORE it is made**, because this tool runs one process per
  errand and an in-memory list is empty by the time `restore()` needs it.
* **A locked phone is refused, and nothing is ever sent at a lock screen.** It cannot pass a credential
  it does not have, and trying spends real unlock attempts on the founder's own phone.
* **Do almost nothing while a take is running.** Reading the recorder window and staging audio inside a
  take lost the pinned editor and produced `handoff=SERVICE_NOT_RUNNING` against a working app.
* **The phone is chosen by SERIAL and never by "the attached device".** An emulator answers that question
  too, and it is not the phone.
"""

from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import uuid
import time
from contextlib import contextmanager
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

# WHERE THE "PUT IT BACK" LIST LIVES, AND WHY IT IS ON DISK.
#
# This tool is invoked as `python3 -c "...from wispr_eyes import *; ..."`, once per errand, so every
# call is a NEW PROCESS. An in-memory journal is therefore empty at the start of the call that would
# have used it: a change made in one call is not merely un-restored, it is unknown to the next call and
# to `restore()` itself, which then answers "nothing was changed" and reads as an all-clear.
#
# Measured 2026-09-06: `keep_awake()` raised the founder's screen timeout from 10 to 30 minutes, and
# the very next call reported an empty journal. The macOS twin never had this because it runs inside
# one long-lived session; here the process boundary is the normal case, not the exception.
#
# Keyed by device serial, because a change owed to one phone must never be "restored" onto another.
_JOURNAL = Path(os.path.expanduser("~/.cache/wispr-eyes/restore.json"))

_STATE = {"serial": None, "restore": [], "tree": None, "holding_journal": False,
          "restored_for": None}


def _journal_read():
    """The book, or a refusal. NEVER an empty book standing in for an unreadable one.

    Swallowing a read error returned `{}`, which reads as "nothing is owed" — the one answer that makes
    a caller stop looking, about a phone that may have several settings changed.
    """
    try:
        raw = _JOURNAL.read_text()
    except FileNotFoundError:
        return {}
    except OSError as why:
        raise Blocked(f"the restore book at {_JOURNAL} cannot be read: {why}") from why
    try:
        book = json.loads(raw)
    except ValueError as why:
        raise Blocked(
            f"the restore book at {_JOURNAL} is damaged and will not be overwritten: {why}. "
            "Read it by hand and put back whatever it names."
        ) from why
    if not isinstance(book, dict) or not all(isinstance(v, list) for v in book.values()):
        raise Blocked(f"the restore book at {_JOURNAL} is not in the expected shape")
    return book


@contextmanager
def _journal_locked():
    """Hold the book across a read-modify-write, so two processes cannot overwrite each other.

    `os.replace` makes each WRITE atomic, and that is not the same thing: two processes can both read
    the same book, each add its own debt, and the second write erase the first. One phone means this is
    rare rather than impossible, and a lost debt is a setting left on the founder's phone with nothing
    recording it.
    """
    import fcntl
    # RE-ENTRANT WITHIN THIS PROCESS. `flock` is held per open file description, so a second `open` and
    # `LOCK_EX` from the SAME process blocks on itself, for ever, holding the phone's take open while it
    # waits. `open_recorder` holds this across a start and anything it calls in there could reach the
    # book again, so the shape has to be safe rather than merely currently-unused.
    if _STATE.get("holding_journal"):
        yield
        return
    _JOURNAL.parent.mkdir(parents=True, exist_ok=True)
    with _JOURNAL.with_suffix(".lock").open("a") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX)
        _STATE["holding_journal"] = True
        try:
            yield
        finally:
            _STATE["holding_journal"] = False
            fcntl.flock(handle, fcntl.LOCK_UN)


def _journal_write(book):
    """Replace the book in one step, so an interrupted write cannot leave a half-file.

    A `write_text` that dies mid-way leaves unreadable JSON, and the reader above then refuses
    everything — which is safe, and still worse than not being able to happen.
    """
    _JOURNAL.parent.mkdir(parents=True, exist_ok=True)
    beside = _JOURNAL.with_suffix(".writing")
    beside.write_text(json.dumps(book, indent=2))
    os.replace(beside, _JOURNAL)


def _owed(serial=None):
    """Everything still owed back to a phone, as a list of (what, previous)."""
    serial = serial or _STATE["serial"]
    return [tuple(entry) for entry in _journal_read().get(serial or "", [])]


def _debt_key(entry):
    """What makes two debts the SAME setting. One media volume; one per switch, per screen."""
    what, previous = entry
    if what == "take":
        # Every take is distinct. There is no "the take" to collapse them onto.
        return (what, previous)
    if what == "switch":
        try:
            named = json.loads(previous)
            return (what, named["where"], named["label"])
        except (ValueError, KeyError, TypeError):
            return (what, previous)
    return (what,)


def _owe(entry, serial=None):
    """Record a change BEFORE making it, so a crash between the two leaves a note rather than nothing."""
    serial = serial or _STATE["serial"]
    with _journal_locked():
        _owe_locked(entry, serial)


def _owe_locked(entry, serial):
    book = _journal_read()
    owed = [tuple(e) for e in book.get(serial or "", [])]
    # THE FIRST VALUE FOR EACH SETTING IS THE ONE TO KEEP, and this is not a tidiness rule. Staging
    # twice recorded "media volume was 8" and then "media volume was 3", and restoring both in order
    # ended at 3 with an empty book: the phone left changed and the tool reporting it clean.
    #
    # "EACH SETTING" IS NOT "EACH KIND". There is one media volume, but there are eleven switches, and
    # keying them all on the word `switch` would let one flipped switch hide the next.
    if not any(_debt_key(old) == _debt_key(entry) for old in owed):
        owed.append(entry)
        book[serial or ""] = [list(e) for e in owed]
        _journal_write(book)


def _settled(entry, serial=None):
    """Drop a change from the book, ONLY after its restore was read back."""
    serial = serial or _STATE["serial"]
    with _journal_locked():
        _settled_locked(entry, serial)


def _settled_locked(entry, serial):
    book = _journal_read()
    owed = [tuple(e) for e in book.get(serial or "", []) if tuple(e) != entry]
    book[serial or ""] = [list(e) for e in owed]
    _journal_write(book)


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
    if _owed(_STATE["serial"]):
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

    **NOTHING HERE EVER SENDS INPUT AT A LOCK SCREEN, and that is a rule rather than an oversight.**
    Waking is allowed; getting past the lock is not. A swipe only opens a lock that needs no credential,
    so against this phone it would do nothing but raise the PIN pad — and then every later keystroke or
    tap this harness sends is a wrong credential attempt on the founder's daily driver, which escalates
    to a lockout and can disable fingerprint unlock. Founder, 2026-09-06, after a session tried a swipe:
    *"Do you even know my password? Seems like a bad place to test swiping no?"* The answer to both is
    no. The phone is handed back, always.
    """
    _, power = _adb("dumpsys power | grep -m1 mWakefulness")
    awake = "Awake" in power
    if not awake:
        # Waking is not unlocking. It turns the screen on and stops here.
        _adb("input keyevent KEYCODE_WAKEUP")
        time.sleep(1)
        _, power = _adb("dumpsys power | grep -m1 mWakefulness")
        awake = "Awake" in power
    if not awake:
        raise Blocked("the phone will not wake. Nothing can be driven.")
    # ASK THE WINDOW MANAGER, WHICH OWNS THE KEYGUARD. Two other signals were tried and both mislead:
    # `deviceidle`'s `mScreenLocked` is that service's own view, and `dumpsys trust`'s `deviceLocked`
    # answers "is a CREDENTIAL required", which is 0 for an insecure keyguard that is nonetheless
    # covering the screen and swallowing every tap.
    _, activities = _adb("dumpsys activity activities | grep -m1 -i isKeyguardShowing", check=False)
    if "isKeyguardShowing=true" in activities.replace(" ", ""):
        raise Blocked(
            "the phone is locked, and only its owner can open it. This tool does not send taps, swipes "
            "or keys at a lock screen: it cannot pass a credential it does not have, and trying would "
            "spend real unlock attempts on the founder's own phone. Unlock it and run this again."
        )
    if not activities.strip():
        raise Blocked(
            "the phone would not say whether its lock screen is up, so whether a tap would land is "
            "unknown. Refusing rather than driving blind."
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

    `uiautomator dump` can crash on this phone and write no XML, so it is retried
    (`device-testing.md` FACT: uiautomator-dump-crashes-on-this-phone-INTERMITTENTLY).

    **`check=False` IS WHAT MAKES THE RETRY EXIST.** With the default, `_adb` RAISES on a non-zero
    status, so the loop below never reached its second attempt and the comment describing a retry was
    describing nothing — a claim about a mechanism, which is the one kind of comment that retires a
    check instead of failing it. Measured 2026-09-06: a dump returned status 137 and the run died on
    the spot, with this loop in the file.

    **TWO DUMPS AT ONCE KILL EACH OTHER**, and 137 is that signal. `uiautomator` allows one instance,
    so a second reader anywhere — another session, or a person poking the phone from a terminal — takes
    the first one down. That is `one-phone-serialises-every-session` at the level of a single command,
    and it is why the retry is worth having rather than being papered over: the second attempt usually
    lands once the other reader has finished.
    """
    if not refresh and _STATE["tree"] is not None:
        return _STATE["tree"]
    xml = None
    last = None
    for attempt in (1, 2, 3):
        remote, message = _adb("uiautomator dump /sdcard/wispr-eyes.xml", check=False)
        if remote == 0:
            _, xml = _adb("cat /sdcard/wispr-eyes.xml")
            if xml and "<hierarchy" in xml:
                break
            xml = None
            last = "the dump succeeded but wrote no tree"
        else:
            last = (f"status {remote}" + (f": {message.strip()}" if message.strip() else "")
                    + (" — something else is reading this phone's screen at the same time"
                       if remote == 137 else ""))
        if attempt < 3:
            time.sleep(1.5)
    if not xml:
        raise Blocked(f"uiautomator could not read the screen, three times. Last answer: {last}.")
    # PARSED AS A TREE, because the answer to "what control owns this label" is ANCESTRY and not
    # geometry. A regex over `<node …>` throws the nesting away, and the readers then had to ask which
    # box CONTAINS which — which is right most of the time and silently wrong whenever an unrelated
    # control happens to overlap, on a harness that presses buttons that delete things.
    import xml.etree.ElementTree as ElementTree
    try:
        root = ElementTree.fromstring(xml)
    except ElementTree.ParseError as why:
        raise Blocked(f"the screen came back as XML that will not parse: {why}") from why

    nodes = []

    def visit(element, parent):
        if element.tag != "node":
            for child in element:
                visit(child, parent)
            return
        attrs = element.attrib
        bounds = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", attrs.get("bounds", ""))
        if bounds is None:
            raise Blocked("a node on screen has bounds that cannot be read, so nothing can be pressed "
                          "safely from this reading")
        checkable = attrs.get("checkable") == "true"
        if checkable and attrs.get("checked") not in ("true", "false"):
            raise Blocked("a switch on screen does not say whether it is on, so it must not be reported")
        x0, y0, x1, y1 = (int(v) for v in bounds.groups())
        here = len(nodes)
        nodes.append({
            "text": attrs.get("text", ""),
            "desc": attrs.get("content-desc", ""),
            "package": attrs.get("package", ""),
            "kind": attrs.get("class", "").rsplit(".", 1)[-1],
            "id": attrs.get("resource-id", "").rsplit("/", 1)[-1],
            "clickable": attrs.get("clickable") == "true",
            "enabled": attrs.get("enabled") == "true",
            "selected": attrs.get("selected") == "true",
            "scrollable": attrs.get("scrollable") == "true",
            "focused": attrs.get("focused") == "true",
            # A SWITCH HAS THREE STATES HERE, not two, and collapsing them is how a reader is told a
            # switch is off when the truth is that nothing on this row is a switch.
            "checkable": checkable,
            "on": (attrs.get("checked") == "true") if checkable else None,
            "bounds": (x0, y0, x1, y1),
            "centre": ((x0 + x1) // 2, (y0 + y1) // 2),
            "parent": parent,
        })
        for child in element:
            visit(child, here)

    visit(root, None)
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
    """A screenshot, for PIXELS on a PAGE: colour, layout, spacing.

    **It is not the way to ask whether something EXISTS, and it cannot see the recorder at all.** Use
    `tree()` for a page and `overlay()` for the recorder. An accessibility overlay is excluded from
    screenshots, so anything cropped from where the pill appears belongs to the app BEHIND it.
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
    """The recorder pill: does it exist, where is it, and is the user actually seeing it.

    **READ FROM THE WINDOW MANAGER, WHICH IS THE ONLY EYE THAT SEES THIS WINDOW.** Measured
    2026-09-06 on the founder's phone, with the accessibility service bound and a take running:

    | Eye | Saw the pill? |
    |---|---|
    | `uiautomator dump` — the accessibility tree | NO. Zero nodes from our package. |
    | `screencap` — a screenshot | NO. The home screen, with no pill anywhere on it. |
    | `dumpsys window windows` | YES: `EnviousWispr recording controls`, `frame=[33,138][1046,305]`, `Surface: shown=true`, `isVisible=true`, `HAS_DRAWN`. |

    An accessibility overlay is deliberately excluded from screenshots and from the node tree, so the
    two obvious instruments BOTH answer "no pill" about a pill the user can plainly see. That pair of
    confident negatives nearly became a product defect report against a recorder that was working: the
    window manager settled it in one call.

    This corrects an earlier note in this repo claiming the accessibility tree sees the recorder. It
    does not.

    Returns None when no such window exists, which is the honest reading of "the recorder is not up".
    """
    # `check=True`: a failed dump that happens to contain a window header would otherwise be read
    # as a real measurement of the recorder.
    _, out = _adb("dumpsys window windows", timeout=90)
    lines = out.splitlines()
    # ANCHOR ON THE WINDOW HEADER, NOT ON THE NAME. The same window's block also contains a
    # `WindowStateAnimator{... EnviousWispr recording controls}:` line, so a name match finds ONE
    # window twice and the duplicate guard refuses a perfectly healthy recorder. Measured on the
    # phone 2026-09-06: lines 63 and 81 of one dump, inside one block.
    headers = [i for i, line in enumerate(lines) if re.match(r"\s*Window #\d+ Window\{", line)]
    if not headers:
        raise Blocked("the window dump held no window headers at all, so nothing here can be believed")
    heads = [i for i in headers
             if re.match(r"\s*Window #\d+ Window\{.*recording controls\}:", lines[i])]
    if not heads:
        return None
    if len(heads) > 1:
        raise Blocked(f"{len(heads)} recorder windows exist at once, so which one is showing is a guess")
    # END AT THE NEXT WINDOW, not after a fixed number of lines. A short recorder block followed by a
    # healthy window let this read that window's `shown=true` and `isVisible=true` and report them as
    # the recorder's, which is the plausible-value trap in its purest form.
    stop = next((i for i in headers if i > heads[0]), len(lines))
    block = "\n".join(lines[heads[0]:stop])
    # Every field must be PRESENT. A missing one currently reads as False, which is indistinguishable
    # from a window that is genuinely not drawn.
    for needed in (r"Surface:\s+shown=(true|false)", r"\bisVisible=(true|false)",
                   r"\bmDrawState=\w+", r"\bisOnScreen=(true|false)"):
        if re.search(needed, block) is None:
            raise Blocked("the recorder window's state could not be read in full, so whether the user "
                          "can see it is unknown")

    def field(pattern, cast=str):
        found = re.search(pattern, block)
        return cast(found.group(1)) if found else None

    frame = re.search(r"frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", block)
    shown = "Surface: shown=true" in block
    visible = "isVisible=true" in block
    drawn = "mDrawState=HAS_DRAWN" in block
    on_screen = "isOnScreen=true" in block
    return {
        "where": tuple(int(v) for v in frame.groups()) if frame else None,
        # BOTH numbers out of ONE match. They are printed as `Requested w=1013 h=167`, so a separate
        # search for `Requested h=` finds nothing and the height reads as unknown on a healthy window.
        "size": (lambda m: (int(m.group(1)), int(m.group(2))) if m else (None, None))(
            re.search(r"Requested w=(\d+) h=(\d+)", block)),
        # All four, separately, because they fail apart: a window can have a surface and never be
        # drawn, and one can be drawn and then hidden behind something else.
        "shown": shown,
        "visible": visible,
        "drawn": drawn,
        "on_screen": on_screen,
        # The one a caller must not assume. Every other reader in this file works on the node tree, and
        # this window is absent from it.
        "in_the_node_tree": any(n["package"] == PACKAGE for n in tree(refresh=True)),
        "the_user_can_see_it": shown and visible and drawn and on_screen,
    }


# --------------------------------------------------------------------------------------------------
# Doing
# --------------------------------------------------------------------------------------------------

def _holder(node, want, package=PACKAGE, described=""):
    """The nearest ANCESTOR of this node that satisfies `want`, or None.

    Compose puts a row's words and the row's behaviour on different nodes: `SettingsToggleRow` writes
    its title into a `TextView` and its on/off state onto the `toggleable` Row that CONTAINS it. So
    every reader here finds the WORDS and then walks outward.

    **Outward means UP THE TREE, not "the smallest box around it".** Geometry answers the same question
    correctly almost always and wrongly whenever an unrelated control overlaps the label, and the wrong
    answer is a press on something nobody asked for.
    """
    nodes = tree(refresh=False)
    found = []
    # START AT THE NODE ITSELF. A control that carries its own words — a checkable row with the label
    # written on it rather than on a child — has no ancestor that matches, so starting at the parent
    # made it vanish from `switches()` and refuse in `switch()`. "The nearest thing that satisfies this,
    # looking outward" includes where you are standing.
    current = node
    while current is not None:
        if current["package"] == package and want(current):
            found.append(current)
        current = nodes[current["parent"]] if current.get("parent") is not None else None
    if not found:
        return None
    if len(found) > 1:
        raise Blocked(
            f"{_label(node)!r} sits inside {len(found)} nested {described or 'controls'}, so pressing "
            "one of them is a guess"
        )
    return found[0]


def _rows_by_label(package=PACKAGE, refresh=True):
    """Map each checkable row to the words inside it, walking each node's parents ONCE.

    Built from the switches outward, so a switch added to the app appears without anyone editing this
    file, and a switch whose label changed shows up under its new words rather than going missing.
    """
    nodes = tree(refresh=refresh)
    owner = {}
    for node in nodes:
        if node["package"] != package or not _label(node):
            continue
        current = node
        while current is not None:
            if current["package"] == package and current["checkable"]:
                owner.setdefault(id(current), []).append(node)
                break
            current = nodes[current["parent"]] if current.get("parent") is not None else None
    return nodes, owner


def switch(label, package=PACKAGE):
    """Is this switch on or off? True, False, or a refusal saying why it cannot be read.

    Never returns None for "off". A row that carries no switch at all is a DIFFERENT answer from a
    switch that is off, and collapsing them tells a reader a setting is off when the truth is that
    nothing here is a setting.
    """
    node = find(label, exact=True, clickable=None, package=package)
    row = _holder(node, lambda n: n["checkable"], package=package, described="switches")
    if row is None:
        raise Blocked(
            f"{label!r} is on screen but nothing around it is a switch, so it has no on/off state. "
            "It may be a heading, a link, or a row that leads somewhere."
        )
    return bool(row["on"])


def switches(package=PACKAGE, refresh=True):
    """Every switch on this screen, as {label: on}. The labels are the words INSIDE each switch's row.

    Refuses when two switches carry the same words, rather than letting one overwrite the other in the
    map and reporting a state that belongs to the wrong control.
    """
    nodes, owner = _rows_by_label(package=package, refresh=refresh)
    found = {}
    for row in nodes:
        if row["package"] != package or not row["checkable"]:
            continue
        inside = owner.get(id(row), [])
        if not inside:
            continue
        # The TITLE is the topmost, leftmost label in the row; the sentence under it is the subtitle.
        inside.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
        name = _label(inside[0])
        if name in found:
            raise Blocked(f"two switches on this screen are both labelled {name!r}, so reporting a "
                          "state for that name would be reporting one of them at random")
        found[name] = bool(row["on"])
    return found


def on_screen(where):
    """Is the app showing this named screen right now?

    A tab is the one reporting itself SELECTED; a page is the one whose title is on screen beside the
    back arrow. Both readings come from the app rather than from a memory of where a caller navigated,
    which is the only kind that survives a process boundary.
    """
    if where in TABS:
        # A FRESH READ, and a refusal is passed on rather than answered "no". An unreadable screen is
        # not the same as a screen showing something else, and this decides whether a change to the
        # founder's settings gets recorded against a page that can put it back.
        tree(refresh=True)
        row = _tab_row()
        if where not in row:
            return False
        node = row[where]
        return bool(node["selected"]) or _holder(node, lambda n: n["selected"]) is not None
    if where in PAGES:
        return present(where, exact=True) and present("Back", exact=True)
    raise Blocked(f"{where!r} is not a screen this app has")


def one_way(label, package=PACKAGE):
    """Is this a SWITCH you can turn back, or a CHOICE you cannot un-make?

    **Getting it wrong changes the founder's settings and cannot undo it.** The AI Polish page offers
    `Off` / `This phone` / `Cloud`, and all three report themselves as checkable, exactly like the four
    switches on the Transcription tab. Turning one ON is a tap; turning it OFF is not a thing a
    single-choice group can do. A caller that flips one "and puts it back" turns the founder's polish
    off and leaves it off.

    **The discriminator is measured: a real switch is CLICKABLE while it is on, and the chosen member of
    a group is NOT.** That is Compose saying there is nothing to press, and it is the same signal the
    tab bar gives for the tab you are already on. Read off the phone 2026-09-06: the three Clipboard
    switches all report `checkable, checked, clickable` while on; `This phone` reports
    `checkable, checked` and no click.
    """
    node = find(label, exact=True, clickable=None, package=package)
    row = _holder(node, lambda n: n["checkable"], package=package, described="switches")
    if row is None:
        raise Blocked(f"{label!r} is on screen but nothing around it is a switch")
    top, bottom = row["bounds"][1], row["bounds"][3]
    band = [n for n in tree(refresh=False)
            if n["checkable"] and n["package"] == package
            and n["bounds"][1] < bottom and n["bounds"][3] > top]
    if len(band) < 2:
        return False
    chosen = [n for n in band if n["on"]]
    return len(chosen) == 1 and not chosen[0]["clickable"]


def set_switch(label, on, where, package=PACKAGE):
    """Put a switch into a known state, and READ IT BACK. Returns what it was before.

    Does nothing when it is already there, so a caller can ask for a state rather than for a flip, and
    a run cannot leave a setting inverted by asking twice.

    **`where` NAMES THE SCREEN, and it is required rather than optional.** Flipping a switch changes a
    setting on the founder's phone, so it goes in the book like every other change, and a debt that
    cannot say which screen the switch is on cannot be settled from a fresh process — which is the only
    kind this tool has.
    """
    if not isinstance(on, bool):
        raise Blocked("a switch is set to True or False")
    if where not in TABS and where not in PAGES:
        raise Blocked(
            f"{where!r} is not a screen this app has, and a switch change has to say where it was made "
            f"or it cannot be put back. One of: {', '.join(TABS + PAGES)}"
        )
    # "THIS IS NOT A SWITCH AT ALL" OUTRANKS "you are on the wrong page", because it is the answer that
    # stops a caller changing something it cannot change back.
    if one_way(label, package=package):
        raise Blocked(
            f"{label!r} is one of a set where exactly one is chosen, not a switch. Choosing it cannot be "
            "undone by choosing it again, so a run that flipped it would leave the founder's setting "
            "changed. Select the one you want by name instead, and put the original back by name."
        )
    # AND IT HAS TO BE THE SCREEN YOU ARE ACTUALLY ON. A name that merely EXISTS is not a name that can
    # restore anything: flipping `Smart insertion` while naming `Appearance` writes a debt pointing at a
    # page that does not have that switch, and the restore then fails at the moment it is needed.
    if not on_screen(where):
        raise Blocked(
            f"the phone is not showing {where!r}, so a change recorded against it could not be put back. "
            f"Open it first: open_tab({where!r}) or nav({where!r})."
        )
    before = switch(label, package=package)
    if before == on:
        return before
    # Owed BEFORE the tap. A debt written afterwards is not written at all when the tap is the thing
    # that goes wrong.
    debt = ("switch", json.dumps({"where": where, "label": label, "was": before}, sort_keys=True))
    _owe(debt)
    tap(label, package=package)
    time.sleep(0.6)
    after = switch(label, package=package)
    if after != on:
        raise Blocked(
            f"{label!r} was pressed but is still {'on' if after else 'off'}. The press landed and the "
            "setting did not change, so this is the app refusing it rather than a missed tap."
        )
    # SETTLE THE DEBT THAT THIS PUT RIGHT, not the one this call happened to write. Flipping a switch
    # and flipping it back is two calls: the first writes the debt, and the SECOND is the one that ends
    # it. An earlier version compared its own before-and-after, which are never equal after a real
    # flip, so every debt stayed in the book for ever and `restore()` kept trying to undo work that was
    # already undone.
    for owed in _owed():
        if owed[0] != "switch":
            continue
        named = json.loads(owed[1])
        if named["where"] == where and named["label"] == label and named["was"] == after:
            _settled(owed)
    return before


def _enclosing_control(node, package):
    """The clickable ANCESTOR of this label, or a refusal.

    Was geometry, and is now ancestry for the same reason `_holder` is: the smallest box that happens
    to contain a label is right almost always, and pressing the wrong control is not a mistake this
    harness may make almost never.
    """
    holder = _holder(node, lambda n: n["clickable"], package=package, described="pressable controls")
    if holder is None:
        raise Blocked(f"{_label(node)!r} is on screen but nothing containing it can be pressed")
    return holder


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


# HOW FAR A SCREEN IS WALKED BEFORE THE WALK GIVES UP. History holds one row per dictation and the
# founder's phone has hundreds, so "scroll until it stops moving" never stops: measured 2026-09-06, a
# `scan()` ran for six minutes on that one tab and was killed rather than finishing. A screen's SETTINGS
# are always near its top; an endless list below them is content, not controls.
SCREEN_DEPTH = 6


def reveal(label, package=PACKAGE):
    """Scroll until `label` is on screen, looking UP first. Returns True when it is there.

    **Looking down first is wrong and reads as absence.** A caller that has just walked a screen is at
    the BOTTOM of it, so the control it wants is above, and scrolling further down moves away from it
    until `find` says "nothing on screen matches" about a control that is plainly on the page. Measured
    2026-09-06: `Stop recording on silence` was reported missing from the Transcription tab, which had
    been scrolled past it moments earlier.

    So this goes back to the top, which is where a page's settings live, and only then works downward.
    """
    def try_scroll(direction):
        """True when the screen moved, False when there is nothing to scroll. Anything else RAISES.

        Catching every refusal alike turned an unreachable phone into "the control is not on this
        screen", which is a confident answer about the app produced by a broken instrument.
        """
        try:
            return bool(scroll(direction, 1, package=package))
        except Blocked as why:
            if "nothing on this screen scrolls" in str(why):
                return False
            raise

    if present(label, exact=True):
        return True
    # BOUNDED. A list whose rows change as it moves — a timer, a live count — makes every comparison
    # differ, so "keep going while the screen is moving" never stops. Twice the depth a page is walked
    # to is enough to get back to the top of one.
    for _ in range((SCREEN_DEPTH + 2) * 2):
        if not try_scroll("up"):
            break
        if present(label, exact=True):
            return True
    for _ in range(SCREEN_DEPTH + 2):
        if not try_scroll("down"):
            return False
        if present(label, exact=True):
            return True
    return False


def _walk_this_screen():
    """Every switch on this screen, including the ones below the fold, with a BOUND on how far it looks.

    Returns (switches, reached_the_bottom). The second value is not decoration: a screen this gave up on
    may hold controls nobody saw, and reporting it as fully walked is the quiet kind of wrong.
    """
    found = dict(switches())
    for _ in range(SCREEN_DEPTH):
        # "NOTHING ON THIS SCREEN SCROLLS" IS THE BOTTOM. Anything else that goes wrong is not, and an
        # earlier version caught every refusal alike — so an unreachable phone reported a fully walked
        # screen with no switches on it.
        if not any(n["scrollable"] and n["package"] == PACKAGE for n in tree(refresh=False)):
            return found, True
        if not scroll("down", 1):
            # The screen did not move. That is usually the end, and it is also what a swipe that missed
            # looks like, so it is not claimed as a complete walk.
            return found, False
        found.update(switches(refresh=False))
    return found, False


def scroll(direction="down", amount=1, package=PACKAGE):
    """Scroll the page, INSIDE the thing that actually scrolls, and say whether anything moved.

    Returns the number of swipes that CHANGED the screen. Zero means the page is already at its end,
    and a page with nothing to scroll refuses instead of swiping.

    **A blind swipe at fixed coordinates cannot tell "already at the bottom" from "my swipe missed",
    and both look like a page with nothing more on it.** Measured 2026-09-06 on the AI Polish page:
    three swipes returned an identical screen and reported success, which reads as a fully-walked page.
    That page happened to have nothing below the fold, so the wrong instrument gave the right answer,
    which is the way this defect survives.
    """
    if direction not in ("down", "up"):
        raise Blocked("direction must be 'down' or 'up'")
    if not isinstance(amount, int) or isinstance(amount, bool) or not 1 <= amount <= 20:
        raise Blocked("amount must be a whole number from 1 to 20")
    # ONE read serves both questions asked of this screen: what scrolls, and what was here before the
    # swipe. Reading twice costs about two seconds per swipe, and `scan()` swipes dozens of times.
    here = tree(refresh=True)
    areas = [n for n in here if n["scrollable"] and n["package"] == package]
    if not areas:
        raise Blocked(
            "nothing on this screen scrolls, so there is no more of it to see. Everything the page has "
            "is already on screen."
        )
    # The TALLEST scrollable area is the page body; a chip row scrolls sideways and is not what a
    # caller asking to see more of the page means.
    areas.sort(key=lambda n: n["bounds"][3] - n["bounds"][1], reverse=True)
    x0, y0, x1, y1 = areas[0]["bounds"]
    x = (x0 + x1) // 2
    # Well inside the area's own edges, so the swipe cannot start on the system gesture strip.
    near, far = y0 + (y1 - y0) // 5, y1 - (y1 - y0) // 5
    # AND NEVER START A DOWNWARD DRAG NEAR THE TOP OF THE SCREEN. Scrolling UP means dragging the
    # content down, and a drag that begins in the top strip is how Android opens the notification
    # shade — which then covers the app and every later step reports "EnviousWispr is not on screen".
    # Measured 2026-09-06: `reveal()` scrolled up on a page whose scrollable area starts high, and the
    # shade came down over the settings screen.
    _, height = _screen_size()
    near = max(near, height // 6)
    if near >= far:
        raise Blocked("this screen has no room to swipe in without starting at the very top of it")
    moved = 0
    for _ in range(amount):
        before = [(_label(n), n["bounds"]) for n in here if n["package"] == package]
        if direction == "down":
            _adb(f"input swipe {x} {far} {x} {near} 300")
        else:
            _adb(f"input swipe {x} {near} {x} {far} 300")
        time.sleep(0.6)
        _STATE["tree"] = None
        # The tree read here is left CACHED on purpose. A caller that scrolls and then asks what is on
        # screen would otherwise pay for a third `uiautomator dump` of the same screen, and each one
        # costs about two seconds: `scan()` took six minutes before this.
        here = tree(refresh=True)
        after = [(_label(n), n["bounds"]) for n in here if n["package"] == package]
        if after == before:
            break
        moved += 1
    return moved


def _screen_size():
    _, out = _adb("wm size")
    found = re.search(r"(\d+)x(\d+)", out or "")
    if not found:
        raise Blocked(f"the phone would not say how big its screen is: {out.strip()!r}")
    return int(found.group(1)), int(found.group(2))


def _start(component, what, settle):
    """Launch a component and ASSERT it landed, rather than reporting the launch.

    `am start` printing "Starting:" is not the app being on screen: it prints that for an activity that
    does not exist, and for one that starts and immediately finishes. On 2026-09-06 a navigation ran its
    whole sequence against a launcher because nothing checked, and every later step failed for a reason
    that had nothing to do with its own subject.
    """
    # ASK WHETHER THE PHONE CAN RECEIVE ANYTHING AT ALL, FIRST. `am start` succeeds against a locked
    # phone and the app really does start behind the keyguard, so the landing check below then reports
    # "EnviousWispr is not on screen" and names the lock screen's packages. That is a true sentence
    # about the wrong subject, and it sends a reader looking at the app. Measured 2026-09-06: the phone
    # locked itself mid-session and this is exactly what came back.
    ready()
    remote, out = _adb(f"am start -n {shlex.quote(component)}", check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"{what} would not start: {detail}. Is the app installed on {device()}?")
    time.sleep(settle)
    _STATE["tree"] = None
    showing = {n["package"] for n in tree()}
    if PACKAGE not in showing and "com.android.systemui" in showing:
        # THE SHADE, not a failure to start. It covers whatever is underneath, so the app really is not
        # on screen and the message would be true and useless. Closing a shade is not getting past a
        # lock, so it is allowed here where a swipe at a keyguard is not.
        _adb("cmd statusbar collapse", check=False)
        time.sleep(1.2)
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


def _dictation(what):
    """Send start / stop / cancel through the ONE door adb can reach.

    `DictationSessionService` is NOT exported, so `am start-service` at it is refused
    ("Permission Denial: ... not exported"). `VoiceInputActivity` IS exported and is the Samsung
    side-button trampoline: it reads `stop`, `cancel` and `toggle` boolean extras and forwards the
    matching command to the service. So this is not a back door, it is the same door the side button
    uses. Measured 2026-09-06 against `VoiceInputActivity.kt` and the manifest.
    """
    # STARTING A TAKE IS NOT AVAILABLE HERE, and that is a deletion rather than a guard.
    #
    # Two review rounds each found a different path that reached this function with "start", got past
    # whatever cleanup that caller had, and returned with the microphone open. The third one was found
    # after the fix for the second, which is the signal that the SHAPE is wrong: any number of callers
    # can start a take, and each one has to remember to end it. So the shape is gone. `open_recorder` is
    # a context manager that owns a take from its first instruction to its last, and it is the only
    # thing that can begin one.
    if what not in ("stop", "cancel"):
        raise Blocked("a take is started only by `open_recorder()`, which owns ending it")
    flag = {"stop": "--ez stop true", "cancel": "--ez cancel true"}[what]
    remote, out = _adb(f"am start -n {shlex.quote(RECORDER_ACTIVITY)} {flag}".strip(), check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"the recorder would not accept {what!r}: {detail}")
    time.sleep(1.5)
    _STATE["tree"] = None


def recording():
    """Is a dictation live RIGHT NOW, read from the two lines the capture itself writes.

    `AudioCapture` writes `recording_start` when the microphone opens and `recording_stop` when it
    closes, so a start with no later stop IS a live take. The subject is the witness, which is the
    property that matters here: no other signal is written by the thing actually holding the microphone.

    **The notification was tried first and is a trap.** `dumpsys notification` keeps our entry in its
    dump after the take has ended, so counting mentions of the package answered "yes, recording" a full
    two seconds after the log had already recorded `recording_stop`. A plausible number about the wrong
    thing (`validation-discipline.md`, plausible-value traps).

    Answers False when the log holds neither line, which is the honest reading of "nothing has started
    a take since the log was last cleared".
    """
    text = logs(lines=600)
    last_start = last_stop = -1
    for index, line in enumerate(text.splitlines()):
        if "recording_start" in line:
            last_start = index
        elif "recording_stop" in line:
            last_stop = index
    if last_start < 0 and last_stop < 0:
        # NEITHER LINE IS THERE, which is not "no take is running". It is what a cleared log looks like,
        # and what a take whose start scrolled out of the window looks like, and answering False sends a
        # caller away believing the microphone is closed.
        raise Blocked(
            "the log holds neither the start nor the end of a take, so whether the microphone is open "
            "cannot be read. Clear the log before the take you mean to measure."
        )
    return last_start > last_stop


def _start_take():
    """Send the start intent. THE ONLY CALLER IS `open_recorder`, which owns ending what this begins."""
    remote, out = _adb(f"am start -n {shlex.quote(RECORDER_ACTIVITY)}", check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"the recorder would not start: {detail}")
    time.sleep(1.5)
    _STATE["tree"] = None


def stop_dictation():
    """End a live take and keep what was said."""
    _dictation("stop")


def cancel_dictation():
    """Throw a live take away. This is what a failed check uses, because it keeps nothing."""
    _dictation("cancel")


def _kill_take():
    """Close the microphone, and let NOTHING come before it.

    `stop_app()` reads the accessibility settings first so it can put them back, and that read can fail
    — which used to mean the force-stop never happened and a recording kept running while the harness
    was busy being careful about a setting. So the order is: end the recording, THEN try the repair, and
    the repair's failure is raised after the microphone is already closed.
    """
    # THE ACCESSIBILITY STATE FIRST, because a force-stop CLEARS it and this is the emergency path —
    # the one most likely to be followed by the process ending. Journaled, so a later session can put it
    # back even if this one does not survive to.
    before = None
    try:
        before = _a11y_state()
        if ACCESSIBILITY_SERVICE in _a11y_services(before):
            _owe(("a11y-state", json.dumps(before, sort_keys=True)))
    except Blocked:
        # Unreadable. Closing the microphone still comes first; the settings are the lesser harm.
        before = None
    stopped, why = _adb(f"am force-stop {PACKAGE}", check=False)
    time.sleep(1)
    _STATE["tree"] = None
    if stopped != 0:
        # A FORCE-STOP THAT FAILED IS NOT A CLOSED MICROPHONE. Reporting success here let the caller
        # settle the take's debt while the recording carried on, which is the exact state the debt
        # exists to make impossible.
        raise Blocked(
            f"the recording would not stop and the app would not be force-stopped ({why.strip()}). "
            "The microphone may still be open on this phone."
        )
    # The repair is a separate concern and comes second, always.
    if before is not None and ACCESSIBILITY_SERVICE in _a11y_services(before):
        _put_a11y(before)
        _settled(("a11y-state", json.dumps(before, sort_keys=True)))
    elif not bound():
        enable_auto_paste()


def _cancel_safely():
    """End a take no matter what went wrong, and never RELY on the cancel having worked.

    **Every path out of a started take comes through here.** Two review rounds each found a different
    exit that skipped cleanup, which is why the shape changed: `open_recorder` is now a context manager
    and this is its `finally`.

    `stop_app()` is the last resort rather than the first: it force-stops the app, which CLEARS the
    accessibility permission, so it also repairs that. A recording left running is worse.
    """
    try:
        cancel_dictation()
    except BaseException:
        # The cancel itself could not be sent, so nothing here knows whether the microphone closed.
        _kill_take()
        return
    try:
        still_running = recording()
    except BaseException:
        # Whether a take is running is UNREADABLE, which is not the same as "no". One more look after a
        # pause, and if that fails too the microphone is closed the only way left.
        time.sleep(1.5)
        try:
            still_running = recording()
        except BaseException:
            _kill_take()
            return
    if still_running:
        _kill_take()


@contextmanager
def open_recorder(verify=True):
    """Start the floating recorder and OWN THE TAKE UNTIL THE BLOCK ENDS.

    ```python
    with open_recorder() as pill:
        ...                       # the take is running here, and only here
    ```

    **A context manager rather than a function, and that is the fix for a class rather than a case.**
    Two review rounds each found a different way to reach the start intent, get past whatever cleanup
    that caller had written, and return with the microphone open on the founder's phone — the second
    found after the first was fixed. Any number of callers being able to begin a take, each responsible
    for remembering to end it, is the defect; so beginning one is no longer something a caller can do.

    `verify=False` skips the pill check, for a caller whose subject is the audio rather than the
    recorder's appearance. The take is owned either way.

    `am start -a android.intent.action.ASSIST` shows a chooser on this phone, because more than one app
    answers the action (`device-testing.md` FACT: the-ASSIST-action-opens-a-CHOOSER-on-this-phone), so
    the component is named instead.
    """
    # THE PRE-COMMITTED CONSEQUENCE, and it is paid on every take.
    #
    # Round 5 found the last way a take could run with an empty book, and the criterion declared before
    # its verdict was that every take would then have to be preceded by a completed `restore()`. This is
    # that. It means the phone is left clean before any new recording starts, so an unknown take from a
    # killed run cannot sit under a new one, and a harness that cannot clean the phone cannot record on
    # it. The cost is a full restore before every take, which is the point rather than a side effect.
    if _STATE.get("restored_for") != device():
        raise Blocked(
            "restore() has not been run in this process, so the phone's state is unknown and a take "
            "must not be started on it. Call restore() first; it ends any recording an earlier run left "
            "behind and puts back anything it changed."
        )
    ready()
    if not bound():
        raise Blocked("our accessibility service is not bound, so no recorder will be drawn.")
    # WRITTEN TO DISK BEFORE THE START INTENT IS SENT, and this is the part a `try` cannot do.
    #
    # Three review rounds each found a different way out of a started take, and the third found one in
    # the fix for the second. The fourth was the start itself: the intent had reached the phone and the
    # cleanup was not installed yet. Moving the `try` up closes that window, and it still cannot close
    # the one that matters most — this process being killed, or the laptop losing the phone's network
    # mid-take. Nothing in Python runs then.
    #
    # A debt on disk does. `restore()` cancels any take this book still records, from ANY later session,
    # so "a recording is running and nobody knows" stops being reachable rather than becoming rarer.
    # The named consequence for a fourth escape was to delete `check_recorder` and `test_dictation`;
    # that would not have fixed this, because the manual `with open_recorder()` block has the identical
    # window. The object was wrong, so this replaces it rather than performing it.
    # A DEBT OF ITS OWN, not one keyed on the phone. Two sessions using the same key let the first
    # settle the second's take: A cancels and reads back its own, B starts another under the identical
    # key, and A's settle then erases the record of a recording that is running.
    take = ("take", uuid.uuid4().hex)
    # THE DECLARED CONSEQUENCE, and it is paid on every run.
    #
    # Round 4 found the last way a take could be running with nothing recording it: this process writes
    # the debt, ANOTHER session's `restore()` reads the book, finds a take with nothing running yet,
    # cancels nothing and settles it — and only then does this process send the start. Running, with an
    # empty book.
    #
    # Reading the debt back proves it reached the disk; holding the journal lock ACROSS the start is
    # what makes the window itself not exist, because no other process can read or settle the book
    # until the take is real. The cost is that a second session waits for this one, which on a harness
    # with exactly one phone is a description of the correct behaviour rather than a price.
    with _journal_locked():
        _owe_locked(take, device())
        if take not in _owed():
            raise Blocked("the take could not be recorded on disk, so it will not be started. A take "
                          "nothing has written down is one nobody can end.")
        # NO `except` HERE, DELIBERATELY. An interrupted or timed-out start may already have reached
        # the phone, so settling the debt on the way out is exactly the case the debt exists for: the
        # take runs and the book forgets it. An uncertain start keeps its debt, and `restore()` ends it.
        _start_take()
    try:
        time.sleep(1.0)
        _STATE["tree"] = None
        pill = None
        if verify:
            pill = overlay()
            if pill is None:
                raise Blocked(
                    "the recorder was started but no pill is in the accessibility tree, and the window "
                    "manager has no recorder window either, so the take did not draw. It has been "
                    "cancelled, so nothing is still listening."
                )
            if not pill["the_user_can_see_it"]:
                raise Blocked(f"the recorder window exists but the user cannot see it: {pill}")
        yield pill
    finally:
        _cancel_safely()
        # Settled only after the microphone is closed. `_cancel_safely` force-stops rather than return
        # while a take might still be running, so reaching here means it is not.
        _settled(take)


def nav(page):
    """Open a settings page from the drawer, by name."""
    open_settings()
    tap("Open settings menu")
    tap(page, exact=True)
    return look(only_ours=True)


ACCESSIBILITY_SERVICE = f"{PACKAGE}/{PACKAGE}.paste.PasteAccessibilityService"


# Both halves of the answer. Reading one and writing the other is how a repair half-happens.
_A11Y_KEYS = ("enabled_accessibility_services", "accessibility_enabled")


def _a11y_state():
    """Both accessibility settings, exactly as the phone stores them. `null` means the key is unset."""
    state = {}
    for key in _A11Y_KEYS:
        _, value = _adb(f"settings get secure {key}")
        state[key] = value.strip()
    return state


def _a11y_services(state=None):
    """The enabled services as a LIST. The phone stores them colon-separated, and ours is one of them."""
    raw = (state or _a11y_state())["enabled_accessibility_services"]
    return [item for item in raw.split(":") if item and item != "null"]


def _put_a11y(state):
    """Write both settings and read them back. `null` is a DELETE, not the four characters."""
    for key in _A11Y_KEYS:
        if state[key] == "null":
            _adb(f"settings delete secure {key}", check=False)
        else:
            _adb(f"settings put secure {key} {shlex.quote(state[key])}")
    time.sleep(2)
    now = _a11y_state()
    if now != state:
        raise Blocked(f"the accessibility settings did not go back: wanted {state}, they read {now}")


def enable_auto_paste():
    """Switch our accessibility service on and PROVE it bound. Returns whether anything changed.

    **IT ADDS OURS TO WHATEVER IS ALREADY ON, and never replaces the list.** An earlier version wrote
    our service as the whole value, which switches off every other accessibility service the founder
    uses — a screen reader, a switch-access tool, a password manager — to run a test. The setting is
    colon-separated for exactly this reason.

    Writing the setting is not the same as the service running: it can name a service that never binds
    (`code-gotchas.md` RULE: auto-paste-readiness-is-liveness-not-the-setting-string), so this reads
    `bound()` back rather than reporting the write.
    """
    if bound():
        return False
    before = _a11y_state()
    _owe(("a11y-state", json.dumps(before, sort_keys=True)))
    services = _a11y_services(before)
    if ACCESSIBILITY_SERVICE not in services:
        services.append(ACCESSIBILITY_SERVICE)
    _put_a11y({"enabled_accessibility_services": ":".join(services),
               "accessibility_enabled": "1"})
    for _ in range(6):
        time.sleep(1)
        if bound():
            return True
    raise Blocked(
        "auto-paste was switched on but the service never bound, so text will still not reach the "
        "field you are typing in. Check the log for a crash in the accessibility service."
    )


def stop_app():
    """Force-stop the app, and put auto-paste back, because force-stopping silently switches it off.

    **MEASURED TWO WAYS ON THE PHONE, 2026-09-06.** Before: `bound=True`, the setting names our
    service. After `am force-stop`: `bound=False` and the setting reads `null` — Android does not
    merely unbind it, it CLEARS the enabled-services list. Nothing turns it back on, so from then on
    dictated text stops reaching the field the user is typing in and lands on the clipboard instead.

    That is a silent, user-visible break caused by a harness convenience, which is why this repairs it
    here rather than warning about it. Founder-facing consequence, and worth knowing outside this tool:
    ANY force-stop does this — from app info, a task killer, or Samsung's own "put unused apps to
    sleep".
    """
    # MEMBERSHIP, NOT EQUALITY. With any other accessibility service also enabled the value reads
    # `theirs:ours`, so an equality test answered "it was not on" and the repair below never ran.
    # CAPTURED BEFORE, RESTORED AFTER, and never through `enable_auto_paste`. That function journals
    # the state it finds — which, after a force-stop, is the CLEARED one — so a later `restore()` would
    # put the founder's phone back to having no accessibility services at all.
    before = _a11y_state()
    was_on = ACCESSIBILITY_SERVICE in _a11y_services(before)
    if was_on:
        # ON DISK BEFORE THE FORCE-STOP. A force-stop CLEARS the accessibility settings, and this
        # process can end between the two — a crash, a killed run, the laptop losing the phone's
        # network — leaving the founder unable to dictate into anything with nothing recording why.
        _owe(("a11y-state", json.dumps(before, sort_keys=True)))
    _adb(f"am force-stop {PACKAGE}")
    time.sleep(1)
    _STATE["tree"] = None
    if was_on:
        _put_a11y(before)
        for _ in range(6):
            time.sleep(1)
            if bound():
                _settled(("a11y-state", json.dumps(before, sort_keys=True)))
                return
        raise Blocked(
            "the accessibility settings were put back after the force-stop but the service never bound, "
            "so text will still not reach the field you are typing in."
        )


# --------------------------------------------------------------------------------------------------
# Audio in, at low volume, from the Mac
# --------------------------------------------------------------------------------------------------

TEST_PACKAGE = f"{PACKAGE}.test"
SPEAKER_ACTIVITY = f"{TEST_PACKAGE}/{PACKAGE}.SpeakerPlaybackActivity"
UAT_FIXTURE = "enviouswispr-uat.pcm"
FIXTURE_SAMPLE_RATE = 16_000
FIXTURE_BYTES_PER_SAMPLE = 2


def _media_volume():
    # `check=True`: a failed read whose output happens to carry a plausible volume line was
    # otherwise accepted as the phone's real volume, and journaled as the value to restore.
    _, out = _adb("cmd media_session volume --stream 3 --get")
    reading = re.search(r"volume is (\d+) in range \[0\.\.(\d+)\]", out or "")
    if not reading:
        raise Blocked(f"could not read the phone's media volume, so it could not be safely lowered: {out.strip()!r}")
    return int(reading.group(1)), int(reading.group(2))


def stage_phone_speech(sentence, volume=3, use_fixture=True):
    """Get everything ready to speak BEFORE the take starts. Returns the volume that was there.

    **EVERYTHING EXPENSIVE HAPPENS HERE, and that is the whole point of the split.** Work done while a
    take is running costs the take: `scripts/uat/dictate-once.sh`, which has always worked, does
    exactly three things between start and stop, and a version of this that also checked the package,
    read and wrote the media volume, wrote the utterance file and ran a full `dumpsys window windows`
    inside that window lost the accessibility service and reported `handoff=SERVICE_NOT_RUNNING` —
    the harness manufacturing the exact failure it exists to measure. The proven script's shape is the
    one to copy, not to improve on.
    """
    if not isinstance(sentence, str) or not sentence.strip():
        raise Blocked("a non-empty sentence is required")
    if len(sentence.encode()) > 500:
        raise Blocked("the phone's helper reads at most 500 bytes and says its own sentence beyond that")
    installed = _adb(f"pm list packages {TEST_PACKAGE}", check=False)[1]
    if TEST_PACKAGE not in installed:
        raise Blocked(
            f"{TEST_PACKAGE} is not installed, so the phone cannot speak for itself. Build and install "
            "it (`phone-audio-playback.md`), or pass where='mac' to speak from the Mac instead."
        )
    current, ceiling = _media_volume()
    if not isinstance(volume, int) or isinstance(volume, bool) or not 0 <= volume <= max(1, ceiling // 3):
        raise Blocked(f"volume must be a whole number from 0 to {max(1, ceiling // 3)}; this is a low-volume tool")
    _owe(("media-volume", str(current)))
    _adb(f"cmd media_session volume --stream 3 --set {volume}")
    _adb(f"run-as {TEST_PACKAGE} mkdir -p files")
    # THE SENTENCE IS DATA, AND A HEREDOC MADE IT SYNTAX. Quoting the outer `sh -c` does nothing about
    # what is INSIDE it, so a sentence containing a line reading `WISPREOF` closed the heredoc and
    # everything after it ran as shell on the founder's phone. `printf %s` with the sentence quoted as
    # one argument has no such boundary.
    _adb(f"printf %s {shlex.quote(sentence)} | run-as {shlex.quote(TEST_PACKAGE)} "
         f"sh -c {shlex.quote('cat > files/speaker-utterance.txt')}")
    # WHICH AUDIO WILL ACTUALLY PLAY, said out loud rather than assumed.
    #
    # `SpeakerPlaybackActivity` PREFERS `cache/enviouswispr-uat.pcm` and only falls back to the phone's
    # own voice reading the sentence above. So with a fixture on the phone the caller's sentence is
    # written, ignored, and deleted, and the run measures somebody else's words. Measured 2026-09-06:
    # a run asking for "The quick brown fox jumps over the lazy dog." put
    # "Der Zug war schon wieder 20 Minuten zu spät." into Chrome, and every content check against the
    # fox would have failed while the product was working perfectly.
    fixture, listing = _adb(f"run-as {TEST_PACKAGE} ls -l cache/{UAT_FIXTURE}", check=False)
    if fixture == 0 and UAT_FIXTURE in listing and not use_fixture:
        # PARK IT, so the phone reads the sentence that was asked for. Journaled and put back, because
        # a fixture left parked changes what every later run plays without anyone deciding to.
        parked = f"cache/{UAT_FIXTURE}.parked"
        # REFUSE RATHER THAN OVERWRITE. Something already parked means an earlier run did not put its
        # fixture back, and moving over it destroys that one with nothing recording it.
        already, _ = _adb(f"run-as {TEST_PACKAGE} ls {shlex.quote(parked)}", check=False)
        if already == 0:
            raise Blocked(
                f"{parked} already exists, so an earlier run left a fixture parked. Call restore() to "
                "put it back before parking another one."
            )
        _owe(("parked-fixture", parked))
        _adb(f"run-as {TEST_PACKAGE} mv cache/{UAT_FIXTURE} {shlex.quote(parked)}")
        fixture = 1
    if fixture == 0 and UAT_FIXTURE in listing:
        # HOW LONG IT WILL PLAY, MEASURED NOW RATHER THAN GUESSED LATER. The fixture is 16 kHz mono
        # signed 16-bit, so its length is arithmetic on its size and costs one `ls` before the take
        # starts. A caller that instead waits a fixed number of seconds records the difference as
        # silence, and the founder watches the recorder sit there after the audio has plainly stopped.
        size = re.search(r"\s(\d{3,})\s", listing)
        seconds = int(size.group(1)) / (FIXTURE_SAMPLE_RATE * FIXTURE_BYTES_PER_SAMPLE) if size else None
        return current, {
            "what": f"the recorded fixture cache/{UAT_FIXTURE}, NOT the sentence asked for",
            "seconds": seconds,
        }
    # The phone's own voice has no length to measure in advance, so the caller must poll for the
    # helper to close itself. Saying `None` is how that is communicated, rather than a made-up number.
    return current, {"what": f"the phone's own voice reading {sentence!r}", "seconds": None}


def play_staged_speech():
    """Start the phone speaking. ONE adb call, because this one runs inside a live take."""
    _adb(f"am start -n {shlex.quote(SPEAKER_ACTIVITY)}", check=False)


def unstage_phone_speech():
    """Put back EVERYTHING staging changed: the media volume, and the parked fixture.

    The fixture matters as much as the volume and is easier to forget, because nothing on the phone
    looks different afterwards. A run that parks it and does not put it back silently changes what every
    later run plays, and the next person measures the phone's own voice while believing they measured
    the recorded clip.
    """
    for entry in list(_owed()):
        if entry[0] in ("media-volume", "parked-fixture"):
            _restore_one(entry)
            _settled(entry)


def say_on_phone(sentence, volume=3, seconds=30, use_fixture=True):
    """Speak out of the PHONE'S OWN speaker and wait for it to finish. Returns what it actually said.

    A convenience over `stage_phone_speech` / `play_staged_speech` for when NO take is running.
    **Do not call it during a dictation.** Its staging is several adb round trips, and work done inside
    a live take is what loses the pinned editor; `test_dictation` stages first for that reason.

    Speaking from the phone removes the one precondition the Mac path cannot check — that the phone is
    near the Mac and pointed at it. Measured 2026-09-06 with the phone elsewhere: a 12.3 second take
    decoded in 445 ms to `textChars=0`, a clean transcription of silence, which reads exactly like a
    broken speech engine.
    """
    _, will_say = stage_phone_speech(sentence, volume=volume, use_fixture=use_fixture)
    try:
        started = time.monotonic()
        play_staged_speech()
        # The helper closes ITSELF when the audio ends, so the wait is on that rather than on a guessed
        # duration: a fixed sleep either clips the sentence or records silence after it.
        for _ in range(seconds * 2):
            time.sleep(0.5)
            # `check=True`, and the COUNT is parsed rather than searched for any digit. A failed read
            # returned "", which contains no digit, which read as "the activity is gone" — so a broken
            # connection reported that the phone had finished speaking when it may not have started.
            # COUNTED HERE, not by a `grep -c` at the end of a pipe. A pipeline reports its LAST
            # command's status, so a failed dump produced a confident `0` — "the phone has finished
            # speaking" — from a measurement that did not happen.
            _, activities = _adb("dumpsys activity activities", timeout=90)
            running = str(sum(SPEAKER_ACTIVITY in line for line in activities.splitlines()))
            counted = re.fullmatch(r"\s*(\d+)\s*", running)
            if counted is None:
                raise Blocked(
                    "could not read whether the phone is still speaking, so how much of the sentence "
                    f"reached the microphone is unknown. The phone answered {running.strip()!r}."
                )
            if counted.group(1) == "0":
                break
        else:
            raise Blocked(f"the phone was still speaking after {seconds}s; the audio may be too long")
        return {"seconds": time.monotonic() - started, "said": will_say["what"]}
    finally:
        unstage_phone_speech()


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
    # Written BEFORE the change, and scoped to the HOST rather than to a phone: the Mac's speakers are
    # not a setting on anybody's device, and filing them under a serial would make them restorable only
    # while that phone is the one being driven.
    _owe(entry, serial="host")
    try:
        _checked(["osascript", "-e", f"set volume output volume {volume}"])
        started = time.monotonic()
        _checked(["say", "--", sentence], timeout=180)
        return time.monotonic() - started
    finally:
        _restore_one(entry)
        _settled(entry, serial="host")


# --------------------------------------------------------------------------------------------------
# Reading what happened
# --------------------------------------------------------------------------------------------------

def clear_log():
    """Clear the ring buffer so an absent line means absent rather than left over.

    **It no longer RESIZES the buffer, and that is a deletion rather than a fix.** The old version
    widened it to 16M and journaled "the buffer was N K" from the first line of `logcat -g` — which
    names one buffer out of several, and silently journaled nothing at all when that line did not match,
    while still doing the resize. A debt that cannot describe the change it undoes is worse than no
    resize: it leaves the founder's phone altered and the book saying otherwise. Every take this tool
    measures is seconds long, so the default buffer holds it.
    """
    _adb("logcat -c")


def keep_awake(minutes=30):
    """Hold the screen on for a long walk, and journal the old timeout so `restore()` puts it back.

    A walk of every page takes longer than the founder's screen timeout, and a phone that sleeps
    mid-walk produces a refusal about the lock screen rather than an answer about the page. This is a
    change to HIS daily driver, so it is journaled like any other
    (`session-behavior.md` RULE: revert-the-phone-after-a-session).

    It does NOT touch the lock itself. A locked phone is refused, never opened: waking and swiping does
    not defeat a secure lock, and a harness that tries is doing something nobody asked it to.
    """
    if not isinstance(minutes, int) or isinstance(minutes, bool) or not 1 <= minutes <= 60:
        raise Blocked("minutes must be a whole number from 1 to 60")
    _, previous = _adb("settings get system screen_off_timeout")
    previous = previous.strip()
    if not previous.isdigit():
        raise Blocked(f"could not read the phone's screen timeout, so it could not be safely changed: {previous!r}")
    # Owed BEFORE the write. A note about a change that did not happen is a wasted restore; a change
    # with no note is a setting left on the founder's phone.
    if not any(what == "screen-timeout" for what, _ in _owed()):
        _owe(("screen-timeout", previous))
    _adb(f"settings put system screen_off_timeout {minutes * 60_000}")
    return f"screen stays on for {minutes} minutes (was {int(previous) // 1000}s)"


def logs(pattern=None, lines=200):
    """The app's own log lines, newest last.

    `lines` is validated rather than interpolated: it reaches a shell command, and a string there would
    carry whatever the caller put in it.
    """
    if not isinstance(lines, int) or isinstance(lines, bool) or not 1 <= lines <= 10000:
        raise Blocked("lines must be a whole number from 1 to 10000")
    # LOGCAT'S OWN TAG FILTER, not a pipe into grep. Two things were wrong with the pipe: a pipeline
    # reports its LAST command's status, so a failed `logcat` looked like a successful empty read, and
    # `check=False` then turned that into "nothing was logged" — which `recording()` reads as "the
    # microphone is closed". The filter also cannot be defeated by a tag containing a regex character.
    filters = " ".join(shlex.quote(f"{tag}:V") for tag in APP_TAGS)
    _, out = _adb(f"logcat -d -v time {filters} {shlex.quote('*:S')}", timeout=90)
    selected = out.splitlines()[-lines:]
    if pattern:
        selected = [line for line in selected if re.search(pattern, line)]
    return "\n".join(selected).strip()


def last_take():
    """What the most recent dictation did, as a small dictionary a person can read.

    Every field comes from a line the app itself wrote. A field is None when the app never said it,
    which is different from zero and is left different.
    """
    text = logs(lines=400)
    # ONE TAKE, THE LAST ONE. Every field below is the FIRST match in the text, so with two takes in
    # the log a run could report one take's ending beside another take's character count and read as a
    # single coherent result.
    starts = list(re.finditer(r"(?m)^.*\brecording_start\b.*$", text))
    if not starts:
        raise Blocked("the log holds no take at all since it was last cleared, so there is nothing to "
                      "report on. Call clear_log() before the take you mean to measure.")
    text = text[starts[-1].start():]

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
        # A debt written by the old `clear_log`, which named one buffer out of several. It cannot be
        # settled honestly, so it is not settled. `logcat -G <size>` by hand is the fix.
        raise Blocked(
            f"a log-buffer debt of {previous!r} was left by an older version that could not name which "
            "buffer it changed. Put it back by hand with `adb shell logcat -G <size>` and delete the "
            f"entry from {_JOURNAL}."
        )
    elif what == "take":
        # A RECORDING THAT OUTLIVED THE PROCESS THAT STARTED IT. Only a debt on disk can carry this, and
        # only a later session can act on it, so this is the whole point of the take being journaled.
        cancel_dictation()
        time.sleep(1.5)
        if recording():
            stop_app()
            time.sleep(1.5)
        if recording():
            raise Blocked("a recording from an earlier run is STILL running and would not stop. The "
                          "microphone is open on this phone right now.")
    elif what == "parked-fixture":
        # `previous` is the path the fixture was moved to. Moved BACK, and the MOVE's own status is what
        # decides — an earlier version asked only whether something now sits at the destination, so a
        # failed move over a different file already there settled the debt and left the real fixture
        # parked, changing what every later run plays.
        moved, why = _adb(f"run-as {TEST_PACKAGE} mv {shlex.quote(previous)} cache/{UAT_FIXTURE}",
                          check=False)
        if moved != 0:
            raise Blocked(f"the recorded fixture would not move back from {previous!r}: {why.strip()}")
        code, listing = _adb(f"run-as {TEST_PACKAGE} ls -l cache/{UAT_FIXTURE}", check=False)
        if code != 0 or UAT_FIXTURE not in listing:
            raise Blocked(f"the recorded fixture did not come back from {previous!r}; the phone now plays "
                          "its own voice instead, and later runs would quietly measure the wrong audio")
    elif what == "media-volume":
        _adb(f"cmd media_session volume --stream 3 --set {int(previous)}")
        _, now = _adb("cmd media_session volume --stream 3 --get")
        reading = re.search(r"volume is (\d+)", now or "")
        if not reading or int(reading.group(1)) != int(previous):
            raise Blocked(f"the phone's media volume did not go back to {previous}; it reads {now.strip()!r}")
    elif what == "a11y-state":
        _put_a11y(json.loads(previous))
    elif what == "a11y-services":
        # A debt from an older version that recorded only the service list. The enable flag is derived
        # from it, which is the most that entry can support.
        state = {"enabled_accessibility_services": previous or "null",
                 "accessibility_enabled": "0" if previous in ("null", "", None) else "1"}
        if previous in ("null", "", None):
            state["enabled_accessibility_services"] = "null"
        _put_a11y(state)
    elif what == "switch":
        # Navigate BY NAME and set it BY NAME. A switch debt survives the process that made it, so the
        # screen has to be reached again from wherever the phone happens to be.
        wanted = json.loads(previous)
        if wanted["where"] in TABS:
            open_tab(wanted["where"])
        else:
            open_settings()
            tap("Open settings menu")
            tap(wanted["where"], exact=True)
        if not reveal(wanted["label"]):
            raise Blocked(f"{wanted['label']!r} could not be found on {wanted['where']}, so it cannot "
                          "be put back")
        if switch(wanted["label"]) != wanted["was"]:
            tap(wanted["label"])
            time.sleep(0.6)
        if switch(wanted["label"]) != wanted["was"]:
            raise Blocked(f"{wanted['label']!r} would not go back to "
                          f"{'on' if wanted['was'] else 'off'}")
    elif what == "screen-timeout":
        _adb(f"settings put system screen_off_timeout {int(previous)}")
        _, now = _adb("settings get system screen_off_timeout")
        if now.strip() != str(int(previous)):
            raise Blocked(f"the screen timeout did not go back to {previous}; it reads {now.strip()}")
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
    _STATE["restored_for"] = None
    done = []
    # The HOST book first, then this phone's. Never another phone's: a change owed to a device that is
    # not attached cannot be verified, and an unverified restore is not a restore.
    for scope in ("host", device()):
        for entry in list(_owed(scope)):
            _restore_one(entry)
            _settled(entry, scope)
            done.append(f"{entry[0]} back to {entry[1]}")
    _STATE["restore"] = []
    # THE SCREEN DUMP IS NOT DELETED, and that is the whole of it. `rm -f` on a fixed path removes
    # whatever is sitting there, and "it looks like XML" is a description, not proof of ownership: a
    # file of the founder's with that name and that shape would go silently. It is one small file that
    # every run overwrites, so leaving it costs nothing and deleting it can cost something that is not
    # ours to spend.
    _STATE["restored_for"] = device()
    return done or ["nothing was changed"]


# --------------------------------------------------------------------------------------------------
# Suites, each answering one question end to end
# --------------------------------------------------------------------------------------------------

def check_recorder():
    """Does the floating recorder open, and can the user see it? One call, and it always tidies up."""
    ready()
    report = []
    # The phone is left clean before anything is recorded on it, which is also what makes the take
    # below allowed to start at all.
    report.append("put back first: " + "; ".join(restore()))
    if not bound():
        return ["BLOCKED: auto-paste is switched off, so no recorder will be drawn. "
                "enable_auto_paste() turns it back on."]
    clear_log()
    try:
        with open_recorder() as pill:
            report.append(
                f"VERIFIED: the recorder is on screen at {pill['where']}, "
                f"{pill['size'][0]}x{pill['size'][1]}")
            report.append("VERIFIED: a take is running" if recording()
                          else "ISSUE: the recorder is drawn but nothing is recording")
    except Blocked as why:
        report.append(f"ISSUE: {why}")
    # AFTER the block, so this is asking whether the context manager's own cleanup worked.
    time.sleep(1.5)
    try:
        report.append("ISSUE: the take would not cancel and is STILL RECORDING" if recording()
                      else "VERIFIED: the take was cancelled and nothing is listening")
    except Blocked as why:
        report.append(f"ISSUE: whether anything is still recording could not be read: {why}")
    return report


# The lines that END a dictation, whatever the outcome. Polling for one of these is how
# `scripts/uat/dictate-once.sh` decides a run is over, and it beats a fixed sleep in both directions:
# a slow take is not cut off, and a fast one does not cost fourteen seconds of nothing.
# How long the recorder waits before the audio starts, and after it ends. Both are the smallest value
# that worked rather than a comfortable one: the lead-in covers the speaker activity launching, and the
# trailing margin covers the last word reaching the microphone. Everything beyond them is the founder
# watching his own recorder run on silence.
LEAD_IN_S = 1.5
TRAILING_MARGIN_S = 1.0

_TERMINAL_LINES = (
    "Auto-insert handed", "kept on clipboard", "Transcription failed",
    "Speech recognition failed", "No audio captured", "showError",
)


def _wait_for_the_take_to_finish(seconds=40):
    """Poll the log until the dictation reaches an ending, and say which one. None on a timeout."""
    for _ in range(seconds * 2):
        time.sleep(0.5)
        text = logs(lines=200)
        for marker in _TERMINAL_LINES:
            if marker in text:
                return marker
    return None


# THE WHOLE APP, AS IT IS ON SCREEN. Walked by hand on 2026-09-06 and checked against the code that
# produces it: `AppShell.kt`'s `AppDestination` for the four tabs and `SettingsPage` for the eight
# drawer pages, so a page added to the app and not to this list is a gap somebody can find rather than
# an invisible one. `scan()` reports any page it meets that is not named here.
TABS = ("History", "Dictionary", "Transcription", "AI Polish")
PAGES = ("What's New", "Appearance", "Microphone", "Sounds", "Clipboard",
         "Permissions", "Storage", "Open Source Licenses")


def _tab_row(package=PACKAGE):
    """The four tab labels along the bottom, found by the ONLY thing that distinguishes them.

    **Every tab name is on screen TWICE**: once as the heading of the page you are on, and once in the
    tab bar. `find('History')` correctly refuses that as ambiguous, so something has to say which is
    which, and it must not be a coordinate somebody typed.

    The rule is structural: the tab bar is the one horizontal band that holds ALL FOUR names. A heading
    is alone in its band. So the band is found by asking the app where its own four labels agree,
    which survives a different phone, a different text size and a moved bar.
    """
    nodes = [n for n in tree(refresh=False) if n["package"] == package and _label(n) in TABS]
    for candidate in nodes:
        top, bottom = candidate["bounds"][1], candidate["bounds"][3]
        band = [n for n in nodes if n["bounds"][1] < bottom and n["bounds"][3] > top]
        if len({_label(n) for n in band}) == len(TABS):
            return {_label(n): n for n in band}
    raise Blocked(
        "the four tabs are not all on screen, so which label is the tab bar cannot be told from which "
        "is a page heading. What is there:\n" + look(only_ours=True)
    )


def open_tab(name):
    """Move to one of the four main tabs.

    The tab you are ALREADY on reports itself as `selected` and NOT clickable, which is Compose saying
    there is nothing to press. That is an answer, not a failure, so this says so instead of refusing.
    """
    if name not in TABS:
        raise Blocked(f"{name!r} is not one of the four tabs: {', '.join(TABS)}")
    open_settings()
    tree(refresh=True)
    node = _tab_row()[name]
    if node["selected"] or _holder(node, lambda n: n["clickable"]) is None:
        return f"already on {name}"
    holder = _holder(node, lambda n: n["clickable"])
    x, y = holder["centre"][0], node["centre"][1]
    _STATE["tree"] = None
    _adb(f"input tap {x} {y}")
    time.sleep(1.2)
    return f"opened {name}"


def scan(toggle=False):
    """Walk EVERY screen in the app and read every switch on it. One call.

    With `toggle=True` each switch is flipped, read back, flipped again and read back, so the report
    says whether the switch actually MOVES rather than only what it currently reads. Every switch ends
    where it started, and a switch that will not come back is raised rather than left inverted.

    This is the Android twin of the macOS `scan()`, and the page list is the app's own two enums rather
    than a description of them.
    """
    ready()
    found = []
    for name in TABS:
        open_tab(name)
        here, whole = _walk_this_screen()
        found.append(("tab", name, here, whole))
    for name in PAGES:
        open_settings()
        tap("Open settings menu")
        tap(name, exact=True)
        here, whole = _walk_this_screen()
        found.append(("page", name, here, whole))
        back()

    report = []
    total = 0
    for kind, name, controls, whole in found:
        total += len(controls)
        tail = "" if whole else f" (only the first {SCREEN_DEPTH} screenfuls were looked at)"
        if not controls:
            report.append(f"{kind} {name}: no switches{tail}")
            continue
        report.append(f"{kind} {name}: " + ", ".join(
            f"{label} is {'on' if state else 'off'}" for label, state in sorted(controls.items())) + tail)
    report.append(f"{len(found)} screens, {total} switches")

    if toggle:
        report.append("")
        report.append("--- each switch flipped and put back ---")
        for kind, name, controls, _whole in found:
            if not controls:
                continue
            if kind == "tab":
                open_tab(name)
            else:
                open_settings(); tap("Open settings menu"); tap(name, exact=True)
            for label, was in sorted(controls.items()):
                if not reveal(label):
                    report.append(f"ISSUE: {name} / {label} could not be brought back on screen")
                    continue
                try:
                    if one_way(label):
                        report.append(f"SKIPPED: {name} / {label} is one of a set where exactly one is "
                                      "chosen, so flipping it could not be undone")
                        continue
                except Blocked as why:
                    report.append(f"ISSUE: {name} / {label}: {why}")
                    continue
                try:
                    set_switch(label, not was, where=name)
                    moved = switch(label) != was
                    set_switch(label, was, where=name)
                    back_again = switch(label) == was
                except Blocked as why:
                    report.append(f"ISSUE: {name} / {label}: {why}")
                    continue
                report.append(
                    f"{'VERIFIED' if moved and back_again else 'ISSUE'}: {name} / {label} "
                    f"{'moves and comes back' if moved and back_again else 'did not behave'}")
            if kind == "page":
                back()
    return report


def room_is_quiet(seconds=6):
    """Record for a few seconds playing NOTHING, and report what the microphone heard.

    **THE ONE PROBE THAT SEPARATES THREE FAILURES THAT LOOK IDENTICAL.** A content check can fail
    because the audio never played, because it played too quietly, or because somebody in the room was
    talking over it — and the third is the dangerous one, since it returns a fluent, well-punctuated
    transcript with healthy timing that reads exactly like the product returning a wrong answer.

    Measured 2026-09-06: a take with nothing played came back with 79 characters, and a later run asking
    for a German fixture returned "Usually, yeah." — the founder, sitting beside his phone. Both would
    have been filed against the speech engine.

    Words here mean the room is occupied, so STOP and say so rather than recording a content failure.
    Silence here means the room is fine and a failed content check is about the audio or the product.

    Non-disruptive by construction: it plays nothing, so it is safe to run while somebody is talking,
    which is exactly when it is needed.
    """
    ready()
    restore()
    clear_log()
    # `verify=False`: the subject is what the microphone HEARS, so the recorder's appearance is not
    # what this run is about, and refusing on it would refuse the probe on a phone whose pill is fine.
    with open_recorder(verify=False):
        time.sleep(seconds)
        stop_dictation()
        ending = _wait_for_the_take_to_finish(seconds=30)
    if ending is None:
        raise Blocked("the room probe never reached an ending, so it measured nothing")
    heard = last_take()["transcribed_chars"]
    if heard is None:
        raise Blocked("the probe ran but no transcription result was logged, so it measured nothing")
    return {
        # Named for what was measured. Nothing recognised is good evidence the room was quiet enough
        # for this engine, and it is not the same claim as acoustic silence — a sound the recogniser
        # discards still reaches the microphone.
        "quiet": heard == 0,
        "characters_heard": heard,
        "verdict": ("nothing was recognised with no audio played, so the room is quiet enough to test in"
                    if heard == 0 else
                    f"the room is OCCUPIED: {heard} characters came back with nothing played, so anything "
                    "measured now is measuring whoever is talking"),
    }


def test_dictation(sentence="The quick brown fox jumps over the lazy dog.", volume=3,
                   record_seconds=14, target_package="com.android.chrome", use_fixture=False):
    """Speak into the phone and report what came back. The whole heart path, in one call.

    **THE SHAPE IS COPIED FROM `scripts/uat/dictate-once.sh`, WHICH WORKS, AND THAT IS DELIBERATE.**
    Between starting the take and stopping it this does exactly two things: wait, and launch the
    speaker. Everything else — checking the test package, reading and lowering the media volume,
    writing the sentence to the phone — happens BEFORE the take, and reading the screen happens after.

    Measured 2026-09-06 against Chrome's address bar. A version that also read the recorder window
    (`dumpsys window windows`, several seconds) and did its staging inside the take logged
    `Pinned original editor package=com.android.chrome` and then, twenty-four seconds later,
    `No editor was pinned for this dictation; clipboard only` with `handoff=SERVICE_NOT_RUNNING`. The
    repository's own script, doing less, logged `handoff=SCHEDULED` on the same phone minutes later.
    So the harness was producing the failure, and its log read exactly like a defect in the app's
    insertion path.

    **Point the phone at a real editor first.** Insertion is judged against the field the user was in,
    so a run started from the app's own screens is testing something the product never does. Chrome's
    address bar is the safe one: nothing is sent.
    """
    ready()
    restore()
    if not bound():
        return ["BLOCKED: auto-paste is switched off, so nothing can be inserted. "
                "enable_auto_paste() turns it back on."]
    report = []

    def field_now():
        """The target editor's IDENTITY and its text. THE ORACLE, and it is not the clipboard.

        Publishing to the clipboard is the app's documented FALLBACK, so a clipboard check cannot tell
        a successful insertion from a failed one (`validation-discipline.md`
        RULE: verify-the-feature-not-the-crash). The editor's own text can.

        **Identity travels with the text so the comparison is about ONE editor.** Taking "the first
        EditText" before and after lets a different field answer the second time — a screen that gains
        an editor, or two whose order changes — and any difference then reads as words having arrived.
        Prefers the FOCUSED editor, which is the one a dictation aims at.
        """
        fields = [n for n in tree(refresh=True) if n["kind"] == "EditText"
                  and (target_package is None or n["package"] == target_package)]
        if not fields:
            return None
        focused = [n for n in fields if n["focused"]]
        chosen = focused or fields
        if len(chosen) > 1:
            raise Blocked(
                f"{len(chosen)} editors are on screen and none is uniquely focused, so which one the "
                "words were meant for is a guess"
            )
        node = chosen[0]
        return (node["package"], node["id"] or node["bounds"], node["text"])

    before = field_now()
    _, will_say = stage_phone_speech(sentence, volume=volume, use_fixture=use_fixture)
    # THE RECORDING IS AS LONG AS THE AUDIO, PLUS A MARGIN, AND NOT A ROUND NUMBER. A fixed wait leaves
    # the recorder running after the sound has stopped, which the founder can see and which records
    # whatever else is in the room. `record_seconds` stays as an override and as the answer when the
    # length genuinely cannot be known in advance.
    playing = will_say["seconds"]
    listen_for = round(playing + TRAILING_MARGIN_S, 1) if playing else record_seconds
    report.append(f"NOTE: the phone will play {will_say['what']}"
                  + (f", {playing:.1f}s long, so the take runs {listen_for:.1f}s" if playing else ""))
    try:
        clear_log()
        # The take's whole life is this block, and the context manager closes it however the block ends.
        # `verify=False` because the subject here is the words, not the pill.
        with open_recorder(verify=False):
            time.sleep(LEAD_IN_S)
            play_staged_speech()
            time.sleep(listen_for)
            stop_dictation()
            ending = _wait_for_the_take_to_finish()
    finally:
        # THE MICROPHONE FIRST, the settings second, and that ordering is why the restore sits OUTSIDE
        # the block rather than inside it: an earlier version put the volume back before trying to close
        # the take, so a failure in the restore left the take running.
        unstage_phone_speech()
    if ending is None:
        report.append("ISSUE: the dictation never reached an ending, so nothing can be said about it")
        return report
    take = last_take()
    report.append(f"VERIFIED: a take ran, ended by {take['ended_by']}" if take["ended_by"]
                  else "ISSUE: no take ending was logged")
    if take["transcribed_chars"]:
        report.append(f"VERIFIED: {take['transcribed_chars']} characters came back from the speech engine")
    else:
        report.append("ISSUE: nothing was transcribed. Either nothing reached the microphone, or the "
                      "speech engine returned empty.")
    handoff = take["handoff"]
    if handoff == "SCHEDULED":
        report.append("VERIFIED: the text was handed to the insertion path")
    elif handoff:
        report.append(f"ISSUE: the text did not reach the field ({handoff}); it is on the clipboard")
    else:
        report.append("ISSUE: no handoff was logged at all")
    # THE OUTCOME, not the request. `handoff=SCHEDULED` says the text was handed over; only the
    # editor's own content says it arrived. Measured 2026-09-06: a run logged SCHEDULED and then
    # `Editor action could not be verified after 20 attempts; clipboard only`, and the words were in
    # Chrome's address bar the whole time — so the log alone would have reported a working insertion
    # as a failure, and a clipboard check would have reported the fallback as a success.
    after = field_now()
    if after is None or before is None:
        report.append("ISSUE: no editor was on screen before or after, so where the words went is unknown")
    elif after[:2] != before[:2]:
        report.append("ISSUE: a DIFFERENT editor is on screen now, so nothing here says where the words "
                      "went")
    elif after[2] != before[2]:
        report.append(f"VERIFIED: the same editor changed, and now holds {after[2]!r}")
    else:
        report.append(f"ISSUE: the editor is unchanged; it still holds {after[2]!r}")
    return report


# --------------------------------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------------------------------

def _main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        print("commands: devices look tree find tap tab nav scan switches recorder dictate "
              "quiet logs take shot restore")
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
        elif command == "tab":
            print(open_tab(rest[0]))
        elif command == "scan":
            report = scan(toggle="--toggle" in rest)
            print("\n".join(report))
            return 1 if any(line.startswith(("ISSUE", "BLOCKED")) for line in report) else 0
        elif command == "switches":
            for label, on in sorted(switches().items()):
                print(f"{label}: {'on' if on else 'off'}")
        elif command == "quiet":
            answer = room_is_quiet()
            print(answer["verdict"])
            return 0 if answer["quiet"] else 1
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
