#!/usr/bin/env python3
"""Run Gradle with bounded workers and sample Linux runner memory.

Samples describe the whole /proc view, including unrelated Java processes.
Summed RSS can count shared pages more than once and interval sampling can miss
short peaks. Cgroup counters are observations, not proof Gradle caused an OOM.
Only aggregate values are emitted; process arguments and IDs are never logged.
"""

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import threading
import time


def worker_limit(value):
    if str(value) not in ("2", "4"):
        raise ValueError("Gradle worker limit must be 2 or 4")
    return int(value)


def shared_test_forks(value):
    if str(value) not in ("1", "2"):
        raise ValueError("Android-shared debug Test forks must be 1 or 2")
    return int(value)


def is_linux():
    return sys.platform.startswith("linux")


def memory_value(proc, key):
    try:
        for line in (proc / "meminfo").read_text(errors="replace").splitlines():
            match = re.fullmatch(re.escape(key) + r":\s+(\d+)\s+kB", line)
            if match:
                return int(match.group(1))
    except OSError:
        pass
    return None


def java_rss(proc):
    """Return summed Java VmRSS, count, and unreadable Java entries."""
    total, count, unreadable, missing_java = 0, 0, 0, 0
    try:
        entries = list(proc.iterdir())
    except OSError:
        return None, None, None
    for entry in entries:
        if not entry.name.isdecimal():
            continue
        try:
            if (entry / "comm").read_text(errors="replace").strip() not in ("java", "javaw"):
                continue
        except OSError:
            unreadable += 1
            continue
        try:
            status = (entry / "status").read_text(errors="replace")
            match = re.search(r"^VmRSS:\s+(\d+)\s+kB$", status, re.MULTILINE)
            if match is None:
                unreadable += 1
                missing_java += 1
                continue
            total += int(match.group(1))
            count += 1
        except OSError:
            # Processes can disappear between directory enumeration and reads.
            unreadable += 1
            missing_java += 1
    return (None if missing_java and not count else total), count, unreadable


def _mount_path(value):
    return Path(re.sub(r"\\([0-7]{3})", lambda m: chr(int(m.group(1), 8)), value))


def cgroup_location(proc, fallback=Path("/sys/fs/cgroup"), controller="memory"):
    """Resolve the current cgroup using mount roots and namespace-relative paths."""
    try:
        memberships = [line.split(":", 2) for line in (proc / "self/cgroup").read_text(errors="replace").splitlines()]
        # Hybrid hosts can keep memory/CPU in v1 while exposing other v2
        # controllers. A named v1 controller takes precedence for that resource.
        selected = next((("v1", path) for _, controllers, path in memberships if controller in controllers.split(",")), None)
        if selected is None:
            selected = next((("v2", path) for _, controllers, path in memberships if not controllers), None)
        if selected is None:
            return None
        version, member = selected
        member_path = PurePosixPath(member)
        if not member_path.is_absolute() or ".." in member_path.parts:
            return None
        try:
            mounts = (proc / "self/mountinfo").read_text(errors="replace").splitlines()
        except OSError:
            mounts = []
        for line in mounts:
            before, separator, after = line.partition(" - ")
            fields, fs = before.split(), after.split()
            if not separator or len(fields) < 5 or len(fs) < 3:
                continue
            if not (version == "v2" and fs[0] == "cgroup2" or version == "v1" and fs[0] == "cgroup" and controller in fs[2].split(",")):
                continue
            root = PurePosixPath(str(_mount_path(fields[3])))
            try:
                relative = member_path.relative_to(root)
            except ValueError:
                # A cgroup namespace can expose '/' while the mount root is a
                # host-side subtree. The membership is then mount-relative.
                relative = member_path.relative_to("/")
            mounted = _mount_path(fields[4])
            return version, mounted / str(relative), mounted
        base = fallback if version == "v2" else fallback / controller
        return version, base / str(member_path.relative_to("/")), base
    except (OSError, ValueError):
        return None


def cgroup_directory(proc, fallback=Path("/sys/fs/cgroup")):
    location = cgroup_location(proc, fallback)
    return location[:2] if location else (None, None)


def visible_cgroups(location):
    if location is None:
        return []
    _, directory, mounted = location
    result = [directory]
    while directory != mounted and mounted in directory.parents:
        directory = directory.parent
        result.append(directory)
    return result


def numeric_file(path):
    try:
        value = path.read_text(errors="replace").strip()
        return int(value) if value.isdecimal() else None
    except OSError:
        return None


def memory_limit(path, version):
    try:
        value = path.read_text(errors="replace").strip()
        if value == "max" or version == "v1" and value.isdecimal() and int(value) >= 2**60:
            return {"status": "unlimited", "bytes": None}
        if value.isdecimal():
            return {"status": "limited", "bytes": int(value)}
    except OSError:
        pass
    return {"status": "unavailable", "bytes": None}


def memory_resources(proc=Path("/proc"), fallback=Path("/sys/fs/cgroup")):
    physical_kib = memory_value(proc, "MemTotal")
    physical = physical_kib * 1024 if physical_kib is not None else None
    location = cgroup_location(proc, fallback)
    current = peak = None
    leaf_limit = {"status": "unavailable", "bytes": None}
    limits = []
    if location:
        version, directory, _ = location
        limit_name = "memory.max" if version == "v2" else "memory.limit_in_bytes"
        current = numeric_file(directory / ("memory.current" if version == "v2" else "memory.usage_in_bytes"))
        peak = numeric_file(directory / ("memory.peak" if version == "v2" else "memory.max_usage_in_bytes"))
        limits = [memory_limit(group / limit_name, version) for group in visible_cgroups(location)]
        leaf_limit = limits[0]
    finite = [limit["bytes"] for limit in limits if limit["bytes"] is not None]
    effective_cgroup = min(finite) if finite else None
    capacities = [value for value in (physical, effective_cgroup) if value is not None]
    return {
        "physical_memory_bytes": physical,
        "cgroup_memory_current_bytes": current,
        "cgroup_memory_peak_bytes": peak,
        "cgroup_memory_max": leaf_limit,
        "cgroup_memory_limit_bytes": effective_cgroup,
        "visible_cgroup_limit_count": len(limits),
        "unavailable_cgroup_limit_count": sum(limit["status"] == "unavailable" for limit in limits),
        "effective_memory_capacity_bytes": min(capacities) if capacities else None,
        "limits": "Memory limits cover visible ancestry; a hidden parent can impose a lower limit. memory.peak is the cgroup lifetime peak and is not reset by this wrapper.",
    }


def cpu_resources(proc=Path("/proc"), fallback=Path("/sys/fs/cgroup")):
    logical = os.cpu_count()
    try:
        affinity = len(os.sched_getaffinity(0))
    except (AttributeError, OSError):
        affinity = None
    location = cgroup_location(proc, fallback, controller="cpu") if is_linux() else None
    quotas, observations = [], 0
    for directory in visible_cgroups(location):
        version = location[0]
        try:
            if version == "v2":
                quota, period = (directory / "cpu.max").read_text(errors="replace").split()
                if int(period) <= 0:
                    continue
                observations += 1
                if quota != "max" and int(quota) > 0:
                    quotas.append(int(quota) / int(period))
            else:
                quota = int((directory / "cpu.cfs_quota_us").read_text(errors="replace").strip())
                period = numeric_file(directory / "cpu.cfs_period_us")
                if period:
                    observations += 1
                    if quota > 0:
                        quotas.append(quota / period)
        except (OSError, ValueError):
            continue
    quota = min(quotas) if quotas else None
    capacities = [value for value in (affinity if affinity is not None else logical, quota) if value is not None]
    return {
        "logical_cpu_count": logical,
        "affinity_cpu_count": affinity,
        "cgroup_quota_cores": quota,
        "visible_cgroup_quota_observations": observations,
        "effective_cpu_capacity_cores": min(capacities) if capacities else None,
        "limits": "CPU capacity uses affinity and visible ancestor quotas; missing or hidden quotas remain unknown.",
    }


def memory_bytes(value):
    match = re.fullmatch(r"(\d+)([kKmMgGtT]?)", value)
    if match is None:
        return None
    return int(match.group(1)) * 1024 ** ("kmgt".find(match.group(2).lower()) + 1 if match.group(2) else 0)


def memory_arguments(arguments):
    result = {}
    for argument in arguments:
        for prefix, field in (("-Xmx", "max_heap_bytes"), ("-Xms", "min_heap_bytes"), ("-XX:MaxMetaspaceSize=", "max_metaspace_bytes"), ("-XX:ReservedCodeCacheSize=", "reserved_code_cache_bytes")):
            if argument.startswith(prefix):
                value = memory_bytes(argument[len(prefix):])
                if value is not None:
                    result[field] = value
    return result


def observed_jvm_roles(proc):
    """Parse launch flags in memory; return numeric role aggregates only."""
    roles = {role: [] for role in ("gradle", "kotlin", "test")}
    try:
        entries = list(proc.iterdir())
    except OSError:
        return roles
    for entry in entries:
        if not entry.name.isdecimal():
            continue
        try:
            if (entry / "comm").read_text(errors="replace").strip() not in ("java", "javaw"):
                continue
            arguments = (entry / "cmdline").read_bytes().decode(errors="replace").split("\0")
        except OSError:
            continue
        role = None
        if "org.gradle.launcher.daemon.bootstrap.GradleDaemon" in arguments:
            role = "gradle"
        elif "org.jetbrains.kotlin.daemon.KotlinCompileDaemon" in arguments:
            role = "kotlin"
        elif "worker.org.gradle.process.internal.worker.GradleWorkerMain" in arguments and any(argument.startswith("Gradle Test Executor ") for argument in arguments):
            role = "test"
        if role:
            roles[role].append(memory_arguments(arguments))
    return roles


def cgroup_counters(proc, fallback=Path("/sys/fs/cgroup")):
    version, directory = cgroup_directory(proc, fallback)
    if directory is None:
        return None
    try:
        if version == "v2":
            allowed = {"low", "high", "max", "oom", "oom_kill", "oom_group_kill"}
            counters = {}
            for line in (directory / "memory.events").read_text(errors="replace").splitlines():
                key, value = line.split()
                if key in allowed:
                    counters[key] = int(value)
        else:
            counters = {"failcnt": int((directory / "memory.failcnt").read_text(errors="replace").strip())}
            for line in (directory / "memory.oom_control").read_text(errors="replace").splitlines():
                key, value = line.split()
                if key == "oom_kill":
                    counters[key] = int(value)
        return {"version": version, "counters": counters}
    except (OSError, ValueError):
        return None


def counter_changes(before, after):
    if before is None or after is None or before["version"] != after["version"]:
        return None
    return {
        key: after["counters"][key] - value
        for key, value in before["counters"].items()
        if key in after["counters"] and after["counters"][key] >= value
    }


class MemorySampler:
    def __init__(self, proc=Path("/proc"), enabled=None):
        self.proc = proc
        self.enabled = is_linux() if enabled is None else enabled
        self.samples = 0
        self.min_available = None
        self.max_rss = None
        self.max_java_count = None
        self.unreadable = 0
        self.max_cgroup_current = None
        self.cgroup_peak = None
        self.jvm_roles = {role: {"max_observed_process_count": 0, "launch_memory_flags_bytes": {}} for role in ("gradle", "kotlin", "test")}

    def sample(self):
        if not self.enabled:
            return
        available = memory_value(self.proc, "MemAvailable")
        rss, count, unreadable = java_rss(self.proc)
        memory = memory_resources(self.proc)
        current, peak = memory["cgroup_memory_current_bytes"], memory["cgroup_memory_peak_bytes"]
        if current is not None:
            self.max_cgroup_current = current if self.max_cgroup_current is None else max(self.max_cgroup_current, current)
        if peak is not None:
            self.cgroup_peak = peak if self.cgroup_peak is None else max(self.cgroup_peak, peak)
        for role, processes in observed_jvm_roles(self.proc).items():
            aggregate = self.jvm_roles[role]
            aggregate["max_observed_process_count"] = max(aggregate["max_observed_process_count"], len(processes))
            for process in processes:
                for field, value in process.items():
                    values = aggregate["launch_memory_flags_bytes"].setdefault(field, [])
                    if value not in values:
                        values.append(value)
                        values.sort()
        self.samples += 1
        if unreadable is not None:
            self.unreadable += unreadable
        if available is not None:
            self.min_available = available if self.min_available is None else min(self.min_available, available)
        if count is not None:
            self.max_java_count = count if self.max_java_count is None else max(self.max_java_count, count)
        if rss is not None:
            self.max_rss = rss if self.max_rss is None else max(self.max_rss, rss)

    def summary(self):
        return {
            "scope": "whole_vm_proc_view",
            "sampling_available": self.enabled,
            "sample_count": self.samples,
            "min_mem_available_kib": self.min_available,
            "max_summed_java_rss_kib": self.max_rss,
            "max_java_process_count": self.max_java_count,
            "unreadable_process_observations": self.unreadable,
            "sampled_cgroup_memory_current_max_bytes": self.max_cgroup_current,
            "cgroup_memory_peak_bytes": self.cgroup_peak,
            "jvm_roles": self.jvm_roles,
            "jvm_role_limits": "Observed launch memory flags only; environment JVM options can change effective caps. Warm compilation can omit Kotlin. An unobserved role has no known heap value.",
            "jvm_environment_options_present": {name: bool(os.environ.get(name)) for name in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")},
            "limits": "Interval samples can miss peaks; unrelated Java and shared RSS pages are included; unreadable processes can undercount RSS; missing data is null.",
        }


def run(command, workers, interval=1.0):
    sampler = MemorySampler()
    observation_errors = 0
    try:
        requested_shared_forks = shared_test_forks(os.environ.get("SILO_CI_ANDROID_SHARED_TEST_FORKS", "1"))
    except ValueError:
        # main validates before launch; direct callers must still retain the
        # child's status if request metadata cannot be observed.
        requested_shared_forks = None

    def observe_cgroup():
        nonlocal observation_errors
        try:
            return cgroup_counters(sampler.proc) if sampler.enabled else None
        except Exception:
            observation_errors += 1
            return None

    def sample():
        nonlocal observation_errors
        try:
            sampler.sample()
        except Exception:
            # Telemetry must never prevent the command or override its status.
            # Avoid printing exception details that could contain process data.
            observation_errors += 1

    def observe_memory():
        nonlocal observation_errors
        try:
            return memory_resources(sampler.proc) if sampler.enabled else None
        except Exception:
            observation_errors += 1
            return None

    before = observe_cgroup()
    memory_before = observe_memory()
    started = time.monotonic()
    stop = threading.Event()
    sample()

    def sample_until_done():
        while not stop.wait(interval):
            sample()

    thread = threading.Thread(target=sample_until_done, daemon=True)
    thread.start()
    process = None
    handlers = {}
    returncode = 127
    try:
        # Inherit output directly; the wrapper never collects command output.
        process = subprocess.Popen([*command, f"--max-workers={workers}"], start_new_session=True)

        def forward(signum, _frame):
            try:
                os.killpg(process.pid, signum)
            except ProcessLookupError:
                pass

        for signum in (signal.SIGINT, signal.SIGTERM):
            handlers[signum] = signal.signal(signum, forward)
        returncode = process.wait()
    except OSError:
        print("Could not start Gradle command", file=sys.stderr, flush=True)
    finally:
        stop.set()
        thread.join()
        sample()
        for signum, handler in handlers.items():
            signal.signal(signum, handler)
        after = observe_cgroup()
        memory_after = observe_memory()
        exit_status = returncode if returncode >= 0 else 128 - returncode
        print("SILO_CI_RESOURCE_SUMMARY " + json.dumps({
            **sampler.summary(),
            "worker_limit": workers,
            "requested_android_shared_debug_test_forks": requested_shared_forks,
            "gradle_profile_requested": "--profile" in command,
            "observation_error_count": observation_errors,
            "sample_interval_seconds": interval,
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "child_returncode": returncode,
            "exit_status": exit_status,
            "cgroup_before": before,
            "cgroup_after": after,
            "cgroup_counter_delta": counter_changes(before, after),
            "memory_before": memory_before,
            "memory_after": memory_after,
            "cgroup_limits": "Counters cover the wrapper's cgroup and descendants and do not identify the process responsible for an OOM; absent increments do not exclude JVM heap OOM or other kill causes.",
        }, sort_keys=True), flush=True)
    return exit_status


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-workers", required=True, type=worker_limit)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("A Gradle command is required")
    if any(arg == "--max-workers" or arg.startswith(("--max-workers=", "-Dorg.gradle.workers.max=")) for arg in command):
        parser.error("The wrapper owns the worker limit")
    if "--configuration-cache" in command:
        parser.error("Worker tuning requires configuration cache off")
    try:
        metadata_workers = worker_limit(os.environ.get("SILO_CI_WORKER_LIMIT", str(args.max_workers)))
        if metadata_workers != args.max_workers:
            parser.error("Worker metadata differs from the command worker limit")
        shared_test_forks(os.environ.get("SILO_CI_ANDROID_SHARED_TEST_FORKS", "1"))
    except ValueError as error:
        parser.error(str(error))
    return run([*command, "--init-script", str(Path(__file__).with_name("ci-jvm-settings.gradle")), "--profile"], args.max_workers)


if __name__ == "__main__":
    raise SystemExit(main())
