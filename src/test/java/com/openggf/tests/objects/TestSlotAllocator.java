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
    void s3kAllocationIncludesFinalRomProbe() {
        SlotAllocator allocator =
                new SlotAllocator(ObjectSlotLayout.SONIC_3K, SlotEmptyPredicate.ID_BYTE);
        for (int slot = 4; slot <= 93; slot++) {
            assertEquals(slot, allocator.allocate());
        }
        assertEquals(-1, allocator.allocate());
        assertFalse(allocator.reserve(94));
    }

    @Test
    void hasFreeSlotMatchesAllocateSuccess() {
        // ROM FindFreeObj probe used by routines that branch on success/failure
        // without spawning (e.g. S1 LZ drowning countdown retry when the pool is
        // full). hasFreeSlot() must agree with whether allocate() would succeed.
        SlotAllocator a = s2();
        assertTrue(a.hasFreeSlot());
        assertEquals(16, a.firstFreeSlot());
        assertEquals(16, a.firstFreeSlot(), "probing must not consume the slot");
        for (int i = 0; i < ObjectSlotLayout.SONIC_2.dynamicSlotCount(); i++) {
            assertTrue(a.hasFreeSlot());   // true while a slot remains
            assertTrue(a.allocate() >= 0);
        }
        assertFalse(a.hasFreeSlot());      // pool full → matches allocate()==-1
        assertEquals(-1, a.firstFreeSlot());
        assertEquals(-1, a.allocate());
        a.release(16);
        assertTrue(a.hasFreeSlot());       // freeing one slot re-enables it
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
}
