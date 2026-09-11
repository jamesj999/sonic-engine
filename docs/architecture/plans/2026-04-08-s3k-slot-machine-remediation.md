# S3K Slot Machine Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken approximation-based S3K Slot Machine bonus stage with a ROM-shaped implementation that is complete, accurate, and cleans up superseded code paths.

**Architecture:** Keep `GameLoop` and `Sonic3kBonusStageCoordinator` as the outer lifecycle owners. Replace the current Slots gameplay/render internals with focused slot subsystems: authoritative stage state, slot-player runtime, slot collision/tile handling, layout rendering, option-cycle logic, cage/reward objects, and slot-specific palette/scroll integration. Cleanup is part of the implementation, not a follow-up.

**Tech Stack:** Java 17, Maven, JUnit 5, existing S3K bonus-stage lifecycle, `SpriteManager`, `ObjectManager`, `RingManager`, S3K disassembly in `docs/skdisasm/sonic3k.asm`.

---

## File Structure

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageState.java`
  Responsibility: authoritative ROM-shaped mutable Slots state including scalar fields, reel state, collision state, animation timers, palette mode, and exit mode.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPlayerRuntime.java`
  Responsibility: ROM-shaped slot-player logic extracted from `Obj_Sonic_RotatingSlotBonus`, operating on a playable sprite and `S3kSlotStageState`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotCollisionSystem.java`
  Responsibility: slot-grid collision, ring pickup, tile classification, and tile response generation based on the staged slot layout buffer.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionCycleSystem.java`
  Responsibility: `Slots_CycleOptions` state progression, visible symbol updates, target selection, result resolution, and option-strip staging.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java`
  Responsibility: ROM-shaped runtime buffers for expanded layout rows, staged piece table, transformed point grid, and transient tile-animation slots.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
  Responsibility: become the authoritative container for layout start data, slot piece tables, reel tables, transient animation tables, and other ROM-derived constants.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
  Responsibility: become a thin orchestrator that wires the new subsystems together, restores the original player, and removes runtime-side fallback rendering/state duplication.

- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
  Responsibility: port `Slots_RenderLayout` using `S3kSlotRenderBuffers` and `S3kSlotStageState`.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
  Responsibility: implement ROM-shaped cage behavior and normal object-owned rendering.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
  Responsibility: implement `Obj_SlotRing` behavior and object-owned rendering.

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
  Responsibility: implement `Obj_SlotSpike` behavior and object-owned rendering.

- Modify: `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlSlots.java`
  Responsibility: port `Slots_ScreenInit/Event` and `Slots_BackgroundInit/Event`.

- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
  Responsibility: keep `AnPal_Slots` ROM-shaped and driven from authoritative slot state.

- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
  Responsibility: keep the slot stage-layout render hook in the correct post-sprite ordering without runtime-side cage/reward fallbacks.

- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
  Responsibility: keep runtime ownership and exit handoff, but stop depending on approximation-layer APIs removed during remediation.

- Delete or collapse into new owners:
  - `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java`
  - `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java`
  - `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java`
  - `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java`
  Responsibility: remove the superseded approximation layer once their behavior is absorbed by the new stateful subsystems.

- Create or update tests:
  - `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageState.java`
  - `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPlayerRuntime.java`
  - `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotCollisionSystem.java`
  - `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionCycleSystem.java`
  - `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java`
  - `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java`
  - `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java`
  - `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`
  - `src/test/java/com/openggf/game/sonic3k/TestS3kSlotsPaletteCycling.java`
  - `src/test/java/com/openggf/game/sonic3k/scroll/SwScrlSlotsTest.java`

## Task 1: Replace Slot State And Runtime Buffers

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageState.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageState.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`

- [ ] **Step 1: Write failing tests for ROM-shaped bootstrap state**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotStageState {

    @Test
    void bootstrapUsesRomInitialState() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        assertEquals(0, state.statTable());
        assertEquals(0x40, state.scalarIndex1());
        assertFalse(state.paletteCycleEnabled());
        assertEquals(0, state.lastCollisionTileId());
    }

    @Test
    void renderBuffersUseExpandedLayoutStride() {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();

        assertEquals(0x80, buffers.layoutStrideBytes());
        assertEquals(0x20, buffers.layoutRows());
        assertEquals(0x20, buffers.layoutColumns());
    }
}
```

```java
@Test
void runtimeStagesExpandedLayoutAndPieceTables() {
    S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();

    runtime.bootstrap();

    assertNotNull(runtime.stageStateForTest());
    assertNotNull(runtime.renderBuffersForTest());
    assertEquals(0x80, runtime.renderBuffersForTest().layoutStrideBytes());
}
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotStageState,TestS3kSlotBonusStageRuntime" surefire:test`

Expected: `FAIL` because `S3kSlotStageState`, `S3kSlotRenderBuffers`, and the new runtime test seams do not exist yet.

- [ ] **Step 3: Add authoritative slot state and render buffers**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotStageState {
    private int statTable;
    private int scalarIndex1;
    private int scalarIndex2;
    private int scalarResult0;
    private int scalarResult1;
    private int lastCollisionTileId;
    private int lastCollisionIndex = -1;
    private boolean paletteCycleEnabled;

    public static S3kSlotStageState bootstrap() {
        S3kSlotStageState state = new S3kSlotStageState();
        state.scalarIndex1 = 0x40;
        return state;
    }

    public int statTable() { return statTable; }
    public int scalarIndex1() { return scalarIndex1; }
    public int scalarIndex2() { return scalarIndex2; }
    public int scalarResult0() { return scalarResult0; }
    public int scalarResult1() { return scalarResult1; }
    public int lastCollisionTileId() { return lastCollisionTileId; }
    public int lastCollisionIndex() { return lastCollisionIndex; }
    public boolean paletteCycleEnabled() { return paletteCycleEnabled; }

    public void clearCollision() {
        lastCollisionTileId = 0;
        lastCollisionIndex = -1;
    }
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotRenderBuffers {
    private final byte[] expandedLayout;
    private final int layoutStrideBytes;
    private final int layoutRows;
    private final int layoutColumns;

    private S3kSlotRenderBuffers(byte[] expandedLayout, int layoutStrideBytes, int layoutRows, int layoutColumns) {
        this.expandedLayout = expandedLayout;
        this.layoutStrideBytes = layoutStrideBytes;
        this.layoutRows = layoutRows;
        this.layoutColumns = layoutColumns;
    }

    public static S3kSlotRenderBuffers fromRomData() {
        return new S3kSlotRenderBuffers(S3kSlotRomData.buildExpandedLayoutBuffer(), 0x80, 0x20, 0x20);
    }

    public byte[] expandedLayout() { return expandedLayout; }
    public int layoutStrideBytes() { return layoutStrideBytes; }
    public int layoutRows() { return layoutRows; }
    public int layoutColumns() { return layoutColumns; }
}
```

```java
public final class S3kSlotBonusStageRuntime {
    private S3kSlotStageState slotStageState;
    private S3kSlotRenderBuffers slotRenderBuffers;

    public void bootstrap() {
        slotStageState = S3kSlotStageState.bootstrap();
        slotRenderBuffers = S3kSlotRenderBuffers.fromRomData();
        // existing player/runtime wiring follows in later tasks
    }

    S3kSlotStageState stageStateForTest() { return slotStageState; }
    S3kSlotRenderBuffers renderBuffersForTest() { return slotRenderBuffers; }
}
```

- [ ] **Step 4: Run the targeted tests again**

Run: `mvn -q "-Dtest=TestS3kSlotStageState,TestS3kSlotBonusStageRuntime" surefire:test`

Expected: `BUILD SUCCESS` with the new bootstrap tests green.

- [ ] **Step 5: Commit the state/buffer slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageState.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotStageState.java src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java
git commit -m "feat: add authoritative S3K slot state"
```

## Task 2: Replace Player Runtime And Slot Collision

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPlayerRuntime.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotCollisionSystem.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Delete: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java`
- Delete: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPlayerRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotCollisionSystem.java`

- [ ] **Step 1: Write failing player and collision tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotPlayerRuntime {

    @Test
    void initializeRestoresSavedRingsAndLives() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        TestSlotSprite sprite = new TestSlotSprite();

        S3kSlotPlayerRuntime.initialize(sprite, state, 2, 37, 0x12);

        assertTrue(sprite.getAir());
        assertTrue(sprite.isRolling());
        assertEquals(37, sprite.getRingCount());
        assertEquals(0x12, sprite.getExtraLifeFlagsForTest());
    }
}
```

```java
class TestS3kSlotCollisionSystem {

    @Test
    void collisionReadsExpandedLayoutStride() {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        S3kSlotCollisionSystem system = new S3kSlotCollisionSystem(buffers);

        S3kSlotCollisionSystem.Collision collision = system.checkCollision(0x460, 0x430);

        assertNotNull(collision);
    }
}
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotPlayerRuntime,TestS3kSlotCollisionSystem" surefire:test`

Expected: `FAIL` because the dedicated runtime and collision system do not exist yet.

- [ ] **Step 3: Implement the dedicated slot player runtime and collision system**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.sprites.playable.AbstractPlayableSprite;

public final class S3kSlotPlayerRuntime {

    private S3kSlotPlayerRuntime() {
    }

    public static void initialize(AbstractPlayableSprite sprite, S3kSlotStageState state,
                                  int playerMode, int savedRingCount, int savedExtraLifeFlags) {
        sprite.setAir(true);
        sprite.setRolling(true);
        sprite.setGSpeed((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setRings(savedRingCount);
        sprite.setExtraLifeFlagsForTest(savedExtraLifeFlags);
        state.clearCollision();
    }

    public static void tick(AbstractPlayableSprite sprite, S3kSlotStageState state,
                            S3kSlotCollisionSystem collisionSystem,
                            boolean left, boolean right, boolean jump) {
        state.clearCollision();
        applyGroundOrAirMotion(sprite, state, left, right, jump);
        S3kSlotCollisionSystem.Collision collision = collisionSystem.checkCollision(sprite.getX(), sprite.getY());
        if (collision.tileId() != 0) {
            state.recordCollision(collision.tileId(), collision.layoutIndex());
        }
        S3kSlotCollisionSystem.RingPickup ringPickup = collisionSystem.checkRingPickup(sprite.getX(), sprite.getY());
        if (ringPickup.foundRing()) {
            collisionSystem.consumeRing(ringPickup.layoutIndex());
            sprite.addRings(1);
        }
    }
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotCollisionSystem {
    private final S3kSlotRenderBuffers buffers;

    public S3kSlotCollisionSystem(S3kSlotRenderBuffers buffers) {
        this.buffers = buffers;
    }

    public Collision checkCollision(int xPixel, int yPixel) {
        int stride = buffers.layoutStrideBytes();
        byte[] layout = buffers.expandedLayout();
        int row = Math.floorDiv(yPixel + 0x44, 0x18);
        int col = Math.floorDiv(xPixel + 0x14, 0x18);
        int index = row * stride + col;
        int tile = layout[index] & 0xFF;
        return new Collision(tile, index);
    }

    public record Collision(int tileId, int layoutIndex) {}
}
```

- [ ] **Step 4: Rewire runtime to use the new player/collision path and delete the superseded approximation files**

```bash
git rm -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotGridCollision.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotTileInteraction.java
```

```java
public void update(int frameCounter) {
    slotStageState.clearCollision();
    slotPlayerRuntime.tick(slotPlayer, slotStageState, slotCollisionSystem, leftHeld, rightHeld, jumpPressed);
}
```

- [ ] **Step 5: Run the targeted tests again**

Run: `mvn -q "-Dtest=TestS3kSlotPlayerRuntime,TestS3kSlotCollisionSystem,TestS3kSlotBonusStageRuntime" surefire:test`

Expected: `BUILD SUCCESS` with the new player/collision path green and no references to the deleted approximation files.

- [ ] **Step 6: Commit the player/collision slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotPlayerRuntime.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotCollisionSystem.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPlayerRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotCollisionSystem.java
git commit -m "feat: port S3K slot player and collision runtime"
```

## Task 3: Port Slots_RenderLayout And Transient Tile Animations

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java`

- [ ] **Step 1: Write failing render tests for post-sprite layout and transient tile animations**

```java
@Test
void renderBuildsPointGridFromExpandedBuffers() {
    S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
    S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();

    short[] points = renderer.buildPointGrid(0x40, 0, 0);

    assertEquals(16 * 16 * 2, points.length);
}

@Test
void transientRingAnimationUsesRuntimeAnimationSlots() {
    S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
    buffers.startRingAnimationAt(0x10);

    new S3kSlotLayoutRenderer().tickTransientAnimations(buffers);

    assertTrue(buffers.hasActiveTransientAnimationAt(0x10));
}
```

- [ ] **Step 2: Run the targeted layout tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotLayoutRenderer" surefire:test`

Expected: `FAIL` because the renderer still uses the semantic visible-cell approximation and no runtime transient-animation slots.

- [ ] **Step 3: Port the renderer to the ROM-shaped buffer model**

```java
public final class S3kSlotLayoutRenderer {

    public void render(S3kSlotStageState state, S3kSlotRenderBuffers buffers, Camera camera, ObjectRenderManager orm) {
        tickStageAnimationFrames(state, buffers);
        tickTransientAnimations(buffers);
        short[] points = buildPointGrid(state.statTable(), camera.getX(), camera.getY());
        submitVisiblePieces(points, buffers, orm);
    }

    void tickTransientAnimations(S3kSlotRenderBuffers buffers) {
        buffers.tickTransientAnimations();
    }
}
```

- [ ] **Step 4: Move runtime rendering to the post-sprite slot-stage render pass only**

```java
public void render(Camera camera) {
    slotLayoutRenderer.render(slotStageState, slotRenderBuffers, camera, objectRenderManager);
}
```

- [ ] **Step 5: Run the targeted layout/runtime tests again**

Run: `mvn -q "-Dtest=TestS3kSlotLayoutRenderer,TestS3kSlotBonusStageRuntime" surefire:test`

Expected: `BUILD SUCCESS` with the renderer now consuming the staged buffers instead of the semantic-cell approximation.

- [ ] **Step 6: Commit the layout-render slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java
git commit -m "feat: port S3K slot layout renderer"
```

## Task 4: Port Slots_CycleOptions And Remove The Old Reel Layer

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionCycleSystem.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageState.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Delete: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java`
- Delete: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionCycleSystem.java`

- [ ] **Step 1: Write failing option-cycle tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotOptionCycleSystem {

    @Test
    void bootstrapSeedsThreeReelsFromVIntCounter() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        new S3kSlotOptionCycleSystem().bootstrap(state, 0x6A);

        assertEquals(1, state.optionCycleRoutine());
        assertEquals(8, state.reelSpeedA());
        assertEquals(8, state.reelSpeedB());
        assertEquals(8, state.reelSpeedC());
    }
}
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotOptionCycleSystem" surefire:test`

Expected: `FAIL` because the new option-cycle system and state fields do not exist yet.

- [ ] **Step 3: Implement the ROM-shaped option-cycle system**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotOptionCycleSystem {

    public void bootstrap(S3kSlotStageState state, int vintCounterLowByte) {
        state.resetOptionCycle();
        state.setReelSeedA(vintCounterLowByte & 0xFF);
        state.setReelSeedB(Integer.rotateRight(vintCounterLowByte & 0xFF, 3) & 0xFF);
        state.setReelSeedC(Integer.rotateRight(vintCounterLowByte & 0xFF, 6) & 0xFF);
        state.setReelSpeedA(8);
        state.setReelSpeedB(8);
        state.setReelSpeedC(8);
        state.setOptionCycleRoutine(1);
    }

    public void tick(S3kSlotStageState state, S3kSlotRenderBuffers buffers, int vintCounterWord) {
        switch (state.optionCycleRoutine()) {
            case 0 -> bootstrap(state, vintCounterWord & 0xFF);
            case 1 -> tickSpin(state, buffers);
            case 0x18 -> tickTargetSelection(state, vintCounterWord);
            case 0x1C -> tickDeceleration(state, buffers);
            default -> tickResolved(state, buffers);
        }
    }
}
```

- [ ] **Step 4: Delete the old reel helper layer and rewire runtime**

```bash
git rm -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotReelStateMachine.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java
```

```java
public void render(Camera camera) {
    slotLayoutRenderer.render(slotStageState, slotRenderBuffers, camera, objectRenderManager);
    slotOptionCycleSystem.tick(slotStageState, slotRenderBuffers, lastVintWord);
}
```

- [ ] **Step 5: Run the targeted option/runtime tests again**

Run: `mvn -q "-Dtest=TestS3kSlotOptionCycleSystem,TestS3kSlotBonusStageRuntime,TestS3kSlotLayoutRenderer" surefire:test`

Expected: `BUILD SUCCESS` and no remaining production references to the deleted approximation reel classes.

- [ ] **Step 6: Commit the option-cycle slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionCycleSystem.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageState.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionCycleSystem.java
git commit -m "feat: port S3K slot option cycle"
```

## Task 5: Port Cage, Reward Objects, And Exit Flow

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`

- [ ] **Step 1: Write failing cage/reward/exit tests**

```java
@Test
void cageWritesEventsBgAnchorAndLocksPlayerOnCapture() {
    S3kSlotStageState state = S3kSlotStageState.bootstrap();
    S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(spawn(), state);
    TestSlotSprite player = new TestSlotSprite();

    cage.update(0, player);

    assertEquals(0x460, state.eventsBgX());
    assertEquals(0x430, state.eventsBgY());
}

@Test
void ringRewardGrantsRingAndTransitionsToSparkle() {
    S3kSlotRingRewardObjectInstance ring = new S3kSlotRingRewardObjectInstance(spawn(), S3kSlotStageState.bootstrap());
    ring.activate(0x460, 0x430, 0x460, 0x430);

    for (int i = 0; i < 0x1A; i++) {
        ring.update(i, new TestSlotSprite());
    }

    assertTrue(ring.isInSparkle());
}
```

- [ ] **Step 2: Run the targeted tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects,TestS3kSlotBonusStageRuntime" surefire:test`

Expected: `FAIL` because the cage still uses runtime rendering/fallback ownership and exit is not fully ROM-shaped.

- [ ] **Step 3: Port cage, reward, and exit behavior**

```java
@Override
public void appendRenderCommands(List<GLCommand> commands) {
    PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SLOT_BONUS_CAGE);
    if (renderer == null) {
        return;
    }
    renderer.addFrameCommands(commands, mappingFrame, currentX, currentY, false, false);
}
```

```java
if (state.exitMode() == S3kSlotStageState.ExitMode.FADE) {
    if (--palFadeDelay < 0) {
        palFadeDelay = 2;
        GameServices.fade().fadeStepToBlack();
    }
}
```

- [ ] **Step 4: Remove runtime-side cage and reward rendering fallbacks**

```java
public void render(Camera camera) {
    slotLayoutRenderer.render(slotStageState, slotRenderBuffers, camera, objectRenderManager);
    // no manual cage/reward rendering here
}
```

- [ ] **Step 5: Run the targeted cage/reward/exit tests again**

Run: `mvn -q "-Dtest=TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects,TestS3kSlotBonusStageRuntime,TestS3kSlotBonusStageCoordinator" surefire:test`

Expected: `BUILD SUCCESS` with object-owned rendering and exit handoff in place.

- [ ] **Step 6: Commit the cage/reward/exit slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java
git commit -m "feat: port S3K slot cage and rewards"
```

## Task 6: Port Scroll/Palette Systems And Remove Superseded Mess

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlSlots.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotsPaletteCycling.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/scroll/SwScrlSlotsTest.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotPlayerRuntime.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`

- [ ] **Step 1: Write failing scroll/palette cleanup tests**

```java
@Test
void slotScrollUsesEventsBgAnchor() {
    SwScrlSlots scroll = new SwScrlSlots();
    S3kSlotStageState state = S3kSlotStageState.bootstrap();
    state.setEventsBg(0x460, 0x430);

    scroll.updateForTest(state, 0x300, 0x200);

    assertNotEquals(0, scroll.lastForegroundOriginXForTest());
}

@Test
void slotPaletteModeTracksAuthoritativeStageState() {
    S3kSlotStageState state = S3kSlotStageState.bootstrap();
    state.setPaletteCycleEnabled(true);

    assertEquals(1, Sonic3kPaletteCycler.resolveSlotsModeForTest(state));
}
```

- [ ] **Step 2: Run the targeted scroll/palette tests and verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotsPaletteCycling,SwScrlSlotsTest" surefire:test`

Expected: `FAIL` because the scroll handler still uses the approximation model and palette mode is not yet sourced from the final authoritative state seams.

- [ ] **Step 3: Port slot scroll/background and finalize palette integration**

```java
public final class SwScrlSlots extends AbstractZoneScrollHandler {

    @Override
    public void updateScroll() {
        int originX = 0x400 - slotState.eventsBgX() + camera.getX();
        int originY = 0x400 - slotState.eventsBgY() + camera.getY();
        updateForegroundWindow(originX, originY);
        updateBackgroundScroll(slotState.scalarIndex1(), slotState.backgroundAccumulator());
    }
}
```

```java
private int resolveMode() {
    if (coordinator.activeSlotRuntime() == null) {
        return 0;
    }
    return coordinator.activeSlotRuntime().stageStateForPalette().paletteCycleEnabled() ? 1 : 0;
}
```

- [ ] **Step 4: Remove the remaining broken approximation seams and obsolete tests**

```bash
rg -n "S3kSlotReelStateMachine|S3kSlotGridCollision|S3kSlotTileInteraction|S3kSlotOptionAnimator|renderRewardRings|renderRewardSpikes|renderCage" src/main/java src/test/java
```

```java
@Test
void runtimeExposesOnlyAuthoritativeSlotState() {
    S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();

    runtime.bootstrap();

    assertNotNull(runtime.stageStateForTest());
    assertNotNull(runtime.renderBuffersForTest());
    assertNull(runtime.legacyReelStateMachineForTest());
}
```

- [ ] **Step 5: Run the final focused verification suite**

Run: `mvn -q -DskipTests compile`

Run: `mvn -q "-Dtest=TestS3kSlotStageState,TestS3kSlotPlayerRuntime,TestS3kSlotCollisionSystem,TestS3kSlotOptionCycleSystem,TestS3kSlotLayoutRenderer,TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects,TestS3kSlotBonusStageRuntime,TestS3kSlotsPaletteCycling,SwScrlSlotsTest,TestS3kSlotBonusStageCoordinator" surefire:test`

Run: `mvn -q "-Dtest=TestS3kSlotBonusStageRuntime" "-Ds3k.rom.path=C:/Users/farre/IdeaProjects/sonic-engine/Sonic and Knuckles & Sonic 3 (W) [!].gen" surefire:test`

Expected: all focused slot remediation tests green; if unrelated repository-wide baseline failures still exist outside Slots, document them explicitly and do not conflate them with the slot remediation result.

- [ ] **Step 6: Commit the final cleanup and verification slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/scroll/SwScrlSlots.java src/main/java/com/openggf/game/sonic3k/Sonic3kPaletteCycler.java src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java src/test/java/com/openggf/game/sonic3k/TestS3kSlotsPaletteCycling.java src/test/java/com/openggf/game/sonic3k/scroll/SwScrlSlotsTest.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots src/main/java/com/openggf/game/sonic3k/objects
git commit -m "feat: finish S3K slot remediation"
```
