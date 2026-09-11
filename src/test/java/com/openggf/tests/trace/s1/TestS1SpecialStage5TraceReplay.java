package com.openggf.tests.trace.s1;

import java.nio.file.Path;

/**
 * Replays special stage 5 (recorded {@code special_stage_index} 4) from the
 * {@code s1-sonic-complete-withemeralds} complete-run capture. The run segment
 * carries the same standalone fixture layout as the committed
 * {@code traces/s1/special_stage} recording (metadata.json + physics.csv.gz +
 * aux_state.jsonl.gz), so it replays through the shared comparator unchanged.
 * Comparison-only: no engine state is hydrated from the trace.
 */
class TestS1SpecialStage5TraceReplay extends AbstractS1SpecialStageTraceReplayTest {
    @Override
    protected Path traceDirectory() {
        return Path.of("src", "test", "resources", "traces", "s1", "runs",
                "s1-sonic-complete-withemeralds", "ss_5");
    }
}
