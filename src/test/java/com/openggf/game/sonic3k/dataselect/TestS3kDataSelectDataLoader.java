package com.openggf.game.sonic3k.dataselect;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kDataSelectDataLoader {

    @Test
    void loader_rejectsOddByteWordPayloads() throws Exception {
        S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(new RomByteReader(new byte[0]));
        Method method = S3kDataSelectDataLoader.class.getDeclaredMethod("wordsFromBytes", byte[].class);
        method.setAccessible(true);

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(loader, (Object) new byte[]{0x12, 0x34, 0x56}));

        assertInstanceOf(IllegalArgumentException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("odd"));
    }

    @Test
    void copyRegion_readsRowsFromPackedSourceBlockStride() throws Exception {
        S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(new RomByteReader(new byte[0]));
        Method method = S3kDataSelectDataLoader.class.getDeclaredMethod(
                "copyRegion", int[].class, int.class, int.class, int.class, int.class, int[].class);
        method.setAccessible(true);

        int[] sourceWords = new int[18];
        for (int row = 0; row < 6; row++) {
            for (int col = 0; col < 3; col++) {
                sourceWords[row * 3 + col] = (row << 8) | col;
            }
        }
        int[] planeWords = new int[128 * 32];

        method.invoke(loader, sourceWords, (2 * 2), 0x0102, 3, 2, planeWords);

        int firstRowDestIndex = 0x0102 / 2;
        int secondRowDestIndex = firstRowDestIndex + 128;
        assertArrayEquals(new int[]{0x0002, 0x0100, 0x0101},
                java.util.Arrays.copyOfRange(planeWords, firstRowDestIndex, firstRowDestIndex + 3));
        assertArrayEquals(new int[]{0x0102, 0x0200, 0x0201},
                java.util.Arrays.copyOfRange(planeWords, secondRowDestIndex, secondRowDestIndex + 3));
    }

    @Test
    void layoutOriginal_matchesDisassemblyWorldCoordinates() {
        S3kDataSelectLayout layout = S3kDataSelectLayout.original();

        assertEquals(0xB0, layout.noSaveWorldX());
        assertEquals(0x448, layout.deleteWorldX());
        assertEquals(0x110, layout.slotWorldXStart());
        assertEquals(0x68, layout.slotWorldXStep());
        assertEquals(0x108, layout.slotWorldY());
        assertEquals(0x110, layout.slotWorldX(0));
        assertEquals(0x178, layout.slotWorldX(1));
    }

    @Test
    void saveScreenLayoutEnigmaBase_matchesDisassemblyPriorityBit() {
        assertEquals(Sonic3kConstants.ARTTILE_SAVE_MISC | 0x8000,
                Sonic3kConstants.ENIGMA_BASE_SAVE_SCREEN_LAYOUT);
    }

    @Test
    void loader_readsSaveScreenAssetsAndMusicMetadata_fromRealS3kRom() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();

        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getPath()), "Failed to open S3K ROM");

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(rom));
            loader.loadData();

            assertTrue(loader.getLayoutWords().length > 0);
            assertEquals(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_NEW_SIZE / 2,
                    loader.getNewLayoutWords().length);
            assertTrue(loader.getMenuBackgroundLayoutWords().length > 0);

            assertTrue(loader.getMenuBackgroundPatterns().length > 0);
            assertTrue(loader.getMiscPatterns().length > 0);
            assertTrue(loader.getExtraPatterns().length > 0);
            assertTrue(loader.getSkZonePatterns().length > 0);
            assertTrue(loader.getPortraitPatterns().length > 0);
            assertTrue(loader.getS3ZonePatterns().length > 0);
            assertEquals(70, loader.getSlotIconPatterns(0).length);
            assertEquals(70, loader.getSlotIconPatterns(14).length);

            assertTrue(loader.getSaveScreenMappings().size() > 13);
            assertTrue(loader.getSaveScreenMappings().stream().anyMatch(frame -> !frame.pieces().isEmpty()));

            assertTrue(loader.getMenuBackgroundPaletteBytes().length > 0);
            assertTrue(loader.getCharacterPaletteBytes().length > 0);
            assertTrue(loader.getEmeraldPaletteBytes().length > 0);
            assertEquals(3, loader.getFinishCardPalettes().length);
            for (byte[] palette : loader.getFinishCardPalettes()) {
                assertTrue(palette.length > 0);
            }
            assertEquals(15, loader.getZoneCardPalettes().length);
            for (byte[] palette : loader.getZoneCardPalettes()) {
                assertTrue(palette.length > 0);
            }
            assertTrue(loader.getS3ZoneCard8PaletteBytes().length > 0);
            assertEquals(4, loader.getStaticLayouts().length);
            assertEquals(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_1_SIZE / 2,
                    loader.getStaticLayouts()[0].length);
            assertEquals(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_2_SIZE / 2,
                    loader.getStaticLayouts()[1].length);
            assertEquals(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_3_SIZE / 2,
                    loader.getStaticLayouts()[2].length);
            assertEquals(Sonic3kConstants.MAP_UNC_SAVE_SCREEN_STATIC_4_SIZE / 2,
                    loader.getStaticLayouts()[3].length);
            assertEquals(Sonic3kMusic.DATA_SELECT.id, loader.getMusicId());
        }
    }
}
