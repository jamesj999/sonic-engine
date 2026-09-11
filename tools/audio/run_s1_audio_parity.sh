#!/usr/bin/env bash
# Deterministic two-run Sonic 1 driver parity capture (GHZ music or sound-test SFX).
set -uo pipefail

EXIT_MATCH=0
EXIT_USAGE=2
EXIT_MISMATCH=3
EXIT_TOOL_FAILURE=4

usage() {
	cat <<'EOF'
Usage: tools/audio/run_s1_audio_parity.sh [options]

Options:
  --mode music|sfx|gameplay
                       capture pair to run (default: music — the pinned GHZ movie;
                       sfx runs the sound-test SFX movie and probe; gameplay runs
                       a bounded power-on-through-GHZ1 window of the pinned
                       complete-run movie sonic1-complete-withemeralds.bk2)
  --rom PATH           Sonic 1 World REV01 .gen (otherwise discover at repository root)
  --movie PATH         pinned sound-test BK2 fixture (default: the mode's committed BK2)
  --bizhawk-home PATH  BizHawk 2.11 Linux x64 installation (or BIZHAWK_HOME)
  --output-root PATH   run parent OUTSIDE the repository (required: TraceChaser's
                       output policy rejects reference captures inside either tree)
  -h, --help           show this help

Exit codes: 0=match, 2=usage, 3=mismatch, 4=capture/tool failure.
All four detailed captures and both reports remain in the printed unique run directory.
EOF
}

fail() {
	echo "S1 audio parity capture/tool failure: $*" >&2
	exit "$EXIT_TOOL_FAILURE"
}

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/s1_audio_parity_protocol.sh
source "$SCRIPT_DIR/lib/s1_audio_parity_protocol.sh"
REPO=$(cd "$SCRIPT_DIR/../.." && pwd)
TRACECHASER_BOOTSTRAP="$REPO/tools/tracechaser-bootstrap.sh"
ROM_PATH=""
MODE="music"
MOVIE_PATH=""
BIZHAWK_DIR="${BIZHAWK_HOME:-}"
ARTIFACT_ROOT="$REPO/target"
BUILD_ROOT="$REPO/target"
OUTPUT_ROOT=""
COMMON_GIT_DIR=$(git -C "$REPO" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)
MAIN_REPO=""
[ -n "$COMMON_GIT_DIR" ] && MAIN_REPO=$(dirname "$COMMON_GIT_DIR")

while [ "$#" -gt 0 ]; do
	case "$1" in
		--rom|--movie|--bizhawk-home|--output-root|--mode)
			[ "$#" -ge 2 ] || { echo "Argument error: $1 requires a value" >&2; usage >&2; exit "$EXIT_USAGE"; }
			case "$1" in
				--rom) ROM_PATH=$2 ;;
				--movie) MOVIE_PATH=$2 ;;
				--bizhawk-home) BIZHAWK_DIR=$2 ;;
				--output-root) OUTPUT_ROOT=$2 ;;
				--mode) MODE=$2 ;;
			esac
			shift 2 ;;
		-h|--help) usage; exit "$EXIT_MATCH" ;;
		*) echo "Argument error: unknown option: $1" >&2; usage >&2; exit "$EXIT_USAGE" ;;
	esac
done

[ -z "${OGGF_AUDIO_PARITY_JAVA_BIN:-}" ] || \
	fail "OGGF_AUDIO_PARITY_JAVA_BIN is unsupported; the trusted Java tool cannot be replaced"

case "$MODE" in
	music)
		PROBE="$REPO/tools/audio/probes/s1_audio_driver_parity_probe.lua"
		DEFAULT_MOVIE="$REPO/src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2" ;;
	sfx)
		PROBE="$REPO/tools/audio/probes/s1_audio_sfx_parity_probe.lua"
		DEFAULT_MOVIE="$REPO/src/test/resources/audio/parity/s1/s1-soundtest-sfx.bk2" ;;
	gameplay)
		PROBE="$REPO/tools/audio/probes/s1_gameplay_driver_parity_probe.lua"
		DEFAULT_MOVIE="$REPO/src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2" ;;
	*) echo "Argument error: --mode must be music, sfx, or gameplay" >&2; usage >&2; exit "$EXIT_USAGE" ;;
esac
[ -n "$MOVIE_PATH" ] || MOVIE_PATH=$DEFAULT_MOVIE
[ -f "$PROBE" ] || fail "consumer probe is missing: $PROBE"
if [ -z "$OUTPUT_ROOT" ]; then
	echo "Argument error: --output-root is required and must lie outside the repository" >&2
	usage >&2
	exit "$EXIT_USAGE"
fi
case "$(realpath -m "$OUTPUT_ROOT")" in
	"$REPO"|"$REPO"/*)
		fail "--output-root must lie outside the repository; TraceChaser's output policy rejects reference captures inside either source tree" ;;
esac
LAUNCHER=$("$TRACECHASER_BOOTSTRAP" --require "bizhawk/run_bizhawk_lua.sh") || exit $?

if [ -z "$BIZHAWK_DIR" ]; then
	for candidate in "$REPO/docs/BizHawk-2.11-linux-x64" \
		"$MAIN_REPO/docs/BizHawk-2.11-linux-x64"; do
		if [ -f "$candidate/EmuHawk.exe" ]; then
			BIZHAWK_DIR=$candidate
			break
		fi
	done
fi
[ -n "$BIZHAWK_DIR" ] || fail "BizHawk 2.11 home was not found; pass --bizhawk-home or set BIZHAWK_HOME"

CLASSPATH_FILE="$ARTIFACT_ROOT/s1-audio-parity.classpath"
cd "$REPO" || fail "cannot enter repository root"
if ! mvn -q -Pci -DskipTests compile dependency:build-classpath \
	-Dmdep.outputFile="$CLASSPATH_FILE"; then
	fail "Maven could not compile the parity tool or resolve its runtime classpath"
fi
[ -s "$CLASSPATH_FILE" ] || fail "Maven did not produce the parity tool classpath"
JAVA_CP="$BUILD_ROOT/classes:$(<"$CLASSPATH_FILE")"
JAVA_TOOL=(java -cp "$JAVA_CP" com.openggf.tools.audio.parity.S1AudioParityTool)

VALIDATE_ARGS=(validate --repo "$REPO" --movie "$MOVIE_PATH" --bizhawk-home "$BIZHAWK_DIR" \
	--output-root "$OUTPUT_ROOT" --capture "$MODE")
if [ -n "$ROM_PATH" ]; then
	VALIDATE_ARGS+=(--rom "$ROM_PATH")
else
	ROM_SEARCH_ROOT=$REPO
	[ -z "$MAIN_REPO" ] || ROM_SEARCH_ROOT=$MAIN_REPO
	VALIDATE_ARGS+=(--rom-search-root "$ROM_SEARCH_ROOT")
fi
VALIDATED=$("${JAVA_TOOL[@]}" "${VALIDATE_ARGS[@]}") || fail "input validation failed"
if ! s1_audio_parse_validation_records "$VALIDATED"; then
	fail "invalid Java validation response"
fi
ROM_PATH=$S1_AUDIO_VALIDATED_ROM_PATH
MOVIE_PATH=$S1_AUDIO_VALIDATED_MOVIE_PATH
BIZHAWK_DIR=$S1_AUDIO_VALIDATED_BIZHAWK_HOME
OUTPUT_ROOT=$S1_AUDIO_VALIDATED_OUTPUT_ROOT

mkdir -p -- "$OUTPUT_ROOT" || fail "cannot create safe output root: $OUTPUT_ROOT"
RUN_DIR=$(mktemp -d "$OUTPUT_ROOT/run.XXXXXXXX") || fail "cannot create a fresh run directory"
REFERENCE_1="$RUN_DIR/reference-1.jsonl"
REFERENCE_2="$RUN_DIR/reference-2.jsonl"
OPENGGF_1="$RUN_DIR/openggf-1.jsonl"
OPENGGF_2="$RUN_DIR/openggf-2.jsonl"
capture_reference() {
	local output=$1
	local log=$2
	if ! OGGF_OUT="$output" BIZHAWK_HOME="$BIZHAWK_DIR" \
		OGGF_INPUT_REPOSITORY_ROOT="$REPO" \
		OGGF_WORKDIR="$RUN_DIR/workdir" \
		"$LAUNCHER" "$PROBE" "$MOVIE_PATH" "$ROM_PATH" >"$log" 2>&1; then
		return 1
	fi
	# BizHawk's Lua host exits the client before a probe error can propagate, so
	# a failed probe looks like a clean exit 0. The probes append any hook
	# failure to this sidecar first; surface it instead of reporting a short
	# capture as a parity result.
	if [ -s "$output.error" ]; then
		echo "probe reported a hook failure:" >&2
		cat -- "$output.error" >&2
		return 1
	fi
	[ -s "$output" ]
}

capture_engine() {
	local reference=$1
	local output=$2
	"${JAVA_TOOL[@]}" capture --repo "$REPO" --run-root "$RUN_DIR" \
		--reference "$reference" --rom "$ROM_PATH" --output "$output" --capture "$MODE"
}

echo "Run directory: $RUN_DIR"
echo "Recording BizHawk reference capture 1/2..."
capture_reference "$REFERENCE_1" "$RUN_DIR/reference-1.log" || fail "BizHawk reference capture 1 failed; see $RUN_DIR/reference-1.log"
echo "Recording BizHawk reference capture 2/2..."
capture_reference "$REFERENCE_2" "$RUN_DIR/reference-2.log" || fail "BizHawk reference capture 2 failed; see $RUN_DIR/reference-2.log"
cmp -s -- "$REFERENCE_1" "$REFERENCE_2" || fail "normalized BizHawk captures differ byte-for-byte"

echo "Recording OpenGGF capture 1/2 for the reference terminal count..."
capture_engine "$REFERENCE_1" "$OPENGGF_1" || fail "OpenGGF capture 1 failed"
echo "Recording OpenGGF capture 2/2 for the reference terminal count..."
capture_engine "$REFERENCE_1" "$OPENGGF_2" || fail "OpenGGF capture 2 failed"
cmp -s -- "$OPENGGF_1" "$OPENGGF_2" || fail "normalized OpenGGF captures differ byte-for-byte"

HUMAN_REPORT="$RUN_DIR/parity-report.txt"
JSON_REPORT="$RUN_DIR/parity-report.json"
"${JAVA_TOOL[@]}" compare --repo "$REPO" --run-root "$RUN_DIR" \
	--reference "$REFERENCE_1" --openggf "$OPENGGF_1" \
	--human-report "$HUMAN_REPORT" --json-report "$JSON_REPORT"
RESULT=$?
echo "Detailed captures preserved: $RUN_DIR"
echo "Parity report: $HUMAN_REPORT"
echo "Machine summary: $JSON_REPORT"
case "$RESULT" in
	"$EXIT_MATCH"|"$EXIT_MISMATCH") exit "$RESULT" ;;
	*) fail "comparison could not validate both captures; see $HUMAN_REPORT" ;;
esac
