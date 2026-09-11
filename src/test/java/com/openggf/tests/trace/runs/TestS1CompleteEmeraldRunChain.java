package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed
 * {@code s1-sonic-complete-withemeralds} run: 34 segments carrying Sonic from
 * GHZ1 to SBZ3 through every special stage the route visits. Drives ONE
 * continuous {@code GameLoop} across the segments via the shared
 * {@link AbstractRunChainTest} base, so the transitions between them -- boundary
 * carry-over, special-stage entry and exit, the presentation bridges -- are
 * exercised rather than skipped.
 *
 * <p>The run already had {@link TestS1CompleteEmeraldRunPrefix} pinning its
 * measured frontier, but no test drove the whole route, so how far S1 replays
 * end to end was never reported. Release 6 commits to S1, S2 and S3K chains
 * green, and a commitment with no test against it is not a commitment.
 *
 * <p><b>This is a new end-to-end frontier harness and may well be RED.</b> It
 * is added as an honest measure of the route, not as a regression, and matches
 * the shape of {@link TestS2CompleteEmeraldRunChain}. Nothing here is weakened,
 * tolerance-fitted or trimmed to reach a green: if it fails, the failure is the
 * measurement. Where it stops, and with which first error, belongs in
 * {@code docs/status/trace-frontier-log.md}.
 *
 * <p>The ratcheting prefix pins in {@link TestS1CompleteEmeraldRunPrefix} are
 * the gate; this drive is the exit criterion. Raise those pins as the frontier
 * advances, and never lower one to make a run pass.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestS1CompleteEmeraldRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s1", "runs",
            "s1-sonic-complete-withemeralds");

    /**
     * The whole route, end to end. This is the frontier the run exists to
     * measure; raise nothing and skip nothing to keep it quiet.
     */
    @Test
    void ghz1ToScrapBrainAcrossEverySpecialStage() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
