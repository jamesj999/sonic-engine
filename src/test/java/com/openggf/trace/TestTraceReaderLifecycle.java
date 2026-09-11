package com.openggf.trace;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceReaderLifecycle {
    private static final int CYCLES = 100;

    @Test
    void balancesEveryReaderFamilyForOneHundredDeterministicCycles(
            @TempDir Path root) throws Exception {
        Path ordinaryRun = TraceV5RunFixture.writeS3kBonusRun(
                root.resolve("ordinary/runs"));
        Path plainOrdinary = ordinaryRun.resolve("seg00_aiz");
        Path gzipOrdinary = ordinaryRun.resolve("seg01_gumball");
        gzip(gzipOrdinary.resolve("physics.csv"));
        gzip(gzipOrdinary.resolve("aux_state.jsonl"));
        Files.delete(gzipOrdinary.resolve("physics.csv"));
        Files.delete(gzipOrdinary.resolve("aux_state.jsonl"));

        Path s1 = writeS1SpecialStage(root.resolve("s1"));
        Path s2Run = TraceV5RunFixture.writeS2SpecialStageRun(
                root.resolve("s2/runs"));
        Path s2 = s2Run.resolve("ss");
        Path s3k = writeS3kSpecialStage(root.resolve("s3k"));

        List<ReaderEvent> events = new ArrayList<>();
        AutoCloseable restore = TraceFiles.observeReadersForTest(
                (event, path) -> events.add(new ReaderEvent(event, path)));
        try {
            for (int cycle = 0; cycle < CYCLES; cycle++) {
                TraceData.load(plainOrdinary);
                TraceData.load(gzipOrdinary);
                Sonic1SpecialStageTraceData.load(s1);
                SpecialStageTraceData.load(s2);
                S3kSpecialStageTraceData.load(s3k);
            }
        } finally {
            restore.close();
        }

        assertBalanced(events);
        assertPathBalanced(events, plainOrdinary.resolve("physics.csv"), CYCLES);
        assertPathBalanced(events, plainOrdinary.resolve("aux_state.jsonl"), CYCLES);
        assertPathBalanced(events, gzipOrdinary.resolve("physics.csv.gz"), CYCLES);
        assertPathBalanced(events, gzipOrdinary.resolve("aux_state.jsonl.gz"), CYCLES);
        // S1 carries no dynamic-art state and parses physics once. The S2 run
        // fixture does carry it, so its conditional stored-domain validation
        // adds a second balanced reader lifetime.
        assertPathBalanced(events, s1.resolve("physics.csv"), CYCLES);
        assertPathBalanced(events, s2.resolve("physics.csv"), 2 * CYCLES);
        assertPathBalanced(events, s2.resolve("aux_state.jsonl"), CYCLES);
        assertPathBalanced(events, s3k.resolve("physics.csv"), CYCLES);
        assertEquals(9L * CYCLES, events.stream()
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.OPENED)
                .count(), "every parser family must execute in every cycle");
    }

    @Test
    void balancesSuccessfulPlainAndGzipReaders(@TempDir Path root) throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS3kBonusRun(root.resolve("runs"));
        Path plainSegment = runDirectory.resolve("seg00_aiz");
        Path gzipSegment = runDirectory.resolve("seg01_gumball");
        gzip(gzipSegment.resolve("physics.csv"));
        gzip(gzipSegment.resolve("aux_state.jsonl"));
        Files.delete(gzipSegment.resolve("physics.csv"));
        Files.delete(gzipSegment.resolve("aux_state.jsonl"));
        Path plainPhysics = plainSegment.resolve("physics.csv");
        Path plainAux = plainSegment.resolve("aux_state.jsonl");
        Path gzipPhysics = gzipSegment.resolve("physics.csv.gz");
        Path gzipAux = gzipSegment.resolve("aux_state.jsonl.gz");
        List<ReaderEvent> events = new ArrayList<>();
        AutoCloseable restore = TraceFiles.observeReadersForTest(
                (event, path) -> events.add(new ReaderEvent(event, path)));

        try {
            TraceData.load(plainSegment);
            TraceData.load(gzipSegment);
        } finally {
            restore.close();
        }

        assertBalanced(events);
        assertOpened(events, plainPhysics, plainAux, gzipPhysics, gzipAux);
    }

    @Test
    void balancesReadersWhenSpecialCompositeParserFails(@TempDir Path root)
            throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("runs"));
        TraceRunManifest run = TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json"));
        TraceRunSegmentDescriptor descriptor = TraceRunReplayWalker
                .planDescriptors(run, runDirectory).get(1);
        Files.writeString(runDirectory.resolve("ss/aux_state.jsonl"), "{not-json}\n");
        List<ReaderEvent> events = new ArrayList<>();
        AutoCloseable restore = TraceFiles.observeReadersForTest(
                (event, path) -> events.add(new ReaderEvent(event, path)));

        try {
            assertThrows(IOException.class,
                    () -> TraceRunReplayWalker.openActiveSegment(descriptor, 1));
        } finally {
            restore.close();
        }

        assertBalanced(events);
    }

    @Test
    void openedObserverFailureClosesDelegateAndKeepsCloseFailureSuppressed()
            throws Exception {
        Path path = Path.of("opened-observer.csv");
        IOException closeFailure = new IOException("delegate close failed");
        CloseTrackingReader delegate = new CloseTrackingReader(closeFailure);
        IllegalStateException openedFailure =
                new IllegalStateException("opened observer failed");
        AutoCloseable restore = TraceFiles.observeReadersForTest((event, ignored) -> {
            if (event == TraceFiles.ReaderLifecycleEvent.OPENED) {
                throw openedFailure;
            }
        });

        IllegalStateException thrown;
        try {
            thrown = assertThrows(IllegalStateException.class,
                    () -> TraceFiles.observeReaderForTest(delegate, path));
        } finally {
            restore.close();
        }

        assertSame(openedFailure, thrown);
        assertEquals(1, delegate.closeAttempts);
        assertEquals(List.of(closeFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void closedObserverFailureIsSuppressedBehindDelegateIOException()
            throws Exception {
        Path path = Path.of("closed-observer-after-io.csv");
        IOException closeFailure = new IOException("delegate close failed");
        CloseTrackingReader delegate = new CloseTrackingReader(closeFailure);
        IllegalStateException observerFailure =
                new IllegalStateException("closed observer failed");
        AutoCloseable restore = TraceFiles.observeReadersForTest((event, ignored) -> {
            if (event == TraceFiles.ReaderLifecycleEvent.CLOSED) {
                throw observerFailure;
            }
        });

        IOException thrown;
        try {
            BufferedReader observed = TraceFiles.observeReaderForTest(delegate, path);
            thrown = assertThrows(IOException.class, observed::close);
        } finally {
            restore.close();
        }

        assertSame(closeFailure, thrown);
        assertEquals(1, delegate.closeAttempts);
        assertEquals(List.of(observerFailure), List.of(thrown.getSuppressed()));
    }

    @Test
    void closedObserverFailureIsPrimaryWhenDelegateCloseSucceeds()
            throws Exception {
        Path path = Path.of("closed-observer-after-success.csv");
        CloseTrackingReader delegate = new CloseTrackingReader(null);
        IllegalStateException observerFailure =
                new IllegalStateException("closed observer failed");
        AutoCloseable restore = TraceFiles.observeReadersForTest((event, ignored) -> {
            if (event == TraceFiles.ReaderLifecycleEvent.CLOSED) {
                throw observerFailure;
            }
        });

        IllegalStateException thrown;
        try {
            BufferedReader observed = TraceFiles.observeReaderForTest(delegate, path);
            thrown = assertThrows(IllegalStateException.class, observed::close);
        } finally {
            restore.close();
        }

        assertSame(observerFailure, thrown);
        assertEquals(1, delegate.closeAttempts);
        assertEquals(0, thrown.getSuppressed().length);
    }

    private static void gzip(Path source) throws IOException {
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(
                source.resolveSibling(source.getFileName() + ".gz")))) {
            Files.copy(source, output);
        }
    }

    /**
     * Catches removing TraceFiles' observation wrapper entirely: balance alone
     * would incorrectly accept an empty event stream.
     */
    private static void assertBalanced(List<ReaderEvent> events) {
        long opened = events.stream()
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.OPENED)
                .count();
        long closed = events.stream()
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.CLOSED)
                .count();
        assertTrue(opened > 0,
                "reader observation must report at least one successful open: " + events);
        assertEquals(opened, closed, events::toString);
    }

    private static void assertOpened(List<ReaderEvent> events, Path... expectedPaths) {
        List<Path> openedPaths = events.stream()
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.OPENED)
                .map(ReaderEvent::path)
                .toList();
        for (Path expectedPath : expectedPaths) {
            assertTrue(openedPaths.contains(expectedPath),
                    () -> "missing observed open for " + expectedPath
                            + ": " + openedPaths);
        }
    }

    private static void assertPathBalanced(
            List<ReaderEvent> events, Path path, int expectedCycles) {
        long opened = events.stream().filter(event -> event.path().equals(path))
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.OPENED)
                .count();
        long closed = events.stream().filter(event -> event.path().equals(path))
                .filter(event -> event.event() == TraceFiles.ReaderLifecycleEvent.CLOSED)
                .count();
        assertEquals(expectedCycles, opened,
                () -> "deterministic open count for " + path);
        assertEquals(expectedCycles, closed,
                () -> "deterministic close count for " + path);
    }

    private static Path writeS1SpecialStage(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("metadata.json"), """
                {"game":"s1","trace_profile":"s1_special_stage",
                 "trace_schema":5,"act":1,"bk2_frame_offset":0,
                 "trace_frame_count":2,"start_x":"0000","start_y":"0000"}
                """);
        String header = "frame,input,lag,x_pos,y_pos,vel_x,vel_y,inertia,"
                + "status,ss_angle,ss_rotate,bg_anim,rings,emeralds";
        String state = ",208,0,fffe8000,00478000,fe00,0123,0456,03,"
                + "4000,ff80,6,17,1";
        Files.writeString(directory.resolve("physics.csv"),
                header + "\n0" + state + "\n1" + state + "\n");
        return directory;
    }

    private static Path writeS3kSpecialStage(Path directory) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("metadata.json"), """
                {"game":"s3k","trace_profile":"s3k_special_stage",
                 "trace_schema":5,"act":1,"bk2_frame_offset":0,
                 "trace_frame_count":2,"special_stage_index":0,
                 "start_x":"0000","start_y":"0000"}
                """);
        String header = "frame,input,input_p2,lag,anim_frame,x_pos,y_pos,angle,"
                + "velocity,turning,jumping,fade_timer,spheres_left,ring_count,"
                + "rings_left,rate,rate_timer,clear_timer,clear_routine,started";
        String state = ",8,0,0,2a1b,3c4d,5e6f,70,81,92,a3,b4,c5d6,e7f8,"
                + "1a2b,3c4d,5e6f,7089,9a,1";
        Files.writeString(directory.resolve("physics.csv"),
                header + "\n0" + state + "\n1" + state + "\n");
        return directory;
    }

    private record ReaderEvent(TraceFiles.ReaderLifecycleEvent event, Path path) {
    }

    private static final class CloseTrackingReader extends BufferedReader {
        private final IOException closeFailure;
        private int closeAttempts;

        private CloseTrackingReader(IOException closeFailure) {
            super(new StringReader(""));
            this.closeFailure = closeFailure;
        }

        @Override
        public void close() throws IOException {
            closeAttempts++;
            if (closeFailure != null) {
                throw closeFailure;
            }
            super.close();
        }
    }
}
