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
    WISPR_SERIAL=emulator-5554 python3 scripts/uat/wispr_eyes.py launch | unlock | mic off | dictate-emu | insert "text"

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
import signal
import shutil
import subprocess
import sys
import threading
import uuid
import time
from contextlib import contextmanager
from pathlib import Path

ADB = os.path.expanduser("~/Android/sdk/platform-tools/adb")
PACKAGE = "com.envi.wispr"
SETTINGS_ACTIVITY = f"{PACKAGE}/.ui.SettingsActivity"
RECORDER_ACTIVITY = f"{PACKAGE}/.ui.VoiceInputActivity"

# THE EMULATOR. One AVD, one serial, one PIN we set ourselves (device-testing.md FACT:
# the-play-store-emulator-for-gmail-and-chatgpt), and one gRPC endpoint the launch opens on purpose. The
# emulator is the only device this file ever unlocks or feeds audio to; the physical phone's PIN is the
# founder's and nothing here holds it.
EMULATOR_SERIAL = "emulator-5554"
PLAY_AVD = "EnviousWispr_Android_16_Play"
EMULATOR_PIN = "1234"
EMULATOR_BIN = os.path.expanduser("~/Android/sdk/emulator/emulator")
EMULATOR_PROTO_DIR = os.path.expanduser("~/Android/sdk/emulator/lib")
GRPC_ENDPOINT = "localhost:8554"
GRPC_SERVICE = "android.emulation.control.EmulatorController"

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
          "restored_for": None, "dump_path": None,
          # THE LOG WINDOW (#161 H5). `clear_log()` records the device clock here; every log read is
          # bounded to it, so an earlier take's lines cannot stand in for the take this process owns.
          "log_since": None,
          # HARDWARE IDENTITY PER TRANSPORT (#161 H7). One phone reaches adb as a USB serial, a network
          # address and an mDNS name; the restore book is keyed by `ro.serialno`, never by the cable.
          "identities": {},
          # THE EYE (#181). None: not probed yet. "fast": the debug build's dump receiver answers.
          # "slow": nobody answered the probe (a release build), so `uiautomator` for the rest of this
          # process. `eye_retry_after` counts slow reads still to go before the fast eye is tried again
          # after a transient refusal (unbound, too big, timeout).
          "eye": None, "eye_retry_after": 0}


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


def _atomic_change(function):
    """Hold the book's lock across an ordinary synchronous call.

    **WHAT THE TEST BESIDE THIS PROVES, AND WHAT IT DOES NOT.** It recognises certain debt calls and
    checks they sit inside an explicit `with _journal_locked()` or under an exact synchronous decorator
    stack. It handles a literal `getattr` and treats lambda and generator-expression bodies as deferred.
    It does NOT prove that every phone change is journaled, resolve an arbitrary alias, or prove that
    deferred work keeps the lock.

    An earlier version of this docstring said the class could not be reopened. It cannot say that, and
    saying it stopped the next reader looking — which is how two real defects reached a commit message
    as facts.
    """
    def wrapped(*args, **kwargs):
        with _journal_locked():
            return function(*args, **kwargs)
    wrapped.__name__ = function.__name__
    wrapped.__doc__ = function.__doc__
    wrapped.__wrapped__ = function
    wrapped._holds_the_book = True
    return wrapped


def _journal_write(book):
    """Replace the book in one step, so an interrupted write cannot leave a half-file.

    A `write_text` that dies mid-way leaves unreadable JSON, and the reader above then refuses
    everything — which is safe, and still worse than not being able to happen.
    """
    _JOURNAL.parent.mkdir(parents=True, exist_ok=True)
    beside = _JOURNAL.with_suffix(".writing")
    beside.write_text(json.dumps(book, indent=2))
    os.replace(beside, _JOURNAL)


def _identity_of(transport):
    """The hardware identity behind a transport serial (`ro.serialno`), read once per transport.

    A property read that FAILS is not an identity: it is refused, never guessed, because a debt filed
    under a guessed key is a debt nobody will pay.
    """
    cached = _STATE["identities"].get(transport)
    if cached:
        return cached
    code, out, _ = _run([ADB, "-s", transport, "shell", "getprop ro.serialno"], timeout=20)
    identity = out.strip()
    if code != 0 or not identity or identity == "unknown":
        raise Blocked(f"{transport} would not say what hardware it is (ro.serialno), so its restore "
                      "book cannot be found")
    _STATE["identities"][transport] = identity
    return identity


def _scope_key(scope):
    """The book's key for a scope: the literal `host`, or the hardware identity behind a transport.

    A transport that is NOT attached keeps the name it was filed under: nothing can be paid to it until
    it is attached, and the moment it is, `_migrate_locked` moves its debts under the identity.
    """
    if scope == "host":
        return "host"
    transport = scope or _STATE["serial"]
    cached = _STATE["identities"].get(transport)
    if cached:
        return cached
    if transport not in {serial for serial, _ in devices()}:
        return transport
    return _identity_of(transport)


def _migrate_locked(book, transports):
    """Move debts filed under a TRANSPORT serial to the identity key of that hardware. Under the lock.

    Older versions keyed the book by cable (`100.94.206.47:5555` and the USB serial were two books for
    one phone, #161 finding 11). Every debt is MOVED, never dropped: the transport-keyed entries come
    first (they are older), then the identity-keyed ones, with the first value per setting kept, the
    same rule `_owe_locked` applies. Returns whether the book changed.
    """
    changed = False
    # Group first, decide second, write last: two cables of one phone can carry DIFFERENT previous values
    # for the same setting, and which one adb lists first is not a reason to prefer it (code review round
    # 1). Identical duplicates collapse; a conflict is refused with every original key left untouched.
    plan = {}
    for transport in transports:
        if transport == "host" or transport not in book:
            continue
        identity = _scope_key(transport)
        if identity == transport:
            continue
        plan.setdefault(identity, []).append(transport)
    for identity, sources in plan.items():
        merged = [tuple(e) for e in book.get(identity, [])]
        for transport in sources:
            for entry in (tuple(e) for e in book[transport]):
                clash = [old for old in merged if _debt_key(old) == _debt_key(entry)]
                if clash and clash[0] != entry:
                    raise Blocked(
                        f"the restore book holds two different previous values for {entry[0]!r} on "
                        f"{identity} ({clash[0][1]!r} and {entry[1]!r}, one under {transport}); nothing was "
                        f"migrated. Read {_JOURNAL} and keep the value the phone really had."
                    )
                if not clash:
                    merged.append(entry)
        for transport in sources:
            book.pop(transport)
        book[identity] = [list(e) for e in merged]
        changed = True
    return changed


def _attached_transports():
    """Every transport adb lists right now; the set whose debts can be migrated and paid."""
    return [serial for serial, _ in devices()]


def _owed(serial=None):
    """Everything still owed back to a phone, as a list of (what, previous)."""
    scope = serial or _STATE["serial"]
    key = _scope_key(scope)
    book = _journal_read()
    if scope != "host" and any(t in book and _scope_key(t) != t for t in _attached_transports()):
        with _journal_locked():
            book = _journal_read()
            if _migrate_locked(book, _attached_transports()):
                _journal_write(book)
    return [tuple(entry) for entry in book.get(key or "", [])]


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
    if what == "choice":
        # One debt per GROUP, named by its members: the first state recorded is the one to go back to.
        try:
            named = json.loads(previous)
            return (what, named["where"], tuple(sorted(named["group"])))
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
    scope = serial or _STATE["serial"]
    key = _scope_key(scope)
    if scope != "host" and _migrate_locked(book, _attached_transports()):
        _journal_write(book)
    owed = [tuple(e) for e in book.get(key or "", [])]
    # THE FIRST VALUE FOR EACH SETTING IS THE ONE TO KEEP, and this is not a tidiness rule. Staging
    # twice recorded "media volume was 8" and then "media volume was 3", and restoring both in order
    # ended at 3 with an empty book: the phone left changed and the tool reporting it clean.
    #
    # "EACH SETTING" IS NOT "EACH KIND". There is one media volume, but there are eleven switches, and
    # keying them all on the word `switch` would let one flipped switch hide the next.
    if not any(_debt_key(old) == _debt_key(entry) for old in owed):
        owed.append(entry)
        book[key or ""] = [list(e) for e in owed]
        _journal_write(book)


def _replace_owed_locked(old, new, serial):
    """Replace the exact debt `old` with `new` in ONE journal write, so there is no moment with neither."""
    book = _journal_read()
    scope = serial or _STATE["serial"]
    key = _scope_key(scope)
    if scope != "host":
        _migrate_locked(book, _attached_transports())
    owed = [tuple(e) for e in book.get(key or "", [])]
    if old not in owed:
        raise Blocked(f"the debt {old!r} is not in the book, so it cannot be replaced")
    book[key or ""] = [list(new if e == old else e) for e in owed]
    _journal_write(book)


def _settled(entry, serial=None):
    """Drop a change from the book, ONLY after its restore was read back."""
    serial = serial or _STATE["serial"]
    with _journal_locked():
        _settled_locked(entry, serial)


def _settled_locked(entry, serial):
    book = _journal_read()
    scope = serial or _STATE["serial"]
    key = _scope_key(scope)
    if scope != "host":
        _migrate_locked(book, _attached_transports())
    owed = [tuple(e) for e in book.get(key or "", []) if tuple(e) != entry]
    book[key or ""] = [list(e) for e in owed]
    _journal_write(book)


# ─────────────────────────────────────────────────────────────────────────────────────────────────────
# THE RECORDING HALF OF THIS HARNESS IS OFF.
#
# Six review rounds, and every one found a different sequence that could leave a recording running on the
# founder's phone with nothing recording that it was. Each fix was correct and structural — the raw start
# was deleted, the take became a context manager, the take went into the on-disk book before the start
# intent under a lock held across it, each take got its own id, and no take could begin until the phone
# had been restored. Round six found another anyway: `restore()` decided what to do outside the lock, so
# a second session could erase the debt of a recording that had started while it waited.
#
# The consequence was declared in round six's own prompt, before its verdict was read, and this is it.
# The alternative is a seventh clever fix, and the evidence of five rounds is that the next one has one
# more sequence in it.
#
# The reading and settings half is untouched and is most of what this tool does: `look`, `find`, `tap`,
# `open_tab`, `nav`, `scan`, `switches`, `set_switch`, `overlay`, `recording`, `logs`, `last_take`,
# `restore`. Only the four calls that START a recording refuse.
#
# TO TURN IT BACK ON, delete this constant and the four guards that read it. Do that only on a review
# round whose verdict says, in as many words, that the take-with-no-debt class is CLOSED — not that the
# latest instance is fixed. Round six's `## CLASS` section is the shape of answer required.
RECORDING_IS_OFF = (
    "recording from this harness is off. Six review rounds each found a different sequence that could "
    "leave a recording running on the phone with nothing recording it, so the recording half is not "
    "offered until a round says the class is closed. Everything that READS the phone still works. "
    "(The EMULATOR is exempt: a take leaked there costs nothing and restore() ends it.)"
)


def _recording_is_off():
    """The recording lock, for the PHYSICAL phone only. `is_emulator()` asks the device, not its name."""
    return bool(RECORDING_IS_OFF) and not is_emulator()


class Blocked(RuntimeError):
    """The question cannot be answered right now, and driving on would produce a wrong answer."""


# --------------------------------------------------------------------------------------------------
# The phone
# --------------------------------------------------------------------------------------------------

def _run(args, timeout=60):
    done = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return done.returncode, done.stdout, done.stderr


def _adb(command, timeout=60, check=True, serial=None):
    """One `adb shell` command, with the REMOTE status read and, by default, enforced.

    `serial` names a device OTHER than the selected one, for `restore()` putting back a debt owed to an
    attached device this process is not driving. Nothing else passes it.

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
    target = serial or device()
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
    # THE EYE BELONGS TO THE DEVICE (#181 review): a release phone answering "slow" must not stop a
    # debug emulator selected later from being probed, and the other way round.
    _STATE["eye"], _STATE["eye_retry_after"] = None, 0


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
    # WAKING THE PHONE IS A CHANGE, and it is not journaled on purpose: a phone that is awake goes back
    # to sleep on its own timer, so there is nothing to put back. Nothing here gets past a LOCK.
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
    if _keyguard_showing():
        # THE EMULATOR IS OURS, AND ITS PIN IS OURS. The refusal below is about the founder's phone;
        # `is_emulator()` asks the device what it is (`ro.kernel.qemu`), not only what adb calls it,
        # and `unlock_emulator()` asks again before its first keystroke.
        if is_emulator():
            unlock_emulator()
            if _keyguard_showing():
                raise Blocked("the emulator stayed locked after one unlock attempt. Nothing can be driven.")
            return True
        raise Blocked(
            "the phone is locked, and only its owner can open it. This tool does not send taps, swipes "
            "or keys at a lock screen: it cannot pass a credential it does not have, and trying would "
            "spend real unlock attempts on the founder's own phone. Unlock it and run this again."
        )
    return True


def _keyguard_showing():
    """Whether the lock screen is up, from the one service that owns it.

    ASK THE WINDOW MANAGER, WHICH OWNS THE KEYGUARD. Two other signals were tried and both mislead:
    `deviceidle`'s `mScreenLocked` is that service's own view, and `dumpsys trust`'s `deviceLocked`
    answers "is a CREDENTIAL required", which is 0 for an insecure keyguard that is nonetheless
    covering the screen and swallowing every tap. An unclear answer is a refusal, never a guess.
    """
    _, activities = _adb("dumpsys activity activities | grep -i isKeyguardShowing", check=False)
    states = re.findall(r"isKeyguardShowing\s*=\s*(true|false)", activities)
    if not states or len(set(states)) != 1:
        raise Blocked(
            "the phone would not say clearly whether its lock screen is up"
            + (f" (it said {states})" if states else "")
            + ", so whether a tap would land is unknown. Refusing rather than driving blind."
        )
    return states[0] == "true"


def bound():
    """Whether OUR accessibility service is bound, which is what draws the recorder and inserts text.

    The enabled SETTING is not the answer: it still names a crashed service, and `install -r` unbinds
    without changing it (`code-gotchas.md` RULE: auto-paste-readiness-is-liveness-not-the-setting-string).
    """
    # Read the whole dump and decide here. `grep -c` exits non-zero when it finds nothing, which is the
    # ANSWER, not a failure, and a checked runner cannot tell those apart.
    _, out = _adb("dumpsys accessibility", timeout=90)
    # THE ANSWER HAS TO BE PRESENT. An empty or truncated dump contains no match, which read as "not
    # bound" — a confident no from a measurement that did not happen.
    if "Bound services:" not in out:
        raise Blocked("the accessibility dump did not contain a bound-services line, so whether our "
                      "service is running is unknown")
    # THE WHOLE BLOCK, NOT ITS HEAD (#161 finding 10). The bound entries carry the app LABEL only
    # (`Service[label=EnviousWispr, feedbackType[...], ...]`, read 2026-09-22) and are comma-separated
    # inside one `{...}`, so an older anchor on `{Service[label=EnviousWispr` was false whenever another
    # service was listed first. The COMPONENT lives in the enabled block, so both are required.
    bound_block = re.search(r"Bound services:\{(.*)\}\s*$", out, re.M)
    enabled_block = re.search(r"Enabled services:\{(.*)\}\s*$", out, re.M)
    if not bound_block or not enabled_block:
        raise Blocked("the accessibility dump's bound or enabled block did not parse, so whether our "
                      "service is running is unknown")
    return ("label=EnviousWispr" in bound_block.group(1)
            and ACCESSIBILITY_SERVICE in enabled_block.group(1))


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
    xml = _fast_dump_xml()
    last = None
    # A PATH OF OUR OWN, made once per process. A fixed name is a file we do not own: another session
    # writes it between our dump and our read, and reading it reports THEIR screen as ours.
    dump = _STATE.get("dump_path")
    if dump is None:
        dump = _STATE["dump_path"] = f"/sdcard/wispr-eyes-{uuid.uuid4().hex}.xml"
    for attempt in ((1, 2, 3) if xml is None else ()):
        remote, message = _adb(f"uiautomator dump {shlex.quote(dump)}", check=False)
        if remote == 0:
            _, xml = _adb(f"cat {shlex.quote(dump)}")
            if xml and "<hierarchy" in xml:
                break
            xml = None
            last = "the dump succeeded but wrote no tree"
        else:
            last = (f"status {remote}" + (f": {message.strip()}" if message.strip() else "")
                    + (" — something else is reading this phone's screen at the same time; shot() "
                       "and overlay() still answer, and last_take() reads the log"
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


DUMP_ACTION = "com.envi.wispr.debug.DUMP"
EYE_COOLDOWN_READS = 3


def _fast_dump_xml():
    """The screen from the app's own accessibility service, or None when `uiautomator` must do it.

    A DEBUG build carries `DebugDumpReceiver` (#181): one broadcast answers in ~0.1 s with the same XML
    `uiautomator dump` takes 1.9 s of process start to write, and it sees the service's own overlay.
    The result code is the contract: 1 + data = the tree; 0 = nobody answered, a release build, so
    this process stops asking; 2 (too big) and 3 (unbound, or the read threw), a timeout or an `am`
    error = this read is slow and the fast eye is tried again after EYE_COOLDOWN_READS slow reads, so a
    service that comes back is found again. Undecodable data is a refusal ONCE, naming the eye, then
    the same cooldown: a broken eye must not be quietly retired, and must not block every read.
    """
    if _STATE["eye"] == "slow":
        return None
    if _STATE["eye_retry_after"] > 0:
        _STATE["eye_retry_after"] -= 1
        return None
    try:
        remote, out = _adb(f"am broadcast -a {DUMP_ACTION} {PACKAGE}", timeout=10, check=False)
    except (Blocked, subprocess.TimeoutExpired):
        _STATE["eye_retry_after"] = EYE_COOLDOWN_READS
        return None
    match = re.search(r"Broadcast completed: result=(\d+)(?:, data=\"(.*)\")?", out, re.S)
    if remote != 0 or match is None:
        _STATE["eye_retry_after"] = EYE_COOLDOWN_READS
        return None
    code, data = int(match.group(1)), match.group(2)
    if code == 0:
        _STATE["eye"] = "slow"
        return None
    if code != 1 or not data:
        _STATE["eye_retry_after"] = EYE_COOLDOWN_READS
        return None
    import base64
    try:
        xml = base64.b64decode(data.strip(), validate=True).decode("utf-8")
    except (ValueError, UnicodeDecodeError) as why:
        _STATE["eye_retry_after"] = EYE_COOLDOWN_READS
        raise Blocked(f"the app's dump receiver answered with data that does not decode ({why}); "
                      "falling back to uiautomator on the next read") from why
    if "<hierarchy" not in xml:
        _STATE["eye_retry_after"] = EYE_COOLDOWN_READS
        raise Blocked("the app's dump receiver answered with something that is not a screen tree; "
                      "falling back to uiautomator on the next read")
    _STATE["eye"] = "fast"
    return xml


def _stable_tree(max_wait=1.5, gap=0.12):
    """The screen once it has STOPPED MOVING: two consecutive reads with the same geometry.

    A 1.9 s `uiautomator dump` was an accidental settle; the fast eye (#181) reads mid-animation, and a
    tab bar read 0.15 s after the settings screen launched sat 70 px above where it landed 0.15 s
    later, so the tap missed. Anything that turns a reading into a COORDINATE reads through this.
    `max_wait` bounds it; a screen that never settles (a live clock) is handed over as read, and the
    read-back after the action is what catches a miss.
    """
    def shape(nodes):
        return [(n["package"], n["id"], n["text"], n["desc"], n["bounds"], n["clickable"], n["enabled"],
                 n["checkable"], n["on"], n["parent"]) for n in nodes]

    previous = tree(refresh=True)
    if _STATE["eye"] != "fast":
        # A `uiautomator` read is 1.9 s of process start; the screen has settled long before it answers,
        # and a second one would double the cost of every tap on the phone (#181 review).
        return previous
    deadline = time.monotonic() + max_wait
    while time.monotonic() < deadline:
        time.sleep(gap)
        current = tree(refresh=True)
        if shape(current) == shape(previous):
            return current
        previous = current
    return previous


def _label(node):
    return node["text"] or node["desc"]


def look(only_ours=False, nodes=None):
    """What is on screen, as plain lines, top to bottom.

    Pass `only_ours=True` to drop the system chrome and other apps, which is usually what a UAT wants.
    `nodes` is a snapshot already read; `find` hands its own so a refusal describes the screen that
    failed rather than the one a second read finds (#181).
    """
    lines = []
    if nodes is None:
        nodes = tree()
    for node in sorted(nodes, key=lambda n: (n["bounds"][1], n["bounds"][0])):
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
        showing = sorted({n["package"] for n in nodes if n["package"]})
        scope = "from EnviousWispr" if only_ours else "with any words"
        return f"(nothing on screen {scope}. Showing instead: {', '.join(showing) or 'nothing'})"
    return "\n".join(unique)


def find(text, exact=True, clickable=None, package=PACKAGE, stable=False):
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
    snapshot = _stable_tree() if stable else tree(refresh=True)
    for node in snapshot:
        if package is not None and node["package"] != package:
            continue
        labels = {node["text"], node["desc"]} - {""}
        hit = any(label == text for label in labels) if exact else any(
            text.lower() in label.lower() for label in labels
        )
        if hit:
            matches.append(node)
    if not matches:
        raise Blocked(f"nothing on screen matches {text!r}. What is there:\n"
                      f"{look(only_ours=True, nodes=snapshot)}")
    if len(matches) > 1:
        # One control, several nodes: a button whose label is its own child reports the same text twice
        # at the SAME centre (the Play Store's Update button, 2026-09-21). Two nodes that would be pressed
        # at one point are one press, not a guess; the ambiguity this refuses is two DIFFERENT places.
        if len({n["centre"] for n in matches}) == 1:
            matches = [next((n for n in matches if n["clickable"]), matches[0])]
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
    # NEVER OVER SOMETHING ALREADY THERE. This is the one call that writes to a path the CALLER chose,
    # and `wb` on an existing file destroys it with nothing recording that it did.
    # `xb` RATHER THAN A CHECK. Asking whether the file exists and then opening it leaves a gap in
    # which something else can create it, and `wb` then truncates it. The filesystem can answer both at
    # once, so it does.
    try:
        handle = open(path, "xb")
    except FileExistsError:
        raise Blocked(f"{path} already exists and this will not write over it. Pass another name.") from None
    with handle:
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
        return _page_title_is(where)
    raise Blocked(f"{where!r} is not a screen this app has")


def _page_title_is(where, package=PACKAGE):
    """Is `where` the TITLE of the page showing: a label on the Back button's own line?

    Not "is the word anywhere on screen" (#218 emulator pass, 2026-09-23): the Microphone page also
    shows `Microphone` as the value of its Access row, so an exact-match lookup refused as ambiguous on
    every switch there, and a page that merely MENTIONS another page's name read as that page.
    """
    try:
        back_button = find("Back", exact=True, package=package)
    except Blocked as refusal:
        if "nothing on screen matches" in str(refusal):
            return False
        raise
    top, bottom = back_button["bounds"][1], back_button["bounds"][3]
    titles = [n for n in tree(refresh=False)
              if n["package"] == package and where in (n["text"], n["desc"])
              and top <= n["centre"][1] <= bottom]
    if len(titles) > 1:
        raise Blocked(f"{len(titles)} labels named {where!r} sit on the Back button's line, so which is "
                      "the page title is a guess")
    return bool(titles)


def one_way(label, package=PACKAGE):
    """Is this a SWITCH you can turn back, or a CHOICE you cannot un-make?

    **Getting it wrong changes the founder's settings and cannot undo it.** The AI Polish page offers
    `Off` / `This phone` / `Cloud`, and all three report themselves as checkable, exactly like the four
    switches on the Transcription tab. Turning one ON is a tap; turning it OFF is not a thing a
    single-choice group can do. A caller that flips one "and puts it back" turns the founder's polish
    off and leaves it off.

    **First the mark inside the row, then clickability.** A radio row holds a `RadioButton` and a chip
    row a `CheckBox`; a group is the unbroken run of sibling rows with the same mark (`_choice_group`).
    A radio member is always one-way; a chip group is one-way when exactly one is chosen, and `choose()`
    confirms it by pressing. Only a row with no mark falls back to clickability: a real switch is
    CLICKABLE while it is on, and the chosen member of a radio group is NOT. Read off the phone
    2026-09-06: the three Clipboard switches all report `checkable, checked, clickable` while on;
    `This phone` reports `checkable, checked` and no click. That rule alone misread the Writing style
    chips, whose chosen chip stays clickable (#218 emulator pass, 2026-09-23).
    """
    node = find(label, exact=True, clickable=None, package=package)
    row = _holder(node, lambda n: n["checkable"], package=package, described="switches")
    if row is None:
        raise Blocked(f"{label!r} is on screen but nothing around it is a switch")
    # THE MARK INSIDE THE ROW COMES FIRST (#218 emulator pass, 2026-09-23). The Writing style chips
    # (Tone, Structure, Context) are pick-one, and their CHOSEN chip stays clickable, so the clickable
    # rule below read them as switches: the flip-back press could not un-pick, and the journal filled
    # with switch debts that `restore()` could never pay. A chip row holds a CheckBox, a radio row a
    # RadioButton, and a switch row neither, on both eyes.
    nodes = tree(refresh=False)
    group = _choice_group(row, nodes, package=package)
    if group is not None:
        mark, members = group
        if mark == "RadioButton":
            return True
        chosen = sum(bool(m["on"]) for m in members)
        # NO CHOSEN CHIP IN VIEW IS NOT A SWITCH (Codex r1). A pick-one group scrolled so its chosen
        # chip is off screen shows only unchosen chips, alone or together; pressing one moves the pick
        # and pressing it again does not move it back. It cannot be told from a several-at-once group
        # with nothing on, so it is refused rather than guessed.
        if chosen == 0:
            raise Blocked(f"{label!r} is a chip whose group shows no chosen member, so whether it is "
                          "pick-one cannot be told. Bring the whole group into view first.")
        # Exactly one chosen is what a pick-one group looks like at rest; two or more on at once is a
        # set of check boxes, each one a switch. A several-at-once group with one on is caught by
        # `choose()`, which presses, reads, and undoes.
        return chosen == 1 and len(members) >= 2
    top, bottom = row["bounds"][1], row["bounds"][3]
    band = [n for n in nodes
            if n["checkable"] and n["package"] == package
            and n["bounds"][1] < bottom and n["bounds"][3] > top]
    if len(band) < 2:
        return False
    chosen = [n for n in band if n["on"]]
    return len(chosen) == 1 and not chosen[0]["clickable"]


# The mark a member of a choice group carries inside its own row. A switch row carries neither.
_CHOICE_MARKS = ("RadioButton", "CheckBox")


def _choice_group(row, nodes, package=PACKAGE):
    """(mark, member rows) for a row in a group of choices, or None for a row that is a switch.

    A group is the unbroken run of SIBLING rows carrying the same mark. Siblings, not a horizontal
    band: the Tone chips wrap, so `Formal` sits on the line below `Casual`. Unbroken: the Tone and
    Structure chips share one parent, and the `Structure` heading between them is what ends a group.
    """
    index = next((i for i, n in enumerate(nodes) if n is row), None)
    if index is None:
        raise Blocked("the row being asked about is not on the screen that was read")

    def mark(i):
        if not (nodes[i]["checkable"] and nodes[i]["package"] == package):
            return None
        kinds = {n["kind"] for n in nodes if n.get("parent") == i and n["kind"] in _CHOICE_MARKS}
        return kinds.pop() if len(kinds) == 1 else None

    own = mark(index)
    if own is None:
        return None
    siblings = [i for i, n in enumerate(nodes) if n.get("parent") == row.get("parent")]
    at = siblings.index(index)
    low = high = at
    while low > 0 and mark(siblings[low - 1]) == own:
        low -= 1
    while high + 1 < len(siblings) and mark(siblings[high + 1]) == own:
        high += 1
    return own, [nodes[i] for i in siblings[low:high + 1]]


def _row_title(row, nodes, package=PACKAGE):
    """The words a row is known by: its topmost, leftmost label, as `switches()` names it."""
    inside = []
    for node in nodes:
        if node["package"] != package or not _label(node):
            continue
        current = node
        while current is not None and not (current["package"] == package and current["checkable"]):
            current = nodes[current["parent"]] if current.get("parent") is not None else None
        if current is row:
            inside.append(node)
    if not inside:
        raise Blocked("a choice on screen carries no words, so it cannot be chosen or put back by name")
    inside.sort(key=lambda n: (n["bounds"][1], n["bounds"][0]))
    return _label(inside[0])


def _group_state(label, package=PACKAGE):
    """(mark, {member name: chosen}) for the choice group holding `label`, from a fresh read."""
    node = find(label, exact=True, clickable=None, package=package)
    row = _holder(node, lambda n: n["checkable"], package=package, described="switches")
    nodes = tree(refresh=False)
    group = _choice_group(row, nodes, package=package) if row is not None else None
    if group is None:
        raise Blocked(f"{label!r} is not one of a group of choices")
    mark, members = group
    state = {}
    for member in members:
        name = _row_title(member, nodes, package=package)
        if name in state:
            raise Blocked(f"two choices in the group holding {label!r} are both named {name!r}")
        state[name] = bool(member["on"])
    return mark, state


def _group_settled(label, before, package=PACKAGE, max_wait=1.2, gap=0.1):
    """The group holding `label` once it has moved off `before` and read the same twice, or the last read."""
    deadline = time.monotonic() + max_wait
    last = None
    while True:
        _, now = _group_state(label, package=package)
        if now != before and now == last:
            return now
        last = now
        if time.monotonic() >= deadline:
            return now
        time.sleep(gap)


def _settle_choices_locked(where, state):
    """Settle every choice debt on `where` whose recorded group is the state now showing. Caller holds the book."""
    for owed in _owed():
        if owed[0] != "choice":
            continue
        named = json.loads(owed[1])
        if named["where"] == where and named["group"] == state:
            _settled_locked(owed, _STATE["serial"])


@_atomic_change
def choose(label, where, package=PACKAGE):
    """Pick one member of a pick-one group BY NAME, read the whole group back. Returns it as it was.

    The debt records the WHOLE group, never one member as a switch: a pick-one choice is put back by
    choosing the original by name, which `restore()` does from the book alone. A group that turns out
    to let several be on at once is not pick-one: the press is undone and the call refuses.
    """
    if package != PACKAGE:
        raise Blocked(f"choices are only changed in {PACKAGE}, not in {package}")
    if where not in TABS and where not in PAGES:
        raise Blocked(f"{where!r} is not a screen this app has, and a choice has to say where it was made "
                      f"or it cannot be put back. One of: {', '.join(TABS + PAGES)}")
    if not on_screen(where):
        raise Blocked(f"the phone is not showing {where!r}, so a choice recorded against it could not be "
                      f"put back. Open it first: open_tab({where!r}) or nav({where!r}).")
    _, before = _group_state(label, package=package)
    if len(before) < 2:
        raise Blocked(f"{label!r} is alone, not one of a set of choices")
    already_on = sorted([name for name, on in before.items() if on and name != label])
    if len(already_on) + int(before[label]) > 1:
        # Two already on is several-at-once on its face. Pressing an already-chosen member here would
        # turn it OFF (Codex r1), so this refuses before pressing anything.
        raise Blocked(f"the group holding {label!r} lets several be on at once: "
                      f"{', '.join(sorted(already_on + ([label] if before[label] else [])))} are on, so it is not "
                      "pick-one. Nothing was pressed.")
    if before[label]:
        return before
    debt = ("choice", json.dumps({"where": where, "group": before}, sort_keys=True))
    _owe(debt)
    tap(label, package=package)
    after = _group_settled(label, before, package=package)
    if not after[label]:
        raise Blocked(f"{label!r} was pressed but is not chosen; the group is owed back to how it was")
    still_on = sorted([name for name, on in after.items() if on and name != label])
    if still_on:
        tap(label, package=package)
        undone = _group_settled(label, after, package=package)
        _settle_choices_locked(where, undone)
        raise Blocked(
            f"the group holding {label!r} lets several be on at once: choosing it left "
            f"{', '.join(still_on)} on, so it is not pick-one. {label!r} was pressed again to undo it"
            + ("." if undone == before else f", and the group did NOT go back; it reads {undone}, and "
               "the debt is kept for restore()."))
    _settle_choices_locked(where, after)
    return before


def _switch_settled(label, wanted, package=PACKAGE, max_wait=0.6, gap=0.1):
    """Wait for a switch to read the WANTED state on two consecutive reads, up to `max_wait`.

    Replaces a fixed 0.6 s after the press (#181): with a 0.1 s eye the state is usually there on the
    first read, and a state that appears once and flips back is not settled. A switch that never
    reaches the state is handed to the caller's own read-back, which is what refuses.
    """
    deadline = time.monotonic() + max_wait
    agreed = 0
    while True:
        if switch(label, package=package) == wanted:
            agreed += 1
            if agreed >= 2:
                return True
        else:
            agreed = 0
        if time.monotonic() >= deadline:
            return False
        time.sleep(gap)


@_atomic_change
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
    # A SWITCH DEBT NAMES A SCREEN AND A LABEL, AND NO PACKAGE. Two apps with a same-named switch on a
    # same-named screen would share one debt, and the second change would clear the first — both left
    # changed, book empty. This tool's screens are EnviousWispr's, so the honest fix is to say so.
    if package != PACKAGE:
        raise Blocked(f"switches are only changed in {PACKAGE}, not in {package}")
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
    _switch_settled(label, on, package=package)
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

    **IT RECORDS NO DEBT, and that is the caller's job rather than an oversight.** A press changes
    whatever the control does, and only the caller knows what that was: `set_switch` knows it is
    changing a setting and journals it, while `open_tab` and `nav` know they are only moving between
    screens. A debt written here would have to guess.

    Refuses a control that is present but disabled, and refuses when the phone cannot receive input at
    all, because both of those return success and prove nothing.
    """
    ready()
    # Ask for the LABEL, not for a clickable node. In Compose the words and the touch target are usually
    # different nodes: the drawer row carries "Storage" on a non-clickable child inside a clickable
    # parent, so demanding a clickable match refuses a control that is plainly there.
    node = find(text, exact=exact, clickable=None, package=package, stable=True)
    if clickable and not node["clickable"]:
        holder = _enclosing_control(node, package)
        # PRESS WITHIN THE LABEL'S OWN ROW. A clickable ancestor can be the whole drawer, and its centre
        # is then some other row entirely: tapping it opened "Open Source Licenses" when asked for
        # "Appearance" (2026-09-06). The holder proves something here is pressable; the label says WHERE.
        top, bottom = node["bounds"][1], node["bounds"][3]
        if not (top <= holder["centre"][1] <= bottom):
            holder = dict(holder, centre=(holder["centre"][0], node["centre"][1]))
        # AND THE COMPOSED POINT MUST LIE INSIDE THE CONTROL IT CAME FROM. Taking one coordinate from
        # the control and the other from the label is only sound while the result is still within the
        # control; asserting it is cheap, and the alternative is a press somewhere nobody asked for.
        px, py = holder["centre"]
        hx0, hy0, hx1, hy1 = holder["bounds"]
        if not (hx0 <= px <= hx1 and hy0 <= py <= hy1):
            raise Blocked(
                f"the point worked out for {text!r} falls outside the control it belongs to, so pressing "
                "it would press something else"
            )
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
    here = _stable_tree()
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
    # THIS OPENS THE APP, AND NOTHING ELSE. Handed the recorder's component it sends the very command
    # `open_recorder` writes inline, so the recording off-switch had a door beside it.
    if component != SETTINGS_ACTIVITY:
        raise Blocked(f"{what} is not something this opens; only the app's own settings screen is.")
    remote, out = _adb(f"am start -n {shlex.quote(component)}", check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"{what} would not start: {detail}. Is the app installed on {device()}?")
    # LOOK MORE THAN ONCE. `am start` returns as soon as the request is accepted, and the app arriving on
    # screen is a separate event that a busy phone takes longer to reach: measured 2026-09-06 with
    # Netflix playing, the first check saw Netflix and the very next call to this function found our app
    # already there. One reading was calling a slow transition a failed launch.
    for _ in range(6):
        time.sleep(settle / 3 if settle else 0.5)
        _STATE["tree"] = None
        if PACKAGE in {n["package"] for n in tree()}:
            _stable_tree()
            break
    showing = {n["package"] for n in tree(refresh=False)}
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


def open_settings(dismiss_onboarding=False):
    """Open the app, and get past onboarding so the caller reaches the thing it asked for.

    A fresh install lands on WELCOME, which replaces the whole shell, so every later step fails for a
    reason that has nothing to do with its own subject. Reinstalling and running the connected test task
    both produce that state, so a UAT meets it often.
    """
    _start(SETTINGS_ACTIVITY, "the app", 2.5)
    note = "opened"
    if present("Set up later") and not dismiss_onboarding:
        # OFF BY DEFAULT. Dismissing onboarding changes something the app REMEMBERS and this tool cannot
        # put back, and it is the one route left, outside the raw primitives, by which this harness could
        # change the founder's phone with nothing recording it. Passing `dismiss_onboarding=True` is a
        # caller saying it knows that.
        raise Blocked(
            "the welcome screen is showing, and getting past it changes something the app remembers "
            "that nothing here can put back. Dismiss it on the phone, or pass dismiss_onboarding=True "
            "if losing that is intended."
        )
    if dismiss_onboarding and present("Set up later"):
        # DISMISSING ONBOARDING IS A CHANGE AND IT IS NOT JOURNALED, because it cannot be put back: the
        # app remembers that setup was skipped and nothing here can un-remember it. Said out loud rather
        # than left for somebody to find. This is the founder's daily driver: onboarding state is not
        # restored, app data is never wiped, and `dismiss_onboarding=False` refuses to touch it.
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


def stop_dictation():
    """End a live take and keep what was said."""
    _dictation("stop")


def cancel_dictation():
    """Throw a live take away. This is what a failed check uses, because it keeps nothing."""
    _dictation("cancel")


def toggle_dictation():
    """The side button pressed AGAIN during a take: the bare toggle, the same intent the Samsung side
    button sends, with no `stop` extra. It is the only way to drive the launcher's stop path (#192: the
    launcher used to pin the focused editor before the owner decided that this toggle meant stop, so a
    take started in one editor could land in another). Allowed only while a take is live, so this never
    means "start"; `open_recorder()` alone starts takes.

    The liveness check and the intent are two steps, so a take that ends between them (silence, the cap)
    would make the toggle START one; `_press_launcher` compares the identity of the newest capture
    `recording_start` line before and after, and cancels a take identified as new before raising.
    """
    _press_launcher("toggle", "")


def _newest_recording_start():
    """The newest `recording_start` line the capture wrote, or None when the tail holds none.

    The IDENTITY of that line is the witness a rolling tail cannot fake: a new take writes a new line
    with its own timestamp, while a tail that rolled older lines out still ends at the same newest line.
    A count of lines across two snapshots can read equal or lower after a new start (round G2).
    """
    lines = [line for line in logs(lines=600).splitlines() if "recording_start" in line]
    return lines[-1] if lines else None


def _press_launcher(what, flag, deadline_s=3.0):
    """Send one launcher press during a live take and prove afterwards that it did not begin a take.

    The liveness check and the intent are two steps. A take ending between the liveness check and intent
    delivery turns the toggle into a start. The proof is the newest
    `recording_start` line before the press against the newest one seen during `deadline_s` after it
    (`_newest_recording_start`): a different line is a take this press began, which is cancelled before
    raising; a tail with no start line at all after the press is "cannot tell", which also raises, never
    "no new take". One quiet observation is not proof while a take can still be STARTING, so the whole
    deadline is watched.
    """
    if not recording():
        raise Blocked(f"{what!r} through the launcher is only sent during a live take here, and nothing is "
                      "recording; a take is started only by `open_recorder()`")
    newest_before = _newest_recording_start()
    if newest_before is None:
        raise Blocked("the log holds no recording_start line for the live take, so a new take could not be "
                      "told from it; not pressing")
    remote, out = _adb(f"am start -n {shlex.quote(RECORDER_ACTIVITY)} {flag}".strip(), check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"the recorder would not accept the {what}: {detail}")
    _STATE["tree"] = None
    waited = 0.0
    while waited < deadline_s:
        time.sleep(0.5)
        waited += 0.5
        newest = _newest_recording_start()
        if newest is None:
            raise Blocked(f"after the {what} the log tail held no recording_start line, so whether the press "
                          "began a take cannot be told; check the phone by hand")
        if newest != newest_before:
            _dictation("cancel")
            raise Blocked(f"the take had ended before the {what} landed, so the press began a new take; it "
                          "was cancelled. The scenario has to be run again.")


@_atomic_change
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
    unreadable = None
    try:
        before = _a11y_state()
        if ACCESSIBILITY_SERVICE in _a11y_services(before):
            _owe(("a11y-state", json.dumps(before, sort_keys=True)))
    except Blocked as why:
        # Unreadable. Closing the microphone still comes first, so the force-stop below runs anyway —
        # but the caller is TOLD afterwards, because a force-stop clears the accessibility settings and
        # nothing now knows what they were.
        before = None
        unreadable = why
        # A NOTE THAT SAYS "SOMETHING IS WRONG AND I CANNOT NAME IT" beats no note. The force-stop below
        # clears the accessibility settings and their previous values could not be read, so there is
        # nothing honest to restore — but a later session must still find out that it happened. The
        # unknown-entry handler refuses to settle this, which is the point.
        _owe(("a11y-state-unknown",
              "a force-stop cleared the accessibility settings and their previous values could not be "
              "read first, so they need putting back by hand"))
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
    if before is None:
        raise Blocked(
            "the recording was stopped by force-stopping the app, which CLEARS the accessibility "
            f"settings, and their original value could not be read first ({unreadable}). Auto-paste is "
            "probably off now; enable_auto_paste() turns it back on."
        )
    if ACCESSIBILITY_SERVICE in _a11y_services(before):
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
    if _recording_is_off():
        raise Blocked(RECORDING_IS_OFF)
    # THE PRE-COMMITTED CONSEQUENCE, and it is paid on every take.
    #
    # Round 5 found the last way a take could run with an empty book, and the criterion declared before
    # its verdict was that every take would then have to be preceded by a completed `restore()`. This is
    # that. It means the phone is left clean before any new recording starts, so an unknown take from a
    # killed run cannot sit under a new one, and a harness that cannot clean the phone cannot record on
    # it. The cost is a full restore before every take, which is the point rather than a side effect.
    # POPPED, so it is spent by the take it allows. Left in place it was once per process, which is
    # not what this was said to do: a second take in the same run would have started on a phone whose
    # state had changed since the last restore.
    if _STATE.pop("restored_for", None) != device():
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
    # The named consequence for a fourth escape was to delete `check_recorder` and the speaker take suite (since deleted, #161);
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
    # THE LOCK IS TAKEN HERE, INSIDE, AND NOT BY A DECORATOR. `@_atomic_change` on a
    # `@contextmanager` wraps the call that BUILDS the generator, so it acquires and releases the lock
    # before `__enter__` ever runs: the take, its body and its cleanup all happened outside it, while a
    # commit message of mine said the book was held for the whole take. It was not. A decorator cannot
    # hold a lock across a `yield`; only a `with` inside the generator can.
    with _journal_locked():
        _owe_locked(take, device())
        if take not in _owed():
            raise Blocked("the take could not be recorded on disk, so it will not be started. A take "
                          "nothing has written down is one nobody can end.")
        # THE START IS WRITTEN HERE AND NOWHERE ELSE. It used to be a function of its own, and a
        # function that starts a recording is one somebody can call: with the off-switch removed it
        # would send the start with no debt and no cleanup, which is the whole class again wearing the
        # shape of a helper. There is no such function now.
        #
        # NO `except` AROUND IT, DELIBERATELY. An interrupted or timed-out start may already have
        # reached the phone, so settling the debt on the way out is exactly the case the debt exists
        # for: the take runs and the book forgets it. An uncertain start keeps its debt.
        if _recording_is_off():
            raise Blocked(RECORDING_IS_OFF)
        remote, out = _adb(f"am start -n {shlex.quote(RECORDER_ACTIVITY)}", check=False)
        if remote != 0 or "Error" in out:
            detail = out.strip().splitlines()[-1] if out.strip() else "no message"
            raise Blocked(f"the recorder would not start: {detail}")
        time.sleep(1.5)
        _STATE["tree"] = None
        try:
            time.sleep(1.0)
            _STATE["tree"] = None
            pill = None
            if verify:
                pill = overlay()
                if pill is None:
                    raise Blocked(
                        "the recorder was started but no pill is in the accessibility tree, and the "
                        "window manager has no recorder window either, so the take did not draw. It has "
                        "been cancelled, so nothing is still listening."
                    )
                if not pill["the_user_can_see_it"]:
                    raise Blocked(f"the recorder window exists but the user cannot see it: {pill}")
            yield pill
        finally:
            _cancel_safely()
            # Settled only after the microphone is closed. `_cancel_safely` force-stops rather than
            # return while a take might still be running, so reaching here means it is not.
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
        if not value.strip():
            # An UNSET key reads `null`, so empty means the read itself failed. Saving that as the value
            # to restore would put the founder's phone into a state it was never in.
            raise Blocked(f"the accessibility setting {key} could not be read")
        state[key] = value.strip()
    return state


def _a11y_services(state=None):
    """The enabled services as a LIST. The phone stores them colon-separated, and ours is one of them."""
    raw = (state or _a11y_state())["enabled_accessibility_services"]
    return [item for item in raw.split(":") if item and item != "null"]


def _put_a11y(state):
    """Write both settings and read them back. `null` is a DELETE, not the four characters.

    **IT RECORDS NO DEBT OF ITS OWN, so it REFUSES unless the caller is holding the book.** Every caller
    — `enable_auto_paste`, `stop_app`, `_restore_one` — journals the previous state around it, and that
    was a contract nobody enforced: a future caller changing the founder's accessibility settings
    without a debt is exactly the defect six review rounds kept finding, one function at a time. The
    check makes it impossible rather than reviewable.
    """
    if not _STATE.get("holding_journal"):
        raise Blocked(
            "the accessibility settings may only be changed while holding the restore book, so the "
            "previous value is recorded. Wrap the caller in @_atomic_change."
        )
    # AND HOLDING THE BOOK IS NOT THE SAME AS HAVING WRITTEN IN IT. The lock says nobody else can
    # interfere; it says nothing about whether this change can be undone.
    if not any(what in ("a11y-state", "a11y-services", "a11y-state-unknown") for what, _ in _owed()):
        raise Blocked(
            "the accessibility settings' previous value is not in the restore book, so changing them "
            "now would leave the founder's phone altered with nothing able to put it back."
        )
    for key in _A11Y_KEYS:
        if state[key] == "null":
            _adb(f"settings delete secure {key}", check=False)
        else:
            _adb(f"settings put secure {key} {shlex.quote(state[key])}")
    time.sleep(2)
    now = _a11y_state()
    if now != state:
        raise Blocked(f"the accessibility settings did not go back: wanted {state}, they read {now}")


@_atomic_change
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
    if ACCESSIBILITY_SERVICE in services:
        # NAMED BUT NOT BOUND: an install over the app, or an instrumentation restart of its process
        # (seen 2026-09-22), unbinds the service and re-putting the SAME string does not rebind it
        # (`android-tooling.md` RULE: install-then-force-stop). Clear, settle, then set. The clear is
        # written without `_put_a11y`'s exact read-back: the system normalises `accessibility_enabled`
        # on its own right after the list empties (it read 1 with an empty list on this AVD), and the
        # debt covering both keys is already in the book above.
        _adb("settings delete secure enabled_accessibility_services", check=False)
        _adb("settings put secure accessibility_enabled 0")
        time.sleep(1.5)
    else:
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


def rebind_auto_paste_if_unbound():
    """REBIND our accessibility service when the settings already NAME it but it is not running; never enable it.

    An install over the app, an instrumentation restart of its process, or a force-stop leaves the service
    named in the settings and unbound (#161, seen 2026-09-22 after every `am instrument`). This helper is a
    rebind only (code review round 2): when the list does NOT name our service it changes nothing and
    returns, and the caller's `bound()` check reports BLOCKED, because switching a service ON is a real
    change to the phone that `enable_auto_paste()` journals and `restore()` owns.

    Two named states, two rules:
    - named and `accessibility_enabled` reads 1: rebind, require the settings to read exactly what they
      read before, and settle the debt the rebind journaled; nothing durable changed.
    - named and the flag reads 0: rebind, and settle too, with the reason written here rather than hidden.
      The flag is the SYSTEM's derived value while a service is listed: after a `put 0` with our service
      named, the AVD read 1 back on its own (a restore of that state failed its read-back at 01:37 on
      2026-09-22 and succeeded at 02:21, the same command), so a "named but 0" state is not one the phone
      can reliably be put back INTO, and a debt naming it is a restore that fails at random. The review
      names a phone or OEM that keeps that state deliberately; if one is ever met, this branch is where
      the rule changes, and the settle is logged in the return value so the run's report shows it.
    Returns what happened, for the report.
    """
    if bound():
        return "auto-paste already bound"
    before = _a11y_state()
    if ACCESSIBILITY_SERVICE not in _a11y_services(before):
        return "auto-paste is not enabled in the settings; nothing rebound"
    with _journal_locked():
        enable_auto_paste()
        after = _a11y_state()
        expected = dict(before, accessibility_enabled="1")
        if after != expected:
            return (f"auto-paste rebound but the settings read {after}, not {expected}; the debt is kept for "
                    "restore()")
        for entry in _owed():
            if entry[0] == "a11y-state" and entry[1] == json.dumps(before, sort_keys=True):
                _settled(entry)
        if before["accessibility_enabled"] == "1":
            return "auto-paste rebound (the settings already named and enabled it; nothing to restore)"
        return ("auto-paste rebound; the settings named it with the flag at 0, a state the system does not "
                "keep, so the flag's debt is settled (see rebind_auto_paste_if_unbound)")


@_atomic_change
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
UAT_FIXTURE = "enviouswispr-uat.pcm"
FIXTURE_SAMPLE_RATE = 16_000
FIXTURE_BYTES_PER_SAMPLE = 2


def is_emulator(serial=None):
    """Whether the driven device is an Android emulator: named like one by adb AND saying so itself.

    The serial prefix alone is what adb assigns, and a caller can hand any string to `WISPR_SERIAL`;
    `device()` checks only that it is attached. Everything this file does to an emulator (unlock it with
    a PIN, start a take despite the recording lock) must never reach the founder's phone, so the device
    is asked `ro.kernel.qemu`, which the emulator's kernel sets to `1` and a phone never does. An
    unreadable property answers False: the safe side is "not an emulator".
    """
    target = serial or device()
    if not target.startswith("emulator-"):
        return False
    try:
        code, out, _ = _run([ADB, "-s", target, "shell", "getprop ro.kernel.qemu"], timeout=20)
    except (OSError, subprocess.SubprocessError, UnicodeError):
        return False
    return code == 0 and out.strip() == "1"


def _require_emulator(what):
    target = device()
    if not is_emulator(target):
        raise Blocked(f"{target} is not an emulator, so {what} is refused: it is for the AVD only")
    return target


def unlock_emulator():
    """Open the EMULATOR's lock screen with the PIN this project set on it, once, and read back.

    The physical phone is never unlocked by this file (`ready()` says why). The emulator's PIN is ours
    (`EMULATOR_PIN`), so the same sequence a person uses is sent: wake, swipe up, the PIN, enter. ONE
    attempt: a second guess at a PIN is how a lockout starts, even on a device we own, and a PIN that
    did not work is a fact to report rather than retry. The device is asked what it is again right
    before the first keystroke, because that is the moment a wrong answer would cost something.
    """
    target = _require_emulator("unlocking")
    if not _keyguard_showing():
        return "already open"
    if not is_emulator(target):
        raise Blocked(f"{target} stopped answering as an emulator between two probes; no key was sent")
    _adb("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    # RAISE THE PASSWORD FIELD THROUGH THE WINDOW MANAGER, READ IT BACK, and only then type. A blind
    # swipe-then-type typed the PIN into nothing in two of five benchmark runs (2026-09-20): a
    # notification on the lock screen took the swipe. `wm dismiss-keyguard` asks the keyguard itself,
    # which on a secure lock shows the bouncer (measured: field up on the first read, twice); one swipe
    # is the fallback. The PIN is typed once, after the field is seen.
    def field_up():
        _STATE["tree"] = None
        return any(n["id"] == "passwordEntry" for n in tree())

    if not field_up():
        _adb("wm dismiss-keyguard", check=False)
        for _ in range(3):
            time.sleep(1.0)
            if field_up():
                break
        else:
            width, height = _screen_size()
            _adb(f"input swipe {width // 2} {int(height * 0.8)} {width // 2} {int(height * 0.3)} 300")
            time.sleep(1.2)
            if not field_up():
                raise Blocked("the emulator's password field never came up, so no PIN was typed. "
                              "Something is covering the lock screen; look() shows what.")
    _adb(f"input text {shlex.quote(EMULATOR_PIN)}")
    _adb("input keyevent KEYCODE_ENTER")
    # THE KEYGUARD TAKES A FEW SECONDS TO GO, and a single read at two seconds called a successful unlock
    # a failure (benchmark run 4, 2026-09-20: "stayed locked", and the very next read said open). Poll.
    for _ in range(12):
        time.sleep(0.5)
        if not _keyguard_showing():
            _STATE["tree"] = None
            return "unlocked"
    raise Blocked("the emulator stayed locked six seconds after one PIN attempt; the AVD's PIN may not "
                  f"be {EMULATOR_PIN!r} any more. Not retrying.")


def _grpc(method, payload=None, stdin_path=None, timeout=120):
    """One call to the emulator's own control service, as JSON in and JSON out.

    `grpcurl` (brew) is the client; the proto ships with the emulator. A streaming call hands a file of
    newline-delimited JSON messages as stdin. Anything but a clean exit is a refusal that carries the
    tool's own words, never a guess about what the emulator did.
    """
    if device() != EMULATOR_SERIAL:
        raise Blocked(f"the control service at {GRPC_ENDPOINT} belongs to {EMULATOR_SERIAL} only, and "
                      f"{device()} is selected; a change would land on the wrong device and be owed to "
                      "the wrong one")
    if not _has_tool("grpcurl"):
        raise Blocked("grpcurl is not installed (brew install grpcurl), so the emulator's control "
                      "service cannot be reached")
    args = ["grpcurl", "-plaintext"]
    proto = os.path.join(EMULATOR_PROTO_DIR, "emulator_controller.proto")
    if os.path.exists(proto):
        args += ["-import-path", EMULATOR_PROTO_DIR, "-proto", "emulator_controller.proto"]
    if stdin_path is not None:
        args += ["-d", "@"]
    elif payload is not None:
        args += ["-d", json.dumps(payload)]
    args += [GRPC_ENDPOINT, f"{GRPC_SERVICE}/{method}"]
    try:
        with open(stdin_path if stdin_path else os.devnull, "rb") as feed:
            done = subprocess.run(args, stdin=feed, capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired) as why:
        raise Blocked(f"grpcurl could not run {method}: {why}") from why
    if done.returncode != 0:
        raise Blocked(f"the emulator refused {method} over gRPC at {GRPC_ENDPOINT}: "
                      f"{done.stderr.strip() or done.stdout.strip() or 'no message'}. "
                      "Was it launched with -grpc 8554? launch_emulator() does that.")
    text = done.stdout.strip()
    if not text:
        return {}
    try:
        return json.loads(text)
    except ValueError:
        return {"raw": text}


def _mic_state():
    """Whether the emulator's virtual microphone is fed by the HOST microphone, read from the emulator."""
    return bool(_grpc("getMicrophoneState").get("realAudioEnabled", False))


def set_host_mic(on):
    """Point the EMULATOR's microphone at the host microphone (on) or at nothing but injected audio (off).

    Measured 2026-09-20: gRPC `getMicrophoneState.realAudioEnabled` tracks the console's `hostmicon` /
    `hostmicoff`, and `setMicrophoneState` flips the same switch, so the console is not used any more
    and, unlike the console, the state can be READ. The previous state is journaled and read back from
    disk BEFORE the change, so a crash between the two leaves a debt rather than a silent emulator, and
    a mismatch on the read-back keeps the debt. `restore()` from any later process puts it back.
    """
    _require_emulator("the host microphone switch")
    previous = _mic_state()
    if previous == bool(on):
        return f"host microphone already {'on' if on else 'off'}"
    entry = ("host-mic", "on" if previous else "off")
    with _journal_locked():
        _owe_locked(entry, device())
        if entry not in _owed():
            raise Blocked("the microphone change could not be written to the restore book, so it was "
                          "not made")
        _grpc("setMicrophoneState", {"realAudioEnabled": bool(on)})
        now = _mic_state()
    if now != bool(on):
        raise Blocked(f"the emulator's microphone did not switch {'on' if on else 'off'}; it reads "
                      f"{'on' if now else 'off'}. The previous state stays owed in {_JOURNAL}.")
    return f"host microphone {'on' if on else 'off'}"


@_atomic_change
def _rest_host_mic_off():
    """Make host-mic OFF the emulator's RESTING state, settled rather than owed.

    Measured 2026-09-20: a take whose capture opened right after the host mic was switched dropped the
    injected audio 4 times in 25 (`Transcription result received (chars=0)`, `NO_SPEECH`), and 0 times
    in 10 when the mic was already off and nothing switched inside the take. The mechanism is the
    emulator's, not ours; what is ours is to never switch inside a take. With off as the resting state,
    `dictate_emulator` finds it "already off" and `restore()` has nothing to flip; `say_into_emulator`
    (the BlackHole path) turns it on for its own take and back. Called at launch and at the start of a
    take that finds the mic on.
    """
    if not _mic_state():
        return "host microphone already off"
    _grpc("setMicrophoneState", {"realAudioEnabled": False})
    if _mic_state():
        raise Blocked("the emulator's host microphone would not switch off")
    # A debt whose destination IS off is now moot and is settled. A debt owing "on" is not: something
    # switched the mic off and still owes turning it back on, and `restore()` keeps that promise.
    for owed in list(_owed()):
        if owed == ("host-mic", "off"):
            _settled(owed)
    return "host microphone off (resting state)"


def _processes_named(name):
    """Every process on the device named EXACTLY `name`, as (pid, state), from one `ps` read.

    `ps -A -o PID,S,NAME` is toybox on Android 14+ (read on the AVD 2026-09-21). Exact name, never a
    substring: `com.envi.wispr` is a prefix of every helper process, and a prefix match would pick four.
    """
    code, out = _adb("ps -A -o PID,S,NAME", check=False)
    if code != 0:
        raise Blocked(f"the process table could not be read ({out.strip()})")
    rows = []
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 3 and parts[2] == name:
            rows.append((int(parts[0]), parts[1]))
    return rows


def _the_one_process(name, what):
    """The pid of the ONE process named `name`, or a refusal naming the count and the candidates.

    A harness that acts must refuse, not choose (`tools-and-apps.md` RULE:
    a-harness-that-acts-must-refuse-not-choose): zero matches and two matches are both refusals.
    """
    rows = _processes_named(name)
    if len(rows) != 1:
        raise Blocked(f"{what} refused: {len(rows)} processes are named {name!r} ({rows}); exactly one "
                      "is required, verified in this call")
    return rows[0][0]


JDB_CANDIDATES = ("/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home/bin/jdb", "jdb")
JDB_SUSPENDED = "All threads suspended."


def _jdb():
    for candidate in JDB_CANDIDATES:
        found = shutil.which(candidate) if not os.path.isabs(candidate) else (candidate if os.path.exists(candidate) else None)
        if found:
            return found
    raise Blocked("no `jdb` on this Mac, so a process cannot be frozen; install a JDK (`brew install openjdk@21`)")


def _spawn_debugger(port, log_path):
    """A debugger attached to the forwarded JDWP port that suspends every thread and then WAITS.

    Measured on the AVD 2026-09-21: `kill -STOP` from `run-as` returns 0 and changes nothing (the process
    reads S afterwards), and the Play image has no root, so the wedge is a JDWP `suspend`: binder calls
    into the process then block (`dumpsys meminfo <pid>` takes its 5 s timeout instead of 0.0 s), and the
    VM RESUMES the moment the debugger disconnects, which is what makes the thaw a kill of this process.
    The `sleep` keeps jdb's stdin open so it outlives the Python that spawned it; its own session, so the
    thaw can kill the whole group by the pid the book carries.
    """
    script = f"(echo suspend; exec sleep 100000) | {shlex.quote(_jdb())} -attach localhost:{port} > {shlex.quote(log_path)} 2>&1"
    child = subprocess.Popen(["/bin/sh", "-c", script], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
    return child.pid


def _debugger_command(host_pid):
    """The command line of `host_pid` on this Mac, or None when no such process exists."""
    done = subprocess.run(["/bin/ps", "-o", "command=", "-p", str(host_pid)], capture_output=True, text=True)
    line = done.stdout.strip()
    return line or None


def _kill_debugger(host_pid, port):
    """End the debugger group identified by pid AND command line; verified gone afterwards."""
    command = _debugger_command(host_pid)
    if command is None:
        return "debugger already gone"
    if f"localhost:{port}" not in command or "jdb" not in command:
        raise Blocked(f"host pid {host_pid} is not the debugger the book recorded ({command!r}); refusing to kill it")
    os.killpg(host_pid, signal.SIGTERM)
    for _ in range(50):
        if _debugger_command(host_pid) is None:
            return f"debugger pid {host_pid} ended"
        time.sleep(0.1)
    raise Blocked(f"the debugger pid {host_pid} did not end after SIGTERM; the process may still be frozen")


def _process_answers(pid, within_s=3.0):
    """Whether the device process answers a binder call within `within_s` (a frozen one takes the 5 s timeout)."""
    started = time.monotonic()
    try:
        subprocess.run([ADB, "-s", device(), "shell", f"dumpsys meminfo {pid}"], capture_output=True, text=True, timeout=within_s + 6)
    except subprocess.TimeoutExpired:
        return False
    return time.monotonic() - started < within_s


def freeze_process(name=f"{PACKAGE}:audio"):
    """Freeze one of OUR processes on the EMULATOR through a debugger, by a pid verified in this call, journaled.

    For the #115 scenes: a frozen capture process is the wedge the owner must end a take over. The debt
    (device pid, name, forwarded port, debugger pid) goes into the book BEFORE the debugger is spawned and is
    completed once its pid is known, so `restore()` from any later process thaws it by ending that debugger.
    Verified by the debugger's own line in its log, then by a binder probe that takes its timeout.
    """
    _require_emulator("freezing a process")
    pid = _the_one_process(name, "freezing")
    port = 18700 + pid % 1000
    log_path = os.path.join(os.path.dirname(_JOURNAL), f"jdb-{pid}.log")
    frozen = {"pid": pid, "name": name, "port": port, "host_pid": None}
    entry = ("frozen-process", json.dumps(frozen, sort_keys=True))
    with _journal_locked():
        _owe_locked(entry, device())
        if entry not in _owed():
            raise Blocked("the freeze could not be written to the restore book, so it was not done")
        _adb_host(["forward", f"tcp:{port}", f"jdwp:{pid}"])
        frozen["host_pid"] = _spawn_debugger(port, log_path)
        complete = ("frozen-process", json.dumps(frozen, sort_keys=True))
        # ONE write: settle-then-owe left a whole frozen process with no debt if this died between (#213 review).
        _replace_owed_locked(entry, complete, device())
    for _ in range(100):
        time.sleep(0.1)
        try:
            with open(log_path) as f:
                if JDB_SUSPENDED in f.read():
                    break
        except FileNotFoundError:
            pass
    else:
        thaw_process(name)
        raise Blocked(f"the debugger never reported {JDB_SUSPENDED!r} for pid {pid} ({name}); see {log_path}. Thawed.")
    if _process_answers(pid):
        thaw_process(name)
        raise Blocked(f"pid {pid} ({name}) still answers a binder call after the suspend. Thawed.")
    return f"froze {name} pid {pid} (debugger pid {frozen['host_pid']} on port {port})"



# One row of jdb's `threads` listing, as the Play AVD's ART prints it (measured 2026-09-22):
#   `  (java.lang.Thread)21342        AudioCaptureThread                 running`
# The class varies (HandlerThread, coroutine workers), the id is decimal, and a name may contain single
# spaces ("Signal Catcher"); the name ends at the run of two or more spaces before the status.
JDB_THREAD_LINE = re.compile(r"^\s*\(([^)]+)\)(\S+)\s+(.+?)\s{2,}\S", re.MULTILINE)


def _thread_ids(threads_output, thread_name):
    """The JDWP ids of every thread named exactly `thread_name` in a jdb `threads` listing."""
    return [tid for _cls, tid, name in JDB_THREAD_LINE.findall(threads_output) if name == thread_name]


def _spawn_commandable_debugger(port, log_path, commands_path):
    """A debugger attached to the forwarded JDWP port that reads its commands from `commands_path` as they are
    appended, and outlives the Python that spawned it; its own session, so the thaw ends the whole group by
    the pid the book carries. The VM resumes every thread the moment the debugger disconnects."""
    open(commands_path, "w").close()
    script = (f"tail -f {shlex.quote(commands_path)} | {shlex.quote(_jdb())} -attach localhost:{port} "
              f"> {shlex.quote(log_path)} 2>&1")
    child = subprocess.Popen(["/bin/sh", "-c", script], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
    return child.pid


def _debugger_says(log_path, predicate, seconds=10.0):
    """Wait for the debugger's log to satisfy `predicate`; the log text, or None at the deadline."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with open(log_path) as f:
                text = f.read()
        except FileNotFoundError:
            text = ""
        if predicate(text):
            return text
        time.sleep(0.1)
    return None


def freeze_thread(name=f"{PACKAGE}:audio", thread_name="AudioCaptureThread"):
    """Suspend ONE named thread of one of OUR processes on the EMULATOR, journaled like `freeze_process` (#213).

    `freeze_process` suspends every thread, which also freezes whatever in that process would recover from
    the wedge. This suspends only `thread_name` (exactly one live thread must carry it) and leaves the
    process answering binder calls: for #213 the capture thread, the sole owner of the recorder's release,
    then never reaches its cleanup while the service stays alive. It stages the invariant, not a vendor
    `AudioRecord.read()` that ignored `stop()`. `restore()` and `thaw_process(name)` end the debugger, which
    resumes the thread; the book entry is the same `frozen-process` kind, with the thread named.
    """
    _require_emulator("freezing a thread")
    pid = _the_one_process(name, "freezing a thread of")
    port = 18700 + pid % 1000
    log_path = os.path.join(os.path.dirname(_JOURNAL), f"jdb-{pid}-thread.log")
    commands_path = os.path.join(os.path.dirname(_JOURNAL), f"jdb-{pid}-commands.txt")
    frozen = {"pid": pid, "name": name, "port": port, "host_pid": None, "thread": thread_name}
    entry = ("frozen-process", json.dumps(frozen, sort_keys=True))
    with _journal_locked():
        _owe_locked(entry, device())
        if entry not in _owed():
            raise Blocked("the freeze could not be written to the restore book, so it was not done")
        _adb_host(["forward", f"tcp:{port}", f"jdwp:{pid}"])
        frozen["host_pid"] = _spawn_commandable_debugger(port, log_path, commands_path)
        # ONE write, never settle-then-owe: a crash between those two left a debugger with no debt (#213 review).
        complete = ("frozen-process", json.dumps(frozen, sort_keys=True))
        _replace_owed_locked(entry, complete, device())
        entry = complete

    def command(text):
        with open(commands_path, "a") as f:
            f.write(text + "\n")

    if _debugger_says(log_path, lambda t: "Initializing jdb" in t or "> " in t) is None:
        thaw_process(name)
        raise Blocked(f"the debugger never attached to pid {pid} ({name}); see {log_path}. Thawed.")
    command("threads")
    listing = _debugger_says(log_path, lambda t: JDB_THREAD_LINE.search(t) is not None and thread_name in t)
    ids = _thread_ids(listing or "", thread_name)
    if len(ids) != 1:
        thaw_process(name)
        raise Blocked(f"{len(ids)} live threads are named {thread_name!r} in pid {pid} ({name}); exactly one is "
                      f"required. Thawed.")
    tid = ids[0]
    # The thread's id is in the book BEFORE the suspend it names.
    frozen["thread_id"] = tid
    with _journal_locked():
        named = ("frozen-process", json.dumps(frozen, sort_keys=True))
        _replace_owed_locked(entry, named, device())
        entry = named
    command(f"suspend {tid}")
    command(f"where {tid}")
    stack = _debugger_says(log_path, lambda t: "captureLoop" in t or "isn't suspended" in t)
    if stack is None or "isn't suspended" in stack:
        thaw_process(name)
        raise Blocked(f"{thread_name} ({tid}) in pid {pid} did not report a suspended stack; see {log_path}. Thawed.")
    if not _process_answers(pid):
        thaw_process(name)
        raise Blocked(f"pid {pid} ({name}) stopped answering with only {thread_name} suspended. Thawed.")
    return f"froze thread {thread_name} ({tid}) of {name} pid {pid}, inside captureLoop; the process still answers"


def _adb_host(args):
    """An adb command that is not `shell` (forward, forward --remove) against the selected device."""
    done = subprocess.run([ADB, "-s", device(), *args], capture_output=True, text=True)
    if done.returncode != 0:
        raise Blocked(f"adb {' '.join(args)} failed: {(done.stderr or done.stdout).strip()}")
    return done.stdout.strip()


def _debugger_group(ps_text, port):
    """From `ps -axo pid=,pgid=,command=` text: the LEADER pid of the one process group running a debugger
    on `port`, or None. A launch is a `/bin/sh -c` leader plus its `jdb` child in one group, so rows are
    grouped by PGID; two groups refuse, and a group whose leader is gone refuses."""
    groups = {}
    present = set()
    for line in ps_text.splitlines():
        parts = line.strip().split(None, 2)
        if len(parts) < 3 or not parts[0].isdigit() or not parts[1].isdigit():
            continue
        pid, pgid, command = int(parts[0]), int(parts[1]), parts[2]
        present.add(pid)
        if "jdb" in command and f"localhost:{port}" in command:
            groups.setdefault(pgid, []).append(pid)
    if len(groups) > 1:
        raise Blocked(f"{len(groups)} debugger process groups are attached to port {port} ({sorted(groups)}); none is ended")
    if not groups:
        return None
    leader = next(iter(groups))
    if leader not in present:
        raise Blocked(f"the debugger group {leader} on port {port} has no leader left; none is ended")
    return leader


def _debugger_on_port(port):
    """The leader pid of the ONE debugger group on this Mac attached to `port`, or None. A failed `ps` refuses:
    for a thread-only freeze the process still answers, so "no debugger" would settle a debt still frozen."""
    done = subprocess.run(["/bin/ps", "-axo", "pid=,pgid=,command="], capture_output=True, text=True)
    if done.returncode != 0:
        raise Blocked(f"ps failed ({done.returncode}), so the debugger on port {port} cannot be found; the debt is kept")
    return _debugger_group(done.stdout, port)


def _thaw(frozen):
    """End the debugger the book recorded, drop the forward, and read back that the process answers."""
    lines = []
    host_pid = frozen.get("host_pid")
    if host_pid is None and frozen.get("port") is not None:
        # A debt written before its debugger's pid was: the one debugger on that port is the one it names.
        host_pid = _debugger_on_port(frozen["port"])
    if host_pid is not None:
        lines.append(_kill_debugger(host_pid, frozen["port"]))
    if frozen.get("port") is not None:
        subprocess.run([ADB, "-s", device(), "forward", "--remove", f"tcp:{frozen['port']}"], capture_output=True, text=True)
    state = dict(_processes_named(frozen["name"])).get(frozen["pid"])
    if state is None:
        lines.append(f"{frozen['name']} pid {frozen['pid']} is gone; nothing to thaw")
        return "; ".join(lines)
    if not _process_answers(frozen["pid"]):
        raise Blocked(f"pid {frozen['pid']} ({frozen['name']}) still does not answer after the debugger ended")
    lines.append(f"thawed {frozen['name']} pid {frozen['pid']}")
    return "; ".join(lines)


@_atomic_change
def thaw_process(name=f"{PACKAGE}:audio"):
    """Thaw the process `freeze_process` froze, from the book, and settle the debt. Under the book's lock
    for the whole read-act-settle, like `restore()`."""
    _require_emulator("thawing a process")
    lines = []
    for entry in list(_owed()):
        if entry[0] != "frozen-process":
            continue
        frozen = json.loads(entry[1])
        if frozen["name"] != name:
            continue
        lines.append(_thaw(frozen))
        _settled(entry)
    return lines or [f"no frozen {name} is in the book"]


def kill_process(name=f"{PACKAGE}:audio"):
    """SIGKILL one of OUR processes on the EMULATOR, by a pid verified in this call, from the app's own uid.

    `run-as <package> kill -9 <pid>` lands (measured on the AVD 2026-09-21; the shell uid cannot signal an
    app process, and `-STOP` from `run-as` does not take). Android restarts a bound service's process on
    the next bind, so nothing is owed afterwards. A frozen process dies too; its thaw debt is settled by
    the gone-pid branch of `restore()`.
    """
    _require_emulator("killing a process")
    pid = _the_one_process(name, "killing")
    package = name.split(":")[0]
    code, why = _adb(f"run-as {shlex.quote(package)} kill -9 {pid}", check=False)
    if code != 0:
        raise Blocked(f"pid {pid} ({name}) would not die: {why.strip()}")
    for _ in range(30):
        if pid not in dict(_processes_named(name)):
            return f"killed {name} pid {pid}"
        time.sleep(0.1)
    raise Blocked(f"pid {pid} ({name}) is still in the process table 3 s after SIGKILL")


def _pcm_from_sentence(sentence, path):
    """Say a sentence into a raw PCM file the emulator's microphone accepts: s16le, mono, 48 kHz."""
    for tool in ("say", "ffmpeg"):
        if not _has_tool(tool):
            raise Blocked(f"{tool} is not available, so no audio can be made for the emulator")
    aiff = path + ".aiff"
    _checked(["say", "-v", "Samantha", "-r", "160", "-o", aiff, "--", sentence], timeout=180)
    _checked(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", aiff,
              "-f", "s16le", "-ar", "48000", "-ac", "1", path], timeout=180)
    # HALF A SECOND OF SILENCE ON THE END. The stream ends when the last packet is sent, and the last
    # word was cut to "to Ma." on the first live take (2026-09-20, 1.8 s of audio, no padding); the
    # recogniser needs the tail to hear the word end.
    with open(path, "ab") as tail:
        tail.write(bytes(48000))
    return path


PCM_CHUNK_BYTES = 9600  # 100 ms of s16le mono at 48 kHz


def _audio_packets(pcm_path, packets_path):
    """Chunk raw PCM into the newline-delimited JSON AudioPackets `injectAudio` streams.

    Every packet carries its `format` AND a `timestamp` (microseconds, ascending). A packet with no
    timestamp crashed the emulator on 2026-09-13 (device-testing.md RULE:
    feed-emulator-audio-over-grpc-injectaudio-not-blackhole). This is the ONLY packet builder.
    """
    import base64
    data = Path(pcm_path).read_bytes()
    if not data:
        raise Blocked(f"{pcm_path} holds no audio")
    count = 0
    t0 = int(time.time() * 1_000_000)  # epoch microseconds, ascending 100 ms per packet
    with open(packets_path, "w") as out:
        for count, offset in enumerate(range(0, len(data), PCM_CHUNK_BYTES), start=1):
            out.write(json.dumps({
                "format": {"samplingRate": 48000, "channels": "Mono", "format": "AUD_FMT_S16"},
                "timestamp": t0 + (count - 1) * 100_000,
                "audio": base64.b64encode(data[offset:offset + PCM_CHUNK_BYTES]).decode(),
            }) + "\n")
    return count, len(data) / (48000 * 2)


def inject_audio(pcm_path):
    """Stream a PCM file into the EMULATOR's microphone while a take is listening.

    The injected stream IS the microphone only while the app holds it open, so this refuses unless
    `recording()` says a take is live; injected before the microphone opens, the stream is dropped and
    the take records silence, which reads exactly like a product that heard nothing.
    """
    _require_emulator("audio injection")
    _grpc("getStatus")
    if not Path(pcm_path).is_file():
        raise Blocked(f"{pcm_path} is not a file")
    if not recording():
        raise Blocked("no take is listening on the emulator, so injected audio would be dropped. "
                      "Inject inside `with open_recorder():`.")
    packets = pcm_path + ".packets.jsonl"
    count, seconds = _audio_packets(pcm_path, packets)
    _grpc("injectAudio", stdin_path=packets, timeout=int(seconds) + 60)
    return f"injected {count} packets, {seconds:.1f}s of audio"


def _focused_field(target_package):
    """The one focused editor in the target app, as (package, identity, text); None when there is none.

    IDENTITY TRAVELS WITH THE TEXT so a before/after comparison is about ONE editor. Exactly one FOCUSED
    editor is required: an unfocused field can become "the target" and read as words having arrived
    somewhere they were never aimed (#161).
    """
    fields = [n for n in tree(refresh=True) if n["kind"] == "EditText"
              and (target_package is None or n["package"] == target_package)
              and n["focused"]]
    if not fields:
        return None
    if len(fields) > 1:
        raise Blocked(f"{len(fields)} focused editors are on screen, so which one the words were "
                      "meant for is a guess")
    node = fields[0]
    return (node["package"], node["id"] or node["bounds"], node["text"])


def _field_by_identity(package, identity):
    """The ONE editor with this identity in the package, as (package, identity, text); None when gone.

    The AFTER read of a take (#161, grounded round 1): focus is not required here, because the harness's
    own launcher pauses the editor and hides its keyboard for a moment, and a verdict that needed focus
    back would fail for the harness's reason, not the app's. Identity is the resource id, or the bounds
    when the editor has none. Two matches is a refusal, never a pick.
    """
    fields = [n for n in tree(refresh=True) if n["kind"] == "EditText" and n["package"] == package
              and (n["id"] or n["bounds"]) == identity]
    if not fields:
        return None
    if len(fields) > 1:
        raise Blocked(f"{len(fields)} editors in {package} share the identity {identity!r}, so which one "
                      "holds the words is a guess")
    node = fields[0]
    return (node["package"], node["id"] or node["bounds"], node["text"])


def _plain(text):
    """A text with only the app's own decoration removed: NFKC, case-folded, punctuation dropped,
    whitespace collapsed. NOT a tokenizer: three review rounds each found a script a tokenizer broke
    (Cyrillic, combining marks, unspaced CJK), so the verdict is a plain substring match on this."""
    import unicodedata
    folded = unicodedata.normalize("NFKC", text or "").casefold()
    kept = "".join(c for c in folded if not unicodedata.category(c).startswith("P"))
    return " ".join(kept.split())


def _excerpt(text, keep=80):
    """The tail of an editor's text, so a report line stays one line whatever the draft has grown to."""
    text = (text or "").replace("\xa0", " ").strip()
    return repr(text if len(text) <= keep else "…" + text[-keep:]) + f" ({len(text)} chars)"


def open_app(package):
    """Bring an app to the front by its package, the way the launcher would, and assert it arrived."""
    ready()
    _adb(f"monkey -p {shlex.quote(package)} -c android.intent.category.LAUNCHER 1")
    for _ in range(8):
        time.sleep(0.5)
        _STATE["tree"] = None
        if package in {n["package"] for n in tree()}:
            return f"{package} is on screen"
    raise Blocked(f"{package} did not come to the front")


def focus_field(label, package):
    """Press an editor by its hint or text and READ BACK that it took focus.

    An editor is pressable by definition, whatever the tree says about `clickable`, so the press lands
    on the label's own centre. The read-back is the point: a dictation aims at the focused field, and a
    press that did not focus it would send the words somewhere else.
    """
    tap(label, clickable=False, package=package)
    time.sleep(0.8)
    field = _focused_field(package)
    if field is None:
        raise Blocked(f"{label!r} was pressed but no editor in {package} has focus")
    return f"focused {label!r} ({field[1]})"


def dictate_emulator(sentence, target_package="com.google.android.gm", expected_final=None, route=None,
                     record=None):
    """One spoken take on the EMULATOR, fed over gRPC, judged by the editor's own whole text.

    Replaces `scripts/uat/grpc-take.sh`, which tapped coordinates it had worked out by hand, turned the
    host microphone off and never back on, and trusted a log line. Here the take is owned by
    `open_recorder()`, the microphone by `set_host_mic()` and its journal, and the verdict by the editor
    that was uniquely focused before the take, read back by its identity after it.

    `expected_final` is the caller's LITERAL whole final editor text (#161 finding 3): the speech engine
    adds its own punctuation and the insertion its capitalisation and spacing, so it cannot be derived
    from the sentence, and "the text changed" is not "the words landed". `route="COMMIT"` (or "PASTE")
    makes any other route an ISSUE; without it the route is reported, never assumed. `record` is a host
    path for an owned screen recording around the take (`screen_recording`).
    """
    _require_emulator("a spoken take")
    if _recording_is_off():
        return [f"NOT RUN: {RECORDING_IS_OFF}"]
    if not isinstance(expected_final, str):
        return ["BLOCKED: expected_final is required: the literal whole text the editor must hold after "
                "the take (the speech engine's punctuation and the insertion's spacing included)"]
    if route is not None and route not in ("COMMIT", "PASTE"):
        return ["BLOCKED: route must be 'COMMIT', 'PASTE' or None"]
    report = []
    restore()
    ready()
    report.append(f"NOTE: {rebind_auto_paste_if_unbound()}")
    if not bound():
        return report + ["BLOCKED: auto-paste is switched off, so nothing can be inserted. "
                         "enable_auto_paste() turns it back on."]
    before = _focused_field(target_package)
    if before is None:
        return report + [f"BLOCKED: no focused editor in {target_package} is on screen; tap into one first"]
    scratch = os.path.join(os.environ.get("TMPDIR", "/tmp"), "wispr-eyes")
    os.makedirs(scratch, exist_ok=True)
    pcm = _pcm_from_sentence(sentence, os.path.join(scratch, f"utt-{uuid.uuid4().hex}.pcm"))
    report.append(f"NOTE: {sentence!r} was rendered to {pcm}")
    ending = None
    recording_note = None
    try:
        # OFF AS THE RESTING STATE, never a switch inside the take: see _rest_host_mic_off. Done before
        # the audio is rendered so the switch, if one happens at all, is as far from the capture as the
        # call allows.
        report.append(f"NOTE: {_rest_host_mic_off()}")
        clear_log()
        with screen_recording(record):
            with open_recorder(verify=False):
                # THE OWNER'S OWN LIVE LINE, not a warm-up sleep (#161, grounded round 2): the line is
                # written after the capture process admitted its first audio block, which is what
                # "the microphone is ready for the audio" means.
                report.append(f"NOTE: {_wait_for_owner_listening()}")
                report.append(f"NOTE: {inject_audio(pcm)}")
                time.sleep(2.0)
                stop_dictation()
                ending = _wait_for_the_take_to_finish()
    finally:
        for line in restore():
            report.append(f"NOTE: restored {line}")
    recording_note = _STATE.get("last_screen_recording") if record else None
    if recording_note:
        report.append(f"NOTE: {recording_note}")
    if ending is None:
        report.append("ISSUE: the dictation never reached an ending, so nothing can be said about it")
        return report
    take = last_take()
    if take["unknown"]:
        report.append(f"ISSUE: UNKNOWN, {take['unknown']}")
        return report
    report.append(f"VERIFIED: a take ran, ended by {take['ended_by']}" if take["ended_by"]
                  else "ISSUE: no take ending was logged")
    if take["transcribed_chars"]:
        report.append(f"VERIFIED: {take['transcribed_chars']} characters came back from the speech engine")
    else:
        report.append("ISSUE: nothing was transcribed; either the stream never reached the microphone "
                      "or the engine returned empty")
    report.extend(_judge_insertion(take, target_package, route))
    report.extend(_judge_editor(before, target_package, expected_final))
    return report


def _judge_insertion(take, target_package, route=None):
    """The take's own outcome line, field by field: VERIFIED only when it says so (#161 finding 2)."""
    outcome = take["insertion"]
    if take["insertions"] != 1 or outcome is None:
        return [f"ISSUE: {take['insertions']} insertion outcome lines were logged for this take, so its "
                "delivery cannot be read"]
    lines = [f"NOTE: insertion api={outcome['api']} route={outcome['route']} written={outcome['written']} "
             f"evidence={outcome['evidence']} outcome={outcome['outcome']} attempts={outcome['attempts']} "
             f"target={outcome['target']}"]
    problems = []
    if outcome["outcome"] != "VERIFIED":
        problems.append(f"outcome={outcome['outcome']}")
    if outcome["written"] != "true":
        problems.append(f"written={outcome['written']}")
    if target_package is not None and outcome["target"] != target_package:
        problems.append(f"target={outcome['target']} (wanted {target_package})")
    if route is not None and outcome["route"] != route:
        problems.append(f"route={outcome['route']} (wanted {route})")
    if problems:
        lines.append("ISSUE: the app's own outcome line does not say the words were delivered: "
                     + ", ".join(problems))
    else:
        lines.append(f"VERIFIED: route={outcome['route']}, the app's own outcome line says written, "
                     f"verified, into {outcome['target']}")
    return lines


def _judge_editor(before, target_package, expected_final):
    """The editor chosen BEFORE the take, read back by identity, its WHOLE text against the literal."""
    after = _field_by_identity(before[0], before[1])
    if after is None:
        return ["ISSUE: the editor that was focused before the take is no longer on screen, so where "
                "the words went is unknown"]
    focused_now = _focused_field(target_package)
    focus_note = ("the same editor is focused" if focused_now and focused_now[:2] == before[:2]
                  else "focus is elsewhere for now (not part of the verdict)")
    text = after[2] or ""
    if text == expected_final:
        return [f"VERIFIED: the editor's whole text now equals the expectation ({len(text)} chars); "
                f"{focus_note}"]
    # RAW tails, not `_excerpt`: that helper folds a no-break space into a space and strips, which hid the
    # only difference on the first live run (Gmail stores the inserted trailing space as U+00A0).
    return [f"ISSUE: the editor's whole text differs from the expectation; it ends {_raw_tail(text)}, "
            f"expected {_raw_tail(expected_final)}; {focus_note}"]


def _raw_tail(text, keep=80):
    """The exact tail of a text with its real length, every character shown as Python would write it."""
    return (repr(text) if len(text) <= keep else "…" + repr(text[-keep:])) + f" ({len(text)} chars)"


def debug_insert(text, target_package=None, expected_final=None):
    """Insert a sentence through the app's real insertion path with NO audio, on the EMULATOR's DEBUG build.

    Replaces `scripts/uat/debug-insert.sh`. The debug-only broadcast pins the focused editor and
    commits or pastes exactly as a take would, so it drives the caret matrix deterministically. The
    editor's own text is the verdict, never the clipboard.
    """
    _require_emulator("the debug insert")
    if not isinstance(expected_final, str):
        return ["BLOCKED: expected_final is required: the literal whole text the editor must hold after "
                "the insert (the insertion's own capitalisation and spacing included)"]
    ready()
    report = [f"NOTE: {rebind_auto_paste_if_unbound()}"]
    if not bound():
        return report + ["BLOCKED: auto-paste is switched off, so nothing can be inserted"]
    # THE PACKAGE's flag line is the bracketed one; the first bare `flags=0x0` belongs to a component
    # and read as "not debuggable" on the first live run (2026-09-20).
    _, dump = _adb(f"dumpsys package {PACKAGE} | grep -m1 DEBUGGABLE", check=False)
    if "DEBUGGABLE" not in dump:
        return [f"BLOCKED: the installed {PACKAGE} is not a debug build, so the insert receiver is absent"]
    before = _focused_field(target_package)
    if before is None:
        return ["BLOCKED: no focused editor is on screen; tap into one first"]
    clear_log()
    _adb(f"am broadcast -a {PACKAGE}.debug.INSERT --es text {shlex.quote(text)} {PACKAGE}")
    ending = _wait_for_the_take_to_finish(seconds=20)
    if ending is None:
        report.append("ISSUE: no insertion outcome was logged within 20 s, so where the text went is unknown")
        return report
    _, pin = _adb("logcat -d " + _log_window_args() + "| grep 'DebugInsert: pin=' | grep -v adbd | tail -1",
                  check=False)
    if pin.strip():
        report.append(f"NOTE: {pin.strip()[-160:]}")
    text_in_window = logs(lines=400)
    take = {"insertion": _insertion_line(text_in_window),
            "insertions": len(re.findall(r"insertion api=", text_in_window))}
    report.extend(_judge_insertion(take, target_package, None))
    report.extend(_judge_editor(before, target_package, expected_final))
    return report


TEST_RUNNER = f"{TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner"
# The reserved status code and keys the device test publishes on the `am instrument -r` stream when it
# is ready for the driver's audio (`VoicePipelineDeviceTest.DRIVER_READY_STATUS`). A bundle carrying this
# code and `driver_phase=READY` is a driver-control event, never a test verdict (#161 T2).
DRIVER_READY_STATUS = 161
EXTERNAL_AUDIO_DONE = f"{PACKAGE}.debug.EXTERNAL_AUDIO_DONE"


def _instrumentation_groups(stream):
    """Yield each `INSTRUMENTATION_STATUS` bundle with its following `INSTRUMENTATION_STATUS_CODE`.

    Raw `am instrument -r` prints every key of a bundle as `INSTRUMENTATION_STATUS: key=value` (a value
    may run over several lines, a stack trace does) and then ONE `INSTRUMENTATION_STATUS_CODE: n`; the
    final `INSTRUMENTATION_RESULT:`/`INSTRUMENTATION_CODE:` pair ends the run and is yielded as a group
    with `code` None and the key `INSTRUMENTATION_CODE`.
    """
    bundle = {}
    key = None
    for raw in stream:
        line = raw.rstrip("\n")
        if line.startswith("INSTRUMENTATION_STATUS: "):
            body = line[len("INSTRUMENTATION_STATUS: "):]
            key, _, value = body.partition("=")
            bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = line[len("INSTRUMENTATION_STATUS_CODE: "):].strip()
            yield {"code": int(code) if code.lstrip("-").isdigit() else None, **bundle}
            bundle, key = {}, None
        elif line.startswith("INSTRUMENTATION_RESULT: "):
            body = line[len("INSTRUMENTATION_RESULT: "):]
            key, _, value = body.partition("=")
            bundle[key] = value
        elif line.startswith("INSTRUMENTATION_CODE: "):
            bundle["INSTRUMENTATION_CODE"] = line[len("INSTRUMENTATION_CODE: "):].strip()
            yield {"code": None, **bundle}
            return
        elif key is not None:
            bundle[key] = bundle[key] + "\n" + line
    if bundle:
        yield {"code": None, **bundle}


def run_device_test(test, sentence, expected_final=None, timeout=240, starve=False):
    """Run ONE instrumented row on the EMULATOR with this harness feeding its audio (#161 T2).

    The row (`VoicePipelineDeviceTest`, `-e audio external`) starts the take itself, publishes
    `driver_phase=READY` on the instrumentation stream once the owner reported LISTENING and its staging
    is done, and waits for this driver's audio-done broadcast before it stops the take. Here: the test
    APK must already be installed (`adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`;
    never `connectedAndroidTest`, which uninstalls the app); the sentence is rendered to PCM; the stream
    is read group by group; on the READY group carrying this run's token the audio is injected and the
    done broadcast sent; every other group is a runner result and is reported as VERIFIED or ISSUE.

    `expected_final` is passed through as `-e expected_final`; the row's own literal is used when None.
    `starve=True` is the NEGATIVE CONTROL: the driver answers READY with the done broadcast and feeds no
    audio at all, so a row that still passes is a row that proves nothing. On the phone this refuses:
    the phone's driver is `scripts/uat/silent-audio/run.py`.
    """
    device()
    if _recording_is_off():
        return [f"NOT RUN: {RECORDING_IS_OFF}"]
    _require_emulator("driving a device test's audio")
    if not re.fullmatch(r"[A-Za-z_][\w.]*(#\w+)?", test):
        return [f"BLOCKED: {test!r} is not a test class or class#method"]
    _, path = _adb(f"pm path {TEST_PACKAGE}", check=False)
    if "package:" not in path:
        return [f"BLOCKED: the test APK {TEST_PACKAGE} is not installed; install it with "
                "adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    report = [f"NOTE: {rebind_auto_paste_if_unbound()}"]
    if not bound():
        return report + ["BLOCKED: auto-paste is switched off, so nothing can be inserted. "
                         "enable_auto_paste() turns it back on."]
    token = uuid.uuid4().hex
    scratch = os.path.join(os.environ.get("TMPDIR", "/tmp"), "wispr-eyes")
    os.makedirs(scratch, exist_ok=True)
    pcm = _pcm_from_sentence(sentence, os.path.join(scratch, f"utt-{uuid.uuid4().hex}.pcm"))
    report.append(f"NOTE: {sentence!r} was rendered to {pcm}")
    args = [ADB, "-s", device(), "shell", "am", "instrument", "-w", "-r",
            "-e", "class", test, "-e", "audio", "external", "-e", "driver_token", token]
    if expected_final is not None:
        args += ["-e", "expected_final", expected_final]
    args.append(TEST_RUNNER)
    restore()
    ready()
    report.append(f"NOTE: {_rest_host_mic_off()}")
    clear_log()
    fed = False
    rebound_checked = False
    results = []
    timed_out = []
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    # A TIMER, not a check between groups: the group reader blocks while the runner is silent, so a check
    # that runs only when a group arrives never runs on a hung run (code review round 1).
    timer = threading.Timer(timeout, lambda: (timed_out.append(True), process.kill()))
    timer.start()
    try:
        for group in _instrumentation_groups(process.stdout):
            if group.get("code") == DRIVER_READY_STATUS and group.get("driver_phase") == "READY":
                if group.get("driver_token") != token:
                    report.append("NOTE: a READY from another run was ignored (token mismatch)")
                    continue
                if starve:
                    report.append("NOTE: STARVED on purpose: no audio fed (the negative control)")
                else:
                    report.append(f"NOTE: {inject_audio(pcm)}")
                _adb(f"am broadcast -a {EXTERNAL_AUDIO_DONE} --es driver_token {token} {PACKAGE}")
                report.append("NOTE: audio done broadcast sent")
                fed = True
                continue
            if "INSTRUMENTATION_CODE" in group:
                results.append(group)
                break
            if group.get("code") == 1 and group.get("test") and not rebound_checked:
                # THE TEST HAS STARTED, so the app's process is up again. Instrumentation restarts that
                # process and the accessibility service dies with it; on this AVD the system does not
                # rebind it on its own (seen 2026-09-22: handoff=SERVICE_NOT_RUNNING), so the driver does,
                # journaled, and the row waits on the service's own liveness fact before its take.
                rebound_checked = True
                if not bound():
                    report.append(f"NOTE: the accessibility service was unbound by the instrumentation "
                                  f"restart; {rebind_auto_paste_if_unbound()}")
                continue
            if group.get("code") in (0, -1, -2, -3, -4) and group.get("test"):
                results.append(group)
    finally:
        timer.cancel()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
        for line in restore():
            report.append(f"NOTE: restored {line}")
    if timed_out:
        report.append(f"ISSUE: the instrumentation ran past {timeout} s and was killed; UNKNOWN")
    if not fed:
        report.append("ISSUE: the row never said READY, so no audio was fed (staging, not the product)")
    report.extend(_report_runner_results(results, "not_run"))
    return report


REAL_BOUNDARY_TEST = "com.envi.wispr.VoicePipelineDeviceTest#transcribesThenPolishesWithSavedCustomWords"
PREREQUISITE_FIRST_LINE = "java.lang.AssertionError: Prerequisite:"


def _report_runner_results(results, assumption_policy):
    """The runner's groups as report lines, one per test and one for an abnormal end (#161, #215).

    `assumption_policy="not_run"`: a runner assumption (-3/-4) is NOT RUN, which is how `run_device_test`
    has always reported it. `"regression"` (the real-boundary door): an assumption means an `assumeTrue`
    came back into a row that must assert, so it is an ISSUE; and a failure whose FIRST stack line begins
    exactly `java.lang.AssertionError: Prerequisite:` is NOT RUN (the row's named prerequisite), never a
    later frame or a bare word.
    """
    lines = []
    for group in results:
        name = group.get("test", "")
        cls = group.get("class", "").rsplit(".", 1)[-1]
        code = group.get("code")
        stack = group.get("stack", "").strip().splitlines()
        first = stack[0] if stack else ""
        if code == 0:
            lines.append(f"VERIFIED: {cls}.{name} passed on the device")
        elif code == -2 and assumption_policy == "regression" and first.startswith(PREREQUISITE_FIRST_LINE):
            lines.append(f"NOT RUN: {cls}.{name}: {first[len('java.lang.AssertionError: '):]}")
        elif code in (-1, -2):
            lines.append(f"ISSUE: {cls}.{name} FAILED: {first or 'no message'}")
        elif code in (-3, -4):
            if assumption_policy == "regression":
                lines.append(f"ISSUE: {cls}.{name} was SKIPPED by an assumption, which this row must never "
                             f"make (an assumeTrue came back): {first or 'no reason given'}")
            else:
                lines.append(f"NOT RUN: {cls}.{name} was skipped by its own precondition: "
                             f"{first or 'no reason given'}")
        elif "INSTRUMENTATION_CODE" in group:
            if group["INSTRUMENTATION_CODE"] != "-1":
                lines.append(f"ISSUE: the instrumentation ended with code {group['INSTRUMENTATION_CODE']}: "
                             f"{group.get('shortMsg') or group.get('stream', '').strip()[-200:]}")
    if not any(g.get("test") for g in results):
        lines.append("ISSUE: the runner reported no test at all; UNKNOWN")
    return lines


def _real_boundary_preflight(probes):
    """The real-boundary row's prerequisites, from read-only probes; a list of NOT RUN lines, empty when ready.

    `probes` maps a probe name to `(exit status, output)`, the status kept apart from the output:
    `target` (`pm path com.envi.wispr`), `test` (`pm path com.envi.wispr.test`), `runner`
    (`pm list instrumentation`), `fixture` (`run-as com.envi.wispr sh -c 'test -f ... && stat -c %s ...'`)
    and `runas` (`run-as com.envi.wispr true`). Models and the saved name are judged by the row itself.
    """
    missing = []
    code, out = probes["target"]
    if code != 0 or "package:" not in out:
        missing.append(f"NOT RUN: {PACKAGE} is not installed")
    code, out = probes["test"]
    if code != 0 or "package:" not in out:
        missing.append(f"NOT RUN: the test APK {TEST_PACKAGE} is not installed; install it with adb install -r -t "
                       "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    code, out = probes["runner"]
    if code != 0 or not any(TEST_RUNNER in line and f"(target={PACKAGE})" in line for line in out.splitlines()):
        missing.append(f"NOT RUN: the runner {TEST_RUNNER} is not registered against {PACKAGE}")
    code, _ = probes["runas"]
    if code != 0:
        missing.append(f"NOT RUN: {PACKAGE} is absent or not debuggable, so its cache cannot be read")
        return missing
    code, out = probes["fixture"]
    size = out.strip()
    if code != 0 or not size.isdigit() or int(size) <= 0 or int(size) % 2 != 0:
        missing.append(f"NOT RUN: no usable fixture at {PACKAGE}/cache/{UAT_FIXTURE} (a regular, non-empty, "
                       "even-length 16 kHz s16le file); stage it with stage_uat_fixture(sentence)")
    return missing


def _real_boundary_probes():
    """Run the read-only probes `_real_boundary_preflight` judges. Nothing here writes."""
    return {
        "target": _adb(f"pm path {PACKAGE}", check=False),
        "test": _adb(f"pm path {TEST_PACKAGE}", check=False),
        "runner": _adb("pm list instrumentation", check=False),
        "runas": _adb(f"run-as {PACKAGE} true", check=False),
        "fixture": _adb(f"run-as {PACKAGE} sh -c {shlex.quote(f'test -f cache/{UAT_FIXTURE} && stat -c %s cache/{UAT_FIXTURE}')}",
                        check=False),
    }


def run_real_boundary(timeout=240):
    """Run the heart's one real ASR-and-local-polish row, honestly (#215).

    Preflight first, read-only: both APKs, the runner's registration, a debuggable target, the fixture. A
    missing member answers NOT RUN and the row is not started. Then the row alone, through `am instrument`
    (never Gradle, which uninstalls the app). Its results use the `regression` policy: a named
    `Prerequisite:` failure is NOT RUN, an assumption is an ISSUE.
    """
    device()
    missing = _real_boundary_preflight(_real_boundary_probes())
    if missing:
        return missing
    args = [ADB, "-s", device(), "shell", "am", "instrument", "-w", "-r", "-e", "class", REAL_BOUNDARY_TEST, TEST_RUNNER]
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    timed_out = []
    timer = threading.Timer(timeout, lambda: (timed_out.append(True), process.kill()))
    timer.start()
    try:
        results, saw_final = _collect_runner_groups(_instrumentation_groups(process.stdout))
    finally:
        timer.cancel()
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
    return _real_boundary_report(results, saw_final, bool(timed_out), timeout)


def _collect_runner_groups(groups):
    """The test groups and the final group from a runner stream, and whether the final one arrived."""
    results = []
    for group in groups:
        if "INSTRUMENTATION_CODE" in group:
            results.append(group)
            return results, True
        if group.get("code") in (0, -1, -2, -3, -4) and group.get("test"):
            results.append(group)
    return results, False


def _real_boundary_report(results, saw_final, timed_out, timeout):
    """The door's verdict. A stream that ended without its final INSTRUMENTATION_CODE (a runner crash, a cut
    transport) is UNKNOWN whatever the test groups said: a passing group before a truncated end is not a pass."""
    report = []
    if timed_out:
        report.append(f"ISSUE: the instrumentation ran past {timeout} s and was killed; UNKNOWN")
    elif not saw_final:
        report.append("ISSUE: the instrumentation ended without a final INSTRUMENTATION_CODE; UNKNOWN")
    lines = _report_runner_results(results, "regression")
    if report:
        lines = [line.replace("VERIFIED:", "UNKNOWN (not a pass):", 1) for line in lines]
    return report + lines


def _pcm16_from_sentence(sentence, path):
    """Say a sentence into the real-boundary fixture's format: s16le, mono, 16 kHz, plus half a second of
    silence. Separate from `_pcm_from_sentence`, whose 48 kHz is the emulator microphone's contract."""
    for tool in ("say", "ffmpeg"):
        if not _has_tool(tool):
            raise Blocked(f"{tool} is not available, so no fixture can be made")
    aiff = path + ".aiff"
    _checked(["say", "-v", "Samantha", "-r", "160", "-o", aiff, "--", sentence], timeout=180)
    _checked(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", aiff,
              "-f", "s16le", "-ar", str(FIXTURE_SAMPLE_RATE), "-ac", "1", path], timeout=180)
    with open(path, "ab") as tail:
        tail.write(bytes(FIXTURE_SAMPLE_RATE * FIXTURE_BYTES_PER_SAMPLE // 2))
    return path


def _exec_in(remote_command, local_path):
    """Stream a local file's bytes to a remote command's stdin. adb's own exit status is transport-only."""
    with open(local_path, "rb") as source:
        done = subprocess.run([ADB, "-s", device(), "exec-in", *remote_command], stdin=source,
                              capture_output=True, timeout=120)
    return done.returncode


def _remote_size(path):
    """The size of a REGULAR file in the target's private storage, or None (absent, not regular, unreadable)."""
    code, out = _adb(f"run-as {PACKAGE} sh -c {shlex.quote(f'test -f {path} && stat -c %s {path}')}", check=False)
    size = out.strip()
    return int(size) if code == 0 and size.isdigit() else None


# What a device call raises when the device, not the caller, failed: no transport, no status, a timeout.
_DEVICE_ERRORS = (Blocked, OSError, subprocess.SubprocessError)


def _remote_presence(path):
    """'present' or 'absent' for any name (file, directory or link) in the target's private storage; None
    when the device could not tell (including a device call that raised), which callers never read as
    absent."""
    probe = f"if [ -e {path} ] || [ -L {path} ]; then echo present; else echo absent; fi"
    try:
        code, out = _adb(f"run-as {PACKAGE} sh -c {shlex.quote(probe)}", check=False)
    except _DEVICE_ERRORS:
        return None
    answer = out.strip()
    return answer if code == 0 and answer in ("present", "absent") else None


def _remove_owned(path):
    """Remove a name THIS process created and prove it is gone: None when absent afterwards, otherwise a
    sentence saying it may remain."""
    try:
        removed, why = _adb(f"run-as {PACKAGE} rm -f {path}", check=False)
        detail = f"rm exit {removed}: {why.strip()[-120:] or 'no message'}"
    except _DEVICE_ERRORS as error:
        detail = f"rm raised: {error}"
    if _remote_presence(path) == "absent":
        return None
    return f"{PACKAGE}/{path} may remain ({detail})"


def stage_uat_fixture(sentence):
    """Write the real-boundary fixture into the target's cache ONLY where none exists (#215).

    Rendered locally at 16 kHz; streamed with `adb exec-in` into a unique temporary name; admitted only
    after a regular-file, exact-size read-back. Published in one device shell: `set -C` makes `true >
    final` refuse a name that exists (exit 3), so a fixture already there, or one that appears meanwhile,
    is never touched; only then is the temporary copied in with `>|` (exit 4 on failure). Exit 3 is read
    as a race only when the final name is then present; otherwise the creation failed (permission, space)
    and staging is Blocked. Hard links are not an option: SELinux denies `link` to `runas_app` on app data
    (emulator API 36, 2026-09-22). The final name is read back again; a final name THIS call created and
    could not fill whole is removed. The temporary name is removed on every path. Every removal is proved
    by reading the name back absent; one that cannot be proved is Blocked, naming what may remain.
    """
    device()
    final = f"cache/{UAT_FIXTURE}"
    if _remote_size(final) is not None:
        return f"a fixture is already at {PACKAGE}/{final}; it was left untouched and nothing was staged"
    scratch = os.path.join(os.environ.get("TMPDIR", "/tmp"), "wispr-eyes")
    os.makedirs(scratch, exist_ok=True)
    local = _pcm16_from_sentence(sentence, os.path.join(scratch, f"fixture-{uuid.uuid4().hex}.pcm"))
    size = os.path.getsize(local)
    temporary = f"cache/.{UAT_FIXTURE}.{uuid.uuid4().hex}.tmp"

    def publish():
        _exec_in(["run-as", PACKAGE, "sh", "-c", f"cat > {temporary}"], local)
        if _remote_size(temporary) != size:
            raise Blocked(f"the fixture did not arrive whole ({_remote_size(temporary)} of {size} bytes); nothing was staged")
        script = f"set -C; true > {final} || exit 3; cat {temporary} >| {final} || exit 4"
        try:
            published, why = _adb(f"run-as {PACKAGE} sh -c {shlex.quote(script)}", check=False)
        except _DEVICE_ERRORS as error:
            # The shell may have created or part-filled the final name before the answer was lost. Who made
            # it cannot be told, so it is named, never removed.
            presence = _remote_presence(final) or "could not tell"
            raise Blocked(f"the publish to {PACKAGE}/{final} gave no answer ({error}); a final name may remain "
                          f"after an indeterminate publish (presence: {presence}) and was not removed, because "
                          "this call cannot tell who made it; check it before trusting a run") from error
        detail = why.strip()[-120:] or "no message"
        if published == 3:
            presence = _remote_presence(final)
            if presence == "present":
                return (f"a fixture appeared at {PACKAGE}/{final} while staging; it was left untouched and "
                        f"nothing was staged ({detail})")
            if presence == "absent":
                raise Blocked(f"{PACKAGE}/{final} could not be created and no fixture is there ({detail}); nothing was staged")
            raise Blocked(f"{PACKAGE}/{final} could not be created and the device could not tell whether a fixture is "
                          f"there ({detail}); nothing was staged")
        if published == 4:
            left = _remove_owned(final)
            raise Blocked(f"the fixture could not be copied into {PACKAGE}/{final} ({detail}); "
                          + (left or "the name this call created was removed"))
        if published != 0:
            raise Blocked(f"the fixture could not be published at {PACKAGE}/{final} (exit {published}: {detail})")
        try:
            arrived = _remote_size(final)
        except _DEVICE_ERRORS as error:
            left = _remove_owned(final)
            raise Blocked(f"the published fixture at {PACKAGE}/{final} could not be read back ({error}); "
                          + (left or "the name this call created was removed")) from error
        if arrived != size:
            left = _remove_owned(final)
            raise Blocked(f"the published fixture at {PACKAGE}/{final} did not read back at {size} bytes; "
                          + (left or "the name this call created was removed"))
        return f"staged {size} bytes ({size // (FIXTURE_SAMPLE_RATE * FIXTURE_BYTES_PER_SAMPLE):.0f} s) at {PACKAGE}/{final}"

    try:
        outcome = publish()
    except BaseException as failure:
        left = _remove_owned(temporary)
        if left and isinstance(failure, Blocked):
            raise Blocked(f"{failure}; the temporary {left}") from failure
        if left:
            failure.add_note(f"the temporary {left}")
        raise
    left = _remove_owned(temporary)
    if left:
        raise Blocked(f"{outcome}, but the temporary {left}")
    return outcome


def open_page(url, package="com.android.chrome"):
    """Open a URL in a browser and assert the browser is on screen. Nothing is sent to anyone."""
    ready()
    remote, out = _adb(f"am start -a android.intent.action.VIEW -d {shlex.quote(url)} {shlex.quote(package)}",
                       check=False)
    if remote != 0 or "Error" in out:
        detail = out.strip().splitlines()[-1] if out.strip() else "no message"
        raise Blocked(f"{url} would not open in {package}: {detail}")
    for _ in range(6):
        time.sleep(0.5)
        _STATE["tree"] = None
        if package in {n["package"] for n in tree()}:
            return f"{package} is showing {url}"
    raise Blocked(f"{package} did not come to the front for {url}")


def launch_emulator(avd=PLAY_AVD, restart=False):
    """Boot the AVD with its control service on, and wait until Android says it is up.

    Replaces `scripts/uat/launch-grpc.sh`. A booted emulator is reused unless `restart=True`, because
    killing one that another session is driving is the one-device rule broken at the level of the
    machine. `-allow-host-audio` and `-grpc 8554` are always passed: without the first every microphone
    sample is zeroed by design, without the second nothing here can feed audio or read the mic switch.
    """
    attached = {serial for serial, _ in devices()}
    if EMULATOR_SERIAL in attached and not restart:
        code, out, _ = _run([ADB, "-s", EMULATOR_SERIAL, "shell", "getprop sys.boot_completed"], timeout=20)
        if code == 0 and out.strip() == "1":
            device(EMULATOR_SERIAL)
            _grpc("getStatus")
            _rest_host_mic_off()
            return f"{EMULATOR_SERIAL} is already booted with gRPC at {GRPC_ENDPOINT}, host microphone off"
    if EMULATOR_SERIAL in attached:
        if _owed(EMULATOR_SERIAL):
            raise Blocked(f"changes are still owed to {EMULATOR_SERIAL}; restore() before restarting it")
        _run([ADB, "-s", EMULATOR_SERIAL, "emu", "kill"], timeout=30)
        for _ in range(30):
            time.sleep(2)
            if EMULATOR_SERIAL not in {serial for serial, _ in devices()}:
                break
        else:
            raise Blocked(f"{EMULATOR_SERIAL} would not shut down")
        time.sleep(4)
    if not os.access(EMULATOR_BIN, os.X_OK):
        raise Blocked(f"{EMULATOR_BIN} is not executable; is the Android SDK installed?")
    log = open(os.path.join(os.environ.get("TMPDIR", "/tmp"), f"{avd}.log"), "ab")
    subprocess.Popen([EMULATOR_BIN, "-avd", avd, "-gpu", "swiftshader_indirect", "-no-boot-anim",
                      "-allow-host-audio", "-grpc", GRPC_ENDPOINT.rsplit(":", 1)[1]],
                     stdout=log, stderr=log, stdin=subprocess.DEVNULL, start_new_session=True)
    for _ in range(180):
        time.sleep(1)
        code, out, _ = _run([ADB, "-s", EMULATOR_SERIAL, "shell", "getprop sys.boot_completed"], timeout=20)
        if code == 0 and out.strip() == "1":
            break
    else:
        raise Blocked(f"{avd} did not finish booting in 180 s; its log is {log.name}")
    device(EMULATOR_SERIAL)
    _grpc("getStatus")
    _rest_host_mic_off()
    return f"{avd} booted as {EMULATOR_SERIAL} with gRPC at {GRPC_ENDPOINT}, host microphone off"


CABLE = "BlackHole 2ch"
SWITCH_AUDIO = "SwitchAudioSource"


def _has_tool(name):
    return shutil.which(name) is not None


def _mac_input():
    return _checked([SWITCH_AUDIO, "-c", "-t", "input"]).strip()


@_atomic_change
def say_into_emulator(sentence):
    """Speak a sentence straight into the EMULATOR through the virtual audio cable, never the speakers.

    The emulator's microphone is whatever the Mac's CURRENT default input is (measured 2026-09-13: an
    emulator launched on the cable heard the room once the default input went back to the built-in
    microphone, and heard the cable once the default was the cable again), so this switches the default
    input to the cable for the length of the sentence, plays the voice INTO the cable with `say -a`,
    and puts the input back, read back to prove it. Nothing comes out of the speakers, the room cannot
    interfere, and no volume is involved: the speaker path was the flaky half of every emulator take
    (founder 2026-09-13, "the volume wasn't loud enough for the emulator to capture the input").

    Needs `brew install blackhole-2ch switchaudio-osx`; a missing half is reported, never worked around.
    """
    if not is_emulator():
        raise Blocked(f"{device()} is not an emulator; the phone hears through its own microphone")
    if not _has_tool(SWITCH_AUDIO):
        raise Blocked(f"{SWITCH_AUDIO} is not installed (brew install switchaudio-osx), so the Mac's input "
                      "cannot be pointed at the cable")
    if CABLE not in _checked([SWITCH_AUDIO, "-a", "-t", "input"]):
        raise Blocked(f"the virtual cable {CABLE!r} is not an input device (brew install blackhole-2ch)")
    set_host_mic(True)
    previous = _mac_input()
    entry = ("mac-input", previous)
    _owe(entry, serial="host")
    try:
        _checked([SWITCH_AUDIO, "-t", "input", "-s", CABLE])
        now = _mac_input()
        if now != CABLE:
            raise Blocked(f"the Mac's input did not switch to {CABLE!r}; it reads {now!r}")
        started = time.monotonic()
        _checked(["say", "-v", "Samantha", "-r", "170", "-a", CABLE, "--", sentence], timeout=180)
        return time.monotonic() - started
    finally:
        # BOTH cleanups run whatever the other does: the Mac's input goes back, and the emulator's host
        # mic returns to its OFF resting state (#181; the on-debt `set_host_mic(True)` wrote is settled
        # by that, or kept for `restore()` if the switch fails).
        try:
            _restore_one(entry)
            _settled(entry, serial="host")
        finally:
            _rest_host_mic_off()


@_atomic_change
def say(sentence, volume=25):
    """Speak a sentence out of the MAC's speakers, into the phone's microphone, quietly.

    The Mac speaks rather than the phone, so the phone's own audio settings are never touched and the
    sound arrives through the air like a person talking. The founder's standing instruction is that any
    audio test runs at LOW VOLUME.

    The volume is put back in a `finally` and then READ BACK. Restoring in the happy path only is how a
    timeout leaves a machine loud, and trusting a restore nobody checked is how it stays loud quietly.

    **The phone has to be near the Mac.** That is the one precondition this cannot check, so a report
    says it rather than assuming it.

    **On an emulator the voice goes through the cable instead** ([say_into_emulator]): the phone-speaker
    path (deleted in #161) played through the guest speaker, which no guest microphone hears, and the
    speaker-to-microphone path was the flaky half of every emulator take.
    """
    if not isinstance(volume, int) or isinstance(volume, bool) or not 0 <= volume <= 40:
        raise Blocked("volume must be a whole number from 0 to 40; this is a low-volume tool")
    if is_emulator():
        return say_into_emulator(sentence)
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

def _screenrecord_processes():
    """Every `screenrecord` on the device as (pid, full command line), read from /proc, never by name alone."""
    _, out = _adb("pidof screenrecord", check=False)
    found = []
    for pid in out.split():
        if not pid.isdigit():
            continue
        _, cmd = _adb(f"cat /proc/{pid}/cmdline | tr '\\0' ' '", check=False)
        found.append((pid, cmd.strip()))
    return found


@contextmanager
def screen_recording(path=None):
    """Record the screen around a take into `path` on the host, owned end to end (#161 finding 13).

    `None` records nothing and yields None. Otherwise: any recorder already running is a refusal that
    names every pid and command (never adopted, never killed); the debt is journaled BEFORE the recorder
    starts; the started process is identified by a pid whose command line carries this run's token;
    on exit `kill -2` goes to that pid alone, the pid must EXIT before the file is trusted, pulled and
    deleted, and only then is the debt settled (`tools-and-apps.md` RULE:
    identify-a-process-by-path-never-by-name). The file is evidence to look at, never a verdict.
    """
    if path is None:
        yield None
        return
    token = uuid.uuid4().hex
    remote = f"/sdcard/wispr-eyes/{token}.mp4"
    # The host side is made ready BEFORE anything on the device changes: a pull that fails for a missing
    # host folder would leave the debt and the file behind for a reason that was never the device's.
    os.makedirs(os.path.dirname(os.path.abspath(path)) or ".", exist_ok=True)
    # UNDER THE LOCK from the check to the owned pid: the debt is in the book before the recorder exists,
    # and no other session can settle it in the gap.
    with _journal_locked():
        existing = _screenrecord_processes()
        if existing:
            raise Blocked("a screen recorder is already running on the device and is not this run's: "
                          + "; ".join(f"pid {pid}: {cmd}" for pid, cmd in existing))
        _adb("mkdir -p /sdcard/wispr-eyes")
        _owe(("screenrecord", token))
        process = subprocess.Popen([ADB, "-s", device(), "shell", "screenrecord", "--time-limit", "180", remote],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        owned = None
        for _ in range(20):
            time.sleep(0.25)
            mine = [(pid, cmd) for pid, cmd in _screenrecord_processes() if token in cmd]
            if len(mine) == 1:
                owned = mine[0]
                break
            if len(mine) > 1:
                raise Blocked(f"{len(mine)} recorders carry this run's token, so none can be owned")
        if owned is None:
            process.kill()
            raise Blocked("the screen recorder did not start (no process carries this run's token); the "
                          f"debt {token} is kept until restore() finds nothing to kill")
    _STATE["last_screen_recording"] = None
    try:
        yield f"screen recording in progress ({remote})"
    finally:
        _adb(f"kill -2 {owned[0]}", check=False)
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            raise Blocked(f"the screen recorder (pid {owned[0]}) did not exit after kill -2; the debt "
                          f"{token} is kept and the file is left")
        if any(pid == owned[0] for pid, _ in _screenrecord_processes()):
            raise Blocked(f"the screen recorder pid {owned[0]} still exists after its handle closed; the "
                          f"debt {token} is kept")
        stat_code, size = _adb(f"stat -c %s {remote}", check=False)
        if stat_code != 0 or not size.strip().isdigit() or int(size) <= 0:
            raise Blocked(f"the screen recording at {remote} is missing or empty ({size.strip()!r}); the "
                          f"device file and the debt {token} are kept")
        code, _, err = _run([ADB, "-s", device(), "pull", remote, path], timeout=120)
        if code != 0:
            raise Blocked(f"the screen recording could not be pulled: {err.strip()}; the device file and the "
                          f"debt {token} are kept")
        pulled = os.path.getsize(path) if os.path.isfile(path) else -1
        if pulled != int(size):
            raise Blocked(f"the pulled recording is {pulled} bytes, the device file {size.strip()}; the "
                          f"device file and the debt {token} are kept")
        _adb(f"rm {remote}")
        with _journal_locked():
            _settled(("screenrecord", token))
        _STATE["last_screen_recording"] = f"screen recording saved to {path} ({size.strip()} bytes)"


def clear_log():
    """Clear the ring buffer so an absent line means absent rather than left over.

    **IT CHANGES THE PHONE AND CANNOT BE PUT BACK, and that is stated rather than journaled.** The log
    it discards is gone; a debt promising to restore it would be a lie, and a lie in the restore book is
    worse than an honest gap. What is lost is diagnostic history, never the founder's own data.

    **It no longer RESIZES the buffer, and that is a deletion rather than a fix.** The old version
    widened it to 16M and journaled "the buffer was N K" from the first line of `logcat -g` — which
    names one buffer out of several, and silently journaled nothing at all when that line did not match,
    while still doing the resize. A debt that cannot describe the change it undoes is worse than no
    resize: it leaves the founder's phone altered and the book saying otherwise. Every take this tool
    measures is seconds long, so the default buffer holds it.
    """
    _adb("logcat -c")
    # THE WINDOW OPENS HERE (#161 H5). The device's own clock, so `-T` compares like with like; every
    # read below is bounded to it, and a read with no window says so instead of pretending.
    _, stamp = _adb("date '+%m-%d %H:%M:%S.000'")
    _STATE["log_since"] = stamp.strip() or None


def _log_window_args():
    """The `-T` argument bounding a logcat read to this process's window, or nothing when none is open."""
    since = _STATE["log_since"]
    return f"-T {shlex.quote(since)} " if since else ""


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
    # UNDER THE LOCK, for the same reason `restore()` is: another session could settle this debt in the
    # gap between writing it and making the change, leaving the founder's screen timeout altered with
    # nothing recording it.
    with _journal_locked():
        _, previous = _adb("settings get system screen_off_timeout")
        previous = previous.strip()
        if not previous.isdigit():
            raise Blocked(
                f"could not read the phone's screen timeout, so it could not be safely changed: {previous!r}")
        # Owed BEFORE the write. A note about a change that did not happen is a wasted restore; a change
        # with no note is a setting left on the founder's phone.
        _owe_locked(("screen-timeout", previous), device())
        wanted = str(minutes * 60_000)
        _adb(f"settings put system screen_off_timeout {wanted}")
        _, actual = _adb("settings get system screen_off_timeout")
        if actual.strip() != wanted:
            raise Blocked("the screen timeout did not change, so the note about it would be wrong")
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
    _, out = _adb(f"logcat -d {_log_window_args()}-v time {filters} {shlex.quote('*:S')}", timeout=90)
    selected = out.splitlines()[-lines:]
    if pattern:
        selected = [line for line in selected if re.search(pattern, line)]
    return "\n".join(selected).strip()


_INSERTION_FIELDS = ("api", "route", "written", "returned", "evidence", "outcome", "attempts", "ms",
                     "overrun", "target")


def _insertion_line(text):
    """The app's `insertion api=... route=... outcome=... target=...` line as a dict, or None.

    One producer writes it (`paste/InsertionOutcomeLine.kt`), so the fields are read by name; a line
    missing a field yields None for that field rather than a guess (#161 finding 2).
    """
    match = re.search(r"insertion api=\S+.*$", text, re.M)
    if not match:
        return None
    line = match.group(0)
    return {field: (m.group(1) if (m := re.search(rf"\b{field}=(\S+)", line)) else None)
            for field in _INSERTION_FIELDS}


def last_take():
    """What the most recent dictation did, as a small dictionary a person can read.

    Every field comes from a line the app itself wrote. A field is None when the app never said it,
    which is different from zero and is left different. `bounded` says whether the read was limited to
    this process's log window; `unknown` names why the take cannot be read (two takes in the window),
    with every other field None, so a caller that does not look for it still cannot mistake two takes
    for one (#161 finding 9).
    """
    text = logs(lines=400)
    bounded = _STATE["log_since"] is not None
    starts = list(re.finditer(r"(?m)^.*\brecording_start\b.*$", text))
    if not starts:
        raise Blocked("the log holds no take at all since it was last cleared, so there is nothing to "
                      "report on. Call clear_log() before the take you mean to measure.")
    if bounded and len(starts) > 1:
        return {"unknown": f"{len(starts)} takes in the window; which one is meant is a guess",
                "bounded": True, "started": True, "ended_by": None, "bytes": None, "seconds": None,
                "warned_at_ms": None, "hit_the_cap": None, "transcribed_chars": None, "handoff": None,
                "insertion": None, "insertions": len(re.findall(r"insertion api=", text))}
    # ONE TAKE, THE LAST ONE. Every field below is the FIRST match in the text, so with two takes in
    # the log a run could report one take's ending beside another take's character count and read as a
    # single coherent result; an unbounded read keeps the old last-start rule.
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
        # The app writes `insertion api=... route=... outcome=...`; the older `Insertion completed via`
        # key matched a line no producer writes (`grep -rn 'Insertion completed via' app/src` is empty).
        "insertion": _insertion_line(text),
        "insertions": len(re.findall(r"insertion api=", text)),
        "bounded": bounded,
        "unknown": None,
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


@_atomic_change
@contextmanager
def _as_serial(serial):
    """Drive ANOTHER attached device for the length of a restore, and put the selection back.

    Not `_switch_to`: that refuses while the selected device has debts, and a restore is exactly the
    moment it does. The selection is put back in `finally` so `device()` and `restored_for` are what
    they were, whichever device's debt was just settled.
    """
    if serial is None or serial == _STATE["serial"]:
        yield
        return
    saved = {k: _STATE[k] for k in ("serial", "tree", "eye", "eye_retry_after")}
    _STATE.update({"serial": serial, "tree": None, "eye": None, "eye_retry_after": 0})
    try:
        yield
    finally:
        _STATE.update(saved)


def _restore_one(entry, serial=None):
    """Put ONE change back, and read it back to prove it took.

    `serial` names an attached device other than the selected one; see `_as_serial`.

    A restore that returned zero is not a restore that happened.

    **THE DEBT HAS TO BE IN THE BOOK.** Handed one that is not, this changed the founder's phone to a
    value nobody had recorded — `_restore_one(("screen-timeout", "1800000"))` against an empty book set
    his timeout to half an hour and left no trace. "Undo" is only undo when there is something to undo.
    """
    with _as_serial(serial):
        _restore_one_here(entry)


def _restore_one_here(entry):
    what, previous = entry
    scope = "host" if what in ("mac-volume", "mac-input") else device()
    if entry not in _owed(scope):
        raise Blocked(
            f"there is no record of {what!r} having been changed on {scope}, so putting it 'back' to "
            f"{previous!r} would be changing it to a value nobody chose."
        )
    if what == "mac-volume":
        _checked(["osascript", "-e", f"set volume output volume {int(previous)}"])
        now = _checked(["osascript", "-e", "output volume of (get volume settings)"]).strip()
        if now != str(int(previous)):
            raise Blocked(f"the Mac's volume did not go back to {previous}; it reads {now}")
    elif what == "mac-input":
        _checked([SWITCH_AUDIO, "-t", "input", "-s", previous])
        now = _mac_input()
        if now != previous:
            raise Blocked(f"the Mac's input did not go back to {previous!r}; it reads {now!r}")
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
        # IN PLACE WHEN THE SCREEN IS ALREADY UP (#181): both the screen identity and the exact label,
        # never the label alone, which can sit on another page. Otherwise navigate as before.
        if not (on_screen(wanted["where"]) and present(wanted["label"], exact=True)):
            _reach(wanted["where"])
        if not reveal(wanted["label"]):
            raise Blocked(f"{wanted['label']!r} could not be found on {wanted['where']}, so it cannot "
                          "be put back")
        if switch(wanted["label"]) != wanted["was"]:
            tap(wanted["label"])
            _switch_settled(wanted["label"], wanted["was"])
        if switch(wanted["label"]) != wanted["was"]:
            raise Blocked(f"{wanted['label']!r} would not go back to "
                          f"{'on' if wanted['was'] else 'off'}")
    elif what == "choice":
        # A pick-one group goes back by CHOOSING BY NAME, never by pressing a member "off". The debt
        # holds the whole group, so the check at the end is the whole group, not one member.
        wanted = json.loads(previous)
        where, group = wanted["where"], wanted["group"]
        names = sorted(group)
        chosen = ", ".join(n for n in names if group[n]) or "nothing chosen"
        if not (on_screen(where) and present(names[0], exact=True)):
            _reach(where)
        if not reveal(names[0]):
            raise Blocked(f"{names[0]!r} could not be found on {where}, so its group cannot be put back")
        _, now = _group_state(names[0])
        if sorted(now) != names:
            # The rest of the group may be just below the fold. A page that does not scroll is showing
            # all of itself, and the mismatch is then reported below as it stands.
            try:
                scroll("down", 1)
            except Blocked as why:
                if "nothing on this screen scrolls" not in str(why):
                    raise
            _, now = _group_state(names[0])
        for _ in range(len(names) + 1):
            if sorted(now) != names:
                raise Blocked(f"the group holding {names[0]!r} on {where} reads {sorted(now)}, not the "
                              f"{names} that was changed, so it is not put back")
            if now == group:
                break
            target = next((n for n in names if group[n] and not now[n]), None) or next(
                n for n in names if now[n] and not group[n])
            tap(target)
            now = _group_settled(target, now)
        if now != group:
            raise Blocked(f"the group holding {names[0]!r} on {where} would not go back to {chosen}")
    elif what == "host-mic":
        wanted = previous == "on"
        _grpc("setMicrophoneState", {"realAudioEnabled": wanted})
        now = _mic_state()
        if now != wanted:
            raise Blocked(f"the emulator's host microphone did not go back {previous}; it reads "
                          f"{'on' if now else 'off'}")
    elif what == "frozen-process":
        _thaw(json.loads(previous))
    elif what == "screenrecord":
        # A recorder that outlived the process that started it. Identified by the token in ITS command
        # line, killed by that pid alone, and the debt is paid only once the pid is gone.
        mine = [(pid, cmd) for pid, cmd in _screenrecord_processes() if previous in cmd]
        if len(mine) > 1:
            raise Blocked(f"{len(mine)} screen recorders carry the token {previous}; none is killed")
        if mine:
            _adb(f"kill -2 {mine[0][0]}", check=False)
            for _ in range(30):
                time.sleep(0.5)
                if not any(pid == mine[0][0] for pid, _ in _screenrecord_processes()):
                    break
            else:
                raise Blocked(f"the screen recorder pid {mine[0][0]} would not exit; the debt is kept and "
                              "its file left")
        _adb(f"rm -f /sdcard/wispr-eyes/{previous}.mp4", check=False)
    elif what == "screen-timeout":
        _adb(f"settings put system screen_off_timeout {int(previous)}")
        _, now = _adb("settings get system screen_off_timeout")
        if now.strip() != str(int(previous)):
            raise Blocked(f"the screen timeout did not go back to {previous}; it reads {now.strip()}")
    else:
        raise Blocked(f"there is no verified way to put {what!r} back, so it must not have been journaled")


def restore(keep_frozen_threads=False):
    """Put back everything this session changed, and say what.

    `keep_frozen_threads=True` is for the one scene that needs a take WHILE a thread stays suspended
    (#213: the next start after a capture thread that never released its recorder). It restores and
    settles everything else and leaves every `freeze_thread` debt OWED in the book, so the next plain
    `restore()`, from this or any later session, still thaws it. It never keeps a whole-process freeze.

    **THE WHOLE RUN IS UNDER THE BOOK'S LOCK, and that is the fix for the last race.** Reading the book,
    acting on it, and settling were three separate steps with gaps between them, so another session could
    decide what to do from a state that had already changed: it read a take debt, checked and found
    nothing recording YET, waited, and then erased the debt of a recording that had started in the
    meantime. Deciding and settling have to be one indivisible act.
    """
    with _journal_locked():
        return _restore_locked(keep_frozen_threads)


def _frozen_thread_debt(entry):
    """True for a `freeze_thread` debt: a frozen-process entry that names a thread."""
    if entry[0] != "frozen-process":
        return False
    try:
        return bool(json.loads(entry[1]).get("thread"))
    except (ValueError, AttributeError):
        return False


def _restore_locked(keep_frozen_threads=False):
    """Put back everything this session changed, and say what.

    An entry is removed ONLY after its restore was read back. A failure leaves it in the journal and
    raises, so the next call tries again rather than reporting a rollback that did not happen.

    **What this does NOT cover, said plainly rather than implied:** a dictation this tool started and
    the History row it produced, the app's foreground state, and onboarding once dismissed. This is the
    founder's daily driver: those are not restored, app data is never wiped and `com.envi.wispr` is
    never uninstalled from here, and no report should say otherwise.
    """
    _STATE["restored_for"] = None
    done = []
    # The HOST book first, then this device's, then any OTHER device in the book that is attached right
    # now. A debt owed to a device that is not attached cannot be verified, so it is reported and kept,
    # never settled: an unverified restore is not a restore. The other attached devices are driven
    # through `_as_serial`, so the selection this process made is untouched afterwards.
    selected = device()
    attached = [serial for serial, _ in devices()]
    # EVERY ATTACHED TRANSPORT IS RESOLVED TO ITS HARDWARE FIRST (#161 H7): debts filed under a cable's
    # name are moved under the identity they belong to, and each identity is paid through ONE attached
    # transport that reaches it. A key that names no attached hardware is reported and kept.
    book = _journal_read()
    if _migrate_locked(book, attached):
        _journal_write(book)
    transport_for = {}
    for transport in attached:
        transport_for.setdefault(_scope_key(transport), transport)
    selected_key = _scope_key(selected)
    others = sorted(key for key in _journal_read() if key not in ("host", selected_key))
    for key in ["host", selected_key] + others:
        if key not in ("host", selected_key) and key not in transport_for:
            if _journal_read().get(key):
                done.append(f"{key}: not attached, debt kept")
            continue
        scope = "host" if key == "host" else transport_for.get(key, selected)
        where = "" if key in ("host", selected_key) else f" on {scope} [{key}]"
        for entry in list(_owed(scope)):
            if keep_frozen_threads and _frozen_thread_debt(entry):
                done.append(f"{entry[0]} kept frozen, still owed: {entry[1]}{where}")
                continue
            _restore_one(entry, serial=None if key in ("host", selected_key) else scope)
            _settled_locked(entry, scope)
            done.append(f"{entry[0]} back to {entry[1]}{where}")
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
    if _recording_is_off():
        return [f"NOT RUN: {RECORDING_IS_OFF}"]
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
    # `Take terminal: <STATE>` is the session's own ending line (seen `NO_SPEECH` on 2026-09-20); without
    # it a take that heard nothing waited the full 40 s and 200 log reads before saying so.
    "Take terminal:",
)


def _stream_log_until(markers, seconds, tags, reject=()):
    """ONE blocking `logcat` reader over this process's window; the first line carrying a marker wins.

    A line that carries a marker AND any `reject` substring is skipped: `Take terminal: COMPLETED` is
    written BEFORE the accessibility service answers, so it must not end the wait (code review round 1).

    Not a loop over `logcat -d` snapshots (#161, grounded rounds 3 and 4): a snapshot loop is a poll
    with a budget, and a budget that runs out reads exactly like the subject never answering. The
    timeout here is a failure bound, reported as None, never a product verdict.
    """
    filters = [f"{tag}:I" for tag in tags] + ["*:S"]
    since = _STATE["log_since"]
    args = [ADB, "-s", device(), "logcat", "-v", "time"] + (["-T", since] if since else []) + filters
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    # A TIMER, NOT select(): a text pipe reads ahead into its own buffer, so select() on the descriptor
    # can say "nothing new" while a whole line already sits unread in Python (the first run of this
    # missed the owner's live line that way). Killing the reader from a timer makes the blocking line
    # iterator return, and the bound stays a bound.
    timer = threading.Timer(seconds, process.kill)
    timer.start()
    try:
        for line in process.stdout:
            if any(bad in line for bad in reject):
                continue
            for marker in markers:
                if marker in line:
                    return marker
        return None
    finally:
        timer.cancel()
        process.kill()
        process.wait(timeout=5)


# WHAT ENDS A TAKE, for the wait: the accessibility service's own outcome line, the clipboard fallback the
# owner logs when no service answered, an ASR failure, or a non-COMPLETED terminal. `Auto-insert handed`
# and `Take terminal: COMPLETED` are written BEFORE the service answers and are not endings here (code
# review round 1: returning on them recreated the early-completion gap).
_TAKE_ENDINGS = (
    "insertion api=", "kept on clipboard", "Transcription failed", "Speech recognition failed",
    "No audio captured", "showError", "Take ended:", "Take terminal:",
)
_NOT_ENDINGS = ("Take terminal: COMPLETED", "Auto-insert handed")


def _wait_for_the_take_to_finish(seconds=90):
    """Block until the dictation reaches an ending the insertion has answered, and say which. None on the bound."""
    return _stream_log_until(_TAKE_ENDINGS, seconds, ("DictationSession", "PasteService", "AsrService"),
                             reject=_NOT_ENDINGS)


def _wait_for_owner_listening(seconds=20):
    """Block until the session owner says the take is live, the signal that the microphone is ready.

    The owner writes `Recording started (live after ...)` once the capture process has admitted and
    written its first audio block; the capture process's own `recording_start` comes earlier and is not
    readiness (#161, grounded round 2). A timeout is a refusal about staging, never a verdict.
    """
    marker = _stream_log_until(("Recording started (live after",), seconds, ("DictationSession",))
    if marker is None:
        raise Blocked(f"the owner never said the take went live within {seconds} s, so no audio was "
                      "fed; the microphone was never ready for it")
    return "the owner reported the take live"


# THE WHOLE APP, AS IT IS ON SCREEN. Walked by hand on 2026-09-06 and checked against the code that
# produces it: `AppShell.kt`'s `AppDestination` for the four tabs and `SettingsPage` for the eight
# drawer pages, so a page added to the app and not to this list is a gap somebody can find rather than
# an invisible one. `scan()` reports any page it meets that is not named here.
TABS = ("History", "Dictionary", "Transcription", "AI Polish")
PAGES = ("What's New", "Appearance", "Microphone", "Sounds", "Clipboard",
         "Permissions", "Privacy", "Storage", "Open Source Licenses")


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
    _stable_tree()
    node = _tab_row()[name]
    if node["selected"] or _holder(node, lambda n: n["clickable"]) is None:
        return f"already on {name}"
    holder = _holder(node, lambda n: n["clickable"])
    x, y = holder["centre"][0], node["centre"][1]
    _STATE["tree"] = None
    _adb(f"input tap {x} {y}")
    # READ BACK that the tab took, polling up to the old fixed wait; a tap that landed mid-animation
    # is reported here rather than by the next call failing to find a switch.
    deadline = time.monotonic() + 1.2
    while True:
        time.sleep(0.15)
        if on_screen(name):
            return f"opened {name}"
        if time.monotonic() >= deadline:
            raise Blocked(f"{name!r} was pressed at ({x}, {y}) but the app is not showing it 1.2 s later")


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
        _reach(name)
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
            _reach(name)
            report.extend(_exercise_screen(name, controls))
            if kind == "page":
                back()
    return report


def _reach(where):
    """Open a tab or a drawer page by name, from wherever the app is."""
    if where in TABS:
        open_tab(where)
    else:
        open_settings()
        tap("Open settings menu")
        tap(where, exact=True)


def _exercise_screen(name, controls):
    """Flip every switch on the showing screen and put it back; pick through every pick-one chip group.

    A switch is flipped and flipped back. A chip group (Tone, Structure, Context) is walked BY NAME:
    each other member is chosen and the original chosen back, and its debt is the whole group, never a
    switch debt on one chip. A radio group is skipped: it chooses the polish engine or the microphone,
    and picking through those starts work (a model, a device) that a settings walk has no business
    starting.
    """
    report = []
    done_groups = set()
    for label, was in sorted(controls.items()):
        if not reveal(label):
            report.append(f"ISSUE: {name} / {label} could not be brought back on screen")
            continue
        try:
            if one_way(label):
                try:
                    mark, group = _group_state(label)
                except Blocked as why:
                    if "not one of a group of choices" not in str(why):
                        raise
                    mark, group = None, {}
                if mark != "CheckBox":
                    report.append(f"SKIPPED: {name} / {label} is one of a set where exactly one is "
                                  "chosen, so flipping it could not be undone")
                    continue
                if tuple(sorted(group)) not in done_groups:
                    done_groups.add(tuple(sorted(group)))
                    report.extend(_exercise_group(name, group))
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
    return report


def _exercise_group(name, group):
    """Choose each other member of a pick-one chip group by name, then the original back by name."""
    original = next(member for member, on in group.items() if on)
    report = []
    for other in sorted(group):
        if other == original:
            continue
        try:
            choose(other, where=name)
            _, picked = _group_state(other)
            moved = picked[other] and not picked[original]
            choose(original, where=name)
            _, now = _group_state(original)
            back_again = now == group
        except Blocked as why:
            if "lets several be on at once" in str(why):
                report.append(f"NOTE: {name} / {other}: several can be on at once in the group with "
                              f"{original}, so it was not picked through; {why}")
                return report
            report.append(f"ISSUE: {name} / {other}: {why}")
            continue
        report.append(
            f"VERIFIED: {name} / {other}: picks {other} and goes back to {original}" if moved and back_again
            else f"ISSUE: {name} / {other}: the pick {'did not move' if not moved else 'did not come back'} "
                 f"(the group reads {now})")
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
    if _recording_is_off():
        return [f"NOT RUN: {RECORDING_IS_OFF}"]
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


# --------------------------------------------------------------------------------------------------
# Command line
# --------------------------------------------------------------------------------------------------

def _print_report(command, report, verbose):
    """One line on success, the whole report on a finding or with `--verbose`.

    Everything the harness prints lands in the calling agent's context, so a routine success costs one
    line: the last VERIFIED sentence. A finding prints every line, because then the NOTEs are the
    evidence. Founder 2026-09-20: "blazing fast and not crazy token heavy".
    """
    # NOT RUN is not success either: a skipped row or a recording-off suite that exited 0 and printed OK
    # was a green light over nothing (code review round 1).
    red = any(line.startswith(("ISSUE", "BLOCKED", "NOT RUN")) for line in report)
    if red or verbose:
        print("\n".join(report))
    else:
        verified = [line for line in report if line.startswith("VERIFIED")]
        print(f"OK {command}: {verified[-1][len('VERIFIED: '):] if verified else 'done'} (--verbose for the notes)")
    return 1 if red else 0


def _main(argv):
    verbose = "--verbose" in argv
    argv = [a for a in argv if a != "--verbose"]
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__)
        print("commands: devices look tree find tap tab nav scan switches recorder "
              "quiet logs take shot restore | emulator: launch unlock mic on|off inject <pcm> "
              "dictate-emu <sentence> <expected_final> | insert <text> <expected_final>")
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
            return 1 if any(line.startswith(("ISSUE", "BLOCKED", "NOT RUN")) for line in report) else 0
        elif command == "switches":
            for label, on in sorted(switches().items()):
                print(f"{label}: {'on' if on else 'off'}")
        elif command == "quiet":
            answer = room_is_quiet()
            print(answer["verdict"])
            return 0 if answer["quiet"] else 1
        elif command == "recorder":
            report = check_recorder()
            # A findings report that exits 0 is a green light over a red result.
            return _print_report(command, report, verbose)
        elif command == "logs":
            print(logs(rest[0] if rest else None))
        elif command == "take":
            print(json.dumps(last_take(), indent=2))
        elif command == "shot":
            print(shot(rest[0] if rest else None))
        elif command == "restore":
            lines = restore()
            print("\n".join(lines) if verbose or len(lines) > 3 else "; ".join(lines))
        elif command == "launch":
            print(launch_emulator(restart="--restart" in rest))
        elif command == "unlock":
            print(unlock_emulator())
        elif command == "mic":
            if not rest or rest[0] not in ("on", "off"):
                print("usage: mic on|off", file=sys.stderr)
                return 2
            print(set_host_mic(rest[0] == "on"))
        elif command == "inject":
            print(inject_audio(rest[0]))
        elif command in ("dictate-emu", "insert"):
            if len(rest) < 2:
                print(f"usage: {command} <text> <expected_final>", file=sys.stderr)
                return 2
            report = (dictate_emulator(rest[0], expected_final=rest[1]) if command == "dictate-emu"
                      else debug_insert(rest[0], expected_final=rest[1]))
            return _print_report(command, report, verbose)
        else:
            print(f"unknown command {command!r}", file=sys.stderr)
            return 2
    except Blocked as refusal:
        print(f"BLOCKED: {refusal}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
