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

import json
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

ONE_BUTTON_TWO_NODES = """<?xml version='1.0' encoding='UTF-8'?>
<hierarchy rotation="0">
  <node text="Update" bounds="[700,820][868,884]" package="com.android.vending" clickable="true" enabled="true">
    <node text="Update" bounds="[700,820][868,884]" package="com.android.vending" clickable="false" enabled="true" />
  </node>
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
    # THE FAKE TRANSPORTS' HARDWARE IDENTITIES (#161 H7), as `getprop ro.serialno` would answer them; the
    # book is keyed by these, and `_owed("emulator-5554")` reads the book under `EMU-5554`. A transport
    # that is not attached keeps its own name as its key (see `test_book_is_keyed_by_identity` below).
    eyes._STATE["identities"].update({"emulator-5554": "EMU-5554", "100.94.206.47:5555": "S26-HW"})
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
        # THE NAMES AND THE PLACES, not a count of brackets. Counting `(` passed on a message with the
        # right shape and the wrong contents, which is the whole thing this refusal exists to give:
        # somebody has to be able to tell the two Removes apart from what it says.
        check("and it names every candidate",
              "'Remove' at (257, 908)" in message and "'Remove' at (257, 1777)" in message, message)
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

    # One control reported twice at the SAME point is one press, not a guess (the Play Store's Update
    # button, 2026-09-21): the clickable node wins. Two nodes at DIFFERENT points still refuse (above).
    original = with_screen(ONE_BUTTON_TWO_NODES)
    found = eyes.find("Update", exact=True, package="com.android.vending")
    check("two nodes at one point resolve to the clickable one", found["clickable"] and found["centre"] == (784, 852), found)
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

    # ---- the emulator is the ONLY device this file unlocks, feeds, or records on unasked --------------
    # #177: one front door. Every row here drives the real functions with adb and grpcurl faked, and
    # every row exists because the same call against the founder's phone would cost him something.
    import tempfile as _tempfile
    emu_book = Path(_tempfile.mkdtemp()) / "restore.json"
    original_run, original_grpc, original_journal = eyes._run, eyes._grpc, eyes._JOURNAL
    original_adb, original_devices, original_screen = eyes._adb, eyes.devices, eyes._screen_size
    original_recording, original_bound = eyes.recording, eyes.bound
    eyes._JOURNAL = emu_book
    emu = {"qemu": "1", "keyguard": "true", "mic": True, "pin_accepted": True, "attached": ["emulator-5554"]}
    sent = []
    grpc_calls = []

    def emu_run(args, timeout=60):
        if len(args) >= 5 and args[3] == "shell" and args[4] == "getprop ro.kernel.qemu":
            return (0, emu["qemu"] + "\n", "")
        return (1, "", "not faked: " + " ".join(args))

    def emu_grpc(method, payload=None, stdin_path=None, timeout=120):
        grpc_calls.append((method, payload, stdin_path))
        if method == "getMicrophoneState":
            return {"realAudioEnabled": True} if emu["mic"] else {}
        if method == "setMicrophoneState":
            emu["mic"] = bool(payload["realAudioEnabled"])
            return {}
        return {}

    BOUNCER = ('<?xml version="1.0"?><hierarchy rotation="0"><node text="Enter password" resource-id="bouncer_primary_message_area" '
               'bounds="[0,0][100,50]" package="com.android.systemui" class="android.widget.TextView" clickable="false" enabled="true" />'
               '<node text="" resource-id="passwordEntry" bounds="[0,60][100,120]" package="com.android.systemui" '
               'class="android.widget.EditText" clickable="true" enabled="true" focused="true" /></hierarchy>')
    LOCKSCREEN = ('<?xml version="1.0"?><hierarchy rotation="0"><node text="Sun, Sep 20" resource-id="" bounds="[0,0][100,50]" '
                  'package="com.android.systemui" class="android.widget.TextView" clickable="false" enabled="true" /></hierarchy>')
    # How the password field comes up: "dismiss" (the window manager raises it), "swipe" (only a swipe
    # does), or "never".
    emu["field"] = "dismiss"
    emu["field_up"] = False

    def emu_adb(command, timeout=60, check=True, serial=None):
        if command.startswith("input"):
            sent.append(command)
            if command.startswith("input swipe") and emu["field"] == "swipe":
                emu["field_up"] = True
            if command.startswith("input keyevent KEYCODE_ENTER") and emu["pin_accepted"]:
                emu["keyguard"] = "false"
            return 0, ""
        if command.startswith("wm dismiss-keyguard"):
            sent.append(command)
            if emu["field"] == "dismiss":
                emu["field_up"] = True
            return 0, ""
        if command.startswith("uiautomator dump"):
            return 0, ""
        if command.startswith("cat "):
            return 0, (BOUNCER if emu["field_up"] else LOCKSCREEN)
        if command.startswith("wm size"):
            return 0, "Physical size: 1080x2400\n"
        if "isKeyguardShowing" in command:
            return 0, f"isKeyguardShowing={emu['keyguard']}\n"
        if command.startswith("dumpsys power"):
            return 0, "mWakefulness=Awake\n"
        return 0, ""

    eyes._run, eyes._grpc, eyes._adb = emu_run, emu_grpc, emu_adb
    eyes.devices = lambda: [(s, "sdk_gphone64_arm64") for s in emu["attached"]] + [("100.94.206.47:5555", "SM_S948U1")]
    eyes._screen_size = lambda: (1080, 2400)
    eyes._STATE["serial"] = "emulator-5554"

    check("an emulator serial that says ro.kernel.qemu=1 is an emulator", eyes.is_emulator("emulator-5554"))
    emu["qemu"] = "0"
    check("an emulator-looking serial whose kernel is not qemu is NOT an emulator",
          not eyes.is_emulator("emulator-5554"))
    check("and the recording lock stays closed for it", eyes._recording_is_off())
    emu["qemu"] = "1"
    check("a phone serial is never an emulator, without asking it", not eyes.is_emulator("100.94.206.47:5555"))
    check("and the recording lock opens only for the real emulator", not eyes._recording_is_off())

    # ready() opens the emulator itself, and the phone refusal is byte-for-byte what it was.
    sent.clear()
    check("ready() unlocks a locked emulator and passes", eyes.ready() is True and emu["keyguard"] == "false")
    check("the unlock sent wake, dismiss-keyguard, the PIN and enter, in that order, and no swipe",
          sent[0] == "input keyevent KEYCODE_WAKEUP" and sent[1] == "wm dismiss-keyguard"
          and sent[2] == f"input text {eyes.EMULATOR_PIN}" and sent[3] == "input keyevent KEYCODE_ENTER"
          and not any(c.startswith("input swipe") for c in sent), sent)
    check("an already-open emulator gets no input at all",
          eyes.unlock_emulator() == "already open" and len(sent) == 4)
    # When the window manager does not raise the field, ONE swipe is tried, and the PIN typed after it.
    emu["keyguard"], emu["field"], emu["field_up"] = "true", "swipe", False
    sent.clear()
    check("a field the window manager did not raise gets one swipe, and the PIN typed once after it",
          eyes.unlock_emulator() == "unlocked" and sum(c.startswith("input swipe") for c in sent) == 1
          and sent.index(f"input text {eyes.EMULATOR_PIN}") > sent.index(next(c for c in sent if c.startswith("input swipe"))), sent)
    emu["keyguard"], emu["field"], emu["field_up"] = "true", "never", False
    sent.clear()
    try:
        eyes.unlock_emulator()
        check("no password field at all means no PIN typed", False, "it returned")
    except eyes.Blocked as refusal:
        check("no password field at all means no PIN typed",
              "never came up" in str(refusal) and not any("input text" in c for c in sent)
              and sum(c.startswith("input swipe") for c in sent) == 1, sent)
    emu["keyguard"], emu["pin_accepted"], emu["field"], emu["field_up"] = "true", False, "dismiss", False
    sent.clear()
    try:
        eyes.unlock_emulator()
        check("a PIN that does not open it is reported after ONE attempt", False, "it returned")
    except eyes.Blocked as refusal:
        check("a PIN that does not open it is reported after ONE attempt",
              "stayed locked" in str(refusal) and sent.count(f"input text {eyes.EMULATOR_PIN}") == 1, sent)
    emu["pin_accepted"] = True
    # The identity is asked AGAIN right before the first keystroke, and a changed answer sends nothing.
    answers = iter(["1", "0", "0", "0"])
    eyes._run = lambda args, timeout=60: (0, next(answers) + "\n", "") if args[-1] == "getprop ro.kernel.qemu" else emu_run(args, timeout)
    sent.clear()
    try:
        eyes.unlock_emulator()
        check("a device that stops answering as an emulator between the two probes gets no key", False, "it returned")
    except eyes.Blocked as refusal:
        check("a device that stops answering as an emulator between the two probes gets no key",
              "no key was sent" in str(refusal) and sent == [], (refusal, sent))
    eyes._run = emu_run
    emu["keyguard"] = "true"
    eyes._STATE["serial"] = "100.94.206.47:5555"
    sent.clear()
    try:
        eyes.ready()
        check("a locked PHONE is still refused", False, "it passed")
    except eyes.Blocked as refusal:
        check("a locked PHONE is still refused, with the owner message",
              "only its owner can open it" in str(refusal))
        check("and not one input command was sent at it", sent == [], sent)
    emu["keyguard"] = "false"
    eyes._STATE["serial"] = "emulator-5554"

    # The host microphone: journaled BEFORE the change, read back after, restorable from any process.
    emu["mic"] = True
    grpc_calls.clear()
    check("set_host_mic(False) turns it off over gRPC", eyes.set_host_mic(False) == "host microphone off" and emu["mic"] is False)
    check("and the previous state is in the book", eyes._owed("emulator-5554") == [("host-mic", "on")])
    order = [m for m, _, _ in grpc_calls]
    check("the set is preceded by a read and followed by a read-back",
          order == ["getMicrophoneState", "setMicrophoneState", "getMicrophoneState"], order)
    check("setting it to what it already is changes nothing and owes nothing",
          eyes.set_host_mic(False) == "host microphone already off" and len(eyes._owed("emulator-5554")) == 1)
    # A journal write that fails means NO set call: the change is refused before it is made.
    real_owe_locked = eyes._owe_locked
    eyes._owe_locked = lambda entry, serial: None
    grpc_calls.clear()
    try:
        eyes.set_host_mic(True)
        check("a change whose debt cannot be written is not made", False, "it returned")
    except eyes.Blocked:
        check("a change whose debt cannot be written is not made",
              "setMicrophoneState" not in [m for m, _, _ in grpc_calls], grpc_calls)
    eyes._owe_locked = real_owe_locked
    # A read-back that disagrees keeps the debt.
    stubborn = dict(emu)

    def stubborn_grpc(method, payload=None, stdin_path=None, timeout=120):
        grpc_calls.append((method, payload, stdin_path))
        return {"realAudioEnabled": True} if method == "getMicrophoneState" and stubborn["mic"] else {}

    eyes._grpc = stubborn_grpc
    stubborn["mic"] = False
    eyes._settled(("host-mic", "on"), "emulator-5554")
    try:
        eyes.set_host_mic(True)
        check("a read-back that disagrees is a refusal that keeps the debt", False, "it returned")
    except eyes.Blocked as refusal:
        check("a read-back that disagrees is a refusal that keeps the debt",
              "did not switch on" in str(refusal) and eyes._owed("emulator-5554") == [("host-mic", "off")], refusal)
    eyes._grpc = emu_grpc
    # The book now owes "off" from the refused change above; replace it with a real "on" debt and an
    # emulator whose mic is off, so a restore has something to put back.
    eyes._settled(("host-mic", "off"), "emulator-5554")
    eyes._owe(("host-mic", "on"), "emulator-5554")
    emu["mic"] = False
    # restore() from a process pointed at the PHONE still puts the attached emulator's mic back, and
    # leaves the selection and the restored-for marker on the phone.
    eyes._STATE["serial"] = "100.94.206.47:5555"
    lines = eyes.restore()
    check("a phone-selected restore puts an attached emulator's mic back",
          emu["mic"] is True and eyes._owed("emulator-5554") == [] and any("on emulator-5554" in l for l in lines), lines)
    check("and the selection stays on the phone afterwards",
          eyes._STATE["serial"] == "100.94.206.47:5555" and eyes._STATE["restored_for"] == "100.94.206.47:5555")
    # A debt owed to a device that is NOT attached is reported and kept, never settled blind.
    eyes._owe(("host-mic", "on"), "emulator-5556")
    lines = eyes.restore()
    check("a debt to a device that is not attached is kept and named",
          any("emulator-5556: not attached, debt kept" in l for l in lines) and eyes._owed("emulator-5556") == [("host-mic", "on")], lines)
    eyes._settled(("host-mic", "on"), "emulator-5556")
    eyes._STATE["serial"] = "emulator-5554"

    # The take's verdict (#161): the app's own outcome line, field by field, and the editor's WHOLE text
    # against the caller's literal. "Changed" is not "correct"; "contains" is not "equals".
    live = ("09-22 03:21:54.255 I PasteService: vice: insertion api=36 route=COMMIT written=true returned=VOID "
            "evidence=SURROUNDING outcome=VERIFIED attempts=1 ms=54 overrun=false target=com.google.android.gm")
    parsed = eyes._insertion_line(live)
    check("the live outcome line parses field by field",
          parsed == {"api": "36", "route": "COMMIT", "written": "true", "returned": "VOID", "evidence": "SURROUNDING",
                     "outcome": "VERIFIED", "attempts": "1", "ms": "54", "overrun": "false",
                     "target": "com.google.android.gm"}, parsed)
    check("a line no producer writes parses to nothing", eyes._insertion_line("Insertion completed via COMMIT") is None)
    good = {"insertion": parsed, "insertions": 1}
    check("a verified, written, right-target line is VERIFIED and names the route",
          any(l.startswith("VERIFIED: route=COMMIT") for l in eyes._judge_insertion(good, "com.google.android.gm")))
    check("a wanted route that differs is an ISSUE",
          any("route=COMMIT (wanted PASTE)" in l for l in eyes._judge_insertion(good, "com.google.android.gm", "PASTE")))
    for field, value in (("outcome", "UNVERIFIED"), ("written", "false"), ("target", "com.other.app")):
        bad = {"insertion": dict(parsed, **{field: value}), "insertions": 1}
        check(f"{field}={value} is an ISSUE, never VERIFIED",
              any(l.startswith("ISSUE:") and f"{field}=" in l for l in eyes._judge_insertion(bad, "com.google.android.gm"))
              and not any(l.startswith("VERIFIED") for l in eyes._judge_insertion(bad, "com.google.android.gm")))
    two = {"insertion": parsed, "insertions": 2}
    check("two outcome lines in the window is an ISSUE, not a pick",
          any("2 insertion outcome lines" in l for l in eyes._judge_insertion(two, "com.google.android.gm")))
    check("no outcome line is an ISSUE",
          any(l.startswith("ISSUE:") for l in eyes._judge_insertion({"insertion": None, "insertions": 0}, None)))

    # The editor's whole text against the literal, read back by IDENTITY, focus a note only.
    before = ("com.google.android.gm", "editor", "Hi. ")
    original_by_identity, original_focused = eyes._field_by_identity, eyes._focused_field
    eyes._focused_field = lambda package: None
    for text, verdict in (("Hi. And I will send the deck tomorrow. ", "VERIFIED"),
                          ("Hi. And I will send the deck tomorrow. And I will send the deck tomorrow. ", "ISSUE"),
                          ("Hi. And I will send the deck tomorr", "ISSUE"),
                          ("Hi. and I will send the deck tomorrow. ", "ISSUE"),
                          ("Hi. ", "ISSUE")):
        eyes._field_by_identity = lambda package, identity, _text=text: (package, identity, _text)
        lines = eyes._judge_editor(before, "com.google.android.gm", "Hi. And I will send the deck tomorrow. ")
        check(f"whole text {text!r} is {verdict}", lines and lines[0].startswith(verdict), lines)
    check("focus elsewhere after the take is a note, not the verdict",
          "not part of the verdict" in eyes._judge_editor(before, "com.google.android.gm", "Hi. And I will send the deck tomorrow. ")[0])
    eyes._field_by_identity = lambda package, identity: None
    check("an editor that vanished is an ISSUE naming it",
          "no longer on screen" in eyes._judge_editor(before, "com.google.android.gm", "x")[0])
    eyes._field_by_identity, eyes._focused_field = original_by_identity, original_focused
    check("dictate_emulator without the literal is BLOCKED before acting",
          eyes.dictate_emulator("hello", expected_final=None)[0].startswith("BLOCKED: expected_final is required")
          if eyes.is_emulator("emulator-5554") else True)

    # bound(): the whole block and the exact component (#161 finding 10).
    dump_ours_second = ("     Bound services:{Service[label=TalkBack, feedbackType[FEEDBACK_SPOKEN]], "
                        "Service[label=EnviousWispr, feedbackType[FEEDBACK_GENERIC], capabilities=1]}\n"
                        "     Enabled services:{{com.google.talkback/.TalkBackService}, "
                        "{com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService}}\n"
                        "     Binding services:{}\n")
    dump_label_only = ("     Bound services:{Service[label=EnviousWispr, feedbackType[FEEDBACK_GENERIC]]}\n"
                       "     Enabled services:{{com.other/.Service}}\n")
    dump_absent = "     Bound services:{}\n     Enabled services:{}\n"
    original_bound_adb = eyes._adb
    for dump, expected, name in ((dump_ours_second, True, "ours listed second is bound"),
                                 (dump_label_only, False, "the label without our component is not bound"),
                                 (dump_absent, False, "an empty block is not bound")):
        eyes._adb = lambda command, timeout=60, check=True, serial=None, _d=dump: (0, _d)
        check(name, eyes.bound() is expected)
    eyes._adb = lambda command, timeout=60, check=True, serial=None: (0, "no such line\n")
    try:
        eyes.bound()
        check("a dump with no bound-services line is a refusal", False)
    except eyes.Blocked:
        check("a dump with no bound-services line is a refusal", True)
    eyes._adb = original_bound_adb

    # The book is keyed by hardware identity, and a cable-keyed debt migrates (#161 finding 11).
    migrate_book = Path(".test-migrate-journal.json")
    if migrate_book.exists():
        migrate_book.unlink()
    original_journal_m, original_devices_m, original_run_m = eyes._JOURNAL, eyes.devices, eyes._run
    eyes._JOURNAL = migrate_book
    eyes.devices = lambda: [("usb-ABC", "SM_S948U1"), ("10.0.0.9:5555", "SM_S948U1")]
    eyes._run = lambda args, timeout=60: (0, "HW-PHONE\n", "") if args[-1] == "getprop ro.serialno" else (1, "", "")
    eyes._STATE["identities"].pop("usb-ABC", None)
    eyes._STATE["identities"].pop("10.0.0.9:5555", None)
    migrate_book.write_text(json.dumps({"usb-ABC": [["screen-timeout", "600000"]],
                                        "10.0.0.9:5555": [["media-volume", "8"], ["screen-timeout", "1800000"]]}))
    eyes._STATE["serial"] = "10.0.0.9:5555"
    owed = eyes._owed("10.0.0.9:5555")
    book_now = json.loads(migrate_book.read_text())
    check("both cable-keyed books moved under the hardware identity, first value per setting kept",
          owed == [("media-volume", "8"), ("screen-timeout", "1800000")] or owed == [("screen-timeout", "600000"), ("media-volume", "8")],
          owed)
    check("and the cable keys are gone from the book, nothing dropped",
          set(book_now) == {"HW-PHONE"} and len(book_now["HW-PHONE"]) == 2, book_now)
    check("a debt owed through the other cable reads the same book", eyes._owed("usb-ABC") == owed)
    check("a transport that is not attached keeps its own key", eyes._scope_key("emulator-5556") == "emulator-5556")
    eyes._JOURNAL, eyes.devices, eyes._run = original_journal_m, original_devices_m, original_run_m
    eyes._STATE["serial"] = "emulator-5554"
    migrate_book.unlink()
    lock = Path(str(migrate_book).replace(".json", ".lock"))
    if lock.exists():
        lock.unlink()

    # Audio goes in only while a take is listening, as timestamped packets from the ONE builder.
    pcm = Path(_tempfile.mkdtemp()) / "utt.pcm"
    pcm.write_bytes(bytes(9600 * 2 + 100))
    count, seconds = eyes._audio_packets(str(pcm), str(pcm) + ".packets.jsonl")
    packets = [json.loads(l) for l in open(str(pcm) + ".packets.jsonl")]
    check("the packet builder chunks 100 ms at a time and keeps the tail", count == 3 and len(packets) == 3)
    stamps = [pk["timestamp"] for pk in packets]
    check("every packet carries its format and an epoch timestamp 100 ms after the last",
          all("format" in pk for pk in packets) and stamps[0] > 1_600_000_000_000_000
          and [b - a for a, b in zip(stamps, stamps[1:])] == [100000, 100000], stamps)
    check("the audio length is computed from the bytes", abs(seconds - (9600 * 2 + 100) / 96000) < 1e-9)
    eyes.recording = lambda: False
    try:
        eyes.inject_audio(str(pcm))
        check("injection refuses while no take is listening", False, "it returned")
    except eyes.Blocked as refusal:
        check("injection refuses while no take is listening", "no take is listening" in str(refusal))
    eyes.recording = lambda: True
    grpc_calls.clear()
    check("injection streams the packets file into injectAudio",
          "injected 3 packets" in eyes.inject_audio(str(pcm))
          and any(m == "injectAudio" and stdin for m, _, stdin in grpc_calls))
    eyes.recording = original_recording
    # The gRPC endpoint being down is a refusal that names the launch call.
    def down_grpc(method, payload=None, stdin_path=None, timeout=120):
        raise eyes.Blocked(f"the emulator refused {method} over gRPC at localhost:8554: connection refused. "
                           "Was it launched with -grpc 8554? launch_emulator() does that.")
    eyes._grpc = down_grpc
    try:
        eyes.inject_audio(str(pcm))
        check("a closed gRPC port names launch_emulator()", False, "it returned")
    except eyes.Blocked as refusal:
        check("a closed gRPC port names launch_emulator()", "launch_emulator()" in str(refusal))
    eyes._grpc = emu_grpc

    # The recording lock: the phone is refused exactly as before; the emulator reaches the start intent.
    starts = []

    def take_adb(command, timeout=60, check=True, serial=None):
        if command.startswith("am start -n") and "VoiceInputActivity" in command and "--ez" not in command:
            starts.append(command)
            return 0, "Starting: Intent\n"
        if "isKeyguardShowing" in command:
            return 0, "isKeyguardShowing=false\n"
        if command.startswith("dumpsys power"):
            return 0, "mWakefulness=Awake\n"
        if command.startswith("settings get secure"):
            return 0, "com.envi.wispr/.paste.AutoPasteService\n" if "enabled_accessibility_services" in command else "1\n"
        if command.startswith("dumpsys accessibility"):
            return 0, "com.envi.wispr/.paste.AutoPasteService bound\n"
        if command.startswith("logcat -d"):
            return 0, "I/AudioCapture(1): recording_start [+0ms]\nI/AudioCapture(1): recording_stop [+5ms]\n"
        return emu_adb(command, timeout, check, serial)

    eyes._adb = take_adb
    eyes.bound = lambda: True
    eyes._STATE["serial"] = "100.94.206.47:5555"
    eyes._STATE["restored_for"] = "100.94.206.47:5555"
    try:
        with eyes.open_recorder(verify=False):
            pass
        check("a take on the PHONE is still refused by the recording lock", False, "it started")
    except eyes.Blocked as refusal:
        check("a take on the PHONE is still refused by the recording lock",
              "recording from this harness is off" in str(refusal) and starts == [], (refusal, starts))
    eyes._STATE["serial"] = "emulator-5554"
    eyes._STATE["restored_for"] = "emulator-5554"
    try:
        with eyes.open_recorder(verify=False):
            pass
    except eyes.Blocked as refusal:
        check("a take on the EMULATOR reaches the start intent", False, refusal)
    else:
        check("a take on the EMULATOR reaches the start intent", len(starts) == 1, starts)
    check("and the take debt is settled once the block ends", eyes._owed("emulator-5554") == [])
    eyes.bound = original_bound
    eyes._run, eyes._grpc, eyes._adb, eyes._JOURNAL = original_run, original_grpc, original_adb, original_journal
    eyes.devices, eyes._screen_size = original_devices, original_screen
    eyes._STATE["serial"] = None
    eyes._STATE["restored_for"] = None

    # ---- the emulator is spoken to through the cable, never the speakers ---------------------------
    # Founder 2026-09-13: the speaker-to-microphone path was the flaky half of every emulator take.
    # These rows pin the shape of a cable take: input switched to the cable BEFORE the voice, the
    # voice played INTO the cable, the input put back and read back AFTER, no speaker volume touched.
    import tempfile as _tempfile
    cable_book = Path(_tempfile.mkdtemp()) / "restore.json"
    original_journal, original_has_tool = eyes._JOURNAL, eyes._has_tool
    eyes._JOURNAL = cable_book
    eyes._STATE["serial"] = "emulator-5554"
    calls = []
    mac_input = ["MacBook Pro Microphone"]

    def cable_run(args, timeout=60):
        calls.append(args)
        if args[0] == eyes.SWITCH_AUDIO:
            if args[1:] == ["-a", "-t", "input"]:
                return (0, "BlackHole 2ch\nMacBook Pro Microphone\n", "")
            if args[1:3] == ["-c", "-t"]:
                return (0, mac_input[0] + "\n", "")
            if args[1:4] == ["-t", "input", "-s"]:
                mac_input[0] = args[4]
                return (0, "", "")
        if args[0] == "say":
            return (0, "", "")
        if args[0] == "osascript":
            return (0, "35\n", "")
        if args[-1] == "getprop ro.kernel.qemu":
            return (0, "1\n", "")
        return original_run(args, timeout)

    mic_calls = []
    cable_mic = {"on": False}

    def cable_grpc(method, payload=None, stdin_path=None, timeout=120):
        mic_calls.append((method, payload))
        if method == "setMicrophoneState":
            cable_mic["on"] = bool(payload["realAudioEnabled"])
        return {"realAudioEnabled": True} if (method == "getMicrophoneState" and cable_mic["on"]) else {}

    eyes._grpc = cable_grpc
    eyes._run = cable_run
    eyes._has_tool = lambda name: True
    eyes.say("hello there")
    said = [c for c in calls if c[0] == "say"]
    switches = [c[4] for c in calls if c[0] == eyes.SWITCH_AUDIO and c[1:4] == ["-t", "input", "-s"]]
    check("on an emulator the voice is played into the cable, not the speakers",
          len(said) == 1 and said[0][said[0].index("-a") + 1] == eyes.CABLE and "hello there" in said[0])
    check("the Mac's input is switched to the cable before the voice and put back after",
          switches == [eyes.CABLE, "MacBook Pro Microphone"] and mac_input[0] == "MacBook Pro Microphone")
    check("the input switch is settled in the book once it is back",
          eyes._owed("host") == [])
    check("no speaker volume is touched on a cable take",
          not any(c[0] == "osascript" and "set volume" in " ".join(c) for c in calls))
    check("the emulator's host microphone is switched on first, over gRPC",
          ("setMicrophoneState", {"realAudioEnabled": True}) in mic_calls, mic_calls)
    check("and rests OFF again once the cable take is over, with nothing owed",
          cable_mic["on"] is False and eyes._owed("emulator-5554") == [], (cable_mic, eyes._owed("emulator-5554")))
    eyes._has_tool = lambda name: False
    try:
        eyes.say("hello there")
        check("a Mac without the audio switcher is refused with the install line", False, "it returned")
    except eyes.Blocked as refusal:
        check("a Mac without the audio switcher is refused with the install line", "switchaudio-osx" in str(refusal))
    eyes._has_tool = lambda name: True
    eyes._STATE["serial"] = "100.94.206.47:5555"
    try:
        eyes.say_into_emulator("hello there")
        check("a phone is refused by say_into_emulator", False, "it returned")
    except eyes.Blocked:
        check("a phone is refused by say_into_emulator", True)
    eyes._run = original_run
    eyes._grpc = original_grpc
    eyes._has_tool = original_has_tool
    eyes._JOURNAL = original_journal
    eyes._STATE["serial"] = None

    # ---- the fast eye (#181): the app's own dump receiver first, uiautomator as the fallback -----------
    # Every row drives the real `tree()` with adb faked: the broadcast's result code is the contract.
    import base64 as _b64
    eye_book = Path(_tempfile.mkdtemp()) / "restore.json"
    original_journal_eye, original_adb_eye = eyes._JOURNAL, eyes._adb
    eyes._JOURNAL = eye_book
    eye_calls = []
    eye = {"result": 1, "data": _b64.b64encode(TWO_REMOVES.encode()).decode(), "raise": False}

    def eye_adb(command, timeout=60, check=True, serial=None):
        if command.startswith("am broadcast"):
            eye_calls.append("broadcast")
            if eye["raise"]:
                raise eyes.Blocked("adb could not reach the device")
            data = f', data="{eye["data"]}"' if eye["data"] is not None else ""
            return 0, f"Broadcasting: Intent\nBroadcast completed: result={eye['result']}{data}\n"
        if command.startswith("uiautomator dump"):
            eye_calls.append("uiautomator")
            return 0, ""
        if command.startswith("cat "):
            return 0, TWO_REMOVES
        return 0, ""

    eyes._adb = eye_adb
    eyes._STATE.update({"serial": "emulator-5554", "eye": None, "eye_retry_after": 0, "tree": None})
    nodes = eyes.tree()
    check("a receiver answering 1 + data is the fast eye, and uiautomator is not run",
          len(nodes) == 7 and eye_calls == ["broadcast"] and eyes._STATE["eye"] == "fast", eye_calls)
    eye_calls.clear()
    eye["result"], eye["data"] = 0, None
    eyes._STATE["eye"] = None
    eyes.tree()
    check("nobody answering (a release build) is slow for the rest of the process",
          eye_calls == ["broadcast", "uiautomator"] and eyes._STATE["eye"] == "slow", eye_calls)
    eye_calls.clear()
    eyes.tree()
    check("and the broadcast is not sent again in that process", eye_calls == ["uiautomator"], eye_calls)
    # A transient refusal (unbound) is slow for this read and retried after the cooldown.
    eye_calls.clear()
    eye["result"], eye["data"] = 3, None
    eyes._STATE.update({"eye": None, "eye_retry_after": 0})
    eyes.tree()
    check("an unbound service is slow for this read", eye_calls == ["broadcast", "uiautomator"], eye_calls)
    eye["result"], eye["data"] = 1, _b64.b64encode(TWO_REMOVES.encode()).decode()
    eye_calls.clear()
    for _ in range(eyes.EYE_COOLDOWN_READS):
        eyes.tree()
    check("the next three reads stay slow without asking", eye_calls == ["uiautomator"] * 3, eye_calls)
    eye_calls.clear()
    eyes.tree()
    check("and the fourth asks again and is fast", eye_calls == ["broadcast"] and eyes._STATE["eye"] == "fast", eye_calls)
    # Too big and a broken transport behave the same way.
    for code, label in ((2, "a tree over the cap"),):
        eye["result"], eye["data"] = code, None
        eyes._STATE["eye_retry_after"] = 0
        eye_calls.clear()
        eyes.tree()
        check(f"{label} is slow for this read with a cooldown",
              eye_calls == ["broadcast", "uiautomator"] and eyes._STATE["eye_retry_after"] == eyes.EYE_COOLDOWN_READS, eye_calls)
    eye["raise"] = True
    eyes._STATE["eye_retry_after"] = 0
    eye_calls.clear()
    eyes.tree()
    check("an adb failure on the broadcast is slow for this read with a cooldown",
          eye_calls == ["broadcast", "uiautomator"] and eyes._STATE["eye_retry_after"] == eyes.EYE_COOLDOWN_READS, eye_calls)
    eye["raise"] = False
    # Undecodable data is a refusal ONCE, naming the eye, then slow.
    eye["result"], eye["data"] = 1, "not base64 at all !!!"
    eyes._STATE["eye_retry_after"] = 0
    eye_calls.clear()
    try:
        eyes.tree()
        check("undecodable data is a refusal that names the eye", False, "it returned")
    except eyes.Blocked as refusal:
        check("undecodable data is a refusal that names the eye", "dump receiver" in str(refusal), refusal)
    eye_calls.clear()
    eyes.tree()
    check("and the read after it is slow, without raising", eye_calls == ["uiautomator"], eye_calls)
    eyes._adb = original_adb_eye
    eyes._STATE.update({"serial": None, "eye": None, "eye_retry_after": 0, "tree": None})

    # The eye belongs to the device: selecting another device forgets the answer.
    eyes._STATE.update({"serial": "100.94.206.47:5555", "eye": "slow", "eye_retry_after": 2})
    eyes._switch_to("emulator-5554")
    check("switching device resets the eye so the new device is probed",
          eyes._STATE["eye"] is None and eyes._STATE["eye_retry_after"] == 0)
    eyes._STATE["eye"] = "fast"
    with eyes._as_serial("emulator-5556"):
        check("a restore on another device starts with its own unprobed eye", eyes._STATE["eye"] is None)
        eyes._STATE["eye"] = "slow"
    check("and the selected device's eye comes back afterwards", eyes._STATE["eye"] == "fast")
    eyes._STATE.update({"serial": None, "eye": None, "eye_retry_after": 0})

    # ---- a refusal describes the screen that FAILED, not the one a second read finds ------------------
    reads = []
    screens = iter([EXACT_VS_SUBSTRING, NOTHING_OF_OURS, NOTHING_OF_OURS])

    def changing_adb(command, timeout=60, check=True, serial=None):
        if command.startswith("uiautomator dump"):
            reads.append(1)
            return 0, ""
        if command.startswith("cat "):
            return 0, next(screens)
        if command.startswith("am broadcast"):
            return 0, "Broadcast completed: result=0\n"
        return 0, ""

    eyes._adb = changing_adb
    eyes._STATE.update({"serial": "fixture", "eye": "slow", "tree": None})
    try:
        eyes.find("Bluetooth pairing")
        check("an absent query on a changing screen still refuses", False, "it found something")
    except eyes.Blocked as refusal:
        check("the refusal lists the screen of the read that failed, from one read",
              "Remove all models" in str(refusal) and "nexuslauncher" not in str(refusal) and len(reads) == 1,
              (str(refusal)[:120], len(reads)))
    eyes._adb = original_adb_eye
    eyes._STATE.update({"serial": None, "eye": None, "tree": None})

    # ---- a switch is put back IN PLACE when its screen is already up, and by navigating when not -----
    # Navigation helpers are SPIED: the row is red if any of them is invoked on the in-place path.
    spied = {name: 0 for name in ("open_tab", "open_settings", "nav")}
    originals = {name: getattr(eyes, name) for name in spied}
    for name in spied:
        setattr(eyes, name, (lambda n: (lambda *a, **k: spied.__setitem__(n, spied[n] + 1)))(name))
    original_on_screen, original_reveal, original_switch, original_tap = eyes.on_screen, eyes.reveal, eyes.switch, eyes.tap
    original_present = eyes.present
    place = {"on_screen": True, "present": True, "state": [False, True, True]}
    eyes.on_screen = lambda where: place["on_screen"]
    eyes.present = lambda text, exact=False: place["present"]
    eyes.reveal = lambda label, package=eyes.PACKAGE: True
    eyes.switch = lambda label, package=eyes.PACKAGE: place["state"].pop(0) if len(place["state"]) > 1 else place["state"][0]
    taps = []
    eyes.tap = lambda text, **k: taps.append(text) or "pressed"
    eyes._STATE["serial"] = "fixture"
    debt = ("switch", json.dumps({"where": "Transcription", "label": "Spoken emoji", "was": True}, sort_keys=True))
    eyes._owe(debt)
    eyes._restore_one(debt)
    check("a switch whose screen is up is put back without navigating",
          sum(spied.values()) == 0 and taps == ["Spoken emoji"], (spied, taps))
    eyes._settled(debt)
    place.update({"on_screen": False, "present": True, "state": [False, True, True]})
    taps.clear()
    eyes._owe(debt)
    eyes._restore_one(debt)
    check("the label alone, on another screen, is not enough: the tab is opened",
          spied["open_tab"] == 1 and taps == ["Spoken emoji"], (spied, taps))
    eyes._settled(debt)
    for name, fn in originals.items():
        setattr(eyes, name, fn)
    eyes.on_screen, eyes.reveal, eyes.switch, eyes.tap, eyes.present = original_on_screen, original_reveal, original_switch, original_tap, original_present
    eyes._STATE["serial"] = None

    # A slow read is its own settle: one dump, not two.
    reads = []
    eyes._adb = lambda command, timeout=60, check=True, serial=None: (reads.append(command[:12]), (0, TWO_REMOVES if command.startswith("cat ") else ""))[1]
    eyes._STATE.update({"serial": "fixture", "eye": "slow", "tree": None})
    eyes._stable_tree()
    check("a stable read through uiautomator is ONE dump", reads.count("uiautomator ") == 1, reads)
    eyes._adb = original_adb_eye
    eyes._STATE.update({"serial": None, "eye": None, "tree": None})

    # The resting-state helper settles only a debt whose destination is off.
    mic = {"on": True}
    original_mic_state, original_grpc2 = eyes._mic_state, eyes._grpc
    eyes._mic_state = lambda: mic["on"]
    eyes._grpc = lambda method, payload=None, stdin_path=None, timeout=120: mic.__setitem__("on", payload["realAudioEnabled"]) if method == "setMicrophoneState" else {}
    eyes._STATE["serial"] = "emulator-5554"
    eyes._owe(("host-mic", "on"))
    check("resting the mic off keeps a debt that owes ON",
          "off" in eyes._rest_host_mic_off() and eyes._owed("emulator-5554") == [("host-mic", "on")] and mic["on"] is False,
          eyes._owed("emulator-5554"))
    eyes._settled(("host-mic", "on"))
    mic["on"] = True
    eyes._owe(("host-mic", "off"))
    check("and settles a debt whose destination is OFF, now moot",
          "off" in eyes._rest_host_mic_off() and eyes._owed("emulator-5554") == [] and mic["on"] is False,
          eyes._owed("emulator-5554"))
    eyes._mic_state, eyes._grpc = original_mic_state, original_grpc2
    eyes._STATE["serial"] = None

    # ---- the switch settle waits for the WANTED state twice, and gives up on time -------------------
    readings = iter([False, True, True])
    eyes.switch = lambda label, package=eyes.PACKAGE: next(readings)
    clock = {"t": 0.0}
    real_monotonic, real_sleep2 = eyes.time.monotonic, eyes.time.sleep
    eyes.time.monotonic = lambda: clock["t"]
    eyes.time.sleep = lambda s: clock.__setitem__("t", clock["t"] + s)
    check("the settle returns once the wanted state is read twice in a row",
          eyes._switch_settled("x", True) is True and clock["t"] < 0.6, clock["t"])
    readings = iter([False] * 50)
    clock["t"] = 0.0
    check("and gives up after the bound when it never arrives",
          eyes._switch_settled("x", True) is False and 0.6 <= clock["t"] <= 0.8, clock["t"])
    eyes.time.monotonic, eyes.time.sleep = real_monotonic, real_sleep2
    eyes.switch = original_switch
    eyes._JOURNAL = original_journal_eye

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
    # next. **AND THIS ROW HAS TO ACTUALLY CROSS A PROCESS BOUNDARY.** It used to import a second copy
    # of the module inside THIS process and read it back — which passes whether the book is a file or a
    # module-level list, so it never tested the thing its name promised. It runs a real interpreter now.
    eyes._owe(("screen-timeout", "600000"))
    import subprocess as _sp
    elsewhere = _sp.run(
        [sys.executable, "-c",
         "import sys, pathlib;"
         f"sys.path.insert(0, {str(Path(__file__).parent)!r});"
         "import wispr_eyes as w;"
         f"w._JOURNAL = pathlib.Path({str(book)!r});"
         "print(w._owed('fixture'))"],
        capture_output=True, text=True, timeout=60)
    check("a change survives into a REAL next process",
          "screen-timeout" in elsewhere.stdout and "600000" in elsewhere.stdout,
          elsewhere.stdout.strip() or elsewhere.stderr.strip()[:200])

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
    # `dictate_emulator` carries the same NOT RUN branch, but it cannot be reached here: `_require_emulator`
    # refuses a phone first, and on an emulator the gate is open, so the row covers the two suites a
    # phone can reach.
    for name, call in (("check_recorder", eyes.check_recorder),
                       ("room_is_quiet", eyes.room_is_quiet)):
        answer = call()
        check(f"{name} reports it as NOT RUN rather than raising (#161 finding 12)",
              isinstance(answer, list) and answer and answer[0].startswith("NOT RUN:"), answer)

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

    # A change owed to ANOTHER phone is never restored onto this one. Since #177 the debt is NAMED as
    # kept rather than passed over in silence, and it stays in the book.
    eyes._owe(("screen-timeout", "15000"), serial="some-other-phone")
    said = eyes.restore()
    check("another phone's change is left alone, and said so",
          said == ["some-other-phone: not attached, debt kept"]
          and eyes._owed("some-other-phone") == [("screen-timeout", "15000")], said)
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

    # ---- the two changes that record NOTHING, and are stopped rather than journaled ---------------
    # Enumerating every function that changes something (not only the ones already journaled) turned up
    # two that could alter something of the founder's with no record: a screenshot over a file he named,
    # and an accessibility write from a caller that had not taken the book.
    import tempfile as _tf
    existing = Path(_tf.mkdtemp()) / "his-file.png"
    existing.write_text("something of his")
    try:
        eyes.shot(str(existing))
        check("a screenshot refuses to write over a file", False, "it overwrote it")
    except eyes.Blocked as refusal:
        check("a screenshot refuses to write over a file", "already exists" in str(refusal), refusal)
    check("and the file is untouched", existing.read_text() == "something of his")

    eyes._STATE["holding_journal"] = False
    try:
        eyes._put_a11y({"enabled_accessibility_services": "x", "accessibility_enabled": "1"})
        check("accessibility cannot be changed without the book", False, "it wrote them")
    except eyes.Blocked as refusal:
        check("accessibility cannot be changed without the book",
              "holding the restore book" in str(refusal), refusal)

    # ---- THE SWEEP, ENUMERATED FROM THE CODE ITSELF ----------------------------------------------
    # Six review rounds each found ONE function that wrote a debt and then made its change outside the
    # book's lock, so another session could settle the debt in between and leave the founder's phone
    # changed with nothing recording it. Six rounds, six functions, because each round was handed an
    # instance instead of the set.
    #
    # **THE FIRST VERSION OF THIS ROW WAS WEAKER THAN ITS OWN NAME.** It looked only at top-level
    # functions, matched the word `_journal_locked()` anywhere in the source INCLUDING COMMENTS, and
    # ignored `_owe_locked` and `_settled_locked` callers entirely — so it passed while claiming to make
    # the class impossible to reopen. A test that claims a mechanism and does not have it is worse than
    # no test, because it stops the next person looking.
    #
    # This version walks EVERY function at any nesting depth, including methods, decides "is it inside
    # the lock" from the syntax tree rather than from text, and covers the locked helpers too.
    import ast
    source = (Path(__file__).parent / "wispr_eyes.py").read_text()
    module = ast.parse(source)
    TOUCHES = {"_owe", "_settled", "_owe_locked", "_settled_locked"}

    def unguarded_calls(function):
        """Every debt call in this function that is NOT inside a `with _journal_locked()`.

        **IT WALKS, RATHER THAN COLLECTING NAMES.** The first version gathered the names called inside
        any lock and the names called anywhere, and compared the two sets — so one `_owe` inside a lock
        made every other `_owe` in the function count as protected. An unlocked call could hide behind a
        locked one, which is precisely the defect this row exists to find.

        **A LAMBDA OR A GENERATOR EXPRESSION RUNS LATER**, possibly after the lock is gone, so anything
        inside one starts again as unheld however deep in a `with` it was written.
        """
        bad = []

        def visit(node, held):
            # A nested definition is its own function and is judged on its own.
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                return
            if isinstance(node, (ast.Lambda, ast.GeneratorExp)):
                # Deferred: whatever is inside runs when it is CONSUMED, which may be outside the lock.
                for child in ast.iter_child_nodes(node):
                    visit(child, False)
                return
            if isinstance(node, ast.With):
                active = held
                for item in node.items:
                    visit(item.context_expr, active)
                    expr = item.context_expr
                    if (isinstance(expr, ast.Call) and isinstance(expr.func, ast.Name)
                            and expr.func.id == "_journal_locked"):
                        active = True
                for statement in node.body:
                    visit(statement, active)
                return
            if isinstance(node, ast.Call):
                target = node.func
                name = (target.id if isinstance(target, ast.Name)
                        else target.attr if isinstance(target, ast.Attribute) else None)
                # `getattr(module, "_owe")(...)` is the same call wearing a disguise.
                if (isinstance(target, ast.Call) and isinstance(target.func, ast.Name)
                        and target.func.id == "getattr" and len(target.args) >= 2
                        and isinstance(target.args[1], ast.Constant)):
                    name = target.args[1].value
                if name in TOUCHES and not held:
                    bad.append(node.lineno)
            for child in ast.iter_child_nodes(node):
                visit(child, held)

        for statement in function.body:
            visit(statement, False)
        return bad

    def needs_lock_review(node):
        """THE ONE DECISION, used by the sweep AND by every control beside it.

        The controls used to recompute this themselves, so changing the real rule left them green while
        the sweep started accepting the very thing they were named for. A control that does not share
        the decision it guards is not a control.
        """
        if node.name.endswith("_locked") or node.name in ("_owe", "_settled", "_atomic_change", "wrapped"):
            return False
        decorators = [getattr(d, "id", "") for d in node.decorator_list]
        # A DECORATOR CANNOT HOLD A LOCK ACROSS A `yield`, a lambda or a generator: it wraps the call
        # that BUILDS them, so the lock is gone before the body runs. Only an exact, known decorator
        # stack is credited; anything else might defer too.
        deferred = isinstance(node, ast.AsyncFunctionDef) or any(
            isinstance(n, (ast.Yield, ast.YieldFrom, ast.GeneratorExp, ast.Lambda))
            for n in ast.walk(node))
        if decorators == ["_atomic_change"] and not deferred:
            return False
        return bool(unguarded_calls(node))

    unlocked = [node.name for node in ast.walk(module)
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
                and needs_lock_review(node)]
    check("recognised debt calls in non-exempt functions sit inside the book's lock", not unlocked, unlocked)

    # THE CONTROLS, all through the SAME decision, so changing the rule moves them together.
    def one(text):
        return ast.parse(text).body[0]

    for name, snippet, should_flag in (
        ("an unlocked call cannot hide behind a locked one",
         "def mixed():\n    with _journal_locked():\n        _owe(('a','b'))\n    _owe(('c','d'))\n", True),
        ("a comment mentioning the lock does not count",
         "def sneaky():\n    # with _journal_locked()\n    _owe(('x','y'))\n", True),
        # ONE decorator, deliberately. With `@contextmanager` under it as well, the stack alone is
        # unrecognised and the row passed without the rule ever having to notice a generator — so
        # deleting the deferred-work check left it green.
        ("a decorator on a generator does not count as holding the lock",
         "@_atomic_change\ndef a_take():\n    _owe(('t','x'))\n    yield\n", True),
        ("a debt reached through getattr is still seen",
         "def sly():\n    getattr(eyes, '_owe')(('a','b'))\n", True),
        ("a debt inside a generator expression is not covered by the lock around it",
         "def later():\n    with _journal_locked():\n        return (_owe(('a','b')) for _ in [1])\n", True),
        ("and an ordinary locked function is accepted",
         "def fine():\n    with _journal_locked():\n        _owe(('a','b'))\n", False),
        ("and a plain decorated function is accepted",
         "@_atomic_change\ndef also_fine():\n    _owe(('a','b'))\n", False),
    ):
        check(name, needs_lock_review(one(snippet)) is should_flag)

    # ---- the launcher presses during a live take (#192) prove they began no take ----------------------
    # `_press_launcher` is stubbed at its four seams: recording(), logs() (the newest recording_start line),
    # _adb (the press) and _dictation (the cancel). The proof is the identity of the newest start line.
    originals = {name: getattr(eyes, name) for name in ("recording", "logs", "_adb", "_dictation")}
    world = {"recording": True, "tail": ["09-21 11:45:53.000 I AudioCapture: recording_start [+0ms]"], "presses": [], "cancels": []}
    eyes.recording = lambda: world["recording"]
    eyes.logs = lambda pattern=None, lines=200: "\n".join(world["tail"][-lines:])
    eyes._adb = lambda command, timeout=60, check=True, serial=None: (world["presses"].append(command), (0, ""))[1]
    eyes._dictation = lambda what: world["cancels"].append(what)
    eyes._STATE["serial"] = "fixture"
    # 1. no new start: the newest line is unchanged through the deadline
    eyes._press_launcher("toggle", "", deadline_s=1.0)
    check("a press that began no take returns quietly and cancels nothing",
          len(world["presses"]) == 1 and world["cancels"] == [], (world["presses"], world["cancels"]))
    # 2. a new start after the press: a different newest line, cancelled and refused
    world["presses"].clear()
    def press_and_start(command, timeout=60, check=True, serial=None):
        world["presses"].append(command)
        # The tail ROLLS: the old start line is gone and the new one takes its place, so the count stays at
        # one and only the identity changes. A count-based proof would read "no new take" here.
        world["tail"] = ["09-21 11:46:06.000 I AudioCapture: recording_start [+0ms]"]
        return (0, "")
    eyes._adb = press_and_start
    try:
        eyes._press_launcher("toggle", "", deadline_s=1.0)
        check("a press that began a take is refused", False, "no refusal")
    except eyes.Blocked as refusal:
        check("a press that began a take is refused after cancelling it",
              world["cancels"] == ["cancel"] and "began a new take" in str(refusal), (world["cancels"], str(refusal)[:80]))
    # 3. a rolling tail that lost every start line after the press: cannot tell, refused, nothing cancelled
    world.update({"tail": ["09-21 11:45:53.000 I AudioCapture: recording_start [+0ms]"], "presses": [], "cancels": []})
    def press_and_roll(command, timeout=60, check=True, serial=None):
        world["presses"].append(command)
        world["tail"] = ["09-21 11:46:07.000 I AudioCapture: something else"]
        return (0, "")
    eyes._adb = press_and_roll
    try:
        eyes._press_launcher("toggle", "", deadline_s=1.0)
        check("a tail with no start line after the press is refused", False, "no refusal")
    except eyes.Blocked as refusal:
        check("a tail with no start line after the press is 'cannot tell', refused without a cancel",
              world["cancels"] == [] and "cannot be told" in str(refusal), (world["cancels"], str(refusal)[:80]))
    # 4. nothing recording: no press is sent at all
    world.update({"recording": False, "presses": []})
    try:
        eyes._press_launcher("toggle", "", deadline_s=1.0)
        check("with nothing recording the press is refused", False, "no refusal")
    except eyes.Blocked:
        check("with nothing recording no intent is sent", world["presses"] == [], world["presses"])
    for name, fn in originals.items():
        setattr(eyes, name, fn)
    eyes._STATE["serial"] = None

    # ---- freeze / thaw / kill act on ONE verified pid or refuse (#115) ----
    originals = {name: getattr(eyes, name) for name in ("_adb", "_JOURNAL", "devices", "is_emulator", "_spawn_debugger", "_kill_debugger", "_process_answers", "_adb_host")}
    proc_book = Path(__file__).parent / ".test-proc-journal.json"
    if proc_book.exists():
        proc_book.unlink()
    eyes._JOURNAL = proc_book
    table = {"rows": [(100, "S", "com.envi.wispr"), (101, "S", "com.envi.wispr:audio")], "signals": [], "debuggers": [], "frozen": set(), "forwards": []}

    def proc_adb(command, timeout=60, check=True, serial=None):
        if command.startswith("ps -A"):
            return 0, "  PID S NAME\n" + "".join(f"{pid} {state} {name}\n" for pid, state, name in table["rows"])
        if command.startswith("run-as com.envi.wispr kill -9 "):
            table["signals"].append(command)
            pid = int(command.split()[-1])
            table["rows"] = [row for row in table["rows"] if row[0] != pid]
            return 0, ""
        return 0, ""

    def fake_spawn(port, log_path):
        table["debuggers"].append(port)
        table["frozen"].add(port)
        with open(log_path, "w") as f:
            f.write(eyes.JDB_SUSPENDED + "\n")
        return 4242

    def fake_kill(host_pid, port):
        table["frozen"].discard(port)
        return f"debugger pid {host_pid} ended"

    eyes._adb = proc_adb
    eyes._adb_host = lambda args: table["forwards"].append(tuple(args)) or ""
    eyes._spawn_debugger = fake_spawn
    eyes._kill_debugger = fake_kill
    eyes._process_answers = lambda pid, within_s=3.0: not table["frozen"]
    eyes.devices = lambda: [("emulator-5554", "sdk_gphone64_arm64")]
    eyes.is_emulator = lambda serial=None: True
    eyes._STATE["serial"] = "emulator-5554"
    try:
        eyes.freeze_process("com.envi.wispr:asr")
        check("freezing a name with NO process is refused", False, "it froze")
    except eyes.Blocked as refusal:
        check("freezing a name with NO process is refused", "0 processes" in str(refusal) and table["debuggers"] == [], str(refusal)[:80])
    table["rows"].append((102, "S", "com.envi.wispr:audio"))
    try:
        eyes.freeze_process("com.envi.wispr:audio")
        check("freezing a name with TWO processes is refused", False, "it froze")
    except eyes.Blocked as refusal:
        check("freezing a name with TWO processes is refused", "2 processes" in str(refusal) and table["debuggers"] == [], str(refusal)[:80])
    del table["rows"][-1]
    line = eyes.freeze_process("com.envi.wispr:audio")
    check("one match is frozen through a debugger on its own forwarded port", table["debuggers"] == [18700 + 101] and "pid 101" in line, (table["debuggers"], line))
    owed = eyes._owed("emulator-5554")
    check("and the thaw is owed in the book with the debugger's pid", len(owed) == 1 and owed[0][0] == "frozen-process" and json.loads(owed[0][1])["host_pid"] == 4242, owed)
    restored = eyes.restore()
    check("restore() ends the debugger and settles the debt", not table["frozen"] and eyes._owed("emulator-5554") == [], (table["frozen"], restored))
    eyes.freeze_process("com.envi.wispr:audio")
    line = eyes.kill_process("com.envi.wispr:audio")
    check("a kill goes to the one pid from the app's own uid", table["signals"] == ["run-as com.envi.wispr kill -9 101"] and len(table["rows"]) == 1, (table["signals"], line))
    restored = eyes.restore()
    check("and restore() finds the frozen pid gone and settles it", eyes._owed("emulator-5554") == [], restored)
    for name, fn in originals.items():
        setattr(eyes, name, fn)
    eyes._STATE["serial"] = None
    eyes._STATE["restored_for"] = None
    for leftover in (proc_book, Path(str(proc_book) + ".lock"), Path(proc_book).parent / ".test-proc-journal.lock", Path(proc_book).parent / "jdb-101.log"):
        if leftover.exists():
            leftover.unlink()

    print()
    print(f"{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("failed: " + ", ".join(FAILED))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
