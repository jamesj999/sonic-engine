package com.openggf.tests.trace.s3k.sonictails;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.s3k.AbstractS3kSpecialStageTraceReplayTest;

import java.nio.file.Path;

/**
 * Compared replay of special-stage segment {@code ss_4} (special stage index
 * 3) of the committed {@code s3k-sonic-tails-complete-emeralds} run --
 * bk2 offset 39830, 5102 rows.
 *
 * <p><b>GREEN.</b> This was a deliberate frontier harness, added to say WHERE
 * this third S3K route diverged. It now replays clean over its full row count:
 * the divergence was the special stage's grid-cell check running after the
 * jump physics rather than at its ROM position inside the movement routine
 * sub_9580 (sonic3k.asm:11467, gate at 12074-12078), which consumed a
 * jump-landed blue sphere one frame early. Nothing was weakened,
 * tolerance-fitted or trimmed to reach the green; see
 * {@code docs/status/trace-frontier-log.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSonicTailsSs4SpecialStageTraceReplay extends AbstractS3kSpecialStageTraceReplayTest {
    @Override protected Path traceDirectory() { return Path.of("src/test/resources/traces/s3k/runs/s3k-sonic-tails-complete-emeralds/ss_4"); }
}
