package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that HCZ Act 1 starts with the correct falling intro state.
 * ROM: sonic3k.asm SpawnLevelMainSprites loc_6834.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHcz1IntroState {
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic player;
    private HeadlessTestRunner runner;

    @BeforeAll
    public static void loadHcz1ViaProductionPath() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_HCZ, 0);
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        assertTrue(fixture.sprite() instanceof Sonic, "Player sprite should be Sonic");
        player = (Sonic) fixture.sprite();
        runner = fixture.runner();
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        } else {
            TestEnvironment.resetAll();
        }
    }

    @Test
    public void playerStartsAirborne() {
        assertTrue(player.getAir(), "HCZ1 player should start airborne (Status_InAir set)");
    }

    @Test
    public void playerHasFallingAnimation() {
        assertEquals(Sonic3kAnimationIds.HURT_FALL.id(), player.getForcedAnimationId(),
                "HCZ1 player should have HURT_FALL forced animation (0x1B)");
    }

    @Test
    public void cameraFastScrollCapIs24ForS3k() {
        assertEquals(24, GameServices.camera().getFastScrollCap(),
                "S3K camera fast scroll cap should be 24 (0x18)");
    }

    @Test
    public void fallingStatePersistsAfterFirstFrames() {
        for (int frame = 0; frame < 5; frame++) {
            runner.stepFrame(false, false, false, false, false);

            assertTrue(player.getAir(), "Player should still be airborne at frame " + frame
                    + " (y=" + player.getCentreY() + ")");
            assertEquals(Sonic3kAnimationIds.HURT_FALL.id(), player.getForcedAnimationId(),
                    "Forced animation should still be HURT_FALL at frame " + frame);
        }
    }

    @Test
    public void animationIdMatchesHurtFallAfterFrames() {
        runner.stepFrame(false, false, false, false, false);
        assertEquals(Sonic3kAnimationIds.HURT_FALL.id(), player.getAnimationId(),
                "Animation ID should be HURT_FALL (0x1B) after first frame");
    }

    @Test
    public void hurtFallProducesTumbleMappingFrames() {
        runner.stepFrame(false, false, false, false, false);
        int mappingFrame = player.getMappingFrame();
        assertTrue(mappingFrame == 0x8C || mappingFrame == 0x8D,
                "HURT_FALL mapping frame should be 0x8C or 0x8D (tumble frames), got 0x"
                        + Integer.toHexString(mappingFrame));
    }

    @Test
    public void firstTerrainLandingReleasesIntroAnimationImmediately() {
        int frames = 0;
        while (player.getAir() && frames++ < 180) {
            runner.stepFrame(false, false, false, false, false);
        }

        assertFalse(player.getAir(), "HCZ1 player should reach the intro floor");
        assertEquals(-1, player.getForcedAnimationId(),
                "Player_TouchFloor_Check_Spindash releases the intro animation on the landing tick");
        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId(),
                "the landing routine publishes anim=Walk before the same-frame animator");
    }

    @Test
    public void animationSetContainsHurtFallScript() {
        var animSet = player.getAnimationSet();
        assertNotNull(animSet, "Player should have an animation set");
        var script = animSet.getScript(Sonic3kAnimationIds.HURT_FALL.id());
        assertNotNull(script, "Animation set should have script for HURT_FALL (0x1B)");
        assertFalse(script.frames().isEmpty(), "HURT_FALL script should have frames");
        assertEquals(0x8C, (int) script.frames().get(0), "HURT_FALL first frame should be 0x8C");
    }
}
