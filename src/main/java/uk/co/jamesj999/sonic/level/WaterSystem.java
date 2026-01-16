package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages water configuration for Sonic 2 levels.
 * Extracts water heights and underwater palettes from ROM data.
 * Provides deterministic water distortion parameters.
 */
public class WaterSystem {
    private static final Logger LOGGER = Logger.getLogger(WaterSystem.class.getName());
    private static final int WATER_SURFACE_OBJECT_ID = 0x04;

    // Default water heights for levels (measured in pixels from top)
    // CPZ Act 2: ~0x2C6 = 710
    // ARZ Act 1: ~0x42D = 1069 (Corrected based on in-game testing)
    // ARZ Act 2: ~0x1FE = 510
    private static final int CPZ_ACT_2_WATER_HEIGHT = 710;
    private static final int ARZ_ACT_1_WATER_HEIGHT = 1069;
    private static final int ARZ_ACT_2_WATER_HEIGHT = 510;

    // ROM addresses for underwater palettes (from SCHG)
    // HPZ unused in final game, but address preserved for reference
    @SuppressWarnings("unused")
    private static final int HPZ_UNDERWATER_PALETTE_ADDR = 0x2C62;
    private static final int CPZ_UNDERWATER_PALETTE_ADDR = 0x2E62;
    private static final int ARZ_UNDERWATER_PALETTE_ADDR = 0x2FA2;
    private static final int PALETTE_SIZE_BYTES = 128; // 64 colors * 2 bytes per color

    // ROM zone IDs (from SPGSonic2Overlay.Lua ZONE_NAMES table)
    private static final int ZONE_ID_CPZ = 0x0D; // Chemical Plant Zone
    private static final int ZONE_ID_ARZ = 0x0F; // Aquatic Ruin Zone

    // Singleton instance
    private static WaterSystem instance;

    // Water configuration data
    private final Map<String, WaterConfig> waterConfigs = new HashMap<>();

    /**
     * Water configuration for a specific zone/act.
     */
    public static class WaterConfig {
        private final boolean hasWater;
        private final int waterLevelY; // Y position in world space (pixels)
        private final Palette[] underwaterPalette; // null if no underwater palette

        public WaterConfig(boolean hasWater, int waterLevelY, Palette[] underwaterPalette) {
            this.hasWater = hasWater;
            this.waterLevelY = waterLevelY;
            this.underwaterPalette = underwaterPalette;
        }

        public boolean hasWater() {
            return hasWater;
        }

        public int getWaterLevelY() {
            return waterLevelY;
        }

        public Palette[] getUnderwaterPalette() {
            return underwaterPalette;
        }
    }

    private WaterSystem() {
        // Private constructor for singleton
    }

    public static WaterSystem getInstance() {
        if (instance == null) {
            instance = new WaterSystem();
        }
        return instance;
    }

    /**
     * Load water configuration from ROM and level object data.
     * Must be called during level initialization.
     * 
     * @param rom     ROM data
     * @param zoneId  Zone index
     * @param actId   Act index
     * @param objects List of object spawns for this level
     */
    public void loadForLevel(Rom rom, int zoneId, int actId, List<ObjectSpawn> objects) {
        String key = makeKey(zoneId, actId);

        // Extract water height from object layout
        Integer waterHeight = extractWaterHeight(zoneId, actId, objects);

        if (waterHeight == null) {
            // No water in this level
            waterConfigs.put(key, new WaterConfig(false, 0, null));
            LOGGER.info(String.format("Zone %d Act %d: No water detected", zoneId, actId));
            return;
        }

        // Validate against known reference values
        validateWaterHeight(zoneId, actId, waterHeight);

        // Load underwater palette
        Palette[] underwaterPalette = loadUnderwaterPalette(rom, zoneId, actId);

        // Store configuration
        waterConfigs.put(key, new WaterConfig(true, waterHeight, underwaterPalette));
        LOGGER.info(String.format("Zone %d Act %d: Water detected at Y=%d, palette=%s",
                zoneId, actId, waterHeight,
                (underwaterPalette != null ? "loaded" : "none")));
    }

    /**
     * Extract water height from object layout by finding water surface object (ID
     * 0x04).
     * Falls back to known hardcoded values for levels with water.
     * 
     * @param zoneId  Zone index
     * @param actId   Act index
     * @param objects List of object spawns
     * @return Water surface Y position, or null if no water
     */
    private Integer extractWaterHeight(int zoneId, int actId, List<ObjectSpawn> objects) {
        // First, try to find from object data
        if (objects != null) {
            for (ObjectSpawn spawn : objects) {
                if (spawn.objectId() == WATER_SURFACE_OBJECT_ID) {
                    LOGGER.fine(String.format("Found water surface object at x=%d, y=%d",
                            spawn.x(), spawn.y()));
                    System.out.printf("  Water surface object found at y=%d%n", spawn.y());
                    return spawn.y();
                }
            }
        }

        // Fallback to known hardcoded water levels for Sonic 2
        // These are the default water heights for each level
        // Chemical Plant Zone Act 2
        if (zoneId == ZONE_ID_CPZ && actId == 1) {
            System.out.println("  Using hardcoded water height for CPZ Act 2");
            return CPZ_ACT_2_WATER_HEIGHT; // 710 (0x2C6)
        }
        // Aquatic Ruin Zone Act 1
        if (zoneId == ZONE_ID_ARZ && actId == 0) {
            System.out.println("  Using hardcoded water height for ARZ Act 1");
            return ARZ_ACT_1_WATER_HEIGHT; // 410 (0x19A)
        }
        // Aquatic Ruin Zone Act 2
        if (zoneId == ZONE_ID_ARZ && actId == 1) {
            System.out.println("  Using hardcoded water height for ARZ Act 2");
            return ARZ_ACT_2_WATER_HEIGHT; // 510 (0x1FE)
        }

        return null;
    }

    /**
     * Validate extracted water height against known reference values.
     * Logs warnings if values differ significantly.
     */
    private void validateWaterHeight(int zoneId, int actId, int extractedHeight) {
        Integer expectedHeight = null;
        String levelName = "";

        // Chemical Plant Zone (ROM zone ID 0x0D)
        if (zoneId == ZONE_ID_CPZ && actId == 1) {
            expectedHeight = CPZ_ACT_2_WATER_HEIGHT;
            levelName = "CPZ Act 2";
        }
        // Aquatic Ruin Zone (ROM zone ID 0x0F)
        else if (zoneId == ZONE_ID_ARZ && actId == 0) {
            expectedHeight = ARZ_ACT_1_WATER_HEIGHT;
            levelName = "ARZ Act 1";
        } else if (zoneId == ZONE_ID_ARZ && actId == 1) {
            expectedHeight = ARZ_ACT_2_WATER_HEIGHT;
            levelName = "ARZ Act 2";
        }

        if (expectedHeight != null) {
            int diff = Math.abs(extractedHeight - expectedHeight);
            if (diff > 5) { // Allow small tolerance
                LOGGER.warning(String.format(
                        "%s: Extracted water height %d differs from expected %d (diff=%d px)",
                        levelName, extractedHeight, expectedHeight, diff));
            } else {
                LOGGER.info(String.format(
                        "%s: Water height validated: %d (expected %d)",
                        levelName, extractedHeight, expectedHeight));
            }
        }
    }

    /**
     * Load underwater palette from ROM for zones that have water.
     * 
     * @param rom    ROM data
     * @param zoneId Zone index
     * @param actId  Act index
     * @return Underwater palette, or null if not applicable
     */
    private Palette[] loadUnderwaterPalette(Rom rom, int zoneId, int actId) {
        int paletteAddr = getUnderwaterPaletteAddress(zoneId, actId);

        if (paletteAddr < 0) {
            return null; // No underwater palette for this zone
        }

        try {
            // Read palette data from ROM using readBytes
            byte[] paletteData = rom.readBytes(paletteAddr, PALETTE_SIZE_BYTES);

            // Create palette and load from Sega format
            Palette[] palettes = new Palette[4];
            for (int i = 0; i < 4; i++) {
                byte[] lineData = new byte[32];
                System.arraycopy(paletteData, i * 32, lineData, 0, 32);
                palettes[i] = new Palette();
                palettes[i].fromSegaFormat(lineData);
            }

            return palettes;
        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "Failed to load underwater palette for zone %d act %d at 0x%X: %s",
                    zoneId, actId, paletteAddr, e.getMessage()));
            return null;
        }
    }

    /**
     * Get ROM address for underwater palette based on zone/act.
     * 
     * @return ROM address, or -1 if no underwater palette
     */
    private int getUnderwaterPaletteAddress(int zoneId, int actId) {
        // HPZ (Hidden Palace Zone) - zone index unknown, not in final game
        // For now, handle known zones: CPZ (1) and ARZ (2)

        if (zoneId == ZONE_ID_CPZ) { // Chemical Plant Zone
            return CPZ_UNDERWATER_PALETTE_ADDR;
        } else if (zoneId == ZONE_ID_ARZ) { // Aquatic Ruin Zone
            return ARZ_UNDERWATER_PALETTE_ADDR;
        }

        // No underwater palette for other zones
        return -1;
    }

    /**
     * Check if a level has water.
     */
    public boolean hasWater(int zoneId, int actId) {
        WaterConfig config = waterConfigs.get(makeKey(zoneId, actId));
        return config != null && config.hasWater();
    }

    /**
     * Get water surface Y position in world coordinates.
     * 
     * @return Water level Y in pixels, or 0 if no water
     */
    public int getWaterLevelY(int zoneId, int actId) {
        WaterConfig config = waterConfigs.get(makeKey(zoneId, actId));
        return config != null ? config.getWaterLevelY() : 0;
    }

    /**
     * Get underwater palette for a level.
     * 
     * @return Underwater palette, or null if none
     */
    public Palette[] getUnderwaterPalette(int zoneId, int actId) {
        WaterConfig config = waterConfigs.get(makeKey(zoneId, actId));
        return config != null ? config.getUnderwaterPalette() : null;
    }

    /**
     * Get water distortion table for underwater ripple effect.
     * For now, returns a simple generated sine-wave pattern.
     * TODO: Extract actual ROM data for pixel-perfect accuracy.
     * 
     * @return Array of horizontal pixel offsets (per scanline)
     */
    public int[] getDistortionTable() {
        // Generate a simple sinusoidal distortion pattern
        // The original uses a specific lookup table - this is a placeholder
        int tableSize = 64; // Must match shader expectations
        int[] table = new int[tableSize];

        for (int i = 0; i < tableSize; i++) {
            // Simple sine wave: amplitude of ~3 pixels
            double angle = (i * 2.0 * Math.PI) / tableSize;
            table[i] = (int) Math.round(3.0 * Math.sin(angle));
        }

        return table;
    }

    private String makeKey(int zoneId, int actId) {
        return zoneId + "_" + actId;
    }

    /**
     * Reset all water configurations (for testing or level reload).
     */
    public void reset() {
        waterConfigs.clear();
    }
}
