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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for the MGZ spindash route into the twisting loop.
 *
 * <p>Repro from debug overlay coordinates (top-left):
 * Sonic starts at X=2337, Y=555 in MGZ Act 2, lands, charges a 5-tap spindash,
 * then enters the twisting loop. The current bug ejects him around Y~=750,
 * while ROM behavior keeps him attached until roughly Y~=800.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzTwistingLoopSpindashRouteRegression {
    private static final int START_X = 2337;
    private static final int START_Y = 555;
    private static final int LOOP_CENTER_X = 2432;
    private static final int MAX_WAIT_FOR_GROUND = 120;
    private static final int MAX_WAIT_FOR_LOOP_CAPTURE = 180;
    private static final int MAX_WAIT_FOR_LOOP_RELEASE = 180;
    private static final int MIN_EXPECTED_RELEASE_Y = 775;
    private static final int MAX_EXPECTED_RELEASE_Y = 810;
    private static final int MIN_EXPECTED_RELEASE_CENTER_X = LOOP_CENTER_X + 6;
    private static final int MIN_EXPECTED_FIRST_FREE_FRAME_Y = 790;
    private static final int MAX_POST_RELEASE_UPWARD_SNAP = 8;
    private static final int MIN_POST_RELEASE_Y_SPEED = -0x400;
    private static final int POST_RELEASE_GROUNDED_FRAMES = 4;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

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
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 1);
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
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        teleportToStart();
    }

    @Test
    public void mgzSpindashRoute_staysOnTwistingLoopUntilAboutY800() {
        boolean grounded = waitUntilGrounded();
        assertTrue(grounded, "Expected Sonic to land before charging the spindash. " + describeState());

        performFiveChargeSpindash();

        boolean captured = waitUntilLoopCapture();
        assertTrue(captured,
                "Expected Sonic to reach and enter the MGZ twisting loop after the spindash route. " + describeState());

        ReleaseObservation release = waitUntilLoopRelease();
        assertFalse(release.topLeftY < 0,
                "Expected Sonic to leave the twisting loop during the observation window. " + describeState());
        assertTrue(release.topLeftY >= MIN_EXPECTED_RELEASE_Y,
                "Sonic left the twisting loop too early at topLeftY=" + release.topLeftY
                        + " (expected around 800). " + describeState());
        assertTrue(release.topLeftY <= MAX_EXPECTED_RELEASE_Y,
                "Sonic left the twisting loop too late at topLeftY=" + release.topLeftY
                        + " (expected around 800). " + describeState());
        assertTrue(release.centreX >= MIN_EXPECTED_RELEASE_CENTER_X,
                "Sonic left the twisting loop too close to its centre at centreX=" + release.centreX
                        + " (loop centreX=" + LOOP_CENTER_X + "). " + describeState());

        PostReleaseObservation[] postReleaseFrames = observeFreeFrames(POST_RELEASE_GROUNDED_FRAMES);
        PostReleaseObservation firstFreeFrame = postReleaseFrames[0];
        assertTrue(firstFreeFrame.topLeftY >= MIN_EXPECTED_FIRST_FREE_FRAME_Y,
                "Sonic still started the free run too early after the twisting loop: firstFreeFrameY="
                        + firstFreeFrame.topLeftY + ". " + describeState());
        assertTrue(firstFreeFrame.topLeftY <= MAX_EXPECTED_RELEASE_Y,
                "Sonic started the free run too late after the twisting loop: firstFreeFrameY="
                        + firstFreeFrame.topLeftY + ". " + describeState());
        for (int i = 0; i < postReleaseFrames.length; i++) {
            PostReleaseObservation postRelease = postReleaseFrames[i];
            assertFalse(postRelease.air,
                    "Sonic should stay grounded after leaving the twisting loop (free frame " + i + "). "
                            + describeState());
            assertTrue(postRelease.topLeftY >= release.topLeftY - MAX_POST_RELEASE_UPWARD_SNAP,
                    "Sonic snapped upward after leaving the twisting loop on free frame " + i
                            + ": releaseY=" + release.topLeftY + ", postReleaseY=" + postRelease.topLeftY
                            + ". " + describeState());
            assertTrue(postRelease.ySpeed >= MIN_POST_RELEASE_Y_SPEED,
                    "Sonic gained an incorrect upward ySpeed after the twisting loop on free frame " + i
                            + ": ySpeed=" + postRelease.ySpeed + ". " + describeState());
        }
    }

    private void teleportToStart() {
        sprite.setX((short) START_X);
        sprite.setY((short) START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setPushing(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        GameServices.level().getObjectManager().reset(camera.getX());
        fixture.stepIdleFrames(1);
    }

    private boolean waitUntilGrounded() {
        for (int frame = 0; frame < MAX_WAIT_FOR_GROUND; frame++) {
            if (!sprite.getAir()) {
                return true;
            }
            fixture.stepFrame(false, false, false, false, false);
        }
        return !sprite.getAir();
    }

    private void performFiveChargeSpindash() {
        fixture.stepFrame(false, true, false, false, false); // first grounded frame: hold down
        for (int i = 0; i < 5; i++) {
            fixture.stepFrame(false, true, false, false, true);  // press jump
            fixture.stepFrame(false, true, false, false, false); // release jump
        }
        fixture.stepFrame(false, false, false, false, false); // release down
    }

    private boolean waitUntilLoopCapture() {
        for (int frame = 0; frame < MAX_WAIT_FOR_LOOP_CAPTURE; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sprite.isObjectControlled()) {
                return true;
            }
        }
        return false;
    }

    private ReleaseObservation waitUntilLoopRelease() {
        for (int frame = 0; frame < MAX_WAIT_FOR_LOOP_RELEASE; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (!sprite.isObjectControlled()) {
                return new ReleaseObservation(sprite.getY(), sprite.getCentreX());
            }
        }
        return new ReleaseObservation(-1, -1);
    }

    private PostReleaseObservation[] observeFreeFrames(int frames) {
        PostReleaseObservation[] observations = new PostReleaseObservation[frames];
        for (int i = 0; i < frames; i++) {
            fixture.stepFrame(false, false, false, false, false);
            observations[i] = new PostReleaseObservation(sprite.getY(), sprite.getYSpeed(), sprite.getAir());
        }
        return observations;
    }

    private String describeState() {
        return "frame=" + fixture.frameCount()
                + " x=" + sprite.getX()
                + " y=" + sprite.getY()
                + " cx=" + sprite.getCentreX()
                + " cy=" + sprite.getCentreY()
                + " gSpeed=" + sprite.getGSpeed()
                + " xSpeed=" + sprite.getXSpeed()
                + " ySpeed=" + sprite.getYSpeed()
                + " angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF)
                + " mode=" + sprite.getGroundMode()
                + " air=" + sprite.getAir()
                + " rolling=" + sprite.getRolling()
                + " objCtrl=" + sprite.isObjectControlled();
    }

    private record ReleaseObservation(int topLeftY, int centreX) {
    }

    private record PostReleaseObservation(int topLeftY, int ySpeed, boolean air) {
    }
}
