# Cross-Branch Red-Suite Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate every in-scope red on `develop` and `next`, while retaining an explicit 96-test unfinished-S&K exclusion catalogue and implementing shared fixes only once on `develop`.

**Architecture:** Execute the develop and next plans serially. Each leaf task is a coherent root-cause fix with isolated reproduction, TDD, a focused commit, spec review, and code-quality review; shared work is forward-integrated from develop before the next-only inventory is recalculated.

**Tech Stack:** Java 17, Maven, JUnit 5, Git worktrees, Surefire XML reports, OpenGGF rewind/physics/object/profile/runtime frameworks.

---

## File map

- `docs/architecture/designs/2026-07-20-cross-branch-red-suite-remediation-design.md`: approved scope, exclusions, branch flow, and success gates.
- `docs/architecture/plans/2026-07-20-develop-red-suite-remediation.md`: executable D1-D3 tasks for all 36 develop reds.
- `docs/architecture/plans/2026-07-20-next-red-suite-remediation.md`: executable N1-N4 tasks for the current upper bound of 47 next-only reds.
- `docs/architecture/audits/testing/red-suite-inventory.tsv`: tracked ledger produced during execution with columns `branch`, `test`, `kind`, `isolated_result`, `owner`, `wave`, `status`, `disposition`.
- `docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt`: exact 96-test exclusion allowlist; comments identify zone owner and incompleteness reason.
- `tools/testing/Compare-SurefireRedSet.ps1`: deterministic Surefire XML normalizer and exact red-set comparator.
- `tools/testing/Test-CompareSurefireRedSet.ps1`: self-contained fixture harness for comparator success and failure modes.

The 2026-07-21 `origin/develop` merges do not add red Surefire method rows. The first adds eight CNZ object-class diagnostics inside the two existing D1.4 rewind guard rows; the later merge at `6af04f87e` adds three CNZ touch-profile diagnostics plus three `GameLoop` size diagnostics inside three already-catalogued guard methods. `docs/architecture/audits/testing/red-suite-inventory.tsv` records these isolated diagnostics on the existing rows, while develop Tasks D1.4a, D1.5a, and D1.11a own their remediation. Consequently the frozen historical method counts remain 179 union, 36 develop, and 83 in scope; implementation must additionally reach a zero-tail current CNZ sweep and restore every unchanged architecture budget before the develop branch gate.

### Task 0: Protect the primary checkouts and record immutable baselines

**Files:**
- Do not modify: `C:\Users\farre\IdeaProjects\sonic-engine`
- Do not modify: `C:\Users\farre\IdeaProjects\sonic-engine-next`

- [ ] **Step 1: Record primary checkout state**

Run `git status --short --branch` and `git rev-parse HEAD` in both primary checkouts. Expected: both may be dirty; preserve every user-owned modification.

- [ ] **Step 2: Verify local worktree isolation**

Run from the repository root:

```powershell
git check-ignore .worktrees
git worktree list --porcelain
```

Expected: `.worktrees` is ignored and the develop implementation worktree is `C:\Users\farre\IdeaProjects\sonic-engine-next\.worktrees\red-suite-remediation-plan` at its recorded develop-derived SHA.

- [ ] **Step 3: Forbid primary-checkout builds and edits**

All subsequent Maven, edit, and commit commands run only in dedicated worktrees. Create detached clean baseline worktrees from the recorded develop and merged-next SHAs when historical reports must be regenerated; create distinct `bugfix/ai-*` worktrees for implementation.

### Task 1: Freeze the measured inventory and exclusion set

**Files:**
- Create: `docs/architecture/audits/testing/red-suite-inventory.tsv`
- Create: `docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt`
- Create: `tools/testing/Compare-SurefireRedSet.ps1`
- Create: `tools/testing/Test-CompareSurefireRedSet.ps1`
- Test: `target/surefire-reports/TEST-*.xml`

- [ ] **Step 1: Regenerate the branch reports without changing test selection**

Run on the develop implementation worktree:

```powershell
mvn test
```

Expected: the known baseline reports 36 red `class#method` identities before fixes.

Run on the preserved merged-next baseline or a clean worktree at `e128f3a24`:

```powershell
mvn test
```

Expected: the known baseline reports 170 red identities before fixes.

- [ ] **Step 2: Write the comparator fixture harness and prove it red**

The harness creates temporary Surefire XML and allowlists for: exact match, duplicate identity, missing row, extra row, malformed XML, and unexpected skipped/disabled testcase. It invokes the comparator as a child `pwsh` process and asserts exit 0 only for exact match.

Run before implementing the comparator:

```powershell
pwsh -File tools/testing/Test-CompareSurefireRedSet.ps1
```

Expected: FAIL because `Compare-SurefireRedSet.ps1` does not exist.

- [ ] **Step 3: Implement the comparator and prove the harness green**

Implement a PowerShell script accepting `-ReportsPath`, optional `-ExpectedPath`, and optional `-WriteActualPath`. It must parse every `TEST-*.xml`, emit `classname#testcase.name` for each `<failure>` or `<error>`, retain duplicates long enough to reject them, sort ordinally, and compare the exact multiset to the allowlist. Exit nonzero on duplicates, missing/stale allowlist rows, extras, parse errors, or unexpected skipped/disabled tests. Treat accounting identities and executable Surefire selectors as different concepts: parameterized display names are valid accounting identities but are never emitted as `-Dtest` selectors.

Run the harness again. Expected:

```text
PASS exact-match
PASS duplicate-rejected
PASS missing-rejected
PASS extra-rejected
PASS malformed-rejected
PASS skipped-rejected
```

Then process the real report:

```powershell
pwsh -File tools/testing/Compare-SurefireRedSet.ps1 -ReportsPath target/surefire-reports -WriteActualPath target/red-set.txt
```

Expected: `target/red-set.txt` contains the exact normalized current red set and the script prints its failure/error/skipped totals.

- [ ] **Step 4: Materialize the inventory**

Parse every Surefire XML `testcase` containing `failure` or `error`, normalize it as `fully.qualified.Class#method`, and write one TSV row per identity. Classify the exact union as:

```text
develop unique reds: 36
merged next unique reds: 170
shared identities: 27
union identities: 179
unfinished-S&K exclusions: 96
in-scope union: 83
```

Expected: duplicate identities fail the inventory check rather than being silently collapsed.

- [ ] **Step 5: Write the exact exclusion allowlist**

Include only tests whose behavior is owned solely by unfinished MHZ, FBZ, SOZ, or later S&K gameplay, trace, or zone-evidence tooling. Do not include architecture, rewind, singleton-closure, Mod API, service ownership, PatternAtlas, or other cross-cutting guards merely because a diagnostic names an unfinished-zone class or FBZ-named tool.

- [ ] **Step 6: Verify the accounting invariant**

Run a PowerShell comparison of the two files and Surefire reports. Expected:

```text
179 union - 96 excluded = 83 in scope
36 develop + 47 next-only = 83 in scope
N1 20 + N2 10 + N3 7 + N4 10 = 47
```

- [ ] **Step 7: Commit the catalogue and comparator**

```powershell
git add docs/architecture/audits/testing/red-suite-inventory.tsv docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt tools/testing/Compare-SurefireRedSet.ps1 tools/testing/Test-CompareSurefireRedSet.ps1
git commit -m "test: catalogue cross-branch red suite"
```

Use the repository trailer block; `Changelog: n/a: test inventory only` is appropriate.

### Task 2: Execute and review the develop plan

**Files:**
- Execute: `docs/architecture/plans/2026-07-20-develop-red-suite-remediation.md`

Run the origin-integration Tasks D1.4a, D1.5a, and D1.11a at their stated points before leaving the rewind/architecture wave. Their nested CNZ/profile/source-size diagnostics are in scope even though they do not increase the original 36 red-method total.

- [ ] **Step 1: Dispatch exactly one fresh implementer for the next unchecked leaf task**

Paste the complete leaf-task text into the implementer prompt. Require `superpowers:test-driven-development`, worktree path, current base SHA, required trailers, and a commit SHA in the report.

- [ ] **Step 2: Loop spec review until green**

Dispatch a fresh spec reviewer after the implementer reports DONE. If it finds any missing or extra behavior, send the findings to the same implementer, require a fix commit, and re-dispatch the reviewer. Do not start quality review until the response is exactly spec-compliant.

- [ ] **Step 3: Loop code-quality review until green**

Dispatch a fresh code-quality reviewer with base/head SHAs. Send every Critical or Important issue to the implementer and re-review after the fix. Minor issues may remain only when the reviewer explicitly marks the task approved.

- [ ] **Step 4: Repeat serially**

Do not run implementation subagents in parallel. Continue until every D1-D3 leaf task is checked and committed.

### Task 3: Prove develop green twice

**Files:**
- Update: `docs/architecture/audits/testing/red-suite-inventory.tsv`
- Update when required: `CHANGELOG.md`
- Update only if a trace frontier moves: `docs/status/trace-frontier-log.md`

- [ ] **Step 1: Run affected guard and cross-game batches**

Use the exact commands from the develop plan. Expected: zero failures and errors.

- [ ] **Step 2: Run the complete develop suite twice**

```powershell
mvn test
mvn test
```

Expected for each run: zero failures and zero errors. Record both report timestamps and totals in the inventory.

- [ ] **Step 3: Dispatch an entire-change reviewer**

Review the develop remediation base-to-head diff for spec compliance, parity carve-outs, test weakening, baseline masking, and branch-policy documentation. Fix and re-review until approved.

### Task 4: Forward-integrate develop into next and rebase the inventory

**Files:**
- Update: `README.md` if the merge targets `develop` under branch policy
- Update: `docs/architecture/audits/testing/red-suite-inventory.tsv`

- [ ] **Step 1: Create or refresh an isolated next remediation worktree**

Use `.worktrees/`, verify it is ignored with `git check-ignore`, and never edit `C:\Users\farre\IdeaProjects\sonic-engine-next` because it contains user-owned dirty files.

- [ ] **Step 2: Record and merge the exact verified develop remediation head**

Record `git rev-parse HEAD` after the two green develop runs. Use a normal merge commit of that exact SHA into the isolated next remediation branch based on the recorded local merged-next SHA. Resolve only known remediation overlaps; stop if a conflict touches unrelated user work. Do not publish either branch in this execution.

- [ ] **Step 3: Run the unfiltered next suite once and recalculate N1-N4**

```powershell
mvn test
```

Expected: shared reds are gone. Replace the 47-test upper bound with the observed next-only set; do not keep tasks for tests that are already green.

### Task 5: Execute and review the next plan

**Files:**
- Execute: `docs/architecture/plans/2026-07-20-next-red-suite-remediation.md`

- [ ] **Step 1: Apply the same serial implementer → spec-review → quality-review loop**

Implement only identities still red after forward integration. If a next failure proves to belong to shared code, stop that leaf task, implement it on develop, verify develop twice, and merge forward again.

- [ ] **Step 2: Keep exclusions exact**

Do not disable excluded tests or add Maven selection rules. They remain red only in the unfiltered run and must match the catalogue exactly.

### Task 6: Prove both targets meet the success gate

**Files:**
- Update: `docs/architecture/audits/testing/red-suite-inventory.tsv`

- [ ] **Step 1: Run the unfiltered next suite twice and compare each report**

```powershell
mvn test
pwsh -File tools/testing/Compare-SurefireRedSet.ps1 -ReportsPath target/surefire-reports -ExpectedPath docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt
mvn test
pwsh -File tools/testing/Compare-SurefireRedSet.ps1 -ReportsPath target/surefire-reports -ExpectedPath docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt
```

Expected after each run: the normalized failure/error multiset equals `docs/architecture/audits/testing/unfinished-sk-zone-red-exclusions.txt` exactly, with no missing/stale entry, extra, duplicate, or newly skipped/disabled test.

- [ ] **Step 2: Re-run develop twice at its final head**

Expected: both complete runs remain fully green after any shared fix discovered during next execution.

- [ ] **Step 3: Dispatch final review and hand off unpublished branches**

Run a final base-to-head review for both branches and fix/re-review until green. Report both unpublished branch names and SHAs. If the user later requests publication, use `superpowers:finishing-a-development-branch` in a separate session per target branch; do not push or create a PR in this execution.
