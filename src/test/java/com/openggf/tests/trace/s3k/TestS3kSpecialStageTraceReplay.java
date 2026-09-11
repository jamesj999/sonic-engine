package com.openggf.tests.trace.s3k;

import com.openggf.trace.DivergenceReport;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Concrete S3K special-stage trace replay against
 * {@code src/test/resources/traces/s3k/special_stage}. No trace is committed
 * there yet, so {@link AbstractS3kSpecialStageTraceReplayTest
 * #replayProducesFaithfulReport} skips (via {@code assumeTrue}) rather than
 * fabricating trace data; the harness and comparator are exercised end to
 * end once a real BizHawk capture lands in that directory. See
 * {@link AbstractS3kSpecialStageTraceReplayTest}.
 */
class TestS3kSpecialStageTraceReplay extends AbstractS3kSpecialStageTraceReplayTest {

    @Override
    protected Path traceDirectory() {
        return TRACE_DIRECTORY;
    }

    @Test
    void releaseRatchetRejectsTierOneError() {
        DivergenceReport report = new DivergenceReport(List.of(new FrameComparison(0, Map.of(
                "started", new FieldComparison("started", "true", "false",
                Severity.ERROR, 1)))));

        assertThrows(AssertionFailedError.class,
                () -> assertNoReleaseBlockingDivergences(report));
    }
}
