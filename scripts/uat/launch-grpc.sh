#!/bin/bash
# Relaunch the Play AVD with the gRPC service on (port 8554) for injectAudio. Host mic stays available
# but we drive audio over gRPC. Waits for boot.
A=~/Android/sdk/platform-tools/adb
EMU=~/Android/sdk/emulator/emulator
D=emulator-5554
AVD=EnviousWispr_Android_16_Play
$A -s $D emu kill 2>/dev/null
until [ "$(ps -axo command | grep -c '[q]emu-system')" = "0" ]; do sleep 2; done
sleep 4
# BlackHole is the default input already; -grpc 8554 enables the unauthenticated local gRPC endpoint.
"$EMU" -avd "$AVD" -gpu swiftshader_indirect -no-boot-anim -allow-host-audio -grpc 8554 \
  >/tmp/EnviousWispr_Android_16_Play.log 2>&1 &
for _ in $(seq 1 180); do
  [ "$($A -s $D shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 1
done
[ "$($A -s $D shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "boot failed"; exit 1; }
$A -s $D emu avd hostmicon >/dev/null 2>&1
echo "booted with gRPC 8554"
