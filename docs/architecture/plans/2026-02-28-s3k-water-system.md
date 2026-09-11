# S3K Water System Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement full S3K water support (static heights, dynamic handlers, underwater palettes) via a provider-based architecture that replaces game-specific methods in WaterSystem.

**Architecture:** New `WaterDataProvider` interface returned by `GameModule`. Each game implements its own provider. `WaterSystem` becomes game-agnostic, consuming provider data. Dynamic per-frame water behavior is encapsulated in `DynamicWaterHandler` implementations. S2/S1 water is migrated to providers for consistency.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, ROM binary data, existing `WaterSystem`/`DrowningController`/`AbstractPlayableSprite` infrastructure.

**Design doc:** `docs/architecture/designs/2026-02-28-s3k-water-system-design.md`

---

### Task 1: Create WaterDataProvider and DynamicWaterHandler interfaces

**Files:**
- Create: `src/main/java/com/openggf/game/WaterDataProvider.java`
- Create: `src/main/java/com/openggf/game/DynamicWaterHandler.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java` (add `getWaterDataProvider()`)

**Step 1: Create `DynamicWaterHandler` interface**

```java
package com.openggf.game;

import com.openggf.level.WaterSystem;

/**
 * Per-frame dynamic water level handler. Each zone with dynamic water
 * (rising/falling based on camera position) provides an implementation.
 * Called once per frame from {@link WaterSystem#update()}.
 */
public interface DynamicWaterHandler {
    /**
     * Update water target/mean based on current camera position.
     *
     * @param state   mutable water state (current level, target, speed)
     * @param cameraX camera X position in world pixels
     * @param cameraY camera Y position in world pixels
     */
    void update(WaterSystem.DynamicWaterState state, int cameraX, int cameraY);
}
```

**Step 2: Create `WaterDataProvider` interface**

```java
package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.level.Palette;

/**
 * Provides water configuration for a specific game (S1/S2/S3K).
 * Returned by {@link GameModule#getWaterDataProvider()}.
 * <p>
 * Each game implements this to supply zone/act-specific water heights,
 * underwater palettes, and dynamic water handlers from its ROM data.
 */
public interface WaterDataProvider {
    /**
     * Does this zone/act/character combination have water?
     */
    boolean hasWater(int zoneId, int actId, PlayerCharacter character);

    /**
     * Starting water height in pixels (Y world coordinate).
     */
    int getStartingWaterLevel(int zoneId, int actId);

    /**
     * Load underwater palette data from ROM.
     *
     * @return 4-line palette array, or null if no underwater palette
     */
    Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character);

    /**
     * Get the dynamic water handler for per-frame updates.
     *
     * @return handler instance, or null for static water levels
     */
    DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character);

    /**
     * Water movement speed in pixels per frame (ROM: Water_speed).
     * Default is 1.
     */
    default int getWaterSpeed(int zoneId, int actId) {
        return 1;
    }
}
```

**Step 3: Add `getWaterDataProvider()` to `GameModule`**

Add after `getZoneFeatureProvider()` (line 136):

```java
/**
 * Returns the water data provider for this game.
 * Provides zone-specific water heights, palettes, and dynamic handlers.
 *
 * @return the water data provider, or null if water not supported
 */
default WaterDataProvider getWaterDataProvider() {
    return null;
}
```

**Step 4: Build to verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 5: Commit**

```
feat: add WaterDataProvider and DynamicWaterHandler interfaces

New provider-based architecture for game-specific water data.
GameModule.getWaterDataProvider() returns null by default.
```

---

### Task 2: Promote DynamicWaterState and add speed/handler fields

**Files:**
- Modify: `src/main/java/com/openggf/level/WaterSystem.java` (DynamicWaterState: lines 95-128)

**Step 1: Change DynamicWaterState from private to public static**

Change visibility of `DynamicWaterState` from `private static class` to `public static class`. Add fields:

```java
public static class DynamicWaterState {
    private int currentLevel;
    private int targetLevel;
    private int meanLevel;      // NEW: ROM's Mean_water_level
    private boolean rising;
    private int speed;           // NEW: pixels per frame (ROM: Water_speed, default 1)
    private DynamicWaterHandler handler; // NEW: per-frame update logic

    public DynamicWaterState(int initialLevel) {
        this.currentLevel = initialLevel;
        this.targetLevel = initialLevel;
        this.meanLevel = initialLevel;
        this.rising = false;
        this.speed = 1;
        this.handler = null;
    }

    public void setTarget(int targetY) {
        this.targetLevel = targetY;
        this.rising = (meanLevel != targetLevel);
    }

    /** Set mean level directly (ROM bit-15 convention: instant teleport). */
    public void setMeanDirect(int level) {
        this.meanLevel = level;
        this.currentLevel = level;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public void setHandler(DynamicWaterHandler handler) {
        this.handler = handler;
    }

    public DynamicWaterHandler getHandler() {
        return handler;
    }

    public int getCurrentLevel() {
        return currentLevel;
    }

    public int getTargetLevel() {
        return targetLevel;
    }

    public int getMeanLevel() {
        return meanLevel;
    }

    /** Move mean toward target by speed pixels. Returns true if still moving. */
    public boolean update() {
        if (!rising) {
            return false;
        }
        for (int i = 0; i < speed; i++) {
            if (meanLevel > targetLevel) {
                meanLevel--;
            } else if (meanLevel < targetLevel) {
                meanLevel++;
            }
            if (meanLevel == targetLevel) {
                rising = false;
                break;
            }
        }
        currentLevel = meanLevel;
        return rising;
    }
}
```

**Step 2: Update existing references to `currentLevel` → use `meanLevel` semantics**

In `getWaterLevelY()` (line 464), `dynamicState.currentLevel` is now accessed via getter. Ensure `WaterSystem` internal references compile. The `update()` method in `WaterSystem` (line 617-621) should call `dynamicState.update()` which already exists.

**Step 3: Build and run existing water tests**

Run: `mvn test -Dtest=com.openggf.tests.WaterSystemTest,com.openggf.tests.WaterPhysicsTest -q`
Expected: All tests pass (existing behavior preserved)

**Step 4: Commit**

```
refactor: promote DynamicWaterState to public, add speed and handler fields

Adds meanLevel (ROM's Mean_water_level), speed (pixels/frame),
and DynamicWaterHandler reference. Existing update() now moves
by speed pixels instead of hardcoded 1.
```

---

### Task 3: Add game-agnostic loadForLevel to WaterSystem

**Files:**
- Modify: `src/main/java/com/openggf/level/WaterSystem.java`

**Step 1: Add new overload that takes WaterDataProvider**

Add below existing `loadForLevelS1()` method:

```java
/**
 * Load water configuration using a game-agnostic provider.
 * Replaces game-specific loadForLevel/loadForLevelS1 methods.
 *
 * @param provider  game-specific water data provider
 * @param rom       ROM data
 * @param zoneId    zone index
 * @param actId     act index
 * @param character current player character
 */
public void loadForLevelFromProvider(WaterDataProvider provider, Rom rom,
        int zoneId, int actId, PlayerCharacter character) {
    String key = makeKey(zoneId, actId);

    if (!provider.hasWater(zoneId, actId, character)) {
        waterConfigs.put(key, new WaterConfig(false, 0, null));
        LOGGER.info(String.format("Zone %d Act %d: No water (provider)", zoneId, actId));
        return;
    }

    int height = provider.getStartingWaterLevel(zoneId, actId);
    Palette[] palette = provider.getUnderwaterPalette(rom, zoneId, actId, character);
    int speed = provider.getWaterSpeed(zoneId, actId);
    DynamicWaterHandler handler = provider.getDynamicHandler(zoneId, actId, character);

    waterConfigs.put(key, new WaterConfig(true, height, palette));

    DynamicWaterState state = new DynamicWaterState(height);
    state.setSpeed(speed);
    state.setHandler(handler);
    dynamicWaterStates.put(key, state);

    LOGGER.info(String.format("Zone %d Act %d: Water at Y=%d (0x%X), speed=%d, dynamic=%s, palette=%s",
            zoneId, actId, height, height, speed,
            handler != null ? "yes" : "no",
            palette != null ? "loaded" : "none"));
}
```

**Step 2: Add `updateDynamic()` method for per-frame handler dispatch**

```java
/**
 * Update dynamic water handlers for a specific level.
 * Called once per frame from LevelManager when water is active.
 *
 * @param zoneId  zone index
 * @param actId   act index
 * @param cameraX camera X position in world pixels
 * @param cameraY camera Y position in world pixels
 */
public void updateDynamic(int zoneId, int actId, int cameraX, int cameraY) {
    String key = makeKey(zoneId, actId);
    DynamicWaterState state = dynamicWaterStates.get(key);
    if (state == null) {
        return;
    }
    DynamicWaterHandler handler = state.getHandler();
    if (handler != null) {
        handler.update(state, cameraX, cameraY);
    }
    state.update(); // Move mean toward target
}
```

**Step 3: Build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 4: Commit**

```
feat: add game-agnostic loadForLevelFromProvider and updateDynamic to WaterSystem
```

---

### Task 4: Create ThresholdTableWaterHandler

**Files:**
- Create: `src/main/java/com/openggf/game/ThresholdTableWaterHandler.java`
- Create: `src/test/java/com/openggf/game/TestThresholdTableWaterHandler.java`

**Step 1: Write the failing test**

```java
package com.openggf.game;

import com.openggf.level.WaterSystem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestThresholdTableWaterHandler {

    @Test
    public void testTargetSetAtFirstMatchingThreshold() {
        var handler = new ThresholdTableWaterHandler(List.of(
            new ThresholdTableWaterHandler.WaterThreshold(0x0500, 0x0900),
            new ThresholdTableWaterHandler.WaterThreshold(0x0680, 0x2A00)
        ));
        var state = new WaterSystem.DynamicWaterState(0x0600);

        handler.update(state, 0x0400, 0); // cameraX below first threshold

        assertEquals(0x0900, state.getTargetLevel());
    }

    @Test
    public void testTargetSetAtSecondThreshold() {
        var handler = new ThresholdTableWaterHandler(List.of(
            new ThresholdTableWaterHandler.WaterThreshold(0x0500, 0x0900),
            new ThresholdTableWaterHandler.WaterThreshold(0x0680, 0x2A00)
        ));
        var state = new WaterSystem.DynamicWaterState(0x0600);

        handler.update(state, 0x0600, 0); // between thresholds

        assertEquals(0x2A00, state.getTargetLevel());
    }

    @Test
    public void testNoChangeWhenPastAllThresholds() {
        var handler = new ThresholdTableWaterHandler(List.of(
            new ThresholdTableWaterHandler.WaterThreshold(0x0500, 0x0900)
        ));
        var state = new WaterSystem.DynamicWaterState(0x0600);

        handler.update(state, 0x9000, 0); // past all thresholds

        assertEquals("Target should stay at initial", 0x0600, state.getTargetLevel());
    }

    @Test
    public void testInstantSetWhenBit15Set() {
        // Bit 15 set means instant-set mean level
        var handler = new ThresholdTableWaterHandler(List.of(
            new ThresholdTableWaterHandler.WaterThreshold(0x0500, 0x0900 | 0x8000)
        ));
        var state = new WaterSystem.DynamicWaterState(0x0600);

        handler.update(state, 0x0400, 0);

        assertEquals("Mean should be set directly", 0x0900, state.getMeanLevel());
        assertEquals("Current should match mean", 0x0900, state.getCurrentLevel());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.TestThresholdTableWaterHandler -q`
Expected: FAIL (class not found)

**Step 3: Write implementation**

```java
package com.openggf.game;

import com.openggf.level.WaterSystem;

import java.util.List;

/**
 * Scans a threshold table left-to-right, setting the water target when
 * cameraX is below or equal to a threshold. Used by S3K HCZ and LBZ zones.
 * <p>
 * If a target value has bit 15 set, the mean water level is set directly
 * (instant teleport) rather than gradually moving toward the target.
 * <p>
 * Mirrors the ROM pattern at {@code DynamicWaterHeight_HCZ1} (sonic3k.asm:8710).
 */
public class ThresholdTableWaterHandler implements DynamicWaterHandler {

    /** Camera X threshold → target water level pair. */
    public record WaterThreshold(int cameraXThreshold, int targetWaterLevel) {}

    private final List<WaterThreshold> thresholds;

    public ThresholdTableWaterHandler(List<WaterThreshold> thresholds) {
        this.thresholds = List.copyOf(thresholds);
    }

    @Override
    public void update(WaterSystem.DynamicWaterState state, int cameraX, int cameraY) {
        for (var t : thresholds) {
            if (cameraX <= t.cameraXThreshold()) {
                int target = t.targetWaterLevel();
                if ((target & 0x8000) != 0) {
                    // Bit 15 set: instant-set mean level
                    state.setMeanDirect(target & 0x7FFF);
                } else {
                    state.setTarget(target);
                }
                return;
            }
        }
        // Past all thresholds: no change
    }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.TestThresholdTableWaterHandler -q`
Expected: PASS

**Step 5: Commit**

```
feat: add ThresholdTableWaterHandler for table-driven water zones
```

---

### Task 5: Create Sonic3kWaterDataProvider with static water heights

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` (add water ROM address)
- Create: `src/main/java/com/openggf/game/sonic3k/Sonic3kWaterDataProvider.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestSonic3kWaterDataProvider.java`

**Step 1: Add ROM constant for StartingWaterHeights**

Use RomOffsetFinder to locate `StartingWaterHeights` in the S3K ROM:

```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search StartingWaterHeights" -q
```

Add the verified address to `Sonic3kConstants.java` in a new water section:

```java
// ===== WATER =====
public static final int STARTING_WATER_HEIGHTS_ADDR = 0x...; // StartingWaterHeights.bin (32 words)
```

**Step 2: Write the failing test**

```java
package com.openggf.game.sonic3k;

import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests S3K water zone detection and starting heights.
 * Does not require ROM — tests the hasWater() logic and hardcoded height table.
 */
public class TestSonic3kWaterDataProvider {

    // S3K zone IDs (from Sonic3kZoneRegistry)
    private static final int ZONE_AIZ = 0;
    private static final int ZONE_HCZ = 1;
    private static final int ZONE_MGZ = 2;
    private static final int ZONE_CNZ = 3;
    private static final int ZONE_FBZ = 4;
    private static final int ZONE_ICZ = 5;
    private static final int ZONE_LBZ = 6;
    private static final int ZONE_MHZ = 7;

    private final Sonic3kWaterDataProvider provider = new Sonic3kWaterDataProvider();

    // --- hasWater tests ---

    @Test
    public void testAiz1HasWater_SonicAndTails() {
        assertTrue("AIZ1 should have water for Sonic",
                provider.hasWater(ZONE_AIZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testAiz2HasWater_SonicAndTails() {
        assertTrue("AIZ2 should have water for Sonic",
                provider.hasWater(ZONE_AIZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testHcz1HasWater() {
        assertTrue("HCZ1 should have water",
                provider.hasWater(ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testHcz2HasWater() {
        assertTrue("HCZ2 should have water",
                provider.hasWater(ZONE_HCZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testLbz1HasWater() {
        assertTrue("LBZ1 should have water",
                provider.hasWater(ZONE_LBZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testLbz2HasWater() {
        assertTrue("LBZ2 should have water",
                provider.hasWater(ZONE_LBZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testMgzNoWater() {
        assertFalse("MGZ should not have water",
                provider.hasWater(ZONE_MGZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testCnzNoWater() {
        assertFalse("CNZ should not have water",
                provider.hasWater(ZONE_CNZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testFbzNoWater() {
        assertFalse("FBZ should not have water",
                provider.hasWater(ZONE_FBZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testMhzNoWater() {
        assertFalse("MHZ should not have water",
                provider.hasWater(ZONE_MHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    // --- Starting water height tests ---

    @Test
    public void testAiz1StartingHeight() {
        assertEquals(0x0504, provider.getStartingWaterLevel(ZONE_AIZ, 0));
    }

    @Test
    public void testAiz2StartingHeight() {
        assertEquals(0x0528, provider.getStartingWaterLevel(ZONE_AIZ, 1));
    }

    @Test
    public void testHcz1StartingHeight() {
        assertEquals(0x0500, provider.getStartingWaterLevel(ZONE_HCZ, 0));
    }

    @Test
    public void testHcz2StartingHeight() {
        assertEquals(0x0700, provider.getStartingWaterLevel(ZONE_HCZ, 1));
    }

    @Test
    public void testLbz1StartingHeight() {
        assertEquals(0x0AD8, provider.getStartingWaterLevel(ZONE_LBZ, 0));
    }

    @Test
    public void testLbz2StartingHeight() {
        assertEquals(0x0A80, provider.getStartingWaterLevel(ZONE_LBZ, 1));
    }
}
```

**Step 3: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kWaterDataProvider -q`
Expected: FAIL (class not found)

**Step 4: Implement Sonic3kWaterDataProvider**

```java
package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.level.Palette;

/**
 * S3K water data provider. Water heights from {@code StartingWaterHeights.bin},
 * zone detection from {@code CheckLevelForWater} (sonic3k.asm:9751).
 */
public class Sonic3kWaterDataProvider implements WaterDataProvider {

    // S3K zone IDs
    private static final int ZONE_AIZ = 0;
    private static final int ZONE_HCZ = 1;
    private static final int ZONE_LBZ = 6;

    /**
     * Starting water heights from StartingWaterHeights.bin (32 big-endian words).
     * Index = zone * 2 + act. Only water zones have meaningful values;
     * non-water zones have 0x0600 (off-screen default).
     */
    private static final int[][] STARTING_HEIGHTS = {
        // {act0, act1} per zone
        {0x0504, 0x0528}, // Zone 0: AIZ
        {0x0500, 0x0700}, // Zone 1: HCZ
        {0x0600, 0x0600}, // Zone 2: MGZ (no water)
        {0x0600, 0x0600}, // Zone 3: CNZ (no water)
        {0x0600, 0x0600}, // Zone 4: FBZ (no water)
        {0x0600, 0x0600}, // Zone 5: ICZ (no water)
        {0x0AD8, 0x0A80}, // Zone 6: LBZ
        {0x0600, 0x0600}, // Zone 7: MHZ (no water)
        {0x0600, 0x0600}, // Zone 8: SOZ (no water)
        {0x0600, 0x0600}, // Zone 9: LRZ (no water)
        {0x0600, 0x0600}, // Zone 10: SSZ (no water)
        {0x0600, 0x0600}, // Zone 11: DEZ (no water)
        {0x0600, 0x0600}, // Zone 12: DDZ (no water)
    };

    @Override
    public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
        // CheckLevelForWater (sonic3k.asm:9751)
        return zoneId == ZONE_AIZ || zoneId == ZONE_HCZ || zoneId == ZONE_LBZ;
    }

    @Override
    public int getStartingWaterLevel(int zoneId, int actId) {
        if (zoneId >= 0 && zoneId < STARTING_HEIGHTS.length) {
            int act = Math.min(actId, 1);
            return STARTING_HEIGHTS[zoneId][act];
        }
        return 0x0600; // Off-screen default
    }

    @Override
    public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId,
            PlayerCharacter character) {
        // Deferred to Task 9 (water palette loading)
        return null;
    }

    @Override
    public DynamicWaterHandler getDynamicHandler(int zoneId, int actId,
            PlayerCharacter character) {
        // Deferred to Task 6 (dynamic handlers)
        return null;
    }
}
```

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kWaterDataProvider -q`
Expected: PASS

**Step 6: Commit**

```
feat: add Sonic3kWaterDataProvider with static water heights

Zone detection matches CheckLevelForWater (sonic3k.asm:9751).
Heights from StartingWaterHeights.bin. Dynamic handlers and
palette loading deferred to subsequent tasks.
```

---

### Task 6: Add S3K dynamic water handlers (HCZ, LBZ, AIZ2, Ending)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kWaterDataProvider.java`
- Create: `src/main/java/com/openggf/game/sonic3k/Aiz2DynamicWaterHandler.java`
- Create: `src/test/java/com/openggf/game/sonic3k/TestSonic3kDynamicWaterHandlers.java`

**Step 1: Write failing tests for HCZ1 threshold handler**

```java
package com.openggf.game.sonic3k;

import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.WaterSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kDynamicWaterHandlers {

    private final Sonic3kWaterDataProvider provider = new Sonic3kWaterDataProvider();

    // --- HCZ1: Threshold table ---

    @Test
    public void testHcz1Handler_belowFirstThreshold() {
        DynamicWaterHandler handler = provider.getDynamicHandler(1, 0, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull("HCZ1 should have dynamic handler", handler);

        var state = new WaterSystem.DynamicWaterState(0x0500);
        handler.update(state, 0x0400, 0); // Below 0x8500
        assertEquals(0x0900, state.getTargetLevel());
    }

    // --- HCZ2: Character-branched ---

    @Test
    public void testHcz2Handler_sonicTable() {
        DynamicWaterHandler handler = provider.getDynamicHandler(1, 1, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull("HCZ2 should have dynamic handler", handler);

        var state = new WaterSystem.DynamicWaterState(0x0700);
        handler.update(state, 0x0600, 0); // Below Sonic threshold 0x0700
        assertEquals(0x3E00, state.getTargetLevel());
    }

    @Test
    public void testHcz2Handler_knucklesTable() {
        DynamicWaterHandler handler = provider.getDynamicHandler(1, 1, PlayerCharacter.KNUCKLES);
        assertNotNull("HCZ2 Knuckles should have dynamic handler", handler);

        var state = new WaterSystem.DynamicWaterState(0x0700);
        handler.update(state, 0x0600, 0);
        assertEquals("Knuckles deeper water", 0x4100, state.getTargetLevel());
    }

    // --- LBZ1: Threshold table ---

    @Test
    public void testLbz1Handler_exists() {
        DynamicWaterHandler handler = provider.getDynamicHandler(6, 0, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull("LBZ1 should have dynamic handler", handler);

        var state = new WaterSystem.DynamicWaterState(0x0AD8);
        handler.update(state, 0x0100, 0); // Below first threshold
        assertEquals(0x0E00, state.getTargetLevel());
    }

    // --- AIZ1: Static (no handler) ---

    @Test
    public void testAiz1Handler_isNull() {
        DynamicWaterHandler handler = provider.getDynamicHandler(0, 0, PlayerCharacter.SONIC_AND_TAILS);
        assertNull("AIZ1 should have no dynamic handler (static water)", handler);
    }

    // --- AIZ2: Custom handler ---

    @Test
    public void testAiz2Handler_exists() {
        DynamicWaterHandler handler = provider.getDynamicHandler(0, 1, PlayerCharacter.SONIC_AND_TAILS);
        assertNotNull("AIZ2 should have dynamic handler", handler);
    }

    // --- Non-water zones: null handler ---

    @Test
    public void testMgzHandler_isNull() {
        DynamicWaterHandler handler = provider.getDynamicHandler(2, 0, PlayerCharacter.SONIC_AND_TAILS);
        assertNull("MGZ should have no handler", handler);
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kDynamicWaterHandlers -q`
Expected: FAIL (handlers return null)

**Step 3: Create Aiz2DynamicWaterHandler**

```java
package com.openggf.game.sonic3k;

import com.openggf.game.DynamicWaterHandler;
import com.openggf.level.WaterSystem;

/**
 * AIZ2 dynamic water handler. Complex state machine with camera X triggers.
 * <p>
 * ROM reference: DynamicWaterHeight_AIZ2 (sonic3k.asm:8648-8695).
 * <ul>
 *   <li>Camera X &lt; 0x2440 and target==0x0618: drop to 0x0528, speed=2</li>
 *   <li>Camera X &gt;= 0x2850: trigger rising water event</li>
 *   <li>When triggered: raise back to 0x0618</li>
 * </ul>
 */
public class Aiz2DynamicWaterHandler implements DynamicWaterHandler {

    private static final int INITIAL_LEVEL = 0x0618;
    private static final int DROP_LEVEL = 0x0528;
    private static final int FIRST_THRESHOLD_X = 0x2440;
    private static final int TRIGGER_X = 0x2850;

    private boolean triggered = false;

    @Override
    public void update(WaterSystem.DynamicWaterState state, int cameraX, int cameraY) {
        // Phase 1: Before first threshold, drop water
        if (cameraX < FIRST_THRESHOLD_X) {
            if (state.getTargetLevel() == INITIAL_LEVEL) {
                state.setTarget(DROP_LEVEL);
                state.setSpeed(2);
            }
            return;
        }

        // Phase 2: Check for trigger zone entry
        if (!triggered && cameraX >= TRIGGER_X) {
            triggered = true;
        }

        // Phase 3: When triggered, raise water back
        if (triggered && state.getTargetLevel() != INITIAL_LEVEL) {
            state.setTarget(INITIAL_LEVEL);
            state.setSpeed(1);
        }
    }

    /** Reset state for level reload. */
    public void reset() {
        triggered = false;
    }
}
```

**Step 4: Wire dynamic handlers in Sonic3kWaterDataProvider.getDynamicHandler()**

Update `getDynamicHandler()` to return appropriate handlers:

```java
@Override
public DynamicWaterHandler getDynamicHandler(int zoneId, int actId,
        PlayerCharacter character) {
    if (zoneId == ZONE_AIZ) {
        if (actId == 0) return null; // AIZ1: static
        return new Aiz2DynamicWaterHandler(); // AIZ2: state machine
    }
    if (zoneId == ZONE_HCZ) {
        if (actId == 0) return createHcz1Handler();
        return createHcz2Handler(character);
    }
    if (zoneId == ZONE_LBZ) {
        if (actId == 0) return createLbz1Handler();
        if (character == PlayerCharacter.KNUCKLES) return createLbz2KnucklesHandler();
        return null; // LBZ2 Sonic/Tails: no dynamic water
    }
    return null;
}

private DynamicWaterHandler createHcz1Handler() {
    return new ThresholdTableWaterHandler(List.of(
        new ThresholdTableWaterHandler.WaterThreshold(0x8500, 0x0900),
        new ThresholdTableWaterHandler.WaterThreshold(0x8680, 0x2A00),
        new ThresholdTableWaterHandler.WaterThreshold(0x86A0, 0x3500)
    ));
}

private DynamicWaterHandler createHcz2Handler(PlayerCharacter character) {
    if (character == PlayerCharacter.KNUCKLES) {
        return new ThresholdTableWaterHandler(List.of(
            new ThresholdTableWaterHandler.WaterThreshold(0x0700, 0x4100),
            new ThresholdTableWaterHandler.WaterThreshold(0x8360, 0xFFFF)
        ));
    }
    return new ThresholdTableWaterHandler(List.of(
        new ThresholdTableWaterHandler.WaterThreshold(0x0700, 0x3E00),
        new ThresholdTableWaterHandler.WaterThreshold(0x07E0, 0xFFFF)
    ));
}

private DynamicWaterHandler createLbz1Handler() {
    return new ThresholdTableWaterHandler(List.of(
        new ThresholdTableWaterHandler.WaterThreshold(0x8B00, 0x0E00),
        new ThresholdTableWaterHandler.WaterThreshold(0x8A00, 0x1980),
        new ThresholdTableWaterHandler.WaterThreshold(0x8AC8, 0x2C00)
    ));
}

private DynamicWaterHandler createLbz2KnucklesHandler() {
    return new ThresholdTableWaterHandler(List.of(
        new ThresholdTableWaterHandler.WaterThreshold(0x8FF0, 0x0D80)
    ));
}
```

Add `import com.openggf.game.ThresholdTableWaterHandler;` and `import java.util.List;` to imports.

**Step 5: Run tests**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kDynamicWaterHandlers,com.openggf.game.sonic3k.TestSonic3kWaterDataProvider -q`
Expected: PASS

**Step 6: Commit**

```
feat: add S3K dynamic water handlers for HCZ, LBZ, AIZ2

ThresholdTableWaterHandler for HCZ1/2 and LBZ1/2 (character-branched).
Aiz2DynamicWaterHandler for AIZ2 state machine (drop/rise triggers).
```

---

### Task 7: Migrate S2 water to provider

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/Sonic2WaterDataProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Create: `src/test/java/com/openggf/game/sonic2/TestSonic2WaterDataProvider.java`

**Step 1: Write failing regression test**

```java
package com.openggf.game.sonic2;

import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test: S2 water detection matches original WaterSystem behavior.
 */
public class TestSonic2WaterDataProvider {

    private final Sonic2WaterDataProvider provider = new Sonic2WaterDataProvider();

    private static final int ZONE_EHZ = 0x00;
    private static final int ZONE_CPZ = 0x0D;
    private static final int ZONE_ARZ = 0x0F;
    private static final int ZONE_HTZ = 0x07;
    private static final int ZONE_MCZ = 0x0B;

    @Test
    public void testCpzHasWater() {
        assertTrue(provider.hasWater(ZONE_CPZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_CPZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testArzHasWater() {
        assertTrue(provider.hasWater(ZONE_ARZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_ARZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testHtzHasWater() {
        assertTrue(provider.hasWater(ZONE_HTZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testEhzNoWater() {
        assertFalse(provider.hasWater(ZONE_EHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testMczNoWater() {
        assertFalse(provider.hasWater(ZONE_MCZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }
}
```

**Step 2: Implement Sonic2WaterDataProvider**

Extract the existing water detection logic from `Sonic2ZoneFeatureProvider` and `WaterSystem.extractWaterHeight()` into the new provider. This class wraps the existing S2 ROM-based water height extraction (object-based + ROM table fallback).

**Step 3: Register in Sonic2GameModule**

```java
@Override
public WaterDataProvider getWaterDataProvider() {
    return new Sonic2WaterDataProvider();
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=com.openggf.game.sonic2.TestSonic2WaterDataProvider,com.openggf.tests.WaterSystemTest,com.openggf.tests.WaterPhysicsTest -q`
Expected: All pass

**Step 5: Commit**

```
feat: migrate S2 water to Sonic2WaterDataProvider
```

---

### Task 8: Migrate S1 water to provider

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/Sonic1WaterDataProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Create: `src/test/java/com/openggf/game/sonic1/TestSonic1WaterDataProvider.java`

**Step 1: Write failing regression test**

```java
package com.openggf.game.sonic1;

import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestSonic1WaterDataProvider {

    private final Sonic1WaterDataProvider provider = new Sonic1WaterDataProvider();

    // S1 zone IDs
    private static final int ZONE_GHZ = 0x00;
    private static final int ZONE_LZ = 0x01;
    private static final int ZONE_SBZ = 0x05;

    @Test
    public void testLzHasWater() {
        assertTrue(provider.hasWater(ZONE_LZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_LZ, 1, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_LZ, 2, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSbz3HasWater() {
        assertTrue(provider.hasWater(ZONE_SBZ, 2, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSbz1NoWater() {
        assertFalse(provider.hasWater(ZONE_SBZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testGhzNoWater() {
        assertFalse(provider.hasWater(ZONE_GHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testLz1WaterHeight() {
        assertEquals(0x00B8, provider.getStartingWaterLevel(ZONE_LZ, 0));
    }

    @Test
    public void testLz2WaterHeight() {
        assertEquals(0x0328, provider.getStartingWaterLevel(ZONE_LZ, 1));
    }

    @Test
    public void testLz3WaterHeight() {
        assertEquals(0x0900, provider.getStartingWaterLevel(ZONE_LZ, 2));
    }

    @Test
    public void testSbz3WaterHeight() {
        assertEquals(0x0228, provider.getStartingWaterLevel(ZONE_SBZ, 2));
    }
}
```

**Step 2: Implement Sonic1WaterDataProvider**

Extract from existing `WaterSystem.getS1WaterHeight()` and `loadS1UnderwaterPalette()`.

**Step 3: Register in Sonic1GameModule**

**Step 4: Run tests**

Run: `mvn test -Dtest=com.openggf.game.sonic1.TestSonic1WaterDataProvider -q`
Expected: PASS

**Step 5: Commit**

```
feat: migrate S1 water to Sonic1WaterDataProvider
```

---

### Task 9: Wire providers into LevelManager and WaterSystem

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (initWater + update loop)
- Modify: `src/main/java/com/openggf/level/WaterSystem.java` (deprecate old methods)
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` (register provider)
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java` (wire hasWater/getWaterLevel)

**Step 1: Register Sonic3kWaterDataProvider in Sonic3kGameModule**

```java
@Override
public WaterDataProvider getWaterDataProvider() {
    return new Sonic3kWaterDataProvider();
}
```

**Step 2: Update LevelManager.initWater()**

Replace the current game-specific routing with provider-based loading:

```java
public void initWater() throws IOException {
    Rom rom = GameServices.rom().getRom();
    WaterSystem waterSystem = WaterSystem.getInstance();
    WaterDataProvider waterProvider = gameModule.getWaterDataProvider();
    if (waterProvider != null) {
        PlayerCharacter character = gameModule.getPlayerCharacter();
        waterSystem.loadForLevelFromProvider(waterProvider, rom,
                getFeatureZoneId(), getFeatureActId(), character);
    }
}
```

Note: Need to check if `getPlayerCharacter()` exists on `GameModule`. If not, add a `default` method that returns `PlayerCharacter.SONIC_AND_TAILS`, or get it from game state.

**Step 3: Update LevelManager per-frame water update**

In the update loop (around line 865), add dynamic handler dispatch:

```java
WaterSystem waterSystem = WaterSystem.getInstance();
int featureZone = getFeatureZoneId();
int featureAct = getFeatureActId();
if (waterSystem.hasWater(featureZone, featureAct)) {
    Camera camera = Camera.getInstance();
    waterSystem.updateDynamic(featureZone, featureAct, camera.getX(), camera.getY());
}
```

**Step 4: Update Sonic3kZoneFeatureProvider**

Wire `hasWater()` and `getWaterLevel()` to delegate to `WaterSystem`:

```java
@Override
public boolean hasWater(int zoneIndex) {
    return WaterSystem.getInstance().hasWater(zoneIndex, /* actId from context */);
}
```

Note: The `ZoneFeatureProvider.hasWater(int zoneIndex)` only takes zone, not act. May need to adjust the interface or use act from level manager context.

**Step 5: Run full test suite**

Run: `mvn test -q`
Expected: All 1434+ tests pass

**Step 6: Commit**

```
feat: wire WaterDataProvider into LevelManager and S3K game module

LevelManager.initWater() now uses provider-based loading.
Per-frame update dispatches dynamic water handlers.
S3K zones with water (AIZ, HCZ, LBZ) now properly initialized.
```

---

### Task 10: Add S3K underwater palette loading

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kWaterDataProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

**Step 1: Find water palette ROM addresses**

Use RomOffsetFinder:
```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search WaterTransition" -q
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Pal_WaterKnux" -q
```

Add constants to `Sonic3kConstants.java`:

```java
public static final int WATER_PALETTE_AIZ1_ADDR = 0x...;
public static final int WATER_PALETTE_AIZ2_ADDR = 0x...;
public static final int WATER_PALETTE_HCZLBZ1_ADDR = 0x...;
public static final int WATER_PALETTE_LBZ2_ADDR = 0x...;
public static final int WATER_PALETTE_KNUCKLES_PATCH_ADDR = 0x...;
```

**Step 2: Implement getUnderwaterPalette() in Sonic3kWaterDataProvider**

Load 4-line (64-color) underwater palette from ROM based on zone/act. Apply Knuckles palette patch (6 bytes at offset 0x04) when character is Knuckles.

```java
@Override
public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId,
        PlayerCharacter character) {
    int paletteAddr = getWaterPaletteAddr(zoneId, actId);
    if (paletteAddr == 0) return null;

    Palette[] palettes = loadPaletteFromRom(rom, paletteAddr);

    if (character == PlayerCharacter.KNUCKLES && palettes != null) {
        applyKnucklesPatch(rom, palettes);
    }
    return palettes;
}
```

**Step 3: Run full test suite**

Run: `mvn test -q`
Expected: All pass

**Step 4: Commit**

```
feat: add S3K underwater palette loading with Knuckles patch
```

---

### Task 11: Remove deprecated game-specific methods from WaterSystem

**Files:**
- Modify: `src/main/java/com/openggf/level/WaterSystem.java`
- Modify: any remaining callers of `loadForLevel(Rom, int, int, List)` or `loadForLevelS1()`

**Step 1: Search for remaining callers**

Grep for `loadForLevel(` and `loadForLevelS1(` to find any code still using the old methods. Update all callers to use `loadForLevelFromProvider()`.

**Step 2: Remove old methods**

Remove `loadForLevel(Rom, int, int, List<ObjectSpawn>)`, `loadForLevelS1(Rom, int, int)`, `extractWaterHeight()`, `getS1WaterHeight()`, and any S1/S2-specific helpers that are now in providers.

**Step 3: Rename `loadForLevelFromProvider` to `loadForLevel`**

Now that the old method is gone, the provider-based method takes the clean name.

**Step 4: Run full test suite**

Run: `mvn test -q`
Expected: All pass (WaterSystemTest may need updates to use providers)

**Step 5: Commit**

```
refactor: remove deprecated game-specific water loading from WaterSystem

All water loading now goes through WaterDataProvider.
```

---

### Task 12: Add S3K visual water oscillation

**Files:**
- Modify: `src/main/java/com/openggf/level/WaterSystem.java` (getVisualWaterLevelY)

**Step 1: Update getVisualWaterLevelY for S3K zones**

The current method has S2 CPZ and S1 LZ/SBZ cases. Add S3K zone cases. Based on the ROM, S3K water oscillation is visual-only (used for H-interrupt timing), not gameplay.

Check the disassembly for which S3K zones use oscillation and the formula. If S3K zones don't oscillate (Handle_Onscreen_Water_Height just copies Mean to Water_level), then no changes are needed — the base level is returned as-is.

**Step 2: Run tests**

Run: `mvn test -q`
Expected: All pass

**Step 3: Commit**

```
feat: add S3K water visual oscillation support (if applicable)
```

---

### Task 13: Final validation sweep

**Step 1: Run full test suite**

Run: `mvn test`
Expected: All 1434+ tests pass, 0 failures

**Step 2: Run S3K-specific tests**

Run: `mvn test -Dtest=com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading -q`
Expected: All pass

**Step 3: Verify no regressions in S1/S2 water tests**

Run: `mvn test -Dtest=com.openggf.tests.WaterSystemTest,com.openggf.tests.WaterPhysicsTest -q`
Expected: All pass

**Step 4: Code review**

Request code review on the full implementation.
