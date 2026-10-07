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


def cgroup_directory(proc, fallback=Path("/sys/fs/cgroup")):
    """Resolve the current cgroup using mount roots and namespace-relative paths."""
    try:
        memberships = [line.split(":", 2) for line in (proc / "self/cgroup").read_text(errors="replace").splitlines()]
        selected = next((("v2", path) for _, controllers, path in memberships if not controllers), None)
        if selected is None:
            selected = next((("v1", path) for _, controllers, path in memberships if "memory" in controllers.split(",")), None)
        if selected is None:
            return None, None
        version, member = selected
        member_path = PurePosixPath(member)
        if not member_path.is_absolute() or ".." in member_path.parts:
            return None, None
        try:
            mounts = (proc / "self/mountinfo").read_text(errors="replace").splitlines()
        except OSError:
            mounts = []
        for line in mounts:
            before, separator, after = line.partition(" - ")
            fields, fs = before.split(), after.split()
            if not separator or len(fields) < 5 or len(fs) < 3:
                continue
            if not (version == "v2" and fs[0] == "cgroup2" or version == "v1" and fs[0] == "cgroup" and "memory" in fs[2].split(",")):
                continue
            root = PurePosixPath(str(_mount_path(fields[3])))
            try:
                relative = member_path.relative_to(root)
            except ValueError:
                # A cgroup namespace can expose '/' while the mount root is a
                # host-side subtree. The membership is then mount-relative.
                relative = member_path.relative_to("/")
            return version, _mount_path(fields[4]) / str(relative)
        base = fallback if version == "v2" else fallback / "memory"
        return version, base / str(member_path.relative_to("/"))
    except (OSError, ValueError):
        return None, None


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

    def sample(self):
        if not self.enabled:
            return
        available = memory_value(self.proc, "MemAvailable")
        rss, count, unreadable = java_rss(self.proc)
        self.samples += 1
        if unreadable is not None:
            self.unreadable += unreadable
        if available is not None:
            self.min_available = available if self.min_available is None else min(self.min_available, available)
        if rss is not None:
            self.max_rss = rss if self.max_rss is None else max(self.max_rss, rss)
            self.max_java_count = count if self.max_java_count is None else max(self.max_java_count, count)

    def summary(self):
        return {
            "scope": "whole_vm_proc_view",
            "sampling_available": self.enabled,
            "sample_count": self.samples,
            "min_mem_available_kib": self.min_available,
            "max_summed_java_rss_kib": self.max_rss,
            "max_java_process_count": self.max_java_count,
            "unreadable_process_observations": self.unreadable,
            "limits": "Interval samples can miss peaks; unrelated Java and shared RSS pages are included; unreadable processes can undercount RSS; missing data is null.",
        }


def run(command, workers, interval=1.0):
    sampler = MemorySampler()
    observation_errors = 0

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

    before = observe_cgroup()
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
        exit_status = returncode if returncode >= 0 else 128 - returncode
        print("SILO_CI_RESOURCE_SUMMARY " + json.dumps({
            **sampler.summary(),
            "worker_limit": workers,
            "observation_error_count": observation_errors,
            "sample_interval_seconds": interval,
            "elapsed_seconds": round(time.monotonic() - started, 3),
            "child_returncode": returncode,
            "exit_status": exit_status,
            "cgroup_before": before,
            "cgroup_after": after,
            "cgroup_counter_delta": counter_changes(before, after),
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
    try:
        worker_limit(os.environ.get("SILO_CI_REQUESTED_WORKERS", str(args.max_workers)))
    except ValueError as error:
        parser.error(str(error))
    return run(command, args.max_workers)


if __name__ == "__main__":
    raise SystemExit(main())
