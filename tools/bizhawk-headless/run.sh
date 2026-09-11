#!/usr/bin/env bash
set -u
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 4
exec "$root/tools/tracechaser.sh" bizhawk-headless/run.sh "$@"
