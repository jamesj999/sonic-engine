package com.openggf.game.sonic1.continuescreen;

import com.openggf.data.RomByteReader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1ContinueScreen {
    @org.junit.jupiter.api.BeforeEach void headless() {
        com.openggf.game.GameServices.graphics().initHeadless();
    }

    @Test void headlessDrawCoversFallingWaitingAndDepartureFrames() {
        var screen = new Sonic1ContinueScreenProvider();
        screen.initialize(16, 7);
        for (int i = 0; i < 200; i++) {
            screen.draw();
            screen.update(false, false);
        }
        screen.update(true, false);
        for (int i = 0; i < 100 && !screen.isFinished(); i++) {
            screen.draw();
            screen.update(false, false);
        }
        assertTrue(screen.isFinished());
        assertTrue(screen.isAccepted());
    }

    @Test void timeoutWithoutUsingContinue() {
        var screen = new Sonic1ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 658; i++) screen.update(false, false);
        assertFalse(screen.isFinished());
        screen.update(false, false);
        assertTrue(screen.isFinished());
        assertFalse(screen.isAccepted());
    }

    @Test void secondControllerCannotSpendContinue() {
        var screen = new Sonic1ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 100; i++) screen.update(false, true);
        assertFalse(screen.isAccepted());
    }

    @Test void acceptanceWaitsForNativeExitMotion() {
        var screen = new Sonic1ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 55; i++) screen.update(true, false);
        assertFalse(screen.isAccepted(), "CSon_ChkLand ignores Start until Sonic reaches the floor");
        screen.update(true, false);
        assertTrue(screen.isAccepted());
        for (int i = 0; i < 76; i++) screen.update(false, false);
        assertFalse(screen.isFinished());
        screen.update(false, false);
        assertTrue(screen.isFinished());
        screen.reset();
        assertFalse(screen.isAccepted());
        assertFalse(screen.isFinished());
    }

    @Test void artAndMappingOffsetsMatchOwningRomInstructions() throws Exception {
        var reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        assertEquals(Sonic1ContinueScreenProvider.ART_CONTINUE, reader.readU32BE(0x4D44));
        assertEquals(Sonic1ContinueScreenProvider.ART_MINI, reader.readU32BE(0x4D58));
        assertEquals(Sonic1ContinueScreenProvider.MAP, reader.readU32BE(0x4E6A));
    }
}
