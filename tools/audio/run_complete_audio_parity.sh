#!/usr/bin/bash -p
set -euo pipefail
export LC_ALL=C LANG=C

readonly script_dir="$(CDPATH= cd -- "$(/usr/bin/dirname -- "$0")" && /bin/pwd -P)"
readonly repo_root="$(CDPATH= cd -- "$script_dir/../.." && /bin/pwd -P)"
readonly tool_class='com.openggf.tools.audio.completerun.CompleteRunAudioTool'
readonly java_bin='/usr/bin/java'
readonly artifact_root="$repo_root/target"
readonly jar="$artifact_root/OpenGGF-0.6.prerelease-jar-with-dependencies.jar"

usage() {
  /usr/bin/printf '%s\n' 'usage: run_complete_audio_parity.sh --run-root ABS --profile ID --rom ABS --bk2 ABS --run-manifest ABS --reference-home ABS'
}

if [[ ${1-} == --help ]]; then usage; exit 0; fi

for name in BASH_ENV ENV JAVA_TOOL_OPTIONS MAVEN_OPTS JAVA_HOME CLASSPATH LD_PRELOAD LD_LIBRARY_PATH LD_AUDIT GIT_CONFIG GIT_CONFIG_GLOBAL GIT_CONFIG_SYSTEM SSH_ASKPASS; do
  if [[ -v $name ]]; then
    /usr/bin/printf 'rejected ambient variable: %s\n' "$name" >&2; exit 2
  fi
done
while IFS='=' read -r name _; do
  case "$name" in LD_*|GIT_*|OPENGGF_*_COMMAND|OPENGGF_*_TOOL) /usr/bin/printf 'rejected ambient variable: %s\n' "$name" >&2; exit 2;; esac
done < <(/usr/bin/env)

run_root= profile= rom= bk2= run_manifest= reference_home=
while (( $# )); do
  (( $# >= 2 )) || { usage >&2; exit 2; }
  case "$1" in
    --run-root) run_root=${2-}; shift 2;;
    --profile) profile=${2-}; shift 2;;
    --rom) rom=${2-}; shift 2;;
    --bk2) bk2=${2-}; shift 2;;
    --run-manifest) run_manifest=${2-}; shift 2;;
    --reference-home) reference_home=${2-}; shift 2;;
    *) usage >&2; exit 2;;
  esac
done

safe_text() { [[ -n $1 && $1 != *[[:cntrl:]]* ]]; }
safe_absolute() { safe_text "$1" && [[ $1 == /* && $1 != *//* && $1 != */../* && $1 != */./* && $1 != */.. && $1 != */. ]]; }
safe_absolute "$run_root" || { /usr/bin/printf '%s\n' 'run root must be a normalized absolute path' >&2; exit 2; }
safe_text "$profile" || { /usr/bin/printf '%s\n' 'profile contains invalid text' >&2; exit 2; }
for input in "$rom" "$bk2" "$run_manifest"; do
  safe_absolute "$input" && [[ -f $input && ! -L $input ]] || {
    /usr/bin/printf '%s\n' 'ROM, BK2, and run manifest must be absolute plain files' >&2; exit 2;
  }
  [[ $(/usr/bin/realpath -e -- "$input") == "$input" ]] || { /usr/bin/printf '%s\n' 'input path is not canonical' >&2; exit 2; }
done
safe_absolute "$reference_home" && [[ -d $reference_home && ! -L $reference_home ]] || {
  /usr/bin/printf '%s\n' 'reference home must be an absolute plain directory' >&2; exit 2;
}
[[ $(/usr/bin/realpath -e -- "$reference_home") == "$reference_home" ]] || {
  /usr/bin/printf '%s\n' 'reference home path is not canonical' >&2; exit 2;
}
if [[ -n $(/usr/bin/find "$reference_home" \( -type l -o -type f -links +1 -o \! -type d \! -type f \) -print -quit) ]]; then
  /usr/bin/printf '%s\n' 'reference home contains a linked or special entry' >&2; exit 2
fi
[[ -f $jar && ! -L $jar ]] || { /usr/bin/printf '%s\n' 'fixed OpenGGF tool jar is missing' >&2; exit 4; }

readonly allowed_root="$repo_root/target/audio-parity/runs"
ensure_plain_child_dir() {
  local parent=$1 name=$2 child="$1/$2"
  [[ -d $parent && ! -L $parent && $(CDPATH= cd -- "$parent" && /bin/pwd -P) == "$parent" ]] || {
    /usr/bin/printf '%s\n' 'canonical target has an untrusted parent' >&2; exit 2;
  }
  if [[ -e $child || -L $child ]]; then
    [[ -d $child && ! -L $child ]] || {
      /usr/bin/printf '%s\n' 'canonical target contains a linked or special entry' >&2; exit 2;
    }
  else
    /usr/bin/mkdir -- "$child"
  fi
  [[ -d $child && ! -L $child && $(CDPATH= cd -- "$child" && /bin/pwd -P) == "$child" ]] || {
    /usr/bin/printf '%s\n' 'canonical target has a redirected ancestor' >&2; exit 2;
  }
}
ensure_plain_child_dir "$repo_root" target
ensure_plain_child_dir "$repo_root/target" audio-parity
ensure_plain_child_dir "$repo_root/target/audio-parity" runs
readonly allowed_real="$(CDPATH= cd -- "$allowed_root" && /bin/pwd -P)"
[[ $allowed_real == "$allowed_root" ]] || {
  /usr/bin/printf '%s\n' 'canonical target has a redirected ancestor' >&2; exit 2;
}
readonly parent="$(/usr/bin/dirname -- "$run_root")"
[[ -d $parent ]] || { /usr/bin/printf '%s\n' 'run-root parent must already exist' >&2; exit 2; }
readonly parent_real="$(CDPATH= cd -- "$parent" && /bin/pwd -P)"
[[ $parent_real == "$allowed_real" && $(/usr/bin/basename -- "$run_root") != . && $(/usr/bin/basename -- "$run_root") != .. ]] || {
  /usr/bin/printf '%s\n' 'run root is outside the canonical target' >&2; exit 2;
}
[[ ! -e $run_root && ! -L $run_root ]] || { /usr/bin/printf '%s\n' 'run root already exists' >&2; exit 2; }
readonly run_stage="$(/usr/bin/mktemp -d "$allowed_real/.run-stage.XXXXXXXX")"
published=0
cleanup() {
  if (( ! published )) && [[ -d $run_stage && $run_stage == "$allowed_real"/.run-stage.* ]]; then
    /usr/bin/find "$run_stage" -depth -delete
  fi
}
trap cleanup EXIT
readonly empty_home="$run_stage/.home"
/usr/bin/mkdir -- "$empty_home"

run_reference_producer() {
  local output=$1
  run_tool produce REFERENCE "$profile" "$rom" "$bk2" "$run_manifest" \
    "$run_stage/reference-home" "$output"
}
run_engine_producer() {
  local output=$1
  run_tool produce OPENGGF "$profile" "$rom" "$bk2" "$run_manifest" - "$output"
}
run_tool() { /usr/bin/env -i PATH=/usr/bin:/bin LC_ALL=C LANG=C HOME="$empty_home" "$java_bin" -cp "$jar" "$tool_class" "$@"; }
run_capture() { local status=0; "$@" || status=$?; (( status == 0 )) && return 0; (( status == 2 )) && exit 2; exit 4; }
validate_capture() { local status=0; run_tool validate "$1" "$2" "$profile" || status=$?; (( status == 0 )) && return 0; (( status == 2 )) && exit 2; exit 4; }
tree_identity() {
  /usr/bin/find "$1" -printf '%y %m %n %P\0' | /usr/bin/sort -z
  /usr/bin/find "$1" -type f -print0 | /usr/bin/sort -z | /usr/bin/xargs -0 -r /usr/bin/sha256sum --zero
}

run_capture run_tool producer-status "$profile"
validate_home_status=0
run_tool verify-reference-home "$reference_home" "$profile" || validate_home_status=$?
(( validate_home_status == 0 )) || { (( validate_home_status == 2 )) && exit 2; exit 4; }
readonly reference_before="$(tree_identity "$reference_home" | /usr/bin/sha256sum)"
/usr/bin/cp -a -- "$reference_home" "$run_stage/reference-home"
validate_home_status=0
run_tool verify-reference-home "$run_stage/reference-home" "$profile" || validate_home_status=$?
(( validate_home_status == 0 )) || { (( validate_home_status == 2 )) && exit 2; exit 4; }
[[ $reference_before == "$(tree_identity "$run_stage/reference-home" | /usr/bin/sha256sum)" ]] || exit 4
run_capture run_reference_producer "$run_stage/reference-a"
run_capture run_reference_producer "$run_stage/reference-b"
run_capture run_engine_producer "$run_stage/engine-a"
run_capture run_engine_producer "$run_stage/engine-b"
[[ $reference_before == "$(tree_identity "$reference_home" | /usr/bin/sha256sum)" \
   && $reference_before == "$(tree_identity "$run_stage/reference-home" | /usr/bin/sha256sum)" ]] || exit 4
/usr/bin/diff -qr -- "$run_stage/reference-a" "$run_stage/reference-b" >/dev/null || exit 4
/usr/bin/diff -qr -- "$run_stage/engine-a" "$run_stage/engine-b" >/dev/null || exit 4
validate_capture "$run_stage/reference-a" REFERENCE
validate_capture "$run_stage/engine-a" OPENGGF

readonly report_stage="$run_stage/.report-stage"
/usr/bin/mkdir -- "$report_stage"
status=0
run_tool compare "$run_stage/reference-a" "$run_stage/engine-a" >"$report_stage/report.json" || status=$?
text_status=0
run_tool compare-text "$run_stage/reference-a" "$run_stage/engine-a" >"$report_stage/report.txt" || text_status=$?
[[ $text_status -eq $status ]] || exit 4
(( status == 0 || status == 3 )) || exit 4
/usr/bin/mv -T --no-copy --no-clobber -- "$report_stage" "$run_stage/report" || exit 4
/usr/bin/find "$run_stage/reference-home" -depth -delete
/usr/bin/rmdir -- "$empty_home"
/usr/bin/mv -T --no-copy --no-clobber -- "$run_stage" "$run_root" || exit 4
[[ ! -e $run_stage && ! -L $run_stage && -d $run_root && ! -L $run_root ]] || exit 4
published=1
exit "$status"
