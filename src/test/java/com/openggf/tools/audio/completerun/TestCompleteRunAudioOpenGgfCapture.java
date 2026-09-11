package com.openggf.tools.audio.completerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManifestSegment;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.NativeCapabilitySummary;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.Record;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Modifier;

class TestCompleteRunAudioOpenGgfCapture {
    @Test
    void rawCaptureAdapterIsNotAPublicProducerAuthority() {
        for (Class<?> internal : List.of(CompleteRunAudioOpenGgfCapture.class,
                CompleteRunAudioCaptureReducer.class,
                CompleteRunAudioFrameProjection.class,
                CompleteRunAudioFrameChipProjection.class)) {
            assertFalse(Modifier.isPublic(internal.getModifiers()), internal.getName());
        }
    }

    @Test
    void authenticatedRowsFinalizeOnlyAfterTheExactTerminalRow() throws Exception {
        RecordingWriter writer = new RecordingWriter();

        CompleteRunAudioOpenGgfCapture.reduce(fixture(), writer,
                projection(), consumer -> {
                    for (int row = 0; row < 3; row++) consumer.accept(result(row));
                });

        assertTrue(writer.finished);
        assertTrue(writer.closed);
        assertFalse(writer.aborted);
        assertEquals(4, writer.records.size(),
                "baseline, two retained frames, and cutoff precede store terminal");
    }

    @Test
    void rowSourceFailureAbortsThePrivateWriterWithoutFinalization() {
        RecordingWriter writer = new RecordingWriter();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompleteRunAudioOpenGgfCapture.reduce(fixture(), writer,
                        projection(), consumer -> {
                            consumer.accept(result(0));
                            throw new IllegalStateException("runner failed");
                        }));

        assertEquals("runner failed", failure.getMessage());
        assertTrue(writer.aborted);
        assertFalse(writer.finished);
        assertFalse(writer.closed);
    }

    @Test
    void movieCardinalityMismatchAbortsWriterBeforeRunnerStartup() {
        RecordingWriter writer = new RecordingWriter();
        Bk2Movie wrongMovie = new Bk2Movie(Path.of("wrong.bk2"), "LogKey:|P1 A|",
                Map.of(), List.of(new Bk2FrameInput(0, 0, 0, false, "|..|")), 0);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioOpenGgfCapture.run(null, wrongMovie, fixture(),
                        writer, projection()));

        assertTrue(failure.getMessage().contains("BK2 rows"));
        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    @Test
    void nullProjectionAbortsWriterBeforeReducerConstruction() {
        RecordingWriter writer = new RecordingWriter();

        assertThrows(NullPointerException.class,
                () -> CompleteRunAudioOpenGgfCapture.reduce(
                        fixture(), writer, null, consumer -> { }));

        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    @Test
    void nullRowSourceAbortsWriterBeforeReducerConstruction() {
        RecordingWriter writer = new RecordingWriter();

        assertThrows(NullPointerException.class,
                () -> CompleteRunAudioOpenGgfCapture.reduce(
                        fixture(), writer, projection(), null));

        assertTrue(writer.aborted);
        assertFalse(writer.finished);
    }

    private static ProductionBk2AudioRunner.RowResult result(int row) {
        AudioLogicalSnapshot before = snapshot(row * 10L);
        AudioLogicalSnapshot after = snapshot(row * 10L + 1);
        return new ProductionBk2AudioRunner.RowResult(row,
                new PreRowBoundary(row, 0, before, List.of()),
                new RowObservation(row, after, List.of()));
    }

    private static AudioLogicalSnapshot snapshot(long marker) {
        return new AudioLogicalSnapshot(true, marker, 0, 0,
                AudioPresentationSnapshot.empty(), Set.of(), Set.of());
    }

    private static CompleteRunFixture fixture() {
        return new CompleteRunFixture("0".repeat(40), "1".repeat(8), "2".repeat(64), 3,
                "3".repeat(64), List.of(new ManifestSegment("run", 1, 3)), 1, 3);
    }

    private static CompleteRunAudioFrameChipProjection projection() {
        return new CompleteRunAudioFrameChipProjection(
                CompleteRunAudioObservationInventories.frameChipsOnly(
                        "test limitation"));
    }

    private static final class RecordingWriter implements CompleteRunAudioCaptureStore.Writer {
        final List<Record> records = new ArrayList<>();
        boolean finished;
        boolean aborted;
        boolean closed;
        @Override public void append(Record record) { records.add(record); }
        @Override public void finish(NativeCapabilitySummary capability) { finished = true; }
        @Override public void abort() { aborted = true; }
        @Override public void close() { closed = true; }
    }
}
