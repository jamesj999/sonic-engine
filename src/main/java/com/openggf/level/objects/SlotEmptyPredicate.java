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
