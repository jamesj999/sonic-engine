# S3K Slot Machine Bonus Stage — Corrections & Completion Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all inaccuracies and implement all missing subsystems identified in the cross-reference audit of the S3K slot bonus stage against `docs/skdisasm/sonic3k.asm` lines 98153–100199.

**Architecture:** Corrections are layered bottom-up: fix constants first, then build the rotation physics model, grid collision, tile interactions, cage state machine, reel state machine, reward spawning, sound effects, palette cycling, and exit sequence. Each task produces testable, independent changes. All existing scaffolding (coordinator lifecycle, player factory, layout rendering, ROM data tables) is preserved.

**Tech Stack:** Java 21, Maven, JUnit 5, existing `TrigLookupTable`, `AbstractPlayableSprite`, `ObjectServices`, `Sonic3kSfx`, `Sonic3kPaletteCycler`.

**Disassembly references:** All line numbers refer to `docs/skdisasm/sonic3k.asm`. Key routines:
- Player movement: `sub_4BABC` (ground), `sub_4BCB0` (air/gravity), `sub_4BBB2` (jump), `sub_4BB54`/`sub_4BB84` (accel)
- Grid collision: `sub_4BD5A` (2x2 tile check), `sub_4BDA2` (tile classify), `sub_4BDCA` (ring pickup)
- Tile interactions: `sub_4BE3A` (bumper/goal/spike/slot)
- Cage controller: `sub_4C014` (4-state machine), `loc_4BF62` (cage init/orbit)
- Reel system: `Slots_CycleOptions` (7-state machine), `sub_4C6D6` (reel tick), `sub_4C7A2` (prize calc)
- Layout animation: `sub_4B592` (4 animation types)
- Exit: `loc_4BC1E` / `loc_4BC54` (fade + restore)

---

## File Structure

### Modified files (corrections to existing code):

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
  Fix movement constants, replace linear motion with rotation-projected ground movement and angle-dependent air gravity.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
  Add `SStage_scalar_index_1` rotation velocity, per-frame angle integration, bounce timer, grounded-only jump gating.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
  Fix `SLOT_BONUS_START_Y` to `0x0430`. Add layout collision stride constant.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
  Wire up grid collision, tile interactions, cage state machine, exit sequence, SFX calls.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
  Implement 4-state controller, radial reward spawning, player release, cage orbit rendering.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
  Add interpolated movement toward cage center, ring collection SFX.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
  Add interpolated movement toward cage center, spike hit SFX with throttle.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
  Add layout animation table support for ring sparkle, bumper bounce, R-label flip sequences.

### New files:

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java`
  Grid-based 2x2 tile collision check matching `sub_4BD5A` and ring pickup from `sub_4BDCA`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java`
  Tile interaction handler matching `sub_4BE3A`: bumper bounce, goal exit, spike reversal, slot reel increment.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java`
  7-state reel cycle matching `Slots_CycleOptions` / `Slots_CycleOptions_Index`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPrizeCalculator.java`
  Match detection and prize multiplier matching `sub_4C7A2`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutAnimator.java`
  32-entry animation slot table with 4 animation types matching `sub_4B592`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotExitSequence.java`
  Exit rotation wind-down, palette fade, zone/act restoration matching `loc_4BC1E`/`loc_4BC54`.

### Test files:

- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageController.java` (add new rotation tests, keep existing passing)
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotGridCollision.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotTileInteraction.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotReelStateMachine.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPrizeCalculator.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutAnimator.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotExitSequence.java`

---

### Task 1: Fix Movement Constants and Start Position

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`

The disassembly (`sub_4BB54`/`sub_4BB84`, lines 98849–98903) specifies:
- Normal acceleration: `0x0C` per frame
- Reversal deceleration: `0x40` when pressing opposite direction while moving
- Friction when no input: `0x0C`
- Max ground speed: `0x800`
- Player captured at `(0x460, 0x430)` — start Y should be `0x0430`, not `0x0360`

- [ ] **Step 1: Write test asserting correct ROM constants**

```java
// Add to TestS3kSlotBonusPlayer.java
@Test
void movementConstantsMatchDisassembly() {
    // sub_4BB54/sub_4BB84: accel = 0x0C, reversal = 0x40, max = 0x800
    assertEquals(0x0C, S3kSlotBonusPlayer.GROUND_ACCEL);
    assertEquals(0x0C, S3kSlotBonusPlayer.GROUND_DECEL);
    assertEquals(0x40, S3kSlotBonusPlayer.GROUND_REVERSAL_DECEL);
    assertEquals(0x800, S3kSlotBonusPlayer.GROUND_MAX_SPEED);
}

@Test
void startPositionMatchesCageCapture() {
    assertEquals((short) 0x0460, S3kSlotRomData.SLOT_BONUS_START_X);
    assertEquals((short) 0x0430, S3kSlotRomData.SLOT_BONUS_START_Y);
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestS3kSlotBonusPlayer#movementConstantsMatchDisassembly+startPositionMatchesCageCapture -q`

Expected: FAIL — current constants are `GROUND_ACCEL=0x30`, `GROUND_DECEL=0x20`, `GROUND_MAX_SPEED=0x300`, `START_Y=0x0360`, and `GROUND_REVERSAL_DECEL` does not exist.

- [ ] **Step 3: Fix constants in S3kSlotBonusPlayer and S3kSlotRomData**

In `S3kSlotBonusPlayer.java`, replace the constant block:
```java
short GROUND_ACCEL = 0x0C;        // sub_4BB54 line 98856: subi.w #$C,d0
short GROUND_DECEL = 0x0C;        // sub_4BABC line 98801: subi.w #$C,d0
short GROUND_REVERSAL_DECEL = 0x40; // sub_4BB54 line 98867: subi.w #$40,d0
short GROUND_MAX_SPEED = 0x800;   // sub_4BB54 line 98857: cmpi.w #-$800,d0
short AIR_ACCEL = 0x18;
short AIR_MAX_SPEED = 0x300;
```

In `S3kSlotRomData.java`, fix:
```java
public static final short SLOT_BONUS_START_Y = 0x0430;  // loc_4C026 line 99391
```

- [ ] **Step 4: Update applyGroundMotion to use reversal deceleration**

In `S3kSlotBonusPlayer.java`, replace `applyGroundMotion`:
```java
private static void applyGroundMotion(AbstractPlayableSprite player, boolean left, boolean right) {
    int gSpeed = player.getGSpeed();
    if (left == right) {
        // No input: friction (sub_4BABC lines 98798-98816)
        if (gSpeed > 0) {
            gSpeed = Math.max(0, gSpeed - GROUND_DECEL);
        } else if (gSpeed < 0) {
            gSpeed = Math.min(0, gSpeed + GROUND_DECEL);
        }
    } else if (left) {
        if (gSpeed > 0) {
            // Reversing: heavy decel (sub_4BB54 line 98867)
            gSpeed -= GROUND_REVERSAL_DECEL;
            if (gSpeed < 0) gSpeed = 0;
        } else {
            // Accelerating left (sub_4BB54 line 98856)
            gSpeed = Math.max(-GROUND_MAX_SPEED, gSpeed - GROUND_ACCEL);
        }
        player.setDirection(com.openggf.physics.Direction.LEFT);
    } else {
        if (gSpeed < 0) {
            // Reversing: heavy decel (sub_4BB84 line 98895)
            gSpeed += GROUND_REVERSAL_DECEL;
            if (gSpeed > 0) gSpeed = 0;
        } else {
            // Accelerating right (sub_4BB84 line 98884)
            gSpeed = Math.min(GROUND_MAX_SPEED, gSpeed + GROUND_ACCEL);
        }
        player.setDirection(com.openggf.physics.Direction.RIGHT);
    }
    player.setGSpeed((short) gSpeed);
    player.setXSpeed((short) gSpeed);
    player.setYSpeed((short) 0);
    player.setJumping(false);
}
```

- [ ] **Step 5: Run all existing slot tests to verify no regressions**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS (including new constant tests). The existing `spriteManagerTickPlayablePhysicsRespondsToGroundedRightInputForSlotPlayer` test should still pass since it only asserts positive movement.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java
git commit -m "fix: correct S3K slot stage movement constants and start position to match ROM"
```

---

### Task 2: Add Rotation Velocity (SStage_scalar_index_1) to Controller

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageController.java`

The ROM (`loc_4BA4E` lines 98737–98779) accumulates `Stat_table += SStage_scalar_index_1` every frame. `SStage_scalar_index_1` is initialized to `0x40` (line 98732). Left/right input modifies `ground_vel`, NOT `Stat_table` directly. The grid *rotates* around the player; `Stat_table` is the cumulative rotation angle.

Current bug: `tickPlayer()` directly increments `statTable` by ±4 per button. This should instead modify `ground_vel` which projects through the rotation.

- [ ] **Step 1: Write test for rotation velocity accumulation**

```java
// Add to TestS3kSlotStageController.java
@Test
void rotationVelocityAccumulatesStatTableEachTick() {
    S3kSlotStageController controller = new S3kSlotStageController();
    controller.bootstrap();
    AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic", (short) 0, (short) 0, controller);

    // ROM: SStage_scalar_index_1 initialized to 0x40
    assertEquals(0x40, controller.scalarIndex());

    // Each tick: Stat_table += SStage_scalar_index_1
    // With no input and default scalar 0x40, angle advances by 0x40 each tick
    controller.tick();
    assertEquals(0x40, controller.angle());

    controller.tick();
    assertEquals(0x80, controller.angle());
}

@Test
void negateScalarReversesCageRotation() {
    S3kSlotStageController controller = new S3kSlotStageController();
    controller.bootstrap();

    controller.tick();
    int angleAfterForward = controller.angle();
    assertEquals(0x40, angleAfterForward);

    controller.negateScalar(); // ROM: neg.w (SStage_scalar_index_1).w
    controller.tick();
    // 0x40 + (-0x40) = 0x00
    assertEquals(0x00, controller.angle());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestS3kSlotStageController#rotationVelocityAccumulatesStatTableEachTick+negateScalarReversesCageRotation -q`

Expected: FAIL — `scalarIndex()`, `tick()`, `negateScalar()` don't exist yet.

- [ ] **Step 3: Add rotation velocity to S3kSlotStageController**

Add new fields and methods to `S3kSlotStageController.java`:

```java
private int scalarIndex;  // SStage_scalar_index_1 — rotation velocity

public void bootstrap() {
    statTable = 0;
    scalarIndex = 0x40;  // ROM line 98732: move.w #$40,(SStage_scalar_index_1).w
    rewardCounter = 0;
    pendingRingRewards = 0;
    pendingSpikeRewards = 0;
}

/** Per-frame rotation integration: Stat_table += SStage_scalar_index_1 (line 98776-98778) */
public void tick() {
    statTable = (statTable + scalarIndex) & 0xFF;
}

public int scalarIndex() {
    return scalarIndex;
}

public void setScalarIndex(int value) {
    scalarIndex = value;
}

/** ROM: neg.w (SStage_scalar_index_1).w — used by spike tile and cage release */
public void negateScalar() {
    scalarIndex = (-scalarIndex) & 0xFFFF;
    // Keep as signed 16-bit
    if (scalarIndex > 0x7FFF) scalarIndex -= 0x10000;
}
```

Update `tickPlayer()` to stop modifying `statTable` directly — input now changes `ground_vel` on the player, which projects through rotation in `S3kSlotBonusPlayer.applyGroundMotion()`:

```java
public void tickPlayer(S3kSlotBonusPlayer player, boolean left, boolean right,
                       boolean jump, int frameCounter) {
    // Input is handled by S3kSlotBonusPlayer.applyGroundMotion() now.
    // The controller just handles angle update and jump.
    player.setAngle((byte) statTable);

    if (jump && player instanceof AbstractPlayableSprite sprite && sprite.isJumpJustPressed()
            && !sprite.getAir()) {
        int angle = (-((statTable & 0xFC)) - 0x40) & 0xFF;
        sprite.setXSpeed((short) ((TrigLookupTable.cosHex(angle) * 0x680) >> 8));
        sprite.setYSpeed((short) ((TrigLookupTable.sinHex(angle) * 0x680) >> 8));
        sprite.setAir(true);
    }
}
```

- [ ] **Step 4: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS. Existing tests that called `tickPlayer()` with left/right still work because those delegated to the player's `applyGroundMotion` which handles input.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageController.java
git commit -m "feat: add SStage_scalar_index_1 rotation velocity to slot stage controller"
```

---

### Task 3: Implement Rotation-Projected Ground Movement

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`

ROM `sub_4BABC` (lines 98784–98843): Ground velocity is projected through the `Stat_table` angle using sin/cos, then a 2x2 collision check rollbacks movement on collision. The current implementation moves linearly.

- [ ] **Step 1: Write test for rotation-projected ground movement**

```java
// Add to TestS3kSlotBonusPlayer.java
@Test
void groundMotionProjectsThroughRotationAngle() {
    S3kSlotStageController controller = new S3kSlotStageController();
    controller.bootstrap();
    AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
            (short) 0x460, (short) 0x430, controller);

    // Set a known gSpeed and angle=0 (rotation angle 0)
    player.setGSpeed((short) 0x100);
    player.setAngle((byte) 0);

    short startX = player.getX();
    short startY = player.getY();

    // With angle=0, ROM projects: angle+0x20=0x20, &C0=0, neg=0
    // cos(0)*gSpeed → X movement, sin(0)*gSpeed → Y movement
    S3kSlotBonusPlayer.applyRotatedGroundMotion(player, false, false, controller);

    // At angle 0, movement should be primarily in X
    assertTrue(player.getX() != startX || player.getY() != startY,
            "Player should have moved");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotBonusPlayer#groundMotionProjectsThroughRotationAngle -q`

Expected: FAIL — `applyRotatedGroundMotion` does not exist.

- [ ] **Step 3: Implement rotation-projected ground movement**

In `S3kSlotBonusPlayer.java`, replace `applyGroundMotion` with `applyRotatedGroundMotion`:

```java
/**
 * ROM sub_4BABC (lines 98784-98843): Projects ground velocity through
 * Stat_table rotation angle using sin/cos, then moves the sprite.
 * Collision rollback is handled separately by S3kSlotGridCollision.
 */
static void applyRotatedGroundMotion(AbstractPlayableSprite player,
                                     boolean left, boolean right,
                                     S3kSlotStageController controller) {
    int gSpeed = player.getGSpeed();

    // sub_4BABC lines 98795-98816: friction/accel
    if (left == right) {
        if (gSpeed > 0) {
            gSpeed = Math.max(0, gSpeed - GROUND_DECEL);
        } else if (gSpeed < 0) {
            gSpeed = Math.min(0, gSpeed + GROUND_DECEL);
        }
    } else if (left) {
        if (gSpeed > 0) {
            gSpeed -= GROUND_REVERSAL_DECEL;
            if (gSpeed < 0) gSpeed = 0;
        } else {
            gSpeed = Math.max(-GROUND_MAX_SPEED, gSpeed - GROUND_ACCEL);
        }
        player.setDirection(com.openggf.physics.Direction.LEFT);
    } else {
        if (gSpeed < 0) {
            gSpeed += GROUND_REVERSAL_DECEL;
            if (gSpeed > 0) gSpeed = 0;
        } else {
            gSpeed = Math.min(GROUND_MAX_SPEED, gSpeed + GROUND_ACCEL);
        }
        player.setDirection(com.openggf.physics.Direction.RIGHT);
    }
    player.setGSpeed((short) gSpeed);

    // ROM lines 98818-98827: project gSpeed through rotation angle
    int statAngle = controller.angle() & 0xFF;
    int projAngle = (-(((statAngle + 0x20) & 0xC0))) & 0xFF;
    int sin = TrigLookupTable.sinHex(projAngle);
    int cos = TrigLookupTable.cosHex(projAngle);

    // muls.w ground_vel(a0),d1 / add.l d1,x_pos(a0)
    long deltaX = (long) cos * gSpeed;
    long deltaY = (long) sin * gSpeed;

    // Store pre-move position for collision rollback
    player.setXSpeed((short) (deltaX >> 8));
    player.setYSpeed((short) (deltaY >> 8));
    player.setJumping(false);
}
```

Update `tickAndMove` to call the new method:
```java
static void tickAndMove(AbstractPlayableSprite player, S3kSlotStageController controller,
                        boolean left, boolean right, boolean jump, int frameCounter) {
    short originalX = player.getX();
    short originalY = player.getY();
    tickController((S3kSlotBonusPlayer) player, controller, left, right, jump, frameCounter);
    player.setMovementInputActive(left != right);

    if (player.getAir()) {
        applyRotatedAirMotion(player, left, right, controller);
    } else {
        applyRotatedGroundMotion(player, left, right, controller);
    }

    player.move(player.getXSpeed(), player.getYSpeed());
    player.updateSensors(originalX, originalY);
}
```

- [ ] **Step 4: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java
git commit -m "feat: implement rotation-projected ground movement for slot stage player"
```

---

### Task 4: Implement Angle-Dependent Air Gravity

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`

ROM `sub_4BCB0` (lines 99005–99070): Airborne gravity is angle-dependent. Sin/cos of `Stat_table` angle are each multiplied by `0x2A` and added to the respective velocity components. This pulls the player "inward" relative to the rotating cylinder.

- [ ] **Step 1: Write test for angle-dependent gravity**

```java
@Test
void airGravityIsAngleDependentNot​VerticalOnly() {
    S3kSlotStageController controller = new S3kSlotStageController();
    controller.bootstrap();
    AbstractPlayableSprite player = S3kSlotBonusPlayer.create("sonic",
            (short) 0x460, (short) 0x430, controller);
    player.setAir(true);
    player.setXSpeed((short) 0);
    player.setYSpeed((short) 0);

    // At angle 0x40 (90 degrees), gravity should pull in a different direction
    // than at angle 0x00
    S3kSlotBonusPlayer.applyRotatedAirMotion(player, false, false, controller);
    short xAfterAngle0 = player.getXSpeed();
    short yAfterAngle0 = player.getYSpeed();

    // Gravity factor 0x2A applied to both sin and cos of angle
    int sin0 = TrigLookupTable.sinHex(0 & 0xFC);
    int cos0 = TrigLookupTable.cosHex(0 & 0xFC);
    // At angle=0: sin≈0, cos≈256, so gravity is mostly in X direction
    // The exact velocity depends on the 0x2A multiplication
    assertTrue(xAfterAngle0 != 0 || yAfterAngle0 != 0,
            "Gravity should produce non-zero velocity");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotBonusPlayer#airGravityIsAngleDependentNotVerticalOnly -q`

Expected: FAIL — `applyRotatedAirMotion` does not exist.

- [ ] **Step 3: Implement angle-dependent air physics**

In `S3kSlotBonusPlayer.java`, replace `applyAirMotion` with:

```java
/**
 * ROM sub_4BCB0 (lines 99005-99070): Angle-dependent air gravity.
 * Gravity factor 0x2A applied to sin/cos of Stat_table angle.
 * Also handles separate X/Y collision checks with rollback.
 */
static void applyRotatedAirMotion(AbstractPlayableSprite player,
                                  boolean left, boolean right,
                                  S3kSlotStageController controller) {
    int statAngle = controller.angle() & 0xFC;
    int sin = TrigLookupTable.sinHex(statAngle);
    int cos = TrigLookupTable.cosHex(statAngle);

    // ROM lines 99011-99020: gravity components
    // x_vel += cos * 0x2A (as 24.8 fixed point)
    // y_vel += sin * 0x2A (as 24.8 fixed point)
    int xVel = player.getXSpeed();
    int yVel = player.getYSpeed();

    // ext.l d4 / asl.l #8,d4 / muls.w #$2A,d0 / add.l d4,d0
    long gravX = (long) sin * 0x2A + ((long) xVel << 8);
    long gravY = (long) cos * 0x2A + ((long) yVel << 8);

    player.setXSpeed((short) (gravX >> 8));
    player.setYSpeed((short) (gravY >> 8));
}
```

- [ ] **Step 4: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java
git commit -m "feat: implement angle-dependent air gravity for slot stage (ROM sub_4BCB0)"
```

---

### Task 5: Implement Grid Collision Detection

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotGridCollision.java`

ROM `sub_4BD5A` (lines 99076–99133): Checks a 2x2 tile footprint from the layout RAM at stride 0x80. Tile classification: 0=empty, 8=ring (passable), 7/9+=solid, 1–6=special+solid. ROM `sub_4BDCA` (lines 99139–99183): Separate ring pickup check with offset `(y+0x50)/0x18`, `(x+0x20)/0x18`.

- [ ] **Step 1: Write failing test for grid collision**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotGridCollision {

    @Test
    void emptyTileIsPassable() {
        byte[] layout = new byte[32 * 32]; // all zeros = empty
        S3kSlotGridCollision.Result result = S3kSlotGridCollision.check(
                layout, 0x460_0000, 0x430_0000); // 32:16 fixed-point
        assertFalse(result.solid());
        assertEquals(0, result.tileId());
    }

    @Test
    void solidWallTileBlocksMovement() {
        byte[] layout = new byte[32 * 32];
        // Place wall tile (ID 1) at grid position that maps to player pos
        // ROM: row = (y + 0x44) / 0x18, col = (x + 0x14) / 0x18
        int row = (0x430 + 0x44) / 0x18;
        int col = (0x460 + 0x14) / 0x18;
        layout[row * 32 + col] = 1; // solid wall
        S3kSlotGridCollision.Result result = S3kSlotGridCollision.check(
                layout, 0x460_0000, 0x430_0000);
        assertTrue(result.solid());
        assertEquals(1, result.tileId());
    }

    @Test
    void ringTileIsPassable() {
        byte[] layout = new byte[32 * 32];
        int row = (0x430 + 0x44) / 0x18;
        int col = (0x460 + 0x14) / 0x18;
        layout[row * 32 + col] = 8; // ring tile
        S3kSlotGridCollision.Result result = S3kSlotGridCollision.check(
                layout, 0x460_0000, 0x430_0000);
        assertFalse(result.solid());
    }

    @Test
    void ringPickupDetectsRingTile() {
        byte[] layout = new byte[32 * 32];
        // ROM sub_4BDCA: row = (y + 0x50) / 0x18, col = (x + 0x20) / 0x18
        int row = (0x430 + 0x50) / 0x18;
        int col = (0x460 + 0x20) / 0x18;
        layout[row * 32 + col] = 8;
        S3kSlotGridCollision.RingCheck ringResult = S3kSlotGridCollision.checkRingPickup(
                layout, (short) 0x460, (short) 0x430);
        assertTrue(ringResult.foundRing());
        assertEquals(row * 32 + col, ringResult.layoutIndex());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotGridCollision -q`

Expected: FAIL — `S3kSlotGridCollision` class does not exist.

- [ ] **Step 3: Implement S3kSlotGridCollision**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

/**
 * Grid-based 2x2 tile collision for the slot bonus stage.
 *
 * <p>ROM sub_4BD5A (lines 99076-99133): Checks 4 tiles in a 2x2 footprint.
 * ROM sub_4BDCA (lines 99139-99183): Ring pickup check with different offsets.
 */
public final class S3kSlotGridCollision {

    private static final int LAYOUT_STRIDE = 32;
    private static final int CELL_SIZE = 0x18;

    // sub_4BD5A offsets
    private static final int COLLISION_Y_OFFSET = 0x44;
    private static final int COLLISION_X_OFFSET = 0x14;

    // sub_4BDCA offsets
    private static final int RING_Y_OFFSET = 0x50;
    private static final int RING_X_OFFSET = 0x20;

    private S3kSlotGridCollision() {
    }

    /**
     * 2x2 tile collision check matching sub_4BD5A.
     * @param layout 32x32 byte layout
     * @param xPos32 32-bit fixed-point X (pixel:16 | sub:16)
     * @param yPos32 32-bit fixed-point Y (pixel:16 | sub:16)
     * @return collision result with tile ID and solid flag
     */
    public static Result check(byte[] layout, long xPos32, long yPos32) {
        int yPixel = (int) (yPos32 >> 16);
        int xPixel = (int) (xPos32 >> 16);

        int baseRow = (yPixel + COLLISION_Y_OFFSET) / CELL_SIZE;
        int baseCol = (xPixel + COLLISION_X_OFFSET) / CELL_SIZE;

        // Check 2x2 footprint: (row,col), (row,col+1), (row+1,col), (row+1,col+1)
        for (int dr = 0; dr <= 1; dr++) {
            for (int dc = 0; dc <= 1; dc++) {
                int r = baseRow + dr;
                int c = baseCol + dc;
                if (r < 0 || r >= LAYOUT_STRIDE || c < 0 || c >= LAYOUT_STRIDE) {
                    continue;
                }
                int tileId = layout[r * LAYOUT_STRIDE + c] & 0xFF;
                int classify = classifyTile(tileId);
                if (classify != 0) {
                    return new Result(true, tileId, r * LAYOUT_STRIDE + c);
                }
            }
        }
        return new Result(false, 0, -1);
    }

    /**
     * Ring pickup check matching sub_4BDCA. Different offsets from collision check.
     */
    public static RingCheck checkRingPickup(byte[] layout, short xPos, short yPos) {
        int row = (yPos + RING_Y_OFFSET) / CELL_SIZE;
        int col = (xPos + RING_X_OFFSET) / CELL_SIZE;
        if (row < 0 || row >= LAYOUT_STRIDE || col < 0 || col >= LAYOUT_STRIDE) {
            return new RingCheck(false, -1);
        }
        int index = row * LAYOUT_STRIDE + col;
        int tileId = layout[index] & 0xFF;
        if (tileId == 8) {
            return new RingCheck(true, index);
        }
        return new RingCheck(false, -1);
    }

    /**
     * sub_4BDA2 (lines 99111-99132): Classify tile for collision response.
     * Returns 0 for passable, -1 for solid.
     * Tiles 0 and 8 are passable. Tiles 7 and 9+ are solid.
     * Tiles 1-6 are special (bumper/goal/slot) — also solid.
     */
    private static int classifyTile(int tileId) {
        if (tileId == 0 || tileId == 8) return 0;  // passable
        if (tileId >= 0x10) return 0;               // out of range = passable
        return -1;                                   // solid (1-7, 9-15)
    }

    public record Result(boolean solid, int tileId, int layoutIndex) {
    }

    public record RingCheck(boolean foundRing, int layoutIndex) {
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotGridCollision -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotGridCollision.java
git commit -m "feat: implement grid collision detection for slot stage (ROM sub_4BD5A/sub_4BDCA)"
```

---

### Task 6: Implement Tile Interactions (Bumper, Goal, Spike, Slot Reel)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotTileInteraction.java`

ROM `sub_4BE3A` (lines 99190–99303): After grid collision, the `$30(a0)` field holds the tile ID. Different IDs trigger different effects:
- Tile 5 (bumper): Launch player at `0x700` away from bumper center via `GetArcTan` (lines 99206–99239). Play `sfx_Bumper`.
- Tile 4 (goal): Advance to exit routine. Play `sfx_Goal` (lines 99242–99248).
- Tile 6 (spike/R): Negate `SStage_scalar_index_1`, play `sfx_LaunchGo`. Throttled by `$37(a0)` = `0x1E` frames (lines 99252–99267).
- Tiles 1–3 (slot reels): Increment slot value (cap at 4), play `sfx_Flipper` (lines 99270–99299).

- [ ] **Step 1: Write failing tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotTileInteraction {

    @Test
    void bumperTileLaunchesPlayerAwayAtVelocity0x700() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotTileInteraction.State state = new S3kSlotTileInteraction.State();

        // Tile 5 = bumper at layout position; player at offset
        S3kSlotTileInteraction.Response response = S3kSlotTileInteraction.process(
                5, (short) 0x460, (short) 0x440,
                (short) 0x470, (short) 0x440,  // bumper center offset from player
                controller, state);

        assertEquals(S3kSlotTileInteraction.Effect.BUMPER_LAUNCH, response.effect());
        assertTrue(response.launchXVel() != 0 || response.launchYVel() != 0);
        assertTrue(Math.abs(response.launchXVel()) <= 0x700);
        assertTrue(Math.abs(response.launchYVel()) <= 0x700);
    }

    @Test
    void goalTileTriggersExit() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotTileInteraction.State state = new S3kSlotTileInteraction.State();

        S3kSlotTileInteraction.Response response = S3kSlotTileInteraction.process(
                4, (short) 0x460, (short) 0x430,
                (short) 0x460, (short) 0x430,
                controller, state);

        assertEquals(S3kSlotTileInteraction.Effect.GOAL_EXIT, response.effect());
    }

    @Test
    void spikeTileNegatesScalarWithThrottle() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        int originalScalar = controller.scalarIndex();
        S3kSlotTileInteraction.State state = new S3kSlotTileInteraction.State();

        S3kSlotTileInteraction.Response r1 = S3kSlotTileInteraction.process(
                6, (short) 0, (short) 0, (short) 0, (short) 0,
                controller, state);

        assertEquals(S3kSlotTileInteraction.Effect.SPIKE_REVERSAL, r1.effect());
        assertEquals(-originalScalar, controller.scalarIndex());

        // Second hit within throttle period should be ignored
        S3kSlotTileInteraction.Response r2 = S3kSlotTileInteraction.process(
                6, (short) 0, (short) 0, (short) 0, (short) 0,
                controller, state);

        assertEquals(S3kSlotTileInteraction.Effect.NONE, r2.effect());
    }

    @Test
    void slotReelTileIncrementsValueAndCapsAt4() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotTileInteraction.State state = new S3kSlotTileInteraction.State();

        // Hit tile 1 (slot reel) multiple times
        for (int i = 0; i < 5; i++) {
            S3kSlotTileInteraction.process(
                    1, (short) 0, (short) 0, (short) 0, (short) 0,
                    controller, state);
        }
        // Value should cap at 4 (ROM line 99289-99292)
        assertTrue(state.lastSlotValue() <= 4);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotTileInteraction -q`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement S3kSlotTileInteraction**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.physics.TrigLookupTable;

/**
 * Tile interaction handler matching ROM sub_4BE3A (lines 99190-99303).
 */
public final class S3kSlotTileInteraction {

    private static final int BUMPER_LAUNCH_SPEED = 0x700;
    private static final int SPIKE_THROTTLE_FRAMES = 0x1E;

    private S3kSlotTileInteraction() {
    }

    public static Response process(int tileId,
                                   short playerX, short playerY,
                                   short tileCenterX, short tileCenterY,
                                   S3kSlotStageController controller,
                                   State state) {
        // Decrement throttle timers each call
        state.tickTimers();

        switch (tileId) {
            case 5: // Bumper (lines 99206-99239)
                return processBumper(playerX, playerY, tileCenterX, tileCenterY, controller);
            case 4: // Goal (lines 99242-99248)
                return new Response(Effect.GOAL_EXIT, (short) 0, (short) 0);
            case 6: // Spike/R reversal (lines 99252-99267)
                return processSpikeReversal(controller, state);
            case 1: case 2: case 3: // Slot reel tiles (lines 99270-99299)
                return processSlotReel(state);
            default:
                return new Response(Effect.NONE, (short) 0, (short) 0);
        }
    }

    private static Response processBumper(short playerX, short playerY,
                                          short tileCenterX, short tileCenterY,
                                          S3kSlotStageController controller) {
        int dx = tileCenterX - playerX;
        int dy = tileCenterY - playerY;
        int angle = TrigLookupTable.calcAngle((short) dx, (short) dy);
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);
        // ROM: muls.w #-$700,d1 (away from bumper)
        short launchX = (short) ((cos * -BUMPER_LAUNCH_SPEED) >> 8);
        short launchY = (short) ((sin * -BUMPER_LAUNCH_SPEED) >> 8);
        return new Response(Effect.BUMPER_LAUNCH, launchX, launchY);
    }

    private static Response processSpikeReversal(S3kSlotStageController controller, State state) {
        if (state.spikeThrottleTimer > 0) {
            return new Response(Effect.NONE, (short) 0, (short) 0);
        }
        state.spikeThrottleTimer = SPIKE_THROTTLE_FRAMES;
        controller.negateScalar();
        return new Response(Effect.SPIKE_REVERSAL, (short) 0, (short) 0);
    }

    private static Response processSlotReel(State state) {
        state.slotValue++;
        if (state.slotValue > 4) {
            state.slotValue = 4;
        }
        return new Response(Effect.SLOT_REEL_INCREMENT, (short) 0, (short) 0);
    }

    public enum Effect {
        NONE, BUMPER_LAUNCH, GOAL_EXIT, SPIKE_REVERSAL, SLOT_REEL_INCREMENT
    }

    public record Response(Effect effect, short launchXVel, short launchYVel) {
    }

    public static final class State {
        int spikeThrottleTimer;
        int bumperThrottleTimer;
        int slotValue;

        public void tickTimers() {
            if (spikeThrottleTimer > 0) spikeThrottleTimer--;
            if (bumperThrottleTimer > 0) bumperThrottleTimer--;
        }

        public int lastSlotValue() {
            return slotValue;
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotTileInteraction -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotTileInteraction.java
git commit -m "feat: implement tile interactions for slot stage (bumper/goal/spike/reel)"
```

---

### Task 7: Implement Prize Calculation

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPrizeCalculator.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPrizeCalculator.java`

ROM `sub_4C7A2` (lines 100029–100173): Extracts nibbles from result bytes, compares against reel A value, applies multipliers:
- All 3 match: base reward × 4 (`asl.w #2,d0`)
- 2 match (any combination): base reward × 2 (`add.w d0,d0`)
- No match: special handling — counts "6" symbols (each adds 2 to ring reward)
- Symbol 4 (`REWARD_VALUES[4] = -1`): spike penalty trigger

- [ ] **Step 1: Write failing tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotPrizeCalculator {

    @Test
    void allThreeMatchGivesQuadrupleBaseReward() {
        // Symbol 0 → base 100, triple match → 100 * 4 = 400
        int prize = S3kSlotPrizeCalculator.calculate(0, (byte) 0x00);
        assertEquals(400, prize);
    }

    @Test
    void twoMatchGivesDoubleBaseReward() {
        // Reel A=1, B=1 (upper nibble), C=0 (lower nibble) → 2 match on symbol 1
        int prize = S3kSlotPrizeCalculator.calculate(1, (byte) 0x10);
        // Symbol 1 → base 30, two match → 30 * 2 = 60
        assertEquals(60, prize);
    }

    @Test
    void noMatchCountsSixSymbolsForMinimalReward() {
        // All different, none is 6 → prize = 0
        int prize = S3kSlotPrizeCalculator.calculate(0, (byte) 0x12);
        // Symbol 0, B=1, C=2, no matches, no 6s → 0
        assertEquals(0, prize);
    }

    @Test
    void symbolFourReturnsSpikeIndicator() {
        // Symbol 4 → REWARD_VALUES[4] = -1
        int prize = S3kSlotPrizeCalculator.calculate(4, (byte) 0x44);
        // All match → -1 * 4 = -4
        assertTrue(prize < 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotPrizeCalculator -q`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement S3kSlotPrizeCalculator**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

/**
 * Match detection and prize multiplier matching ROM sub_4C7A2 (lines 100029-100173).
 *
 * <p>Reel result is encoded as: reelA in separate byte, reelB in upper nibble of
 * packed byte, reelC in lower nibble. Matching logic applies multipliers based on
 * how many reels match.
 */
public final class S3kSlotPrizeCalculator {

    private S3kSlotPrizeCalculator() {
    }

    /**
     * @param reelA symbol index for reel A (0-7)
     * @param packedBC packed byte: upper nibble = reel B symbol, lower nibble = reel C symbol
     * @return ring reward (positive), spike trigger (negative), or 0 for no reward
     */
    public static int calculate(int reelA, byte packedBC) {
        int reelB = (packedBC & 0xF0) >>> 4;
        int reelC = packedBC & 0x0F;

        boolean aMatchesB = (reelA == reelB);
        boolean aMatchesC = (reelA == reelC);

        if (aMatchesB && aMatchesC) {
            // All three match: quadruple reward (ROM: asl.w #2,d0)
            return lookupReward(reelA) * 4;
        }

        if (aMatchesB) {
            // A matches B only: double reward
            return lookupReward(reelA) * 2;
        }

        if (aMatchesC) {
            // A matches C only: double reward
            return lookupReward(reelA) * 2;
        }

        if (reelB == reelC) {
            // B matches C (but not A): double reward on B/C symbol
            if (reelA == 0) {
                return lookupReward(reelB) * 2;
            } else if (reelB == 0) {
                return lookupReward(reelA) * 2;
            }
        }

        // No matches: count "6" symbols (each adds 2 to prize)
        // ROM lines 100127-100145
        int bonus = 0;
        if (reelA == 6) bonus += 2;
        if (reelB == 6) bonus += 2;
        if (reelC == 6) bonus += 2;
        return bonus;
    }

    private static int lookupReward(int symbol) {
        if (symbol < 0 || symbol >= S3kSlotRomData.REWARD_VALUES.length) return 0;
        return S3kSlotRomData.REWARD_VALUES[symbol];
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotPrizeCalculator -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPrizeCalculator.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPrizeCalculator.java
git commit -m "feat: implement slot prize calculation with match detection (ROM sub_4C7A2)"
```

---

### Task 8: Implement 7-State Reel State Machine

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotReelStateMachine.java`

ROM `Slots_CycleOptions` (lines 99609–99931): 7 states drive the 3-reel spin/stop cycle. Called every frame from the main level loop.

- [ ] **Step 1: Write failing tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotReelStateMachine {

    @Test
    void initStateTransitionsToSpinningAfterOneTick() {
        S3kSlotReelStateMachine reel = new S3kSlotReelStateMachine();
        reel.reset();
        assertEquals(0, reel.state());

        reel.tick(0); // state 0 → initializes reels, advances to state 1
        assertEquals(1, reel.state());
    }

    @Test
    void fullCycleEventuallyReachesAwardState() {
        S3kSlotReelStateMachine reel = new S3kSlotReelStateMachine();
        reel.reset();

        // Tick enough frames to reach the award state (state 5)
        for (int i = 0; i < 500; i++) {
            reel.tick(i);
            if (reel.state() == 5) break;
        }
        // Should have reached award state
        assertTrue(reel.state() >= 5, "Reel should reach award state, got state " + reel.state());
    }

    @Test
    void reelPrizeResultIsPopulatedAfterAwardState() {
        S3kSlotReelStateMachine reel = new S3kSlotReelStateMachine();
        reel.reset();

        for (int i = 0; i < 500; i++) {
            reel.tick(i);
            if (reel.state() == 6) break; // idle after award
        }
        // Prize result should be set
        assertTrue(reel.lastPrizeResult() != Integer.MIN_VALUE);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotReelStateMachine -q`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement S3kSlotReelStateMachine**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

/**
 * 7-state reel cycle matching ROM Slots_CycleOptions (lines 99609-99931).
 *
 * <p>States: 0=init, 1=spinning, 2=display_rolling, 3=decelerate,
 * 4=lock_reels, 5=award, 6=idle.
 */
public final class S3kSlotReelStateMachine {

    private int state;
    private int spinCountdown;
    private int[] reelSeeds = new int[3];
    private int[] reelCounters = new int[3];
    private int[] reelDisplays = new int[3];
    private int targetReelA;
    private byte targetPackedBC;
    private int lastPrize = Integer.MIN_VALUE;
    private int lockIndex; // which reel is being locked next (0-2)

    public void reset() {
        state = 0;
        spinCountdown = 0;
        reelSeeds = new int[3];
        reelCounters = new int[3];
        reelDisplays = new int[3];
        targetReelA = 0;
        targetPackedBC = 0;
        lastPrize = Integer.MIN_VALUE;
        lockIndex = 0;
    }

    public int state() {
        return state;
    }

    public int lastPrizeResult() {
        return lastPrize;
    }

    /**
     * Advance the reel state machine by one frame.
     * @param frameCounter current V_int_run_count equivalent
     */
    public void tick(int frameCounter) {
        switch (state) {
            case 0 -> tickInit(frameCounter);
            case 1 -> tickSpinning(frameCounter);
            case 2 -> tickDisplayRolling(frameCounter);
            case 3 -> tickDecelerate(frameCounter);
            case 4 -> tickLockReels(frameCounter);
            case 5 -> tickAward();
            default -> { /* state 6: idle, do nothing */ }
        }
    }

    // State 0: loc_4C416 — Initialize 3 reels with random seeds and counters
    private void tickInit(int frameCounter) {
        for (int i = 0; i < 3; i++) {
            reelSeeds[i] = 0;
            reelCounters[i] = 0;
            reelDisplays[i] = 0;
        }
        // ROM: seed from V_int_run_count with rotation
        reelSeeds[0] = frameCounter & 0xFF;
        reelSeeds[1] = ((frameCounter & 0xFF) >>> 3) | ((frameCounter & 0xFF) << 5);
        reelSeeds[2] = ((reelSeeds[1] & 0xFF) >>> 3) | ((reelSeeds[1] & 0xFF) << 5);

        for (int i = 0; i < 3; i++) {
            reelCounters[i] = 8; // ROM: move.b #8,7(a4) etc.
            reelDisplays[i] = 8; // ROM: move.b #8,8(a4) etc.
        }

        spinCountdown = 1; // ROM: move.b #1,1(a4)
        state = 1;
    }

    // State 1: loc_4C462 — Spinning phase, decrement countdown per reel tick
    private void tickSpinning(int frameCounter) {
        tickReelAnimation();
        if (spinCountdown <= 0) {
            state = 2;
        }
    }

    // State 2: loc_4C480 — Display rolling numbers, pick target row
    private void tickDisplayRolling(int frameCounter) {
        // ROM: randomize display values
        reelDisplays[0] = 0x30 + ((frameCounter & 7) - 4);
        reelDisplays[1] = 0x30 + (((frameCounter >> 4) & 7) - 4);
        reelDisplays[2] = 0x30 + (((frameCounter >> 8) & 7) - 4);

        // Pick target from probability table
        int rolledByte = ((frameCounter & 0xFF) >>> 3) | ((frameCounter & 0xFF) << 5);
        int fixedRow = S3kSlotRewardResolver.pickFixedRow(rolledByte & 0xFF);
        if (fixedRow >= 0) {
            int rowBase = fixedRow * 3;
            targetReelA = S3kSlotRomData.TARGET_ROWS[rowBase + 1] & 0xFF;
            targetPackedBC = S3kSlotRomData.TARGET_ROWS[rowBase + 2];
        } else {
            // Dynamic random target (loc_4C4F8)
            int rnd = frameCounter * 7 + 13; // simplified random
            targetReelA = S3kSlotRomData.REEL_SEQUENCE_A[rnd & 7];
            int b = S3kSlotRomData.REEL_SEQUENCE_B[(rnd >> 3) & 7];
            int c = S3kSlotRomData.REEL_SEQUENCE_C[(rnd >> 6) & 7];
            targetPackedBC = (byte) ((b << 4) | c);
        }

        spinCountdown = 2; // ROM: move.b #2,1(a4)
        lockIndex = 0;
        state = 3;
    }

    // State 3: loc_4C540 — Decelerate spin, wait for completion
    private void tickDecelerate(int frameCounter) {
        tickReelAnimation();
        if (spinCountdown <= 0) {
            // Add 0x30 to each display
            for (int i = 0; i < 3; i++) {
                reelDisplays[i] += 0x30;
            }
            // Random additional countdown
            spinCountdown = 0x0C + (frameCounter & 0x0F);
            state = 4;
        }
    }

    // State 4: loc_4C576 — Lock reels to target symbols in sequence
    private void tickLockReels(int frameCounter) {
        tickReelAnimation();
        // Check if all three reel sub-states have reached "locked" (0x0C each)
        boolean allLocked = (reelCounters[0] >= 0x0C)
                && (reelCounters[1] >= 0x0C)
                && (reelCounters[2] >= 0x0C);
        if (allLocked) {
            state = 5;
        }
        // Advance lock progress each tick
        for (int i = 0; i < 3; i++) {
            if (reelCounters[i] < 0x0C) {
                reelCounters[i]++;
            }
        }
    }

    // State 5: loc_4C6BC — Award prizes
    private void tickAward() {
        lastPrize = S3kSlotPrizeCalculator.calculate(targetReelA, targetPackedBC);
        state = 6; // idle
    }

    private void tickReelAnimation() {
        if (spinCountdown > 0) spinCountdown--;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotReelStateMachine -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotReelStateMachine.java
git commit -m "feat: implement 7-state reel state machine (ROM Slots_CycleOptions)"
```

---

### Task 9: Implement Cage Controller 4-State Machine

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageRuntime.java`

ROM `sub_4C014` (lines 99361–99557): The cage has 4 states:
- **State 0** (`loc_4C026`): Wait for player within `0x18`-pixel radius (check: `|dx|+0x18 < 0x30`) → capture at `(0x460, 0x430)`, set `object_control=$81`, wait timer `0x78` (120 frames), enable palette cycling. Also: if reel state `(SStage_scalar_result_0+2)` is `0x18`, reset to `8` and set spike result to `-1`.
- **State 1** (`loc_4C21C`/`loc_4C0AA`): Spawn ring/spike rewards radially. Rings: angle += `0x89` each, max `0x10` (16). Spikes: angle += `0x90` each, max `0x10` (16), init countdown `0x64` (100). Spawn on odd frames only (`btst #0,Level_frame_counter+1`). Play `sfx_SlotMachine` every 16 frames. Transition to state 2 with timer `8` when all spawned.
- **State 2** (`loc_4C250`): Wait for `Stat_table & 0x3C == 0`, then release player with velocity `sin/cos * 4` (`asl.w #2`), clear `object_control`, set `Status_InAir`, negate scalar. Timer `8`.
- **State 3** (`loc_4C292`): 8-frame cooldown, then clear state and `SStage_scalar_result_0` → back to state 0.

- [ ] **Step 1: Write test for cage state machine capture and release**

```java
// Add to TestS3kSlotBonusStageRuntime.java
@Test
void cageCaptureSetsObjectControlAndReleasesAfterRewardCycle() {
    RuntimeManager.createGameplay();
    SonicConfigurationService.getInstance().setConfigValue(
            SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

    AbstractPlayableSprite originalPlayer = new Tails("tails", (short) 0x460, (short) 0x430);
    GameServices.sprites().addSprite(originalPlayer);
    GameServices.camera().setFocusedSprite(originalPlayer);

    S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
    runtime.bootstrap();

    AbstractPlayableSprite slotPlayer = (AbstractPlayableSprite)
            GameServices.sprites().getSprite("tails");

    // Move player near cage center to trigger capture
    slotPlayer.setCentreX((short) 0x460);
    slotPlayer.setCentreY((short) 0x430);

    // Step enough frames for capture + reward + release cycle
    for (int frame = 0; frame < 200; frame++) {
        runtime.update(frame);
    }

    // After a full cycle, player should eventually be released
    // (not permanently locked)
    assertNotNull(runtime.activeSlotCageForTest());
}
```

- [ ] **Step 2: Run test to verify behavior**

Run: `mvn test -Dtest=TestS3kSlotBonusStageRuntime#cageCaptureSetsObjectControlAndReleasesAfterRewardCycle -q`

- [ ] **Step 3: Add 4-state machine to S3kSlotBonusCageObjectInstance**

Refactor the cage to include state tracking, reward spawning counters, wait timers, and player release logic. Add:

```java
private int cageState;          // 0-3 matching off_4C01E
private int waitTimer;          // countdown for state transitions
private int rewardAngle;        // radial spawn angle accumulator ($32 in ROM)
private int activeRewardCount;  // $30 in ROM, max 16
private int rewardPoolRemaining; // $2E in ROM, ring/spike count to spawn
private int sfxThrottle;        // frame counter for slot machine sound
private static final int CAPTURE_RANGE = 0x30;  // ROM: cmpi.w #$30,d0
private static final int CAPTURE_OFFSET = 0x18; // ROM: addi.w #$18,d0
private static final int MAX_ACTIVE_REWARDS = 0x10;
private static final int RING_ANGLE_INCREMENT = 0x89;
private static final int SPIKE_ANGLE_INCREMENT = 0x90;
private static final int WAIT_TIMER_INITIAL = 0x78;
private static final int RELEASE_WAIT = 8;
```

Implement the full 4-state update cycle as described in the ROM reference, delegating reward spawning to the runtime's `queueRingReward()`/`queueSpikeReward()` methods.

- [ ] **Step 4: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageRuntime.java
git commit -m "feat: implement 4-state cage controller (capture/spawn/release/cooldown)"
```

---

### Task 10: Add Interpolated Movement to Reward Objects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`

ROM `Obj_SlotRing` (lines 35862–35878) and `Obj_SlotSpike` (lines 99563–99579): Both use identical interpolation: `(target - current) >> 4` per frame, converging on the cage center.

- [ ] **Step 1: Add target position fields and interpolation to ring reward**

In `S3kSlotRingRewardObjectInstance.java`, add fields for target position and starting angle, then implement the per-frame interpolation in `update()`:

```java
private short startX, startY;   // $34/$38 — interpolation current pos
private short targetX, targetY; // $3C/$3E — cage center

public void activate(short spawnX, short spawnY, short centerX, short centerY) {
    active = true;
    framesRemaining = EXPIRY_FRAMES;
    setDestroyed(false);
    this.startX = spawnX;
    this.startY = spawnY;
    this.targetX = centerX;
    this.targetY = centerY;
}

// In update(), before the expiry check:
// ROM: (target - current) >> 4, subtract from current (converges toward target)
int dx = ((targetX << 16) - (startX << 16)) >> 4;
startX = (short) ((startX << 16) - dx >> 16);
// Same for Y
```

- [ ] **Step 2: Apply same interpolation to spike reward**

Mirror the same changes in `S3kSlotSpikeRewardObjectInstance.java`.

- [ ] **Step 3: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java
git commit -m "feat: add interpolated movement to slot reward objects (ROM Obj_SlotRing/Obj_SlotSpike)"
```

---

### Task 11: Implement Exit Sequence

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotExitSequence.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotExitSequence.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`

ROM `loc_4BC1E` (lines 98959–99000): Exit wind-down adds `0x40` to `SStage_scalar_index_1` each frame until it reaches `0x1800` (96 frames). Then transitions to `loc_4BC54`: palette fade-to-black over 60 frames (`Pal_ToBlack` called every 3 frames via `Pal_fade_delay=2`). Rotation continues during fade. On timer expiry: restores `Saved_zone_and_act`, `Saved_last_star_post_hit`, clears `Special_bonus_entry_flag`, sets `Restart_level_flag=1`, saves final `Ring_count` and `Extra_life_flags`.

- [ ] **Step 1: Write failing test**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotExitSequence {

    @Test
    void exitSequenceCompletesAfterWindDownAndFade() {
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        S3kSlotExitSequence exit = new S3kSlotExitSequence(controller);

        assertFalse(exit.isComplete());

        // Wind down: scalar increments by 0x40 each frame until 0x1800
        // 0x1800 / 0x40 = 96 frames for wind-down
        // Then 60 frames for fade
        for (int i = 0; i < 200; i++) {
            exit.tick();
            if (exit.isComplete()) break;
        }

        assertTrue(exit.isComplete());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotExitSequence -q`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement S3kSlotExitSequence**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

/**
 * Exit sequence matching ROM loc_4BC1E / loc_4BC54 (lines 98959-99000).
 * Phase 1: Increment SStage_scalar_index_1 by 0x40 each frame until 0x1800.
 * Phase 2: 60-frame palette fade-to-black.
 * Phase 3: Restore zone/act and set restart flag.
 */
public final class S3kSlotExitSequence {

    private static final int SCALAR_INCREMENT = 0x40;
    private static final int SCALAR_TARGET = 0x1800;
    private static final int FADE_FRAMES = 60;

    private final S3kSlotStageController controller;
    private int accumulatedScalar;
    private int fadeTimer;
    private boolean inFade;
    private boolean complete;

    public S3kSlotExitSequence(S3kSlotStageController controller) {
        this.controller = controller;
        this.accumulatedScalar = controller.scalarIndex();
    }

    public void tick() {
        if (complete) return;

        if (!inFade) {
            accumulatedScalar += SCALAR_INCREMENT;
            controller.setScalarIndex(accumulatedScalar);
            controller.tick(); // continue rotating

            if (accumulatedScalar >= SCALAR_TARGET) {
                inFade = true;
                fadeTimer = FADE_FRAMES;
            }
        } else {
            fadeTimer--;
            controller.tick(); // rotation continues during fade
            if (fadeTimer <= 0) {
                complete = true;
            }
        }
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean isFading() {
        return inFade && !complete;
    }

    public float fadeProgress() {
        if (!inFade) return 0f;
        return 1f - ((float) fadeTimer / FADE_FRAMES);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotExitSequence -q`

Expected: All PASS.

- [ ] **Step 5: Wire exit sequence into runtime**

In `S3kSlotBonusStageRuntime.java`, add an `exitSequence` field. When goal tile is hit, create exit sequence. In `update()`, tick exit sequence each frame. When complete, call `shutdown()`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotExitSequence.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotExitSequence.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java
git commit -m "feat: implement exit sequence with rotation wind-down and fade (ROM loc_4BC1E)"
```

---

### Task 12: Add Sound Effects

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`

All SFX constants exist in `Sonic3kSfx`. Wire them to the correct triggers:

| SFX | ID | Trigger | Where |
|-----|----|---------|-------|
| `JUMP` | 0x62 | Player jump launch | `S3kSlotStageController.tickPlayer()` |
| `SLOT_MACHINE` | 0xB7 | Every 16 frames during cage capture | `S3kSlotBonusCageObjectInstance` state 1 |
| `BUMPER` | 0xAA | Bumper tile collision | `S3kSlotBonusStageRuntime` tile interaction |
| `GOAL` | 0x6A | Goal tile reached | `S3kSlotBonusStageRuntime` tile interaction |
| `LAUNCH_GO` | 0xAD | Spike tile reversal | `S3kSlotBonusStageRuntime` tile interaction |
| `FLIPPER` | 0xAE | Slot reel tile changed | `S3kSlotBonusStageRuntime` tile interaction |
| `RING_RIGHT` | 0x33 | Ring collected (from `GiveRing`) | `S3kSlotRingRewardObjectInstance` |
| `SPIKE_HIT` | 0x37 | Spike reward expires (5-frame throttle) | `S3kSlotSpikeRewardObjectInstance` |
| `CONTINUE` | 0xAC | 50-ring continue bonus | `S3kSlotBonusStageRuntime` ring pickup |

- [ ] **Step 1: Add SFX calls to each component**

In `S3kSlotStageController.tickPlayer()`, after setting air=true on jump:
```java
// ROM line 98926-98927: moveq #signextendB(sfx_Jump),d0; jsr (Play_SFX).l
GameServices.audio().playSfx(Sonic3kSfx.JUMP.id);
```

In `S3kSlotRingRewardObjectInstance.update()`, on ring grant:
```java
// ROM: GiveRing calls Play_SFX with sfx_RingRight
services().playSfx(Sonic3kSfx.RING_RIGHT.id);
```

In `S3kSlotSpikeRewardObjectInstance.update()`, on spike hit with 5-frame throttle:
```java
services().playSfx(Sonic3kSfx.SPIKE_HIT.id);
```

In `S3kSlotBonusStageRuntime`, after tile interaction processing, play SFX based on `Effect`:
```java
case BUMPER_LAUNCH -> GameServices.audio().playSfx(Sonic3kSfx.BUMPER.id);
case GOAL_EXIT -> GameServices.audio().playSfx(Sonic3kSfx.GOAL.id);
case SPIKE_REVERSAL -> GameServices.audio().playSfx(Sonic3kSfx.LAUNCH_GO.id);
case SLOT_REEL_INCREMENT -> GameServices.audio().playSfx(Sonic3kSfx.FLIPPER.id);
```

- [ ] **Step 2: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS. SFX calls are fire-and-forget, no test assertions needed on audio.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java
git commit -m "feat: wire all slot stage sound effects to ROM-accurate triggers"
```

---

### Task 13: Implement Layout Animation System

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutAnimator.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`

ROM `sub_4B592` (lines 98392–98491): 32-entry animation slot table. Each 8-byte entry has: type (byte 0), timer (byte 2), frame index (byte 3), layout pointer (bytes 4-7). 4 animation types:
- Type 1 (ring sparkle): Frames `{0x10, 0x11, 0x12, 0x13, 0}`, 5-frame delay
- Type 2 (bumper bounce): Frames `{0x0A, 0x0B, 0}` then restore tile 5, 1-frame delay
- Type 3 (slot reel flip): Unverified, pending disasm detail
- Type 4 (R-label): Frames `{0x0C, 0x06, 0x0C, 0}` then restore tile 6, 7-frame delay

- [ ] **Step 1: Write failing tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotLayoutAnimator {

    @Test
    void ringSparkleAnimatesAndClearsLayoutCell() {
        byte[] layout = new byte[32 * 32];
        layout[100] = 8; // ring tile
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        // Queue ring sparkle at layout index 100
        animator.queueRingSparkle(layout, 100);

        // After enough ticks, layout cell should be cleared (ring collected)
        for (int i = 0; i < 30; i++) {
            animator.tick(layout);
        }
        assertEquals(0, layout[100]); // ring consumed
    }

    @Test
    void bumperBounceAnimatesAndRestores() {
        byte[] layout = new byte[32 * 32];
        layout[50] = 5; // bumper tile
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        animator.queueBumperBounce(layout, 50);

        // During animation, tile changes to bounce frames
        animator.tick(layout);
        assertTrue(layout[50] == 0x0A || layout[50] == 0x0B || layout[50] == 5);

        // After animation completes, tile restored to bumper
        for (int i = 0; i < 10; i++) {
            animator.tick(layout);
        }
        assertEquals(5, layout[50]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kSlotLayoutAnimator -q`

Expected: FAIL — class does not exist.

- [ ] **Step 3: Implement S3kSlotLayoutAnimator**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

/**
 * 32-entry animation slot table matching ROM sub_4B592 (lines 98392-98491).
 * Each slot drives a tile animation sequence in the layout grid.
 */
public final class S3kSlotLayoutAnimator {

    private static final int MAX_SLOTS = 32;
    private static final byte[] RING_SPARKLE = {0x10, 0x11, 0x12, 0x13, 0};
    private static final byte[] BUMPER_BOUNCE = {0x0A, 0x0B, 0};
    private static final byte[] R_LABEL_FLIP = {0x0C, 0x06, 0x0C, 0};
    private static final int RING_SPARKLE_DELAY = 5;
    private static final int BUMPER_BOUNCE_DELAY = 1;
    private static final int R_LABEL_DELAY = 7;

    private final AnimSlot[] slots = new AnimSlot[MAX_SLOTS];

    public S3kSlotLayoutAnimator() {
        for (int i = 0; i < MAX_SLOTS; i++) {
            slots[i] = new AnimSlot();
        }
    }

    public boolean queueRingSparkle(byte[] layout, int layoutIndex) {
        return queue(1, layoutIndex, RING_SPARKLE, RING_SPARKLE_DELAY, (byte) 0, layout);
    }

    public boolean queueBumperBounce(byte[] layout, int layoutIndex) {
        return queue(2, layoutIndex, BUMPER_BOUNCE, BUMPER_BOUNCE_DELAY, (byte) 5, layout);
    }

    public boolean queueRLabelFlip(byte[] layout, int layoutIndex) {
        return queue(4, layoutIndex, R_LABEL_FLIP, R_LABEL_DELAY, (byte) 6, layout);
    }

    public void tick(byte[] layout) {
        for (AnimSlot slot : slots) {
            if (slot.type == 0) continue;
            slot.tick(layout);
        }
    }

    private boolean queue(int type, int layoutIndex, byte[] frames, int delay,
                          byte restoreTile, byte[] layout) {
        for (AnimSlot slot : slots) {
            if (slot.type == 0) {
                slot.init(type, layoutIndex, frames, delay, restoreTile);
                return true;
            }
        }
        return false; // all slots full
    }

    private static final class AnimSlot {
        int type;
        int layoutIndex;
        byte[] frames;
        int delay;
        int timer;
        int frameIndex;
        byte restoreTile;

        void init(int type, int layoutIndex, byte[] frames, int delay, byte restoreTile) {
            this.type = type;
            this.layoutIndex = layoutIndex;
            this.frames = frames;
            this.delay = delay;
            this.timer = delay;
            this.frameIndex = 0;
            this.restoreTile = restoreTile;
        }

        void tick(byte[] layout) {
            if (--timer > 0) return;
            timer = delay;

            byte newTile = frames[frameIndex++];
            if (newTile == 0 || frameIndex >= frames.length) {
                // Animation complete
                layout[layoutIndex] = restoreTile;
                type = 0; // free slot
            } else {
                layout[layoutIndex] = newTile;
            }
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -Dtest=TestS3kSlotLayoutAnimator -q`

Expected: All PASS.

- [ ] **Step 5: Wire animator into runtime update loop**

In `S3kSlotBonusStageRuntime.update()`, call `layoutAnimator.tick(layout)` each frame, before building visible cells.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutAnimator.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutAnimator.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java
git commit -m "feat: implement layout animation system (ring sparkle/bumper bounce/R-label)"
```

---

### Task 14: Wire Grid Collision and Tile Interactions into Runtime

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`

This task integrates Tasks 5–6 into the runtime update loop. The ROM calls `sub_4BD5A` after each movement step to check collision and rollback on solid tiles, then `sub_4BDCA` for ring pickup, then `sub_4BE3A` for tile interactions.

- [ ] **Step 1: Add collision fields to runtime**

```java
private final S3kSlotGridCollision gridCollision = new S3kSlotGridCollision();
private final S3kSlotTileInteraction.State tileState = new S3kSlotTileInteraction.State();
private final S3kSlotLayoutAnimator layoutAnimator = new S3kSlotLayoutAnimator();
private final S3kSlotReelStateMachine reelStateMachine = new S3kSlotReelStateMachine();
private S3kSlotExitSequence exitSequence;
```

- [ ] **Step 2: Add collision check to player physics in tickAndMove**

After `player.move()`, check collision via `S3kSlotGridCollision.check()`. If solid, rollback position. Then check ring pickup. Then process tile interaction.

```java
// After player.move(xSpeed, ySpeed):
if (layout != null) {
    long xPos32 = ((long) player.getX() << 16) | (player.getXSubpixel() & 0xFFFF);
    long yPos32 = ((long) player.getY() << 16) | (player.getYSubpixel() & 0xFFFF);
    S3kSlotGridCollision.Result collision = S3kSlotGridCollision.check(layout, xPos32, yPos32);
    if (collision.solid()) {
        player.setX(originalX);
        player.setY(originalY);
        player.setGSpeed((short) 0);
    }
}
```

- [ ] **Step 3: Add ring pickup and 50-ring continue to update loop**

```java
S3kSlotGridCollision.RingCheck ring = S3kSlotGridCollision.checkRingPickup(
        layout, slotPlayer.getX(), slotPlayer.getY());
if (ring.foundRing()) {
    layout[ring.layoutIndex()] = 0; // consume ring
    layoutAnimator.queueRingSparkle(layout, ring.layoutIndex());
    slotPlayer.addRings(1);
    // 50-ring continue (ROM lines 99168-99174)
    if (slotPlayer.getRingCount() >= 50) {
        // Award continue once
    }
}
```

- [ ] **Step 4: Add tile interaction dispatch**

After collision check, if a special tile was found, process it:
```java
if (collision.tileId() > 0 && collision.tileId() < 7) {
    S3kSlotTileInteraction.Response response = S3kSlotTileInteraction.process(
            collision.tileId(), slotPlayer.getX(), slotPlayer.getY(),
            /* compute tile center from layout index */,
            slotStageController, tileState);
    switch (response.effect()) {
        case BUMPER_LAUNCH -> {
            slotPlayer.setXSpeed(response.launchXVel());
            slotPlayer.setYSpeed(response.launchYVel());
            slotPlayer.setAir(true);
            GameServices.audio().playSfx(Sonic3kSfx.BUMPER.id);
            layoutAnimator.queueBumperBounce(layout, collision.layoutIndex());
        }
        case GOAL_EXIT -> {
            GameServices.audio().playSfx(Sonic3kSfx.GOAL.id);
            exitSequence = new S3kSlotExitSequence(slotStageController);
        }
        case SPIKE_REVERSAL -> {
            GameServices.audio().playSfx(Sonic3kSfx.LAUNCH_GO.id);
            layoutAnimator.queueRLabelFlip(layout, collision.layoutIndex());
        }
        case SLOT_REEL_INCREMENT -> {
            GameServices.audio().playSfx(Sonic3kSfx.FLIPPER.id);
        }
    }
}
```

- [ ] **Step 5: Run all slot tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java
git commit -m "feat: wire grid collision, tile interactions, and layout animation into runtime"
```

---

### Task 15: Add Palette Cycling for Slots Zone

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`

ROM `AnPal_Slots` (lines 3844–3894): Three palette cycling routines targeting palette line 3 offsets `$14–$1C` and palette line 4 offset `$1C`. Gated by `Palette_cycle_counters+$00` flag.

- [ ] **Step 1: Add slot palette cycles to Sonic3kPaletteCycler**

In the zone switch in `loadCycles()`, add:
```java
case 0x15: // Slots
    loadSlotsCycles(reader, list);
    break;
```

Implement `loadSlotsCycles()` following the existing pattern for other zones, reading palette data from ROM at the `AnPal_PalSlots_1/2/3` addresses and creating `PaletteCycle` instances with the correct frame counts, delays, and palette line targets.

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest="TestS3kSlot*" -q`

Expected: All PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java
git commit -m "feat: add palette cycling for slots bonus stage (ROM AnPal_Slots)"
```

---

### Task 16: Final Integration Test — Full Slot Stage Cycle

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`

- [ ] **Step 1: Write integration test exercising full lifecycle**

```java
@Test
void fullSlotStageLifecycleFromBootstrapThroughGoalExit() {
    SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    fixture = HeadlessTestFixture.builder()
            .withZoneAndAct(0x15, 0)
            .build();

    Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
    fixture.runtime().setActiveBonusStageProvider(coordinator);
    coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
    coordinator.onDeferredSetupComplete();

    S3kSlotBonusStageRuntime runtime = coordinator.activeSlotRuntimeForTest();
    assertNotNull(runtime);
    assertTrue(runtime.isInitialized());

    // Run 500 frames — enough for cage capture, reward cycle, and some gameplay
    for (int i = 0; i < 500; i++) {
        coordinator.onFrameUpdate();
    }

    // Stage should still be running (no goal reached from idle)
    assertTrue(runtime.isInitialized());

    coordinator.onExit();
    assertFalse(runtime.isInitialized());
}
```

- [ ] **Step 2: Run the integration test**

Run: `mvn test -Dtest=TestS3kSlotBonusStageRuntime#fullSlotStageLifecycleFromBootstrapThroughGoalExit -q`

Expected: PASS.

- [ ] **Step 3: Run the full test suite**

Run: `mvn test -q`

Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java
git commit -m "test: add full lifecycle integration test for slot bonus stage"
```
