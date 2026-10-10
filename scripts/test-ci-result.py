#!/usr/bin/env python3

import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import unittest


SCRIPT_DIR = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ci_result", SCRIPT_DIR / "ci-result.py")
result = importlib.util.module_from_spec(spec)
spec.loader.exec_module(result)
FIXTURES = json.loads((SCRIPT_DIR / "fixtures/ci-result.json").read_text())


class AggregateResultTest(unittest.TestCase):
    def test_successful_full_docs_and_ffmpeg_modes(self):
        for mode, needs in FIXTURES.items():
            with self.subTest(mode=mode):
                self.assertEqual(mode, result.check_results(needs))

    def test_each_selected_job_must_succeed(self):
        for mode, jobs in (("full", ("unit-tests", "lint", "release-readiness")), ("ffmpeg", ("ffmpeg-aar",))):
            for job in jobs:
                for outcome in ("skipped", "failure", "cancelled", "unknown", None):
                    with self.subTest(mode=mode, job=job, outcome=outcome):
                        needs = copy.deepcopy(FIXTURES[mode])
                        needs[job]["result"] = outcome
                        with self.assertRaises(ValueError):
                            result.check_results(needs)

    def test_unselected_jobs_must_be_intentionally_skipped(self):
        for mode, jobs in (("full", ("ffmpeg-aar",)), ("docs", ("unit-tests", "lint", "release-readiness", "ffmpeg-aar")),
                           ("ffmpeg", ("unit-tests", "lint", "release-readiness"))):
            for job in jobs:
                for outcome in ("success", "failure", "cancelled", "unknown", None):
                    with self.subTest(mode=mode, job=job, outcome=outcome):
                        needs = copy.deepcopy(FIXTURES[mode])
                        needs[job]["result"] = outcome
                        with self.assertRaises(ValueError):
                            result.check_results(needs)

    def test_classifier_must_succeed_in_every_mode(self):
        for mode in FIXTURES:
            for outcome in ("skipped", "failure", "cancelled", None):
                with self.subTest(mode=mode, outcome=outcome):
                    needs = copy.deepcopy(FIXTURES[mode])
                    needs["changes"]["result"] = outcome
                    with self.assertRaises(ValueError):
                        result.check_results(needs)

    def test_outputs_must_be_complete_and_consistent(self):
        for mode in FIXTURES:
            for key in FIXTURES[mode]["changes"]["outputs"]:
                with self.subTest(mode=mode, missing=key):
                    needs = copy.deepcopy(FIXTURES[mode])
                    del needs["changes"]["outputs"][key]
                    with self.assertRaises(ValueError):
                        result.check_results(needs)
                for invalid in ("", "unknown", True, False, None):
                    with self.subTest(mode=mode, key=key, invalid=invalid):
                        needs = copy.deepcopy(FIXTURES[mode])
                        needs["changes"]["outputs"][key] = invalid
                        with self.assertRaises(ValueError):
                            result.check_results(needs)

    def test_docs_mode_cannot_turn_on_a_validation_job(self):
        for key in ("unit_tests", "lint", "release_readiness", "ffmpeg_aar"):
            with self.subTest(key=key):
                needs = copy.deepcopy(FIXTURES["docs"])
                needs["changes"]["outputs"][key] = "true"
                with self.assertRaises(ValueError):
                    result.check_results(needs)

    def test_incomplete_dependency_payload_is_rejected(self):
        for job in FIXTURES["full"]:
            with self.subTest(job=job):
                needs = copy.deepcopy(FIXTURES["full"])
                del needs[job]
                with self.assertRaises(ValueError):
                    result.check_results(needs)
        for needs in (None, [], {}, {**FIXTURES["full"], "unexpected": {"result": "success"}}):
            with self.subTest(needs=needs):
                with self.assertRaises(ValueError):
                    result.check_results(needs)

    def test_cli_exits_nonzero_for_failed_jobs_and_invalid_json(self):
        failed = copy.deepcopy(FIXTURES["full"])
        failed["release-readiness"]["result"] = "failure"
        for payload in (json.dumps(failed), "{", "null"):
            with self.subTest(payload=payload):
                process = self.run_cli(payload)
                self.assertEqual(1, process.returncode)
                self.assertIn("Android CI failed", process.stderr)

    def test_cli_accepts_only_valid_special_mode_results(self):
        for mode in ("docs", "ffmpeg"):
            with self.subTest(mode=mode):
                process = self.run_cli(json.dumps(FIXTURES[mode]))
                self.assertEqual(0, process.returncode, process.stderr)
                self.assertIn(f"Android CI passed ({mode})", process.stdout)

    def run_cli(self, payload):
        return subprocess.run(
            ["python3", str(SCRIPT_DIR / "ci-result.py")], capture_output=True, text=True,
            env={**os.environ, "SILO_CI_NEEDS_JSON": payload},
        )


if __name__ == "__main__":
    unittest.main()
