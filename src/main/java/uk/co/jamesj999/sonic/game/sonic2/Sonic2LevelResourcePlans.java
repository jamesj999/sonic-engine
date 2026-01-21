package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.level.resources.LevelResourcePlan;
import uk.co.jamesj999.sonic.level.resources.LoadOp;

/**
 * Factory for creating LevelResourcePlans for Sonic 2 zones.
 *
 * <p>Most zones use standard single-source loading. Hill Top Zone (HTZ) is special
 * because it composites resources from multiple sources:
 * <ul>
 *   <li>Patterns (8x8): EHZ_HTZ base + HTZ_Supp overlay at offset 0x3F80</li>
 *   <li>Chunks (16x16): EHZ base + HTZ overlay at offset 0x0980</li>
 *   <li>Blocks (128x128): Shared EHZ_HTZ (no overlay)</li>
 *   <li>Collision: Shared EHZ/HTZ collision indices</li>
 * </ul>
 *
 * <p>This composition is documented in the s2disasm SonLVL.ini file:
 * <pre>
 * [Hill Top Zone Act 1/2]
 * tiles=../art/kosinski/EHZ_HTZ.bin|../art/kosinski/HTZ_Supp.bin:0x3F80
 * blocks=../mappings/16x16/EHZ.bin|../mappings/16x16/HTZ.bin:0x980      (SonLVL "blocks" = engine "chunks")
 * chunks=../mappings/128x128/EHZ_HTZ.bin                                 (SonLVL "chunks" = engine "blocks")
 * colind1=../collision/EHZ and HTZ primary 16x16 collision index.bin
 * colind2=../collision/EHZ and HTZ secondary 16x16 collision index.bin
 * </pre>
 *
 * <h2>Terminology Note</h2>
 * <p>SonLVL uses inverted terminology from this engine:
 * <ul>
 *   <li>SonLVL "blocks" (16x16) = Engine "chunks"</li>
 *   <li>SonLVL "chunks" (128x128) = Engine "blocks"</li>
 * </ul>
 *
 * <h2>Why overlays?</h2>
 * <p>The original Sonic 2 shares art between EHZ and HTZ to save ROM space.
 * Both zones use the same base tileset (EHZ_HTZ.bin), but HTZ needs different
 * foreground tiles (fir trees instead of palm trees). The HTZ_Supp.bin file
 * contains these zone-specific tiles and is written at VRAM offset 0x3F80
 * (pattern index 0x01FC) to replace the EHZ foreground tiles.
 *
 * <p>Similarly, 16x16 chunk definitions are shared, with HTZ-specific chunks
 * written at byte offset 0x0980 (chunk index 0x0130 assuming 8 bytes per chunk).
 *
 * @see LevelResourcePlan
 */
public final class Sonic2LevelResourcePlans {

    private Sonic2LevelResourcePlans() {
        // Utility class
    }

    /**
     * Creates the resource plan for Hill Top Zone.
     *
     * <p>HTZ uses EHZ_HTZ shared base data with HTZ-specific overlays:
     * <ul>
     *   <li>Patterns (8x8): Base at 0x095C24, overlay HTZ_Supp at 0x098AB4 -> offset 0x3F80</li>
     *   <li>Chunks (16x16): Base at 0x094E74, overlay HTZ at 0x0985A4 -> offset 0x0980</li>
     *   <li>Blocks (128x128): Shared EHZ_HTZ at 0x099D34 (no overlay)</li>
     *   <li>Collision: Shared EHZ/HTZ primary at 0x044E50, secondary at 0x044F40</li>
     * </ul>
     */
    public static LevelResourcePlan createHtzPlan() {
        return LevelResourcePlan.builder()
                // Patterns (8x8): Base EHZ_HTZ + HTZ supplemental overlay
                .addPatternOp(LoadOp.kosinskiBase(Sonic2Constants.HTZ_PATTERNS_BASE_ADDR))
                .addPatternOp(LoadOp.kosinskiOverlay(
                        Sonic2Constants.HTZ_PATTERNS_OVERLAY_ADDR,
                        Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET))
                // Chunks (16x16): Base EHZ + HTZ overlay
                .addChunkOp(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR))
                .addChunkOp(LoadOp.kosinskiOverlay(
                        Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR,
                        Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET))
                // Blocks (128x128): Shared EHZ_HTZ (no overlay)
                .addBlockOp(LoadOp.kosinskiBase(Sonic2Constants.HTZ_BLOCKS_ADDR))
                // Collision: Shared EHZ/HTZ indices
                .setPrimaryCollision(LoadOp.kosinskiBase(Sonic2Constants.HTZ_COLLISION_PRIMARY_ADDR))
                .setSecondaryCollision(LoadOp.kosinskiBase(Sonic2Constants.HTZ_COLLISION_SECONDARY_ADDR))
                .build();
    }

    /**
     * Returns the resource plan for a zone, if it requires custom loading.
     *
     * @param romZoneId The ROM zone ID (e.g., 0x07 for HTZ)
     * @return The LevelResourcePlan for the zone, or null if the zone uses standard loading
     */
    public static LevelResourcePlan getPlanForZone(int romZoneId) {
        return switch (romZoneId) {
            case Sonic2Constants.ZONE_HTZ -> createHtzPlan();
            // Other zones use standard loading via ROM directory tables
            default -> null;
        };
    }

    /**
     * Returns true if the specified zone requires custom resource plan loading.
     *
     * @param romZoneId The ROM zone ID
     * @return true if the zone has a custom resource plan
     */
    public static boolean hasCustomPlan(int romZoneId) {
        return romZoneId == Sonic2Constants.ZONE_HTZ;
    }
}
