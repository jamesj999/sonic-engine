package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGameRngGoldenSequence {

    @Test
    void s3kDefaultSeedStepMatchesRandomNumber() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        assertEquals(0x8010, rng.nextWord());
        assertEquals(0x8010B493L, rng.getSeed());
    }

    @Test
    void s1S2DefaultSeedStepUsesFullLongZeroCheckVariant() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);

        assertEquals(0x7FE7, rng.nextWord());
        assertEquals(0x7FE7B46AL, rng.getSeed());
    }

    @Test
    void s3kReseedsWhenLowWordIsZero() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 0x12340000L);

        assertEquals(0x8010, rng.nextWord());
        assertEquals(0x8010B493L, rng.getSeed());
    }

    @Test
    void s1S2DoesNotReseedNonZeroLongWithZeroLowWord() {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 0x12340000L);

        assertEquals(0xEA54, rng.nextWord());
        assertEquals(0xEA540000L, rng.getSeed());
    }

    @Test
    void flavourMetadataDocumentsRomVariants() {
        assertEquals(0x2A6D365AL, GameRng.Flavour.S1_S2.reseedValue());
        assertFalse(GameRng.Flavour.S1_S2.lowWordZeroCheck());
        assertEquals(0x2A6D365BL, GameRng.Flavour.S3K.reseedValue());
        assertTrue(GameRng.Flavour.S3K.lowWordZeroCheck());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GameRng(null));
        assertThrows(IllegalArgumentException.class, () -> new GameRng(GameRng.Flavour.S3K).nextInt(0));
    }
}


