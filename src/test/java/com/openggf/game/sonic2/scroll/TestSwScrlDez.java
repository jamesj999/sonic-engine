package com.openggf.game.sonic2.scroll;

import com.openggf.data.Rom;
import com.openggf.level.scroll.M68KMath;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_2)
class TestSwScrlDez {

    @Test
    void dezShakeTimerAppliesRomRippleOffsetsAndCountsDown() throws IOException {
        Rom rom = TestEnvironment.currentRom();
        ParallaxTables tables = new ParallaxTables(rom);
        SwScrlDez handler = new SwScrlDez(tables);
        int[] hscroll = new int[M68KMath.VISIBLE_LINES];

        handler.setVscrollFactorBG((short) 0x20);
        handler.triggerScreenShake(0x40);
        handler.update(hscroll, 0x100, 0x30, 10, 0);

        int expectedY = tables.getRippleSigned(10);
        int expectedX = tables.getRippleSigned(11);
        assertEquals(expectedX, handler.getShakeOffsetX(), "DEZ shake should use SwScrl_RippleData X byte");
        assertEquals(expectedY, handler.getShakeOffsetY(), "DEZ shake should use SwScrl_RippleData Y byte");
        assertEquals(0x3F, handler.getDezShakeTimer(), "SwScrl_DEZ decrements DEZ_Shake_Timer after applying the ripple");
        assertEquals((short) (0x30 + expectedY), handler.getVscrollFactorFG(), "FG VScroll should include the ripple Y offset");
        assertEquals((short) (0x20 + expectedY), handler.getVscrollFactorBG(), "BG VScroll should include the ripple Y offset");
    }

    @Test
    void dezShakeStopsAfterTimerUnderflows() throws IOException {
        SwScrlDez handler = new SwScrlDez(new ParallaxTables(TestEnvironment.currentRom()));
        int[] hscroll = new int[M68KMath.VISIBLE_LINES];

        handler.triggerScreenShake(0);
        handler.update(hscroll, 0, 0, 0, 0);
        assertEquals(-1, handler.getDezShakeTimer(), "Timer zero should still produce one final ripple frame before underflow");

        handler.update(hscroll, 0, 0, 1, 0);
        assertEquals(0, handler.getShakeOffsetX(), "Shake X should clear once DEZ_Shake_Timer is inactive");
        assertEquals(0, handler.getShakeOffsetY(), "Shake Y should clear once DEZ_Shake_Timer is inactive");
    }
}
