package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.WaterSystem;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests for water system ROM extraction and validation.
 */
public class WaterSystemTest {

    private static final int WATER_SURFACE_OBJECT_ID = 0x04;

    // Zone IDs
    private static final int ZONE_EHZ = 0;
    private static final int ZONE_CPZ = 1;
    private static final int ZONE_ARZ = 2;

    // Expected water heights (from original game)
    private static final int CPZ_ACT_2_EXPECTED = 710;
    private static final int ARZ_ACT_1_EXPECTED = 410;
    private static final int ARZ_ACT_2_EXPECTED = 510;

    private WaterSystem waterSystem;
    private Rom rom;

    @Before
    public void setUp() {
        waterSystem = WaterSystem.getInstance();
        waterSystem.reset();

        try {
            rom = RomManager.getInstance().getRom();
        } catch (Exception e) {
            // ROM not available in test environment
            rom = null;
        }
    }

    @Test
    public void testNoWaterInEHZ() {
        // EHZ has no water - should detect this
        List<ObjectSpawn> emptyObjects = new ArrayList<>();

        if (rom != null) {
            waterSystem.loadForLevel(rom, ZONE_EHZ, 0, emptyObjects);
            assertFalse("EHZ Act 1 should not have water", waterSystem.hasWater(ZONE_EHZ, 0));
        }
    }

    @Test
    public void testWaterDetectionFromObject() {
        // Simulate CPZ Act 2 with water surface object
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(0, CPZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        if (rom != null) {
            waterSystem.loadForLevel(rom, ZONE_CPZ, 1, objects);

            assertTrue("CPZ Act 2 should have water", waterSystem.hasWater(ZONE_CPZ, 1));
            int waterY = waterSystem.getWaterLevelY(ZONE_CPZ, 1);

            // Allow small tolerance for extraction accuracy
            int tolerance = 5;
            assertTrue(String.format("CPZ Act 2 water should be around %d (got %d)",
                    CPZ_ACT_2_EXPECTED, waterY),
                    Math.abs(waterY - CPZ_ACT_2_EXPECTED) <= tolerance);
        }
    }

    @Test
    public void testARZ1WaterHeight() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(100, ARZ_ACT_1_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        if (rom != null) {
            waterSystem.loadForLevel(rom, ZONE_ARZ, 0, objects);

            assertTrue("ARZ Act 1 should have water", waterSystem.hasWater(ZONE_ARZ, 0));
            int waterY = waterSystem.getWaterLevelY(ZONE_ARZ, 0);

            int tolerance = 5;
            assertTrue(String.format("ARZ Act 1 water should be around %d (got %d)",
                    ARZ_ACT_1_EXPECTED, waterY),
                    Math.abs(waterY - ARZ_ACT_1_EXPECTED) <= tolerance);
        }
    }

    @Test
    public void testARZ2WaterHeight() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(200, ARZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        if (rom != null) {
            waterSystem.loadForLevel(rom, ZONE_ARZ, 1, objects);

            assertTrue("ARZ Act 2 should have water", waterSystem.hasWater(ZONE_ARZ, 1));
            int waterY = waterSystem.getWaterLevelY(ZONE_ARZ, 1);

            int tolerance = 5;
            assertTrue(String.format("ARZ Act 2 water should be around %d (got %d)",
                    ARZ_ACT_2_EXPECTED, waterY),
                    Math.abs(waterY - ARZ_ACT_2_EXPECTED) <= tolerance);
        }
    }

    @Test
    public void testUnderwaterPaletteLoading() {
        List<ObjectSpawn> objects = new ArrayList<>();
        objects.add(new ObjectSpawn(0, CPZ_ACT_2_EXPECTED, WATER_SURFACE_OBJECT_ID, 0, 0, false, 0));

        if (rom != null) {
            waterSystem.loadForLevel(rom, ZONE_CPZ, 1, objects);

            Palette[] underwaterPalette = waterSystem.getUnderwaterPalette(ZONE_CPZ, 1);
            assertNotNull("CPZ should have underwater palette", underwaterPalette);
            assertEquals("Palette array should have 4 palettes", 4, underwaterPalette.length);

            // Verify first palette has correct number of colors
            assertNotNull("Palette 0 should not be null", underwaterPalette[0]);
            assertEquals("Palette should have 16 colors", 16, underwaterPalette[0].colors.length);
        }
    }

    @Test
    public void testDistortionTableGeneration() {
        int[] distortionTable = waterSystem.getDistortionTable();

        assertNotNull("Distortion table should not be null", distortionTable);
        assertTrue("Distortion table should have reasonable size", distortionTable.length > 0);

        // Verify table contains varied values (not all zeros)
        boolean hasVariation = false;
        for (int value : distortionTable) {
            if (value != 0) {
                hasVariation = true;
                break;
            }
        }
        assertTrue("Distortion table should have variation", hasVariation);
    }

    @Test
    public void testMultipleLevelConfigs() {
        if (rom != null) {
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
            assertTrue("CPZ should have water", waterSystem.hasWater(ZONE_CPZ, 1));
            assertTrue("ARZ should have water", waterSystem.hasWater(ZONE_ARZ, 0));
            assertFalse("EHZ should not have water", waterSystem.hasWater(ZONE_EHZ, 0));

            // Verify water heights are distinct
            assertNotEquals("CPZ and ARZ should have different water levels",
                    waterSystem.getWaterLevelY(ZONE_CPZ, 1),
                    waterSystem.getWaterLevelY(ZONE_ARZ, 0));
        }
    }
}
