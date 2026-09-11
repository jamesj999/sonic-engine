package com.openggf.tests.trace;

import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the synthetic S2 multi-segment run fixture
 * ({@code run_ehz_ss_3seg}: level -&gt; special_stage -&gt; level), the
 * executable specification for the Task 1 {@code s2_trace_recorder.lua}
 * run-mode emitters. Model: {@link TestTraceRunSyntheticFixture} (the plan-a
 * S3K synthetic fixture test), except the special-stage segment here loads
 * through {@link SpecialStageTraceData} (the S2 48-column ss schema) rather
 * than the shared v7 level {@link TraceData} loader used for the bonus-stage
 * segment in the S3K model.
 */
class TestS2SyntheticRunFixture {

    @Test
    void manifestLoadsAndValidates(@TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root);
        TraceRunManifest run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));
        run.validate(runDir);

        assertEquals("s2", run.game());
        assertEquals(3, run.segments().size());

        TraceRunManifest.Segment seg0 = run.segments().get(0);
        assertEquals("level", seg0.kind());
        assertEquals("gameplay_unlock", seg0.traceProfile());
        assertEquals(0, seg0.zoneId());
        assertEquals(1, seg0.act());

        TraceRunManifest.Segment seg1 = run.segments().get(1);
        assertEquals("special_stage", seg1.kind());
        assertEquals("s2_special_stage", seg1.traceProfile());
        assertEquals(0, seg1.specialStageIndex());

        TraceRunManifest.Segment seg2 = run.segments().get(2);
        assertEquals("level", seg2.kind());
        assertEquals("gameplay_unlock", seg2.traceProfile());
        assertEquals(0, seg2.zoneId());
        assertEquals(1, seg2.act());

        assertEquals(2, run.transitions().size());

        TraceRunManifest.Transition starpostSpecial = run.transitions().get(0);
        assertEquals(0, starpostSpecial.fromSegment());
        assertEquals(1, starpostSpecial.toSegment());
        assertEquals("starpost_special", starpostSpecial.entryKind());
        assertNotNull(starpostSpecial.specialBonusEntryFlag());
        assertNotNull(starpostSpecial.savedXPos());
        assertNotNull(starpostSpecial.savedYPos());
        assertNotNull(starpostSpecial.lastStarPostHit());
        assertNotNull(starpostSpecial.ringsBefore());
        assertNotNull(starpostSpecial.emeraldsBefore());

        TraceRunManifest.Transition stageExit = run.transitions().get(1);
        assertEquals(1, stageExit.fromSegment());
        assertEquals(2, stageExit.toSegment());
        assertEquals("stage_exit", stageExit.entryKind());
        assertEquals(0, stageExit.ringsAfter());
        assertNotNull(stageExit.emeraldsAfter());
    }

    @Test
    void specialStageSegmentParsesThroughSpecialStageTraceData(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root);
        SpecialStageTraceData ss = SpecialStageTraceData.load(runDir.resolve("ss"));

        assertEquals(2, ss.frameCount());
        assertEquals("run_ehz_ss_3seg", ss.metadata().runId());
        assertEquals(1, ss.metadata().segmentIndex());
    }

    @Test
    void bothLevelSegmentsParseThroughTraceDataWithMatchingFrameCounts(@TempDir Path root)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root);
        TraceRunManifest run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));
        run.validate(runDir);

        for (TraceRunManifest.Segment seg : run.segments()) {
            if (!"level".equals(seg.kind())) {
                continue;
            }
            TraceData data = TraceData.load(runDir.resolve(seg.dir()));
            assertEquals(seg.traceFrameCount(), data.frameCount(), "segment " + seg.dir());
            assertEquals("run_ehz_ss_3seg", data.metadata().runId(), "segment " + seg.dir());
            assertNotNull(data.metadata().segmentIndex(), "segment " + seg.dir());
        }
    }
}
