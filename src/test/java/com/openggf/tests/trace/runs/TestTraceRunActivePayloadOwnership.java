package com.openggf.tests.trace.runs;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceHudModel;
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

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Transitive collection proof for every installed active-payload consumer. */
class TestTraceRunActivePayloadOwnership extends AbstractRunChainTest {

    @Test
    void normalTeardownCollectsEveryInstalledOrdinaryAndSpecialPayloadGraph(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        InstalledRoots roots = new InstalledRoots(this, fixture.descriptors());

        List<NamedReference> released = exerciseOrdinaryAndSpecial(
                fixture.descriptors(), roots, null);

        assertHeadlessPayloadAliasesCleared();
        assertInstalledRootsDetached(roots);
        awaitCollected(released);
        fenceInstalledRoots(roots);
    }

    @Test
    void failureTeardownPreservesPrimaryAndCollectsEveryInstalledGraph(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        InstalledRoots roots = new InstalledRoots(this, fixture.descriptors());
        AssertionError primary = new AssertionError("injected comparison failure");

        List<NamedReference> released = exerciseOrdinaryAndSpecial(
                fixture.descriptors(), roots, primary);

        assertHeadlessPayloadAliasesCleared();
        assertInstalledRootsDetached(roots);
        awaitCollected(released);
        fenceInstalledRoots(roots);
    }

    @Test
    void comparatorRetentionControlStaysLiveUntilTheInjectedRootIsRemoved(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        InstalledRoots roots = new InstalledRoots(this, fixture.descriptors());

        WeakReference<LiveTraceComparator> comparator =
                installCloseAndIntentionallyRetainComparator(
                        fixture.descriptors().getFirst(), roots);

        forceGcCycles(12);
        assertNotNull(comparator.get(),
                "the mutation root must keep the real comparator reachable");
        roots.retainedMutation.set(null);
        awaitCollected(List.of(new NamedReference(
                "retained comparator after mutation removal", comparator)));
        fenceInstalledRoots(roots);
    }

    private List<NamedReference> exerciseOrdinaryAndSpecial(
            List<TraceRunSegmentDescriptor> descriptors,
            InstalledRoots roots,
            AssertionError specialFailure) throws Exception {
        List<NamedReference> released = new ArrayList<>();
        released.addAll(installAndCloseOrdinary(descriptors.getFirst(), roots));
        released.addAll(installAndCloseSpecial(
                descriptors.get(1), roots, specialFailure));
        return List.copyOf(released);
    }

    private List<NamedReference> installAndCloseOrdinary(
            TraceRunSegmentDescriptor descriptor,
            InstalledRoots roots) throws Exception {
        ActiveSegmentPayload lease = openHeadlessPayload(descriptor, 0);
        TraceData trace = lease.trace();
        TraceEvent auxEvent = trace.getEventsForFrame(0).getFirst();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null);
        TraceStructuralRowComparator structural =
                new TraceStructuralRowComparator(
                        trace, ToleranceConfig.DEFAULT, 0,
                        List::of, () -> null);
        TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, trace.frameCount());
        TraceRunReplayWalker.BoundaryProbe probe = boundaryProbe();
        RecordingSlotFactory slotFactory = roots.slotFactory;
        slotProbeFactory = slotFactory;
        attachHeadlessComparator(probe, comparator, trace, 0, () -> { });
        installHeadlessPayloadAliases(
                probe, comparator, structural, null, null, null, dynamicArt);
        RetainingSlotProbe slotProbe = slotFactory.last.get();
        assertNotNull(slotProbe, "the real comparator attachment must install a slot probe");
        roots.attach(probe, comparator, trace, null);

        List<NamedReference> references = List.of(
                weak("ordinary trace", trace),
                weak("ordinary aux event", auxEvent),
                weak("ordinary live comparator", comparator),
                weak("ordinary structural comparator", structural),
                weak("ordinary dynamic-art comparison", dynamicArt),
                weak("ordinary slot probe", slotProbe),
                weak("ordinary lease", lease));
        assertReachable(references);

        finishHeadlessBoundary(roots::detachPayloadConsumers);
        assertTrue(lease.isClosed());
        slotFactory.last.set(null);
        return references;
    }

    private List<NamedReference> installAndCloseSpecial(
            TraceRunSegmentDescriptor descriptor,
            InstalledRoots roots,
            AssertionError primary) throws Exception {
        ActiveSegmentPayload lease = openHeadlessPayload(descriptor, 1);
        TraceData trace = lease.trace();
        TraceRunSpecialStageRows rows = lease.specialStageRows();
        TraceRunSpecialStageRowDriver driver =
                new TraceRunSpecialStageRowDriver(rows, trace);
        SpecialStageRunObjectsPassBinder binder =
                rows.newRunObjectsPassBinder().orElseThrow();
        assertTrue(binder.hasRemaining(),
                "the S2 fixture must bind a real recorded RunObjects pass");
        List<?> passPacingList = binderPassPacingList(binder);
        assertEquals(1, passPacingList.size(),
                "the reachability proof must cover the recorded pass list");
        Object recordedPass = passPacingList.getFirst();
        TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, rows.rowCount(), rows.normalizedDynamicArtRows());
        SpecialObserver specialObserver = new SpecialObserver(driver, binder);
        TraceRunReplayWalker.BoundaryProbe probe = boundaryProbe();
        probe.setDelegate(specialObserver);
        installHeadlessPayloadAliases(
                probe, null, null, driver, rows, binder, dynamicArt);
        roots.attach(probe, specialObserver, trace, rows);

        List<NamedReference> references = List.of(
                weak("special metadata trace", trace),
                weak("special rows", rows),
                weak("special row driver", driver),
                weak("special pass binder", binder),
                weak("special pass-pacing list", passPacingList),
                weak("special recorded pass", recordedPass),
                weak("special dynamic-art comparison", dynamicArt),
                weak("special boundary delegate", specialObserver),
                weak("special lease", lease));
        assertReachable(references);

        if (primary == null) {
            finishHeadlessBoundary(() -> {
                roots.detachPayloadConsumers();
                specialObserver.detach();
            });
        } else {
            AssertionError thrown = assertThrows(AssertionError.class,
                    () -> finishHeadlessBoundary(() -> {
                        roots.detachPayloadConsumers();
                        specialObserver.detach();
                        throw primary;
                    }));
            assertSame(primary, thrown,
                    "cleanup must preserve the injected comparison failure");
        }
        assertTrue(lease.isClosed());
        return references;
    }

    private WeakReference<LiveTraceComparator>
            installCloseAndIntentionallyRetainComparator(
                    TraceRunSegmentDescriptor descriptor,
                    InstalledRoots roots) throws Exception {
        ActiveSegmentPayload lease = openHeadlessPayload(descriptor, 0);
        TraceData trace = lease.trace();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null);
        TraceRunReplayWalker.BoundaryProbe probe = boundaryProbe();
        attachHeadlessComparator(probe, comparator, trace, 0, () -> { });
        roots.attach(probe, comparator, trace, null);
        roots.retainedMutation.set(comparator);
        WeakReference<LiveTraceComparator> reference =
                new WeakReference<>(comparator);

        finishHeadlessBoundary(roots::detachPayloadConsumers);
        assertTrue(lease.isClosed());
        return reference;
    }

    private static Fixture fixture(Path root) throws Exception {
        Path runDirectory = TraceV5RunFixture.writeS2SpecialStageRun(
                root.resolve("s2/runs"));
        Path auxiliary = runDirectory.resolve("ss/aux_state.jsonl");
        Files.writeString(auxiliary, Files.readString(auxiliary) + """
                {"frame":0,"type":"control_state","started":1}
                {"frame":1,"type":"run_objects_end","pass_sequence":0,"started_at_input_sample":1,"first_eligible_frame":1,"completion_cursor_frame":1,"input_sample_frame":1,"input_sample_bk2_frame":801,"previous_input_sample_frame":0,"previous_input_sample_bk2_frame":800,"input_sample_sequence":1,"input_source":"vint_s2ss_read_joypads","p1_held":0,"p2_held":0,"previous_p1_held":0,"previous_p2_held":0}
                """);
        TraceRunManifest manifest = TraceRunManifest.load(
                runDirectory.resolve("run_manifest.json"));
        return new Fixture(TraceRunReplayWalker.planDescriptors(
                manifest, runDirectory));
    }

    private static TraceRunReplayWalker.BoundaryProbe boundaryProbe() {
        return new TraceRunReplayWalker.BoundaryProbe(
                new TraceRunReplayWalker.EngineHooks() {
                    @Override
                    public int currentBk2Frame() {
                        return 0;
                    }

                    @Override
                    public BonusStageType peekBonusRequest() {
                        return null;
                    }

                    @Override
                    public boolean isSpecialStageRequested() {
                        return false;
                    }

                    @Override
                    public GameMode currentMode() {
                        return GameMode.LEVEL;
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static List<?> binderPassPacingList(
            SpecialStageRunObjectsPassBinder binder) throws Exception {
        Field passes = SpecialStageRunObjectsPassBinder.class
                .getDeclaredField("passes");
        passes.setAccessible(true);
        return (List<?>) passes.get(binder);
    }

    private static void assertInstalledRootsDetached(InstalledRoots roots) {
        assertNotNull(roots.session);
        assertFalse(roots.descriptors.isEmpty());
        assertNotNull(roots.playbackObserver.get());
        assertNull(roots.boundaryDelegate.get());
        assertNull(roots.hudSupplier.get());
        assertNull(roots.cameraSupplier.get());
        assertNull(roots.fixture.get().trace);
        assertNull(roots.fixture.get().rows);
    }

    private static void assertReachable(List<NamedReference> references) {
        for (NamedReference reference : references) {
            assertNotNull(reference.reference().get(),
                    reference.name() + " precondition");
        }
    }

    private static void awaitCollected(List<NamedReference> references) {
        for (int attempt = 0; attempt < 100
                && references.stream().anyMatch(
                        reference -> reference.reference().get() != null);
                attempt++) {
            System.gc();
            System.runFinalization();
            byte[] pressure = new byte[128 * 1024];
            pressure[0] = 1;
            try {
                Thread.sleep(10);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(
                        "interrupted while awaiting payload collection", failure);
            }
        }
        for (NamedReference reference : references) {
            assertNull(reference.reference().get(),
                    reference.name() + " remained strongly reachable");
        }
    }

    private static void forceGcCycles(int cycles) {
        for (int attempt = 0; attempt < cycles; attempt++) {
            System.gc();
            byte[] pressure = new byte[64 * 1024];
            pressure[0] = 1;
        }
    }

    private static void fenceInstalledRoots(InstalledRoots roots) {
        Reference.reachabilityFence(roots.session);
        Reference.reachabilityFence(roots.playbackObserver);
        Reference.reachabilityFence(roots.boundaryDelegate);
        Reference.reachabilityFence(roots.hudSupplier);
        Reference.reachabilityFence(roots.cameraSupplier);
        Reference.reachabilityFence(roots.fixture);
        Reference.reachabilityFence(roots.slotFactory);
        Reference.reachabilityFence(roots.descriptors);
        Reference.reachabilityFence(roots);
    }

    private static <T> NamedReference weak(String name, T referent) {
        return new NamedReference(name, new WeakReference<>(referent));
    }

    private record Fixture(List<TraceRunSegmentDescriptor> descriptors) {
        private Fixture {
            descriptors = List.copyOf(descriptors);
        }
    }

    private record NamedReference(
            String name, WeakReference<?> reference) {
    }

    private static final class InstalledRoots {
        private final TestTraceRunActivePayloadOwnership session;
        private final List<TraceRunSegmentDescriptor> descriptors;
        private final AtomicReference<PlaybackDebugManager.PlaybackFrameObserver>
                playbackObserver = new AtomicReference<>();
        private final AtomicReference<Object> boundaryDelegate =
                new AtomicReference<>();
        private final AtomicReference<TraceHudModel> hudSupplier =
                new AtomicReference<>();
        private final AtomicReference<Supplier<Integer>> cameraSupplier =
                new AtomicReference<>();
        private final AtomicReference<FixtureConsumer> fixture =
                new AtomicReference<>(new FixtureConsumer());
        private final RecordingSlotFactory slotFactory =
                new RecordingSlotFactory();
        private final AtomicReference<LiveTraceComparator> retainedMutation =
                new AtomicReference<>();

        private InstalledRoots(
                TestTraceRunActivePayloadOwnership session,
                List<TraceRunSegmentDescriptor> descriptors) {
            this.session = session;
            this.descriptors = List.copyOf(descriptors);
        }

        private void attach(
                TraceRunReplayWalker.BoundaryProbe observer,
                Object delegate,
                TraceData trace,
                TraceRunSpecialStageRows rows) {
            playbackObserver.set(observer);
            boundaryDelegate.set(delegate);
            hudSupplier.set(delegate instanceof TraceHudModel hud ? hud : null);
            cameraSupplier.set(trace::frameCount);
            fixture.get().trace = trace;
            fixture.get().rows = rows;
        }

        private void detachPayloadConsumers() {
            boundaryDelegate.set(null);
            hudSupplier.set(null);
            cameraSupplier.set(null);
            fixture.get().trace = null;
            fixture.get().rows = null;
        }
    }

    private static final class FixtureConsumer {
        private TraceData trace;
        private TraceRunSpecialStageRows rows;
    }

    private static final class RecordingSlotFactory
            implements HeadlessSlotProbeFactory {
        private final AtomicReference<RetainingSlotProbe> last =
                new AtomicReference<>();

        @Override
        public HeadlessSlotProbe create(TraceData trace, String label) {
            RetainingSlotProbe probe = new RetainingSlotProbe(trace);
            last.set(probe);
            return probe;
        }
    }

    private static final class RetainingSlotProbe implements HeadlessSlotProbe {
        private TraceData trace;

        private RetainingSlotProbe(TraceData trace) {
            this.trace = trace;
        }

        @Override
        public void observe(
                int traceFrame,
                com.openggf.level.objects.ObjectManager objectManager) {
        }

        @Override
        public void close() {
            trace = null;
        }
    }

    private static final class SpecialObserver
            implements PlaybackDebugManager.PlaybackFrameObserver {
        private TraceRunSpecialStageRowDriver driver;
        private SpecialStageRunObjectsPassBinder binder;

        private SpecialObserver(
                TraceRunSpecialStageRowDriver driver,
                SpecialStageRunObjectsPassBinder binder) {
            this.driver = driver;
            this.binder = binder;
        }

        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return false;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean skipped) {
        }

        private void detach() {
            driver = null;
            binder = null;
        }
    }
}
