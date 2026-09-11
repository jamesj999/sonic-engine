package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.level.Palette;
import com.openggf.game.GameServices;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for water system ROM extraction and validation.
 */
@RequiresRom(SonicGame.SONIC_2)
public class WaterSystemTest {

    private static final int WATER_SURFACE_OBJECT_ID = 0x04;

    // Zone IDs
    private static final int ZONE_EHZ = 0x00;
    private static final int ZONE_CPZ = 0x0D;
    private static final int ZONE_ARZ = 0x0F;

    // Expected water heights (from original game)
    private static final int CPZ_ACT_2_EXPECTED = 0x710;
    private static final int ARZ_ACT_1_EXPECTED = 0x410;
    private static final int ARZ_ACT_2_EXPECTED = 0x510;
    private WaterSystem waterSystem;
    private Rom rom;

    @BeforeEach
    public void setUp() {
        waterSystem = GameServices.water();
        waterSystem.reset();
        rom = com.openggf.tests.TestEnvironment.currentRom();
    }

    @Test
    public void testNoWaterInEHZ() {
        // EHZ has no water - should detect this
        List<ObjectSpawn> emptyObjects = new ArrayList<>();

        waterSystem.loadForLevel(rom, ZONE_EHZ, 0, emptyObjects);
        assertFalse(waterSystem.hasWater(ZONE_EHZ, 0), "EHZ Act 1 should not have water");
    }

    @Test
    public void testWaterDetectionFromObject() {
        // Simulate CPZ Act 2 with water surface object
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(0, CPZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        waterSystem.loadForLevel(rom, ZONE_CPZ, 1, objects);

        assertTrue(waterSystem.hasWater(ZONE_CPZ, 1), "CPZ Act 2 should have water");
        int waterY = waterSystem.getWaterLevelY(ZONE_CPZ, 1);

        // Allow small tolerance for extraction accuracy
        int tolerance = 5;
        assertTrue(Math.abs(waterY - CPZ_ACT_2_EXPECTED) <= tolerance, String.format("CPZ Act 2 water should be around %d (got %d)",
                CPZ_ACT_2_EXPECTED, waterY));
    }

    @Test
    public void testARZ1WaterHeight() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(100, ARZ_ACT_1_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        waterSystem.loadForLevel(rom, ZONE_ARZ, 0, objects);

        assertTrue(waterSystem.hasWater(ZONE_ARZ, 0), "ARZ Act 1 should have water");
        int waterY = waterSystem.getWaterLevelY(ZONE_ARZ, 0);

        int tolerance = 5;
        assertTrue(Math.abs(waterY - ARZ_ACT_1_EXPECTED) <= tolerance, String.format("ARZ Act 1 water should be around %d (got %d)",
                ARZ_ACT_1_EXPECTED, waterY));
    }

    @Test
    public void testARZ2WaterHeight() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(200, ARZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        waterSystem.loadForLevel(rom, ZONE_ARZ, 1, objects);

        assertTrue(waterSystem.hasWater(ZONE_ARZ, 1), "ARZ Act 2 should have water");
        int waterY = waterSystem.getWaterLevelY(ZONE_ARZ, 1);

        int tolerance = 5;
        assertTrue(Math.abs(waterY - ARZ_ACT_2_EXPECTED) <= tolerance, String.format("ARZ Act 2 water should be around %d (got %d)",
                ARZ_ACT_2_EXPECTED, waterY));
    }

    @Test
    public void testUnderwaterPaletteLoading() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(0, CPZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        waterSystem.loadForLevel(rom, ZONE_CPZ, 1, objects);

        Palette[] underwaterPalette = waterSystem.getUnderwaterPalette(ZONE_CPZ, 1);
        assertNotNull(underwaterPalette, "CPZ should have underwater palette");
        assertEquals(4, underwaterPalette.length, "Palette array should have 4 palettes");

        // Verify first palette has correct number of colors
        assertNotNull(underwaterPalette[0], "Palette 0 should not be null");
        assertEquals(16, underwaterPalette[0].colors.length, "Palette should have 16 colors");
    }

    @Test
    public void testDistortionTableGeneration() {
        int[] distortionTable = waterSystem.getDistortionTable();

        assertNotNull(distortionTable, "Distortion table should not be null");
        assertTrue(distortionTable.length > 0, "Distortion table should have reasonable size");

        // Verify table contains varied values (not all zeros)
        boolean hasVariation = false;
        for (int value : distortionTable) {
            if (value != 0) {
                hasVariation = true;
                break;
            }
        }
        assertTrue(hasVariation, "Distortion table should have variation");
    }

    @Test
    public void testMultipleLevelConfigs() {
        // Load config for multiple levels
        List<ObjectSpawn> cpzObjects = new ArrayList<>();
        cpzObjects.add(new ObjectSpawn(0, 700, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));
        waterSystem.loadForLevel(rom, ZONE_CPZ, 1, cpzObjects);

        List<ObjectSpawn> arzObjects = new ArrayList<>();
        arzObjects.add(new ObjectSpawn(0, 400, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));
        waterSystem.loadForLevel(rom, ZONE_ARZ, 0, arzObjects);

        List<ObjectSpawn> ehzObjects = new ArrayList<>();
        waterSystem.loadForLevel(rom, ZONE_EHZ, 0, ehzObjects);

        // Verify all configs are stored correctly
        assertTrue(waterSystem.hasWater(ZONE_CPZ, 1), "CPZ should have water");
        assertTrue(waterSystem.hasWater(ZONE_ARZ, 0), "ARZ should have water");
        assertFalse(waterSystem.hasWater(ZONE_EHZ, 0), "EHZ should not have water");

        // Verify water heights are distinct
        assertNotEquals(waterSystem.getWaterLevelY(ZONE_CPZ, 1), waterSystem.getWaterLevelY(ZONE_ARZ, 0), "CPZ and ARZ should have different water levels");
    }
}


