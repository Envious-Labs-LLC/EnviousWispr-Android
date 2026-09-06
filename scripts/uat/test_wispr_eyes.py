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

PASSED, FAILED = [], []


def check(name, condition, detail=""):
    (PASSED if condition else FAILED).append(name)
    print(f"{'  ok  ' if condition else ' FAIL '} {name}{'' if condition else f'  <- {detail}'}")


def with_screen(xml):
    """Point the module at a fixture screen instead of a phone."""
    eyes._STATE["tree"] = None
    eyes._STATE["serial"] = "fixture"
    original = eyes._adb
    eyes._adb = lambda command, timeout=60, serial=None: (
        (0, xml) if command.startswith("cat ") else (0, "")
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
        check("and it says how to narrow it", "exact=True" in message, message)

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

    # ---- the recorder is read from the tree, which is the eye that can see it ---------------------
    restore_adb(original)
    original = with_screen(RECORDER)
    pill = eyes.overlay()
    check("the recorder is found", pill is not None)
    if pill:
        check("its timer is read", pill["timer"] == "0:04", pill["timer"])
        check("its state is read", pill["state"] == "LISTENING", pill["state"])
        check("its cancel control is located", pill["cancel"] == (835, 200), pill["cancel"])
        check("its stop control is located", pill["stop"] == (975, 200), pill["stop"])

    # A screen with no pill must answer None rather than half a pill.
    restore_adb(original)
    original = with_screen(TWO_REMOVES)
    check("no recorder means None, not a guess", eyes.overlay() is None, eyes.overlay())
    restore_adb(original)

    # ---- restore() reports honestly --------------------------------------------------------------
    eyes._STATE["restore"] = []
    eyes._adb = lambda command, timeout=60, serial=None: (0, "")
    check("with nothing changed it says so", eyes.restore() == ["nothing was changed"])

    print()
    print(f"{len(PASSED)} passed, {len(FAILED)} failed")
    if FAILED:
        print("failed: " + ", ".join(FAILED))
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())
