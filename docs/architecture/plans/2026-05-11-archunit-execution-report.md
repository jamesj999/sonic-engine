# ArchUnit Execution Report

Date: 2026-05-11
Branch: `feature/ai-archunit-adoption`
Blueprint: `docs/architecture/designs/2026-05-11-archunit-implementation-blueprint.md`

## Requirements

Executed the blueprint's PR 1 scope:

- add ArchUnit as a test-only dependency;
- port the six bytecode-suitable architecture invariants into ArchUnit;
- keep source/XML checks for surfaces ArchUnit cannot inspect;
- verify the ArchUnit import path under the existing Surefire/Mockito-agent setup.

PR 2 expansion rules were executed after human approval of the exception policy:
allowlists for intentional bridge classes, `FreezingArchRule` for current debt,
freeze baselines in `src/test/resources/archunit/frozen`, and exception rationale
in `docs/architecture/archunit-exceptions.md`.

## Exploration Synthesis

The worktree already contained edits to `pom.xml` and
`TestArchitecturalReviewGuard.java`. The implementation preserved those changes:

- `project.basedir` remains passed through Surefire for source-path guards.
- `TestArchitecturalReviewGuard` keeps source-text checks that intentionally fail
  on comments and string literals containing forbidden identifiers.
- The reflection-only rewind snapshot check moved to ArchUnit because it has no
  source-text enforcement value.

PR 1 full-suite verification exposed an order-sensitive failure in
`TestLazyMappingHolder`: the test assumed no active gameplay session, but the full
suite can leave `GameServices.levelOrNull()` non-null in the same fork. The test
now clears `SessionManager` before each method to assert its intended no-runtime
precondition directly.

PR 2 generated three frozen baselines:

- object packages accessing global `GameServices`;
- shared `level..` / non-game-specific `game..` code depending on per-game
  packages;
- per-game package cross-dependencies.

## Architecture Decision

Architecture enforcement is split by surface:

- `TestArchUnitRules` owns bytecode-level dependency invariants.
- `TestArchitecturalReviewGuard` retains source-text and XML/build-profile guards.

This avoids losing comment/string enforcement while still gaining bytecode
dependency coverage.

## Feature Design

Implemented PR 1:

- `pom.xml` adds `com.tngtech.archunit:archunit-junit5:1.4.2` with test scope.
- `TestArchUnitRules` uses
  `@AnalyzeClasses(packages = "com.openggf", cacheMode = CacheMode.FOREVER)`.
- ArchUnit rules cover:
  - S2/S3K level animation managers implement `RewindSnapshottable`;
  - `ObjectServices` avoids `GameServices`;
  - shared `CheckpointState` avoids S3K event manager coupling;
  - `LevelManager` / `LevelRenderer` avoid `Sonic3kZoneIds`;
  - S1/S2 modules avoid `S3kDataSelectManager`;
  - shared data-select entry point avoids S3K delegate dependencies.
- A small import smoke rule verifies ArchUnit sees application classes under the
  existing test runtime.

Implemented PR 2:

- `TestArchUnitRules` now imports production classes only.
- Added frozen object-service, shared-layer, and per-game slice rules.
- Added `TestArchUnitTestRules` for bytecode-level JUnit 4 API detection in test
  classes.
- Added `src/test/resources/archunit.properties` with freeze-store creation
  disabled after baseline generation.
- Added `docs/architecture/archunit-exceptions.md`.

## Implementation Plan

Completed:

- T1: dependency and ArchUnit test scaffold.
- T2: six current-rule translations plus source/XML guard retention.
- T3/T4 PR 2 violation inventory and expanded rules.
- Freeze-store setup.
- Broader package-boundary enforcement.

## Integration Report

Changed files owned by this execution:

- `CHANGELOG.md`
- `pom.xml`
- `src/test/java/com/openggf/tests/TestArchUnitRules.java`
- `src/test/java/com/openggf/tests/TestArchUnitTestRules.java`
- `src/test/java/com/openggf/tests/TestArchitecturalReviewGuard.java`
- `src/test/java/com/openggf/util/TestLazyMappingHolder.java`
- `src/test/resources/archunit.properties`
- `src/test/resources/archunit/frozen/*`
- `docs/architecture/archunit-exceptions.md`
- `docs/architecture/designs/2026-05-11-archunit-implementation-blueprint.md`
- `docs/architecture/plans/2026-05-11-archunit-execution-report.md`

The workspace also contains unrelated pre-existing modified and untracked files;
they were not reverted.

## End-to-End Review

PR 2 rules were added with frozen baselines and architecture-exception
documentation. No gameplay known-discrepancy document was updated because these
are architecture exceptions, not parity/rendering discrepancies.

Residual risks:

- Source-text companion checks intentionally overlap with ArchUnit by invariant
  name, but protect a different enforcement surface.
- Frozen baselines are intentionally current-state snapshots; cleanup PRs should
  shrink them over time.
- Hook trailers should use `Changelog: updated`; other mapped documentation
  trailers remain `n/a` unless additional mapped files are changed.
