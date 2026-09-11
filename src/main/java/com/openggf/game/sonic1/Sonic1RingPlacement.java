package com.openggf.game.sonic1;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Extracts ring objects (ID 0x25) from the Sonic 1 object spawn list and
 * expands them into individual {@link RingSpawn} positions.
 *
 * <p>In Sonic 1, rings are ordinary objects with a subtype encoding that
 * describes the ring count and spacing pattern:
 * <pre>
 *   Bits 0-2: ring count minus 1 (0 = 1 ring, up to 6 = 7 rings; 7 is clamped to 6)
 *   Bits 4-7: spacing pattern index (0-15), indexes into Ring_PosData
 * </pre>
 *
 * <p>The Ring_PosData table contains 16 signed (deltaX, deltaY) byte pairs
 * that define the offset between consecutive rings in a group.
 */
public class Sonic1RingPlacement {

    private static final int RING_OBJECT_ID = 0x25;
    private static final int MAX_RING_COUNT = 7;

    /**
     * Ring_PosData from {@code _incObj/25 & 37 Rings.asm}.
     * Each entry is {deltaX, deltaY} in pixels.
     */
    private static final int[][] RING_POS_DATA = {
            {0x10, 0x00},   //  0: horizontal tight
            {0x18, 0x00},   //  1: horizontal medium
            {0x20, 0x00},   //  2: horizontal wide
            {0x00, 0x10},   //  3: vertical tight
            {0x00, 0x18},   //  4: vertical medium
            {0x00, 0x20},   //  5: vertical wide
            {0x10, 0x10},   //  6: diagonal SE tight
            {0x18, 0x18},   //  7: diagonal SE medium
            {0x20, 0x20},   //  8: diagonal SE wide
            {-0x10, 0x10},  //  9: diagonal SW tight
            {-0x18, 0x18},  // 10: diagonal SW medium
            {-0x20, 0x20},  // 11: diagonal SW wide
            {0x10, 0x08},   // 12: shallow SE tight
            {0x18, 0x10},   // 13: shallow SE medium
            {-0x10, 0x08},  // 14: shallow SW tight
            {-0x18, 0x10},  // 15: shallow SW medium
    };

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

    /**
     * Separates ring objects from the full object list, expanding each ring
     * object into individual {@link RingSpawn} positions based on the subtype.
     *
     * @param allObjects all object spawns from the level
     * @return extracted rings and the remaining non-ring objects
     */
    public Result extract(List<ObjectSpawn> allObjects) {
        List<RingSpawn> rings = new ArrayList<>();
        List<ObjectSpawn> remaining = new ArrayList<>();
        java.util.Map<ObjectSpawn, List<RingSpawn>> mapping = new HashMap<>();

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

    /**
     * Returns the (deltaX, deltaY) spacing for the given ring subtype.
     * Used by Sonic1RingInstance to compute child ring positions.
     *
     * @param subtype the ring object's subtype byte
     * @return int array {deltaX, deltaY} in pixels
     */
    public static int[] getRingSpacing(int subtype) {
        int patternIndex = (subtype >> 4) & 0x0F;
        return RING_POS_DATA[patternIndex];
    }

    private void expandRing(ObjectSpawn spawn, List<RingSpawn> out) {
        int subtype = spawn.subtype();
        int countMinusOne = subtype & 0x07;
        if (countMinusOne > 6) {
            countMinusOne = 6; // Clamp to max 7 rings
        }
        int ringCount = countMinusOne + 1;

        int patternIndex = (subtype >> 4) & 0x0F;
        int dx = RING_POS_DATA[patternIndex][0];
        int dy = RING_POS_DATA[patternIndex][1];

        int x = spawn.x();
        int y = spawn.y();

        for (int i = 0; i < ringCount; i++) {
            out.add(new RingSpawn(x, y));
            x += dx;
            y += dy;
        }
    }
}
