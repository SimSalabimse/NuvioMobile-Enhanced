#!/usr/bin/env python3
"""Fail the Android host-test job only when a failure is outside the baseline list."""

import os
import pathlib
import sys
import xml.etree.ElementTree as ET

baseline_path = pathlib.Path(".github/test-baseline-failures.txt")
allowed = []
for raw in baseline_path.read_text(encoding="utf-8").splitlines():
    line = raw.split("#", 1)[0].strip()
    if line:
        allowed.append(line)
allowed_set = set(allowed)


def normalize(name: str) -> str:
    return name.split("[", 1)[0].strip()


# Gradle copies the same XML under intermediates/. Count only the task results.
results_dir = pathlib.Path("composeApp/build/test-results/testAndroidHostTest")
xml_files = sorted(results_dir.glob("TEST-*.xml")) if results_dir.is_dir() else []

tests = failures = errors = skipped = 0
failed = []
seen_suites = set()
for path in xml_files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as error:
        print(f"Could not parse {path}: {error}", file=sys.stderr)
        continue
    suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
    for suite in suites:
        if suite.tag != "testsuite":
            continue
        identity = (
            path.as_posix(),
            suite.attrib.get("name", ""),
            suite.attrib.get("timestamp", ""),
            suite.attrib.get("tests", ""),
        )
        if identity in seen_suites:
            continue
        seen_suites.add(identity)
        tests += int(suite.attrib.get("tests", "0") or 0)
        failures += int(suite.attrib.get("failures", "0") or 0)
        errors += int(suite.attrib.get("errors", "0") or 0)
        skipped += int(suite.attrib.get("skipped", "0") or 0)
        for case in suite.findall("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            classname = case.attrib.get("classname", "").strip()
            method = normalize(case.attrib.get("name", ""))
            failed.append(f"{classname}.{method}" if classname else method)

new_failures = [item for item in failed if item not in allowed_set]
gradle_status = os.environ.get("GRADLE_STATUS", "1")
lines = [
    "## Android host tests",
    "",
    f"- Tests: {tests}",
    f"- Failed: {failures}",
    f"- Errors: {errors}",
    f"- Skipped: {skipped}",
    f"- Baseline entries: {len(allowed)}",
    f"- Failures already in the baseline: {len(failed) - len(new_failures)}",
    f"- New failures: {len(new_failures)}",
    f"- Gradle exit: {gradle_status}",
]
if failed:
    lines.extend(["", "### Failures", ""])
    for item in failed:
        marker = "baseline" if item in allowed_set else "new"
        lines.append(f"- `{item}` ({marker})")
summary = "\n".join(lines) + "\n"
print(summary)
step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
if step_summary:
    with open(step_summary, "a", encoding="utf-8") as handle:
        handle.write(summary)

if not xml_files:
    print("No Android host test results were written.", file=sys.stderr)
    sys.exit(1)
if gradle_status != "0" and not failed:
    print("Gradle failed and reported no test failures.", file=sys.stderr)
    sys.exit(1)
if new_failures:
    print("New failures not in .github/test-baseline-failures.txt:", file=sys.stderr)
    for item in new_failures:
        print(item, file=sys.stderr)
    sys.exit(1)
if failures + errors != len(failed):
    print(
        f"Result XML counts {failures + errors} failures/errors but listed {len(failed)} cases.",
        file=sys.stderr,
    )
    sys.exit(1)
sys.exit(0)
