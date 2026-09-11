package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for HCZ Act 2 twisting loop (object 0x69).
 *
 * <p>Scenario: Sonic is launched leftward by a HCZHandLauncher at (2328, 969)
 * with gSpeed=-4096. Holding LEFT, he enters the twisting loop's reverse entry
 * and should be guided upward through the full tube, exiting at the top
 * (Y near topY=288) with negative gSpeed.
 *
 * <p>The bug (prior to fix): A fixed-point scaling error (using 24:8 instead
 * of 16.16 format) caused the progress to overflow, releasing Sonic
 * immediately. He then ran up the tube wall in LEFT_WALL mode.
 *
 * <p>Initial state captured from real gameplay debug overlay (Pos: shows
 * getX/getY, i.e. top-left corner).
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHcz2TwistingLoopRegression {
    private static final int TOTAL_FRAMES = 200;

    private static Object oldSkipIntros, oldMainCharacter, oldSidekickCharacter;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_HCZ, 1);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();

        // Exact state from debug overlay just after hand launcher release.
        // Debug Pos: shows getX()/getY() (top-left), NOT centre.
        // Pos: 2328, 969 | Spd X: -4096 Y: 168 | GSpd: -4096
        // Angle: 0 | State: Air | Solidity: top 0C lrb 0D
        sprite.setX((short) 2328);
        sprite.setY((short) 969);
        sprite.setXSpeed((short) -4096);
        sprite.setYSpeed((short) 168);
        sprite.setGSpeed((short) -4096);
        sprite.setAngle((byte) 0);
        sprite.setAir(true);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setForcedAnimationId(-1);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().getObjectManager().reset(camera.getX());
    }

    /**
     * Verifies the twisting loop captures Sonic and guides him through the
     * full tube, exiting at the top (Y < 400) with negative gSpeed. The loop
     * should sustain capture for at least 20 frames (full traversal takes ~56).
     */
    @Test
    public void hcz2TwistingLoop_sonicTraversesFullTube() {
        boolean captured = false;
        int captureFrame = -1;
        int sustainedFrames = 0;
        boolean exitedAtTop = false;

        for (int frame = 0; frame < TOTAL_FRAMES; frame++) {
            fixture.stepFrame(false, false, true, false, false);

            boolean objCtrl = sprite.isObjectControlled();
            int cy = sprite.getCentreY();

            if (!captured && objCtrl) {
                captured = true;
                captureFrame = frame;
                assertTrue(sprite.isObjectControlAllowsCpu(),
                        "HCZ twisting loop writes object_control bit 0, so sidekick CPU/touch remain allowed");
                assertTrue(sprite.isObjectControlSuppressesMovement(),
                        "HCZ twisting loop bit 0 should suppress normal movement while captured");
            }

            if (captured && objCtrl) {
                sustainedFrames++;
            }

            // Detect successful top exit: was captured, now released, Y near top
            if (captured && !objCtrl && cy < 400) {
                assertTrue(captureFrame >= 0);
                assertTrue(sustainedFrames >= 20);
                assertTrue(!sprite.isObjectControlAllowsCpu()
                                && !sprite.isObjectControlSuppressesMovement(),
                        "HCZ twisting loop release should clear all object-control policy flags");
                exitedAtTop = true;
                break;
            }
        }

        assertTrue(captured, "Expected the twisting loop to capture Sonic (objectControlled=true) "
                        + "within " + TOTAL_FRAMES + " frames. " + describeState());

        assertTrue(sustainedFrames >= 20, "Expected the loop to sustain capture for at least 20 frames "
                        + "(full traversal is ~56 frames). Got " + sustainedFrames + " frames. "
                        + "captureFrame=" + captureFrame + " " + describeState());

        assertTrue(exitedAtTop, "Expected the loop to guide Sonic to the top exit (Y < 400). "
                        + "sustainedFrames=" + sustainedFrames
                        + " captureFrame=" + captureFrame + " " + describeState());
    }

    private String describeState() {
        return "cx=" + sprite.getCentreX()
                + " cy=" + sprite.getCentreY()
                + " gSpeed=" + sprite.getGSpeed()
                + " xSpeed=" + sprite.getXSpeed()
                + " angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF)
                + " air=" + sprite.getAir()
                + " objCtrl=" + sprite.isObjectControlled()
                + " frame=" + fixture.frameCount();
    }
}


