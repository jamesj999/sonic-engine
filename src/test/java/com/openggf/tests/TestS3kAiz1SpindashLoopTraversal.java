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

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1SpindashLoopTraversal {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;
    private static final short START_X = (short) 8561;
    private static final short START_Y = (short) 1093;
    private static final int PASS_X = 9029;
    private static final int TIMEOUT_FRAMES = 180;
    private static final short SPINDASH_GSPEED = 0x800;

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
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        AizHollowTreeObjectInstance.resetTreeRevealCounter();
        GameServices.level().getObjectManager().reset(0);
    }

    @Test
    public void aiz1SpindashLoop_traversesLoopWithin180Frames() {
        teleportToStart();
        assertTrue(!sprite.getAir(), "Sonic should be grounded after teleport");

        for (int frame = 0; frame < TIMEOUT_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sprite.getX() >= PASS_X) return;
        }

        assertTrue(sprite.getX() >= PASS_X, "Expected Sonic to pass X=" + PASS_X + " within " + TIMEOUT_FRAMES
                + " frames. " + describeState(TIMEOUT_FRAMES));
    }

    private void teleportToStart() {
        sprite.setX(START_X);
        sprite.setY(START_Y);
        sprite.setXSpeed(SPINDASH_GSPEED);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed(SPINDASH_GSPEED);
        sprite.setAngle((byte) 0);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setAir(false);
        sprite.setRolling(true);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);
        sprite.setObjectMappingFrameControl(false);
        sprite.setForcedAnimationId(-1);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
        sprite.updateSensors(sprite.getX(), sprite.getY());
        GameServices.collision().resolveGroundAttachment(sprite, 14, () -> false);
        sprite.setAir(false);
        GameServices.level().getObjectManager().reset(camera.getX());
    }

    private String describeState(int frame) {
        return "frame=" + frame + " x=" + sprite.getX() + " y=" + sprite.getY()
                + " gSpeed=" + sprite.getGSpeed() + " air=" + sprite.getAir()
                + " rolling=" + sprite.getRolling()
                + " angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF);
    }
}

