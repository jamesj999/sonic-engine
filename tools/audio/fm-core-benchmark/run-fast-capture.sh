#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'fm-core-fast-capture: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'EOF'
Usage: run-fast-capture.sh --output ABSOLUTE_TARGET_PATH \
  --ymfm-source DIR --capture COMPLETE_PHYSICAL_BUS_JSONL

Runs a correctness-only C++ ymfm candidate over an admitted complete raw-YM
capture. No timing or PCM file is collected. Because ymfm generates one frame
at a time, writes at native cycles 24n..24n+23 are applied before ymfm frame n;
this explicit quantization is not a fidelity claim.
EOF
}

tool_root=$(cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
output=
ymfm_source=
capture=
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --output) output=${2-}; shift 2 ;;
    --ymfm-source) ymfm_source=${2-}; shift 2 ;;
    --capture) capture=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$output" && "$output" = /* ]] || fail '--output must be an absolute path'
[[ -n "$ymfm_source" ]] || fail '--ymfm-source is required'
[[ -f "$capture" ]] || fail '--capture must name a file'

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

ymfm_source=$(cd -- "$ymfm_source" && pwd -P)
capture=$(cd -- "$(dirname -- "$capture")" && pwd -P)/$(basename -- "$capture")
python3 "$tool_root/verify-source.py" --source "$ymfm_source" --lock "$tool_root/ymfm.lock"
cxx=${CXX:-c++}
command -v "$cxx" >/dev/null || fail "C++ compiler not found: $cxx"

mkdir -p -- "${resolved%/*}"
mkdir -- "$resolved"
python3 "$tool_root/validate-capture.py" --input "$capture" \
  --events-output "$resolved/capture-events.tsv" \
  --metadata-output "$resolved/capture-metadata.json"

inputs=(
  "$tool_root/FastYmfmCaptureProof.cpp"
  "$ymfm_source/src/ymfm.h"
  "$ymfm_source/src/ymfm_adpcm.cpp"
  "$ymfm_source/src/ymfm_adpcm.h"
  "$ymfm_source/src/ymfm_fm.h"
  "$ymfm_source/src/ymfm_fm.ipp"
  "$ymfm_source/src/ymfm_opn.cpp"
  "$ymfm_source/src/ymfm_opn.h"
  "$ymfm_source/src/ymfm_ssg.cpp"
  "$ymfm_source/src/ymfm_ssg.h"
)
"$cxx" -O2 -std=c++14 -I "$ymfm_source/src" \
  "$tool_root/FastYmfmCaptureProof.cpp" \
  "$ymfm_source/src/ymfm_opn.cpp" "$ymfm_source/src/ymfm_ssg.cpp" \
  "$ymfm_source/src/ymfm_adpcm.cpp" -o "$resolved/fast-capture-proof"
terminal=$(python3 - "$resolved/capture-metadata.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["terminal_ym_cycle"])
PY
)
"$resolved/fast-capture-proof" "$resolved/capture-events.tsv" "$terminal" \
  > "$resolved/proof.json"

python3 - "$resolved/result.json" "$resolved/proof.json" \
    "$resolved/capture-metadata.json" "$capture" "$cxx" "${inputs[@]}" <<'PY'
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

output, proof_path, metadata_path, capture_path, compiler, *inputs = sys.argv[1:]
result = json.loads(Path(proof_path).read_text(encoding="utf-8"))
metadata = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
if result["frames"] != metadata["terminal_ym_cycle"] // 24:
    raise SystemExit("candidate frame count disagrees with capture endpoint")
result.update(metadata)
result.update({
    "schema": "openggf.fm-core-fast-capture.v1",
    "timing_collected": False,
    "publishable": False,
    "presentation_pcm_reconstructable": False,
    "capture_input_sha256": hashlib.sha256(Path(capture_path).read_bytes()).hexdigest(),
    "compiler": subprocess.run([compiler, "--version"], capture_output=True,
                               text=True, check=True).stdout.splitlines()[0],
    "compiler_flags": ["-O2", "-std=c++14"],
    "host": {"platform": platform.platform(), "machine": platform.machine()},
    "compiled_input_sha256": {
        Path(path).name: hashlib.sha256(Path(path).read_bytes()).hexdigest()
        for path in inputs
    },
})
Path(output).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n",
                        encoding="utf-8")
PY

printf '%s\n' "$resolved/result.json"
