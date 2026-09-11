package com.openggf.tests.trace.runs;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceStructuralRowComparator {

    @Test
    void nonterminalPresentationRowComparesOnlyPhysicalStructures(
            @TempDir Path temp) throws Exception {
        TraceData trace = structuralTrace(temp, 2);
        TraceStructuralRowComparator comparator = comparator(
                trace, List.of(idleQueueSnapshot()));

        comparator.prepareRow(new Bk2FrameInput(
                912, AbstractPlayableSprite.INPUT_LEFT,
                0, false, "physical presentation input"));
        FrameComparison result = comparator.completePostProduction(
                DynamicArtDiagnosticsSnapshot.unpublished(20, 7),
                publishedEmptyDynamicArt(0, 21, 7), true, true);

        assertTrue(result.hasErrorInField("input_alignment"),
                "the exact physical BK2 frame must be validated");
        assertTrue(result.fields().containsKey(
                "queue.s1_nemesis_plc.present"));
        assertTrue(result.fields().containsKey("dynamic_art.frame"));
        assertFalse(result.fields().containsKey("x"));
        assertFalse(result.fields().containsKey("camera_x"));
        assertFalse(result.fields().containsKey("player_animation_id"));

        assertEquals(1, comparator.errorCount());
        assertEquals(0, comparator.warningCount());
        assertEquals(1, comparator.laggedFrames());
        assertEquals(0, comparator.recentActionMask());
        assertEquals(AbstractPlayableSprite.INPUT_LEFT,
                comparator.recentInputMask());
        assertFalse(comparator.recentStartPressed());
        assertTrue(comparator.hasRecordingDesync());
        assertEquals("input_alignment",
                comparator.recentMismatches().getLast().field());
    }

    @Test
    void terminalProductionDefersDynamicArtButRetainsInputAndQueue(
            @TempDir Path temp) throws Exception {
        TraceData trace = structuralTrace(temp, 1);
        TraceStructuralRowComparator comparator = comparator(trace, List.of());

        comparator.prepareRow(new Bk2FrameInput(
                77, AbstractPlayableSprite.INPUT_LEFT,
                0, false, "terminal presentation input"));
        DynamicArtDiagnosticsSnapshot terminalPublication =
                publishedEmptyDynamicArt(0, 31, 9);
        FrameComparison beforeClosure = comparator.completePostProduction(
                DynamicArtDiagnosticsSnapshot.unpublished(30, 9),
                terminalPublication);

        assertNull(beforeClosure,
                "the terminal row must be emitted only after the comparison window closes");
        assertFalse(comparator.isComplete());

        FrameComparison afterClosure = comparator.finalizeSegment(
                terminalPublication);

        assertTrue(afterClosure.hasErrorInField("input_alignment"));
        assertTrue(afterClosure.hasErrorInField(
                "queue.s1_nemesis_plc.present"));
        assertTrue(afterClosure.fields().containsKey("dynamic_art.frame"));
        assertFalse(afterClosure.fields().containsKey("x"));
        assertFalse(afterClosure.fields().containsKey("camera_x"));
        assertFalse(afterClosure.fields().containsKey(
                "player_animation_id"));
        assertTrue(comparator.isComplete());
        assertNull(comparator.finalizeSegment(afterClosureSnapshot()),
                "terminal publication must be idempotent");
    }

    @Test
    void lifecycleRejectsOverlappingOrOutOfOrderRows(
            @TempDir Path temp) throws Exception {
        TraceStructuralRowComparator comparator = comparator(
                structuralTrace(temp, 2), List.of(idleQueueSnapshot()));
        Bk2FrameInput input = new Bk2FrameInput(
                0, AbstractPlayableSprite.INPUT_RIGHT,
                0, false, "presentation input");

        assertThrows(IllegalStateException.class,
                () -> comparator.completePostProduction(
                        DynamicArtDiagnosticsSnapshot.unpublished(0, 1),
                        publishedEmptyDynamicArt(0, 1, 1)));

        comparator.prepareRow(input);
        assertThrows(IllegalStateException.class,
                () -> comparator.prepareRow(input));
        assertThrows(IllegalStateException.class,
                () -> comparator.finalizeSegment(afterClosureSnapshot()));
    }

    private static TraceStructuralRowComparator comparator(
            TraceData trace, List<QueueDiagnosticSnapshot> queues) {
        // Owns its own timing service rather than reaching for the installed
        // gameplay mode: GameServices.hardwareTiming() throws without one, so
        // depending on it made this class pass only when an earlier test in the
        // same fork had leaked a context.
        HardwareTimingService timing = new HardwareTimingService();
        return new TraceStructuralRowComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> queues, timing::capture);
    }

    private static TraceData structuralTrace(Path temp, int frameCount)
            throws Exception {
        Path metadataPath = temp.resolve("metadata.json");
        Files.writeString(metadataPath, """
                {"game":"s1","zone":"TEST","zone_id":0,"act":0,
                 "bk2_frame_offset":0,"trace_frame_count":%d,
                 "trace_schema":5,
                 "aux_schema_extras":["load_queue_state_per_frame",
                 "dynamic_art_transfer_state_per_frame"]}
                """.formatted(frameCount));
        TraceMetadata metadata = TraceMetadata.load(metadataPath);

        List<TraceFrame> frames = java.util.stream.IntStream.range(0, frameCount)
                .mapToObj(frame -> new TraceFrame(
                        frame, AbstractPlayableSprite.INPUT_RIGHT,
                        (short) (0x2000 + frame), (short) 0x0300,
                        (short) 0x0100, (short) 0, (short) 0x0100,
                        (byte) 0, false, false, 0,
                        0xAA00, 0x5500, 2, 0x1234, 0x0300,
                        99, 6, 0x100 + frame, 0x20,
                        0x200 + frame, 0, 0x1C, 0x2E, null))
                .toList();
        Map<Integer, List<TraceEvent>> events =
                new java.util.LinkedHashMap<>();
        for (int frame = 0; frame < frameCount; frame++) {
            events.put(frame, List.of(
                    idleQueueEvent(frame),
                    new TraceEvent.DynamicArtTransferState(
                            frame, List.of(), List.of())));
        }
        return TraceFixtures.trace(metadata, frames, events);
    }

    private static TraceEvent.LoadQueueState idleQueueEvent(int frame) {
        return new TraceEvent.LoadQueueState(
                frame, "s1_nemesis_plc", false, false,
                -1, -1, -1, -1, List.of(), List.of());
    }

    private static QueueDiagnosticSnapshot idleQueueSnapshot() {
        return QueueDiagnosticSnapshot.idle(
                QueueDiagnosticSnapshot.Kind.S1_NEMESIS_PLC, List.of());
    }

    private static DynamicArtDiagnosticsSnapshot publishedEmptyDynamicArt(
            int frame, long deliverySerial, long generation) {
        return new DynamicArtDiagnosticsSnapshot(
                frame, List.of(), List.of(), deliverySerial,
                generation, true);
    }

    private static DynamicArtDiagnosticsSnapshot afterClosureSnapshot() {
        return publishedEmptyDynamicArt(0, 31, 9);
    }
}
