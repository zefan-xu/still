#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Prints the latest test run as Markdown for the GitHub Actions job summary.

--live-outputs prints the live verdict (passed, blocked or broken) and the blocked and broken
platforms as GitHub Actions step outputs instead."""
import json
from pathlib import Path
import sys

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
    live = report.get("live")
    if live:
        lines += ["", f"**Live verdict: {verdict(report)}.** Blocked means the platform refused this network; "
                  "only broken platforms open the `live-check` issue.", ""]
        lines += ["| Platform | Result | Error |", "| --- | --- | --- |"]
        for status in ("broken", "blocked"):
            for name, error in live[status].items():
                error = " ".join(error.split())
                shown = error if len(error) <= 300 else error[:299] + "…"
                lines.append(f"| {name} | {'**broken**' if status == 'broken' else 'blocked'} | "
                             f"{shown.replace('|', '&#124;')} |")
        if live["passed"]:
            lines.append(f"| {', '.join(live['passed'])} | pass | |")
    return "\n".join(lines) + "\n"


def verdict(report):
    """Blocked only when every failure is a blocked platform; anything unexplained is broken."""
    if report.get("passed"):
        return "passed"
    live = report.get("live")
    if not live or live["broken"] or not live["blocked"] or report.get("error"):
        return "broken"
    if any(not s.get("passed") for s in report.get("suites", []) if "evidence" not in s):
        return "broken"
    return "blocked"


def live_outputs(report):
    live = (report or {}).get("live") or {"blocked": {}, "broken": {}}
    return "\n".join([f"verdict={verdict(report) if report else 'broken'}",
                      f"blocked={', '.join(live['blocked'])}",
                      f"broken={', '.join(live['broken'])}"]) + "\n"


def main():
    runs = sorted((ROOT / "artifacts/test-runs").glob("*/report.json"))
    report = json.loads(runs[-1].read_text()) if runs else None
    if sys.argv[1:] == ["--live-outputs"]:
        print(live_outputs(report), end="")
    else:
        print(summary(report) if report else "No test report was written.")


if __name__ == "__main__":
    main()
