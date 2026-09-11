# Debug and Trace Test Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the S3K Data Select visual regression headless-safe and produce an evidence-backed audit of every debug- or trace-related Java test.

**Architecture:** Perform all edits and executions in a clean `/tmp` worktree based on fetched `origin/develop`. Keep the GLFW availability decision at the visual-test boundary, preserve the capture utility’s actionable failures, and record the test inventory and runtime evidence in one durable Markdown report.

**Tech Stack:** Java 21, JUnit Jupiter, LWJGL GLFW, Maven Surefire, Bash/`rg`/`awk`, Git.

## Global Constraints

- Trace fixtures and auxiliary data are comparison-only and must never hydrate engine state.
- Do not loosen trace tolerances, regenerate fixtures, or add game/zone/route/frame carve-outs.
- Discover actual `.gen` files, verify documented hashes, and pass explicit ROM properties.
- Preserve every pre-existing primary-worktree change.
- Enforce the timeout budgets in the approved design.
- Update `docs/status/trace-frontier-log.md` if a full sweep selects or moves a frontier, lands a trace fix, or regresses a passing trace.

---

### Task 1: Create the isolated implementation worktree

**Files:**
- Read: `AGENTS.md`
- Read: `.agents/skills/trace-replay-bug-fixing/SKILL.md`
- Create through Git: `/tmp/openggf-debug-trace-audit`

**Interfaces:**
- Consumes: fetched `origin/develop`
- Produces: clean branch `bugfix/ai-debug-trace-test-audit` in an isolated worktree

- [ ] **Step 1: Record the primary-worktree baseline**

Run:

```bash
git status --short --branch
git diff -- .idea/vcs.xml docs/status/rewind-round-trip-gaps.md
```

Expected: the previously identified local changes remain visible and untouched.
Also record `git rev-parse origin/develop` as `AUDIT_BASE`; every later patch range uses
this exact commit.

- [ ] **Step 2: Create the isolated worktree**

Use the `superpowers:using-git-worktrees` skill. Create a uniquely named directory under
`/tmp`, based on `origin/develop`, on branch `bugfix/ai-debug-trace-test-audit`.

- [ ] **Step 3: Verify isolation**

Run in the new worktree:

```bash
git status --short --branch
git rev-parse HEAD
git rev-parse origin/develop
```

Expected: clean status and identical commit IDs.

### Task 2: Make the visual regression headless-safe

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`
- Modify if lifecycle extraction is needed: `src/test/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectVisualCapture.java`
- Test: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

**Interfaces:**
- Consumes: LWJGL `glfwInit()`, `glfwTerminate()`, and JUnit `Assumptions.assumeTrue`
- Produces: a visual test that skips only when GLFW is unavailable and otherwise preserves the existing ROM capture and pixel assertions

- [ ] **Step 1: Reproduce RED**

Run with an enforceable 5-minute limit:

```bash
mkdir -p target/debug-trace-audit
set -o pipefail
timeout --signal=TERM --kill-after=30s 5m \
  mvn -Dtest=com.openggf.game.sonic3k.dataselect.TestS3kDataSelectPresentation#visualCapture_selectedSaveSlotShowsRightBodyRail test \
  2>&1 | tee target/debug-trace-audit/dataselect-red.log
```

Expected in the headless worktree: ERROR `Unable to initialize GLFW`.
Exit `124` means `TIMEOUT`; preserve the log and partial Surefire XML, identify the last
active test, and do not continue as though RED or GREEN was established.

- [ ] **Step 2: Implement the smallest lifecycle-safe guard**

Before `S3kDataSelectVisualCapture.main(...)`, probe GLFW in a tightly scoped helper:

```java
private static boolean canInitializeGlfw() {
    boolean initialized = glfwInit();
    if (initialized) {
        glfwTerminate();
    }
    return initialized;
}
```

Use:

```java
assumeTrue(canInitializeGlfw(), "GLFW unavailable; skipping S3K Data Select visual capture");
```

Import GLFW functions statically. If an error callback is installed for the probe, detach
and free only that callback. Do not catch exceptions from the actual capture.

- [ ] **Step 3: Verify GREEN in the headless environment**

Repeat the focused timeout command with output in `dataselect-green.log`.

Expected: exit 0 with one skipped test on headless Linux, or one passed test when GLFW is available.

- [ ] **Step 4: Run the whole containing class**

Run with the same `timeout --signal=TERM --kill-after=30s 5m` wrapper:

```bash
mvn -Dtest=com.openggf.game.sonic3k.dataselect.TestS3kDataSelectPresentation test
```

Expected: zero failures and zero errors.

- [ ] **Step 5: Commit the focused fix**

Stage only the modified Data Select test source(s) and commit with required trailers. Do
not stage generated reports or unrelated files.

### Task 3: Build the complete static inventory

**Files:**
- Create: `docs/architecture/audits/testing/debug-trace-tests.md`
- Read: `pom.xml`
- Read: matching Java sources under `src/test/java`

**Interfaces:**
- Consumes: the exact approved inventory predicate
- Produces: one audit row per matching source with selection and safety classification

- [ ] **Step 1: Generate the canonical source list**

Run:

```bash
rg --files src/test/java |
awk -F/ '{ n=$NF; sub(/\.java$/, "", n);
  if (n ~ /^(Test)?Debug/ || n ~ /^(Test)?Trace/ || n ~ /Trace/ ||
      $0 ~ /\/tests\/trace\//) print }' |
sort > target/debug-trace-test-sources.txt
```

Record the line count and include the command in the report.

- [ ] **Step 2: Extract classification evidence**

For every listed source, inspect:

```bash
rg -n '@Test|@Disabled|@Tag|@ParameterizedTest|@TestFactory|Assumptions|assumeTrue|assert[A-Z]|Assertions\\.|fail\\(|System\\.out|Files\\.(write|create)|target/|src/test/resources|docs/|glfw|\\.gen|rom\\.path' <source>
```

Also map the source against the normal and `trace-replay` Surefire include/exclude rules in
`pom.xml`.

- [ ] **Step 3: Write the durable report**

Start with summary counts, exact predicate, Maven selection rules, and definitions. Add a
table containing these columns for every source:

```text
Source | Category | Normal suite | Trace profile | Annotations |
Requirements | Assertions/strength | Side effects | Execution | Command |
Outcome | Issue/fix
```

Categories must be exactly: `UNIT`, `TRACE_REPLAY`, `DIAGNOSTIC_PROBE`,
`VISUAL_MANUAL`, `HELPER_ABSTRACT`, or `SUSPICIOUS`.

- [ ] **Step 4: Validate inventory completeness**

Extract report source links and compare them against
`target/debug-trace-test-sources.txt`. The two sorted sets must be identical with no
duplicates.

- [ ] **Step 5: Commit the inventory draft**

Stage only `docs/architecture/audits/testing/debug-trace-tests.md` and commit with required documentation
trailers.

### Task 4: Execute matching normal-suite tests

**Files:**
- Modify: `docs/architecture/audits/testing/debug-trace-tests.md`
- Runtime output: `target/debug-trace-audit/normal-suite.log`

**Interfaces:**
- Consumes: normal-Surefire-selected matching classes from Task 3
- Produces: per-class and aggregate normal-suite outcomes

- [ ] **Step 1: Record pre-run status**

Run `git status --short` and save it with the command log.

- [ ] **Step 2: Run the matching normal tests**

Construct a comma-separated `-Dtest=` list from matching classes that normal Surefire
selects. Derive each fully qualified class name from its declared `package` plus top-level
class name, exclude interfaces/helpers without JUnit executable annotations, and reject
duplicate FQCNs. Run with:

```bash
set -o pipefail
timeout --signal=TERM --kill-after=30s 90m \
  mvn "-Dtest=<comma-separated-FQCN-list>" test \
  2>&1 | tee target/debug-trace-audit/normal-suite.log
```

Exit `124` is `TIMEOUT`; retain logs and partial XML and investigate the last active test.
After completion, reconcile the requested FQCN list against Surefire XML suite names and
require every executable class to appear with `tests > 0`.

Expected green criterion: zero failures and zero errors; the visual test may skip through
its GLFW assumption.

- [ ] **Step 3: Inspect reports and side effects**

Aggregate Surefire XML counts. Compare `git status`, tracked diffs, and untracked paths
against the baseline. Attribute every new path. Restore only generated changes proven to
come from this command.

- [ ] **Step 4: Update the audit**

Record command, duration, test/failure/error/skip totals, per-source outcome, output-volume
issues, and any side effect.

### Task 5: Execute trace replay groups and the full profile

**Files:**
- Modify: `docs/architecture/audits/testing/debug-trace-tests.md`
- Modify conditionally: `docs/status/trace-frontier-log.md`
- Runtime output: `target/debug-trace-audit/trace-*.log`
- Runtime reports: `target/trace-reports/`

**Interfaces:**
- Consumes: verified ROM paths and `mvn -Ptrace-replay`
- Produces: game-group results, first-divergence evidence, and full-profile status

- [ ] **Step 1: Discover and verify ROMs**

Search the primary project root read-only for `.gen` files because ignored ROMs are not
present in the `/tmp` worktree. Compute CRC32 and SHA-1 there, compare against `AGENTS.md`,
and record only absolute paths and hashes—never ROM contents. Pass those absolute primary
paths into Maven commands executed from the isolated worktree.

- [ ] **Step 2: Run S1, S2, and S3K groups**

For each game, derive FQCNs from declared packages and top-level executable class names,
excluding abstract/helper sources. Reject duplicate FQCNs. Use the exact property mapping:
S1 → `sonic1.rom.path`, S2 → `sonic2.rom.path`, S3K → `s3k.rom.path`. Run the applicable
one of:

```bash
set -o pipefail
timeout --signal=TERM --kill-after=30s 90m \
  mvn -Ptrace-replay "-Dtest=<s1-class-list>" "-Dsonic1.rom.path=<verified-s1-absolute-path>" test \
  2>&1 | tee target/debug-trace-audit/trace-s1.log
timeout --signal=TERM --kill-after=30s 90m \
  mvn -Ptrace-replay "-Dtest=<s2-class-list>" "-Dsonic2.rom.path=<verified-s2-absolute-path>" test \
  2>&1 | tee target/debug-trace-audit/trace-s2.log
timeout --signal=TERM --kill-after=30s 90m \
  mvn -Ptrace-replay "-Dtest=<s3k-class-list>" "-Ds3k.rom.path=<verified-s3k-absolute-path>" test \
  2>&1 | tee target/debug-trace-audit/trace-s3k.log
```

Record totals and reconcile requested executable FQCNs against Surefire XML, requiring
`tests > 0` for each. Exit `124` is `TIMEOUT`; retain logs and partial reports and
investigate the last active test. For every failure, run the documented `TraceTriageTool`
command and record first-error frame, field, expected value, actual value, and owning
subsystem.

- [ ] **Step 3: Fix only confirmed implementation/test defects**

For a new unexpected failure, follow `trace-replay-bug-fixing` Phase 1 through root cause
and document it. Stop the audit fix loop and create a separate design/plan for any engine
or trace-harness behavior change, including disassembly references, cross-game checks,
TDD steps, focused verification, review, and its own commit. After that reviewed fix is
applied to the primary worktree, use the exact reviewed commit from the separate isolated
fix branch as the new `AUDIT_BASE`; applying the patch to the dirty primary worktree does
not create or require a primary-worktree commit. Recreate the isolated audit worktree
directly from that reviewed fix commit and reapply only the audit allowlist commits
(Data Select guard and audit documents). Verify
`git diff --name-only AUDIT_BASE..HEAD` contains only the Task 7 allowlist, then rerun the
affected group. Do not merge the separate fix commit into the audit patch range, and do
not make open-ended engine fixes inside this audit plan.

- [ ] **Step 4: Run the full trace profile**

Run with a 4-hour limit and every verified available ROM property:

```bash
set -o pipefail
timeout --signal=TERM --kill-after=30s 4h \
  mvn -Ptrace-replay \
    "-Dsonic1.rom.path=<verified-s1-absolute-path>" \
    "-Dsonic2.rom.path=<verified-s2-absolute-path>" \
    "-Ds3k.rom.path=<verified-s3k-absolute-path>" test \
  2>&1 | tee target/debug-trace-audit/trace-full.log
```

Expected green criterion: zero unexpected failures. Known parity gaps must remain explicitly
classified and require user direction before they can be treated as accepted. Report
ROM-caused skips separately. Exit `124` follows the same timeout evidence procedure.

- [ ] **Step 5: Apply frontier documentation obligations**

If the sweep selects/moves a frontier, lands a trace fix, or regresses a passing trace,
update `docs/status/trace-frontier-log.md` with command, branch/worktree commit, pass/fail, error
count, and first-error frame/field.

### Task 6: Execute safe excluded diagnostics and probes

**Files:**
- Modify: `docs/architecture/audits/testing/debug-trace-tests.md`
- Runtime output: `target/debug-trace-audit/diagnostic-*.log`

**Interfaces:**
- Consumes: excluded `Debug*`, `*Debug*`, and `*Probe*` classifications
- Produces: evidence for every safe excluded executable

- [ ] **Step 1: Review execution safety**

Do not execute a class that intentionally fails, requires an unavailable display/native
tool, writes outside `target/`, or performs fixture regeneration. Mark the precise reason
in the report.

- [ ] **Step 2: Execute safe classes individually**

Create an audit-only `pom.xml` change in the isolated worktree that removes the normal
`tests/trace/**` exclusions and the trace-profile `Debug*`/`Probe*` exclusions. Record the
exact pre-edit blob ID and never commit or transfer this temporary Maven edit. Run each
approved class with:

```bash
set -o pipefail
timeout --signal=TERM --kill-after=30s 30m \
  mvn "-Dtest=<fully-qualified-class>" test \
  2>&1 | tee target/debug-trace-audit/diagnostic-<class>.log
```

Pass verified absolute ROM properties when required. For every invocation, require a
matching Surefire XML suite with `tests > 0`; zero executed tests is a failed audit result,
never green. Exit `124` is `TIMEOUT` with retained logs and partial XML. Restore `pom.xml`
to the recorded blob after this execution group and prove it has no diff.

- [ ] **Step 3: Record assertion and output quality**

Classify pass, skip, expected diagnostic failure, timeout, or confirmed defect. Flag tests
that only print diagnostics, produce excessive output, have no meaningful assertion, or
are misleadingly named.

- [ ] **Step 4: Route confirmed cleanup separately**

Document concrete issues involving `@Disabled`, class names, Maven selection, headless
handling, or output behavior, but do not edit those additional sources in this audit
branch. Create a separate reviewed design/plan for each coherent cleanup set, then resume
the audit using the same sequence as Task 5: apply the reviewed fix patch to the primary
worktree, set the exact isolated fix commit as the new `AUDIT_BASE`, recreate the audit
worktree from it, and reapply only audit-allowlist commits. This keeps the transfer
allowlist fixed and evidence-backed.

### Task 7: Final verification, review, and handoff

**Files:**
- Modify: `docs/architecture/audits/testing/debug-trace-tests.md`
- Modify conditionally: `docs/status/trace-frontier-log.md`

**Interfaces:**
- Consumes: all prior commits and runtime evidence
- Produces: independently reviewed implementation and a safe primary-worktree patch

- [ ] **Step 1: Run fresh verification**

Run the focused Data Select class, matching normal test group, affected diagnostic tests,
and the final applicable trace groups. Record fresh exit codes and counts.

- [ ] **Step 2: Self-review the audit**

Verify inventory set equality, scan for missing outcomes and placeholders, check every
implemented fix has evidence, and run `git diff --check`.

- [ ] **Step 3: Request independent implementation review**

First stage and commit the completed `docs/architecture/audits/testing/debug-trace-tests.md` and any
obligatory `docs/status/trace-frontier-log.md` update with the required documentation trailers.
Verify the worktree is clean except ignored runtime output.

Provide the reviewer the approved design, this plan, all commits/diffs, and verification
logs. Fix every blocking/material finding and re-submit until the reviewer reports GREEN.
Commit each review-driven correction before re-review so reviewed branch `HEAD` contains
the complete deliverable.

- [ ] **Step 4: Transfer reviewed changes**

Use the `AUDIT_BASE` captured in Task 1 and reviewed branch `HEAD`. The transfer allowlist
is:

```text
src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java
src/test/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectVisualCapture.java
docs/architecture/audits/testing/debug-trace-tests.md
docs/status/trace-frontier-log.md
```

Omit allowlisted files not changed by `AUDIT_BASE..HEAD`. Verify the reviewed commit range
with `git log --oneline AUDIT_BASE..HEAD` and `git diff --name-only AUDIT_BASE..HEAD`;
reject any changed path outside the allowlist. Produce a binary-capable patch for exactly
`AUDIT_BASE..HEAD` and the allowlist. In the primary worktree, verify each changed
allowlisted path has no pre-existing diff or untracked collision, apply the patch, and
compare complete status/diff against the original baseline. Stop for user direction on
any overlap or rejected hunk.

- [ ] **Step 5: Report actual status**

Summarize fixes, inventory counts, executed/skipped/unsafe groups, remaining known trace
gaps, exact verification commands, and preserved unrelated worktree changes.
