package uk.co.jamesj999.sonic.sprites.animation;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;

import static org.junit.Assert.assertEquals;

public class TestScriptedVelocityAnimationProfile {

    // Simple manual subclass to avoid full environment setup
    private static class TestSonic extends Sonic {
        public TestSonic() {
            super("TEST", (short)0, (short)0, false);
        }
        @Override
        public void defineSpeeds() {
            super.defineSpeeds();
        }
    }

    private TestSonic sonic;
    private ScriptedVelocityAnimationProfile profile;

    private static final int WALK_ID = 10;
    private static final int RUN_ID = 20;
    private static final int ROLL_ID = 30;
    private static final int AIR_ID = 40;

    @Before
    public void setup() {
        sonic = new TestSonic();
        profile = new ScriptedVelocityAnimationProfile(
                0, // idle
                WALK_ID,
                RUN_ID,
                ROLL_ID,
                ROLL_ID, // roll2
                0, // push
                0, // duck
                0, // spindash
                AIR_ID,
                0, // walk threshold
                100, // run threshold
                0 // fallback
        );
    }

    @Test
    public void testRollingPrioritizedOverAir() {
        // Setup: Sonic is Rolling AND in Air (e.g. Jumping)
        sonic.setRolling(true);
        sonic.setAir(true);

        // Act
        int animId = profile.resolveAnimationId(sonic, 0, 0);

        // Assert: Should be ROLL_ID, because rolling takes precedence over generic air
        assertEquals("When rolling and in air (jumping), animation should be ROLL", ROLL_ID, animId);
    }

    @Test
    public void testAirPrioritizedWhenNotRolling() {
        // Setup: Sonic is in Air but NOT Rolling (e.g. Walking off ledge)
        sonic.setRolling(false);
        sonic.setAir(true);

        // Act
        int animId = profile.resolveAnimationId(sonic, 0, 0);

        // Assert: Should be AIR_ID
        assertEquals("When in air and NOT rolling, animation should be AIR_ID", AIR_ID, animId);
    }
}
