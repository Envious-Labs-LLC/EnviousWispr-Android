"""Harness Contract tests for scripts/testlab-run.py (#505), against real Test Lab outputs from 2026-11-02.

Fixtures in scripts/testdata/testlab/ are verbatim downloads: pass.xml (Motorola edge 50 fusion, run 2), fail.xml
(Galaxy S24, run 3) and submit.txt (the gcloud lines of run 3). Expected values are literals.

Run: python3 -I scripts/test_testlab_run.py
"""
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

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


if __name__ == "__main__":
    sys.exit(0 if unittest.main(exit=False).result.wasSuccessful() else 1)
