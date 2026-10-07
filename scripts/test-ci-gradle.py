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
import xml.etree.ElementTree as ET
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
        self.process(920004, "java", 1)
        (self.proc / "920001/status").write_text("VmRSS:\t1 kB\n")
        (self.proc / "920002/status").write_text("VmRSS:\t1 kB\n")
        sampler.sample()
        self.assertEqual(sampler.summary()["max_java_process_count"], 3)
        self.assertEqual(sampler.summary()["max_summed_java_rss_kib"], 350)
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

    def test_parent_quota_and_memory_limit_bound_unlimited_child(self):
        mounted = self.mount("v2", "/", "/job")
        job = mounted / "job"
        job.mkdir()
        (self.proc / "meminfo").write_text("MemTotal: 16777216 kB\n")
        (mounted / "memory.max").write_text(str(8 * 1024**3))
        (job / "memory.max").write_text("max\n")
        (job / "memory.current").write_text("1234\n")
        (job / "memory.peak").write_text("2345\n")
        (mounted / "cpu.max").write_text("250000 100000\n")
        (job / "cpu.max").write_text("max 100000\n")
        memory = ci.memory_resources(self.proc)
        self.assertEqual(memory["physical_memory_bytes"], 16 * 1024**3)
        self.assertEqual(memory["cgroup_memory_max"], {"status": "unlimited", "bytes": None})
        self.assertEqual(memory["cgroup_memory_limit_bytes"], 8 * 1024**3)
        self.assertEqual(memory["effective_memory_capacity_bytes"], 8 * 1024**3)
        self.assertEqual(memory["cgroup_memory_current_bytes"], 1234)
        self.assertEqual(memory["cgroup_memory_peak_bytes"], 2345)
        with patch.object(ci, "is_linux", return_value=True), patch.object(os, "cpu_count", return_value=16), patch.object(os, "sched_getaffinity", return_value={0, 1, 2, 3}, create=True):
            cpu = ci.cpu_resources(self.proc)
        self.assertEqual(cpu["affinity_cpu_count"], 4)
        self.assertEqual(cpu["cgroup_quota_cores"], 2.5)
        self.assertEqual(cpu["effective_cpu_capacity_cores"], 2.5)
        self.assertNotIn(str(mounted), json.dumps({"cpu": cpu, "memory": memory}))

    def test_missing_capacity_does_not_assume_host_memory(self):
        memory = ci.memory_resources(self.proc)
        self.assertIsNone(memory["effective_memory_capacity_bytes"])
        self.assertEqual(memory["cgroup_memory_max"]["status"], "unavailable")
        with patch.object(ci, "is_linux", return_value=True), patch.object(os, "cpu_count", return_value=2), patch.object(os, "sched_getaffinity", side_effect=OSError, create=True):
            cpu = ci.cpu_resources(self.proc)
        self.assertIsNone(cpu["cgroup_quota_cores"])
        self.assertIsNone(cpu["affinity_cpu_count"])
        self.assertEqual(cpu["effective_cpu_capacity_cores"], 2)

    def test_cpu_affinity_can_be_lower_than_quota_and_v1_sentinels(self):
        mounted = self.mount("v1", "/", "/")
        (self.proc / "meminfo").write_text("MemTotal: 4194304 kB\n")
        (mounted / "memory.limit_in_bytes").write_text(str(2**63 - 4096))
        (mounted / "memory.usage_in_bytes").write_text("1024")
        (mounted / "memory.max_usage_in_bytes").write_text("2048")
        resources = ci.memory_resources(self.proc)
        self.assertEqual(resources["cgroup_memory_max"]["status"], "unlimited")
        self.assertEqual(resources["effective_memory_capacity_bytes"], 4 * 1024**3)
        (self.proc / "self/cgroup").write_text("0::/\n4:memory:/\n5:cpu:/\n")
        (self.proc / "self/mountinfo").write_text(f"1 0 0:1 / {mounted} rw - cgroup cgroup rw,cpu\n")
        (mounted / "cpu.cfs_quota_us").write_text("300000")
        (mounted / "cpu.cfs_period_us").write_text("100000")
        with patch.object(ci, "is_linux", return_value=True), patch.object(os, "cpu_count", return_value=16), patch.object(os, "sched_getaffinity", return_value={0, 1}, create=True):
            cpu = ci.cpu_resources(self.proc)
            self.assertEqual(cpu["effective_cpu_capacity_cores"], 2)
            (mounted / "cpu.cfs_quota_us").write_text("-1")
            self.assertIsNone(ci.cpu_resources(self.proc)["cgroup_quota_cores"])

    def test_numeric_jvm_role_flags_exclude_args_paths_and_ids(self):
        roles = (
            (920001, "org.gradle.launcher.daemon.bootstrap.GradleDaemon", "4g", []),
            (920002, "org.jetbrains.kotlin.daemon.KotlinCompileDaemon", "4g", []),
            (920003, "worker.org.gradle.process.internal.worker.GradleWorkerMain", "512m", ["'Gradle Test Executor 1'"]),
        )
        for identifier, role, heap, extra in roles:
            self.process(identifier, "java", 100)
            (self.proc / str(identifier) / "cmdline").write_bytes("\0".join(["/PRIVATE_JAVA_PATH", "-Xmx" + heap, "-Dprivate=SECRET_MARKER", role, *extra]).encode())
        sampler = ci.MemorySampler(self.proc, enabled=True)
        sampler.sample()
        result = sampler.summary()
        self.assertEqual(result["jvm_roles"]["kotlin"]["launch_memory_flags_bytes"]["max_heap_bytes"], [4 * 1024**3])
        self.assertEqual(result["jvm_roles"]["test"]["launch_memory_flags_bytes"]["max_heap_bytes"], [512 * 1024**2])
        encoded = json.dumps(result)
        for private in ("920001", "920002", "920003", "PRIVATE_JAVA_PATH", "SECRET_MARKER", "KotlinCompileDaemon"):
            self.assertNotIn(private, encoded)
        self.assertEqual(ci.memory_arguments(["-Xmx4g", "-Xmxinvalid", "-Dsecret=private"]), {"max_heap_bytes": 4 * 1024**3})

    def test_real_test_display_name_quotes_and_unknown_workers(self):
        worker = "worker.org.gradle.process.internal.worker.GradleWorkerMain"
        cases = (
            (worker, "'Gradle Test Executor 1'", True),
            (worker, "Gradle Test Executor 12", True),
            (worker, "'Gradle Test Executor 1", False),
            (worker, "Gradle Test Executor 1'", False),
            (worker, "\"Gradle Test Executor 1\"", False),
            (worker, "'Gradle Test Executor 1''", False),
            (worker, "'Gradle Test Executor 1' trailing", False),
            (worker, "'Gradle Test Executor arbitrary'", False),
            (worker, "'Gradle Worker Daemon 1'", False),
            ("org.example.UnrelatedWorker", "'Gradle Test Executor 1'", False),
        )
        self.process(920004, "java", 100)
        for main_class, display_name, expected in cases:
            with self.subTest(display_name=display_name, main_class=main_class):
                (self.proc / "920004" / "cmdline").write_bytes("\0".join(["/PRIVATE_JAVA_PATH", "-Xmx512m", "-Dprivate=SECRET_MARKER", main_class, display_name]).encode())
                sampler = ci.MemorySampler(self.proc, enabled=True)
                sampler.sample()
                result = sampler.summary()
                role = result["jvm_roles"]["test"]
                self.assertEqual(role["max_observed_process_count"], int(expected))
                self.assertEqual(role["launch_memory_flags_bytes"], {"max_heap_bytes": [512 * 1024**2]} if expected else {})
                for private in ("920004", "PRIVATE_JAVA_PATH", "SECRET_MARKER", display_name, main_class):
                    self.assertNotIn(private, json.dumps(result))
        # A recognized executor without an observed heap flag remains unknown.
        (self.proc / "920004" / "cmdline").write_bytes("\0".join(["java", worker, "'Gradle Test Executor 1'"]).encode())
        self.assertEqual(ci.observed_jvm_roles(self.proc)["test"], [{}])

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
        result = self.command("import sys; print('fixture stdout'); assert sys.argv[1] == '--init-script' and sys.argv[-1] == '--max-workers=4'; sys.exit(17)", workers="4")
        self.assertEqual(result.returncode, 17)
        self.assertIn("fixture stdout", result.stdout)
        summary = self.summary(result)
        self.assertEqual(summary["child_returncode"], 17)
        self.assertEqual(summary["exit_status"], 17)
        self.assertEqual(summary["worker_limit"], 4)
        self.assertTrue(summary["gradle_profile_requested"])
        self.assertNotIn("command", summary)
        self.assertNotIn("args", summary)
        self.assertNotIn("pid", summary)

    def test_short_success_has_summary(self):
        result = self.command("print('ok')")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.summary(result)["exit_status"], 0)
        self.assertEqual(self.summary(result)["requested_android_shared_debug_test_forks"], 1)

    def test_requested_shared_forks_are_validated_before_launch(self):
        result = self.command("print('ok')", env={"SILO_CI_ANDROID_SHARED_TEST_FORKS": "2"})
        self.assertEqual(result.returncode, 0)
        self.assertEqual(self.summary(result)["requested_android_shared_debug_test_forks"], 2)
        for forks in ("0", "3", "2.0", "2; echo private"):
            with self.subTest(forks=forks):
                result = self.command("print('CHILD_STARTED')", env={"SILO_CI_ANDROID_SHARED_TEST_FORKS": forks})
                self.assertEqual(result.returncode, 2)
                self.assertNotIn("CHILD_STARTED", result.stdout)

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

    def test_memory_observation_errors_preserve_child_status(self):
        output = io.StringIO()
        with patch.object(ci, "is_linux", return_value=True), patch.object(ci, "memory_resources", side_effect=RuntimeError("PRIVATE_PROCESS_MARKER")), redirect_stdout(output):
            status = ci.run([sys.executable, "-c", "import sys; sys.exit(17)"], 2)
        result = subprocess.CompletedProcess([], status, output.getvalue(), "")
        self.assertEqual(status, 17)
        self.assertIsNone(self.summary(result)["memory_before"])
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
        result = self.command("print('CHILD_STARTED')", env={"SILO_CI_WORKER_LIMIT": "3"})
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("CHILD_STARTED", result.stdout)
        result = self.command("print('CHILD_STARTED')", env={"SILO_CI_WORKER_LIMIT": "4"})
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("CHILD_STARTED", result.stdout)
        result = self.command("print('CHILD_STARTED')", extra=("--configuration-cache",))
        self.assertEqual(result.returncode, 2)
        self.assertNotIn("CHILD_STARTED", result.stdout)


@unittest.skipUnless(os.environ.get("SILO_CI_FIXTURE_GRADLE"), "Set SILO_CI_FIXTURE_GRADLE to run isolated real Gradle fixtures")
class GradleFixtures(unittest.TestCase):
    def metadata(self, result):
        line = next(line for line in result.stdout.splitlines() if line.startswith("SILO_CI_JVM_SETTINGS "))
        return json.loads(line.split(" ", 1)[1])

    def fixture(self, settings):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            (project / "settings.gradle").write_text("rootProject.name = 'ci-jvm-fixture'\n")
            (project / "build.gradle").write_text("plugins { id 'java' }\ntasks.test { " + settings + " }\n")
            return subprocess.run([
                sys.executable, str(WRAPPER), "--max-workers", "2", "--",
                os.environ["SILO_CI_FIXTURE_GRADLE"], "test", "--offline", "--no-daemon", "--no-configuration-cache", "--console=plain",
                "-Dorg.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8", "-Pandroid.r8.maxWorkers=1",
            ], cwd=project, env={**os.environ, "GRADLE_USER_HOME": str(project / "gradle-home"), "SILO_CI_ANDROID_SHARED_TEST_FORKS": "1"}, capture_output=True, text=True, timeout=90)

    def test_actual_daemon_heap_and_selected_test_settings(self):
        result = self.fixture("maxHeapSize = '768m'")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        metadata = self.metadata(result)
        self.assertEqual(metadata["gradle"]["max_heap_bytes"], 512 * 1024**2)
        self.assertEqual(metadata["gradle"]["worker_limit"], 2)
        self.assertEqual(metadata["test"]["selected_task_count"], 1)
        self.assertEqual(metadata["test"]["max_parallel_forks"], [1])
        self.assertEqual(metadata["test"]["configured_max_heap_bytes"], [768 * 1024**2])
        self.assertEqual(metadata["r8_max_workers"], 1)

    def test_multiple_test_forks_are_rejected(self):
        result = self.fixture("maxParallelForks = 2")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Worker tuning requires selected Test tasks to start with one fork", result.stdout + result.stderr)
        line = next(line for line in result.stdout.splitlines() if line.startswith("SILO_CI_RESOURCE_SUMMARY "))
        self.assertNotEqual(json.loads(line.split(" ", 1)[1])["exit_status"], 0)

    def multi_project_fixture(self, requested=None, unexpected=None, include_shared=True):
        with tempfile.TemporaryDirectory() as temporary:
            project = Path(temporary)
            (project / "settings.gradle").write_text("rootProject.name = 'ci-scoped-fork-fixture'\ninclude 'android-shared', 'androidApp', 'shared'\n")
            # Use Gradle's own local JUnit jars so test execution needs no
            # dependency download or shared Gradle daemon registry.
            (project / "build.gradle").write_text('''
subprojects {
    apply plugin: 'java'
    dependencies {
        testImplementation files("${gradle.gradleHomeDir}/lib/junit-4.13.2.jar", "${gradle.gradleHomeDir}/lib/hamcrest-core-1.3.jar")
    }
    tasks.register('testDebugUnitTest', Test) {
        testClassesDirs = sourceSets.test.output.classesDirs
        classpath = sourceSets.test.runtimeClasspath
        dependsOn tasks.testClasses
        useJUnit()
        maxHeapSize = '128m'
    }
    tasks.withType(Test).configureEach { maxHeapSize = '128m' }
}
''')
            if unexpected:
                with (project / "build.gradle").open("a") as build:
                    build.write(f"project('{unexpected}').tasks.named('testDebugUnitTest') {{ maxParallelForks = 2 }}\n")
            for module in ("android-shared", "androidApp", "shared"):
                sources = project / module / "src/test/java"
                sources.mkdir(parents=True)
                for name in ("First", "Second"):
                    (sources / (name + "Test.java")).write_text(
                        "import org.junit.Test;\npublic class " + name + "Test {\n"
                        "@Test public void worker() throws Exception {\n"
                        'System.out.println("FIXTURE_WORKER " + System.getProperty("org.gradle.test.worker"));\n'
                        "Thread.sleep(200);\n}\n}\n"
                    )
            environment = {**os.environ, "GRADLE_USER_HOME": str(project / "gradle-home")}
            environment.pop("SILO_CI_ANDROID_SHARED_TEST_FORKS", None)
            if requested is not None:
                environment["SILO_CI_ANDROID_SHARED_TEST_FORKS"] = requested
            tasks = [":androidApp:testDebugUnitTest", ":shared:testDebugUnitTest"]
            if include_shared:
                tasks += [":android-shared:testDebugUnitTest", ":android-shared:test"]
            result = subprocess.run([
                sys.executable, str(WRAPPER), "--max-workers", "2", "--", os.environ["SILO_CI_FIXTURE_GRADLE"],
                *tasks, "--offline", "--no-daemon", "--no-configuration-cache", "--console=plain",
                "-Dorg.gradle.jvmargs=-Xmx512m -Dfile.encoding=UTF-8",
            ], cwd=project, env=environment, capture_output=True, text=True, timeout=90)
            reports = {}
            for report in project.glob("*/build/test-results/*/TEST-*.xml"):
                key = f":{report.parts[-5]}:{report.parts[-2]}"
                reports.setdefault(key, []).append(ET.parse(report).getroot())
            profiles = list(project.glob("build/reports/profile/profile-*.html"))
            return result, reports, bool(profiles)

    def assert_scoped_execution(self, requested, expected):
        result, reports, profile = self.multi_project_fixture(requested)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        metadata = self.metadata(result)["test"]
        self.assertEqual(metadata["requested_android_shared_debug_max_parallel_forks"], expected)
        self.assertEqual(metadata["override_task_path"], ":android-shared:testDebugUnitTest")
        actual = {task["path"]: task["max_parallel_forks"] for task in metadata["selected_task_settings"]}
        self.assertEqual(actual, {":android-shared:testDebugUnitTest": expected, ":android-shared:test": 1, ":androidApp:testDebugUnitTest": 1, ":shared:testDebugUnitTest": 1})
        self.assertEqual(set(reports), set(actual))
        self.assertTrue(profile, "Gradle profile was not produced")
        for path, suites in reports.items():
            with self.subTest(path=path):
                self.assertEqual(sum(int(suite.attrib["tests"]) for suite in suites), 2)
                self.assertEqual(sum(int(suite.attrib["failures"]) + int(suite.attrib["errors"]) for suite in suites), 0)
                workers = {line.removeprefix("FIXTURE_WORKER ") for suite in suites for line in (suite.findtext("system-out") or "").splitlines() if line.startswith("FIXTURE_WORKER ")}
                self.assertEqual(len(workers), expected if path == ":android-shared:testDebugUnitTest" else 1)
        self.assertTrue(all(task["fork_every"] == 0 for task in metadata["selected_task_settings"]))

    def test_scoped_default_executes_every_test_with_one_fork(self):
        self.assert_scoped_execution(None, 1)

    def test_scoped_override_executes_only_shared_debug_with_two_forks(self):
        self.assert_scoped_execution("2", 2)

    def test_unexpected_forks_fail_before_test_execution(self):
        result, reports, _ = self.multi_project_fixture("2", unexpected=":androidApp")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Worker tuning requires selected Test tasks to start with one fork", result.stdout + result.stderr)
        self.assertFalse(reports)

    def test_two_fork_request_requires_the_exact_selected_task(self):
        result, reports, _ = self.multi_project_fixture("2", include_shared=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("The two-fork pilot requires :android-shared:testDebugUnitTest in the selected graph", result.stdout + result.stderr)
        self.assertFalse(reports)


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
        for job in ("unit", "lint"):
            choices = re.search(r"^      " + job + r"_worker_limit:\n(.*?)(?=^      \w+:|\Z)", self.controller, re.DOTALL | re.MULTILINE).group(1)
            self.assertIn('default: "2"', choices)
            self.assertIn('type: choice', choices)
            self.assertEqual(re.findall(r'^          - "(\d+)"$', choices, re.MULTILINE), ["2", "4"])
            self.assertIn(job + "_worker_limit: ${{ fromJSON(inputs." + job + "_worker_limit || '2') }}", self.controller)
            self.assertRegex(self.child, job + r"_worker_limit:\n        type: number\n        required: false\n        default: 2")
        forks = re.search(r"^      unit_shared_test_forks:\n(.*?)(?=^      \w+:|\Z)", self.controller, re.DOTALL | re.MULTILINE).group(1)
        self.assertIn('default: "1"', forks)
        self.assertIn('type: choice', forks)
        self.assertEqual(re.findall(r'^          - "(\d+)"$', forks, re.MULTILINE), ["1", "2"])
        self.assertIn("unit_shared_test_forks: ${{ fromJSON(inputs.unit_shared_test_forks || '1') }}", self.controller)
        self.assertRegex(self.child, r"unit_shared_test_forks:\n        type: number\n        required: false\n        default: 1")

    def test_fork_override_is_exported_only_by_the_unit_job(self):
        self.assertIn("SILO_CI_ANDROID_SHARED_TEST_FORKS: ${{ inputs.unit_shared_test_forks }}", self.job("unit-tests"))
        self.assertEqual(self.child.count("SILO_CI_ANDROID_SHARED_TEST_FORKS:"), 1)
        for name in ("lint", "release-readiness", "ffmpeg-aar"):
            self.assertNotIn("SILO_CI_ANDROID_SHARED_TEST_FORKS:", self.job(name))

    def test_exact_task_graphs_heap_and_job_worker_policy(self):
        expected = {
            "unit-tests": ["testDebugUnitTest"],
            "lint": [":android-shared:lintDebug", ":androidApp:lintDebug", ":androidTvApp:lintDebug"],
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
        self.assertIn("SILO_CI_CONFIGURATION_FLAG: --no-configuration-cache", self.child)
        self.assertIn("-Pandroid.r8.maxWorkers=1", self.gradle_args(readiness))
        unit = self.job("unit-tests")
        self.assertIn("SILO_CI_PARALLEL_FLAG: ${{ inputs.unit_parallel && '--parallel' || '--no-parallel' }}", unit)
        self.assertIn("SILO_CI_PARALLEL_FLAG: ${{ inputs.lint_parallel && '--parallel' || '--no-parallel' }}", self.job("lint"))
        build = (ROOT / "build.gradle.kts").read_text()
        self.assertIn('outputs.cacheIf("Tests inspect files outside their runtime classpath") { false }', build)
        self.assertIn('outputs.upToDateWhen { false }', build)
        self.assertIn("glob('*/build/test-results/testDebugUnitTest/TEST-*.xml')", self.job("unit-tests"))

    def test_routing_and_aggregate_require_every_selected_job(self):
        changes = self.job("changes")
        for output in ("schema", "mode", "unit_tests", "lint", "release_readiness", "ffmpeg_aar"):
            self.assertIn(output + ": ${{ steps.route.outputs." + output + " }}", changes)
        for job, output in (("unit-tests", "unit_tests"), ("lint", "lint"), ("release-readiness", "release_readiness"), ("ffmpeg-aar", "ffmpeg_aar")):
            selected = self.job(job)
            self.assertIn("needs: changes", selected)
            self.assertIn("if: needs.changes.outputs." + output + " == 'true'", selected)
        aggregate = self.job("android-ci")
        self.assertIn("if: always()", aggregate)
        needs = re.search(r"^    needs: \[(.*)\]$", aggregate, re.MULTILINE).group(1)
        self.assertEqual({job.strip() for job in needs.split(",")}, {"changes", "unit-tests", "lint", "release-readiness", "ffmpeg-aar"})
        self.assertIn("run: python3 scripts/ci-result.py", aggregate)
        self.assertIn("SILO_CI_NEEDS_JSON: ${{ toJSON(needs) }}", aggregate)
        for check in ("test-check-build-supply-chain.sh", "check-build-supply-chain.sh", "test-release-workflow.sh", "test-ci-routing.py", "test-ci-result.py", "test-ci-gradle.py"):
            self.assertIn("scripts/" + check, changes)
        self.assertNotIn("Check build supply chain", self.job("unit-tests"))

    def test_manual_profile_defaults_keep_serial_two_workers_and_one_fork(self):
        for prefix in ("unit", "lint"):
            self.assertRegex(self.controller, prefix + r"_parallel:\n        description: [^\n]+\n        required: false\n        default: false\n        type: boolean")
        self.assertIn("workflow_dispatch:", self.controller)
        self.assertIn("uses: ./.github/workflows/trusted-linux-ci.yml", self.controller)
        self.assertNotIn("lintVitalRelease", self.job("lint"))
        self.assertIn("Release readiness builds both release bundles, which run fatal", self.job("lint"))

    def test_profiles_persist_after_success_or_failure_with_narrow_scope(self):
        names = set()
        for name in ("unit-tests", "lint", "release-readiness"):
            with self.subTest(job=name):
                job = self.job(name)
                steps = re.findall(r"^      - name: Upload Gradle profile\n(.*?)(?=^      - name:|\Z)", job, re.MULTILINE | re.DOTALL)
                self.assertEqual(len(steps), 1)
                step = steps[0]
                # always() has no success prerequisite and keeps the profile
                # upload eligible after the Gradle step succeeds or fails.
                self.assertRegex(step, re.compile(r"^        if: always\(\)$", re.MULTILINE))
                self.assertIn("uses: actions/upload-artifact@330a01c490aca151604b8cf639adc76d48f6c5d4 # v5", step)
                self.assertIn("retention-days: 7", step)
                self.assertIn("if-no-files-found: warn", step)
                self.assertNotIn("continue-on-error", step)
                artifact = re.search(r"^          name: (.+)$", step, re.MULTILINE).group(1)
                self.assertEqual(artifact, "gradle-profile-" + name)
                names.add(artifact)
                pattern = re.search(r"^          path: (.+)$", step, re.MULTILINE).group(1)
                self.assertEqual(pattern, "build/reports/profile/*.html")
                self.assertGreater(job.index("- name: Upload Gradle profile"), job.index("./scripts/ci-gradle.py"))
                with tempfile.TemporaryDirectory() as temporary:
                    workspace = Path(temporary)
                    files = ("build/reports/profile/profile-a.html", "build/reports/profile/profile-b.html", "build/reports/profile/private.txt", "build/reports/profile/nested/private.html", "build/reports/tests/private.html", "build/private.log", ".gradle/private.env")
                    for path in files:
                        target = workspace / path
                        target.parent.mkdir(parents=True, exist_ok=True)
                        target.write_text("fixture")
                    selected = {str(path.relative_to(workspace)) for path in workspace.glob(pattern)}
                    self.assertEqual(selected, set(files[:2]))
        self.assertEqual(len(names), 3)

    def test_metadata_reports_effective_job_workers_and_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "--allow-empty", "-qm", "fixture"], check=True)
            (root / "gradle/wrapper").mkdir(parents=True)
            (root / "gradle/wrapper/gradle-wrapper.properties").write_text("distributionUrl=gradle-8.12-bin.zip")
            (root / "gradle/libs.versions.toml").write_text('agp = "8.10.1"')
            (root / "bin").mkdir()
            java = root / "bin/java"
            java.write_text('#!/bin/sh\necho "Picked up JAVA_TOOL_OPTIONS: SECRET_JVM_OPTIONS" >&2\necho \'openjdk version "21.0.11" 2026-04-21\' >&2\n')
            java.chmod(0o755)
            environment = {
                **os.environ,
                "SILO_CI_WORKER_LIMIT": "2",
                "SILO_CI_PARALLEL_FLAG": "--no-parallel",
                "SILO_CI_CONFIGURATION_FLAG": "--no-configuration-cache",
                "SILO_CI_CACHE_FLAG": "--build-cache",
                "GITHUB_JOB": "release-readiness",
                "PATH": str(root / "bin") + os.pathsep + os.environ.get("PATH", ""),
            }
            environment.pop("SILO_CI_ANDROID_SHARED_TEST_FORKS", None)
            environment.pop("GITHUB_EVENT_PATH", None)
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True, check=True)
            metadata = json.loads(result.stdout.removeprefix("SILO_CI_BENCHMARK "))
            self.assertEqual(metadata["requested_worker_limit"], 2)
            self.assertEqual(metadata["worker_limit"], 2)
            self.assertEqual(metadata["requested_android_shared_debug_test_forks"], 1)
            self.assertEqual(metadata["test_fork_override_task_path"], ":android-shared:testDebugUnitTest")
            self.assertEqual(metadata["job"], "release-readiness")
            self.assertIn("--no-parallel", metadata["profile"])
            self.assertEqual(metadata["java_version"], "21.0.11")
            self.assertNotIn("SECRET_JVM_OPTIONS", result.stdout)
            environment["SILO_CI_ANDROID_SHARED_TEST_FORKS"] = "2"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout.removeprefix("SILO_CI_BENCHMARK "))["requested_android_shared_debug_test_forks"], 2)
            environment["SILO_CI_ANDROID_SHARED_TEST_FORKS"] = "3"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("SILO_CI_BENCHMARK", result.stdout)
            environment["SILO_CI_ANDROID_SHARED_TEST_FORKS"] = "1"
            environment["SILO_CI_WORKER_LIMIT"] = "3"
            result = subprocess.run([sys.executable, str(ROOT / "scripts/ci-build-metadata.py")], cwd=root, env=environment, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("SILO_CI_BENCHMARK", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
