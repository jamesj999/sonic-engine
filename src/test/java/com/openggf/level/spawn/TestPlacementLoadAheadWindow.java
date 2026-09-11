package com.openggf.level.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the forward load-ahead window math ({@link AbstractPlacementManager#loadAheadFor}).
 *
 * <p>Regression guard for the widescreen "objects intermittently fail to load when
 * scrolling right" bug: the window must stay close to native so the fixed,
 * ROM-sized object slot pool (S1=96, S2=112, S3K=89) is not overrun in dense
 * areas. The window grows only by the minimum pre-load lead needed for the wider
 * screen — NOT by the full {@code extraAhead} margin on top of the wider screen.
 */
class TestPlacementLoadAheadWindow {

    /** The extraAhead value the live {@code ObjectManager.Placement} uses (0x140 = 320). */
    private static final int EXTRA_AHEAD = 0x140;

    /** unloadBehind used by Placement (0x80 = 128), for window-total assertions. */
    private static final int UNLOAD_BEHIND = 0x80;

    @Test
    void native320_isByteIdentical_to0x280() {
        // 320 + 320 = 640 = 0x280 — the legacy native window, unchanged.
        assertEquals(0x280, AbstractPlacementManager.loadAheadFor(320, EXTRA_AHEAD),
                "native load-ahead must equal the legacy 0x280 window");
    }

    @Test
    void moderateWidescreen_staysAtNativeWindow() {
        // For widths up to the crossover (512), width+128 <= 640, so the native
        // baseline holds and the window is unchanged from native (no pool pressure).
        assertEquals(640, AbstractPlacementManager.loadAheadFor(352, EXTRA_AHEAD)); // 16:10
        assertEquals(640, AbstractPlacementManager.loadAheadFor(400, EXTRA_AHEAD)); // 16:9
        assertEquals(640, AbstractPlacementManager.loadAheadFor(512, EXTRA_AHEAD)); // crossover
    }

    @Test
    void ultrawide_growsByMinimumLeadOnly_notFullExtraAhead() {
        // ULTRA_21_9 (528): capped at width + 128 = 656, NOT width + 320 = 848.
        assertEquals(656, AbstractPlacementManager.loadAheadFor(528, EXTRA_AHEAD));
        assertTrue(AbstractPlacementManager.loadAheadFor(528, EXTRA_AHEAD) < 528 + EXTRA_AHEAD,
                "ultrawide load-ahead must be capped below the old width+extraAhead window");
    }

    @Test
    void superUltrawide_coversScreenPlusLead() {
        // SUPER_32_9 (800): width + 128 = 928 (the native baseline no longer covers
        // the screen, so the width-driven term wins). Still far below the old 1120.
        assertEquals(928, AbstractPlacementManager.loadAheadFor(800, EXTRA_AHEAD));
        assertTrue(AbstractPlacementManager.loadAheadFor(800, EXTRA_AHEAD) < 800 + EXTRA_AHEAD);
    }

    @Test
    void neverBelowNativeBaseline() {
        for (int w : new int[] {320, 352, 400, 480, 512, 528, 640, 800}) {
            assertTrue(AbstractPlacementManager.loadAheadFor(w, EXTRA_AHEAD) >= 640,
                    "load-ahead must never drop below the native baseline at width " + w);
        }
    }

    @Test
    void windowStaysNearNative_atUltrawide() {
        // The whole point: live-object span (unloadBehind + loadAhead) at ULTRA must
        // stay within a few percent of native so the fixed slot pool does not overrun.
        int nativeWindow = UNLOAD_BEHIND + AbstractPlacementManager.loadAheadFor(320, EXTRA_AHEAD); // 768
        int ultraWindow = UNLOAD_BEHIND + AbstractPlacementManager.loadAheadFor(528, EXTRA_AHEAD);  // 784
        assertEquals(768, nativeWindow);
        assertEquals(784, ultraWindow);
        // Old behaviour was 128 + 848 = 976 (+27%); now +2%.
        assertTrue(ultraWindow <= nativeWindow * 21 / 20,
                "ultrawide window must stay within ~5% of native to protect the slot pool");
    }
}
