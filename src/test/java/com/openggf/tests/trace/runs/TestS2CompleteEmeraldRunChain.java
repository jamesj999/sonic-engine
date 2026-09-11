package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed
 * {@code s2-sonic-tails-complete-emeralds} run: 35 segments and 34 transitions
 * carrying Sonic and Tails from EHZ1 to DEZ, through seven special stages and
 * every zone the route visits. Drives ONE continuous {@code GameLoop} through
 * the segments via the shared {@link AbstractRunChainTest} base.
 *
 * <p>Until now the run's payload was only ever read a segment at a time (the
 * {@code *CompleteEmeraldsSegmentTraceReplay} lanes and the standalone
 * {@code TestS2SpecialStage1..7TraceReplay} classes); nothing drove the
 * transitions BETWEEN them, which is where boundary carry-over, the special
 * stage's entry index and the presentation bridges live.
 *
 * <p><b>This is a new end-to-end frontier harness and is expected RED.</b> It
 * was added deliberately, as an honest measure of how far the route replays --
 * not as a regression. Nothing here is weakened, tolerance-fitted or trimmed to
 * reach a green. See the entry in
 * {@code docs/status/trace-frontier-log.md} for where it currently stops and
 * with which first error.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestS2CompleteEmeraldRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s2", "runs",
            "s2-sonic-tails-complete-emeralds");

    /**
     * The whole route, end to end. This is the frontier the run exists to
     * measure; raise nothing and skip nothing to keep it quiet.
     * <p>
     * No {@link #assertChainReplayThroughSegmentRow} prefix pin accompanies it,
     * unlike {@link TestS1CompleteEmeraldRunPrefix}: that target is only
     * honoured on an INTERIOR segment's row driver, and this route's first
     * interior -- special stage 1, manifest segment 1 -- is already the segment
     * the frontier stops in, at its very first row. A prefix would therefore
     * reach no further than the drive below while reporting less. Add one here
     * the moment the frontier clears segment 1.
     */
    @Test
    void ehz1ToDeathEggAcrossEverySpecialStage() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
