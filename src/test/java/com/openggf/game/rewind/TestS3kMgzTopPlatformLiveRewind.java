package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live-rewind reproduction for the MGZ spinning top (Obj 0x5B): capture a
 * keyframe while the player is grabbed mid-ride, keep playing, then restore
 * the keyframe the way {@code LiveRewindManager} does and verify the ride
 * resumes coherently.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMgzTopPlatformLiveRewind {
    // Same debug-overlay repro coordinates as TestS3kMgzTopPlatformParityHeadless.
    private static final int START_PIXEL_X = 10612;
    private static final int START_PIXEL_Y = 2036;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 0);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        sprite.setCentreX((short) (START_PIXEL_X + (sprite.getWidth() / 2)));
        sprite.setCentreY((short) (START_PIXEL_Y + (sprite.getHeight() / 2)));
        sprite.setPushing(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setDirection(com.openggf.physics.Direction.LEFT);
        sprite.clearWallClingState();
        fixture.camera().updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
        fixture.stepIdleFrames(1);
    }

    @Test
    void rewindRestoreMidRideKeepsCarryCoherentAndRideResumes() {
        MGZTopPlatformObjectInstance platform = runUntilGrabbedHoldingLeft();
        assertNotNull(platform, "Expected Sonic to grab the MGZ top platform");

        // Ride for a while so the platform is moving with the player attached.
        for (int i = 0; i < 30; i++) {
            fixture.stepFrame(false, false, true, false, false);
        }
        assertTrue(sprite.isObjectControlled(), "Should still be carried before capture");
        assertNotNull(findGrabbedPlatform(), "Platform should still hold the grab before capture");

        RewindRegistry registry = fixture.gameplayMode().getRewindRegistry();
        assertNotNull(registry, "GameplayModeContext rewind registry must exist");
        CompositeSnapshot midRide = registry.capture();
        int capturedPlayerX = sprite.getCentreX();
        int capturedPlayerY = sprite.getCentreY();

        // Keep playing forward (still riding).
        for (int i = 0; i < 60; i++) {
            fixture.stepFrame(false, false, true, false, false);
        }

        // Rewind back to the mid-ride keyframe.
        registry.restore(midRide);

        assertEquals(capturedPlayerX, sprite.getCentreX(), "restore must return player X");
        assertEquals(capturedPlayerY, sprite.getCentreY(), "restore must return player Y");
        assertTrue(sprite.isObjectControlled(), "restored player must still be object-controlled");
        assertTrue(sprite.isWallCling(), "restored player must still be wall-cling carried");

        MGZTopPlatformObjectInstance restoredPlatform = findGrabbedPlatform();
        assertNotNull(restoredPlatform, "restored platform must still hold the grab state");
        assertTrue(sprite.isMgzTopPlatformCarryOwnedBy(restoredPlatform),
                "player carry ownership must point at the restored platform instance");

        // Resume the ride: the player must keep tracking the platform.
        for (int i = 0; i < 30; i++) {
            fixture.stepFrame(false, false, true, false, false);
            if (!sprite.isObjectControlled()) {
                break;
            }
        }
        MGZTopPlatformObjectInstance after = findGrabbedPlatform();
        assertTrue(sprite.isObjectControlled(),
                "player must still be carried by the top after rewind restore + resume");
        assertNotNull(after, "platform must still hold the grab after rewind restore + resume");
        assertEquals(after.getX(), sprite.getCentreX(),
                "carried player must stay snapped to the platform X after rewind restore");
    }

    private MGZTopPlatformObjectInstance runUntilGrabbedHoldingLeft() {
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, true, false, false);
            MGZTopPlatformObjectInstance platform = findGrabbedPlatform();
            if (platform != null) {
                return platform;
            }
        }
        return null;
    }

    private MGZTopPlatformObjectInstance findGrabbedPlatform() {
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform && platform.isPlayerGrabbed(sprite)) {
                return platform;
            }
        }
        return null;
    }
}
