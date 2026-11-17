"""Harness Contract tests for scripts/testlab-run.py (#505), against real Test Lab outputs from 2026-11-02.

Fixtures in scripts/testdata/testlab/ are verbatim downloads: pass.xml (Motorola edge 50 fusion, run 2), fail.xml
(Galaxy S24, run 3) and submit.txt (the gcloud lines of run 3). Expected values are literals.

Run: python3 -I scripts/test_testlab_run.py
"""
from __future__ import annotations

import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("testlab_run", HERE / "testlab-run.py")
tl = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tl)
DATA = HERE / "testdata" / "testlab"


class TestlabRunTest(unittest.TestCase):
    def test_a_real_passing_result_reads_as_passed(self):
        self.assertEqual(tl.junit_verdict((DATA / "pass.xml").read_text()), (True, "1 passed"))

    def test_a_real_failing_result_names_the_assertion(self):
        self.assertEqual(
            tl.junit_verdict((DATA / "fail.xml").read_text()),
            (False, "java.lang.AssertionError: RESULT=ALLOW_FULLY_COVERED"),
        )

    def test_empty_truncated_split_or_skipped_results_never_read_as_passed(self):
        self.assertEqual(tl.junit_verdict(""), (False, "no test result written"))
        self.assertEqual(tl.junit_verdict("<testsuite tests='1' failures='0'>"), (False, "invalid XML"))
        self.assertEqual(tl.junit_verdict("<testsuite tests='0' failures='0'/>"), (False, "zero tests ran"))
        self.assertEqual(tl.junit_verdict('<testsuite tests="1" failures="0" skipped="1"/>'), (False, "1 of 1 skipped"))
        two = "<testsuites><testsuite tests='1' failures='0'/><testsuite tests='1' failures='1'/></testsuites>"
        self.assertEqual(tl.junit_verdict(two), (False, "unsupported suite count 2"))

    def test_one_model_on_two_android_versions_is_two_results(self):
        def ex(eid, version, state):
            return {"id": eid, "state": state, "environment": {"androidDevice": {"androidModelId": "a35x", "androidVersionId": version}}}
        m = {"state": "FINISHED", "testExecutions": [ex("1", "34", "FINISHED"), ex("2", "35", "FINISHED")]}
        self.assertEqual([e["id"] for e in tl.newly_closed(m, set())], ["1", "2"])
        self.assertEqual([e["id"] for e in tl.newly_closed(m, {"1"})], ["2"])
        self.assertFalse(tl.matrix_done(m, {"1"}))
        self.assertTrue(tl.matrix_done(m, {"1", "2"}))

    def test_completion_needs_the_matrix_to_say_finished_with_executions(self):
        self.assertFalse(tl.matrix_done({"state": "RUNNING", "testExecutions": [{"id": "1"}]}, {"1"}))
        self.assertFalse(tl.matrix_done({"state": "FINISHED", "testExecutions": []}, set()))
        self.assertFalse(tl.matrix_done({"state": "FINISHED"}, {"1"}))

    def test_retries_and_shards_are_refused(self):
        self.assertEqual(tl.unsupported({"testExecutions": [{"id": "1"}]}), "")
        # The real shape of an UNSHARDED execution (matrix-311gptl8gf02u, 2026-11-02).
        self.assertEqual(tl.unsupported({"testExecutions": [{"id": "1", "shard": {"numShards": 1}}]}), "")
        self.assertNotEqual(tl.unsupported({"flakyTestAttempts": 1}), "")
        self.assertNotEqual(tl.unsupported({"testExecutions": [{"id": "1", "shard": {"shardIndex": 0, "numShards": 2}}]}), "")

    def test_submission_output_yields_matrix_and_results_folder(self):
        self.assertEqual(
            tl.parse_submission((DATA / "submit.txt").read_text()),
            ("matrix-33kf3r2wbpc6g", "gs://test-lab-2crxayvf4t0aq-nvbxw8j14cj7a/2026-11-02_21:58:26.955894_XVWN"),
        )
        with self.assertRaises(ValueError):
            tl.parse_submission("ERROR: (gcloud.firebase.test.android.run) quota exceeded")

    def test_results_folder_and_device_flag_match_test_lab_naming(self):
        self.assertEqual(
            tl.device_dir({"androidModelId": "SC-51E", "androidVersionId": "36", "locale": "en", "orientation": "portrait"}),
            "SC-51E-36-en-portrait",
        )
        self.assertEqual(tl.full_device("model=frankel,version=36"), "model=frankel,version=36,locale=en,orientation=portrait")

    def test_build_submit_cmd_omits_other_files_when_absent(self):
        args = tl.parse_args([
            "run", "--project", "business", "--app", "app.apk", "--test", "test.apk",
            "--target", "class com.envi.wispr.polish.PolishServiceDeviceTest#s1PolishesTextOnAutomaticStandardBackend",
            "--device", "model=frankel,version=36",
        ])
        cmd = tl.build_submit_cmd(args)
        self.assertFalse(any(flag.startswith("--other-files=") for flag in cmd))

    def test_build_submit_cmd_forwards_repeatable_other_files_to_gcloud(self):
        args = tl.parse_args([
            "run", "--project", "business", "--app", "app.apk", "--test", "test.apk",
            "--target", "class com.envi.wispr.polish.PolishServiceDeviceTest#s1PolishesTextOnAutomaticStandardBackend",
            "--device", "model=frankel,version=36",
            "--env", "firebaseS1Fixture=true",
            "--other-file", "/data/local/tmp/s1-mini-q4_k_m.gguf=/tmp/s1.gguf",
            "--other-file", "/sdcard/wispr/extra.txt=/tmp/extra.txt",
        ])
        cmd = tl.build_submit_cmd(args)
        self.assertEqual(cmd[:6], ["gcloud", "--configuration=business", "--project=ageless-domain-493017-j8", "firebase", "test", "android"])
        self.assertIn("--environment-variables=firebaseS1Fixture=true", cmd)
        self.assertIn(
            "--other-files=/data/local/tmp/s1-mini-q4_k_m.gguf=/tmp/s1.gguf,/sdcard/wispr/extra.txt=/tmp/extra.txt",
            cmd,
        )

    def test_parse_args_rejects_invalid_or_duplicate_other_files(self):
        base = [
            "run", "--project", "business", "--app", "app.apk", "--test", "test.apk",
            "--target", "class com.envi.wispr.Foo", "--device", "model=frankel,version=36",
        ]
        bad_cases = [
            ["--other-file", "missing_equals"],
            ["--other-file", "relative/path.gguf=/tmp/a.gguf"],
            ["--other-file", "/data/local/tmp/=/tmp/a.gguf"],
            ["--other-file", "/data/local/tmp/a.gguf="],
            ["--other-file", "/data/local/tmp/a,b.gguf=/tmp/a.gguf"],
            ["--other-file", "/data/local/tmp/a.gguf=/tmp/one.gguf", "--other-file", "/data/local/tmp/a.gguf=/tmp/two.gguf"],
        ]
        for extra in bad_cases:
            with self.subTest(extra=extra), redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as ctx:
                tl.parse_args(base + extra)
            self.assertEqual(ctx.exception.code, 2)

    def test_local_gate_pushes_staged_files_before_instrumentation(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / "s1-mini-q4_k_m.gguf"
            fixture.write_bytes(b"gguf-bytes")
            args = tl.parse_args([
                "run", "--project", "business", "--app", "app.apk", "--test", "test.apk",
                "--target", "class com.envi.wispr.polish.PolishServiceDeviceTest#s1PolishesTextOnAutomaticStandardBackend",
                "--device", "model=frankel,version=36",
                "--local-serial", "emulator-5554",
                "--env", "firebaseS1Fixture=true",
                "--other-file", f"/data/local/tmp/sub/s1-mini-q4_k_m.gguf={fixture}",
            ])
            recorded: list[list[str]] = []

            def fake_sh(cmd: list[str], timeout: int = 600):
                recorded.append(cmd)
                if "install" in cmd:
                    return subprocess.CompletedProcess(cmd, 0, stdout="Success\n", stderr="")
                if len(cmd) >= 5 and cmd[3] == "shell" and "mkdir -p" in cmd[4]:
                    return subprocess.CompletedProcess(cmd, 0, stdout="RC=0\n", stderr="")
                if "am" in cmd and "instrument" in cmd:
                    return subprocess.CompletedProcess(cmd, 0, stdout="OK (1 test)\n", stderr="")
                return subprocess.CompletedProcess(cmd, 0, stdout="", stderr="")

            with mock.patch.object(tl, "sh", side_effect=fake_sh):
                tl.local_gate(args)

        self.assertEqual(
            recorded,
            [
                ["adb", "-s", "emulator-5554", "install", "-r", "app.apk"],
                ["adb", "-s", "emulator-5554", "install", "-r", "test.apk"],
                ["adb", "-s", "emulator-5554", "shell", "mkdir -p /data/local/tmp/sub; echo RC=$?"],
                ["adb", "-s", "emulator-5554", "push", str(fixture), "/data/local/tmp/sub/s1-mini-q4_k_m.gguf"],
                [
                    "adb", "-s", "emulator-5554", "shell", "am", "instrument", "-w",
                    "-e", "class", "com.envi.wispr.polish.PolishServiceDeviceTest#s1PolishesTextOnAutomaticStandardBackend",
                    "-e", "firebaseS1Fixture", "true",
                    "com.envi.wispr.test/androidx.test.runner.AndroidJUnitRunner",
                ],
            ],
        )

    def test_local_gate_fails_fast_on_missing_remote_or_failed_push(self):
        base = [
            "run", "--project", "business", "--app", "app.apk", "--test", "test.apk",
            "--target", "class com.envi.wispr.Foo", "--device", "model=frankel,version=36",
        ]
        with mock.patch.object(tl, "sh") as mock_sh:
            with self.assertRaises(OSError):
                tl.local_gate(tl.parse_args(base + ["--other-file", "/data/local/tmp/x.gguf=/nonexistent/x.gguf"]))
            with self.assertRaises(ValueError):
                tl.local_gate(tl.parse_args(base + ["--other-file", "/data/local/tmp/x.gguf=gs://bucket/x.gguf"]))
            mock_sh.assert_not_called()

        with tempfile.TemporaryDirectory() as tmp:
            fixture = Path(tmp) / "x.gguf"
            fixture.write_bytes(b"data")
            args = tl.parse_args(base + ["--other-file", f"/data/local/tmp/x.gguf={fixture}"])
            seen: list[list[str]] = []

            def fail_push(cmd: list[str], timeout: int = 600):
                seen.append(cmd)
                if "install" in cmd:
                    return subprocess.CompletedProcess(cmd, 0, stdout="Success\n", stderr="")
                if len(cmd) >= 5 and cmd[3] == "shell" and "mkdir -p" in cmd[4]:
                    return subprocess.CompletedProcess(cmd, 0, stdout="RC=0\n", stderr="")
                if "push" in cmd:
                    return subprocess.CompletedProcess(cmd, 1, stdout="", stderr="adb: error: read-only")
                raise AssertionError(f"unexpected command after push failure: {cmd}")

            with mock.patch.object(tl, "sh", side_effect=fail_push), self.assertRaises(SystemExit) as ctx:
                tl.local_gate(args)
            self.assertEqual(ctx.exception.code, 4)
            self.assertFalse(any("instrument" in c for c in seen))

            mk_seen: list[list[str]] = []

            def fail_mkdir(cmd: list[str], timeout: int = 600):
                mk_seen.append(cmd)
                if "install" in cmd:
                    return subprocess.CompletedProcess(cmd, 0, stdout="Success\n", stderr="")
                if len(cmd) >= 5 and cmd[3] == "shell" and "mkdir -p" in cmd[4]:
                    return subprocess.CompletedProcess(cmd, 0, stdout="mkdir: Permission denied\nRC=1\n", stderr="")
                raise AssertionError(f"unexpected command after mkdir failure: {cmd}")

            with mock.patch.object(tl, "sh", side_effect=fail_mkdir), self.assertRaises(SystemExit) as ctx:
                tl.local_gate(args)
            self.assertEqual(ctx.exception.code, 4)
            self.assertFalse(any("push" in c or "instrument" in c for c in mk_seen))


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
