package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed
 * {@code s3k-knuckles-complete-superemeralds} run: 67 segments carrying
 * Knuckles from AIZ1 to DDZ, through fourteen special stages, ten bonus stages
 * (gumball, slots and pachinko) and the Hidden Palace emerald-conversion
 * returns the super-emerald route threads between acts. Drives ONE continuous
 * {@code GameLoop} through the segments via the shared
 * {@link AbstractRunChainTest} base.
 *
 * <p>Until now the run was referenced only by
 * {@code TestCommittedHardwareTimingFixtures}, which validates its recorded
 * timing streams without ever replaying a frame of it.
 *
 * <p><b>This is a new end-to-end frontier harness and is expected RED.</b> S3K
 * parity work is incomplete, so the honest expectation is that it stops early;
 * it was added deliberately, to say WHERE, rather than as a regression. Nothing
 * here is weakened, tolerance-fitted or trimmed to reach a green. See the entry
 * in {@code docs/status/trace-frontier-log.md} for the current stop point and
 * first error.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kKnucklesSuperEmeraldRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs",
            "s3k-knuckles-complete-superemeralds");

    /**
     * The whole route, end to end. This is the frontier the run exists to
     * measure; raise nothing and skip nothing to keep it quiet.
     * <p>
     * No {@link #assertChainReplayThroughSegmentRow} prefix pin accompanies it,
     * unlike {@link TestS1CompleteEmeraldRunPrefix}: that target is only
     * honoured on an INTERIOR segment's row driver, and the drive stops in
     * segment 0's own AIZ1 source act -- before any interior exists -- so a
     * prefix could not be placed short of the frontier. Add one once the drive
     * reaches segment 1.
     */
    @Test
    void aiz1ToDoomsdayAcrossEverySpecialAndBonusStage() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
