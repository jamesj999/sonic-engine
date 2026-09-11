# Worktree Resource Link Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` (recommended) or
> `superpowers:executing-plans` to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent worktree-generated links, machine-local paths, and transient
merge handoffs from being committed or pushed, including through merge commits
and new remote branches.

**Architecture:** Generate filesystem-only worktree links with relative
targets. Extend the existing shell/PowerShell policy pair with one shared
candidate model for staged entries, commit deltas, pushed ranges, and tip-tree
cleanliness. Wire the policy into commit, merge, push, and GitHub Actions paths,
with functional temporary-repository tests.

**Tech Stack:** POSIX shell, PowerShell, Git plumbing commands, GitHub Actions,
Java 21, JUnit 5.

## Global Constraints

- Generated resource links must never appear in a Git tree.
- Newly published machine-local home paths are rejected.
- Existing legitimate relative symlinks remain permitted.
- Shell and PowerShell policy behavior must remain equivalent.
- New-branch validation uses the fixed worktree-resource-policy cutover as a
  stable boundary and rejects branches that do not contain it.
- `AGENTS.md` and `CLAUDE.md` remain byte-for-byte synchronized when changed.
- Do not bypass repository hooks with `--no-verify`.

---

### Task 1: Add failing policy and hook regression tests

**Files:**

- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

**Interfaces:**

- Consumes: `.githooks/validate-policy.sh` command-line modes and standard Git
  hook input formats.
- Produces: temporary-repository helpers and regression tests that define the
  policy contract.

- [ ] **Step 1: Add temporary Git repository helpers**

Add helpers that initialize repositories with deterministic local identity,
write/stage files and symlinks, create commits and merges, invoke policy scripts
from the OpenGGF checkout, and capture exit code/output. Construct forbidden
home paths from string fragments at runtime so test source does not contain a
literal forbidden path. Protected resources that are intentionally ignored must
be staged with `git add -f -- <path>`; the helper must assert that
`git ls-files --stage` reports mode `120000` before invoking the policy.

- [ ] **Step 2: Add staged-index RED tests**

Add tests proving:

- a protected relative `docs/skdisasm` symlink is rejected;
- an unprotected absolute POSIX or Windows-user-home symlink is rejected;
- an unprotected relative symlink is accepted;
- `/homebrew/example` is accepted as ordinary text;
- constructed POSIX and Windows user-home paths in added text are rejected;
- root `MERGE-STATUS-incident.md` and `HANDOVER-incident.md` are rejected; and
- a document below `docs/architecture/audits/` is accepted.

Add an ignore regression proving every disassembly path is ignored both when it
is a real directory and when it is a symlink.

- [ ] **Step 3: Add merge RED tests**

Create a real conflicted/in-progress merge fixture, stage a generated resource
link in the resolution, and assert both `pre-commit` policy mode and
`commit-msg` with `MERGE_HEAD` reject it.

- [ ] **Step 4: Add push-range RED tests**

Create temporary bare remotes and histories proving:

- an existing-branch update containing a bad merge is rejected;
- a new branch with a clean tip but an earlier uniquely unpublished bad commit
  is rejected by `pre-push` and `ci-push`;
- a new branch based on a clean, remediated remote branch accepts pre-cutover
  old bad history;
- two peer new refs sharing a post-cutover bad-and-removed commit are rejected;
- an existing range that only removes a base violation is accepted; and
- a deleted ref is accepted.

- [ ] **Step 5: Add functional checkout-hook RED test**

Create a temporary main repository and linked worktree, invoke
`.githooks/post-checkout`, and assert the created `docs/skdisasm` link target is
relative and resolves to the main repository source.

- [ ] **Step 6: Verify RED**

Run:

```bash
mvn -Dtest=TestBuildToolingGuard test
```

Expected: the new tests fail because relative link generation, staged resource
validation, merge validation, pre-push, and new-branch CI behavior do not yet
exist.

### Task 2: Make generated worktree links relative and unstageable by default

**Files:**

- Modify: `.githooks/post-checkout`
- Modify: `.gitignore`

**Interfaces:**

- Consumes: `git rev-parse --path-format=relative --git-common-dir`.
- Produces: relative symlink targets for worktree resources.

- [ ] **Step 1: Change disassembly ignore patterns**

Replace the five trailing-slash disassembly patterns with root-anchored patterns
that match both directories and symlinks.

- [ ] **Step 2: Implement relative target derivation**

From each destination parent, obtain the relative common Git directory, verify
its basename is `.git`, strip that component to obtain the relative main-tree
root, and pass `<relative-main>/<resource-path>` to `ln -s`. Retain absolute
paths only for source existence checks.

Migrate an existing absolute symlink only when it resolves to the expected main
resource. Prepare the relative replacement before removing the legacy link,
restore the old target if installation fails, and preserve relative, broken,
or unknown symlinks. Add both migration and preservation regressions.

- [ ] **Step 3: Run focused tests**

Run:

```bash
mvn -Dtest=TestBuildToolingGuard test
```

Expected: checkout-hook and ignore tests pass; policy-mode tests remain red.

### Task 3: Implement staged, commit-range, and push policy in shell

**Files:**

- Modify: `.githooks/validate-policy.sh`
- Add: `.githooks/pre-commit`
- Add: `.githooks/pre-push`
- Modify: `.githooks/commit-msg`

**Interfaces:**

- Produces modes:
  `pre-commit`, `commit-msg`, `pre-push`, `ci-pr`, and `ci-push`.
- Consumes pre-push stdin rows:
  `<local-ref> <local-oid> <remote-ref> <remote-oid>`.

- [ ] **Step 1: Implement candidate and blob helpers**

Use cached `--no-renames --diff-filter=AMT` candidates for local commits. Query
index/tree modes and symlink blobs with Git plumbing. Implement protected-path,
absolute-link, root-scratch, and machine-local-text predicates with actionable
path-specific errors.

In both shell and PowerShell, match concrete Windows user-home paths using
forward, backward, mixed, and doubled separators. Exercise all forms through
each available policy runtime and enforce identical predicate constants. Keep
environment-variable and neutral-placeholder username components accepted.

- [ ] **Step 2: Validate before merge early returns**

Call staged-content validation before `commit-msg` handles `MERGE_HEAD`, and
expose the same validation through `pre-commit`.

- [ ] **Step 3: Implement commit-delta validation**

For every commit in a supplied range, compare it to its first parent (empty
tree for root), validate added/modified entries including merge-result
additions, and independently validate the delivered tip tree for protected and
absolute symlinks.

- [ ] **Step 4: Implement pre-push range selection**

For existing refs validate `<remote-oid>..<local-oid>`. For a new ref validate
`rev-list <local-oid> --not --remotes=<remote-name>`. Skip deletion updates and
fail closed when the remote name or required objects cannot be resolved.

- [ ] **Step 5: Implement CI new-ref selection**

For an all-zero `before` OID, require the fixed resource-policy cutover commit
`ccdd33edf4f9cd4a7937791f1d4c2f37cbeeb5e0` to be an ancestor of the pushed
tip and validate the complete `<cutover>..<tip>` range. Require identical shell
and PowerShell constants.

- [ ] **Step 6: Run focused tests**

Run:

```bash
mvn -Dtest=TestBuildToolingGuard test
```

Expected: shell-policy, merge, pre-push, and new-branch tests pass.

### Task 4: Implement PowerShell parity and all-branch CI

**Files:**

- Modify: `.githooks/validate-policy.ps1`
- Modify: `.githooks/run-policy`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/release.yml`
- Modify: `src/test/java/com/openggf/tests/TestBuildToolingGuard.java`

**Interfaces:**

- PowerShell modes and errors match the shell policy.
- GitHub Actions fetches full history so the fixed cutover object is available.

- [ ] **Step 1: Port candidate and path policy to PowerShell**

Implement the same Git-command candidate selection, mode/blob inspection,
protected path rules, Git text classification, commit-delta validation, and
tip-tree validation.

Retain case-sensitive Git ref identity while mirroring the shell path predicate.

- [ ] **Step 2: Port pre-push and new-ref range selection**

Parse standard-input updates and mirror shell existing/new/deletion behavior.

- [ ] **Step 3: Wire all-branch push CI**

Run `ci-push` for every branch push and fetch full history so the fixed cutover
object is available. For new refs, use only the cutover constant and tip;
fetched peer refs must not influence range selection. Retain existing PR and
release validation. Keep the all-branch push policy as a lightweight job/step
with conditions that do not trigger the full Maven suite on every branch push.

- [ ] **Step 4: Add parity assertions**

Extend `TestBuildToolingGuard` to require both policy implementations, tracked
executable hook dispatchers, all-branch workflow wiring, and matching policy
constants/predicates, including the exact cutover OID.

- [ ] **Step 5: Run focused tests**

Run:

```bash
mvn -Dtest=TestBuildToolingGuard test
```

Expected: all focused tests pass.

### Task 5: Document, verify, and prepare integration

**Files:**

- Modify: `docs/agent-workflow/README.md`
- Modify: `README.md`
- Retain:
  `docs/architecture/designs/2026-07-29-worktree-resource-link-policy-design.md`
- Retain:
  `docs/architecture/audits/2026-07-29-next-merge-broad-staging-audit.md`
- Retain:
  `docs/architecture/plans/2026-07-29-worktree-resource-link-policy.md`

**Interfaces:**

- Produces contributor-facing explanation of filesystem-only worktree links and
  policy failure remediation.

- [ ] **Step 1: Document the policy**

Explain relative worktree links, forbidden generated-resource entries,
machine-local-path handling, merge validation, and pre-push/new-branch
behavior. Add the required branch summary to `README.md`.

Before enabling the content guard, redact machine-specific literals in all new
design, plan, and audit documents to `$HOME`, `<user>`, or equivalent neutral
forms. Run the staged policy against the complete documentation set.

- [ ] **Step 2: Run formatting and policy checks**

Run:

```bash
git diff --check
mvn -Dtest=TestBuildToolingGuard test
```

Expected: both commands pass.

- [ ] **Step 3: Run the full suite under JDK 21**

Confirm `mvn -v` reports Java 21, then run:

```bash
mvn test
```

Expected: no new failure relative to the updated `develop` baseline.

- [ ] **Step 4: Commit intentionally**

Stage only the files listed in this plan, inspect `git diff --cached --stat` and
`git diff --cached`, then commit with all required documentation trailers.

- [ ] **Step 5: Follow repository integration workflow**

Fetch and fast-forward the main `develop` workspace without disturbing user
changes, record baseline full-suite results, merge the implementation branch
into `develop`, rerun focused and full verification, push `develop`, then
remove the clean merged worktree and delete its fully merged local branch.
