#!/usr/bin/env bash
# Rebuilds the port-level bit-exactness harness against the pinned upstream
# source, runs every synthetic script body under src/test/resources/audio/nuked-opn2/port/
# through it once per chip-type flag set (0..3) and rewrites expected.txt with
# one line per (body, type):
#   <body> <type> <cycles> <pin-stream fnv1a64> <side lines> <side fnv1a64>
# TestNukedOpn2BitExactScripts asserts those numbers on the Java port.
#
# Usage: regenerate-bitexact-expectations.sh /abs/path/to/nuked-src [/abs/build/dir]
#        [--audio-fixtures /abs/external/root]
# With --audio-fixtures, regenerate only external captured game scripts under
# audio/nuked-opn2/port; the public synthetic expectations are untouched.
# (fetch the source first with ../fetch-source.sh --output /abs/path/to/nuked-src)
set -euo pipefail
SRC=${1:?pinned Nuked-OPN2 source directory}
shift
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../../.." && pwd)
PORT="$REPO/src/test/resources/audio/nuked-opn2/port"
BUILD=""
CAPTURED=false
while (($#)); do
    case "$1" in
        --audio-fixtures)
            PORT="${2:?external audio fixture root}/audio/nuked-opn2/port"
            CAPTURED=true
            shift 2
            ;;
        --*) echo "Unknown option: $1" >&2; exit 2 ;;
        *)
            if [[ -n "$BUILD" ]]; then
                echo "Unexpected argument: $1" >&2; exit 2
            fi
            BUILD=$1
            shift
            ;;
    esac
done
shopt -s nullglob
bodies=("$PORT"/*.txt.gz)
if ((${#bodies[@]} == 0)); then
    echo "No FM script bodies in $PORT" >&2; exit 2
fi
for body in "${bodies[@]}"; do
    name=$(basename "$body" .txt.gz)
    case "$name" in
        s1-*|s2-*|s3k-*) actual=true ;;
        *) actual=false ;;
    esac
    if [[ "$actual" != "$CAPTURED" ]]; then
        echo "Script belongs to the wrong fixture partition: $body" >&2; exit 2
    fi
done
BUILD=${BUILD:-$(mktemp -d)}
mkdir -p "$BUILD"
cc -O2 -I"$SRC" "$HERE/bitexact_harness.c" "$SRC/ym3438.c" -o "$BUILD/bitexact_harness"
: > "$BUILD/expected.txt"
for body in "${bodies[@]}"; do
    name=$(basename "$body" .txt.gz)
    for type in 0 1 2 3; do
        { echo "type $type"; gzip -dc "$body"; } > "$BUILD/script.txt"
        line=$("$BUILD/bitexact_harness" "$BUILD/script.txt" "$BUILD/out.pcm" "$BUILD/side.txt")
        cycles=$(echo "$line" | awk '{print $2}')
        checksum=$(echo "$line" | awk '{print $4}')
        side=$(python3 - "$BUILD/side.txt" <<'PY'
import sys
h = 0xcbf29ce484222325
n = 0
with open(sys.argv[1], "rb") as f:
    for raw in f:
        n += 1
        for b in raw:
            h ^= b
            h = (h * 0x100000001b3) & 0xffffffffffffffff
print(n, format(h, "016x"))
PY
)
        echo "$name $type $cycles $checksum $side" >> "$BUILD/expected.txt"
    done
done
cp "$BUILD/expected.txt" "$PORT/expected.txt"
wc -l "$PORT/expected.txt"
