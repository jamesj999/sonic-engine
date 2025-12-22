package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses Sonic 2 Rev01 ring placement data into {@link RingSpawn} records.
 */
public class Sonic2RingPlacement {
    // Offset index of ring location lists in Rev01.
    public static final int OFF_RINGS_REV01 = 0x0E4300;
    private static final int ACTS_PER_ZONE = 2;
    private static final int RECORD_SIZE = 4;
    private static final int TERMINATOR = 0xFFFF;
    private static final int RING_SPACING = 0x18;

    private final RomByteReader rom;

    public Sonic2RingPlacement(RomByteReader rom) {
        this.rom = rom;
    }

    public List<RingSpawn> load(ZoneAct zoneAct) {
        int pointerIndex = zoneAct.pointerIndex(ACTS_PER_ZONE);
        int listAddr = rom.readPointer16(OFF_RINGS_REV01, pointerIndex);
        if (zoneAct.act() > 0 && isListEmpty(listAddr)) {
            listAddr = rom.readPointer16(OFF_RINGS_REV01, zoneAct.zone() * ACTS_PER_ZONE);
        }

        List<RingSpawn> spawns = new ArrayList<>();
        int cursor = listAddr;
        while (true) {
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
            cursor += RECORD_SIZE;
        }

        spawns.sort(Comparator.comparingInt(RingSpawn::x));
        return List.copyOf(spawns);
    }

    private boolean isListEmpty(int listAddr) {
        return rom.readU16BE(listAddr) == TERMINATOR;
    }
}
