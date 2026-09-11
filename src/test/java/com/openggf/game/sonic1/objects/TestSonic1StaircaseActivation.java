package com.openggf.game.sonic1.objects;

import com.openggf.game.GameServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the SLZ Staircase (Object 0x5B).
 *
 * Places Sonic at the screenshot coordinates (1070, 441) in SLZ1, where
 * he stands on a staircase. Verifies the staircase activates and carries
 * Sonic down ~128 pixels.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestSonic1StaircaseActivation {
    private static final int ZONE_SLZ = 4;
    private static final int ACT_1 = 0;
    private static final int SCREENSHOT_X = 1070;
    private static final int SCREENSHOT_Y = 441;

    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_1, ZONE_SLZ, ACT_1);
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
    public void sonicDescendsWithStaircase() {
        ObjectManager om = GameServices.level().getObjectManager();

        // Position Sonic at the screenshot location and let objects spawn
        fixture.sprite().setCentreX((short) SCREENSHOT_X);
        fixture.sprite().setCentreY((short) SCREENSHOT_Y);
        fixture.sprite().setAir(false);
        fixture.sprite().setXSpeed((short) 0);
        fixture.sprite().setYSpeed((short) 0);
        fixture.sprite().setGSpeed((short) 0);
        fixture.camera().updatePosition(true);
        fixture.stepIdleFrames(3);

        // Find the staircase
        Sonic1StaircaseObjectInstance staircase = null;
        for (ObjectInstance obj : om.getActiveObjects()) {
            if (obj instanceof Sonic1StaircaseObjectInstance s) {
                staircase = s;
                break;
            }
        }
        assertNotNull(staircase, "Should find a staircase near screenshot position");

        // Let Sonic fall to the staircase (no terrain at this location)
        for (int f = 0; f < 30; f++) {
            fixture.stepFrame(false, false, false, false, false);
            if (!fixture.sprite().getAir() && fixture.sprite().isOnObject()) break;
        }
        assertTrue(fixture.sprite().isOnObject(), "Sonic should land on staircase");

        int startY = fixture.sprite().getCentreY();

        // Wait for activation (30 timer) + descent (128 frames) + margin.
        // Track peak descent: Sonic rides the staircase down, then may fall off
        // and land on terrain below, so final Y doesn't reflect the ride distance.
        int maxDescent = 0;
        for (int f = 0; f < 200; f++) {
            fixture.stepFrame(false, false, false, false, false);
            int descent = fixture.sprite().getCentreY() - startY;
            if (descent > maxDescent) {
                maxDescent = descent;
            }
        }

        assertTrue(staircase.getState() > 0, "Staircase should activate (state > 0, got " + staircase.getState() + ")");
        assertTrue(maxDescent > 30, "Sonic should descend with staircase (expected >30px, got " + maxDescent + "px)");
    }
}


