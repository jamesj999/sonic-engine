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

    // ROM offsets for water height table (Water_Height in s2disasm)
    // Table starts at 0x04584, each zone has 2 entries (Act 1 and Act 2)
    // Verified offsets found by searching ROM for known ARZ values:
    // ARZ1 (0x0410) at offset 0x45A0
    // ARZ2 (0x0510) at offset 0x45A2
    private static final int WATER_HEIGHT_ROM_ARZ1 = 0x45A0; // ARZ Act 1 water height offset
    private static final int WATER_HEIGHT_ROM_ARZ2 = 0x45A2; // ARZ Act 2 water height offset
    // CPZ2: Water_Height table entry at 0x459A contains 0x0710 (1808)
    // This is the same table that ARZ uses (and ARZ works correctly)
    private static final int WATER_HEIGHT_ROM_CPZ2 = 0x459A; // CPZ Act 2 water height offset

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

    // Dynamic water level state (for levels where water rises/falls)
    private final Map<String, DynamicWaterState> dynamicWaterStates = new HashMap<>();

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

    /**
     * Tracks dynamic water level state for levels where water rises or falls.
     * Used for CPZ2's rising Mega Mack.
     */
    private static class DynamicWaterState {
        private int currentLevel; // Current water Y position
        private int targetLevel; // Target water Y position (water moves toward this)
        private boolean rising; // True if water is actively rising

        DynamicWaterState(int initialLevel) {
            this.currentLevel = initialLevel;
            this.targetLevel = initialLevel;
            this.rising = false;
        }

        void setTarget(int targetY) {
            this.targetLevel = targetY;
            this.rising = (currentLevel != targetLevel);
        }

        /** Move water toward target by 1 pixel. Returns true if still rising. */
        boolean update() {
            if (!rising) {
                return false;
            }
            if (currentLevel > targetLevel) {
                // Water rising (lower Y = higher on screen)
                currentLevel--;
            } else if (currentLevel < targetLevel) {
                // Water falling (higher Y = lower on screen)
                currentLevel++;
            }
            if (currentLevel == targetLevel) {
                rising = false;
            }
            return rising;
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
        Integer waterHeight = extractWaterHeight(zoneId, actId, objects, rom);

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

        // Initialize dynamic water state with the initial level
        dynamicWaterStates.put(key, new DynamicWaterState(waterHeight));

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
    private Integer extractWaterHeight(int zoneId, int actId, List<ObjectSpawn> objects, Rom rom) {
        // Also log the object Y for comparison (even if we use ROM value)
        if (objects != null) {
            for (ObjectSpawn spawn : objects) {
                if (spawn.objectId() == WATER_SURFACE_OBJECT_ID) {
                    break; // Just show the first one for comparison
                }
            }
        }

        // For levels with known ROM water height offsets, read from ROM directly
        // This is more accurate than object data for initial water levels
        try {
            if (zoneId == ZONE_ID_CPZ && actId == 1) {
                // CPZ Act 2 - read from ROM (Level Boundaries table)
                int height = readWaterHeightFromRom(rom, WATER_HEIGHT_ROM_CPZ2);
                return height;
            }
            if (zoneId == ZONE_ID_ARZ && actId == 0) {
                // ARZ Act 1 - read from ROM
                int height = readWaterHeightFromRom(rom, WATER_HEIGHT_ROM_ARZ1);
                return height;
            }
            if (zoneId == ZONE_ID_ARZ && actId == 1) {
                // ARZ Act 2 - read from ROM
                int height = readWaterHeightFromRom(rom, WATER_HEIGHT_ROM_ARZ2);
                return height;
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to read water height from ROM: " + e.getMessage());
        }

        // Fallback: try to find from object data for other levels
        // Also log the object Y for debugging, even if we used ROM value
        if (objects != null) {
            for (ObjectSpawn spawn : objects) {
                if (spawn.objectId() == WATER_SURFACE_OBJECT_ID) {
                    // Only return this if we didn't already return a ROM value
                    return spawn.y();
                }
            }
        }

        return null;
    }

    /**
     * Read a 16-bit big-endian water height value from ROM at the given offset.
     */
    private int readWaterHeightFromRom(Rom rom, int offset) {
        try {
            int high = rom.readByte(offset) & 0xFF;
            int low = rom.readByte(offset + 1) & 0xFF;
            return (high << 8) | low;
        } catch (java.io.IOException e) {
            LOGGER.warning("Failed to read water height from ROM at offset 0x" +
                    Integer.toHexString(offset) + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Validate extracted water height against known reference values.
     * Since water heights are now read from ROM, this method just logs the value.
     */
    private void validateWaterHeight(int zoneId, int actId, int extractedHeight) {
        String levelName = "";

        // Chemical Plant Zone (ROM zone ID 0x0D)
        if (zoneId == ZONE_ID_CPZ && actId == 1) {
            levelName = "CPZ Act 2";
        }
        // Aquatic Ruin Zone (ROM zone ID 0x0F)
        else if (zoneId == ZONE_ID_ARZ && actId == 0) {
            levelName = "ARZ Act 1";
        } else if (zoneId == ZONE_ID_ARZ && actId == 1) {
            levelName = "ARZ Act 2";
        }

        if (!levelName.isEmpty()) {
            LOGGER.info(String.format(
                    "%s: Water height loaded from ROM: %d (0x%X)",
                    levelName, extractedHeight, extractedHeight));
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
     * This is the fixed/gameplay water level used for detecting if Sonic is
     * underwater.
     * 
     * @return Water level Y in pixels, or 0 if no water
     */
    public int getWaterLevelY(int zoneId, int actId) {
        String key = makeKey(zoneId, actId);
        // Return dynamic level if available (supports rising water)
        DynamicWaterState dynamicState = dynamicWaterStates.get(key);
        if (dynamicState != null) {
            return dynamicState.currentLevel;
        }
        // Fallback to static config
        WaterConfig config = waterConfigs.get(key);
        return config != null ? config.getWaterLevelY() : 0;
    }

    /**
     * Get the visual water surface Y position with oscillation applied.
     * This is used for rendering the water surface sprites and palette/shader
     * split.
     * <p>
     * CPZ water bobs up and down by about the height of a ring (~16 pixels total).
     * ARZ water does NOT oscillate - it remains at a fixed level.
     * <p>
     * Note: This does NOT affect gameplay - Sonic's underwater detection uses
     * the fixed water level from {@link #getWaterLevelY(int, int)}.
     * 
     * @return Visual water level Y in pixels with oscillation offset applied (CPZ
     *         only)
     */
    public int getVisualWaterLevelY(int zoneId, int actId) {
        int baseLevel = getWaterLevelY(zoneId, actId);
        if (baseLevel == 0) {
            return 0; // No water
        }
        // Only CPZ has water oscillation - ARZ water stays fixed
        if (zoneId == ZONE_ID_CPZ) {
            // Apply oscillation offset from oscillator index 0 (limit=0x10, 0-16 range)
            // Center around 0 by subtracting half the limit (8)
            // Result is ±8 pixels (~16 pixels total bobbing, ring height)
            int oscillation = uk.co.jamesj999.sonic.game.sonic2.OscillationManager.getByte(0);
            return baseLevel + (oscillation - 8);
        }
        return baseLevel; // ARZ: no oscillation
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
        dynamicWaterStates.clear();
    }

    // =========================================================================
    // Dynamic Water Level Methods (for rising/falling water like CPZ2)
    // =========================================================================

    /**
     * Set a target water level, triggering the water to rise or fall toward it.
     * Called by LevelEventManager when player crosses a trigger point.
     *
     * @param zoneId  Zone index
     * @param actId   Act index
     * @param targetY Target water Y position (lower = higher on screen)
     */
    public void setWaterLevelTarget(int zoneId, int actId, int targetY) {
        String key = makeKey(zoneId, actId);
        DynamicWaterState state = dynamicWaterStates.get(key);
        if (state != null) {
            state.setTarget(targetY);
            LOGGER.info(String.format("Zone %d Act %d: Water target set to %d (0x%X)",
                    zoneId, actId, targetY, targetY));
        }
    }

    /**
     * Update dynamic water levels. Should be called once per frame.
     * Moves water toward its target level by 1 pixel per call.
     */
    public void update() {
        for (DynamicWaterState state : dynamicWaterStates.values()) {
            state.update();
        }
    }

    /**
     * Check if water is currently rising or falling toward a target.
     *
     * @return true if water is actively moving
     */
    public boolean isWaterRising(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null && state.rising;
    }
}
