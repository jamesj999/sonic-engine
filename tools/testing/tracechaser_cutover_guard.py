#!/usr/bin/env python3
"""Enforce the reviewed TraceChaser cutover disposition without importing it."""

from __future__ import annotations

import csv
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "docs/architecture/validation/trace/2026-08-29-tracechaser-extraction-inventory.tsv"


def main() -> int:
    tracked = set(subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-files", "-z"], text=False
    ).decode().split("\0"))
    violations: list[str] = []
    with INVENTORY.open(newline="", encoding="utf-8") as source:
        rows = list(csv.DictReader(source, delimiter="\t"))
    for row in rows:
        path = row["old_path"]
        disposition = row["cutover_disposition"]
        if disposition in {"delete", "delete-after-task-5"} and path in tracked:
            violations.append(f"deleted implementation remains tracked: {path}")
        elif disposition == "forwarder":
            if path not in tracked:
                violations.append(f"reviewed forwarder is missing: {path}")
                continue
            data = (ROOT / path).read_text(encoding="utf-8", errors="replace")
            if "tracechaser" not in data.lower() or len(data.encode()) > 4096:
                violations.append(f"forwarder is not thin TraceChaser delegation: {path}")
    for violation in sorted(violations):
        print(violation, file=sys.stderr)
    return 1 if violations else 0


if __name__ == "__main__":
    raise SystemExit(main())
