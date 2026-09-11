# `next` Merge Broad-Staging Audit

## Scope

This audit examines merge commit
`0b8359abbb97daf088b670c11938b7d78f4d7bd1` (`develop` into `next`) for
content plausibly captured by a broad staging operation.

## Finding

The merge result contains 79 paths that exist in neither parent
(`126e78ca8f4401bb3b81abfa5b0e5ad85b7dfff0` and
`ab71e5569de96f39fcd474690ef71f2d8b9ba9b2`). This is strong evidence that
pre-existing untracked worktree content was swept into the merge commit.

The paths comprise:

- five absolute disassembly symlinks;
- one root `MERGE-STATUS-develop-into-next.md` scratch handoff;
- 69 architecture audit, design, plan, and research artifacts;
- three Java implementation files; and
- one test-resource file.

The five symlinks are definitively accidental. Their blobs contain absolute
targets under a local `$HOME/code/projects/OpenGGF/docs/` workspace. The root merge-status
file is also a transient artifact: it describes `MERGE_HEAD` as still present
and embeds the local `OpenGGF-next` worktree path.

The 69 architecture artifacts and four code/test files may contain intentional
engineering work produced during the extensive manual merge. They were not
inherited from either parent, however, so their inclusion cannot be validated
from branch ancestry and requires an intent/provenance review before the merge
is treated as clean.

## Exposure scan

No merge-only ROMs, local configuration files, build directories, JAR/class
outputs, private-key blocks, obvious API tokens/password assignments, or large
binary payloads were found. Merge-only regular blobs are at most 68,244 bytes.

Machine-local paths do occur in the merge-only content:

- the merge-status file contains the Linux `OpenGGF-next` path;
- one plan contains a `<drive>:/Users/<user>/IdeaProjects/sonic-engine` path; and
- several research artifacts contain Windows user/worktree paths in recorded
  evaluation prompts.

## Cause

The worktree hook creates symlinks at the disassembly paths. The relevant
`.gitignore` patterns end in `/`, so Git treats them as directory-only patterns.
They ignore real disassembly directories but do not ignore symlinks with the
same names. Consequently, `git add -A` stages those links.

The existing commit policy also skipped the decisive checks:

- merge-time validation returned after the integration `README.md` rule; and
- CI documentation validation enumerated commits with `--no-merges`.

## Required follow-up

1. Prevent generated resource links, machine-local paths, and transient root
   handoff files from entering commits.
2. Validate merge results locally and in CI.
3. Replace absolute worktree link targets with relative targets.
4. Remove the accidental artifacts from current `next`.
5. Review the 74 non-symlink merge-only paths for provenance and intent.
6. Decide separately whether published history must be rewritten to erase the
   disclosed path.
