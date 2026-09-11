package com.openggf.game.sonic3k;

import com.openggf.data.RomByteReader;
import com.openggf.level.rings.RingSpawn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic3kRingPlacement {

    @Test
    void parsesRawFourByteRingRecordsWithoutS2RowExpansion() {
        byte[] rom = new byte[0x20];
        writeWord(rom, 0x00, 0x0120);
        writeWord(rom, 0x02, 0x3240);
        writeWord(rom, 0x04, 0x0100);
        writeWord(rom, 0x06, 0xA260);
        writeWord(rom, 0x08, 0xFFFF);

        List<RingSpawn> rings = Sonic3kRingPlacement.parseRawRingRecords(new RomByteReader(rom), 0);

        assertEquals(List.of(
                new RingSpawn(0x0100, 0xA260),
                new RingSpawn(0x0120, 0x3240)
        ), rings);
    }

    private static void writeWord(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >>> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }
}
