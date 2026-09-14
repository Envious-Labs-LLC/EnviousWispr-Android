#!/bin/bash
# One dictation take that feeds audio over gRPC injectAudio (no coreaudio/BlackHole). Preconditions: a
# field is focused and the record bubble is present. $1 sentence, $2 tag.
# Correct usage per the emulator: host mic OFF, recording already running, THEN inject.
set -u
A=~/Android/sdk/platform-tools/adb; D=emulator-5554; LIB=~/Android/sdk/emulator/lib
S="${TMPDIR:-/tmp}/ew-uat"; mkdir -p "$S"
SENTENCE="${1:-and I will send the deck tomorrow}"; TAG="${2:-g}"; BX=1224; BY=1864; CHK=1222; CHY=1860
FRAME=$($A -s $D shell dumpsys window windows | grep -o "EnviousWispr recording controls, frame=\[Rect([0-9, -]*)\]" | head -1)
[ -z "$FRAME" ] && { echo "[$TAG] no bubble"; exit 1; }
# Build the PCM + timestamped packets ONCE, before the take.
say -v Samantha -r 160 -o "$S/utt.aiff" "$SENTENCE"
ffmpeg -hide_banner -loglevel error -y -i "$S/utt.aiff" -f s16le -ar 48000 -ac 1 "$S/utt.pcm"
python3 - "$S/utt.pcm" "$S/packets.jsonl" <<'PY'
import base64, json, sys, time
data = open(sys.argv[1], "rb").read()
chunk = 9600  # ~100 ms at 48kHz mono S16
t0 = int(time.time() * 1_000_000)
with open(sys.argv[2], "w") as f:
    for n, i in enumerate(range(0, len(data), chunk)):
        f.write(json.dumps({
            "format": {"samplingRate": 48000, "channels": "Mono", "format": "AUD_FMT_S16"},
            "timestamp": n * 100_000,
            "audio": base64.b64encode(data[i:i+chunk]).decode(),
        }) + "\n")
PY
$A -s $D emu avd hostmicoff >/dev/null 2>&1
$A -s $D logcat -c
$A -s $D shell input tap $BX $BY          # start recording (mic opens)
sleep 3                                    # recogniser warms
# Inject WHILE recording is active.
grpcurl -plaintext -import-path "$LIB" -proto emulator_controller.proto -d @ \
  localhost:8554 android.emulation.control.EmulatorController/injectAudio < "$S/packets.jsonl" 2>&1 | head -3
echo "[$TAG] inject exit=${PIPESTATUS[0]}"
sleep 2
$A -s $D shell input tap $CHK $CHY         # confirm/insert
sleep 8
echo "[$TAG] --- result ---"
$A -s $D logcat -d | grep -E "AsrService: Decode|insertion api=" | cut -c1-200 | tail -3
