package com.openggf.game.sonic2;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Sonic2WaterDataProvider}.
 * Verifies water zone detection and starting water heights against
 * known ROM values from the S2 Water_Height table.
 */
public class TestSonic2WaterDataProvider {
    private final Sonic2WaterDataProvider provider = new Sonic2WaterDataProvider();

    private static final int ZONE_EHZ = Sonic2ZoneConstants.ROM_ZONE_EHZ; // 0x00
    private static final int ZONE_CPZ = Sonic2ZoneConstants.ROM_ZONE_CPZ; // 0x0D
    private static final int ZONE_ARZ = Sonic2ZoneConstants.ROM_ZONE_ARZ; // 0x0F
    private static final int ZONE_HTZ = Sonic2ZoneConstants.ROM_ZONE_HTZ; // 0x07
    private static final int ZONE_MCZ = Sonic2ZoneConstants.ROM_ZONE_MCZ; // 0x0B
    private static final int ZONE_CNZ = Sonic2ZoneConstants.ROM_ZONE_CNZ; // 0x0C
    private static final int ZONE_OOZ = Sonic2ZoneConstants.ROM_ZONE_OOZ; // 0x0A
    private static final int ZONE_DEZ = Sonic2ZoneConstants.ROM_ZONE_DEZ; // 0x0E

    // =========================================================================
    // hasWater() tests
    // =========================================================================

    @Test
    public void testCpzWaterByAct() {
        assertFalse(provider.hasWater(ZONE_CPZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_CPZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testArzHasWater() {
        assertTrue(provider.hasWater(ZONE_ARZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertTrue(provider.hasWater(ZONE_ARZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testHtzNoWater() {
        // HTZ lava is a background visual effect, not water (s2.asm Level_InitWater)
        assertFalse(provider.hasWater(ZONE_HTZ, 0, PlayerCharacter.SONIC_AND_TAILS));
        assertFalse(provider.hasWater(ZONE_HTZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testEhzNoWater() {
        assertFalse(provider.hasWater(ZONE_EHZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testMczNoWater() {
        assertFalse(provider.hasWater(ZONE_MCZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testCnzNoWater() {
        assertFalse(provider.hasWater(ZONE_CNZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testOozNoWater() {
        assertFalse(provider.hasWater(ZONE_OOZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testDezNoWater() {
        assertFalse(provider.hasWater(ZONE_DEZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testWaterIgnoresCharacter() {
        // Water zones are the same for all characters in S2
        assertTrue(provider.hasWater(ZONE_ARZ, 0, PlayerCharacter.SONIC_ALONE));
        assertTrue(provider.hasWater(ZONE_ARZ, 0, PlayerCharacter.TAILS_ALONE));
        assertTrue(provider.hasWater(ZONE_ARZ, 0, PlayerCharacter.KNUCKLES));
    }

    // =========================================================================
    // getStartingWaterLevel() tests
    // =========================================================================

    @Test
    public void testCpz2Height() {
        // ROM Water_Height table at 0x459A = 0x0710
        assertEquals(0x0710, provider.getStartingWaterLevel(ZONE_CPZ, 1));
    }

    @Test
    public void testArz1Height() {
        // ROM Water_Height table at 0x45A0 = 0x0410
        assertEquals(0x0410, provider.getStartingWaterLevel(ZONE_ARZ, 0));
    }

    @Test
    public void testArz2Height() {
        // ROM Water_Height table at 0x45A2 = 0x0510
        assertEquals(0x0510, provider.getStartingWaterLevel(ZONE_ARZ, 1));
    }

    @Test
    public void testCpz1HeightIsDefault() {
        // CPZ Act 1 has no specific ROM entry; uses default
        assertEquals(0, provider.getStartingWaterLevel(ZONE_CPZ, 0));
    }

    // =========================================================================
    // Default method / null-safe tests
    // =========================================================================

    @Test
    public void testUnderwaterPaletteReturnsNull() {
        // Palette loading deferred to existing WaterSystem
        assertNull(provider.getUnderwaterPalette(null, ZONE_ARZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testDynamicHandlerReturnsNull() {
        // Dynamic water handled by existing LevelEventManager
        assertNull(provider.getDynamicHandler(ZONE_CPZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    public void testDefaultWaterSpeed() {
        // S2 uses default water speed of 1
        assertEquals(1, provider.getWaterSpeed(ZONE_ARZ, 0));
        assertEquals(1, provider.getWaterSpeed(ZONE_CPZ, 1));
    }

    // =========================================================================
    // getGameplayWaterLevelOffset() tests
    // =========================================================================

    @Test
    public void testCpz2GameplayOffsetTracksRawRomOscillationAfterStepping() {
        // ROM MoveWater writes Water_Level_1 = Water_Level_2 +
        // ((Oscillating_Data).w >> 1), then Sonic_Water compares against
        // Water_Level_1. Refs: docs/s2disasm/s2.asm:5273-5282 and
        // docs/s2disasm/s2.asm:36375-36380.
        OscillationManager.reset();
        for (int frame = 1; frame <= 50; frame++) {
            OscillationManager.update(frame);
        }
        int byte0 = OscillationManager.getByte(0);
        assertNotEquals(0, byte0, "Oscillator 0 should have advanced after 50 update() calls");
        assertEquals(byte0 >> 1, provider.getGameplayWaterLevelOffset(ZONE_CPZ, 1));
    }

    @Test
    public void testArzGameplayOffsetIsZero() {
        // ROM MoveWater skips the oscillator for ARZ before writing Water_Level_1.
        // Refs: docs/s2disasm/s2.asm:5275-5282.
        OscillationManager.reset();
        assertEquals(0, provider.getGameplayWaterLevelOffset(ZONE_ARZ, 0));
    }

    // =========================================================================
    // getVisualWaterLevelOffset() tests
    // =========================================================================

    @Test
    public void testCpz2VisualOffsetAtResetIsMinusEight() {
        // CPZ centres the bob around 0 by subtracting half the limit (8) from
        // oscillator 0's high byte. After reset, oscillator 0's value word is
        // 0x0080 -> high byte 0x00 -> expected offset = 0 - 8 = -8.
        OscillationManager.reset();
        assertEquals(-8, provider.getVisualWaterLevelOffset(ZONE_CPZ, 1));
    }

    @Test
    public void testCpz2VisualOffsetTracksOscillatorAfterStepping() {
        // After stepping the oscillator several frames, getByte(0) must move
        // off zero and the provider must return getByte(0) - 8.
        OscillationManager.reset();
        for (int frame = 1; frame <= 50; frame++) {
            OscillationManager.update(frame);
        }
        int byte0 = OscillationManager.getByte(0);
        assertNotEquals(0, byte0, "Oscillator 0 should have advanced after 50 update() calls");
        assertEquals(byte0 - 8, provider.getVisualWaterLevelOffset(ZONE_CPZ, 1));
    }

    @Test
    public void testArzVisualOffsetIsZero() {
        // S2 ARZ has a static water surface (no oscillation offset applied).
        OscillationManager.reset();
        assertEquals(0, provider.getVisualWaterLevelOffset(ZONE_ARZ, 0));
    }

    @Test
    public void testEhzVisualOffsetIsZero() {
        // Non-water zones report no oscillation offset.
        OscillationManager.reset();
        assertEquals(0, provider.getVisualWaterLevelOffset(ZONE_EHZ, 0));
    }
}

