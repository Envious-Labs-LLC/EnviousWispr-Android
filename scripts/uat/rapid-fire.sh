#!/bin/bash
# Stress test: fire N no-audio inserts back to back into the focused field with a short gap, to catch
# dropped or refused inserts when one insertion overlaps the previous one's verification window (#141).
# Preconditions: debug build, accessibility on, a field focused. $1 = count (default 5), $2 = gap seconds
# (default 1).
set -u
A=~/Android/sdk/platform-tools/adb
D=emulator-5554
N="${1:-5}"
GAP="${2:-1}"
"$A" -s "$D" logcat -c
for i in $(seq 1 "$N"); do
  "$A" -s "$D" shell "am broadcast -a com.envi.wispr.debug.INSERT --es text 'Rapid take number $i.' com.envi.wispr" >/dev/null 2>&1
  sleep "$GAP"
done
sleep 3
echo "--- outcomes ($N fired) ---"
"$A" -s "$D" logcat -d | grep -E "insertion api=|handoff=|already pending" | cut -c1-160
