#!/usr/bin/env bash
set -euo pipefail

fail() { printf 'fetch-sources: %s\n' "$*" >&2; exit 2; }
usage() {
  cat <<'EOF'
Usage: fetch-sources.sh --output ABSOLUTE_TARGET_PATH

Fetches the immutable Nuked-OPN2 and ymfm revisions, verifies commit, tree,
licence and every benchmark input hash, then publishes below this worktree's
target/. The output path must not already exist.
EOF
}
tool_root=$(cd -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(cd -- "$tool_root/../../.." && pwd -P)
output=
while (($#)); do
  case "$1" in
    --help) usage; exit 0 ;;
    --output) output=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$output" && "$output" = /* ]] || fail '--output must be an absolute path'
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

stage=$(mktemp -d "$repo_root/target/.fm-core-sources.XXXXXX")
cleanup() { [[ -d "${stage-}" ]] && rm -rf -- "$stage"; }
trap cleanup EXIT

fetch_one() {
  local name=$1 url=$2 commit=$3 tree=$4 lock=$5
  local destination="$stage/$name"
  git -c core.hooksPath=/dev/null init -q "$destination"
  git -C "$destination" -c protocol.allow=never -c protocol.https.allow=always \
    fetch -q --depth=1 "$url" "$commit"
  git -C "$destination" -c core.hooksPath=/dev/null checkout -q --detach FETCH_HEAD
  [[ $(git -C "$destination" rev-parse HEAD) = "$commit" ]] || fail "$name commit mismatch"
  [[ $(git -C "$destination" rev-parse 'HEAD^{tree}') = "$tree" ]] || fail "$name tree mismatch"
  python3 "$tool_root/verify-source.py" --source "$destination" --lock "$tool_root/$lock"
}

fetch_one nuked https://github.com/nukeykt/Nuked-OPN2.git \
  335747d78cb0abbc3b55b004e62dad9763140115 \
  6637a500d1da3b08cbc0cec1532ab305197b8978 nuked.lock
fetch_one ymfm https://github.com/aaronsgiles/ymfm.git \
  81aec25ccbb98f4873a255f7551ac4dadac59b4a \
  03f76ed27b1281357c91005e99d043eebd5119c1 ymfm.lock

mv -T --no-clobber "$stage" "$resolved"
stage=
printf '%s\n' "$resolved"
