#!/usr/bin/env python3
"""Record reproducible build inputs without publishing a Build Scan."""

import json
import os
import re
import subprocess
from pathlib import Path

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("ci_gradle", Path(__file__).with_name("ci-gradle.py"))
ci_gradle = module_from_spec(spec)
spec.loader.exec_module(ci_gradle)
requested_workers = ci_gradle.worker_limit(os.environ.get("SILO_CI_REQUESTED_WORKERS", "2"))
workers = ci_gradle.worker_limit(os.environ.get("SILO_CI_WORKER_LIMIT", "2"))

source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text()
catalog = Path("gradle/libs.versions.toml").read_text()
gradle = re.search(r"gradle-([\d.]+)-bin", wrapper).group(1)
agp = re.search(r'^agp = "([^"]+)"', catalog, re.MULTILINE).group(1)
cache = os.environ.get("SILO_CI_CACHE_FLAG", "--build-cache")
event_path = os.environ.get("GITHUB_EVENT_PATH")
event = json.loads(Path(event_path).read_text()) if event_path else {}
pull_request = event.get("pull_request") or {}
head_repository = (pull_request.get("head") or {}).get("repo") or {}
metadata = {
    "source_sha": source_sha,
    "workflow_sha": os.environ.get("GITHUB_WORKFLOW_SHA", os.environ.get("GITHUB_SHA", "local")),
    "variant": "baseline" if cache == "--no-build-cache" else "optimized",
    "cache_regime": "unverified",
    "cache_namespace": os.environ.get("GITHUB_REF", "local"),
    "job": os.environ.get("GITHUB_JOB", "local"),
    "requested_worker_limit": requested_workers,
    "worker_limit": workers,
    "runner_cpu_count": os.cpu_count(),
    "runner_memory_total_kib": ci_gradle.memory_value(Path("/proc"), "MemTotal") if ci_gradle.is_linux() else None,
    "profile": " ".join(
        os.environ.get(key, "")
        for key in ("SILO_CI_CACHE_FLAG", "SILO_CI_PARALLEL_FLAG", "SILO_CI_CONFIGURATION_FLAG")
    ).strip(),
    "toolchain": f"JDK 21 / Gradle {gradle} / AGP {agp}",
    "java_version": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).splitlines()[0],
    "runner_image": os.environ.get("ImageVersion", "unavailable"),
    "cache_policy": {
        "read_only": os.environ.get("SILO_CI_CACHE_READ_ONLY", "unavailable"),
        "event_name": os.environ.get("GITHUB_EVENT_NAME", "local"),
        "head_repository": head_repository.get("full_name", ""),
        "author_association": pull_request.get("author_association", ""),
    },
}
print("SILO_CI_BENCHMARK " + json.dumps(metadata, sort_keys=True))
