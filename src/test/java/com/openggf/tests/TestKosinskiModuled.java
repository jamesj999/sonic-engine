package com.openggf.tests;

import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.data.compression.KosinskiReader;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Kosinski Moduled (KosM) decompression.
 * Uses the shipped AIZ1 KosM streams from the configured locked-on ROM.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestKosinskiModuled {

    private static final int AIZ1_PRIMARY_KOSM = 0x3A566A;
    private static final int AIZ1_PRIMARY_KOSM_SIZE = 0x0E12;
    private static final int AIZ1_SECONDARY_KOSM = 0x3A647C;
    private static final int AIZ1_SECONDARY_KOSM_SIZE = 0x2FD2;
    private static final int AIZ1_MAIN_LEVEL_KOSM = 0x3A944E;
    private static final int AIZ1_MAIN_LEVEL_KOSM_SIZE = 0x27F2;

    @Test
    public void testDecompressAiz1Primary() throws Exception {
        byte[] compressed = readRomBytes(AIZ1_PRIMARY_KOSM, AIZ1_PRIMARY_KOSM_SIZE);
        assertTrue(compressed.length > 2, "File should have data");

        // Read expected size from big-endian header
        int expectedSize = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);
        assertTrue(expectedSize > 0, "Header size should be positive");

        byte[] decompressed = KosinskiReader.decompressModuled(compressed, 0);

        assertEquals(expectedSize, decompressed.length, "Decompressed size should match header");
        System.out.printf("AIZ1 Primary: %d bytes compressed -> %d bytes decompressed (header: 0x%04X)%n",
                compressed.length, decompressed.length, expectedSize);
    }

    @Test
    public void testDecompressAiz1MainLevel() throws Exception {
        byte[] compressed = readRomBytes(AIZ1_MAIN_LEVEL_KOSM, AIZ1_MAIN_LEVEL_KOSM_SIZE);
        int expectedSize = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);

        byte[] decompressed = KosinskiReader.decompressModuled(compressed, 0);

        assertEquals(expectedSize, decompressed.length, "Decompressed size should match header");
        System.out.printf("AIZ1 Main Level: %d bytes compressed -> %d bytes decompressed (header: 0x%04X)%n",
                compressed.length, decompressed.length, expectedSize);
    }

    @Test
    public void testDecompressAiz1Secondary() throws Exception {
        byte[] compressed = readRomBytes(AIZ1_SECONDARY_KOSM, AIZ1_SECONDARY_KOSM_SIZE);
        int expectedSize = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);

        byte[] decompressed = KosinskiReader.decompressModuled(compressed, 0);

        assertEquals(expectedSize, decompressed.length, "Decompressed size should match header");
        System.out.printf("AIZ1 Secondary: %d bytes compressed -> %d bytes decompressed (header: 0x%04X)%n",
                compressed.length, decompressed.length, expectedSize);
    }

    @Test
    public void testDecompressWithOffset() throws Exception {
        byte[] compressed = readRomBytes(AIZ1_PRIMARY_KOSM, AIZ1_PRIMARY_KOSM_SIZE);

        // Wrap the data in a larger array with an offset
        byte[] padded = new byte[compressed.length + 100];
        System.arraycopy(compressed, 0, padded, 50, compressed.length);

        byte[] decompressed = KosinskiReader.decompressModuled(padded, 50);

        int expectedSize = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);
        assertEquals(expectedSize, decompressed.length, "Decompressed size should match header");
    }

    @Test
    public void testMultipleModules() throws Exception {
        // AIZ1 Main Level is large enough to have multiple modules (0x1000 bytes each)
        byte[] compressed = readRomBytes(AIZ1_MAIN_LEVEL_KOSM, AIZ1_MAIN_LEVEL_KOSM_SIZE);
        int expectedSize = ((compressed[0] & 0xFF) << 8) | (compressed[1] & 0xFF);

        // Expected size > 0x1000 means multiple modules
        assertTrue(expectedSize > 0x1000, "Expected multi-module data (size > 0x1000)");

        byte[] decompressed = KosinskiReader.decompressModuled(compressed, 0);
        assertEquals(expectedSize, decompressed.length, "Decompressed size should match header");

        int moduleCount = (expectedSize + 0xFFF) / 0x1000;
        System.out.printf("AIZ1 Main Level: %d modules (0x%04X = %d bytes decompressed)%n",
                moduleCount, expectedSize, expectedSize);
    }

    @Test
    public void testEmptyHeader() throws Exception {
        // A header of 0x0000 should return empty array
        byte[] data = {0x00, 0x00};
        byte[] result = KosinskiReader.decompressModuled(data, 0);
        assertEquals(0, result.length, "Empty header should produce empty output");
    }

    @Test
    public void testTruncatedHeader() throws Exception {
        // Only 1 byte - not enough for the header
        byte[] data = {0x10};
        assertThrows(java.io.IOException.class, () -> KosinskiReader.decompressModuled(data, 0));
    }

    private static byte[] readRomBytes(int address, int length) throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getAbsolutePath()), "Failed to open S3K ROM");
            return rom.readBytes(address, length);
        }
    }
}

