package com.openggf.tests.trace.runs;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.SegmentExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestTraceRunSegmentExecutionPolicyCatalog {

    private static final List<CatalogRun> RUNS = List.of(
            run("s1", "s1-ghz-maze-roundtrip",
                    SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE, 1),
            run("s1", "s1-sonic-complete-withemeralds",
                    SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE, 6),
            run("s2", "s2-ehz-halfpipe-roundtrip",
                    SegmentExecutionPolicy.GAMEPLAY, 2),
            run("s3k", "s3-knux-multibonus-ss",
                    SegmentExecutionPolicy.GAMEPLAY, 11));

    @Test
    void committedStageExitDestinationsRetainTheirRecordedExecutionPolicy()
            throws Exception {
        int classified = 0;
        for (CatalogRun catalogRun : RUNS) {
            TraceRunManifest manifest = TraceRunManifest.load(
                    catalogRun.runDir().resolve("run_manifest.json"));
            List<TraceRunManifest.Transition> stageExits =
                    manifest.transitions().stream()
                            .filter(transition -> "stage_exit".equals(
                                    transition.entryKind()))
                            .toList();
            assertEquals(catalogRun.expectedStageExitCount(), stageExits.size(),
                    catalogRun.runDir()::toString);
            for (TraceRunManifest.Transition stageExit : stageExits) {
                TraceRunManifest.Segment destination =
                        manifest.segments().get(stageExit.toSegment());
                TraceData trace = TraceData.load(
                        catalogRun.runDir().resolve(destination.dir()),
                        destination.dynamicArtInitialLedgerDescriptors());
                assertEquals(catalogRun.expectedPolicy(),
                        TraceRunReplayWalker.segmentExecutionPolicy(
                                destination, stageExit, trace),
                        () -> manifest.runId() + "/" + destination.dir());
                classified++;
            }
        }
        assertEquals(20, classified,
                "the committed catalog must cover every selected stage-exit destination");
    }

    private static CatalogRun run(
            String game, String runId, SegmentExecutionPolicy expected,
            int stageExitCount) {
        return new CatalogRun(Path.of(
                "src", "test", "resources", "traces", game, "runs", runId),
                expected, stageExitCount);
    }

    private record CatalogRun(
            Path runDir,
            SegmentExecutionPolicy expectedPolicy,
            int expectedStageExitCount) {
    }
}
