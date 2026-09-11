#!/usr/bin/env bash
# Rebuild the adapter parity harness against the fetched pinned source and
# rewrite the expectations TestYm2612ChipNukedParity compares against.
set -euo pipefail
source_dir=${1:?usage: regenerate-expectations.sh /abs/path/to/fetched/Nuked-OPN2}
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)
scripts="$repo/src/test/resources/audio/nuked-opn2/adapter"
build=$(mktemp -d "$repo/target/nuked-harness.XXXXXX")
trap 'rm -rf "$build"' EXIT
cc -O2 -Wall -o "$build/adapter_parity_harness" "$here/adapter_parity_harness.c" \
    "$source_dir/ym3438.c" -I"$source_dir"
python3 "$here/generate-adapter-scripts.py" "$scripts"
: > "$scripts/expected.txt"
for script in "$scripts"/*.txt; do
    name=$(basename "$script" .txt)
    [[ "$name" == expected ]] && continue
    out=$("$build/adapter_parity_harness" < "$script")
    summary=$(printf '%s\n' "$out" | tail -1)
    frames=$(awk '{print $2}' <<< "$summary")
    checksum=$(awk '{print $4}' <<< "$summary")
    tail4=$(printf '%s\n' "$out" | grep -v STATUS | tail -5 | head -4 | awk '{printf "%s,%s;", $1, $2}')
    statuses=$(printf '%s\n' "$out" | grep STATUS | awk '{printf "%s,", $2}' || true)
    printf '%s %s %s %s %s\n' "$name" "$frames" "$checksum" "$tail4" "${statuses:--}" >> "$scripts/expected.txt"
done
echo "wrote $(wc -l < "$scripts/expected.txt") expectations to $scripts/expected.txt"
