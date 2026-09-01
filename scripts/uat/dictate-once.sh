#!/usr/bin/env bash
# One end-to-end dictation on the phone: launcher toggle, the speech fixture through the speaker, stop,
# then wait for the handoff line. Writes the run's logcat to <outdir>/<label>.log and prints the timeline.
#
#   scripts/uat/dictate-once.sh <label> <outdir> [device] [record-seconds]   (default 12.5, the short fixture plus margin)
#
# Preconditions the caller checks (residency-campaign.sh does): Bluetooth off (a headset steals the speaker
# and the microphone hears nothing), screen awake and unlocked, both APKs from this build installed, the
# fixture present and non-empty in both packages' cache (phone-audio-playback.md).
set -uo pipefail
LABEL="$1"; OUT="$2"; D="${3:-100.94.206.47:5555}"; RECORD_S="${4:-12.5}"; mkdir -p "$OUT"
adb -s "$D" logcat -c
adb -s "$D" shell "am start -n com.envi.wispr/.ui.VoiceInputActivity --ez toggle true" >/dev/null
sleep 1.5
adb -s "$D" shell "am start -n com.envi.wispr.test/com.envi.wispr.SpeakerPlaybackActivity" >/dev/null
sleep "$RECORD_S"
adb -s "$D" shell "am start -n com.envi.wispr/.ui.VoiceInputActivity --ez stop true" >/dev/null
LOG=""
for _ in $(seq 1 80); do
  LOG=$(adb -s "$D" logcat -d -v time -s 'DictationSession:*' 'PolishService:*' 'AsrService:*' 'PasteAccessibility:*' 'ActivityManager:*' 'lmkd:*' 2>/dev/null)
  if echo "$LOG" | grep -q "Auto-insert handed\|kept on clipboard\|showError\|Transcription failed\|Speech recognition failed\|No audio captured"; then break; fi
  sleep 0.5
done
printf '%s\n' "$LOG" > "$OUT/$LABEL.log"
printf '%s\n' "$LOG" | grep -i "recording_stop\|asr_request\|result_received\|S1-mini loaded\|polish_done\|Polish outcome\|Polish result received\|Auto-insert handed\|kept on clipboard\|showError\|failed\|Killing\|lowmemory\|lmk" \
  | sed 's/^\(..-.. ..:..:..\.[0-9]*\).*: \(.*\)$/\1 \2/'
