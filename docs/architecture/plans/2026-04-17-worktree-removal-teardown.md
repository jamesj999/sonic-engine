# Worktree Removal Teardown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add PowerShell and POSIX-shell worktree removal wrappers that teardown worktree-local disassembly repos, ROM files, and `config.json` before calling `git worktree remove`.

**Architecture:** Create two small repo-local wrapper scripts under a new `scripts/` directory. Each script validates a target worktree against `git worktree list --porcelain`, deletes only the repo's known shared-resource paths inside that worktree, then delegates to `git worktree remove`, with optional post-remove branch deletion. Update the tracked workflow docs so contributors stop using raw `git worktree remove` for this repository.

**Tech Stack:** PowerShell, POSIX shell, Git worktree porcelain output, repo docs in Markdown

---

## File Structure

- Create: `scripts/` - new top-level directory for repo-local worktree lifecycle helpers
- Create: `scripts/remove-worktree.ps1` - PowerShell wrapper for teardown + `git worktree remove`
- Create: `scripts/remove-worktree.sh` - POSIX shell wrapper with the same teardown behavior
- Modify: `.githooks/post-checkout` - comment block that points readers to the removal wrappers for teardown
- Modify: `CLAUDE.md` - contributor workflow note describing the supported removal command

### Task 1: Add the PowerShell Removal Wrapper

**Files:**
- Create: `scripts/remove-worktree.ps1`

- [ ] **Step 1: Create the PowerShell script with a failing-safe skeleton**

```powershell
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$WorktreePath,

    [switch]$DeleteBranch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

function Resolve-AbsolutePath([string]$Path) {
    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    return [System.IO.Path]::GetFullPath($resolved.Path)
}

function Normalize-Dir([string]$Path) {
    $full = [System.IO.Path]::GetFullPath($Path)
    return $full.TrimEnd('\', '/')
}

function Assert-ChildPath([string]$Parent, [string]$Child) {
    $parentNorm = (Normalize-Dir $Parent) + [System.IO.Path]::DirectorySeparatorChar
    $childNorm = Normalize-Dir $Child
    if (-not $childNorm.StartsWith($parentNorm, [System.StringComparison]::OrdinalIgnoreCase)) {
        Fail "Refusing to touch path outside target worktree: $Child"
    }
}

Write-Host "remove-worktree.ps1: validating target worktree"
```

- [ ] **Step 2: Add worktree discovery and branch lookup from porcelain output**

```powershell
function Get-WorktreeRecords {
    $lines = git worktree list --porcelain
    if ($LASTEXITCODE -ne 0) {
        Fail "git worktree list --porcelain failed"
    }

    $records = @()
    $current = $null
    foreach ($line in $lines) {
        if ($line -like "worktree *") {
            if ($null -ne $current) {
                $records += [pscustomobject]$current
            }
            $current = @{
                Worktree = $line.Substring(9)
                Branch   = $null
                IsMain   = $false
            }
            continue
        }
        if ($null -eq $current -or [string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($line -like "branch *") {
            $current.Branch = $line.Substring(7)
            continue
        }
    }
    if ($null -ne $current) {
        $records += [pscustomobject]$current
    }

    if ($records.Count -eq 0) {
        Fail "No worktrees were returned by git worktree list --porcelain"
    }

    $records[0].IsMain = $true
    return $records
}

$targetPath = Resolve-AbsolutePath $WorktreePath
$records = Get-WorktreeRecords
$record = $records | Where-Object { (Normalize-Dir $_.Worktree) -eq (Normalize-Dir $targetPath) } | Select-Object -First 1

if ($null -eq $record) {
    Fail "Target is not a registered worktree for this repository: $targetPath"
}

if ($record.IsMain) {
    Fail "Refusing to remove the main repository worktree: $targetPath"
}
```

- [ ] **Step 3: Add teardown helpers for disasm directories, ROMs, and config**

```powershell
function Remove-KnownDirectory([string]$WorktreeRoot, [string]$RelativePath) {
    $fullPath = Join-Path $WorktreeRoot $RelativePath
    Assert-ChildPath $WorktreeRoot $fullPath
    if (-not (Test-Path -LiteralPath $fullPath)) {
        return
    }
    Write-Host "  removing $RelativePath"
    Remove-Item -LiteralPath $fullPath -Recurse -Force
}

function Remove-KnownFile([string]$WorktreeRoot, [string]$RelativePath) {
    $fullPath = Join-Path $WorktreeRoot $RelativePath
    Assert-ChildPath $WorktreeRoot $fullPath
    if (-not (Test-Path -LiteralPath $fullPath)) {
        return
    }
    Write-Host "  removing $RelativePath"
    Remove-Item -LiteralPath $fullPath -Force
}

function Remove-TopLevelRoms([string]$WorktreeRoot) {
    Get-ChildItem -LiteralPath $WorktreeRoot -Filter *.gen -File -Force | ForEach-Object {
        Assert-ChildPath $WorktreeRoot $_.FullName
        Write-Host "  removing $($_.Name)"
        Remove-Item -LiteralPath $_.FullName -Force
    }
}

Write-Host "remove-worktree.ps1: tearing down shared worktree resources"
Remove-KnownDirectory $targetPath "docs/s1disasm"
Remove-KnownDirectory $targetPath "docs/s2disasm"
Remove-KnownDirectory $targetPath "docs/skdisasm"
Remove-KnownFile $targetPath "config.json"
Remove-TopLevelRoms $targetPath
```

- [ ] **Step 4: Finish the script with removal and optional branch deletion**

```powershell
Write-Host "remove-worktree.ps1: removing worktree $targetPath"
git worktree remove $targetPath
if ($LASTEXITCODE -ne 0) {
    Fail "git worktree remove failed for $targetPath"
}

if ($DeleteBranch -and $record.Branch) {
    $branchName = $record.Branch -replace '^refs/heads/', ''
    Write-Host "remove-worktree.ps1: deleting branch $branchName"
    git branch -d -- $branchName
    if ($LASTEXITCODE -ne 0) {
        Fail "Worktree removed, but branch deletion failed for $branchName"
    }
}

Write-Host "remove-worktree.ps1: done"
```

- [ ] **Step 5: Run a disposable PowerShell smoke test**

Run:

```powershell
$tempPath = ".worktrees/remove-worktree-ps1-smoke"
git worktree add $tempPath -b bugfix/ai-remove-worktree-ps1-smoke
powershell -ExecutionPolicy Bypass -File scripts/remove-worktree.ps1 $tempPath -DeleteBranch
git worktree list --porcelain
git branch --list bugfix/ai-remove-worktree-ps1-smoke
```

Expected:
- the wrapper prints teardown/removal messages
- the temp worktree path no longer appears in `git worktree list --porcelain`
- `git branch --list ...` prints nothing

- [ ] **Step 6: Commit the PowerShell wrapper**

```bash
git add scripts/remove-worktree.ps1
git commit -m "tools: add PowerShell worktree removal wrapper"
```

### Task 2: Add the POSIX Shell Removal Wrapper

**Files:**
- Create: `scripts/remove-worktree.sh`

- [ ] **Step 1: Create the shell script with strict mode and argument parsing**

```bash
#!/usr/bin/env sh
set -eu

DELETE_BRANCH=0

fail() {
  echo "remove-worktree.sh: $1" >&2
  exit 1
}

usage() {
  echo "Usage: scripts/remove-worktree.sh [--delete-branch] <worktree-path>" >&2
  exit 1
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --delete-branch)
      DELETE_BRANCH=1
      shift
      ;;
    --help|-h)
      usage
      ;;
    -*)
      fail "unknown option: $1"
      ;;
    *)
      break
      ;;
  esac
done

[ "$#" -eq 1 ] || usage
TARGET_INPUT=$1
```

- [ ] **Step 2: Add worktree parsing and target validation**

```bash
normalize_dir() {
  python - "$1" <<'PY'
import os, sys
print(os.path.normcase(os.path.abspath(sys.argv[1])).rstrip("\\/"))
PY
}

TARGET_PATH=$(normalize_dir "$TARGET_INPUT")

WORKTREE_LIST=$(git worktree list --porcelain) || fail "git worktree list --porcelain failed"
MAIN_WORKTREE=""
TARGET_BRANCH=""
FOUND_TARGET=0
CURRENT_WORKTREE=""
CURRENT_BRANCH=""
IS_FIRST=1

while IFS= read -r line || [ -n "$line" ]; do
  case "$line" in
    worktree\ *)
      CURRENT_WORKTREE=$(normalize_dir "${line#worktree }")
      CURRENT_BRANCH=""
      if [ "$IS_FIRST" -eq 1 ]; then
        MAIN_WORKTREE=$CURRENT_WORKTREE
        IS_FIRST=0
      fi
      ;;
    branch\ *)
      CURRENT_BRANCH=${line#branch }
      ;;
    "")
      if [ "$CURRENT_WORKTREE" = "$TARGET_PATH" ]; then
        FOUND_TARGET=1
        TARGET_BRANCH=$CURRENT_BRANCH
      fi
      CURRENT_WORKTREE=""
      CURRENT_BRANCH=""
      ;;
  esac
done <<EOF
$WORKTREE_LIST
EOF

[ -n "$MAIN_WORKTREE" ] || fail "unable to determine main worktree"
[ "$FOUND_TARGET" -eq 1 ] || fail "target is not a registered worktree: $TARGET_PATH"
[ "$TARGET_PATH" != "$MAIN_WORKTREE" ] || fail "refusing to remove the main worktree"
```

- [ ] **Step 3: Add teardown helpers and known-path cleanup**

```bash
assert_child_path() {
  parent=$(normalize_dir "$1")
  child=$(normalize_dir "$2")
  case "$child/" in
    "$parent"/*) ;;
    *)
      fail "refusing to touch path outside target worktree: $2"
      ;;
  esac
}

remove_known_path() {
  full_path="$1"
  [ -e "$full_path" ] || return 0
  assert_child_path "$TARGET_PATH" "$full_path"
  echo "  removing $full_path"
  rm -rf -- "$full_path"
}

echo "remove-worktree.sh: tearing down shared worktree resources"
remove_known_path "$TARGET_PATH/docs/s1disasm"
remove_known_path "$TARGET_PATH/docs/s2disasm"
remove_known_path "$TARGET_PATH/docs/skdisasm"
remove_known_path "$TARGET_PATH/config.json"

find "$TARGET_PATH" -maxdepth 1 -type f -name '*.gen' -print | while IFS= read -r rom; do
  [ -n "$rom" ] || continue
  assert_child_path "$TARGET_PATH" "$rom"
  echo "  removing $rom"
  rm -f -- "$rom"
done
```

- [ ] **Step 4: Finish the script with worktree removal and optional branch deletion**

```bash
echo "remove-worktree.sh: removing worktree $TARGET_PATH"
git worktree remove "$TARGET_PATH" || fail "git worktree remove failed for $TARGET_PATH"

if [ "$DELETE_BRANCH" -eq 1 ] && [ -n "${TARGET_BRANCH:-}" ]; then
  BRANCH_NAME=$(printf '%s' "$TARGET_BRANCH" | sed 's#^refs/heads/##')
  echo "remove-worktree.sh: deleting branch $BRANCH_NAME"
  git branch -d -- "$BRANCH_NAME" || fail "worktree removed, but branch deletion failed for $BRANCH_NAME"
fi

echo "remove-worktree.sh: done"
```

- [ ] **Step 5: Run a disposable POSIX-shell smoke test**

Run:

```bash
temp_path=".worktrees/remove-worktree-sh-smoke"
git worktree add "$temp_path" -b bugfix/ai-remove-worktree-sh-smoke
sh scripts/remove-worktree.sh --delete-branch "$temp_path"
git worktree list --porcelain
git branch --list bugfix/ai-remove-worktree-sh-smoke
```

Expected:
- the wrapper prints teardown/removal messages
- the temp worktree path no longer appears in `git worktree list --porcelain`
- `git branch --list ...` prints nothing

- [ ] **Step 6: Commit the shell wrapper**

```bash
git add scripts/remove-worktree.sh
git commit -m "tools: add shell worktree removal wrapper"
```

### Task 3: Document the Supported Removal Workflow

**Files:**
- Modify: `.githooks/post-checkout`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the hook comments to mention the paired removal wrappers**

```bash
#!/bin/bash
# post-checkout hook: auto-link ROMs, config, and disassemblies in git worktrees
#
# When `git worktree add` creates a new worktree, gitignored files (ROMs,
# config.json) and submodule directories (disassemblies) are missing. This hook
# detects worktree context and creates links back to the main working tree so
# everything just works.
#
# Worktree teardown is handled separately by:
#   scripts/remove-worktree.ps1
#   scripts/remove-worktree.sh
# Use those wrappers instead of raw `git worktree remove` for this repository.
#
# Works on macOS (native bash) and Windows (Git Bash with Developer Mode).
```

- [ ] **Step 2: Add a short contributor note to `CLAUDE.md` near the worktree/disassembly guidance**

```markdown
### Worktree teardown

This repository uses `.githooks/post-checkout` to wire shared resources into new worktrees, including ROMs, `config.json`, and the `docs/*disasm` directories. Do not use raw `git worktree remove` for cleanup in this repository.

Use one of these wrappers instead:

- `powershell -ExecutionPolicy Bypass -File scripts/remove-worktree.ps1 <worktree-path>`
- `sh scripts/remove-worktree.sh <worktree-path>`

Add `-DeleteBranch` or `--delete-branch` if you also want the worktree branch deleted after successful removal.
```

- [ ] **Step 3: Verify the docs mention the new workflow**

Run:

```powershell
Select-String -Path .githooks/post-checkout,CLAUDE.md -Pattern "remove-worktree|git worktree remove"
```

Expected:
- both files contain the wrapper names
- `CLAUDE.md` warns against raw `git worktree remove`

- [ ] **Step 4: Commit the documentation update**

```bash
git add .githooks/post-checkout CLAUDE.md
git commit -m "docs: document worktree removal wrappers"
```

### Task 4: Run End-to-End Verification

**Files:**
- Verify: `scripts/remove-worktree.ps1`
- Verify: `scripts/remove-worktree.sh`
- Verify: `.githooks/post-checkout`
- Verify: `CLAUDE.md`

- [ ] **Step 1: Verify the repo sees the new scripts and docs changes**

Run:

```powershell
git status --short
Get-ChildItem scripts | Select-Object Name,Length | Format-Table -AutoSize
```

Expected:
- the new scripts are present
- only the intended files are modified

- [ ] **Step 2: Run the PowerShell removal wrapper against a disposable worktree without deleting the branch**

Run:

```powershell
$tempPath = ".worktrees/remove-worktree-final-ps1"
git worktree add $tempPath -b bugfix/ai-remove-worktree-final-ps1
powershell -ExecutionPolicy Bypass -File scripts/remove-worktree.ps1 $tempPath
git worktree list --porcelain
git branch --list bugfix/ai-remove-worktree-final-ps1
```

Expected:
- the worktree path is gone from `git worktree list --porcelain`
- the branch still exists because `-DeleteBranch` was not supplied

- [ ] **Step 3: Delete the retained verification branch manually**

Run:

```bash
git branch -d bugfix/ai-remove-worktree-final-ps1
```

Expected:
- Git deletes the branch cleanly

- [ ] **Step 4: Run the shell removal wrapper against a disposable worktree with branch deletion**

Run:

```bash
temp_path=".worktrees/remove-worktree-final-sh"
git worktree add "$temp_path" -b bugfix/ai-remove-worktree-final-sh
sh scripts/remove-worktree.sh --delete-branch "$temp_path"
git worktree list --porcelain
git branch --list bugfix/ai-remove-worktree-final-sh
```

Expected:
- the worktree path is gone from `git worktree list --porcelain`
- `git branch --list ...` prints nothing

- [ ] **Step 5: Verify both wrappers reject the main worktree**

Run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/remove-worktree.ps1 .
```

Expected:
- FAIL with a message that the main worktree cannot be removed

Run:

```bash
sh scripts/remove-worktree.sh .
```

Expected:
- FAIL with a message that the main worktree cannot be removed

- [ ] **Step 6: Commit the verified implementation**

```bash
git add scripts/remove-worktree.ps1 scripts/remove-worktree.sh .githooks/post-checkout CLAUDE.md
git commit -m "tools: add safe worktree teardown wrappers"
```
