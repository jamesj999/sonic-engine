package com.openggf.tests.trace.s3k.sonictails;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.s3k.AbstractS3kSpecialStageTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of special-stage segment {@code ss_8} (special stage index 0 -- NOT the directory ordinal; ROM sub_85B0 masks the
 * index with {@code andi.w #7,d0} (sonic3k.asm:10858), so it is 0-7 in BOTH
 * halves and does not uniquely identify a segment: index 0 also names
 * {@code ss}. The layout set comes from {@code SK_special_stage_flag}, which no
 * committed fixture records -- see docs/status/known-discrepancies.md) of the committed {@code s3k-sonic-tails-complete-emeralds} run --
 * bk2 offset 228734, 5419 rows.
 *
 * <p><b>New frontier harness: expected RED.</b> It was added deliberately, to
 * say WHERE this third S3K route diverges, not as a regression. Nothing is
 * weakened, tolerance-fitted or trimmed to reach a green. The measured stop
 * point and first error for this run are recorded in
 * {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSonicTailsSs8SpecialStageTraceReplay extends AbstractS3kSpecialStageTraceReplayTest {
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-sonic-tails-complete-emeralds/ss_8"); }
}
