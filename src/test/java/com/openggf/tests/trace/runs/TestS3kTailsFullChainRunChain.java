package com.openggf.tests.trace.runs;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Chain integration test for the committed {@code s3k-tails-full-chain-all-emeralds}
 * run -- 70 segments, 48 transitions, 484,206 rows: Tails alone from AIZ through DDZ
 * with all fifteen special stages and nine bonus stages (gumball x3, pachinko x3,
 * slots x3). Drives ONE continuous {@code GameLoop} through every segment via the
 * shared {@link AbstractRunChainTest} base, exercising the {@code bonus_stage}
 * per-frame comparator path, the {@code special_stage} advance-uncompared path, and
 * every return-boundary assertion the run carries.
 *
 * <p>Tagged {@code trace-scope-r7}: this Tails route is not a release-6 deliverable,
 * so it is selected by {@code -Ptrace-replay-r7} and dropped from the
 * release-blocking {@code -Ptrace-replay} suite. See
 * {@code docs/status/trace-scope-release-6.md}.
 *
 * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to say
 * WHERE this route diverges, not as a regression. Nothing is weakened,
 * tolerance-fitted or trimmed to reach a green. The measured stop point and first
 * error are recorded in {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
@Tag("trace-scope-r7")
class TestS3kTailsFullChainRunChain extends AbstractRunChainTest {

    private static final Path RUN_DIR = Path.of(
            "src", "test", "resources", "traces", "s3k", "runs",
            "s3k-tails-full-chain-all-emeralds");

    @Test
    void tailsFullChainAllEmeraldsRoundTrip() throws Exception {
        assertChainReplay(RUN_DIR);
    }
}
