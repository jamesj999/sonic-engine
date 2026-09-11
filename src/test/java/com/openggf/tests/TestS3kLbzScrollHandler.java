package com.openggf.tests;

import com.openggf.game.sonic3k.scroll.SwScrlLbz;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kLbzScrollHandler {

    @Test
    void lbz2RequestsWideBackgroundPeriodForParallaxSpread() {
        SwScrlLbz handler = new SwScrlLbz();
        int[] hscroll = new int[224];

        handler.update(hscroll, 0x4390, 0x0668, 0, 1);

        assertTrue(handler.getBgPeriodWidth() > 512,
                "LBZ2_Deform writes widely separated HScroll bands; the renderer must not "
                        + "wrap the BG FBO at the default 512px plane width.");
    }
}
