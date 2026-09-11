# Complete Architecture Guarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the existing ArchUnit adoption into a complete architecture guard suite covering the ten identified project guard goals.

**Architecture:** Use ArchUnit for bytecode dependency, ownership, construction, and assignability rules. Use source-scanner JUnit guards for textual or semantic constraints that ArchUnit cannot express reliably, including path literals, `GameId` branching intent, trace hydration hazards, coordinate hazards, and file-size budgets. Existing frozen ArchUnit baselines remain backstops while narrower rules make exception categories explicit.

**Tech Stack:** Java 21, Maven, JUnit 5, ArchUnit 1.4.2, existing source-scanner guard patterns.

---

## Requirements

- Add guard coverage for ten architecture goals:
  1. Runtime ownership boundaries.
  2. `GameId` behavior branching restrictions.
  3. Runtime-owned registry construction ownership.
  4. Trace replay comparison-only invariants.
  5. Split or clarify broad frozen baselines.
  6. Provider concrete-construction boundaries.
  7. ROM-only runtime asset sourcing.
  8. Rewind override and annotation-growth policy.
  9. `Engine.java` and `ObjectArtData` growth budgets.
  10. Centre-coordinate hazard detection.
- Keep production behavior unchanged unless an existing trivial issue must be corrected for a guard to pass.
- Prefer hard ArchUnit rules for clean invariants and `FreezingArchRule` for current debt.
- Prefer explicit allowlists for permanent composition roots.
- Keep scanner tests focused and documented so false positives are explainable.

## Exploration Synthesis

Parallel exploration identified these current strengths:

- `TestArchUnitRules` already covers core package/dependency boundaries.
- Existing source scanners cover singleton closure, object construction safety, JUnit 5 migration, direct map mutation, and trace replay hazards.
- Current frozen baselines are useful but too broad: composition roots, cross-game donation, and real debt are mixed in the same files.

Primary gaps:

- Session/runtime ownership is not fully encoded in ArchUnit.
- `GameId` branching is not constrained in behavior layers.
- Runtime-shared registries can be locally constructed in future code.
- Trace replay scanner roots and hydration heuristics are incomplete.
- ROM-only asset sourcing and coordinate semantics need source guards.

## Architecture Decision

- ArchUnit remains the bytecode guard layer.
- Source scanners remain first-class architecture guards where exact source text or intent matters.
- Permanent architecture surfaces are explicit allowlists.
- Current migration debt is frozen and documented.
- Broad freezes should shrink over time into category-specific rules.

## Feature Design

### ArchUnit Guard Expansion

- Add session construction and runtime-owned registry construction rules.
- Add no-runtime-manager-dependency rule for `WorldSession`.
- Add no-global-facade-access rule for runtime-owned framework packages.
- Add provider concrete-construction boundary if current debt can be frozen clearly.
- Add assignability rules for stateful runtime-shared registries that must remain rewind-snapshot capable.

### Source Scanner Guard Expansion

- Add behavior-layer `GameId` branching scanner.
- Add runtime asset scanner for `docs/s1disasm`, `docs/s2disasm`, and `docs/skdisasm`.
- Add `Engine.java` budget guard.
- Add `ObjectArtData` game/zone-specific growth guard.
- Add conservative coordinate hazard scanner for object interaction code.

### Trace/Rewind/Test Guard Expansion

- Expand trace replay invariant roots.
- Add trace data/parser dependency guard.
- Add rewind override and annotation-growth guards.
- Add trace replay test inheritance guard.
- Add singleton/gameplay lifecycle setup guard if the current baseline can be
  frozen without making the rule noisy.

## Implementation Plan

### Task 1: Bytecode ArchUnit Ownership Rules

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestArchUnitRules.java`
- Modify if needed: `src/test/resources/archunit/frozen/*`
- Modify if needed: `docs/architecture/archunit-exceptions.md`

- [ ] Add session/runtime ownership rules one at a time.
- [ ] Add runtime registry construction ownership rules.
- [ ] Add runtime framework no-global-facade rule.
- [ ] Add rewind assignability rules.
- [ ] Add provider construction boundary if feasible.
- [ ] Run `mvn test "-Dtest=TestArchUnitRules"`.

### Task 2: Semantic Source Scanner Rules

**Files:**
- Create or modify: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java`
- Optionally modify: `src/test/java/com/openggf/game/TestProductionSingletonClosureGuard.java`

- [ ] Add scanner helper tests for comments/string stripping and path matching.
- [ ] Add `GameId` behavior-branch scanner with allowlists.
- [ ] Add runtime `docs/disasm` asset scanner.
- [ ] Add `Engine.java` budget guard.
- [ ] Add `ObjectArtData` growth guard.
- [ ] Add conservative coordinate hazard scanner.
- [ ] Run targeted scanner tests.

### Task 3: Trace/Rewind/Test Policy Rules

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceReplayInvariantGuard.java`
- Create or modify: `src/test/java/com/openggf/game/rewind/TestRewindArchitectureGuard.java`
- Create or modify: `src/test/java/com/openggf/tests/rules/TestTraceReplayTestArchitectureGuard.java`

- [ ] Expand trace replay scanner roots.
- [ ] Add hydration hazard patterns and allowlists.
- [ ] Add trace data/parser dependency guard.
- [ ] Add rewind override and annotation-growth guards.
- [ ] Add trace replay inheritance guard.
- [ ] Run targeted trace/rewind/test-policy tests.

### Task 4: Integration And Verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify if needed: `docs/architecture/archunit-exceptions.md`
- Modify if needed: `docs/architecture/plans/2026-05-11-complete-architecture-guarding.md`

- [ ] Review worker changes for overlap.
- [ ] Run `mvn test "-Dtest=TestArchUnitRules,TestArchUnitTestRules,TestArchitecturalReviewGuard,TestArchitecturalSourceGuard,TestTraceReplayInvariantGuard,TestRewindArchitectureGuard,TestTraceReplayTestArchitectureGuard"`.
- [ ] Run broader `mvn test` if targeted checks pass.
- [ ] Update changelog and architecture exception notes.
- [ ] Prepare final human review summary.

## Integration Report

- Added bytecode ArchUnit guards in `TestArchUnitRules` for session/context
  construction ownership, `WorldSession` runtime isolation, runtime-owned
  framework static-root access, `GameServices` concrete game isolation,
  runtime registry/controller construction ownership, runtime-shared rewind
  snapshot capability, and shared-code concrete Sonic provider construction.
- Split new frozen migration debt into explicit ArchUnit baselines under
  `src/test/resources/archunit/frozen` and documented the exception categories
  in `docs/architecture/archunit-exceptions.md`.
- Added `TestArchitecturalSourceGuard` for `GameId` behavior branching,
  runtime disassembly path literals, `Engine.java` growth budgets,
  `ObjectArtData` game/zone-specific surface growth, and conservative object
  coordinate hazard scanning.
- Expanded `TestTraceReplayInvariantGuard` to scan `TraceSessionLauncher` and
  trace consumers outside the original roots, strengthen trace hydration
  detection, guard parser/data/catalog runtime dependencies, and require
  concrete trace replay tests to use shared replay bases.
- Added `TestRewindArchitectureGuard` to freeze object rewind override growth,
  rewind annotation growth, and production `RewindRegistry` construction
  ownership.
- Added `TestSingletonLifecycleGuard` to freeze setup methods that open
  `TestEnvironment.activeGameplayMode()` without first using the central
  `resetAll`, game-module fixture, ROM fixture, or per-test reset lifecycle.
  The guard includes scanner sample tests and a migration baseline so new
  ambient gameplay setup does not appear silently.
- Stabilized `TestSidekickCpuFollowParity` by using the centralized
  `TestEnvironment.resetAll()` fixture setup. The full-suite failure was caused
  by reused Maven forks inheriting an S3K bootstrap module from earlier tests,
  which made S2-default sidekick nudge cases resolve S3K follow-lead behavior.
- Cleanup follow-through migrated `TestSidekickCpuDespawnParity`,
  `TestSidekickCpuControllerCatchUpFlight`,
  `TestSidekickCpuControllerFlightAutoRecovery`, and `TestTimerManager` onto
  `TestEnvironment.resetAll()`, removing those methods from the lifecycle
  baseline.
- Additional cleanup migrated `TestSonic1LevelInitProfile`,
  `TestSonic2LevelInitProfile`, `TestObjectManagerLifecycle`, and
  `TestDestructionEffects` onto `TestEnvironment.resetAll()`, further reducing
  the lifecycle baseline.
- Hardened `TestObjectServicesMigrationGuard` with a consolidated global
  runtime-access scanner for both game object packages and shared
  `level.objects` sources. The scanner fails direct `GameServices`,
  `EngineServices`, runtime fallback, `GameModuleRegistry`, and monitored
  singleton access outside documented object-service bridge exceptions.
- Completed object-service guard follow-ups: shared guard scanner utilities now
  live in `ObjectGuardSourceScanner`; global runtime bridge exceptions are
  source-line-level entries with reasons instead of whole-class trust; the
  `ObjectManager` rewind overlap restore path now resolves sidekicks through
  injected `ObjectServices.spriteManager()`; and
  `docs/guide/contributing/architecture.md` documents the object service access
  contract for contributors. The shared-layer frozen ArchUnit baseline now also
  records the current `DefaultPowerUpSpawner` lambda-form S3K shield bridge
  violations.

## End-to-End Review

- Targeted architecture verification passed:
  `mvn test "-Dtest=TestArchUnitRules,TestArchUnitTestRules,TestArchitecturalReviewGuard,TestArchitecturalSourceGuard,TestSingletonLifecycleGuard,TestTraceReplayInvariantGuard,TestRewindArchitectureGuard,TestJunit5MigrationGuard,TestProductionSingletonClosureGuard,TestNoDirectMapMutationsInGameplay"`
  reported `MSE:OK modules=1 passed=4906 failed=0 errors=0 skipped=6`.
- Sidekick isolation verification passed:
  `mvn test "-Dtest=TestSidekickCpuFollowParity"` reported
  `MSE:OK modules=1 passed=4902 failed=0 errors=0 skipped=6`.
- Full-suite verification passed:
  `mvn test` reported
  `MSE:OK modules=1 passed=4906 failed=0 errors=0 skipped=6`.
- Cleanup verification passed:
  `mvn test "-Dtest=TestSingletonLifecycleGuard,TestSidekickCpuDespawnParity,TestSidekickCpuControllerCatchUpFlight,TestSidekickCpuControllerFlightAutoRecovery,TestTimerManager"`
  and a final `mvn test` both reported
  `MSE:OK modules=1 passed=4906 failed=0 errors=0 skipped=6`.
- Additional cleanup verification passed:
  `mvn test "-Dtest=TestSingletonLifecycleGuard,TestSonic1LevelInitProfile,TestSonic2LevelInitProfile,TestObjectManagerLifecycle,TestDestructionEffects"`
  reported `MSE:OK modules=1 passed=4906 failed=0 errors=0 skipped=6`.
- Object-service guard hardening verification passed:
  `mvn test "-Dtest=TestObjectServicesMigrationGuard"` reported
  `MSE:OK modules=1 passed=4909 failed=0 errors=0 skipped=6`.
- Post-hardening full-suite verification passed:
  `mvn test` reported
  `MSE:OK modules=1 passed=4909 failed=0 errors=0 skipped=6`.
- Object-service follow-up verification passed:
  `mvn test "-Dtest=TestObjectServicesMigrationGuard,TestNoServicesInObjectConstructors,TestConstructionContextGuard,TestObjectManagerRewindSnapshot"`
  reported `MSE:OK modules=1 passed=4911 failed=0 errors=0 skipped=6`.
- Final object-service follow-up verification passed:
  `mvn test "-Dtest=TestObjectServicesMigrationGuard,TestNoServicesInObjectConstructors,TestConstructionContextGuard,TestObjectManagerRewindSnapshot,TestArchUnitRules"`
  reported `MSE:OK modules=1 passed=4908 failed=0 errors=0 skipped=5`, and
  `mvn test` reported
  `MSE:OK modules=1 passed=4911 failed=0 errors=0 skipped=6`.
