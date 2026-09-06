#!/usr/bin/env python3
"""Tests for Wispr Eyes that need no phone.

    python3 scripts/uat/test_wispr_eyes.py

The parts worth testing here are the SAFETY rules, not the adb plumbing. Each rule below exists because
something went wrong driving the founder's phone by hand on 2026-09-06, and a rule nobody tests is a
comment (`tools-and-apps.md` RULE: reproduce-the-regression-before-building-the-guard).

The screen is a fixture, so these run anywhere and are fast. What they cannot cover is whether `adb`
behaves, which is what the device commands are for.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import wispr_eyes as eyes  # noqa: E402


# The shape a real dump has: two identically-labelled buttons, one of which deletes a 484 MB model.
TWO_REMOVES = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="S1-mini" bounds="[112,700][504,760]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="484.2 MB on this phone" bounds="[112,768][504,813]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="Remove" bounds="[180,880][335,937]" package="com.envi.wispr" clickable="true" enabled="true" />
  <node text="Development models folder" bounds="[112,1500][900,1560]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="12.3 MB on this phone" bounds="[112,1620][504,1682]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="Remove" bounds="[180,1749][335,1806]" package="com.envi.wispr" clickable="true" enabled="true" />
  <node text="" content-desc="Open settings menu" bounds="[12,183][156,327]" package="com.envi.wispr" clickable="true" enabled="true" />
</hierarchy>"""

NOTHING_OF_OURS = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="Gmail" bounds="[0,100][100,200]" package="com.google.android.apps.nexuslauncher" clickable="true" enabled="true" />
</hierarchy>"""

RECORDER = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="0:04" bounds="[150,150][300,240]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="LISTENING" bounds="[560,170][760,220]" package="com.envi.wispr" clickable="false" enabled="true" />
  <node text="" content-desc="Cancel" bounds="[770,140][900,260]" package="com.envi.wispr" clickable="true" enabled="true" />
  <node text="" content-desc="Stop" bounds="[910,140][1040,260]" package="com.envi.wispr" clickable="true" enabled="true" />
</hierarchy>"""



ANOTHER_APP = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="Remove" bounds="[10,10][90,90]" package="com.android.settings" clickable="true" enabled="true" />
</hierarchy>"""

# A real recorder window, trimmed from `dumpsys window windows` on the founder's phone, 2026-09-06.
PILL_SHOWING = """  Window #2 Window{28b96f u0 EnviousWispr recording controls}:
    mOwnerUid=10567 showForAllUsers=false package=com.envi.wispr appop=CREATE_ACCESSIBILITY_OVERLAY
    mAttrs={(0,138)(1013xwrap) gr=TOP CENTER ty=ACCESSIBILITY_OVERLAY fmt=TRANSLUCENT
    Requested w=1013 h=167 mLayoutSeq=2715
    mHasSurface=true isReadyForDisplay()=true
    Frames: parent=[0,0][1080,2340] frame=[33,138][1046,305] last=[33,138][1046,305]
    Surface: shown=true      mDrawState=HAS_DRAWN       mLastHidden=false
    isOnScreen=true
    isVisible=true
    WindowStateAnimator{839f4bd EnviousWispr recording controls}:
    mSurface=Surface(name=EnviousWispr recording controls$_29457#2486)"""

# The same window, created but never painted. It exists, and the user sees nothing.
PILL_NOT_DRAWN = """  Window #2 Window{28b96f u0 EnviousWispr recording controls}:
    Requested w=1013 h=167 mLayoutSeq=2715
    mHasSurface=false isReadyForDisplay()=false
    Frames: parent=[0,0][1080,2340] frame=[33,138][1046,305] last=[0,0][0,0]
    Surface: shown=false     mDrawState=NO_SURFACE      mLastHidden=true
    isOnScreen=false
    isVisible=false"""

PASSED, FAILED = [], []


def check(name, condition, detail=""):
    (PASSED if condition else FAILED).append(name)
    print(f"{'  ok  ' if condition else ' FAIL '} {name}{'' if condition else f'  <- {detail}'}")


def with_screen(xml):
    """Point the module at a fixture screen instead of a phone."""
    eyes._STATE["tree"] = None
    eyes._STATE["serial"] = "fixture"
    original = eyes._adb
    eyes._adb = lambda command, timeout=60, check=True: (
        (0, xml) if command.startswith("cat ") else (0, "")
    )
    return original


def with_window(dump):
    """Point the module at a fixture `dumpsys window windows`, and at an empty node tree.

    The empty tree is the POINT, not a shortcut: the recorder window is genuinely absent from the node
    tree on the real phone, so a fixture that supplied one would let a reader written against the wrong
    eye pass here and fail on hardware.
    """
    eyes._STATE["tree"] = None
    eyes._STATE["serial"] = "fixture"
    original = eyes._adb
    eyes._adb = lambda command, timeout=60, check=True: (
        (0, dump) if command.startswith("dumpsys window") else
        (0, "<?xml version='1.0'?><hierarchy rotation=\"0\"></hierarchy>") if command.startswith("cat ") else
        (0, "")
    )
    return original


def restore_adb(original):
    eyes._adb = original


def main():
    # ---- the tree is parsed into something with a centre to press -------------------------------
    original = with_screen(TWO_REMOVES)
    nodes = eyes.tree()
    check("every node is parsed", len(nodes) == 7, f"got {len(nodes)}")
    menu = [n for n in nodes if n["desc"] == "Open settings menu"][0]
    check("a centre is computed from bounds", menu["centre"] == (84, 255), menu["centre"])
    check("a content description is read like text", eyes.present("Open settings menu"))

    # ---- THE RULE THAT MATTERS: ambiguity refuses, it does not pick ------------------------------
    # Two "Remove" buttons, and one of them deletes a model the founder would have to download again.
    try:
        eyes.find("Remove")
        check("an ambiguous query refuses", False, "it picked one instead of refusing")
    except eyes.Blocked as refusal:
        message = str(refusal)
        check("an ambiguous query refuses", True)
        check("and it names every candidate", message.count("(") >= 2, message)
        check("and it says how to narrow it", "longer phrase" in message, message)

    # An unambiguous query still works, so the guard is not simply always-on.
    found = eyes.find("Development models folder")
    check("an unambiguous query still resolves", found["centre"] == (506, 1530), found["centre"])

    # `exact` is a real escape hatch, not decoration.
    try:
        eyes.find("12.3 MB on this phone", exact=True)
        check("exact matching narrows a query", True)
    except eyes.Blocked as refusal:
        check("exact matching narrows a query", False, refusal)

    # ---- absence says what IS there, so a wrong screen is diagnosed in one read -------------------
    try:
        eyes.find("Bluetooth pairing")
        check("an absent query refuses", False, "it found something")
    except eyes.Blocked as refusal:
        check("an absent query refuses", True)
        check("and it shows the screen it looked at", "Remove" in str(refusal), str(refusal))

    # ---- look NEVER returns an empty string ------------------------------------------------------
    restore_adb(original)
    original = with_screen(NOTHING_OF_OURS)
    text = eyes.look(only_ours=True)
    check("look never answers with silence", bool(text.strip()), repr(text))
    check("and it names what is showing instead", "nexuslauncher" in text, text)

    # ---- the recorder is read from the WINDOW MANAGER, the only eye that sees it ------------------
    # Trimmed from a real `dumpsys window windows` block captured on the founder's phone on
    # 2026-09-06 while a take was running.
    restore_adb(original)
    original = with_window(PILL_SHOWING)
    pill = eyes.overlay()
    check("the recorder is found", pill is not None)
    if pill:
        check("its position is read", pill["where"] == (33, 138, 1046, 305), pill["where"])
        check("its size is read", pill["size"] == (1013, 167), pill["size"])
        check("and it says the user can see it", pill["the_user_can_see_it"] is True, pill)
    # ONE window whose block names itself twice is still ONE window. The nested animator line is in
    # the fixture above precisely so a name-matching reader fails this row.
    check("a window named twice in its own block is not two windows", pill is not None)

    # A window that exists but was never drawn must NOT read as one the user can see. This is the
    # whole reason the four signals are kept apart instead of collapsed into one "is it up".
    restore_adb(original)
    original = with_window(PILL_NOT_DRAWN)
    pill = eyes.overlay()
    check("an undrawn recorder is not reported as visible",
          pill is not None and pill["the_user_can_see_it"] is False, pill)

    # No such window means None, never half a pill.
    restore_adb(original)
    original = with_window("Window #2 Window{1 u0 com.sec.android.app.launcher}:\n  isVisible=true")
    check("no recorder means None, not a guess", eyes.overlay() is None, eyes.overlay())

    # Two recorder windows at once is a refusal, because a caller would otherwise be told a position
    # that belongs to whichever one the dump happened to print first.
    restore_adb(original)
    original = with_window(PILL_SHOWING + "\n" + PILL_SHOWING)
    try:
        eyes.overlay()
        check("a duplicated recorder window refuses", False, "it picked one")
    except eyes.Blocked as refusal:
        check("a duplicated recorder window refuses", "guess" in str(refusal), refusal)
    restore_adb(original)
    original = with_screen(TWO_REMOVES)

    # ---- the rules the review added, each with its own control -----------------------------------
    original = with_screen(TWO_REMOVES)
    try:
        eyes.find("")
        check("an empty query refuses", False, "it matched something")
    except eyes.Blocked:
        check("an empty query refuses", True)

    # Substring matching is opt-in now, so a partial word does not quietly widen the net.
    try:
        eyes.find("Remov")
        check("a partial word does not match by default", False, "it matched")
    except eyes.Blocked as refusal:
        check("a partial word does not match by default", "nothing on screen matches" in str(refusal))
    check("and substring is available on request",
          eyes.find("Development models", exact=False)["centre"] == (506, 1530))

    restore_adb(original)
    original = with_screen(ANOTHER_APP)
    try:
        eyes.find("Remove")
        check("another app's control is out of scope", False, "it matched a Settings button")
    except eyes.Blocked as refusal:
        check("another app's control is out of scope", "nothing on screen matches" in str(refusal))

    # `lines` reaches a shell command.
    restore_adb(original)
    original = with_screen(TWO_REMOVES)
    try:
        eyes.logs(lines="200; input tap 257 908")
        check("a non-numeric line count refuses", False, "it was accepted")
    except eyes.Blocked:
        check("a non-numeric line count refuses", True)
    restore_adb(original)

    # ---- the "put it back" book -------------------------------------------------------------------
    # Every row here drives the REAL journal, pointed at a throwaway file. The rows exist because the
    # book used to live in memory, and this tool runs one process per errand.
    import tempfile
    book = Path(tempfile.mkdtemp()) / "restore.json"
    eyes._JOURNAL = book
    eyes._STATE["serial"] = "fixture"

    # A phone that really stores what it is told, so the read-back in `_restore_one` has something to
    # read. A stub that answers "" makes every restore refuse, which would pass the refusal rows for
    # the wrong reason and hide whether a restore can ever SUCCEED.
    store = {"screen_off_timeout": "1800000"}

    def fake_phone(command, timeout=60, check=True):
        put = re.match(r"settings put system (\w+) (\S+)", command)
        if put:
            store[put.group(1)] = put.group(2)
            return 0, ""
        got = re.match(r"settings get system (\w+)", command)
        if got:
            return 0, store.get(got.group(1), "null") + "\n"
        return 0, ""

    eyes._adb = fake_phone

    check("with nothing changed it says so", eyes.restore() == ["nothing was changed"])

    # THE DEFECT THAT MADE THIS FILE-BACKED: a change written by one process must be visible to the
    # next. Reading it back through a FRESH import of the module is the only way to test that, because
    # anything else shares this process's memory and would pass with the old in-memory list.
    eyes._owe(("screen-timeout", "600000"))
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "wispr_eyes_second_process", Path(__file__).parent / "wispr_eyes.py")
    fresh = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(fresh)
    fresh._JOURNAL = book
    check("a change survives into the NEXT process",
          fresh._owed("fixture") == [("screen-timeout", "600000")], fresh._owed("fixture"))

    # And it is put back FOR REAL, once, and then gone.
    said = eyes.restore()
    check("restoring it says what it did", said == ["screen-timeout back to 600000"], said)
    check("and the phone actually holds the old value",
          store["screen_off_timeout"] == "600000", store)
    check("and the book is then empty", eyes._owed("fixture") == [], eyes._owed("fixture"))

    # A change owed to ANOTHER phone is never restored onto this one.
    eyes._owe(("screen-timeout", "15000"), serial="some-other-phone")
    check("another phone's change is left alone", eyes.restore() == ["nothing was changed"])
    check("and it is still owed to that phone",
          eyes._owed("some-other-phone") == [("screen-timeout", "15000")])

    # A restore with no verified way to put something back must RAISE, not claim success.
    eyes._owe(("something-nobody-wrote-a-handler-for", "x"))
    try:
        eyes.restore()
        check("an unrestorable change refuses", False, "it reported success")
    except eyes.Blocked as refusal:
        check("an unrestorable change refuses", "no verified way" in str(refusal), refusal)
    check("and it stays in the book for the next try", len(eyes._owed("fixture")) == 1)

    print()
    print(f"{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("failed: " + ", ".join(FAILED))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
