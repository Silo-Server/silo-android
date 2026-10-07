#!/usr/bin/env python3

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


SCRIPT_DIR = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ci_routing", SCRIPT_DIR / "ci-routing.py")
routing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(routing)


class DiffRoutingTest(unittest.TestCase):
    def test_document_and_full_validation_fixtures(self):
        fixtures = json.loads((SCRIPT_DIR / "fixtures/ci-routing.json").read_text())
        for fixture in fixtures:
            with self.subTest(fixture["case"]):
                self.assertEqual(
                    fixture["mode"], routing.classify_diff(fixture["diff"].encode())[0]
                )

    def test_invalid_path_encoding_runs_all_jobs(self):
        self.assertEqual("full", routing.classify_diff(b"M\0docs/plans/\xff.md\0")[0])

    def test_manual_dispatch_runs_all_jobs_without_needing_a_diff(self):
        self.assertEqual("full", routing.classify_environment({"GITHUB_EVENT_NAME": "workflow_dispatch"})[0])

    def test_only_explicit_ffmpeg_dispatch_selects_ffmpeg(self):
        for event in ("workflow_dispatch", "pull_request", "push", "unknown"):
            with self.subTest(event=event):
                expected = "ffmpeg" if event == "workflow_dispatch" else "full"
                self.assertEqual(expected, routing.classify_environment({
                    "GITHUB_EVENT_NAME": event, "SILO_CI_FFMPEG_ONLY": "true"
                })[0])

    def test_invalid_dispatch_input_runs_all_jobs(self):
        self.assertEqual("full", routing.classify_environment({
            "GITHUB_EVENT_NAME": "workflow_dispatch", "SILO_CI_FFMPEG_ONLY": "yes"
        })[0])

    def test_unavailable_event_runs_all_jobs(self):
        for event in ("push", "pull_request", "unknown", ""):
            with self.subTest(event=event):
                self.assertEqual("full", routing.classify_environment({"GITHUB_EVENT_NAME": event})[0])

    def test_timeout_runs_all_jobs(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / "event.json"
            event.write_text(json.dumps({"before": "a" * 40}))
            with patch.object(routing, "git", side_effect=subprocess.TimeoutExpired("git", 30)):
                self.assertEqual("full", routing.classify_environment({
                    "GITHUB_EVENT_NAME": "push", "GITHUB_EVENT_PATH": str(event), "GITHUB_SHA": "b" * 40
                })[0])


class GitRoutingTest(unittest.TestCase):
    """Exercise the CLI against real commits and a shallow local checkout."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "source"
        self.source.mkdir()
        self.run_git("init", "--quiet")
        (self.source / "README.md").write_text("Original documentation\n")
        (self.source / "build.gradle.kts").write_text("// build inputs\n")
        self.run_git("add", ".")
        self.base = self.commit("base")

    def run_git(self, *arguments, cwd=None, check=True):
        return subprocess.run(
            ["git", "-c", "user.name=CI Fixture", "-c", "user.email=ci@example.invalid", *arguments],
            cwd=cwd or self.source, check=check, capture_output=True, text=True,
            env={**os.environ, "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": os.devnull},
        )

    def commit(self, message):
        self.run_git("commit", "--quiet", "-am", message)
        return self.run_git("rev-parse", "HEAD").stdout.strip()

    def classify(self, *, before=None, event_name="push", head_override=None, invalid_event=False):
        checkout = self.root / "checkout"
        self.run_git("clone", "--quiet", "--depth=1", self.source.as_uri(), str(checkout))
        head = self.run_git("rev-parse", "HEAD", cwd=checkout).stdout.strip()
        event = self.root / "event.json"
        base = self.base if before is None else before
        payload = {"before": base} if event_name == "push" else {"pull_request": {"base": {"sha": base}}}
        event.write_text("{" if invalid_event else json.dumps(payload))
        output = self.root / "output.txt"
        process = subprocess.run(
            ["python3", str(SCRIPT_DIR / "ci-routing.py")], cwd=checkout,
            capture_output=True, text=True, check=True,
            env={**os.environ, "GITHUB_EVENT_NAME": event_name, "GITHUB_EVENT_PATH": str(event),
                 "GITHUB_SHA": head_override or head, "GITHUB_OUTPUT": str(output),
                 "SILO_CI_FFMPEG_ONLY": "false"},
        )
        result = json.loads(process.stdout)
        saved = dict(line.split("=", 1) for line in output.read_text().splitlines())
        self.assertEqual({key: value for key, value in result.items() if key != "reason"}, saved)
        return result, checkout

    def test_fetches_exact_before_commit_from_shallow_checkout(self):
        (self.source / "README.md").write_text("Updated documentation\n")
        self.commit("docs")
        result, checkout = self.classify()
        self.assertEqual("docs", result["mode"])
        self.assertEqual(0, self.run_git("cat-file", "-e", f"{self.base}^{{commit}}", cwd=checkout).returncode)
        self.assertEqual("false", result["unit_tests"])

    def test_pull_request_uses_exact_base_commit(self):
        (self.source / "README.md").write_text("Updated documentation\n")
        self.commit("docs")
        self.assertEqual("docs", self.classify(event_name="pull_request")[0]["mode"])

    def test_mixed_code_and_documentation_runs_all_jobs(self):
        (self.source / "README.md").write_text("Updated documentation\n")
        (self.source / "build.gradle.kts").write_text("// changed build inputs\n")
        self.commit("mixed")
        result, _ = self.classify()
        self.assertEqual("full", result["mode"])
        for key in ("unit_tests", "lint", "release_readiness"):
            self.assertEqual("true", result[key])

    def test_deleted_documentation_runs_all_jobs(self):
        self.run_git("rm", "README.md")
        self.commit("delete docs")
        self.assertEqual("full", self.classify()[0]["mode"])

    def test_renamed_documentation_runs_all_jobs(self):
        self.run_git("mv", "README.md", "FEATURES.md")
        self.commit("rename docs")
        self.assertEqual("full", self.classify()[0]["mode"])

    def test_missing_base_runs_all_jobs(self):
        self.assertEqual("full", self.classify(before="f" * 40)[0]["mode"])

    def test_new_branch_runs_all_jobs(self):
        self.assertEqual("full", self.classify(before="0" * 40)[0]["mode"])

    def test_wrong_checkout_runs_all_jobs(self):
        self.assertEqual("full", self.classify(head_override="f" * 40)[0]["mode"])

    def test_invalid_event_json_runs_all_jobs(self):
        self.assertEqual("full", self.classify(invalid_event=True)[0]["mode"])

    def test_added_document_symlink_runs_all_jobs(self):
        (self.source / "docs/plans").mkdir(parents=True)
        (self.source / "docs/plans/input.md").symlink_to("../../build.gradle.kts")
        self.run_git("add", ".")
        self.commit("symlink docs")
        self.assertEqual("full", self.classify()[0]["mode"])

    def test_executable_document_runs_all_jobs(self):
        self.run_git("update-index", "--chmod=+x", "README.md")
        self.run_git("commit", "--quiet", "-m", "executable docs")
        self.assertEqual("full", self.classify()[0]["mode"])


if __name__ == "__main__":
    unittest.main()
