package com.openggf.sprites.animation;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TestAnimationTranslator {

    private SpriteAnimationSet buildAnimSet(int... scriptIds) {
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (int id : scriptIds) {
            set.addScript(id, new SpriteAnimationScript(1, List.of(0),
                    SpriteAnimationEndAction.LOOP, 0));
        }
        return set;
    }

    private ScriptedVelocityAnimationProfile buildS1Profile() {
        return new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(Sonic1AnimationIds.WAIT)
                .setWalkAnimId(Sonic1AnimationIds.WALK)
                .setRunAnimId(Sonic1AnimationIds.RUN)
                .setRollAnimId(Sonic1AnimationIds.ROLL)
                .setAirAnimId(Sonic1AnimationIds.WALK)
                .setSpringAnimId(Sonic1AnimationIds.SPRING)
                .setDeathAnimId(Sonic1AnimationIds.DEATH)
                .setHurtAnimId(Sonic1AnimationIds.HURT)
                .setDrownAnimId(Sonic1AnimationIds.DROWN)
                .setDuckAnimId(Sonic1AnimationIds.DUCK)
                .setLookUpAnimId(Sonic1AnimationIds.LOOK_UP)
                .setPushAnimId(Sonic1AnimationIds.PUSH)
                .setBalanceAnimId(Sonic1AnimationIds.BALANCE)
                .setAnglePreAdjust(false)
                .setCompactSuperRunSlope(false)
                .setRunSpeedThreshold(0x600);
    }

    private DonorCapabilities buildS1Donor() {
        return new com.openggf.game.sonic1.Sonic1GameModule().getDonorCapabilities();
    }

    @Test
    void translatedProfilePreservesNonAnimationProperties() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertFalse(translated.isAnglePreAdjust());
        assertFalse(translated.isCompactSuperRunSlope());
        assertFalse(translated.isWalkRunPublishesFrameBeforeTimerAdvance());
        assertEquals(0x600, translated.getRunSpeedThreshold());
    }

    @Test
    void translatedProfilePreservesWalkRunPublicationOrder() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile()
                .setWalkRunPublishesFrameBeforeTimerAdvance(true);
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertTrue(translated.isWalkRunPublishesFrameBeforeTimerAdvance());
    }

    @Test
    void translatedProfilePreservesWalkRunTierData() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile()
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700)
                .setWalkSlopeFrameStride(4)
                .setRunSlopeFrameStride(3)
                .setHighSpeedSlopeFrameStride(3)
                .setDoubleWalkRunAnimationSpeedWhenSliding(true);
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A, 0x1F);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertEquals(0x1F, translated.getHighSpeedWalkRunAnimId());
        assertEquals(0x700, translated.getHighSpeedWalkRunThreshold());
        assertEquals(4, translated.getWalkSlopeFrameStride());
        assertEquals(3, translated.getRunSlopeFrameStride());
        assertEquals(3, translated.getHighSpeedSlopeFrameStride());
        assertTrue(translated.isDoubleWalkRunAnimationSpeedWhenSliding());
    }

    @Test
    void translatedProfileDisablesMissingPrivateHighSpeedScript() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile()
                .setHighSpeedWalkRunAnimId(0x1F)
                .setHighSpeedWalkRunThreshold(0x700);
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertEquals(-1, translated.getHighSpeedWalkRunAnimId());
    }

    @Test
    void translatedProfilePreservesRawWalkIdForRunFrames() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile()
                .setRunFramesUseWalkAnimationId(true);
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertTrue(translated.isRunFramesUseWalkAnimationId(),
                "donor translation must retain the ROM raw-Walk/rendered-Run split");
    }

    @Test
    void translatedProfileHasValidScriptsForCoreAnimations() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertNotNull(donorSet.getScript(translated.getIdleAnimId()));
        assertNotNull(donorSet.getScript(translated.getWalkAnimId()));
        assertNotNull(donorSet.getScript(translated.getRunAnimId()));
        assertNotNull(donorSet.getScript(translated.getRollAnimId()));
    }

    @Test
    void spindashFallsToDuckForS1Donor() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        // S1 fallback: SPINDASH -> DUCK. S1 DUCK = 0x08.
        assertEquals(Sonic1AnimationIds.DUCK.id(), translated.getSpindashAnimId());
        assertNotNull(donorSet.getScript(translated.getSpindashAnimId()));
    }

    @Test
    void airAnimIdAlwaysMapsToWalk() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 5, 8, 0x10, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertEquals(translated.getWalkAnimId(), translated.getAirAnimId());
    }

    @Test
    void missingFallbackScriptDisablesAnimation() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        // Minimal set WITHOUT 0x0D (STOP). S1 fallback: SKID -> STOP.
        // Since STOP script is missing, skid should be -1.
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 5, 8);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertEquals(-1, translated.getSkidAnimId());
    }

    @Test
    void absentFromFallbackMapFallsToWait() {
        // Create a donor with an incomplete fallback map (missing some entries)
        // The translator should fall back to WAIT for anything not in the map
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 5, 8);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        // WAIT (0x05) is in the set, so idle should resolve to it
        assertEquals(Sonic1AnimationIds.WAIT.id(), translated.getIdleAnimId());
    }
}
