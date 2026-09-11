package com.openggf.game.sonic2;

import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies Wing Fortress Zone loads the {@code ArtKos_WFZ} ("WFZ_Supp.kos") foreground
 * pattern supplement on top of the shared SCZ base art ({@code ArtKos_SCZ}).
 *
 * <p>Like HTZ, WFZ shares a base tileset and overlays a zone supplement that the ROM
 * applies via a hardcoded patch (s2.asm:6494-6499). The supplement tiles
 * ({@code $0307..$0379}) build the WFZ-specific foreground, including Robotnik's getaway
 * ship the player grabs at the ending. Without the overlay the pattern buffer stops at
 * the SCZ base ({@code ArtTile_ArtKos_NumTiles_SCZ = $036E}) and the ship samples
 * missing/garbage tiles.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestWfzSupplementArt {

    /** EHZ=0, CPZ=1, ARZ=2, CNZ=3, HTZ=4, MCZ=5, OOZ=6, MTZ=7, SCZ=8, WFZ=9, DEZ=10. */
    private static final int WFZ_ZONE_INDEX = 9;
    private static final int ACT_1 = 0;

    /** ROM {@code ArtTile_ArtKos_NumTiles_WFZ} = SCZ base ($036E) extended by the WFZ supplement. */
    private static final int WFZ_TOTAL_TILES = 0x0379;

    @Test
    public void wfzLoadsSupplementPatternsOntoSczBase() throws Exception {
        SharedLevel shared = SharedLevel.load(SonicGame.SONIC_2, WFZ_ZONE_INDEX, ACT_1);
        try {
            int patternCount = shared.level().getPatternCount();
            assertTrue(patternCount >= WFZ_TOTAL_TILES,
                    "WFZ must load the ArtKos_WFZ supplement onto the SCZ base "
                            + "(expected patternCount >= 0x" + Integer.toHexString(WFZ_TOTAL_TILES)
                            + ", got 0x" + Integer.toHexString(patternCount)
                            + "). Without the overlay the getaway-ship foreground tiles are missing.");
        } finally {
            shared.dispose();
        }
    }
}
