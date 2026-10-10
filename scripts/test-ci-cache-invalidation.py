#!/usr/bin/env python3
"""Prove source-reading tests fail after file-only edits in a warm workspace."""

import json
import re
import subprocess
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
logs = root / "build/ci-cache-invalidation"
logs.mkdir(parents=True, exist_ok=True)
probes = [
    (
        "workflow",
        ":androidTvApp:testDebugUnitTest",
        "org.siloserver.silo.tv.ui.screens.player.TvFireTvRcFeedbackOwnershipTest",
        "release display version keeps the complete tag",
        root / ".github/workflows/release.yml",
        lambda text: text.replace(
            "SILO_DISPLAY_VERSION: ${{ needs.setup.outputs.version }}",
            "SILO_DISPLAY_VERSION: ${{ needs.setup.outputs.version_name }}",
        ),
    ),
    (
        "sibling-manifest",
        ":androidApp:testDebugUnitTest",
        "org.siloserver.silo.android.auth.NativeSignInManifestTest",
        "tvNeverClaimsTheAppRedirect",
        root / "androidTvApp/src/androidMain/AndroidManifest.xml",
        lambda text: text + '\n<!-- org.siloserver.silo" -->\n',
    ),
    (
        "source-comment",
        ":shared:testDebugUnitTest",
        "org.siloserver.silo.network.apiv2.ApiV2NoV1TransportSourceTest",
        "noV1PathLiteralUnderApiV2Package",
        next((root / "shared/src/commonMain/kotlin/org/siloserver/silo/network/apiv2").glob("*.kt")),
        lambda text: text + "\n// /api/v1\n",
    ),
]

results = []
for name, task, test, method, source, mutate in probes:
    command = [
        str(root / "gradlew"), task, "--tests", test,
        "--build-cache", "--max-workers=2", "--console=plain",
        '-Dorg.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8',
    ]
    report = root / task.split(":")[1] / "build/test-results/testDebugUnitTest" / f"TEST-{test}.xml"

    def run(label):
        result = subprocess.run(command, cwd=root, capture_output=True, text=True)
        output = result.stdout + result.stderr
        (logs / f"{name}-{label}.log").write_text(output)
        suffix = " FAILED" if label == "changed" else ""
        if not re.search(r"^> Task " + re.escape(task) + suffix + r"$", output, re.MULTILINE):
            raise RuntimeError(f"{name}/{label}: the target Test task did not execute")
        if not report.exists():
            raise RuntimeError(f"{name}/{label}: the target test produced no report")
        (logs / f"{name}-{label}.xml").write_bytes(report.read_bytes())
        cases = ET.parse(report).getroot().findall("testcase")
        target = [case for case in cases if case.get("classname") == test and case.get("name") == method]
        if len(target) != 1 or any(case.find("skipped") is not None for case in cases):
            raise RuntimeError(f"{name}/{label}: the target test did not execute")
        if label == "changed":
            failures = target[0].findall("failure")
            if len(failures) != 1 or failures[0].get("type") != "java.lang.AssertionError":
                raise RuntimeError(f"{name}: mutation did not fail the expected assertion")
        elif result.returncode or any(case.find("failure") is not None or case.find("error") is not None for case in cases):
            raise RuntimeError(f"{name}/{label}: unchanged focused tests failed")
        return result.returncode, output

    original = source.read_bytes()
    changed = mutate(original.decode())
    if changed == original.decode():
        raise RuntimeError(f"{name}: mutation did not change the input")
    try:
        status, output = run("warm")
        source.write_text(changed)
        status, output = run("changed")
        if not status or not re.search(r"^> Task " + re.escape(task) + r" FAILED$", output, re.MULTILINE):
            raise RuntimeError(f"{name}: changed source was not rejected by the Test task")
    finally:
        source.write_bytes(original)
    status, output = run("restored")
    if status:
        raise RuntimeError(f"{name}: restored focused test failed")
    results.append({"probe": name, "changed_input": str(source.relative_to(root)), "result": "passed"})
    print(f"{name}: warm passed; file-only mutation failed; restoration passed", flush=True)

(logs / "results.json").write_text(json.dumps(results, indent=2))
