package com.openggf.tests.trace.s2;

import java.nio.file.Path;

/**
 * S2 special-stage trace replay against segment {@code ss_4} of the committed
 * {@code s2-sonic-tails-complete-emeralds} run — the run's special stage
 * 4 (recorder {@code special_stage_index} 3, Sonic + Tails).
 *
 * <p>Unlike the standalone MVP trace, this segment is recorded inside a run:
 * its BK2 lives at the run root and it opens with the dynamic-art ledger the
 * preceding level segment left outstanding. Both are resolved from the sibling
 * {@code run_manifest.json} by {@link AbstractS2SpecialStageTraceReplayTest}.
 */
class TestS2SpecialStage4TraceReplay extends AbstractS2SpecialStageTraceReplayTest {

    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s2", "runs",
                "s2-sonic-tails-complete-emeralds", "ss_4");
    }
}
