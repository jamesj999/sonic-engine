# Pattern Atlas Overlap Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make virtual pattern-id range overlap a hard failure instead of a log warning, preventing silent atlas corruption.

**Architecture:** `PatternAtlas` owns virtual pattern range registration, so overlap validation belongs there. Tests should cover adjacent ranges, exact overlap, partial overlap, and duplicate-base overlap.

**Tech Stack:** Java 21, JUnit 5, Maven.

---

## File Map

- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlas.java` or add `src/test/java/com/openggf/graphics/TestPatternAtlasRangeRegistration.java`
- Docs: inspect `KNOWN_DISCREPANCIES.md` and update the virtual pattern range table only if the implementation changes a published range or capacity statement

## Tasks

### Task 1: Write failing range-overlap tests

- [ ] Add tests that register `[0x38000, 0x38100)` and `[0x38100, 0x38200)` and assert success.
- [ ] Add tests that register `[0x38000, 0x38100)` then `[0x38080, 0x38180)` and assert `IllegalArgumentException`.
- [ ] Add tests for exact duplicate ranges and enclosing ranges.
- [ ] Run `mvn "-Dtest=com.openggf.graphics.TestPatternAtlasRangeRegistration" test` and confirm the overlap tests fail before implementation.

### Task 2: Fail fast in `PatternAtlas.registerRange`

- [ ] Replace the overlap warning path in `PatternAtlas.registerRange(...)` with an `IllegalArgumentException` that names the new range and conflicting existing range.
- [ ] Keep adjacent ranges valid by using half-open interval overlap logic: `newStart < existingEnd && existingStart < newEnd`.
- [ ] Do not change lookup tiering or rendering behavior.

### Task 3: Validate known range registrations

- [ ] Run targeted tests that initialize player/sidekick pattern banks if such tests already exist.
- [ ] Run `rg "registerRange\\(" src/main/java src/test/java` and inspect dynamic registrations for intentional overlaps.
- [ ] If an existing intentional overlap is discovered, split or rename the range before weakening the guard.

### Task 4: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.graphics.TestPatternAtlasRangeRegistration,com.openggf.tests.TestArchitecturalSourceGuard" test`.
- [ ] Commit with `fix: reject overlapping pattern atlas ranges`.
