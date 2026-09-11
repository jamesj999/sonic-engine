package com.openggf.trace.replay.runs;

import com.openggf.trace.StoredPhysicsFrameDomain;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceRunManifest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Test-only eager reference loader for focused synthetic adapter fixtures. */
public final class TraceRunReferencePlanLoader {
    private TraceRunReferencePlanLoader() {
    }

    public static List<TraceRunReplayWalker.SegmentPlan> load(
            TraceRunManifest run, Path runDirectory) throws IOException {
        List<TraceRunSegmentDescriptor> descriptors =
                TraceRunReplayWalker.planDescriptors(run, runDirectory);
        List<TraceRunReplayWalker.SegmentPlan> result =
                new ArrayList<>(descriptors.size());
        for (TraceRunSegmentDescriptor descriptor : descriptors) {
            TraceRunManifest.Segment segment = descriptor.segment();
            Path segmentDirectory = descriptor.segmentDirectory();
            TraceData trace;
            TraceRunSpecialStageRows specialRows;
            if ("special_stage".equals(segment.kind())) {
                specialRows = TraceRunSpecialStageRows.load(
                        segment.traceProfile(), segmentDirectory,
                        segment.dynamicArtInitialLedgerDescriptors());
                trace = TraceData.loadMetadataOnly(
                        segmentDirectory,
                        StoredPhysicsFrameDomain.FrameEncoding.DECIMAL,
                        segment.dynamicArtInitialLedgerDescriptors());
            } else {
                specialRows = null;
                trace = TraceData.load(
                        segmentDirectory,
                        segment.dynamicArtInitialLedgerDescriptors());
            }
            result.add(new TraceRunReplayWalker.SegmentPlan(
                    segment, trace, descriptor.entryBoundary(),
                    descriptor.exitBoundary(), specialRows,
                    descriptor.executionPolicy()));
        }
        return List.copyOf(result);
    }
}
