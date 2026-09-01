#!/usr/bin/env bash
# The dictation measurement campaign on the founder's S26 Ultra, built for #72: N dictations through the
# speaker per memory condition (rested, loaded), cold and cached strata, a long-take stratum, the phone's
# state verified before every run, memory and thermal sampled on the device.
#
#   scripts/uat/residency-campaign.sh <runs-per-cell> <short-fixture.pcm> <long-fixture.pcm> [outdir]
#
# #72 measured four polish warm-up moments through a debug-only override in the session owner; the decision
# (warm at connect) is recorded in architecture-rules.md RULE: isolate-limbs and the override was removed
# with it, so this script now measures the SHIPPED shape. To compare shapes again, restore the override
# from the #72 branch history. Everyday apps opened for the loaded condition are the founder's and are left
# open. Refuses to start when the numbers would not mean anything.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.." || exit 2
N="${1:?runs per cell}"; SHORT="${2:?short fixture}"; LONG="${3:?long fixture}"; OUT="${4:-.validation/uat/residency}"
D=100.94.206.47:5555; PKG=com.envi.wispr; HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APPS=(com.android.chrome com.google.android.gm com.google.android.apps.messaging com.Slack com.google.android.apps.maps com.google.android.apps.docs.editors.docs)
SHAPES=(SHIPPED)
LONG_RUNS=3

sh() { adb -s "$D" shell "$@" | tr -d '\r'; }
# Under pipefail a `grep -q` that matches early closes the pipe and the pipeline reports FAILURE, so every
# yes/no probe here captures the whole output first and matches it in the shell (validation-discipline.md
# FACT: silent-empty-traps, "a pipeline returns its LAST command's status").
has() { local out; out=$(sh "$1"); case "$out" in *"$2"*) return 0;; *) return 1;; esac; }
refuse() { echo "REFUSED: $*" >&2; exit 2; }
log() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/campaign.log"; }

# ---- phone state the campaign changes, restored from the trap whatever happens
mkdir -p "$OUT"
ORIG_TIMEOUT=$(sh "settings get system screen_off_timeout")
restore_phone() {
  sh "settings put system screen_off_timeout ${ORIG_TIMEOUT:-600000}" >/dev/null
  sh "touch /data/local/tmp/sampler.stop" >/dev/null
  install_fixture "$SHORT"
}
trap restore_phone EXIT; trap 'exit 130' INT; trap 'exit 143' TERM

install_fixture() {
  adb -s "$D" push "$1" /data/local/tmp/enviouswispr-uat.pcm >/dev/null
  for pkg in $PKG $PKG.test; do sh "run-as $pkg sh -c 'cat /data/local/tmp/enviouswispr-uat.pcm > cache/enviouswispr-uat.pcm'" >/dev/null; done
}

# ---- preconditions, each one a measured trap
[ "$(sh 'dumpsys bluetooth_manager 2>/dev/null' | grep -m1 'enabled:' | awk '{print $2}')" = "false" ] || refuse "Bluetooth is on; a headset steals the speaker"
AUDIO=$(sh 'dumpsys audio')
printf '%s\n' "$AUDIO" | grep -A4 'STREAM_MUSIC' | grep 'Current:' | head -1 | grep -E '2 \(speaker\): [1-9]' >/dev/null; [ "${PIPESTATUS[3]}" -eq 0 ] || refuse "media volume on the speaker is 0"
if printf '%s\n' "$AUDIO" | grep -iE 'wired_headset|usb_headset|usb_device' | grep -i 'connected' >/dev/null; then refuse "a wired or USB headset is connected"; fi
# A dimmed phone is woken; a LOCKED one needs the founder's PIN and is refused (device-testing.md FACT:
# a-locked-screen-is-a-real-blocker-not-an-occupancy-question).
has 'dumpsys power' 'mWakefulness=Awake' || { sh 'input keyevent 224' >/dev/null; sleep 2; }
has 'dumpsys power' 'mWakefulness=Awake' || refuse "screen is not awake even after a wake key"
has 'dumpsys deviceidle' 'mScreenLocked=false' || refuse "screen is locked; the founder's PIN is needed"
has "run-as $PKG cat shared_prefs/envious_wispr_provider_configuration.xml" '>OFFLINE_S1<' || refuse "AI Polish mode is not This phone (switch it on the screen, never by editing the file)"
sh "settings put system screen_off_timeout 1800000" >/dev/null   # restored from the trap; the campaign outruns the founder's 10-minute timeout
install_fixture "$SHORT"
for pkg in $PKG $PKG.test; do size=$(sh "run-as $pkg stat -c %s cache/enviouswispr-uat.pcm 2>/dev/null"); [ "${size:-0}" -gt 100000 ] || refuse "fixture missing in $pkg"; done
adb -s "$D" push "$HERE/device-sampler.sh" /data/local/tmp/device-sampler.sh >/dev/null; sh "chmod 755 /data/local/tmp/device-sampler.sh" >/dev/null

BUILD=$(sh 'dumpsys package com.envi.wispr' | grep -m1 lastUpdateTime); echo "$BUILD" > "$OUT/installed-build.txt"
sh 'getprop' | grep -i lmk > "$OUT/lmk-properties.txt"
BASE_THERMAL=$(sh 'dumpsys thermalservice' | grep -m1 'Thermal Status' | grep -o '[0-9]*')
BASE_TEMP=$(sh 'dumpsys battery' | grep -m1 temperature | grep -o '[0-9]*')
log "campaign: $N runs per cell, shapes ${SHAPES[*]}, thermal baseline $BASE_THERMAL, battery ${BASE_TEMP}dC, build: $BUILD"
[ "${PREFLIGHT_ONLY:-0}" = "1" ] && { log "preflight only: all checks passed"; exit 0; }

thermal() { sh 'dumpsys thermalservice' | grep -m1 'Thermal Status' | grep -o '[0-9]*'; }
battery_temp() { sh 'dumpsys battery' | grep -m1 temperature | grep -o '[0-9]*'; }
wait_for_baseline() {  # RECORDS the thermal state; never waits. Founder 2026-09-01: back-to-back runs are the
  # stress test, so thermal status and battery temperature are measured per run and shown in the report
  # instead of gating the campaign (a 2 dC battery band had stalled it at run four).
  log "thermal status $(thermal), battery $(battery_temp)"
  return 0
}
loaded_roster_ok() {  # every everyday app alive; re-open up to twice
  local tries=0
  while :; do
    app_state > "$1"
    grep -q '=$' "$1" || return 0
    tries=$((tries+1)); [ $tries -le 2 ] || return 1
    log "an everyday app was gone; re-opening"; open_apps
  done
}
app_state() {  # pid, start time (stat field 22), PSS kB per everyday app
  for a in "${APPS[@]}"; do
    local p=$(sh "pidof $a" | cut -d' ' -f1)
    if [ -n "$p" ]; then printf '%s=%s:%s:%s\n' "$a" "$p" "$(sh "cut -d' ' -f22 /proc/$p/stat")" "$(sh "dumpsys meminfo -s $p" | awk '/TOTAL PSS:/{print $3; exit}')"
    else printf '%s=\n' "$a"; fi
  done
}
open_apps() { for a in "${APPS[@]}"; do sh "monkey -p $a -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1; sleep 4; done; sh "am start -n $PKG/.ui.SettingsActivity" >/dev/null; sleep 2; }

one_run() {  # cond shape index stratum [record-seconds]
  local cond="$1" shape="$2" i="$3" stratum="$4" secs="${5:-12.5}"; local rd="$OUT/$cond/$shape/$i"; mkdir -p "$rd"
  echo "$stratum" > "$rd/stratum-requested.txt"
  if ! wait_for_baseline; then echo "thermal-baseline-not-reached" > "$rd/discarded.txt"; log "$cond/$shape/$i DISCARDED: thermal"; return; fi
  if [ "$stratum" = cold ]; then
    sh "am kill $PKG" >/dev/null; sleep 2   # not force-stop: that would unbind the accessibility service
    [ -z "$(sh "pidof $PKG:polish")" ] || refuse "the polish process survived am kill before a cold run; the campaign will not label a warm run cold"
  fi
  sh "pidof $PKG:polish" > "$rd/polish-pid-before.txt"   # the stratum is DERIVED from this by the report
  if [ "${cond#loaded}" != "$cond" ]; then
    loaded_roster_ok "$rd/apps-before.txt" || { echo "loaded-roster-incomplete" > "$rd/discarded.txt"; log "$cond/$shape/$i DISCARDED: roster"; return; }
  fi
  thermal > "$rd/thermal-before.txt"; battery_temp > "$rd/battery-before.txt"
  sh "rm -f /data/local/tmp/sampler.stop /data/local/tmp/sampler.mem.csv /data/local/tmp/sampler.pss.csv; nohup /data/local/tmp/device-sampler.sh /data/local/tmp/sampler /data/local/tmp/sampler.stop >/dev/null 2>&1 &" >/dev/null
  "$HERE/dictate-once.sh" run "$rd" "$D" "$secs" > "$rd/timeline.txt"
  sh "touch /data/local/tmp/sampler.stop" >/dev/null; sleep 1.5
  adb -s "$D" pull /data/local/tmp/sampler.mem.csv "$rd/mem.csv" >/dev/null; adb -s "$D" pull /data/local/tmp/sampler.pss.csv "$rd/pss.csv" >/dev/null
  sh "pidof $PKG:polish" > "$rd/polish-pid-after.txt"
  [ "${cond#loaded}" != "$cond" ] && app_state > "$rd/apps-after.txt"
  thermal > "$rd/thermal-after.txt"; battery_temp > "$rd/battery-after.txt"
  local chars=$(grep -o 'chars=[0-9]*' "$rd/run.log" | head -1 | grep -o '[0-9]*')
  echo "${chars:-0}" > "$rd/transcript-chars.txt"
  log "$cond/$shape/$i ($stratum): chars=${chars:-0} $(grep -c 'S1-mini loaded' "$rd/run.log") load line(s)"
  sleep 5
}

for cond in rested loaded; do
  if [ "$cond" = rested ]; then sh "am kill-all" >/dev/null; sleep 3; else open_apps; fi
  # cold run first, then cached rounds; with more than one shape the order rotates per round
  for shape in "${SHAPES[@]}"; do one_run "$cond" "$shape" 1 cold; done
  for i in $(seq 2 "$N"); do
    rot=$(( (i - 2) % ${#SHAPES[@]} ))
    for k in $(seq 0 $(( ${#SHAPES[@]} - 1 ))); do shape=${SHAPES[$(( (k + rot) % ${#SHAPES[@]} ))]}; echo "$cond round $i: $shape" >> "$OUT/run-order.txt"; one_run "$cond" "$shape" "$i" cached; done
  done
done

# ---- long-take stratum
install_fixture "$LONG"
LONG_S=$(python3 -c "import os;print(round(os.path.getsize('$LONG')/32000+1.5,1))")
for shape in "${SHAPES[@]}"; do for i in $(seq 1 $LONG_RUNS); do one_run "loaded-long" "$shape" "$i" cached-long "$LONG_S"; done; done
log "campaign complete: $OUT"
