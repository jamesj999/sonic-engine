package com.openggf.game.rewind;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.TestEnvironment;
import com.openggf.timer.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLiveRewindSpeedModifiers {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void noModifiersGiveUnitMultiplier() {
        assertEquals(1.0, LiveRewindManager.speedMultiplier(false, false), 1e-9);
    }

    @Test
    void halfModifierHalvesSpeed() {
        assertEquals(0.5, LiveRewindManager.speedMultiplier(true, false), 1e-9);
    }

    @Test
    void doubleModifierDoublesSpeed() {
        assertEquals(2.0, LiveRewindManager.speedMultiplier(false, true), 1e-9);
    }

    @Test
    void bothModifiersCancelToNormalSpeed() {
        assertEquals(1.0, LiveRewindManager.speedMultiplier(true, true), 1e-9);
    }

    @Test
    void modifierKeysMirrorAcrossLeftAndRightVariants() {
        assertEquals(GLFW.GLFW_KEY_RIGHT_CONTROL, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_LEFT_CONTROL));
        assertEquals(GLFW.GLFW_KEY_LEFT_CONTROL, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_RIGHT_CONTROL));
        assertEquals(GLFW.GLFW_KEY_RIGHT_SHIFT, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_LEFT_SHIFT));
        assertEquals(GLFW.GLFW_KEY_LEFT_SHIFT, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_RIGHT_SHIFT));
        assertEquals(GLFW.GLFW_KEY_RIGHT_ALT, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_LEFT_ALT));
    }

    @Test
    void nonModifierKeysHaveNoMirror() {
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_R));
        assertEquals(GLFW.GLFW_KEY_UNKNOWN, LiveRewindManager.mirroredModifier(GLFW.GLFW_KEY_SPACE));
    }

    @Test
    void rewindRestoreKeepsTheSpeedShoesTimerExpiringAfterItsOriginalRemainingDuration() {
        Sonic sonic = new Sonic("sonic", (short) 100, (short) 200);
        sonic.giveSpeedShoes();

        String code = "SpeedShoes-" + sonic.getCode();
        Timer timer = GameServices.timers().getTimerForCode(code);
        assertNotNull(timer, "giveSpeedShoes must register a countdown timer");
        timer.setTicks(5); // simulate time having passed, 5 frames of duration left

        PerObjectRewindSnapshot snapshot = sonic.captureRewindState();

        // Simulate stepping forward past the natural expiry: the timer counts
        // down, fires (deactivating speed shoes), and self-removes -- exactly
        // like ordinary gameplay driving TimerManager.update() every frame.
        for (int i = 0; i < 5; i++) {
            GameServices.timers().updateDisplayPhaseTimersFor(sonic);
        }
        assertFalse(sonic.hasSpeedShoes(), "sanity: speed shoes must have naturally expired by now");
        assertNull(GameServices.timers().getTimerForCode(code), "sanity: the expired timer must have removed itself");

        // Rewind back to the snapshot: speed shoes must be active again with
        // exactly 5 ticks remaining, and that timer must still be a REAL
        // countdown -- not a behavior-inert placeholder that never calls back
        // to deactivate speed shoes (the "lasts indefinitely" bug).
        sonic.restoreRewindState(snapshot);
        assertTrue(sonic.hasSpeedShoes(), "restore must bring speed shoes back to their captured active state");

        for (int i = 0; i < 4; i++) {
            GameServices.timers().updateDisplayPhaseTimersFor(sonic);
        }
        assertTrue(sonic.hasSpeedShoes(),
                "speed shoes must still be active after only 4 of the 5 originally-captured remaining ticks pass");

        GameServices.timers().updateDisplayPhaseTimersFor(sonic);
        assertFalse(sonic.hasSpeedShoes(),
                "the restored timer must still expire after its original remaining duration instead of "
                        + "lasting indefinitely");
    }
}
