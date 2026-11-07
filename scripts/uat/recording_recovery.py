#!/usr/bin/env python3
"""Operator-assisted recovery acceptance using the existing emulator recorder rig."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import time
import uuid

import wispr_eyes as eyes

ROOT = Path(__file__).resolve().parents[2]
SENTENCE = "the patient recovery marker belongs in history"
EXPECTED = "The patient recovery marker belongs in history."
NORMAL_SENTENCE = "the quarterly marker sentence lands tomorrow"
TEST = "com.envi.wispr.VoicePipelineDeviceTest#aSideButtonTakeLandsInTheFocusedEditorExactlyOnce"
PROTECTED = "no_backup/protected-recovery.v1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("step", choices=["interrupt", "check", "reopen", "next"])
    parser.add_argument("--serial", required=True)
    parser.add_argument("--out", required=True, type=Path, help="new evidence directory outside the checkout")
    args = parser.parse_args()
    eyes.device(args.serial)
    eyes._require_emulator("recording recovery fault injection")
    out = args.out.resolve()
    adb = [eyes.ADB, "-s", eyes.device()]

    def shell(*parts):
        return subprocess.check_output(adb + ["shell", *parts], timeout=30)

    def write(name, value):
        (out / name).write_text(json.dumps(value, indent=2) + "\n")

    def read(name):
        return json.loads((out / name).read_text())

    def database(label):
        # Retain every snapshot, including a pending or failed observation.
        stem = out / (label + "-" + uuid.uuid4().hex)
        archive = stem.with_suffix(".tar")
        archive.write_bytes(subprocess.check_output(
            adb + ["exec-out", "run-as", "com.envi.wispr", "tar", "-cf", "-", "databases"], timeout=30))
        stem.mkdir()
        with tarfile.open(archive) as saved:
            saved.extractall(stem, filter="data")
        with sqlite3.connect(stem / "databases/enviouswispr.db") as connection:
            connection.row_factory = sqlite3.Row
            assert connection.execute("PRAGMA quick_check").fetchone()[0] == "ok", "Database snapshot is unreadable"
            return {table: [dict(row) for row in connection.execute("SELECT * FROM " + table)]
                    for table in ("transcripts", "take_journal")}

    def recovered_row():
        take = read("boundary.json")["take_id"]
        rows = [row for row in database("history")["transcripts"] if row["takeId"] == take]
        assert len(rows) == 1, "Expected exactly one History row for this run's take"
        return rows[0]

    if args.step == "interrupt":
        out.mkdir(parents=True, exist_ok=False)
        assert eyes.ready(), "The owned emulator is not ready"
        assert "IDLE" in shell("run-as", "com.envi.wispr", "cat", "shared_prefs/dictation_surface_state.xml").decode()
        baseline = database("before")
        before_ids = {row["take_id"] for row in baseline["take_journal"]}
        state = {"serial": eyes.device(), "expected": EXPECTED, "recording_headroom_seconds": 20,
                 "checkout_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
        original = eyes.inject_audio

        def interrupt_after_speech(pcm):
            state["reached_live_audio"] = True
            shutil.copy2(pcm, out / "tts.pcm")
            injected = out / "injected.pcm"
            # Input pre-roll: let the virtual audio stream settle before the unchanged spoken phrase.
            injected.write_bytes(bytes(48_000 * 2) + Path(pcm).read_bytes())
            state["lead_silence_seconds"] = 1
            state["injected_sha256"] = hashlib.sha256(injected.read_bytes()).hexdigest()
            answer = original(str(injected))
            print("Speech delivered; keeping the recorder open for 20 seconds.", flush=True)
            time.sleep(20)  # test-fixture-timer: comfortable interruption window, never a completion oracle.
            assert eyes.recording(), "NOT STAGED: capture ended before the planned interruption"
            phase = shell("run-as", "com.envi.wispr", "cat", "shared_prefs/dictation_surface_state.xml").decode()
            assert '<string name="phase">LISTENING</string>' in phase, "NOT STAGED: owner is no longer recording"
            current = database("live")["take_journal"]
            admitted = [row for row in current if row["take_id"] not in before_ids and row["terminal_result"] is None]
            assert len(admitted) == 1 and admitted[0]["stage"] == "RECORDING", "NOT STAGED: ambiguous live take"
            state["take_id"] = admitted[0]["take_id"]
            captured = shell("run-as", "com.envi.wispr", "cat", "cache/recording-take-" + state["take_id"] + ".pcm")
            (out / "captured-before-kill.pcm").write_bytes(captured)
            state["captured_prefix_sha256"] = hashlib.sha256(captured).hexdigest()
            listing = shell("run-as", "com.envi.wispr", "ls", "-l", PROTECTED).decode()
            audio = [line.split() for line in listing.splitlines() if line.endswith(("slot-0.audio", "slot-1.audio"))]
            assert len(audio) == 1 and int(audio[0][4]) > 64, "NOT STAGED: protected audio is unavailable"
            state["protected_files"] = listing
            assert shell("run-as", "com.envi.wispr.test", "cat", "files/paste-target-received.txt") == b""
            state["kill_requested"] = True
            state["kill"] = eyes.kill_process("com.envi.wispr")
            write("boundary.json", state)
            return answer + "; intentionally interrupted before Stop"

        try:
            eyes.look()
            eyes.stop_agent()
            eyes.inject_audio = interrupt_after_speech
            state["aborted_driver_report"] = eyes.run_device_test(TEST, SENTENCE)
            assert state.get("kill"), "The interruption was not staged"
        finally:
            eyes.inject_audio = original
            if state.get("reached_live_audio") and not state.get("kill_requested"):
                eyes.cancel_dictation()
            write("interruption-driver.json", state)
            eyes.restore()
        # Exactly one foreground launch. A pending History draft is not a recovery verdict.
        eyes.open_settings()
        (out / "first-open-ui.txt").write_text(eyes.look())
        print("Interruption staged. Wait for the words in History, then run check. The aborted native runner is not a passed test.")
    elif args.step == "check":
        row = recovered_row()
        if not row["finalText"]:
            print("INCOMPLETE: History has not finalized this take. Observe completion, then rerun check without reopening the app.")
            return 2
        assert row["originalText"] == EXPECTED and row["finalText"] == EXPECTED, "Recovered words differ from the independent literal"
        assert row["recovered"] and row["recoveryModelIdentity"], "Missing recovery provenance"
        assert shell("run-as", "com.envi.wispr.test", "cat", "files/paste-target-received.txt") == b"", "Recovery touched the original editor"
        write("recovered.json", row)
        print("PASS: the exact words recovered once into History; the original editor is empty.")
    elif args.step == "reopen":
        original = read("recovered.json")
        eyes.kill_process("com.envi.wispr")
        eyes.open_settings()
        assert recovered_row() == original, "A second restart changed or duplicated recovery"
        assert shell("run-as", "com.envi.wispr.test", "cat", "files/paste-target-received.txt") == b""
        write("reopened.json", {"same_row_unchanged": True})
        print("PASS: second restart kept the same one row and left the editor empty.")
    else:
        assert read("reopened.json")["same_row_unchanged"]
        eyes.look()
        eyes.stop_agent()
        report = eyes.run_device_test(TEST, NORMAL_SENTENCE)
        write("next-recording.json", report)
        assert any(line.startswith("VERIFIED:") for line in report) and not any(
            line.startswith(("ISSUE:", "BLOCKED:", "NOT RUN:", "UNKNOWN")) for line in report), "Next recording did not pass; inspect its retained report"
        assert recovered_row() == read("recovered.json"), "Normal recording altered the recovered row"
        write("result.json", {"result": "PASS", "scope": "emulator interruption/recovery/reopen/next recording", "take_id": read("boundary.json")["take_id"]})
        print("PASS: complete recovery acceptance flow, including the next normal recording.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    finally:
        print(eyes.stop_agent())
