package com.openggf.game;

import com.openggf.game.rules.GameRules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins down the {@code stageRingsUseObjectTouchCollection} rule on
 * {@link GameRules}. Sonic 1 routes stage-ring collection through the
 * object-touch-response pipeline (ROM: Obj25 Ring / Touch_Rings). Sonic 2 and
 * Sonic 3&amp;K collect stage rings via the bounding-box sweep in RingManager
 * (ROM: Touch_Rings_Test). This lets RingManager branch on the rule
 * instead of checking {@code GameId} directly, matching the project's rule
 * that game differences must be gated by rules and not by game-name
 * if/else chains.
 */
public class TestStageRingsTouchCollectionRule {

    @Test
    public void sonic1EnablesStageRingObjectTouchCollection() {
        assertTrue(GameRules.SONIC_1.ring().stageRingsUseObjectTouchCollection(),
                "S1 stage rings must use the object-touch pipeline (ROM: Touch_Rings via Obj25)");
    }

    @Test
    public void sonic2DisablesStageRingObjectTouchCollection() {
        assertFalse(GameRules.SONIC_2.ring().stageRingsUseObjectTouchCollection(),
                "S2 stage rings must use the bounding-box sweep (ROM: Touch_Rings_Test)");
    }

    @Test
    public void sonic3kDisablesStageRingObjectTouchCollection() {
        assertFalse(GameRules.SONIC_3K.ring().stageRingsUseObjectTouchCollection(),
                "S3K stage rings must use the bounding-box sweep (ROM: Touch_Rings_Test)");
    }
}
