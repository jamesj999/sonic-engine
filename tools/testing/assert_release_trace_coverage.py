#!/usr/bin/env python3
"""Verify selected release trace classes executed, including nested tests."""
import fnmatch
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

surefire_dir = Path("target/surefire-reports")
trace_dir = Path("target/trace-reports")
source_root = Path("src/test/java/com/openggf/tests/trace")
TRACE_REPLAY_DIAGNOSTIC_EXCLUDES = (
    "Debug*.java",
    "*Debug*.java",
    "*Probe.java",
    "*Probe*.java",
)
TRACE_REPLAY_PROFILE_EXCLUDES = (
    "**/tests/trace/s3k/**/*Mhz*.java",
    "**/tests/trace/s3k/**/*Fbz*.java",
    "**/tests/trace/s3k/**/*Ssz*.java",
    "**/tests/trace/s3k/**/*Soz*.java",
    "**/tests/trace/s3k/**/*Lrz*.java",
    "**/tests/trace/s3k/**/*Hpz*.java",
    "**/tests/trace/s3k/**/*Ddz*.java",
    "**/tests/trace/s3k/**/*Dez*.java",
    "**/tests/trace/s3k/**/*Zone0c*.java",
    "**/tests/trace/s3k/TestS3kGumballBonusTraceReplay.java",
    "**/tests/trace/s3k/TestS3kPachinkoBonusTraceReplay.java",
    "**/tests/trace/s3k/TestS3kSpecialStageTraceReplay.java",
    "**/tests/trace/runs/TestS3kKnucklesSuperEmeraldRunChain.java",
    "**/tests/trace/runs/TestS3kMegaRunChain.java",
    "**/tests/trace/s3k/sonictails/*.java",
    "**/tests/trace/s3k/*ZoneSliceTraceReplay.java",
)

def is_diagnostic_trace_source(source):
    return any(fnmatch.fnmatch(source.name, pattern)
               for pattern in TRACE_REPLAY_DIAGNOSTIC_EXCLUDES)

def is_release_profile_excluded_source(source):
    relative = source.relative_to(Path("src/test/java")).as_posix()
    if not relative.startswith("com/openggf/tests/trace/"):
        return False
    for pattern in TRACE_REPLAY_PROFILE_EXCLUDES:
        # Ant **/ matches zero or more directories; basename matching would
        # accidentally exclude every S3K class for sonictails/*.java.
        expression = re.escape(pattern).replace(r"\*\*/", "(?:.*/)?").replace(r"\*", "[^/]*")
        if re.fullmatch(expression, relative):
            return True
    return False

# Opt-in measurements are selected by Maven but are not release execution gates.
OPTIONAL_MEASUREMENT_CLASSES = {
    # Still-unrecorded deferred bonus round trips; exact skip reasons are
    # retained and compared, and fixture additions require baseline review.
    "com.openggf.tests.trace.runs.TestS3kBonusRoundTripChain",
    "com.openggf.tests.trace.TestTraceDataAuxSchemaPerformance",
    "com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance",
    "com.openggf.tests.trace.runs.TestTraceRunDescriptorPlanningPerformance",
}
expected_policy_reports = set()
expected_trace_reports = set()
for source in sorted(source_root.rglob("Test*.java")):
    if is_diagnostic_trace_source(source):
        continue
    if is_release_profile_excluded_source(source):
        continue
    text = source.read_text()
    class_header = re.split(r"\b(?:public\s+)?(?:final\s+)?class\s+", text, maxsplit=1)[0]
    if re.search(r'@Tag\("(?:trace-scope-r7|performance-measurement)"\)', class_header):
        continue
    rel = source.relative_to(Path("src/test/java")).with_suffix("")
    class_name = ".".join(rel.parts)
    report_name = f"TEST-{class_name}.xml"
    if class_name in OPTIONAL_MEASUREMENT_CLASSES:
        continue
    expected_policy_reports.add(report_name)
    if source.name.endswith("TraceReplay.java"):
        expected_trace_reports.add(report_name)

missing_policy = [
    report_name for report_name in sorted(expected_policy_reports)
    if not (surefire_dir / report_name).exists()
]
if missing_policy:
    print("Missing expected trace policy reports:", file=sys.stderr)
    for report_name in missing_policy:
        print(f"{surefire_dir / report_name}", file=sys.stderr)
    sys.exit(1)

missing_expected = [
    report_name for report_name in sorted(expected_trace_reports)
    if not (surefire_dir / report_name).exists()
]
if missing_expected:
    print("Missing expected trace replay reports:", file=sys.stderr)
    for report_name in missing_expected:
        print(f"{surefire_dir / report_name}", file=sys.stderr)
    sys.exit(1)

expected_policy_executed = 0
expected_executed = 0
for report_name in sorted(expected_policy_reports):
    report = surefire_dir / report_name
    if not report.exists():
        continue
    family = [report, *surefire_dir.glob(report.stem + "$*.xml")]
    roots = [ET.parse(member).getroot() for member in family]
    tests = sum(int(root.attrib.get("tests", "0")) for root in roots)
    skipped_count = sum(int(root.attrib.get("skipped", "0")) for root in roots)
    executed_count = tests - skipped_count
    if executed_count <= 0:
        if report_name in expected_trace_reports:
            print(
                f"Expected trace replay report did not execute: {report} "
                f"tests={tests} skipped={skipped_count}",
                file=sys.stderr)
        else:
            print(
                f"Expected trace policy report did not execute: {report} "
                f"tests={tests} skipped={skipped_count}",
                file=sys.stderr)
        sys.exit(1)
    expected_policy_executed += executed_count
    if report_name in expected_trace_reports:
        expected_executed += executed_count

reports = sorted(surefire_dir.glob("com.openggf.tests.trace*TraceReplay.txt"))
total = 0
skipped = 0
skipped_reports = []
for report in reports:
    text = report.read_text(encoding="utf-8", errors="replace")
    match = re.search(r"Tests run: (\d+), Failures: \d+, Errors: \d+, Skipped: (\d+)", text)
    if match:
        run_count = int(match.group(1))
        skipped_count = int(match.group(2))
        total += run_count
        skipped += skipped_count
        if skipped_count > 0:
            skipped_reports.append((report, skipped_count))

executed = total - skipped
if executed == 0:
    print("Trace replay profile produced no executed ROM-backed trace tests", file=sys.stderr)
    print(f"trace_reports={len(reports)} total={total} skipped={skipped}", file=sys.stderr)
    sys.exit(1)

unexpected_skipped = [
    (report, skipped_count)
    for report, skipped_count in skipped_reports
]
if unexpected_skipped:
    print("Trace replay skipped tests are release-blocking",
          file=sys.stderr)
    for report, skipped_count in unexpected_skipped:
        print(f"{report}: skipped={skipped_count}", file=sys.stderr)
    sys.exit(1)

print(
    f"Trace replay coverage: executed={executed} total={total} skipped={skipped} "
    f"expected_trace_executed={expected_executed} "
    f"expected_trace_reports={len(expected_trace_reports)} "
    f"expected_policy_executed={expected_policy_executed} "
    f"expected_policy_reports={len(expected_policy_reports)}")
