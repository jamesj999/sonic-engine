package com.openggf.trace.replay.runs;

import com.openggf.trace.TraceRunManifest;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestActiveSegmentPayload {

    @Test
    void ordinaryPayloadGuardsAllAccessAfterIdempotentClose(@TempDir Path root)
            throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS3kBonusRun(root.resolve("runs"));
        TraceRunSegmentDescriptor descriptor = descriptors(runDirectory).getFirst();

        ActiveSegmentPayload payload = TraceRunReplayWalker.openActiveSegment(
                descriptor, 0);

        assertSame(descriptor, payload.descriptor());
        assertNotNull(payload.trace());
        assertNull(payload.specialStageRows());
        assertFalse(payload.isClosed());

        payload.close();

        assertTrue(payload.isClosed());
        assertThrows(IllegalStateException.class, payload::trace);
        assertThrows(IllegalStateException.class, payload::specialStageRows);
        assertThrows(IllegalStateException.class, payload::descriptor);
        payload.close();
    }

    @Test
    void specialStagePayloadRetainsTheEagerCompositeShape(@TempDir Path root)
            throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("runs"));
        TraceRunManifest run = TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor descriptor = TraceRunReplayWalker
                .planDescriptors(run, runDirectory).get(1);
        TraceRunManifest.Segment segment = run.segments().get(1);
        TraceRunSpecialStageRows expectedRows = TraceRunSpecialStageRows.load(
                segment.traceProfile(), runDirectory.resolve(segment.dir()),
                segment.dynamicArtInitialLedgerDescriptors());
        com.openggf.trace.TraceData expectedTrace =
                com.openggf.trace.TraceData.loadMetadataOnly(
                        runDirectory.resolve(segment.dir()),
                        com.openggf.trace.StoredPhysicsFrameDomain.FrameEncoding.DECIMAL,
                        segment.dynamicArtInitialLedgerDescriptors());

        try (ActiveSegmentPayload payload = TraceRunReplayWalker.openActiveSegment(
                descriptor, 1)) {
            assertSame(descriptor, payload.descriptor());
            assertNotNull(payload.trace());
            assertNotNull(payload.specialStageRows());
            assertEquals(0, payload.trace().frameCount(),
                    "special-stage TraceData remains metadata-only");
            assertEquals(expectedTrace.metadata(), payload.trace().metadata());
            assertEquals(expectedTrace.hardwareTimingSchedule(),
                    payload.trace().hardwareTimingSchedule());
            assertEquals(expectedRows.metadata(),
                    payload.specialStageRows().metadata());
            assertEquals(expectedRows.rowCount(),
                    payload.specialStageRows().rowCount());
            assertEquals(expectedRows.hardwareTimingSchedule(),
                    payload.specialStageRows().hardwareTimingSchedule());
            assertEquals(expectedRows.newRunObjectsPassBinder().isPresent(),
                    payload.specialStageRows().newRunObjectsPassBinder().isPresent());
            assertEquals(expectedRows.normalizedDynamicArtRows(),
                    payload.specialStageRows().normalizedDynamicArtRows());
        }
    }

    @Test
    void specialStagePayloadRetainsPassBinderAndSpillRowsWhenRecorded(
            @TempDir Path root) throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("runs"));
        Path specialStage = runDirectory.resolve("ss");
        Files.writeString(specialStage.resolve("aux_state.jsonl"),
                Files.readString(specialStage.resolve("aux_state.jsonl")) + """
                {"frame":0,"type":"control_state","started":1}
                {"frame":1,"type":"run_objects_end","pass_sequence":0,"started_at_input_sample":1,"first_eligible_frame":1,"completion_cursor_frame":1,"input_sample_frame":1,"input_sample_bk2_frame":801,"previous_input_sample_frame":0,"previous_input_sample_bk2_frame":800,"input_sample_sequence":1,"input_source":"vint_s2ss_read_joypads","p1_held":0,"p2_held":0,"previous_p1_held":0,"previous_p2_held":0}
                """);
        TraceRunManifest run = TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor descriptor = TraceRunReplayWalker
                .planDescriptors(run, runDirectory).get(1);
        TraceRunManifest.Segment segment = run.segments().get(1);
        TraceRunSpecialStageRows expectedRows = TraceRunSpecialStageRows.load(
                segment.traceProfile(), specialStage,
                segment.dynamicArtInitialLedgerDescriptors());

        try (ActiveSegmentPayload payload = TraceRunReplayWalker.openActiveSegment(
                descriptor, 1)) {
            TraceRunSpecialStageRows rows = payload.specialStageRows();
            assertTrue(rows.newRunObjectsPassBinder().isPresent());
            assertEquals(expectedRows.passPacedFromRow(), rows.passPacedFromRow());
            assertEquals(expectedRows.normalizedDynamicArtRows(),
                    rows.normalizedDynamicArtRows());
        }
    }

    @Test
    void specialStageConstructionFailureDoesNotPublishPartialPayload(
            @TempDir Path root) throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("runs"));
        TraceRunManifest run = TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor descriptor = TraceRunReplayWalker
                .planDescriptors(run, runDirectory).get(1);
        Files.writeString(runDirectory.resolve("ss/aux_state.jsonl"), "{not-json}\n");

        var failure = assertThrows(java.io.IOException.class,
                () -> TraceRunReplayWalker.openActiveSegment(descriptor, 1));

        assertTrue(failure.getMessage().contains("Segment 1 parser failed"));
        assertTrue(failure.getMessage().contains("s2_special_stage"));
    }

    private static List<TraceRunSegmentDescriptor> descriptors(Path runDirectory)
            throws Exception {
        return TraceRunReplayWalker.planDescriptors(TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json")), runDirectory);
    }
}
