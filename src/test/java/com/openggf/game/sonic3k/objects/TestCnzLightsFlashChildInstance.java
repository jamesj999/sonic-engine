package com.openggf.game.sonic3k.objects;

import com.openggf.data.Rom;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.Palette;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the CNZ Act 2 lights-off / water palette flash ({@code loc_62480}).
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestCnzLightsFlashChildInstance {

    private static final int LINE_BYTES = 32;
    private static final int VARIANT_BYTES = 64;
    private static final int FLASH_LINE_LOW = 2;
    private static final int FLASH_LINE_HIGH = 3;

    @Test
    void cutsceneFlashDarkensLines2And3AndLeavesThemDark() throws Exception {
        Rom rom = TestEnvironment.currentRom();
        byte[] flash = rom.readBytes(Sonic3kConstants.PAL_CNZ_FLASH_ADDR, Sonic3kConstants.PAL_CNZ_FLASH_SIZE);

        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] palettes = newPalettes();

        // subtype 0 -> restoreAfter = false (cutscene button: lights stay off).
        CnzLightsFlashChildInstance fx = new CnzLightsFlashChildInstance(
                spawn(), false);
        fx.setServices(new TestObjectServices().withRom(rom).withPaletteOwnershipRegistry(registry));

        boolean checkedFirstStep = false;
        int frames = 0;
        while (!fx.isDestroyed() && frames < 200) {
            registry.beginFrame();
            fx.update(frames, null);
            registry.resolveInto(palettes, null, null, palettes[0]);
            if (!checkedFirstStep) {
                // First update writes step 0 = variant A (Pal_CNZFlash+0).
                assertColorEquals(flash, 0, palettes[FLASH_LINE_LOW].getColor(0), "step0 line2");
                assertColorEquals(flash, LINE_BYTES, palettes[FLASH_LINE_HIGH].getColor(0), "step0 line3");
                checkedFirstStep = true;
            }
            frames++;
        }

        assertTrue(fx.isDestroyed(), "flash deletes itself after six flicker steps");
        // The final write was step 5 = variant B (Pal_CNZFlash+$40); no restore.
        assertColorEquals(flash, VARIANT_BYTES, palettes[FLASH_LINE_LOW].getColor(0), "final line2 dark");
        assertColorEquals(flash, VARIANT_BYTES + LINE_BYTES, palettes[FLASH_LINE_HIGH].getColor(0),
                "final line3 dark");
    }

    @Test
    void waterButtonFlashRestoresPalCnzAtTheEnd() throws Exception {
        Rom rom = TestEnvironment.currentRom();
        byte[] palCnz = rom.readBytes(Sonic3kConstants.PAL_CNZ_ADDR + 0x20, VARIANT_BYTES);

        PaletteOwnershipRegistry registry = new PaletteOwnershipRegistry();
        Palette[] palettes = newPalettes();

        // subtype set -> restoreAfter = true (water-level button: lights back on).
        CnzLightsFlashChildInstance fx = new CnzLightsFlashChildInstance(spawn(), true);
        fx.setServices(new TestObjectServices().withRom(rom).withPaletteOwnershipRegistry(registry));

        int frames = 0;
        while (!fx.isDestroyed() && frames < 200) {
            registry.beginFrame();
            fx.update(frames, null);
            registry.resolveInto(palettes, null, null, palettes[0]);
            frames++;
        }

        assertTrue(fx.isDestroyed(), "flash deletes itself after six flicker steps");
        // Final write restores Pal_CNZ+$20 -> lights on.
        assertColorEquals(palCnz, 0, palettes[FLASH_LINE_LOW].getColor(0), "restored line2");
        assertColorEquals(palCnz, LINE_BYTES, palettes[FLASH_LINE_HIGH].getColor(0), "restored line3");
    }

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x1E00, 0x0338, Sonic3kObjectIds.CUTSCENE_BUTTON, 0, 0, false, 0);
    }

    private static Palette[] newPalettes() {
        Palette[] palettes = new Palette[4];
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = new Palette();
        }
        return palettes;
    }

    private static void assertColorEquals(byte[] segaData, int offset, Palette.Color actual, String label) {
        Palette.Color expected = new Palette.Color();
        expected.fromSegaFormat(segaData, offset);
        assertEquals(expected.r, actual.r, label + " (r)");
        assertEquals(expected.g, actual.g, label + " (g)");
        assertEquals(expected.b, actual.b, label + " (b)");
    }
}
