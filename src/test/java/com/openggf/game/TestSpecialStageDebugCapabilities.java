package com.openggf.game;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSpecialStageDebugCapabilities {
    @Test
    void sonic1AdvertisesOnlyItsWorkingGameplayDebugMovement() {
        assertEquals(
                new SpecialStageDebugCapabilities(true, false, false, false, false, false, false),
                new Sonic1SpecialStageProvider().debugCapabilities());
    }

    @Test
    void sonic2AdvertisesSpritePlaneAndAlignmentToolsWithoutALiveLagModel() {
        assertEquals(
                new SpecialStageDebugCapabilities(false, false, false, true, true, true, false),
                new Sonic2SpecialStageProvider().debugCapabilities());
    }

    @Test
    void sonic3kKeepsStageAndLayoutNavigationWithoutClaimingMissingViewers() {
        assertEquals(
                new SpecialStageDebugCapabilities(false, true, true, false, false, false, false),
                new Sonic3kSpecialStageProvider().debugCapabilities());
    }

    @Test
    void nullCapabilityResponsesNormalizeToNone() {
        assertSame(SpecialStageDebugCapabilities.NONE,
                SpecialStageDebugCapabilities.orNone(null));
    }
}
