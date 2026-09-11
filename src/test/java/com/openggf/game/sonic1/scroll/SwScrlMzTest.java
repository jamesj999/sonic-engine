package com.openggf.game.sonic1.scroll;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

public class SwScrlMzTest {

    @Test
    public void usesMultipleParallaxBandsInsteadOfUniformScroll() {
        SwScrlMz handler = new SwScrlMz();
        int[] hScroll = new int[224];

        int cameraY = 0x1C8; // bgY = 0x200, buffer start index = 0
        handler.update(hScroll, 0, cameraY, 0, 0);
        handler.update(hScroll, 256, cameraY, 1, 0);

        short cloud = unpackBG(hScroll[8]);      // cloud blend entry
        short mountain = unpackBG(hScroll[88]);  // mountain entry
        short bushes = unpackBG(hScroll[120]);   // bushes/buildings entry

        assertNotEquals(cloud, mountain, "Cloud and mountain bands should differ");
        assertNotEquals(mountain, bushes, "Mountain and bushes bands should differ");
    }

    @Test
    public void bandSpeedsMatchRev01RatiosForCameraMovement() {
        SwScrlMz handler = new SwScrlMz();
        int[] hScroll = new int[224];
        int cameraY = 0x1C8;

        handler.update(hScroll, 0, cameraY, 0, 0);
        short mountainStart = unpackBG(hScroll[88]);  // BG3 (1/4x)
        short bushesStart = unpackBG(hScroll[120]);   // BG2 (1/2x)

        handler.update(hScroll, 256, cameraY, 1, 0);
        short mountainEnd = unpackBG(hScroll[88]);
        short bushesEnd = unpackBG(hScroll[120]);

        assertEquals(-64, delta(mountainStart, mountainEnd), "Mountain band should move at 1/4x");
        assertEquals(-128, delta(bushesStart, bushesEnd), "Bushes band should move at 1/2x");
    }

    @Test
    public void deepCameraShowsInteriorAtThreeQuarterSpeed() {
        SwScrlMz handler = new SwScrlMz();
        int[] hScroll = new int[224];
        int deepCameraY = 0x400; // clamps to interior portion of the 32-entry buffer

        handler.update(hScroll, 0, deepCameraY, 0, 0);
        short interiorStart = unpackBG(hScroll[100]);

        handler.update(hScroll, 256, deepCameraY, 1, 0);
        short interiorEnd = unpackBG(hScroll[100]);

        assertEquals(-192, delta(interiorStart, interiorEnd), "Interior band should move at 3/4x");
    }

    @Test
    public void bgYThresholdFormulaMatchesDisassembly() {
        SwScrlMz handler = new SwScrlMz();
        int[] hScroll = new int[224];

        handler.update(hScroll, 0, 0x1C7, 0, 0);
        assertEquals(0x0200, handler.getVscrollFactorBG() & 0xFFFF);

        handler.update(hScroll, 0, 0x1C8, 1, 0);
        assertEquals(0x0200, handler.getVscrollFactorBG() & 0xFFFF);

        handler.update(hScroll, 0, 0x1D8, 2, 0); // yOffset = 0x10, add 0x0C
        assertEquals(0x020C, handler.getVscrollFactorBG() & 0xFFFF);
    }

    private short unpackBG(int packed) {
        return (short) (packed & 0xFFFF);
    }

    private int delta(short start, short end) {
        return (short) (end - start);
    }
}


