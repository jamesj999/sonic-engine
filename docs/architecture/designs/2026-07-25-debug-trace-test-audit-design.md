# Debug and Trace Test Audit Design

## Goal

Make the S3K Data Select visual regression test safe on headless systems, then
inventory and evaluate every debug- or trace-related Java test without weakening
trace comparison contracts or changing fixtures.

## Scope

The audit covers:

- every Java source selected by this reproducible predicate:
  `rg --files src/test/java | awk -F/ '{ n=$NF; sub(/\.java$/, "", n);
  if (n ~ /^(Test)?Debug/ || n ~ /^(Test)?Trace/ || n ~ /Trace/ ||
  $0 ~ /\/tests\/trace\//) print }'`;
- Maven Surefire selection and exclusion rules affecting those sources.

Production classes named `Debug*` or `Trace*` are outside the inventory except
where a test's contract or selection depends on them.

## Approach

### Headless visual regression

The test
`TestS3kDataSelectPresentation.visualCapture_selectedSaveSlotShowsRightBodyRail`
will probe GLFW availability before invoking the manual capture utility. If GLFW
cannot initialize, JUnit will report the test as skipped. On a machine with a
working graphics platform, the existing capture and pixel assertions will run
unchanged.

The availability check must release any GLFW state it acquires. It must not
catch arbitrary failures from the capture utility, because ROM, rendering, or
pixel-regression failures remain actionable.

The check will use a tightly scoped lifecycle owned by the test class. It will
not terminate GLFW state owned by another test and will restore any error
callback it installs. It must remain safe under Surefire's four-fork execution;
no JVM shares GLFW state with another fork.

### Static inventory

Each matching source will be classified as one of:

1. normal unit or contract test;
2. trace replay;
3. diagnostic or probe;
4. visual or manual utility;
5. helper or abstract fixture;
6. suspicious or misconfigured.

The audit will record Maven selection, JUnit annotations, external requirements
(ROM, display, native tools), assertion strength, output volume, and filesystem
side effects in `docs/architecture/audits/testing/debug-trace-tests.md`. Each inventory row
will include source, category, normal-suite selection, trace-profile selection,
annotations, requirements, side effects, execution decision, command, outcome,
and issue or fix reference. Existing generated and untracked workspace files
will not be altered.

### Runtime verification

Verification is split into three groups so failures retain their meaning:

1. matching tests selected by normal `mvn test`;
2. the `trace-replay` Maven profile;
3. excluded `Debug*` and `*Probe*` classes, executed individually only when
   their source shows that doing so is safe.

Manual utilities or tests designed to intentionally fail will be documented
rather than forced into the normal suite. ROM-dependent tests may skip when the
required ROM is absent. Trace fixtures remain comparison-only and will not be
regenerated.

Before ROM-backed execution, discover `.gen` files from the project root,
identify the game from the actual filename, verify the documented CRC32/SHA-1,
and pass the discovered path through the relevant Maven system property. Never
rename, copy, delete, or symlink a ROM to satisfy a command.

Trace replay execution will be grouped by game before any full profile sweep,
with command output retained under `target/`. A failing group must record the
first-error frame and field from its divergence report. Enforce wall-clock
limits with the command runner: 5 minutes for a focused unit or visual test,
20 minutes for one trace replay class, 90 minutes for one game-group sweep,
4 hours for the complete trace profile, and 30 minutes for an individually
approved debug or probe class. On timeout, terminate Maven, retain its output
and partial Surefire reports, classify the result as `TIMEOUT`, and investigate
the last active test before retrying. A timeout is never a pass.

### Worktree safety

Implementation and runtime audit commands will run in a clean isolated
worktree based on the fetched `origin/develop`, leaving the user's current
dirty worktree untouched. Before every execution group, record `git status`
and tracked/untracked baselines. Afterward, compare status and diffs, attribute
new paths to the command, and restore only changes proven to have been produced
by that command. A test known to write into tracked repository paths will be
run only in the isolated worktree.

After implementation review, produce a patch containing only design-approved
paths and apply it to the primary worktree. First verify that none of those
paths has a pre-existing primary-worktree edit; stop for user direction if any
overlap exists. Re-check the complete primary-worktree diff afterward to prove
unrelated dirty files were not changed. Do not merge, reset, or broadly check
out the primary worktree.

### Green criteria and review loop

“Green” is evaluated separately for each deliverable:

- design and plan review: no blocking or material reviewer findings;
- focused visual regression: passes with GLFW or is reported skipped by a
  JUnit assumption when GLFW is unavailable;
- normal suite: zero failures and zero errors;
- trace groups and full profile: zero unexpected failures. Every remaining
  known parity failure must be reproduced, classified with first-error
  evidence, and presented for explicit user direction; it cannot be silently
  called green;
- excluded diagnostics/probes: every safe test has a recorded pass, skip,
  expected diagnostic failure, or confirmed defect. Intentionally failing
  utilities are green only as classified diagnostics and remain excluded;
- implementation review: no blocking or material findings, followed by fresh
  verification of affected commands.

After each review, concrete findings will be fixed and submitted to a fresh
review turn until the reviewer reports green.

## Constraints

- Do not hydrate engine state from trace data.
- Do not loosen trace tolerances or comparisons.
- Do not add game, zone, route, or frame carve-outs.
- Do not regenerate or modify committed trace fixtures.
- Runtime artifacts must stay under `target/` or an existing documented scratch
  location.
- Preserve all pre-existing worktree changes.
- If a full trace sweep chooses a next frontier, moves a frontier, lands a fix,
  or regresses a passing trace, update `docs/status/trace-frontier-log.md` with the
  command, commit/worktree context, pass/fail, error count, and first-error
  frame/field.

## Deliverables

- A headless-safe S3K Data Select visual regression test.
- Focused verification evidence for that regression.
- A complete categorized inventory of matching test sources.
- A durable audit at `docs/architecture/audits/testing/debug-trace-tests.md`.
- Runtime results for each safe execution group.
- A concise list of concrete test naming, selection, side-effect, and reliability
  issues, with fixes limited to confirmed problems.

## Success Criteria

- The visual test skips rather than errors when GLFW is unavailable and still
  performs its pixel assertions when GLFW is available.
- No matching test source is omitted from the inventory.
- Every executed group has a recorded command and outcome.
- Any proposed or implemented cleanup is supported by a reproduced issue.
- Each deliverable satisfies its explicit green criterion and passes independent
  review with no blocking or material findings.
