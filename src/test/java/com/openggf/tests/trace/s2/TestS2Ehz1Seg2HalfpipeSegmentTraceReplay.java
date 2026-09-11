package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg2_ehz1} — the EHZ act 1 level segment of the
 * committed {@code s2-ehz-halfpipe-roundtrip} run (bk2 offset 9701, 2903 rows).
 *
 * <p>This segment is an inherited prefix that
 * {@link S2SpecialStagePredecessorReplay} replays <em>uncompared</em> before
 * {@code ss_2}: {@code ss_2} declares a non-empty
 * {@code dynamic_art_initial_ledger_descriptors}, so the predecessor walk
 * starts at this segment (the nearest one whose own opening ledger is empty)
 * and drives every one of its 2903 recorded rows with no comparison window
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
 * <p>The three closing-edge {@code dynamic_art} errors this class used to
 * report on row 2902 were the segment's trailing main-loop iteration being
 * forwarded onto its last row; see
 * {@link TestS2Arz1CompleteEmeraldsSegmentTraceReplay}.
 *
 * <p>The 38 frame-0 {@code player_history} bootstrap entries this class
 * reported at b961eae47 were the untouched {@code Obj01_Init_Continued}
 * pre-fill remnant of a mid-act re-entry; the bootstrap comparator now
 * excludes exactly that remnant — see
 * {@code TraceBinder.untouchedPreFillRemnantStart}.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Ehz1Seg2HalfpipeSegmentTraceReplay extends AbstractTraceReplayTest {

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
                "s2-ehz-halfpipe-roundtrip", "seg2_ehz1");
    }
}
