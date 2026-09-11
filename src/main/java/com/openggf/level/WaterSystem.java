package com.openggf.level;

import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.WaterSystemSnapshot;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ScreenShakeTimerSlotObjectInstance;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Manages water configuration for all supported games.
 * Extracts water heights and underwater palettes from ROM data.
 * Provides deterministic water distortion parameters.
 *
 * <p>The preferred entry point is {@link #loadForLevelFromProvider(WaterDataProvider, Rom, int, int, PlayerCharacter)}
 * which delegates to a game-specific {@link WaterDataProvider} implementation. Each game module
 * supplies its own provider via {@code GameModule.getWaterDataProvider()}.
 *
 * <p>The legacy method {@link #loadForLevel(Rom, int, int, List)} (S2) is deprecated and
 * retained only for backward compatibility with existing tests.
 */
public class WaterSystem implements RewindSnapshottable<WaterSystemSnapshot> {
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

    // ROM zone IDs - Sonic 2 (from SPGSonic2Overlay.Lua ZONE_NAMES table)
    private static final int ZONE_ID_CPZ = 0x0D; // Chemical Plant Zone
    private static final int ZONE_ID_ARZ = 0x0F; // Aquatic Ruin Zone

    // Water configuration data
    private final Map<String, WaterConfig> waterConfigs = new HashMap<>();

    // Dynamic water level state (for levels where water rises/falls)
    private final Map<String, DynamicWaterState> dynamicWaterStates = new HashMap<>();

    // ROM: Water_entered_counter — incremented each time any player enters or exits water.
    // Objects snapshot this value and compare each frame to detect water state changes.
    // (sonic3k.constants.asm: Water_entered_counter)
    private int waterEnteredCounter;

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
     * Used for CPZ2's rising Mega Mack and S3K zones with dynamic water.
     *
     * <p>{@code meanLevel} corresponds to the ROM's {@code Mean_water_level}
     * and is kept separate from {@code currentLevel} so that visual oscillation
     * can be layered on top without disturbing the gameplay water position.
     *
     * <p>{@code speed} controls how many pixels per frame the water moves
     * toward its target (ROM: {@code Water_speed}, default 1). S3K AIZ2 uses
     * speed=2 for faster water drops.
     */
    public static class DynamicWaterState {
        private int currentLevel;  // Current water Y position
        private int targetLevel;   // Target water Y position (water moves toward this)
        private int meanLevel;     // ROM's Mean_water_level (separate from currentLevel for oscillation)
        private boolean rising;    // True if water is actively moving
        private int speed;         // Pixels per frame toward target (ROM: Water_speed, default 1)
        private DynamicWaterHandler handler; // Per-frame update logic, nullable
        private boolean enabled; // ROM Water_flag: level can disable water at runtime
        private boolean locked;    // ROM _unkFAA2: when true, dynamic handler is skipped (boss/cutscene)
        private int shakeTimer;    // Screen shake countdown frames (0 = inactive)

        public DynamicWaterState(int initialLevel) {
            this.currentLevel = initialLevel;
            this.targetLevel = initialLevel;
            this.meanLevel = initialLevel;
            this.rising = false;
            this.speed = 1;
            this.handler = null;
            this.enabled = true;
            this.locked = false;
            this.shakeTimer = 0;
        }

        public void setTarget(int targetY) {
            this.targetLevel = targetY;
            this.rising = (meanLevel != targetLevel);
        }

        /** Set mean level directly (ROM bit-15 convention: instant teleport).
         *  ROM loc_6F44 always writes Target_water_level too, so we set all three. */
        public void setMeanDirect(int level) {
            this.meanLevel = level;
            this.currentLevel = level;
            this.targetLevel = level;
            this.rising = false;
        }

        public void setSpeed(int speed) { this.speed = speed; }
        public void setHandler(DynamicWaterHandler handler) { this.handler = handler; }
        public DynamicWaterHandler getHandler() { return handler; }
        public int getCurrentLevel() { return currentLevel; }
        public int getTargetLevel() { return targetLevel; }
        public int getMeanLevel() { return meanLevel; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        /** ROM _unkFAA2: when true, the dynamic handler is skipped (boss/cutscene lock). */
        public boolean isLocked() { return locked; }
        public void setLocked(boolean locked) { this.locked = locked; }

        /** Screen shake countdown frames. 0 = inactive. */
        public int getShakeTimer() { return shakeTimer; }
        public void setShakeTimer(int timer) { this.shakeTimer = timer; }

        /** Move mean toward target by speed pixels. Returns true if still moving.
         *  ROM adds full speed in one step (add.w d1,(Mean_water_level).w at
         *  sonic3k.asm:8602), which can overshoot — this is correct behavior. */
        public boolean update() {
            if (meanLevel == targetLevel) {
                rising = false;
                return false;
            }
            rising = true;
            if (meanLevel < targetLevel) {
                meanLevel += speed;
            } else {
                meanLevel -= speed;
            }
            currentLevel = meanLevel;
            if (meanLevel == targetLevel) {
                rising = false;
            }
            return rising;
        }
    }

    public WaterSystem() {
    }

    /**
     * Load water configuration from ROM and level object data (S2-specific).
     * Must be called during level initialization.
     *
     * @param rom     ROM data
     * @param zoneId  Zone index
     * @param actId   Act index
     * @param objects List of object spawns for this level
     * @deprecated Use {@link #loadForLevelFromProvider(WaterDataProvider, Rom, int, int, PlayerCharacter)} instead.
     *             Retained for backward compatibility with tests.
     */
    @Deprecated
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
     * Load water configuration using a game-agnostic provider.
     * New entry point that replaces game-specific loadForLevel/loadForLevelS1.
     *
     * @param provider  game-specific water data provider
     * @param rom       ROM data
     * @param zoneId    zone index
     * @param actId     act index
     * @param character current player character
     */
    public void loadForLevelFromProvider(WaterDataProvider provider, Rom rom,
            int zoneId, int actId, PlayerCharacter character) {
        loadForLevelFromProvider(provider, rom, zoneId, actId, character, false);
    }

    /**
     * Load water for a level with seamless transition awareness.
     *
     * @param seamlessTransition true when called during a seamless act transition
     *     (ROM: Apparent_zone_and_act != Current_zone_and_act)
     */
    public void loadForLevelFromProvider(WaterDataProvider provider, Rom rom,
            int zoneId, int actId, PlayerCharacter character,
            boolean seamlessTransition) {
        String key = makeKey(zoneId, actId);

        if (!provider.hasWater(zoneId, actId, character, seamlessTransition)) {
            waterConfigs.put(key, new WaterConfig(false, 0, null));
            LOGGER.info(String.format("Zone %d Act %d: No water (provider)", zoneId, actId));
            return;
        }

        int height = provider.getStartingWaterLevel(zoneId, actId);
        Palette[] palette = provider.getUnderwaterPalette(rom, zoneId, actId, character);
        int speed = provider.getWaterSpeed(zoneId, actId);
        DynamicWaterHandler handler = provider.getDynamicHandler(zoneId, actId, character);

        waterConfigs.put(key, new WaterConfig(true, height, palette));

        DynamicWaterState state = new DynamicWaterState(height);
        state.setSpeed(speed);
        state.setHandler(handler);
        dynamicWaterStates.put(key, state);

        LOGGER.info(String.format("Zone %d Act %d: Water at Y=%d (0x%X), speed=%d, dynamic=%s, palette=%s",
                zoneId, actId, height, height, speed,
                handler != null ? "yes" : "no",
                palette != null ? "loaded" : "none"));
    }

    /**
     * Update dynamic water handlers for a specific level.
     * Called once per frame from LevelManager when water is active.
     *
     * @param zoneId  zone index
     * @param actId   act index
     * @param cameraX camera X position in world pixels
     * @param cameraY camera Y position in world pixels
     */
    public void updateDynamic(int zoneId, int actId, int cameraX, int cameraY) {
        String key = makeKey(zoneId, actId);
        DynamicWaterState state = dynamicWaterStates.get(key);
        if (state == null) {
            return;
        }
        // ROM _unkFAA2: skip handler when locked (boss/cutscene)
        DynamicWaterHandler handler = state.getHandler();
        int shakeTimerBeforeHandler = state.shakeTimer;
        if (handler != null && !state.isLocked()) {
            handler.update(state, cameraX, cameraY);
        }
        if (handler != null
                && handler.shakeTimerOccupiesObjectSlot()
                && shakeTimerBeforeHandler <= 0
                && state.shakeTimer > 0) {
            LevelManager levelManager = GameServices.levelOrNull();
            if (levelManager != null && levelManager.getObjectManager() != null) {
                int duration = state.shakeTimer;
                levelManager.getObjectManager().createDynamicObject(
                        () -> new ScreenShakeTimerSlotObjectInstance(duration));
            }
        }
        // Note: state.update() (mean->target movement) is called by WaterSystem.update(),
        // not here, to avoid double-movement per frame.

        // Tick screen shake countdown (ROM: Obj_6E6E 180-frame timer)
        if (state.shakeTimer > 0) {
            state.shakeTimer--;
        }
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
        String key = makeKey(zoneId, actId);
        WaterConfig config = waterConfigs.get(key);
        DynamicWaterState state = dynamicWaterStates.get(key);
        return config != null && config.hasWater()
                && (state == null || state.isEnabled());
    }

    /**
     * Reads only the loaded mutable {@code Water_flag} state.
     *
     * <p>Unlike {@link #hasWater(int, int)}, this does not fall back to a
     * level's static water capability when no live state exists. Callers that
     * model a branch on the previous level's RAM flag need that distinction.
     */
    public boolean isLiveWaterFlagSet(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null && state.isEnabled();
    }

    /**
     * Get the current/base water surface Y position in world coordinates.
     * This corresponds to the non-oscillated runtime water register such as
     * S1/S2 {@code v_waterpos2}/{@code Water_Level_2} or S3K
     * {@code Mean_water_level}.
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
     * Get the gameplay waterline used by player/object water-state checks.
     * <p>
     * Some games derive this from the current/base level each frame. In S1,
     * {@code LZWaterFeatures} writes {@code v_waterpos1 = v_waterpos2 +
     * (v_oscillate+2)/2}, and {@code Sonic_Water} compares Sonic against
     * {@code v_waterpos1} (docs/s1disasm/_inc/LZWaterFeatures.asm:19-25;
     * docs/s1disasm/_incObj/01 Sonic.asm:222-247).
     */
    public int getGameplayWaterLevelY(int zoneId, int actId) {
        int baseLevel = getWaterLevelY(zoneId, actId);
        if (baseLevel == 0) {
            return 0;
        }
        WaterDataProvider provider = GameServices.module().getWaterDataProvider();
        int offset = provider != null ? provider.getGameplayWaterLevelOffset(zoneId, actId) : 0;
        return baseLevel + offset;
    }

    /**
     * Get the visual water surface Y position with oscillation applied.
     * This is used for rendering the water surface sprites and palette/shader
     * split.
     * <p>
     * S2 CPZ and S1 LZ water bobs up and down using oscillation data.
     * S2 ARZ water does NOT oscillate - it remains at a fixed level.
     * <p>
     * S1 LZ oscillation (from LZWaterFeatures.asm):
     * <pre>
     *   move.b (v_oscillate+2).w,d0   ; get oscillation byte
     *   lsr.w  #1,d0                   ; divide by 2
     *   add.w  (v_waterpos2).w,d0      ; add to base water position
     * </pre>
     * <p>
     * Gameplay water-state checks use {@link #getGameplayWaterLevelY(int, int)}.
     *
     * @return Visual water level Y in pixels with oscillation offset applied
     */
    public int getVisualWaterLevelY(int zoneId, int actId) {
        int baseLevel = getWaterLevelY(zoneId, actId);
        if (baseLevel == 0) {
            return 0; // No water
        }
        // Per-game oscillation logic lives on the WaterDataProvider so this
        // shared infrastructure stays game-agnostic. S2 CPZ and S1 LZ/SBZ3
        // override; S3K and S2 ARZ return 0 (no oscillation).
        WaterDataProvider provider = GameServices.module().getWaterDataProvider();
        int offset = provider != null ? provider.getVisualWaterLevelOffset(zoneId, actId) : 0;
        return baseLevel + offset;
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
     * ROM's SwScrl_RippleData table (s2.asm:15408-15413, label byte_C682).
     * 66 bytes of horizontal pixel offsets per scanline for water ripple.
     * Values range 0-3 representing pixel displacement.
     */
    private static final int[] RIPPLE_DATA = {
        1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
        2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
        1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
        2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
        1, 2
    };

    /**
     * Get water distortion table for underwater ripple effect.
     * Uses the ROM's hand-tuned SwScrl_RippleData table (s2.asm:15408).
     *
     * @return Array of horizontal pixel offsets (per scanline)
     */
    public int[] getDistortionTable() {
        return RIPPLE_DATA;
    }

    private String makeKey(int zoneId, int actId) {
        return zoneId + "_" + actId;
    }

    /**
     * Returns the current water entered counter value.
     * Objects snapshot this and compare each frame to detect water entry/exit.
     * ROM: Water_entered_counter (sonic3k.constants.asm)
     */
    public int getWaterEnteredCounter() {
        return waterEnteredCounter;
    }

    /**
     * Increments the water entered counter.
     * Called from player sprite when entering or exiting water.
     */
    public void incrementWaterEnteredCounter() {
        waterEnteredCounter++;
    }

    /**
     * Reset all water configurations (for testing or level reload).
     */
    public void reset() {
        waterConfigs.clear();
        dynamicWaterStates.clear();
        waterEnteredCounter = 0;
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
        }
    }

    /**
     * Set the movement speed for a dynamic water level.
     *
     * <p>Mirrors ROM writes to {@code Water_speed}. Object/event code should use
     * this when a concrete ROM routine changes water movement speed as part of a
     * state transition, rather than reaching into {@link DynamicWaterState}.
     */
    public void setWaterSpeed(int zoneId, int actId, int speed) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        if (state != null) {
            state.setSpeed(speed);
        }
    }

    /**
     * Enables or disables the level's runtime water flag without discarding its
     * loaded height, target, palette, or dynamic handler state.
     *
     * <p>Mirrors writes to a game's mutable {@code Water_flag}. This is distinct
     * from the immutable provider decision that the level supports water.
     */
    public void setWaterEnabled(int zoneId, int actId, boolean enabled) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        if (state != null) {
            state.setEnabled(enabled);
        }
    }

    /**
     * Sets the ROM {@code _unkFAA2} dynamic-water lock for a loaded zone/act.
     * While locked, the per-zone handler cannot replace the retained target;
     * callers may still install an explicit current/target level as the ROM's
     * transition routines do.
     */
    public void setDynamicWaterLocked(int zoneId, int actId, boolean locked) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        if (state != null) {
            state.setLocked(locked);
        }
    }

    public boolean isDynamicWaterLocked(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null && state.isLocked();
    }

    /**
     * Returns the dynamic water handler for a level, if one is active.
     *
     * <p>This is used by object/event bridges that need to set handler-owned ROM
     * state, such as LBZ2 Knuckles' pipe-plug flag.
     */
    public DynamicWaterHandler getDynamicWaterHandler(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null ? state.getHandler() : null;
    }

    /**
     * Set the current water level directly (instant, no gradual movement).
     * ROM equivalent: writing directly to v_waterpos2.
     * Used by Sonic 1 LZ water events where the ROM sets both v_waterpos2
     * and v_waterpos3 simultaneously for instant water level changes.
     *
     * @param zoneId   Zone index
     * @param actId    Act index
     * @param currentY Current water Y position to set immediately
     */
    public void setWaterLevelDirect(int zoneId, int actId, int currentY) {
        String key = makeKey(zoneId, actId);
        DynamicWaterState state = dynamicWaterStates.get(key);
        if (state != null) {
            state.currentLevel = currentY;
            state.meanLevel = currentY;
            LOGGER.fine(String.format("Zone %d Act %d: Water level set directly to %d (0x%X)",
                    zoneId, actId, currentY, currentY));
        }
    }

    /**
     * Get the current target water level (v_waterpos3 equivalent).
     *
     * @param zoneId Zone index
     * @param actId  Act index
     * @return Target water Y position, or 0 if no water
     */
    public int getWaterLevelTarget(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null ? state.targetLevel : 0;
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

    /**
     * Returns the current screen shake countdown for the given zone/act.
     * ROM: Obj_6E6E 180-frame timer that clears Screen_shake_flag.
     * Used by the screen event handler to determine constant-mode shake.
     *
     * @return shake frames remaining, or 0 if inactive
     */
    public int getShakeTimer(int zoneId, int actId) {
        DynamicWaterState state = dynamicWaterStates.get(makeKey(zoneId, actId));
        return state != null ? state.getShakeTimer() : 0;
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "water";
    }

    @Override
    public WaterSystemSnapshot capture() {
        Map<String, WaterSystemSnapshot.DynamicWaterEntry> entries = new LinkedHashMap<>();
        for (Map.Entry<String, DynamicWaterState> e : dynamicWaterStates.entrySet()) {
            DynamicWaterState s = e.getValue();
            entries.put(e.getKey(), new WaterSystemSnapshot.DynamicWaterEntry(
                    s.getCurrentLevel(),
                    s.getTargetLevel(),
                    s.getMeanLevel(),
                    s.rising,
                    s.speed,
                    s.isEnabled(),
                    s.isLocked(),
                    s.getShakeTimer()
            ));
        }
        return new WaterSystemSnapshot(waterEnteredCounter, entries);
    }

    @Override
    public void restore(WaterSystemSnapshot snap) {
        waterEnteredCounter = snap.waterEnteredCounter();
        for (Map.Entry<String, WaterSystemSnapshot.DynamicWaterEntry> e
                : snap.dynamicStates().entrySet()) {
            DynamicWaterState state = dynamicWaterStates.get(e.getKey());
            if (state == null) {
                continue;
            }
            WaterSystemSnapshot.DynamicWaterEntry entry = e.getValue();
            state.currentLevel = entry.currentLevel();
            state.targetLevel = entry.targetLevel();
            state.meanLevel = entry.meanLevel();
            state.rising = entry.rising();
            state.speed = entry.speed();
            state.setEnabled(entry.enabled());
            state.setLocked(entry.locked());
            state.setShakeTimer(entry.shakeTimer());
        }
    }
}
