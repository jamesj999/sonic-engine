package com.openggf.tests.trace.s3k.sonictails;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.s3k.AbstractS3kSpecialStageTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of special-stage segment {@code ss_7} (special stage index
 * 6) of the committed {@code s3k-sonic-tails-complete-emeralds} run --
 * bk2 offset 90491, 4992 rows.
 *
 * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to
 * say WHERE this third S3K route diverges, not as a regression. Nothing is
 * weakened, tolerance-fitted or trimmed to reach a green. The measured stop
 * point and first error for this run are recorded in
 * {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSonicTailsSs7SpecialStageTraceReplay extends AbstractS3kSpecialStageTraceReplayTest {
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-sonic-tails-complete-emeralds/ss_7"); }
}
