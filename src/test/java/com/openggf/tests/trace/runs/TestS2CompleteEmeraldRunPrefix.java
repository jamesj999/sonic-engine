package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Ratcheting parity frontier for the committed
 * {@code s2-sonic-tails-complete-emeralds} route.
 *
 * <p>{@link TestS2CompleteEmeraldRunChain} drives all 35 segments and is red
 * until the whole route replays. That makes it a good exit criterion and a poor
 * progress signal: a drive that reaches segment 11 and one that dies in segment
 * 0 both report simply "red", so a silent regression is indistinguishable from
 * no progress. This class holds the ratchet.
 *
 * <p>The chain's own javadoc deferred adding a pin here until the frontier
 * cleared segment 1, because a prefix target is only honoured on an interior
 * segment's row driver and segment 1 was where the drive then stopped. That
 * condition is met: the frontier now stops inside segment 11 ({@code
 * seg7_ehz2}), so segments 0-10 and the whole of the route's first five special
 * stages replay.
 *
 * <p><b>Raise these pins as the frontier advances; never lower one to make a
 * run pass.</b> A pin that starts failing means the drive slid backwards, which
 * is exactly the regression the binary chain cannot report. The live frontier's
 * measured first-error frame and field belong in
 * {@code docs/status/trace-frontier-log.md}, as they always have.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestS2CompleteEmeraldRunPrefix extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s2", "runs",
            "s2-sonic-tails-complete-emeralds");

    /**
     * Segment 10 is {@code seg10_cpz2}'s predecessor in the drive order -- the
     * last segment the chain replays with no divergence on any axis. Reaching
     * its first driven row proves every earlier segment and boundary, including
     * five special-stage round trips and the EHZ1 -> EHZ2 act advance.
     *
     * <p>Pinned here rather than at segment 11 deliberately. A prefix target
     * does not halt the drive before the pinned segment's interior: pinning
     * {@code (11, 1)} still replays the whole of {@code seg7_ehz2} and so still
     * reports its frame-3525 divergence on {@code queue.s2_nemesis_plc.busy},
     * plus a walk-failure exhausting that segment's source comparator. A pin
     * must defend ground already won, not restate the frontier -- otherwise it
     * is a second copy of the chain test and ratchets nothing.
     *
     * <p>Raise this to 11 the moment the {@code seg7_ehz2} divergence closes.
     */
    @Test
    void ehz1ThroughFiveSpecialStagesIntoEhz2() throws Exception {
        assertChainReplayThroughSegmentRow(RUN_DIR, 10, 1);
    }
}
