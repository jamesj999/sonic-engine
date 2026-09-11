package com.openggf.tests.trace.runs;

import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.level.objects.ObjectManager;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHeadlessRunActivePayloadLifecycle {

    @Test
    void ordinarySpecialBridgeReturnOwnOnePayloadAndKeepRowParity(
            @TempDir Path root) throws Exception {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(root, true);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);

        ActiveSegmentPayload ordinary = harness.open(descriptors.get(0), 0);
        LiveTraceComparator ordinaryComparator = new LiveTraceComparator(
                ordinary.trace(), ToleranceConfig.DEFAULT, 0, () -> null);
        harness.attach(probe(), ordinaryComparator, ordinary.trace(), 0,
                () -> { });
        harness.finishBoundary(() -> { });
        assertTrue(ordinary.isClosed());
        assertFalse(harness.hasActivePayload(), "ordinary -> special gap");

        ActiveSegmentPayload special = harness.open(descriptors.get(1), 1);
        TraceRunSpecialStageRows rows = special.specialStageRows();
        SpecialStageRunObjectsPassBinder binder =
                rows.newRunObjectsPassBinder().orElseThrow();
        assertEquals(1, binder.passesForObservation(1).size(),
                "S2 pass pacing must remain bound to the active special payload");
        harness.finishBoundary(() -> { });
        assertTrue(special.isClosed());
        assertFalse(harness.hasActivePayload(), "special -> bridge gap");

        ActiveSegmentPayload bridge = harness.open(descriptors.get(2), 2);
        assertEquals(
                TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE,
                bridge.descriptor().executionPolicy());
        TraceStructuralRowComparator structural =
                new TraceStructuralRowComparator(
                        bridge.trace(), ToleranceConfig.DEFAULT, 0);
        harness.installStructuralAlias(structural);
        harness.finishBoundary(() -> { });
        assertTrue(bridge.isClosed());
        assertFalse(harness.hasActivePayload(), "bridge -> return gap");

        ActiveSegmentPayload returned = harness.open(descriptors.get(3), 3);
        assertEquals(descriptors.get(3).openingFrame(),
                returned.trace().getFrame(0),
                "the descriptor supplies the boundary opening frame while the "
                        + "active destination supplies the adopted comparison row");
        LiveTraceComparator returnedComparator = new LiveTraceComparator(
                returned.trace(), ToleranceConfig.DEFAULT, 1, () -> null);
        harness.attach(probe(), returnedComparator, returned.trace(), 3,
                () -> { });
        int errorsBeforeAdoption = returnedComparator.errorCount();
        returnedComparator.compareAdoptedOpeningRow(
                0, new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of(99L)));
        assertTrue(returnedComparator.errorCount() > errorsBeforeAdoption,
                "the active return comparator must execute the adopted row");
        harness.finishBoundary(() -> { });

        assertEquals(List.of("open 0", "close 0", "open 1", "close 1",
                "open 2", "close 2", "open 3", "close 3"),
                factory.transcript);
        assertEquals(1, factory.maximumActive);
        assertEquals(0, factory.activeCount());
    }

    @Test
    void failingComparisonClearsAllPayloadAliasesBeforePropagating(
            @TempDir Path root) throws Exception {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(root, true);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);
        ActiveSegmentPayload active = harness.open(descriptors.get(1), 1);
        Reachability reachability = harness.installEveryAlias(
                active.trace(), active.specialStageRows());
        assertReferentsReachable(reachability);
        AssertionError primary = new AssertionError("injected comparison failure");

        Throwable thrown = assertThrows(Throwable.class,
                () -> harness.finishBoundary(() -> {
                    TraceRunSpecialStageRowDriver driver =
                            reachability.driver().get();
                    driver.admitCurrentRow(
                            DynamicArtDiagnosticsSnapshot.unpublished(0, 0));
                    driver.publishAdmittedRow(
                            new DynamicArtDiagnosticsSnapshot(
                                    0, List.of(), List.of(), 1, 0, true));
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertTrue(active.isClosed());
        assertFalse(harness.hasActivePayload());
        harness.assertAliasesCleared();
        awaitCollected(reachability.trace(), "source trace");
        awaitCollected(reachability.auxEvent(), "source auxiliary graph");
        awaitCollected(reachability.comparator(), "source comparator");
        awaitCollected(reachability.rows(), "special rows");
        awaitCollected(reachability.driver(), "special row driver");
        awaitCollected(reachability.binder(), "S2 pass binder");
        awaitCollected(reachability.dynamicArt(), "dynamic-art comparison");
        Reference.reachabilityFence(active);
    }

    @Test
    void failingDestinationOpenLeavesGapWithoutPayload(@TempDir Path root)
            throws Exception {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(root, false);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);
        harness.open(descriptors.get(0), 0);
        harness.finishBoundary(() -> { });
        factory.failOpenSegment = 1;

        IOException failure = assertThrows(IOException.class,
                () -> harness.openAndAttach(
                        descriptors.get(1), 1, payload -> null));

        assertEquals("injected open failure 1", failure.getMessage());
        assertFalse(harness.hasActivePayload());
        harness.assertAliasesCleared();
        assertEquals(List.of("open 0", "close 0", "open-failed 1"),
                factory.transcript);
        assertEquals(0, factory.activeCount());
    }

    @Test
    void initialSetupFailureClosesSegmentZeroThroughTheRealChainEntry(
            @TempDir Path root) throws Exception {
        Path runDir = writeRun(root, false);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);
        AssertionError primary = new AssertionError(
                "injected initial setup failure");
        harness.failInitialSetupWith(primary);

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> harness.run(runDir));

        assertSame(primary, thrown);
        assertEquals(List.of("open 0", "close 0"), factory.transcript);
        assertEquals(0, factory.activeCount());
        harness.assertAliasesCleared();
    }

    @Test
    void attachmentFailureDetachesDelegateAndClosesTheOpenedDestination(
            @TempDir Path root) throws Exception {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(root, false);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);
        AssertionError primary = new AssertionError(
                "injected timing attachment failure");

        AttachmentFailureReachability reachability =
                exerciseAttachmentFailure(
                        harness, descriptors.getFirst(), primary);

        assertSame(primary, reachability.failure());
        assertTrue(reachability.active().isClosed());
        harness.assertAliasesCleared();
        awaitCollected(reachability.comparator(), "attachment comparator");
        Reference.reachabilityFence(reachability.active());
    }

    @Test
    void slotProbeCloseFailureIsSuppressedAndCannotBypassLeaseCleanup(
            @TempDir Path root) throws Exception {
        List<TraceRunSegmentDescriptor> descriptors = descriptors(root, false);
        RecordingFactory factory = new RecordingFactory();
        Harness harness = new Harness(factory);
        FailingSlotProbe slotProbe = new FailingSlotProbe();
        harness.slotProbe(slotProbe);
        ActiveSegmentPayload active = harness.open(descriptors.getFirst(), 0);
        LiveTraceComparator comparator = new LiveTraceComparator(
                active.trace(), ToleranceConfig.DEFAULT, 0, () -> null);
        harness.attach(probe(), comparator, active.trace(), 0, () -> { });
        AssertionError primary = new AssertionError(
                "injected comparison failure");

        Throwable thrown = assertThrows(Throwable.class,
                () -> harness.finishBoundary(() -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(slotProbe.failure, thrown.getSuppressed()[0]);
        assertTrue(slotProbe.closeAttempted);
        assertTrue(active.isClosed());
        assertEquals(0, factory.activeCount());
        harness.assertAliasesCleared();
    }

    private static List<TraceRunSegmentDescriptor> descriptors(
            Path root, boolean passBinder) throws Exception {
        Path runDir = writeRun(root, passBinder);
        TraceRunManifest manifest = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        List<TraceRunSegmentDescriptor> planned =
                TraceRunReplayWalker.planDescriptors(manifest, runDir);
        TraceRunSegmentDescriptor returned = planned.get(2);
        TraceRunSegmentDescriptor bridge = new TraceRunSegmentDescriptor(
                returned.segment(), returned.segmentDirectory(),
                returned.metadata(), returned.rowCount(),
                returned.openingFrame(), returned.rawFrames(),
                returned.laggedRows(), returned.hardwareTimingSchedule(),
                returned.terminalDynamicArtLedger(), returned.entryBoundary(),
                returned.exitBoundary(), returned.rowCount(),
                TraceRunReplayWalker.SegmentExecutionPolicy
                        .LEVEL_PRESENTATION_BRIDGE);
        return List.of(planned.get(0), planned.get(1), bridge, returned);
    }

    private static Path writeRun(Path root, boolean passBinder)
            throws Exception {
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("s2"));
        if (passBinder) {
            Path aux = runDir.resolve("ss/aux_state.jsonl");
            Files.writeString(aux, Files.readString(aux) + """
                    {"frame":0,"type":"control_state","started":1}
                    {"frame":1,"type":"run_objects_end","pass_sequence":0,"started_at_input_sample":1,"first_eligible_frame":1,"completion_cursor_frame":1,"input_sample_frame":1,"input_sample_bk2_frame":801,"previous_input_sample_frame":0,"previous_input_sample_bk2_frame":800,"input_sample_sequence":1,"input_source":"vint_s2ss_read_joypads","p1_held":0,"p2_held":0,"previous_p1_held":0,"previous_p2_held":0}
                    """);
        }
        return runDir;
    }

    private static AttachmentFailureReachability exerciseAttachmentFailure(
            Harness harness, TraceRunSegmentDescriptor descriptor,
            AssertionError primary) throws Exception {
        ActiveSegmentPayload active = harness.open(descriptor, 0);
        LiveTraceComparator comparator = new LiveTraceComparator(
                active.trace(), ToleranceConfig.DEFAULT, 0, () -> null);
        WeakReference<LiveTraceComparator> comparatorReference =
                new WeakReference<>(comparator);
        Throwable failure = assertThrows(Throwable.class,
                () -> harness.attach(
                        probe(), comparator, active.trace(), 0,
                        () -> {
                            throw primary;
                        }));
        return new AttachmentFailureReachability(
                failure, active, comparatorReference);
    }

    private static TraceRunReplayWalker.BoundaryProbe probe() {
        return new TraceRunReplayWalker.BoundaryProbe(
                new TraceRunReplayWalker.EngineHooks() {
                    @Override public int currentBk2Frame() { return 0; }
                    @Override public BonusStageType peekBonusRequest() {
                        return null;
                    }
                    @Override public boolean isSpecialStageRequested() {
                        return false;
                    }
                    @Override public GameMode currentMode() {
                        return GameMode.LEVEL;
                    }
                });
    }

    private static void assertReferentsReachable(Reachability reachability) {
        assertTrue(reachability.trace().get() != null, "source trace precondition");
        assertTrue(reachability.auxEvent().get() != null, "aux graph precondition");
        assertTrue(reachability.comparator().get() != null, "comparator precondition");
        assertTrue(reachability.rows().get() != null, "special rows precondition");
        assertTrue(reachability.driver().get() != null, "special driver precondition");
        assertTrue(reachability.binder().get() != null, "pass binder precondition");
        assertTrue(reachability.dynamicArt().get() != null,
                "dynamic-art comparison precondition");
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

    private record Reachability(
            WeakReference<TraceData> trace,
            WeakReference<TraceEvent> auxEvent,
            WeakReference<LiveTraceComparator> comparator,
            WeakReference<TraceRunSpecialStageRows> rows,
            WeakReference<TraceRunSpecialStageRowDriver> driver,
            WeakReference<SpecialStageRunObjectsPassBinder> binder,
            WeakReference<TraceRunReplayWalker.DynamicArtSegmentComparison>
                    dynamicArt) { }

    private record AttachmentFailureReachability(
            Throwable failure,
            ActiveSegmentPayload active,
            WeakReference<LiveTraceComparator> comparator) { }

    private static final class Harness extends AbstractRunChainTest {
        private Harness(RecordingFactory factory) {
            activeSegmentFactory = factory;
        }

        private ActiveSegmentPayload open(
                TraceRunSegmentDescriptor descriptor, int segmentIndex)
                throws IOException {
            return openHeadlessPayload(descriptor, segmentIndex);
        }

        private void finishBoundary(
                AbstractRunChainTest.HeadlessBoundaryAction action)
                throws Exception {
            finishHeadlessBoundary(action);
        }

        private LiveTraceComparator attach(
                TraceRunReplayWalker.BoundaryProbe probe,
                LiveTraceComparator comparator,
                TraceData trace,
                int segmentIndex,
                Runnable timingAttachment) {
            return attachHeadlessComparator(
                    probe, comparator, trace, segmentIndex,
                    timingAttachment);
        }

        private <T> T openAndAttach(
                TraceRunSegmentDescriptor descriptor,
                int segmentIndex,
                AbstractRunChainTest.HeadlessPayloadAttachment<T> attachment)
                throws Exception {
            return openAndAttachHeadlessPayload(
                    descriptor, segmentIndex, attachment);
        }

        private DynamicArtGapJournalEvidence run(Path runDir) throws Exception {
            return assertChainReplay(runDir);
        }

        private void failInitialSetupWith(AssertionError failure) {
            afterInitialHeadlessPayloadOpen = () -> {
                throw failure;
            };
        }

        private void slotProbe(FailingSlotProbe probe) {
            slotProbeFactory = (trace, label) -> probe;
        }

        private Reachability installEveryAlias(
                TraceData trace, TraceRunSpecialStageRows rows) {
            TraceEvent aux = trace.getEventsForFrame(0).getFirst();
            LiveTraceComparator comparator = new LiveTraceComparator(
                    trace, ToleranceConfig.DEFAULT, 0, () -> null);
            TraceRunSpecialStageRowDriver driver =
                    new TraceRunSpecialStageRowDriver(rows, trace);
            SpecialStageRunObjectsPassBinder binder =
                    rows.newRunObjectsPassBinder().orElseThrow();
            TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt =
                    new TraceRunReplayWalker.DynamicArtSegmentComparison(
                            trace, trace.frameCount());
            TraceRunReplayWalker.BoundaryProbe probe =
                    new TraceRunReplayWalker.BoundaryProbe(
                            new TraceRunReplayWalker.EngineHooks() {
                                @Override public int currentBk2Frame() { return 0; }
                                @Override public BonusStageType peekBonusRequest() {
                                    return null;
                                }
                                @Override public boolean isSpecialStageRequested() {
                                    return false;
                                }
                                @Override public GameMode currentMode() {
                                    return GameMode.LEVEL;
                                }
                            });
            probe.setDelegate(comparator);
            installHeadlessPayloadAliases(
                    probe, comparator, null, driver, rows, binder, dynamicArt);
            return new Reachability(
                    new WeakReference<>(trace), new WeakReference<>(aux),
                    new WeakReference<>(comparator), new WeakReference<>(rows),
                    new WeakReference<>(driver), new WeakReference<>(binder),
                    new WeakReference<>(dynamicArt));
        }

        private void installStructuralAlias(
                TraceStructuralRowComparator structural) {
            installHeadlessPayloadAliases(
                    null, null, structural, null, null, null, null);
        }

        private void assertAliasesCleared() {
            assertHeadlessPayloadAliasesCleared();
        }
    }

    private static final class FailingSlotProbe
            implements AbstractRunChainTest.HeadlessSlotProbe {
        private final RuntimeException failure =
                new IllegalStateException("injected slot-probe close failure");
        private boolean closeAttempted;

        @Override
        public void observe(int traceFrame, ObjectManager objectManager) {
        }

        @Override
        public void close() {
            closeAttempted = true;
            throw failure;
        }
    }

    private static final class RecordingFactory
            implements AbstractRunChainTest.ActiveSegmentFactory {
        private final List<String> transcript = new ArrayList<>();
        private final List<WeakReference<ActiveSegmentPayload>> leases =
                new ArrayList<>();
        private final List<Integer> leaseIndexes = new ArrayList<>();
        private int maximumActive;
        private int failOpenSegment = -1;

        @Override
        public ActiveSegmentPayload open(
                TraceRunSegmentDescriptor descriptor, int segmentIndex)
                throws IOException {
            assertEquals(0, activeCount(),
                    "the preceding lease must close before the next open");
            if (segmentIndex == failOpenSegment) {
                transcript.add("open-failed " + segmentIndex);
                throw new IOException("injected open failure " + segmentIndex);
            }
            ActiveSegmentPayload payload =
                    openActiveSegment(descriptor, segmentIndex);
            transcript.add("open " + segmentIndex);
            leases.add(new WeakReference<>(payload));
            leaseIndexes.add(segmentIndex);
            maximumActive = Math.max(maximumActive, activeCount());
            return payload;
        }

        @Override
        public void close(ActiveSegmentPayload payload) {
            int index = -1;
            for (int candidate = 0; candidate < leases.size(); candidate++) {
                if (leases.get(candidate).get() == payload) {
                    index = leaseIndexes.get(candidate);
                    break;
                }
            }
            payload.close();
            transcript.add("close " + index);
        }

        private int activeCount() {
            int active = 0;
            for (WeakReference<ActiveSegmentPayload> reference : leases) {
                ActiveSegmentPayload payload = reference.get();
                if (payload != null && !payload.isClosed()) {
                    active++;
                }
            }
            return active;
        }
    }

    private static ActiveSegmentPayload openActiveSegment(
            TraceRunSegmentDescriptor descriptor, int segmentIndex)
            throws IOException {
        return TraceRunReplayWalker.openActiveSegment(descriptor, segmentIndex);
    }
}
