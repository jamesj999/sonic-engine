#!/usr/bin/env bash
set -euo pipefail

worktree="$(git rev-parse --show-toplevel)"
if ! git -C "$worktree" config --local core.hooksPath .githooks; then
    echo "Unable to install OpenGGF hooks in $worktree: Git configuration is not writable." >&2
    exit 1
fi

echo "OpenGGF hooks installed for $worktree (core.hooksPath=.githooks)"
