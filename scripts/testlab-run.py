#!/usr/bin/env python3
"""The only way to submit a Firebase Test Lab instrumentation matrix from this repository (#505).

Why it exists (2026-11-02, the #477 probe): a matrix launched with a broken test finished on 7 of 8 phones in three
minutes, all red, while the session waited two hours for the eighth phone that never got a device. So:

  1. LOCAL GATE. The same app and test APKs run the same test target on the emulator first; a failure there
     submits nothing. `--skip-local "<reason>"` is the one escape hatch and is printed in the output.
  2. FIRST RESULT. `run` submits asynchronously and exits as soon as the FIRST phone finishes, printing that
     phone's verdict and failure message, and the `watch` command for the rest.
  3. DEADLINE. `watch` follows the matrix to the end; at the deadline it cancels what has not run and names it.
  4. HONEST POLLS. An API that cannot be reached three polls in a row ends the run as UNREACHABLE, never a verdict.

Run either mode with Bash `run_in_background: true`; the exit is the notification.

  scripts/testlab-run.py run --project personal --app APP.apk --test TEST.apk \\
      --target "class com.envi.wispr.ui.FloatingGuideAllowProbe" \\
      --device model=frankel,version=36 --device model=SC-51E,version=36 [--env forceDrag=true] [--deadline-min 20]
  scripts/testlab-run.py watch --project personal --matrix matrix-abc123 --results gs://bucket/dir [--deadline-min 20]

Exit: 0 every reported phone passed · 10 a reported phone failed · 3 deadline (cancelled) · 4 local gate failed ·
5 unreachable · 2 usage or submission error. Projects and allowances: .claude/knowledge/firebase-test-lab.md.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

PROJECTS = {
    "personal": ("personal", "titanium-vigil-488402-s1"),
    "business": ("business", "ageless-domain-493017-j8"),
}
POLL_SECS = 20
UNREACHABLE_AFTER = 3
OPEN_STATES = {"PENDING", "VALIDATING", "RUNNING"}


def device_dir(device: dict) -> str:
    """Test Lab's results folder for one execution's androidDevice: model-version-locale-orientation."""
    return f"{device['androidModelId']}-{device['androidVersionId']}-{device.get('locale', 'en')}-{device.get('orientation', 'portrait')}"


def full_device(spec: str) -> str:
    parts = dict(p.split("=", 1) for p in spec.split(","))
    parts.setdefault("locale", "en")
    parts.setdefault("orientation", "portrait")
    return ",".join(f"{k}={v}" for k, v in parts.items())


def parse_submission(text: str) -> tuple[str, str]:
    """Matrix id and gs:// results folder from `gcloud firebase test android run --async` output."""
    matrix = re.search(r"\b(matrix-[a-z0-9]+)\b", text)
    folder = re.search(r"storage/browser/([^ \]\s]+?)/?\]", text)
    if not matrix or not folder:
        raise ValueError("could not find the matrix id and results folder in the gcloud output")
    return matrix.group(1), "gs://" + folder.group(1).rstrip("/")


def junit_verdict(xml: str) -> tuple[bool, str]:
    """(passed, one-line reason) from a test_result_1.xml. No testsuite, or zero tests, is a failure."""
    suite = re.search(r"<testsuite\b[^>]*>", xml)
    if not suite:
        return False, "no test result written"
    # Test Lab writes single-quoted attributes (measured 2026-11-02); accept either quote.
    attrs = {k: v for k, _, v in re.findall(r"""(\w+)=(['"])(.*?)\2""", suite.group(0))}
    tests, failures, errors, skipped = (int(attrs.get(k, "0")) for k in ("tests", "failures", "errors", "skipped"))
    if tests == 0:
        return False, "zero tests ran"
    if failures or errors:
        msg = re.search(r"""<(?:failure|error)\b[^>]*message=(['"])(.*?)\1""", xml)
        body = re.search(r"<(?:failure|error)\b[^>]*>\s*([^<]{0,300})", xml)
        text = msg.group(2) if msg else (body.group(1) if body else "")
        reason = (text.strip().splitlines() or ["failed"])[0]
        return False, reason[:240]
    if skipped:
        return False, f"{skipped} of {tests} skipped"
    return True, f"{tests} passed"


def sh(cmd: list[str], timeout: int = 600) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def say(line: str) -> None:
    print(line, flush=True)


def local_gate(args) -> None:
    if args.skip_local:
        say(f"LOCAL GATE SKIPPED: {args.skip_local}")
        return
    adb = ["adb", "-s", args.local_serial]
    for apk in (args.app, args.test):
        r = sh(adb + ["install", "-r", apk])
        if "Success" not in r.stdout:
            say(f"LOCAL GATE FAILED: install {apk} on {args.local_serial}: {(r.stdout + r.stderr).strip()[-300:]}")
            sys.exit(4)
    target_kind, target = args.target.split(" ", 1)
    inst = adb + ["shell", "am", "instrument", "-w", "-e", target_kind, target]
    for kv in args.env:
        k, v = kv.split("=", 1)
        inst += ["-e", k, v]
    r = sh(inst + [args.runner], timeout=args.local_timeout)
    out = r.stdout + r.stderr
    if re.search(r"^OK \(\d+ tests?\)", out, re.M):
        say(f"LOCAL GATE PASSED on {args.local_serial}")
        return
    say(f"LOCAL GATE FAILED on {args.local_serial}; nothing was submitted. Last output:\n{out.strip()[-1500:]}")
    sys.exit(4)


def submit(args) -> tuple[str, str]:
    config, project = PROJECTS[args.project]
    cmd = ["gcloud", f"--configuration={config}", f"--project={project}", "firebase", "test", "android", "run",
           "--async", "--type=instrumentation", f"--app={args.app}", f"--test={args.test}",
           f"--test-targets={args.target}", f"--timeout={args.timeout}", "--num-flaky-test-attempts=0",
           "--no-use-orchestrator", f"--results-history-name={args.history}"]
    cmd += [f"--device={full_device(d)}" for d in args.device]
    if args.env:
        cmd.append("--environment-variables=" + ",".join(args.env))
    if args.pull:
        cmd.append(f"--directories-to-pull={args.pull}")
    r = sh(cmd, timeout=900)
    try:
        return parse_submission(r.stdout + r.stderr)
    except ValueError as e:
        say(f"SUBMISSION FAILED: {e}\n{(r.stdout + r.stderr).strip()[-1500:]}")
        sys.exit(2)


def api(project_key: str, path: str, method: str = "GET") -> dict:
    config, project = PROJECTS[project_key]
    token = sh(["gcloud", f"--configuration={config}", "auth", "print-access-token"], timeout=60).stdout.strip()
    req = urllib.request.Request(f"https://testing.googleapis.com/v1/projects/{project}/{path}", method=method,
                                 headers={"Authorization": f"Bearer {token}", "x-goog-user-project": project})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read() or b"{}")


def phone_report(project_key: str, results: str, device: dict) -> tuple[bool, str]:
    config, _ = PROJECTS[project_key]
    folder = f"{results}/{device_dir(device)}"
    r = sh(["gcloud", f"--configuration={config}", "storage", "cat", f"{folder}/test_result_1.xml"], timeout=120)
    if r.returncode != 0:
        return False, f"no result file at {folder} ({r.stderr.strip()[-160:]})"
    return junit_verdict(r.stdout)


def watch(args, matrix: str, results: str, stop_at_first: bool) -> None:
    deadline = time.time() + args.deadline_min * 60
    reported: set[str] = set()
    any_failed = False
    misses = 0
    while True:
        try:
            m = api(args.project, f"testMatrices/{matrix}")
            misses = 0
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, subprocess.TimeoutExpired) as e:
            misses += 1
            if misses >= UNREACHABLE_AFTER:
                say(f"UNREACHABLE: could not read {matrix} {misses} times in a row ({e}). I could not check; nothing is known.")
                sys.exit(5)
            time.sleep(POLL_SECS)
            continue
        states = []
        for ex in m.get("testExecutions", []):
            dev = ex["environment"]["androidDevice"]
            model, version, state = dev["androidModelId"], dev["androidVersionId"], ex.get("state", "?")
            states.append((model, version, state))
            if state not in OPEN_STATES and model not in reported:
                reported.add(model)
                if state == "FINISHED":
                    ok, why = phone_report(args.project, results, dev)
                else:
                    ok, why = False, f"execution {state}"
                any_failed |= not ok
                say(f"{'PASS' if ok else 'FAIL'} {model} (API {version}): {why}")
        open_ = [f"{mo}={st}" for mo, _, st in states if st in OPEN_STATES]
        if reported and (stop_at_first or not open_):
            if open_:
                say(f"STILL OPEN: {' '.join(open_)}")
                say(f"KEEP WATCHING: scripts/testlab-run.py watch --project {args.project} --matrix {matrix} "
                    f"--results {results} --deadline-min {args.deadline_min}")
            else:
                say(f"MATRIX DONE: {matrix}")
            sys.exit(10 if any_failed else 0)
        if time.time() > deadline:
            try:
                api(args.project, f"testMatrices/{matrix}:cancel", method="POST")
                cancelled = "cancelled"
            except Exception as e:  # noqa: BLE001 - the deadline line must print whatever the cancel did
                cancelled = f"cancel FAILED ({e}); cancel it in the Console"
            say(f"DEADLINE {args.deadline_min} min: {cancelled}. Never ran or never finished: {' '.join(open_) or 'none'}")
            sys.exit(3)
        time.sleep(POLL_SECS)


def main(argv: list[str]) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="mode", required=True)
    for name in ("run", "watch"):
        s = sub.add_parser(name)
        s.add_argument("--project", choices=sorted(PROJECTS), required=True)
        s.add_argument("--deadline-min", type=int, default=20)
    run = sub.choices["run"]
    run.add_argument("--app", required=True)
    run.add_argument("--test", required=True)
    run.add_argument("--target", required=True, help='"class com.x.Y" or "class com.x.Y#method"')
    run.add_argument("--device", action="append", required=True, help="model=ID,version=API[,locale=,orientation=]")
    run.add_argument("--env", action="append", default=[], help="instrumentation argument key=value")
    run.add_argument("--pull", default="", help="device directory to pull, e.g. /sdcard/Download")
    run.add_argument("--timeout", default="5m")
    run.add_argument("--history", default="enviouswispr-device-tests")
    run.add_argument("--runner", default="com.envi.wispr.test/androidx.test.runner.AndroidJUnitRunner")
    run.add_argument("--local-serial", default=os.environ.get("ANDROID_SERIAL", "emulator-5554"))
    run.add_argument("--local-timeout", type=int, default=600)
    run.add_argument("--skip-local", default="", help="reason the emulator cannot run this test (printed)")
    w = sub.choices["watch"]
    w.add_argument("--matrix", required=True)
    w.add_argument("--results", required=True)
    args = p.parse_args(argv)
    if args.mode == "run":
        if not args.target.startswith(("class ", "package ")):
            p.error('--target must start with "class " or "package "')
        local_gate(args)
        matrix, results = submit(args)
        say(f"SUBMITTED {matrix} results {results}")
        watch(args, matrix, results, stop_at_first=True)
    else:
        watch(args, args.matrix, args.results, stop_at_first=False)


if __name__ == "__main__":
    main(sys.argv[1:])
