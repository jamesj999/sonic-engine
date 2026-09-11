package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.game.common.CommonPlacementParser;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.objects.ObjectSpawn;

import java.util.List;

/**
 * Parses Sonic 3&amp;K object placement data into {@link ObjectSpawn} records.
 *
 * <p>S3K uses a 6-byte record format identical to Sonic 2:
 * <pre>
 *   Bytes 0-1: X position (16-bit, big-endian)
 *   Bytes 2-3: Y word: R0FF YYYY YYYY YYYY
 *              R = respawn tracked (bit 15)
 *              FF = render flags (bits 13-14)
 *              Y = 12-bit Y position (bits 0-11)
 *   Byte 4:   Object ID
 *   Byte 5:   Subtype
 * </pre>
 *
 * <p>The pointer table at {@link Sonic3kConstants#SPRITE_LOC_PTRS_ADDR} uses
 * 32-bit absolute addresses, indexed as {@code zone * 2 + act}.
 */
public class Sonic3kObjectPlacement {

    private final RomByteReader rom;

    public Sonic3kObjectPlacement(RomByteReader rom) {
        this.rom = rom;
    }

    /**
     * Loads object spawns for the given zone and act.
     *
     * @param zone Zone index (0-based, e.g. 0=AIZ, 1=HCZ, ...)
     * @param act  Act index (0-based)
     * @return Sorted, immutable list of object spawns
     */
    public List<ObjectSpawn> load(int zone, int act) {
        int ptrIndex = zone * 2 + act;
        int listAddr = rom.readU32BE(Sonic3kConstants.SPRITE_LOC_PTRS_ADDR + ptrIndex * 4);

        if (listAddr == 0 || listAddr >= rom.size()) {
            return List.of();
        }

        // ROM Load_Sprites groups the list by 0x80 X-chunk, then scans each
        // chunk in its authored record order. The placement controller applies
        // the chunk grouping; retain the source order for FindFreeObj slots.
        return CommonPlacementParser.parseObjectRecords(rom, listAddr, false);
    }
}
