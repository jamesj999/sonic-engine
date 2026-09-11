package com.openggf.tests.trace.s3k.sonictails;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of segment {@code hcz_2} of the committed
 * {@code s3k-sonic-tails-complete-emeralds} run -- zone_id 1, act 1,
 * bk2 offset 63075, 14208 rows.
 *
 * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to
 * say WHERE this third S3K route diverges, not as a regression. Nothing is
 * weakened, tolerance-fitted or trimmed to reach a green. The measured stop
 * point and first error for this run are recorded in
 * {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSonicTailsHcz2SegmentTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 1; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-sonic-tails-complete-emeralds/hcz_2"); }
}
