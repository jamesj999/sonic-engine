package com.openggf.game.sonic3k.dataselect;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestHostEmeraldPresentation {

    @Test
    void s1HostEmeraldsKeepNativeS3kDonorPalette() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        Rom s1Rom = TestEnvironment.currentRom();
        try (Rom s3kRom = new Rom()) {
            assumeTrue(s3kRom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");
            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(s3kRom));
            loader.loadData();

            HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost(
                    "s1",
                    s1Rom,
                    loader.getEmeraldPaletteBytes());

            assertEquals(6, result.activeEmeraldCount());
            assertEquals(readGenesisWords(loader.getEmeraldPaletteBytes()), readGenesisWords(result.paletteBytes()));
            assertFalse(result.usesRetintedRamp());
        }
    }

    @Test
    void s2HostEmeraldsKeepNativeLine2PaletteAndExposeCustomPurplePalette() throws Exception {
        File s2RomFile = RomTestUtils.ensureSonic2RomAvailable();
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s2RomFile != null && s2RomFile.exists(), "Sonic 2 ROM not available");
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom s2Rom = new Rom(); Rom s3kRom = new Rom()) {
            assumeTrue(s2Rom.open(s2RomFile.getPath()), "Failed to open Sonic 2 ROM");
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(s3kRom));
            loader.loadData();
            List<Integer> nativeWords = readGenesisWords(loader.getEmeraldPaletteBytes());

            HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost(
                    "s2",
                    s2Rom,
                    loader.getEmeraldPaletteBytes());
            List<Integer> actualWords = readGenesisWords(result.paletteBytes());
            List<Integer> purpleWords = readGenesisWords(result.customPurplePaletteBytes());

            assertEquals(7, result.activeEmeraldCount());
            assertEquals(15, actualWords.size());
            assertEquals(nativeWords, actualWords,
                    "S2 should keep the shared line-2 emerald palette native so blue and green stay correct");
            assertEquals(15, purpleWords.size());
            assertFalse(result.usesRetintedRamp());
            assertEquals(nativeWords.subList(0, 8), purpleWords.subList(0, 8));
            assertEquals(nativeWords.subList(10, 15), purpleWords.subList(10, 15));
            assertNotEquals(nativeWords.get(8), purpleWords.get(8));
            assertNotEquals(nativeWords.get(9), purpleWords.get(9));
            assertTrue(purpleWords.get(8) != 0x0EE4 && purpleWords.get(9) != 0x0E60,
                    "Dedicated purple frame palette should not keep the native pale-blue/cyan colours");
        }
    }

    @Test
    void invalidHostRomFallsBackToEmptyPaletteBytes() {
        HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", null);

        assertEquals(0, result.paletteBytes().length);
        assertEquals(7, result.layout().positions().size());
    }

    @Test
    void invalidHostGameFallsBackToEmptyPaletteBytes() {
        Rom rom = TestEnvironment.currentRom();

        HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("nope", rom);

        assertEquals(0, result.paletteBytes().length);
        assertEquals(7, result.layout().positions().size());
    }

    @Test
    void s1PresentationPaletteDiffersFromLegacyDirectBuilderEntryPoint() throws Exception {
        Rom rom = TestEnvironment.currentRom();

        HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", rom);
        byte[] legacy = HostEmeraldPaletteBuilder.buildForHostGame("s1", rom);

        assertEquals(6, result.activeEmeraldCount());
        assertEquals(7 * 4 + 2, result.paletteBytes().length);
        assertEquals(7 * 4 + 2, legacy.length);
        assertFalse(java.util.Arrays.equals(result.paletteBytes(), legacy));
    }

    @Test
    void s1LayoutActiveCountMatchesPresentationResult() {
        Rom rom = TestEnvironment.currentRom();

        HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", rom);

        assertEquals(6, result.activeEmeraldCount());
        assertEquals(result.activeEmeraldCount(), result.layout().activeEmeraldCount());
        assertEquals(6, result.layout().positions().size());
    }

    @Test
    void s1SixRingUsesReferenceAlignedHexagonOffsets() {
        HostEmeraldLayoutProfile layout = HostEmeraldLayoutProfile.s1SixRing();

        assertEquals(6, layout.activeEmeraldCount());
        assertEquals(List.of(
                new HostEmeraldLayoutProfile.Point(25, -32),
                new HostEmeraldLayoutProfile.Point(-2, 2),
                new HostEmeraldLayoutProfile.Point(2, 2),
                new HostEmeraldLayoutProfile.Point(-22, 38),
                new HostEmeraldLayoutProfile.Point(33, -11),
                new HostEmeraldLayoutProfile.Point(-25, 18)),
                layout.positions());
    }

    @Test
    void derivedPurplePairPreservesPinkShadeOrdering() {
        List<HostEmeraldPaletteBuilder.GenesisColour> nativeRamp = List.of(
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0EEE),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0444),
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0000));
        byte[] paletteBytes = HostEmeraldPaletteBuilder.composeMappedPaletteBytes(List.of(
                new HostEmeraldPaletteBuilder.EmeraldPalettePair(
                        HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0E4C),
                        HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(0x0C0A))),
                nativeRamp);
        HostEmeraldPaletteBuilder.GenesisColour highlight =
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(readGenesisWord(paletteBytes, 0));
        HostEmeraldPaletteBuilder.GenesisColour shadow =
                HostEmeraldPaletteBuilder.GenesisColour.fromGenesisWord(readGenesisWord(paletteBytes, 2));

        assertTrue(highlight.brightness() > shadow.brightness());
        assertEquals(0x0E4C, highlight.toGenesisWord());
        assertEquals(0x0C0A, shadow.toGenesisWord());
        assertEquals(nativeRamp.get(nativeRamp.size() - 1).toGenesisWord(),
                readGenesisWord(paletteBytes, paletteBytes.length - 2));
    }

    @Test
    void nativeRampFromS3kPaletteBytes_matchesRealDonorEmeraldPalette() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null && romFile.exists(), "Sonic 3K ROM not available");

        try (Rom rom = new Rom()) {
            assumeTrue(rom.open(romFile.getPath()), "Failed to open Sonic 3K ROM");

            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(rom));
            loader.loadData();

            List<Integer> expected = readGenesisWords(loader.getEmeraldPaletteBytes());
            List<Integer> actual = HostEmeraldPaletteBuilder.nativeRampFromS3kPaletteBytes(
                    loader.getEmeraldPaletteBytes()).stream()
                    .map(HostEmeraldPaletteBuilder.GenesisColour::toGenesisWord)
                    .toList();

            assertEquals(expected, actual);
        }
    }

    @Test
    void s1PresentationDoesNotRetintPalette() {
        Rom rom = TestEnvironment.currentRom();
        HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost("s1", rom);
        assertFalse(result.usesRetintedRamp());
    }

    @Test
    void s2PresentationStillKeepsDistinctColourWordsForPurpleSlot() throws Exception {
        File s2RomFile = RomTestUtils.ensureSonic2RomAvailable();
        File s3kRomFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(s2RomFile != null && s2RomFile.exists(), "Sonic 2 ROM not available");
        assumeTrue(s3kRomFile != null && s3kRomFile.exists(), "Sonic 3K ROM not available");

        try (Rom s2Rom = new Rom(); Rom s3kRom = new Rom()) {
            assumeTrue(s2Rom.open(s2RomFile.getPath()), "Failed to open Sonic 2 ROM");
            assumeTrue(s3kRom.open(s3kRomFile.getPath()), "Failed to open Sonic 3K ROM");
            S3kDataSelectDataLoader loader = new S3kDataSelectDataLoader(RomByteReader.fromRom(s3kRom));
            loader.loadData();
            HostEmeraldPresentation.Result result = HostEmeraldPresentation.forHost(
                    "s2",
                    s2Rom,
                    loader.getEmeraldPaletteBytes());
            List<Integer> words = readGenesisWords(result.customPurplePaletteBytes());
            assertNotEquals(words.get(8), words.get(9));
        }
    }

    private static int readGenesisWord(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static List<Integer> readGenesisWords(byte[] bytes) {
        java.util.ArrayList<Integer> words = new java.util.ArrayList<>(bytes.length / 2);
        for (int offset = 0; offset + 1 < bytes.length; offset += 2) {
            words.add(readGenesisWord(bytes, offset));
        }
        return words;
    }

}
