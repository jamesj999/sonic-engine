package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotRomData {

    @Test
    void rewardAndTargetTablesMatchDisassembly() {
        assertArrayEquals(new short[] {100, 30, 20, 25, -1, 10, 8, 200}, S3kSlotRomData.REWARD_VALUES);
        assertArrayEquals(new byte[] {
                4, 0, 0,
                9, 1, 0x11,
                4, 3, 0x33,
                0x12, 4, 0x44,
                9, 2, 0x22,
                0x0F, 5, 0x55,
                0x0F, 6, 0x66,
                (byte) 0xFF, 0x0F, (byte) 0xFF
        }, S3kSlotRomData.TARGET_ROWS);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 5, 3}, S3kSlotRomData.REEL_SEQUENCE_A);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 1, 3}, S3kSlotRomData.REEL_SEQUENCE_B);
        assertArrayEquals(new byte[] {0, 1, 2, 5, 4, 6, 3, 5}, S3kSlotRomData.REEL_SEQUENCE_C);
    }
}


