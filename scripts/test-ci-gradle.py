#!/usr/bin/env python3
"""Fixture checks for worker profiles, sampling privacy, and child exit handling."""

import importlib.util
import io
import json
import os
from pathlib import Path
import re
import select
import shlex
import signal
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
WRAPPER = ROOT / "scripts/ci-gradle.py"
spec = importlib.util.spec_from_file_location("ci_gradle", WRAPPER)
ci = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ci)


class SamplingFixtures(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.proc = self.root / "proc"
        self.proc.mkdir()
        (self.proc / "self").mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def process(self, identifier, name, rss=None):
        directory = self.proc / str(identifier)
        directory.mkdir()
        (directory / "comm").write_text(name + "\n")
        if rss is not None:
            (directory / "status").write_text(f"Name:\t{name}\nVmRSS:\t{rss} kB\n")
        # Neither fixture value may appear in any sampler output.
        (directory / "cmdline").write_text("PRIVATE_ARGUMENT_MARKER")

    def test_available_memory_and_java_sum_ignore_other_processes(self):
        (self.proc / "meminfo").write_text("MemTotal: 8192 kB\nMemAvailable: 4096 kB\n")
        self.process(920001, "java", 100)
        self.process(920002, "java", 250)
        self.process(920003, "python", 900)
        self.assertEqual(ci.memory_value(self.proc, "MemAvailable"), 4096)
        self.assertEqual(ci.java_rss(self.proc), (350, 2, 0))
        sampler = ci.MemorySampler(self.proc, enabled=True)
        sampler.sample()
        (self.proc / "meminfo").write_text("MemAvailable: 2048 kB\n")
        sampler.sample()
        result = sampler.summary()
        self.assertEqual(result["min_mem_available_kib"], 2048)
        self.assertEqual(result["max_summed_java_rss_kib"], 350)
        encoded = json.dumps(result)
        for private in ("920001", "920002", "920003", "PRIVATE_ARGUMENT_MARKER"):
            self.assertNotIn(private, encoded)

    def test_missing_and_malformed_proc_values_are_unavailable(self):
        self.assertIsNone(ci.memory_value(self.proc, "MemAvailable"))
        (self.proc / "meminfo").write_text("MemAvailable: invalid kB\n")
        self.assertIsNone(ci.memory_value(self.proc, "MemAvailable"))
        self.assertEqual(ci.java_rss(self.root / "absent"), (None, None, None))
        self.process(920001, "java")
        self.assertEqual(ci.java_rss(self.proc), (None, 0, 1))

    def test_non_linux_does_not_read_proc(self):
        sampler = ci.MemorySampler(self.proc, enabled=False)
        with patch.object(ci, "memory_value", side_effect=AssertionError("proc read")):
            sampler.sample()
        result = sampler.summary()
        self.assertFalse(result["sampling_available"])
        self.assertEqual(result["sample_count"], 0)
        self.assertIsNone(result["max_summed_java_rss_kib"])

    def test_non_utf8_process_name_is_ignored_without_breaking_samples(self):
        self.process(920001, "java", 100)
        self.process(920002, "unrelated", 250)
        (self.proc / "920002/comm").write_bytes(b"\xff\n")
        self.assertEqual(ci.java_rss(self.proc), (100, 1, 0))
        sampler = ci.MemorySampler(self.proc, enabled=True)
        sampler.sample()
        self.assertEqual(sampler.summary()["max_summed_java_rss_kib"], 100)

    def mount(self, version, root, member):
        mounted = self.root / "mounted"
        mounted.mkdir()
        fs = "cgroup2 cgroup rw" if version == "v2" else "cgroup cgroup rw,memory"
        (self.proc / "self/mountinfo").write_text(f"1 0 0:1 {root} {mounted} rw - {fs}\n")
        membership = f"0::{member}\n" if version == "v2" else f"4:memory:{member}\n"
        (self.proc / "self/cgroup").write_text(membership)
        return mounted

    def test_cgroup_v2_mount_root_and_oom_deltas(self):
        mounted = self.mount("v2", "/host/subtree", "/host/subtree/job")
        group = mounted / "job"
        group.mkdir()
        events = group / "memory.events"
        events.write_text("low 0\nhigh 2\nmax 3\noom 1\noom_kill 1\nprivate 123\n")
        before = ci.cgroup_counters(self.proc)
        events.write_text("low 0\nhigh 2\nmax 4\noom 2\noom_kill 2\n")
        after = ci.cgroup_counters(self.proc)
        self.assertEqual(ci.counter_changes(before, after)["oom_kill"], 1)
        self.assertNotIn("private", before["counters"])
        self.assertNotIn(str(mounted), json.dumps(before))

    def test_cgroup_namespace_root_and_v1(self):
        mounted = self.mount("v1", "/host/subtree", "/")
        (mounted / "memory.failcnt").write_text("7\n")
        (mounted / "memory.oom_control").write_text("oom_kill_disable 0\nunder_oom 0\noom_kill 2\n")
        self.assertEqual(ci.cgroup_counters(self.proc), {"version": "v1", "counters": {"failcnt": 7, "oom_kill": 2}})
        self.assertIsNone(ci.counter_changes(None, ci.cgroup_counters(self.proc)))

    def test_bad_cgroup_data_does_not_break_sampling(self):
        mounted = self.mount("v2", "/", "/")
        (mounted / "memory.events").write_text("oom invalid\n")
        self.assertIsNone(ci.cgroup_counters(self.proc))
        (self.proc / "self/cgroup").write_text("0::/../escape\n")
        self.assertIsNone(ci.cgroup_counters(self.proc))


class WrapperFixtures(unittest.TestCase):
    def command(self, source, workers="2", extra=(), env=None):
        return subprocess.run(
            [sys.executable, str(WRAPPER), "--max-workers", workers, "--", sys.executable, "-c", source, *extra],
            capture_output=True, text=True, env={**os.environ, **(env or {})},
        )

    def summary(self, result):
        lines = [line for line in result.stdout.splitlines() if line.startswith("SILO_CI_RESOURCE_SUMMARY ")]
        self.assertEqual(len(lines), 1)
        return json.loads(lines[0].split(" ", 1)[1])

    def test_child_stdout_and_nonzero_exit_are_preserved(self):
        result = self.command("import sys; print('fixture stdout'); assert sys.argv[1:] == ['--max-workers=4']; sys.exit(17)", workers="4")
        self.assertEqual(result.returncode, 17)
        self.assertIn("fixture stdout", result.stdout)
        summary = self.summary(result)
        self.assertEqual(summary["child_returncode"], 17)
        self.assertEqual(summary["exit_status"], 17)
        self.assertEqual(summary["worker_limit"], 4)
        self.assertNotIn("command", summary)
        self.assertNotIn("args", summary)
        self.assertNotIn("pid", summary)

    def test_short_success_has_summary(self):
        result = self.command("print('ok')")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.summary(result)["exit_status"], 0)

    def test_observation_errors_preserve_child_status(self):
        output = io.StringIO()
        with patch.object(ci.MemorySampler, "sample", side_effect=RuntimeError("PRIVATE_PROCESS_MARKER")), redirect_stdout(output):
            status = ci.run([sys.executable, "-c", "import sys; sys.exit(17)"], 2)
        self.assertEqual(status, 17)
        result = subprocess.CompletedProcess([], status, output.getvalue(), "")
        summary = self.summary(result)
        self.assertEqual(summary["exit_status"], 17)
        self.assertGreaterEqual(summary["observation_error_count"], 2)
        self.assertNotIn("PRIVATE_PROCESS_MARKER", output.getvalue())

    @unittest.skipUnless(os.name == "posix", "POSIX signals")
    def test_signal_exit_preserves_shell_status(self):
        result = self.command("import os, signal; os.kill(os.getpid(), signal.SIGTERM)")
        self.assertEqual(result.returncode, 128 + signal.SIGTERM)
        self.assertEqual(self.summary(result)["child_returncode"], -signal.SIGTERM)

    @unittest.skipUnless(os.name == "posix", "POSIX signals")
    def test_wrapper_forwards_cancellation_and_emits_summary(self):
        process = subprocess.Popen([
            sys.executable, str(WRAPPER), "--max-workers", "2", "--",
            sys.executable, "-c", "import time; print('CHILD_READY', flush=True); time.sleep(60)",
        ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertTrue(select.select([process.stdout], [], [], 5)[0], "child startup timed out")
            self.assertEqual(process.stdout.readline().strip(), "CHILD_READY")
            process.send_signal(signal.SIGTERM)
            output, errors = process.communicate(timeout=5)
            result = subprocess.CompletedProcess([], process.returncode, output, errors)
            self.assertEqual(result.returncode, 128 + signal.SIGTERM)
            self.assertEqual(self.summary(result)["child_returncode"], -signal.SIGTERM)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_invalid_workers_and_duplicate_override_never_launch_child(self):
        for workers in ("0", "3", "-1", "2.0", "2; echo private"):
            with self.subTest(workers=workers):
                result = self.command("print('CHILD_STARTED')", workers=workers)
                self.assertEqual(result.returncode, 2)
                self.assertNotIn("CHILD_STARTED", result.stdout)
        result = self.command("print('CHILD_STARTED')", extra=("--max-workers=4",))
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("CHILD_STARTED", result.stdout)
        result = self.command("print('CHILD_STARTED')", env={"SILO_CI_REQUESTED_WORKERS": "3"})
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("CHILD_STARTED", result.stdout)


class WorkflowFixtures(unittest.TestCase):
    def setUp(self):
        self.controller = (ROOT / ".github/workflows/android-build.yml").read_text()
        self.child = (ROOT / ".github/workflows/trusted-linux-ci.yml").read_text()

    def job(self, name):
        match = re.search(r"^  " + re.escape(name) + r":\n(.*?)(?=^  [\w-]+:\n|\Z)", self.child, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(match)
        return match.group(1)

    def gradle_args(self, job):
        command = re.search(r"^          (\./scripts/ci-gradle\.py.*?)$(?!\n            )", job, re.MULTILINE | re.DOTALL)
        self.assertIsNotNone(command)
        # A command ends at its first line without a continuation backslash.
        lines = command.group(1).splitlines()
        selected = []
        for line in lines:
            selected.append(line.strip().removesuffix("\\"))
            if not line.rstrip().endswith("\\"):
                break
        return shlex.split(" ".join(selected))

    def test_dispatch_choices_and_typed_default(self):
        choices = re.search(r"^      worker_limit:\n(.*?)(?=^      \w+:|\Z)", self.controller, re.DOTALL | re.MULTILINE).group(1)
        self.assertIn('default: "2"', choices)
        self.assertIn('type: choice', choices)
        self.assertEqual(re.findall(r'^          - "(\d+)"$', choices, re.MULTILINE), ["2", "4"])
        self.assertIn("worker_limit: ${{ fromJSON(inputs.worker_limit || '2') }}", self.controller)
        self.assertRegex(self.child, r"worker_limit:\n        type: number\n        required: false\n        default: 2")

    def test_exact_task_graphs_heap_and_job_worker_policy(self):
        expected = {
            "unit-tests": ["testDebugUnitTest"],
            "lint": [":android-shared:lintDebug", ":androidApp:lintDebug", ":androidTvApp:lintDebug", ":androidApp:lintVitalRelease", ":androidTvApp:lintVitalRelease"],
            "release-readiness": [":androidApp:bundleRelease", ":androidTvApp:bundleRelease"],
        }
        for name, tasks in expected.items():
            with self.subTest(job=name):
                job = self.job(name)
                args = self.gradle_args(job)
                self.assertEqual(args[:5], ["./scripts/ci-gradle.py", "--max-workers", "${SILO_CI_WORKER_LIMIT}", "--", "./gradlew"])
                self.assertIn("-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8", args)
                actual = [arg for arg in args[5:] if arg == "testDebugUnitTest" or arg.startswith(":")]
                self.assertEqual(actual, tasks)
                for flag in ("${SILO_CI_CACHE_FLAG}", "${SILO_CI_PARALLEL_FLAG}", "${SILO_CI_CONFIGURATION_FLAG}"):
                    self.assertIn(flag, args)
                self.assertIn("run: ./scripts/ci-build-metadata.py", job)
        readiness = self.job("release-readiness")
        self.assertIn('SILO_CI_WORKER_LIMIT: "2"', readiness)
        self.assertIn('SILO_CI_PARALLEL_FLAG: --no-parallel', readiness)
        for name in ("unit-tests", "lint"):
            self.assertNotIn('SILO_CI_WORKER_LIMIT: "2"', self.job(name))
        self.assertRegex(self.child, r"configuration_cache:\n        type: boolean\n        required: false\n        default: false")
        build = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('outputs.cacheIf("Tests inspect files outside their runtime classpath") { false }', build)
        self.assertIn('outputs.upToDateWhen { false }', build)
        self.assertIn("glob('*/build/test-results/testDebugUnitTest/TEST-*.xml')", self.job("unit-tests"))

    def test_metadata_reports_effective_job_workers_and_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-qm", "fixture"], check=True)
            (root / "gradle/wrapper").mkdir(parents=True)
            (root / "gradle/wrapper/gradle-wrapper.properties").write_text("distributionUrl=gradle-8.12-bin.zip")
            (root / "gradle/libs.versions.toml").write_text('agp = "8.10.1"')
            environment = {
                **os.environ,
                "SILO_CI_REQUESTED_WORKERS": "4",
                "SILO_CI_WORKER_LIMIT": "2",
                "SILO_CI_PARALLEL_FLAG": "--no-parallel",
                "SILO_CI_CONFIGURATION_FLAG": "--no-configuration-cache",
                "SILO_CI_CACHE_FLAG": "--build-cache",
                "GITHUB_JOB": "release-readiness",
            }
            environment.pop("GITHUB_EVENT_PATH", None)
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True, check=True)
            metadata = json.loads(result.stdout.removeprefix("SILO_CI_BENCHMARK "))
            self.assertEqual(metadata["requested_worker_limit"], 4)
            self.assertEqual(metadata["worker_limit"], 2)
            self.assertEqual(metadata["job"], "release-readiness")
            self.assertIn("--no-parallel", metadata["profile"])
            environment["SILO_CI_REQUESTED_WORKERS"] = "3"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("SILO_CI_BENCHMARK", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
