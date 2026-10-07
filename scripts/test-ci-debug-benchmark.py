#!/usr/bin/env python3
"""Offline failure, coverage, routing and controller fixtures; no app builds."""

import copy
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("benchmark", ROOT / "scripts/ci-debug-benchmark.py")
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)


def environment(layout="separate"):
    return {"GITHUB_REPOSITORY": "Silo-Server/silo-android", "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REF": benchmark.REF, "SILO_CI_DEBUG_LAYOUT": layout,
            "SILO_CI_UNIT_WORKERS": "2", "SILO_CI_LINT_WORKERS": "2",
            "SILO_CI_UNIT_PARALLEL": "false", "SILO_CI_LINT_PARALLEL": "false", "SILO_CI_SHARED_FORKS": "1"}


def dependencies(layout, mode="full"):
    needs = {job: {"result": "skipped"} for job in ("unit-tests", "lint", "combined-debug", "release-readiness", "ffmpeg-aar")}
    needs["changes"] = {"result": "success", "outputs": benchmark.load("ci_routing").outputs(mode)}
    if mode == "full":
        needs["release-readiness"]["result"] = "success"
        if layout == "combined":
            needs["combined-debug"] = {"result": "success", "outputs": {"unit_proof": "true", "lint_proof": "true"}}
        else:
            needs["unit-tests"] = {"result": "success", "outputs": {"unit_proof": "true"}}
            needs["lint"] = {"result": "success", "outputs": {"lint_proof": "true"}}
    if mode == "ffmpeg":
        needs["ffmpeg-aar"]["result"] = "success"
    return needs


class GuardAndResults(unittest.TestCase):
    def test_manual_guard_accepts_defaults_and_equal_opt_in_settings(self):
        for layout in ("separate", "combined"):
            env = environment(layout)
            self.assertEqual(benchmark.guard(env), layout)
            env.update(SILO_CI_UNIT_PARALLEL="true", SILO_CI_LINT_PARALLEL="true", SILO_CI_SHARED_FORKS="2")
            self.assertEqual(benchmark.guard(env), layout)

    def test_wrong_authority_and_unknown_settings_are_rejected(self):
        for key, value in (("GITHUB_REPOSITORY", "other/repo"), ("GITHUB_EVENT_NAME", "pull_request"),
                           ("GITHUB_REF", "refs/heads/main"), ("SILO_CI_DEBUG_LAYOUT", "unknown"),
                           ("SILO_CI_UNIT_WORKERS", "4"), ("SILO_CI_LINT_WORKERS", "4"),
                           ("SILO_CI_UNIT_WORKERS", "8"), ("SILO_CI_LINT_PARALLEL", "yes"), ("SILO_CI_SHARED_FORKS", "3")):
            with self.subTest(key=key):
                env = environment();env[key] = value
                with self.assertRaises(ValueError): benchmark.guard(env)

    def test_selected_parallel_unit_serial_lint_control_is_accepted_for_both_layouts(self):
        for layout in ("separate", "combined"):
            env = environment(layout);env["SILO_CI_UNIT_PARALLEL"] = "true"
            self.assertEqual(benchmark.guard(env), layout)

    def test_combined_guard_discloses_effective_graph_and_separate_lint_request(self):
        env = {**os.environ, **environment("combined"), "SILO_CI_UNIT_PARALLEL": "true"}
        result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-debug-benchmark.py"), "guard"], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("Combined Tests and lint use unit_parallel=true; separate lint_parallel=false", result.stdout)

    def test_four_workers_are_declined_in_both_layouts(self):
        for layout in ("separate", "combined"):
            env = environment(layout)
            env.update(SILO_CI_UNIT_WORKERS="4", SILO_CI_LINT_WORKERS="4")
            with self.assertRaises(ValueError): benchmark.guard(env)

    def test_full_docs_and_ffmpeg_layout_contracts(self):
        for layout in ("separate", "combined"):
            for mode in ("full", "docs", "ffmpeg"):
                self.assertEqual(benchmark.result(dependencies(layout, mode), layout), mode)

    def test_failure_cancellation_and_unexpected_skip_never_pass(self):
        for layout in ("separate", "combined"):
            jobs = ("changes", "release-readiness", "unit-tests", "lint") if layout == "separate" else ("changes", "release-readiness", "combined-debug")
            for job in jobs:
                for failure in ("failure", "cancelled", "skipped", "timed_out"):
                    with self.subTest(layout=layout, job=job, failure=failure):
                        needs = dependencies(layout);needs[job]["result"] = failure
                        with self.assertRaises(ValueError): benchmark.result(needs, layout)

    def test_missing_lint_proof_is_not_replaced_by_job_success(self):
        for layout, job in (("combined", "combined-debug"), ("separate", "lint")):
            needs = dependencies(layout);needs[job]["outputs"] = {"unit_proof": "true"}
            with self.assertRaises(ValueError): benchmark.result(needs, layout)

    def test_malformed_outputs_and_extra_missing_dependencies_fail_closed(self):
        for layout in ("separate", "combined"):
            needs = dependencies(layout)
            mutations = []
            wrong = copy.deepcopy(needs);wrong["changes"]["outputs"]["lint"] = "false";mutations.append(wrong)
            wrong = copy.deepcopy(needs);wrong.pop("release-readiness");mutations.append(wrong)
            wrong = copy.deepcopy(needs);wrong["extra"] = {"result": "success"};mutations.append(wrong)
            wrong = copy.deepcopy(needs);wrong["lint"] = None;mutations.append(wrong)
            selected = "combined-debug" if layout == "combined" else "unit-tests"
            wrong = copy.deepcopy(needs);wrong[selected]["outputs"] = "true";mutations.append(wrong)
            for wrong in mutations:
                with self.assertRaises(ValueError): benchmark.result(wrong, layout)

    def test_unselected_success_and_partial_rerun_cannot_hide_lint_failure(self):
        for layout in ("separate", "combined"):
            needs = dependencies(layout, "docs");needs["combined-debug"]["result"] = "success"
            with self.assertRaises(ValueError): benchmark.result(needs, layout)
        needs = dependencies("separate");needs["lint"]["result"] = "failure"
        with self.assertRaises(ValueError): benchmark.result(needs, "separate")
        needs["lint"]["result"] = "success"
        self.assertEqual(benchmark.result(needs, "separate"), "full")

    def test_cli_guard_nonzero_precedes_native_work(self):
        env = {**os.environ, **environment()};env["GITHUB_EVENT_NAME"] = "push"
        result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-debug-benchmark.py"), "guard"], env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("exact branch", result.stdout)

    def test_cli_aggregate_cannot_pass_failure_or_missing_lint_proof(self):
        success = dependencies("combined")
        failed = copy.deepcopy(success);failed["combined-debug"]["result"] = "failure"
        missing = copy.deepcopy(success);missing["combined-debug"]["outputs"].pop("lint_proof")
        for needs, expected in ((success, 0), (failed, 1), (missing, 1)):
            env = {**os.environ, "SILO_CI_DEBUG_LAYOUT": "combined", "SILO_CI_NEEDS_JSON": json.dumps(needs)}
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-debug-benchmark.py"), "result"], env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, expected)


class Coverage(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name);self.started = time.time_ns()
        self.profile = self.root / "build/reports/profile/profile-fixture.html"
        self.profile.parent.mkdir(parents=True)
        self.tasks = {task: "" for task in benchmark.TESTS | benchmark.LINT}
        self.write_profile()
        self.lint_reports = [self.root / module / "build/reports/lint-results-debug.xml"
                             for module in ("android-shared", "androidApp", "androidTvApp")]
        for path in self.lint_reports:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('<issues format="6" by="lint 8.10.1" />')
        self.reports = []
        for index in range(714):
            module = sorted(benchmark.MODULES)[index % 5]
            path = self.root / module / f"build/test-results/testDebugUnitTest/TEST-{index}.xml"
            path.parent.mkdir(parents=True, exist_ok=True)
            count = 7 + (index < 533)
            suite = ET.Element("testsuite", tests=str(count), failures="0", errors="0", skipped="0")
            for case in range(count): ET.SubElement(suite, "testcase", classname=f"Fixture{index}", name=f"case{case}")
            ET.ElementTree(suite).write(path);self.reports.append(path)

    def write_profile(self):
        rows = ''.join(f'<tr><td class="indentPath">{path}</td><td>0.1s</td><td>{result}</td></tr>' for path, result in sorted(self.tasks.items()))
        self.profile.write_text('<html><table>' + rows + '</table></html>')

    def check(self, kind="combined"):
        return benchmark.coverage(self.root, kind, self.started, time.time_ns())

    def test_complete_union_and_original_xml_are_retained(self):
        before = [p.read_bytes() for p in self.reports]
        proof = self.check()
        self.assertEqual((proof["tests"], proof["reports"]), (5531, 714))
        self.assertTrue(proof["unit_proof"] and proof["lint_proof"])
        self.assertEqual(before, [p.read_bytes() for p in self.reports])

    def test_cli_emits_proof_only_after_complete_coverage(self):
        folder = self.root / benchmark.PROOF;folder.mkdir()
        (folder / "combined-start.json").write_text(json.dumps({"started_ns": self.started}))
        output = self.root / "outputs.txt"
        env = {**os.environ, "GITHUB_OUTPUT": str(output)}
        command = [sys.executable, str(ROOT / "scripts/ci-debug-benchmark.py"), "coverage", "--kind", "combined"]
        result = subprocess.run(command, cwd=self.root, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(output.read_text(), "unit_proof=true\nlint_proof=true\n")
        output.write_text("")
        self.tasks.pop(next(iter(benchmark.LINT)));self.write_profile()
        result = subprocess.run(command, cwd=self.root, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(output.read_text(), "")

    def test_unit_and_cache_reused_lint_controls_have_real_coverage(self):
        self.tasks = {task: "" for task in benchmark.TESTS};self.write_profile()
        self.assertTrue(self.check("unit")["unit_proof"])
        self.tasks = {task: "UP-TO-DATE" for task in benchmark.LINT};self.write_profile()
        self.assertTrue(self.check("lint")["lint_proof"])

    def test_actual_agp_unit_and_lint_dependencies_are_not_test_tasks(self):
        # These dependency names occur in the retained Gradle 8.12 cold profiles.
        for module in benchmark.MODULES:
            self.tasks[f":{module}:javaPreCompileDebugUnitTest"] = ""
            self.tasks[f":{module}:lintAnalyzeDebugUnitTest"] = ""
        for module in ("android-shared", "androidApp", "androidTvApp"):
            for task in ("convertXmlValueResourcesForAndroidUnitTest", "copyNonXmlValueResourcesForAndroidUnitTest",
                         "generateResourceAccessorsForAndroidUnitTest", "packageDebugUnitTestForUnitTest",
                         "prepareComposeResourcesTaskForAndroidUnitTest"):
                self.tasks[f":{module}:{task}"] = ""
        self.write_profile()
        self.assertEqual(self.check()["fresh_test_tasks"], sorted(benchmark.TESTS))
        dependencies = {k: v for k, v in self.tasks.items() if k not in benchmark.TESTS | benchmark.LINT}
        self.tasks = {**dependencies, **{task: "" for task in benchmark.TESTS}}
        self.write_profile()
        self.assertEqual(self.check("unit")["fresh_test_tasks"], sorted(benchmark.TESTS))
        self.tasks = {**dependencies, **{task: "" for task in benchmark.LINT}}
        self.write_profile()
        self.assertEqual(self.check("lint")["fresh_test_tasks"], [])

    def test_three_original_lint_reports_are_required_and_cached_timestamps_allowed(self):
        for path in self.lint_reports:
            os.utime(path, ns=(self.started - 1, self.started - 1))
        self.assertEqual(len(self.check()["lint_reports"]), 3)
        for kind in ("lint", "combined"):
            self.tasks = {task: "" for task in benchmark.LINT | (benchmark.TESTS if kind == "combined" else set())}
            self.write_profile()
            for path in self.lint_reports:
                original = path.read_bytes()
                for text in ("", "<bad>", "<testsuite />"):
                    path.write_text(text)
                    with self.assertRaises(ValueError): self.check(kind)
                path.unlink()
                with self.assertRaises(ValueError): self.check(kind)
                path.write_bytes(original)

    def test_stale_or_future_xml_cannot_pass_correct_totals(self):
        for stamp in (self.started - 1, time.time_ns() + 10**12):
            os.utime(self.reports[0], ns=(stamp, stamp))
            with self.assertRaises(ValueError): self.check()

    def test_reused_missing_extra_or_failed_tasks_are_rejected(self):
        original = self.tasks.copy()
        for task, result in ((next(iter(benchmark.TESTS)), "FROM-CACHE"), (next(iter(benchmark.LINT)), "SKIPPED")):
            self.tasks = {**original, task: result};self.write_profile()
            with self.assertRaises(ValueError): self.check()
        self.tasks = {k: v for k, v in original.items() if k != next(iter(benchmark.LINT))};self.write_profile()
        with self.assertRaises(ValueError): self.check()
        self.tasks = {**original, ":shared:testReleaseUnitTest": ""};self.write_profile()
        with self.assertRaises(ValueError): self.check()

    def test_missing_reports_skips_errors_failures_and_inconsistent_counts_fail(self):
        path = self.reports[0];original = path.read_bytes()
        for tag, attribute in (("skipped", "skipped"), ("error", "errors"), ("failure", "failures")):
            suite = ET.fromstring(original);ET.SubElement(suite.find("testcase"), tag);suite.set(attribute, "1");ET.ElementTree(suite).write(path)
            with self.assertRaises(ValueError): self.check()
        suite = ET.fromstring(original);suite.set("tests", "0");ET.ElementTree(suite).write(path)
        with self.assertRaises(ValueError): self.check()
        path.unlink()
        with self.assertRaises(ValueError): self.check()

    def test_absent_stale_duplicate_and_duplicate_task_profiles_fail(self):
        original = self.profile.read_text();self.profile.unlink()
        with self.assertRaises(ValueError): self.check()
        self.profile.write_text(original);os.utime(self.profile, ns=(self.started - 1, self.started - 1))
        with self.assertRaises(ValueError): self.check()
        self.profile.write_text(original)
        other = self.profile.with_name("profile-extra.html");other.write_text(original)
        with self.assertRaises(ValueError): self.check()
        other.unlink();self.profile.write_text(original.replace('</table>', '<tr><td class="indentPath">:shared:testDebugUnitTest</td><td>0.1s</td><td></td></tr></table>'))
        with self.assertRaises(ValueError): self.check()


class Controller(unittest.TestCase):
    def test_manual_read_only_input_can_restore_without_widening_writer_policy(self):
        entry = (ROOT / ".github/workflows/android-build.yml").read_text()
        workflow = (ROOT / ".github/workflows/trusted-linux-ci.yml").read_text()
        self.assertRegex(entry, r"cache_read_only:\n        description: [^\n]+\n        required: false\n        default: true\n        type: boolean")
        self.assertIn("cache_read_only: ${{ github.event_name == 'workflow_dispatch' && inputs.cache_read_only }}", entry)
        self.assertRegex(workflow, r"cache_read_only:\n        type: boolean\n        required: false\n        default: false")
        self.assertIn("SILO_CI_CACHE_READ_ONLY: ${{ !inputs.cache_read_only && inputs.build_cache && (", workflow)
        self.assertEqual(workflow.count("cache-read-only: ${{ env.SILO_CI_CACHE_READ_ONLY }}"), 4)

    def test_registered_entry_is_manual_only_and_defaults_to_control(self):
        entry = (ROOT / ".github/workflows/android-build.yml").read_text()
        self.assertIn("on:\n  workflow_dispatch:", entry)
        self.assertNotRegex(entry, re.compile(r"^  (?:push|pull_request|schedule):", re.M))
        self.assertIn("default: separate", entry)
        self.assertIn("uses: ./.github/workflows/trusted-linux-ci.yml", entry)

    def test_gate_precedes_selection_and_readiness_does_not_depend_on_debug(self):
        workflow = (ROOT / ".github/workflows/trusted-linux-ci.yml").read_text()
        self.assertLess(workflow.index("ci-debug-benchmark.py guard"), workflow.index("ci-routing.py"))
        block = lambda job: re.search(r'^  '+job+r':\n.*?(?=^  [a-z][a-z-]*:\n|\Z)', workflow, re.M | re.S).group(0)
        self.assertIn("needs: changes", block("release-readiness"))
        self.assertNotIn("combined-debug", block("release-readiness"))
        union = block("combined-debug")
        self.assertIn("SILO_CI_PARALLEL_FLAG: ${{ inputs.unit_parallel && '--parallel' || '--no-parallel' }}", union)
        for task in ("testDebugUnitTest", ":android-shared:lintDebug", ":androidApp:lintDebug", ":androidTvApp:lintDebug"):
            self.assertIn(task, union)
        self.assertEqual(union.count("./scripts/ci-gradle.py --max-workers"), 1)
        self.assertNotIn("--continue", union)
        self.assertIn('Xmx4g', union)
        self.assertIn("ci-debug-benchmark.py coverage --kind combined", union)
        self.assertIn("needs: [changes, unit-tests, lint, combined-debug, release-readiness, ffmpeg-aar]", block("android-ci"))


if __name__ == "__main__":
    unittest.main()
