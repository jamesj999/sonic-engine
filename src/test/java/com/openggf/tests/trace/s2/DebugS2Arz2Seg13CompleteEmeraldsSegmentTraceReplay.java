package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Local standalone replay of {@code seg13_arz2} — manifest segment 19 of the
 * {@code s2-sonic-tails-complete-emeralds} run, the segment the chain aborts in.
 *
 * <p>Diagnostic only, and deliberately named {@code Debug*} so the default
 * Surefire include pattern does not pick it up. It exists to separate two
 * explanations of that segment's divergence: state carried across the
 * {@code seg12_arz1 -> seg13_arz2} boundary, versus a defect intrinsic to ARZ2
 * that a fresh level load reproduces on its own.
 */
@RequiresRom(SonicGame.SONIC_2)
public class DebugS2Arz2Seg13CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return Sonic2ZoneConstants.ZONE_ARZ;
    }

    @Override
    protected int act() {
        return 1;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg13_arz2");
    }
}
