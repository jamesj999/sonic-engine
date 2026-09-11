package com.openggf.game;

import com.openggf.game.rules.GameRules;

import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests S3K duck-while-moving feature.
 * <p>ROM ref: sonic3k.asm:23223-23250 (SonicKnux_Roll).
 * S3K allows ducking at speeds below 0x100, where S2 requires standing still.
 * S3K roll threshold is 0x100 (vs S2's 0x80).
 */
class TestS3kMovingCrouch {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
    }

    @AfterEach
    void tearDown() {
        GameModuleRegistry.reset();
    }

    @Test
    void s3k_hasMovingCrouchThreshold() {
        GameRules fs = GameRules.SONIC_3K;
        assertEquals(0x100, fs.playerMovement().movingCrouchThreshold(),
                "S3K moving crouch threshold should be $100");
    }

    @Test
    void s2_noMovingCrouchThreshold() {
        assertEquals(0, GameRules.SONIC_2.playerMovement().movingCrouchThreshold(),
                "S2 should have no moving crouch threshold");
    }

    @Test
    void s1_noMovingCrouchThreshold() {
        assertEquals(0, GameRules.SONIC_1.playerMovement().movingCrouchThreshold(),
                "S1 should have no moving crouch threshold");
    }

    @Test
    void s3k_thresholdHigherThanS2RollSpeed() {
        // S3K roll threshold (0x100) is higher than S2's (0x80)
        assertTrue(GameRules.SONIC_3K.playerMovement().movingCrouchThreshold() > 0x80,
                "S3K threshold should be higher than S2's roll speed");
    }
}


