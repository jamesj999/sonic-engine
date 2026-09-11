#!/usr/bin/env python3
"""Validate benchmark controls and write one machine-readable evidence record."""

import argparse
import hashlib
import json
import math
import os
import platform
import subprocess
from datetime import datetime, timezone
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"assemble-results: {message}")


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        fail(f"invalid JSON in {path}: {error}")
    if not isinstance(value, dict):
        fail(f"expected object in {path}")
    return value


def command(*parts: str) -> str:
    try:
        process = subprocess.run(parts, check=True, text=True, capture_output=True)
        return (process.stdout or process.stderr).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unavailable"


parser = argparse.ArgumentParser()
parser.add_argument("--java", required=True, type=Path)
parser.add_argument("--native", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--repo", required=True, type=Path)
parser.add_argument("--frames", required=True, type=int)
parser.add_argument("--warmups", required=True, type=int)
parser.add_argument("--iterations", required=True, type=int)
parser.add_argument("--affinity", default="none")
parser.add_argument("--nuked-lock", required=True, type=Path)
parser.add_argument("--ymfm-lock", required=True, type=Path)
parser.add_argument("--build-input", action="append", default=[])
parser.add_argument("--native-c-flags", required=True)
parser.add_argument("--native-cxx-flags", required=True)
args = parser.parse_args()

java = read_json(args.java)
native = read_json(args.native)


def strict_int(value: object, name: str, *, minimum: int = 0) -> int:
    if type(value) is not int or value < minimum:
        fail(f"{name} must be an integer >= {minimum}")
    return value


def validate_dimensions(value: dict, name: str) -> None:
    expected = {
        "frames": args.frames,
        "warmups": args.warmups,
        "iterations": args.iterations,
    }
    for field, expected_value in expected.items():
        actual = strict_int(value.get(field), f"{name}.{field}")
        if actual != expected_value:
            fail(f"{name}.{field} does not match command line")


def validate_record(value: object, name: str, expected_id: str | None = None) -> dict:
    if not isinstance(value, dict):
        fail(f"{name} must be an object")
    if expected_id is not None and value.get("implementation") != expected_id:
        fail(f"{name}.implementation must be {expected_id}")
    checksum = strict_int(value.get("checksum"), f"{name}.checksum")
    if checksum > 0xffffffffffffffff:
        fail(f"{name}.checksum exceeds unsigned 64-bit range")
    strict_int(value.get("snapshot_errors"), f"{name}.snapshot_errors")
    strict_int(value.get("negative_control_changes"), f"{name}.negative_control_changes")
    timings = value.get("nanoseconds_per_frame")
    if not isinstance(timings, list) or len(timings) != args.iterations:
        fail(f"{name}.nanoseconds_per_frame must contain exactly {args.iterations} values")
    for timing in timings:
        if (isinstance(timing, bool) or not isinstance(timing, (int, float))
                or not math.isfinite(timing) or timing < 0):
            fail(f"{name}.nanoseconds_per_frame values must be finite and nonnegative")
    return value


try:
    implementations = native["implementations"]
    if not isinstance(implementations, dict):
        fail("native.implementations must be an object")
    if set(implementations) != {"c-nuked", "cpp-ymfm"}:
        fail("native implementations must be exactly c-nuked and cpp-ymfm")
    if args.frames <= 0 or args.warmups < 0 or args.iterations <= 0:
        fail("invalid measurement dimensions")
    validate_dimensions(java, "java")
    validate_dimensions(native, "native")
    java = validate_record(java, "java", "java-nuked")
    c_nuked = validate_record(implementations["c-nuked"], "c-nuked")
    ymfm = validate_record(implementations["cpp-ymfm"], "cpp-ymfm")
    records = [java, c_nuked, ymfm]
    checksum_match = java["checksum"] == c_nuked["checksum"]
    snapshots_pass = all(item["snapshot_errors"] == 0 for item in records)
    controls_pass = all(item["negative_control_changes"] > 0 for item in records)
except (KeyError, TypeError) as error:
    fail(f"missing result field: {error}")
if not checksum_match:
    fail("Java and C Nuked checksums differ")
if not snapshots_pass:
    fail("snapshot replay failed")
if not controls_pass:
    fail("negative control was inert")
repo = args.repo.resolve()
output = args.output.resolve()
target = repo / "target"
if target not in output.parents:
    fail("output must be below the invoking worktree's target directory")
output.parent.mkdir(parents=True, exist_ok=True)

head = command("git", "-C", str(repo), "rev-parse", "HEAD")
status = command("git", "-C", str(repo), "status", "--porcelain")
build_inputs = {}
for item in args.build_input:
    if "=" not in item:
        fail(f"malformed build input: {item}")
    label, raw_path = item.split("=", 1)
    path = Path(raw_path)
    if not label or label in build_inputs or not path.is_file() or path.is_symlink():
        fail(f"invalid build input: {item}")
    build_inputs[label] = hashlib.sha256(path.read_bytes()).hexdigest()
if not build_inputs:
    fail("at least one build input is required")
record = {
    "schema": "openggf.fm-core-benchmark.v1",
    "created_utc": datetime.now(timezone.utc).isoformat(),
    "repository": {"head": head, "tracked_tree_clean": status == ""},
    "environment": {
        "platform": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unreported",
        "java": command("java", "-version"),
        "javac": command("javac", "-version"),
        "c_compiler": command(os.environ.get("CC", "cc"), "--version").splitlines()[0],
        "cxx_compiler": command(os.environ.get("CXX", "c++"), "--version").splitlines()[0],
        "python": platform.python_version(),
        "affinity": args.affinity,
    },
    "measurement": {
        "frames_per_iteration": args.frames,
        "warmups": args.warmups,
        "iterations": args.iterations,
        "publishable": False,
        "note": "Local diagnostic only; host reservation and publication review are external requirements.",
    },
    "build": {
        "inputs": build_inputs,
        "java_flags": "javac defaults for JDK 21",
        "native_c_flags": args.native_c_flags,
        "native_cxx_flags": args.native_cxx_flags,
    },
    "source_pins": {
        "nuked": {
            "commit": "335747d78cb0abbc3b55b004e62dad9763140115",
            "tree": "6637a500d1da3b08cbc0cec1532ab305197b8978",
            "license": "LGPL-2.1-or-later",
            "lock_sha256": hashlib.sha256(args.nuked_lock.read_bytes()).hexdigest(),
        },
        "ymfm": {
            "commit": "81aec25ccbb98f4873a255f7551ac4dadac59b4a",
            "tree": "03f76ed27b1281357c91005e99d043eebd5119c1",
            "license": "BSD-3-Clause",
            "lock_sha256": hashlib.sha256(args.ymfm_lock.read_bytes()).hexdigest(),
        },
    },
    "validation": {
        "java_c_nuked_checksum_match": True,
        "stream_checksum": "fnv1a64-le-signed-int32-stereo",
        "snapshot_replay": "sample-wise-exact-pass",
        "active_negative_controls": "pass",
    },
    "java": java,
    "native": native,
}
output.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(output)
