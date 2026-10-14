#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prints the latest test run as Markdown for the GitHub Actions job summary."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def summary(report):
    lines = [f"### {'✅' if report.get('passed') else '❌'} `scripts/test.py {report.get('mode')}`", ""]
    lines += ["| Suite | Result | Detail |", "| --- | --- | --- |"]
    for suite in report.get("suites", []):
        detail = []
        if "jvm_tests" in suite:
            detail.append(f"{suite['jvm_tests']} JVM tests")
        for key, label in (("line_coverage", "line coverage"), ("branch_coverage", "branch coverage"),
                           ("mutation_score", "mutants killed")):
            if key in suite:
                detail.append(f"{suite[key]:.1%} {label}")
        if "count" in suite:
            detail.append(f"{suite['count']} device tests")
        detail += suite.get("errors", [])[:3]
        lines.append(f"| {suite.get('name')} | {'pass' if suite.get('passed') else '**fail**'} | {'; '.join(detail)} |")
    if report.get("error"):
        lines += ["", f"> {report['error']}"]
    return "\n".join(lines) + "\n"


def main():
    runs = sorted((ROOT / "artifacts/test-runs").glob("*/report.json"))
    print(summary(json.loads(runs[-1].read_text())) if runs else "No test report was written.")


if __name__ == "__main__":
    main()
