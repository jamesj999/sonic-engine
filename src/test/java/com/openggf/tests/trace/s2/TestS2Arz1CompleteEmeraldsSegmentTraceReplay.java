package com.openggf.tests.trace.s2;

import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of {@code seg11_arz1} — the ARZ1 level segment of the
 * committed {@code s2-sonic-tails-complete-emeralds} run (bk2 offset 89600,
 * 3420 rows, Sonic + Tails).
 *
 * <p>This segment is the inherited prefix that
 * {@link S2SpecialStagePredecessorReplay} replays <em>uncompared</em> before
 * {@code ss_7}. Uncompared means a divergence inside it surfaces only as a
 * downstream special-stage symptom with no first-error frame of its own, which
 * is how a one-frame sidekick spring-push phase error sat here undetected. The
 * segment has its own recorded {@code physics.csv}; comparing it turns that
 * class of failure into an ordinary red test.
 *
 * <p>The segment's closing edge used to carry three {@code dynamic_art} errors
 * on row 3419, because the replay ran the ROM's next main-loop iteration and
 * forwarded its Tails DPLC submission onto that row. The run recorder
 * attributes that submission to the {@code run_gap} following this segment
 * (which is why {@code ss_7}'s manifest opens with one inherited descriptor),
 * so the recorded row 3419 shows an empty ledger. The replay now runs that
 * trailing iteration only for a standalone trace, which is the only shape the
 * recorder terminal-forwards; see
 * {@code AbstractTraceReplayTest#finishDynamicArtComparison}.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Arz1CompleteEmeraldsSegmentTraceReplay extends AbstractTraceReplayTest {

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
        return 0;
    }

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "seg11_arz1");
    }
}
