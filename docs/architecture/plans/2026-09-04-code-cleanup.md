# Code cleanup implementation plan

**Goal:** Remove confirmed dead helpers and low-value tests, repair misleading coverage,
and repeat the audit twice without changing gameplay behavior.

**Architecture:** Keep production ownership and ROM behavior intact. Delete helpers with
no production consumers; test actual production entry points and snapshot contracts.
The live legacy/canonical collision-profile migration is outside this cleanup.

**Stack:** Java 21, JUnit 5, Maven; base `develop` at `3295ee9cd`.

## Tasks

- [x] Pass 1: delete the two unused TouchResponseProfileAdapter classes,
  PersistentAccumulator and its test, TraceHistoryHydration and its tests, and the
  unused LevelRewindBoundaryCoordinator. Point TestLevelManagerRewindBoundary at
  LevelManager.markRewindLevelLoadBoundary. Repair TestOscillationManagerSnapshot
  to re-read the mutated snapshot. Replace the listener setter smoke test in
  TestGameLoop with actual transition notification checks. Remove duplicated enum
  and initial-state tests and inherited no-op tests in TestSonic2EndingProvider.
- [x] Pass 2: inspect adjacent tests for self-confirming assertions, dead adapters,
  and wrappers. Remove only candidates confirmed by caller searches and inspection.
- [x] Pass 3: repeat the source/caller audit after the first two passes; inspect the
  resulting diff for lost meaningful coverage and remaining stale references.
- [x] Verify repaired tests with temporary mutations to the production behavior;
  restore production sources before the final focused run.
- [x] Run `mvn -Dmse=off test -B` and `mvn -Dmse=off -Pguards test -B` on the
  updated baseline and development tree, with absolute paths for
  all three verified ROMs and `LUA_BIN=lua5.4`. Preserve exact test outcomes under
  each tree's target directory and compare testcase outcomes, not just totals.
- [x] Update the current contributor reference and README release summary, and
  record findings and validation under docs/architecture/audits.

## Delivery procedure

Commit with policy trailers and merge into develop without switching the main
workspace branch. Repeat ordinary and guard suites there and compare with the
recorded baseline; push develop only after verification, then remove the clean
merged worktree and its local branch. Post-merge outcomes and pushed commits are
reported in the final delivery message.
