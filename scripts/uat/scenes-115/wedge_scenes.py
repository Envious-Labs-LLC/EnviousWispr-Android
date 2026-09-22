"""#115 emulator scenes through wispr-eyes. Usage: WISPR_SERIAL=emulator-5554 115-scene.py <uat-dir> <scene> [label]

Scenes:
  a  start a take, FREEZE the :audio process, press stop, watch the recorder for 12 s, thaw, restore.
     On the baseline the recorder stays up with no sentence (P1 red-by-today); after chunk A it ends
     within the bound with its sentence.
  b  one ordinary spoken take (dictate_emulator) and the tick gaps from the owner's log.
  c  freeze during a take with NO stop pressed: the bound must end it; then thaw and one normal take.
  d  kill -9 during a take: the existing ending; then one normal take.
Every scene ends in restore(). The report is plain lines; the log excerpt is written next to it.
"""
import os
import re
import subprocess
import sys
import time

sys.path.insert(0, sys.argv[1])
import wispr_eyes as eyes  # noqa: E402

ADB = os.path.expanduser("~/Android/sdk/platform-tools/adb")
SERIAL = os.environ.get("WISPR_SERIAL", "emulator-5554")
AUDIO = "com.envi.wispr:audio"


def logcat(pattern):
    out = subprocess.run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time"], capture_output=True, text=True).stdout
    return [line for line in out.splitlines() if re.search(pattern, line)]


def report(lines, label):
    path = os.path.join(os.environ.get("SCENE_OUT", os.getcwd()), f"115-scene-{label}.txt")
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(f"written {path}")


TERMINAL = r"(Take ended|Transcription result|Capture process silent|Ignoring [A-Z_]+: the take already has an ending|Session ended|insertion api=|Copied to clipboard|Final text)"


def take_running():
    """The recorder pill is on screen: the OWNER's view of the take, which is the subject here. The
    capture process's own recording_start/stop lines cannot answer while that process is frozen."""
    pill = eyes.overlay()
    return bool(pill and pill["the_user_can_see_it"])


def owner_reached_an_ending(since_count):
    """The owner's log gained a terminal line after `since_count` such lines: the take moved on from the
    stop. On the baseline with a frozen capture process nothing is ever written; that IS the defect."""
    return len(logcat(TERMINAL)) > since_count


def scene_a(label):
    lines = [f"scene a ({label}): freeze the capture process, press stop"]
    eyes.restore()
    eyes.ready()
    assert eyes.bound(), "auto-paste is not bound"
    eyes.open_app("com.google.android.gm")
    eyes.clear_log()
    with eyes.open_recorder(verify=False):
        time.sleep(3.0)
        lines.append(f"recorder on screen before freeze: {take_running()}")
        before = len(logcat(TERMINAL))
        t_freeze = time.monotonic()
        lines.append(eyes.freeze_process(AUDIO))
        lines.append(f"freeze verified {time.monotonic() - t_freeze:.1f} s after it was sent")
        t0 = time.monotonic()
        eyes.stop_dictation()
        lines.append("stop pressed")
        ended_at = None
        for _ in range(40):
            time.sleep(0.5)
            if owner_reached_an_ending(before):
                ended_at = time.monotonic() - t0
                break
        lines.append(f"owner {ended_at is None and 'REACHED NO ENDING 20 s after the stop (stuck)' or f'reached an ending {ended_at:.1f} s after the stop (or before it: read the log times)'}")
        lines.append(f"recorder pill on screen at that point: {take_running()}")
        lines.append(eyes.thaw_process(AUDIO)[0])
    for line in eyes.restore():
        lines.append(f"restored {line}")
    for line in logcat(r"(Capture process silent|Take ended|Ignoring|Stopping recording|Recording started|Stopped by|stopped answering|Audio capture)")[:40]:
        lines.append("log " + line[-200:])
    take = eyes.last_take()
    lines.append(f"last take: {take}")
    report(lines, f"a-{label}")


def scene_b(label):
    lines = [f"scene b ({label}): one ordinary take with the tick gaps"]
    eyes.restore()
    eyes.ready()
    eyes.open_app("com.google.android.gm")
    eyes.tap("Compose", package="com.google.android.gm")
    eyes.focus_field("Compose email", "com.google.android.gm")
    lines += eyes.dictate_emulator("and I will send the deck tomorrow", expected_final="And I will send the deck tomorrow. ")
    ticks = logcat(r"take tick|onTick|heartbeat")
    lines.append(f"tick lines: {len(ticks)}")
    for line in ticks[:30]:
        lines.append("log " + line[-160:])
    report(lines, f"b-{label}")


def scene_c(label):
    lines = [f"scene c ({label}): freeze mid-take, no stop pressed; the bound ends it"]
    eyes.restore()
    eyes.ready()
    eyes.open_app("com.google.android.gm")
    eyes.clear_log()
    with eyes.open_recorder(verify=False):
        time.sleep(3.0)
        before = len(logcat(TERMINAL))
        lines.append(eyes.freeze_process(AUDIO))
        t0 = time.monotonic()
        ended_at = None
        for _ in range(40):
            time.sleep(0.5)
            if owner_reached_an_ending(before):
                ended_at = time.monotonic() - t0
                break
        lines.append(f"owner {ended_at is None and 'REACHED NO ENDING 20 s after the freeze (stuck)' or f'reached an ending {ended_at:.1f} s after the freeze'}")
        lines.append(f"recorder pill on screen at that point: {take_running()}")
        lines.append(eyes.thaw_process(AUDIO)[0])
    for line in eyes.restore():
        lines.append(f"restored {line}")
    for line in logcat(r"(Capture process silent|Take ended|Ignoring|Recording started|Stopped by|stopped answering|Audio capture)")[:30]:
        lines.append("log " + line[-200:])
    lines.append(f"last take: {eyes.last_take()}")
    report(lines, f"c-{label}")


def scene_d(label):
    lines = [f"scene d ({label}): kill -9 the capture process mid-take"]
    eyes.restore()
    eyes.ready()
    eyes.open_app("com.google.android.gm")
    eyes.clear_log()
    with eyes.open_recorder(verify=False):
        time.sleep(3.0)
        before = len(logcat(TERMINAL))
        lines.append(eyes.kill_process(AUDIO))
        t0 = time.monotonic()
        ended_at = None
        for _ in range(40):
            time.sleep(0.5)
            if owner_reached_an_ending(before):
                ended_at = time.monotonic() - t0
                break
        lines.append(f"owner {ended_at is None and 'REACHED NO ENDING 20 s after the kill (stuck)' or f'reached an ending {ended_at:.1f} s after the kill'}")
        lines.append(f"recorder pill on screen at that point: {take_running()}")
    for line in eyes.restore():
        lines.append(f"restored {line}")
    for line in logcat(r"(died|disconnect|Take ended|Ignoring|Recording started|Stopped by|Audio capture)")[:30]:
        lines.append("log " + line[-200:])
    lines.append(f"last take: {eyes.last_take()}")
    report(lines, f"d-{label}")


if __name__ == "__main__":
    scene = sys.argv[2]
    label = sys.argv[3] if len(sys.argv) > 3 else scene
    {"a": scene_a, "b": scene_b, "c": scene_c, "d": scene_d}[scene](label)
