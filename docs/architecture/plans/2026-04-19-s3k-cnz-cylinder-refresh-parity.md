# S3K CNZ Cylinder Refresh Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh `CnzCylinderInstance` so its motion, rider-slot handling, and twist/render-state behavior match `Obj_CNZCylinder` in `docs/skdisasm/sonic3k.asm`, with shared-engine work only if refreshed parity tests prove the cylinder cannot express the ROM contract locally.

**Architecture:** Keep the first implementation pass object-local. Strengthen the existing directed CNZ cylinder tests into branch-facing regressions, port `sub_321E2` and `sub_324C0` more literally inside `CnzCylinderInstance`, and only widen into `ObjectManager` ordering/publication changes if the refreshed tests still require a non-ROM fallback seam.

**Tech Stack:** Java, JUnit 5, Maven, existing headless fixture infrastructure, S3K disassembly in `docs/skdisasm/sonic3k.asm`

---

## File Map

- `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
  Purpose: object-local port of `sub_321E2` motion dispatch, rider-slot state, release branches, twist frame selection, and visible frame cadence.
- `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
  Purpose: branch-facing parity coverage for motion init, square/circular quadrants, rider capture/release gates, player/sidekick independence, and twist/priority behavior.
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
  Purpose: renderer-facing object regression coverage for visible cylinder mapping frame cadence and initial frame registration.
- `src/main/java/com/openggf/level/objects/ObjectManager.java`
  Purpose: conditional-only shared solid/contact publication follow-up if object-local parity still cannot express the standing-bit gate.
- `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`
  Purpose: conditional-only regression coverage for any shared solid/publication contract change.

## Commit Policy Note

All `git commit` steps below assume the branch-policy hooks are installed. On non-`master` branches, `prepare-commit-msg` appends the required trailer block automatically; before completing each commit, fill every trailer with `updated` or `n/a` to match the staged paths.

### Task 1: Tighten Motion Parity Tests

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Add failing tests for mode-0 init and `off_321EE` circular dispatch**

```java
@Test
void cnzCylinderMode0SeedsSpeedCapWithoutHotStartingLiveVelocity() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x00, 0, false, 0));

    assertEquals(0x04E0, getCylinderInt(cylinder, "speedCap"));
    assertEquals(0, getCylinderInt(cylinder, "mode0Velocity"),
            "ROM init stores the cap in $3E(a0) but leaves y_vel(a0) at rest");

    cylinder.update(0, fixture.sprite());

    assertEquals(0x38C0, getCylinderInt(cylinder, "centerX"));
}

@Test
void cnzCylinderCircularSubtypeFollowsSquareQuadrantTransitionsFromOff321Ee() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x49, 0, false, 0));

    int[] quadrants = new int[48];
    for (int frame = 0; frame < quadrants.length; frame++) {
        cylinder.update(frame, fixture.sprite());
        quadrants[frame] = getCylinderInt(cylinder, "routeQuadrant");
    }

    assertTrue(arrayContains(quadrants, 0));
    assertTrue(arrayContains(quadrants, 1));
    assertTrue(arrayContains(quadrants, 2));
    assertTrue(arrayContains(quadrants, 3),
            "Routines 9-12 should mutate $44(a0) through all square-route quadrants");
}
```

- [ ] **Step 2: Add minimal reflection helpers used by the new assertions**

```java
private static int getCylinderInt(CnzCylinderInstance cylinder, String fieldName) throws Exception {
    java.lang.reflect.Field field = CnzCylinderInstance.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(cylinder);
}

private static boolean arrayContains(int[] values, int expected) {
    for (int value : values) {
        if (value == expected) {
            return true;
        }
    }
    return false;
}
```

- [ ] **Step 3: Run the focused motion tests and verify they fail behaviorally**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderMode0SeedsSpeedCapWithoutHotStartingLiveVelocity+cnzCylinderCircularSubtypeFollowsSquareQuadrantTransitionsFromOff321Ee" test
```

Expected: FAIL because the current constructor hot-seeds `mode0Velocity` and the current circular route assertions are still smoke-level.

- [ ] **Step 4: Re-run the existing motion smoke tests and keep them alongside the stricter ones**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion+cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed" test
```

Expected: FAIL or PASS independently of the new stricter tests; do not delete the existing smoke coverage.

- [ ] **Step 5: Commit the failing motion test slice**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "test: tighten cnz cylinder motion parity coverage"
```

### Task 2: Port `sub_321E2` Motion More Literally

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Fix mode-0 init so the cap is stored without hot-starting live velocity**

```java
public CnzCylinderInstance(ObjectSpawn spawn) {
    super(spawn, "CNZCylinder");
    this.baseX = spawn.x();
    this.baseY = spawn.y();
    this.centerX = spawn.x();
    this.centerY = spawn.y();
    int subtype = spawn.subtype() & 0xFF;
    this.motionSelector = (subtype << 1) & 0x1E;
    this.speedCap = MODE0_SPEED_CAPS[((subtype >>> 3) & 0x0E) >>> 1];
    this.circularRoute = motionSelector >= 0x12;
    this.routeQuadrant = ((subtype & 0x0F) - 0x0A) & 0x03;
    int step = (subtype & 0xF0) << 2;
    if ((spawn.renderFlags() & 0x01) != 0) {
        step = -step;
    }
    this.angleStep = step;
    this.mode0Velocity = 0;
    this.currentYVelocity = 0;
    updateDynamicSpawn(centerX, centerY);
}
```

- [ ] **Step 2: Keep motion dispatch aligned to `off_321EE` instead of a generic route abstraction**

```java
private void updateMotion() {
    if (circularRoute) {
        updateCircularRoute();
    } else {
        switch (motionSelector) {
            case 0x00 -> updateMode0VerticalController();
            case 0x02 -> updateHorizontalShift(3);
            case 0x04 -> updateHorizontalShift(2);
            case 0x06 -> updateHorizontalThreeEighths();
            case 0x08 -> updateHorizontalShift(1);
            case 0x0A -> updateVerticalShift(3);
            case 0x0C -> updateVerticalShift(2);
            case 0x0E -> updateVerticalThreeEighths();
            case 0x10 -> updateVerticalShift(1);
            default -> updateMode0VerticalController();
        }
    }
    updateDynamicSpawn(centerX, centerY);
}
```

- [ ] **Step 3: Rewrite `updateCircularRoute()` around quadrant mutation and `$80-$FF` clamping**

```java
private void updateCircularRoute() {
    angle = (angle + angleStep) & 0xFFFF;
    int angleByte = (angle >> 8) & 0xFF;

    if (angleStep < 0 && angleByte < 0x80) {
        angleByte = (angleByte & 0x7F) + 0x80;
        angle = (angle & 0x00FF) | (angleByte << 8);
        routeQuadrant = (routeQuadrant - 1) & 0x03;
    } else if (angleStep > 0 && angleByte < 0x80) {
        angleByte = (angleByte & 0x7F) + 0x80;
        angle = (angle & 0x00FF) | (angleByte << 8);
        routeQuadrant = (routeQuadrant + 1) & 0x03;
    }

    int varying = TrigLookupTable.cosHex(angleByte) >> 3;
    switch (routeQuadrant & 0x03) {
        case 0 -> {
            centerX = baseX + varying;
            centerY = baseY - CIRCULAR_HALF_EXTENT;
        }
        case 1 -> {
            centerX = baseX + CIRCULAR_HALF_EXTENT;
            centerY = baseY + varying;
        }
        case 2 -> {
            centerX = baseX - varying;
            centerY = baseY + CIRCULAR_HALF_EXTENT;
        }
        default -> {
            centerX = baseX - CIRCULAR_HALF_EXTENT;
            centerY = baseY - varying;
        }
    }
}
```

- [ ] **Step 4: Run the stricter motion slice again and confirm it passes**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderMode0SeedsSpeedCapWithoutHotStartingLiveVelocity+cnzCylinderCircularSubtypeFollowsSquareQuadrantTransitionsFromOff321Ee+cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion+cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed" test
```

Expected: PASS

- [ ] **Step 5: Commit the motion implementation**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "fix: port cnz cylinder motion dispatch closer to rom"
```

### Task 3: Add Rider-Slot Branch Regression Tests

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

ROM ordering note for this task: `loc_32188` runs `sub_321E2`, then `sub_324C0` for each rider slot, and only then `SolidObjectFull`. Until Task 7 proves otherwise, rider-entry tests should assume `sub_324C0` is observing the standing bit published by the previous `SolidObjectFull` pass.

- [ ] **Step 1: Add a no-capture test for non-standing side contact**

```java
@Test
void cnzCylinderDoesNotCaptureFromSideContactWithoutStandingBit() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38E8, 0x0800);

    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));

    cylinder.onSolidContact(player, new SolidContact(false, true, false, false, true), 0);
    cylinder.update(0, player);

    assertFalse(player.isObjectControlled(),
            "The slot-init branch must stay closed when btst d6,status(a0) would fail");
}
```

- [ ] **Step 2: Add separate tests for standing-loss, jump-release, and forced-release paths**

```java
@Test
void cnzCylinderStandingLossClearsSlotWithoutJumpSetup() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38C0, 0x07F0);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);
    fixture.camera().updatePosition(true);

    waitForCylinderCapture(fixture, player);

    player.setJumpInputPressed(false);
    player.setAir(true);
    player.setCentreY((short) (player.getCentreY() - 0x20));
    fixture.stepFrame(false, false, false, false, false);

    assertFalse(player.isObjectControlled());
    assertFalse(player.isJumping());
    assertEquals(0, player.getYSpeed());
    assertFalse(isPlayerOneSlotActive(cylinder));
}

@Test
void cnzCylinderJumpReleaseClearsSlotOnTheJumpFrame() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38C0, 0x07F0);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);
    fixture.camera().updatePosition(true);

    waitForCylinderCapture(fixture, player);

    player.setJumpInputPressed(true);
    fixture.stepFrame(false, false, false, false, true);

    assertTrue(player.isJumping());
    assertTrue(player.getAir());
    assertTrue(player.getYSpeed() < 0);
    assertFalse(player.isObjectControlled());
    assertFalse(isPlayerOneSlotActive(cylinder));
}

@Test
void cnzCylinderForcedReleaseClearsInvalidRiderStateWithoutUsingTheJumpPath() throws Exception {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38C0, 0x07F0);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);
    fixture.camera().updatePosition(true);

    waitForCylinderCapture(fixture, player);

    player.setHurt(true);
    fixture.stepFrame(false, false, false, false, false);

    assertFalse(player.isObjectControlled());
    assertFalse(player.isJumping());
    assertEquals(0, player.getYSpeed());
    assertFalse(isPlayerOneSlotActive(cylinder));
}
```

- [ ] **Step 3: Add a tiny reflective helper for rider-slot activity**

```java
private static boolean isPlayerOneSlotActive(CnzCylinderInstance cylinder) throws Exception {
    java.lang.reflect.Field slotField = CnzCylinderInstance.class.getDeclaredField("playerOneSlot");
    slotField.setAccessible(true);
    Object slot = slotField.get(cylinder);
    java.lang.reflect.Field activeField = slot.getClass().getDeclaredField("active");
    activeField.setAccessible(true);
    return activeField.getBoolean(slot);
}

private static void waitForCylinderCapture(HeadlessTestFixture fixture, AbstractPlayableSprite player) {
    boolean captured = false;
    for (int frame = 0; frame < 32; frame++) {
        fixture.stepFrame(false, false, false, false, false);
        if (player.isObjectControlled()) {
            captured = true;
            break;
        }
    }
    assertTrue(captured, "Cylinder should capture the rider before exercising release branches");
}
```

- [ ] **Step 4: Run the rider-only slice and verify it fails for the right reasons**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderDoesNotCaptureFromSideContactWithoutStandingBit+cnzCylinderStandingLossClearsSlotWithoutJumpSetup+cnzCylinderJumpReleaseClearsSlotOnTheJumpFrame+cnzCylinderForcedReleaseClearsInvalidRiderStateWithoutUsingTheJumpPath" test
```

Expected: FAIL because the current object still uses `canFallbackCapture()`, merges release cases, and infers slot ownership heuristically.

- [ ] **Step 5: Commit the failing rider-slot slice**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "test: add cnz cylinder rider branch regressions"
```

### Task 4: Port `sub_324C0` Rider Logic More Literally

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Remove `canFallbackCapture()` and gate slot init on standing-bit state**

```java
private void updateRiderSlot(RiderSlot slot, int frameCounter) {
    AbstractPlayableSprite player = slot.player;
    if (player == null) {
        slot.active = false;
        slot.contactLatched = false;
        return;
    }
    if (player.getDead() || player.isHurt()) {
        clearSlot(slot, frameCounter, ReleaseKind.FORCED);
        return;
    }

    boolean standing = slot.contactLatched || hasStandingBit(slot);
    slot.contactLatched = false;

    if (!slot.active) {
        if (!standing || player.wasRecentlyObjectControlled(frameCounter, RECAPTURE_COOLDOWN_FRAMES)
                || player.isObjectControlled()) {
            return;
        }
        captureSlot(slot, player);
        return;
    }

    if (!riderStateIsValid(player)) {
        clearSlot(slot, frameCounter, ReleaseKind.FORCED);
    } else if (!standing) {
        clearSlot(slot, frameCounter, ReleaseKind.STANDING_LOSS);
    } else if (player.isJumpPressed()) {
        beginJumpRelease(slot, player, frameCounter);
    } else {
        holdSlot(slot);
    }
}
```

- [ ] **Step 2: Split release handling into distinct ROM-shaped branches**

```java
private enum ReleaseKind {
    STANDING_LOSS,
    FORCED,
    JUMP_RELEASE
}

private void beginJumpRelease(RiderSlot slot, AbstractPlayableSprite player, int frameCounter) {
    player.setJumping(true);
    player.applyRollingRadii(false);
    player.setRolling(true);
    player.setAnimationId(2);
    player.setYSpeed((short) (currentYVelocity + RELEASE_Y_SPEED));
    player.setXSpeed((short) 0);
    player.setGSpeed((short) 0);
    clearSlot(slot, frameCounter, ReleaseKind.JUMP_RELEASE);
}

private void clearSlot(RiderSlot slot, int frameCounter, ReleaseKind kind) {
    AbstractPlayableSprite player = slot.player;
    slot.active = false;
    if (player == null) {
        return;
    }
    player.setObjectMappingFrameControl(false);
    player.setControlLocked(false);
    player.releaseFromObjectControl(frameCounter);
    player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
    player.setAir(true);
    if (kind != ReleaseKind.JUMP_RELEASE) {
        player.setJumping(false);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
    }
}
```

- [ ] **Step 3: Replace camera/sidekick inference with explicit slot ownership rules**

```java
private RiderSlot resolveContactSlot(AbstractPlayableSprite sprite) {
    if (playerOneSlot.player == sprite) {
        return playerOneSlot;
    }
    if (playerTwoSlot.player == sprite) {
        return playerTwoSlot;
    }
    if (sprite == services().camera().getFocusedSprite()) {
        playerOneSlot.player = sprite;
        return playerOneSlot;
    }
    if (isTrackedSidekick(sprite)) {
        playerTwoSlot.player = sprite;
        return playerTwoSlot;
    }
    return null;
}
```

- [ ] **Step 4: Run the rider slice plus existing independence coverage**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderDoesNotCaptureFromSideContactWithoutStandingBit+cnzCylinderStandingLossClearsSlotWithoutJumpSetup+cnzCylinderJumpReleaseClearsSlotOnTheJumpFrame+cnzCylinderForcedReleaseClearsInvalidRiderStateWithoutUsingTheJumpPath+cnzCylinderMaintainsIndependentRiderStateForPlayerAndSidekick+cnzCylinderReleasesWhenStandingContactIsLostWithoutJumpInput+cnzCylinderKeepsControlUntilThePlayerJumpsOut" test
```

Expected: PASS

- [ ] **Step 5: Commit the rider-slot implementation**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "fix: port cnz cylinder rider state closer to rom"
```

### Task 5: Add Twist, Priority, and Visible-Frame Cadence Regressions

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`

- [ ] **Step 1: Tighten the directed twist test to assert the exact 12-frame cycle**

```java
@Test
void cnzCylinderUsesTheExactPlayerTwistFramesTableWhileCaptured() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38C0, 0x07F0);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);
    fixture.camera().updatePosition(true);
    waitForCylinderCapture(fixture, player);

    int[] expected = {0x55, 0x59, 0x5A, 0x5B, 0x5A, 0x59, 0x55, 0x56, 0x57, 0x58, 0x57, 0x56};
    int[] actual = new int[expected.length];

    for (int frame = 0; frame < expected.length; frame++) {
        fixture.stepFrame(false, false, false, false, false);
        actual[frame] = player.getMappingFrame();
    }

    assertArrayEquals(expected, actual);
}
```

- [ ] **Step 2: Add an explicit priority-threshold test and a visible-frame cadence test**

```java
@Test
void cnzCylinderDropsPriorityOnlyWhenThresholdByteFallsBelowObject35() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    prepareRiderForCylinder(player, 0x38C0, 0x07F0);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);
    fixture.camera().updatePosition(true);
    waitForCylinderCapture(fixture, player);

    int[] priorities = new int[12];
    for (int frame = 0; frame < priorities.length; frame++) {
        fixture.stepFrame(false, false, false, false, false);
        priorities[frame] = player.getPriorityBucket();
    }

    assertTrue(arrayContains(priorities, RenderPriority.PLAYER_DEFAULT));
    assertTrue(arrayContains(priorities, RenderPriority.PLAYER_DEFAULT - 1));
    assertEquals(RenderPriority.PLAYER_DEFAULT - 1, priorities[3],
            "The drop should occur only on the low-threshold half of the twist cycle");
}

@Test
public void cnzCylinderVisibleFrameAdvancesOnTheRomTwoTickCadence() throws Exception {
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x1E00, 0x0600, 0x47, 0, 0, false, 0));

    int[] frames = new int[8];
    for (int i = 0; i < frames.length; i++) {
        cylinder.update(i, null);
        frames[i] = getIntField(cylinder, "mappingFrame");
    }

    assertArrayEquals(new int[] {1, 1, 2, 2, 3, 3, 0, 0}, frames);
}
```

- [ ] **Step 3: Add any missing test helpers in the two test classes**

```java
private static int getIntField(Object target, String fieldName) throws Exception {
    java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return field.getInt(target);
}
```

- [ ] **Step 4: Run the twist/render slice and verify it fails behaviorally**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderUsesTheExactPlayerTwistFramesTableWhileCaptured+cnzCylinderDropsPriorityOnlyWhenThresholdByteFallsBelowObject35,TestCnzTraversalRegistry#cnzCylinderVisibleFrameAdvancesOnTheRomTwoTickCadence" test
```

Expected: FAIL because the current priority split still uses a heuristic threshold and the cadence is not yet locked by a test.

- [ ] **Step 5: Commit the failing twist/render slice**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java
git commit -m "test: add cnz cylinder twist and render regressions"
```

Keep the existing smoke-level twist test `cnzCylinderDrivesRomTwistFramesAndPlayerPriorityWhileCaptured` alongside these stricter assertions; strengthen coverage, do not replace it.

### Task 6: Align Twist, Priority, and Visible Frame Handling

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`

- [ ] **Step 1: Rename the rider field to `priorityThresholdSource` and define where the engine gets the ROM `$35(a0)` equivalent**

Before changing the compare, add an explicit getter and document the current source of the threshold byte. The implementer must verify whether `CnzCylinderInstance` already writes any `$35(a0)`-equivalent state; if not, add the smallest explicit field/source needed instead of reusing the old computed threshold scratch field.

```java
private static final class RiderSlot {
    private boolean active;
    private boolean contactLatched;
    private int twistAngle;
    private int horizontalDistance;
    private int priorityThresholdSource;
    private AbstractPlayableSprite player;
}

private int priorityThresholdSource;

private int getPriorityThresholdSource() {
    return priorityThresholdSource & 0xFF;
}
```

- [ ] **Step 2: Replace the priority heuristic with the ROM compare against object byte `$35(a0)`**

```java
private void holdSlot(RiderSlot slot) {
    AbstractPlayableSprite player = slot.player;
    if (player == null) {
        return;
    }

    int xOffset = (TrigLookupTable.cosHex(slot.twistAngle) * slot.horizontalDistance) >> 8;
    player.setCentreX((short) (centerX + xOffset));
    player.setXSpeed((short) 0);
    player.setYSpeed((short) 0);
    player.setGSpeed((short) 0);

    int thresholdByte = ((TrigLookupTable.sinHex(slot.twistAngle) + 0x100) >> 2) & 0xFF;
    int objectThreshold = Byte.toUnsignedInt((byte) slot.priorityThresholdSource);
    player.setPriorityBucket(thresholdByte < objectThreshold
            ? PLAYER_TWIST_PRIORITY
            : PLAYER_CAPTURE_PRIORITY);
    applyTwistFrame(player, slot.twistAngle);
    slot.twistAngle = (slot.twistAngle + 2) & 0xFF;
}
```

- [ ] **Step 3: Keep capture-state cleanup and jump-state setup aligned with the ROM branches**

```java
private void captureSlot(RiderSlot slot, AbstractPlayableSprite player) {
    slot.player = player;
    slot.active = true;
    slot.twistAngle = player.getCentreX() < centerX ? 0x80 : 0x00;
    slot.horizontalDistance = Math.min(0xFF, Math.abs(player.getCentreX() - centerX));
    slot.priorityThresholdSource = getPriorityThresholdSource();

    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setObjectMappingFrameControl(true);
    player.applyRollingRadii(false);
    player.setRolling(true);
    player.setAir(false);
    player.setPushing(false);
    player.setRollingJump(false);
    player.setJumping(false);
    player.setAnimationId(0);
}
```

- [ ] **Step 4: Start `animFrameTimer` at `0` and lock the object animation cadence to the ROM two-tick loop**

```java
private int animFrameTimer = 0;

private void advanceAnimation() {
    animFrameTimer--;
    if (animFrameTimer >= 0) {
        return;
    }
    animFrameTimer = 1;
    mappingFrame = (mappingFrame + 1) & 0x03;
}
```

- [ ] **Step 5: Run the full directed CNZ cylinder suite and the registry render checks**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalRegistry" test
```

Expected: PASS

- [ ] **Step 6: Commit the twist/render implementation**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java
git commit -m "fix: align cnz cylinder twist and render cadence with rom"
```

Keep the existing smoke-level twist test `cnzCylinderDrivesRomTwistFramesAndPlayerPriorityWhileCaptured` green here as well; the stricter cycle/threshold/cadence checks are additive.

### Task 7: Conditional Shared Solid-Pipeline Follow-Up

**Execute this task only if Tasks 4 and 6 still fail because current standing/contact publication cannot express the `btst d6,status(a0)` gate without a non-ROM fallback seam.**

Premise for escalation: in ROM `loc_32188`, `sub_324C0` runs before `SolidObjectFull`. This task is justified only if the refreshed cylinder work still needs a current-frame publication checkpoint to express that standing-bit contract without `canFallbackCapture()`.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java`
- Re-test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Add a failing sentinel that proves current-frame standing publication is still missing for traversal full-solids**

```java
@Test
void sameFrameStandingPublicationIsVisibleToTraversalObjectsBeforeFallbackLogicRuns() {
    SnapshotProbeObject probe = new SnapshotProbeObject(SnapshotProbeObject.Scenario.SAME_FRAME_STANDING, 0x120, 0x100);
    GameServices.level().getObjectManager().addDynamicObject(probe);

    player.setCentreX((short) 0x120);
    player.setCentreY((short) 0x0EE);
    player.setAir(true);
    player.setYSpeed((short) 0x100);

    fixture.stepFrame(false, false, false, false, false);

    assertTrue(probe.firstStandingNow());
    assertTrue(probe.helperStandingAfterMove());
}
```

- [ ] **Step 2: Implement the narrowest `ObjectManager` change that publishes the current standing snapshot in time for object logic**

Why this is the narrowest change: it publishes the standing snapshot before `instance.update()` observes it, without broadly reordering the rest of the shared solid pipeline.

```java
private void executeObjectWithSolidContext(ObjectInstance instance, PlayableEntity player,
        List<? extends PlayableEntity> sidekicks,
        boolean inlineSolidResolution, boolean solidPostMovement) {
    SolidExecutionRegistry registry = objectServices.solidExecutionRegistry();
    SolidExecutionMode mode = null;
    if (inlineSolidResolution && instance instanceof SolidObjectProvider provider) {
        mode = provider.solidExecutionMode();
    }

    ObjectSolidExecutionContext.Resolver resolver =
            mode == SolidExecutionMode.MANUAL_CHECKPOINT
                    ? () -> solidContacts.processManualCheckpoint(instance, player, sidekicks, solidPostMovement)
                    : null;

    registry.beginObject(instance, resolver);
    try {
        if (mode == SolidExecutionMode.AUTO_AFTER_UPDATE && !instance.isDestroyed() && !solidPostMovement) {
            registry.publishCheckpoint(
                    solidContacts.processCompatibilityCheckpoint(instance, player, sidekicks, false));
        }
        instance.update(vblaCounter, player);
        if (mode == SolidExecutionMode.AUTO_AFTER_UPDATE && !instance.isDestroyed() && solidPostMovement) {
            registry.publishCheckpoint(
                    solidContacts.processCompatibilityCheckpoint(instance, player, sidekicks, true));
        }
    } finally {
        registry.endObject(instance);
    }
}
```

- [ ] **Step 3: Re-run the sentinel plus rider tests and confirm the fallback seam is now unnecessary**

Run:

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestSolidOrderingSentinelsHeadless,TestS3kCnzDirectedTraversalHeadless#cnzCylinderDoesNotCaptureFromSideContactWithoutStandingBit+cnzCylinderStandingLossClearsSlotWithoutJumpSetup+cnzCylinderJumpReleaseClearsSlotOnTheJumpFrame" test
```

Expected: PASS

- [ ] **Step 4: Delete `canFallbackCapture(AbstractPlayableSprite)` and simplify the remaining standing check**

```java
private boolean isStandingOnCylinder(AbstractPlayableSprite player) {
    int bit = standingMaskBitFor(player);
    return bit != 0 && (standingMask & bit) != 0;
}
```

- [ ] **Step 5: Commit the conditional shared-engine fix**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "fix: publish solid standing state in time for traversal riders"
```

### Task 8: Layered Verification

**Files:**
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
- Test: `src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java` (conditional)

- [ ] **Step 1: Run the focused CNZ cylinder suites**

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalRegistry" test
```

Expected: PASS

- [ ] **Step 2: Run the broader related CNZ traversal verification**

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=TestCnzTraversalRegistry,TestCnzTraversalObjectArt,TestSonic3kObjectArtProvider,TestS3kCnzDirectedTraversalHeadless" test
```

Expected: PASS

- [ ] **Step 3: Run the solid-ordering sentinel suite if Task 7 was used**

Skip this step entirely if Task 7 was not executed.

```bash
mvn "-Dtest=TestSolidOrderingSentinelsHeadless" test
```

Expected: PASS

- [ ] **Step 4: Run the full Maven test suite**

```bash
mvn "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" test
```

Expected: PASS. If unrelated failures remain, record the exact failing test class names in the validation note before treating the run as blocked.

- [ ] **Step 5: Commit any final verification-only test/doc touchups**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java src/test/java/com/openggf/tests/TestSolidOrderingSentinelsHeadless.java
git commit -m "test: verify refreshed cnz cylinder rom parity"
```

## Self-Review

- Spec coverage:
  Motion parity is covered by Tasks 1-2.
  Rider-slot capture/release branches are covered by Tasks 3-4.
  Twist, priority, and visible-frame cadence are covered by Tasks 5-6.
  Conditional shared-engine escalation is isolated in Task 7.
  Layered verification is captured in Task 8, with Task 8 Step 3 skipped when Task 7 is not used.
- Placeholder scan:
  No placeholder steps remain; each task includes concrete test code, implementation snippets, commands, and expected outcomes.
- Type consistency:
  The plan uses existing class/file names from the current branch and keeps the shared-engine follow-up scoped to `ObjectManager` plus the sentinel suite.
