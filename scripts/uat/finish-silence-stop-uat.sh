#!/usr/bin/env bash
# The one obligation left on issue #5: a silence-stopped take putting its words into a REAL editor.
#
#   scripts/uat/finish-silence-stop-uat.sh [device]
#
# Run it with the phone UNLOCKED. It refuses to run otherwise, because insertion is impossible on a
# locked phone: the accessibility service can see only the keyguard and no application window at all
# (device-testing.md FACT: insertion-is-IMPOSSIBLE-on-a-locked-phone-and-here-is-the-proof).
#
# It does everything except the judgement: it opens Gmail, starts a dictation the way the side button
# does, plays real speech, and waits for the take to end itself. YOU read the compose field.
set -uo pipefail
D="${1:-100.94.206.47:5555}"
ADB="adb -s $D"

LOCKED=$($ADB shell 'dumpsys deviceidle | grep -m1 mScreenLocked' 2>/dev/null | tr -d ' \r')
case "$LOCKED" in
  *mScreenLocked=true*)
    echo "REFUSING: the phone is locked, and insertion cannot happen on a locked phone."
    echo "Unlock it, leave it unlocked, and run this again."
    exit 2
    ;;
esac

BOUND=$($ADB shell 'dumpsys accessibility | grep -c "Bound services:{Service\[label=EnviousWispr"' 2>/dev/null | tr -d ' \r')
if [ "$BOUND" != "1" ]; then
  echo "The accessibility service is not bound. Rebinding, because installing over the app unbinds it"
  echo "and re-putting the same setting string does NOT rebind."
  $ADB shell 'settings put secure enabled_accessibility_services ""; settings put secure accessibility_enabled 0' >/dev/null
  sleep 2
  $ADB shell 'settings put secure enabled_accessibility_services com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService; settings put secure accessibility_enabled 1' >/dev/null
  sleep 3
  BOUND=$($ADB shell 'dumpsys accessibility | grep -c "Bound services:{Service\[label=EnviousWispr"' 2>/dev/null | tr -d ' \r')
  [ "$BOUND" = "1" ] || { echo "STILL NOT BOUND. Turn EnviousWispr on under Accessibility by hand."; exit 3; }
fi
echo "accessibility service bound"

echo
echo "TURN ON 'Stop recording on silence' under Transcription if you have not already."
$ADB shell 'am start -n com.envi.wispr/.ui.SettingsActivity' >/dev/null 2>&1
echo "Press return once the switch is on."
read -r _

echo "opening a Gmail compose window"
$ADB shell 'am start -a android.intent.action.SENDTO -d mailto: --es android.intent.extra.SUBJECT "EnviousWispr auto-stop test"' >/dev/null 2>&1
sleep 4
$ADB shell 'input tap 540 900' >/dev/null 2>&1   # focus the body
sleep 1

$ADB logcat -c
echo "starting a dictation the way the side button does"
$ADB shell 'am start -a android.intent.action.ASSIST' >/dev/null 2>&1
sleep 3

echo "playing real speech; do not touch the phone"
$ADB shell 'am start -n com.envi.wispr.test/com.envi.wispr.SpeakerPlaybackActivity' >/dev/null 2>&1

echo "waiting for the take to end BY ITSELF (no stop is sent)"
for i in $(seq 1 60); do
  if $ADB logcat -d -s 'AudioCapture:*' 2>/dev/null | grep -q "Stopped\."; then break; fi
  sleep 1
done

echo
echo "--- what the phone did ---"
$ADB logcat -d -v time -s 'AudioCapture:*' 'SilenceVad:*' 'DictationSession:*' 'PasteAccessibility:*' 2>/dev/null \
  | grep -iE "Detector ready|Stopped\.|Auto-stop|Insertion|Auto-insert|clipboard|showError" \
  | sed 's/^\(..-.. ..:..:..\.[0-9]*\).*: \(.*\)$/\1 \2/'

echo
echo "--- the only question that matters ---"
echo "Look at the Gmail compose field. Are the spoken words in it, and did you press nothing to stop?"
echo "If yes, the last obligation on #5 is met and PR #113 can merge."
