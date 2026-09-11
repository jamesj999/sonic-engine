# Develop Test-Suite Repair Design

## Context

At commit `a29d5fd7a`, a preliminary ordinary JDK 21 Maven run reported 14,867
tests with 43 failures, 6 errors, and 36 skips when all three verified retail
ROMs were provided. That count was collected before the repair worktree existed
and without first clearing prior Surefire reports or pinning run order, so it is
orientation evidence, not the authoritative control. The first implementation
step must reproduce and archive a clean, alphabetical baseline in the isolated
worktree.

The observed failures span runtime scheduling, rewind, S2 and S3K objects,
collision, rendering, and configuration. They are largely inherited: the
August 27 audio rollback records a method-matched baseline of 44 failures and
16 errors. The repair therefore cannot be treated as one recent regression or
as an invitation to restore the rolled-back audio programme.

## Goals

- Make the largest safe reduction in the inherited red suite, ideally reaching
  a clean ordinary suite.
- Fix production behavior at the ROM- or architecture-owned boundary rather
  than weakening assertions, adding trace-specific exceptions, or fitting
  constants to fixtures.
- Preserve every test and trace that passes on the `a29d5fd7a` baseline.
- Keep the user's workspace configuration, including the configured capture
  output directory, intact.
- Produce independently reviewable commits whose focused verification identifies
  exactly which failure cluster each commit changes.
- Treat a failure as improved only when it becomes green or its independently
  meaningful frontier advances without adding a new mismatch; a lower aggregate
  count alone is insufficient.

## Non-goals

- Reintroducing the deferred SMPS playback-authenticity programme.
- Updating trace fixtures to agree with current engine behavior.
- Hydrating gameplay state from trace comparison data.
- Broad framework migration unrelated to a reproduced failure.
- Hiding unresolved failures by changing coverage inventories, tolerances, or
  expected values without an independently established production contract.

## Programme decomposition

This design is an umbrella for independently planned repair subprojects. Only
the frame/resource-lifecycle subproject is planned initially. After each
subproject, the ordinary suite is remeasured and the remaining red set is
reclassified before the next plan is written. This prevents later plans from
prescribing fixes for failures that disappear when an upstream contract is
corrected.

Each subproject must be independently acceptable: it has a named failing set,
one or more verified root causes, focused regression tests, a full-suite delta,
and a commit that can be retained even if a later subproject is stopped. Once
the shared lifecycle subproject is green and remeasured, genuinely independent
leaf subprojects may be investigated in separate worktrees, but they merge into
this repair branch one at a time after focused and full-delta review.

## Repair strategy and ordering

Work proceeds in dependency order because an incorrect shared scheduling or
ownership contract can produce many downstream object failures.

1. **Frame and resource lifecycle.** Diagnose VBlank, object-scan, pending-title,
   transition, PLC, and hardware-timing phase ordering. Compare the production
   call graph with the existing tests and the relevant ROM loops. Correct the
   earliest shared owner before touching downstream title, queue, or event
   symptoms.
2. **Rewind and dynamic object ownership.** Resolve duplicate registration,
   snapshot side effects, child-graph recreation, and stale coverage inventory.
   Inventory updates are permitted only after the production object set and
   recreate path are proven correct.
3. **S2 gameplay contracts.** Address object load order, spring solid contact,
   trigger participation, tube handoff, playable-state transitions, and
   fractional position writes. Shared collision changes require cross-game
   disassembly review and the S3K keep-green set.
4. **S3K gameplay contracts.** Address object initialization and lifetime,
   boss/event transitions, MGZ scroll state, and ROM-backed art submission at
   their existing object, event, scroll, or resource owners. No zone predicate
   may be added to shared code.
5. **Rendering and configuration isolation.** Restore SAT priority propagation.
   Make the capture-default test exercise the packaged default through an
   isolated configuration source rather than the repository-root user override.
   Do not change the production default-loading precedence or the user's
   preferred output directory merely to make the test pass.

Each lane starts with the already-failing test as its red case. Investigation
must establish a single root-cause hypothesis before production edits. One
minimal fix is applied, its focused class and nearby canaries are run, and the
ordinary red-set names are diffed before the next lane. New tests are added only
when the existing failure does not pin the corrected boundary.

## Trace safety

Trace data remains comparison-only. No fix may branch on a trace name, row,
frame, route, or zone identity in shared runtime code. Values and structural
rules must come from the disassembly or an existing production contract. If a
shared scheduling change can affect recorded replays, validation uses a fresh
report directory, `-Ptrace-replay`, `-Dmse=off`, and alphabetical Surefire
ordering. Any moved frontier is recorded in `docs/status/trace-frontier-log.md`
with command, commit, result, error count, and first divergent field.

## Verification and regression control

The immutable source control is `a29d5fd7a`. Before implementation, the isolated
repair worktree clears generated reports and reproduces the ordinary red set
with explicit ROM properties, full Maven output, and alphabetical test order.
The baseline record includes every failing test name, assertion or exception
message, and skipped count, and is stored in
`docs/architecture/audits/2026-08-28-develop-suite-repair-baseline.md`. Each
commit records focused commands and compares those names and messages, not just
totals. A regression is any newly failing test, any changed pre-existing failure
attributable to the repair, or any previously failing test whose error becomes
earlier, broader, or more severe.

The ordinary control and candidate command is:

```bash
rm -rf target/surefire-reports
mvn -Dmse=off -Dsurefire.runOrder=alphabetical \
  -Dsonic1.rom.path=s1.gen -Dsonic2.rom.path=s2.gen \
  -Ds3k.rom.path=s3k.gen test -B
```

Before integration, also run:

- `mvn -Dmse=off -Pguards test -B` in a fresh JVM;
- the S3K keep-green command after any shared runtime change:

  ```bash
  mvn -Dmse=off -Dsurefire.runOrder=alphabetical \
    -Ds3k.rom.path=s3k.gen \
    -Dtest='TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils' \
    test -B
  ```

- the trace-replay command after any trace-reachable change:

  ```bash
  rm -rf target/surefire-reports target/trace-reports
  mvn -Dmse=off -Ptrace-replay -Dsurefire.runOrder=alphabetical \
    -Dsonic1.rom.path=s1.gen -Dsonic2.rom.path=s2.gen \
    -Ds3k.rom.path=s3k.gen test -B
  ```

Focused Maven invocations do not establish full-suite totals because unrelated
XML may remain in the worktree. Their evidence is limited to the explicitly
selected classes and the process exit code.

Delivery follows the repository workflow: fetch and fast-forward `develop`,
record its updated baseline, run the same full verification in the repair
worktree, merge into the main workspace without switching it, update the README
release summary required by policy, compare post-merge results against the
updated baseline, push only `develop`, then remove the clean merged worktree and
delete its local branch.

## Stop conditions

A failure may remain unresolved when its accurate fix requires a larger
subsystem redesign, unavailable hardware evidence, or trace-fixture publication
approval. In that case the branch may still deliver independently verified
repairs, but the unresolved test remains red and is reported with its established
root cause and evidence. Three failed fix hypotheses for one cluster trigger an
architecture review rather than a fourth speculative edit.
