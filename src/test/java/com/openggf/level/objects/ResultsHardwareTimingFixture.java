package com.openggf.level.objects;

import com.openggf.game.timing.HardwareTimingService;

/**
 * Session-shaped hardware timing owner for lightweight results-object harnesses.
 */
public final class ResultsHardwareTimingFixture {
    private final HardwareTimingService hardwareTiming = new HardwareTimingService();

    public HardwareTimingService hardwareTiming() {
        return hardwareTiming;
    }
}
