package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless regression for HCZ Act 1 upward water tunnel pipe.
 *
 * <p>Places Sonic at (12107, 1715) top-left inside the rightmost vertical
 * pipe. The water tunnel handler pulls Sonic upward through the straight
 * section, then the twisting loop captures and guides him through the
 * curved upper path to at least y=1100.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHcz1WaterTunnelUpwardPipe {
    private static final short START_X = 12107;
    private static final short START_Y = 1715;
    private static final int TARGET_Y = 1100;
    private static final int MAX_FRAMES = 180;
    private static final int INITIAL_ART_DRAIN_LIMIT = 4096;

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
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_HCZ, 0);
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
        drainInitialRuntimeArt();
        sprite = (Sonic) fixture.sprite();

        // This fixture teleports directly to a late HCZ route window. In normal
        // play the horizontal geysers in that window have already run their
        // y-guard and retired before Sonic reaches the upward pipe. Dispatch
        // that guard once at the route camera before entering the pipe so the
        // synthetic teleport does not enqueue four historical geyser archives
        // alongside the vertical archive on one frame.
        Camera camera = fixture.camera();
        sprite.setX(START_X);
        sprite.setY(START_Y);
        camera.updatePosition(true);
        camera.setFrozen(true);
        sprite.setCentreY((short) 0x400);
        GameServices.level().getObjectManager().reset(camera.getX());
        fixture.stepFrame(false, false, false, false, false);

        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        // Do NOT zero gSpeed â€” ROM tunnel handler doesn't touch ground_vel.
        // The twisting loop's flipped-entry detection needs gSpeed < 0.
        sprite.setGSpeed((short) 0);
        sprite.setAngle((byte) 0);
        sprite.setAir(true);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setForcedAnimationId(-1);

        camera.setFrozen(false);
        camera.updatePosition(true);
    }

    private static void drainInitialRuntimeArt() {
        int frames = 0;
        while (GameServices.hardwareTiming()
                .incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE) > 0
                && frames++ < INITIAL_ART_DRAIN_LIMIT) {
            serviceArtBoundary(HardwareServiceBoundary.PRE_MAIN_LOOP);
            serviceArtBoundary(HardwareServiceBoundary.POST_OBJECTS);
        }
        if (GameServices.hardwareTiming()
                .incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE) != 0) {
            fail("Initial HCZ runtime art did not drain within "
                    + INITIAL_ART_DRAIN_LIMIT + " hardware frames");
        }
    }

    private static void serviceArtBoundary(HardwareServiceBoundary boundary) {
        HardwareBoundaryPump.service(boundary);
    }

    /**
     * Full scenario: tunnel + twisting loop. Sonic must reach y &lt; 1100
     * within 180 frames.
     */
    @Test
    public void hcz1Pipe_sonicReachesTarget() {
        int minY = START_Y;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            int y = sprite.getY();
            if (y < minY) minY = y;

            if (minY < TARGET_Y) {
                return;
            }

            if (frame == 60 && minY > START_Y - 100) {
                fail("Stuck after 60 frames. minY=" + minY
                        + " y=" + y + " cy=" + sprite.getCentreY()
                        + " ySpd=" + sprite.getYSpeed()
                        + " gSpd=" + sprite.getGSpeed()
                        + " objCtrl=" + sprite.isObjectControlled()
                        + " air=" + sprite.getAir());
            }
        }

        fail("Did not reach y=" + TARGET_Y + " in " + MAX_FRAMES
                + " frames. minY=" + minY + " y=" + sprite.getY()
                + " cy=" + sprite.getCentreY()
                + " ySpd=" + sprite.getYSpeed()
                + " gSpd=" + sprite.getGSpeed()
                + " objCtrl=" + sprite.isObjectControlled());
    }
}
