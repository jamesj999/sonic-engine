#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'fm-core-jni-proof: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'EOF'
Usage: run-jni-proof.sh --output ABSOLUTE_TARGET_PATH --nuked-source DIR \
  [--capture COMPLETE_PHYSICAL_BUS_JSONL]

Builds and runs a Linux/JDK-21 correctness proof. It transfers actual stereo
PCM through JNI and collects no timings. Output must be a new directory below
the invoking worktree's target/.
EOF
}

tool_root=$(cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
output=
nuked_source=
capture=
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --output) output=${2-}; shift 2 ;;
    --nuked-source) nuked_source=${2-}; shift 2 ;;
    --capture) capture=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$output" && "$output" = /* ]] || fail '--output must be an absolute path'
[[ -n "$nuked_source" ]] || fail '--nuked-source is required'
[[ $(uname -s) = Linux ]] || fail 'this research proof currently supports Linux only'

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

nuked_source=$(cd -- "$nuked_source" && pwd -P)
python3 "$tool_root/verify-source.py" \
  --source "$nuked_source" --lock "$tool_root/nuked.lock"
command -v javac >/dev/null || fail 'javac not found'
command -v java >/dev/null || fail 'java not found'
cc=${CC:-cc}
command -v "$cc" >/dev/null || fail "C compiler not found: $cc"
[[ $(javac -version 2>&1) = javac\ 21.* ]] || fail 'JDK 21 is required'
java_major=$(java -XshowSettings:properties -version 2>&1 \
  | sed -n 's/^[[:space:]]*java.version = \([0-9][0-9]*\).*/\1/p' | head -n1)
[[ "$java_major" = 21 ]] || fail 'the Java runtime must be JDK 21'
javac_path=$(readlink -f -- "$(command -v javac)")
jdk_root=${javac_path%/bin/javac}
[[ -f "$jdk_root/include/jni.h" && -f "$jdk_root/include/linux/jni_md.h" ]] \
  || fail "JNI headers not found below $jdk_root"

mkdir -p -- "${resolved%/*}"
mkdir -- "$resolved"
mkdir -- "$resolved/classes" "$resolved/stage" "$resolved/relocated"
if [[ -n "$capture" ]]; then
  [[ -f "$capture" ]] || fail "capture not found: $capture"
  capture=$(cd -- "$(dirname -- "$capture")" && pwd -P)/$(basename -- "$capture")
  python3 "$tool_root/validate-capture.py" --input "$capture" \
    --events-output "$resolved/capture-events.tsv" \
    --metadata-output "$resolved/capture-metadata.json"
fi
native_flags=(-O2 -fPIC -shared -Wl,-z,defs)
"$cc" "${native_flags[@]}" \
  -I "$jdk_root/include" -I "$jdk_root/include/linux" -I "$nuked_source" \
  "$tool_root/nuked_jni.c" "$nuked_source/ym3438.c" \
  -o "$resolved/stage/libopenggf-fm-jni-proof.so"
javac -d "$resolved/classes" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2State.java" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2Tables.java" \
  "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2.java" \
  "$tool_root/JniNukedProof.java"

mv -- "$resolved/stage/libopenggf-fm-jni-proof.so" "$resolved/relocated/"
(
  cd -- "$repo_root/target"
  java -cp "$resolved/classes" com.openggf.tools.audio.benchmark.JniNukedProof \
    "$resolved/relocated/libopenggf-fm-jni-proof.so"
) > "$resolved/result.json"

if [[ -n "$capture" ]]; then
  terminal=$(python3 - "$resolved/capture-metadata.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1], encoding="utf-8"))["terminal_ym_cycle"])
PY
)
  (
    cd -- "$repo_root/target"
    java -cp "$resolved/classes" com.openggf.tools.audio.benchmark.JniNukedProof \
      "$resolved/relocated/libopenggf-fm-jni-proof.so" \
      --capture-events "$resolved/capture-events.tsv" "$terminal"
  ) > "$resolved/capture-proof.json"
fi

mv -- "$resolved/result.json" "$resolved/proof.json"
python3 - "$resolved/proof.json" "$resolved/result.json" "$repo_root" "$cc" \
    "$resolved/capture-metadata.json" "$resolved/capture-proof.json" "$capture" \
    "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2State.java" \
    "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2Tables.java" \
    "$repo_root/src/main/java/com/openggf/audio/synth/nuked/NukedOpn2.java" \
    "$tool_root/JniNukedProof.java" "$tool_root/nuked_jni.c" \
    "$nuked_source/ym3438.c" "$nuked_source/ym3438.h" <<'PY'
import hashlib
import json
import platform
import subprocess
import sys
from pathlib import Path

proof_path, output_path, repo, compiler, metadata_path, capture_proof_path, capture_path, *inputs = sys.argv[1:]
result = json.loads(Path(proof_path).read_text(encoding="utf-8"))
result.update({
    "java_major": 21,
    "java_version": subprocess.run(["java", "-version"], capture_output=True,
                                   text=True, check=True).stderr.splitlines()[0],
    "native_compiler": subprocess.run([compiler, "--version"], capture_output=True,
                                      text=True, check=True).stdout.splitlines()[0],
    "native_compiler_flags": ["-O2", "-fPIC", "-shared", "-Wl,-z,defs"],
    "host": {"platform": platform.platform(), "machine": platform.machine()},
    "git_head": subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"],
                               capture_output=True, text=True, check=True).stdout.strip(),
    "git_dirty": bool(subprocess.run(["git", "-C", repo, "status", "--porcelain"],
                                     capture_output=True, text=True, check=True).stdout),
    "compiled_input_sha256": {
        Path(path).name: hashlib.sha256(Path(path).read_bytes()).hexdigest()
        for path in inputs
    },
})
if capture_path:
    capture = json.loads(Path(metadata_path).read_text(encoding="utf-8"))
    proof = json.loads(Path(capture_proof_path).read_text(encoding="utf-8"))
    if capture["terminal_ym_cycle"] != proof["terminal_ym_cycle"] \
            or capture["ym_events"] != proof["ym_events"]:
        raise SystemExit("capture metadata and replay proof disagree")
    capture.update(proof)
    capture["presentation_pcm_reconstructable"] = False
    capture["input_sha256"] = hashlib.sha256(Path(capture_path).read_bytes()).hexdigest()
    result["capture_replay"] = capture
Path(output_path).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n",
                             encoding="utf-8")
Path(proof_path).unlink()
PY

python3 -m json.tool "$resolved/result.json" >/dev/null
printf '%s\n' "$resolved/result.json"
