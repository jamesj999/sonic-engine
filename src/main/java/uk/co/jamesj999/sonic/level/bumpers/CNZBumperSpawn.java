package uk.co.jamesj999.sonic.level.bumpers;

import uk.co.jamesj999.sonic.level.spawn.SpawnPoint;

/**
 * Represents a CNZ map bumper spawn point.
 * <p>
 * ROM Format (6 bytes per bumper):
 * <ul>
 *   <li>Offset 0 (word): bumper_id - Type bits 1-3 determine behavior (6 types: 0-5)</li>
 *   <li>Offset 2 (word): bumper_x - X position in level</li>
 *   <li>Offset 4 (word): bumper_y - Y position in level</li>
 * </ul>
 * <p>
 * Disassembly Reference: s2.asm lines 32146-32674 (SpecialCNZBumpers)
 *
 * @param index The sequential index of this bumper in the level data
 * @param type The bumper type (0-5) determining collision box and bounce behavior
 * @param x X position in level coordinates
 * @param y Y position in level coordinates
 */
public record CNZBumperSpawn(int index, int type, int x, int y) implements SpawnPoint {

    /**
     * Extract bumper type from raw bumper ID.
     * <p>
     * ROM Reference: s2.asm line 32310-32311
     * <pre>
     * move.w  bumper_id(a1),d0
     * andi.w  #$E,d0           ; Mask bits 1-3
     * </pre>
     * The result is used as a word offset into the handler table, so we divide by 2
     * to get the type index (0-5).
     *
     * @param bumperId The raw bumper ID word from ROM
     * @return The bumper type (0-5)
     */
    public static int extractType(int bumperId) {
        return (bumperId & 0x0E) >> 1;
    }
}
