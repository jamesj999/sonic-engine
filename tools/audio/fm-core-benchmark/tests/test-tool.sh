#!/usr/bin/env bash
set -euo pipefail

tool_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
test_root="$repo_root/target/fm-core-benchmark-tests"
rm -rf -- "$test_root"
mkdir -p -- "$test_root/source" "$test_root/output"

"$tool_root/run.sh" --help | grep -q -- '--nuked-source'
"$tool_root/fetch-sources.sh" --help | grep -q -- '--output'

printf 'pinned input\n' > "$test_root/source/input.txt"
input_sha=$(sha256sum "$test_root/source/input.txt" | cut -d' ' -f1)
printf '%s  input.txt\n' "$input_sha" > "$test_root/source.lock"

python3 "$tool_root/verify-source.py" \
  --source "$test_root/source" --lock "$test_root/source.lock"

printf 'tampered\n' > "$test_root/source/input.txt"
if python3 "$tool_root/verify-source.py" \
    --source "$test_root/source" --lock "$test_root/source.lock" >/dev/null 2>&1; then
  echo 'verify-source accepted a hash mismatch' >&2
  exit 1
fi

printf 'not-a-lock-row\n' > "$test_root/malformed.lock"
if python3 "$tool_root/verify-source.py" \
    --source "$test_root/source" --lock "$test_root/malformed.lock" >/dev/null 2>&1; then
  echo 'verify-source accepted malformed lock data' >&2
  exit 1
fi
printf 'pinned input\n' > "$test_root/source/input.txt"

cat > "$test_root/java.json" <<'JSON'
{"implementation":"java-nuked","frames":128,"warmups":0,"iterations":1,"checksum":1234,"snapshot_errors":0,"negative_control_changes":19,"nanoseconds_per_frame":[10.0]}
JSON
cat > "$test_root/native.json" <<'JSON'
{"frames":128,"warmups":0,"iterations":1,"implementations":{"c-nuked":{"checksum":1234,"snapshot_errors":0,"negative_control_changes":17,"nanoseconds_per_frame":[8.0]},"cpp-ymfm":{"checksum":9876,"snapshot_errors":0,"negative_control_changes":16,"nanoseconds_per_frame":[3.0]}}}
JSON

expect_assemble_failure() {
  local java_input=$1 native_input=$2 label=$3
  if python3 "$tool_root/assemble-results.py" \
      --java "$java_input" --native "$native_input" \
      --output "$test_root/output/$label.json" --repo "$repo_root" \
      --frames 128 --warmups 0 --iterations 1 \
      --nuked-lock "$test_root/source.lock" --ymfm-lock "$test_root/source.lock" \
      --build-input fixture="$test_root/source/input.txt" \
      --native-c-flags=-O2 --native-cxx-flags='-O2 -std=c++14' >/dev/null 2>&1; then
    echo "assemble-results accepted $label" >&2
    exit 1
  fi
}

python3 "$tool_root/assemble-results.py" \
  --java "$test_root/java.json" --native "$test_root/native.json" \
  --output "$test_root/output/result.json" --repo "$repo_root" \
  --frames 128 --warmups 0 --iterations 1 \
  --nuked-lock "$test_root/source.lock" --ymfm-lock "$test_root/source.lock" \
  --build-input fixture="$test_root/source/input.txt" \
  --native-c-flags=-O2 --native-cxx-flags='-O2 -std=c++14'

python3 - "$test_root/output/result.json" <<'PY'
import json
import sys
result = json.load(open(sys.argv[1], encoding="utf-8"))
assert result["validation"] == {
    "java_c_nuked_checksum_match": True,
    "stream_checksum": "fnv1a64-le-signed-int32-stereo",
    "snapshot_replay": "sample-wise-exact-pass",
    "active_negative_controls": "pass",
}
assert result["measurement"]["publishable"] is False
assert len(result["source_pins"]["nuked"]["lock_sha256"]) == 64
assert len(result["source_pins"]["ymfm"]["lock_sha256"]) == 64
assert result["build"]["inputs"]["fixture"] == "569dcc66411eb4426c8032cd03aeb5b1128c1ad63024377ec80deee1cd215e08"
assert result["build"]["native_cxx_flags"] == "-O2 -std=c++14"
PY

python3 - "$test_root/native.json" <<'PY'
import json
import sys
path = sys.argv[1]
value = json.load(open(path, encoding="utf-8"))
value["implementations"]["c-nuked"]["checksum"] = 4321
json.dump(value, open(path, "w", encoding="utf-8"))
PY
if python3 "$tool_root/assemble-results.py" \
    --java "$test_root/java.json" --native "$test_root/native.json" \
    --output "$test_root/output/mismatch.json" --repo "$repo_root" \
    --frames 128 --warmups 0 --iterations 1 \
    --nuked-lock "$test_root/source.lock" --ymfm-lock "$test_root/source.lock" \
    --build-input fixture="$test_root/source/input.txt" \
    --native-c-flags=-O2 --native-cxx-flags='-O2 -std=c++14' >/dev/null 2>&1; then
  echo 'assemble-results accepted a Java/C checksum mismatch' >&2
  exit 1
fi

python3 - "$test_root/java.json" "$test_root/native.json" "$test_root" <<'PY'
import json
import sys
from pathlib import Path

java_path, native_path, root = map(Path, sys.argv[1:])
java = json.loads(java_path.read_text(encoding="utf-8"))
native = json.loads(native_path.read_text(encoding="utf-8"))
native["implementations"]["c-nuked"]["checksum"] = 1234
native["implementations"]["cpp-ymfm"]["negative_control_changes"] = 16

cases = {
    "wrong-java-id": ({**java, "implementation": "java-other"}, native),
    "unexpected-native-id": (java, {**native, "implementations": {
        **native["implementations"], "extra-core": native["implementations"]["cpp-ymfm"]}}),
    "boolean-checksum": ({**java, "checksum": True}, native),
    "missing-timings": ({key: value for key, value in java.items()
                         if key != "nanoseconds_per_frame"}, native),
    "short-timings": ({**java, "nanoseconds_per_frame": []}, native),
    "nan-timing": ({**java, "nanoseconds_per_frame": [float("nan")]}, native),
    "boolean-timing": ({**java, "nanoseconds_per_frame": [True]}, native),
    "negative-timing": ({**java, "nanoseconds_per_frame": [-1.0]}, native),
    "wrong-dimensions": ({**java, "frames": 127}, native),
    "native-boolean-errors": (java, {**native, "implementations": {
        **native["implementations"], "c-nuked": {
            **native["implementations"]["c-nuked"], "snapshot_errors": False}}}),
    "native-missing-timings": (java, {**native, "implementations": {
        **native["implementations"], "cpp-ymfm": {
            key: value for key, value in native["implementations"]["cpp-ymfm"].items()
            if key != "nanoseconds_per_frame"}}}),
    "native-wrong-dimensions": (java, {**native, "warmups": 1}),
}
for label, (java_case, native_case) in cases.items():
    (root / f"{label}-java.json").write_text(json.dumps(java_case), encoding="utf-8")
    (root / f"{label}-native.json").write_text(json.dumps(native_case), encoding="utf-8")
PY

for label in wrong-java-id unexpected-native-id boolean-checksum missing-timings \
    short-timings nan-timing boolean-timing negative-timing wrong-dimensions \
    native-boolean-errors native-missing-timings native-wrong-dimensions; do
  expect_assemble_failure "$test_root/$label-java.json" "$test_root/$label-native.json" "$label"
done

python3 - "$test_root/native.json" <<'PY'
import json
import sys
path = sys.argv[1]
value = json.load(open(path, encoding="utf-8"))
value["implementations"]["c-nuked"]["checksum"] = 1234
value["implementations"]["cpp-ymfm"]["negative_control_changes"] = 0
json.dump(value, open(path, "w", encoding="utf-8"))
PY
if python3 "$tool_root/assemble-results.py" \
    --java "$test_root/java.json" --native "$test_root/native.json" \
    --output "$test_root/output/inert.json" --repo "$repo_root" \
    --frames 128 --warmups 0 --iterations 1 \
    --nuked-lock "$test_root/source.lock" --ymfm-lock "$test_root/source.lock" \
    --build-input fixture="$test_root/source/input.txt" \
    --native-c-flags=-O2 --native-cxx-flags='-O2 -std=c++14' >/dev/null 2>&1; then
  echo 'assemble-results accepted an inert negative control' >&2
  exit 1
fi

outside=$(mktemp -d)
trap 'rm -rf -- "$outside"' EXIT
if "$tool_root/run.sh" --output "$outside/result" \
    --nuked-source "$test_root/source" --ymfm-source "$test_root/source" \
    --frames 8 --warmups 0 --iterations 1 >/dev/null 2>&1; then
  echo 'run.sh accepted output outside the worktree target directory' >&2
  exit 1
fi

ln -s "$outside" "$test_root/escape-link"
if "$tool_root/run.sh" --output "$test_root/escape-link/nested/created-outside" \
    --nuked-source "$test_root/source" --ymfm-source "$test_root/source" \
    --frames 8 --warmups 0 --iterations 1 >/dev/null 2>&1; then
  echo 'run.sh accepted a target symlink escape' >&2
  exit 1
fi
if [[ -e "$outside/nested" ]]; then
  echo 'run.sh created an output directory before rejecting a symlink escape' >&2
  exit 1
fi
if "$tool_root/fetch-sources.sh" \
    --output "$test_root/escape-link/fetch-nested/sources" >/dev/null 2>&1; then
  echo 'fetch-sources accepted a target symlink escape' >&2
  exit 1
fi
if [[ -e "$outside/fetch-nested" ]]; then
  echo 'fetch-sources created a directory before rejecting a symlink escape' >&2
  exit 1
fi

echo 'fm-core-benchmark boundary tests: PASS'
