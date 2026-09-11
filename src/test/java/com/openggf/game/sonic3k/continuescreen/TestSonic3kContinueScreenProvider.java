package com.openggf.game.sonic3k.continuescreen;

import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SingletonResetExtension.class)
class TestSonic3kContinueScreenProvider {
    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @Test
    void setupTickPublishesNineAndEachDigitLastsSixtyMainLoopTicks() {
        var screen = new Sonic3kContinueScreenProvider();
        screen.initialize(3);
        assertEquals(9, screen.countdown());
        for (int digit = 9; digit >= 0; digit--) {
            for (int i = 0; i < 59; i++) {
                screen.update(false, false);
                assertFalse(screen.isFinished());
                assertEquals(digit, screen.countdown());
            }
            screen.update(false, false);
        }
        assertTrue(screen.isFinished());
        assertFalse(screen.isAccepted());
    }

    @Test
    void secondControllerStartWinsOnLastCountdownTickAndWaitsForTailsExit() {
        var screen = new Sonic3kContinueScreenProvider();
        screen.initialize(1);
        for (int i = 0; i < 599; i++) {
            screen.update(false, false);
        }
        screen.update(false, true);
        assertTrue(screen.isAccepted());
        for (int i = 0; i < 89; i++) {
            screen.update(false, false);
            assertFalse(screen.isFinished());
        }
        screen.update(false, false);
        assertTrue(screen.isFinished());
    }

    @Test
    void characterDepartureOwnersDetermineCompletion() {
        assertDeparture(0, false, 90);
        // Locked-on uses the same Sonic/Tails actors for all non-Knuckles modes.
        assertDeparture(1, false, 90);
        assertDeparture(2, false, 90);
        assertDeparture(0, true, 79);
        assertDeparture(3, false, 82);
    }

    @Test
    void fadingAdvancesVintWithoutProcessingSpritesOrCountdown() {
        var screen = new Sonic3kContinueScreenProvider();
        screen.initialize(2, 15);
        for (int i = 0; i < 22; i++) {
            screen.advanceFadeFrame();
        }
        assertEquals(15, screen.vintRunCount(), "displayed mapping stays frozen during fade");
        assertEquals(9, screen.countdown());
        screen.update(false, false);
        assertEquals(38, screen.vintRunCount());
        assertEquals(9, screen.countdown());
    }

    @Test
    void iconRowExcludesConsumedContinueAndCapsAtNine() {
        var screen = new Sonic3kContinueScreenProvider();
        screen.initialize(1);
        assertEquals(0, screen.iconCount());
        screen.initialize(9);
        assertEquals(8, screen.iconCount());
        screen.initialize(10);
        assertEquals(9, screen.iconCount());
        screen.initialize(255);
        assertEquals(9, screen.iconCount());
        screen.initialize(0);
        assertEquals(9, screen.iconCount());
    }

    @Test
    void restartRetainsCheckpointAndRequiresSavePublication() {
        var screen = new Sonic3kContinueScreenProvider();
        assertFalse(screen.clearsCheckpointOnContinue());
        assertTrue(screen.savesOnContinue());
        screen.initialize(3);
        screen.update(true, false);
        screen.reset();
        screen.update(true, true);
        assertFalse(screen.isAccepted());
        assertFalse(screen.isFinished());
    }

    @Test
    void rawAnimationStartsAtSecondEntryAndPreservesItsPhaseAcrossIdleTicks() {
        var solo = new Sonic3kContinueScreenProvider(0, true);
        solo.initialize(3);
        assertEquals(0xBE, solo.soloFrame());
        for (int i = 0; i < 11; i++) {
            solo.update(false, false);
            assertEquals(0xBE, solo.soloFrame());
        }
        solo.update(false, false);
        assertEquals(0xBD, solo.soloFrame());
        solo.update(true, false);
        solo.update(false, false);
        assertEquals(0x22, solo.knucklesFrame());

        var knuckles = new Sonic3kContinueScreenProvider(3, false);
        knuckles.initialize(3);
        assertEquals(2, knuckles.knucklesFrame());
        for (int i = 0; i < 12; i++) {
            knuckles.update(false, false);
        }
        assertEquals(4, knuckles.knucklesFrame());
    }

    private void assertDeparture(int mode, boolean alone, int duration) {
        var screen = new Sonic3kContinueScreenProvider(mode, alone);
        screen.initialize(3);
        screen.update(true, false);
        for (int i = 1; i < duration; i++) {
            screen.update(false, false);
            assertFalse(screen.isFinished(), "mode=" + mode + " age=" + i);
        }
        screen.update(false, false);
        assertTrue(screen.isAccepted());
        assertTrue(screen.isFinished());
    }
}
