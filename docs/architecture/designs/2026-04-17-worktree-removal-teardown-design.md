# Worktree Removal Teardown Design

Date: 2026-04-17

## Problem

This repository uses a `post-checkout` hook in `.githooks/post-checkout` to make newly created git worktrees usable by wiring in shared resources from the main tree. Today that workflow covers:

- root-level `*.gen` ROM files
- root-level `config.json`
- `docs/s1disasm`
- `docs/s2disasm`
- `docs/skdisasm`

Worktree creation is automated, but worktree removal is not. Raw `git worktree remove` fails when a target worktree still contains populated disassembly repos under `docs/*disasm`. In practice, those paths are tracked as gitlinks and can exist in a worktree as nested repositories rather than removable links. That means removal needs a repo-specific teardown step before Git can delete the worktree.

## Goals

- Provide a supported removal workflow for this repository's worktrees.
- Teardown the shared-resource paths that the repo wires into worktrees.
- Keep deletion narrowly scoped to known generated/shared resources.
- Support both PowerShell and POSIX shell users.
- Preserve the existing `post-checkout` creation flow.

## Non-Goals

- Replace `git worktree add` or redesign worktree creation.
- Generalize cleanup for arbitrary nested repos or arbitrary files.
- Add a native Git hook for worktree removal; Git does not provide one for this lifecycle point.

## Proposed Approach

Add two repo-local wrapper scripts:

- `scripts/remove-worktree.ps1`
- `scripts/remove-worktree.sh`

These scripts become the supported way to remove worktrees in this repository. They perform a repo-specific teardown first, then call `git worktree remove`.

## Target Cleanup Scope

Before removing the worktree, the wrapper deletes only these paths inside the target worktree:

- `docs/s1disasm`
- `docs/s2disasm`
- `docs/skdisasm`
- root-level `config.json`
- root-level `*.gen`

No recursive ROM search is performed. Only top-level `*.gen` files in the target worktree root are eligible.

## Script Interface

Both wrappers expose the same behavior:

- Required argument: target worktree path
- Optional flag: delete the associated branch after successful worktree removal

Example intent:

- remove worktree only
- remove worktree and then delete its branch

The exact flag spelling can remain shell-idiomatic:

- PowerShell: `-DeleteBranch`
- POSIX shell: `--delete-branch`

## Safety Rules

The scripts fail closed.

They must:

1. Resolve the target to an absolute path.
2. Verify that the target path appears in `git worktree list --porcelain`.
3. Reject the main repository worktree.
4. Limit teardown to the known paths listed in this spec.
5. Confirm each resolved removal target remains under the target worktree root.
6. Abort before calling `git worktree remove` if any teardown deletion fails.

The scripts must not:

- delete anything outside the target worktree
- recurse through arbitrary file globs beyond top-level `*.gen`
- delete the branch before the worktree is successfully removed

## Teardown Semantics

For each known disassembly path:

- If the path does not exist, skip it.
- If the path is a directory, symlink, junction, or nested repo, remove it recursively.

For root-level shared files:

- If `config.json` exists, remove it.
- If any top-level `*.gen` files exist, remove them.

After successful teardown:

1. Call `git worktree remove <path>`.
2. If branch deletion was requested, look up the branch from `git worktree list --porcelain` data captured before removal.
3. Delete the branch only after successful worktree removal.

## Error Handling

- Unknown worktree path: fail with a clear error.
- Main worktree passed as target: fail with a clear error.
- Teardown deletion failure: stop immediately and report the path that failed.
- `git worktree remove` failure: surface Git's error and do not delete the branch.
- Branch deletion failure: report it separately after successful worktree removal.

Partial teardown is acceptable only in the sense that the worktree remains on disk for manual recovery; the scripts should not try to continue once a teardown step fails.

## Verification

Validation for this feature should be end-to-end rather than heavy unit scaffolding:

1. Create a disposable worktree.
2. Confirm the target worktree contains the expected shared resources.
3. Run the PowerShell wrapper and verify:
   - teardown paths are removed
   - `git worktree list` no longer shows the worktree
4. Recreate a disposable worktree.
5. Run the POSIX shell wrapper and verify the same behavior.
6. Verify branch deletion behavior separately with and without the delete-branch option.
7. Verify the scripts reject the main worktree.

## Documentation

Document the new workflow wherever worktree usage is described so contributors stop using raw `git worktree remove` directly for this repository.

At minimum, the implementation should update the tracked workflow notes near the existing worktree automation and explain that creation is handled by `post-checkout`, while removal must go through the wrapper scripts.

## Rollout

1. Add both wrapper scripts.
2. Validate them against a disposable worktree.
3. Update repo workflow documentation.
4. Use the wrappers for future cleanup instead of raw `git worktree remove`.
