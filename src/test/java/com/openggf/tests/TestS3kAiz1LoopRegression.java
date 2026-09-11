package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.GroundMode;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for the AIZ1 loop before the hollow log.
 *
 * <p>Scenario from the retired 2026-03-25 AIZ working list (now folded into
 * {@code docs/status/known-bugs.md}): teleport Sonic beside the
 * red spring, let the spring launch him into the loop, and verify he exits the
 * loop instead of stalling in the wall and rolling backward.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1LoopRegression {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    private static final short START_X = (short) 10572;
    private static final short START_Y = (short) 1097;
    private static final int PASS_EXIT_X = 11211;
    private static final int SPRING_TRIGGER_WINDOW_FRAMES = 45;
    private static final int LOOP_TIMEOUT_FRAMES = 360;
    private static final int MOMENTUM_LOSS_THRESHOLD = 0x20;

    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;
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

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
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
        }
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        AizHollowTreeObjectInstance.resetTreeRevealCounter();
        GameServices.level().getObjectManager().reset(0);
    }

    @Test
    public void aiz1LoopBeforeHollowLog_exitsLoopInsteadOfStallingInWall() {
        teleportToLoopSpring();

        int springTriggerFrame = runRightUntilSpringLaunches();
        assertTrue(springTriggerFrame >= 0, "Expected the red spring beside Sonic to launch him rightward."
                        + " " + describeState(-1));

        int startX = sprite.getX();
        int maxX = startX;
        int minY = sprite.getY();
        boolean enteredLoopAscent = false;
        int momentumLossFrame = -1;
        int momentumLossX = 0;
        int momentumLossY = 0;
        int momentumLossGSpeed = 0;

        for (int frame = 0; frame < LOOP_TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);

            int x = sprite.getX();
            int y = sprite.getY();
            if (x > maxX) {
                maxX = x;
            }
            if (y < minY) {
                minY = y;
            }
            if (!enteredLoopAscent && (maxX >= 11050 || minY <= START_Y - 64)) {
                enteredLoopAscent = true;
            }

            if (x >= PASS_EXIT_X) {
                return;
            }

            if (enteredLoopAscent
                    && momentumLossFrame < 0
                    && !sprite.getAir()
                    && Math.abs(sprite.getGSpeed()) <= MOMENTUM_LOSS_THRESHOLD) {
                momentumLossFrame = frame;
                momentumLossX = x;
                momentumLossY = y;
                momentumLossGSpeed = sprite.getGSpeed();
                break;
            }
        }

        assertTrue(sprite.getX() >= PASS_EXIT_X && momentumLossFrame < 0, "Expected Sonic to clear the AIZ1 loop and exit beyond X=" + PASS_EXIT_X
                        + " after spring launch, but loop momentum collapsed first."
                        + " springTriggerFrame=" + springTriggerFrame
                        + " maxX=" + maxX
                        + " minY=" + minY
                        + " enteredLoopAscent=" + enteredLoopAscent
                        + " momentumLossFrame=" + momentumLossFrame
                        + " momentumLossX=" + momentumLossX
                        + " momentumLossY=" + momentumLossY
                        + " momentumLossGSpeed=" + momentumLossGSpeed
                        + " momentumLossThreshold=" + MOMENTUM_LOSS_THRESHOLD
                        + " " + describeState(fixture.frameCount()));
    }

    private void teleportToLoopSpring() {
        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAngle((byte) 0);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        sprite.updateSensors(sprite.getX(), sprite.getY());

        // Rebuild the object spawn window around the teleported camera so the
        // nearby spring is active immediately.
        GameServices.level().getObjectManager().reset(camera.getX());
    }

    private int runRightUntilSpringLaunches() {
        for (int frame = 0; frame < SPRING_TRIGGER_WINDOW_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false);
            if (sprite.getXSpeed() > 0x100 || sprite.getGSpeed() > 0x100 || sprite.getX() > START_X + 8) {
                return frame;
            }
        }
        return -1;
    }

    private String describeState(int frame) {
        return "frame=" + frame
                + " x=" + sprite.getX()
                + " y=" + sprite.getY()
                + " xSub=" + (sprite.getXSubpixel() & 0xFF)
                + " ySub=" + (sprite.getYSubpixel() & 0xFF)
                + " xSpeed=" + sprite.getXSpeed()
                + " ySpeed=" + sprite.getYSpeed()
                + " gSpeed=" + sprite.getGSpeed()
                + " angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF)
                + " groundMode=" + sprite.getGroundMode()
                + " air=" + sprite.getAir()
                + " rolling=" + sprite.getRolling();
    }
}


