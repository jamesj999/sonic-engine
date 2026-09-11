package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic3kDecodingUtils {

    @Test
    public void decodeLayoutRowOffsetUsesRamBase() {
        assertEquals(0x0088, Sonic3kLevel.decodeLayoutRowOffset(0x8088));
        assertEquals(0x0FFF, Sonic3kLevel.decodeLayoutRowOffset(0x8FFF));
        assertEquals(-1, Sonic3kLevel.decodeLayoutRowOffset(0x0000));
    }

    @Test
    public void readCollisionIndexUsesStrideTwo() {
        // Table as seen by the engine after +1 pointer alignment:
        // chunk0 -> byte0, chunk1 -> byte2, chunk2 -> byte4
        byte[] table = new byte[]{
                0x10, 0x00,
                0x20, 0x00,
                0x30, 0x00
        };

        assertEquals(0x10, Sonic3kLevel.readCollisionIndex(table, 0));
        assertEquals(0x20, Sonic3kLevel.readCollisionIndex(table, 1));
        assertEquals(0x30, Sonic3kLevel.readCollisionIndex(table, 2));
        assertEquals(0x00, Sonic3kLevel.readCollisionIndex(table, 3));
    }

    @Test
    public void decodeCollisionPointerHonorsMarkers() {
        Sonic3k.CollisionAddressInfo interleaved = Sonic3k.decodeCollisionPointer(0x00123456);
        assertTrue(interleaved.interleaved());
        assertEquals(0x00123456, interleaved.primaryAddress());
        assertEquals(0x00123457, interleaved.secondaryAddress());

        Sonic3k.CollisionAddressInfo nonInterleavedLowBit = Sonic3k.decodeCollisionPointer(0x0027E5A7);
        assertFalse(nonInterleavedLowBit.interleaved());
        assertEquals(0x0027E5A7, nonInterleavedLowBit.primaryAddress());
        assertEquals(0x0027EBA7, nonInterleavedLowBit.secondaryAddress());

        Sonic3k.CollisionAddressInfo nonInterleavedHighBit = Sonic3k.decodeCollisionPointer(0x80123456);
        assertFalse(nonInterleavedHighBit.interleaved());
        assertEquals(0x00123456, nonInterleavedHighBit.primaryAddress());
        assertEquals(0x00123A56, nonInterleavedHighBit.secondaryAddress());
    }
}


