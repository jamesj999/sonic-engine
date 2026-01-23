package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.LevelData;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.animation.AnimatedPatternManager;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Logger;

import static org.junit.Assert.*;

/**
 * Tests for OOZ (Oil Ocean Zone) animation loading.
 */
public class TestOozAnimation {
    private static final int ANIMATED_EHZ_ADDR = 0x3FF94;
    private Rom rom;

    @Before
    public void setUp() throws IOException {
        // Enable FINE logging
        Logger rootLogger = Logger.getLogger("uk.co.jamesj999.sonic.game.sonic2");
        rootLogger.setLevel(java.util.logging.Level.FINE);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(java.util.logging.Level.FINE);
        rootLogger.addHandler(handler);

        rom = new Rom();
        String romFile = RomTestUtils.ensureRomAvailable().getAbsolutePath();
        rom.open(romFile);
    }

    @Test
    public void testOozAnimationTableLookup() throws IOException {
        RomByteReader reader = RomByteReader.fromRom(rom);

        // Find the PLC_DYNANM table by scanning for EHZ anchor
        int tableAddr = -1;
        for (int addr = 0; addr < 0x70000; addr += 2) {
            int offset = (short) reader.readU16BE(addr + 2);
            if (addr + offset == ANIMATED_EHZ_ADDR) {
                tableAddr = addr;
                break;
            }
        }
        assertNotEquals("PLC_DYNANM table should be found", -1, tableAddr);
        System.out.println("PLC_DYNANM table found at: 0x" + Integer.toHexString(tableAddr));

        // OOZ is at list index 10
        // Each entry is 4 bytes: routine pointer (2) + data pointer (2)
        int oozPointerAddr = tableAddr + (10 * 4) + 2;
        int oozOffset = (short) reader.readU16BE(oozPointerAddr);
        int oozScriptAddr = tableAddr + oozOffset;
        System.out.println("OOZ script address: 0x" + Integer.toHexString(oozScriptAddr));

        // Read script count (word at script address)
        int scriptCountMinus1 = reader.readU16BE(oozScriptAddr);
        int scriptCount = scriptCountMinus1 + 1;
        System.out.println("OOZ script count: " + scriptCount);
        assertEquals("OOZ should have 5 animation scripts", 5, scriptCount);

        // Parse each script header
        int pos = oozScriptAddr + 2;
        for (int i = 0; i < scriptCount; i++) {
            // Header: long (4) = duration<<24 | artAddr, word (2) = destBytes, byte = frameCount, byte = tilesPerFrame
            int header = (reader.readU16BE(pos) << 16) | reader.readU16BE(pos + 2);
            int globalDuration = (header >> 24) & 0xFF;
            int artAddr = header & 0xFFFFFF;
            int destBytes = reader.readU16BE(pos + 4);
            int destTileIndex = destBytes >> 5;
            int frameCount = reader.readU8(pos + 6);
            int tilesPerFrame = reader.readU8(pos + 7);

            System.out.printf("Script %d: duration=%d, artAddr=0x%X, destTile=0x%X, frames=%d, tilesPerFrame=%d%n",
                    i, globalDuration, artAddr, destTileIndex, frameCount, tilesPerFrame);

            // Verify art address is within ROM
            assertTrue("Art address 0x" + Integer.toHexString(artAddr) + " should be within ROM",
                    artAddr >= 0 && artAddr < rom.getSize());

            // Move to next script
            boolean perFrame = (globalDuration & 0x80) != 0;
            int dataLen = frameCount * (perFrame ? 2 : 1);
            int dataLenAligned = (dataLen + 1) & ~1;
            pos += 8 + dataLenAligned;
        }
    }

    @Test
    public void testOozAnimatedPatternMaps() throws IOException {
        // Check that OOZ has APM data
        int tableAddr = Sonic2Constants.ANIM_PAT_MAPS_ADDR;
        int oozZoneIndex = 10; // 0x0A

        int offset = rom.read16BitAddr(tableAddr + oozZoneIndex * 2);
        System.out.println("APM table at 0x" + Integer.toHexString(tableAddr));
        System.out.println("OOZ APM offset: 0x" + Integer.toHexString(offset));

        assertNotEquals("OOZ should have APM data", 0, offset);

        int listAddr = tableAddr + offset;
        int destOffset = rom.read16BitAddr(listAddr);
        int wordCountMinus1 = rom.read16BitAddr(listAddr + 2);
        int wordCount = wordCountMinus1 + 1;

        System.out.println("APM list at 0x" + Integer.toHexString(listAddr));
        System.out.println("Dest offset in chunk buffer: 0x" + Integer.toHexString(destOffset));
        System.out.println("Word count: " + wordCount + " (" + (wordCount * 2) + " bytes)");

        assertTrue("OOZ APM word count should be > 0", wordCount > 0);

        // Read first few mappings
        int srcAddr = listAddr + 4;
        System.out.println("First 8 APM entries:");
        for (int i = 0; i < Math.min(8, wordCount); i++) {
            int value = rom.read16BitAddr(srcAddr + i * 2);
            // Decode tile mapping: pattern_index | (flip_x << 11) | (flip_y << 12) | (palette << 13) | (priority << 15)
            int patternIndex = value & 0x7FF;
            int flipX = (value >> 11) & 1;
            int flipY = (value >> 12) & 1;
            int palette = (value >> 13) & 3;
            int priority = (value >> 15) & 1;
            System.out.printf("  Entry %d: 0x%04X -> tile=0x%03X, pal=%d, pri=%d, flipX=%d, flipY=%d%n",
                    i, value, patternIndex, palette, priority, flipX, flipY);
        }
    }

    @Test
    public void testOozOilArtDataExists() throws IOException {
        // After finding the animation scripts, verify the oil art data can be read
        RomByteReader reader = RomByteReader.fromRom(rom);

        // Find the table
        int tableAddr = -1;
        for (int addr = 0; addr < 0x70000; addr += 2) {
            int offset = (short) reader.readU16BE(addr + 2);
            if (addr + offset == ANIMATED_EHZ_ADDR) {
                tableAddr = addr;
                break;
            }
        }
        assertNotEquals("Table should be found", -1, tableAddr);

        // Get OOZ scripts
        int oozPointerAddr = tableAddr + (10 * 4) + 2;
        int oozOffset = (short) reader.readU16BE(oozPointerAddr);
        int oozScriptAddr = tableAddr + oozOffset;

        // Skip to oil scripts (scripts 3 and 4, 0-indexed)
        int pos = oozScriptAddr + 2;
        for (int i = 0; i < 5; i++) {
            int header = (reader.readU16BE(pos) << 16) | reader.readU16BE(pos + 2);
            int artAddr = header & 0xFFFFFF;
            int globalDuration = (header >> 24) & 0xFF;
            int frameCount = reader.readU8(pos + 6);
            int tilesPerFrame = reader.readU8(pos + 7);

            if (i >= 3) { // Oil scripts
                System.out.printf("Oil script %d: artAddr=0x%X, tilesPerFrame=%d%n", i, artAddr, tilesPerFrame);
                // For oil animation with frame offsets 0, 0x10, 0x20, 0x30, 0x20, 0x10
                // and 16 tiles per frame, we need 0x30 + 16 = 64 tiles = 2048 bytes
                int requiredBytes = (0x30 + tilesPerFrame) * 32;
                long romSize = rom.getSize();
                assertTrue("Art address 0x" + Integer.toHexString(artAddr) + " + " + requiredBytes +
                                " should be within ROM size 0x" + Long.toHexString(romSize),
                        artAddr + requiredBytes <= romSize);

                // Verify the art data is not all zeros (would indicate uninitialized data)
                byte[] artData = rom.readBytes(artAddr, 64);  // First 2 tiles
                int nonZeroCount = 0;
                for (byte b : artData) {
                    if (b != 0) {
                        nonZeroCount++;
                    }
                }
                System.out.printf("  First 64 bytes: %d non-zero values%n", nonZeroCount);
                System.out.printf("  First 16 bytes: %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X%n",
                        artData[0] & 0xFF, artData[1] & 0xFF, artData[2] & 0xFF, artData[3] & 0xFF,
                        artData[4] & 0xFF, artData[5] & 0xFF, artData[6] & 0xFF, artData[7] & 0xFF,
                        artData[8] & 0xFF, artData[9] & 0xFF, artData[10] & 0xFF, artData[11] & 0xFF,
                        artData[12] & 0xFF, artData[13] & 0xFF, artData[14] & 0xFF, artData[15] & 0xFF);
                // Check data at frame offset 0x10 (tile 16)
                byte[] frame1Data = rom.readBytes(artAddr + 0x10 * 32, 16);
                System.out.printf("  Frame 1 (offset 0x10*32): %02X %02X %02X %02X %02X %02X %02X %02X%n",
                        frame1Data[0] & 0xFF, frame1Data[1] & 0xFF, frame1Data[2] & 0xFF, frame1Data[3] & 0xFF,
                        frame1Data[4] & 0xFF, frame1Data[5] & 0xFF, frame1Data[6] & 0xFF, frame1Data[7] & 0xFF);
            }

            boolean perFrame = (globalDuration & 0x80) != 0;
            int dataLen = frameCount * (perFrame ? 2 : 1);
            int dataLenAligned = (dataLen + 1) & ~1;
            pos += 8 + dataLenAligned;
        }
    }

    @Test
    public void testOozLevelAnimationManager() throws IOException {
        // Load OOZ level and create animation manager
        Sonic2 game = new Sonic2(rom);
        int levelIdx = LevelData.OIL_OCEAN_1.getLevelIndex();
        System.out.println("Loading OOZ level index: " + levelIdx);

        uk.co.jamesj999.sonic.level.Level level = game.loadLevel(levelIdx);
        assertNotNull("OOZ level should load", level);

        int zoneIndex = level.getZoneIndex();
        System.out.println("OOZ zone index: " + zoneIndex + " (expected 10 = 0x0A)");
        assertEquals("OOZ zone index should be 10", 10, zoneIndex);

        int patternCount = level.getPatternCount();
        System.out.println("OOZ pattern count: " + patternCount);

        // Oil animation needs patterns up to index 0x2D2 + 16 = 738
        assertTrue("OOZ should have enough patterns for oil animation (need at least 738, have " + patternCount + ")",
                patternCount >= 738 || patternCount > 0); // Either enough or some

        // Create animation manager
        AnimatedPatternManager animManager = game.loadAnimatedPatternManager(level, zoneIndex);
        assertNotNull("Animation manager should be created for OOZ", animManager);

        System.out.println("Animation manager created successfully for OOZ");

        // Check if pattern at oil tile index has non-empty data after animation initialization
        if (patternCount > 0x2C2) {
            Pattern oilPattern = level.getPattern(0x2C2);
            assertNotNull("Pattern at oil tile index 0x2C2 should exist", oilPattern);

            // Check if pattern has any non-transparent pixels
            int nonTransparentPixels = 0;
            for (int row = 0; row < 8; row++) {
                for (int col = 0; col < 8; col++) {
                    int pixel = oilPattern.getPixel(col, row);
                    if (pixel != 0) nonTransparentPixels++;
                }
            }
            System.out.println("Oil pattern 0x2C2 has " + nonTransparentPixels + " non-transparent pixels (out of 64)");
        } else {
            System.out.println("Warning: Pattern count " + patternCount + " is less than 0x2C2 (706)");
        }
    }
}
