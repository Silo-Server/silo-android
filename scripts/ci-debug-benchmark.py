#!/usr/bin/env python3
"""Qualify a manual debug-layout experiment without changing Gradle work."""

import argparse
from html.parser import HTMLParser
import importlib.util
import json
import os
from pathlib import Path
import time
import xml.etree.ElementTree as ET


REF = "refs/heads/ci/android-combined-debug-benchmark"
MODULES = {"android-shared", "androidApp", "androidTvApp", "shared", "libass-bridge"}
TESTS = {f":{module}:testDebugUnitTest" for module in MODULES}
LINT = {f":{module}:lintDebug" for module in ("android-shared", "androidApp", "androidTvApp")}
KINDS = {"unit", "lint", "combined"}
PROOF = Path(".ci-debug-benchmark")


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name.replace("_", "-") + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def guard(env):
    if (env.get("GITHUB_REPOSITORY"), env.get("GITHUB_EVENT_NAME"), env.get("GITHUB_REF")) != (
        "Silo-Server/silo-android", "workflow_dispatch", REF
    ):
        raise ValueError("This experiment requires its own repository, manual event and exact branch")
    layout = env.get("SILO_CI_DEBUG_LAYOUT")
    if layout not in {"separate", "combined"}:
        raise ValueError("Unknown debug layout")
    if any(env.get(f"SILO_CI_{job}_WORKERS") != "2" for job in ("UNIT", "LINT")):
        raise ValueError("Unsupported worker setting")
    if any(env.get(f"SILO_CI_{job}_PARALLEL") not in {"true", "false"} for job in ("UNIT", "LINT")):
        raise ValueError("Unsupported parallel setting")
    if env.get("SILO_CI_SHARED_FORKS") not in {"1", "2"}:
        raise ValueError("Unsupported shared Test forks")
    if layout == "combined" and any(
        env[f"SILO_CI_UNIT_{setting}"] != env[f"SILO_CI_LINT_{setting}"] for setting in ("WORKERS", "PARALLEL")
    ):
        raise ValueError("One invocation requires identical unit/lint worker and parallel settings")
    return layout


class Tasks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.row = self.cell = None
        self.tasks = {}

    def handle_starttag(self, tag, attrs):
        if tag == "tr":
            self.row = []
        elif tag == "td" and self.row is not None:
            self.cell = [dict(attrs).get("class", ""), ""]

    def handle_data(self, data):
        if self.cell is not None:
            self.cell[1] += data

    def handle_endtag(self, tag):
        if tag == "td" and self.cell is not None:
            self.row.append(self.cell)
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if len(self.row) == 3 and "indentPath" in self.row[0][0].split():
                path = self.row[0][1].strip()
                if path in self.tasks:
                    raise ValueError("Duplicate profiled task")
                self.tasks[path] = self.row[2][1].strip()
            self.row = None


def coverage(root, kind, started, ended):
    if kind not in KINDS:
        raise ValueError("Unknown coverage kind")
    profiles = list((root / "build/reports/profile").glob("profile-*.html"))
    fresh = [p for p in profiles if started <= p.stat().st_mtime_ns <= ended]
    if len(fresh) != 1:
        raise ValueError("Expected one fresh Gradle profile")
    parser = Tasks()
    parser.feed(fresh[0].read_text())
    selected_tests = {p for p in parser.tasks if p.rsplit(":", 1)[-1].startswith("test") and p.endswith("UnitTest")}
    unit = kind in {"unit", "combined"}
    lint = kind in {"lint", "combined"}
    if selected_tests != (TESTS if unit else set()):
        raise ValueError("Profile does not contain the exact five debug Test tasks")
    if any(parser.tasks[p] != "" for p in selected_tests):
        raise ValueError("Every debug Test must execute freshly")
    selected_lint = {p for p in parser.tasks if p.endswith(":lintDebug")}
    if selected_lint != (LINT if lint else set()):
        raise ValueError("Profile does not contain the exact three debug lint tasks")
    if any(parser.tasks[p] not in {"", "UP-TO-DATE", "FROM-CACHE"} for p in selected_lint):
        raise ValueError("Selected lint did not complete")
    lint_reports = [root / module / "build/reports/lint-results-debug.xml"
                    for module in ("android-shared", "androidApp", "androidTvApp")] if lint else []
    for report in lint_reports:
        if not report.is_file() or report.stat().st_size == 0:
            raise ValueError("A selected debug lint report is missing or empty")
        try:
            if ET.parse(report).getroot().tag != "issues":
                raise ValueError("Invalid debug lint report")
        except ET.ParseError as error:
            raise ValueError("Malformed debug lint report") from error
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    reports = sorted(root.glob("*/build/test-results/testDebugUnitTest/TEST-*.xml")) if unit else []
    if unit:
        if len(reports) != 714 or {p.relative_to(root).parts[0] for p in reports} != MODULES:
            raise ValueError("Expected 714 reports from all five debug modules")
        for report in reports:
            if not started <= report.stat().st_mtime_ns <= ended:
                raise ValueError("Stale or future JUnit report")
            suite = ET.parse(report).getroot()
            cases = suite.findall("testcase")
            actual = {"tests": len(cases), **{
                key: sum(c.find(tag) is not None for c in cases)
                for key, tag in (("failures", "failure"), ("errors", "error"), ("skipped", "skipped"))
            }}
            if suite.tag != "testsuite" or any(int(suite.attrib[k]) != actual[k] for k in totals):
                raise ValueError("Inconsistent JUnit suite")
            for key in totals:
                totals[key] += actual[key]
        if totals != {"tests": 5531, "failures": 0, "errors": 0, "skipped": 0}:
            raise ValueError("The frozen debug suite must pass all 5531 cases without skips")
    return {"unit_proof": unit, "lint_proof": lint, **totals, "reports": len(reports),
            "fresh_test_tasks": sorted(selected_tests), "lint_tasks": sorted(selected_lint),
            "lint_reports": [str(p.relative_to(root)) for p in lint_reports]}


def result(needs, layout):
    if layout not in {"separate", "combined"} or not isinstance(needs, dict) or set(needs) != {
        "changes", "unit-tests", "lint", "combined-debug", "release-readiness", "ffmpeg-aar"
    }:
        raise ValueError("Unknown layout or missing/unfamiliar dependency")
    if any(not isinstance(value, dict) for value in needs.values()):
        raise ValueError("Malformed dependency result")
    def proved(job, key):
        outputs = needs[job].get("outputs")
        return isinstance(outputs, dict) and outputs.get(key) == "true"
    if layout == "separate":
        if needs["combined-debug"].get("result") != "skipped":
            raise ValueError("Combined job must be skipped in the control")
        mode = load("ci_result").check_results({k: v for k, v in needs.items() if k != "combined-debug"})
        if mode == "full" and any(not proved(j, proof) for j, proof in (
            ("unit-tests", "unit_proof"), ("lint", "lint_proof")
        )):
            raise ValueError("Separate job coverage proof missing")
        return mode
    classifier = needs["changes"]
    if classifier.get("result") != "success":
        raise ValueError("Change selection did not succeed")
    outputs = classifier.get("outputs")
    mode = outputs.get("mode") if isinstance(outputs, dict) else None
    if mode not in {"full", "docs", "ffmpeg"} or outputs != load("ci_routing").outputs(mode):
        raise ValueError("Incomplete or inconsistent classifier outputs")
    if any(needs[j].get("result") != "skipped" for j in ("unit-tests", "lint")):
        raise ValueError("Separate debug jobs must be skipped in combined mode")
    for job, selected in (("combined-debug", mode == "full"), ("release-readiness", mode == "full"), ("ffmpeg-aar", mode == "ffmpeg")):
        if needs[job].get("result") != ("success" if selected else "skipped"):
            raise ValueError(f"Unexpected result for {job}")
    if mode == "full" and any(not proved("combined-debug", p) for p in ("unit_proof", "lint_proof")):
        raise ValueError("Combined job must prove real unit and lint coverage")
    return mode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("guard", "start", "coverage", "result"))
    parser.add_argument("--kind", choices=sorted(KINDS))
    args = parser.parse_args()
    try:
        if args.command == "guard":
            print("Debug benchmark layout: " + guard(os.environ))
        elif args.command == "result":
            print("Debug layout benchmark passed (" + result(json.loads(os.environ["SILO_CI_NEEDS_JSON"]), os.environ["SILO_CI_DEBUG_LAYOUT"]) + ")")
        else:
            if not args.kind:
                raise ValueError("Coverage kind required")
            PROOF.mkdir(exist_ok=True)
            start = PROOF / f"{args.kind}-start.json"
            if args.command == "start":
                start.write_text(json.dumps({"started_ns": time.time_ns()}))
            else:
                proof = coverage(Path.cwd(), args.kind, json.loads(start.read_text())["started_ns"], time.time_ns())
                (PROOF / f"{args.kind}-coverage.json").write_text(json.dumps(proof, sort_keys=True) + "\n")
                print("SILO_CI_DEBUG_COVERAGE " + json.dumps(proof, sort_keys=True))
                if os.environ.get("GITHUB_OUTPUT"):
                    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
                        for key in ("unit_proof", "lint_proof"):
                            output.write(f"{key}={str(proof[key]).lower()}\n")
    except (KeyError, ValueError, TypeError, OSError, ET.ParseError) as error:
        print(f"Debug layout benchmark failed: {error}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
