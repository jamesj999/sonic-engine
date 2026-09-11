package com.openggf.tests.trace.s3k.tailsfullchain;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;

/**
 * Compared replay of segment {@code ssz_2} of the committed
 * {@code s3k-tails-full-chain-all-emeralds} run -- zone_id 11, act 2, bk2 offset 468159, 5202 rows.
 *
 * <p>Tagged {@code trace-scope-r7}: this Tails route is not a release-6
 * deliverable, so it is selected by {@code -Ptrace-replay-r7} and dropped from
 * the release-blocking {@code -Ptrace-replay} suite. See
 * {@code docs/status/trace-scope-release-6.md}.
 *
 * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to
 * say WHERE this route diverges, not as a regression. Nothing is weakened,
 * tolerance-fitted or trimmed to reach a green. The measured stop point and
 * first error for this run are recorded in
 * {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
@Tag("trace-scope-r7")
public class TestS3kTailsFullChainSsz2SegmentTraceReplay extends AbstractTraceReplayTest {
    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 11; }
    @Override protected int act() { return 1; }
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-tails-full-chain-all-emeralds/ssz_2"); }
}
