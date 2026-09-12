package com.openggf.game.sonic1.scroll;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class TestUniformQuarterSpeedScroll {
    @Test
    void fractionalAndNegativeDeltasFillEveryPackedLineAndRestore() {
        var scroll = new UniformQuarterSpeedScroll();
        int[] actual = new int[224];
        scroll.update(actual, 100, 15, 0, 0);
        assertScroll(actual, 100, 100);
        assertEquals(1, scroll.getVscrollFactorBG());
        Object start = scroll.captureRewindState();
        scroll.update(actual, 103, 22, 1, 0);
        assertScroll(actual, 103, 100);
        assertEquals(1, scroll.getVscrollFactorBG());
        scroll.update(actual, 104, 23, 2, 0);
        assertScroll(actual, 104, 101);
        assertEquals(2, scroll.getVscrollFactorBG());
        scroll.update(actual, 99, 14, 3, 0);
        assertScroll(actual, 99, 99);
        assertEquals(0, scroll.getVscrollFactorBG());
        scroll.restoreRewindState(start);
        scroll.update(actual, 100, 15, 4, 0);
        assertScroll(actual, 100, 100);
        assertEquals(1, scroll.getVscrollFactorBG());
    }

    @Test
    void routesOwnIndependentStateAndInitResetsFractions() throws Exception {
        var provider = new Sonic1ScrollHandlerProvider();
        provider.load(null);
        var sbz = provider.getHandler(Sonic1ZoneConstants.ZONE_SBZ);
        var fz = provider.getHandler(Sonic1ZoneConstants.ZONE_FZ);
        assertNotSame(sbz, fz);
        assertEquals(sbz.getClass(), fz.getClass());
        int[] actual = new int[224];
        sbz.update(actual, 100, 15, 0, 0);
        fz.update(actual, 500, 80, 0, 2);
        sbz.update(actual, 104, 23, 1, 0);
        assertScroll(actual, 104, 101);
        assertEquals(500, fz.getBgCameraX());
        assertEquals(10, fz.getVscrollFactorBG());
        ((UniformQuarterSpeedScroll) sbz).init(-1, -1);
        sbz.update(actual, -1, -1, 2, 0);
        assertScroll(actual, -1, -1);
        assertEquals(-1, sbz.getVscrollFactorBG());
    }

    private void assertScroll(int[] actual, int foregroundX, int backgroundX) {
        int[] expected = new int[224];
        Arrays.fill(expected, ((-foregroundX & 0xFFFF) << 16) | (-backgroundX & 0xFFFF));
        assertArrayEquals(expected, actual);
    }
}
