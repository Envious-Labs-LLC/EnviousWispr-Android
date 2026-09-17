#!/usr/bin/env python3
"""One real take into the already focused editor. PCM remains on the phone.
Requires existing authorized ADB, unlocked phone, and EnviousWispr auto-paste bound.
No APK installation, volume changes, root, permissions changes or microphone upload.
"""
import argparse
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import uuid

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import wispr_eyes as eyes


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--pcm', required=True, help='existing PHONE path, 16 kHz mono signed little-endian PCM')
    parser.add_argument('--expect', required=True, help='expected final editor text, including any pre-existing text')
    parser.add_argument('--receipt', type=Path, required=True)
    args = parser.parse_args()
    args.receipt.mkdir(parents=True, exist_ok=True)
    adb = [eyes.ADB, '-s', args.serial]
    def shell(command):
        value = subprocess.check_output(adb + ['shell', command + '; echo SILENT_RC=$?'], text=True, timeout=15)
        if not value.rstrip().endswith('SILENT_RC=0'):
            raise RuntimeError(value)
        return value.rsplit('SILENT_RC=', 1)[0].strip()
    def volume():
        value = shell('cmd media_session volume --stream 3 --get')
        match = re.search(r'volume is (\d+)', value)
        if not match: raise RuntimeError('Cannot read media volume')
        return int(match[1])
    eyes.device(args.serial)
    if not eyes.ready() or not eyes.bound(): raise RuntimeError('Phone or auto-paste not ready')
    if eyes.recording(): raise RuntimeError('Another take is active')
    # Caller owns the editor. Do not launch an activity that steals its pinned focus.
    before_volume = volume()
    remote = '/data/local/tmp/wispr-silent-' + uuid.uuid4().hex + '.jar'
    rows = []
    processes = []
    take_started = False
    def monitor(process):
        q = queue.Queue()
        def pump():
            for line in process.stdout:
                rows.append(line)
                q.put(line)
            q.put(None)
        threading.Thread(target=pump, daemon=True).start()
        return q
    def wait(q, text, seconds=35):
        end = time.monotonic() + seconds
        while True:
            try: line = q.get(timeout=max(0, end-time.monotonic()))
            except queue.Empty: raise RuntimeError('Deadline waiting for ' + text)
            if line is None: raise RuntimeError('Process ended waiting for ' + text)
            if text in line:
                print(line.strip(), flush=True)
                return line.strip()
    helper = None
    try:
        sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home()/'Android/sdk')))
        java = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@21'))
        with tempfile.TemporaryDirectory(prefix='wispr-silent-') as temp:
            temp = Path(temp)
            android = sdk/'platforms/android-36/android.jar'
            subprocess.run([str(java/'bin/javac'), '--release', '8', '-cp', str(android), '-d', str(temp), str(HERE/'SilentAudio.java')], check=True)
            env = dict(os.environ, JAVA_HOME=str(java))
            subprocess.run([str(sdk/'build-tools/34.0.0/d8'), '--lib', str(android), '--output', str(temp/'helper.jar'), str(temp/'SilentAudio.class')], env=env, check=True)
            subprocess.run(adb + ['push', str(temp/'helper.jar'), remote], check=True)
        logs = subprocess.Popen(adb + ['logcat', '-T', '1', '-v', 'threadtime', 'AudioCapture:I', 'DictationSession:I', 'AsrService:I', 'PasteService:I', '*:S'], stdout=subprocess.PIPE, text=True)
        processes.append(logs)
        logq = monitor(logs)
        helper = subprocess.Popen(adb + ['shell', 'CLASSPATH=' + shlex.quote(remote) + ' app_process /system/bin SilentAudio ' + shlex.quote(args.pcm)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        processes.append(helper)
        hq = monitor(helper)
        wait(hq, 'READY')
        helper.stdin.write('ARM\n'); helper.stdin.flush()
        wait(hq, 'ARMED')
        take_started = True
        shell('am start -n com.envi.wispr/.ui.VoiceInputActivity --ez toggle true')
        wait(logq, 'Recording started (PID:')
        routing = shell('dumpsys media.audio_policy')
        (args.receipt/'routing.txt').write_text(routing)
        helper.stdin.write('GO\n'); helper.stdin.flush()
        wait(hq, 'INJECTED')
        shell('am start -n com.envi.wispr/.ui.VoiceInputActivity --ez stop true')
        outcome = wait(logq, 'insertion api=', 40)
        view = eyes.tree()
        (args.receipt/'editor.json').write_text(__import__('json').dumps(view, indent=2))
        # Assert the actual editable node, not a suggestion or clipboard projection.
        fields = [n for n in view if n.get('kind') == 'EditText']
        expected = [n for n in fields if n.get('text', '').strip() == args.expect.strip()]
        if len(expected) != 1: raise RuntimeError('Expected text did not match exactly one editor: ' + repr(fields))
        print('EDITOR_MATCH ' + args.expect, flush=True)
        helper.stdin.write('CLOSE\n'); helper.stdin.flush()
        wait(hq, 'CLEANED')
        if helper.wait(timeout=10) != 0: raise RuntimeError('Helper failed')
        take_started = False
        after_volume = volume()
        if after_volume != before_volume: raise RuntimeError('Media volume changed externally')
        print(f'PASS media_volume_before={before_volume} after={after_volume}; {outcome}', flush=True)
    finally:
        if take_started:
            shell('am start -n com.envi.wispr/.ui.VoiceInputActivity --ez cancel true')
        if helper is not None and helper.poll() is None:
            try: helper.stdin.close(); helper.wait(timeout=8)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                # Its on-phone deadline cancels the take and binder death removes the policy.
                helper.wait(timeout=65)
        for process in processes:
            if process.poll() is None: process.terminate(); process.wait(timeout=5)
        shell('rm -f ' + shlex.quote(remote))
        (args.receipt/'take.log').write_text(''.join(rows))
        (args.receipt/'routing-after.txt').write_text(shell('dumpsys media.audio_policy'))
        print('CLEANUP helper removed; ' + str(eyes.restore()), flush=True)

if __name__ == '__main__':
    run()
