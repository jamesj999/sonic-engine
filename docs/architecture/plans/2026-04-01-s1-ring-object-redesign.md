# S1 Ring Object Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `Sonic1PhantomRingInstance` with individual `Sonic1RingInstance` objects (one per ring, one slot each) and fix per-game ring timing bugs.

**Architecture:** S1 ring objects own their slot lifecycle (spawn, touch response, sparkle countdown, destruction) while RingManager remains the unified renderer and collection detector across all three games. Per-game differences (sparkle delay, collision size, lightning shield) become explicit configuration in `PhysicsFeatureSet`.

**Tech Stack:** Java 21, Maven, JUnit

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `PhysicsFeatureSet.java` | Modify | Add `ringSparkleDelay` field |
| `Sonic3kRingArt.java` | Modify | Add `SPARKLE_FRAME_DELAY = 5`, use 7-param constructor |
| `Sonic2RingArt.java` | Modify | Add explicit `SPARKLE_FRAME_DELAY = 8`, use 7-param constructor |
| `RingManager.java` | Modify | Add explicit lightning shield config guard |
| `Sonic1RingPlacement.java` | Modify | Return `ObjectSpawn → List<RingSpawn>` mapping, keep ring spawns in object list |
| `Sonic1.java` | Modify | Pass ring-spawn mapping to object registry |
| `Sonic1ObjectRegistry.java` | Modify | Accept ring-spawn map, create `Sonic1RingInstance` |
| `Sonic1RingInstance.java` | Create | Individual ring object replacing phantom |
| `Sonic1PhantomRingInstance.java` | Delete | Replaced by `Sonic1RingInstance` |
| `ObjectManager.java` | Modify | Remove `preAllocatePhantomRingChildren()` and its call site |
| `TestRingSparkleDelay.java` | Create | Per-game sparkle delay verification |
| `TestSonic1RingInstance.java` | Create | Ring object lifecycle tests |

---

### Task 1: Fix S3K sparkle delay

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kRingArt.java:34-56`
- Test: `src/test/java/com/openggf/tests/TestRingSparkleDelay.java`

- [ ] **Step 1: Write test verifying sparkle delays per game**

```java
package com.openggf.tests;

import com.openggf.level.Pattern;
import com.openggf.level.rings.RingFrame;
import com.openggf.level.rings.RingFramePiece;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestRingSparkleDelay {

    @Test
    public void testS1SparkleDelayIs6() {
        RingSpriteSheet sheet = buildSheet(8, 6, 4, 4);
        assertEquals(6, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testS2SparkleDelayIs8() {
        RingSpriteSheet sheet = buildSheet(8, 8, 4, 4);
        assertEquals(8, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testS3kSparkleDelayIs5() {
        RingSpriteSheet sheet = buildSheet(8, 5, 4, 4);
        assertEquals(5, sheet.getSparkleFrameDelay());
    }

    @Test
    public void testDefaultConstructorUsesSpinDelayForSparkle() {
        // 6-param constructor defaults sparkle delay to spin delay
        Pattern pattern = new Pattern();
        RingFramePiece piece = new RingFramePiece(0, 0, 1, 1, 0, false, false, 0);
        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = List.of(frame, frame, frame, frame, frame, frame, frame, frame);
        RingSpriteSheet sheet = new RingSpriteSheet(new Pattern[]{pattern}, frames, 1, 8, 4, 4);
        assertEquals(8, sheet.getSparkleFrameDelay());
    }

    private RingSpriteSheet buildSheet(int spinDelay, int sparkleDelay, int spinFrames, int sparkleFrames) {
        Pattern pattern = new Pattern();
        RingFramePiece piece = new RingFramePiece(0, 0, 1, 1, 0, false, false, 0);
        RingFrame frame = new RingFrame(List.of(piece));
        List<RingFrame> frames = new java.util.ArrayList<>();
        for (int i = 0; i < spinFrames + sparkleFrames; i++) {
            frames.add(frame);
        }
        return new RingSpriteSheet(new Pattern[]{pattern}, frames, 1, spinDelay, sparkleDelay, spinFrames, sparkleFrames);
    }
}
```

- [ ] **Step 2: Run test to verify it passes (tests existing behavior)**

Run: `mvn test -Dtest=TestRingSparkleDelay -q`
Expected: All 4 tests PASS (these test the RingSpriteSheet constructors, which already work correctly)

- [ ] **Step 3: Fix Sonic3kRingArt to use correct sparkle delay**

In `Sonic3kRingArt.java`, add the constant and switch to the 7-parameter constructor:

```java
// After line 34 (RING_FRAME_DELAY):
    /**
     * Ani_Ring sparkle delay byte = 5 in S3K (same value as S1 but for different reasons).
     * AnimateSprite displays each frame for 5 VBlanks. Different from the spin rate
     * because sparkle uses per-object AnimateSprite, not the global SynchroAnimate.
     */
    private static final int SPARKLE_FRAME_DELAY = 5;
```

Change the constructor call in `load()` from:
```java
        cached = new RingSpriteSheet(patterns, frames, RING_PALETTE_INDEX, RING_FRAME_DELAY,
                SPIN_FRAME_COUNT, SPARKLE_FRAME_COUNT);
```
to:
```java
        cached = new RingSpriteSheet(patterns, frames, RING_PALETTE_INDEX, RING_FRAME_DELAY,
                SPARKLE_FRAME_DELAY, SPIN_FRAME_COUNT, SPARKLE_FRAME_COUNT);
```

- [ ] **Step 4: Make Sonic2RingArt sparkle delay explicit**

In `Sonic2RingArt.java`, add after line 22:
```java
    /**
     * S2 sparkle delay matches spin delay (8 VBlanks per frame). Made explicit
     * rather than relying on 6-param constructor default for clarity.
     */
    private static final int SPARKLE_FRAME_DELAY = 8;
```

Change the constructor call in `load()` from:
```java
        cached = new RingSpriteSheet(patterns, frameSet.frames(), RING_PALETTE_INDEX, RING_FRAME_DELAY,
                frameSet.spinFrameCount(), frameSet.sparkleFrameCount());
```
to:
```java
        cached = new RingSpriteSheet(patterns, frameSet.frames(), RING_PALETTE_INDEX, RING_FRAME_DELAY,
                SPARKLE_FRAME_DELAY, frameSet.spinFrameCount(), frameSet.sparkleFrameCount());
```

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=TestRingSparkleDelay,TestRingManager -q`
Expected: All PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kRingArt.java \
       src/main/java/com/openggf/game/sonic2/Sonic2RingArt.java \
       src/test/java/com/openggf/tests/TestRingSparkleDelay.java
git commit -m "fix: S3K sparkle delay corrected to 5 VBlanks, S2 made explicit"
```

---

### Task 2: Add ringSparkleDelay to PhysicsFeatureSet

**Files:**
- Modify: `src/main/java/com/openggf/game/PhysicsFeatureSet.java`

- [ ] **Step 1: Add ringSparkleDelay field**

Add after line 24 (`int ringFloorCheckMask,`):
```java
        /** VBlanks per sparkle animation frame.
         *  S1: 6 (Ani_Ring delay=5, AnimateSprite: 5→0→wrap = 6 ticks).
         *  S2: 8 (same as spin delay).
         *  S3K: 5 (Ani_Ring delay=5 but AnimateSprite ticks differently). */
        int ringSparkleDelay,
```

- [ ] **Step 2: Add constants**

Add after line 54 (`RING_FLOOR_CHECK_MASK_S2`):
```java
    /** S1: sparkle frame delay = 6 VBlanks (Ani_Ring delay byte 5). */
    public static final int RING_SPARKLE_DELAY_S1 = 6;
    /** S2: sparkle frame delay = 8 VBlanks (same as spin). */
    public static final int RING_SPARKLE_DELAY_S2 = 8;
    /** S3K: sparkle frame delay = 5 VBlanks (Ani_Ring delay byte 5). */
    public static final int RING_SPARKLE_DELAY_S3K = 5;
```

- [ ] **Step 3: Update the three static instances**

`SONIC_1` (line 60-62) — add `RING_SPARKLE_DELAY_S1` after `RING_FLOOR_CHECK_MASK_S1`:
```java
    public static final PhysicsFeatureSet SONIC_1 = new PhysicsFeatureSet(
            false, null, CollisionModel.UNIFIED, true, LOOK_SCROLL_DELAY_NONE, true, true, false, false, false, false,
            RING_FLOOR_CHECK_MASK_S1, RING_SPARKLE_DELAY_S1, null, (short) 0, true, false, false);
```

`SONIC_2` (line 68-71) — add `RING_SPARKLE_DELAY_S2` after `RING_FLOOR_CHECK_MASK_S2`:
```java
    public static final PhysicsFeatureSet SONIC_2 = new PhysicsFeatureSet(true, new short[]{
            0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00
    }, CollisionModel.DUAL_PATH, false, LOOK_SCROLL_DELAY_S2, false, false, false, false, true, true,
            RING_FLOOR_CHECK_MASK_S2, RING_SPARKLE_DELAY_S2, null, (short) 0, true, false, true);
```

`SONIC_3K` (line 79-84) — add `RING_SPARKLE_DELAY_S3K` after `RING_FLOOR_CHECK_MASK_S2`:
```java
    public static final PhysicsFeatureSet SONIC_3K = new PhysicsFeatureSet(true, new short[]{
            0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00
    }, CollisionModel.DUAL_PATH, false, LOOK_SCROLL_DELAY_S2, false, false, true, true, true, true,
            RING_FLOOR_CHECK_MASK_S2, RING_SPARKLE_DELAY_S3K, new short[]{
            0x0B00, 0x0B80, 0x0C00, 0x0C80, 0x0D00, 0x0D80, 0x0E00, 0x0E80, 0x0F00
    }, (short) 0x100, true, true, true);
```

- [ ] **Step 4: Fix any compilation errors from the new record field**

The record now has one more parameter. Search for any other code that constructs `PhysicsFeatureSet` directly and add the new parameter.

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run existing tests**

Run: `mvn test -q`
Expected: All tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/PhysicsFeatureSet.java
git commit -m "feat: add ringSparkleDelay to PhysicsFeatureSet for per-game sparkle timing"
```

---

### Task 3: Add explicit lightning shield guard in RingManager

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java:129-148`

- [ ] **Step 1: Add feature set lookup and guard**

Replace the lightning shield section (lines 129-148):

```java
        // Lightning shield ring attraction
        if (player.getShieldType() == ShieldType.LIGHTNING) {
```

with:

```java
        // Lightning shield ring attraction — S3K only
        PhysicsFeatureSet featureSet = null;
        PhysicsProvider physProvider = GameModuleRegistry.getCurrent() != null
                ? GameModuleRegistry.getCurrent().getPhysicsProvider() : null;
        if (physProvider != null) {
            featureSet = physProvider.getFeatureSet();
        }
        if (featureSet != null && featureSet.elementalShieldsEnabled()
                && player.getShieldType() == ShieldType.LIGHTNING) {
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Run existing tests**

Run: `mvn test -Dtest=TestRingManager -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/rings/RingManager.java
git commit -m "fix: gate lightning shield ring attraction behind elementalShieldsEnabled"
```

---

### Task 4: Update Sonic1RingPlacement to return ObjectSpawn→RingSpawn mapping

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1RingPlacement.java`

- [ ] **Step 1: Update Result record**

Replace the `Result` record (line 57):
```java
    public record Result(List<RingSpawn> rings, List<ObjectSpawn> remainingObjects) {}
```
with:
```java
    /**
     * Result of extracting rings from the object list.
     *
     * @param rings              expanded individual ring positions for RingManager
     * @param remainingObjects   all objects INCLUDING ring entries (0x25)
     * @param ringSpawnMapping   maps each ring ObjectSpawn to its expanded RingSpawn positions
     *                           (index 0 = parent position, 1..N = children)
     */
    public record Result(List<RingSpawn> rings, List<ObjectSpawn> remainingObjects,
                         java.util.Map<ObjectSpawn, List<RingSpawn>> ringSpawnMapping) {}
```

- [ ] **Step 2: Update extract() to keep ring objects and build mapping**

Replace the `extract()` method (lines 66-79):
```java
    public Result extract(List<ObjectSpawn> allObjects) {
        List<RingSpawn> rings = new ArrayList<>();
        List<ObjectSpawn> remaining = new ArrayList<>();
        java.util.Map<ObjectSpawn, List<RingSpawn>> mapping = new java.util.IdentityHashMap<>();

        for (ObjectSpawn spawn : allObjects) {
            if (spawn.objectId() == RING_OBJECT_ID) {
                List<RingSpawn> expanded = new ArrayList<>();
                expandRing(spawn, expanded);
                rings.addAll(expanded);
                mapping.put(spawn, List.copyOf(expanded));
            }
            // All objects stay in the list (ring objects no longer removed)
            remaining.add(spawn);
        }

        return new Result(List.copyOf(rings), List.copyOf(remaining),
                java.util.Collections.unmodifiableMap(mapping));
    }
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (Sonic1.java still compiles because `Result` fields are additive)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/Sonic1RingPlacement.java
git commit -m "feat: Sonic1RingPlacement returns ObjectSpawn->RingSpawn mapping and keeps ring objects"
```

---

### Task 5: Create Sonic1RingInstance

**Files:**
- Create: `src/main/java/com/openggf/game/sonic1/objects/Sonic1RingInstance.java`
- Test: `src/test/java/com/openggf/tests/TestSonic1RingInstance.java`

- [ ] **Step 1: Write unit test for ring instance lifecycle**

```java
package com.openggf.tests;

import com.openggf.game.sonic1.objects.Sonic1RingInstance;
import com.openggf.level.objects.TouchResponseProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1RingInstance {

    @Test
    public void testRingCollisionFlagsBeforeCollection() {
        // Ring collision type $47 = powerup ($40) + size index 7
        assertEquals(0x47, Sonic1RingInstance.RING_COLLISION_FLAGS);
    }

    @Test
    public void testImplementsTouchResponseProvider() {
        assertTrue(TouchResponseProvider.class.isAssignableFrom(Sonic1RingInstance.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic1RingInstance -q`
Expected: FAIL — `Sonic1RingInstance` does not exist

- [ ] **Step 3: Implement Sonic1RingInstance**

Create `src/main/java/com/openggf/game/sonic1/objects/Sonic1RingInstance.java`:

```java
package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.Sonic1RingPlacement;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.rings.RingManager;
import com.openggf.level.rings.RingSpawn;

import java.util.List;

/**
 * ROM-faithful ring object for Sonic 1. Each instance represents a single ring
 * occupying one dynamic slot, matching the ROM's Ring_Main / FindFreeObj behavior.
 *
 * <p>Ring rendering and collection detection are handled by {@link RingManager}
 * (unified across all games). This object manages:
 * <ul>
 *   <li>Slot allocation (parent spawns children via {@code spawnChild()})</li>
 *   <li>Touch response blocking (collision flags $47 for ReactToItem scan order)</li>
 *   <li>Sparkle countdown and self-destruction</li>
 * </ul>
 */
public class Sonic1RingInstance extends AbstractObjectInstance implements TouchResponseProvider {

    /** S1 ring collision type: $47 = powerup category ($40) + size index 7. */
    public static final int RING_COLLISION_FLAGS = 0x47;

    /**
     * ROM parity: sparkle countdown from collection detection to slot release.
     * S1: 4 sparkle frames × 6 VBlanks/frame + 1 Ring_Delete frame = 25.
     */
    private static final int SPARKLE_SLOT_COUNTDOWN = 25;

    private enum State { INIT, ANIMATE, SPARKLE }

    private final RingSpawn ringSpawn;
    private final List<RingSpawn> childRingSpawns;

    private State state;
    private int sparkleCountdown;

    /**
     * Creates a parent ring instance from a layout entry.
     *
     * @param spawn           the ObjectSpawn from layout data
     * @param allRingSpawns   expanded ring positions: index 0 = this ring, 1..N = children
     */
    public Sonic1RingInstance(ObjectSpawn spawn, List<RingSpawn> allRingSpawns) {
        super(spawn, "Ring");
        this.ringSpawn = allRingSpawns.get(0);
        this.childRingSpawns = allRingSpawns.size() > 1
                ? allRingSpawns.subList(1, allRingSpawns.size())
                : List.of();
        this.state = State.INIT;
    }

    /**
     * Creates a child ring instance spawned by a parent.
     *
     * @param spawn     dynamically built spawn at child position
     * @param ringSpawn the child's RingSpawn reference in RingManager
     */
    Sonic1RingInstance(ObjectSpawn spawn, RingSpawn ringSpawn) {
        super(spawn, "Ring");
        this.ringSpawn = ringSpawn;
        this.childRingSpawns = List.of();
        this.state = State.ANIMATE;
    }

    @Override
    public void update(int frameCounter, PlayableEntity player) {
        switch (state) {
            case INIT -> {
                spawnChildren();
                state = State.ANIMATE;
            }
            case ANIMATE -> {
                RingManager ringManager = services().ringManager();
                if (ringManager != null && ringManager.isCollected(ringSpawn)) {
                    state = State.SPARKLE;
                    sparkleCountdown = SPARKLE_SLOT_COUNTDOWN;
                }
            }
            case SPARKLE -> {
                sparkleCountdown--;
                if (sparkleCountdown <= 0) {
                    setDestroyed(true);
                }
            }
        }
    }

    private void spawnChildren() {
        if (childRingSpawns.isEmpty()) {
            return;
        }
        int subtype = spawn.subtype();
        int[] spacing = Sonic1RingPlacement.getRingSpacing(subtype);
        int dx = spacing[0];
        int dy = spacing[1];
        int baseX = spawn.x();
        int baseY = spawn.y();

        for (int i = 0; i < childRingSpawns.size(); i++) {
            int childX = baseX + (i + 1) * dx;
            int childY = baseY + (i + 1) * dy;
            RingSpawn childRing = childRingSpawns.get(i);
            spawnChild(() -> new Sonic1RingInstance(buildSpawnAt(childX, childY), childRing));
        }
    }

    // ── TouchResponseProvider ─────────────────────────────────────────────

    @Override
    public int getCollisionFlags() {
        return state == State.ANIMATE ? RING_COLLISION_FLAGS : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No rendering: handled by RingManager
    }

    @Override
    public boolean isHighPriority() {
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestSonic1RingInstance -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/Sonic1RingInstance.java \
       src/test/java/com/openggf/tests/TestSonic1RingInstance.java
git commit -m "feat: add Sonic1RingInstance - individual ring object replacing phantom"
```

---

### Task 6: Wire up Sonic1RingInstance in registry and level loading

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/objects/Sonic1ObjectRegistry.java:34-42`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1.java:186-189`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectRegistry.java` (if needed for ring spawn map injection)

- [ ] **Step 1: Add ring spawn map storage to Sonic1ObjectRegistry**

The registry needs access to the `ObjectSpawn → List<RingSpawn>` mapping to pass to ring instance constructors. Add a setter and field:

In `Sonic1ObjectRegistry.java`, add before `registerDefaultFactories()`:

```java
    private java.util.Map<ObjectSpawn, java.util.List<com.openggf.level.rings.RingSpawn>> ringSpawnMapping =
            java.util.Map.of();

    public void setRingSpawnMapping(
            java.util.Map<ObjectSpawn, java.util.List<com.openggf.level.rings.RingSpawn>> mapping) {
        this.ringSpawnMapping = mapping != null ? mapping : java.util.Map.of();
    }
```

- [ ] **Step 2: Update ring factory to create Sonic1RingInstance**

Replace lines 38-42 in `registerDefaultFactories()`:

```java
        // ROM parity: ring layout entries need phantom slot placeholders so that
        // FindFreeObj/allocateSlot assigns the same slot numbers as the ROM.
        // Ring behaviour (collection, rendering) is handled by RingManager.
        factories.put(Sonic1ObjectIds.RING,
                (spawn, registry) -> new Sonic1PhantomRingInstance(spawn));
```

with:

```java
        // ROM parity: each ring layout entry becomes a real ring object that
        // occupies a slot, spawns children via spawnChild (FindFreeObj equivalent),
        // and manages its own sparkle countdown. Rendering/collection by RingManager.
        factories.put(Sonic1ObjectIds.RING,
                (spawn, registry) -> {
                    java.util.List<com.openggf.level.rings.RingSpawn> ringSpawns =
                            ringSpawnMapping.get(spawn);
                    if (ringSpawns == null || ringSpawns.isEmpty()) {
                        // Fallback: single ring at spawn position
                        ringSpawns = java.util.List.of(
                                new com.openggf.level.rings.RingSpawn(spawn.x(), spawn.y()));
                    }
                    return new Sonic1RingInstance(spawn, ringSpawns);
                });
```

- [ ] **Step 3: Update Sonic1.java to pass ring spawn mapping**

In `Sonic1.java`, replace lines 186-189:
```java
        List<ObjectSpawn> allObjects = objectPlacement.load(zone, act);
        Sonic1RingPlacement.Result ringResult = ringPlacement.extract(allObjects);
        List<RingSpawn> rings = ringResult.rings();
        List<ObjectSpawn> objects = ringResult.remainingObjects();
```

with:
```java
        List<ObjectSpawn> allObjects = objectPlacement.load(zone, act);
        Sonic1RingPlacement.Result ringResult = ringPlacement.extract(allObjects);
        List<RingSpawn> rings = ringResult.rings();
        List<ObjectSpawn> objects = ringResult.remainingObjects();

        // Pass ring spawn mapping to object registry so ring factories can pair
        // each ObjectSpawn with its expanded RingSpawn positions.
        var gameModule = GameModuleRegistry.getCurrent();
        if (gameModule != null) {
            var registry = gameModule.createObjectRegistry();
            if (registry instanceof Sonic1ObjectRegistry s1Registry) {
                s1Registry.setRingSpawnMapping(ringResult.ringSpawnMapping());
            }
        }
```

Note: The registry is created by `Sonic1GameModule.createObjectRegistry()`. The approach above calls `createObjectRegistry()` which returns a fresh instance — verify whether `GameModuleRegistry` caches the registry or creates a new one each time. If it caches, use the cached accessor. If `createObjectRegistry()` creates fresh instances, the mapping must be set on the instance that `ObjectManager` will use. The implementer should trace the registry lifecycle and find the right injection point — it may be more appropriate to set the mapping in `Sonic1GameModule` or pass it through to `LevelManager` where the registry is handed to `ObjectManager`.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/objects/Sonic1ObjectRegistry.java \
       src/main/java/com/openggf/game/sonic1/Sonic1.java
git commit -m "feat: wire Sonic1RingInstance into registry and level loading"
```

---

### Task 7: Remove phantom ring infrastructure

**Files:**
- Delete: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PhantomRingInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java:220-222,1510-1538`

- [ ] **Step 1: Remove preAllocatePhantomRingChildren() call**

In `ObjectManager.java`, replace lines 217-222:
```java
        // ROM parity: Ring_Main allocates child ring slots during ExecuteObjects,
        // BEFORE ObjPosLoad loads new objects. Pre-allocate here so child slots
        // get lower numbers than objects loaded in syncActiveSpawnsLoad.
        if (counterBased) {
            preAllocatePhantomRingChildren();
        }
```

with:
```java
```
(Remove the block entirely.)

- [ ] **Step 2: Remove preAllocatePhantomRingChildren() method**

Delete the entire `preAllocatePhantomRingChildren()` method (lines 1510-1538).

- [ ] **Step 3: Delete Sonic1PhantomRingInstance.java**

Delete the file: `src/main/java/com/openggf/game/sonic1/objects/Sonic1PhantomRingInstance.java`

- [ ] **Step 4: Remove import of Sonic1PhantomRingInstance from ObjectManager if present**

Search for and remove any import of `Sonic1PhantomRingInstance` in `ObjectManager.java`.

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git rm src/main/java/com/openggf/game/sonic1/objects/Sonic1PhantomRingInstance.java
git add src/main/java/com/openggf/level/objects/ObjectManager.java
git commit -m "refactor: remove phantom ring infrastructure from ObjectManager"
```

---

### Task 8: Full integration verification

**Files:** None (verification only)

- [ ] **Step 1: Run full test suite**

Run: `mvn test -q`
Expected: All tests PASS

- [ ] **Step 2: Build the project**

Run: `mvn package -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify no phantom references remain**

Search for any remaining references to `Sonic1PhantomRingInstance` or `preAllocatePhantomRingChildren`:

Run: `grep -r "PhantomRing\|preAllocatePhantom" src/main/java/ --include="*.java" -l`
Expected: No results

- [ ] **Step 4: Commit if any cleanup was needed**

Only if step 3 found stale references:
```bash
git add -A
git commit -m "chore: clean up remaining phantom ring references"
```
