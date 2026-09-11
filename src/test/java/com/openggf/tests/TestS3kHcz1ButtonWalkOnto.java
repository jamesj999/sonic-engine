package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kButtonObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Headless regression for HCZ Act 1 SolidObjectTop button landing.
 *
 * <p>At approximately x=13424 y=2037 there is a SolidObjectTop button
 * (subtype 0x20, trigger index 0). The button is co-located with
 * retractable spikes, so a ground-level side-approach is blocked.
 * Instead, we place Sonic directly above the button and let him fall
 * onto it â€” this isolates the landing threshold behaviour.
 *
 * <p>ROM reference: sonic3k.asm Obj_Button loc_2C62C (SolidObjectTop path),
 * SolidObjectTop_1P landing at lines 41983-42015. The landing vertical
 * window uses {@code cmpi.w #-$10,d0; blo} which accepts d0 = -$10
 * (distY = 16 in engine convention). The engine's threshold must use
 * {@code distY > 0x10} for SolidObjectTop (not {@code >= 0x10}).
 *
 * <p>Additionally, SolidObjectTop landing uses the full d1 for horizontal
 * bounds (no width_pixels re-read), so isWithinTopLandingWidth must
 * skip the {@code -0x0B} reduction for topSolidOnly objects.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHcz1ButtonWalkOnto {
    /** Button layout position from HCZ1 object data (0x3470, 0x07F5). */
    private static final int BUTTON_X = 13424;
    private static final int BUTTON_RAW_Y = 2037;

    /**
     * Sonic is placed ON THE GROUND at the button's X, at the user-reported
     * Y position (top-left 2009 â†’ centreY 2028). This gives distY = 16:
     *   button collision Y = 2037 + 4 (init) = 2041
     *   maxTop = airHalfHeight(6) + yRadius(19) = 25
     *   relY = 2028 - 2041 + 4 + 25 = 16
     *   distY = 16
     * SolidObjectTop must accept distY = 16 (ROM: cmpi.w #-$10,d0; blo).
     */
    private static final short GROUND_TOP_LEFT_X = (short) BUTTON_X;
    private static final short GROUND_TOP_LEFT_Y = 2009;

    /** Maximum frames for the button to register standing contact. */
    private static final int MAX_FRAMES = 10;

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
        sprite = (Sonic) fixture.sprite();

        // Position Sonic ABOVE the spikes for the setup frame so they don't
        // interact. Y=1800 is well above the button/spike area (2037).
        sprite.setX((short) BUTTON_X);
        sprite.setY((short) 1800);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
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

        // Step one idle frame to populate the spawn window (Sonic at safe height).
        fixture.stepFrame(false, false, false, false, false);

        // Destroy all non-button objects near the button (spikes, fans, etc.)
        // to prevent interference with the button collision test.
        var om = GameServices.level().getObjectManager();
        for (ObjectInstance obj : new java.util.ArrayList<>(om.getActiveObjects())) {
            var spawn = obj.getSpawn();
            if (obj instanceof com.openggf.level.objects.AbstractObjectInstance absObj
                    && spawn != null
                    && spawn.objectId() != 0x33
                    && Math.abs(spawn.x() - BUTTON_X) < 60) {
                absObj.setDestroyed(true);
            }
        }
        // Step another idle frame to let destroyed objects be cleaned up.
        fixture.stepFrame(false, false, false, false, false);

        // Now reposition Sonic at the exact distY=16 position, on the ground.
        // centreY=2028 produces distY=16 against button at collision Y=2041.
        sprite.setCentreX((short) BUTTON_X);
        sprite.setCentreY((short) 2028);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(false);
        sprite.setOnObject(false);

        Sonic3kLevelTriggerManager.reset();
    }

    /**
     * Sonic stands on the ground at the button's position, at the exact Y
     * that produces distY = 16.  SolidObjectTop must accept this (ROM does).
     * Steps a few idle frames â€” button collision should detect standing and
     * set the trigger.
     */
    @Test
    public void standingAtButtonHeightActivatesTrigger() {
        boolean triggerFired = false;

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, false, false); // idle

            if (Sonic3kLevelTriggerManager.testAny(0)) {
                triggerFired = true;
                break;
            }
        }

        assertTrue(triggerFired, "Sonic at ground level (topLeft Y=" + GROUND_TOP_LEFT_Y
                + ") should activate the HCZ1 button (trigger 0). "
                + "This tests distY=16 acceptance for SolidObjectTop. "
                + "Final centre: (" + sprite.getCentreX() + ", " + sprite.getCentreY()
                + "), air=" + sprite.getAir()
                + ", onObject=" + sprite.isOnObject());
    }

    /**
     * Verify the button object spawns at the expected location.
     */
    @Test
    public void buttonObjectSpawnsNearExpectedPosition() {
        // Step a frame to populate the spawn window
        fixture.stepFrame(false, false, false, false, false);

        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof Sonic3kButtonObjectInstance btn) {
                int dx = Math.abs(btn.getX() - BUTTON_X);
                if (dx < 20) {
                    return; // found it
                }
            }
        }

        fail("No Button object found near x=" + BUTTON_X + " in HCZ1");
    }
}


