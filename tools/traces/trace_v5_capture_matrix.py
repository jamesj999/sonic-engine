#!/usr/bin/env python3
"""OpenGGF 0.6 forwarder to the pinned TraceChaser capture matrix."""
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
resolved = subprocess.run(
    [str(root / "tools/tracechaser-bootstrap.sh"), "--require",
     "traces/trace_v5_capture_matrix.py"], text=True, stdout=subprocess.PIPE)
if resolved.returncode:
    raise SystemExit(resolved.returncode)
os.execv(sys.executable, [sys.executable, resolved.stdout.strip(), *sys.argv[1:]])
