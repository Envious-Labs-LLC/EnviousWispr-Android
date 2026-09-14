#!/bin/bash
# One no-audio insertion take for synthetic UAT (#141). Fires the debug-only INSERT broadcast into the
# text field that is focused RIGHT NOW, then prints the insertion outcome. This exercises the real
# production insert path (pin the focused editor, then commit/paste) with a fixed sentence and no
# microphone or speech recognition, so it is deterministic and survives many back-to-back runs.
#
# Preconditions:
#   - The DEBUG build is installed on the emulator (release builds do not contain the receiver).
#   - The accessibility service is enabled and bound.
#   - A text field is focused (tap into it first). The caret position is wherever you left it, so this
#     is how the start / between-sentences / end-of-sentence / new-paragraph caret matrix is driven.
#
# Usage: scripts/uat/debug-insert.sh "sentence to insert" tag
# Note: the sentence must not contain a single quote (the broadcast value is single-quoted for adb).
set -u
A=~/Android/sdk/platform-tools/adb
D=emulator-5554
SENTENCE="${1:-The quarterly report is ready for review.}"
TAG="${2:-take}"
"$A" -s "$D" logcat -c
"$A" -s "$D" shell "am broadcast -a com.envi.wispr.debug.INSERT --es text '$SENTENCE' com.envi.wispr" >/dev/null 2>&1
sleep 4
echo "[$TAG] --- outcome ---"
"$A" -s "$D" logcat -d | grep -E "insertion api=|DebugInsert: pin=" | cut -c1-200 | tail -2
