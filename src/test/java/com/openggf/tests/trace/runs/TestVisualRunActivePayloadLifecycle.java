package com.openggf.tests.trace.runs;

import com.openggf.GameLoop;
import com.openggf.TraceSessionLauncher;
import com.openggf.game.GameMode;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestVisualRunActivePayloadLifecycle {

    @AfterEach
    void tearDown() {
        VisualRunReplayHarness.tearDown();
    }

    @Test
    void descriptorFrameViewAnswersSegmentLagGapAndTailWithoutOpeningPayload(
            @TempDir Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root.resolve("s3k"));
        List<TraceRunSegmentDescriptor> planned = descriptors(runDir);
        BitSet lagged = planned.getFirst().laggedRows();
        lagged.set(1);
        TraceRunSegmentDescriptor first = copyWithLag(planned.getFirst(), lagged);
        List<TraceRunSegmentDescriptor> descriptors = List.of(
                first, planned.get(1), planned.get(2));
        GameLoop loop = mock(GameLoop.class);
        when(loop.getCurrentGameMode()).thenReturn(GameMode.LEVEL);

        VisualRunReplayHarness.FrameView firstRow =
                VisualRunReplayHarness.frameView(500, 3, loop, descriptors, true);
        VisualRunReplayHarness.FrameView last =
                VisualRunReplayHarness.frameView(501, 4, loop, descriptors, true);
        VisualRunReplayHarness.FrameView gap =
                VisualRunReplayHarness.frameView(700, 5, loop, descriptors, true);
        VisualRunReplayHarness.FrameView handoff =
                VisualRunReplayHarness.frameView(1750, 6, loop, descriptors, true);
        VisualRunReplayHarness.FrameView adopted =
                VisualRunReplayHarness.frameView(1900, 7, loop, descriptors, true);
        VisualRunReplayHarness.FrameView tail =
                VisualRunReplayHarness.frameView(2999, 8, loop, descriptors, true);

        assertEquals(0, firstRow.segmentIndex());
        assertEquals(0, firstRow.segmentRow());
        assertFalse(firstRow.lag());
        assertEquals(0, last.segmentIndex());
        assertEquals(1, last.segmentRow());
        assertTrue(last.lag());
        assertNull(gap.segment());
        assertNull(handoff.segment());
        assertEquals(1, adopted.segmentIndex());
        assertEquals(0, adopted.segmentRow());
        assertFalse(gap.lag());
        assertNull(tail.segment());
    }

    @Test
    void visualAndCompleteAudioCloseLocallyOnPreTransferConstructorFailure(
            @TempDir Path root) throws Exception {
        Path visual = preparedRun(root.resolve("visual"));
        AtomicReference<ActiveSegmentPayload> visualPayload =
                new AtomicReference<>();
        Exception visualFailure = assertThrows(Exception.class,
                () -> VisualRunReplayHarness.replay(
                        visual, new VisualRunReplayHarness.Stop(1, -1),
                        VisualRunReplayHarness.FrameObserver.NONE, false,
                        failingConstructor(visualPayload), minimalBootstrap(false)));
        assertEquals("injected constructor failure", visualFailure.getMessage());
        assertTrue(visualPayload.get().isClosed(), "visual");
        VisualRunReplayHarness.tearDown();

        Path completeAudio = preparedRun(root.resolve("complete-audio"));
        AtomicReference<ActiveSegmentPayload> audioPayload =
                new AtomicReference<>();
        Exception audioFailure = assertThrows(Exception.class,
                () -> VisualRunReplayHarness.replayCompleteAudio(
                        completeAudio,
                        new VisualRunReplayHarness.CompleteAudioStop(500, 3000, 0),
                        VisualRunReplayHarness.FrameObserver.NONE,
                        failingConstructor(audioPayload), minimalBootstrap(false)));
        assertEquals("injected constructor failure", audioFailure.getMessage());
        assertTrue(audioPayload.get().isClosed(), "complete-audio");
    }

    @Test
    void visualAndCompleteAudioCloseThroughSessionAfterTransferFailure(
            @TempDir Path root) throws Exception {
        Path visual = preparedRun(root.resolve("visual"));
        AtomicReference<ActiveSegmentPayload> visualPayload =
                new AtomicReference<>();
        AtomicReference<TraceSessionLauncher> visualSession =
                new AtomicReference<>();
        AssertionError visualFailure = assertThrows(AssertionError.class,
                () -> VisualRunReplayHarness.replay(
                        visual, new VisualRunReplayHarness.Stop(1, -1),
                        VisualRunReplayHarness.FrameObserver.NONE, false,
                        recordingConstructor(visualPayload, visualSession),
                        minimalBootstrap(true)));
        assertEquals("injected post-transfer bootstrap failure",
                visualFailure.getMessage());
        assertTrue(visualPayload.get().isClosed(), "visual");
        assertNull(field(visualSession.get(), "activeRunPayload"), "visual");
        VisualRunReplayHarness.tearDown();

        Path completeAudio = preparedRun(root.resolve("complete-audio"));
        AtomicReference<ActiveSegmentPayload> audioPayload =
                new AtomicReference<>();
        AtomicReference<TraceSessionLauncher> audioSession =
                new AtomicReference<>();
        AssertionError audioFailure = assertThrows(AssertionError.class,
                () -> VisualRunReplayHarness.replayCompleteAudio(
                        completeAudio,
                        new VisualRunReplayHarness.CompleteAudioStop(500, 3000, 0),
                        VisualRunReplayHarness.FrameObserver.NONE,
                        recordingConstructor(audioPayload, audioSession),
                        minimalBootstrap(true)));
        assertEquals("injected post-transfer bootstrap failure",
                audioFailure.getMessage());
        assertTrue(audioPayload.get().isClosed(), "complete-audio");
        assertNull(field(audioSession.get(), "activeRunPayload"),
                "complete-audio");
    }

    @Test
    void visualAndCompleteAudioSuppressCloserFailureAfterRealCloseAndReleaseAliases(
            @TempDir Path root) throws Exception {
        CloseSuppressionReachability visual = closeSuppression(
                preparedRun(root.resolve("visual")), false);
        assertSame(visual.primary(), visual.thrown(), "visual primary");
        assertSuppressedExactly(visual.thrown(), visual.cleanupFailure(), "visual");
        awaitCollected(visual.payload(), "visual payload");
        awaitCollected(visual.trace(), "visual trace");

        CloseSuppressionReachability audio = closeSuppression(
                preparedRun(root.resolve("complete-audio")), true);
        assertSame(audio.primary(), audio.thrown(), "complete-audio primary");
        assertSuppressedExactly(audio.thrown(), audio.cleanupFailure(),
                "complete-audio");
        awaitCollected(audio.payload(), "complete-audio payload");
        awaitCollected(audio.trace(), "complete-audio trace");
    }

    private static CloseSuppressionReachability closeSuppression(
            Path runDir, boolean completeAudio) throws Exception {
        AtomicReference<ActiveSegmentPayload> payload = new AtomicReference<>();
        AtomicReference<TraceSessionLauncher> session = new AtomicReference<>();
        AtomicReference<WeakReference<com.openggf.trace.TraceData>> trace =
                new AtomicReference<>();
        AssertionError primary = new AssertionError("injected replay failure");
        IllegalStateException cleanup = new IllegalStateException(
                "injected visual payload cleanup failure");
        VisualRunReplayHarness.VisualPayloadCloser closer = active -> {
            active.close();
            throw cleanup;
        };
        Throwable thrown = assertThrows(Throwable.class, () -> {
            if (completeAudio) {
                VisualRunReplayHarness.replayCompleteAudio(
                        runDir,
                        new VisualRunReplayHarness.CompleteAudioStop(500, 3000, 0),
                        VisualRunReplayHarness.FrameObserver.NONE,
                        recordingConstructor(payload, session, trace),
                        failingAfterTransfer(primary), closer);
            } else {
                VisualRunReplayHarness.replay(
                        runDir, new VisualRunReplayHarness.Stop(1, -1),
                        VisualRunReplayHarness.FrameObserver.NONE, false,
                        recordingConstructor(payload, session, trace),
                        failingAfterTransfer(primary), closer);
            }
        });
        ActiveSegmentPayload active = payload.getAndSet(null);
        WeakReference<ActiveSegmentPayload> payloadReference =
                new WeakReference<>(active);
        assertTrue(active.isClosed(), "real closer delegates before failure");
        assertNull(field(session.get(), "activeRunPayload"),
                "session released active payload alias");
        session.set(null);
        active = null;
        return new CloseSuppressionReachability(
                primary, cleanup, thrown, payloadReference, trace.get());
    }

    @Test
    void closerFailureIsThrownAloneWhenReplayCompletedWithoutFailure(
            @TempDir Path root) throws Exception {
        Path runDir = preparedRun(root.resolve("normal-completion"));
        AtomicReference<ActiveSegmentPayload> payload = new AtomicReference<>();
        AtomicReference<TraceSessionLauncher> session = new AtomicReference<>();
        IllegalStateException cleanup = new IllegalStateException(
                "injected lone visual payload cleanup failure");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> VisualRunReplayHarness.replay(
                        runDir, new VisualRunReplayHarness.Stop(0, -1),
                        VisualRunReplayHarness.FrameObserver.NONE, false,
                        recordingConstructor(payload, session), minimalBootstrap(false), active -> {
                            active.close();
                            throw cleanup;
                        }));

        assertSame(cleanup, thrown);
        assertEquals(0, thrown.getSuppressed().length);
        assertTrue(payload.get().isClosed());
    }

    @Test
    void repeatedHarnessTearDownRemainsIdempotentAfterVisualFailure(
            @TempDir Path root) throws Exception {
        Path runDir = preparedRun(root.resolve("repeated-teardown"));
        AtomicReference<ActiveSegmentPayload> payload = new AtomicReference<>();

        assertThrows(Exception.class, () -> VisualRunReplayHarness.replay(
                runDir, new VisualRunReplayHarness.Stop(1, -1),
                VisualRunReplayHarness.FrameObserver.NONE, false,
                failingConstructor(payload), minimalBootstrap(false)));

        assertTrue(payload.get().isClosed());
        assertDoesNotThrow(VisualRunReplayHarness::tearDown);
        assertDoesNotThrow(VisualRunReplayHarness::tearDown);
    }

    private static Path preparedRun(Path root) throws Exception {
        Path runDir = TraceV5RunFixture.writeS3kBonusRun(root);
        TraceV5RunFixture.writeMovie(runDir.resolve("synthetic.bk2"));
        return runDir;
    }

    private static VisualRunReplayHarness.RunSessionConstructor
            failingConstructor(
                    AtomicReference<ActiveSegmentPayload> payload) {
        return (entry, movie, descriptors, activePayload) -> {
            payload.set(activePayload);
            throw new Exception("injected constructor failure");
        };
    }

    private static VisualRunReplayHarness.RunSessionConstructor
            recordingConstructor(
                    AtomicReference<ActiveSegmentPayload> payload,
                    AtomicReference<TraceSessionLauncher> session) {
        return (entry, movie, descriptors, activePayload) -> {
            payload.set(activePayload);
            TraceSessionLauncher created = VisualRunReplayHarness.newRunSession(
                    entry, movie, descriptors, activePayload);
            session.set(created);
            return created;
        };
    }

    private static VisualRunReplayHarness.RunSessionConstructor
            recordingConstructor(
                    AtomicReference<ActiveSegmentPayload> payload,
                    AtomicReference<TraceSessionLauncher> session,
                    AtomicReference<WeakReference<com.openggf.trace.TraceData>>
                            trace) {
        return (entry, movie, descriptors, activePayload) -> {
            payload.set(activePayload);
            trace.set(new WeakReference<>(activePayload.trace()));
            TraceSessionLauncher created = VisualRunReplayHarness.newRunSession(
                    entry, movie, descriptors, activePayload);
            session.set(created);
            return created;
        };
    }

    private static VisualRunReplayHarness.RunBootstrap minimalBootstrap(
            boolean failAfterTransfer) {
        return minimalBootstrap(failAfterTransfer, new AssertionError(
                "injected post-transfer bootstrap failure"));
    }

    private static VisualRunReplayHarness.RunBootstrap failingAfterTransfer(
            AssertionError primary) {
        return minimalBootstrap(true, primary);
    }

    private static VisualRunReplayHarness.RunBootstrap minimalBootstrap(
            boolean failAfterTransfer, AssertionError failure) {
        return new VisualRunReplayHarness.RunBootstrap() {
            @Override
            public GameLoop beforeTransfer(
                    com.openggf.trace.catalog.TraceEntry entry,
                    com.openggf.trace.TraceData firstTrace,
                    VisualRunReplayHarness.FrameObserver observer) {
                return mock(GameLoop.class);
            }

            @Override
            public void afterTransfer(
                    TraceSessionLauncher session, GameLoop loop) {
                if (failAfterTransfer) {
                    throw failure;
                }
            }
        };
    }

    private static void awaitCollected(WeakReference<?> reference, String label)
            throws InterruptedException {
        for (int attempt = 0; attempt < 80 && reference.get() != null; attempt++) {
            System.gc();
            System.runFinalization();
            byte[] pressure = new byte[64 * 1024];
            pressure[0] = 1;
            Thread.sleep(10);
        }
        assertNull(reference.get(), label + " remained strongly reachable");
    }

    private static void assertSuppressedExactly(
            Throwable primary, Throwable cleanup, String label) {
        assertEquals(1, primary.getSuppressed().length, label + " cleanup count");
        assertSame(cleanup, primary.getSuppressed()[0], label + " cleanup");
    }

    private record CloseSuppressionReachability(
            AssertionError primary,
            IllegalStateException cleanupFailure,
            Throwable thrown,
            WeakReference<ActiveSegmentPayload> payload,
            WeakReference<com.openggf.trace.TraceData> trace) { }

    private static List<TraceRunSegmentDescriptor> descriptors(Path runDir)
            throws Exception {
        return TraceRunReplayWalker.planDescriptors(TraceRunManifest.load(
                runDir.resolve("run_manifest.json")), runDir);
    }

    private static TraceRunSegmentDescriptor copyWithLag(
            TraceRunSegmentDescriptor descriptor, BitSet lagged) {
        return new TraceRunSegmentDescriptor(
                descriptor.segment(), descriptor.segmentDirectory(),
                descriptor.metadata(), descriptor.rowCount(),
                descriptor.openingFrame(), descriptor.rawFrames(), lagged,
                descriptor.hardwareTimingSchedule(),
                descriptor.terminalDynamicArtLedger(), descriptor.entryBoundary(),
                descriptor.exitBoundary(), descriptor.levelLoopRowCount(),
                descriptor.executionPolicy());
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

}
