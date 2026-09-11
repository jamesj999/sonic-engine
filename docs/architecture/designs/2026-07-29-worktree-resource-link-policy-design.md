# Worktree Resource Link Policy Design

## Problem

OpenGGF's `post-checkout` hook creates convenience links from secondary
worktrees to ROMs, configuration, and disassembly directories in the main
workspace. The hook currently writes absolute targets. During merge resolution
on `next`, a broad staging operation captured all five generated disassembly
links as Git mode `120000` entries. The resulting merge commit exposed a local
home-directory path and made other machines check out broken links.

Two policy gaps allowed this:

1. merge-time commit validation returns after checking only the `README.md`
   integration rule; and
2. CI range validation deliberately excludes merge commits.

Changing absolute links to relative links removes the path disclosure but does
not prevent generated filesystem scaffolding from becoming repository content.

## Invariants

- Worktree convenience links exist only in the filesystem and never in a Git
  tree.
- The protected generated-resource symlink paths are `config.yaml`, every
  `*.gen` path, and these disassembly directories:
  `docs/s1disasm`, `docs/s2disasm`, `docs/kis2disasm`,
  `docs/scddisasm`, and `docs/skdisasm`.
- The repository-wide ROM-like asset rule rejects added or modified paths with
  the extensions `.gen`, `.smd`, `.bin`, `.sms`, `.gg`, or `.32x`.
- A protected path must not be committed with symlink mode `120000`, regardless
  of whether its target is relative or absolute.
- No newly committed symlink anywhere in the repository may have an absolute
  POSIX, drive-qualified Windows, or UNC target.
- Newly committed textual content must not embed a machine-local user-home
  workspace path (`/home/<user>/`, `/var/home/<user>/`, `/Users/<user>/`, or
  `<drive>:\Users\<user>\`). Windows separators may be forward, backward,
  mixed, or escaped/doubled in source text. Use repository-relative paths,
  environment variables, or neutral placeholders instead.
- Root-level `MERGE-STATUS*.md` and `HANDOVER*.md` scratch artifacts are not
  deliverables. Intentional audits or handovers must be classified under the
  applicable `docs/architecture/` directory.
- The same checks run on Linux/macOS shell hooks, Windows PowerShell hooks, and
  CI.
- Existing legitimate relative symlinks outside the protected paths remain
  permitted.

## Design

### Relative worktree links

`post-checkout` will derive the main workspace as a path relative to the
destination link's parent directory. Git's
`rev-parse --path-format=relative --git-common-dir`, executed from that parent,
provides a portable relationship to the common repository. Removing its
trailing `.git` component yields the main workspace without embedding a local
absolute prefix.

The source existence checks may continue using an internal absolute path; only
the text stored in the symlink must be relative.

When a protected path already contains a legacy absolute symlink, the hook
replaces it only if the target exists and canonically resolves to the expected
main-worktree resource. It prepares the relative replacement before removing
the old link and restores the old target if installation fails. Relative,
broken, unknown, and user-authored symlinks remain untouched.

This derivation assumes the repository's existing convention that the common
Git directory is `<main-worktree>/.git`. The hook will verify that basename and
fail closed rather than emit a wrong link for a bare or separate-git-dir
layout.

The disassembly entries in `.gitignore` will omit the trailing slash so the
patterns match both real directories and symlinks. The current trailing-slash
patterns match directories only, which is why `git add -A` captured the five
hook-created links.

### Staged-index validation

The policy implementations will derive added and modified candidates from
`git diff --cached --no-renames --diff-filter=AMT` against `HEAD` (or the empty
tree for an unborn branch). Disabling rename detection deliberately represents
a rename destination as an added path. They will then query each candidate's
index mode with `git ls-files --stage` and read symlink blobs through
`git cat-file blob :<path>`. During a merge, this compares the resolved index
with the first-parent `HEAD`, so merge-result additions are candidates.

Validation fails when:

- the path is one of the protected generated-resource paths;
- the candidate is at any repository path and has an extension covered by the
  repository-wide ROM denylist; or
- the target is absolute on POSIX or Windows.

The same candidate pass rejects root merge/handover scratch artifacts and
machine-local home paths in textual blobs. Both implementations delegate text
classification to `git grep --cached -I`: Git's binary detection treats a blob
containing a NUL byte in the inspected buffer as binary and `-I` suppresses
matches, providing one cross-platform definition without decoding blobs in
PowerShell. The existing ROM denylist remains repository-wide and covers
`.gen`, `.smd`, `.bin`, `.sms`, `.gg`, and `.32x`, rather than introducing a
second root-only ROM rule.

The shared Windows-home expression accepts one or more forward or backward
slashes at every separator boundary. This covers native, mixed, and
escaped/doubled textual representations without classifying `$USER`,
`%USERNAME%`, or angle-bracket placeholders as concrete usernames.

This validation runs before merge-specific early returns in `commit-msg`, and
also through a new `pre-commit` hook. The duplicate entry points are
intentional: `pre-commit` gives immediate feedback, while `commit-msg` covers
merge commit paths and installations that predate the new hook.

A tracked `pre-push` hook parses Git's standard ref-update stream and validates
every non-deletion local range before allowing transport. This covers direct
pushes to `next` and other working branches, including pushes from installations
where the offending commit predates the new commit hooks.

For a new remote branch (all-zero remote OID), `pre-push` validates every commit
reachable from the local OID that is not already reachable from any
remote-tracking ref for the named remote (`rev-list <local> --not
--remotes=<remote>`), plus the pushed tip. It fails closed if the remote name
cannot be determined. This catches an earlier bad unpublished commit even when
the new branch tip has removed its visible artifact.

### Committed-tree and CI validation

CI validation will enumerate all commits introduced by the checked range,
including merges. It will compare each commit with its first parent (or the
empty tree for a root commit) and validate entries added or changed by that
commit. A merge-resolution addition is therefore visible even when neither
parent contained it, without relying on combined-diff output. Documentation
trailer validation remains limited to non-merge commits.

CI also validates the delivered range-head tree for forbidden resource links
and absolute symlinks. A removal commit can repair a violation inherited from
the range base because the violating introduction is outside the checked
range and the delivered head becomes clean. If a bad commit and its removal
are both inside the submitted range, CI continues to reject the bad commit;
the submitter must rewrite that unpublished range. This prevents a
machine-local path from remaining in newly published history merely because a
later commit hides it at the tip.

GitHub Actions will run the push-range policy on all branch pushes, not only
`master`. This is an independent detection backstop; actually preventing a
noncompliant remote ref update requires the repository ruleset to make that
check required. The local `pre-push` hook is the in-repository preventive gate.
For a new branch whose event `before` OID is all zero, CI uses the fixed
worktree-resource-policy cutover commit
`ccdd33edf4f9cd4a7937791f1d4c2f37cbeeb5e0` as its stable pre-policy integration
boundary. This is the immutable `develop` ancestor from which the policy branch
was created, avoiding a self-reference to a future policy commit.
The cutover must be an ancestor of the new tip; otherwise CI fails closed and
requires the branch to incorporate the policy baseline. CI validates every
commit after that cutover plus the tip tree. Unlike post-update remote refs,
this boundary cannot be influenced by peer branches created together.
CI checkout fetches full history so the cutover object is locally available.
Shell and PowerShell constants must contain the identical OID.

### Regression coverage

`TestBuildToolingGuard` will exercise the policy in temporary Git repositories.
Tests will prove that:

- a protected relative symlink is rejected;
- an unprotected absolute symlink is rejected;
- an unprotected relative symlink is accepted;
- a merge commit that introduces a protected symlink is rejected by CI range
  validation; and
- a cleanup commit repairs a violation inherited from the range base, while a
  bad-plus-removal pair wholly inside a submitted range remains rejected;
- `commit-msg` rejects a protected staged symlink while `MERGE_HEAD` exists;
- `pre-commit` rejects the same staged entry;
- `pre-push` rejects a direct branch update range containing a merge-result
  protected link, and permits deletions;
- both `pre-push` and `ci-push` reject a new branch with a clean tip when an
  earlier newly published commit contains a forbidden artifact;
- a new branch from a remediated remote branch is accepted when the old bad
  commit is already reachable from the policy cutover, the cutover is
  reachable from the new tip, and the new tip is clean;
- two newly created refs sharing a forbidden-and-removed post-cutover commit
  are both rejected;
- an existing-ref range whose base contains a forbidden link and whose only
  outgoing commit removes it is accepted with a clean tip;
- the workflow invokes push-range validation for every branch push;
- root merge/handover scratch artifacts and machine-local paths are rejected;
  and
- a temporary main repository plus linked worktree runs `post-checkout`,
  produces a relative target, and resolves that target to the intended source.
- a legacy absolute link resolving to the expected main resource is migrated
  to a relative target, while an unknown symlink is preserved.

Machine-path fixtures will assemble forbidden strings from fragments at
runtime so the guard does not reject its own test source. Boundary coverage
will accept `/homebrew/...`, reject constructed `/home/<name>/...` and Windows
user-home forms, and accept a properly classified architecture document whose
filename contains `HANDOVER` below `docs/architecture/audits/`.

Static parity assertions will ensure both shell and PowerShell policy
implementations expose the new validation path.

## Error handling

Failures identify both the offending path and the reason. Policy reads fail
closed: an index/tree entry declared as a symlink whose blob cannot be read is
reported as a validation error rather than silently skipped.

The checkout hook remains idempotent and never replaces a real file or
non-empty directory.

## Remediation boundary

This change prevents recurrence. Removing the already committed links from
`next` and deciding whether to rewrite published history are separate branch
remediation operations; they are not performed implicitly by this policy
change.
