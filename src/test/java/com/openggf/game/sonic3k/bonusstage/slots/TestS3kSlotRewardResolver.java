package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotRewardResolver {

    @Test
    void fixedTargetThresholdsMatchByte4C8B4() {
        assertEquals(0, S3kSlotRewardResolver.pickFixedRow(0x00));
        assertEquals(1, S3kSlotRewardResolver.pickFixedRow(0x04));
        assertEquals(3, S3kSlotRewardResolver.pickFixedRow(0x11));
        assertEquals(6, S3kSlotRewardResolver.pickFixedRow(0x49));
        assertEquals(-1, S3kSlotRewardResolver.pickFixedRow(0x4A));
    }

    @Test
    void fixedTargetThresholdsCoverInteriorValuesInEachBucket() {
        assertAll(
                () -> assertEquals(0, S3kSlotRewardResolver.pickFixedRow(0x02)),
                () -> assertEquals(1, S3kSlotRewardResolver.pickFixedRow(0x08)),
                () -> assertEquals(2, S3kSlotRewardResolver.pickFixedRow(0x0F)),
                () -> assertEquals(3, S3kSlotRewardResolver.pickFixedRow(0x1A)),
                () -> assertEquals(4, S3kSlotRewardResolver.pickFixedRow(0x27)),
                () -> assertEquals(5, S3kSlotRewardResolver.pickFixedRow(0x33)),
                () -> assertEquals(6, S3kSlotRewardResolver.pickFixedRow(0x42))
        );
    }

    @Test
    void pickFixedRowMasksRolledByteToUnsignedRange() {
        assertEquals(S3kSlotRewardResolver.pickFixedRow(0xFF), S3kSlotRewardResolver.pickFixedRow(-1));
        assertEquals(5, S3kSlotRewardResolver.pickFixedRow(-200));
    }

    @Test
    void pickFixedRowReturnsNoMatchBeyondWeightedRange() {
        assertEquals(-1, S3kSlotRewardResolver.pickFixedRow(0x4B));
        assertEquals(-1, S3kSlotRewardResolver.pickFixedRow(0x7F));
    }
}


