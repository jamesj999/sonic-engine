# Rewind Framework v1.5.1 — AbstractPlayableSprite Capture Extension

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the player-state coverage cliff surfaced by `RewindBenchmark`'s long-tail determinism gate. `AbstractPlayableSprite` declares 50+ mutable gameplay-relevant primitive fields not covered by `AbstractObjectInstance`'s default 11-field capture; the player object's restore therefore reconstructs only its position and a handful of base fields, leaving velocities/angles/state-flags/timers at default values. After landing, the long-tail gate should report clean rewind across multi-second scrub distances.

**Architecture:** Mirror Plan 3 Track B's `AbstractBadnikInstance` pattern. Define a `PlayerRewindExtra` nested record on `PerObjectRewindSnapshot` carrying the player's full mutable surface; override `captureRewindState`/`restoreRewindState` on `AbstractPlayableSprite` to capture/restore the extra. Subclasses (`Sonic`, `Tails`, `Knuckles`) carry no additional primitive instance fields (verified) — no per-class overrides needed for v1.5.1.

**Tech Stack:** Java 21, JUnit 5 / Jupiter, Maven 3.x with MSE. Reuses Plans 2 + 3 framework. The `RewindBenchmark` long-tail gate (Plan 4) is the verification harness.

**Spec reference:** `docs/architecture/designs/2026-05-04-rewind-framework-design.md`. Investigation report: see commit `45188ab57` body and chat log from session 2026-05-05 for the per-frame divergence trace at frame +1 confirming this is the root cause.

**Prerequisites already landed:** Plans 1, 2, 3 (full rewind framework with 20 covered keys), Plan 4 (RewindBenchmark + long-tail determinism gate). `AbstractObjectInstance.captureRewindState()` returns `PerObjectRewindSnapshot`. `AbstractBadnikInstance` already overrides via `BadnikRewindExtra` (Plan 3 Track B).

---

## Scope

**In scope:**

- `PlayerRewindExtra` nested record on `PerObjectRewindSnapshot` covering the player's mutable gameplay surface.
- `AbstractPlayableSprite.captureRewindState()` / `restoreRewindState()` overrides.
- Focused unit test that verifies all captured fields round-trip via direct mutation + capture + reset + restore.
- Re-run `RewindBenchmark` and confirm the long-tail gate reports clean rewind ≥ 60 frames; ideally several hundred frames.

**Out of scope:**

- Render-only state (`mappingFrame`, `renderFlagWidthPixels`, `renderFlagOnScreen`, `renderHFlip`/`renderVFlip`, `priorityBucket`, etc.) — re-derived from gameplay state on the next render frame.
- Character physics constants (`runAccel`, `runDecel`, `friction`, `maxRoll`, `slopeRunning`, etc.) — set at construction, treated immutable thereafter.
- Animation state (`animationId`, `forcedAnimationId`, `animationFrameIndex`, `animationTick`) — derived from gameplay state; regenerates within 1 frame of motion. **If the long-tail gate after this plan still reports divergence on animation-driven keys, animation state moves to a v1.5.2 follow-up.**
- `slopeRunning`/`slopeRollingUp`/`slopeRollingDown`/`runAccel`/`runDecel`/`friction`/`maxRoll`/`max`/`rollDecel`/`minStartRollSpeed`/`minRollSpeed`/`rollHeight`/`runHeight`/`standXRadius`/`standYRadius`/`rollXRadius`/`rollYRadius` — character physics constants. Excluded.
- `xRadius`/`yRadius` — these alternate between `standXRadius`/`rollXRadius` etc. based on `rolling`. Restoring `rolling` regenerates them within 1 frame. **Decision committed: NOT captured in v1.5.1.** If the gate surfaces a 1-frame divergence on collision-radius-dependent paths, revisit.
- `debugMode` — debug-only field.

**Captured fields (full list, ~50 gameplay-relevant mutables):**

Movement / physics:
- `gSpeed` (short), `xSpeed` (short), `ySpeed` (short), `jump` (short)
- `angle` (byte), `statusTertiary` (byte), `loopLowPlane` (boolean)
- `topSolidBit` (byte), `lrbSolidBit` (byte)
- `prePhysicsAir` (boolean), `prePhysicsAngle` (byte), `prePhysicsGSpeed` (short), `prePhysicsXSpeed` (short), `prePhysicsYSpeed` (short)
- `air` (boolean), `rolling` (boolean), `jumping` (boolean), `rollingJump` (boolean)
- `pinballMode` (boolean), `pinballSpeedLock` (boolean), `tunnelMode` (boolean)

Surface interaction / collision:
- `onObject` (boolean), `onObjectAtFrameStart` (boolean)
- `latchedSolidObjectId` (int), `slopeRepelJustSlipped` (boolean)
- `stickToConvex` (boolean), `sliding` (boolean), `pushing` (boolean)
- `skidding` (boolean), `skidDustTimer` (int)
- `wallClimbX` (short), `rightWallPenetrationTimer` (int)
- `balanceState` (int)

Special states / hazards:
- `springing` (boolean), `springingFrames` (int)
- `dead` (boolean), `drowningDeath` (boolean), `drownPreDeathTimer` (int)
- `hurt` (boolean), `deathCountdown` (int)
- `invulnerableFrames` (int), `invincibleFrames` (int)

Player abilities:
- `spindash` (boolean), `spindashCounter` (short)
- `crouching` (boolean), `lookingUp` (boolean), `lookDelayCounter` (short)
- `doubleJumpFlag` (int), `doubleJumpProperty` (byte)
- `shield` (boolean), `instaShieldRegistered` (boolean)
- `speedShoes` (boolean), `superSonic` (boolean)

Input gating / control:
- `forceInputRight` (boolean), `forcedInputMask` (int)
- `forcedJumpPress` (boolean), `suppressNextJumpPress` (boolean)
- `deferredObjectControlRelease` (boolean)
- `controlLocked` (boolean), `hasQueuedControlLockedState` (boolean), `queuedControlLocked` (boolean)
- `hasQueuedForceInputRightState` (boolean), `queuedForceInputRight` (boolean)
- `moveLockTimer` (int)
- `objectControlled` (boolean), `objectControlAllowsCpu` (boolean), `objectControlSuppressesMovement` (boolean)
- `objectControlReleasedFrame` (int)
- `suppressAirCollision` (boolean), `suppressGroundWallCollision` (boolean), `forceFloorCheck` (boolean)
- `hidden` (boolean)

MGZ-specific:
- `mgzTopPlatformSpringHandoffPending` (boolean), `mgzTopPlatformSpringHandoffXVel` (int), `mgzTopPlatformSpringHandoffYVel` (int)

Input edge tracking:
- `jumpInputPressed`, `jumpInputJustPressed`, `jumpInputPressedPreviousFrame` (booleans)
- `upInputPressed`, `downInputPressed`, `leftInputPressed`, `rightInputPressed` (booleans)
- `movementInputActive` (boolean)
- `logicalInputState` (short), `logicalJumpPressState` (boolean)

CPU / sidekick / spiral:
- `cpuControlled` (boolean), `historyPos` (byte), `followerHistoryRecordedThisTick` (boolean)
- `spiralActiveFrame` (int), `flipAngle` (byte), `flipSpeed` (byte), `flipsRemaining` (byte), `flipTurned` (boolean)

Water:
- `inWater` (boolean), `waterPhysicsActive` (boolean), `wasInWater` (boolean), `waterSkimActive` (boolean)
- `preventTailsRespawn` (boolean)

Other:
- `badnikChainCounter` (int)
- `bubbleAnimId` (int)
- `initPhysicsActive` (boolean)
- `objectMappingFrameControl` (boolean)

That's ~75 gameplay-relevant primitive fields. The `PlayerRewindExtra` record will mirror these one-for-one.

---

## File structure

| Path | Responsibility |
|---|---|
| `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java` | Add `PlayerRewindExtra` nested record + nullable `playerExtra` field on `PerObjectRewindSnapshot` (mirroring `BadnikRewindExtra`). Wither method `withPlayerExtra(PlayerRewindExtra)`. |
| `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` | Override `captureRewindState()` and `restoreRewindState(PerObjectRewindSnapshot)` to populate/consume the `PlayerRewindExtra`. |
| `src/test/java/com/openggf/sprites/playable/TestAbstractPlayableSpriteRewindCapture.java` | Focused unit test: mutate every captured field, capture, reset to defaults, restore, assert all captured values are restored. |
| `src/test/java/com/openggf/game/rewind/RewindBenchmark.java` | (verification only — re-run after the source changes; no code edits) |

---

## Track A — Define `PlayerRewindExtra`

### Task 1 — Define record + add to `PerObjectRewindSnapshot`

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java`

- [ ] **Step 1: Read the current `PerObjectRewindSnapshot.java`** to understand the existing record shape and the `BadnikRewindExtra` pattern Plan 3 Track B established. Confirm whether `BadnikRewindExtra` is a separate file or a nested record, and whether the wither method is `withBadnikExtra` or `with(...)`.

- [ ] **Step 2: Add `PlayerRewindExtra` as a nested public record on `PerObjectRewindSnapshot`** (matching the `BadnikRewindExtra` pattern). Field order should match the categories above to keep the record readable. Each field is a primitive matching the source field's type:

```java
/**
 * Mutable gameplay state on AbstractPlayableSprite that AbstractObjectInstance's
 * default 11-field capture surface does NOT cover. Per the
 * v1.5.1 plan, render-only state, character physics constants, animation
 * state, and collision radii are excluded — they are derived from these
 * fields and regenerate within one forward frame of replay.
 */
public record PlayerRewindExtra(
        // Movement / physics
        short gSpeed, short xSpeed, short ySpeed, short jump,
        byte angle, byte statusTertiary, boolean loopLowPlane,
        byte topSolidBit, byte lrbSolidBit,
        boolean prePhysicsAir, byte prePhysicsAngle,
        short prePhysicsGSpeed, short prePhysicsXSpeed, short prePhysicsYSpeed,
        boolean air, boolean rolling, boolean jumping, boolean rollingJump,
        boolean pinballMode, boolean pinballSpeedLock, boolean tunnelMode,
        // Surface interaction / collision
        boolean onObject, boolean onObjectAtFrameStart,
        int latchedSolidObjectId, boolean slopeRepelJustSlipped,
        boolean stickToConvex, boolean sliding, boolean pushing,
        boolean skidding, int skidDustTimer,
        short wallClimbX, int rightWallPenetrationTimer,
        int balanceState,
        // Special states / hazards
        boolean springing, int springingFrames,
        boolean dead, boolean drowningDeath, int drownPreDeathTimer,
        boolean hurt, int deathCountdown,
        int invulnerableFrames, int invincibleFrames,
        // Player abilities
        boolean spindash, short spindashCounter,
        boolean crouching, boolean lookingUp, short lookDelayCounter,
        int doubleJumpFlag, byte doubleJumpProperty,
        boolean shield, boolean instaShieldRegistered,
        boolean speedShoes, boolean superSonic,
        // Input gating / control
        boolean forceInputRight, int forcedInputMask,
        boolean forcedJumpPress, boolean suppressNextJumpPress,
        boolean deferredObjectControlRelease,
        boolean controlLocked, boolean hasQueuedControlLockedState, boolean queuedControlLocked,
        boolean hasQueuedForceInputRightState, boolean queuedForceInputRight,
        int moveLockTimer,
        boolean objectControlled, boolean objectControlAllowsCpu, boolean objectControlSuppressesMovement,
        int objectControlReleasedFrame,
        boolean suppressAirCollision, boolean suppressGroundWallCollision, boolean forceFloorCheck,
        boolean hidden,
        // MGZ-specific
        boolean mgzTopPlatformSpringHandoffPending,
        int mgzTopPlatformSpringHandoffXVel,
        int mgzTopPlatformSpringHandoffYVel,
        // Input edge tracking
        boolean jumpInputPressed, boolean jumpInputJustPressed, boolean jumpInputPressedPreviousFrame,
        boolean upInputPressed, boolean downInputPressed,
        boolean leftInputPressed, boolean rightInputPressed,
        boolean movementInputActive,
        short logicalInputState, boolean logicalJumpPressState,
        // CPU / sidekick / spiral
        boolean cpuControlled, byte historyPos, boolean followerHistoryRecordedThisTick,
        int spiralActiveFrame, byte flipAngle, byte flipSpeed,
        byte flipsRemaining, boolean flipTurned,
        // Water
        boolean inWater, boolean waterPhysicsActive, boolean wasInWater, boolean waterSkimActive,
        boolean preventTailsRespawn,
        // Other
        int badnikChainCounter,
        int bubbleAnimId,
        boolean initPhysicsActive,
        boolean objectMappingFrameControl
) {}
```

- [ ] **Step 3: Add a nullable `playerExtra` field** to the `PerObjectRewindSnapshot` record, mirroring how `BadnikRewindExtra` was added. The exact mechanism depends on whether `PerObjectRewindSnapshot` uses an explicit set of nullable extras (one field per kind) or a generic `extras` map. Read the current record shape before editing.

  If the existing pattern uses one nullable field per extra kind:

  ```java
  public record PerObjectRewindSnapshot(
          /* ... existing 11 standard fields ... */,
          BadnikRewindExtra badnikExtra,
          PlayerRewindExtra playerExtra
  ) {
      // Witherers
      public PerObjectRewindSnapshot withBadnikExtra(BadnikRewindExtra extra) { /* ... */ }
      public PerObjectRewindSnapshot withPlayerExtra(PlayerRewindExtra extra) {
          return new PerObjectRewindSnapshot(/* ... */, badnikExtra, extra);
      }
  }
  ```

  If a generic `Map<String, Object> extras` exists, add `withExtra("player", PlayerRewindExtra)` instead.

- [ ] **Step 4: Update `AbstractObjectInstance.captureRewindState()`** if the existing code constructs `PerObjectRewindSnapshot` with a specific arg list; pass `null` for the new `playerExtra` field. (Same shape as how `null` is passed for `badnikExtra` in the default capture.)

- [ ] **Step 5: Compile-check the project:**

```
mvn compile -q 2>&1 | tail -10
```

Expected: compiles cleanly. If `AbstractBadnikInstance.captureRewindState()` (which does construct a snapshot with a `BadnikRewindExtra`) breaks because the constructor signature changed, update its construction call to pass `null` for `playerExtra`.

- [ ] **Step 6: No commit yet.** This task only adds the record + structural plumbing. The override on `AbstractPlayableSprite` lands in Task 2; tests in Task 3. Wait for Task 2's tests to pass before committing the bundle as one feature commit, OR commit this small structural change separately if you prefer per-step granularity. The plan-author recommendation: ONE commit covering Tasks 1 + 2 + 3, since they form a single coherent feature.

---

## Track B — Override on `AbstractPlayableSprite`

### Task 2 — Override `captureRewindState` / `restoreRewindState`

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`

- [ ] **Step 1: Read the existing `AbstractObjectInstance.captureRewindState()`** to understand the base capture format. This is in `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`. Note exactly which 11 standard fields it captures and how it constructs `PerObjectRewindSnapshot`.

- [ ] **Step 2: Read the existing `AbstractBadnikInstance` override** for the pattern to mirror. The override is at `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`. Note how it composes a `BadnikRewindExtra` from its 7 fields and chains the result onto `super.captureRewindState()` via `withBadnikExtra(...)`.

- [ ] **Step 3: Add the override on `AbstractPlayableSprite`:**

```java
@Override
public PerObjectRewindSnapshot captureRewindState() {
    PerObjectRewindSnapshot base = super.captureRewindState();
    PlayerRewindExtra extra = new PlayerRewindExtra(
            gSpeed, xSpeed, ySpeed, jump,
            angle, statusTertiary, loopLowPlane,
            topSolidBit, lrbSolidBit,
            prePhysicsAir, prePhysicsAngle,
            prePhysicsGSpeed, prePhysicsXSpeed, prePhysicsYSpeed,
            air, rolling, jumping, rollingJump,
            pinballMode, pinballSpeedLock, tunnelMode,
            onObject, onObjectAtFrameStart,
            latchedSolidObjectId, slopeRepelJustSlipped,
            stickToConvex, sliding, pushing,
            skidding, skidDustTimer,
            wallClimbX, rightWallPenetrationTimer,
            balanceState,
            springing, springingFrames,
            dead, drowningDeath, drownPreDeathTimer,
            hurt, deathCountdown,
            invulnerableFrames, invincibleFrames,
            spindash, spindashCounter,
            crouching, lookingUp, lookDelayCounter,
            doubleJumpFlag, doubleJumpProperty,
            shield, instaShieldRegistered,
            speedShoes, superSonic,
            forceInputRight, forcedInputMask,
            forcedJumpPress, suppressNextJumpPress,
            deferredObjectControlRelease,
            controlLocked, hasQueuedControlLockedState, queuedControlLocked,
            hasQueuedForceInputRightState, queuedForceInputRight,
            moveLockTimer,
            objectControlled, objectControlAllowsCpu, objectControlSuppressesMovement,
            objectControlReleasedFrame,
            suppressAirCollision, suppressGroundWallCollision, forceFloorCheck,
            hidden,
            mgzTopPlatformSpringHandoffPending,
            mgzTopPlatformSpringHandoffXVel,
            mgzTopPlatformSpringHandoffYVel,
            jumpInputPressed, jumpInputJustPressed, jumpInputPressedPreviousFrame,
            upInputPressed, downInputPressed,
            leftInputPressed, rightInputPressed,
            movementInputActive,
            logicalInputState, logicalJumpPressState,
            cpuControlled, historyPos, followerHistoryRecordedThisTick,
            spiralActiveFrame, flipAngle, flipSpeed,
            flipsRemaining, flipTurned,
            inWater, waterPhysicsActive, wasInWater, waterSkimActive,
            preventTailsRespawn,
            badnikChainCounter,
            bubbleAnimId,
            initPhysicsActive,
            objectMappingFrameControl);
    return base.withPlayerExtra(extra);
}

@Override
public void restoreRewindState(PerObjectRewindSnapshot s) {
    super.restoreRewindState(s);
    PlayerRewindExtra extra = s.playerExtra();
    if (extra == null) {
        throw new IllegalStateException(
                "AbstractPlayableSprite.restoreRewindState requires PlayerRewindExtra");
    }
    this.gSpeed = extra.gSpeed();
    this.xSpeed = extra.xSpeed();
    this.ySpeed = extra.ySpeed();
    this.jump = extra.jump();
    this.angle = extra.angle();
    this.statusTertiary = extra.statusTertiary();
    this.loopLowPlane = extra.loopLowPlane();
    this.topSolidBit = extra.topSolidBit();
    this.lrbSolidBit = extra.lrbSolidBit();
    this.prePhysicsAir = extra.prePhysicsAir();
    this.prePhysicsAngle = extra.prePhysicsAngle();
    this.prePhysicsGSpeed = extra.prePhysicsGSpeed();
    this.prePhysicsXSpeed = extra.prePhysicsXSpeed();
    this.prePhysicsYSpeed = extra.prePhysicsYSpeed();
    this.air = extra.air();
    this.rolling = extra.rolling();
    this.jumping = extra.jumping();
    this.rollingJump = extra.rollingJump();
    this.pinballMode = extra.pinballMode();
    this.pinballSpeedLock = extra.pinballSpeedLock();
    this.tunnelMode = extra.tunnelMode();
    this.onObject = extra.onObject();
    this.onObjectAtFrameStart = extra.onObjectAtFrameStart();
    this.latchedSolidObjectId = extra.latchedSolidObjectId();
    this.slopeRepelJustSlipped = extra.slopeRepelJustSlipped();
    this.stickToConvex = extra.stickToConvex();
    this.sliding = extra.sliding();
    this.pushing = extra.pushing();
    this.skidding = extra.skidding();
    this.skidDustTimer = extra.skidDustTimer();
    this.wallClimbX = extra.wallClimbX();
    this.rightWallPenetrationTimer = extra.rightWallPenetrationTimer();
    this.balanceState = extra.balanceState();
    this.springing = extra.springing();
    this.springingFrames = extra.springingFrames();
    this.dead = extra.dead();
    this.drowningDeath = extra.drowningDeath();
    this.drownPreDeathTimer = extra.drownPreDeathTimer();
    this.hurt = extra.hurt();
    this.deathCountdown = extra.deathCountdown();
    this.invulnerableFrames = extra.invulnerableFrames();
    this.invincibleFrames = extra.invincibleFrames();
    this.spindash = extra.spindash();
    this.spindashCounter = extra.spindashCounter();
    this.crouching = extra.crouching();
    this.lookingUp = extra.lookingUp();
    this.lookDelayCounter = extra.lookDelayCounter();
    this.doubleJumpFlag = extra.doubleJumpFlag();
    this.doubleJumpProperty = extra.doubleJumpProperty();
    this.shield = extra.shield();
    this.instaShieldRegistered = extra.instaShieldRegistered();
    this.speedShoes = extra.speedShoes();
    this.superSonic = extra.superSonic();
    this.forceInputRight = extra.forceInputRight();
    this.forcedInputMask = extra.forcedInputMask();
    this.forcedJumpPress = extra.forcedJumpPress();
    this.suppressNextJumpPress = extra.suppressNextJumpPress();
    this.deferredObjectControlRelease = extra.deferredObjectControlRelease();
    this.controlLocked = extra.controlLocked();
    this.hasQueuedControlLockedState = extra.hasQueuedControlLockedState();
    this.queuedControlLocked = extra.queuedControlLocked();
    this.hasQueuedForceInputRightState = extra.hasQueuedForceInputRightState();
    this.queuedForceInputRight = extra.queuedForceInputRight();
    this.moveLockTimer = extra.moveLockTimer();
    this.objectControlled = extra.objectControlled();
    this.objectControlAllowsCpu = extra.objectControlAllowsCpu();
    this.objectControlSuppressesMovement = extra.objectControlSuppressesMovement();
    this.objectControlReleasedFrame = extra.objectControlReleasedFrame();
    this.suppressAirCollision = extra.suppressAirCollision();
    this.suppressGroundWallCollision = extra.suppressGroundWallCollision();
    this.forceFloorCheck = extra.forceFloorCheck();
    this.hidden = extra.hidden();
    this.mgzTopPlatformSpringHandoffPending = extra.mgzTopPlatformSpringHandoffPending();
    this.mgzTopPlatformSpringHandoffXVel = extra.mgzTopPlatformSpringHandoffXVel();
    this.mgzTopPlatformSpringHandoffYVel = extra.mgzTopPlatformSpringHandoffYVel();
    this.jumpInputPressed = extra.jumpInputPressed();
    this.jumpInputJustPressed = extra.jumpInputJustPressed();
    this.jumpInputPressedPreviousFrame = extra.jumpInputPressedPreviousFrame();
    this.upInputPressed = extra.upInputPressed();
    this.downInputPressed = extra.downInputPressed();
    this.leftInputPressed = extra.leftInputPressed();
    this.rightInputPressed = extra.rightInputPressed();
    this.movementInputActive = extra.movementInputActive();
    this.logicalInputState = extra.logicalInputState();
    this.logicalJumpPressState = extra.logicalJumpPressState();
    this.cpuControlled = extra.cpuControlled();
    this.historyPos = extra.historyPos();
    this.followerHistoryRecordedThisTick = extra.followerHistoryRecordedThisTick();
    this.spiralActiveFrame = extra.spiralActiveFrame();
    this.flipAngle = extra.flipAngle();
    this.flipSpeed = extra.flipSpeed();
    this.flipsRemaining = extra.flipsRemaining();
    this.flipTurned = extra.flipTurned();
    this.inWater = extra.inWater();
    this.waterPhysicsActive = extra.waterPhysicsActive();
    this.wasInWater = extra.wasInWater();
    this.waterSkimActive = extra.waterSkimActive();
    this.preventTailsRespawn = extra.preventTailsRespawn();
    this.badnikChainCounter = extra.badnikChainCounter();
    this.bubbleAnimId = extra.bubbleAnimId();
    this.initPhysicsActive = extra.initPhysicsActive();
    this.objectMappingFrameControl = extra.objectMappingFrameControl();
}
```

- [ ] **Step 4: Compile:**

```
mvn compile -q 2>&1 | tail -10
```

Resolve any field-name typos surfaced by the compiler. The list of ~75 fields is large; expect at least one typo.

---

## Track C — Tests

### Task 3 — Focused unit test for player capture round-trip

**Files:**
- Create: `src/test/java/com/openggf/sprites/playable/TestAbstractPlayableSpriteRewindCapture.java`

The test exercises capture/restore round-trip by mutating each field to a non-default value, capturing, resetting the field, restoring, and asserting the captured value comes back.

- [ ] **Step 1: Find a way to construct a minimal `AbstractPlayableSprite` for testing.** The class is abstract; concrete subclasses are `Sonic`, `Tails`, `Knuckles` (each ~100 lines). Pick whichever is easiest to construct in a test fixture. **Investigative step:** read existing tests under `src/test/java/com/openggf/sprites/playable/` to see how `Sonic`/`Tails` are constructed in tests. If they require a level/ROM/managers/services setup, leverage the `HeadlessTestFixture` from `TestRewindParityAgainstTrace`'s pattern.

- [ ] **Step 2: Write the test.** Pattern:

```java
package com.openggf.sprites.playable;

import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestAbstractPlayableSpriteRewindCapture {

    @Test
    void roundTripCoversFullPlayerSurface() throws Exception {
        // Boot minimal fixture / construct a Sonic instance.
        // (Pattern from existing Sonic tests; see investigative step above.)
        Sonic sonic = constructTestSonic();

        // Mutate every captured field to a non-default value. Use sentinel
        // values that are unlikely to collide with defaults.
        sonic.gSpeed = 0x123;
        sonic.xSpeed = 0x456;
        sonic.ySpeed = (short) 0xFF80;   // negative
        sonic.jump = 0x42;
        sonic.angle = (byte) 0x40;
        sonic.statusTertiary = (byte) 0x05;
        sonic.loopLowPlane = true;
        sonic.topSolidBit = (byte) 0x0E;
        sonic.lrbSolidBit = (byte) 0x0F;
        sonic.prePhysicsAir = true;
        sonic.prePhysicsAngle = (byte) 0x80;
        sonic.prePhysicsGSpeed = 0x111;
        sonic.prePhysicsXSpeed = 0x222;
        sonic.prePhysicsYSpeed = 0x333;
        sonic.air = true;
        sonic.rolling = true;
        sonic.jumping = true;
        sonic.rollingJump = true;
        sonic.pinballMode = true;
        sonic.pinballSpeedLock = true;
        sonic.tunnelMode = true;
        sonic.onObject = true;
        // ... continue for every captured field
        // (Use distinct sentinels per field to catch accidental field
        //  reordering in the record.)

        // Capture
        PerObjectRewindSnapshot snap = sonic.captureRewindState();
        assertNotNull(snap.playerExtra(), "PlayerRewindExtra must be populated");

        // Reset all captured fields to defaults
        sonic.gSpeed = 0;
        sonic.xSpeed = 0;
        sonic.ySpeed = 0;
        sonic.jump = 0;
        sonic.angle = 0;
        // ... continue for every captured field (mirror the mutation list)
        sonic.air = false;
        sonic.rolling = false;
        // ...

        // Restore
        sonic.restoreRewindState(snap);

        // Assert all values are back
        assertEquals(0x123, sonic.gSpeed);
        assertEquals(0x456, sonic.xSpeed);
        assertEquals((short) 0xFF80, sonic.ySpeed);
        assertEquals(0x42, sonic.jump);
        assertEquals((byte) 0x40, sonic.angle);
        // ... continue for every captured field
        // Use Arrays.deepEquals or per-field assertEquals — verbose is fine.
    }
}
```

The test is intentionally verbose (~75 mutation lines, ~75 assertion lines). That's OK — it's the only test that proves complete coverage. Bonus: writing the assertions field-by-field surfaces typos in the override (any field that asserts wrong value indicates a missing copy in `restoreRewindState`).

- [ ] **Step 3: Run the test:**

```
mvn test "-Dtest=TestAbstractPlayableSpriteRewindCapture" -q 2>&1 | tail -10
```

Verify via `target/surefire-reports/TEST-com.openggf.sprites.playable.TestAbstractPlayableSpriteRewindCapture.xml` showing `failures="0"`.

- [ ] **Step 4: Stage explicit paths only and commit:**

```
git add src/main/java/com/openggf/level/objects/PerObjectRewindSnapshot.java \
        src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java \
        src/test/java/com/openggf/sprites/playable/TestAbstractPlayableSpriteRewindCapture.java
git --no-pager diff --cached --stat
```

Expected: only those 3 files staged. If extra files appear (parallel agents may be at work — verify by listing the working tree), `git restore --staged <path>` to unstage them.

- [ ] **Step 5: Commit:**

```
git commit -m "$(cat <<'EOF'
feat(rewind): cover AbstractPlayableSprite mutable fields in default capture

AbstractPlayableSprite carries ~75 mutable gameplay-relevant primitive
fields (xSpeed/ySpeed/gSpeed/angle/air/rolling/jumping/spindash/etc.) not
in the AbstractObjectInstance default capture surface. Without this
override, the player object's restore reconstructs only its position +
11 standard fields, leaving velocities/state-flags/timers at default
values. Surfaced by RewindBenchmark's long-tail determinism gate as a
9-key cascade divergence at frame +1 after seek-and-replay.

Add PlayerRewindExtra record on PerObjectRewindSnapshot and override
captureRewindState/restoreRewindState on AbstractPlayableSprite to
populate/consume it. Mirrors Plan 3 Track B's BadnikRewindExtra pattern.

Render-only state, character physics constants, animation state, and
collision radii are excluded — they are derived from the captured
fields and regenerate within one forward replay frame.

Per-class overrides on Sonic/Tails/Knuckles are NOT needed for v1.5.1
because those subclasses declare no additional primitive instance
fields (verified empty enumeration).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final integration check

### Task FINAL.1 — Re-run RewindBenchmark long-tail gate

- [ ] **Step 1: Run the benchmark:**

```
mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Dmse=off" 2>&1 | grep -E "per-frame|Long-tail|clean rewind|first diverge" | tail -10
```

- [ ] **Step 2: Interpret the result.**

  - **Best case:** `Long-tail result: longestCleanRewind=1200 frames (20.00 sec @ 60fps)`. The gate replays the full configured scrub distance with no divergence. Plan ships.

  - **Likely case:** clean rewind ≥ 60 frames but divergence at a longer distance (e.g., 300 or 600). The first-divergent key tells you which subsystem is the next coverage gap. **Document this as a v1.5.2 follow-up** — name the key + the smallest scrub distance at which it diverges. Don't try to fix in this plan.

  - **Bad case:** still 0 frames of clean rewind. The first-divergent key is something other than (or in addition to) the player. Look at the per-frame trace and the Camera diff dump to see what specifically still differs. Possibilities: animation state matters more than expected, OR `xRadius`/`yRadius` matter on the first frame, OR another subsystem has its own coverage cliff.

- [ ] **Step 3: Run the default test suite to verify no new regressions:**

```
mvn test "-Dmse=off" 2>&1 | grep -E "Tests run:.*Failures.*Errors" | tail -1
```

Expected: failure count unchanged from pre-plan baseline (currently 26 — though this may shift if the parallel-agent merge work is in flight). The new `TestAbstractPlayableSpriteRewindCapture` adds 1 test to the passing count.

- [ ] **Step 4: Report final state to user** with: longest clean rewind in frames, any remaining divergent key + distance, and confirmation of no default-suite regression.

---

## Summary

When this plan completes, the codebase has:

1. `PlayerRewindExtra` record on `PerObjectRewindSnapshot` covering `AbstractPlayableSprite`'s ~75 gameplay-relevant mutable fields.
2. `AbstractPlayableSprite.captureRewindState/restoreRewindState` overrides populating/consuming the extra.
3. A focused unit test that proves field-by-field round-trip.
4. (Verified externally:) `RewindBenchmark`'s long-tail determinism gate now reports clean rewind ≥ 60 frames for the S2 EHZ1 trace.

Documented follow-ups (if the gate still surfaces divergence at longer distances):
- v1.5.2 — animation-state capture (`animationId`, `forcedAnimationId`, `animationFrameIndex`, `animationTick` on `AbstractPlayableSprite`; possibly `mappingFrame`).
- v1.5.3 — per-class boss subclass overrides (~40 boss classes with phase counters, hit counters, arena state).
- v1.5.4 — non-`AbstractBadnikInstance` badnik subclasses with private gameplay state.
- v1.6 — anything still surfaced by the long-tail gate after the above.
