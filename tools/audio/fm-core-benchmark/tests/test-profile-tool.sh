#!/usr/bin/env bash
set -euo pipefail

tool_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)

"$tool_root/profile-java.sh" --help | grep -q -- '--prepare-only'

test_root="$repo_root/target/fm-core-profile-tool-test"
if [[ -e "$test_root" ]]; then
  echo "test output already exists: $test_root" >&2
  exit 2
fi
"$tool_root/profile-java.sh" --prepare-only --output "$test_root" \
  --frames 32 --passes 2

python3 - "$test_root/provenance.json" <<'PY'
import json
import sys

result = json.load(open(sys.argv[1], encoding="utf-8"))
assert result["schema"] == "openggf.fm-core-java-profile.v1"
assert result["mode"] == "prepare-only"
assert result["profile_collected"] is False
assert result["timing_collected"] is False
assert result["publishable"] is False
assert result["java_major"] == 21
assert result["dimensions"] == {"frames_per_phase": 32, "passes": 2}
assert set(result["compiled_input_sha256"]) == {
    "NukedOpn2State.java", "NukedOpn2Tables.java", "NukedOpn2.java",
    "JavaNukedBenchmark.java", "JavaNukedProfile.java"
}
assert all(len(value) == 64 for value in result["compiled_input_sha256"].values())
PY

[[ ! -e "$test_root/profile.jfr" ]]

if "$tool_root/profile-java.sh" --prepare-only \
    --output "$repo_root/profile-outside-target" >/dev/null 2>&1; then
  echo 'profile-java accepted output outside target' >&2
  exit 1
fi
if "$tool_root/profile-java.sh" --prepare-only \
    --output "$repo_root/target/profile-invalid" --frames 0 >/dev/null 2>&1; then
  echo 'profile-java accepted zero frames' >&2
  exit 1
fi

echo 'fm-core Java profile tool tests: PASS'
