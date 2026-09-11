package com.openggf.game.common;

import com.openggf.data.RomByteReader;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Game-agnostic record parsers for ring and object placement data.
 *
 * <p>S1, S2, and S3K all share the same 4-byte ring record format and
 * 6-byte object record format. Only the pointer table resolution differs
 * (S2 uses 16-bit relative offsets, S3K uses 32-bit absolute addresses).
 * Each game's placement class resolves the list start address, then
 * delegates to these shared parsers for the inner record loop.
 */
public final class CommonPlacementParser {

    private static final int RING_RECORD_SIZE = 4;
    private static final int OBJECT_RECORD_SIZE = 6;
    private static final int TERMINATOR = 0xFFFF;
    private static final int RING_SPACING = 0x18;

    private CommonPlacementParser() {}

    /**
     * Parses ring placement records starting at {@code startAddr} until
     * the {@code 0xFFFF} terminator.
     *
     * <p>Record format (4 bytes, big-endian):
     * <pre>
     *   Bytes 0-1: X position (16-bit)
     *   Bytes 2-3: Y word: CCCC YYYY YYYY YYYY
     *              C = count nibble (bits 12-15): &lt;8 = horizontal, &gt;=8 = vertical
     *              Y = 12-bit Y position (bits 0-11)
     * </pre>
     *
     * <p>Each record expands to {@code (countNibble & 7) + 1} individual
     * rings spaced 0x18 pixels apart horizontally or vertically.
     *
     * @param rom       ROM byte reader
     * @param startAddr address of the first ring record
     * @return sorted, immutable list of expanded ring spawns
     */
    public static List<RingSpawn> parseRingRecords(RomByteReader rom, int startAddr) {
        List<RingSpawn> spawns = new ArrayList<>();
        int cursor = startAddr;

        while (cursor + RING_RECORD_SIZE <= rom.size()) {
            int x = rom.readU16BE(cursor);
            if (x == TERMINATOR) {
                break;
            }

            int yWord = rom.readU16BE(cursor + 2);
            int y = yWord & 0x0FFF;
            int countNibble = (yWord >> 12) & 0xF;
            boolean vertical = countNibble >= 0x8;
            int extra = vertical ? (countNibble - 0x8) : countNibble;
            int total = extra + 1;

            for (int i = 0; i < total; i++) {
                int ringX = x + (vertical ? 0 : i * RING_SPACING);
                int ringY = y + (vertical ? i * RING_SPACING : 0);
                spawns.add(new RingSpawn(ringX, ringY));
            }
            cursor += RING_RECORD_SIZE;
        }

        spawns.sort(Comparator.comparingInt(RingSpawn::x));
        return List.copyOf(spawns);
    }

    /**
     * Parses object placement records starting at {@code startAddr} until
     * the {@code 0xFFFF} terminator.
     *
     * <p>Record format (6 bytes, big-endian):
     * <pre>
     *   Bytes 0-1: X position (16-bit)
     *   Bytes 2-3: Y word: R0FF YYYY YYYY YYYY
     *              R = respawn tracked (bit 15)
     *              FF = render flags (bits 13-14)
     *              Y = 12-bit Y position (bits 0-11)
     *   Byte 4:   Object ID
     *   Byte 5:   Subtype
     * </pre>
     *
     * @param rom       ROM byte reader
     * @param startAddr address of the first object record
     * @return sorted, immutable list of object spawns
     */
    public static List<ObjectSpawn> parseObjectRecords(RomByteReader rom, int startAddr) {
        return parseObjectRecords(rom, startAddr, true);
    }

    /**
     * Parses object placement records while optionally retaining the source
     * order from the ROM list.
     *
     * <p>S3K's loader groups records by 0x80 X-chunk, but walks records within
     * a chunk in their original list order. The placement manager performs the
     * chunk grouping itself, so S3K must retain that source order here. S1 and
     * S2 keep the historical full-X ordering through the default overload.
     *
     * @param rom read-only ROM data
     * @param startAddr address of the first object record
     * @param sortByX whether to return records in ascending full-X order
     * @return immutable list of object spawns
     */
    public static List<ObjectSpawn> parseObjectRecords(
            RomByteReader rom, int startAddr, boolean sortByX) {
        List<ObjectSpawn> spawns = new ArrayList<>();
        int cursor = startAddr;

        while (cursor + OBJECT_RECORD_SIZE <= rom.size()) {
            int x = rom.readU16BE(cursor);
            if (x == TERMINATOR) {
                break;
            }

            int yWord = rom.readU16BE(cursor + 2);
            int y = yWord & 0x0FFF;
            int renderFlags = (yWord >> 13) & 0x3;
            boolean respawnTracked = (yWord & 0x8000) != 0;
            int objectId = rom.readU8(cursor + 4);
            int subtype = rom.readU8(cursor + 5);

            spawns.add(new ObjectSpawn(x, y, objectId, subtype, renderFlags, respawnTracked, yWord, spawns.size()));
            cursor += OBJECT_RECORD_SIZE;
        }

        if (sortByX) {
            spawns.sort(Comparator.comparingInt(ObjectSpawn::x));
        }
        return List.copyOf(spawns);
    }
}
