#!/bin/bash
# Deterministic BUG 1 detector for synthetic UAT (#141). Reports, in one line, the three signals that
# decide whether the record bubble SHOULD be showing and whether it IS:
#   focus   = the window that currently has input focus (which app/activity)
#   keyboard= whether a soft input (a real editable field) is shown right now
#   bubble  = whether the EnviousWispr record-controls overlay window is present
# The bug (BUG 1) is: keyboard=shown AND bubble=absent. A field is open but the bubble did not reappear.
# $1 = tag.
set -u
A=~/Android/sdk/platform-tools/adb
D=emulator-5554
TAG="${1:-sweep}"
FOCUS=$("$A" -s "$D" shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | sed 's/.*\///; s/}.*//')
KB=$("$A" -s "$D" shell dumpsys input_method 2>/dev/null | grep -m1 mInputShown | tr -d ' \r')
BUBBLE=$("$A" -s "$D" shell dumpsys window windows 2>/dev/null | grep -c "EnviousWispr recording controls")
if [ "$BUBBLE" -gt 0 ]; then BUB="present"; else BUB="ABSENT"; fi
echo "[$TAG] focus=$FOCUS keyboard=$KB bubble=$BUB"
