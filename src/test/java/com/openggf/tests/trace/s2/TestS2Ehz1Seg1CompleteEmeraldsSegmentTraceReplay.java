package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg1_ehz1} — the EHZ act 1 level segment of the
 * committed {@code s2-sonic-tails-complete-emeralds} run (bk2 offset 769, 3710 rows).
 *
 * <p>This segment is an inherited prefix that
 * {@link S2SpecialStagePredecessorReplay} replays <em>uncompared</em> before
 * {@code ss}: {@code ss} declares a non-empty
 * {@code dynamic_art_initial_ledger_descriptors}, so the predecessor walk
 * starts at this segment (the nearest one whose own opening ledger is empty)
 * and drives every one of its 3710 recorded rows with no comparison window
 * open. A divergence inside those rows is therefore invisible: it surfaces, if
 * at all, as a downstream dynamic-art assertion in the special-stage test with
 * no first-error frame of its own. That is exactly how the {@code ss_2}
 * object-load-order defect and the {@code ss_7} spring phase error each cost
 * multiple rounds to locate.
 *
 * <p>The segment has its own recorded {@code physics.csv}, so comparing it
 * costs nothing but a test class and turns that class of failure into an
 * ordinary red test with a frame and a field. Modelled on
 * {@link TestS2Arz1CompleteEmeraldsSegmentTraceReplay}.
 *
 * <p>This class used to land red with 4 closing-edge {@code dynamic_art}
 * errors on the final row 3709. That was the same trailing-iteration
 * attribution documented on
 * {@link TestS2Arz1CompleteEmeraldsSegmentTraceReplay}: the run recorder puts
 * the iteration after a segment's last row into the following {@code run_gap},
 * so the replay must not forward it onto that row.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Ehz1Seg1CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return Sonic2ZoneConstants.ZONE_EHZ;
    }

    @Override
    protected int act() {
        return 0;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg1_ehz1");
    }
}
