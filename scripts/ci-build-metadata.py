#!/usr/bin/env python3
"""Record reproducible build inputs without publishing a Build Scan."""

import json
import os
import re
import subprocess
from pathlib import Path

source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
wrapper = Path("gradle/wrapper/gradle-wrapper.properties").read_text()
catalog = Path("gradle/libs.versions.toml").read_text()
gradle = re.search(r"gradle-([\d.]+)-bin", wrapper).group(1)
agp = re.search(r'^agp = "([^"]+)"', catalog, re.MULTILINE).group(1)
cache = os.environ.get("SILO_CI_CACHE_FLAG", "--build-cache")
metadata = {
    "source_sha": source_sha,
    "workflow_sha": os.environ.get("GITHUB_SHA", "local"),
    "variant": "baseline" if cache == "--no-build-cache" else "optimized",
    "cache_regime": "unverified",
    "cache_namespace": os.environ.get("GITHUB_REF", "local"),
    "profile": " ".join(
        os.environ.get(key, "")
        for key in ("SILO_CI_CACHE_FLAG", "SILO_CI_PARALLEL_FLAG", "SILO_CI_CONFIGURATION_FLAG")
    ).strip(),
    "toolchain": f"JDK 21 / Gradle {gradle} / AGP {agp}",
}
print("SILO_CI_BENCHMARK " + json.dumps(metadata, sort_keys=True))
