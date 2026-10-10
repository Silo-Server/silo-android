#!/usr/bin/env python3
"""Select Android CI work from a complete Git diff; uncertainty runs all jobs."""

import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess


# These are prose-only areas. Keep policies, API contracts, fixtures, native
# notices, scripts, and unfamiliar documentation locations on full CI.
DOC_FILES = {
    "README.md",
    "FEATURES.md",
    "scripts/README-ffmpeg-aar.md",
    "docs/playback/README.md",
    "docs/playback/intro-skip.md",
    "docs/playback/01-media3-only-player-architecture.md",
    "docs/playback/02-migration-compatibility-validation.md",
    "docs/playback/03-reference-implementation-review.md",
    "docs/playback/04-implementation-status-and-dv-handoff.md",
}
DOC_DIRECTORIES = (
    "docs/notes/",
    "docs/plans/",
    "docs/superpowers/notes/",
    "docs/superpowers/plans/",
    "docs/superpowers/specs/",
)
SHA = re.compile(r"[0-9a-f]{40}")


def outputs(mode):
    return {
        "schema": "1",
        "mode": mode,
        "unit_tests": str(mode == "full").lower(),
        "lint": str(mode == "full").lower(),
        "release_readiness": str(mode == "full").lower(),
        "ffmpeg_aar": str(mode == "ffmpeg").lower(),
    }


def harmless_document(path):
    parts = PurePosixPath(path).parts
    if (
        not parts
        or path.startswith("/")
        or any(part in {"", ".", ".."} for part in path.split("/"))
        or any(ord(character) < 32 for character in path)
        or "\\" in path
    ):
        return False
    # Contract and fixture documents are deliberately outside the prose route,
    # even if a future one is added below an otherwise permitted directory.
    lower_path = path.lower()
    if any(word in lower_path for word in ("api-v2", "contract", "fixture")):
        return False
    return path in DOC_FILES or (
        path.endswith(".md") and path.startswith(DOC_DIRECTORIES)
    )


def classify_diff(data):
    """Consume git diff --name-status -z, including rename source and target."""
    if not data or not data.endswith(b"\0"):
        return "full", "Missing or incomplete diff"
    try:
        fields = data[:-1].decode("utf-8", errors="strict").split("\0")
    except UnicodeDecodeError:
        return "full", "Diff paths are not valid UTF-8"
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        if status not in {"A", "M"}:
            return "full", "Deletion, rename, or unfamiliar change status"
        if index >= len(fields) or not harmless_document(fields[index]):
            return "full", "Changed path requires Android validation"
        index += 1
    return "docs", "Only prose documentation was added or modified"


def git(*arguments):
    return subprocess.run(
        ["git", *arguments], check=True, capture_output=True, timeout=30
    ).stdout


def classify_environment(environment):
    event_name = environment.get("GITHUB_EVENT_NAME", "")
    ffmpeg = environment.get("SILO_CI_FFMPEG_ONLY", "false")
    if ffmpeg not in {"true", "false"}:
        return "full", "Invalid manual FFmpeg input"
    if event_name == "workflow_dispatch":
        return ("ffmpeg", "Explicit FFmpeg-only dispatch") if ffmpeg == "true" else (
            "full", "Manual dispatch requests full Android validation"
        )
    if event_name not in {"pull_request", "push"} or ffmpeg == "true":
        return "full", "Unfamiliar event or invalid FFmpeg event"
    try:
        event = json.loads(Path(environment["GITHUB_EVENT_PATH"]).read_text())
        base = event["pull_request"]["base"]["sha"] if event_name == "pull_request" else event["before"]
        head = environment["GITHUB_SHA"]
        if not isinstance(base, str) or not SHA.fullmatch(base) or base == "0" * 40:
            return "full", "No usable base commit"
        if not SHA.fullmatch(head) or git("rev-parse", "HEAD").decode().strip() != head:
            return "full", "Checkout does not match the requested commit"
        # Checkout stays shallow. Fetch only the exact base/before commit if it
        # is absent; failures keep all validation rather than trusting a file list
        # from the API (which can be truncated).
        try:
            git("cat-file", "-e", f"{base}^{{commit}}")
        except subprocess.CalledProcessError:
            git("fetch", "--no-tags", "--depth=1", "origin", base)
        data = git("diff", "--name-status", "-z", "--find-renames", base, head, "--")
        mode, reason = classify_diff(data)
        if mode == "docs":
            paths = data[:-1].decode("utf-8").split("\0")[1::2]
            tree = git("ls-tree", "-z", head, "--", *paths)
            entries = tree[:-1].split(b"\0") if tree.endswith(b"\0") else []
            found = set()
            for entry in entries:
                metadata, path = entry.split(b"\t", 1)
                if not metadata.startswith(b"100644 blob "):
                    return "full", "Documentation is not a regular prose file"
                found.add(path.decode("utf-8"))
            if found != set(paths):
                return "full", "Could not verify every documentation file"
    except (KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
        return "full", "Could not obtain a complete diff"
    return mode, reason


def main():
    mode, reason = classify_environment(os.environ)
    selected = outputs(mode)
    print(json.dumps({**selected, "reason": reason}, sort_keys=True))
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in selected.items():
                output.write(f"{key}={value}\n")


if __name__ == "__main__":
    main()
