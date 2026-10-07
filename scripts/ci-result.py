#!/usr/bin/env python3
"""Fail the stable Android CI check unless every selected job succeeded."""

import json
import os
import sys


JOBS = {
    "unit-tests": "unit_tests",
    "lint": "lint",
    "release-readiness": "release_readiness",
    "ffmpeg-aar": "ffmpeg_aar",
}


def check_results(needs):
    if not isinstance(needs, dict) or set(needs) != {"changes", *JOBS}:
        raise ValueError("Missing or unfamiliar dependency results")
    classifier = needs["changes"]
    if not isinstance(classifier, dict) or classifier.get("result") != "success":
        raise ValueError("Change classification did not succeed")
    selected = classifier.get("outputs")
    if not isinstance(selected, dict):
        raise ValueError("Missing classifier outputs")
    mode = selected.get("mode")
    if mode not in {"full", "docs", "ffmpeg"}:
        raise ValueError("Invalid classifier mode")
    expected = {
        "schema": "1",
        "mode": mode,
        "unit_tests": "true" if mode == "full" else "false",
        "lint": "true" if mode == "full" else "false",
        "release_readiness": "true" if mode == "full" else "false",
        "ffmpeg_aar": "true" if mode == "ffmpeg" else "false",
    }
    if selected != expected:
        raise ValueError("Incomplete or inconsistent classifier outputs")
    for job, output in JOBS.items():
        result = needs[job].get("result") if isinstance(needs[job], dict) else None
        required_result = "success" if selected[output] == "true" else "skipped"
        if result != required_result:
            raise ValueError(f"{job}: expected {required_result}, got {result!r}")
    return mode


def main():
    try:
        mode = check_results(json.loads(os.environ["SILO_CI_NEEDS_JSON"]))
    except (KeyError, ValueError, TypeError) as error:
        print(f"Android CI failed: {error}", file=sys.stderr)
        return 1
    print(f"Android CI passed ({mode})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
