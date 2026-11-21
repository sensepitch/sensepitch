#!/usr/bin/env python3
"""Summarise repeated test runs to find flaky tests.

Reads one directory per run, each holding the JUnit XML reports of Surefire and Failsafe
(`TEST-*.xml`) and a file `exit-code` with the exit code of Maven. Writes a Markdown report: the
tests that failed, how often and in which runs, then an overview per run.

Usage: flaky_report.py RUNS_DIR [REPORT_FILE]
Exit code 1 if any test failed or any run ended with a build error, so the workflow fails.
"""

import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

FAILURE_TAGS = ("failure", "error")
MESSAGE_LIMIT = 160


def main():
    runs_dir = Path(sys.argv[1])
    report_file = Path(sys.argv[2]) if len(sys.argv) > 2 else None
    runs = sorted((d for d in runs_dir.iterdir() if d.is_dir()), key=run_number)
    failures = defaultdict(list)  # test id -> [(run, message)]
    overview = []
    for run in runs:
        totals = read_run(run, failures)
        overview.append((run.name, totals, read_exit_code(run)))
    report = render(len(runs), failures, overview)
    if report_file:
        report_file.write_text(report)
    print(report)
    build_errors = any(code not in ("0", None) and totals["failed"] == 0
                       for _, totals, code in overview)
    return 1 if failures or build_errors else 0


def run_number(directory):
    """Sort `run-2` before `run-10`."""
    digits = "".join(c for c in directory.name if c.isdigit())
    return int(digits) if digits else 0


def read_run(run, failures):
    """Count the test cases of one run and record each failed one under its test id."""
    totals = {"tests": 0, "failed": 0, "skipped": 0}
    for report in sorted(run.rglob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError as e:
            # a test JVM that died while writing, counted like a failure of the whole class
            failures[report.stem.removeprefix("TEST-")].append((run.name, f"unreadable report: {e}"))
            totals["failed"] += 1
            continue
        for case in root.iter("testcase"):
            totals["tests"] += 1
            problem = next((case.find(tag) for tag in FAILURE_TAGS if case.find(tag) is not None),
                           None)
            if problem is not None:
                totals["failed"] += 1
                failures[test_id(case)].append((run.name, message(problem)))
            elif case.find("skipped") is not None:
                totals["skipped"] += 1
    return totals


def test_id(case):
    """`Class#method`, the class alone for a failure outside a method, e.g. in `@BeforeAll`."""
    name = case.get("name") or ""
    classname = case.get("classname") or "unknown"
    return f"{classname}#{name}" if name else classname


def message(problem):
    """First line of the failure, shortened to fit a table cell."""
    text = (problem.get("message") or problem.get("type") or problem.text or "").strip()
    first = text.splitlines()[0] if text else ""
    if len(first) > MESSAGE_LIMIT:
        first = first[:MESSAGE_LIMIT] + "..."
    return first.replace("|", "\\|")


def read_exit_code(run):
    path = run / "exit-code"
    return path.read_text().strip() if path.exists() else None


def render(run_count, failures, overview):
    lines = [f"## Flaky test hunt, runs: {run_count}", ""]
    if failures:
        lines += ["| Test | Failed | Runs | First message |", "|---|---|---|---|"]
        ranked = sorted(failures.items(), key=lambda item: (-len(item[1]), item[0]))
        for test, occurrences in ranked:
            run_names = ", ".join(name.removeprefix("run-") for name, _ in occurrences)
            lines.append(f"| `{test}` | {len(occurrences)} of {run_count} | {run_names} "
                         f"| {occurrences[0][1]} |")
    else:
        lines.append("No test failed.")
    lines += ["", "| Run | Tests | Failed | Skipped | Maven exit |", "|---|---|---|---|---|"]
    for name, totals, code in overview:
        lines.append(f"| {name} | {totals['tests']} | {totals['failed']} | {totals['skipped']} "
                     f"| {code if code is not None else '?'} |")
    build_errors = [name for name, totals, code in overview
                    if code not in ("0", None) and totals["failed"] == 0]
    if build_errors:
        lines += ["", "Maven failed without a failed test (build error, crashed or timed out "
                  f"test JVM) in: {', '.join(build_errors)}. See the logs of these runs."]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    sys.exit(main())
