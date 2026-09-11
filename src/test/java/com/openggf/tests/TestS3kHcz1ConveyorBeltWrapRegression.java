package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.HCZConveyorBeltObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless regression for HCZ Act 1 conveyor belt top-to-bottom wrap.
 *
 * <p>On a clockwise belt, Sonic rides the top surface rightward to the edge,
 * falls off, and should be captured by the paired bottom belt object. In the
 * ROM, each belt location has two objects: a rightward top belt (subtype 0xN0,
 * renderFlags=0) and a leftward bottom belt (subtype 0x1N, renderFlags=1),
 * separated by 0x2A pixels vertically. The bottom belt captures the falling
 * player via its standing detection range.
 *
 * <p>This test targets belt entry 0: bounds [0x0B28, 0x0CD8] (width 432px).
 * Wide belts are vulnerable to the ObjectManager's isOutOfRangeS1 check
 * killing the bottom belt before it can capture the falling player, because
 * getX() returns leftBound which falls behind the camera as Sonic rides
 * toward the right edge. The belt handles its own camera culling correctly
 * using both leftBound and rightBound with a 0x280 margin, but the external
 * check fires first.
 *
 * <p>ROM layout: top belt at Y=0x048B (subtype 0x00), bottom belt at
 * Y=0x04B5 (subtype 0x10).
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHcz1ConveyorBeltWrapRegression {

    /** Belt entry 0: bounds [0x0B28, 0x0CD8] — 432px wide. */
    private static final int BELT_LEFT = 0x0B28;
    private static final int BELT_RIGHT = 0x0CD8;

    /** Top belt object Y from ROM layout. */
    private static final int TOP_BELT_Y = 0x048B;
    /** Bottom belt object Y from ROM layout. */
    private static final int BOTTOM_BELT_Y = 0x04B5;

    /** Top belt standing snap: topBeltY + 0x14 = 0x049F. */
    private static final int TOP_STANDING_Y = TOP_BELT_Y + 0x14;
    /** Bottom belt standing snap: bottomBeltY + 0x14 = 0x04C9. */
    private static final int BOTTOM_STANDING_Y = BOTTOM_BELT_Y + 0x14;

    /** Max frames to wait for initial belt capture. */
    private static final int CAPTURE_TIMEOUT = 30;
    /** Max frames for Sonic to ride the belt to the right edge. */
    private static final int RIDE_TIMEOUT = 200;
    /** Max frames for Sonic to fall and be recaptured by the bottom belt. */
    private static final int RECAPTURE_TIMEOUT = 60;

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
        HCZConveyorBeltObjectInstance.resetLoadArray();

        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();

        // Place Sonic safely above the belt area for initial frame
        sprite.setX((short) BELT_LEFT);
        sprite.setY((short) 0x0300);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAngle((byte) 0);
        sprite.setAir(true);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setControlLocked(false);
        sprite.setObjectControlled(false);

        Camera camera = fixture.camera();
        camera.setX((short) BELT_LEFT);
        camera.setY((short) TOP_BELT_Y);
        camera.updatePosition(true);
        GameServices.level().getObjectManager().reset(camera.getX());

        // Step one frame to populate the spawn window
        fixture.stepFrame(false, false, false, false, false);

        // Destroy all non-belt objects near the belt to prevent fans/spikes
        // from interfering with the isolated belt capture test.
        var om = GameServices.level().getObjectManager();
        for (ObjectInstance obj : new ArrayList<>(om.getActiveObjects())) {
            if (obj instanceof AbstractObjectInstance absObj
                    && !(obj instanceof HCZConveyorBeltObjectInstance)) {
                var spawn = obj.getSpawn();
                if (spawn != null) {
                    int sx = spawn.x();
                    int sy = spawn.y();
                    if (sx >= BELT_LEFT - 0x80 && sx <= BELT_RIGHT + 0x80
                            && sy >= TOP_BELT_Y - 0x80 && sy <= BOTTOM_BELT_Y + 0x80) {
                        absObj.setDestroyed(true);
                    }
                }
            }
        }
        // Clean up destroyed objects
        fixture.stepFrame(false, false, false, false, false);

        // Now reposition Sonic just above the top belt's standing detection
        // range so he falls into it. Standing detection: Y > objY+0x14.
        sprite.setCentreX((short) 0x0B80);
        sprite.setCentreY((short) (TOP_STANDING_Y - 4));
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(true);
        sprite.setOnObject(false);
        sprite.setObjectControlled(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
    }

    /**
     * Sonic falls onto the top belt, rides it to the right edge, falls off,
     * and should be recaptured by the bottom belt.
     */
    @Test
    public void sonicWrapsFromTopBeltToBottomBelt() {
        // Phase 1: Wait for Sonic to be captured by the top belt
        boolean capturedOnTop = false;
        for (int frame = 0; frame < CAPTURE_TIMEOUT; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            if (sprite.isObjectControlled()) {
                int centreY = sprite.getCentreY() & 0xFFFF;
                // Accept capture on either surface of the top belt
                if (centreY >= TOP_BELT_Y - 0x20 && centreY <= TOP_BELT_Y + 0x30) {
                    capturedOnTop = true;
                    break;
                }
            }
        }
        assertTrue(capturedOnTop,
                "Sonic should be captured on the top belt. "
                        + "Actual: objCtrl=" + sprite.isObjectControlled()
                        + " centreY=0x" + Integer.toHexString(sprite.getCentreY() & 0xFFFF));

        // Phase 2: Ride the belt rightward until Sonic is released at the edge
        boolean released = false;
        for (int frame = 0; frame < RIDE_TIMEOUT; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            if (!sprite.isObjectControlled()) {
                released = true;
                break;
            }
        }
        assertTrue(released,
                "Sonic should be released at the belt's right edge. "
                        + "centreX=0x" + Integer.toHexString(sprite.getCentreX() & 0xFFFF));

        int releaseX = sprite.getCentreX() & 0xFFFF;
        int releaseY = sprite.getCentreY() & 0xFFFF;

        // Phase 3: Wait for recapture by the bottom belt
        boolean capturedOnBottom = false;
        for (int frame = 0; frame < RECAPTURE_TIMEOUT; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            if (sprite.isObjectControlled()) {
                int centreY = sprite.getCentreY() & 0xFFFF;
                // Accept capture on the bottom belt's surface range
                if (centreY >= BOTTOM_BELT_Y - 0x20 && centreY <= BOTTOM_BELT_Y + 0x30) {
                    capturedOnBottom = true;
                    break;
                }
            }
        }
        assertTrue(capturedOnBottom,
                "After falling off the top belt, Sonic should be captured by the "
                        + "bottom belt (near Y~0x" + Integer.toHexString(BOTTOM_STANDING_Y) + "). "
                        + "Release pos: (0x" + Integer.toHexString(releaseX) + ", 0x"
                        + Integer.toHexString(releaseY) + "). "
                        + "Final: objCtrl=" + sprite.isObjectControlled()
                        + " centreY=0x" + Integer.toHexString(sprite.getCentreY() & 0xFFFF)
                        + " yVel=" + sprite.getYSpeed()
                        + ". Belt objects present: " + countBeltObjects());
    }

    private int countBeltObjects() {
        int count = 0;
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof HCZConveyorBeltObjectInstance) {
                count++;
            }
        }
        return count;
    }
}
