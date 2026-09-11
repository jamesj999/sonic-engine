package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.rings.RingSpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses Sonic 3&amp;K ring placement data into {@link RingSpawn} records.
 *
 * <p>S3K uses a raw 4-byte ring record format:
 * <pre>
 *   Bytes 0-1: X position (16-bit, big-endian)
 *   Bytes 2-3: Y position (16-bit, big-endian)
 *   Terminator: 0xFFFF
 * </pre>
 *
 * <p>Unlike Sonic 2's preload step, the S3K ring collision routine advances
 * directly across these 4-byte records and does not expand row/column count
 * nibbles.
 *
 * <p>The pointer table at {@link Sonic3kConstants#RING_LOC_PTRS_ADDR} uses
 * 32-bit absolute addresses, indexed as {@code zone * 2 + act}.
 */
public class Sonic3kRingPlacement {
    private static final int RING_RECORD_SIZE = 4;
    private static final int TERMINATOR = 0xFFFF;

    private final RomByteReader rom;

    public Sonic3kRingPlacement(RomByteReader rom) {
        this.rom = rom;
    }

    /**
     * Loads ring spawns for the given zone and act.
     *
     * @param zone Zone index (0-based, e.g. 0=AIZ, 1=HCZ, ...)
     * @param act  Act index (0-based)
     * @return Sorted, immutable list of ring spawns
     */
    public List<RingSpawn> load(int zone, int act) {
        int ptrIndex = zone * 2 + act;
        int listAddr = rom.readU32BE(Sonic3kConstants.RING_LOC_PTRS_ADDR + ptrIndex * 4);

        if (listAddr == 0 || listAddr >= rom.size()) {
            return List.of();
        }

        return parseRawRingRecords(rom, listAddr);
    }

    static List<RingSpawn> parseRawRingRecords(RomByteReader rom, int startAddr) {
        List<RingSpawn> spawns = new ArrayList<>();
        int cursor = startAddr;

        while (cursor + RING_RECORD_SIZE <= rom.size()) {
            int x = rom.readU16BE(cursor);
            if (x == TERMINATOR) {
                break;
            }

            spawns.add(new RingSpawn(x, rom.readU16BE(cursor + 2)));
            cursor += RING_RECORD_SIZE;
        }

        spawns.sort(Comparator.comparingInt(RingSpawn::x));
        return List.copyOf(spawns);
    }
}
