#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES="$PROJECT_ROOT/local.properties"
AVD_NAME="${EW_AVD_NAME:-EnviousWispr_Android_16}"
SERIAL="${EW_EMULATOR_SERIAL:-emulator-5554}"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -f "$LOCAL_PROPERTIES" ]]; then
  echo "Missing $LOCAL_PROPERTIES" >&2
  exit 1
fi

SDK_ROOT="$(sed -n 's/^sdk\.dir=//p' "$LOCAL_PROPERTIES" | head -n 1)"
ADB="$SDK_ROOT/platform-tools/adb"
EMULATOR="$SDK_ROOT/emulator/emulator"

if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  echo "Android SDK tools are missing under $SDK_ROOT" >&2
  exit 1
fi

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  # -allow-host-audio: without it the emulator zeroes every microphone sample by design (its own help
  # text), so a dictation records silence and the recogniser returns nothing. With it, the Mac's
  # microphone is the phone's, and `say` through the Mac speaker is a real utterance to the app.
  "$EMULATOR" -avd "$AVD_NAME" -gpu host -no-boot-anim -allow-host-audio \
    >"/tmp/${AVD_NAME}.log" 2>&1 &
fi

for _ in $(seq 1 180); do
  if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null || true)" == "1" ]]; then
    break
  fi
  sleep 1
done

if [[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null || true)" != "1" ]]; then
  echo "Emulator did not finish booting. See /tmp/${AVD_NAME}.log" >&2
  exit 1
fi

# -allow-host-audio only PERMITS the host microphone; the emulator's own switch for it (Extended
# controls > Microphone > "Virtual microphone uses host audio input") defaults to OFF and is not
# persisted, so without this console command every take records silence and the recogniser returns
# zero characters while the whole pipeline reports success. Measured 2026-09-13: seven silent takes
# across the built-in mic, a loopback device, a cold boot and the AVD.conf key, until this one line.
# Idempotent; safe on a running emulator. Captured, then tested: `producer | grep -q` under pipefail
# fails on a MATCH (validation-discipline.md FACT: silent-empty-traps).
# What the microphone HEARS is the Mac's default input at record time, not at launch, so the voice
# path is chosen per take: scripts/uat/wispr_eyes.py say_into_emulator() plays into the virtual cable
# (BlackHole 2ch) and switches the input for the sentence. Nothing about the cable is needed here.
HOSTMIC="$("$ADB" -s "$SERIAL" emu avd hostmicon 2>&1 || true)"
case "$HOSTMIC" in
  OK*) ;;
  *) echo "Could not turn the emulator's host microphone on (adb emu avd hostmicon): $HOSTMIC" >&2; exit 1 ;;
esac

if [[ "${1:-}" == "--build" || ! -f "$APK_PATH" ]]; then
  "$PROJECT_ROOT/gradlew" -p "$PROJECT_ROOT" :app:assembleDebug --no-daemon
fi

"$ADB" -s "$SERIAL" install -r "$APK_PATH"
"$ADB" -s "$SERIAL" shell pm grant com.envi.wispr android.permission.RECORD_AUDIO
"$ADB" -s "$SERIAL" shell pm grant com.envi.wispr android.permission.POST_NOTIFICATIONS
"$ADB" -s "$SERIAL" shell settings put secure enabled_accessibility_services \
  com.envi.wispr/com.envi.wispr.paste.PasteAccessibilityService
"$ADB" -s "$SERIAL" shell settings put secure accessibility_enabled 1
"$ADB" -s "$SERIAL" shell svc power stayon true
"$ADB" -s "$SERIAL" shell settings put system screen_off_timeout 2147483647
"$ADB" -s "$SERIAL" shell am start -f 0x14000000 -n com.envi.wispr/.ui.SettingsActivity

echo "EnviousWispr is ready on $SERIAL ($AVD_NAME)."
