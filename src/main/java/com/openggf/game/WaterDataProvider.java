package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.level.Palette;

/**
 * Provides water configuration for a specific game (S1/S2/S3K).
 * Returned by {@link GameModule#getWaterDataProvider()}.
 * <p>
 * Each game implements this to supply zone/act-specific water heights,
 * underwater palettes, and dynamic water handlers from its ROM data.
 */
public interface WaterDataProvider {
    boolean hasWater(int zoneId, int actId, PlayerCharacter character);

    /**
     * Check for water considering seamless act transition state.
     * ROM: CheckLevelForWater (sonic3k.asm:9754-9759) checks Apparent_zone_and_act.
     * During seamless transitions, Apparent != Current, which enables water in cases
     * that a direct load (level select) would disable (e.g. AIZ2 Knuckles).
     *
     * @param seamlessTransition true when called during a seamless act transition
     */
    default boolean hasWater(int zoneId, int actId, PlayerCharacter character,
                             boolean seamlessTransition) {
        return hasWater(zoneId, actId, character);
    }

    int getStartingWaterLevel(int zoneId, int actId);
    Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character);
    DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character);
    default int getWaterSpeed(int zoneId, int actId) { return 1; }

    /**
     * Returns the per-frame gameplay water-surface offset (in pixels) for the
     * given zone/act. Added on top of the current/base water level for player and
     * object water-state checks.
     * <p>
     * Default is 0. Games whose ROM has a separate gameplay waterline register
     * should override this rather than making shared player code game-specific.
     *
     * @return signed pixel offset to add to the current/base water level
     */
    default int getGameplayWaterLevelOffset(int zoneId, int actId) {
        return 0;
    }

    /**
     * Returns the per-frame visual water-surface oscillation offset (in pixels)
     * for the given zone/act. Added on top of the base water level when rendering
     * the water surface and palette/shader split.
     * <p>
     * Default is 0 (no oscillation). Games that bob their water surface (S2 CPZ,
     * S1 LZ/SBZ3) override this to return the appropriate oscillator-driven offset.
     *
     * @return signed pixel offset to add to the base water level
     */
    default int getVisualWaterLevelOffset(int zoneId, int actId) {
        return 0;
    }

    /**
     * Returns the ROM address of the Super/Hyper palette-cycle table that should
     * be written to the underwater palette for the given zone/act, or 0 when the
     * game has no separate underwater table (the normal cycle data is mirrored
     * into the water palette instead).
     * <p>
     * ROM: {@code SuperHyper_PalCycle_SonicApply} (sonic3k.asm:4666-4681) writes
     * the cycle frame to {@code Water_palette+$04} whenever {@code Water_flag} is
     * set, choosing between {@code PalCycle_SuperSonicUnderwaterAIZICZ} and
     * {@code PalCycle_SuperSonicUnderwaterHCZCNZLBZ} by {@code Current_zone}.
     *
     * @return ROM address of the underwater cycle table, or 0 for none
     */
    default int getUnderwaterSuperPaletteCycleAddress(int zoneId, int actId) {
        return 0;
    }
}
