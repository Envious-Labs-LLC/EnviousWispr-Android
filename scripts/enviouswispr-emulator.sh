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
  "$EMULATOR" -avd "$AVD_NAME" -gpu host -no-boot-anim \
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
