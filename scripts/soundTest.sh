#!/usr/bin/env bash
#
# Incrementally compile and launch the standalone SMPS sound test.

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$script_dir"

exec_args=""
for arg in "$@"; do
    escaped="${arg//\'/\'\\\'\'}"
    exec_args+="${exec_args:+ }'$escaped'"
done

exec mvn -q -Dmse=off -DskipTests compile exec:java \
    -Dexec.mainClass=com.openggf.audio.debug.SoundTestApp \
    "-Dexec.args=$exec_args"
