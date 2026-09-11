# Editor Session Factory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move editor view manager construction out of `Engine` and into an owned editor context/factory so gameplay/editor transitions have a single session lifecycle boundary.

**Architecture:** Keep `WorldSession` durable and `GameplayModeContext` disposable. Introduce an editor-owned factory path parallel to gameplay manager attachment, with `EditorModeContext` owning editor camera/render/view dependencies that are currently manually assembled by `Engine`.

**Tech Stack:** Java 21, JUnit 5, LWJGL runtime classes, existing session tests.

---

## File Map

- Modify: `src/main/java/com/openggf/game/session/EditorModeContext.java`
- Add or modify: `src/main/java/com/openggf/game/session/EditorSessionFactory.java`
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/game/session/TestEditorModeContextLifecycle.java`
- Test: `src/test/java/com/openggf/game/session/TestSessionManager.java`

## Tasks

### Task 1: Characterize current editor manager ownership

- [ ] Add a test that enters editor mode from an active gameplay session and asserts the loaded `WorldSession` is preserved.
- [ ] Add assertions for the editor-owned manager references that currently must be rebound by `Engine`.
- [ ] Run `mvn "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle" test`.

### Task 2: Introduce `EditorSessionFactory`

- [ ] Create `EditorSessionFactory` with one public method: `create(WorldSession worldSession, EngineContext engineContext)`.
- [ ] Move editor camera/view/render manager construction from `Engine.bindEditorLevelView(...)` into the factory.
- [ ] Return a fully initialized `EditorModeContext`.

### Task 3: Make `EditorModeContext` own editor view state

- [ ] Add final fields for editor camera/view managers currently cached on `Engine`.
- [ ] Add accessors needed by rendering and input code.
- [ ] Add `destroy()` cleanup matching `GameplayModeContext.destroy()` style where resources need teardown.

### Task 4: Route `Engine` through the editor context

- [ ] Replace manual editor manager construction in `Engine.bindEditorLevelView(...)` with retrieval from the active `EditorModeContext`.
- [ ] Remove duplicate editor manager fields from `Engine` when all uses can be served by the context.
- [ ] Keep gameplay rendering behavior unchanged.

### Task 5: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=com.openggf.game.session.TestEditorModeContextLifecycle,com.openggf.game.session.TestSessionManager,com.openggf.tests.TestArchUnitRules" test`.
- [ ] Commit with `refactor: own editor managers in editor session`.

