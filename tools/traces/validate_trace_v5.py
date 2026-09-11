#!/usr/bin/env python3
"""OpenGGF 0.6 forwarder to the pinned TraceChaser v5 validator."""
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[2]
resolved = subprocess.run(
    [str(root / "tools/tracechaser-bootstrap.sh"), "--require",
     "traces/validate_trace_v5.py"], text=True, stdout=subprocess.PIPE)
if resolved.returncode:
    raise SystemExit(resolved.returncode)
os.execv(sys.executable, [sys.executable, resolved.stdout.strip(), *sys.argv[1:]])
