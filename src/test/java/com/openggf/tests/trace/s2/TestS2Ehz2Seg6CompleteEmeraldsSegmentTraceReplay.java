package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg6_ehz2} — the EHZ act 2 level segment of the
 * committed {@code s2-sonic-tails-complete-emeralds} run (bk2 offset 46374, 3794 rows).
 *
 * <p>This segment is an inherited prefix that
 * {@link S2SpecialStagePredecessorReplay} replays <em>uncompared</em> before
 * {@code ss_5}: {@code ss_5} declares a non-empty
 * {@code dynamic_art_initial_ledger_descriptors}, so the predecessor walk
 * starts at this segment (the nearest one whose own opening ledger is empty)
 * and drives every one of its 3794 recorded rows with no comparison window
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
 * <p><b>Status (landed red, deliberately).</b> 5 errors: 3 closing-edge
 * {@code dynamic_art} errors on row 3793, and <b>one genuine gameplay
 * divergence</b> spanning two rows — at frames 1278..1279 the
 * engine's {@code tails_cpu_respawn_counter} reads {@code 0x003F} where the
 * ROM reads {@code 0x0000}, i.e. the engine starts the sidekick CPU respawn
 * countdown on two frames the ROM does not. That divergence has been invisible
 * until now because these rows were only ever replayed uncompared. It is
 * reported, not fixed, by this coverage lane.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Ehz2Seg6CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

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
        return 1;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg6_ehz2");
    }
}
