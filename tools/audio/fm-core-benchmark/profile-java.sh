#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'fm-core-java-profile: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'EOF'
Usage: profile-java.sh (--prepare-only | --record) \
  --output ABSOLUTE_TARGET_PATH [--frames N] [--passes N] [--cpu N]

Compiles a deterministic sustain/release/DAC driver for the production Java
Nuked core. --record collects a JDK Flight Recorder profile and hot-method
view, but no wall-clock timings. --prepare-only validates and compiles without
starting the workload; use it for tool smoke tests or while the host is busy.
Output must be a new directory below the invoking worktree's target/.
EOF
}

tool_root=$(cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
mode=
output=
frames=100000
passes=20
cpu=
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --prepare-only) [[ -z "$mode" ]] || fail 'choose exactly one mode'; mode=prepare-only; shift ;;
    --record) [[ -z "$mode" ]] || fail 'choose exactly one mode'; mode=record; shift ;;
    --output) output=${2-}; shift 2 ;;
    --frames) frames=${2-}; shift 2 ;;
    --passes) passes=${2-}; shift 2 ;;
    --cpu) cpu=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$mode" ]] || fail 'choose --prepare-only or --record'
[[ -n "$output" && "$output" = /* ]] || fail '--output must be an absolute path'
[[ "$frames" =~ ^[1-9][0-9]*$ ]] || fail '--frames must be positive'
[[ "$passes" =~ ^[1-9][0-9]*$ ]] || fail '--passes must be positive'
if [[ -n "$cpu" ]]; then
  [[ "$cpu" =~ ^[0-9]+$ ]] || fail '--cpu must be a non-negative integer'
  command -v taskset >/dev/null || fail '--cpu requires taskset'
fi

resolved=$(python3 - "$repo_root" "$output" <<'PY'
import sys
from pathlib import Path
target = (Path(sys.argv[1]) / "target").resolve()
candidate = Path(sys.argv[2]).resolve(strict=False)
if target not in candidate.parents:
    raise SystemExit(2)
print(candidate)
PY
) || fail "output must resolve below $repo_root/target"
[[ ! -e "$resolved" && ! -L "$resolved" ]] || fail "output already exists: $resolved"

command -v javac >/dev/null || fail 'javac not found'
command -v java >/dev/null || fail 'java not found'
[[ $(javac -version 2>&1) = javac\ 21.* ]] || fail 'JDK 21 is required'
java_major=$(java -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java.version = \([0-9][0-9]*\).*/\1/p' | head -n1)
[[ "$java_major" = 21 ]] || fail 'the Java runtime must be JDK 21'
if [[ "$mode" = record ]]; then
  command -v jfr >/dev/null || fail 'JDK jfr tool not found'
fi

mkdir -p -- "${resolved%/*}"
mkdir -- "$resolved"
mkdir -- "$resolved/classes"
inputs=(
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2State.java"
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2Tables.java"
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2.java"
  "$tool_root/JavaNukedBenchmark.java"
  "$tool_root/JavaNukedProfile.java"
)
javac -d "$resolved/classes" "${inputs[@]}"

runner=()
affinity=none
if [[ -n "$cpu" ]]; then
  runner=(taskset -c "$cpu")
  affinity="cpu:$cpu"
fi
if [[ "$mode" = record ]]; then
  mkdir -- "$resolved/jfr-tmp"
  "${runner[@]}" java \
    -Djava.io.tmpdir="$resolved/jfr-tmp" \
    -XX:StartFlightRecording="filename=$resolved/profile.jfr,settings=profile,dumponexit=true" \
    -cp "$resolved/classes" com.openggf.tools.audio.benchmark.JavaNukedProfile \
    "$frames" "$passes" > "$resolved/checksum.txt"
  jfr view hot-methods "$resolved/profile.jfr" > "$resolved/hot-methods.txt"
fi

python3 - "$resolved/provenance.json" "$mode" "$frames" "$passes" \
    "$affinity" "$java_major" "${inputs[@]}" <<'PY'
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

output, mode, frames, passes, affinity, java_major, *inputs = sys.argv[1:]
document = {
    "schema": "openggf.fm-core-java-profile.v1",
    "mode": mode,
    "profile_collected": mode == "record",
    "timing_collected": False,
    "publishable": False,
    "java_major": int(java_major),
    "java_version": subprocess.run(["java", "-version"], capture_output=True,
                                   text=True, check=True).stderr.splitlines()[0],
    "host": {"platform": platform.platform(), "machine": platform.machine()},
    "affinity": affinity,
    "dimensions": {"frames_per_phase": int(frames), "passes": int(passes)},
    "workloads": ["six-channel-sustain", "channel-zero-release", "dac-write-stream"],
    "compiled_input_sha256": {
        Path(path).name: hashlib.sha256(Path(path).read_bytes()).hexdigest()
        for path in inputs
    },
}
Path(output).write_text(json.dumps(document, indent=2, sort_keys=True) + "\n",
                        encoding="utf-8")
PY

printf '%s\n' "$resolved/provenance.json"
