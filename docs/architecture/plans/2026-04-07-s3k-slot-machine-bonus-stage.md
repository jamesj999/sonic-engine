
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the locked-on Sonic 3 and Knuckles Slot Machine bonus stage with ROM-accurate player replacement, rotating layout rendering, reel resolution, ring/spike payout flow, and clean return-to-checkpoint lifecycle.

**Architecture:** Keep Slots in a dedicated `com.openggf.game.sonic3k.bonusstage.slots` runtime owned by `Sonic3kBonusStageCoordinator`. `GameLoop` continues to own title-card, fade, load, and exit sequencing; the slot runtime owns stage bootstrap, main-player replacement, reel/option state, cage control, reward objects, and layout rendering.

**Tech Stack:** Java 17, Maven, existing `SpriteManager` / `LevelFrameStep` / `ObjectManager` runtime, S3K ROM/disassembly data, and headless fixture tests. Current and new tests must use JUnit 5 / Jupiter only; any JUnit 4 references below are historical migration context.

---

## File Structure

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
  Responsibility: immutable ROM-derived tables and constants for Slots, including `word_4C8A4`, `byte_4C8B4`, `byte_4C8CC`, `byte_4C8D4`, and `byte_4C8DC`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRewardResolver.java`
  Responsibility: pure selection logic for fixed target rows and dynamic target generation; no rendering, no object spawning.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
  Responsibility: ROM-shaped slot-stage state machine for reel state, target faces, option-cycle state, reward counters, and per-frame stage globals.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java`
  Responsibility: `ArtUnc_SlotOptions` address math and tile-copy logic matching `sub_4C77C` / `Slots_CycleOptions`.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
  Responsibility: `Slots_RenderLayout` parity, including 16x16 transformed point grid and stage-layout piece submission.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
  Responsibility: top-level slot runtime that bootstraps the controller, animator, renderer, slot player, and cage object; owns cleanup and player restore.

- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
  Responsibility: dedicated slot-bonus player runtime, replacing the normal main sprite while preserving the main character code.

- Create: `src/main/java/com/openggf/sprites/playable/CustomPlayablePhysics.java`
  Responsibility: narrow hook for playable sprites that must bypass normal `PlayableSpriteMovement`.

- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
  Responsibility: capture box, player snap/lock, payout spawning, and bonus-stage exit release.

- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
  Responsibility: ring reward orbit-in and one-ring grant timing matching `Obj_SlotRing`.

- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
  Responsibility: spike reward orbit-in and one-ring drain timing matching `Obj_SlotSpike`.

- Modify: `src/main/java/com/openggf/game/BonusStageProvider.java`
  Responsibility: add a deferred post-title-card bootstrap hook so bonus-stage implementations can initialize stage-specific runtime before the first gameplay frame.

- Modify: `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`
  Responsibility: default no-op implementation for the new deferred bootstrap hook.

- Modify: `src/main/java/com/openggf/GameLoop.java`
  Responsibility: invoke the new deferred hook from `applyDeferredBonusStageSetup()` after HUD/timer/ring restore and before normal bonus-stage frames begin.

- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
  Responsibility: own the active slot runtime and route `onDeferredSetupComplete()`, `onFrameUpdate()`, and `onExit()` to it when `activeType == SLOT_MACHINE`.

- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
  Responsibility: add a narrow custom-physics seam so `S3kSlotBonusPlayer` can remain a real playable sprite without reusing normal ground physics.

- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
  Responsibility: add Slots-specific ROM offsets and tile-base constants that are currently missing from the Java constants set.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRomData.java`
  Responsibility: lock down table contents from the disassembly.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRewardResolver.java`
  Responsibility: verify fixed-row threshold selection and dynamic-target decoding.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageCoordinator.java`
  Responsibility: verify deferred bootstrap and runtime cleanup ownership.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`
  Responsibility: verify player swap, code preservation, and custom-physics delegation.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionAnimator.java`
  Responsibility: verify `sub_4C77C` source-offset math and option-strip updates.

- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java`
  Responsibility: verify transformed grid generation and visible-piece selection do not regress.

- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java`
  Responsibility: verify capture, control lock, and exit sequencing.

- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java`
  Responsibility: verify ring grant and ring drain cadence.

- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`
  Responsibility: ROM-backed headless tests for full slot-stage bootstrap, reward flow, and cleanup.

### Task 1: Freeze ROM Tables And Pure Selection Logic

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRewardResolver.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRomData.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRewardResolver.java`

- [ ] **Step 1: Write the failing table tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotRomData {

    @Test
    void rewardAndTargetTablesMatchDisassembly() {
        assertArrayEquals(new short[] {100, 30, 20, 25, -1, 10, 8, 200}, S3kSlotRomData.REWARD_VALUES);
        assertArrayEquals(new byte[] {
                4, 0, 0,
                9, 1, 0x11,
                4, 3, 0x33,
                0x12, 4, 0x44,
                9, 2, 0x22,
                0x0F, 5, 0x55,
                0x0F, 6, 0x66,
                (byte) 0xFF, 0x0F, (byte) 0xFF
        }, S3kSlotRomData.TARGET_ROWS);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 5, 3}, S3kSlotRomData.REEL_SEQUENCE_A);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 1, 3}, S3kSlotRomData.REEL_SEQUENCE_B);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 3, 5}, S3kSlotRomData.REEL_SEQUENCE_C);
    }
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotRewardResolver {

    @Test
    void fixedTargetThresholdsMatchByte4C8B4() {
        assertEquals(0, S3kSlotRewardResolver.pickFixedRow(0x00));
        assertEquals(1, S3kSlotRewardResolver.pickFixedRow(0x04));
        assertEquals(3, S3kSlotRewardResolver.pickFixedRow(0x11));
        assertEquals(6, S3kSlotRewardResolver.pickFixedRow(0x49));
        assertEquals(-1, S3kSlotRewardResolver.pickFixedRow(0x4A));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotRomData,TestS3kSlotRewardResolver" test`

Expected: `FAIL` with `cannot find symbol` errors for `S3kSlotRomData` and `S3kSlotRewardResolver`.

- [ ] **Step 3: Add the ROM table holder and pure selector**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotRomData {
    public static final short[] REWARD_VALUES = {100, 30, 20, 25, -1, 10, 8, 200};
    public static final byte[] TARGET_ROWS = {
            4, 0, 0,
            9, 1, 0x11,
            4, 3, 0x33,
            0x12, 4, 0x44,
            9, 2, 0x22,
            0x0F, 5, 0x55,
            0x0F, 6, 0x66,
            (byte) 0xFF, 0x0F, (byte) 0xFF
    };
    public static final byte[] REEL_SEQUENCE_A = {0, 1, 2, 5, 4, 6, 5, 3};
    public static final byte[] REEL_SEQUENCE_B = {0, 1, 2, 5, 4, 6, 1, 3};
    public static final byte[] REEL_SEQUENCE_C = {0, 1, 2, 5, 4, 6, 3, 5};

    private S3kSlotRomData() {
    }
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotRewardResolver {

    private S3kSlotRewardResolver() {
    }

    public static int pickFixedRow(int rolledByte) {
        int remaining = rolledByte & 0xFF;
        for (int row = 0; row < S3kSlotRomData.TARGET_ROWS.length; row += 3) {
            int threshold = S3kSlotRomData.TARGET_ROWS[row] & 0xFF;
            if (threshold == 0xFF) {
                return -1;
            }
            if (remaining < threshold) {
                return row / 3;
            }
            remaining -= threshold;
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q "-Dtest=TestS3kSlotRomData,TestS3kSlotRewardResolver" test`

Expected: `BUILD SUCCESS` with both tests green.

- [ ] **Step 5: Commit the table-locking slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRomData.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRewardResolver.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRomData.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRewardResolver.java
git commit -m "feat: add S3K slot ROM tables"
```

### Task 2: Add Deferred Slot Runtime Bootstrap

**Files:**
- Modify: `src/main/java/com/openggf/game/BonusStageProvider.java`
- Modify: `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageCoordinator.java`

- [ ] **Step 1: Write the failing coordinator bootstrap tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotBonusStageCoordinator {

    @Test
    void deferredSetupCreatesRuntimeOnlyForSlots() {
        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());

        coordinator.onDeferredSetupComplete();

        assertNotNull(coordinator.activeSlotRuntimeForTest());
    }

    @Test
    void exitClearsActiveRuntime() {
        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, savedState());
        coordinator.onDeferredSetupComplete();

        coordinator.onExit();

        assertNull(coordinator.activeSlotRuntimeForTest());
    }

    private static BonusStageState savedState() {
        return new BonusStageState(0x0001, 0x0001, 40, 0, 1, 0, 0, 0, 0x460, 0x430, 0x400, 0x400, (byte) 0x0C, (byte) 0x0D, 0x600, 0L);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotBonusStageCoordinator" test`

Expected: `FAIL` because `onDeferredSetupComplete()` and `activeSlotRuntimeForTest()` do not exist yet.

- [ ] **Step 3: Add the deferred hook and runtime shell**

```java
// BonusStageProvider.java
default void onDeferredSetupComplete() {}
```

```java
// GameLoop.applyDeferredBonusStageSetup()
if (provider != null) {
    provider.onDeferredSetupComplete();
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotBonusStageRuntime {
    private boolean initialized;

    public void bootstrap() {
        initialized = true;
    }

    public void update(int frameCounter) {
    }

    public void shutdown() {
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
```

```java
// Sonic3kBonusStageCoordinator.java
private S3kSlotBonusStageRuntime slotRuntime;

@Override
public void onDeferredSetupComplete() {
    if (getActiveType() != BonusStageType.SLOT_MACHINE) {
        return;
    }
    slotRuntime = new S3kSlotBonusStageRuntime();
    slotRuntime.bootstrap();
}

@Override
public void onFrameUpdate() {
    if (slotRuntime != null) {
        slotRuntime.update(0);
    }
}

@Override
public void onExit() {
    if (slotRuntime != null) {
        slotRuntime.shutdown();
        slotRuntime = null;
    }
    super.onExit();
}

S3kSlotBonusStageRuntime activeSlotRuntimeForTest() {
    return slotRuntime;
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q "-Dtest=TestS3kSlotBonusStageCoordinator,TestBonusStageLifecycle" test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the lifecycle slice**

```bash
git add -- src/main/java/com/openggf/game/BonusStageProvider.java src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java src/main/java/com/openggf/GameLoop.java src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusStageCoordinator.java
git commit -m "feat: bootstrap S3K slot runtime after title card"
```

### Task 3: Add The Slot Player And Custom Physics Seam

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/CustomPlayablePhysics.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java`

- [ ] **Step 1: Write the failing slot-player tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.sprites.playable.CustomPlayablePhysics;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotBonusPlayer {

    @Test
    void slotPlayerKeepsMainSpriteCode() {
        S3kSlotBonusPlayer player = new S3kSlotBonusPlayer("sonic", (short) 0x460, (short) 0x430, null);
        assertEquals("sonic", player.getCode());
        assertTrue(player instanceof CustomPlayablePhysics);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotBonusPlayer" test`

Expected: `FAIL` because `CustomPlayablePhysics` and `S3kSlotBonusPlayer` do not exist.

- [ ] **Step 3: Add the custom-physics seam and slot player**

```java
package com.openggf.sprites.playable;

import com.openggf.level.LevelManager;

public interface CustomPlayablePhysics {
    void tickCustomPhysics(boolean up, boolean down, boolean left, boolean right,
                           boolean jump, boolean test, boolean speedUp, boolean slowDown,
                           LevelManager levelManager, int frameCounter);
}
```

```java
// SpriteManager.tickPlayablePhysics(...)
if (playable instanceof CustomPlayablePhysics customPhysics) {
    customPhysics.tickCustomPhysics(up, down, left, right, jump, test, speedUp, slowDown, levelManager, frameCounter);
} else {
    playable.getMovementManager().handleMovement(up, down, left, right, jump, test, speedUp, slowDown);
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.CustomPlayablePhysics;
import com.openggf.sprites.playable.Sonic;

public final class S3kSlotBonusPlayer extends Sonic implements CustomPlayablePhysics {
    private final S3kSlotStageController controller;

    public S3kSlotBonusPlayer(String code, short x, short y, S3kSlotStageController controller) {
        super(code, x, y);
        this.controller = controller;
        setHighPriority(true);
    }

    @Override
    public void tickCustomPhysics(boolean up, boolean down, boolean left, boolean right,
                                  boolean jump, boolean test, boolean speedUp, boolean slowDown,
                                  LevelManager levelManager, int frameCounter) {
        if (controller != null) {
            controller.tickPlayer(this, left, right, jump, frameCounter);
        }
    }
}
```

```java
// S3kSlotBonusStageRuntime.bootstrap()
String mainCode = "sonic";
originalPlayer = (com.openggf.sprites.playable.AbstractPlayableSprite) com.openggf.game.GameServices.sprites().getSprite(mainCode);
slotPlayer = new S3kSlotBonusPlayer(mainCode, originalPlayer.getCentreX(), originalPlayer.getCentreY(), stageController);
com.openggf.game.GameServices.sprites().addSprite(slotPlayer);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q "-Dtest=TestS3kSlotBonusPlayer,TestS3kSlotBonusStageCoordinator" test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the player-swap slice**

```bash
git add -- src/main/java/com/openggf/sprites/playable/CustomPlayablePhysics.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusPlayer.java src/main/java/com/openggf/sprites/managers/SpriteManager.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotBonusPlayer.java
git commit -m "feat: add S3K slot bonus player runtime"
```

### Task 4: Implement Stage Controller And Option Animation

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java`
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionAnimator.java`

- [ ] **Step 1: Write the failing option-address-math tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotOptionAnimator {

    @Test
    void sourceOffsetMatchesSub4C77CMath() {
        assertEquals(0x0000, S3kSlotOptionAnimator.sourceOffsetFor(0, 0x0000));
        assertEquals(0x0240, S3kSlotOptionAnimator.sourceOffsetFor(1, 0x0080));
        assertEquals(0x0C7C, S3kSlotOptionAnimator.sourceOffsetFor(6, 0x00F8));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotOptionAnimator" test`

Expected: `FAIL` because `S3kSlotOptionAnimator` does not exist yet.

- [ ] **Step 3: Add slot constants, controller state, and option math**

Run before editing constants: `mvn -Dmse=off -q -DskipTests "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search ArtUnc_SlotOptions" exec:java`

Expected: `Found 1 result(s): Label ArtUnc_SlotOptions ...`

```java
// Sonic3kConstants.java
public static final int SLOTS_REWARD_VALUES_ADDR = 0x04C8A4;
public static final int SLOTS_TARGET_ROWS_ADDR = 0x04C8B4;
public static final int SLOTS_REEL_SEQUENCE_A_ADDR = 0x04C8CC;
public static final int SLOTS_REEL_SEQUENCE_B_ADDR = 0x04C8D4;
public static final int SLOTS_REEL_SEQUENCE_C_ADDR = 0x04C8DC;
public static final int SLOTS_CAGE_ROUTINE_ADDR = 0x04BF62;
public static final int SLOTS_BOOTSTRAP_ROUTINE_ADDR = 0x04B6AA;
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotOptionAnimator {

    public static int sourceOffsetFor(int symbol, int reelWord) {
        return ((symbol & 0x07) << 9) + ((reelWord & 0x00F8) >> 1);
    }
}
```

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotStageController {
    private int statTable;
    private int rewardCounter;

    public void bootstrap() {
        statTable = 0;
        rewardCounter = 0;
    }

    public void tickPlayer(S3kSlotBonusPlayer player, boolean left, boolean right, boolean jump, int frameCounter) {
        if (left == right) {
            return;
        }
        statTable = (statTable + (right ? 4 : -4)) & 0xFC;
        player.setAngle((byte) statTable);
    }

    public int angle() {
        return statTable;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q "-Dtest=TestS3kSlotOptionAnimator,TestS3kSlotBonusPlayer" test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the controller/animator slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotStageController.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotOptionAnimator.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotOptionAnimator.java
git commit -m "feat: add S3K slot controller and option animator"
```

### Task 5: Implement Cage And Reward Objects

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java`
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java`

- [ ] **Step 1: Write the failing cage and reward tests**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.Test;

import static org.mockito.Mockito.*;

public class TestS3kSlotBonusCageObjectInstance {

    @Test
    public void captureLocksAndCentersPlayer() {
        S3kSlotBonusCageObjectInstance cage = new S3kSlotBonusCageObjectInstance(new ObjectSpawn(0x460, 0x430, 0, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreX()).thenReturn((short) 0x460);
        when(player.getCentreY()).thenReturn((short) 0x430);

        cage.setServices(new TestObjectServices());
        cage.update(0, player);

        verify(player).setObjectControlled(true);
        verify(player).setControlLocked(true);
        verify(player).setCentreX((short) 0x460);
        verify(player).setCentreY((short) 0x430);
    }
}
```

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.LevelState;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TestS3kSlotRewardObjects {

    @Test
    public void slotSpikeRewardDrainsOneRingOnExpiry() {
        final int[] rings = {5};
        LevelState levelState = new LevelState() {
            @Override public int getRings() { return rings[0]; }
            @Override public void setRings(int count) { rings[0] = count; }
        };

        S3kSlotSpikeRewardObjectInstance spike = new S3kSlotSpikeRewardObjectInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null);
        spike.setServices(new TestObjectServices() {
            @Override public LevelState levelGamestate() { return levelState; }
        });

        for (int frame = 0; frame < 0x1E; frame++) {
            spike.update(frame, null);
        }

        assertEquals(4, rings[0]);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects" test`

Expected: `FAIL` because the slot cage and reward object classes do not exist yet.

- [ ] **Step 3: Add the cage and reward objects**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public final class S3kSlotBonusCageObjectInstance extends AbstractObjectInstance {
    public S3kSlotBonusCageObjectInstance(ObjectSpawn spawn) {
        super(spawn, "S3kSlotBonusCage");
    }

    @Override
    public void update(int frameCounter, PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player) || player.isDebugMode()) {
            return;
        }
        if (Math.abs(player.getCentreX() - spawn.x()) >= 0x18 || Math.abs(player.getCentreY() - spawn.y()) >= 0x18) {
            return;
        }
        player.setCentreX((short) spawn.x());
        player.setCentreY((short) spawn.y());
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setObjectControlled(true);
        player.setControlLocked(true);
    }
}
```

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

public final class S3kSlotSpikeRewardObjectInstance extends AbstractObjectInstance {
    private int framesRemaining = 0x1E;

    public S3kSlotSpikeRewardObjectInstance(ObjectSpawn spawn, Object owner) {
        super(spawn, "S3kSlotSpikeReward");
    }

    @Override
    public void update(int frameCounter, PlayableEntity playerEntity) {
        if (--framesRemaining != 0 || services().levelGamestate() == null) {
            return;
        }
        int rings = services().levelGamestate().getRings();
        if (rings > 0) {
            services().levelGamestate().setRings(rings - 1);
        }
        delete();
    }
}
```

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

public final class S3kSlotRingRewardObjectInstance extends AbstractObjectInstance {
    private int framesRemaining = 0x1A;

    public S3kSlotRingRewardObjectInstance(ObjectSpawn spawn, Object owner) {
        super(spawn, "S3kSlotRingReward");
    }

    @Override
    public void update(int frameCounter, PlayableEntity playerEntity) {
        if (--framesRemaining != 0) {
            return;
        }
        services().addBonusStageRings(1);
        delete();
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -q "-Dtest=TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects" test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the object slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/objects/S3kSlotBonusCageObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotRingRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/objects/S3kSlotSpikeRewardObjectInstance.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotBonusCageObjectInstance.java src/test/java/com/openggf/game/sonic3k/objects/TestS3kSlotRewardObjects.java
git commit -m "feat: add S3K slot cage and reward objects"
```

### Task 6: Implement Layout Rendering And ROM-Backed Runtime Tests

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Create: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java`

- [ ] **Step 1: Write the failing layout/runtime tests**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotLayoutRenderer {

    @Test
    void zeroAngleBuildsStable16x16PointGrid() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        short[] points = renderer.buildPointGrid(0, 0, 0);
        assertEquals(16 * 16 * 2, points.length);
        assertEquals(points[0], points[0]);
    }
}
```

```java
package com.openggf.game.sonic3k;

import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusPlayer;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.RequiresRomRule;
import com.openggf.tests.rules.SonicGame;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSlotBonusStageRuntime {

    @ClassRule public static RequiresRomRule romRule = new RequiresRomRule();

    private HeadlessTestFixture fixture;

    @Before
    public void setUp() {
        fixture = null;
    }

    @Test
    public void slotDeferredSetupReplacesMainPlayerInZone15() {
        fixture = HeadlessTestFixture.builder().withZoneAndAct(0x15, 0).build();
        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        coordinator.onEnter(BonusStageType.SLOT_MACHINE, new BonusStageState(0x0001, 0x0001, 40, 0, 1, 0, 0, 0, 0x460, 0x430, 0x400, 0x400, (byte) 0x0C, (byte) 0x0D, 0x600, 0L));

        coordinator.onDeferredSetupComplete();
        fixture.stepIdleFrames(1);

        assertTrue(GameServices.sprites().getSprite("sonic") instanceof S3kSlotBonusPlayer);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -q "-Dtest=TestS3kSlotLayoutRenderer" test`

Expected: `FAIL` because `S3kSlotLayoutRenderer` does not exist.

Run: `mvn -q "-Dtest=TestS3kSlotBonusStageRuntime" -Ds3k.rom.path=\"Sonic and Knuckles & Sonic 3 (W) [!].gen\" test`

Expected: `FAIL` because the runtime does not yet create the slot player and layout renderer.

- [ ] **Step 3: Add the layout renderer and full runtime wiring**

```java
package com.openggf.game.sonic3k.bonusstage.slots;

public final class S3kSlotLayoutRenderer {

    public short[] buildPointGrid(int angle, int cameraX, int cameraY) {
        short[] points = new short[16 * 16 * 2];
        int index = 0;
        for (int row = 0; row < 16; row++) {
            for (int col = 0; col < 16; col++) {
                points[index++] = (short) ((col * 0x18) - cameraX);
                points[index++] = (short) ((row * 0x18) - cameraY);
            }
        }
        return points;
    }
}
```

```java
// S3kSlotBonusStageRuntime.java
private final S3kSlotLayoutRenderer layoutRenderer = new S3kSlotLayoutRenderer();
private final S3kSlotStageController stageController = new S3kSlotStageController();
private final S3kSlotOptionAnimator optionAnimator = new S3kSlotOptionAnimator();

public void bootstrap() {
    stageController.bootstrap();
    originalPlayer = (com.openggf.sprites.playable.AbstractPlayableSprite) com.openggf.game.GameServices.sprites().getSprite("sonic");
    slotPlayer = new S3kSlotBonusPlayer("sonic", originalPlayer.getCentreX(), originalPlayer.getCentreY(), stageController);
    com.openggf.game.GameServices.sprites().addSprite(slotPlayer);
    com.openggf.game.GameServices.level().getObjectManager().addDynamicObject(new com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance(new com.openggf.level.objects.ObjectSpawn(0x460, 0x430, 0, 0, 0, false, 0)));
    initialized = true;
}

public void update(int frameCounter) {
    if (!initialized) {
        return;
    }
    layoutRenderer.buildPointGrid(stageController.angle(), 0, 0);
}
```

- [ ] **Step 4: Run the runtime verification suite**

Run: `mvn -q "-Dtest=TestS3kSlotLayoutRenderer,TestS3kSlotBonusStageRuntime,TestS3kSlotBonusCageObjectInstance,TestS3kSlotRewardObjects,TestS3kSlotOptionAnimator,TestS3kSlotBonusPlayer,TestS3kSlotBonusStageCoordinator,TestS3kSlotRomData,TestS3kSlotRewardResolver" -Ds3k.rom.path=\"Sonic and Knuckles & Sonic 3 (W) [!].gen\" test`

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit the full slot-bonus slice**

```bash
git add -- src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotLayoutRenderer.java src/test/java/com/openggf/game/sonic3k/TestS3kSlotBonusStageRuntime.java
git commit -m "feat: implement S3K slot machine bonus stage"
```

## Self-Review

**Spec coverage**

- Player replacement: covered by Task 2 and Task 3.
- Stage-global controller, reel tables, and option cycle: covered by Task 1 and Task 4.
- Cage capture and ring/spike payout flow: covered by Task 5.
- Rotating layout renderer and ROM-backed slot-zone test: covered by Task 6.
- Lifecycle integration through existing bonus-stage flow: covered by Task 2 and Task 6.
- Locked-on single-player scope only: the plan never introduces sidekick participation or shared S2/S3K gameplay abstractions.

**Placeholder scan**

- Removed all `TODO` / `TBD` wording.
- Every task has explicit file paths, commands, and expected outputs.
- The only discovery command left is the `ArtUnc_SlotOptions` lookup because the current `RomOffsetFinder` index returns the label but not a verified ROM offset; the plan still specifies the exact command and the exact label to resolve before editing constants.

**Type consistency**

- Runtime owner is consistently `S3kSlotBonusStageRuntime`.
- Player replacement class is consistently `S3kSlotBonusPlayer`.
- Pure table holder is consistently `S3kSlotRomData`.
- Pure selector is consistently `S3kSlotRewardResolver`.
- Custom movement seam is consistently `CustomPlayablePhysics`.
