package com.openggf.game.sonic3k;

import com.openggf.data.PaletteLoader;
import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ThresholdTableWaterHandler;
import com.openggf.game.ThresholdTableWaterHandler.WaterThreshold;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.Palette;

import java.util.List;
import java.util.logging.Logger;

/**
 * Water data provider for Sonic 3 &amp; Knuckles.
 * <p>
 * Water zones from CheckLevelForWater (sonic3k.asm:9751):
 * <ul>
 *   <li>Zone 0 (AIZ) - Angel Island, both acts</li>
 *   <li>Zone 1 (HCZ) - Hydrocity, both acts</li>
 *   <li>Zone 3 (CNZ) - Carnival Night, act 2 only (Sonic/Tails, not Knuckles)</li>
 *   <li>Zone 5 (ICZ) - IceCap, act 2 only</li>
 *   <li>Zone 6 (LBZ) - Launch Base, act 2 only</li>
 * </ul>
 * <p>
 * Starting heights from StartingWaterHeights.bin (32 big-endian words).
 * <p>
 * Dynamic handlers from DynamicWaterHeight_Index (sonic3k.asm:8609).
 */
public class Sonic3kWaterDataProvider implements WaterDataProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kWaterDataProvider.class.getName());

    private static final int PALETTE_SIZE_BYTES = 128; // 4 palette lines x 32 bytes each (used for padding)

    /**
     * Water palette IDs from LoadWaterPalette (sonic3k.asm:9817).
     * Each entry is the PalPoint table index for that zone/act combination.
     * -1 indicates no water palette for that zone/act.
     * Indexed as [zone][act].
     */
    private static final int[][] WATER_PALETTE_IDS = {
        { 0x2B, 0x2C }, // Zone 0: AIZ act 0 = $2B, act 1 = $2C
        { 0x31, 0x32 }, // Zone 1: HCZ act 0 = $31, act 1 = $32
        {   -1,   -1 }, // Zone 2: MGZ (no water palette)
        {   -1, 0x3A }, // Zone 3: CNZ act 1 = $3A
        {   -1,   -1 }, // Zone 4: FBZ (no water palette)
        {   -1, 0x39 }, // Zone 5: ICZ act 1 = $39
        { 0x2D, 0x2E }, // Zone 6: LBZ act 0 = $2D, act 1 = $2E
    };

    /**
     * Starting water heights indexed by zone (0-12), each with 2 acts.
     * From StartingWaterHeights.bin (hex dump verified against ROM binary).
     * Non-water zones use 0x0600 (off-screen).
     */
    private static final int[][] STARTING_HEIGHTS = {
        { 0x0504, 0x0528 }, // Zone 0: AIZ
        { 0x0500, 0x0700 }, // Zone 1: HCZ
        { 0x0600, 0x0600 }, // Zone 2: MGZ (no water)
        { 0x0600, 0x0A90 }, // Zone 3: CNZ (act 1 water at 0x0A90)
        { 0x0600, 0x0600 }, // Zone 4: FBZ (no water)
        { 0x0AD8, 0x0AD8 }, // Zone 5: ICZ (both acts 0x0AD8)
        { 0x0A80, 0x065E }, // Zone 6: LBZ (act 0=0x0A80, act 1=0x065E)
        { 0x0600, 0x0600 }, // Zone 7: MHZ (no water)
        { 0x0600, 0x0600 }, // Zone 8: SOZ (no water)
        { 0x0600, 0x0600 }, // Zone 9: LRZ (no water)
        { 0x0600, 0x0600 }, // Zone 10: SSZ (no water)
        { 0x0600, 0x0600 }, // Zone 11: DEZ (no water)
        { 0x0600, 0x0600 }, // Zone 12: DDZ (no water)
    };

    @Override
    public boolean hasWater(int zoneId, int actId, PlayerCharacter character) {
        return hasWater(zoneId, actId, character, false);
    }

    @Override
    public boolean hasWater(int zoneId, int actId, PlayerCharacter character,
                            boolean seamlessTransition) {
        // CheckLevelForWater (sonic3k.asm:9751-9778)
        // AIZ1: water for all characters (line 9752-9753)
        if (zoneId == Sonic3kZoneIds.ZONE_AIZ && actId == 0) return true;
        // AIZ2: ROM checks Apparent_zone_and_act (line 9756).
        // During seamless transition from AIZ1, Apparent != Current → water enabled
        // for ALL characters (including Knuckles).
        // During direct load (level select), Apparent == Current → Knuckles excluded.
        if (zoneId == Sonic3kZoneIds.ZONE_AIZ && actId == 1) {
            if (seamlessTransition) return true;
            if (character != PlayerCharacter.KNUCKLES) return true;
            return false;
        }
        // HCZ both acts (line 9762-9763, zone byte check)
        if (zoneId == Sonic3kZoneIds.ZONE_HCZ) return true;
        // CNZ2: Sonic/Tails only, not Knuckles (line 9764-9767)
        if (zoneId == Sonic3kZoneIds.ZONE_CNZ && actId == 1
                && character != PlayerCharacter.KNUCKLES) return true;
        // ICZ2 (line 9770-9771)
        if (zoneId == Sonic3kZoneIds.ZONE_ICZ && actId == 1) return true;
        // LBZ2 only — NOT LBZ1 (line 9772-9773)
        if (zoneId == Sonic3kZoneIds.ZONE_LBZ && actId == 1) return true;
        return false;
    }

    @Override
    public int getStartingWaterLevel(int zoneId, int actId) {
        if (zoneId < 0 || zoneId >= STARTING_HEIGHTS.length) {
            return 0x0600; // Default off-screen
        }
        int act = Math.min(actId, STARTING_HEIGHTS[zoneId].length - 1);
        if (act < 0) {
            act = 0;
        }
        return STARTING_HEIGHTS[zoneId][act];
    }

    @Override
    public Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character) {
        // Look up the PalPoint palette ID for this zone/act
        int paletteId = getWaterPaletteId(zoneId, actId);
        if (paletteId < 0) {
            return null; // No water palette for this zone/act
        }

        try {
            // Read the PalPoint entry: 4-byte ROM source, 2-byte RAM dest, 2-byte longword count
            int palPointAddr = Sonic3kConstants.PAL_POINTERS_ADDR
                    + paletteId * Sonic3kConstants.PAL_POINTER_ENTRY_SIZE;
            int sourceAddr = rom.read32BitAddr(palPointAddr);
            // RAM dest (2 bytes) - skip, we load the full palette
            int longwordCount = rom.read16BitAddr(palPointAddr + 6);
            int byteCount = (longwordCount + 1) * 4; // dbf loop: (count + 1) iterations x 4 bytes

            // Clamp to standard 128-byte palette buffer (4 lines x 32 bytes)
            int readSize = Math.min(byteCount, PALETTE_SIZE_BYTES);

            byte[] paletteData = rom.readBytes(sourceAddr, readSize);

            // If the palette data is smaller than 128 bytes, pad with zeros
            if (readSize < PALETTE_SIZE_BYTES) {
                byte[] padded = new byte[PALETTE_SIZE_BYTES];
                System.arraycopy(paletteData, 0, padded, 0, readSize);
                paletteData = padded;
            }

            // Split into 4 palette lines (32 bytes each = 16 colors)
            Palette[] palettes = PaletteLoader.fromBytes(paletteData);

            // Knuckles palette patch: overwrite colors 2-4 of palette line 0
            // with zone-specific data from Pal_WaterKnux (sonic3k.asm:9872)
            if (character == PlayerCharacter.KNUCKLES
                    && zoneId < Sonic3kConstants.PAL_WATER_KNUX_ZONE_COUNT) {
                int knuxPatchAddr = Sonic3kConstants.PAL_WATER_KNUX_ADDR
                        + zoneId * Sonic3kConstants.PAL_WATER_KNUX_ENTRY_SIZE;
                byte[] knuxColors = rom.readBytes(knuxPatchAddr,
                        Sonic3kConstants.PAL_WATER_KNUX_ENTRY_SIZE);
                applyKnucklesWaterPatch(palettes[0], knuxColors);
            }

            return palettes;
        } catch (Exception e) {
            LOGGER.warning(String.format(
                    "Failed to load S3K underwater palette for zone %d act %d (palId 0x%02X): %s",
                    zoneId, actId, paletteId, e.getMessage()));
            return null;
        }
    }

    private static void applyKnucklesWaterPatch(Palette paletteLine, byte[] knuxColors) {
        // Patch colors 2, 3, 4 of line 0 (byte offset 4 = color index 2, 2 bytes/color).
        for (int c = 0; c < 3; c++) {
            paletteLine.getColor(2 + c).fromSegaFormat(knuxColors, c * 2);
        }
    }

    /**
     * Returns the PalPoint palette ID for the given zone/act, or -1 if none.
     * Based on LoadWaterPalette in sonic3k.asm:9817.
     */
    private int getWaterPaletteId(int zoneId, int actId) {
        if (zoneId < 0 || zoneId >= WATER_PALETTE_IDS.length) {
            return -1;
        }
        int act = Math.min(Math.max(actId, 0), WATER_PALETTE_IDS[zoneId].length - 1);
        return WATER_PALETTE_IDS[zoneId][act];
    }

    @Override
    public DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character) {
        // AIZ1: static water (ROM handler is rts)
        if (zoneId == Sonic3kZoneIds.ZONE_AIZ && actId == 0) {
            return null;
        }

        // AIZ2: custom state machine
        if (zoneId == Sonic3kZoneIds.ZONE_AIZ && actId == 1) {
            return new Aiz2DynamicWaterHandler();
        }

        // HCZ1: threshold table (word_6E8C)
        // ROM format: dc.w target, cameraX_threshold (32-bit longword pairs)
        if (zoneId == Sonic3kZoneIds.ZONE_HCZ && actId == 0) {
            return new ThresholdTableWaterHandler(List.of(
                new WaterThreshold(0x0900, 0x8500),   // cameraX <= 0x0900 -> instant 0x0500
                new WaterThreshold(0x2A00, 0x8680),   // cameraX <= 0x2A00 -> instant 0x0680
                new WaterThreshold(0x3500, 0x8680),   // cameraX <= 0x3500 -> instant 0x0680
                new WaterThreshold(0xFFFF, 0x86A0)    // fallback -> instant 0x06A0
            ));
        }

        // HCZ2: character-dependent thresholds (word_6EBA / word_6EC2)
        if (zoneId == Sonic3kZoneIds.ZONE_HCZ && actId == 1) {
            if (character == PlayerCharacter.KNUCKLES) {
                return new ThresholdTableWaterHandler(List.of(
                    new WaterThreshold(0x4100, 0x0700),   // cameraX <= 0x4100 -> target 0x0700
                    new WaterThreshold(0xFFFF, 0x8360)    // fallback -> instant 0x0360
                ));
            }
            // Sonic/Tails
            return new ThresholdTableWaterHandler(List.of(
                new WaterThreshold(0x3E00, 0x0700),   // cameraX <= 0x3E00 -> target 0x0700
                new WaterThreshold(0xFFFF, 0x07E0)    // fallback -> target 0x07E0
            ));
        }

        // LBZ1: no water per CheckLevelForWater (only LBZ2 has water)
        // LBZ2: Knuckles only — threshold table + pipe plug alternate path (loc_6F00)
        if (zoneId == Sonic3kZoneIds.ZONE_LBZ && actId == 1) {
            if (character == PlayerCharacter.KNUCKLES) {
                ThresholdTableWaterHandler thresholds = new ThresholdTableWaterHandler(List.of(
                    new WaterThreshold(0x0D80, 0x8FF0),   // cameraX <= 0x0D80 -> instant 0x0FF0
                    new WaterThreshold(0xFFFF, 0x8B20)    // fallback -> instant 0x0B20
                ));
                return new Lbz2KnucklesDynamicWaterHandler(thresholds);
            }
            // Sonic/Tails: no dynamic water
            return null;
        }

        return null;
    }

    @Override
    public int getUnderwaterSuperPaletteCycleAddress(int zoneId, int actId) {
        // SuperHyper_PalCycle_SonicApply (sonic3k.asm:4671-4677): AIZ and ICZ use the
        // green-tinted underwater table, every other water zone the blue-tinted one.
        if (zoneId == Sonic3kZoneIds.ZONE_AIZ || zoneId == Sonic3kZoneIds.ZONE_ICZ) {
            return Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_AIZ_ICZ_ADDR;
        }
        return Sonic3kConstants.PAL_CYCLE_SUPER_SONIC_UNDERWATER_HCZ_CNZ_LBZ_ADDR;
    }
}
