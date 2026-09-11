package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless regression for HCZ Act 2 spindash getting stuck at X~7499.
 *
 * <p>Scenario: Sonic is placed at (7292, 2758) top-left coordinates in HCZ2.
 * After landing, he charges and releases a spindash to the right. He should
 * roll freely past X=7499 without gSpeed dropping to zero.
 *
 * <p>The bug: Sonic stops at X=7499 with gSpeed=0, suggesting either a
 * terrain wall collision or solid object side collision is incorrectly
 * zeroing his ground speed while rolling.
 */
public class TestS3kHcz2SpindashStuckRegression {

    private static final int ROLL_FRAMES = 120;

    private static Object oldSkipIntros, oldMainCharacter, oldSidekickCharacter;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeAll
    public static void loadLevel() throws Exception {
        // Load ROM and configure engine services before SharedLevel.load() needs them
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
                .startPosition((short) 7292, (short) 2758)
                .build();
        sprite = (Sonic) fixture.sprite();

        // Let Sonic land and settle first
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (!sprite.getAir()) break;
        }

        // Now set up a post-spindash rolling state directly at his settled position.
        // A fully-charged spindash gives gSpeed=0x800 (2048).
        // The settle puts him at x=7292 y=2767 angle=0xFE on ground.
        sprite.setDirection(com.openggf.physics.Direction.RIGHT);
        sprite.setGSpeed((short) 0x800);
        sprite.setXSpeed((short) 0x800);
        sprite.setYSpeed((short) 0);
        sprite.setRolling(true);
        sprite.setJumping(false);
        sprite.setPushing(false);
        sprite.applyRollingRadii(false);

        Camera camera = fixture.camera();
        camera.updatePosition(true);
    }

    /**
     * Verifies that Sonic can roll right from ~7292 past X=7499
     * without getting stuck with gSpeed=0.
     */
    @Test
    public void hcz2Spindash_shouldNotGetStuckAtX7499() {
        assertTrue(sprite.getRolling(), "Sonic should be rolling after setUp. " + describeState());
        assertTrue(sprite.getGSpeed() > 0, "gSpeed should be positive (rightward). " + describeState());

        short startX = sprite.getX();
        boolean passedTarget = false;
        int stuckFrame = -1;

        for (int frame = 0; frame < ROLL_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            short currentX = sprite.getX();
            short currentGSpeed = sprite.getGSpeed();

            if (currentX > 7499) {
                passedTarget = true;
                break;
            }

            // Detect the stuck condition: at or near x=7499 with gSpeed=0
            if (currentX >= 7490 && currentX <= 7510 && currentGSpeed == 0 && !sprite.getAir()) {
                stuckFrame = frame;
                break;
            }
        }

        if (stuckFrame >= 0) {
            fail("Sonic got stuck near X=7499 with gSpeed=0 at roll frame " + stuckFrame + ". " + describeState());
        }

        assertTrue(passedTarget, "Sonic should have rolled past X=7499 from starting X=" + startX + ". " + describeState());
    }

    /**
     * Diagnostic variant: logs per-frame state during the roll
     * for investigation purposes.
     */
    @Test
    public void hcz2Spindash_diagnosticTrace() {
        System.out.println("[DIAG] Initial: " + describeState());

        boolean stuckGrounded = false;
        int stuckFrame = -1;
        short stuckX = 0;
        boolean reachedTarget = false;

        for (int frame = 0; frame < ROLL_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            short x = sprite.getX();
            short gSpeed = sprite.getGSpeed();

            System.out.printf("[DIAG] frame=%3d x=%-5d y=%-5d gSpd=%-6d xSpd=%-6d ySpd=%-6d angle=0x%02X air=%-5b roll=%-5b push=%-5b%n",
                    frame, x, sprite.getY(), gSpeed, sprite.getXSpeed(), sprite.getYSpeed(),
                    sprite.getAngle() & 0xFF, sprite.getAir(), sprite.getRolling(), sprite.getPushing());

            // Reaching the target area before stopping means the roll succeeded.
            if (x > 7550) {
                reachedTarget = true;
                System.out.println("[DIAG] Passed target area — no stuck condition.");
                break;
            }

            if (gSpeed == 0 && !sprite.getAir()) {
                stuckGrounded = true;
                stuckFrame = frame;
                stuckX = x;
                System.out.println("[DIAG] *** gSpeed=0 at x=" + x + " ***");
                for (int extra = 0; extra < 5; extra++) {
                    fixture.stepFrame(false, false, false, false, false);
                    System.out.printf("[DIAG] frame=%3d x=%-5d y=%-5d gSpd=%-6d xSpd=%-6d ySpd=%-6d angle=0x%02X air=%-5b roll=%-5b push=%-5b%n",
                            frame + extra + 1, sprite.getX(), sprite.getY(), sprite.getGSpeed(),
                            sprite.getXSpeed(), sprite.getYSpeed(), sprite.getAngle() & 0xFF,
                            sprite.getAir(), sprite.getRolling(), sprite.getPushing());
                }
                break;
            }
        }

        // Regression guard: the rolling spindash must not stall on the ground
        // (gSpeed==0 while grounded) before clearing the documented x>7550 target.
        assertFalse(stuckGrounded,
                "Sonic got stuck grounded with gSpeed=0 at roll frame " + stuckFrame
                        + " x=" + stuckX + " before reaching x>7550. " + describeState());
        assertTrue(reachedTarget,
                "Sonic should have rolled past the x>7550 target without stalling. " + describeState());
    }

    private String describeState() {
        return "x=" + sprite.getX()
                + " y=" + sprite.getY()
                + " cx=" + sprite.getCentreX()
                + " cy=" + sprite.getCentreY()
                + " gSpeed=" + sprite.getGSpeed()
                + " xSpeed=" + sprite.getXSpeed()
                + " ySpeed=" + sprite.getYSpeed()
                + " angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF)
                + " air=" + sprite.getAir()
                + " rolling=" + sprite.getRolling()
                + " pushing=" + sprite.getPushing()
                + " frame=" + fixture.frameCount();
        }
}
