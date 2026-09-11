#!/usr/bin/env bash
set -u
repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 4
target=$("$repo_root/tools/tracechaser-bootstrap.sh" --require "${1:-}") || exit $?
shift
exec "$target" "$@"
