package com.openggf.game.sonic2.continuescreen;

import com.openggf.data.RomByteReader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2ContinueScreen {
    @org.junit.jupiter.api.BeforeEach void headless() {
        com.openggf.game.GameServices.graphics().initHeadless();
    }

    @Test void headlessDrawCoversFallingWaitingAndDepartureFrames() {
        var screen = new Sonic2ContinueScreenProvider();
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

    @Test void retainedSuperFlagKeepsRomOverflowScriptsWithoutMutatingGameplaySprite() {
        var main = new com.openggf.sprites.playable.Sonic("sonic", (short) 0, (short) 0);
        main.setSuperSonic(true);
        com.openggf.game.GameServices.sprites().addSprite(main, "sonic");
        var screen = new Sonic2ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 200; i++) {
            screen.draw();
            screen.update(false, false);
        }
        screen.update(true, false);
        while (!screen.isFinished()) {
            screen.draw();
            screen.update(false, false);
        }
        assertTrue(main.isSuperSonic());
        assertTrue(screen.isAccepted());
    }

    @Test void specialStagePaletteIsLoadedIntoLineThreeWithoutReplacingSonicPalette() throws Exception {
        var screen = new Sonic2ContinueScreenProvider();
        screen.initialize(3);
        var artField = Sonic2ContinueScreenProvider.class.getDeclaredField("art");
        artField.setAccessible(true);
        var art = artField.get(screen);
        var palettesField = art.getClass().getDeclaredField("palettes");
        palettesField.setAccessible(true);
        var palettes = (com.openggf.level.Palette[]) palettesField.get(art);
        var rom = TestEnvironment.currentRom();
        var normal = new com.openggf.level.Palette();
        normal.fromSegaFormat(rom.readBytes(
                com.openggf.game.sonic2.constants.Sonic2Constants.SONIC_TAILS_PALETTE_ADDR, 32));
        var special = new com.openggf.level.Palette();
        special.fromSegaFormat(rom.readBytes(Sonic2ContinueScreenProvider.PALETTE, 32));
        for (int i = 1; i < 16; i++) {
            assertEquals(normal.colors[i].r, palettes[0].colors[i].r);
            assertEquals(normal.colors[i].g, palettes[0].colors[i].g);
            assertEquals(normal.colors[i].b, palettes[0].colors[i].b);
            assertEquals(special.colors[i].r, palettes[3].colors[i].r);
            assertEquals(special.colors[i].g, palettes[3].colors[i].g);
            assertEquals(special.colors[i].b, palettes[3].colors[i].b);
        }
    }

    @Test void continueMusicUsesTheNativeNonzeroMailboxRequest() throws Exception {
        var rom = TestEnvironment.currentRom();
        var reader = RomByteReader.fromRom(rom);
        int id = com.openggf.game.sonic2.audio.Sonic2Music.CONTINUE.id;
        assertEquals(0x9C, id);
        assertEquals(id, reader.readU8(0x78F9)); // ContinueScreen: move.b #MusID_Continue,d0.
        assertNotNull(new com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader(rom).loadMusic(id));
    }

    @Test void timeoutWithoutUsingContinue() {
        var screen = new Sonic2ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 657; i++) screen.update(false, false);
        assertFalse(screen.isFinished());
        screen.update(false, false);
        assertTrue(screen.isFinished());
        assertFalse(screen.isAccepted());
    }

    @Test void secondControllerCannotSpendContinue() {
        var screen = new Sonic2ContinueScreenProvider();
        screen.initialize(3);
        for (int i = 0; i < 100; i++) screen.update(false, true);
        assertFalse(screen.isAccepted());
    }

    @Test void acceptanceWaitsForNativeExitMotion() {
        var screen = new Sonic2ContinueScreenProvider();
        screen.initialize(3);
        screen.update(true, false);
        assertTrue(screen.isAccepted());
        for (int i = 0; i < 87; i++) screen.update(false, false);
        assertFalse(screen.isFinished());
        screen.update(false, false);
        assertTrue(screen.isFinished());
        screen.reset();
        assertFalse(screen.isAccepted());
        assertFalse(screen.isFinished());
    }

    @Test void artAndMappingOffsetsMatchOwningRomInstructions() throws Exception {
        var reader = RomByteReader.fromRom(TestEnvironment.currentRom());
        assertEquals(Sonic2ContinueScreenProvider.ART_TAILS, reader.readU32BE(0x78B8));
        assertEquals(Sonic2ContinueScreenProvider.ART_MINI_SONIC, reader.readU32BE(0x78CC));
        assertEquals(Sonic2ContinueScreenProvider.ART_MINI_TAILS, reader.readU32BE(0x78DA));
        assertEquals(Sonic2ContinueScreenProvider.MAP, reader.readU32BE(0x7A84));
    }
}
