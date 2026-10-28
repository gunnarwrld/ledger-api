#!/usr/bin/env python3
"""Summarize JUnit and Kover results, and keep the coverage badge in sync."""

import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REPORT = Path("build/reports/kover/report.xml")
BADGE = Path(".github/badges/coverage.svg")
TEST_RESULTS = Path("build/test-results/test")


def line_coverage(report: Path) -> tuple[int, int]:
    root = ET.parse(report).getroot()
    for counter in root.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered", "0")), int(counter.get("missed", "0"))
    sys.exit(f"{report} has no report-level line counter")


def percent(covered: int, missed: int) -> int:
    total = covered + missed
    if total == 0:
        return 0
    return round((100 * covered) / total)


def badge_svg(pct: int) -> str:
    color = "#4c1" if pct >= 80 else "#dfb317" if pct >= 60 else "#e05d44"
    value = f"{pct}%"
    width = 104 if len(value) <= 3 else 112
    label_width = 62
    value_width = width - label_width
    value_center = label_width + (value_width / 2)
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="20" role="img" '
        'aria-label="coverage: {value}">\n'
        '  <linearGradient id="smooth" x2="0" y2="100%">\n'
        '    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>\n'
        '    <stop offset="1" stop-opacity=".1"/>\n'
        "  </linearGradient>\n"
        '  <clipPath id="round"><rect width="{width}" height="20" rx="3" fill="#fff"/></clipPath>\n'
        '  <g clip-path="url(#round)">\n'
        '    <rect width="{label_width}" height="20" fill="#555"/>\n'
        '    <rect x="{label_width}" width="{value_width}" height="20" fill="{color}"/>\n'
        '    <rect width="{width}" height="20" fill="url(#smooth)"/>\n'
        "  </g>\n"
        '  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">\n'
        '    <text x="31" y="14">coverage</text>\n'
        '    <text x="{value_center}" y="14">{value}</text>\n'
        "  </g>\n"
        "</svg>\n"
    ).format(
        width=width,
        value=value,
        label_width=label_width,
        value_width=value_width,
        value_center=value_center,
        color=color,
    )


def junit_totals() -> tuple[int, int, int, int]:
    tests = failures = errors = skipped = 0
    if not TEST_RESULTS.exists():
        return tests, failures, errors, skipped
    for path in TEST_RESULTS.glob("TEST-*.xml"):
        suite = ET.parse(path).getroot()
        tests += int(suite.get("tests", "0"))
        failures += int(suite.get("failures", "0"))
        errors += int(suite.get("errors", "0"))
        skipped += int(suite.get("skipped", "0"))
    return tests, failures, errors, skipped


def write_summary(pct: int, covered: int, missed: int) -> None:
    tests, failures, errors, skipped = junit_totals()
    passed = tests - failures - errors - skipped
    lines = [
        "## Tests",
        "",
        f"- Passed: {passed}",
        f"- Failed: {failures}",
        f"- Errors: {errors}",
        f"- Skipped: {skipped}",
        "",
        "## Coverage",
        "",
        f"- Line coverage: {pct}% ({covered} covered, {missed} missed)",
        "",
    ]
    text = "\n".join(lines)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text)


def main() -> None:
    if not REPORT.exists():
        sys.exit(f"missing {REPORT}; run ./gradlew koverXmlReport")
    covered, missed = line_coverage(REPORT)
    pct = percent(covered, missed)
    expected = badge_svg(pct)
    write_summary(pct, covered, missed)
    current = BADGE.read_text(encoding="utf-8") if BADGE.exists() else ""
    if current != expected:
        if "--write" in sys.argv:
            BADGE.parent.mkdir(parents=True, exist_ok=True)
            BADGE.write_text(expected, encoding="utf-8")
            print(f"wrote {BADGE} ({pct}%)")
            return
        sys.exit(
            f"coverage badge does not match Kover line coverage ({pct}%). "
            "Run: python3 scripts/ci_summary.py --write",
        )


if __name__ == "__main__":
    main()
