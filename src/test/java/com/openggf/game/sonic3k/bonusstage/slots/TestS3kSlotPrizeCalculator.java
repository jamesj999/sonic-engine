package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kSlotPrizeCalculator {

    @Test
    void allThreeMatchSymbol0Gives100() {
        assertEquals(100, S3kSlotPrizeCalculator.calculate(0, (byte) 0x00));
    }

    @Test
    void allThreeMatchUnusedSymbol7Gives200() {
        assertEquals(200, S3kSlotPrizeCalculator.calculate(7, (byte) 0x77));
    }

    @Test
    void allThreeMatchSymbol4GivesNegative() {
        assertEquals(-1, S3kSlotPrizeCalculator.calculate(4, (byte) 0x44));
    }

    @Test
    void twoMatchABWithCZeroGivesDouble() {
        assertEquals(60, S3kSlotPrizeCalculator.calculate(1, (byte) 0x10));
    }

    @Test
    void twoMatchACWithBZeroGivesDouble() {
        assertEquals(40, S3kSlotPrizeCalculator.calculate(2, (byte) 0x02));
    }

    @Test
    void twoMatchWithMatchedSymbolZeroGivesQuadruple() {
        assertEquals(100, S3kSlotPrizeCalculator.calculate(0, (byte) 0x03));
    }

    @Test
    void bcMatchWithAZeroGivesDouble() {
        assertEquals(20, S3kSlotPrizeCalculator.calculate(0, (byte) 0x55));
    }

    @Test
    void bcMatchWithBCZeroGivesATimesQuadruple() {
        assertEquals(100, S3kSlotPrizeCalculator.calculate(3, (byte) 0x00));
    }

    @Test
    void noMatchNoSixesGivesZero() {
        assertEquals(0, S3kSlotPrizeCalculator.calculate(1, (byte) 0x23));
    }

    @Test
    void noMatchWithSixesCountsBonus() {
        assertEquals(4, S3kSlotPrizeCalculator.calculate(6, (byte) 0x16));
    }

    @Test
    void allThreeSixesGiveTripleMatchNotSixCount() {
        assertEquals(8, S3kSlotPrizeCalculator.calculate(6, (byte) 0x66));
    }

    @Test
    void twoJackpotsWithSonicGivesQuadrupleSonicReward() {
        assertEquals(120, S3kSlotPrizeCalculator.calculate(0, (byte) 0x01));
    }

    @Test
    void jackpotWithTwoRingsGivesDoubleRingReward() {
        assertEquals(20, S3kSlotPrizeCalculator.calculate(0, (byte) 0x55));
    }

    @Test
    void robotnikWithTwoJackpotsIsStillNegativePrize() {
        assertEquals(-4, S3kSlotPrizeCalculator.calculate(4, (byte) 0x00));
    }
}


