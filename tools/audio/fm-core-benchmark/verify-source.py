#!/usr/bin/env python3
"""Verify every file named by a two-column SHA-256 lock."""

import argparse
import hashlib
import re
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"verify-source: {message}")


parser = argparse.ArgumentParser()
parser.add_argument("--source", required=True, type=Path)
parser.add_argument("--lock", required=True, type=Path)
args = parser.parse_args()

source = args.source.resolve()
if not source.is_dir():
    fail(f"source is not a directory: {source}")

rows = []
for number, raw in enumerate(args.lock.read_text(encoding="utf-8").splitlines(), 1):
    if not raw or raw.startswith("#"):
        continue
    match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.+/-]+)", raw)
    if not match:
        fail(f"malformed lock row {number}")
    relative = Path(match.group(2))
    if relative.is_absolute() or ".." in relative.parts:
        fail(f"unsafe path on row {number}")
    rows.append((match.group(1), relative))
if not rows:
    fail("lock contains no files")

for expected, relative in rows:
    path = source / relative
    resolved = path.resolve()
    if source not in resolved.parents or not path.is_file() or path.is_symlink():
        fail(f"missing regular file: {relative}")
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != expected:
        fail(f"sha256 mismatch: {relative}")

print(f"verified {len(rows)} files in {source}")
