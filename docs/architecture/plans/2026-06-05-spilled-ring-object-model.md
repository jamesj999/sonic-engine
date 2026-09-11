# Spilled-Ring Object Model (Obj37) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Model spilled (lost) rings as real ROM Obj37 objects that execute physics in the object loop at the ROM frame point and participate in the unified slot-ordered touch loop, so a lower-slot spilled ring suppresses a later hazard exactly as ROM does — fixing mtz2 f641 without regressing mcz1.

**Architecture:** Introduce a game-agnostic `LostRingObjectInstance` (Obj37) under `level.rings` that owns per-ring physics/collision and is driven through the normal dynamic-object exec + touch path. Keep the global spill-spin animation (`Ring_spill_anim_*`) in a shared owner. Add a type-keyed every-frame lost-ring touch branch (collect-if-not-invulnerable, then break) before the SPECIAL edge-trigger gate. Add a central `LostRingRewindCodec` for dynamic recreate-on-seek. The legacy `LostRingPool` per-ring physics/collection/rewind is removed only after the object path is green.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven. Headless physics via `HeadlessTestRunner`/`SingletonResetExtension`. Trace replay via `*TraceReplay` against ROM captures.

**Spec:** `docs/architecture/designs/2026-06-05-spilled-ring-object-model-design.md`

---

## Conventions used by every task

**Run a focused unit test (PowerShell, quote the selector):**
```
mvn "-Dtest=com.openggf.<pkg>.<Class>#<method>" test
```

**Run a trace replay (bash, flake-free single fork, parens-free ROM copy):**
```
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=true \
  '-Ds2.rom.path=C:\Users\farre\IdeaProjects\sonic-engine\s2.gen' \
  '-Dtest=TestS2Mtz2LevelSelectTraceReplay#replayMatchesTrace' test
```
Judge a trace by its per-class surefire `Tests run / First error: frame` line and `target/trace-reports/s2_<zone>_report.json` — NOT the MSE project-wide total.

**Commit trailers (every non-master commit):** fill `Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills` each with `updated` or `n/a: <reason>`. A `feat`/`fix` touching `src/main/` must set `Changelog: updated` and stage `CHANGELOG.md`. End engine commits with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`. Never `--no-verify`; never `git add -A` (shared repo).

---

## File Structure

| File | Responsibility | New/Modify |
|---|---|---|
| `src/main/java/com/openggf/level/rings/LostRingObjectInstance.java` | ROM Obj37 object: per-ring physics (`updateMovement`), `collision_flags=$47`, render, lost-ring marker. Game-agnostic. | **Create** |
| `src/main/java/com/openggf/level/rings/SpillAnimationState.java` | Global ROM spill-spin owner (`Ring_spill_anim_counter/accum/frame`), ticked once per frame; rewind-snapshottable. | **Create** |
| `src/main/java/com/openggf/level/rings/RingManager.java` | Slim `LostRingPool` to spawner + render registry + shared-spin owner; remove per-ring physics/collection/rewind once cutover is green. | **Modify** |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | Type-keyed lost-ring touch branch (every-frame collect+break) before edge-trigger gate; register `LostRingRewindCodec`. | **Modify** |
| `src/main/java/com/openggf/level/objects/LostRingRewindCodec.java` | `RewindDynamicObjectCodec` recreate factory for `LostRingObjectInstance`. | **Create** |
| `src/main/java/com/openggf/game/PhysicsFeatureSet.java` | Already exposes `ringFloorCheckMask()`; add any missing lost-ring feature accessors used by the object. | **Modify (if needed)** |
| `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java` | Per-ring physics frame-exactness; capacity/allocation. | **Create** |
| `src/test/java/com/openggf/level/objects/TestLostRingTouchOrdering.java` | Ordering invariant, invuln gate, every-frame collect, type-keying guard. | **Create** |
| `src/test/java/com/openggf/level/rings/TestLostRingRewindCodec.java` | Rings reappear on seek; shared-spin sync. | **Create** |

---

## Stage 1 — `LostRingObjectInstance` + shared spill-animation owner

Goal: a real Obj37 object whose physics runs in the object exec loop, with the global spin in a shared owner. The legacy pool still runs collection/rewind in this stage (parallel), so no behavior changes yet — we are building the object path under test before cutover.

### Task 1.1: SpillAnimationState (shared spin owner)

**Files:**
- Create: `src/main/java/com/openggf/level/rings/SpillAnimationState.java`
- Test: `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java`

- [ ] **Step 1: Write the failing test** (in a new test file; reset singletons not required — pure object)

```java
package com.openggf.level.rings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TestLostRingObjectInstance {

    @Test
    void spillAnimationDeceleratesLikeRom() {
        // ROM ChangeRingFrame: accum += counter each frame; frame = (accum >> 9) & 3;
        // counter decrements; counter starts at 0xFF.
        SpillAnimationState anim = new SpillAnimationState();
        anim.reset();                 // counter=0xFF, accum=0, frame=0
        assertEquals(0xFF, anim.counter());
        anim.tick();                  // accum = 0xFF; frame = (0xFF>>9)&3 = 0; counter=0xFE
        assertEquals(0, anim.frame());
        assertEquals(0xFE, anim.counter());
        // advance enough to roll bits 10:9
        for (int i = 0; i < 3; i++) anim.tick();
        // accum after 4 ticks = 0xFF+0xFE+0xFD+0xFC = 0x03FA; (0x03FA>>9)&3 = 1
        assertEquals(1, anim.frame());
    }
}
```

- [ ] **Step 2: Run it to verify it fails** — `mvn "-Dtest=com.openggf.level.rings.TestLostRingObjectInstance#spillAnimationDeceleratesLikeRom" test` → FAIL (class not found).

- [ ] **Step 3: Implement `SpillAnimationState`** — extract the ROM spin math from `RingManager.LostRingPool.updatePhysics` (RingManager.java:1208-1216):

```java
package com.openggf.level.rings;

/**
 * Global ROM spilled-ring spin animation (Ring_spill_anim_counter / _accum / _frame).
 * Shared across all live spilled rings — NOT per-ring. The counter doubles as the
 * decelerating-spin speed input. Ported from RingManager.LostRingPool.updatePhysics
 * (s2.asm ChangeRingFrame). One instance per gameplay session; tick once per frame.
 */
public final class SpillAnimationState {
    static final int INITIAL_COUNTER = 0xFF;
    private int counter;
    private int accum;
    private int frame;

    public void reset() { counter = INITIAL_COUNTER; accum = 0; frame = 0; }

    /** Advance the shared spin one frame (no-op once the counter reaches 0). */
    public void tick() {
        if (counter > 0) {
            accum = (accum + counter) & 0xFFFF;   // ROM: add counter to accumulator
            frame = (accum >> 9) & 3;             // ROM: rol.w #7 / andi.w #3 → bits 10:9
            counter--;
        }
    }

    public int counter() { return counter; }
    public int accum() { return accum; }
    public int frame() { return frame; }

    // Rewind: small explicit snapshot (global state, not per-ring).
    public int[] snapshot() { return new int[] { counter, accum, frame }; }
    public void restore(int[] s) { counter = s[0]; accum = s[1]; frame = s[2]; }
}
```

- [ ] **Step 4: Run it to verify it passes** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/rings/SpillAnimationState.java \
        src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java
git config core.hooksPath .githooks
git commit -m "feat(rings): extract shared spilled-ring spin owner (SpillAnimationState)

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```
(Stage `CHANGELOG.md` with a one-line entry too; the hook requires it for a `feat` touching `src/main`.)

### Task 1.2: LostRingObjectInstance physics (object-loop `updateMovement`)

**Files:**
- Create: `src/main/java/com/openggf/level/rings/LostRingObjectInstance.java`
- Test: `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java`

Mirror an existing minimal `AbstractObjectInstance` subclass for the boilerplate (constructor, `updateMovement`, `getCollisionSizeIndex`, render). Good reference: a simple dynamic object in `com.openggf.level.objects` (e.g. `AbstractPointsObjectInstance` subclasses) and `AbstractBadnikInstance`.

- [ ] **Step 1: Write the failing test** — port the per-ring physics assertions from `LostRingPool.updatePhysics` (RingManager.java:1240-1283). The object holds the same fields as `LostRing` (xSubpixel/ySubpixel/xVel/yVel/lifetime/phaseOffset).

```java
@Test
void ringBouncePhysicsMatchesLegacyPool() {
    // Fixed-point contract (identical to LostRing.reset, RingManager LostRing.java:24):
    //   xSubpixel = x << 8 (pixel coordinate stored in the high byte; low byte = sub-pixel).
    // forTest(x, y, ...) constructs with xSubpixel = x << 8, ySubpixel = y << 8.
    LostRingObjectInstance ring = LostRingObjectInstance.forTest(
            /*xPixel*/0x100, /*yPixel*/0x100, /*xVel*/0x0200, /*yVel*/-0x0400, /*phase*/0, /*lifetime*/0xFF);
    assertEquals(0x100 << 8, ring.getXSubpixelForTest());      // 0x10000 at start
    ring.stepPhysicsForTest(/*gravity*/0x18, /*floorCheck*/false);
    // ROM step (LostRingPool.updatePhysics, RingManager.java:1245-1247):
    //   xSubpixel += xVel;  ySubpixel += yVel;  yVel += gravity.
    assertEquals((0x100 << 8) + 0x0200, ring.getXSubpixelForTest()); // 0x10200
    assertEquals((0x100 << 8) + (-0x0400), ring.getYSubpixelForTest()); // 0x0FC00
    assertEquals(-0x0400 + 0x18, ring.getYVelForTest());
}
```

(Use the same fixed-point contract as `LostRing` (`LostRing.java:24` — `xSubpixel = x << 8`):
`addXSubpixel(xVel)`, `addYSubpixel(yVel)`, `addYVel(gravity)`. Assert on `xSubpixel`/`ySubpixel`
(fixed-point), not pixel values, so the sub-pixel carry is exercised exactly as the legacy math.)

- [ ] **Step 2: Run → FAIL** (class/methods absent).

- [ ] **Step 3: Implement `LostRingObjectInstance`.** Extend `AbstractObjectInstance` (or the closest minimal base). Carry the `LostRing` fields. `updateMovement()` runs ONE ring physics step using the math relocated verbatim from `LostRingPool.updatePhysics` (velocity integrate, gravity, per-game floor-check via `services()` feature set, lifetime decrement, off-bottom/lifetime delete via `services().objectLifetimeOps()`/`setDestroyed`). The displayed mapping frame = `sharedSpillAnimFrame + phaseOffset` (read from the shared owner; injected). Expose `static forTest(...)` and small `*ForTest()` accessors plus `stepPhysicsForTest(gravity, floorCheck)` that calls the same private step with a fixed gravity and skips world-collision when `floorCheck` is false, so physics is unit-testable without a loaded level.

Key methods:
- `@Override public int getCollisionSizeIndex()` → size index `7` (so `collision_flags` low bits = `$07`).
- `public int getCollisionFlags()` → `0x47` while not collected, else `0` (mirrors `Sonic1RingInstance.java:167` pattern but on the lost-ring object).
- `public boolean isLostRingCollectible()` → `true` (the type marker used by the touch branch in Stage 2).
- `@Override protected void updateMovement()` → one physics step (the relocated math).
- Rendering: `appendRenderCommands(...)` using the shared spin frame + phase, mirroring `RingRenderer`’s spilled-ring draw.

- [ ] **Step 4: Run → PASS.**

- [ ] **Step 5: Commit** (`feat(rings): LostRingObjectInstance Obj37 with object-loop physics`).

### Task 1.3: Spawn LostRingObjectInstance objects (parallel to legacy, behind a flag)

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java` (LostRingPool.spawnLostRings, ~1145)
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (add a `spawnLostRingObject(...)` helper using `addDynamicObject` so rings enter `execOrder`)
- Test: `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java`

- [ ] **Step 1: Write the failing test** — after `spawnLostRings(player, 4, frame)`, the ObjectManager exec set contains 4 `LostRingObjectInstance`s in ascending slot order, and the shared `SpillAnimationState` is reset (counter `0xFF`).

```java
@Test
void spawnRegistersRingObjectsInSlotOrder() {
    // Use a headless ObjectManager fixture (see TestObjectManagerChildSlotAllocation for setup).
    // ... spawn 4 rings ...
    List<LostRingObjectInstance> rings = objectManager.activeObjectsOfType(LostRingObjectInstance.class);
    assertEquals(4, rings.size());
    for (int i = 1; i < rings.size(); i++) {
        assertTrue(rings.get(i).getSlotIndex() > rings.get(i - 1).getSlotIndex());
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement — single allocation per ring (NO double-consume), into `dynamicObjects`.** The
  legacy `allocateSlotIndices` (RingManager.java:1321) already calls `objectManager.allocateSlotAfter(...)`
  for each ring. **Do NOT also allocate via `addDynamicObject`** (that would consume a *second* SST slot
  per ring and shift slot pressure/order before cutover). Add
  `ObjectManager.spawnLostRingObjectAtSlot(LostRingObjectInstance ring, int reservedSlot)` that:
  - sets services and `ring.setSlotIndex(reservedSlot)` (slot already reserved via `allocateSlotAfter`,
    so it does NOT re-allocate);
  - **adds the ring to `dynamicObjects`** (the `List<ObjectInstance>` at ObjectManager.java:414, the same
    collection used by `addDynamicObjectInternal` at :1805) — **NOT** to the `activeObjects` map. This is
    critical for Stage 4: rewind capture iterates `activeObjects` into placement `PerSlotEntry`
    (restored via `registry.create(spawn)`, ObjectManager.java:3143) and `dynamicObjects` into
    `DynamicObjectEntry` (restored via the `LostRingRewindCodec`/`recreateDynamicObject`, :3172). A
    non-placement lost ring placed in `activeObjects` would be restored through the placement registry
    instead of the codec — directly conflicting with Stage 4.1;
  - marks the active/touch caches dirty (call `rebuildActiveObjectCaches()`, ObjectManager.java:1607) so
    the ring joins the slot-ordered touch iteration;
  - wires `execOrder[execIndexForSlot(reservedSlot)] = ring` **only when same-frame execution is needed**
    (mirror the conditional `execOrder` write at :1823); otherwise the ring executes next frame like any
    freshly-spawned dynamic object.

  In `LostRingPool.spawnLostRings`, for each reserved slot from `allocateSlotIndices`, construct the
  `LostRingObjectInstance` and call `spawnLostRingObjectAtSlot(ring, reservedSlot)`. Reset the shared
  `SpillAnimationState`. During this parallel stage the legacy `LostRing[]` is the OWNER of collection
  and rewind (object path is exec-only); the object and its legacy `LostRing` twin share the **same**
  slot, so total slot consumption is unchanged from today. Add `ObjectManager.activeObjectsOfType(Class)`
  test accessor (it scans `dynamicObjects` for the ring type).

- [ ] **Step 4: Run → PASS.** Also run the full existing ring tests to ensure no break:
  `mvn "-Dtest=com.openggf.level.rings.*,com.openggf.level.objects.TestObjectManagerChildSlotAllocation" test` → PASS.

- [ ] **Step 5: Commit** (`feat(rings): spawn LostRingObjectInstance into the object exec loop (parallel to legacy)`).

---

## Stage 2 — Type-keyed every-frame lost-ring touch branch

Goal: the unified touch loop collects lost-ring objects (every frame, type-keyed) and breaks — making a lower-slot ring suppress a later hazard. Cut collection over to the object path; remove legacy `checkLostRingCollection`.

### Task 2.1: Ordering invariant + type-keyed branch

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (`TouchResponses.processCollisionLoop`, dispatch block ~5392-5422)
- Test: `src/test/java/com/openggf/level/objects/TestLostRingTouchOrdering.java`

- [ ] **Step 1: Write the failing tests** (use the `TestTouchResponseManager` headless fixture pattern):

```java
package com.openggf.level.objects;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
// + fixture imports mirroring TestTouchResponseManager

class TestLostRingTouchOrdering {

    @Test
    void lowerSlotRingSuppressesLaterHazard() {
        // Player overlaps BOTH a lost ring (lower slot) and a HURT hazard (higher slot).
        // Expect: ring collected, hazard NOT triggered this frame (loop broke at the ring).
        // ... build fixture: spawn ring at slot N, hazard at slot N+1, both overlapping ...
        runTouchResponsesForPlayer(player);
        assertTrue(ring.isCollected());
        assertFalse(player.wasHurtThisFrame());
    }

    @Test
    void higherSlotRingDoesNotSuppressEarlierHazard() {
        // Ring at slot N+1, hazard at slot N → hazard fires first (loop breaks at hazard).
        runTouchResponsesForPlayer(player);
        assertTrue(player.wasHurtThisFrame());
        assertFalse(ring.isCollected());
    }

    @Test
    void overlapDuringInvulnerabilityBreaksWithoutCollecting() {
        player.setInvulnerableFrames(120); // >= 90
        runTouchResponsesForPlayer(player); // ring overlaps, lower slot than hazard
        assertFalse(ring.isCollected());
        assertFalse(player.wasHurtThisFrame()); // loop still broke at the ring
    }

    @Test
    void collectsOnceInvulnerabilityDropsWhileStillOverlapping() {
        player.setInvulnerableFrames(91);
        runTouchResponsesForPlayer(player); // frame 1: still invuln (>=90) → no collect, no edge
        assertFalse(ring.isCollected());
        player.setInvulnerableFrames(89);   // dropped below 90, STILL overlapping (no new edge)
        runTouchResponsesForPlayer(player); // frame 2: must collect (every-frame eval, not edge)
        assertTrue(ring.isCollected());
    }

    @Test
    void placedRingObjectNotHandledByLostRingBranch() {
        // An S1 placed ring (Sonic1RingInstance, collision_flags 0x47) must go through
        // its own listener path, NOT the lost-ring branch.
        Sonic1RingInstance placed = ...; // overlapping, in the object loop
        runTouchResponsesForPlayer(player);
        assertTrue(placed.wasCollectedViaListenerPath());      // existing path used
        assertFalse(lostRingBranchHandled(placed));            // NOT the lost-ring branch
    }
}
```

- [ ] **Step 2: Run → FAIL** (lost-ring branch absent; ordering not yet honored for ring objects).

- [ ] **Step 3: Implement the branch.** In `processCollisionLoop`, in the dispatch block (after `if (!overlap) continue; buildingSet.add(instance);`, before the `shouldTrigger` SPECIAL edge-trigger logic at ObjectManager.java:5392), add:

```java
// Type-keyed lost-ring collectible: ROM Touch_ChkValue ring branch (s2.asm:85196-85219).
// Evaluated EVERY frame on overlap (not edge-triggered) so the ring collects the frame
// invulnerable_time drops below 90 while the player is still continuously overlapping.
// Keyed on the LostRingObjectInstance marker, NOT the 0x47 byte shape — so S1 placed
// rings (Sonic1RingInstance, also 0x47) keep their own listener path.
//
// NOTE: the loop param `player` is PlayableEntity (ObjectManager.java:5284), which does
// NOT expose ring/invuln APIs. addRings(int) and getInvulnerableFrames() live on
// AbstractPlayableSprite (AbstractPlayableSprite.java:1427 / :2117) — cast to reach them.
// Ring crediting matches the legacy LostRingPool.checkCollection gate: ONLY the main
// character collects/credits (sidekick Tails does not pick up rings); BOTH still break.
if (instance instanceof LostRingObjectInstance lostRing && lostRing.isLostRingCollectible()) {
    if (!isSidekick && player instanceof AbstractPlayableSprite aps) {
        int invuln = aps.getInvulnerableFrames();                       // :2117
        if (invuln < LOST_RING_INVULNERABLE_THRESHOLD /*90*/ && !lostRing.isCollected()) {
            lostRing.markCollected(frameCounterForTouch());
            aps.addRings(1);                                            // :1427
            audioManager.playSfx(GameSound.RING);  // same SFX as legacy checkCollection
        }
    }
    break; // ROM: rts — the ring was the first overlapping object; stop the loop (both paths).
}
```

`LOST_RING_INVULNERABLE_THRESHOLD = 90` mirrors the legacy
`LOST_RING_RECOLLECTION_INVULNERABLE_THRESHOLD` (RingManager.java). The branch sits in the shared
`processCollisionLoop`, which the main path (ObjectManager.java:5199) and sidekick path (:5260) both
call — `isSidekick` is in scope. A sidekick overlap takes the `break` (suppresses the later hazard) but
does not collect/credit, matching ROM + legacy `cannotCollectRings`.

- [ ] **Step 4: Run → PASS** (all five tests). Then run `TestTouchResponseManager` to confirm no SPECIAL regression:
  `mvn "-Dtest=com.openggf.level.objects.TestTouchResponseManager,com.openggf.level.objects.TestLostRingTouchOrdering" test` → PASS.

- [ ] **Step 5: Commit** (`feat(objects): type-keyed every-frame lost-ring touch branch (collect+break)`).

### Task 2.2: Cut collection over; remove legacy `checkLostRingCollection`

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java` (remove `checkCollection` + `checkLostRingCollection`)
- Modify: `src/main/java/com/openggf/level/LevelManager.java:865` (drop the `checkLostRingCollection` call)
- Test: existing ring tests + the Stage 2.1 tests

> **Compile-safety rule (applies to every removal task, 2.2 / 4.2 / 5.4):** before deleting any public
> API, `grep -rn "<symbol>" src --include=*.java` and migrate **every** caller — main AND test — in the
> SAME task/commit, or the repo will not compile. Known referencing tests for the ring subsystem:
> `com.openggf.tests.TestRingManager`, `com.openggf.level.rings.TestRingManagerRewindSnapshot`,
> `com.openggf.tests.TestS3kIczFreezerObject`, `com.openggf.tests.trace.s1.TestS1Mz1SlotLayoutRegression`,
> and the test stubs `com.openggf.level.objects.StubObjectServices` / `TestObjectServices`.

- [ ] **Step 1:** `grep -rn "checkLostRingCollection" src --include=*.java` → confirm the only caller is
  `LevelManager.java:865` (no tests). Delete that call and the `RingManager.checkLostRingCollection` /
  `LostRingPool.checkCollection` methods. Collection now happens only via the Stage-2.1 touch branch.
- [ ] **Step 2: Run** a BROAD set (not just `level.rings.*`): `mvn "-Dtest=com.openggf.level.rings.*,com.openggf.tests.TestRingManager,com.openggf.level.objects.TestLostRingTouchOrdering" test` → PASS (compiles + green).
- [ ] **Step 3: Commit** (`refactor(rings): collection now via unified touch loop; remove legacy checkCollection`).

---

## Stage 3 — Atomic slot-allocation contract + 0x20 cap

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java` (`LostRingPool.spawnLostRings`, `allocateSlotIndices` ~1321)
- Test: `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java`

- [ ] **Step 1: Write the failing test:**

```java
@Test
void spawnStopsOnAllocationFailureAndCapsAt32() {
    // Pre-fill the dynamic slot pool so only 3 slots remain free.
    // Request 10 rings → exactly 3 LostRingObjectInstances exist, in ascending slot order,
    // and no ring exists for a failed (-1) allocation.
    objectManager.reserveAllButNFreeSlots(3);
    ringManager.spawnLostRings(player, 10, frame);
    var rings = objectManager.activeObjectsOfType(LostRingObjectInstance.class);
    assertEquals(3, rings.size());
    for (int i = 1; i < rings.size(); i++)
        assertTrue(rings.get(i).getSlotIndex() > rings.get(i-1).getSlotIndex());
}

@Test
void neverSpawnsMoreThanRomCapOf32() {
    objectManager.reserveAllButNFreeSlots(64);
    ringManager.spawnLostRings(player, 50, frame);
    assertEquals(0x20, objectManager.activeObjectsOfType(LostRingObjectInstance.class).size());
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implement the atomic contract** in `spawnLostRings`:

```java
int toSpawn = Math.min(ringCount, MAX_LOST_RINGS /*0x20*/);
// Allocation predecessor: keep the EXISTING anchor used by the working legacy code —
// `previousSlot = 31` (RingManager.java:1328). Spilling happens from the player's hurt
// handler, OUTSIDE the object exec cursor, so there is no "current spawning object slot"
// to anchor on; the fixed predecessor reproduces today's slot placement. (Flagged for
// later ROM re-verification, but 31 is the current, trace-validated value — do NOT swap
// to an exec-cursor value, which would be wrong/unavailable on the touch/spawn path.)
int previousSlot = 31;
spillAnimation.reset();
int spawned = 0;
for (int i = 0; i < toSpawn; i++) {
    int slot = objectManager.allocateSlotAfter(previousSlot);
    if (slot < 0) {                 // ROM: no free slot → stop spilling (truncate)
        // log() how many of (toSpawn - spawned) were truncated
        break;
    }
    // compute this ring's pos/vel/phase, construct the instance, register it ON this real slot
    LostRingObjectInstance ring = buildRing(slot, ...);
    objectManager.spawnLostRingObjectAtSlot(ring, slot);
    previousSlot = slot;
    spawned++;
}
```

Replace the old two-phase `allocateSlotIndices` (which pre-allocated an array of slots, some possibly
`-1`, before constructing rings) with this single in-loop allocate-then-construct path, so a failed
allocation never leaves a reserved-but-unused slot and slot order never diverges. Add
`ObjectManager.reserveAllButNFreeSlots(n)` test helper. **Note for Task 1.3 reconciliation:** once this
Stage-3 contract lands, Task 1.3's "reuse legacy reserved slot" wiring collapses into this single path —
the object IS the ring (legacy `LostRing[]` twin is already retired by Stage 4.2), so
`spawnLostRingObjectAtSlot` is the sole allocator.

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** (`fix(rings): atomic stop-on-(-1) lost-ring slot allocation + 0x20 cap`).

---

## Stage 4 — `LostRingRewindCodec` + retire per-ring snapshot

Goal: rings survive rewind-seek via a dynamic recreate codec; retire the bespoke per-ring snapshot; keep the small shared-spin snapshot.

### Task 4.1: LostRingRewindCodec (recreate-on-seek)

**Files:**
- Create: `src/main/java/com/openggf/level/objects/LostRingRewindCodec.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` (add to `BUILT_IN_REWIND_DYNAMIC_OBJECT_CODECS`, line ~70)
- Test: `src/test/java/com/openggf/level/rings/TestLostRingRewindCodec.java`

- [ ] **Step 1: Write the failing test** — spill rings, snapshot, advance/clear, restore, assert rings reappear with identical positions and spin frame:

```java
@Test
void ringsReappearOnSeekAcrossSpill() {
    ringManager.spawnLostRings(player, 6, frame);
    var before = positionsOf(objectManager.activeObjectsOfType(LostRingObjectInstance.class));
    var snap = rewind.captureObjectManagerSnapshot();
    objectManager.clearDynamicObjects();           // simulate seek wiping live state
    assertEquals(0, objectManager.activeObjectsOfType(LostRingObjectInstance.class).size());
    rewind.restoreObjectManagerSnapshot(snap);     // must recreate via the codec
    var after = positionsOf(objectManager.activeObjectsOfType(LostRingObjectInstance.class));
    assertEquals(before, after);                   // NOT empty → proves the codec recreates
    assertEquals(snap.spillAnimFrame(), spillAnimation.frame()); // shared spin restored
}
```

- [ ] **Step 2: Run → FAIL** (without a codec the rings are diagnostic-only and `after` is empty).

- [ ] **Step 3: Implement the codec** mirroring `pointsCodec` (ObjectManager.java:308-333): `supports(inst) == inst.getClass()==LostRingObjectInstance.class`, `className()`, and `recreate(context, entry)` that rebuilds the ring from `entry.spawn()` + restored fields via `context.objectServices()`. Register it in `BUILT_IN_REWIND_DYNAMIC_OBJECT_CODECS`. Ensure the ring's capturable fields (xSubpixel/ySubpixel/xVel/yVel/lifetime/collected/sparkleStartFrame/phaseOffset) round-trip via the generic field capture + the entry (add codecs/`@RewindTransient` as needed, consistent with other dynamic objects). Capture the shared `SpillAnimationState` in the object-manager (or ring-manager) snapshot via its `snapshot()`/`restore()`.

- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** (`feat(rewind): LostRingRewindCodec recreates spilled rings on seek`).

### Task 4.2: Retire legacy per-ring physics + snapshot; keep shared-spin snapshot

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java` (remove `LostRingPool.updatePhysics` per-ring loop + per-ring `RingSnapshot.LostRingEntry` capture/restore at ~740-820; keep shared spin tick + render registry)
- Modify: `src/main/java/com/openggf/level/LevelManager.java:1020` (replace `ringManager.updateLostRingPhysics(frame+1)` with a shared-spin `tick()` only — physics now runs in the object loop)
- Test: `src/test/java/com/openggf/level/rings/TestRingManagerRewindSnapshot.java` (update expectations)

- [ ] **Step 1:** `grep -rn "updateLostRingPhysics\|RingSnapshot.LostRingEntry\|LostRingEntry" src --include=*.java`. Remove the per-ring physics loop and the per-ring `RingSnapshot.LostRingEntry` capture/restore; `RingManager.update`/`LevelManager.update:1020` now only `spillAnimation.tick()` once per frame. **Migrate `TestRingManagerRewindSnapshot` in this same task** — it constructs `RingSnapshot.LostRingEntry[]` and asserts per-ring counters at lines 183/212/221/225-257; rewrite those to assert the shared-spin snapshot only (per-ring coverage moves to `TestLostRingRewindCodec` from Task 4.1). If the `RingSnapshot.LostRingEntry` record is now unused, delete it (grep first).
- [ ] **Step 2: Run** `mvn "-Dtest=com.openggf.level.rings.*,com.openggf.game.rewind.*,com.openggf.tests.TestRingManager" test` → PASS (compiles + green).
- [ ] **Step 3: Commit** (`refactor(rings): retire legacy per-ring physics/snapshot; physics runs in object loop`).

---

## Stage 5 — Cross-game gating + occupancy oracle + trace verification

### Task 5.1: Cross-game floor cadence + S3K behaviors via PhysicsFeatureSet

**Files:**
- Modify: `src/main/java/com/openggf/level/rings/LostRingObjectInstance.java` (read `services()` feature set for floor mask + S3K reverse-gravity/lightning-shield)
- Modify: `src/main/java/com/openggf/game/PhysicsFeatureSet.java` (confirm `ringFloorCheckMask()` + any S3K ring flags exist; add only if missing)
- Test: `src/test/java/com/openggf/level/rings/TestLostRingObjectInstance.java`

- [ ] **Step 1: Write the failing tests** — S1 floor-check cadence mask `#3` vs S2/S3K `#7`; S3K reverse-gravity probes the ceiling. Assert the object reads the feature set (port the assertions from the legacy `updatePhysics` per-game branches at RingManager.java:1219-1278).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** by relocating the per-game branches verbatim into `LostRingObjectInstance` using `services()`'s feature set (no `gameId` branches). Reverse-gravity uses the S3K flag; lightning-shield attraction preserved as Obj37 behavior gated on the existing S3K shield flag.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** (`feat(rings): per-game lost-ring cadence + S3K reverse-gravity/shield via PhysicsFeatureSet`).

### Task 5.2: Occupancy oracle — spilled rings occupy slots

**Files:**
- Test: `src/test/java/com/openggf/.../TestS2ObjectOccupancyOracle.java` (or its harness)

- [ ] **Step 1:** Confirm `TestS2ObjectOccupancyOracle` (comparison-only) now sees `Obj37` rings occupying slots consistent with ROM during a spill. If the oracle has an allowlist of slot-occupant ids, add `$37`. Run it: `mvn "-Dtest=...TestS2ObjectOccupancyOracle" test` → PASS (4/4).
- [ ] **Step 2: Commit** if the oracle/allowlist needed an update (`test(objects): oracle recognises Obj37 spilled rings in slots`), else skip.

### Task 5.3: Trace verification — mtz2 advances, mcz1 must not regress

**Files:** none (verification + frontier log).

- [ ] **Step 1: Run mtz2** (bash, single fork):
```
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=true \
  '-Ds2.rom.path=C:\Users\farre\IdeaProjects\sonic-engine\s2.gen' \
  '-Dtest=TestS2Mtz2LevelSelectTraceReplay#replayMatchesTrace' test
```
Expected: first error frame advances past **f641** (the Tails-hurt-timing divergence is gone).

- [ ] **Step 2: Run the regression-critical pair + greens:**
```
mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=true \
  '-Ds2.rom.path=C:\Users\farre\IdeaProjects\sonic-engine\s2.gen' \
  '-Dtest=TestS2MczLevelSelectTraceReplay,TestS2Ehz1TraceReplay,TestS2SczLevelSelectTraceReplay,TestS2WfzLevelSelectTraceReplay' test
```
Expected: **mcz1 still first-diverges at f2757 (NOT regressed toward f1665)**; EHZ1/SCZ/WFZ green.

- [ ] **Step 3: Full S1/S2/S3K sweep** (the authoritative pass), parse `target/surefire-reports/*TraceReplay*.txt` for `First error: frame`. Expected: no green regresses (S1 ×10, S2 ehz1/scz/wfz); **no S2/S3K frontier moves backward** vs the pre-change snapshot in `docs/status/trace-frontier-log.md`.

- [ ] **Step 4: Update `docs/status/trace-frontier-log.md`** with the command, commit/worktree context, mtz2 before→after, the mcz1 hold, and the full-sweep result. **Commit** (`docs(trace): spilled-ring object model — mtz2 advance, mcz1 held, no regression`).

### Task 5.4: Remove dead legacy code

**Files:**
- Modify/Delete: `src/main/java/com/openggf/level/rings/LostRing.java` (delete if fully unused), legacy `LostRing[] ringPool`, `RingSnapshot.LostRingEntry` (if unused).

- [ ] **Step 1:** `grep -rn "LostRing\b\|ringPool\b" src --include=*.java` (BOTH main and test). Migrate
  every remaining reference: `com.openggf.tests.TestRingManager`,
  `com.openggf.tests.TestS3kIczFreezerObject`, `com.openggf.tests.trace.s1.TestS1Mz1SlotLayoutRegression`,
  and the stubs `StubObjectServices`/`TestObjectServices` — repoint them at `LostRingObjectInstance`/the
  slim spawner (most call `spawnLostRings`, which STAYS; only direct `LostRing`/`ringPool` field uses need
  migration). Delete `LostRing.java` and the legacy `LostRing[] ringPool` only once the grep is clean.
  Keep `LostRingObjectInstance`, `SpillAnimationState`, and the slim spawner.
- [ ] **Step 2: Run** the broad set: `mvn "-Dtest=com.openggf.level.rings.*,com.openggf.level.objects.TestLostRingTouchOrdering,com.openggf.tests.TestRingManager,com.openggf.tests.TestS3kIczFreezerObject,com.openggf.tests.trace.s1.TestS1Mz1SlotLayoutRegression,com.openggf.game.rewind.*" test` → PASS (compiles + green). Then re-run the Stage 5.3 trace pair (mtz2 + mcz1).
- [ ] **Step 3: Commit** (`refactor(rings): remove dead legacy LostRing pool after object-model cutover`).

---

## Self-Review (run before execution)

- **Spec coverage:** Stage 1 ↔ object + shared spin; Stage 2 ↔ type-keyed every-frame branch + S1 guard; Stage 3 ↔ atomic allocation/cap; Stage 4 ↔ codec + retire per-ring snapshot, keep shared-spin; Stage 5 ↔ cross-game + oracle + trace bar. All spec sections mapped.
- **Type consistency:** `LostRingObjectInstance` (`isLostRingCollectible()`, `isCollected()`, `markCollected(int)`, `getSlotIndex()`, `getCollisionFlags()→0x47`), `SpillAnimationState` (`reset/tick/frame/counter/accum/snapshot/restore`), `ObjectManager` (`spawnLostRingObjectAtSlot`, `activeObjectsOfType`, `reserveAllButNFreeSlots`, `LOST_RING_INVULNERABLE_THRESHOLD=90`), `LostRingRewindCodec` (mirrors `pointsCodec`). Names are consistent across tasks.
- **Cutover safety:** legacy collection removed only in Stage 2.2 (after the object branch is green); legacy physics/snapshot removed only in Stage 4.2 (after the codec is green); dead code deleted only in Stage 5.4 (after grep-confirmed unused).
- **Verification bar gates the end:** Stage 5.3 enforces mtz2-advances / mcz1-no-regress / no-frontier-regression before the work is considered done.

**Plan-review fixes incorporated (2026-06-05):**
1. **No double-consumed slots** in the parallel stage — Task 1.3 spawns the object into the legacy-reserved slot (`spawnLostRingObjectAtSlot`, no second `addDynamicObject` allocation); total slot use unchanged until cutover.
2. **Allocation anchor** is the existing literal `previousSlot = 31` (RingManager.java:1328), not an exec-cursor value (spilling runs outside the object exec cursor); flagged for ROM re-verification but kept at the trace-validated value.
3. **Player API** — the loop param is `PlayableEntity` (ObjectManager.java:5284); the branch casts to `AbstractPlayableSprite` for `getInvulnerableFrames()` (:2117) / `addRings(int)` (:1427); only the main character collects/credits, both paths break (matches legacy `cannotCollectRings`).
4. **Compile-safety on removals** — 2.2 / 4.2 / 5.4 grep all callers and migrate referencing tests (`TestRingManagerRewindSnapshot`, `TestRingManager`, `TestS3kIczFreezerObject`, `TestS1Mz1SlotLayoutRegression`, stubs) in the same task, with broadened test commands.
5. **Fixed-point contract** — Task 1.2 asserts on `xSubpixel`/`ySubpixel` with `x << 8` start (`LostRing.java:24`), e.g. `(0x100 << 8) + xVel`, not pixel-space `x + xVel`.
6. **Dynamic, not placement** — `spawnLostRingObjectAtSlot` adds the ring to `dynamicObjects` (ObjectManager.java:414/:1805), NOT the `activeObjects` map. Rewind captures `activeObjects`→placement `PerSlotEntry` (`registry.create(spawn)`, :3143) and `dynamicObjects`→`DynamicObjectEntry` (the codec, :3172); placing a lost ring in `activeObjects` would route it through the placement registry instead of the Stage-4.1 `LostRingRewindCodec`. `execOrder` is wired only when same-frame exec is needed.
