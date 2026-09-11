package com.openggf.tests.trace.s2;

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
 * Concrete S2 special-stage trace replay against the committed MVP trace at
 * {@code src/test/resources/traces/s2/special_stage} (Special Stage 1,
 * Sonic + Tails). Every compared field is release-ratcheted: any divergence
 * fails the replay. See
 * {@link AbstractS2SpecialStageTraceReplayTest}.
 */
class TestS2SpecialStageTraceReplay extends AbstractS2SpecialStageTraceReplayTest {

    @Override
    protected Path traceDirectory() {
        return Path.of(System.getProperty(
                "openggf.trace.candidate.dir",
                TRACE_DIRECTORY.toString()));
    }

    @Test
    void releaseRatchetRejectsTierOneError() {
        DivergenceReport report = new DivergenceReport(List.of(new FrameComparison(0, Map.of(
                "present", new FieldComparison("present", "true", "false",
                Severity.ERROR, 1)))));

        assertThrows(AssertionFailedError.class,
                () -> assertNoReleaseBlockingDivergences(report));
    }
}
