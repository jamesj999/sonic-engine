package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg9_cpz2} — the CPZ act 2 level segment of the
 * committed {@code s2-sonic-tails-complete-emeralds} run (bk2 offset 67996, 5837 rows).
 *
 * <p>This segment is an inherited prefix that
 * {@link S2SpecialStagePredecessorReplay} replays <em>uncompared</em> before
 * {@code ss_6}: {@code ss_6} declares a non-empty
 * {@code dynamic_art_initial_ledger_descriptors}, so the predecessor walk
 * starts at this segment (the nearest one whose own opening ledger is empty)
 * and drives every one of its 5837 recorded rows with no comparison window
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
 * <p><b>Status at b961eae47 (landed red, deliberately) — the big one.</b>
 * 12927 errors, 0 bootstrap errors. First error frame 415,
 * {@code tails_cpu_respawn_counter} expected {@code 0x0001} actual
 * {@code 0x0000}, followed by a full cascade across {@code x}, {@code y},
 * {@code camera_x}, {@code camera_y}, {@code player_mapping_frame} and the
 * {@code dynamic_art} channels for the rest of the 5837 rows. This entire
 * segment is replayed uncompared before {@code ss_6}, so a divergence this
 * large has been sitting in the run with no test able to report a frame or a
 * field for it. Reported, not fixed: no engine change belongs in a coverage
 * lane.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Cpz2Seg9CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() {
        return SonicGame.SONIC_2;
    }

    @Override
    protected int zone() {
        return Sonic2ZoneConstants.ZONE_CPZ;
    }

    @Override
    protected int act() {
        return 1;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg9_cpz2");
    }
}
