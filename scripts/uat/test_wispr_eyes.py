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

# Two labels where one is a PREFIX of the other, so exact and substring matching disagree. Without a
# screen like this, a row claiming to test exact matching passes with exact matching deleted.
EXACT_VS_SUBSTRING = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="Remove" bounds="[180,880][335,937]" package="com.envi.wispr" clickable="true" enabled="true" />
  <node text="Remove all models" bounds="[180,1000][600,1057]" package="com.envi.wispr" clickable="true" enabled="true" />
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

# A real switch and a single-choice group, side by side, exactly as the phone reports them. The Clipboard
# switch is clickable while ON; the chosen member of the AI Polish group is not, and that is the ONLY
# thing telling them apart.
SWITCH_AND_CHOICE = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node bounds="[0,0][1080,2340]" package="com.envi.wispr" class="android.widget.FrameLayout" checkable="false" clickable="false" enabled="true" text="">
    <node bounds="[56,600][1024,760]" package="com.envi.wispr" class="android.view.View" checkable="true" checked="true" clickable="true" enabled="true" text="">
      <node bounds="[74,620][900,680]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="Smart insertion" />
      <node bounds="[74,690][900,740]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="Match spacing to the text around your cursor." />
    </node>
    <node bounds="[56,900][350,1060]" package="com.envi.wispr" class="android.view.View" checkable="true" checked="false" clickable="true" enabled="true" text="">
      <node bounds="[74,980][300,1040]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="Off" />
    </node>
    <node bounds="[370,900][660,1060]" package="com.envi.wispr" class="android.view.View" checkable="true" checked="true" clickable="false" enabled="true" text="">
      <node bounds="[380,980][650,1040]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="This phone" />
    </node>
    <node bounds="[680,900][1024,1060]" package="com.envi.wispr" class="android.view.View" checkable="true" checked="false" clickable="true" enabled="true" text="">
      <node bounds="[700,980][1000,1040]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="Cloud" />
    </node>
    <node bounds="[56,1200][1024,1300]" package="com.envi.wispr" class="android.widget.TextView" checkable="false" clickable="false" enabled="true" text="Cancel" />
  </node>
</hierarchy>"""

# The same window with EVERY signal healthy except one. Without a fixture like this, a row claiming to
# test the drawn state passes with the drawn check deleted, because some other false signal carries it.
PILL_SHOWN_BUT_NOT_DRAWN = PILL_SHOWING.replace("mDrawState=HAS_DRAWN", "mDrawState=NO_SURFACE")

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

    # `exact` IS THE THING BEING TESTED, so the fixture has to be one where exact and substring give
    # DIFFERENT answers. Against a screen with a single candidate either way, turning exact matching
    # into substring matching changes nothing and the row stays green having asserted nothing.
    restore_adb(original)
    original = with_screen(EXACT_VS_SUBSTRING)
    found = eyes.find("Remove", exact=True)
    check("exact matching finds the one whose whole label matches",
          found["bounds"] == (180, 880, 335, 937), found["bounds"])
    try:
        eyes.find("Remove", exact=False)
        check("and substring matching would have been ambiguous", False, "it resolved to one")
    except eyes.Blocked as refusal:
        check("and substring matching would have been ambiguous", "2 nodes match" in str(refusal), refusal)
    restore_adb(original)
    original = with_screen(TWO_REMOVES)

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

    # ---- a switch you can turn back, and a choice you cannot un-make -------------------------------
    # Getting this wrong changes the founder's settings and cannot undo it: choosing "Off" on the polish
    # page turns polish off, and choosing it again does not turn it back on.
    restore_adb(original)
    original = with_screen(SWITCH_AND_CHOICE)
    check("a real switch is not one-way", eyes.one_way("Smart insertion") is False)
    check("the chosen member of a set IS one-way", eyes.one_way("This phone") is True)
    check("and so are its unchosen siblings", eyes.one_way("Off") is True)

    # The guard is what stops the flip, so drive the real function.
    try:
        eyes.set_switch("Off", True, where="AI Polish")
        check("flipping a one-way choice refuses", False, "it pressed it")
    except eyes.Blocked as refusal:
        check("flipping a one-way choice refuses", "cannot be undone" in str(refusal), refusal)

    # A switch change has to say WHERE it was made, or the debt cannot be settled from a fresh process
    # — and a fresh process is the only kind this tool has.
    try:
        eyes.set_switch("Smart insertion", False, where="somewhere")
        check("a switch change must name its screen", False, "it accepted a screen that does not exist")
    except eyes.Blocked as refusal:
        check("a switch change must name its screen", "not a screen this app has" in str(refusal), refusal)

    # A name that merely EXISTS is not a name that can restore anything. This screen is not Clipboard,
    # so a debt recorded against Clipboard would point somewhere the switch is not.
    try:
        eyes.set_switch("Smart insertion", False, where="Clipboard")
        check("and it must be the screen actually showing", False, "it accepted the wrong screen")
    except eyes.Blocked as refusal:
        check("and it must be the screen actually showing", "is not showing" in str(refusal), refusal)

    # And the guard is not simply always-on: a real switch still reads.
    check("a real switch still reads", eyes.switch("Smart insertion") is True)

    # `switches()` walks from the SWITCHES outward rather than from a list of names, so a switch added
    # to the app appears without anyone editing the harness. This row is what binds that: it asserts the
    # whole map, so a reader that found three of four would fail rather than look complete.
    found = eyes.switches()
    check("every switch on the screen is found, by its own words",
          found == {"Smart insertion": True, "Off": False, "This phone": True, "Cloud": False}, found)

    # A row with no switch in it is NOT a switch that is off. Collapsing those tells a reader a setting
    # is off when the truth is that nothing there is a setting.
    try:
        eyes.switch("Cancel")
        check("a row with no switch is not reported as off", False, "it answered False")
    except eyes.Blocked as refusal:
        check("a row with no switch is not reported as off",
              "nothing around it is a switch" in str(refusal), refusal)
    restore_adb(original)
    original = with_screen(TWO_REMOVES)

    # ---- reading the screen RETRIES, and the retry is exercised ----------------------------------
    # The retry existed as a loop and a sentence for a while and could never run, because the helper it
    # called raised on a failing status before the second attempt. These two rows are what bind it.
    restore_adb(original)
    eyes._STATE["tree"] = None
    attempts = []

    def flaky(command, timeout=60, check=True):
        """A phone whose screen read fails twice, behaving like the REAL helper.

        **Honouring `check` is what makes this row a control.** A stub that returns a status instead of
        raising passes whether or not production asks for `check=False`, so removing the very thing the
        retry depends on would have left this green. The real `_adb` raises when `check` is set, so
        this does too.
        """
        if command.startswith("uiautomator"):
            attempts.append(command)
            # 137 is the real signal seen on the phone: another reader killed this one.
            remote = 0 if len(attempts) >= 3 else 137
            if check and remote:
                raise eyes.Blocked(f"the phone refused it (status {remote})")
            return remote, ""
        if command.startswith("cat "):
            return 0, TWO_REMOVES
        return 0, ""

    eyes._adb = flaky
    # Caught rather than allowed to escape: a mutation that breaks the retry raises here, and a raise
    # that ends the whole suite hides every row after it.
    try:
        nodes = eyes.tree(refresh=True)
        check("a screen read that fails twice still succeeds", len(nodes) == 7, f"got {len(nodes)}")
    except eyes.Blocked as why:
        check("a screen read that fails twice still succeeds", False, f"it gave up: {why}")
    check("and it really did retry", len(attempts) == 3, attempts)

    eyes._STATE["tree"] = None
    def never_reads(command, timeout=60, check=True):
        remote = 137 if command.startswith("uiautomator") else 0
        if check and remote:
            raise eyes.Blocked(f"the phone refused it (status {remote})")
        return remote, ""

    eyes._adb = never_reads
    try:
        eyes.tree(refresh=True)
        check("a screen that never reads refuses", False, "it returned something")
    except eyes.Blocked as refusal:
        check("a screen that never reads refuses", "three times" in str(refusal), refusal)
        check("and it names the other reader", "same time" in str(refusal), refusal)
    restore_adb(original)
    original = with_screen(TWO_REMOVES)

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

    # AND WITH ONLY THE DRAWN STATE WRONG. The row above has every signal false, so deleting the drawn
    # check leaves it green on the strength of the others. This one is carried by nothing else.
    restore_adb(original)
    original = with_window(PILL_SHOWN_BUT_NOT_DRAWN)
    pill = eyes.overlay()
    check("a window that was never painted is not reported as visible",
          pill is not None and pill["drawn"] is False and pill["the_user_can_see_it"] is False, pill)

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
    # ONE labelled node on screen, deliberately. Against a busy screen the empty query was refused for
    # AMBIGUITY, so deleting the empty-input guard left this row green. With a single node there is
    # nothing ambiguous, and only the guard can produce a refusal.
    original = with_screen(
        "<?xml version='1.0'?><hierarchy rotation=\"0\">"
        "<node text=\"Remove\" bounds=\"[10,10][90,90]\" package=\"com.envi.wispr\" "
        "clickable=\"true\" enabled=\"true\" /></hierarchy>")
    try:
        eyes.find("")
        check("an empty query refuses", False, "it matched something")
    except eyes.Blocked as refusal:
        check("an empty query refuses", "non-empty" in str(refusal), refusal)
    restore_adb(original)
    original = with_screen(TWO_REMOVES)

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
        if "VoiceInputActivity" in command and "cancel" in command:
            store["take"] = "stopped"
            return 0, ""
        if command.startswith("am force-stop"):
            store["take"] = "stopped"
            return 0, ""
        if command.startswith("logcat -d"):
            # A TAKE THAT IS STILL RUNNING UNTIL SOMETHING ENDS IT. A fixture that always reports a
            # stopped take lets a restore which cancels NOTHING report success, so the row asserting
            # that a later session ends a leftover recording was green with the cancel deleted.
            started = "09-06 10:00:00.000 I/AudioCapture(1): recording_start [+0ms]\n"
            if store.get("take") == "running":
                return 0, started
            return 0, started + "09-06 10:00:05.000 I/AudioCapture(1): recording_stop [+5000ms]\n"
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

    # TWO SWITCHES ARE TWO DEBTS. Keying them on the word "switch" let one flipped switch hide the
    # next, so a run that flipped four and crashed would have recorded one.
    import json as _json
    first = ("switch", _json.dumps({"where": "Clipboard", "label": "Smart insertion", "was": True},
                                   sort_keys=True))
    second = ("switch", _json.dumps({"where": "Clipboard", "label": "Auto-copy to clipboard", "was": True},
                                    sort_keys=True))
    eyes._owe(first)
    eyes._owe(second)
    check("two switches on one screen are two debts", len(eyes._owed("fixture")) == 2, eyes._owed("fixture"))
    # And the same switch recorded twice stays ONE debt, holding the value it started at.
    eyes._owe(("switch", _json.dumps({"where": "Clipboard", "label": "Smart insertion", "was": False},
                                     sort_keys=True)))
    check("the same switch twice is still one debt", len(eyes._owed("fixture")) == 2, eyes._owed("fixture"))
    # THE SPECIFIC ENTRY, not "one of them". `dict(...)["switch"]` collapses both switch debts to
    # whichever came last, so changing Smart insertion's saved value left this green on the strength of
    # the OTHER switch's untouched one.
    smart = [_json.loads(e[1]) for e in eyes._owed("fixture")
             if e[0] == "switch" and _json.loads(e[1])["label"] == "Smart insertion"]
    check("and it keeps the value it started at",
          len(smart) == 1 and smart[0]["was"] is True, smart)
    eyes._settled(first)
    eyes._settled(second)
    check("settling them empties the book", eyes._owed("fixture") == [], eyes._owed("fixture"))

    # THE RECORDING HALF IS OFF, and this row is what says so rather than a comment claiming it. Six
    # review rounds each found a different sequence that could leave a recording running with nothing
    # recording it, and the consequence declared before round six's verdict was that the four calls which
    # START one refuse until a round says the class is closed.
    try:
        with eyes.open_recorder():
            check("starting a recording is refused", False, "it started one")
    except eyes.Blocked as refusal:
        check("starting a recording is refused", "recording from this harness is off" in str(refusal),
              refusal)
    for name, call in (("check_recorder", eyes.check_recorder),
                       ("test_dictation", eyes.test_dictation),
                       ("room_is_quiet", eyes.room_is_quiet)):
        answer = call()
        check(f"{name} reports it as BLOCKED rather than raising",
              isinstance(answer, list) and answer and answer[0].startswith("BLOCKED:"), answer)

    # AND THE GUARD UNDERNEATH IS STILL THERE, so turning recording back on does not also turn off the
    # rule that a take may not begin on a phone whose state is unknown. Read directly, because the
    # refusal above now happens first.
    real_off = eyes.RECORDING_IS_OFF
    eyes.RECORDING_IS_OFF = ""
    eyes._STATE["restored_for"] = None
    try:
        with eyes.open_recorder():
            check("a take refuses on an unrestored phone", False, "it started one")
    except eyes.Blocked as refusal:
        check("a take refuses on an unrestored phone", "restore() has not been run" in str(refusal), refusal)
    finally:
        eyes.RECORDING_IS_OFF = real_off

    # A RECORDING THAT OUTLIVED THE PROCESS THAT STARTED IT. Only a debt on disk can carry this across
    # a killed run, and only a later session can act on it, so this row is the whole reason the take is
    # journaled rather than merely wrapped in a `finally`.
    # THE LOCK MUST NOT BLOCK ON ITSELF. `flock` is per open file description, so a second `open` plus
    # `LOCK_EX` from the same process waits for ever — and `open_recorder` holds this lock across the
    # start, so a deadlock there means a take running on the phone with the harness frozen.
    with eyes._journal_locked():
        with eyes._journal_locked():
            eyes._owe(("screen-timeout", "1"))
    check("taking the book's lock twice in one process does not hang",
          ("screen-timeout", "1") in eyes._owed("fixture"), eyes._owed("fixture"))
    eyes._settled(("screen-timeout", "1"))

    store["take"] = "running"
    eyes._owe(("take", "fixture"))
    # NAMED FOR WHAT IT DOES. This inserts a debt by hand; it does not kill a process, so it is evidence
    # that the book carries a take, not that one survived a crash.
    check("a take debt is readable from the book",
          ("take", "fixture") in eyes._owed("fixture"), eyes._owed("fixture"))
    said = eyes.restore()
    # NAMED FOR WHAT IT CHECKS. This runs in the same process, so it is not evidence that a LATER
    # session ends anything; it says the restore reported handling the debt. The row below is the one
    # that says the microphone closed, and that is the property.
    check("restore says it handled the take debt", any("take" in line for line in said), said)
    # THE PHONE, not the report. The row above says what the harness CLAIMED; this one says the
    # microphone actually closed, and it is the one that fails when the cancel is deleted.
    check("and the microphone is actually closed", store.get("take") == "stopped", store.get("take"))
    check("and the book is clear afterwards", eyes._owed("fixture") == [], eyes._owed("fixture"))

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
