#!/usr/bin/env bash
set -u

repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || {
  echo "TraceChaser: run this command from an OpenGGF checkout." >&2
  exit 4
}
submodule_path="$repo_root/tools/tracechaser"
index_record=$(git -C "$repo_root" ls-files -s -- tools/tracechaser)
expected=$(printf '%s\n' "$index_record" | awk '$1 == "160000" && $4 == "tools/tracechaser" { print $2 }')

if [[ ! $expected =~ ^[0-9a-f]{40}$ ]]; then
  echo "TraceChaser: tools/tracechaser is missing or is not a gitlink in the OpenGGF index." >&2
  exit 4
fi
if [[ -L $submodule_path || ( -e $submodule_path && ! -d $submodule_path ) ]]; then
  echo "TraceChaser: tools/tracechaser is an unsafe path; expected gitlink $expected." >&2
  exit 4
fi
if [[ ! -e $submodule_path/.git ]]; then
  echo "TraceChaser is not initialized; expected $expected." >&2
  echo "Run: git submodule update --init --recursive tools/tracechaser" >&2
  exit 2
fi
actual=$(git -C "$submodule_path" rev-parse HEAD 2>/dev/null) || {
  echo "TraceChaser: initialized checkout is unreadable; expected $expected." >&2
  exit 4
}
if [[ $actual != "$expected" ]]; then
  echo "TraceChaser checkout is at $actual; expected $expected." >&2
  echo "Run: git submodule update --init --recursive tools/tracechaser" >&2
  exit 3
fi

case ${1:-} in
  --check) exit 0 ;;
  --require)
    relative=${2:-}
    if [[ -z $relative || $relative == /* || $relative == *\\* ||
          $relative =~ (^|/)\.\.?(/|$) ]]; then
      echo "TraceChaser: unsafe or missing command path '$relative'." >&2
      exit 4
    fi
    if [[ -n $(git -C "$submodule_path" status --porcelain --untracked-files=all 2>/dev/null) ]]; then
      echo "TraceChaser: initialized checkout is dirty; refusing command '$relative'." >&2
      exit 4
    fi
    target="$submodule_path/$relative"
    cursor=$submodule_path
    IFS=/ read -r -a components <<< "$relative"
    for ((index = 0; index < ${#components[@]}; index++)); do
      cursor="$cursor/${components[index]}"
      if [[ -L $cursor ]]; then
        echo "TraceChaser: unsafe symlink component in command '$relative'." >&2
        exit 4
      fi
      if (( index + 1 < ${#components[@]} )) && [[ ! -d $cursor ]]; then
        echo "TraceChaser: unsafe or missing command ancestor in '$relative'." >&2
        exit 4
      fi
    done
    canonical_root=$(realpath -e -- "$submodule_path" 2>/dev/null) || {
      echo "TraceChaser: initialized checkout root is unreadable." >&2
      exit 4
    }
    canonical_target=$(realpath -e -- "$target" 2>/dev/null) || {
      echo "TraceChaser: unsafe or missing command '$relative'." >&2
      exit 4
    }
    if [[ $canonical_target != "$canonical_root"/* || ! -f $canonical_target ]]; then
      echo "TraceChaser: unsafe or missing command '$relative'." >&2
      exit 4
    fi
    printf '%s\n' "$canonical_target"
    ;;
  *)
    echo "usage: tools/tracechaser-bootstrap.sh --check | --require RELATIVE_PATH" >&2
    exit 4
    ;;
esac
