# ROM Object-Windowing Port — Stage 0 + Stage 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land ROM-frame-exact S2 object load/unload/slot-recycle timing on top of a single `ObjectManager`-owned slot-allocator authority, so MTZ1/MTZ3 (and the wider sidekick cluster) can be re-derived, without leaving non-ROM heuristics running.

**Architecture:** Stage 0 extracts the scattered slot **occupancy/allocation** mechanics (`allocateSlot`/`allocateSlotAfter`/`releaseSlot`/`reserveDynamicSlot`/`usedSlots`) into one cohesive `SlotAllocator` authority with a **per-game empty predicate** (S1/S2 id-byte, S3K routine-pointer), and routes every allocation path (windowing, child, dynamic, boss) through it. **`execOrder` and `objectIdInSlot` stay in `ObjectManager`** — `ObjectManager` remains the slot→occupant **identity authority** (the recycled-slot id the MTZ3 invariant reads); the allocator owns only occupancy/allocation. Stage 1 replaces the S2 manager-level distance/dormancy unload with ROM object-side `MarkObjGone` (`(x&$FF80) − ((cam−$80)&$FF80) > $280`, first-delete bucket $300), corrects S2 frame ordering to exec-then-load, aligns the S2 load cursor to the final `[camRounded−$80, camRounded+$280]` window, and adds a comparison-only slot→id/type occupancy oracle. S1 and S3K are later stages (same loop).

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Maven (Maven Silent Extension; `-Dsurefire.forkCount=1 -DreuseForks=true` for trace sweeps), `HeadlessTestRunner`/`HeadlessTestFixture`, trace replay harness (`AbstractTraceReplayTest`), S2 disassembly under `docs/s2disasm/`.

**Spec:** `docs/architecture/designs/2026-06-04-rom-object-windowing-port-design.md` (develop `275b32f67`).

**Worktree:** create one isolated worktree off `develop` for the whole plan via `superpowers:using-git-worktrees` before Task 0.1 (branch `feature/ai-rom-object-windowing-s2`). Do NOT edit the main checkout. `git add` only the files each task names; never `-A`.

**Per-stage loop (applies after Stage 0 and after Stage 1):** build → run the occupancy oracle on the MTZ + a green sample → full `*TraceReplay` sweep (`-Dsurefire.forkCount=1`) → write the before→after cascade map into `docs/status/trace-frontier-log.md` → (Stage 1 only) re-derive regressed frontiers → commit on the branch. Stage 0 must be a **pure refactor**: zero trace-frontier movement (it only consolidates allocation; behavior identical).

---

## File Structure

**Create:**
- `src/main/java/com/openggf/level/objects/SlotAllocator.java` — the single SST **occupancy/allocation** authority: `usedSlots` BitSet + `allocate()`/`allocateAfter(parent)`/`reserve(slot)`/`release(slot)`/`isEmpty(slot)`, parameterized by `ObjectSlotLayout` + a `SlotEmptyPredicate`. Does **not** hold `execOrder` or report occupant ids — `ObjectManager` keeps `execOrder` + `objectIdInSlot` as the identity authority.
- `src/main/java/com/openggf/level/objects/SlotEmptyPredicate.java` — per-game enum/strategy documenting the ROM identity field read by `AllocateObject` (S1/S2 = `ID_BYTE`, S3K = `ROUTINE_POINTER`); used for parity reasoning + S3K-later wiring. (Engine occupancy stays BitSet-backed; this records which ROM field the predicate models so S3K isn't wired with an id-byte assumption.)
- `src/main/java/com/openggf/game/sonic2/objects/S2ObjectWindowing.java` — S2 ROM loader cursor + `MarkObjGone` object-side unload helper, delegating allocation/free to `SlotAllocator`. (Placement-timing port only.)
- `src/test/java/com/openggf/tests/objects/TestSlotAllocator.java` — Stage 0 unit tests.
- `src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java` — Stage 1 unit tests (cursor boundaries, MarkObjGone, ChkLoadObj skip-vs-full, recycle).
- `src/test/java/com/openggf/tests/trace/ObjectOccupancyOracle.java` — comparison-only per-frame slot→id/type diff helper (test scope; reads trace `object_appeared`/`object_removed`/`object_near` events, never hydrates engine state).
- `src/test/java/com/openggf/tests/trace/TestS2ObjectOccupancyOracle.java` — runs the oracle on `TestS2MtzLevelSelectTraceReplay` + one green S2 trace.

**Modify:**
- `src/main/java/com/openggf/level/objects/ObjectManager.java` — own a `SlotAllocator` field; replace the private `allocateSlot`/`allocateSlotAfter`/`releaseSlot`/`reserveDynamicSlot` bodies + the `usedSlots` BitSet field with delegation to it (call sites unchanged in signature). **Keep** `execOrder` and `objectIdInSlot` here (identity authority). Stage 1: swap the S2 unload path off `isOutOfRangeS1` onto `S2ObjectWindowing.markObjGone`, fix the S2 exec/load ordering across **both** load sites (`update()` `:716-746` AND the post-block `:775-782`), and wire the S2 cursor scan to `S2ObjectWindowing` final boundaries.
- `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java` — Stage 1: S2 window math sourced from `S2ObjectWindowing` (final `[−$80,+$280]` boundaries); remove the S2 dormancy path once `MarkObjGone` drives unload.
- `docs/status/trace-frontier-log.md` — cascade-map entries per stage.
- `CHANGELOG.md` — Stage 1 engine change.

---

## STAGE 0 — Extract the single SlotAllocator authority (pure refactor, zero behavior change)

### Task 0.1: Characterization test — current allocation behavior is preserved

**Files:**
- Test: `src/test/java/com/openggf/tests/objects/TestSlotAllocator.java`

- [ ] **Step 1: Write characterization tests against the NEW `SlotAllocator` API (will not compile yet)**

```java
package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.*;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.SlotAllocator;
import com.openggf.level.objects.SlotEmptyPredicate;
import org.junit.jupiter.api.Test;

class TestSlotAllocator {

    private SlotAllocator s2() {
        return new SlotAllocator(ObjectSlotLayout.SONIC_2, SlotEmptyPredicate.ID_BYTE);
    }

    @Test
    void allocateReturnsFirstDynamicSlotThenAscending() {
        SlotAllocator a = s2();
        assertEquals(16, a.allocate());   // SONIC_2.firstDynamicSlot
        assertEquals(17, a.allocate());
        assertEquals(18, a.allocate());
    }

    @Test
    void releaseMakesSlotFirstChoiceAgain_recycle() {
        SlotAllocator a = s2();
        int s16 = a.allocate(); // 16
        int s17 = a.allocate(); // 17
        a.release(s16);
        assertTrue(a.isEmpty(s16));
        assertEquals(16, a.allocate()); // recycled: linear-first-empty returns 16, not 18
        assertEquals(18, a.allocate());
    }

    @Test
    void allocateAfterFindsNextFreeAfterParent() {
        SlotAllocator a = s2();
        int parent = a.allocate(); // 16
        a.allocate();              // 17
        assertEquals(18, a.allocateAfter(parent)); // first free strictly after parent slot
    }

    @Test
    void allocateReturnsMinusOneWhenPoolFull() {
        SlotAllocator a = s2();
        for (int i = 0; i < ObjectSlotLayout.SONIC_2.dynamicSlotCount(); i++) {
            assertTrue(a.allocate() >= 0);
        }
        assertEquals(-1, a.allocate());
    }

    @Test
    void reserveSpecificSlotSucceedsWhenFreeAndFailsWhenTaken() {
        SlotAllocator a = s2();
        assertTrue(a.reserve(20));
        assertFalse(a.isEmpty(20));
        assertFalse(a.reserve(20));      // already taken
        assertFalse(a.reserve(5));        // outside dynamic range (below firstDynamicSlot)
    }

    @Test
    void rewindSnapshotRestoreRoundTrip_preservesOccupancyAndCount() {
        SlotAllocator a = s2();
        a.allocate(); a.allocate();      // 16, 17
        a.reserve(40);
        long[] bits = a.toLongArray();
        int count = a.activeCount();     // 3

        SlotAllocator b = new SlotAllocator(ObjectSlotLayout.SONIC_2, SlotEmptyPredicate.ID_BYTE);
        b.restoreFromLongArray(bits);
        assertEquals(count, b.activeCount());
        assertFalse(b.isEmpty(16));
        assertFalse(b.isEmpty(17));
        assertFalse(b.isEmpty(40));
        assertTrue(b.isEmpty(18));
    }

    @Test
    void reserveOrMarkUsedForcesOccupiedEvenIfAlreadySet() {
        SlotAllocator a = s2();
        a.reserveOrMarkUsed(50);
        assertFalse(a.isEmpty(50));
        a.reserveOrMarkUsed(50);          // idempotent force-set, no exception
        assertEquals(1, a.activeCount());
    }
}
```

- [ ] **Step 2: Run to verify it fails to compile (observe locally — do NOT commit)**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestSlotAllocator" test`
Expected: COMPILE FAILURE — `SlotAllocator` / `SlotEmptyPredicate` do not exist.

- [ ] **Step 3: Hold the test uncommitted.** Per the repo's red-to-green commit discipline (below), do **not** commit a non-compiling test — it would leave the branch unbuildable between commits and trip hooks/CI. Tasks 0.2 + 0.3 add the implementation; **Task 0.3 commits this test together with `SlotAllocator` once green.**

> **Commit discipline (whole plan):** write the failing test, observe the failure **locally**, implement, then commit the **compiling red-to-green** result (test + implementation together) in one commit. Never commit a non-compiling or red state.

### Task 0.2: Create `SlotEmptyPredicate`

**Files:**
- Create: `src/main/java/com/openggf/level/objects/SlotEmptyPredicate.java`

- [ ] **Step 1: Write the enum**

```java
package com.openggf.level.objects;

/**
 * Which ROM SST identity field a game's {@code AllocateObject} tests to decide a
 * slot is empty/recyclable. Engine occupancy is BitSet-backed in {@link SlotAllocator};
 * this records the ROM field so the allocator is not wired with an id-byte assumption
 * that breaks S3K.
 *
 * <ul>
 *   <li>{@link #ID_BYTE} — S1/S2: {@code tst.b id(a1)} (id byte == 0 ⇒ empty).
 *       DeleteObject zeroes the slot incl. the id byte (s2.asm:30324).</li>
 *   <li>{@link #ROUTINE_POINTER} — S3K: {@code tst.l (a1)} (first longword /
 *       object routine-pointer == 0 ⇒ empty), s3k AllocateObject; Delete_Current_Sprite
 *       zeroes the slot incl. the routine pointer.</li>
 * </ul>
 */
public enum SlotEmptyPredicate {
    ID_BYTE,
    ROUTINE_POINTER
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/openggf/level/objects/SlotEmptyPredicate.java
git commit -m "feat: SlotEmptyPredicate (per-game ROM empty-field marker)"
```

### Task 0.3: Create `SlotAllocator` (move the BitSet/exec mechanics out of ObjectManager)

**Files:**
- Create: `src/main/java/com/openggf/level/objects/SlotAllocator.java`
- Reference (read, do not yet change): `ObjectManager.java` allocation methods at `:1757-1833` and the `usedSlots`/`execOrder`/`peakSlotCount` fields, `slotIndexForExec`/`execIndexForSlot`/`isManagedDynamicSlot` helpers (grep for them).

- [ ] **Step 1: Write `SlotAllocator`** — lift the existing BitSet logic verbatim (same semantics as `allocateSlot`/`allocateSlotAfter`/`releaseSlot`/`reserveDynamicSlot`), parameterized by layout:

```java
package com.openggf.level.objects;

import java.util.BitSet;

/**
 * The single authority for dynamic SST slot occupancy + allocation for one game's
 * {@link ObjectSlotLayout}. Mirrors ROM FindFreeObj / FindNextFreeObj (linear
 * first-empty scan) and DeleteObject/Delete_Current_Sprite (identity cleared ⇒
 * slot recyclable). Every allocation path — windowing load, child spawn, dynamic
 * object, boss sub-part — MUST go through this; there is no second allocator.
 */
public final class SlotAllocator {
    private final ObjectSlotLayout layout;
    private final SlotEmptyPredicate emptyPredicate;
    private final BitSet used;
    private int peak;

    public SlotAllocator(ObjectSlotLayout layout, SlotEmptyPredicate emptyPredicate) {
        this.layout = layout;
        this.emptyPredicate = emptyPredicate;
        this.used = new BitSet(layout.dynamicSlotCount());
    }

    public SlotEmptyPredicate emptyPredicate() { return emptyPredicate; }
    public int peakSlotCount() { return peak; }

    /** ROM FindFreeObj: first empty slot from the start of the dynamic range. -1 if pool full. */
    public int allocate() {
        return allocateFrom(0);
    }

    /** ROM FindNextFreeObj: first empty slot strictly after {@code parentSlot}. -1 if none. */
    public int allocateAfter(int parentSlot) {
        int startExec = Math.max(0, layout.toExecIndex(parentSlot) + 1);
        return allocateFrom(startExec);
    }

    private int allocateFrom(int startExec) {
        int bit = used.nextClearBit(startExec);
        if (bit >= layout.dynamicSlotCount()) {
            return -1;
        }
        used.set(bit);
        peak = Math.max(peak, used.cardinality());
        return layout.toSlotIndex(bit);
    }

    /** Re-reserve a specific dynamic slot (rewind restore of subsystem-owned occupants). */
    public boolean reserve(int slotIndex) {
        if (!layout.isDynamicSlot(slotIndex)) {
            return false;
        }
        int exec = layout.toExecIndex(slotIndex);
        if (used.get(exec)) {
            return false;
        }
        used.set(exec);
        peak = Math.max(peak, used.cardinality());
        return true;
    }

    public void release(int slotIndex) {
        if (layout.isDynamicSlot(slotIndex)) {
            used.clear(layout.toExecIndex(slotIndex));
        }
    }

    public boolean isEmpty(int slotIndex) {
        return !layout.isDynamicSlot(slotIndex) || !used.get(layout.toExecIndex(slotIndex));
    }

    public void clear() {
        used.clear();
        peak = 0;
    }

    // --- Occupancy introspection + rewind/restore (parity with the prior raw `usedSlots` usage) ---

    /** Number of occupied dynamic slots (was {@code usedSlots.cardinality()}). */
    public int activeCount() { return used.cardinality(); }

    /** Snapshot occupancy for rewind (was {@code usedSlots.toLongArray()}). */
    public long[] toLongArray() { return used.toLongArray(); }

    /** Restore occupancy from a rewind snapshot (replaces the BitSet contents wholesale). */
    public void restoreFromLongArray(long[] bits) {
        used.clear();
        used.or(java.util.BitSet.valueOf(bits));
        peak = Math.max(peak, used.cardinality());
    }

    /**
     * Force-mark a dynamic slot occupied regardless of current state — for rewind
     * restore and pre-assigned/reserved slots where {@link #reserve(int)}'s
     * "fail if already taken" semantics are not wanted. No-op for non-dynamic slots.
     */
    public void reserveOrMarkUsed(int slotIndex) {
        if (layout.isDynamicSlot(slotIndex)) {
            used.set(layout.toExecIndex(slotIndex));
            peak = Math.max(peak, used.cardinality());
        }
    }
}
```

> **Note for Task 0.4:** these four methods are the parity surface for `ObjectManager`'s existing raw `usedSlots` usage. Grep `usedSlots` in `ObjectManager` and the rewind snapshot/restore code: `usedSlots.cardinality()`→`activeCount()`, `usedSlots.toLongArray()`→`toLongArray()`, restore-from-bits→`restoreFromLongArray(...)`, any direct `usedSlots.set(...)` for pre-assigned/rewound slots→`reserveOrMarkUsed(...)`. If a usage exists that none of these cover, add the minimal method here rather than reaching back into a raw BitSet.

- [ ] **Step 2: Run the Task 0.1 tests**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestSlotAllocator" test`
Expected: PASS (all 5).

- [ ] **Step 3: Commit (test + implementation together, green)**

```bash
git add src/main/java/com/openggf/level/objects/SlotAllocator.java src/test/java/com/openggf/tests/objects/TestSlotAllocator.java
git commit -m "feat: SlotAllocator — single SST occupancy/allocation authority + tests (stage 0)"
```

### Task 0.4: Route `ObjectManager` through `SlotAllocator` (delegation, identical behavior)

**Files:**
- Modify: `ObjectManager.java` — fields (the `usedSlots` BitSet + `peakSlotCount`, near `:451`/`:496-497`), and the bodies of `allocateSlot` `:1757`, `allocateSlotAfter` `:1805`, `releaseSlot` `:1820`, `reserveDynamicSlot` `:1782`, `allocateDynamicSlot` `:1774`.

- [ ] **Step 1: Add the field, construct it where `slotLayout` is known**

Add field near the existing `slotLayout` field:
```java
private final SlotAllocator slotAllocator = new SlotAllocator(slotLayout,
        slotLayout.twoAxisCursorPlacement() ? SlotEmptyPredicate.ROUTINE_POINTER : SlotEmptyPredicate.ID_BYTE);
```
(If `slotLayout` is set in the constructor rather than as a field initializer, construct `slotAllocator` immediately after `slotLayout` is assigned.)

- [ ] **Step 2: Delegate each method body** (keep signatures + call sites unchanged):

```java
private int allocateSlot() { return slotAllocator.allocate(); }
public int allocateDynamicSlot() { return slotAllocator.allocate(); }
public boolean reserveDynamicSlot(int slotIndex) { return slotAllocator.reserve(slotIndex); }
public int allocateSlotAfter(int parentSlot) { return slotAllocator.allocateAfter(parentSlot); }
private void releaseSlot(int slotIndex) { slotAllocator.release(slotIndex); }
```
Delete the now-dead `usedSlots` BitSet field and `peakSlotCount` int; replace **every** `usedSlots.*` / `peakSlotCount` usage (grep both, including the rewind snapshot/restore code): `peakSlotCount`→`slotAllocator.peakSlotCount()`; `usedSlots.clear()`→`slotAllocator.clear()`; `usedSlots.cardinality()`→`slotAllocator.activeCount()`; `usedSlots.toLongArray()`→`slotAllocator.toLongArray()`; restore-from-bits→`slotAllocator.restoreFromLongArray(...)`; direct `usedSlots.set(...)` for pre-assigned/rewound slots→`slotAllocator.reserveOrMarkUsed(...)`. The build must compile with **zero** remaining references to a raw `usedSlots` BitSet (a compile error here means a usage the allocator API doesn't cover — add the minimal method to `SlotAllocator`, don't reintroduce the field). **Keep `execOrder` AND `objectIdInSlot` in `ObjectManager`, unchanged** — `ObjectManager` stays the slot→occupant identity authority (the recycled-slot id the MTZ3 invariant reads); the allocator owns occupancy only. Do **not** delegate or move `objectIdInSlot`.

- [ ] **Step 3: Build + run the object/slot regression guards**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestSlotAllocator,TestObjectServicesMigrationGuard,TestNoServicesInObjectConstructors" test`
Expected: PASS.

- [ ] **Step 4: Stage-0 pure-refactor verification — full sweep must show ZERO frontier movement**

Run: `mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 -DreuseForks=true "-Ds1.rom.path=<abs>" "-Ds2.rom.path=<abs>" "-Ds3k.rom.path=<abs>" "-Dtest=*TraceReplay" test`
Expected: every trace class's first-error frame **identical to develop baseline** (Stage 0 changes nothing observable). If any frame moves, the refactor changed behavior — STOP and reconcile before proceeding.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java
git commit -m "refactor: route ObjectManager allocation through SlotAllocator (stage 0, no behavior change)"
```

### Task 0.5: Assert single-authority — child/dynamic/boss spawning uses the same allocator

**Files:**
- Test: `src/test/java/com/openggf/tests/objects/TestSlotAllocator.java` (add)
- Reference: grep call sites of `allocateSlot(`, `allocateSlotAfter(`, `allocateDynamicSlot(`, `reserveDynamicSlot(` across `src/main` to confirm none bypass the new field.

- [ ] **Step 1: Add a guard test** that fails if any other class re-implements slot allocation (scan-based, mirroring `TestObjectServicesMigrationGuard` style):

```java
@Test
void noSecondAllocatorOutsideSlotAllocatorAndObjectManager() throws Exception {
    // Scan src/main for `new BitSet` used as object-slot occupancy or `nextClearBit`
    // outside SlotAllocator. Allowed files: SlotAllocator.java only.
    java.nio.file.Path root = java.nio.file.Path.of("src/main/java");
    var offenders = new java.util.ArrayList<String>();
    try (var paths = java.nio.file.Files.walk(root)) {
        paths.filter(p -> p.toString().endsWith(".java"))
             .filter(p -> !p.getFileName().toString().equals("SlotAllocator.java"))
             .forEach(p -> {
                 try {
                     String src = java.nio.file.Files.readString(p);
                     if (src.contains("nextClearBit")) offenders.add(p.toString());
                 } catch (Exception ignored) {}
             });
    }
    assertTrue(offenders.isEmpty(), "slot allocation must live only in SlotAllocator; offenders: " + offenders);
}
```

- [ ] **Step 2: Run**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestSlotAllocator#noSecondAllocatorOutsideSlotAllocatorAndObjectManager" test`
Expected: PASS (only `SlotAllocator` uses `nextClearBit`). If it fails, the named offender still has inline allocation — migrate it to call `ObjectManager`'s allocator APIs.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/objects/TestSlotAllocator.java
git commit -m "test: guard single SlotAllocator authority (stage 0)"
```

**Stage 0 gate:** Task 0.4 Step 4 showed zero frontier movement. Stage 0 is a clean substrate. No cascade map needed (nothing moved). Proceed to Stage 1.

---

## STAGE 1 — S2 ROM windowing timing (loader window + MarkObjGone unload + exec-then-load)

### Task 1.1: `S2ObjectWindowing` — coarse-camera + final cursor boundaries (unit-tested)

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/objects/S2ObjectWindowing.java`
- Test: `src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java`

- [ ] **Step 1: Write the failing boundary tests** (real ROM values; assert FINAL boundaries, never `±$300` from `camRounded`):

```java
package com.openggf.tests.objects;

import static org.junit.jupiter.api.Assertions.*;
import com.openggf.game.sonic2.objects.S2ObjectWindowing;
import org.junit.jupiter.api.Test;

class TestS2ObjectWindowing {

    @Test
    void loadBaseIsCameraMaskedWithoutSubtract() {
        // camRounded = Camera_X_pos & $FF80 (NO -$80 on the load base) — s2.asm:33026
        assertEquals(0x1500, S2ObjectWindowing.loadCoarse(0x1540));
        assertEquals(0x1500, S2ObjectWindowing.loadCoarse(0x157F));
    }

    @Test
    void unloadCoarseSubtracts80FirstThenMasks() {
        // Camera_X_pos_coarse = (Camera_X_pos - $80) & $FF80 — s2.asm MarkObjGone base
        assertEquals(0x1480, S2ObjectWindowing.unloadCoarse(0x1540)); // (0x1540-0x80)&0xFF80 = 0x14C0&0xFF80=0x1480
        assertEquals(0x1500, S2ObjectWindowing.unloadCoarse(0x1580)); // (0x1580-0x80)&0xFF80 = 0x1500
    }

    @Test
    void forwardLoadEdgeIsCoarsePlus280_trimEdgeIsCoarseMinus80() {
        int cam = 0x1500; // already chunk-aligned
        assertEquals(0x1500 + 0x280, S2ObjectWindowing.forwardLoadEdge(cam));
        assertEquals(0x1500 - 0x80,  S2ObjectWindowing.leftTrimEdge(cam));
        // NOT camRounded - 0x300:
        assertNotEquals(0x1500 - 0x300, S2ObjectWindowing.leftTrimEdge(cam));
    }

    @Test
    void backwardLoadEdgeIsCoarseMinus80_rightTrimEdgeIsCoarsePlus280() {
        int cam = 0x1500;
        assertEquals(0x1500 - 0x80,  S2ObjectWindowing.backwardLoadEdge(cam));
        assertEquals(0x1500 + 0x280, S2ObjectWindowing.rightTrimEdge(cam));
        assertNotEquals(0x1500 + 0x300, S2ObjectWindowing.rightTrimEdge(cam));
    }

    @Test
    void markObjGone_firstDeleteBucketIs300_native() {
        // (x & $FF80) - Camera_X_pos_coarse > $280  ⇒ first deleting bucket = $300 (value is $80-coarse)
        int cam = 0x1500;            // unloadCoarse(0x1500) = (0x1500-0x80)&0xFF80 = 0x1480
        int base = S2ObjectWindowing.unloadCoarse(cam); // 0x1480
        // object exactly at base + $280 → compare == $280 → NOT > $280 → stays
        assertFalse(S2ObjectWindowing.markObjGone(base + 0x280, cam));
        // object at base + $300 (next $80 bucket) → > $280 → delete
        assertTrue(S2ObjectWindowing.markObjGone(base + 0x300, cam));
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestS2ObjectWindowing" test`
Expected: COMPILE FAILURE — `S2ObjectWindowing` does not exist.

- [ ] **Step 3: Implement `S2ObjectWindowing` boundary math**

```java
package com.openggf.game.sonic2.objects;

/**
 * ROM-exact S2 object windowing math (docs/s2disasm/s2.asm).
 * Load base: camRounded = Camera_X_pos & $FF80 (ObjectsManager_Main, s2.asm:33026).
 * Unload base: Camera_X_pos_coarse = (Camera_X_pos - $80) & $FF80 (MarkObjGone, s2.asm:30209).
 * Live window (final boundaries): [camRounded - $80, camRounded + $280] (width $300).
 */
public final class S2ObjectWindowing {
    private S2ObjectWindowing() {}

    public static final int LOAD_AHEAD = 0x280;
    public static final int TRIM_BEHIND = 0x80;
    /** MarkObjGone native compare constant = $80 + roundToNextMultiple(320,$80)=$180 + $80 = $280. */
    public static final int UNLOAD_COMPARE = 0x280;

    public static int loadCoarse(int cameraX)   { return cameraX & 0xFF80; }
    public static int unloadCoarse(int cameraX) { return (cameraX - 0x80) & 0xFF80; }

    public static int forwardLoadEdge(int cameraX) { return loadCoarse(cameraX) + LOAD_AHEAD; }
    public static int leftTrimEdge(int cameraX)    { return loadCoarse(cameraX) - TRIM_BEHIND; }
    public static int backwardLoadEdge(int cameraX){ return loadCoarse(cameraX) - TRIM_BEHIND; }
    public static int rightTrimEdge(int cameraX)   { return loadCoarse(cameraX) + LOAD_AHEAD; }

    /** ROM MarkObjGone delete decision: (x_pos & $FF80) - Camera_X_pos_coarse > $280 (unsigned 16-bit). */
    public static boolean markObjGone(int objX, int cameraX) {
        int dist = ((objX & 0xFF80) - unloadCoarse(cameraX)) & 0xFFFF;
        return dist > UNLOAD_COMPARE;
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `mvn -q -Dmse=relaxed "-Dtest=TestS2ObjectWindowing" test`
Expected: PASS (all boundary + MarkObjGone tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/S2ObjectWindowing.java src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java
git commit -m "feat: S2 ROM windowing math (final cursor boundaries + MarkObjGone) (stage 1)"
```

### Task 1.2: ChkLoadObj skip-vs-full distinction (unit-tested)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/S2ObjectWindowing.java` (add a `LoadResult`/respawn-bit helper)
- Test: `src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java` (add)

- [ ] **Step 1: Add the failing test** capturing `s2.asm:33592` semantics (`bset #7` tests-and-sets; already-loaded ⇒ continue scan; only SST-full ⇒ stop):

```java
@Test
void chkLoadObj_alreadyLoadedContinuesScan_onlyFullStops() {
    // respawn bit already set (object already loaded) → CONTINUE (advance list ptr by 6), success
    assertEquals(S2ObjectWindowing.LoadOutcome.ALREADY_LOADED_CONTINUE,
            S2ObjectWindowing.chkLoadObj(/*respawnBitAlreadySet*/ true, /*slotAllocatable*/ false));
    // not yet loaded + a free slot → LOADED
    assertEquals(S2ObjectWindowing.LoadOutcome.LOADED,
            S2ObjectWindowing.chkLoadObj(false, true));
    // not loaded + SST full → STOP (only allocation failure halts the scan)
    assertEquals(S2ObjectWindowing.LoadOutcome.SST_FULL_STOP,
            S2ObjectWindowing.chkLoadObj(false, false));
}
```

- [ ] **Step 2: Run to verify failure** — `mvn -q -Dmse=relaxed "-Dtest=TestS2ObjectWindowing#chkLoadObj_alreadyLoadedContinuesScan_onlyFullStops" test` → COMPILE FAILURE.

- [ ] **Step 3: Implement**

```java
    public enum LoadOutcome { LOADED, ALREADY_LOADED_CONTINUE, SST_FULL_STOP }

    /**
     * ROM ChkLoadObj (s2.asm:33592): bset #7 tests-and-sets the respawn entry.
     * If bit 7 was already set, the object is already loaded → advance the list
     * pointer and CONTINUE scanning (success). Otherwise allocate; only a full SST
     * (no allocatable slot) STOPS the scan.
     */
    public static LoadOutcome chkLoadObj(boolean respawnBitAlreadySet, boolean slotAllocatable) {
        if (respawnBitAlreadySet) {
            return LoadOutcome.ALREADY_LOADED_CONTINUE;
        }
        return slotAllocatable ? LoadOutcome.LOADED : LoadOutcome.SST_FULL_STOP;
    }
```

- [ ] **Step 4: Run** → PASS. **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/objects/S2ObjectWindowing.java src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java
git commit -m "feat: S2 ChkLoadObj skip-vs-full outcome (stage 1)"
```

### Task 1.3: Slot recycle via SlotAllocator — id reflects the new occupant (unit-tested)

**Files:**
- Test: `src/test/java/com/openggf/tests/objects/TestSlotAllocator.java` (add)

- [ ] **Step 1: Add the recycle/identity test** (delete clears identity ⇒ next allocate reuses the slot; the recycled slot's reported occupant changes):

```java
@Test
void recycle_releasedSlotIsReusedAndIdentityCleared() {
    SlotAllocator a = new SlotAllocator(ObjectSlotLayout.SONIC_2, SlotEmptyPredicate.ID_BYTE);
    int s = a.allocate();          // 16, "occupied by object A"
    assertFalse(a.isEmpty(s));
    a.release(s);                  // ROM DeleteObject: identity cleared
    assertTrue(a.isEmpty(s));      // empty predicate now true
    int s2 = a.allocate();         // ROM AllocateObject linear-first-empty → same slot
    assertEquals(s, s2);           // recycled to the same slot for "object B"
}
```

- [ ] **Step 2: Run** → PASS (allocator-level reuse). NOTE: this proves slot *reuse* only, not "the slot now reports the new occupant's id".

- [ ] **Step 3: Add the `ObjectManager`-level recycle-IDENTITY test** (the MTZ3 invariant) — using the headless object harness (see `TestHeadlessWallCollision`/`HeadlessTestRunner` setup; reset singletons via `@FullReset`/`SingletonResetExtension`): spawn object A into a dynamic slot via the manager, assert `om.objectIdInSlot(slot) == A.objectId`; destroy A and run the unload so the slot is released; spawn object B which recycles the **same** slot, assert `om.objectIdInSlot(slot) == B.objectId` (NOT A's, NOT −1). File: `src/test/java/com/openggf/tests/objects/TestObjectManagerSlotRecycleIdentity.java`. This is the engine-level proof that the recycled slot reports the new id, which the sidekick despawn compare reads. *(The Task 1.7 occupancy oracle is the per-frame integration proof of the same invariant across a real trace.)*

- [ ] **Step 4: Run** → PASS. **Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/objects/TestSlotAllocator.java src/test/java/com/openggf/tests/objects/TestObjectManagerSlotRecycleIdentity.java
git commit -m "test: slot-recycle reuse + objectIdInSlot reports new occupant (stage 1)"
```

### Task 1.4: Build the comparison-only occupancy oracle

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/ObjectOccupancyOracle.java`
- Create: `src/test/java/com/openggf/tests/trace/TestS2ObjectOccupancyOracle.java`
- Reference: `com.openggf.trace.TraceData`/`TraceEvent` (read its `object_appeared`/`object_removed`/`object_near` records), `AbstractTraceReplayTest` (how it steps frames + exposes engine state), `ObjectManager.objectIdInSlot`.

- [ ] **Step 0: Add `ObjectManager.occupiedDynamicSlotIds()`** — a read-only `Map<Integer,Integer>` (slot→`spawn.objectId()&0xFF`) for every live (non-destroyed, spawn-backed) dynamic-slot occupant, built from the same `getActiveObjects()` scan `objectIdInSlot` uses (`ObjectManager.java:1477-1490`). This is the engine side of the oracle's extra-occupant detection. Commit with the oracle in Step 4.

- [ ] **Step 1: Write the oracle** — reconstruct expected per-frame slot→id occupancy from the trace events and diff against the engine **in both directions** (missing/wrong expected slots AND extra engine occupants ROM unloaded). **Comparison-only: it must never write engine state.** If the recorder cannot express a same-frame delete+reallocate of one slot (see spec §7 caveat), fall back to comparing the full per-frame slot→id snapshot table rather than transition deltas. Skeleton:

```java
package com.openggf.tests.trace;

import com.openggf.trace.TraceData;
import com.openggf.level.objects.ObjectManager;
import java.util.HashMap;
import java.util.Map;

/** Comparison-only: diffs engine slot->id occupancy against the ROM trace timeline. */
public final class ObjectOccupancyOracle {
    public record Divergence(int frame, int slot, int expectedId, int actualId) {}

    /** Build expected slot->id for {@code frame} from object_appeared/removed up to that frame. */
    public static Map<Integer,Integer> expectedOccupancy(TraceData trace, int frame) {
        Map<Integer,Integer> occ = new HashMap<>();
        // Replay appeared/removed (and recycle) events with frame <= frame, keyed by slot.
        // (Use TraceData's aux accessors; appeared sets slot->id, removed clears it.)
        // ... implement against TraceData's actual event API ...
        return occ;
    }

    /**
     * First slot where engine occupancy differs from ROM expected, or null.
     * Compares BOTH directions: a wrong/missing expected slot AND an EXTRA engine
     * occupant absent from ROM (engine kept an object loaded ROM had unloaded —
     * the MTZ3 failure mode). Uses {@code -1} to mean "empty" on either side.
     */
    public static Divergence firstDivergence(TraceData trace, ObjectManager om, int frame) {
        Map<Integer,Integer> expected = expectedOccupancy(trace, frame);
        Map<Integer,Integer> actual = engineOccupancy(om); // slot -> id for every live dynamic slot
        java.util.TreeSet<Integer> slots = new java.util.TreeSet<>();
        slots.addAll(expected.keySet());
        slots.addAll(actual.keySet());
        for (int slot : slots) {
            int want = expected.getOrDefault(slot, -1);
            int got = actual.getOrDefault(slot, -1);
            if (want != got) return new Divergence(frame, slot, want, got); // missing, wrong, OR extra
        }
        return null;
    }

    /** Engine slot->id for every occupied dynamic slot (extra-occupant detection). */
    private static Map<Integer,Integer> engineOccupancy(ObjectManager om) {
        Map<Integer,Integer> m = new HashMap<>();
        // Add ObjectManager.occupiedDynamicSlotIds() (Task 1.4 Step 0): returns slot->id
        // for every live (non-destroyed, spawn-backed) dynamic-slot occupant.
        for (var e : om.occupiedDynamicSlotIds().entrySet()) m.put(e.getKey(), e.getValue());
        return m;
    }
}
```

- [ ] **Step 2: Write `TestS2ObjectOccupancyOracle`** — drive `TestS2MtzLevelSelectTraceReplay`'s BK2 through the engine and, each frame, assert `ObjectOccupancyOracle.firstDivergence(...)` is null up to the trace's current frontier; emit the first divergence frame+slot as the diagnostic (this is a measurement test, expected to FAIL initially at the windowing-drift frame — annotate `@Disabled("oracle measurement; enable once S2 windowing lands")` or run as a reporting `@Test` that logs rather than asserts until Task 1.7).

- [ ] **Step 3: Run as a measurement** — `mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 "-Ds2.rom.path=<abs>" "-Dtest=TestS2ObjectOccupancyOracle" test` → record the first slot-occupancy divergence frame for MTZ1 (expected: near the SteamSpring load drift). **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java src/test/java/com/openggf/tests/trace/ObjectOccupancyOracle.java src/test/java/com/openggf/tests/trace/TestS2ObjectOccupancyOracle.java
git commit -m "feat+test: occupiedDynamicSlotIds + comparison-only S2 occupancy oracle (stage 1)"
```

### Task 1.4b: Wire the S2 load cursor scan to `S2ObjectWindowing` final boundaries

**Files:**
- Modify: `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java` (the S2 window source) and/or the S2 `Placement` in `ObjectManager.java:3459-3758` + the `syncActiveSpawnsLoad(true)` path (grep it).
- Test: `src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java` (add a spawn-list scan test).

The S2 load must consume `S2ObjectWindowing.forwardLoadEdge`/`backwardLoadEdge`/`leftTrimEdge`/`rightTrimEdge` as the **final** load/trim boundaries, driven by camera **motion direction** (ROM keeps a right cursor + left cursor and loads forward when the camera moved right, backward when it moved left — `s2.asm:33026` `ObjectsManager_GoingForward`/`GoingBackward`). The current `AbstractPlacementManager.getWindowStart/getWindowEnd` (`:181-189`) is a symmetric window-recompute; at native width its edges already equal `loadCoarse−$80`/`loadCoarse+$280`, but it is not directional and the trim side is not ROM-cursor-shaped.

- [ ] **Step 1: Read** `AbstractPlacementManager.getWindowStart/getWindowEnd`, the S2 `Placement.update`/`syncActiveSpawnsLoad` path, and confirm the directional-cursor gap vs `ObjectsManager_GoingForward/GoingBackward`.
- [ ] **Step 2: Write the failing scan test** — a real sorted spawn list (e.g. x = {0x1000, 0x1180, 0x1400, 0x1700, 0x1A00}) driven through camera motion **forward then reverse**, asserting the ROM **exclusive** load boundary:
  - ROM `ObjectsManager_GoingForward` does `cmp.w (a0),d6 / bls.s .done` (`s2.asm:33095`): `bls` stops when `d6 <= objX`, so it loads while `objX < d6`. **The load edge is EXCLUSIVE** — a spawn loads iff `spawn.x < forwardLoadEdge(cam)`; a spawn exactly at `forwardLoadEdge` does **NOT** load; `forwardLoadEdge − 1` loads.
  - the left cursor trims per ROM's exact branch at `leftTrimEdge(cam)`; moving backward, loads use `backwardLoadEdge(cam)` (same exclusive `<` rule) and the right cursor trims at `rightTrimEdge(cam)`. **Match each cursor's comparison to its exact ROM branch** (`bls`/`bcs`/`bcc`) — verify per cursor, do not assume inclusive.
  - no spawn loads/trims `$80` early or late (spec trap) and none is off-by-one inclusive (this finding).

```java
@Test
void s2ForwardScanLoadBoundaryIsExclusive() {
    int cam = 0x1500;
    int edge = S2ObjectWindowing.forwardLoadEdge(cam); // camRounded + 0x280
    // Pseudocode contract — implement against the real Placement/scan once read:
    // ForwardScan fwd = new ForwardScan(spawnXs);
    // assertTrue(fwd.loadsAt(edge - 1, cam));   // strictly below edge → loads
    // assertFalse(fwd.loadsAt(edge, cam));      // AT edge → ROM bls stops → NOT loaded
    // assertFalse(fwd.loadsAt(edge + 1, cam));  // above edge → not loaded
    // ... backward-direction analogue with backwardLoadEdge / rightTrimEdge, same exclusive rule ...
}
```

- [ ] **Step 3: Implement** the directional S2 cursor scan consuming the `S2ObjectWindowing` edges (replace the symmetric window source for S2; keep S1/S3K on their existing paths this stage).
- [ ] **Step 4: Run** the scan test → PASS. **Step 5: Commit** (test + impl together, green):

```bash
git add src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java src/main/java/com/openggf/level/objects/ObjectManager.java src/test/java/com/openggf/tests/objects/TestS2ObjectWindowing.java
git commit -m "feat: S2 directional load cursor consumes ROM final boundaries (stage 1)"
```

### Task 1.5: Wire S2 object-side `MarkObjGone` unload (replace the manager distance heuristic for S2)

**Files:**
- Modify: `ObjectManager.java` — the S2 unload path. `unloadCounterBasedOutOfRange` `:2520` is S1's counter path; the S2 (exec-then-load / `syncActiveSpawnsUnload` or per-instance) unload currently leans on `isOutOfRangeS1` `:2485`. Route S2 instances' off-screen unload decision through `S2ObjectWindowing.markObjGone(objX, cameraX)` instead of the distance limit, clearing the respawn "loaded" bit (the existing `dispatchDestroyRemoveFromActive`/`removeFromActiveForUnload` respawnable path) then releasing the slot via the allocator.

- [ ] **Step 1: Read** the current S2 unload call path (grep `isOutOfRangeS1`, `syncActiveSpawnsUnload`, `removeFromActiveForUnload`, `outOfRangeReferenceX`) and identify the S2 branch.
- [ ] **Step 2: Add an S2 unload decision** that calls `S2ObjectWindowing.markObjGone(romXPos, cameraX)` for S2 (gate by `slotLayout`/game), keeping S1/S3K paths untouched in this stage. Clear the respawn bit + `release(slot)` on delete, matching ROM (`respawn_index != 0` ⇒ clear bit 7; then DeleteObject).
  - **Coordinate-semantics guard (REQUIRED):** ROM `MarkObjGone` reads `x_pos(a0)`, which in this engine is the **centre** X — use `getCentreX()` (or the object's explicit ROM out-of-range reference via the existing `getOutOfRangeReferenceX()`/`outOfRangeReferenceX(...)` path, `ObjectManager.java:2563`), **never** `getX()`/sprite top-left bounds. A top-left X would shift the unload boundary by the object's half-width.
- [ ] **Step 3: Unit test** the decision wrapper: assert it delegates to `markObjGone` AND that it is fed `getCentreX()` (or the ROM reference X), not `getX()`. Construct a stub object whose `getX()` and `getCentreX()` differ by a known half-width and assert the unload decision uses the centre value (the object stays loaded at a centre-X inside the window even though its top-left X is outside it, and vice-versa).
- [ ] **Step 4: Targeted trace check** — `mvn -q -Dmse=relaxed -Dsurefire.forkCount=1 "-Ds2.rom.path=<abs>" "-Dtest=TestS2MtzLevelSelectTraceReplay#replayMatchesTrace" test` → record frame movement. **Step 5: Commit.**

### Task 1.6: Correct S2 frame ordering to exec-then-load (BOTH load sites — there are two)

**Files:**
- Modify: `ObjectManager.java` `update()` — the S2 path currently loads **twice** per frame:
  1. **top branch** `:740-743` (`execThenLoad && !twoAxisCursorPlacement`): `syncActiveSpawnsLoad(true)` **then** `runExecLoop(...)` (load-then-exec), and
  2. **post-block** `:775-782` (`!counterBased`): `placement.update(cameraX)` then again `cleanupDestroyedDynamicObjects()` + `syncActiveSpawnsLoad(true)`.
  ROM S2 is `RunObjects` (`s2.asm:5095`) then exactly one `ObjectsManager` (`:5112`) = **exec → one load**.

- [ ] **Step 1: Read** both sites (`:716-783`) and confirm the double-load. Map which spawns each currently loads (add a temporary log if needed) so Step 2's consolidation is verified, not assumed.
- [ ] **Step 2: Consolidate S2 to exec → exactly one load.** In the top S2 branch, run `runExecLoop(...)` (with the Task 1.5 object-side `MarkObjGone` self-deletes) **first**, then exactly one `placement.update(cameraX)` + `syncActiveSpawnsLoad(true)` in the ROM `RunObjects → ObjectsManager` position. **Remove (or gate off for S2) the post-block load at `:775-782`** so S2 does not load a second time. Remove the pre-exec top-branch `syncActiveSpawnsLoad(true)` at `:740`. Net S2 per-frame: `runExecLoop` → one `placement.update` + one `syncActiveSpawnsLoad`.
  - **Camera-timing note:** verify the single load sees the same `cameraX` (pre- vs post-`DeformLayers`) that ROM `ObjectsManager` sees, so cursor/respawn assignment matches. The post-block comment (`:771-774`) documents the S1 post-camera requirement; confirm the S2 load's `cameraX` source against ROM before finalizing the position.
  - **Do NOT change** the S1 (`counterBased`) branch or the S3K (`twoAxisCursorPlacement`) branch — S3K stays load-then-exec (`Load_Sprites` before `Process_Sprites`, `sonic3k.asm:7884`).
- [ ] **Step 3: Add a frame-ordering assertion** — a focused test (headless ObjectManager, S2 layout) that loads/executes are called in the order exec→load exactly once each per frame for S2 (e.g. via a spy/counter on the load + exec entry points), guarding against re-introducing a second load.
- [ ] **Step 4: Targeted + same-game-green check** — run MTZ1/MTZ3 + EHZ1/SCZ/WFZ with `-Dsurefire.forkCount=1`; confirm no double-load artifacts (object count per frame sane). **Step 5: Commit.**

### Task 1.7: Occupancy oracle passes for S2 up to frontier; enable the assert

**Files:**
- Modify: `TestS2ObjectOccupancyOracle.java` — flip the measurement to an assertion (remove `@Disabled`/logging) now that S2 windowing is ROM-timed.

- [ ] **Step 1:** Run the oracle on MTZ1 + a green S2 trace (`-Dsurefire.forkCount=1`). Expected: engine slot→id occupancy matches the ROM trace timeline frame-for-frame (or diverges only at/after the trace's own physics frontier, not before it on object presence).
- [ ] **Step 2: Commit** the enabled oracle test.

### Task 1.8: Remove the dead S2 manager heuristic + dormancy

**Files:**
- Modify: `ObjectManager.java` (`isOutOfRangeS1` usage for S2, `unloadCounterBasedOutOfRange` S2 reachability), `AbstractPlacementManager.java` (S2 dormancy/window-recompute).

- [ ] **Step 1:** With S2 unload now object-side (Task 1.5), delete the S2 reachability into `isOutOfRangeS1` and the S2 dormancy `markDormant` path (keep them for S1/S3K until their stages). Confirm no S2 code path still double-drives unload (grep + read).
- [ ] **Step 2: Full sweep** (`-Dsurefire.forkCount=1`, all 3 ROMs) — S1 and S3K frontiers must be **byte-identical** (untouched); S2 frontiers are the cascade. **Step 3: Commit.**

### Task 1.9: Stage-1 cascade map + re-derive + merge

- [ ] **Step 1:** Parse the full sweep → write a before→after first-error-frame table for every trace into `docs/status/trace-frontier-log.md`; for each S2 regression, attribute it to the prior compensating fix (git blame the relevant object/sidekick code).
- [ ] **Step 2:** Confirm the intended wins: MTZ1 advances past 375, MTZ3 restored ≥3719 (or its slot-recycle now fires on the ROM frame). Re-derive any S2 regression on the now-correct timing (separate commits).
- [ ] **Step 3:** `CHANGELOG.md` entry; commit with the full trailer block (`Changelog: updated`); do NOT merge to develop until the cascade nets acceptable per the land-correct philosophy. Then merge via the standard integration path.

---

## Self-Review

- **Spec coverage:** §2 invariants → Tasks 1.1/1.3; §3 single-authority **occupancy/allocation** (identity stays in ObjectManager) → Stage 0 (0.2-0.5); §4 S2 loader window/scan → 1.1 + **1.4b**, ChkLoadObj → 1.2, unload + coordinate-semantics → 1.5, Allocate/Delete recycle → 0.3/1.3; §5 S2 exec-then-**one**-load across **both** load sites → 1.6 (S3K load-then-exec preserved); §6 slot recycle + **objectIdInSlot-reports-new-id** → 1.3 (allocator reuse + ObjectManager identity test) + 1.7 (oracle); §7 occupancy oracle **bidirectional** (missing/wrong AND extra occupants) + same-frame-recycle caveat → 1.4/1.7; §8 edge-case tests → 1.1/1.2/0.1/1.3/1.4b; §9 cascade loop → 1.9. S1/S3K loaders are explicitly later stages.
- **Review-fix coverage:** [P1] second S2 load site → 1.6 Step 2; [P1] loader-window wiring → 1.4b; [P1] identity authority (ObjectManager keeps objectIdInSlot; recycle-id test) → arch text + 0.4 + 1.3; [P1] oracle bidirectional → 1.4 firstDivergence + `occupiedDynamicSlotIds`; [P2] coordinate semantics (`getCentreX`) → 1.5; [P2] execOrder consistency (stays in ObjectManager) → arch + file structure + 0.4; [P2] no committing non-compiling tests → red-to-green commit discipline (0.1 Step 3, applied throughout).
- **Placeholder scan:** Tasks 1.4b/1.5/1.6/1.8 use read-then-modify steps (not full code) because they edit specific branches of the 8851-line `ObjectManager`/`AbstractPlacementManager`; each names the exact method/line + the precise transformation + ROM citation. All NEW components have complete code; oracle `expectedOccupancy` + the 1.4b scan test are skeletons against the real `TraceData`/`Placement` APIs, explicitly called out for the implementing agent to fill.
- **Type consistency:** `SlotAllocator.allocate/allocateAfter/reserve/release/isEmpty/clear/peakSlotCount`, `SlotEmptyPredicate.ID_BYTE/ROUTINE_POINTER`, `S2ObjectWindowing.loadCoarse/unloadCoarse/forwardLoadEdge/leftTrimEdge/backwardLoadEdge/rightTrimEdge/markObjGone/chkLoadObj/LoadOutcome`, `ObjectManager.objectIdInSlot/occupiedDynamicSlotIds` are used consistently across tasks.
- **Scope:** Stage 0 + Stage 1 only; produces working, testable software (clean allocator substrate + ROM-timed S2 windowing). S1/S3K are separate later plans.
