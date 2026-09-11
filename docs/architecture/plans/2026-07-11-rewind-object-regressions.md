# Live Rewind Object Regression Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore intact Sonic 2 monitors and deterministic badnik movement when live gameplay rewinds to an earlier frame.

**Architecture:** Keep the existing `ObjectManagerSnapshot` format and two-phase identity restore. Establish failing production-path tests, diff the incorrectly restored state against the captured frame, change only the first proven divergent seam, and synchronize subclass-owned authoritative movement state with badnik base fields only where tests prove it necessary.

**Tech Stack:** Java 21, JUnit 5, Maven, production `ObjectManager.rewindSnapshottable()` and `RewindRoundTripHarness` test infrastructure.

---

## File map

- Create `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`: production-path monitor recreation, Masher trajectory, and non-Masher legacy badnik regressions.
- Modify only the production seam proven by the monitor snapshot diff: `ObjectManager`, monitor capture/restore, or render invalidation; preserve the existing snapshot format and two-phase object/link restore unless the failing test proves otherwise.
- Modify `src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java`: make the context-aware and legacy restore paths converge on one authoritative motion-state restore.
- Modify one or more inventoried legacy badnik classes under `src/main/java/com/openggf/game/sonic2/objects/badniks/` only if the failing non-Masher test proves an object-local authoritative-state gap.
- Modify `CHANGELOG.md`: record the live rewind correction because production gameplay code changes.

### Task 1: Inventory legacy badnik rewind state

**Files:**
- Create: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`
- Inspect: `src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java`
- Inspect: `src/main/java/com/openggf/game/sonic2/objects/badniks/BuzzerBadnikInstance.java`
- Inspect: `src/main/java/com/openggf/game/sonic2/objects/badniks/CoconutsBadnikInstance.java`
- Inspect: `src/main/java/com/openggf/game/sonic2/objects/badniks/BadnikProjectileInstance.java`

- [ ] **Step 1: Record the inventory in the test class Javadoc**

Add a table listing the four legacy override owners returned by:

```powershell
rg -l "public PerObjectRewindSnapshot captureRewindState\(\)" src/main/java/com/openggf/game/sonic2/objects/badniks
```

Classify `MasherBadnikInstance`, `BuzzerBadnikInstance`, and `CoconutsBadnikInstance` as concrete badniks, and classify `BadnikProjectileInstance` separately as a projectile base with nested concrete children. For each result, record whether it owns a second movement representation (`SubpixelMotion.State`, anchor/origin coordinates, projectile-local position, or only base `currentX/currentY/xVelocity/yVelocity`). This is evidence for selecting the non-Masher representative; it is not a baseline exemption.

Also record the reported “Snapper fish” identification: repository search contains no Snapper object/class; EHZ object ID `0x5C` is `Sonic2ObjectIds.MASHER`, registered by `Sonic2ObjectRegistry` as `MasherBadnikInstance`. Therefore the Masher regression in Task 3 is the concrete Snapper-report reproduction.

- [ ] **Step 2: Commit the inventory-only test scaffold**

Create the test class with its package, imports, class declaration, and inventory Javadoc, but no red test. Commit with subject:

```text
test(rewind): inventory Sonic 2 legacy badnik restore state
```

This commit must remain green.

### Task 2: Reproduce and fix the monitor live-rewind divergence

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`
- Modify as proven: `src/main/java/com/openggf/level/objects/ObjectManager.java`, `src/main/java/com/openggf/level/objects/AbstractMonitorObjectInstance.java`, or `src/main/java/com/openggf/game/sonic2/objects/MonitorObjectInstance.java`

- [ ] **Step 1: Write a monitor test that forces recreation**

Use `RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26)`, `h.objectManager()`, and the existing public `objectManager.setRewindInPlaceRestoreEnabledForTest(false)` hook. Locate the monitor with this complete helper:

```java
private static <T> T only(ObjectManager manager, Class<T> type) {
    return manager.getActiveObjects().stream()
            .filter(type::isInstance)
            .map(type::cast)
            .reduce((left, right) -> {
                throw new AssertionError("expected one " + type.getSimpleName());
            })
            .orElseThrow(() -> new AssertionError("missing " + type.getSimpleName()));
}
```

Initialize the monitor with one update, capture the intact manager snapshot, then execute the real break callback with a rolling Sonic 2 player configured as in `TestMonitorObjectInstance`. Capture the broken snapshot for diff evidence. Restore the intact snapshot first with reuse enabled (live default), then repeat from the same setup with forced reconstruction:

```java
RewindRoundTripHarness h = RewindRoundTripHarness.buildPlaced(GameId.S2, 0x26);
ObjectManager objectManager = h.objectManager();
MonitorObjectInstance original = only(objectManager, MonitorObjectInstance.class);
original.update(0, player);
ObjectManagerSnapshot intact = objectManager.rewindSnapshottable().capture();

original.onTouchResponse(player,
        new TouchResponseResult(0, 0x0E, 0x0E, TouchCategory.SPECIAL), 1);
ObjectManagerSnapshot broken = objectManager.rewindSnapshottable().capture();
assertEquals(0, original.getCollisionFlags(), "precondition: touch breaks the monitor");
assertTrue(readBooleanField(original, "broken"));
assertFalse(Arrays.equals(intact.placement().rememberedBits(),
        broken.placement().rememberedBits()), "precondition: break marks placement remembered");
assertTrue(objectManager.getActiveObjects().stream()
        .anyMatch(MonitorContentsObjectInstance.class::isInstance),
        "precondition: real break path spawned monitor contents");
objectManager.rewindSnapshottable().restore(intact);

MonitorObjectInstance restored = only(objectManager, MonitorObjectInstance.class);
restored.update(2, player);
assertEquals(0x46, restored.getCollisionFlags(), "earlier intact monitor remains solid");
assertFalse(readBooleanField(restored, "broken"));
assertFalse(objectManager.getActiveObjects().stream()
        .anyMatch(MonitorContentsObjectInstance.class::isInstance),
        "broken-frame monitor contents must not survive intact restore");
assertFalse(objectManager.getActiveObjects().stream()
        .anyMatch(ExplosionObjectInstance.class::isInstance),
        "broken-frame explosion must not survive intact restore");

ObjectManagerSnapshot recaptured = objectManager.rewindSnapshottable().capture();
assertPlacementEquals(intact.placement(), recaptured.placement());
assertArrayEquals(intact.usedSlotsBits(), recaptured.usedSlotsBits());
assertEquals(intact.slots().stream().map(ObjectManagerSnapshot.PerSlotEntry::slotIndex).toList(),
        recaptured.slots().stream().map(ObjectManagerSnapshot.PerSlotEntry::slotIndex).toList());
assertEquals(intact.dynamicObjects().stream().map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList(),
        recaptured.dynamicObjects().stream().map(ObjectManagerSnapshot.DynamicObjectEntry::className).toList());
assertTrue(RewindSnapshotDiff.diffKey("object-manager", intact, recaptured).isEmpty());
```

Add `readBooleanField` as the exact reflection helper already used by `TestMonitorObjectInstance`: obtain `MonitorObjectInstance.class.getDeclaredField(name)`, call `setAccessible(true)`, return `field.getBoolean(monitor)`, and wrap `ReflectiveOperationException` in `RuntimeException`.

Define a local `DummyPlayer extends AbstractPlayableSprite` exactly as `TestMonitorObjectInstance` does: constructor calls `super("sonic", (short) 0x0100, (short) 0x0100)`, `defineSpeeds()` zeros every speed/height field, `createSensorLines()` installs empty `Sensor[]` arrays, and `draw()` is empty. Before breaking, call `player.setRolling(true)`, `player.setAnimationId(Sonic2AnimationIds.ROLL)`, and `player.setYSpeed((short) 0x0120)`.

Test forced reconstruction by calling `setRewindInPlaceRestoreEnabledForTest(false)` before restore and asserting `assertNotSame`. Test actual reuse separately: perform one preliminary intact capture/restore to populate `rewindRestoreConstructionSideEffects`, reacquire the reconstructed monitor, capture that warmed intact state, break it, then restore with reuse enabled and assert the observed identity is reused.

Assert placement snapshots with a value helper, never record `equals`:

```java
private static void assertPlacementEquals(
        ObjectManagerSnapshot.PlacementSnapshot expected,
        ObjectManagerSnapshot.PlacementSnapshot actual) {
    assertArrayEquals(expected.activeSpawnIndices(), actual.activeSpawnIndices());
    assertArrayEquals(expected.rememberedBits(), actual.rememberedBits());
    assertArrayEquals(expected.stayActiveBits(), actual.stayActiveBits());
    assertArrayEquals(expected.destroyedInWindowBits(), actual.destroyedInWindowBits());
    assertArrayEquals(expected.dormantBits(), actual.dormantBits());
    assertArrayEquals(expected.objState(), actual.objState());
    assertArrayEquals(expected.pendingCursorLoadBits(), actual.pendingCursorLoadBits());
    assertArrayEquals(expected.pendingCursorLoadOrder(), actual.pendingCursorLoadOrder());
    assertArrayEquals(expected.deferredVerticalLoadBits(), actual.deferredVerticalLoadBits());
    assertEquals(expected.cursorIndex(), actual.cursorIndex());
    assertEquals(expected.lastCameraX(), actual.lastCameraX());
    assertEquals(expected.lastCameraChunk(), actual.lastCameraChunk());
    assertEquals(expected.counterBasedRespawn(), actual.counterBasedRespawn());
    assertEquals(expected.execThenLoadPlacement(), actual.execThenLoadPlacement());
    assertEquals(expected.permanentDestroyLatch(), actual.permanentDestroyLatch());
    assertEquals(expected.maxDynamicSlots(), actual.maxDynamicSlots());
    assertEquals(expected.lastScrollBackward(), actual.lastScrollBackward());
    assertEquals(expected.leftCursorIndex(), actual.leftCursorIndex());
    assertEquals(expected.fwdCounter(), actual.fwdCounter());
    assertEquals(expected.bwdCounter(), actual.bwdCounter());
    assertEquals(expected.spawnCounters(), actual.spawnCounters());
    assertEquals(expected.twoAxisCameraYCoarse(), actual.twoAxisCameraYCoarse());
    assertEquals(expected.s2LatchedCameraX(), actual.s2LatchedCameraX());
}
```

- [ ] **Step 2: Run the monitor test and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#monitorBreakRewindRestoresIntactState*" test
```

Expected: at least one default-reuse or forced-recreation variant FAILS on broken/collision state, orphaned break children, snapshot equivalence, or the first resumed frame. If both variants pass, Task 2 is a bounded diagnostic: commit no monitor production change, report that the direct object adapter is not the failing layer, and stop execution for a follow-up live-controller/render reproduction design. Do not guess or silently broaden this plan.

- [ ] **Step 3: Diff the intact and incorrectly restored snapshots**

Use the already-defined `recaptured` and `RewindSnapshotDiff.diffKey("object-manager", intact, recaptured)` plus explicit assertions to identify the first divergent field or membership entry. Record the single hypothesis in a test comment, for example:

```java
assertEquals(intact.slots().getFirst().state().compactGenericState(),
        recaptured.slots().getFirst().state().compactGenericState());
```

Do not compare opaque blobs only if their equality is identity-based; decode or assert the monitor's exposed collision/render behavior and the relevant `PerObjectRewindSnapshot` extras instead.

- [ ] **Step 4: Implement the minimal proven fix**

Change only the seam named by Step 3's failing assertion. If it is compact capture, correct the schema/policy or overload dispatch. If it is reuse/recreation, correct `canReuseForRewindRestore` or reconstruction cleanup. If it is placement, move only the necessary state-only restore. If object state is correct but the screen remains broken, invalidate the proven stale render cache/bucket after restore. No zone, route, or frame conditions are allowed.

- [ ] **Step 5: Run focused monitor and manager tests and verify GREEN**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#monitorBreakRewindRestoresIntactState*,com.openggf.game.sonic2.objects.TestMonitorObjectInstance,com.openggf.level.objects.TestTwoPhaseRestoreOrdering" test
```

Expected: PASS with zero failures.

- [ ] **Step 6: Update changelog and commit the red/green pair together**

Add a concise `CHANGELOG.md` bullet describing the proven cause and intact monitor restoration during live rewind. Commit production, test, and changelog files only after GREEN, with subject:

```text
fix(rewind): restore Sonic 2 monitor state in live rewind
```

Set `Changelog: updated`; use `n/a` with reasons for unaffected documentation trailers.

### Task 3: Reproduce and fix Masher authoritative movement restoration

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/badniks/MasherBadnikInstance.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write a failing Masher production snapshot trajectory test**

Build `RewindRoundTripHarness h = RewindRoundTripHarness.build(GameId.S2)`, create `MasherBadnikInstance seeded = new MasherBadnikInstance(new ObjectSpawn(160, 240, 0x5C, 0, 0, false, 0))`, and register it with `h.objectManager().addDynamicObject(seeded)`. Call `seeded.update(0, null)` once; `-0x400` velocity changes Y while preserving a fractional phase after subsequent gravity. Continue until `((PerObjectRewindSnapshot.MasherRewindExtra) seeded.captureRewindState().badnikSubclassExtra()).motionYSub() != 0`.

Force manager recreation with `objectManager.setRewindInPlaceRestoreEnabledForTest(false)`. Save the exact captured `PerObjectRewindSnapshot` for Masher from `ObjectManagerSnapshot.dynamicObjects()` before mutation. The immediate oracle compares restored state directly with that captured entry, not with another object restored by the same potentially faulty method:

```java
PerObjectRewindSnapshot actualState = actual.captureRewindState();
assertEquals(capturedState.badnikExtra(), actualState.badnikExtra());
assertEquals(capturedState.badnikSubclassExtra(), actualState.badnikSubclassExtra());
assertEquals(capturedState.badnikExtra().currentX(), actual.getX());
assertEquals(capturedState.badnikExtra().currentY(), actual.getY());

for (int frame = 0; frame < 8; frame++) {
    expected.update(frame, null);
    actual.update(frame, null);
    assertEquals(expected.getY(), actual.getY(), "trajectory frame " + frame);
    assertEquals(expected.captureRewindState().badnikSubclassExtra(),
            actual.captureRewindState().badnikSubclassExtra(), "subpixel frame " + frame);
}
```

The direct captured-state assertions are the RED oracle. After those pass, use an independent control only for resumed trajectory: construct a fresh Masher and apply the captured `MasherRewindExtra` fields to its private `motionState` with the existing reflection style used by `TestMasherBadnikInstance`; set inherited `currentX/currentY/xVelocity/yVelocity/animTimer/animFrame/facingLeft` from `capturedState.badnikExtra()` by walking declared fields up the superclass chain. The manager-restored `actual` must never initialize the control.

- [ ] **Step 2: Run the Masher test and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#masherRecreateRestoresSubpixelTrajectory" test
```

Expected: FAIL on restored position, `MasherRewindExtra`, or a resumed trajectory frame. If it passes, strengthen the setup to force recreation and a non-zero fractional Y phase; do not change production code until the test demonstrates the reported failure.

- [ ] **Step 3: Converge Masher's restore overloads on one implementation**

Add `RewindCaptureContext` overloads and complete helpers:

```java
private PerObjectRewindSnapshot withMasherState(PerObjectRewindSnapshot base) {
    return base.withBadnikSubclassExtra(new PerObjectRewindSnapshot.MasherRewindExtra(
            motionState.x, motionState.y, motionState.xSub, motionState.ySub,
            motionState.xVel, motionState.yVel, initialYPos));
}

private void restoreMasherState(PerObjectRewindSnapshot snapshot) {
    if (!(snapshot.badnikSubclassExtra() instanceof PerObjectRewindSnapshot.MasherRewindExtra extra)) {
        return;
    }
    motionState.x = extra.motionX();
    motionState.y = extra.motionY();
    motionState.xSub = extra.motionXSub();
    motionState.ySub = extra.motionYSub();
    motionState.xVel = extra.motionXVel();
    motionState.yVel = extra.motionYVel();
    initialYPos = extra.initialYPos();
    currentX = motionState.x;
    currentY = motionState.y;
    xVelocity = motionState.xVel;
    yVelocity = motionState.yVel;
}
```

Both no-context overloads delegate to the context-aware overloads; the context-aware capture calls `withMasherState(super.captureRewindState(context))`, and context-aware restore calls `super.restoreRewindState(snapshot, context)` followed by `restoreMasherState(snapshot)`.

- [ ] **Step 4: Run focused Masher tests and verify GREEN**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#masherRecreateRestoresSubpixelTrajectory,com.openggf.game.sonic2.objects.badniks.TestMasherBadnikInstance" test
```

Expected: PASS with zero failures.

- [ ] **Step 5: Update changelog and commit the red/green pair together**

Update `CHANGELOG.md`, then commit the red test and green production fix together with subject `fix(rewind): synchronize Sonic 2 Masher movement restore`. Do not leave a known-red commit on the branch.

### Task 4: Prove non-Masher legacy badnik synchronization

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`
- Modify as proven: `src/main/java/com/openggf/game/sonic2/objects/badniks/BuzzerBadnikInstance.java` or `CoconutsBadnikInstance.java`
- Modify: `CHANGELOG.md` only if production changes are proven necessary

- [ ] **Step 1: Add a failing-or-safety non-Masher legacy badnik test**

Choose Buzzer or Coconuts based on Task 1's inventory. Capture through `ObjectManager`, force recreation, mutate/advance, restore, recapture immediately, then advance both restored and control objects for eight frames. Assert base `BadnikRewindExtra`, subclass extra, position, velocity, animation, and facing each frame.

```java
assertEquals(capturedState.badnikExtra(), restored.captureRewindState().badnikExtra());
assertEquals(capturedState.badnikSubclassExtra(), restored.captureRewindState().badnikSubclassExtra());
ObjectManagerSnapshot recaptured = objectManager.rewindSnapshottable().capture();
assertArrayEquals(captured.usedSlotsBits(), recaptured.usedSlotsBits());
assertEquals(captured.dynamicObjects().stream().map(ObjectManagerSnapshot.DynamicObjectEntry::slotIndex).toList(),
        recaptured.dynamicObjects().stream().map(ObjectManagerSnapshot.DynamicObjectEntry::slotIndex).toList());
for (int frame = 0; frame < 8; frame++) {
    control.update(frame, player);
    restored.update(frame, player);
    assertEquals(control.captureRewindState().badnikExtra(),
            restored.captureRewindState().badnikExtra(), "base frame " + frame);
    assertEquals(control.captureRewindState().badnikSubclassExtra(),
            restored.captureRewindState().badnikSubclassExtra(), "subclass frame " + frame);
}
```

- [ ] **Step 2: Run the non-Masher test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#legacyBadnikRecreateKeepsBaseAndSubclassStateSynchronized" test
```

Expected: PASS is acceptable as safety coverage. If it fails, confirm the failure is a real base/subclass synchronization mismatch before production changes.

- [ ] **Step 3: Apply only proven non-Masher normalization**

If the non-Masher test failed because a subclass-owned authoritative container was not synchronized, use the same context-aware-overload/private-applicator pattern in that class. If it passed, make no production edit to it.

- [ ] **Step 4: Run focused badnik tests and verify GREEN**

Run:

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions,com.openggf.game.sonic2.objects.badniks.TestMasherBadnikInstance,com.openggf.game.rewind.TestRewindInPlaceObjectRestore" test
```

Expected: PASS with zero failures.

- [ ] **Step 5: Update changelog if needed and commit**

If production code changed, extend the existing unreleased changelog bullet. Commit the safety test (and any proven fix) with subject:

```text
test(rewind): cover legacy Sonic 2 badnik movement restore
```

Set `Changelog: updated` only if `CHANGELOG.md` is staged; otherwise use `Changelog: n/a: test-only safety coverage`. Use explicit `n/a` reasons for unaffected trailers.

Use a `fix(rewind): ...` subject instead if production code changed.

### Task 5: Mandatory verification and final review

**Files:**
- Verify: all files changed by Tasks 1-4

- [ ] **Step 1: Run mandatory focused and guard suites**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions,com.openggf.game.sonic2.objects.TestMonitorObjectInstance,com.openggf.game.sonic2.objects.badniks.TestMasherBadnikInstance,com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestStaticStateRewindCoverageGuard,com.openggf.game.rewind.TestRewindArchitectureGuard,com.openggf.game.rewind.TestEveryObjectRewindRoundTrip" test
```

Expected: PASS with zero failures and no new coverage-baseline entries.

- [ ] **Step 2: Run all rewind tests**

```powershell
mvn "-Dtest=*Rewind*" "-DfailIfNoTests=false" test
```

Expected: PASS with zero failures.

- [ ] **Step 3: Run the cross-game trace replay sweep**

```powershell
mvn "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test
```

Expected: PASS when required ROMs are available. If ROM/runtime prerequisites prevent completion or unrelated pre-existing traces fail, preserve the full command output and report the exact limitation; do not weaken assertions or alter trace data.

- [ ] **Step 4: Inspect scope and policy compliance**

```powershell
git status --short
git diff --check HEAD~2..HEAD
git diff --stat origin/develop...HEAD
```

Confirm the user's pre-existing `.idea/vcs.xml`, `docs/status/rewind-round-trip-gaps.md`, and `tools/bizhawk/mz2_glass_*.lua` changes remain unstaged and unmodified by this work.

- [ ] **Step 5: Request independent spec-compliance and code-quality reviews**

Dispatch a fresh reviewer with the design spec, this plan, base SHA, and head SHA. Fix every Critical or Important issue and repeat review until green.

- [ ] **Step 6: Commit review fixes if any**

Use a focused `fix(rewind): ...` or `test(rewind): ...` subject and the full required trailer block. Re-run the exact verification command affected by each review fix before committing.
