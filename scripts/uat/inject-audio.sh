#!/bin/bash
# Speak a sentence straight into the emulator's virtual mic over gRPC injectAudio. No coreaudio, no
# BlackHole: raw S16LE mono 48kHz PCM is streamed as AudioPackets. $1 = sentence.
set -u
LIB=~/Android/sdk/emulator/lib
S="${TMPDIR:-/tmp}/ew-uat"; mkdir -p "$S"
SENTENCE="${1:-and I will send the deck tomorrow}"
say -v Samantha -r 160 -o "$S/utt.aiff" "$SENTENCE"
ffmpeg -hide_banner -loglevel error -y -i "$S/utt.aiff" -f s16le -ar 48000 -ac 1 "$S/utt.pcm"
# Chunk the raw PCM into ~100ms base64 AudioPackets (48000*2 bytes/s * 0.1 = 9600 bytes).
python3 - "$S/utt.pcm" > "$S/packets.jsonl" <<'PY'
import base64, json, sys
data = open(sys.argv[1], "rb").read()
chunk = 9600
for i in range(0, len(data), chunk):
    pkt = {
        "format": {"samplingRate": 48000, "channels": "Mono", "format": "AUD_FMT_S16"},
        "audio": base64.b64encode(data[i:i+chunk]).decode(),
    }
    print(json.dumps(pkt))
PY
echo "pcm bytes: $(wc -c < "$S/utt.pcm"), packets: $(wc -l < "$S/packets.jsonl")"
grpcurl -plaintext -import-path "$LIB" -proto emulator_controller.proto -d @ \
  localhost:8554 android.emulation.control.EmulatorController/injectAudio < "$S/packets.jsonl" 2>&1 | head -5
echo "inject exit=${PIPESTATUS[0]}"
