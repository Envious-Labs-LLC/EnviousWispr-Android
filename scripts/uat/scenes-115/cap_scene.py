"""#115 scene (g): one take with auto-stop OFF left running to the ten-minute cap on the emulator. The take
must end by the pushed MaxDuration ending with its sentence, the words transcribed, and the silence bound
must never fire during the healthy ten minutes (every heartbeat gap under 3 s, the plan's P3 bound).
Usage: WISPR_SERIAL=emulator-5554 115-scene-g.py <uat-dir> <label>"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, sys.argv[1])
import wispr_eyes as eyes  # noqa: E402

ADB = os.path.expanduser("~/Android/sdk/platform-tools/adb")
SERIAL = os.environ.get("WISPR_SERIAL", "emulator-5554")
label = sys.argv[2]
lines = [f"scene g ({label}): auto-stop off, the take runs to the ten-minute cap"]


def logcat(pattern):
    out = subprocess.run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time"], capture_output=True, text=True).stdout
    return [line for line in out.splitlines() if re.search(pattern, line)]


eyes.restore()
eyes.ready()
assert eyes.bound(), "auto-paste is not bound"
eyes.open_tab("Transcription")
eyes.reveal("Stop recording on silence")
lines.append(str(eyes.set_switch("Stop recording on silence", False, where="Transcription")))
eyes.open_app("com.google.android.gm")
eyes.tap("Compose", package="com.google.android.gm")
eyes.focus_field("Compose email", "com.google.android.gm")
scratch = os.path.join(os.environ.get("TMPDIR", "/tmp"), "wispr-eyes")
os.makedirs(scratch, exist_ok=True)
pcm = eyes._pcm_from_sentence("the quarterly numbers are in and the team did well", os.path.join(scratch, "cap-take.pcm"))
lines.append(f"NOTE: {eyes._rest_host_mic_off()}")
eyes.clear_log()
started = time.monotonic()
ended = None
try:
    with eyes.open_recorder(verify=False):
        time.sleep(3.0)
        lines.append(f"NOTE: {eyes.inject_audio(pcm)}")
        # Poll the OWNER's log for the ending; the cap is 600 s, so the bound is 660 s.
        for _ in range(660):
            time.sleep(1.0)
            hits = logcat(r"(Take ended at the duration cap|Capture process silent|Take ended: |Stopped by the duration cap)")
            if hits:
                ended = time.monotonic() - started
                break
        lines.append(f"ending observed {ended is not None and f'{ended:.0f} s after the start' or 'NEVER within 660 s'}")
        # Give the transcription and the insertion a moment to land, then read the last take.
        for _ in range(60):
            time.sleep(1.0)
            if logcat(r"insertion api=|Copied to clipboard|Transcription result"):
                break
finally:
    for line in eyes.restore():
        lines.append(f"restored {line}")
for line in logcat(r"(Recording started|Capture process silent|Take ended|duration cap|Transcription result|insertion api=|Stopped by)")[:20]:
    lines.append("log " + line[-200:])
lines.append(f"last take: {eyes.last_take()}")
path = os.path.join(os.environ.get("SCENE_OUT", os.getcwd()), f"115-scene-g-{label}.txt")
with open(path, "w") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
print(f"written {path}")
