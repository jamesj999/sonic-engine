package com.openggf.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkFeatures;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.LoadTimeDecisionSource;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingSchedule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLoadQueueTraceComparison {

    @Test
    void parsesStrictCanonicalQueueEvent() {
        TraceEvent event = TraceEvent.parseJsonLine("""
                {"frame":7,"event":"load_queue_state","kind":"s1_nemesis_plc",
                "busy":true,"prepared":true,"active_source":1193046,
                "active_destination":837,"total_work":17,"remaining_work":9,
                "queued_fingerprints":["e1cb5d156a023180550e107c0fa41e1de038683d4cbe8c6176b3395bb4dae2fa"],
                "service_observations":[]}
                """, new ObjectMapper());

        assertInstanceOf(TraceEvent.LoadQueueState.class, event);
    }

    @Test
    void rejectsMalformedOrNonCanonicalQueueEvent() {
        assertThrows(IllegalArgumentException.class, () -> TraceEvent.parseJsonLine("""
                {"frame":7,"event":"load_queue_state","kind":"s1_nemesis_plc",
                "busy":false,"prepared":true,"active_source":1,
                "active_destination":-1,"total_work":-1,"remaining_work":-1,
                "queued_fingerprints":[],"service_observations":[]}
                """, new ObjectMapper()));
        assertThrows(IllegalArgumentException.class, () -> TraceEvent.parseJsonLine("""
                {"frame":7,"event":"load_queue_state","kind":"s1_nemesis_plc",
                "busy":true,"prepared":true,"active_source":-1,
                "active_destination":-1,"total_work":-1,"remaining_work":9,
                "queued_fingerprints":[],
                "service_observations":[{"boundary":"LEVEL_MAIN","budget":3}]}
                """, new ObjectMapper()));
    }

    @Test
    void queueMismatchBecomesOrdinaryFrameError() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = TraceFrame.of(7, 0, (short) 1, (short) 2,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        binder.compareFrame(frame, frame.x(), frame.y(), frame.xSpeed(),
                frame.ySpeed(), frame.gSpeed(), frame.angle(), frame.air(),
                frame.rolling(), frame.groundMode());
        TraceEvent.LoadQueueState expected = new TraceEvent.LoadQueueState(
                7, "s3k_kos_direct", true, true, 0x12233, 0xFF8000,
                -1, -1, List.of(), List.of());
        QueueDiagnosticSnapshot actual = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                true, true, 0x12234, 0xFF8000, -1, -1, List.of(),
                List.of());

        binder.compareLoadQueues(7, List.of(expected), List.of(actual));

        assertTrue(binder.comparisonForFrame(7)
                .hasErrorInField("queue.s3k_kos_direct.active_source"));
    }

    @Test
    void signed68kRamDestinationComparesAtZeroTolerance() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = frame(7);
        binder.compareFrame(frame, frame.x(), frame.y(), frame.xSpeed(),
                frame.ySpeed(), frame.gSpeed(), frame.angle(), frame.air(),
                frame.rolling(), frame.groundMode());
        TraceEvent.LoadQueueState expected = new TraceEvent.LoadQueueState(
                7, "s3k_kos_direct", true, true, 0x3A566C, 0xFFFFD000,
                -1, -1, List.of(), List.of());
        QueueDiagnosticSnapshot actual = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                true, true, 0x3A566C, 0xFFFFD000,
                -1, -1, List.of(), List.of());

        binder.compareLoadQueues(7, List.of(expected), List.of(actual));

        assertFalse(binder.comparisonForFrame(7).hasError());
    }

    @Test
    void advertisedCapabilityRejectsIncompleteFrameSet(@TempDir Path temp)
            throws Exception {
        Path metadataPath = temp.resolve("metadata.json");
        Files.writeString(metadataPath, """
                {"game":"s3k","zone":"aiz","act":1,"bk2_frame_offset":0,
                "trace_frame_count":1,"trace_schema":5,
                "aux_schema_extras":["load_queue_state_per_frame"]}
                """);
        TraceData trace = new TraceData(
                TraceMetadata.load(metadataPath),
                List.of(TraceFrame.of(0, 0, (short) 0, (short) 0,
                        (short) 0, (short) 0, (short) 0,
                        (byte) 0, false, false, 0)),
                Map.of(0, List.of(new TraceEvent.LoadQueueState(
                        0, "s3k_kos_direct", false, false,
                        -1, -1, -1, -1, List.of(), List.of()))),
                null);

        assertThrows(IllegalArgumentException.class,
                trace::validateAdvertisedLoadQueueStates);
    }

    @Test
    void advertisedCapabilityRequiresHeartbeatOnFirstMiddleAndLastStoredRows(
            @TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("metadata.json"), """
                {"game":"s1","zone":"ghz","act":1,"bk2_frame_offset":0,
                 "trace_frame_count":3,"trace_schema":5,
                 "aux_schema_extras":["load_queue_state_per_frame"]}
                """);
        TraceMetadata metadata = TraceMetadata.load(temp.resolve("metadata.json"));
        List<TraceFrame> frames = List.of(frame(0), frame(1), frame(2));
        TraceEvent.LoadQueueState idle0 = idleQueue(0);
        TraceEvent.LoadQueueState idle1 = idleQueue(1);
        TraceEvent.LoadQueueState idle2 = idleQueue(2);
        TraceData complete = new TraceData(metadata, frames,
                Map.of(0, List.of(idle0), 1, List.of(idle1), 2, List.of(idle2)),
                null);

        assertDoesNotThrow(complete::validateAdvertisedLoadQueueStates);

        TraceData missingLast = new TraceData(metadata, frames,
                Map.of(0, List.of(idle0), 1, List.of(idle1)), null);
        assertThrows(IllegalArgumentException.class,
                missingLast::validateAdvertisedLoadQueueStates);
    }

    @Test
    void acceptsSigned68kRamDestinationForPreparedS3kDirectQueue(
            @TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("metadata.json"), """
                {"game":"s3k","zone":"aiz","act":1,"bk2_frame_offset":0,
                 "trace_frame_count":1,"trace_schema":5,
                 "aux_schema_extras":["load_queue_state_per_frame"]}
                """);
        TraceEvent parsedDirect = TraceEvent.parseJsonLine("""
                {"frame":0,"event":"load_queue_state",
                 "kind":"s3k_kos_direct","busy":true,"prepared":true,
                 "active_source":3823212,"active_destination":-12288,
                 "total_work":-1,"remaining_work":-1,
                 "queued_fingerprints":[],"service_observations":[]}
                """, new ObjectMapper());
        assertInstanceOf(TraceEvent.LoadQueueState.class, parsedDirect);
        TraceData trace = new TraceData(
                TraceMetadata.load(temp.resolve("metadata.json")),
                List.of(frame(0)),
                Map.of(0, List.of(
                        parsedDirect,
                        new TraceEvent.LoadQueueState(
                                0, "s3k_kos_module", false, false,
                                -1, -1, -1, -1, List.of(), List.of()))),
                null);

        assertDoesNotThrow(trace::validateAdvertisedLoadQueueStates);
    }

    @Test
    void rejectsS3kDirectDestinationsOutsideSigned68kWorkRam(
            @TempDir Path temp) throws Exception {
        Files.writeString(temp.resolve("metadata.json"), """
                {"game":"s3k","zone":"aiz","act":1,"bk2_frame_offset":0,
                 "trace_frame_count":1,"trace_schema":5,
                 "aux_schema_extras":["load_queue_state_per_frame"]}
                """);
        TraceMetadata metadata = TraceMetadata.load(
                temp.resolve("metadata.json"));

        for (int destination : List.of(-65537, -1, 0, 0xD000)) {
            TraceData trace = new TraceData(metadata, List.of(frame(0)),
                    Map.of(0, List.of(
                            new TraceEvent.LoadQueueState(
                                    0, "s3k_kos_direct", true, true,
                                    0x3A566C, destination,
                                    -1, -1, List.of(), List.of()),
                            new TraceEvent.LoadQueueState(
                                    0, "s3k_kos_module", false, false,
                                    -1, -1, -1, -1,
                                    List.of(), List.of()))),
                    null);
            assertThrows(IllegalArgumentException.class,
                    trace::validateAdvertisedLoadQueueStates,
                    () -> "accepted destination " + destination);
        }
    }

    /**
     * A child published by {@code Process_Kos_Module_Queue}'s loop tail
     * (docs/skdisasm/sonic3k.asm:7908) is still an unfinished FIFO head at the
     * next {@code Wait_VSync} sample (7888): {@code Process_Kos_Queue} (7887)
     * can be interrupted and bookmarked mid-stream by V-int
     * (docs/skdisasm/sonic3k.asm:2840-2843, 2957). A held loop tail does not
     * change that, so the recorded row is compared as sampled.
     */
    @Test
    void comparesHeldRowDirectChildAsRecorded(
            @TempDir Path temp) throws Exception {
        TraceMetadata metadata = s3kQueueMetadata(temp, 3);
        TraceEvent.LoadQueueState module0 = moduleQueue(0);
        TraceEvent.LoadQueueState module1 = moduleQueue(1);
        TraceEvent.LoadQueueState module2 = moduleQueue(2);
        TraceData trace = new TraceData(metadata, List.of(
                TraceFrame.executionTestFrame(0, 100, 9, 0),
                TraceFrame.executionTestFrame(1, 101, 10, 0),
                TraceFrame.executionTestFrame(2, 102, 10, 1)),
                Map.of(
                        0, List.of(directIdle(0), module0),
                        1, List.of(directChild(1), module1),
                        2, List.of(directIdle(2), module2)),
                directCompletionSchedule(2));

        TraceEvent.LoadQueueState comparedDirect = queueState(
                trace.loadQueueStatesForComparisonFrame(1),
                "s3k_kos_direct");

        assertTrue(comparedDirect.busy());
        assertFalse(comparedDirect.prepared());
        assertEquals(1, comparedDirect.frame());
    }

    @Test
    void retainsBatchFirstAdmittedOnHeldTail(
            @TempDir Path temp) throws Exception {
        TraceMetadata metadata = s3kQueueMetadata(temp, 2);
        TraceData trace = new TraceData(metadata, List.of(
                TraceFrame.executionTestFrame(0, 100, 10, 0),
                TraceFrame.executionTestFrame(1, 101, 10, 1)),
                Map.of(
                        0, List.of(directChild(0), moduleQueue(0)),
                        1, List.of(directIdle(1), moduleQueue(1))),
                directCompletionSchedule(1));

        TraceEvent.LoadQueueState comparedDirect = queueState(
                trace.loadQueueStatesForComparisonFrame(0),
                "s3k_kos_direct");

        assertTrue(comparedDirect.busy());
        assertFalse(comparedDirect.prepared());
    }

    @Test
    void identifiesDirectChildMissedBetweenIdleFrameEndHeartbeats(
            @TempDir Path temp) throws Exception {
        TraceMetadata metadata = s3kQueueMetadata(temp, 3);
        TraceData trace = new TraceData(metadata, List.of(
                TraceFrame.executionTestFrame(0, 100, 9, 0),
                TraceFrame.executionTestFrame(1, 101, 10, 0),
                TraceFrame.executionTestFrame(2, 102, 10, 1)),
                Map.of(
                        0, List.of(directIdle(0), moduleQueue(0)),
                        1, List.of(directIdle(1), moduleQueue(1)),
                        2, List.of(directIdle(2), moduleQueue(2))),
                directCompletionSchedule(2));

        HardwareCompletionEdge edge =
                trace.unobservedDirectChildForComparisonFrame(1);

        assertNotNull(edge);
        assertEquals(2, edge.rawFrame());
        assertEquals(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, edge.kind());
        assertNull(trace.unobservedDirectChildForComparisonFrame(0));
    }

    /**
     * The engine's direct-queue "prepared" flag is a boundary-granular
     * over-approximation of the ROM's sub-frame decompression-in-progress sign
     * bit, so actual=true/expected=false is excused while the head's recorded
     * completion is still in the future.
     */
    @Test
    void excusesBoundaryGranularInProgressBitAheadOfRecordedCompletion(
            @TempDir Path temp) throws Exception {
        LoadQueueComparisonProjection projection =
                projectDirect(temp, false, true, 2);

        assertTrue(queueState(projection.expected(), "s3k_kos_direct").prepared());
        assertTrue(queueState(projection.expected(), "s3k_kos_direct").busy());
        assertEquals(0x37005A,
                queueState(projection.expected(), "s3k_kos_direct").activeSource());
        assertEquals(2, projection.expected().size());
        assertEquals(1, projection.actual().size());
    }

    /**
     * The opposite polarity is a real timing defect — the engine claiming the
     * decompression was not in progress while the ROM was mid-loop means a
     * completion landed early — so it must never be excused.
     */
    @Test
    void keepsReverseInProgressPolarityAsError(@TempDir Path temp)
            throws Exception {
        LoadQueueComparisonProjection projection =
                projectDirect(temp, true, false, 2);

        assertTrue(queueState(projection.expected(), "s3k_kos_direct").prepared());

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = frame(1);
        binder.compareFrame(frame, frame.x(), frame.y(), frame.xSpeed(),
                frame.ySpeed(), frame.gSpeed(), frame.angle(), frame.air(),
                frame.rolling(), frame.groundMode());
        binder.compareLoadQueues(1, projection.expected(), projection.actual());

        assertTrue(binder.comparisonForFrame(1)
                .hasErrorInField("queue.s3k_kos_direct.prepared"));
    }

    /** With no recorded completion still ahead, a stray true stays an error. */
    @Test
    void keepsInProgressBitComparedWithoutFutureRecordedCompletion(
            @TempDir Path temp) throws Exception {
        LoadQueueComparisonProjection projection =
                projectDirect(temp, false, true, 1);

        assertFalse(queueState(projection.expected(), "s3k_kos_direct").prepared());
    }

    private static LoadQueueComparisonProjection projectDirect(
            Path temp, boolean expectedPrepared, boolean actualPrepared,
            int completionFrame) throws Exception {
        TraceMetadata metadata = s3kQueueMetadata(temp, 3);
        TraceEvent.LoadQueueState expectedDirect = new TraceEvent.LoadQueueState(
                1, "s3k_kos_direct", true, expectedPrepared,
                0x37005A, 0xFFFFD000, -1, -1, List.of(), List.of());
        TraceData trace = new TraceData(metadata, List.of(
                TraceFrame.executionTestFrame(0, 100, 9, 0),
                TraceFrame.executionTestFrame(1, 101, 10, 0),
                TraceFrame.executionTestFrame(2, 102, 11, 0)),
                Map.of(
                        0, List.of(directIdle(0), moduleQueue(0)),
                        1, List.of(expectedDirect, moduleQueue(1)),
                        2, List.of(directIdle(2), moduleQueue(2))),
                directCompletionSchedule(completionFrame));
        QueueDiagnosticSnapshot actualDirect = new QueueDiagnosticSnapshot(
                QueueDiagnosticSnapshot.Kind.S3K_KOS_DIRECT,
                true, actualPrepared, 0x37005A, 0xFFFFD000,
                -1, -1, List.of(), List.of());

        return LoadQueueComparisonProjection.project(
                trace, 1, trace.loadQueueStatesForComparisonFrame(1),
                List.of(actualDirect), pendingDirectJobTiming());
    }

    private static HardwareTimingSnapshot pendingDirectJobTiming() {
        HardwareWorkHandle handle = new HardwareWorkHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0, "child");
        HardwareTimingJob.Snapshot job = new HardwareTimingJob.Snapshot(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                0x37005A, 0x100, 0xFFFFD000, 0x800, "kosinski", 1, false,
                HardwareWorkFeatures.EMPTY, handle, () -> null, null,
                false, false, false, false, 1, 1,
                Set.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                LoadTimeDecisionSource.MEASURED, "recorded");
        return new HardwareTimingSnapshot(
                Map.of(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1L,
                        HardwareWorkKind.KOS_MODULE_QUEUE, 0L,
                        HardwareWorkKind.NEMESIS_PLC_QUEUE, 0L),
                List.of(job),
                Map.of(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED,
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED,
                        HardwareWorkKind.NEMESIS_PLC_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED),
                true, true, HardwareServiceBoundary.PRE_MAIN_LOOP);
    }

    private static TraceMetadata s3kQueueMetadata(Path temp, int frameCount)
            throws Exception {
        Files.writeString(temp.resolve("metadata.json"), """
                {"game":"s3k","zone":"aiz","act":1,
                 "bk2_frame_offset":0,"trace_frame_count":%d,
                 "trace_schema":5,
                 "aux_schema_extras":["load_queue_state_per_frame"]}
                """.formatted(frameCount));
        return TraceMetadata.load(temp.resolve("metadata.json"));
    }

    private static HardwareTimingSchedule directCompletionSchedule(int frame) {
        return new HardwareTimingSchedule(List.of(new HardwareCompletionEdge(
                frame, HardwareServiceBoundary.PRE_MAIN_LOOP,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0, "child")));
    }

    private static TraceEvent.LoadQueueState directChild(int frame) {
        return new TraceEvent.LoadQueueState(
                frame, "s3k_kos_direct", true, false,
                0x37005A, 0xFFFFD000, -1, -1, List.of(), List.of());
    }

    private static TraceEvent.LoadQueueState directIdle(int frame) {
        return new TraceEvent.LoadQueueState(
                frame, "s3k_kos_direct", false, false,
                -1, -1, -1, -1, List.of(), List.of());
    }

    private static TraceEvent.LoadQueueState moduleQueue(int frame) {
        return new TraceEvent.LoadQueueState(
                frame, "s3k_kos_module", true, true,
                -1, -1, -1, 1, List.of("next"), List.of());
    }

    private static TraceEvent.LoadQueueState queueState(
            List<TraceEvent.LoadQueueState> states, String kind) {
        return states.stream()
                .filter(state -> kind.equals(state.kind()))
                .findFirst()
                .orElseThrow();
    }

    private static TraceFrame frame(int frame) {
        return TraceFrame.of(frame, 0, (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
    }

    private static TraceEvent.LoadQueueState idleQueue(int frame) {
        return new TraceEvent.LoadQueueState(frame, "s1_nemesis_plc",
                false, false, -1, -1, -1, -1, List.of(), List.of());
    }
}
