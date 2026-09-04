#!/usr/bin/env bash
# One dictation that is NEVER told to stop. Plays a fixture through the speaker and watches whether the
# take ends by itself, why, and what reached the editor.
#
#   scripts/uat/dictate-until-silence.sh <label> <outdir> <fixture.pcm> [device] [wait-seconds]
#
# The oracle is CONJUNCTIVE on purpose. "The take ended" alone is satisfied by a fixture that never played:
# the phone hears silence, and silence with no speech before it must not auto-stop, so a terminal-reason
# check on its own can pass having tested nothing. The transcript is what proves audio arrived.
#
# Preconditions the caller checks: Bluetooth off, screen awake and unlocked, media volume up, both APKs
# from this build installed, and the accessibility service in whatever state the case under test needs.
set -uo pipefail
LABEL="$1"; OUT="$2"; FIXTURE="$3"; D="${4:-100.94.206.47:5555}"; WAIT_S="${5:-30}"
mkdir -p "$OUT"

echo "== $LABEL: pushing $(basename "$FIXTURE") into both packages"
for PKG in com.envi.wispr com.envi.wispr.test; do
  adb -s "$D" push "$FIXTURE" "/data/local/tmp/uat.pcm" >/dev/null
  adb -s "$D" shell "run-as $PKG cp /data/local/tmp/uat.pcm cache/enviouswispr-uat.pcm" >/dev/null 2>&1
done

echo "== $LABEL: which microphone is actually bound"
adb -s "$D" shell "dumpsys media.audio_flinger" 2>/dev/null \
  | sed -n '/^Input thread/,/^Output thread/p' | grep -iE "Input device:|AUDIO_DEVICE_IN" | head -3

adb -s "$D" logcat -c
adb -s "$D" shell "am start -n com.envi.wispr/.ui.VoiceInputActivity --ez toggle true" >/dev/null
sleep 1.5
adb -s "$D" shell "am start -n com.envi.wispr.test/com.envi.wispr.SpeakerPlaybackActivity" >/dev/null

echo "== $LABEL: waiting up to ${WAIT_S}s for the take to end WITHOUT being told to"
ENDED=""
for _ in $(seq 1 $((WAIT_S * 2))); do
  LOG=$(adb -s "$D" logcat -d -v time -s 'AudioCapture:*' 'DictationSession:*' 'SilenceVad:*' 'SileroVad:*' 'AsrService:*' 'PasteAccessibility:*' 2>/dev/null)
  if printf '%s' "$LOG" | grep -q "Auto-insert handed\|kept on clipboard\|showError\|Transcription failed"; then ENDED=yes; break; fi
  sleep 0.5
done

printf '%s\n' "${LOG:-}" > "$OUT/$LABEL.log"

echo "== $LABEL: did it stop on its own? ($ENDED)"
grep -iE "recording_start|Buffer sizes|Detector ready|Detector unavailable|Auto-stop unavailable|recording_stop|Stopped\.|Max duration|asr_request|result_received|Auto-insert handed|kept on clipboard|showError" \
  "$OUT/$LABEL.log" | sed 's/^\(..-.. ..:..:..\.[0-9]*\).*: \(.*\)$/\1 \2/'

echo "== $LABEL: process check, the detector must be its own pid"
adb -s "$D" shell "pidof com.envi.wispr:audio; pidof com.envi.wispr:vad; echo SENTINEL_OK" | tail -3

if [ -z "$ENDED" ]; then
  echo "== $LABEL: NO ENDING SEEN. Stopping by hand so the phone is not left recording."
  adb -s "$D" shell "am start -n com.envi.wispr/.ui.VoiceInputActivity --ez stop true" >/dev/null
fi
