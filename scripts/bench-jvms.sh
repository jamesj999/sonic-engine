#!/usr/bin/env bash
#
# Runs the OpenGGF trace benchmark across several JVMs and renders a comparison.
#
# The measurement discipline this script exists to enforce:
#
#   * Interleaving. Rounds run A,B,C then A,B,C rather than all of A then all of
#     B. CPU frequency and thermals drift over minutes, and running one runtime's
#     repeats back to back charges that drift entirely to whichever runtime went
#     last. Each round is written out separately so the round-to-round spread is
#     visible in the comparison instead of being averaged into invisibility.
#   * Core pinning. Every run gets the same cores via taskset when available, so
#     a scheduler decision does not become a JVM result.
#   * One shared jar. All runtimes execute identical bytecode; the jar is built
#     once, up front, by whichever JVM Maven happens to use.
#
# Usage:
#   scripts/bench-jvms.sh --trace aiz1 \
#       --jvm 'temurin21-g1|/usr/lib/jvm/temurin-21|-XX:+UseG1GC' \
#       --jvm 'temurin21-zgc|/usr/lib/jvm/temurin-21|-XX:+UseZGC -XX:+ZGenerational' \
#       --jvm 'graal21|/usr/lib/jvm/graalvm-21|'
#
# Options:
#   --trace <spec>        trace id, directory name, or path (required)
#   --jvm 'label|home|flags'  repeatable; JAVA_HOME and extra flags per runtime
#   --mode update|full    benchmark mode (default: update)
#   --rounds N            interleaved rounds per runtime (default: 3)
#   --iterations N        in-process iterations per run (default: 3)
#   --warmup-frames N     frames driven before measuring (default: 2000)
#   --measure-frames N    frames measured (default: 10000)
#   --out-dir <dir>       report destination (default: target/bench)
#   --report <file>       comparison markdown (default: <out-dir>/comparison.md)
#   --cpus <list>         taskset CPU list (default: 2,3 when taskset exists)
#   --skip-build          reuse the existing jar

set -euo pipefail

TRACE=""
MODE="update"
ROUNDS=3
ITERATIONS=3
WARMUP_FRAMES=2000
MEASURE_FRAMES=10000
OUT_DIR="target/bench"
REPORT=""
CPUS="2,3"
SKIP_BUILD=0
JVM_SPECS=()

die() { echo "error: $*" >&2; exit 1; }

while [[ $# -gt 0 ]]; do
    case "$1" in
        --trace)          TRACE="$2"; shift 2 ;;
        --jvm)            JVM_SPECS+=("$2"); shift 2 ;;
        --mode)           MODE="$2"; shift 2 ;;
        --rounds)         ROUNDS="$2"; shift 2 ;;
        --iterations)     ITERATIONS="$2"; shift 2 ;;
        --warmup-frames)  WARMUP_FRAMES="$2"; shift 2 ;;
        --measure-frames) MEASURE_FRAMES="$2"; shift 2 ;;
        --out-dir)        OUT_DIR="$2"; shift 2 ;;
        --report)         REPORT="$2"; shift 2 ;;
        --cpus)           CPUS="$2"; shift 2 ;;
        --skip-build)     SKIP_BUILD=1; shift ;;
        -h|--help)        sed -n '2,40p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                die "unknown argument: $1" ;;
    esac
done

[[ -n "$TRACE" ]] || die "--trace is required"
[[ ${#JVM_SPECS[@]} -gt 0 ]] || die "at least one --jvm 'label|java_home|flags' is required"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"
[[ -n "$REPORT" ]] || REPORT="$OUT_DIR/comparison.md"

# --- one jar, shared by every runtime ---------------------------------------
if [[ $SKIP_BUILD -eq 0 ]]; then
    echo "== building jar =="
    mvn -q package -DskipTests
fi

shopt -s nullglob
JARS=(target/OpenGGF-*-jar-with-dependencies.jar)
shopt -u nullglob
[[ ${#JARS[@]} -eq 1 ]] || die "expected exactly one jar-with-dependencies in target/, found ${#JARS[@]}"
JAR="${JARS[0]}"
echo "jar: $JAR"

# --- core pinning ------------------------------------------------------------
PIN=()
if command -v taskset >/dev/null 2>&1; then
    PIN=(taskset -c "$CPUS")
    echo "pinning to CPUs $CPUS"
else
    echo "note: taskset not found; runs are unpinned and will be noisier"
fi

mkdir -p "$OUT_DIR"
REPORT_FILES=()

# --- validate every runtime before spending an hour on the matrix ------------
for spec in "${JVM_SPECS[@]}"; do
    IFS='|' read -r label home _flags <<< "$spec"
    [[ -n "$label" ]] || die "--jvm spec has no label: $spec"
    [[ -x "$home/bin/java" ]] || die "no executable java under '$home' (from --jvm $label)"
done

# --- interleaved rounds ------------------------------------------------------
for (( round = 1; round <= ROUNDS; round++ )); do
    echo
    echo "===== round $round / $ROUNDS ====="
    for spec in "${JVM_SPECS[@]}"; do
        IFS='|' read -r label home flags <<< "$spec"
        json="$OUT_DIR/${label}-r${round}.json"

        echo
        echo "-- $label (round $round) --"
        # shellcheck disable=SC2086
        "${PIN[@]}" "$home/bin/java" $flags \
            --add-exports java.base/java.lang=ALL-UNNAMED \
            --add-exports java.desktop/sun.awt=ALL-UNNAMED \
            --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
            -cp "$JAR" com.openggf.tools.TraceBenchmarkTool \
            --trace "$TRACE" \
            --mode "$MODE" \
            --warmup-frames "$WARMUP_FRAMES" \
            --measure-frames "$MEASURE_FRAMES" \
            --iterations "$ITERATIONS" \
            --label "$label r$round" \
            --json "$json"

        REPORT_FILES+=("$json")
    done
done

# --- comparison --------------------------------------------------------------
echo
echo "===== comparison ====="
java -cp "$JAR" com.openggf.tools.BenchmarkCompareTool --out "$REPORT" "${REPORT_FILES[@]}"
echo
echo "Reports:   $OUT_DIR"
echo "Comparison: $REPORT"
echo
echo "Before quoting these numbers, check the determinism table in the report:"
echo "if the trajectory digests differ, the runtimes did not simulate the same"
echo "work and the timings are not comparable."
