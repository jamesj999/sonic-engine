# Level Frame Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove ambient `GameServices` reads from `LevelFrameStep` by passing an explicit frame context with the game module, feature providers, and optional bonus-stage controller.

**Architecture:** `LevelFrameStep` remains the canonical frame-order coordinator, but it should receive all frame dependencies as data. This makes frame-order decisions testable without a globally active gameplay context and prevents future service-locator creep.

**Tech Stack:** Java 21, JUnit 5, Maven.

---

## File Map

- Add: `src/main/java/com/openggf/LevelFrameContext.java`
- Modify: `src/main/java/com/openggf/LevelFrameStep.java`
- Modify call sites in: `src/main/java/com/openggf/level/LevelManager.java`, `src/main/java/com/openggf/GameLoop.java`, or current frame-step callers discovered by `rg "LevelFrameStep"`
- Test: `src/test/java/com/openggf/tests/architecture/TestArchitecturalSourceGuard.java`

## Tasks

### Task 1: Add the frame context record

- [ ] Create `LevelFrameContext` in package `com.openggf`.
- [ ] Include fields for the active game module, frame-order feature set, bonus-stage provider/controller, and every runtime flag currently read through `GameServices`.
- [ ] Add static factory `from(GameplayModeContext context)` if that keeps call sites concise.

### Task 2: Update `LevelFrameStep`

- [ ] Change the frame-step entry point to accept `LevelFrameContext`.
- [ ] Replace `GameServices.module()` with `context.gameModule()`.
- [ ] Replace `GameServices.bonusStage()` or equivalent ambient calls with explicit context access.
- [ ] Keep the current update ordering unchanged.

### Task 3: Update callers

- [ ] Locate callers with `rg "LevelFrameStep" src/main/java src/test/java`.
- [ ] At gameplay call sites, build the context from the current `GameplayModeContext`.
- [ ] At tests, create a minimal explicit context instead of opening global services unless the test truly needs a full session.

### Task 4: Guard against regression

- [ ] Extend `TestArchitecturalSourceGuard` with a check that `LevelFrameStep.java` does not contain `GameServices.`.
- [ ] Allow imports of plain data types, but not static/global service access.

### Task 5: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestArchUnitRules" test`.
- [ ] Run any existing frame-order tests found by `rg "FrameStep|frame order|LevelFrame" src/test/java`.
- [ ] Commit with `refactor: pass explicit level frame context`.
