package com.openggf.level.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestPlacementWindowWidth {

    private record Point(int x, int y) implements SpawnPoint {
    }

    private static final class TestPlacement extends AbstractPlacementManager<Point> {
        TestPlacement(int extraAhead, int unloadBehind, java.util.function.IntSupplier width) {
            super(List.of(new Point(0, 0)), extraAhead, unloadBehind, width);
        }
        int windowEnd(int cameraX) { return getWindowEnd(cameraX); }
        int loadAhead() { return getLoadAhead(); }
    }

    @Test
    void nativeWindowEqualsLegacy640() {
        TestPlacement p = new TestPlacement(320, 0x80, () -> 320);
        assertEquals(640, p.loadAhead());
        assertEquals(640, p.windowEnd(0));
    }

    @Test
    void moderateWidescreenStaysAtNativeWindow() {
        // The load-ahead is capped to the minimum lead (width + 128), so for
        // widths up to the 512 crossover the native 640 window still holds —
        // keeping the live-object count (and thus slot-pool usage) at native.
        TestPlacement p = new TestPlacement(320, 0x80, () -> 400);
        assertEquals(640, p.loadAhead());
        assertEquals(640, p.windowEnd(0));
    }

    @Test
    void ultrawideGrowsByMinimumLeadOnly() {
        // ULTRA_21_9 (528): load-ahead = width + 128 = 656, not width + 320 = 848.
        // This keeps the window (128 + 656 = 784) within ~2% of native so the
        // fixed object slot pool is not overrun in dense areas.
        TestPlacement p = new TestPlacement(320, 0x80, () -> 528);
        assertEquals(656, p.loadAhead());
        assertEquals(656, p.windowEnd(0));
    }
}
