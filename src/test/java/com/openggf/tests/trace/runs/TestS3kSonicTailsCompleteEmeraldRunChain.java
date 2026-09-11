package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed
 * {@code s3k-sonic-tails-complete-emeralds} run: the Sonic-and-Tails route
 * from AIZ1 to DDZ, through every special stage the movie enters and the
 * bonus-stage detours between them, collecting all seven chaos emeralds.
 * Drives ONE continuous {@code GameLoop} through the segments via the shared
 * {@link AbstractRunChainTest} base.
 *
 * <p>This is the third committed S3K route, alongside
 * {@link TestS3kKnucklesSuperEmeraldRunChain} (Knuckles, super emeralds) and
 * the multi-bonus runs. It is the first to carry a sidekick across the whole
 * playthrough, so it exercises sidekick lifetime over every zone transition.
 *
 * <p><b>This is a new end-to-end frontier harness and is expected RED.</b> S3K
 * parity work is incomplete, so the honest expectation is that it stops early;
 * it was added deliberately, to say WHERE, rather than as a regression. Nothing
 * here is weakened, tolerance-fitted or trimmed to reach a green. See the entry
 * in {@code docs/status/trace-frontier-log.md} for the current stop point and
 * first error.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kSonicTailsCompleteEmeraldRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs",
            "s3k-sonic-tails-complete-emeralds");

    /**
     * The whole route, end to end. This is the frontier the run exists to
     * measure; raise nothing and skip nothing to keep it quiet.
     * <p>
     * The prefix pin now lives in
     * {@link TestS3kSonicTailsCompleteEmeraldRunPrefix}. It could not exist
     * before 2026-08-17: the target is only honoured on an INTERIOR segment's
     * row driver, and the drive used to stop inside segment 0's own AIZ1 source
     * act, before any interior existed. The drive now reaches segment 1, so the
     * pin was added. Raise it as this frontier advances; never lower it.
     */
    @Test
    void aiz1ToDoomsdayCollectingEverySevenEmeralds() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
