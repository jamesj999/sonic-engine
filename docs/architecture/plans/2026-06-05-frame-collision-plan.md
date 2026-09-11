# Frame Collision Plan Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clarify collision pipeline ownership by replacing the misleading all-in-one `CollisionSystem.step()` surface with an explicit per-frame collision plan used by playable movement and object resolution paths.

**Architecture:** Keep low-level terrain probes in `TerrainCollisionManager`, object solids in `ObjectManager.SolidContacts`, and orchestration in `CollisionSystem`. The new plan object documents which phases run for a frame without forcing all callers through a single oversized method.

**Tech Stack:** Java 21, JUnit 5, Maven.

---

## File Map

- Add: `src/main/java/com/openggf/physics/FrameCollisionPlan.java`
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java`
- Modify likely callers: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`, `src/main/java/com/openggf/sprites/managers/SpriteManager.java`, `src/main/java/com/openggf/LevelFrameStep.java`
- Test: `src/test/java/com/openggf/physics/TestCollisionSystem.java` or add `TestFrameCollisionPlan.java`

## Tasks

### Task 1: Characterize current callers

- [ ] Run `rg "collision\\(\\)|CollisionSystem|terrainCollision|resolveSolid" src/main/java/com/openggf`.
- [ ] Document which paths use terrain-only, object-solid-only, and combined collision.
- [ ] Do not change behavior in this task.

### Task 2: Add `FrameCollisionPlan`

- [ ] Create an immutable record with booleans or enum phases for terrain probes, solid object resolution, post-resolution ground mode, and trace recording.
- [ ] Add named factories such as `playableFrame()`, `terrainOnly()`, and `objectResolutionOnly()` based on the caller inventory.
- [ ] Write tests asserting factory contents and phase order.

### Task 3: Clarify `CollisionSystem`

- [ ] Add `run(FrameCollisionPlan plan, CollisionSubject subject)` or the smallest equivalent API supported by existing subject types.
- [ ] Move the current `step()` documentation onto the new explicit API.
- [ ] Deprecate or narrow `step()` if it is still needed for compatibility.
- [ ] Remove or implement the no-op `postResolutionAdjustments()` so the public pipeline does not promise an empty phase.

### Task 4: Migrate one low-risk caller

- [ ] Start with a test or headless path that already uses a full collision pass.
- [ ] Replace the direct ad hoc sequence with the named `FrameCollisionPlan`.
- [ ] Run its focused tests before touching playable movement hot paths.

### Task 5: Migrate playable movement call sites

- [ ] Replace direct orchestration only where tests show equivalent behavior.
- [ ] Do not change physics feature flags or game-specific behavior gates.
- [ ] Run focused headless collision tests and any trace replay smoke tests that do not require unavailable ROM assets.

### Task 6: Verify and commit

- [ ] Run `mvn "-Dmse=off" "-Dtest=*Collision*,*HeadlessWall*,com.openggf.tests.TestArchitecturalSourceGuard" test`.
- [ ] Commit with `refactor: describe frame collision phases explicitly`.

