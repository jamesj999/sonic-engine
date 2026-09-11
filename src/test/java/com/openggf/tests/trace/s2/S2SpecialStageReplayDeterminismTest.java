package com.openggf.tests.trace.s2;

import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.SpecialStageTraceData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.openggf.tests.trace.s2.AbstractS2SpecialStageTraceReplayTest.TRACE_DIRECTORY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Determinism guard for the S2 special-stage replay: two full replays in the
 * same JVM (each with a fresh headless boot via
 * {@link AbstractS2SpecialStageTraceReplayTest#bootHarness}) must produce
 * byte-identical report JSON. The engine may diverge from the ROM trace, but it
 * must diverge the SAME way every run — non-determinism here would make the
 * red-allowed report unusable as a frontier signal.
 */
@FullReset
@ExtendWith(SingletonResetExtension.class)
class S2SpecialStageReplayDeterminismTest {

    @Test
    void twoReplaysProduceIdenticalReportJson() throws Exception {
        File romFile = com.openggf.tests.RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && Files.exists(Path.of("s2.gen")),
                "s2.gen ROM required for S2 special-stage trace replay");

        String firstJson = runReplay(TRACE_DIRECTORY, romFile);
        String secondJson = runReplay(TRACE_DIRECTORY, romFile);

        assertEquals(firstJson, secondJson,
                "S2 special-stage replay must be deterministic across runs in one JVM");
    }

    private static String runReplay(Path dir, File romFile) throws Exception {
        SpecialStageTraceData trace = SpecialStageTraceData.load(dir);
        S2SpecialStageReplayHarness harness =
                AbstractS2SpecialStageTraceReplayTest.bootHarness(trace, dir, romFile);
        DivergenceReport report =
                AbstractS2SpecialStageTraceReplayTest.compareReplay(trace, harness);
        return report.toJson();
    }
}
