package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for CNZ1 wall sticking while riding lifts.
 * <p>
 * When Sonic rides a CNZ Big Block (ObjD4) near a terrain wall and presses
 * left, the riding state should remain stable. Before the fix, re-evaluating
 * the ridden object in the contact loop caused false side detection
 * (absDistX <= absDistY) at platform edges near walls, clearing isOnObject
 * each alternating frame.
 * <p>
 * ROM reference: s2.asm:34806 â€” SolidObject checks btst d6,status(a0) and
 * branches to MvSonicOnPtfm (carrying only) when already standing, skipping
 * overlap re-evaluation.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestHeadlessCNZ1LiftWallStick {

    private static final int ZONE_CNZ = 3;
    private static final int ACT_1 = 0;

    // Position near a wall where Big Block lifts operate in CNZ1
    private static final int START_CENTRE_X = 3311;
    private static final int START_CENTRE_Y = 1305;

    private static final int SETTLE_FRAMES = 30;
    private static final int HOLD_LEFT_FRAMES = 40;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_CNZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    @Test
    public void testRidingStateDoesNotOscillateNearWall() {
        fixture.sprite().setCentreX((short) START_CENTRE_X);
        fixture.sprite().setCentreY((short) START_CENTRE_Y);
        fixture.sprite().setAir(false);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);

        fixture.camera().updatePosition(true);

        // Let Sonic settle onto ground/platform
        for (int i = 0; i < SETTLE_FRAMES; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        // Record if Sonic is on an object after settling
        boolean wasOnObject = fixture.sprite().isOnObject();

        // Hold left near the wall and track on-object state changes
        int oscillationCount = 0;
        boolean previousOnObject = wasOnObject;
        int lastX = fixture.sprite().getCentreX();
        int maxXJitter = 0;

        for (int frame = 0; frame < HOLD_LEFT_FRAMES; frame++) {
            fixture.stepFrame(false, false, true, false, false);

            boolean currentOnObject = fixture.sprite().isOnObject();
            if (currentOnObject != previousOnObject) {
                oscillationCount++;
            }
            previousOnObject = currentOnObject;

            // Track X position stability
            int currentX = fixture.sprite().getCentreX();
            int jitter = Math.abs(currentX - lastX);
            if (jitter > maxXJitter) {
                maxXJitter = jitter;
            }
            lastX = currentX;
        }

        // The riding state should not oscillate rapidly. A few transitions are
        // acceptable (e.g., initial landing, walking off edge), but rapid
        // on/off toggling (>4 transitions in 40 frames) indicates the bug.
        assertTrue(oscillationCount <= 4, "isOnObject() oscillated " + oscillationCount + " times in "
                + HOLD_LEFT_FRAMES + " frames â€” riding state is unstable near wall. "
                + "Expected <= 4 transitions.");
    }
}


