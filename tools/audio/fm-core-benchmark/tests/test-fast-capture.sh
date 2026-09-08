#!/usr/bin/env bash
set -euo pipefail

tool_root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
ymfm_source=
while (($#)); do
  case "$1" in
    --ymfm-source) ymfm_source=${2-}; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -n "$ymfm_source" ]] || {
  echo 'usage: test-fast-capture.sh --ymfm-source DIR' >&2
  exit 2
}

"$tool_root/run-fast-capture.sh" --help | grep -q -- '--capture'
test_root="$repo_root/target/fm-core-fast-capture-test"
if [[ -e "$test_root" ]]; then
  echo "test output already exists: $test_root" >&2
  exit 2
fi
"$tool_root/run-fast-capture.sh" --output "$test_root" \
  --ymfm-source "$ymfm_source" \
  --capture "$tool_root/tests/fixtures/complete-ym-capture.jsonl"

python3 - "$test_root/result.json" <<'PY'
import json
import sys
result = json.load(open(sys.argv[1], encoding="utf-8"))
assert result["schema"] == "openggf.fm-core-fast-capture.v1"
assert result["implementation"] == "cpp-ymfm"
assert result["timing_collected"] is False
assert result["publishable"] is False
assert result["terminal_ym_cycle"] == 72
assert result["ym_events"] == 2
assert result["frames"] == 3
assert result["deterministic"] is True
assert result["snapshot_errors"] == 0
assert result["negative_control_changed_frames"] > 0
assert result["subframe_mapping"] == "writes-at-cycles-24n-through-24n+23-before-ymfm-frame-n"
assert result["fidelity_equivalent"] is False
assert result["presentation_pcm_reconstructable"] is False
assert result["compiler_flags"] == ["-O2", "-std=c++14"]
assert set(result["compiled_input_sha256"]) == {
    "FastYmfmCaptureProof.cpp", "ymfm_opn.cpp", "ymfm_opn.h",
    "ymfm_fm.h", "ymfm_fm.ipp", "ymfm_ssg.cpp", "ymfm_ssg.h",
    "ymfm_adpcm.cpp", "ymfm_adpcm.h", "ymfm.h"
}
PY

if "$tool_root/run-fast-capture.sh" --output "$repo_root/target/fast-incomplete" \
    --ymfm-source "$ymfm_source" \
    --capture "$tool_root/tests/fixtures/incomplete-ym-capture.jsonl" \
    >/dev/null 2>&1; then
  echo 'fast capture runner accepted an incomplete capture' >&2
  exit 1
fi
if "$tool_root/run-fast-capture.sh" --output "$repo_root/fast-outside-target" \
    --ymfm-source "$ymfm_source" \
    --capture "$tool_root/tests/fixtures/complete-ym-capture.jsonl" \
    >/dev/null 2>&1; then
  echo 'fast capture runner accepted output outside target' >&2
  exit 1
fi

echo 'fm-core fast capture tests: PASS'
