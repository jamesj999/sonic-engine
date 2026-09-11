# DEZ Boss Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix Death Egg Zone so Silver Sonic spawns, camera locks work, and Death Egg Robot renders all body parts.

**Architecture:** Two targeted fixes — correct zone ID constants in the event manager, and register Death Egg Robot children with ObjectManager for rendering.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Mockito

---

### Task 1: Fix Zone ID Constants in Sonic2LevelEventManager

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java:24-38`

**Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic2/TestDEZEventDispatch.java`:

```java
package com.openggf.game.sonic2;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies zone constants in Sonic2LevelEventManager match
 * the Sonic2ZoneRegistry ordering (0-10).
 * The rest of the codebase (ParallaxManager, BackgroundCamera,
 * LevelSelectConstants, Sonic2ZoneConstants) all agree:
 * SCZ=8, WFZ=9, DEZ=10.
 */
public class TestDEZEventDispatch {

    @Test
    public void dezZoneConstantMatchesRegistry() {
        // ZoneRegistry lists DEZ at index 10 (11th zone, 0-based)
        assertEquals("ZONE_DEZ must be 10 to match ZoneRegistry",
                10, Sonic2LevelEventManager.ZONE_DEZ);
    }

    @Test
    public void wfzZoneConstantMatchesRegistry() {
        assertEquals("ZONE_WFZ must be 9 to match ZoneRegistry",
                9, Sonic2LevelEventManager.ZONE_WFZ);
    }

    @Test
    public void sczZoneConstantMatchesRegistry() {
        assertEquals("ZONE_SCZ must be 8 to match ZoneRegistry",
                8, Sonic2LevelEventManager.ZONE_SCZ);
    }

    @Test
    public void cpzZoneConstantMatchesRegistry() {
        // CPZ is index 1 in both registry and event manager
        assertEquals("ZONE_CPZ must be 1 to match ZoneRegistry",
                1, Sonic2LevelEventManager.ZONE_CPZ);
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestDEZEventDispatch -pl .`
Expected: FAIL — `ZONE_DEZ` is 11 (not 10), `ZONE_WFZ` is 10 (not 9), `ZONE_SCZ` is 9 (not 8)

**Step 3: Fix the constants**

In `Sonic2LevelEventManager.java`, replace the zone constant block (lines 24-38):

Old:
```java
    // Zone constants (matches ROM zone ordering)
    public static final int ZONE_EHZ = 0;
    public static final int ZONE_UNUSED_1 = 1;
    public static final int ZONE_ARZ = 2;
    public static final int ZONE_CNZ = 3;
    public static final int ZONE_HTZ = 4;
    public static final int ZONE_MCZ = 5;
    public static final int ZONE_OOZ = 6;
    public static final int ZONE_MTZ = 7;
    public static final int ZONE_UNUSED_2 = 8;
    public static final int ZONE_SCZ = 9;
    public static final int ZONE_WFZ = 10;
    public static final int ZONE_DEZ = 11;
    // CPZ uses zone index 1 in level event ordering (ROM zone ID 0x0D)
    public static final int ZONE_CPZ = 1;
```

New:
```java
    // Zone constants (matches Sonic2ZoneRegistry ordering: game progression, 0-based)
    public static final int ZONE_EHZ = 0;
    public static final int ZONE_CPZ = 1;
    public static final int ZONE_ARZ = 2;
    public static final int ZONE_CNZ = 3;
    public static final int ZONE_HTZ = 4;
    public static final int ZONE_MCZ = 5;
    public static final int ZONE_OOZ = 6;
    public static final int ZONE_MTZ = 7;
    public static final int ZONE_SCZ = 8;
    public static final int ZONE_WFZ = 9;
    public static final int ZONE_DEZ = 10;
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestDEZEventDispatch -pl .`
Expected: PASS (all 4 tests)

**Step 5: Run existing DEZ + HTZ tests to check for regressions**

Run: `mvn test -Dtest=TestDEZMechaSonic,TestDEZDeathEggRobot,TestHTZBossEventRoutine9,TestHTZRisingLavaDisassemblyParity,TestSwScrlHtzEarthquakeMode -pl .`
Expected: All pass (HTZ=4 unchanged, DEZ tests use mocks not zone dispatch)

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java \
        src/test/java/com/openggf/game/sonic2/TestDEZEventDispatch.java
git commit -m "fix: align Sonic2LevelEventManager zone constants with ZoneRegistry

Zone constants had ROM-ordering gaps (UNUSED_1=1, UNUSED_2=8) that
created a 1-slot offset for SCZ/WFZ/DEZ. When DEZ loaded at registry
index 10, the event switch dispatched to WFZ handler (10) instead of
DEZ (11). Removed gaps; constants now match Sonic2ZoneRegistry 0-10.

Fixes: DEZ events never firing, Silver Sonic not spawning, no camera locks."
```

---

### Task 2: Register Death Egg Robot Children with ObjectManager

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java:275-299`

**Step 1: Write the failing test**

Add to existing `src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java`:

```java
@Test
public void childrenRegisteredWithObjectManager() {
    // When ObjectManager is available, children should be registered for rendering
    com.openggf.level.objects.ObjectManager objMgr = mock(com.openggf.level.objects.ObjectManager.class);
    LevelManager lm2 = mock(LevelManager.class);
    when(lm2.getObjectManager()).thenReturn(objMgr);

    Sonic2DeathEggRobotInstance boss2 = new Sonic2DeathEggRobotInstance(
            new ObjectSpawn(BOSS_X, BOSS_Y,
                    Sonic2ObjectIds.DEATH_EGG_ROBOT, 0, 0, false, 0),
            lm2
    );

    // Verify all 10 children were registered
    org.mockito.Mockito.verify(objMgr, org.mockito.Mockito.times(10))
            .addDynamicObject(org.mockito.ArgumentMatchers.any());
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestDEZDeathEggRobot#childrenRegisteredWithObjectManager -pl .`
Expected: FAIL — `addDynamicObject()` never called (wanted 10 times, invoked 0 times)

**Step 3: Add ObjectManager registration in spawnChildren()**

In `Sonic2DeathEggRobotInstance.java`, after the `childComponents.add(...)` block and before `positionChildren()`, add ObjectManager registration:

Old (lines 287-298):
```java
        childComponents.add(shoulder);
        childComponents.add(frontLowerLeg);
        childComponents.add(frontForearm);
        childComponents.add(upperArm);
        childComponents.add(frontThigh);
        childComponents.add(head);
        childComponents.add(jet);
        childComponents.add(backLowerLeg);
        childComponents.add(backForearm);
        childComponents.add(backThigh);

        positionChildren();
```

New:
```java
        childComponents.add(shoulder);
        childComponents.add(frontLowerLeg);
        childComponents.add(frontForearm);
        childComponents.add(upperArm);
        childComponents.add(frontThigh);
        childComponents.add(head);
        childComponents.add(jet);
        childComponents.add(backLowerLeg);
        childComponents.add(backForearm);
        childComponents.add(backThigh);

        // Register children with ObjectManager for rendering (matches Silver Sonic pattern)
        if (levelManager.getObjectManager() != null) {
            for (var child : childComponents) {
                if (child instanceof com.openggf.level.objects.boss.AbstractBossChild bossChild) {
                    levelManager.getObjectManager().addDynamicObject(bossChild);
                }
            }
        }

        positionChildren();
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestDEZDeathEggRobot#childrenRegisteredWithObjectManager -pl .`
Expected: PASS

**Step 5: Run full DEZ test suite**

Run: `mvn test -Dtest=TestDEZDeathEggRobot,TestDEZMechaSonic,TestTodo9_DEZEventSpecs -pl .`
Expected: All pass

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2DeathEggRobotInstance.java \
        src/test/java/com/openggf/tests/TestDEZDeathEggRobot.java
git commit -m "fix: register Death Egg Robot children with ObjectManager for rendering

Children were added to childComponents (for update) but never to
ObjectManager.addDynamicObject() (for rendering). Body parts updated
their state but were invisible. Matches the pattern used by Silver
Sonic's children (MechaSonicDEZWindow, etc.)."
```

---

### Task 3: Full Regression Test

**Step 1: Run entire test suite**

Run: `mvn test -pl .`
Expected: All tests pass. No regressions in EHZ/CPZ/ARZ/CNZ/HTZ/MCZ/OOZ/MTZ bosses.

**Step 2: Verify no other code references the removed ZONE_UNUSED constants**

Search: `grep -r "ZONE_UNUSED" src/`
Expected: No matches (constants only existed in Sonic2LevelEventManager)
