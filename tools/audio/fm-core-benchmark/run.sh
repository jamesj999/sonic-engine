#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'fm-core-benchmark: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'EOF'
Usage: run.sh --output ABSOLUTE_TARGET_PATH \
  --nuked-source DIR --ymfm-source DIR [options]

Options:
  --frames N       Native FM frames per iteration (default: 53267)
  --warmups N      Untimed warm-up iterations (default: 3)
  --iterations N   Timed diagnostic iterations (default: 10)
  --cpu N          Optional Linux taskset CPU; no affinity by default
  --help            Show this help

Output must be a new directory below the invoking worktree's target/.
EOF
}
tool_root=$(cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
output=
nuked_source=
ymfm_source=
frames=53267
warmups=3
iterations=10
cpu=
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --output) output=${2-}; shift 2 ;;
    --nuked-source) nuked_source=${2-}; shift 2 ;;
    --ymfm-source) ymfm_source=${2-}; shift 2 ;;
    --frames) frames=${2-}; shift 2 ;;
    --warmups) warmups=${2-}; shift 2 ;;
    --iterations) iterations=${2-}; shift 2 ;;
    --cpu) cpu=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$output" && "$output" = /* ]] || fail '--output must be an absolute path'
[[ -n "$nuked_source" && -n "$ymfm_source" ]] || fail 'both source directories are required'
[[ "$frames" =~ ^[1-9][0-9]*$ ]] || fail '--frames must be positive'
[[ "$warmups" =~ ^[0-9]+$ ]] || fail '--warmups must be non-negative'
[[ "$iterations" =~ ^[1-9][0-9]*$ ]] || fail '--iterations must be positive'

mkdir -p -- "$repo_root/target"
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
mkdir -p -- "${resolved%/*}"
mkdir -- "$resolved"

nuked_source=$(cd -- "$nuked_source" && pwd -P)
ymfm_source=$(cd -- "$ymfm_source" && pwd -P)
python3 "$tool_root/verify-source.py" --source "$nuked_source" --lock "$tool_root/nuked.lock"
python3 "$tool_root/verify-source.py" --source "$ymfm_source" --lock "$tool_root/ymfm.lock"

cc=${CC:-cc}
cxx=${CXX:-c++}
command -v "$cc" >/dev/null || fail "C compiler not found: $cc"
command -v "$cxx" >/dev/null || fail "C++ compiler not found: $cxx"
command -v javac >/dev/null || fail 'javac not found'
command -v java >/dev/null || fail 'java not found'
[[ $(javac -version 2>&1) = javac\ 21.* ]] || fail 'JDK 21 is required'
java_major=$(java -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java.version = \([0-9][0-9]*\).*/\1/p' | head -n1)
[[ "$java_major" = 21 ]] || fail 'the Java runtime must be JDK 21'

"$cc" -O2 -fPIC -c "$nuked_source/ym3438.c" -o "$resolved/nuked.o"
"$cxx" -O2 -std=c++14 -I "$nuked_source" -I "$ymfm_source/src" \
  "$tool_root/native_benchmark.cpp" "$resolved/nuked.o" \
  "$ymfm_source/src/ymfm_opn.cpp" "$ymfm_source/src/ymfm_ssg.cpp" \
  "$ymfm_source/src/ymfm_adpcm.cpp" -o "$resolved/native-benchmark"

mkdir -- "$resolved/classes"
javac -d "$resolved/classes" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2State.java" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2Tables.java" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2.java" \
  "$tool_root/JavaNukedBenchmark.java"

runner=()
affinity=none
if [[ -n "$cpu" ]]; then
  command -v taskset >/dev/null || fail '--cpu requires taskset'
  [[ "$cpu" =~ ^[0-9]+$ ]] || fail '--cpu must be a non-negative integer'
  runner=(taskset -c "$cpu")
  affinity="cpu:$cpu"
fi
"${runner[@]}" java -cp "$resolved/classes" \
  com.openggf.tools.audio.benchmark.JavaNukedBenchmark \
  "$frames" "$warmups" "$iterations" > "$resolved/java.json"
"${runner[@]}" "$resolved/native-benchmark" \
  "$frames" "$warmups" "$iterations" > "$resolved/native.json"

python3 "$tool_root/assemble-results.py" \
  --java "$resolved/java.json" --native "$resolved/native.json" \
  --output "$resolved/result.json" --repo "$repo_root" \
  --frames "$frames" --warmups "$warmups" --iterations "$iterations" \
  --affinity "$affinity" --nuked-lock "$tool_root/nuked.lock" \
  --ymfm-lock "$tool_root/ymfm.lock" \
  --build-input "java-state=$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2State.java" \
  --build-input "java-tables=$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2Tables.java" \
  --build-input "java-core=$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2.java" \
  --build-input "java-harness=$tool_root/JavaNukedBenchmark.java" \
  --build-input "native-harness=$tool_root/native_benchmark.cpp" \
  --native-c-flags='-O2 -fPIC' --native-cxx-flags='-O2 -std=c++14'
