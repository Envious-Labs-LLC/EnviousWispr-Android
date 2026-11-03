#!/usr/bin/env python3
"""The only way to submit a Firebase Test Lab instrumentation matrix from this repository (#505).

Why it exists (2026-11-02, the #477 probe): a matrix launched with a broken test finished on 7 of 8 phones in three
minutes, all red, while the session waited two hours for the eighth phone that never got a device. So:

  1. LOCAL GATE. The same app and test APKs run the same test target on the emulator first; a failure there
     submits nothing. `--skip-local "<reason>"` is the one escape hatch and is printed in the output.
  2. FIRST RESULT. `run` submits asynchronously and exits as soon as the FIRST phone finishes, printing that
     phone's verdict and failure message, and the `watch` command for the rest.
  3. DEADLINE. `watch` follows the matrix to the end; at the deadline it cancels what has not run and names it.
  4. HONEST POLLS. An API that cannot be reached three polls in a row ends the run as UNREACHABLE (and cancels),
     never a verdict. Completion is only what the matrix itself reports, every execution keyed by its id.
  5. NO GUESSING. A matrix with flaky retries or shards is refused: their results do not live in one file.

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
import xml.etree.ElementTree as ET

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
    """(passed, one-line reason) from a test_result_1.xml. Anything but exactly one well-formed suite with at least
    one test and no failure, error or skip is a failure: truncated XML, several suites, zero tests."""
    if not xml.strip():
        return False, "no test result written"
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return False, "invalid XML"
    suites = list(root.iter("testsuite"))
    if len(suites) != 1:
        return False, f"unsupported suite count {len(suites)}"
    attrs = suites[0].attrib
    try:
        tests, failures, errors, skipped = (int(attrs.get(k, "0")) for k in ("tests", "failures", "errors", "skipped"))
    except ValueError:
        return False, "unreadable test counts"
    if tests == 0:
        return False, "zero tests ran"
    if failures or errors:
        # An Element with no children is falsy, so pick with an explicit None test, never `or`.
        node = next(iter(root.iter("failure")), None)
        if node is None:
            node = next(iter(root.iter("error")), None)
        text = "" if node is None else (node.get("message") or node.text or "")
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
    if r.returncode == 0 and re.search(r"^OK \([1-9]\d* tests?\)", out, re.M):
        say(f"LOCAL GATE PASSED on {args.local_serial}")
        return
    say(f"LOCAL GATE FAILED on {args.local_serial}; nothing was submitted. Last output:\n{out.strip()[-1500:]}")
    sys.exit(4)


def submit(args) -> tuple[str, str]:
    config, project = PROJECTS[args.project]
    cmd = ["gcloud", f"--configuration={config}", f"--project={project}", "firebase", "test", "android", "run",
           "--async", "--type=instrumentation", f"--app={args.app}", f"--test={args.test}",
           f"--test-targets={args.target}", f"--timeout={args.timeout}", "--num-flaky-test-attempts=0",
           "--no-use-orchestrator", f"--results-history-name={args.history}",
           f"--test-runner-class={args.runner.split('/', 1)[1]}"]
    cmd += [f"--device={full_device(d)}" for d in args.device]
    if args.env:
        cmd.append("--environment-variables=" + ",".join(args.env))
    if args.pull:
        cmd.append(f"--directories-to-pull={args.pull}")
    try:
        r = sh(cmd, timeout=900)
    except subprocess.TimeoutExpired:
        say("SUBMISSION UNKNOWN: gcloud did not answer in 15 min; a matrix may exist. Check the Console before retrying.")
        sys.exit(2)
    try:
        return parse_submission(r.stdout + r.stderr)
    except ValueError as e:
        say(f"SUBMISSION UNKNOWN: {e}; check the Console before retrying.\n{(r.stdout + r.stderr).strip()[-1500:]}")
        found = re.search(r"\b(matrix-[a-z0-9]+)\b", r.stdout + r.stderr)
        if found:
            say(cancel(args, found.group(1)))
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
        # Unreadable is not failed: a storage or sign-in error must never be reported as a red phone.
        raise OSError(f"result unreadable at {folder}: {r.stderr.strip()[-160:]}")
    return junit_verdict(r.stdout)


def unsupported(m: dict) -> str:
    """Why this matrix's results cannot be read by folder and test_result_1.xml, or "" when they can."""
    if m.get("flakyTestAttempts", 0):
        return "flaky-test retries write more than one result file"
    # An unsharded execution still carries shard {"numShards": 1} (measured 2026-11-02, matrix-311gptl8gf02u).
    if any(int((e.get("shard") or {}).get("numShards", 1)) > 1 for e in m.get("testExecutions", [])):
        return "sharded executions split results across folders"
    return ""


def newly_closed(m: dict, reported: set[str]) -> list[dict]:
    """Executions that left the open states and have not been reported, keyed by execution id (one model can run
    on several Android versions, locales or orientations in one matrix)."""
    return [e for e in m.get("testExecutions", []) if e.get("state") not in OPEN_STATES and e["id"] not in reported]


def matrix_done(m: dict, reported: set[str]) -> bool:
    """Observed completion only: the matrix says FINISHED and every one of its executions was reported."""
    executions = m.get("testExecutions") or []
    return m.get("state") == "FINISHED" and bool(executions) and all(e["id"] in reported for e in executions)


def cancel(args, matrix: str) -> str:
    try:
        api(args.project, f"testMatrices/{matrix}:cancel", method="POST")
        return "cancelled"
    except Exception as e:  # noqa: BLE001 - every exit path must say what the cancel did
        return f"CANCEL NOT CONFIRMED ({e}); cancel {matrix} in the Console"


def watch(args, matrix: str, results: str, stop_at_first: bool) -> None:
    try:
        watch_loop(args, matrix, results, stop_at_first)
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as e:
        say(f"UNREACHABLE: watching {matrix} failed ({type(e).__name__}: {e}); {cancel(args, matrix)}. Nothing is known.")
        sys.exit(5)


def watch_loop(args, matrix: str, results: str, stop_at_first: bool) -> None:
    deadline = time.time() + args.deadline_min * 60
    reported: set[str] = set()
    any_failed = False
    misses = 0
    open_: list[str] = ["(not read yet)"]
    while True:
        if time.time() > deadline:
            say(f"DEADLINE {args.deadline_min} min: {cancel(args, matrix)}. Never ran or never finished: {' '.join(open_) or 'none'}")
            sys.exit(3)
        try:
            m = api(args.project, f"testMatrices/{matrix}")
            misses = 0
        except (OSError, ValueError, subprocess.TimeoutExpired) as e:
            misses += 1
            if misses >= UNREACHABLE_AFTER:
                say(f"UNREACHABLE: could not read {matrix} {misses} times in a row ({e}); {cancel(args, matrix)}. Nothing is known.")
                sys.exit(5)
            time.sleep(POLL_SECS)
            continue
        why_not = unsupported(m)
        if why_not:
            say(f"UNSUPPORTED: {why_not}; this runner cannot verify {matrix}. {cancel(args, matrix)}.")
            sys.exit(2)
        for ex in newly_closed(m, reported):
            # Each result read can take up to 120 s, so the deadline is checked per read, not once per poll.
            if time.time() >= deadline:
                say(f"DEADLINE {args.deadline_min} min: {cancel(args, matrix)}.")
                sys.exit(3)
            reported.add(ex["id"])
            dev = ex["environment"]["androidDevice"]
            state = ex["state"]
            ok, why = phone_report(args.project, results, dev) if state == "FINISHED" else (False, f"execution {state}")
            any_failed |= not ok
            say(f"{'PASS' if ok else 'FAIL'} {device_dir(dev)}: {why}")
        open_ = [f"{device_dir(e['environment']['androidDevice'])}={e.get('state')}"
                 for e in m.get("testExecutions", []) if e.get("state") in OPEN_STATES]
        if reported and stop_at_first and open_:
            say(f"STILL OPEN: {' '.join(open_)}")
            say(f"KEEP WATCHING: scripts/testlab-run.py watch --project {args.project} --matrix {matrix} "
                f"--results {results} --deadline-min {args.deadline_min}")
            sys.exit(10 if any_failed else 0)
        if matrix_done(m, reported):
            summary = m.get("outcomeSummary", "missing")
            any_failed |= summary != "SUCCESS"
            say(f"MATRIX DONE: {matrix} outcome {summary}")
            sys.exit(10 if any_failed else 0)
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
        if any("=" not in v or "," in v for v in args.env):
            p.error("--env takes key=value without commas")
        if "/" not in args.runner:
            p.error("--runner takes <test package>/<runner class>")
        try:
            local_gate(args)
        except (OSError, ValueError, subprocess.TimeoutExpired) as e:
            say(f"LOCAL GATE FAILED: {type(e).__name__}: {e}; nothing was submitted.")
            sys.exit(4)
        matrix, results = submit(args)
        say(f"SUBMITTED {matrix} results {results}")
        watch(args, matrix, results, stop_at_first=True)
    else:
        watch(args, args.matrix, args.results, stop_at_first=False)


if __name__ == "__main__":
    main(sys.argv[1:])
