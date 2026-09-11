package com.openggf.tools.audio.completerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PsgWriteObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.YmWriteObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Baseline;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CutoffFrontier;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Frame;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManifestSegment;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NativeCapabilitySummary;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Record;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.YmWrite;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.PsgWrite;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerObservationInventory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TestCompleteRunAudioCaptureReducer {
    @Test
    void frameChipProjectorRejectsAnyBroaderObservationClaim() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteRunAudioFrameChipProjection(
                        ProducerObservationInventory.allObserved()));
    }

    @Test
    void reducesFromRowZeroAndRetainsOnlyTheComparisonEpoch() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        CompleteRunAudioCaptureReducer reducer = new CompleteRunAudioCaptureReducer(
                fixture(), writer, projection());

        feed(reducer, 0, List.of());
        feed(reducer, 1, List.of());
        feed(reducer, 2, List.of(new YmWriteObserved(0, 1, 0x22, 0x33)));
        feed(reducer, 3, 1, List.of());
        feed(reducer, 4, 1, List.of(new PsgWriteObserved(1, 0x90)));
        reducer.finish(snapshot(41));

        assertEquals(List.of("baseline", "frame:2", "frame:3", "frame:4",
                "cutoff", "finish", "close"), writer.operations);
        Baseline baseline = (Baseline) writer.records.get(0);
        assertEquals(2, baseline.absoluteFrame());
        assertNull(baseline.state());
        assertNull(baseline.roleOwners());
        assertNull(baseline.frontier().activeStack());
        assertNull(baseline.frontier().rawChipEvents());

        List<Frame> frames = writer.records.stream().filter(Frame.class::isInstance)
                .map(Frame.class::cast).toList();
        assertEquals(java.util.Arrays.asList("first", null, "last"),
                frames.stream().map(Frame::segment).toList());
        assertEquals(List.of(new YmWrite(0, 1, 0x22, 0x33)), frames.get(0).chipEvents());
        assertEquals(List.of(), frames.get(1).chipEvents(),
                "observed empty is distinct from an unobserved null layer");
        assertEquals(List.of(new PsgWrite(1, 0x90)), frames.get(2).chipEvents());
        for (Frame frame : frames) {
            assertNull(frame.lag());
            assertNull(frame.requests());
            assertNull(frame.decisions());
            assertNull(frame.services());
            assertNull(frame.postRowState());
            assertNull(frame.nativeDiagnostics());
        }
        String emptyChipRow = CompleteRunAudioJson.writeRecord(frames.get(1));
        assertTrue(emptyChipRow.contains("\"chipEvents\":[]"));
        assertTrue(emptyChipRow.contains("\"services\":null"));
        assertTrue(emptyChipRow.contains("\"postRowState\":null"));
        CutoffFrontier cutoff = (CutoffFrontier) writer.records.getLast();
        assertNull(cutoff.activeStack());
        assertNull(cutoff.rawChipEvents());
        assertNull(cutoff.terminalState());
    }

    @Test
    void rejectsObserverOrdinalGapsBeforeWritingTheAffectedRow() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        CompleteRunAudioCaptureReducer reducer = new CompleteRunAudioCaptureReducer(
                fixture(), writer, projection());
        feed(reducer, 0, List.of());
        feed(reducer, 1, List.of());
        reducer.beforeFrame(boundary(2));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> reducer.acceptFrame(observation(2,
                        List.of(new PsgWriteObserved(1, 0x90)))));

        assertTrue(failure.getMessage().contains("observation ordinal"));
        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    @Test
    void terminalMustEqualTheLastPresentedRowBoundary() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        CompleteRunAudioCaptureReducer reducer = new CompleteRunAudioCaptureReducer(
                fixture(), writer, projection());
        for (int row = 0; row < 5; row++) feed(reducer, row, List.of());

        assertThrows(IllegalArgumentException.class, () -> reducer.finish(snapshot(999)));
        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    @Test
    void cleanupFailuresAreSuppressedOnTheProjectionFailure() throws Exception {
        RecordingWriter writer = new RecordingWriter();
        writer.abortFailure = new IOException("writer cleanup");
        CompleteRunAudioFrameProjection projection = new CompleteRunAudioFrameProjection() {
            @Override public Frame retainedFrame(PreRowBoundary before,
                    RowObservation after, String segment, long firstChipOrdinal) {
                throw new IllegalStateException("projection failed");
            }
            @Override public void abort() { throw new IllegalArgumentException("projection cleanup"); }
        };
        CompleteRunAudioCaptureReducer reducer = new CompleteRunAudioCaptureReducer(
                fixture(), writer, projection);
        feed(reducer, 0, List.of());
        feed(reducer, 1, List.of());
        reducer.beforeFrame(boundary(2));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> reducer.acceptFrame(observation(2, List.of())));
        assertEquals(List.of("writer cleanup", "projection cleanup"),
                java.util.Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
    }

    private static void feed(CompleteRunAudioCaptureReducer reducer, int row,
            List<CompleteRunAudioObserverLease.Observation> events) throws Exception {
        feed(reducer, row, 0, events);
    }

    private static void feed(CompleteRunAudioCaptureReducer reducer, int row,
            long firstOrdinal, List<CompleteRunAudioObserverLease.Observation> events)
            throws Exception {
        reducer.beforeFrame(boundary(row, firstOrdinal));
        reducer.acceptFrame(observation(row, events));
    }

    private static PreRowBoundary boundary(int row) {
        return boundary(row, 0);
    }

    private static PreRowBoundary boundary(int row, long firstOrdinal) {
        return new PreRowBoundary(row, firstOrdinal, snapshot(row * 10L), List.of());
    }

    private static RowObservation observation(int row,
            List<CompleteRunAudioObserverLease.Observation> events) {
        return new RowObservation(row, snapshot(row * 10L + 1), events);
    }

    private static AudioLogicalSnapshot snapshot(long marker) {
        return new AudioLogicalSnapshot(true, marker, 0, 0,
                AudioPresentationSnapshot.empty(), Set.of(), Set.of());
    }

    private static CompleteRunFixture fixture() {
        return new CompleteRunFixture("0".repeat(40), "1".repeat(8), "2".repeat(64), 5,
                "3".repeat(64), List.of(new ManifestSegment("first", 2, 3),
                        new ManifestSegment("last", 4, 5)), 2, 5);
    }

    private static CompleteRunAudioFrameChipProjection projection() {
        return new CompleteRunAudioFrameChipProjection(
                CompleteRunAudioObservationInventories.frameChipsOnly(
                        "test limitation"));
    }

    private static final class RecordingWriter implements CompleteRunAudioCaptureStore.Writer {
        final List<Record> records = new ArrayList<>();
        final List<String> operations = new ArrayList<>();
        boolean finished;
        boolean aborted;
        IOException abortFailure;

        @Override public void append(Record record) {
            records.add(record);
            if (record instanceof Baseline) operations.add("baseline");
            else if (record instanceof Frame frame) operations.add("frame:" + frame.absoluteFrame());
            else if (record instanceof CutoffFrontier) operations.add("cutoff");
        }
        @Override public void finish(NativeCapabilitySummary capability) {
            finished = true;
            operations.add("finish");
        }
        @Override public void abort() throws IOException {
            aborted = true;
            if (abortFailure != null) throw abortFailure;
        }
        @Override public void close() { operations.add("close"); }
    }
}
