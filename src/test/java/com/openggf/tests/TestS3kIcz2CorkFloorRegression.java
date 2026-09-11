package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Regression for the ICZ2 loop route where Sonic should roll through Obj_CorkFloor.
 */
public class TestS3kIcz2CorkFloorRegression {
    private static final int START_TOP_LEFT_X = 8535;
    private static final int START_TOP_LEFT_Y = 1617;
    private static final int EXPECTED_BROKEN_FALL_Y = 1750;

    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sonic;

    @BeforeAll
    public static void loadLevel() throws Exception {
        java.io.File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null, "S3K ROM not available");
        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        TestEnvironment.configureRomFixture(rom);

        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_ICZ, 1);
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
                .startPosition((short) START_TOP_LEFT_X, (short) START_TOP_LEFT_Y)
                .build();
        sonic = (Sonic) fixture.sprite();
        sonic.setX((short) START_TOP_LEFT_X);
        sonic.setY((short) START_TOP_LEFT_Y);
        sonic.setAir(true);
        sonic.setXSpeed((short) 0);
        sonic.setYSpeed((short) 0);
        sonic.setGSpeed((short) 0);
        sonic.setControlLocked(false);
        sonic.setDirection(com.openggf.physics.Direction.RIGHT);
        GameServices.camera().updatePosition(true);
    }

    @Test
    public void spindashThroughLoopBreaksIcz2CorkFloor() {
        waitUntilGrounded();
        assertFalse(sonic.getAir(), "Sonic should settle onto ICZ2 ground before charging the spindash. " + state());

        chargeAndReleaseSpindash();

        int maxY = sonic.getY();
        for (int frame = 0; frame < 420; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            maxY = Math.max(maxY, sonic.getY());
            if (sonic.getY() >= EXPECTED_BROKEN_FALL_Y) {
                return;
            }
        }

        assertTrue(maxY >= EXPECTED_BROKEN_FALL_Y,
                "Sonic should break the ICZ2 CorkFloor and fall to at least top-left Y="
                        + EXPECTED_BROKEN_FALL_Y + "; maxY=" + maxY + ". " + state());
    }

    private void waitUntilGrounded() {
        for (int frame = 0; frame < 30 && sonic.getAir(); frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
    }

    private void chargeAndReleaseSpindash() {
        fixture.stepFrame(false, true, false, false, false);
        for (int frame = 0; frame < 24; frame++) {
            fixture.stepFrame(false, true, false, false, true);
        }
        fixture.stepFrame(false, false, false, false, false);

        // The fixture has no title-card frames to warm up player input state,
        // so make the released spindash deterministic while preserving the
        // same post-release state used by the ROM: rolling, rightward speed,
        // and rolling radii before the loop carries Sonic into the CorkFloor.
        sonic.setDirection(com.openggf.physics.Direction.RIGHT);
        sonic.setGSpeed((short) 0x0C00);
        sonic.setXSpeed((short) 0x0C00);
        sonic.setYSpeed((short) 0);
        sonic.setRolling(true);
        sonic.setJumping(false);
        sonic.setPushing(false);
        sonic.applyRollingRadii(false);
        sonic.setTopSolidBit((byte) 0x0E);
        sonic.setLrbSolidBit((byte) 0x0F);
    }

    private String state() {
        return "state{x=" + sonic.getX()
                + ", y=" + sonic.getY()
                + ", centreX=" + sonic.getCentreX()
                + ", centreY=" + sonic.getCentreY()
                + ", xSpeed=" + sonic.getXSpeed()
                + ", ySpeed=" + sonic.getYSpeed()
                + ", gSpeed=" + sonic.getGSpeed()
                + ", air=" + sonic.getAir()
                + ", rolling=" + sonic.getRolling()
                + ", anim=" + sonic.getAnimationId()
                + ", topSolid=" + (sonic.getTopSolidBit() & 0xFF)
                + ", nearestCorkFloor=" + nearestCorkFloor()
                + '}';
    }

    private String nearestCorkFloor() {
        ObjectSpawn nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectSpawn spawn : GameServices.level().getObjectManager().getAllSpawns()) {
            if ((spawn.objectId() & 0xFF) != Sonic3kObjectIds.CORK_FLOOR) {
                continue;
            }
            int distance = Math.abs(spawn.x() - sonic.getCentreX()) + Math.abs(spawn.y() - sonic.getCentreY());
            if (distance < nearestDistance) {
                nearest = spawn;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return "none";
        }
        return "x=" + nearest.x()
                + ", y=" + nearest.y()
                + ", subtype=0x" + Integer.toHexString(nearest.subtype() & 0xFF)
                + ", render=0x" + Integer.toHexString(nearest.renderFlags() & 0xFF)
                + ", distance=" + nearestDistance
                + ", active=" + describeActive(nearest);
    }

    private String describeActive(ObjectSpawn spawn) {
        ObjectInstance active = GameServices.level().getObjectManager().getActiveObjectForRewind(spawn);
        if (active == null) {
            return "none";
        }
        return active.getClass().getSimpleName()
                + "{broken=" + field(active, "broken")
                + ", mode=" + field(active, "mode")
                + ", savedPreContactRolling=" + field(active, "savedPreContactRolling")
                + ", playerStanding=" + field(active, "playerStanding")
                + "}";
    }

    private Object field(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }
}
