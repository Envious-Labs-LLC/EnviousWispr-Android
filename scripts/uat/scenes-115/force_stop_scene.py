"""#115 scene (e): the app is force-stopped DURING a take; the next take starts and lands; the History row of
the killed take reads `interrupted`. Usage: WISPR_SERIAL=emulator-5554 115-scene-e.py <uat-dir> <label>"""
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time

sys.path.insert(0, sys.argv[1])
import wispr_eyes as eyes  # noqa: E402

ADB = os.path.expanduser("~/Android/sdk/platform-tools/adb")
SERIAL = os.environ.get("WISPR_SERIAL", "emulator-5554")
label = sys.argv[2]
lines = [f"scene e ({label}): force-stop during a take, then the next take"]


def rows():
    """The History rows, read from a pulled copy of the app's database (debug build, run-as)."""
    tmp = tempfile.mkdtemp()
    for name in ("enviouswispr.db", "enviouswispr.db-wal", "enviouswispr.db-shm"):
        data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "run-as", "com.envi.wispr", "cat", f"databases/{name}"], capture_output=True).stdout
        with open(os.path.join(tmp, name), "wb") as f:
            f.write(data)
    con = sqlite3.connect(os.path.join(tmp, "enviouswispr.db"))
    out = con.execute("select id, status, interrupted, insertionResult, length(finalText) from transcripts order by id desc limit 3").fetchall()
    con.close()
    return out


eyes.restore()
eyes.ready()
assert eyes.bound(), "auto-paste is not bound"
eyes.open_app("com.google.android.gm")
eyes.clear_log()
lines.append(f"rows before: {rows()}")
try:
    with eyes.open_recorder(verify=False):
        time.sleep(3.0)
        # The take is live; the app is force-stopped under it (no onDestroy runs: this is the OS kill).
        lines.append(f"force-stop while recording: {eyes.stop_app()}")
except eyes.Blocked as why:
    lines.append(f"NOTE after the force-stop: {str(why)[:160]}")
time.sleep(2.0)
for line in eyes.restore():
    lines.append(f"restored {line}")
lines.append(f"rows right after the kill: {rows()}")
# The next take: an ordinary spoken take into Gmail.
eyes.ready()
if not eyes.bound():
    lines.append(f"rebound: {eyes.enable_auto_paste()}")
eyes.open_app("com.google.android.gm")
eyes.tap("Compose", package="com.google.android.gm")
eyes.focus_field("Compose email", "com.google.android.gm")
lines += eyes.dictate_emulator("and I will send the deck tomorrow", expected_final="And I will send the deck tomorrow.\u00a0")
lines.append(f"rows after the next take: {rows()}")
for line in [l for l in subprocess.run([ADB, "-s", SERIAL, "logcat", "-d", "-v", "time"], capture_output=True, text=True).stdout.splitlines() if re.search(r"(Recording started|Take ended|recover|Recovered|stale|insertion api=|Reconnected)", l)][:20]:
    lines.append("log " + line[-200:])
path = os.path.join(os.environ.get("SCENE_OUT", os.getcwd()), f"115-scene-e-{label}.txt")
with open(path, "w") as f:
    f.write("\n".join(lines) + "\n")
print("\n".join(lines))
print(f"written {path}")
