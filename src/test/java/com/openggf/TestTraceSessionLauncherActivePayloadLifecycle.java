package com.openggf;

import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.game.BonusStageType;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.testmode.TraceCameraFocusController;
import com.openggf.testmode.TraceHudOverlay;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.DestinationAdmissionReceipt;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.tests.trace.TraceV5RunFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestTraceSessionLauncherActivePayloadLifecycle {

    @AfterEach
    void tearDown() {
        setStaticField(TraceSessionLauncher.class, "activeSession", null);
        Engine.clearGlobalInstance();
        GameServices.playbackDebug().endSession();
        SessionManager.clear();
    }

    @Test
    void launchHandoffGapAndTerminalTailOwnAtMostOneRealPayload(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(fixture.descriptors().get(0), 0);
        TraceSessionLauncher session = session(fixture.descriptors(), initial, factory);

        assertSame(initial, field(session, "activeRunPayload"));
        closeSegment(session, 0);
        assertTrue(initial.isClosed());
        assertNull(field(session, "activeRunPayload"),
                "the transition gap must own no payload");

        openSegment(session, 1);
        ActiveSegmentPayload special =
                (ActiveSegmentPayload) field(session, "activeRunPayload");
        assertFalse(special.isClosed());
        closeSegment(session, 1);
        assertTrue(special.isClosed());
        assertNull(field(session, "activeRunPayload"));

        openSegment(session, 2);
        ActiveSegmentPayload destination =
                (ActiveSegmentPayload) field(session, "activeRunPayload");
        assertEquals(2, destination.trace().frameCount(),
                "the destination stays usable after source release");
        closeSegment(session, 2);

        assertTrue(destination.isClosed());
        assertNull(field(session, "activeRunPayload"),
                "the terminal tail must own no payload");
        assertEquals(List.of("open 0", "close 0", "open 1", "close 1",
                "open 2", "close 2"), factory.transcript);
        assertEquals(1, factory.maximumActive);
        assertEquals(0, factory.activeCount());
    }

    @Test
    void destinationOpenFailureLeavesTheGapUnowned(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(fixture.descriptors().get(0), 0);
        TraceSessionLauncher session = session(fixture.descriptors(), initial, factory);
        closeSegment(session, 0);
        factory.failOpenSegment = 1;

        Throwable failure = assertThrows(Throwable.class,
                () -> openSegment(session, 1));

        assertTrue(rootCause(failure) instanceof IOException, failure::toString);
        assertNull(field(session, "activeRunPayload"));
        assertEquals(List.of("open 0", "close 0", "open-failed 1"),
                factory.transcript);
        assertEquals(0, factory.activeCount());
    }

    @Test
    void sourceLeaseClosesBeforeTimingAndFixtureEnterTheGap(
            @TempDir Path root) throws Exception {
        Fixture fixtureData = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixtureData.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixtureData.descriptors(), initial, factory);
        TraceRunReplayWalker.HardwareTimingCoordinator timing =
                mock(TraceRunReplayWalker.HardwareTimingCoordinator.class);
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        doAnswer(invocation -> {
            factory.transcript.add("timing gap");
            return null;
        }).when(timing).enterTransitionGap();
        doAnswer(invocation -> {
            factory.transcript.add("fixture gap");
            return null;
        }).when(fixture).enterHardwareTimingGap();
        setField(session, "runHardwareTiming", timing);
        setField(session, "fixture", fixture);

        invoke(session, "applyRunCoordinatorActions",
                new Class<?>[] {List.class},
                List.of(new TraceRunPlaybackCoordinator.CloseSegment(0),
                        new TraceRunPlaybackCoordinator.EnterTransitionGap(0, 1)));

        assertEquals(List.of("open 0", "close 0", "timing gap", "fixture gap"),
                factory.transcript);
        assertNull(field(session, "activeRunPayload"));
        assertEquals(0, factory.activeCount());
    }

    @Test
    void cleanupClosesFirstAndSuppressesItsFailureOntoAssertion(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(fixture.descriptors().get(0), 0);
        TraceSessionLauncher session = session(fixture.descriptors(), initial, factory);
        factory.closeFailure = new IllegalStateException("injected close failure");
        AssertionError primary = new AssertionError("production assertion");

        Throwable returned = abort(session, primary, "assertion abort");

        assertSame(primary, returned);
        assertTrue(initial.isClosed(), "the injected closer must close first");
        assertEquals(1, primary.getSuppressed().length);
        assertSame(factory.closeFailure, primary.getSuppressed()[0]);
        assertNull(field(session, "activeRunPayload"));
        assertEquals(0, factory.activeCount());
    }

    @Test
    void abortUserExitAndRepeatedTeardownCloseIdempotently(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(fixture.descriptors().get(0), 0);
        TraceSessionLauncher session = session(fixture.descriptors(), initial, factory);
        session.beginTitleCardPresentation(new TraceSessionLauncher.TitleCardPresentation() {
            @Override public void prepareLevel() { }
            @Override public void enterTitleCard() { }
        });

        session.requestEarlyExit();
        Throwable repeated = abort(session, null, "repeated teardown");

        assertNull(repeated);
        assertTrue(initial.isClosed());
        assertNull(TraceSessionLauncher.active());
        assertEquals(List.of("open 0", "close 0"), factory.transcript,
                "repeated teardown must not close the lease twice");
        assertEquals(0, factory.activeCount());
    }

    @Test
    void activeRunGapExitDoesNotRequireAComparator(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        closeSegment(session, 0);
        setStaticField(TraceSessionLauncher.class, "activeSession", session);

        session.requestEarlyExit();

        assertNull(TraceSessionLauncher.active());
        assertEquals(List.of("open 0", "close 0"), factory.transcript);
    }

    @Test
    void activeRunSpecialLocalExitDoesNotRequireAComparator(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        closeSegment(session, 0);
        openSegment(session, 1);
        ActiveSegmentPayload special =
                (ActiveSegmentPayload) field(session, "activeRunPayload");
        setStaticField(TraceSessionLauncher.class, "activeSession", session);

        session.requestEarlyExit();

        assertNull(TraceSessionLauncher.active());
        assertTrue(initial.isClosed());
        assertTrue(special.isClosed());
        assertNull(field(session, "activeRunPayload"));
    }

    @Test
    void activeRunTerminalTailExitDoesNotRequireAComparator(@TempDir Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        closeSegment(session, 0);
        setField(session, "runTerminalTail",
                new TraceRunReplayWalker.TerminalMovieTailPlan(
                        100, 2, GameMode.TITLE_SCREEN));
        setStaticField(TraceSessionLauncher.class, "activeSession", session);

        session.requestEarlyExit();

        assertNull(TraceSessionLauncher.active());
        assertNull(field(session, "activeRunPayload"));
    }

    @Test
    void wrongCloseIndexKeepsValidationPrimaryAndClosesActualLease(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        factory.closeFailure = new IllegalStateException("close seam failed");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> closeSegment(session, 1));

        assertTrue(failure.getMessage().contains("segment 1"), failure::getMessage);
        assertSame(factory.closeFailure, failure.getSuppressed()[0]);
        assertTrue(initial.isClosed());
        assertNull(field(session, "activeRunPayload"));
    }

    @Test
    void outOfBoundsAdmissionKeepsValidationPrimaryAndClosesActualLease(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        factory.closeFailure = new IllegalStateException("close seam failed");
        DestinationAdmissionReceipt receipt = new DestinationAdmissionReceipt(
                99, DestinationAdmissionReceipt.InputClock.SHARED, 0, 0,
                new DestinationAdmissionReceipt.LevelIdentity(0, 0, 1),
                0, 0, 0);

        IndexOutOfBoundsException failure = assertThrows(
                IndexOutOfBoundsException.class,
                () -> admitDestination(session, receipt));

        assertTrue(failure.getMessage().contains("99"), failure::getMessage);
        assertSame(factory.closeFailure, failure.getSuppressed()[0]);
        assertTrue(initial.isClosed());
        assertNull(field(session, "activeRunPayload"));
    }

    @Test
    void wrongIndexAdmissionKeepsIdentityPrimaryAndClosesActualLease(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        factory.closeFailure = new IllegalStateException("close seam failed");
        DestinationAdmissionReceipt receipt = new DestinationAdmissionReceipt(
                1, DestinationAdmissionReceipt.InputClock.SPECIAL_LOCAL, 800, 0,
                new DestinationAdmissionReceipt.SpecialStageIdentity(0),
                0, 0, 0);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> admitDestination(session, receipt));

        assertTrue(failure.getMessage().contains("segment 1"), failure::getMessage);
        assertSame(factory.closeFailure, failure.getSuppressed()[0]);
        assertTrue(initial.isClosed());
        assertNull(field(session, "activeRunPayload"));
    }

    @Test
    void ordinarySourceAliasesClearWhileDestinationRemainsUsable(
            @TempDir Path root) throws Exception {
        OrdinaryReachability sample = openOrdinaryThenAdvance(root);

        awaitCollected(sample.trace(), "source TraceData");
        awaitCollected(sample.auxEvent(), "source aux graph");
        awaitCollected(sample.comparator(), "source comparator");
        awaitCollected(sample.camera(), "source camera model");
        awaitCollected(sample.overlay(), "source HUD model");
        awaitCollected(sample.dynamicArt(), "source dynamic-art comparison");
        assertEquals(2, sample.destination().specialStageRows().rowCount());
        assertFalse(sample.destination().isClosed());

        closeSegment(sample.session(), 1);
    }

    @Test
    void failedSpecialSourceReleasesRowsDriverAndPassBinder(
            @TempDir Path root) throws Exception {
        SpecialReachability sample = openSpecialThenFail(root);

        awaitCollected(sample.trace(), "special metadata TraceData");
        awaitCollected(sample.rows(), "special rows");
        awaitCollected(sample.driver(), "special row driver");
        awaitCollected(sample.binder(), "S2 pass binder");
        assertNull(field(sample.session(), "activeRunPayload"));
    }

    private static OrdinaryReachability openOrdinaryThenAdvance(Path root)
            throws Exception {
        Fixture fixture = fixture(root, false);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(fixture.descriptors().get(0), 0);
        TraceSessionLauncher session = session(fixture.descriptors(), initial, factory);
        TraceData trace = initial.trace();
        TraceEvent aux = trace.getEventsForFrame(0).getFirst();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null);
        TraceCameraFocusController camera = new TraceCameraFocusController(
                comparator, () -> null, () -> null, () -> null,
                GameServices.configuration(), () -> false);
        TraceHudOverlay overlay = new TraceHudOverlay(comparator,
                () -> null, () -> null);
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
        probe.prepareFrame(new Bk2FrameInput(
                0, 0, 0, false, "prepared source row"));
        setField(session, "comparator", comparator);
        setField(session, "productionIterationComparator", comparator);
        setField(session, "cameraFocusController", camera);
        setField(session, "overlay", overlay);
        setField(session, "runSpecialDynamicArtComparison", dynamicArt);
        setField(session, "runBoundaryProbe", probe);
        GameServices.playbackDebug().setFrameObserver(probe);

        closeSegment(session, 0);
        openSegment(session, 1);
        ActiveSegmentPayload destination =
                (ActiveSegmentPayload) field(session, "activeRunPayload");
        return new OrdinaryReachability(session, destination,
                new WeakReference<>(trace), new WeakReference<>(aux),
                new WeakReference<>(comparator), new WeakReference<>(camera),
                new WeakReference<>(overlay), new WeakReference<>(dynamicArt));
    }

    private static SpecialReachability openSpecialThenFail(Path root)
            throws Exception {
        Fixture fixture = fixture(root, true);
        RecordingFactory factory = new RecordingFactory();
        ActiveSegmentPayload initial = factory.open(
                fixture.descriptors().getFirst(), 0);
        TraceSessionLauncher session = session(
                fixture.descriptors(), initial, factory);
        closeSegment(session, 0);
        openSegment(session, 1);
        ActiveSegmentPayload special =
                (ActiveSegmentPayload) field(session, "activeRunPayload");
        TraceData trace = special.trace();
        TraceRunSpecialStageRows rows = special.specialStageRows();
        TraceRunSpecialStageRowDriver driver =
                new TraceRunSpecialStageRowDriver(rows, trace);
        SpecialStageRunObjectsPassBinder binder =
                rows.newRunObjectsPassBinder().orElseThrow();
        setField(session, "runSpecialRows", rows);
        setField(session, "runSpecialRowDriver", driver);
        setField(session, "runSpecialPassBinder", binder);
        factory.closeFailure = new IllegalStateException(
                "special close cleanup failure");

        Throwable failure = assertThrows(Throwable.class,
                () -> closeSegment(session, 1),
                "the incomplete driver supplies the primary verification failure");
        assertSame(factory.closeFailure, failure.getSuppressed()[0]);
        assertTrue(special.isClosed(), "the failing closer must close first");
        return new SpecialReachability(session,
                new WeakReference<>(trace), new WeakReference<>(rows),
                new WeakReference<>(driver), new WeakReference<>(binder));
    }

    private static Fixture fixture(Path root, boolean passBinder) throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        Path runDir = TraceV5RunFixture.writeS2SpecialStageRun(root.resolve("s2"));
        if (passBinder) {
            Path aux = runDir.resolve("ss/aux_state.jsonl");
            Files.writeString(aux, Files.readString(aux) + """
                    {"frame":0,"type":"control_state","started":1}
                    {"frame":1,"type":"run_objects_end","pass_sequence":0,"started_at_input_sample":1,"first_eligible_frame":1,"completion_cursor_frame":1,"input_sample_frame":1,"input_sample_bk2_frame":801,"previous_input_sample_frame":0,"previous_input_sample_bk2_frame":800,"input_sample_sequence":1,"input_source":"vint_s2ss_read_joypads","p1_held":0,"p2_held":0,"previous_p1_held":0,"previous_p2_held":0}
                    """);
        }
        TraceRunManifest manifest = TraceRunManifest.load(
                runDir.resolve("run_manifest.json"));
        return new Fixture(TraceRunReplayWalker.planDescriptors(manifest, runDir));
    }

    private static TraceSessionLauncher session(
            List<TraceRunSegmentDescriptor> descriptors,
            ActiveSegmentPayload initial,
            TraceSessionLauncher.ActiveSegmentFactory factory) {
        TraceSessionLauncher session = new TraceSessionLauncher(
                null, null, descriptors, initial,
                (TraceReplaySessionBootstrap.ConfigSnapshot) null);
        session.activeSegmentFactory = factory;
        return session;
    }

    private static void closeSegment(TraceSessionLauncher session, int index)
            throws Exception {
        invoke(session, "closeRunSegment", new Class<?>[] {int.class}, index);
    }

    private static void openSegment(TraceSessionLauncher session, int index)
            throws Exception {
        invoke(session, "openRunPayload", new Class<?>[] {int.class}, index);
    }

    private static void admitDestination(TraceSessionLauncher session,
            DestinationAdmissionReceipt receipt) throws Exception {
        invoke(session, "applyRunDestinationAdmission",
                new Class<?>[] {DestinationAdmissionReceipt.class}, receipt);
    }

    private static Throwable abort(TraceSessionLauncher session,
            Throwable primary, String reason) throws Exception {
        return (Throwable) invoke(session, "abortIncompleteSession",
                new Class<?>[] {Throwable.class, String.class, GameLoop.class},
                primary, reason, null);
    }

    private static Object invoke(Object target, String name,
            Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw e;
        }
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        while (result.getCause() != null) {
            result = result.getCause();
        }
        return result;
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

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setStaticField(Class<?> owner, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record Fixture(List<TraceRunSegmentDescriptor> descriptors) { }

    private record OrdinaryReachability(
            TraceSessionLauncher session,
            ActiveSegmentPayload destination,
            WeakReference<TraceData> trace,
            WeakReference<TraceEvent> auxEvent,
            WeakReference<LiveTraceComparator> comparator,
            WeakReference<TraceCameraFocusController> camera,
            WeakReference<TraceHudOverlay> overlay,
            WeakReference<TraceRunReplayWalker.DynamicArtSegmentComparison>
                    dynamicArt) { }

    private record SpecialReachability(
            TraceSessionLauncher session,
            WeakReference<TraceData> trace,
            WeakReference<TraceRunSpecialStageRows> rows,
            WeakReference<TraceRunSpecialStageRowDriver> driver,
            WeakReference<SpecialStageRunObjectsPassBinder> binder) { }

    private static final class RecordingFactory
            implements TraceSessionLauncher.ActiveSegmentFactory {
        private final List<String> transcript = new ArrayList<>();
        private final List<WeakReference<ActiveSegmentPayload>> leases =
                new ArrayList<>();
        private final List<Integer> leaseIndexes = new ArrayList<>();
        private int maximumActive;
        private int failOpenSegment = -1;
        private RuntimeException closeFailure;

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
            if (closeFailure != null) {
                throw closeFailure;
            }
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
