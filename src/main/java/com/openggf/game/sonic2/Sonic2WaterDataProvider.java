package com.openggf.game.sonic2;

import com.openggf.data.PaletteLoader;
import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.Palette;

import java.util.logging.Logger;

/**
 * Water data provider for Sonic 2.
 * Supplies zone/act-specific water heights, underwater palettes, and dynamic handlers.
 * <p>
 * Sonic 2 water zones (from s2.asm Level_InitWater):
 * <ul>
 *   <li>CPZ Act 2 (Chemical Plant Zone) - Mega Mack (purple liquid), rises</li>
 *   <li>ARZ (Aquatic Ruin Zone) - Standard water</li>
 *   <li>HPZ (Hidden Palace Zone) - Standard water (unused zone)</li>
 * </ul>
 * <p>
 * HTZ (Hill Top Zone) does NOT use the water system. Its lava is a background
 * visual effect controlled by the earthquake event system.
 * <p>
 * Starting water heights are from the ROM's Water_Height table (s2disasm).
 * Underwater palette ROM addresses from SCHG documentation.
 */
public class Sonic2WaterDataProvider implements WaterDataProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2WaterDataProvider.class.getName());

    // S2 ROM zone IDs (from Sonic2ZoneConstants)
    private static final int ZONE_ARZ = Sonic2ZoneConstants.ROM_ZONE_ARZ; // 0x0F
    private static final int ZONE_CPZ = Sonic2ZoneConstants.ROM_ZONE_CPZ; // 0x0D

    // ROM addresses for underwater palettes (from SCHG)
    private static final int CPZ_UNDERWATER_PALETTE_ADDR = 0x2E62;
    private static final int ARZ_UNDERWATER_PALETTE_ADDR = 0x2FA2;

    @Override
    public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
        // Water_flag is set for CPZ Act 2, ARZ, and HPZ only (s2.asm Level_InitWater).
        // HTZ has lava but it is a background visual effect, not water.
        if (zoneId == ZONE_CPZ) {
            return actId == 1;
        }
        return zoneId == ZONE_ARZ;
    }

    @Override
    public int getStartingWaterLevel(int zoneId, int actId) {
        // CPZ Act 2: ROM Water_Height table at 0x459A = 0x0710
        if (zoneId == ZONE_CPZ && actId == 1) return 0x0710;
        // ARZ Act 1: ROM Water_Height table at 0x45A0 = 0x0410
        if (zoneId == ZONE_ARZ && actId == 0) return 0x0410;
        // ARZ Act 2: ROM Water_Height table at 0x45A2 = 0x0510
        if (zoneId == ZONE_ARZ && actId == 1) return 0x0510;
        return 0;
    }

    @Override
    public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character) {
        int paletteAddr;
        if (zoneId == ZONE_CPZ) {
            paletteAddr = CPZ_UNDERWATER_PALETTE_ADDR;
        } else if (zoneId == ZONE_ARZ) {
            paletteAddr = ARZ_UNDERWATER_PALETTE_ADDR;
        } else {
            return null; // No underwater palette for other zones
        }

        try {
            return PaletteLoader.loadFullPalette(rom, paletteAddr);
        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "Failed to load underwater palette for zone %d act %d at 0x%X: %s",
                    zoneId, actId, paletteAddr, e.getMessage()));
            return null;
        }
    }

    @Override
    public DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character) {
        // S2 dynamic water (CPZ2 rising Mega Mack) is handled by existing
        // LevelEventManager / WaterSystem interaction
        return null;
    }

    @Override
    public int getGameplayWaterLevelOffset(int zoneId, int actId) {
        // ROM MoveWater writes Water_Level_1 = Water_Level_2 +
        // ((Oscillating_Data).w >> 1) for non-ARZ water before Sonic_Water
        // compares y_pos against Water_Level_1.
        // Refs: docs/s2disasm/s2.asm:5275-5282, 36375-36380.
        if (zoneId == ZONE_CPZ) {
            return OscillationManager.getByte(0) >> 1;
        }
        return 0;
    }

    @Override
    public int getVisualWaterLevelOffset(int zoneId, int actId) {
        // CPZ: water oscillation using oscillator index 0 (limit=0x10, range 0..16).
        // Center the bob around 0 by subtracting half the limit (8) so it
        // produces +/-8 pixels of vertical motion (~ring height).
        // ROM ref: docs/s2disasm/s2.asm:5273-5282 (MoveWater) reads
        // (Oscillating_Data).w and lsr.w #1 then adds to (Water_Level_2). The
        // engine instead centres the same oscillator around zero for visual use
        // (the absolute water level is owned by WaterSystem / event managers).
        if (zoneId == ZONE_CPZ) {
            int oscillation = OscillationManager.getByte(0);
            return oscillation - 8;
        }
        // ARZ and HPZ: no oscillation (fixed water surface)
        return 0;
    }
}
