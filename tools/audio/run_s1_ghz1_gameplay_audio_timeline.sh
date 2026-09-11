#!/usr/bin/bash -p
# Deterministic two-producer capture for the pinned S1 GHZ1 gameplay-audio timeline.
set -euo pipefail

EXIT_MATCH=0
EXIT_USAGE=2
EXIT_MISMATCH=3
EXIT_TOOL_FAILURE=4

usage() {
	printf '%s\n' 'Usage: tools/audio/run_s1_ghz1_gameplay_audio_timeline.sh --rom PATH [--bizhawk-home PATH]' '' \
'Records two independent pinned BizHawk reference streams and two independent' \
'OpenGGF streams beneath target/audio-parity/s1-ghz1-gameplay' \
'probe writes only to a fresh staging file; the trusted Java boundary validates' \
'and atomically create-new publishes it. Existing captures and reports are never' \
'replaced.' '' \
'Trust boundary: the kernel, system dynamic loader, and parent launch environment' \
'through creation of this runner are trusted. Launch without LD_* loader injection.' \
'After startup the runner rejects LD_* variables and starts every child with env -i;' \
'it cannot prevent loader code or diagnostics that run before the script starts.' '' \
'Exit codes: 0=match, 2=usage, 3=parity mismatch, 4=capture/tool failure.'
}

fail() {
	echo "S1 GHZ1 gameplay-audio timeline capture/tool failure: $*" >&2
	exit "$EXIT_TOOL_FAILURE"
}

# A dynamically linked shell cannot sanitize its own pre-exec loader environment:
# the trusted system loader has already acted before the first script instruction.
# Detect every inherited loader control as soon as Bash starts, before parsing
# arguments or resolving any repository/tool path. Children receive a fresh env -i.
for loader_variable in "${!LD_@}"; do
	fail "unsupported loader environment variable: $loader_variable"
done

SCRIPT_DIR=$(cd "${BASH_SOURCE[0]%/*}" && pwd -P)
REPO=$(cd "$SCRIPT_DIR/../.." && pwd)
TRACECHASER_BOOTSTRAP="$REPO/tools/tracechaser-bootstrap.sh"
MOVIE="$REPO/src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/sonic1-complete-withemeralds.bk2"
RUN_PATH="$REPO/src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds"
ARTIFACT_ROOT="$REPO/target"
BUILD_ROOT="$REPO/target"
OUTPUT_ROOT="$REPO/target/audio-parity/s1-ghz1-gameplay"
ROM_PATH=""
BIZHAWK_DIR="${BIZHAWK_HOME:-}"

for replacement in OGGF_AUDIO_TIMELINE_JAVA_BIN OGGF_AUDIO_TIMELINE_MONO_BIN \
	OGGF_AUDIO_PARITY_JAVA_BIN MONO_BIN BIZHAWK_EXTRA_ARGS; do
	if [[ -v "$replacement" ]]; then
		fail "unsupported command replacement environment variable: $replacement"
	fi
done
for replacement in OGGF_BIZHAWK_PROBE_RUNTIME OGGF_BIZHAWK_LIB OGGF_WORKDIR OGGF_TRACE_OUTPUT_DIR \
	OGGF_NO_LUACONSOLE OGGF_BIZHAWK_SOFTGL BIZHAWK_ALLOW_SLOW_LUA JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS \
	_JAVA_OPTIONS MAVEN_OPTS MAVEN_ARGS BASH_ENV ENV MONO_ENV_OPTIONS MONO_PATH CLASSWORLDS_CONF; do
	if [[ -v "$replacement" ]]; then
		fail "unsupported producer/tool replacement environment variable: $replacement"
	fi
done
unset JAVA_HOME
PATH=/usr/bin:/bin
SAFE_ENV=(/usr/bin/env -i PATH=/usr/bin:/bin HOME="${HOME:-}" USER="${USER:-}" DISPLAY="${DISPLAY:-}" \
	XAUTHORITY="${XAUTHORITY:-}" XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-}" DBUS_SESSION_BUS_ADDRESS="${DBUS_SESSION_BUS_ADDRESS:-}")

while [ "$#" -gt 0 ]; do
	case "$1" in
		--rom|--bizhawk-home)
			[ "$#" -ge 2 ] || { echo "Argument error: $1 requires a value" >&2; usage >&2; exit "$EXIT_USAGE"; }
			case "$1" in
				--rom) ROM_PATH=$2 ;;
				--bizhawk-home) BIZHAWK_DIR=$2 ;;
			esac
			shift 2 ;;
		-h|--help) usage; exit "$EXIT_MATCH" ;;
		*) echo "Argument error: unknown option: $1" >&2; usage >&2; exit "$EXIT_USAGE" ;;
	esac
done

PROBE=$("$TRACECHASER_BOOTSTRAP" --require \
	"bizhawk/probes/s1_ghz1_gameplay_audio_timeline_probe.lua") || exit $?
LAUNCHER=$("$TRACECHASER_BOOTSTRAP" --require "bizhawk/run_bizhawk_lua.sh") || exit $?

[ -n "$ROM_PATH" ] || { echo "Argument error: --rom is required" >&2; usage >&2; exit "$EXIT_USAGE"; }
if [ -z "$BIZHAWK_DIR" ]; then
	for candidate in "$REPO/docs/BizHawk-2.11-linux-x64" "${REPO%/*}/OpenGGF/docs/BizHawk-2.11-linux-x64"; do
		if [ -f "$candidate/EmuHawk.exe" ]; then BIZHAWK_DIR=$candidate; break; fi
	done
fi
[ -n "$BIZHAWK_DIR" ] || fail "BizHawk 2.11 home was not found; pass --bizhawk-home"
MAVEN_BIN=/usr/bin/mvn
JAVA_BIN=/usr/bin/java
CMP_BIN=/usr/bin/cmp
MONO_SYSTEM_BIN=/usr/bin/mono
for trusted in "$MAVEN_BIN" "$JAVA_BIN" "$CMP_BIN" "$MONO_SYSTEM_BIN" /usr/bin/realpath; do [ -x "$trusted" ] || fail "trusted system executable is missing: $trusted"; done

CLASSPATH_FILE="$ARTIFACT_ROOT/s1-gameplay-audio-timeline.classpath"
if ! "${SAFE_ENV[@]}" "$MAVEN_BIN" -q -Dmse=off -Pci -DskipTests compile dependency:build-classpath \
	-Dmdep.outputFile="$CLASSPATH_FILE" -f "$REPO/pom.xml"; then
	fail "Maven could not compile the trusted timeline tool"
fi
[ -s "$CLASSPATH_FILE" ] || fail "Maven did not produce the timeline tool classpath"
JAVA_TOOL=("$JAVA_BIN" -cp "$BUILD_ROOT/classes:$(<"$CLASSPATH_FILE")" com.openggf.tools.audio.timeline.S1GameplayAudioTimelineTool)

VALIDATED=$("${SAFE_ENV[@]}" "${JAVA_TOOL[@]}" validate --repo "$REPO" --rom "$ROM_PATH" --movie "$MOVIE" \
	--bizhawk-home "$BIZHAWK_DIR" --output-root "$OUTPUT_ROOT") || fail "pinned input validation failed"
# The Java response is generated only after all pinned identities pass; split only fixed key=value records.
declare -A validated=()
while IFS='=' read -r key value; do
	case "$key" in ROM_PATH|MOVIE_PATH|BIZHAWK_HOME|OUTPUT_ROOT) ;; *) fail "invalid validation response" ;; esac
	[ -n "$value" ] || fail "empty validation response"
	[[ "$value" != *$'\001'* && "$value" != *$'\037'* && "$value" != *$'\177'* ]] || fail "control character in validation response"
	[[ -v "validated[$key]" ]] && fail "duplicate validation response"
	validated[$key]=$value
done <<< "$VALIDATED"
for key in ROM_PATH MOVIE_PATH BIZHAWK_HOME OUTPUT_ROOT; do [[ -v "validated[$key]" ]] || fail "missing validation response: $key"; done
ROM_PATH=${validated[ROM_PATH]}
MOVIE=${validated[MOVIE_PATH]}
BIZHAWK_DIR=${validated[BIZHAWK_HOME]}
OUTPUT_ROOT=${validated[OUTPUT_ROOT]}

/usr/bin/mkdir -p -- "$OUTPUT_ROOT" || fail "cannot create safe output root"
RUN_DIR=$(/usr/bin/mktemp -d "$OUTPUT_ROOT/run.XXXXXXXX") || fail "cannot create a unique run directory"
REFERENCE_1="$RUN_DIR/reference-1.jsonl"
REFERENCE_2="$RUN_DIR/reference-2.jsonl"
OPENGGF_1="$RUN_DIR/openggf-1.jsonl"
OPENGGF_2="$RUN_DIR/openggf-2.jsonl"
HUMAN_REPORT="$RUN_DIR/parity-report.txt"
JSON_REPORT="$RUN_DIR/parity-report.json"

capture_reference() {
	local output=$1 log=$2 staging
	staging=$(/usr/bin/mktemp "$RUN_DIR/reference.XXXXXXXX.staging") || return 1
	if ! "${SAFE_ENV[@]}" BIZHAWK_HOME="$BIZHAWK_DIR" MONO_BIN="$MONO_SYSTEM_BIN" OGGF_OUT="$staging" \
		OGGF_INPUT_REPOSITORY_ROOT="$REPO" "$LAUNCHER" "$PROBE" "$MOVIE" "$ROM_PATH" >"$log" 2>&1; then
		"${SAFE_ENV[@]}" "${JAVA_TOOL[@]}" discard-reference --repo "$REPO" --run-root "$RUN_DIR" --staging "$staging" >/dev/null 2>&1 || true
		return 1
	fi
	"${SAFE_ENV[@]}" "${JAVA_TOOL[@]}" publish-reference --repo "$REPO" --run-root "$RUN_DIR" --staging "$staging" --output "$output"
}

capture_openggf() {
	local output=$1 log=$2
	"${SAFE_ENV[@]}" "$MAVEN_BIN" -f "$REPO/pom.xml" -Dmse=off -Pci \
		-Dtest=com.openggf.tools.audio.timeline.TestS1Ghz1OpenGgfAudioTimelineCapture#captureRequestedOutput \
		-Ds1.audio.timeline.run.path="$RUN_PATH" -Ds1.audio.timeline.output="$output" \
		-Dsonic1.rom.path="$ROM_PATH" test >"$log" 2>&1
}

echo "Run directory: $RUN_DIR"
echo "Recording BizHawk reference capture 1/2..."
capture_reference "$REFERENCE_1" "$RUN_DIR/reference-1.log" || fail "BizHawk capture 1 failed; see $RUN_DIR/reference-1.log"
echo "Recording BizHawk reference capture 2/2..."
capture_reference "$REFERENCE_2" "$RUN_DIR/reference-2.log" || fail "BizHawk capture 2 failed; see $RUN_DIR/reference-2.log"
"$CMP_BIN" -s -- "$REFERENCE_1" "$REFERENCE_2" || fail "BizHawk captures differ byte-for-byte"

echo "Recording OpenGGF capture 1/2..."
capture_openggf "$OPENGGF_1" "$RUN_DIR/openggf-1.log" || fail "OpenGGF capture 1 failed; see $RUN_DIR/openggf-1.log"
echo "Recording OpenGGF capture 2/2..."
capture_openggf "$OPENGGF_2" "$RUN_DIR/openggf-2.log" || fail "OpenGGF capture 2 failed; see $RUN_DIR/openggf-2.log"
"$CMP_BIN" -s -- "$OPENGGF_1" "$OPENGGF_2" || fail "OpenGGF captures differ byte-for-byte"

set +e
"${SAFE_ENV[@]}" "${JAVA_TOOL[@]}" compare --repo "$REPO" --run-root "$RUN_DIR" --reference "$REFERENCE_1" --openggf "$OPENGGF_1" \
	--human-report "$HUMAN_REPORT" --json-report "$JSON_REPORT"
RESULT=$?
set -e
echo "Detailed captures, logs, and reports preserved: $RUN_DIR"
case "$RESULT" in
	"$EXIT_MATCH"|"$EXIT_MISMATCH") exit "$RESULT" ;;
	*) fail "comparison failed; see preserved logs in $RUN_DIR" ;;
esac
