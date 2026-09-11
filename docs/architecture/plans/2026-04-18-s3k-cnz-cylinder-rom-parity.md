# S3K CNZ Cylinder ROM Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port `CNZCylinder` to match `Obj_CNZCylinder`, `sub_321E2`, and `sub_324C0` from the S3K disassembly, with shared-engine changes only if the literal object port proves the architecture is still the blocker.

**Architecture:** Rebuild `CnzCylinderInstance` around ROM state fields and per-player rider slots instead of the current synthetic movement/capture model. First port the object literally and verify each motion/rider seam with focused headless tests; only then widen into shared solid-ordering changes if the object still requires non-ROM fallbacks.

**Tech Stack:** Java, JUnit 5, Maven, existing headless test framework, S3K disassembly in `docs/skdisasm/sonic3k.asm`

---

### Task 1: Lock Down Motion-Family Regression Coverage

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Write the failing motion-family tests**

Add tests that assert representative ROM motion families rather than the current approximate behavior.

```java
@Test
void cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x00, 0, false, 0));

    int startX = cylinder.getX();
    int startY = cylinder.getY();
    for (int frame = 0; frame < 8; frame++) {
        cylinder.update(frame, fixture.sprite());
    }

    assertEquals(startX, cylinder.getX(),
            "ROM mode 0 cylinder should not drift horizontally");
    assertTrue(cylinder.getY() != startY,
            "ROM mode 0 cylinder should move via its vertical velocity controller");
}

@Test
void cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x70, 0, false, 0));

    int[] xs = new int[24];
    int[] ys = new int[24];
    for (int frame = 0; frame < 24; frame++) {
        cylinder.update(frame, fixture.sprite());
        xs[frame] = cylinder.getX();
        ys[frame] = cylinder.getY();
    }

    assertTrue(hasMovementOnBothSidesOfOrigin(xs, 0x38C0),
            "ROM circular cylinder should traverse multiple quadrants, not one fixed side");
    assertTrue(hasMovementOnBothSidesOfOrigin(ys, 0x0800),
            "ROM circular cylinder should traverse multiple vertical quadrants, not one fixed band");
}
```

- [ ] **Step 2: Run the directed CNZ cylinder tests to verify they fail**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion,TestS3kCnzDirectedTraversalHeadless#cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed" test
```

Expected: FAIL because `CnzCylinderInstance` still approximates `sub_321E2` instead of porting it literally.

- [ ] **Step 3: Add any small test helpers needed by those assertions**

If the class needs a helper, add a private test-only helper in the test file rather than in production code.

```java
private static boolean hasMovementOnBothSidesOfOrigin(int[] values, int origin) {
    boolean below = false;
    boolean above = false;
    for (int value : values) {
        if (value < origin) {
            below = true;
        } else if (value > origin) {
            above = true;
        }
    }
    return below && above;
}
```

- [ ] **Step 4: Re-run the same test slice and confirm the failures are still behavioral**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion,TestS3kCnzDirectedTraversalHeadless#cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed" test
```

Expected: FAIL in assertions, not compile/setup errors.

- [ ] **Step 5: Commit the failing test setup**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "test: add cnz cylinder motion parity regressions"
```

### Task 2: Port `sub_321E2` Motion State Literally

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Replace synthetic movement fields with ROM-style motion fields**

Introduce explicit ROM-style state instead of the current merged approximation.

```java
private int baseX;
private int baseY;
private int motionSelector;
private int standingMaskCache;
private int mode0Velocity;
private int speedCap;
private int routeQuadrant;
private int angleStep;
private int angle;
private int centerX;
private int centerY;
```

Remove fields whose only purpose is the approximation, such as the single synthetic route model.

- [ ] **Step 2: Implement the mode-0 vertical controller from `loc_32208`**

Port the ROM logic directly, including standing-mask transitions and velocity damping.

```java
private void updateMode0VerticalController(int heldInputMask) {
    int standingMask = currentStandingMask();
    if (standingMask != standingMaskCache) {
        int delta = standingMask - standingMaskCache;
        standingMaskCache = standingMask;
        if (delta > 0 && Math.abs(mode0Velocity) < 0x200) {
            mode0Velocity += 0x400;
            if (mode0Velocity > speedCap) {
                mode0Velocity = speedCap;
            }
        }
    }

    centerY += mode0Velocity >> 8;

    int offset = centerY - baseY;
    if (offset < 0) {
        if (mode0Velocity < speedCap) {
            mode0Velocity += 0x20;
            if (mode0Velocity < 0) {
                mode0Velocity += 0x10;
            } else if ((heldInputMask & BUTTON_DOWN_MASK) != 0) {
                mode0Velocity += 0x20;
            }
        }
    } else if (offset > 0) {
        int negativeCap = -speedCap;
        if (mode0Velocity > negativeCap) {
            mode0Velocity -= 0x20;
            if (mode0Velocity > 0) {
                mode0Velocity -= 0x10;
            } else if ((heldInputMask & BUTTON_UP_MASK) != 0) {
                mode0Velocity -= 0x20;
            }
        }
    } else if (Math.abs(mode0Velocity) < 0x80) {
        mode0Velocity = 0;
    }
}
```

- [ ] **Step 3: Implement the ROM horizontal/vertical sine families and circular routes**

Translate the disassembly branches into discrete helpers instead of a generic switch with shared approximations.

```java
private void updateHorizontalShift(int shift) {
    int sin = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
    centerX = baseX + (sin >> shift);
    angle = (angle + angleStep) & 0xFFFF;
}

private void updateHorizontalThreeEighths() {
    int sin = TrigLookupTable.sinHex((angle >> 8) & 0xFF);
    int shifted = sin >> 2;
    centerX = baseX + shifted + (shifted >> 1);
    angle = (angle + angleStep) & 0xFFFF;
}

private void updateCircularRoute() {
    // Port loc_323EC through loc_324AA literally.
}
```

- [ ] **Step 4: Re-run the motion regressions and confirm they pass**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderSubtype00UsesRomVerticalControllerInsteadOfGenericRouteMotion,TestS3kCnzDirectedTraversalHeadless#cnzCylinderCircularSubtypeMutatesQuadrantsInsteadOfUsingFixedRouteSeed" test
```

Expected: PASS

- [ ] **Step 5: Run the existing directed CNZ cylinder tests to check for regressions**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless" test
```

Expected: PASS except for the rider/twist tests that have not been ported yet.

- [ ] **Step 6: Commit the motion port**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "feat: port cnz cylinder motion families"
```

### Task 3: Lock Down Dual-Rider and Release-Path Parity Tests

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Add failing rider-slot and release-path tests**

Add explicit tests for per-player local state and non-jump release.

```java
@Test
void cnzCylinderMaintainsIndependentRiderStateForPlayerAndSidekick() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    AbstractPlayableSprite sidekick = GameServices.sprites().getSidekicks().getFirst();

    player.setCentreX((short) 0x38C0);
    player.setCentreY((short) 0x07F0);
    sidekick.setCentreX((short) 0x38D0);
    sidekick.setCentreY((short) 0x07F0);
    player.setAir(false);
    sidekick.setAir(false);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);

    for (int i = 0; i < 12; i++) {
        fixture.stepFrame(false, false, false, false, false);
    }

    assertTrue(player.isObjectControlled());
    assertTrue(sidekick.isObjectControlled());
    assertNotEquals(player.getMappingFrame(), sidekick.getMappingFrame(),
            "Each rider slot should track its own twist phase instead of sharing one global rider state");
}

@Test
void cnzCylinderReleasesWhenStandingContactIsLostWithoutJumpInput() {
    HeadlessTestFixture fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
            .build();

    AbstractPlayableSprite player = fixture.sprite();
    player.setCentreX((short) 0x38C0);
    player.setCentreY((short) 0x07F0);
    player.setAir(false);

    ObjectManager objectManager = GameServices.level().getObjectManager();
    CnzCylinderInstance cylinder = new CnzCylinderInstance(new ObjectSpawn(
            0x38C0, 0x0800, Sonic3kObjectIds.CNZ_CYLINDER, 0x01, 0, false, 0));
    objectManager.addDynamicObject(cylinder);

    for (int i = 0; i < 12 && !player.isObjectControlled(); i++) {
        fixture.stepFrame(false, false, false, false, false);
    }

    player.setAir(true);
    fixture.stepFrame(false, false, false, false, false);

    assertFalse(player.isObjectControlled(),
            "ROM cylinder should release when the rider is no longer in a valid standing state");
}
```

- [ ] **Step 2: Run the new rider tests and verify they fail**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderMaintainsIndependentRiderStateForPlayerAndSidekick,TestS3kCnzDirectedTraversalHeadless#cnzCylinderReleasesWhenStandingContactIsLostWithoutJumpInput" test
```

Expected: FAIL because the current rider state is still not a literal port of the ROM slot logic.

- [ ] **Step 3: Adjust test scaffolding only if the failure is environmental**

If sidekick setup requires explicit enablement in the fixture, do that in the test; do not weaken the assertions.

```java
HeadlessTestFixture fixture = HeadlessTestFixture.builder()
        .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
        .withSidekick("tails")
        .build();
```

- [ ] **Step 4: Re-run and confirm the failures remain behavioral**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderMaintainsIndependentRiderStateForPlayerAndSidekick,TestS3kCnzDirectedTraversalHeadless#cnzCylinderReleasesWhenStandingContactIsLostWithoutJumpInput" test
```

Expected: FAIL in assertions.

- [ ] **Step 5: Commit the failing rider tests**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "test: add cnz cylinder rider slot regressions"
```

### Task 4: Port `sub_324C0` Rider State Literally

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java`
- Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Replace the current rider abstraction with two ROM-shaped rider slots**

Model the player-local state explicitly.

```java
private static final class RiderSlot {
    private boolean active;
    private int twistAngle;
    private int horizontalDistance;
    private int priorityThreshold;
    private AbstractPlayableSprite player;
}

private final RiderSlot playerOneSlot = new RiderSlot();
private final RiderSlot playerTwoSlot = new RiderSlot();
```

- [ ] **Step 2: Port ROM-style capture and hold logic**

Make capture driven by valid standing contact and hold logic driven by the slot fields.

```java
private void captureSlot(RiderSlot slot, AbstractPlayableSprite player) {
    slot.player = player;
    slot.active = true;
    slot.twistAngle = player.getCentreX() < centerX ? 0x80 : 0;
    slot.horizontalDistance = Math.min(0xFF, Math.abs(player.getCentreX() - centerX));
    slot.priorityThreshold = 0;

    player.setObjectControlled(true);
    player.setControlLocked(true);
    player.setObjectMappingFrameControl(true);
    player.restoreDefaultRadii();
    player.setRolling(false);
    player.setAir(false);
    player.setPushing(false);
    player.setRollingJump(false);
    player.setJumping(false);
    player.setAnimationId(0);
    player.setXSpeed((short) 0);
    player.setYSpeed((short) 0);
    player.setGSpeed((short) 0);
}

private void holdSlot(RiderSlot slot) {
    AbstractPlayableSprite player = slot.player;
    int xOffset = (TrigLookupTable.cosHex(slot.twistAngle) * slot.horizontalDistance) >> 8;
    slot.priorityThreshold = (TrigLookupTable.sinHex(slot.twistAngle) + 0x100) >> 2;

    player.setCentreX((short) (centerX + xOffset));
    applyTwistFrame(player, slot.twistAngle);
    player.setPriorityBucket(slot.priorityThreshold <= 0x80 ? 1 : 0);
    slot.twistAngle = (slot.twistAngle + 2) & 0xFF;
}
```

- [ ] **Step 3: Port release paths for jump and invalid rider state**

Release on jump, hurt/dead, or invalid standing/contact state.

```java
private void releaseSlot(RiderSlot slot, int frameCounter, boolean jumpedOff) {
    AbstractPlayableSprite player = slot.player;
    slot.active = false;
    slot.player = null;

    player.setControlLocked(false);
    player.setObjectMappingFrameControl(false);
    player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
    player.releaseFromObjectControl(frameCounter);

    if (jumpedOff) {
        player.setJumping(true);
        player.applyRollingRadii(false);
        player.setRolling(true);
        player.setAnimationId(2);
        player.setYSpeed((short) (mode0Velocity + JUMP_RELEASE_Y_SPEED));
        player.setAir(true);
    }
}
```

- [ ] **Step 4: Re-run the rider/regression slice and verify it passes**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#cnzCylinderMaintainsIndependentRiderStateForPlayerAndSidekick,TestS3kCnzDirectedTraversalHeadless#cnzCylinderReleasesWhenStandingContactIsLostWithoutJumpInput,TestS3kCnzDirectedTraversalHeadless#cnzCylinderDrivesRomTwistFramesAndPlayerPriorityWhileCaptured,TestS3kCnzDirectedTraversalHeadless#cnzCylinderKeepsControlUntilThePlayerJumpsOut" test
```

Expected: PASS

- [ ] **Step 5: Run the full directed CNZ cylinder test class**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless" test
```

Expected: PASS

- [ ] **Step 6: Commit the rider-control port**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "feat: port cnz cylinder rider control"
```

### Task 5: Verify Initial Render/Registry Parity

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java`

- [ ] **Step 1: Align the registry render test with the ROM initial frame**

Keep the registry/render test consistent with the disassembly.

```java
assertVisibleObjectRendersExpectedInitialFrame(new CnzCylinderInstance(new ObjectSpawn(
                0x1E00, 0x0600, 0x47, 0, 0, false, 0)),
        Sonic3kObjectArtKeys.CNZ_CYLINDER, 0, 0x1E00, 0x0600);
```

- [ ] **Step 2: Run the registry/art coverage slice**

Run:

```bash
mvn "-Dtest=TestCnzTraversalObjectArt,TestCnzTraversalRegistry" test
```

Expected: PASS

- [ ] **Step 3: Commit the registry expectation update**

```bash
git add src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java
git commit -m "test: align cnz cylinder initial render parity"
```

### Task 6: Evaluate Whether Shared Solid-Ordering Work Is Still Required

**Files:**
- Inspect: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Potentially Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Potentially Modify: `src/main/java/com/openggf/level/objects/SolidObjectListener.java`
- Potentially Test: `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`

- [ ] **Step 1: Re-run the full focused CNZ verification suite after the literal object port**

Run:

```bash
mvn "-Dtest=TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt,TestCnzTraversalRegistry" test
```

Expected: PASS if the object-only port is sufficient.

- [ ] **Step 2: If and only if object-local fallbacks remain, write a failing architecture test**

Add a targeted test that proves the remaining mismatch is shared solid-ordering behavior rather than cylinder code.

```java
@Test
void solidContactStateMustBeVisibleToObjectLogicInTheSameFrame() {
    // Build the smallest possible reproduction around a same-frame standing/contact seam.
}
```

- [ ] **Step 3: Run that architecture test to confirm it fails before changing the engine**

Run:

```bash
mvn "-Dtest=TestS3kCnzDirectedTraversalHeadless#solidContactStateMustBeVisibleToObjectLogicInTheSameFrame" test
```

Expected: FAIL only if the object port still cannot express the ROM contract cleanly.

- [ ] **Step 4: Apply the smallest shared engine change that restores ROM semantics**

If needed, integrate contact publication more directly into the per-object update flow instead of adding another cylinder-specific seam.

```java
// Example direction only; implement the real minimal change after the failing test proves it.
if (instance instanceof SolidObjectProvider provider) {
    solidContacts.resolveInlineForInstance(instance, provider, currentPlayers);
}
```

- [ ] **Step 5: Re-run the architecture test and the focused CNZ suite**

Run:

```bash
mvn "-Dtest=TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt,TestCnzTraversalRegistry" test
```

Expected: PASS

- [ ] **Step 6: Commit the engine change only if it was actually required**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/level/objects/SolidObjectListener.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java
git commit -m "refactor: improve inline solid contact parity"
```

### Task 7: Final Verification

**Files:**
- Verify only

- [ ] **Step 1: Run the final focused cylinder/CNZ verification command**

Run:

```bash
mvn "-Dtest=TestS3kCnzLocalTraversalHeadless,TestS3kCnzDirectedTraversalHeadless,TestCnzTraversalObjectArt,TestCnzTraversalRegistry" test
```

Expected:

```text
MSE:OK modules=1 passed=77 failed=0 errors=0 skipped=0
```

- [ ] **Step 2: If an engine change was required, run one broader solid-object smoke slice**

Run:

```bash
mvn "-Dtest=TestS1SpikeDoubleHit,TestS2Htz1Headless,TestS3kCnzDirectedTraversalHeadless" test
```

Expected: PASS

- [ ] **Step 3: Record actual verification evidence in the handoff**

Use the exact Maven command(s) and result counts, not “should pass” wording.

- [ ] **Step 4: Commit the final integrated pass**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzCylinderInstance.java src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java src/test/java/com/openggf/game/sonic3k/objects/TestCnzTraversalRegistry.java
git commit -m "feat: complete cnz cylinder rom parity"
```
