package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for GHZ3 bridge terrain collision.
 *
 * When Sonic runs right across the last bridge in GHZ3, he should transition
 * smoothly onto the terrain on the right side without hitting a wall. A bug
 * caused the bridge's collision width to extend too far right, keeping Sonic
 * "on bridge" while his push sensors detected terrain walls.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Ghz3BridgeTerrainCollision {
    private static final int ZONE_GHZ = 0;
    private static final int ACT_3 = 2;

    // Start position: on the ground just before the last GHZ3 bridge
    private static final short START_X = 9464;
    private static final short START_Y = 921;

    // Sonic should pass well beyond this X without entering push mode
    private static final int PUSH_BUG_X = 9707;

    // Run long enough to cross the bridge and reach terrain on the right
    private static final int MAX_FRAMES = 300;

    private static SharedLevel sharedLevel;
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_GHZ, ACT_3);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition(START_X, START_Y)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        if (objectManager != null) {
            objectManager.reset(fixture.camera().getX());
        }
    }

    @Test
    public void sonicRunsAcrossBridgeWithoutHittingWall() {
        ObjectManager objectManager = GameServices.level().getObjectManager();

        // Let Sonic settle and walk right a bit to spawn objects
        fixture.stepIdleFrames(5);

        boolean bridgeLogged = false;

        boolean passedBridgeArea = false;
        int stuckCount = 0;
        int lastX = fixture.sprite().getCentreX();

        for (int frame = 0; frame < MAX_FRAMES; frame++) {
            fixture.stepFrame(false, false, false, true, false); // hold right

            int x = fixture.sprite().getCentreX();
            int y = fixture.sprite().getCentreY();
            boolean pushing = fixture.sprite().getPushing();
            boolean onObject = fixture.sprite().isOnObject();
            boolean air = fixture.sprite().getAir();
            short gSpeed = fixture.sprite().getGSpeed();
            short xSpeed = fixture.sprite().getXSpeed();
            boolean riding = objectManager != null && objectManager.isRidingObject(fixture.sprite());

            // Log bridge info once we're near the problem area
            if (!bridgeLogged && x > 9600 && objectManager != null) {
                for (var obj : objectManager.getActiveObjects()) {
                    if (obj instanceof com.openggf.game.sonic1.objects.Sonic1BridgeObjectInstance) {
                        var spawn = obj.getSpawn();
                        int logCount = Math.max(1, Math.min(16, spawn.subtype() & 0xFF));
                        int hw = logCount * 8;
                        System.out.println("Bridge spawn x=" + spawn.x() + " y=" + spawn.y()
                                + " subtype=0x" + Integer.toHexString(spawn.subtype() & 0xFF)
                                + " logCount=" + logCount + " halfWidth=" + hw
                                + " romRightBound=" + (spawn.x() + hw - 8));
                    }
                }
                bridgeLogged = true;
            }

            // Once we're past the known bug location, mark success
            if (x > PUSH_BUG_X + 20) {
                passedBridgeArea = true;
                break;
            }

            // Detect stuck state: x not advancing while holding right
            if (x == lastX && x > START_X + 50) {
                stuckCount++;
                if (stuckCount >= 3) {
                    // Collect bridge info for diagnostics
                    StringBuilder bridgeInfo = new StringBuilder();
                    if (objectManager != null) {
                        var ridingObj = objectManager.getRidingObject(fixture.sprite());
                        if (ridingObj != null) {
                            var spawn = ridingObj.getSpawn();
                            int lc = Math.max(1, Math.min(16, spawn.subtype() & 0xFF));
                            bridgeInfo.append(" bridgeX=").append(spawn.x())
                                    .append(" bridgeY=").append(spawn.y())
                                    .append(" logCount=").append(lc)
                                    .append(" romRight=").append(spawn.x() + lc * 8 - 8);
                        }
                    }
                    assertFalse(true, "Sonic stuck at x=" + x + " y=" + y + " frame=" + frame
                                    + " gSpeed=" + gSpeed + " xSpeed=" + xSpeed
                                    + " pushing=" + pushing + " onObject=" + onObject
                                    + " air=" + air + " riding=" + riding
                                    + bridgeInfo
                                    + " â€” wall collision with terrain beside bridge");
                }
            } else {
                stuckCount = 0;
            }
            lastX = x;
        }

        assertTrue(passedBridgeArea, "Sonic should have passed x=" + (PUSH_BUG_X + 20)
                        + " but reached x=" + fixture.sprite().getCentreX());
    }
}


