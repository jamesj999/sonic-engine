# S2 Metropolis Zone Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close known Sonic 2 Metropolis Zone parity drift in object discovery metadata, MTZ3 sidekick bounds, and Obj66 solid routine behavior with focused tests and trace validation.

**Architecture:** Keep MTZ behavior on existing Sonic 2 object, event, sidekick CPU, rewind, and solid-profile frameworks. Add semantic state where the ROM has semantic state (`Tails_Min_Y_pos`) and avoid route/frame-specific trace exceptions.

**Tech Stack:** Java 17, Maven, JUnit 5, OpenGGF Sonic 2 ROM-backed loaders, trace replay diagnostics.

---

## File Structure

- Modify `src/main/java/com/openggf/tools/Sonic2ObjectProfile.java`: mark implemented MTZ dynamic boss IDs.
- Modify `src/main/java/com/openggf/game/sonic2/objects/MTZSpringWallObjectInstance.java`: expose the shared solid-profile gate bypass for Obj66 if confirmed by focused tests.
- Modify `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`: add a sidekick min-Y level bound with reset and rewind persistence.
- Modify `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`: add min-Y to `SidekickCpuRewindExtra`.
- Modify `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java` and `Sonic2MTZEvents.java`: route MTZ3 routine 6's `Tails_Min_Y_pos` write through sidekick bounds.
- Modify tests under `src/test/java/com/openggf/game/sonic2`, `src/test/java/com/openggf/sprites/playable`, and `src/test/java/com/openggf/game/sonic2/objects` for focused regressions.
- Update `docs/status/trace-frontier-log.md` and `docs/guide/playing/game-status.md` only when verification changes documented status.

### Task 1: Baseline and Plan

- [x] **Step 1: Create isolated branch/worktree**

Run:
```powershell
git worktree add .worktrees/feature-ai-s2-mtz-parity -b feature/ai-s2-mtz-parity
```

Expected: worktree exists at `.worktrees/feature-ai-s2-mtz-parity`.

- [ ] **Step 2: Run focused baseline tests**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.game.sonic2.TestTodo10_MTZEventSpecs,com.openggf.game.sonic2.TestTodo21_23_MissingArtSheets" test -DfailIfNoTests=false
```

Expected: PASS after resolving unrelated test-compile prerequisites.

### Task 2: Dynamic Boss Discovery Metadata

- [ ] **Step 1: Write or extend a discovery/profile assertion**

Add an assertion that `new Sonic2ObjectProfile().getImplementedIds()` contains `0x53` and `0x54`.

- [ ] **Step 2: Run the profile test**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.tools.TestSonic2ObjectProfile" test -DfailIfNoTests=false
```

Expected before implementation: FAIL because the MTZ dynamic boss IDs are absent.

- [ ] **Step 3: Add IDs to `Sonic2ObjectProfile.IMPLEMENTED_IDS`**

Add `0x53` and `0x54` beside the MTZ object range, with comments naming `MTZBossOrb` and `MTZBoss`.

- [ ] **Step 4: Rerun profile/discovery validation**

Run:
```powershell
mvn -q -Dmse=relaxed exec:java "-Dexec.mainClass=com.openggf.tools.ObjectDiscoveryTool" "-Dexec.args=--game s2 --output target/s2-object-checklist.md"
```

Expected: MTZ dynamic boss rows are checked.

### Task 3: MTZ3 Sidekick Minimum-Y State

- [ ] **Step 1: Add a failing sidekick-bound unit test**

Add a focused test that calls `controller.setLevelBounds(null, null, 0x0400, null)`, asserts `getMinYBound(fallback) == 0x0400`, captures rewind state, mutates the controller, restores the snapshot, and asserts the min-Y bound is restored.

- [ ] **Step 2: Run the sidekick test**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.sprites.playable.TestSidekickCpuDespawnParity" test -DfailIfNoTests=false
```

Expected before implementation: FAIL because `getMinYBound` and the four-argument setter do not exist.

- [ ] **Step 3: Implement min-Y bound in controller and rewind state**

Add `minYBound`, `getMinYBound(int fallback)`, `setLevelBounds(Integer minX, Integer maxX, Integer minY, Integer maxY)`, and keep the existing three-argument overload delegating with `minY = null`. Add the field to capture, restore, and reset paths plus `SidekickCpuRewindExtra`.

- [ ] **Step 4: Preserve ROM write-only semantics**

Do not consume `minYBound` in movement. `docs/s2disasm/s2.constants.asm` documents `Tails_Min_Y_pos` as "seems not actually implemented (only written to)", so the engine should preserve the ROM state for parity/rewind without changing sidekick physics.

- [ ] **Step 5: Route MTZ3 event write**

In `Sonic2ZoneEvents`, add a four-argument sidekick-bound helper while retaining the existing three-argument helper. In `Sonic2MTZEvents`, when routine 6 writes camera min-Y `0x400`, also write sidekick min-Y `0x400`.

- [ ] **Step 6: Rerun MTZ event and sidekick tests**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.game.sonic2.TestTodo10_MTZEventSpecs,com.openggf.sprites.playable.TestSidekickCpuDespawnParity,com.openggf.tests.TestS3kAiz2SidekickBoundsSync" test -DfailIfNoTests=false
```

Expected: PASS; existing S3K max-bound sync remains compatible with the three-argument setter.

### Task 4: Obj66 Solid Gate Profile

- [ ] **Step 1: Add a focused Obj66 profile test**

Create or extend `TestMTZSpringWallObjectInstance` to instantiate `MTZSpringWallObjectInstance` and assert `bypassesOffscreenSolidGate()` is true.

- [ ] **Step 2: Run the focused object test**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.game.sonic2.objects.TestMTZSpringWallObjectInstance" test -DfailIfNoTests=false
```

Expected before implementation: FAIL if Obj66 inherits the default false profile.

- [ ] **Step 3: Implement the profile method**

Add:
```java
@Override
public boolean bypassesOffscreenSolidGate() {
    return true;
}
```

to `MTZSpringWallObjectInstance`.

- [ ] **Step 4: Rerun object and shared solid tests**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.game.sonic2.objects.TestMTZSpringWallObjectInstance,com.openggf.level.objects.TestSolidObjectManager" test -DfailIfNoTests=false
```

Expected: PASS.

### Task 5: Trace and Documentation Validation

- [ ] **Step 1: Run MTZ trace replay tests**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.tests.trace.s2.TestS2MtzLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2Mtz2LevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2Mtz3LevelSelectTraceReplay" test -DfailIfNoTests=false
```

Expected: PASS or a documented frontier that is not worsened.

- [ ] **Step 2: Update trace frontier documentation**

If a frontier moves or a trace remains blocked, update `docs/status/trace-frontier-log.md` with the exact test command, old/new frontier frames, and semantic root cause.

- [ ] **Step 3: Update game status documentation**

If MTZ boss/object status is now verified by tests, update `docs/guide/playing/game-status.md` to remove stale implementation caveats while retaining trace-frontier caveats that remain true.

- [ ] **Step 4: Final verification**

Run:
```powershell
mvn -q -Dmse=relaxed "-Dtest=com.openggf.game.sonic2.TestTodo10_MTZEventSpecs,com.openggf.game.sonic2.TestTodo21_23_MissingArtSheets,com.openggf.game.sonic2.objects.TestMTZSpringWallObjectInstance,com.openggf.sprites.playable.TestSidekickCpuDespawnParity" test -DfailIfNoTests=false
```

Expected: PASS.

## Self-Review

- Spec coverage: dynamic boss profile drift, MTZ3 sidekick min-Y, Obj66 solid gate, trace validation, and docs are each mapped to a task.
- Placeholder scan: no task uses open-ended "TBD" or unspecified implementation instructions.
- Type consistency: min-Y setter overloads preserve the existing three-argument API while adding a four-argument API for ROM min-Y writes.
