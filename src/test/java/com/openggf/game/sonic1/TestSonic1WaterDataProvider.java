package com.openggf.game.sonic1;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayerCharacter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Sonic1WaterDataProvider}.
 * Verifies water presence and starting heights match the ROM's
 * LZWaterFeatures.asm WaterHeight table (lines 49-52).
 */
public class TestSonic1WaterDataProvider {
    private final Sonic1WaterDataProvider provider = new Sonic1WaterDataProvider();

    private static final int ZONE_GHZ = 0x00;
    private static final int ZONE_LZ = 0x01;
    private static final int ZONE_MZ = 0x02;
    private static final int ZONE_SLZ = 0x03;
    private static final int ZONE_SYZ = 0x04;
    private static final int ZONE_SBZ = 0x05;

    // --- hasWater tests ---

    @Test
    public void testLzHasWater() {
        assertTrue(provider.hasWater(ZONE_LZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_LZ, 1, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_LZ, 2, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSbz3HasWater() {
        assertTrue(provider.hasWater(ZONE_SBZ, 2, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSbz1NoWater() {
        assertFalse(provider.hasWater(ZONE_SBZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSbz2NoWater() {
        assertFalse(provider.hasWater(ZONE_SBZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testGhzNoWater() {
        assertFalse(provider.hasWater(ZONE_GHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testMzNoWater() {
        assertFalse(provider.hasWater(ZONE_MZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSlzNoWater() {
        assertFalse(provider.hasWater(ZONE_SLZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testSyzNoWater() {
        assertFalse(provider.hasWater(ZONE_SYZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    // --- getStartingWaterLevel tests ---

    @Test
    public void testLz1Height() {
        assertEquals(0x00B8, provider.getStartingWaterLevel(ZONE_LZ, 0));
    }

    @Test
    public void testLz2Height() {
        assertEquals(0x0328, provider.getStartingWaterLevel(ZONE_LZ, 1));
    }

    @Test
    public void testLz3Height() {
        assertEquals(0x0900, provider.getStartingWaterLevel(ZONE_LZ, 2));
    }

    @Test
    public void testSbz3Height() {
        assertEquals(0x0228, provider.getStartingWaterLevel(ZONE_SBZ, 2));
    }

    @Test
    public void testNonWaterZoneReturnsDefault() {
        // Zones without water return the off-screen default (0x0600)
        assertEquals(0x0600, provider.getStartingWaterLevel(ZONE_GHZ, 0));
    }

    // --- getDynamicHandler tests ---

    @Test
    public void testNoDynamicHandler() {
        // S1 water is static; no dynamic handlers
        assertNull(provider.getDynamicHandler(ZONE_LZ, 0, PlayerCharacter.SONIC_AND_TAILS), "LZ should have no dynamic water handler");
        assertNull(provider.getDynamicHandler(ZONE_SBZ, 2, PlayerCharacter.SONIC_AND_TAILS), "SBZ3 should have no dynamic water handler");
    }

    // --- getWaterSpeed tests ---

    @Test
    public void testDefaultWaterSpeed() {
        // WaterDataProvider default is 1
        assertEquals(1, provider.getWaterSpeed(ZONE_LZ, 0));
    }

    // --- getVisualWaterLevelOffset tests ---

    @Test
    public void testLzVisualOffsetAtResetIsZero() {
        // ROM (LZWaterFeatures.asm): byte at v_oscillate+2, lsr #1, added to
        // v_waterpos2. After resetForSonic1(), oscillator 0's value word is
        // 0x0080 -> high byte 0x00 -> expected offset = 0 >> 1 = 0.
        OscillationManager.resetForSonic1();
        assertEquals(0, provider.getVisualWaterLevelOffset(ZONE_LZ, 0));
    }

    @Test
    public void testLzVisualOffsetTracksOscillatorAfterStepping() {
        // After stepping the oscillator, getByte(0) moves off zero and the
        // provider must return getByte(0) >> 1.
        OscillationManager.resetForSonic1();
        for (int frame = 1; frame <= 50; frame++) {
            OscillationManager.update(frame);
        }
        int byte0 = OscillationManager.getByte(0);
        assertNotEquals(0, byte0, "Oscillator 0 should have advanced after 50 update() calls");
        assertEquals(byte0 >> 1, provider.getVisualWaterLevelOffset(ZONE_LZ, 0));
    }

    @Test
    public void testSbz3VisualOffsetTracksOscillatorAfterStepping() {
        // SBZ3 reuses LZ water mechanics, so it must produce the same formula.
        OscillationManager.resetForSonic1();
        for (int frame = 1; frame <= 50; frame++) {
            OscillationManager.update(frame);
        }
        int byte0 = OscillationManager.getByte(0);
        assertNotEquals(0, byte0, "Oscillator 0 should have advanced after 50 update() calls");
        assertEquals(byte0 >> 1, provider.getVisualWaterLevelOffset(ZONE_SBZ, 2));
    }

    @Test
    public void testGhzVisualOffsetIsZero() {
        // Non-water zones report no oscillation offset.
        OscillationManager.resetForSonic1();
        assertEquals(0, provider.getVisualWaterLevelOffset(ZONE_GHZ, 0));
    }
}


