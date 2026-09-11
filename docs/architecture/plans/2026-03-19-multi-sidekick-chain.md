# Multi-Sidekick Daisy Chain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Support an arbitrary number of CPU-controlled sidekick characters following the main player in a daisy chain, configured via comma-separated `SIDEKICK_CHARACTER_CODE`.

**Architecture:** Rename `TailsCpuController` to `SidekickCpuController` with a pluggable `leader` reference and `SidekickRespawnStrategy` interface. Migrate `SpriteManager.getSidekick()` to `getSidekicks()` returning an ordered list. Update `ObjectManager` to iterate sidekicks with per-sidekick overlap buffer pairs. Generalize art loading to allocate separate VRAM banks per sidekick of the same character type.

**Tech Stack:** Java 21, Maven, JUnit 5, GLFW (rendering), OpenGL

**Spec:** `docs/architecture/designs/2026-03-19-multi-sidekick-chain-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Renamed+generalized from `TailsCpuController.java` |
| `src/main/java/com/openggf/sprites/playable/SidekickRespawnStrategy.java` | Interface for per-character respawn behavior |
| `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java` | Tails fly-in respawn (extracted from current FLYING logic) |
| `src/test/java/com/openggf/game/TestSidekickConfigParsing.java` | Config parsing unit tests |
| `src/test/java/com/openggf/sprites/playable/TestSidekickChainHealing.java` | Chain healing unit tests (same package for package-private access) |
| `src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java` | VRAM bank allocation unit tests |

### Modified Files

| File | Change |
|------|--------|
| `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` | Change `cpuController` type from `TailsCpuController` to `SidekickCpuController` |
| `src/main/java/com/openggf/sprites/managers/SpriteManager.java` | Add `sidekicks` list, `getSidekicks()`, deprecate `getSidekick()` |
| `src/main/java/com/openggf/Engine.java` | Multi-sidekick spawn loop with config parsing |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | `update()` takes `List`, per-sidekick `OverlapBufferPair` in `TouchResponses` |
| `src/main/java/com/openggf/level/LevelManager.java` | ~8 `getSidekick()` → `getSidekicks()` loop migrations |
| `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java` | `setSidekickBounds()` → loop |
| `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic3k/objects/AizHollowTreeObjectInstance.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java` | `getSidekick()` → loop |
| `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FalseFloorInstance.java` | `getSidekick()` → loop |
| 16 S2 object instance files (see Task 8) | `getSidekick()` → loop |
| `src/test/java/com/openggf/level/objects/TestTouchResponseManager.java` | `update()` signature: `null` → `List.of()` |
| `src/test/java/com/openggf/tests/TestHTZBossTouchResponse.java` | `update()` signature: `null` → `List.of()` |
| `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java` | Validate comma-separated `SIDEKICK_CHARACTER_CODE` still resolves `PlayerCharacter` correctly |
| `src/test/java/com/openggf/tests/TestS2Ehz1Headless.java` | Rename: `TailsCpuController` → `SidekickCpuController`, `isFlying()` → `isApproaching()`, `State.FLYING` → `State.APPROACHING`, `getSidekick()` → `getSidekicks()` |
| `src/test/java/com/openggf/tests/TestS2PostLoadAssemblyHeadless.java` | Rename: `TailsCpuController` → `SidekickCpuController` import + constructor |
| `src/test/java/com/openggf/graphics/TestSpriteManagerRender.java` | `getSidekick()` → `getSidekicks()` empty check |
| `src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectInstance.java` | Update comment referencing `getSidekick()` |

### Deleted Files

| File | Reason |
|------|--------|
| `src/main/java/com/openggf/sprites/playable/TailsCpuController.java` | Renamed to `SidekickCpuController.java` |

---

## Task 1: Rename TailsCpuController to SidekickCpuController

This is a pure rename + field rename with no behavioral changes. Get the mechanical refactor done first so all subsequent work builds on the new names.

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Delete: `src/main/java/com/openggf/sprites/playable/TailsCpuController.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:124-127,2219-2223`
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java:238-239`
- Modify: `src/main/java/com/openggf/Engine.java:376`
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java:78`
- Modify: `src/test/java/com/openggf/tests/TestS2Ehz1Headless.java` (~25 references)
- Modify: `src/test/java/com/openggf/tests/TestS2PostLoadAssemblyHeadless.java` (import + constructor)

- [ ] **Step 1: Copy TailsCpuController.java to SidekickCpuController.java**

Copy `src/main/java/com/openggf/sprites/playable/TailsCpuController.java` to `SidekickCpuController.java` in the same package. In the new file:
- Rename class to `SidekickCpuController`
- Rename field `sonic` (line 43) to `leader`
- Rename method `findSonic()` (line 439) to `findLeader()`
- Rename state `FLYING` to `APPROACHING` in the `State` enum (line 37)
- Rename `isFlying()` (line 454) to `isApproaching()`: `public boolean isApproaching() { return state == State.APPROACHING; }`
- Rename `enterFlyingState()` (line 341) to `enterApproachingState()`
- Rename `respawnToFlying()` (line 130) to `respawnToApproaching()`
- Rename `updateFlying()` (line 150) to `updateApproaching()`
- Update switch case: `case FLYING -> updateFlying()` becomes `case APPROACHING -> updateApproaching()`
- Update all internal references to `sonic` → `leader`, `FLYING` → `APPROACHING`
- Update the Javadoc from "Sonic 2 Tails CPU follower" to "CPU-controlled sidekick follower with daisy-chain support"

- [ ] **Step 2: Update AbstractPlayableSprite to use SidekickCpuController**

In `AbstractPlayableSprite.java`:
- Line 127: Change type from `private TailsCpuController cpuController` to `private SidekickCpuController cpuController`
- Line 2219: Change return type of `getCpuController()` from `TailsCpuController` to `SidekickCpuController`
- Line 2223: Change parameter type of `setCpuController()` from `TailsCpuController` to `SidekickCpuController`

- [ ] **Step 3: Update SpriteManager.java**

Line 239: Change `cpuController.isFlying()` to `cpuController.isApproaching()`.
Update the comment on line 238 from "If flying" to "If approaching (respawn in progress)".

- [ ] **Step 4: Update Engine.java to use SidekickCpuController**

Line 376: Change `TailsCpuController cpuController = new TailsCpuController(sidekick)` to `SidekickCpuController cpuController = new SidekickCpuController(sidekick)`

- [ ] **Step 5: Update Sonic2ZoneEvents.java**

Line 78: The `getCpuController()` call returns the new type — verify no cast to `TailsCpuController` exists. If it does, update it.

- [ ] **Step 6: Update TestS2Ehz1Headless.java**

This file has ~25 references to the old names. Update ALL of:
- Import: `TailsCpuController` → `SidekickCpuController`
- Field type (line 270): `TailsCpuController` → `SidekickCpuController`
- Constructor (line 277): `new TailsCpuController(tails)` → `new SidekickCpuController(tails)`
- All `TailsCpuController.State.*` constants (~15 occurrences) → `SidekickCpuController.State.*`
- All `State.FLYING` → `State.APPROACHING`
- All `isFlying()` calls (7 occurrences) → `isApproaching()`
- Comment on line 40: `TestTailsCpuController` → `TestSidekickCpuController`

- [ ] **Step 7: Update TestS2PostLoadAssemblyHeadless.java**

- Import: `TailsCpuController` → `SidekickCpuController`
- Constructor call (line 224): `new TailsCpuController(tails)` → `new SidekickCpuController(tails)`

- [ ] **Step 8: Search for any remaining TailsCpuController/isFlying references**

Run: `grep -r "TailsCpuController\|isFlying" src/`

Update any remaining references found.

- [ ] **Step 9: Delete TailsCpuController.java**

Delete `src/main/java/com/openggf/sprites/playable/TailsCpuController.java`.

- [ ] **Step 10: Build and run tests**

Run: `mvn test -q`

Expected: All existing tests pass — this is a pure rename with no behavioral change.

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor: rename TailsCpuController to SidekickCpuController, FLYING to APPROACHING"
```

---

## Task 2: Add leader field and getEffectiveLeader() chain healing

Replace the `findSonic()` scan with an explicit `leader` reference and add the chain healing method.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Create: `src/test/java/com/openggf/game/TestSidekickChainHealing.java`

- [ ] **Step 1: Write the failing test for chain healing**

Create `src/test/java/com/openggf/sprites/playable/TestSidekickChainHealing.java` (same package as `SidekickCpuController` for package-private access):

```java
package com.openggf.sprites.playable;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SidekickCpuController.getEffectiveLeader() chain healing.
 * Uses minimal TestableSprite stubs — no ROM/OpenGL required.
 */
class TestSidekickChainHealing {

    /** Minimal stub for testing chain relationships. */
    static class TestableSprite extends AbstractPlayableSprite {
        TestableSprite(String code) { super(code, (short) 0, (short) 0); }
        @Override public void draw() {}
        @Override public void defineSpeeds() {}
    }

    @Test
    void settledLeaderReturnedDirectly() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        sk1.setCpuControlled(true);
        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        sk1.setCpuController(ctrl1);

        // Simulate settled: force to NORMAL for 15+ frames
        ctrl1.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        assertSame(main, ctrl1.getEffectiveLeader(),
            "When leader is main player (not CPU), should return main directly");
    }

    @Test
    void unsettledMiddleSkipsToMainPlayer() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        TestableSprite sk2 = new TestableSprite("knux_p3");
        sk1.setCpuControlled(true);
        sk2.setCpuControlled(true);

        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        SidekickCpuController ctrl2 = new SidekickCpuController(sk2, sk1);
        sk1.setCpuController(ctrl1);
        sk2.setCpuController(ctrl2);

        // sk1 is NOT settled (spawning state)
        ctrl1.forceStateForTest(SidekickCpuController.State.SPAWNING, 0);

        assertSame(main, ctrl2.getEffectiveLeader(),
            "When direct leader is unsettled, should walk up chain to main player");
    }

    @Test
    void settledMiddleStopsChainWalk() {
        TestableSprite main = new TestableSprite("sonic");
        TestableSprite sk1 = new TestableSprite("tails_p2");
        TestableSprite sk2 = new TestableSprite("knux_p3");
        sk1.setCpuControlled(true);
        sk2.setCpuControlled(true);

        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, main);
        SidekickCpuController ctrl2 = new SidekickCpuController(sk2, sk1);
        sk1.setCpuController(ctrl1);
        sk2.setCpuController(ctrl2);

        // sk1 IS settled
        ctrl1.forceStateForTest(SidekickCpuController.State.NORMAL, 20);

        assertSame(sk1, ctrl2.getEffectiveLeader(),
            "When direct leader is settled, should return it directly");
    }

    @Test
    void nullLeaderReturnsNull() {
        TestableSprite sk1 = new TestableSprite("tails_p2");
        sk1.setCpuControlled(true);
        SidekickCpuController ctrl1 = new SidekickCpuController(sk1, null);
        sk1.setCpuController(ctrl1);

        assertNull(ctrl1.getEffectiveLeader(),
            "Null leader should return null (level transition case)");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSidekickChainHealing -q`

Expected: Compilation failure — `SidekickCpuController` constructor doesn't accept leader param yet, `getEffectiveLeader()` and `forceStateForTest()` don't exist.

- [ ] **Step 3: Implement leader field and chain healing**

In `SidekickCpuController.java`:

1. Add a second constructor parameter: `public SidekickCpuController(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader)`. Keep the existing single-arg constructor as: `public SidekickCpuController(AbstractPlayableSprite sidekick) { this(sidekick, null); }` for backward compatibility.

2. Store `leader` in the existing field (already renamed from `sonic` in Task 1). Remove the `findLeader()` scan logic — replace `update()`'s null-check block with: if `leader == null`, call `clearInputs()` and return.

3. Add `setLeader(AbstractPlayableSprite leader)` setter.

4. Add `getLeader()` getter returning the direct `leader` reference.

5. Add `isSettled()`: returns `true` when `state == State.NORMAL` and a new `normalFrameCount` counter >= 15. Increment `normalFrameCount` at the start of `updateNormal()`, reset to 0 on any state transition away from NORMAL.

6. Add a `sidekickCount` field (set via constructor or setter, defaults to 1) used as the cycle guard in `getEffectiveLeader()`.

7. Add `getEffectiveLeader()` method per the spec pseudocode:
```java
public AbstractPlayableSprite getEffectiveLeader() {
    AbstractPlayableSprite current = leader;
    int maxSteps = sidekickCount; // cycle guard — matches spec
    while (current != null && current.isCpuControlled() && maxSteps-- > 0) {
        SidekickCpuController ctrl = current.getCpuController();
        if (ctrl == null) {
            return current;
        }
        if (ctrl.isSettled()) {
            return current;
        }
        current = ctrl.getLeader();
    }
    return current;
}
```

8. Add `setInitialState(State state)` — public method for production use (setting SPAWNING on off-screen sidekicks at spawn time). Also add `forceStateForTest(State state, int normalFrames)` package-private method for testing only (also sets `normalFrameCount`).

9. Update the `update()` method to use `getEffectiveLeader()` when reading history buffers (the `NORMAL` state's position/input reads).

- [ ] **Step 4: Update Engine.java constructor call**

Change line 376 from `new SidekickCpuController(sidekick)` to `new SidekickCpuController(sidekick, mainSprite)`.

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=TestSidekickChainHealing -q`

Expected: All 4 tests PASS.

Run: `mvn test -q`

Expected: Full suite passes — no behavioral change for existing single-sidekick usage.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add leader field and getEffectiveLeader() chain healing to SidekickCpuController"
```

---

## Task 3: Extract SidekickRespawnStrategy interface and TailsRespawnStrategy

Extract the Tails-specific APPROACHING (formerly FLYING) logic into a strategy.

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/SidekickRespawnStrategy.java`
- Create: `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

- [ ] **Step 1: Create the SidekickRespawnStrategy interface**

Create `src/main/java/com/openggf/sprites/playable/SidekickRespawnStrategy.java`:

```java
package com.openggf.sprites.playable;

/**
 * Strategy for per-character respawn behavior during the APPROACHING state.
 * Implementations define how a sidekick visually re-enters the game after despawning.
 */
public interface SidekickRespawnStrategy {
    /**
     * Called each frame while in APPROACHING state.
     * @return true when respawn is complete and sidekick should transition to NORMAL
     */
    boolean updateApproaching(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader,
                              int frameCounter);

    /**
     * Position and initialize the sidekick when transitioning from SPAWNING to APPROACHING.
     */
    void beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader);
}
```

- [ ] **Step 2: Create TailsRespawnStrategy**

Create `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`.

Extract the body of `respawnToFlying()` into `beginApproach()` and the body of `updateApproaching()` (formerly `updateFlying()`) into `updateApproaching()`. These methods currently reference constants and fields from `SidekickCpuController` — pass them as constructor params or make the strategy hold a reference to the controller.

The cleanest approach: `TailsRespawnStrategy` receives the `SidekickCpuController` in its constructor so it can access `getLeader()`, animation IDs, and constants. The interface methods receive the sidekick and leader directly.

- [ ] **Step 3: Wire strategy into SidekickCpuController**

Add field: `private SidekickRespawnStrategy respawnStrategy`

In constructor, default to `new TailsRespawnStrategy(this)`.

Add `setRespawnStrategy(SidekickRespawnStrategy strategy)` setter for future character-specific strategies.

Update `respawnToApproaching()` (renamed from `respawnToFlying()`) to call `respawnStrategy.beginApproach(sidekick, leader)`.

Update `updateApproaching()` (renamed from `updateFlying()`) to delegate to `respawnStrategy.updateApproaching(sidekick, leader, frameCounter)` and transition to NORMAL when it returns true.

- [ ] **Step 4: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass — behavior is identical, just restructured.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: extract SidekickRespawnStrategy interface and TailsRespawnStrategy"
```

---

## Task 4: Add getSidekicks() to SpriteManager

Add the list-based API alongside the existing singular method.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java:161-171`

- [ ] **Step 1: Add sidekicks list field and getSidekicks() method**

In `SpriteManager.java`:

1. Add field: `private final List<AbstractPlayableSprite> sidekicks = new ArrayList<>()`

2. Add method:
```java
/**
 * Returns all CPU-controlled sidekick sprites in chain order.
 * Returns empty list when sidekicks are suppressed for the current zone.
 */
public List<AbstractPlayableSprite> getSidekicks() {
    if (isCpuSidekickSuppressed()) {
        return List.of();
    }
    return Collections.unmodifiableList(sidekicks);
}
```

3. Deprecate `getSidekick()`:
```java
/**
 * @deprecated Use {@link #getSidekicks()} instead. Returns first sidekick for compatibility.
 */
@Deprecated
public AbstractPlayableSprite getSidekick() {
    List<AbstractPlayableSprite> list = getSidekicks();
    return list.isEmpty() ? null : list.getFirst();
}
```

4. Maintain the list: in `addSprite()`, if the sprite is `AbstractPlayableSprite` and `isCpuControlled()`, append to `sidekicks`. In `removeSprite()`, remove from `sidekicks`. In `clearAllSprites()` and `resetState()` (the actual cleanup methods used during level transitions), clear `sidekicks` and `sidekickCharacterNames`.

- [ ] **Step 2: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass. `getSidekick()` now delegates to `getSidekicks()` — identical behavior.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/sprites/managers/SpriteManager.java
git commit -m "feat: add getSidekicks() list API to SpriteManager, deprecate getSidekick()"
```

---

## Task 5: Multi-sidekick config parsing and spawn loop

Parse comma-separated `SIDEKICK_CHARACTER_CODE` and spawn multiple sidekicks with chain leader assignment.

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java:360-379`
- Create: `src/test/java/com/openggf/game/TestSidekickConfigParsing.java`

- [ ] **Step 1: Write the failing test for config parsing**

Create `src/test/java/com/openggf/game/TestSidekickConfigParsing.java`:

```java
package com.openggf.game;

import com.openggf.Engine;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for parsing comma-separated SIDEKICK_CHARACTER_CODE values.
 */
class TestSidekickConfigParsing {

    @Test
    void emptyStringReturnsEmptyList() {
        assertEquals(List.of(), Engine.parseSidekickConfig(""));
    }

    @Test
    void singleValueReturnsSingletonList() {
        assertEquals(List.of("tails"), Engine.parseSidekickConfig("tails"));
    }

    @Test
    void commaSeparatedReturnsList() {
        assertEquals(List.of("tails", "knuckles", "sonic"),
            Engine.parseSidekickConfig("tails,knuckles,sonic"));
    }

    @Test
    void whitespaceIsTrimmed() {
        assertEquals(List.of("tails", "sonic"),
            Engine.parseSidekickConfig(" tails , sonic "));
    }

    @Test
    void duplicatesPreserved() {
        assertEquals(List.of("sonic", "sonic", "sonic"),
            Engine.parseSidekickConfig("sonic,sonic,sonic"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSidekickConfigParsing -q`

Expected: Compilation failure — `Engine.parseSidekickConfig()` doesn't exist.

- [ ] **Step 3: Add parseSidekickConfig() to Engine**

Add a `static` package-private method to `Engine.java`:

```java
static List<String> parseSidekickConfig(String value) {
    if (value == null || value.isBlank()) {
        return List.of();
    }
    return Arrays.stream(value.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
}
```

- [ ] **Step 4: Run config parsing tests**

Run: `mvn test -Dtest=TestSidekickConfigParsing -q`

Expected: All 5 tests PASS.

- [ ] **Step 5: Update the spawn block in Engine.java**

Replace the current single-sidekick block (lines 360-379) with a loop:

```java
List<String> sidekickNames = parseSidekickConfig(
    configService.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
boolean sidekickAllowed = GameModuleRegistry.getCurrent().supportsSidekick()
        || CrossGameFeatureProvider.isActive();
if (sidekickAllowed) {
    AbstractPlayableSprite previousLeader = mainSprite;
    int cameraLeftBound = Camera.getInstance().getX(); // or 0 if camera not yet positioned
    for (int i = 0; i < sidekickNames.size(); i++) {
        String charName = sidekickNames.get(i);
        String code = charName + "_p" + (i + 2);
        int spawnX = Math.max(cameraLeftBound, mainSprite.getX() - 0x20 * (i + 1));
        boolean offScreen = (mainSprite.getX() - 0x20 * (i + 1)) < cameraLeftBound;

        AbstractPlayableSprite sidekick;
        if ("tails".equalsIgnoreCase(charName)) {
            sidekick = new Tails(code, (short) spawnX, (short) (mainSprite.getY() + 4));
        } else {
            sidekick = new Sonic(code, (short) spawnX, (short) (mainSprite.getY() + 4));
        }
        sidekick.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(sidekick, previousLeader);
        controller.setSidekickCount(sidekickNames.size());
        if (offScreen) {
            controller.setInitialState(SidekickCpuController.State.SPAWNING);
        }
        sidekick.setCpuController(controller);
        spriteManager.addSprite(sidekick, charName); // pass character name for art loading
        previousLeader = sidekick;
    }
}
```

**Important:** Add an overload `SpriteManager.addSprite(AbstractPlayableSprite sprite, String characterName)` that stores the character name in a `Map<AbstractPlayableSprite, String> sidekickCharacterNames`. This map is used by Tasks 7 and 9 for art loading and VRAM bank allocation. The existing `addSprite(Sprite)` continues to work for non-sidekick sprites.

- [ ] **Step 6: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: parse comma-separated sidekick config and spawn multi-sidekick chain"
```

---

## Task 6: ObjectManager signature migration

Change `ObjectManager.update()` to accept `List<AbstractPlayableSprite>` and iterate touch responses per sidekick with proper double-buffered overlap tracking.

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java:106-112,958-1410`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:912-913,928-929`
- Modify: `src/test/java/com/openggf/level/objects/TestTouchResponseManager.java` (all `update()` calls)
- Modify: `src/test/java/com/openggf/tests/TestHTZBossTouchResponse.java:80,109,114`

- [ ] **Step 1: Change ObjectManager.update() signature**

In `ObjectManager.java`:

Change both overloads:
```java
// Line 106
public void update(int cameraX, AbstractPlayableSprite player,
                   List<AbstractPlayableSprite> sidekicks, int touchFrameCounter) {
    update(cameraX, player, sidekicks, touchFrameCounter, true);
}

// Line 110
public void update(int cameraX, AbstractPlayableSprite player,
                   List<AbstractPlayableSprite> sidekicks,
                   int touchFrameCounter, boolean enableTouchResponses) {
```

In the method body (~line 176), replace:
```java
if (sidekick != null) {
    touchResponses.updateSidekick(sidekick, touchFrameCounter);
}
```
with:
```java
for (AbstractPlayableSprite sk : sidekicks) {
    touchResponses.updateSidekick(sk, touchFrameCounter);
}
```

- [ ] **Step 2: Refactor TouchResponses to use per-sidekick OverlapBufferPair**

In `ObjectManager.java`, in the `TouchResponses` inner class:

Replace the four dedicated sidekick buffers:
```java
// DELETE these:
private final Set<ObjectInstance> sidekickBufferA = ...;
private final Set<ObjectInstance> sidekickBufferB = ...;
private Set<ObjectInstance> sidekickOverlapping = sidekickBufferA;
private Set<ObjectInstance> sidekickBuilding = sidekickBufferB;
```

With a map of buffer pairs:
```java
private static class OverlapBufferPair {
    final Set<ObjectInstance> bufferA = Collections.newSetFromMap(new IdentityHashMap<>());
    final Set<ObjectInstance> bufferB = Collections.newSetFromMap(new IdentityHashMap<>());
    Set<ObjectInstance> overlapping = bufferA;
    Set<ObjectInstance> building = bufferB;

    void swap() {
        Set<ObjectInstance> temp = overlapping;
        overlapping = building;
        building = temp;
    }

    void reset() {
        bufferA.clear();
        bufferB.clear();
        overlapping = bufferA;
        building = bufferB;
    }
}

private final Map<AbstractPlayableSprite, OverlapBufferPair> sidekickOverlaps = new IdentityHashMap<>();
```

Update `updateSidekick()` to get-or-create the buffer pair for the given sidekick:
```java
OverlapBufferPair buffers = sidekickOverlaps.computeIfAbsent(sk, k -> new OverlapBufferPair());
```

Then use `buffers.overlapping` / `buffers.building` / `buffers.swap()` in place of the old field references.

**Also update `checkMultiRegionTouchSidekick()` (lines 1380-1411)** — this method directly accesses `sidekickBuilding.add()` and `sidekickOverlapping.contains()`. It must also use the per-sidekick `OverlapBufferPair` via a parameter or by looking up the sidekick in the map. Pass the `OverlapBufferPair` as a parameter to this method since `updateSidekick()` already has it.

Update `reset()` to call `sidekickOverlaps.values().forEach(OverlapBufferPair::reset)`.

- [ ] **Step 3: Update LevelManager call sites**

In `LevelManager.java`:

Line 912-913 (updateObjects):
```java
// Before:
AbstractPlayableSprite sidekick = spriteManager.getSidekick();
objectManager.update(Camera.getInstance().getX(), playable, sidekick, frameCounter + 1);
// After:
List<AbstractPlayableSprite> sidekicks = spriteManager.getSidekicks();
objectManager.update(Camera.getInstance().getX(), playable, sidekicks, frameCounter + 1);
```

Line 928-929 (updateObjectPositionsWithoutTouches): same pattern.

- [ ] **Step 4: Update test files**

In `TestTouchResponseManager.java`: Replace all `objectManager.update(cameraX, player, null, frame)` calls with `objectManager.update(cameraX, player, List.of(), frame)`.

In `TestHTZBossTouchResponse.java`: Same — `null` → `List.of()` at lines 80, 109, 114.

- [ ] **Step 5: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: ObjectManager.update() accepts List<sidekicks> with per-sidekick overlap buffers"
```

---

## Task 7: LevelManager getSidekick() → getSidekicks() migration

Migrate remaining LevelManager call sites from singular to list iteration.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Migrate ring collection (line ~966)**

```java
// Before:
AbstractPlayableSprite sidekick = spriteManager.getSidekick();
if (sidekick != null && !sidekick.getDead()) {
    ringManager.update(Camera.getInstance().getX(), sidekick, frameCounter + 1);
    ...
}
// After:
for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
    if (!sidekick.getDead()) {
        ringManager.update(Camera.getInstance().getX(), sidekick, frameCounter + 1);
        ...
    }
}
```

- [ ] **Step 2: Migrate art loading (line ~1097)**

This is the most complex call site. Read the current block at lines 1096-1144 carefully. The loop needs to:
1. Track VRAM bank slot counts per character type using a `Map<String, Integer>`
2. For each sidekick, determine its character name from a parallel list (store alongside sidekick references or derive from sprite code by stripping `_pN` suffix)
3. If character matches main character name, start slot counting from 1 (main player is slot 0)
4. Clone `SpriteArtSet` with shifted `basePatternIndex` per slot

The sidekick character names should be stored in `SpriteManager` alongside the sidekick list, or derived from the config. The simplest approach: store a `Map<AbstractPlayableSprite, String> sidekickCharacterNames` in `SpriteManager`, populated during `addSprite()` via an overload or setter.

- [ ] **Step 3: Migrate state reset (line ~1157)**

```java
// Before:
AbstractPlayableSprite sidekick = spriteManager.getSidekick();
if (sidekick != null) { sidekick.resetState(); ... }
// After:
for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
    sidekick.resetState();
    ...
}
```

- [ ] **Step 4: Migrate spawnSidekick() (line ~3977)**

Rename to `spawnSidekicks()`. Loop over `getSidekicks()` and reposition each with progressive offsets.

- [ ] **Step 5: Migrate dynamic object reregistration (line ~4319)**

```java
// Before:
reregisterPlayerDynamicObjects(spriteManager.getSidekick());
// After:
for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
    reregisterPlayerDynamicObjects(sidekick);
}
```

- [ ] **Step 6: Migrate act transition offset (line ~4272)**

```java
// Before:
AbstractPlayableSprite sidekick = spriteManager.getSidekick();
if (sidekick != null) { ... }
// After:
for (AbstractPlayableSprite sidekick : spriteManager.getSidekicks()) {
    ...
}
```

- [ ] **Step 7: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: migrate LevelManager from getSidekick() to getSidekicks() loops"
```

---

## Task 8: Object instance getSidekick() → getSidekicks() migration

Mechanical migration of ~24 object instance files.

**Files (S2 objects — 16 files):**
- `src/main/java/com/openggf/game/sonic2/objects/OOZLauncherObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/TornadoObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/LateralCannonObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/HPropellerObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/SlidingSpikesObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/FanObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/ForcedSpinObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2ARZBossInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/WFZPalSwitcherObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/TiltingPlatformObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/SpeedLauncherObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/SeesawObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/OOZPoppingPlatformObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/LauncherBallObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/LauncherSpringObjectInstance.java`
- `src/main/java/com/openggf/game/sonic2/objects/BarrierObjectInstance.java`

**Files (S3K objects — 5 files):**
- `src/main/java/com/openggf/game/sonic3k/objects/AizHollowTreeObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/AizRideVineObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/AizGiantRideVineObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/S3kSignpostInstance.java`

**Files (S3K events — 1 file):**
- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`

**Files (S2 events — 1 file):**
- `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java`

**Files (S1 objects — 1 file):**
- `src/main/java/com/openggf/game/sonic1/objects/bosses/Sonic1FalseFloorInstance.java`

**Files (S2 objects using isCpuControlled — check these too):**
- `src/main/java/com/openggf/game/sonic2/objects/MonitorObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kMonitorObjectInstance.java`

**Files (Level event manager — validate comma-separated config):**
- `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java:108`

- [ ] **Step 1: Migrate all object instance files**

The pattern for each file is the same. Find the `getSidekick()` call and wrap in a loop:

```java
// Before:
AbstractPlayableSprite sidekick = SpriteManager.getInstance().getSidekick();
if (sidekick != null) {
    doSomething(sidekick);
}

// After:
for (AbstractPlayableSprite sidekick : SpriteManager.getInstance().getSidekicks()) {
    doSomething(sidekick);
}
```

**Slot-array objects:** `AizHollowTreeObjectInstance` uses fixed-size per-player arrays (`progress[2]`, `riding[2]`) indexed by `PLAYER_SLOT_SIDEKICK` (slot 1). Multiple sidekicks sharing slot 1 would corrupt state. For this object specifically, pass only the **first sidekick** to preserve current behavior:
```java
List<AbstractPlayableSprite> sidekicks = SpriteManager.getInstance().getSidekicks();
if (!sidekicks.isEmpty()) {
    updatePlayer(sidekicks.getFirst(), PLAYER_SLOT_SIDEKICK, false);
}
```
Add a `// TODO: multi-slot support for hollow tree` comment. Other vine objects (`AizRideVineObjectInstance`, `AizGiantRideVineObjectInstance`) do not use slot arrays and can iterate all sidekicks normally.

For `Sonic2ZoneEvents.setSidekickBounds()`: loop over `getSidekicks()` and set bounds on each controller.

For `Sonic2LevelEventManager.java` line 108: verify that reading `SIDEKICK_CHARACTER_CODE` as a non-empty check still works with comma-separated values (e.g. `"tails,knuckles"` is non-empty → resolves to `SONIC_AND_TAILS`). If the logic compares exact strings rather than checking emptiness, update to use `!parseSidekickConfig(value).isEmpty()`.

- [ ] **Step 2: Migrate test files**

In `TestS2Ehz1Headless.java` (line 773): Change `getSidekick()` to `getSidekicks().getFirst()`.

In `TestSpriteManagerRender.java` (line 93): Change `assertNull(getSidekick())` to `assertTrue(getSidekicks().isEmpty())`.

In `TestTornadoObjectInstance.java` (line 89): Update comment referencing `getSidekick()`.

- [ ] **Step 3: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: migrate ~24 object instance files from getSidekick() to getSidekicks()"
```

---

## Task 9: Art loading generalization for VRAM bank allocation

Generalize the existing same-character VRAM bank shift to handle multiple sidekicks.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java:1096-1144`
- Create: `src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java`:

```java
package com.openggf.game;

import com.openggf.sprites.art.SpriteArtSet;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests VRAM bank allocation logic for sidekicks sharing character types.
 */
class TestSidekickArtBankAllocation {

    @Test
    void differentCharactersGetSameBase() {
        // sonic main, tails sidekick — no conflict, tails uses its own base
        Map<Integer, Integer> slots = computeSlots("sonic", List.of("tails"));
        assertEquals(0, slots.get(0)); // tails sidekick: slot 0 (no conflict with sonic)
    }

    @Test
    void sameCharacterGetsShiftedBase() {
        // sonic main, sonic sidekick — conflict, sidekick gets slot 1
        Map<Integer, Integer> slots = computeSlots("sonic", List.of("sonic"));
        assertEquals(1, slots.get(0)); // sonic sidekick: slot 1 (main is slot 0)
    }

    @Test
    void multipleSameCharacterGetIncrementingSlots() {
        // sonic main, sonic+sonic+sonic sidekicks
        Map<Integer, Integer> slots = computeSlots("sonic", List.of("sonic", "sonic", "sonic"));
        assertEquals(1, slots.get(0));
        assertEquals(2, slots.get(1));
        assertEquals(3, slots.get(2));
    }

    @Test
    void mixedCharacterSlots() {
        // sonic main, tails+sonic+sonic sidekicks
        Map<Integer, Integer> slots = computeSlots("sonic", List.of("tails", "sonic", "sonic"));
        assertEquals(0, slots.get(0)); // tails: slot 0 (no conflict)
        assertEquals(1, slots.get(1)); // first sonic sidekick: slot 1
        assertEquals(2, slots.get(2)); // second sonic sidekick: slot 2
    }

    /**
     * Computes the VRAM bank slot index for each sidekick.
     * Returns Map<sidekickIndex, slotIndex>.
     */
    private Map<Integer, Integer> computeSlots(String mainChar, List<String> sidekickChars) {
        // This calls the static utility method extracted for testing
        return LevelManager.computeVramSlots(mainChar, sidekickChars);
    }
}
```

Note: `LevelManager.computeVramSlots()` will be a package-private static method extracted for testability.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSidekickArtBankAllocation -q`

Expected: Compilation failure.

- [ ] **Step 3: Extract VRAM slot computation**

Add to `LevelManager.java`:

```java
/**
 * Computes VRAM bank slot index for each sidekick.
 * Characters matching the main character start at slot 1 (main is slot 0).
 * Different characters start at slot 0 (no conflict).
 */
static Map<Integer, Integer> computeVramSlots(String mainChar, List<String> sidekickChars) {
    Map<String, Integer> nextSlot = new HashMap<>();
    // Main character occupies slot 0 for its type
    nextSlot.put(mainChar.toLowerCase(), 1);
    Map<Integer, Integer> result = new HashMap<>();
    for (int i = 0; i < sidekickChars.size(); i++) {
        String charType = sidekickChars.get(i).toLowerCase();
        int slot = nextSlot.getOrDefault(charType, 0);
        result.put(i, slot);
        nextSlot.put(charType, slot + 1);
    }
    return result;
}
```

- [ ] **Step 4: Update art loading loop**

Replace the existing single-sidekick art block (lines 1096-1144) with a loop that:
1. Calls `computeVramSlots()` with main character name and sidekick character names
2. For each sidekick, loads art (cached per character type)
3. When `slot > 0`, creates a shifted `SpriteArtSet` clone: `basePatternIndex + bankSize * slot`

- [ ] **Step 5: Run tests**

Run: `mvn test -Dtest=TestSidekickArtBankAllocation -q`

Expected: All 4 tests PASS.

Run: `mvn test -q`

Expected: Full suite passes.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: generalize VRAM bank allocation for multiple sidekicks of same character type"
```

---

## Task 10: P2 controller input routing

Ensure only the first sidekick receives P2 controller input.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java:232-244`

- [ ] **Step 1: Read the current P2 input routing code**

Read `SpriteManager.java` lines 232-265 to understand how P2 input is currently passed to the CPU controller.

- [ ] **Step 2: Gate P2 input to first sidekick only**

In the update loop, when passing P2 controller input to `cpuController.setController2Input()`, check if the sprite is the first in `sidekicks` list:

```java
if (playable.isCpuControlled() && playable.getCpuController() != null) {
    boolean isFirstSidekick = !sidekicks.isEmpty() && sidekicks.getFirst() == playable;
    if (isFirstSidekick) {
        playable.getCpuController().setController2Input(p2Held, p2Logical);
    }
    playable.getCpuController().update(frameCounter);
}
```

- [ ] **Step 3: Build and run tests**

Run: `mvn test -q`

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/sprites/managers/SpriteManager.java
git commit -m "feat: route P2 controller input to first sidekick only"
```

---

## Task 11: Remove deprecated getSidekick() and final cleanup

Clean up: remove the deprecated method, verify no remaining callers, run full test suite.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/SpriteManager.java`

- [ ] **Step 1: Search for remaining getSidekick() callers**

Run: `grep -r "getSidekick()" src/`

If any remain, migrate them to `getSidekicks()`.

- [ ] **Step 2: Remove getSidekick() method**

Remove the deprecated `getSidekick()` method from `SpriteManager.java`.

- [ ] **Step 3: Delete TailsCpuController.java if not already deleted**

Verify `TailsCpuController.java` was deleted in Task 1.

- [ ] **Step 4: Run full test suite**

Run: `mvn test -q`

Expected: All tests pass — zero references to deleted code.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove deprecated getSidekick(), final cleanup"
```

---

## Task 12: Integration smoke test

Write a headless integration test that verifies multiple sidekicks spawn and follow correctly.

**Files:**
- Create: `src/test/java/com/openggf/tests/TestMultiSidekickSpawn.java`

- [ ] **Step 1: Write the integration test**

Create `src/test/java/com/openggf/tests/TestMultiSidekickSpawn.java`.

This test should:
1. Set up HeadlessTestRunner with Sonic as main player
2. Configure `SIDEKICK_CHARACTER_CODE` to `"tails,sonic,sonic"`
3. Load EHZ1
4. Verify `getSidekicks()` returns 3 sidekicks in order
5. Verify chain leader assignment: sidekick[0].leader = main, sidekick[1].leader = sidekick[0], sidekick[2].leader = sidekick[1]
6. Step 60 frames with rightward input
7. Verify all sidekicks have moved right (X positions increased)
8. Verify chain ordering: mainPlayer.X > sidekick[0].X > sidekick[1].X > sidekick[2].X

Follow the pattern established in `TestS2Ehz1Headless.java` for setup boilerplate.

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TestMultiSidekickSpawn -q`

Expected: PASS.

- [ ] **Step 3: Write TestMultiSidekickFollowing**

Add a test that:
1. Configures 3 sidekicks, loads EHZ1
2. Steps 120 frames with rightward input
3. Verifies each sidekick's X position is approximately 17 frames behind its leader's historical X
4. Verifies chain ordering is maintained: main.X > sk[0].X > sk[1].X > sk[2].X throughout

- [ ] **Step 4: Write TestSidekickChainHealingIntegration**

Add a test that:
1. Configures 3 sidekicks, loads EHZ1, steps enough frames for all to settle
2. Despawns sidekick[1] (force to SPAWNING state)
3. Steps frames and verifies sidekick[2] now follows sidekick[0] (or main player)
4. Re-settles sidekick[1] and verifies sidekick[2] reverts to following sidekick[1]

- [ ] **Step 5: Run all integration tests**

Run: `mvn test -Dtest=TestMultiSidekickSpawn -q`

Expected: All PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add multi-sidekick integration tests (spawn, following, chain healing)"
```
