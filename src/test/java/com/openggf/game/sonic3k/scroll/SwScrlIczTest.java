package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.level.scroll.ZoneScrollHandler;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.packScrollWords;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SwScrlIczTest {

    private static final int[] ICZ1_INTRO_DEFORM = {
            0x44, 0x0C, 0x0B, 0x0D, 0x18, 0x50, 0x02, 0x06, 0x08, 0x10, 0x18, 0x20, 0x28, 0x7FFF
    };

    @Test
    public void act1OpeningUsesIntroMountainDeformation() {
        SwScrlIcz handler = new SwScrlIcz();
        int[] hScroll = new int[VISIBLE_LINES];

        handler.update(hScroll, 0x0100, 0x0600, 0, 0);

        assertEquals(0x0C, handler.getVscrollFactorBG() & 0xFFFF,
                "ICZ1_IntroDeform uses Events_fg_1 >> 7, so the opening mountains should not use the indoor half-speed BG Y");

        int[] expected = buildExpectedIcz1IntroBuffer(0x0100, 0x0600, 0);
        assertEquals(expected[0], hScroll[0]);
        assertEquals(expected[67], hScroll[67]);
        assertEquals(expected[68], hScroll[68]);
        assertEquals(expected[223], hScroll[223]);

        assertNotEquals(unpackBG(hScroll[0]), unpackBG(hScroll[223]),
                "ICZ1 opening should have multiple mountain parallax bands instead of one flat indoor/fallback scroll");
        assertEquals(0x1880, handler.getBgCameraX(),
                "ICZ1_BackgroundInit refreshes the opening BG plane from X=$1880 so the mountain art is visible");
    }

    @Test
    public void act1PastIndoorThresholdUsesNormalIndoorCameraProjection() {
        SwScrlIcz handler = new SwScrlIcz();
        int[] hScroll = new int[VISIBLE_LINES];

        handler.update(hScroll, 0x3940, 0x0600, 0, 0);

        assertEquals(0x0300, handler.getVscrollFactorBG() & 0xFFFF);
        assertEquals(packScrollWords(negWord(0x3940), negWord((0x3940 >> 1) - 0x1D80)), hScroll[0]);
        assertEquals(hScroll[0], hScroll[223],
                "ICZ1 indoor normal path calls PlainDeformation after ICZ1_Deform");
    }

    @Test
    public void act2OutdoorUsesPerLineMountainWobbleBand() {
        SwScrlIcz handler = new SwScrlIcz();
        int[] firstFrame = new int[VISIBLE_LINES];
        int[] secondFrame = new int[VISIBLE_LINES];

        handler.update(firstFrame, 0x0400, 0x0300, 0, 1);
        handler.update(secondFrame, 0x0400, 0x0300, 8, 1);

        assertEquals(0, handler.getVscrollFactorBG());
        assertNotEquals(unpackBG(firstFrame[128]), unpackBG(firstFrame[129]),
                "ICZ2_OutBGDeformArray has a $8030 per-line mountain segment");
        assertNotEquals(unpackBG(firstFrame[128]), unpackBG(secondFrame[128]),
                "ICZ2_OutDeform should include Level_frame_counter in the outdoor mountain wobble");
    }

    @Test
    public void act2CentralCaveUsesIndoorDeform() {
        SwScrlIcz handler = new SwScrlIcz();
        int[] hScroll = new int[VISIBLE_LINES];

        handler.update(hScroll, 0x1800, 0x0800, 0, 1);

        assertEquals(0x0158, handler.getVscrollFactorBG() & 0xFFFF);
        assertEquals(packScrollWords(negWord(0x1800), negWord(0x0C00)), hScroll[0]);
        assertEquals(packScrollWords(negWord(0x1800), negWord(0x0A80)), hScroll[72]);
        assertTrue(handler.getMinScrollOffset() <= handler.getMaxScrollOffset());
    }

    @Test
    public void providerRoutesIczToDedicatedHandler() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneConstants.ZONE_ICZ);

        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlIcz);
    }

    private static int[] buildExpectedIcz1IntroBuffer(int cameraX, int cameraY, int frameCounter) {
        short[] table = new short[14];
        int eventsFg1 = cameraY;
        int bgY = ((short) eventsFg1) >> 7;

        int d0Fixed = ((short) cameraX) << 16;
        d0Fixed >>= 5;
        int d1 = d0Fixed;
        int d3 = 0;
        int pos = 0;
        for (int i = 0; i < 5; i++) {
            table[pos++] = (short) (d0Fixed >> 16);
            d0Fixed += d1;
            d1 += d3;
            d3 += 0x800;
        }

        d0Fixed += d1;
        d1 += d1 >> 1;
        d3 = frameCounter * 0x800;
        for (int i = 0; i < 9; i++) {
            table[pos++] = (short) (d0Fixed >> 16);
            d0Fixed += d1;
            d1 += d3;
            d3 += 0x800;
        }

        int[] buffer = new int[VISIBLE_LINES];
        short fgScroll = negWord(cameraX);
        int segment = 0;
        int tableIndex = 0;
        int y = bgY;
        int height = ICZ1_INTRO_DEFORM[segment++] & 0x7FFF;
        while ((y - height) >= 0) {
            y -= height;
            tableIndex++;
            height = ICZ1_INTRO_DEFORM[segment++] & 0x7FFF;
        }
        int line = 0;
        int firstCount = height - y;
        line = fillExpected(buffer, line, firstCount, fgScroll, negWord(table[tableIndex]));
        while (line < VISIBLE_LINES) {
            int count = ICZ1_INTRO_DEFORM[segment++] & 0x7FFF;
            tableIndex++;
            line = fillExpected(buffer, line, count, fgScroll, negWord(table[Math.min(tableIndex, table.length - 1)]));
        }
        return buffer;
    }

    private static int fillExpected(int[] buffer, int startLine, int count, short fgScroll, short bgScroll) {
        int end = Math.min(VISIBLE_LINES, startLine + count);
        int packed = packScrollWords(fgScroll, bgScroll);
        for (int line = startLine; line < end; line++) {
            buffer[line] = packed;
        }
        return end;
    }
}
