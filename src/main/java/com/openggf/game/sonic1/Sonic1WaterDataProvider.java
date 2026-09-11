package com.openggf.game.sonic1;

import com.openggf.data.PaletteLoader;
import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.OscillationManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Palette;

import java.util.logging.Logger;

/**
 * Water data provider for Sonic the Hedgehog 1.
 * <p>
 * S1 water heights are hardcoded in the assembly (LZWaterFeatures.asm lines 49-52)
 * rather than stored in a ROM table like S2. Only Labyrinth Zone (all 3 acts) and
 * Scrap Brain Zone Act 3 (SBZ3, which reuses LZ water mechanics) have water.
 * <p>
 * S1 has two separate underwater palette sets per water zone:
 * <ul>
 *   <li>Zone palette (128 bytes, 4 palette lines): Pal_LZWater or Pal_SBZ3Water</li>
 *   <li>Sonic palette (32 bytes, 1 palette line): Pal_LZSonWater or Pal_SBZ3SonWat,
 *       overwrites palette line 0 with Sonic's underwater colors</li>
 * </ul>
 */
public class Sonic1WaterDataProvider implements WaterDataProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1WaterDataProvider.class.getName());

    // Water heights from LZWaterFeatures.asm WaterHeight table (lines 49-52)
    private static final int[] LZ_HEIGHTS = {
            Sonic1Constants.WATER_HEIGHT_LZ1,  // 0x00B8 - Act 1
            Sonic1Constants.WATER_HEIGHT_LZ2,  // 0x0328 - Act 2
            Sonic1Constants.WATER_HEIGHT_LZ3   // 0x0900 - Act 3
    };
    private static final int SBZ3_HEIGHT = Sonic1Constants.WATER_HEIGHT_SBZ3; // 0x0228

    @Override
    public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
        if (zoneId == Sonic1Constants.ZONE_LZ && actId >= 0 && actId <= 2) {
            return true;
        }
        if (zoneId == Sonic1Constants.ZONE_SBZ && actId == 2) {
            return true;
        }
        return false;
    }

    @Override
    public int getStartingWaterLevel(int zoneId, int actId) {
        if (zoneId == Sonic1Constants.ZONE_LZ && actId >= 0 && actId < LZ_HEIGHTS.length) {
            return LZ_HEIGHTS[actId];
        }
        if (zoneId == Sonic1Constants.ZONE_SBZ && actId == 2) {
            return SBZ3_HEIGHT;
        }
        return 0x0600; // Off-screen default
    }

    @Override
    public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character) {
        int zoneUnderwaterAddr;
        int sonicUnderwaterAddr;

        if (zoneId == Sonic1Constants.ZONE_LZ) {
            zoneUnderwaterAddr = Sonic1Constants.PAL_LZ_UNDERWATER_ADDR;          // 0x2460
            sonicUnderwaterAddr = Sonic1Constants.PAL_LZ_SONIC_UNDERWATER_ADDR;   // 0x2820
        } else if (zoneId == Sonic1Constants.ZONE_SBZ) {
            zoneUnderwaterAddr = Sonic1Constants.PAL_SBZ3_UNDERWATER_ADDR;        // 0x27A0
            sonicUnderwaterAddr = Sonic1Constants.PAL_SBZ3_SONIC_UNDERWATER_ADDR; // 0x2840
        } else {
            return null;
        }

        try {
            // Load the 4-line zone underwater palette (128 bytes)
            Palette[] palettes = PaletteLoader.loadFullPalette(rom, zoneUnderwaterAddr);

            // Load Sonic's underwater palette (32 bytes = 1 palette line) into line 0.
            // In S1, the Sonic underwater palette replaces palette line 0 (the sprite
            // palette line containing Sonic's colors). The zone underwater palette at
            // destinationPaletteLine=0 covers all 4 lines, then the Sonic-specific
            // palette overwrites line 0 with Sonic's underwater colors.
            palettes[0] = PaletteLoader.loadPaletteLine(rom, sonicUnderwaterAddr);

            return palettes;
        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "Failed to load S1 underwater palette for zone %d at 0x%X: %s",
                    zoneId, zoneUnderwaterAddr, e.getMessage()));
            return null;
        }
    }

    @Override
    public DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character) {
        // S1 water is static (no dynamic handlers); level events set targets directly
        return null;
    }

    @Override
    public int getGameplayWaterLevelOffset(int zoneId, int actId) {
        // ROM: LZWaterFeatures writes v_waterpos1 = v_waterpos2 +
        // ((v_oscillate+2) >> 1), then Sonic_Water compares Sonic against
        // v_waterpos1 for underwater entry/exit.
        // Refs: docs/s1disasm/_inc/LZWaterFeatures.asm:19-25;
        // docs/s1disasm/_incObj/01 Sonic.asm:222-247.
        return getOscillatedWaterOffset(zoneId, actId);
    }

    @Override
    public int getVisualWaterLevelOffset(int zoneId, int actId) {
        // S1 LZ and SBZ3: water surface bobs using oscillator data (v_oscillate+2).
        // ROM (LZWaterFeatures.asm): reads byte at v_oscillate+2, shifts right by 1
        // (divides by 2), and adds to v_waterpos2. SBZ3 reuses LZ water mechanics.
        return getOscillatedWaterOffset(zoneId, actId);
    }

    private int getOscillatedWaterOffset(int zoneId, int actId) {
        if (zoneId == Sonic1Constants.ZONE_LZ
                || (zoneId == Sonic1Constants.ZONE_SBZ && actId == 2)) {
            int oscillation = OscillationManager.getByte(0);
            return oscillation >> 1;
        }
        return 0;
    }
}
