package com.openggf.tests.trace.runs;

import com.openggf.TraceSessionLauncher;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceHudModel;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.live.MismatchEntry;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import com.openggf.testmode.SpecialStageTraceHudOverlay;
import com.openggf.testmode.TraceCameraFocusController;
import com.openggf.testmode.TraceHudOverlay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in fixed-cap evidence for bounded active trace-run payload ownership. */
@EnabledIfSystemProperty(
        named = "openggf.trace.activePayloadBenchmark", matches = "true")
class TestTraceRunActivePayloadPerformance extends AbstractRunChainTest {
    private static final String S3K_RUN_ID =
            "s3k-knuckles-complete-superemeralds";
    private static final long FIXED_WARMED_EAGER_BASELINE_BYTES =
            1_087_200_800L;
    private static final long MAX_DESCRIPTOR_RETAINED_BYTES = 16_777_216L;
    private static final long MAX_INSTALLED_BYTES = 268_435_456L;
    private static final double MIN_REDUCTION_PERCENT = 75.0;
    private static final Field SESSION_ENTRY_FIELD = sessionField("entry");
    private static final Field SESSION_MOVIE_FIELD = sessionField("movie");
    private static final Field SESSION_SEGMENTS_FIELD = sessionField("runSegments");
    private static final Field SESSION_PAYLOAD_FIELD =
            sessionField("activeRunPayload");
    private static final Field SESSION_FIXTURE_FIELD = sessionField("fixture");
    private static final Field SESSION_COMPARATOR_FIELD = sessionField("comparator");
    private static final Field SESSION_STRUCTURAL_FIELD =
            sessionField("runStructuralComparator");
    private static final Field SESSION_BOUNDARY_FIELD =
            sessionField("runBoundaryProbe");
    private static final Field SESSION_SPECIAL_ROWS_FIELD =
            sessionField("runSpecialRows");
    private static final Field SESSION_SPECIAL_DRIVER_FIELD =
            sessionField("runSpecialRowDriver");
    private static final Field SESSION_PASS_BINDER_FIELD =
            sessionField("runSpecialPassBinder");
    private static final Field SESSION_DYNAMIC_ART_FIELD =
            sessionField("runSpecialDynamicArtComparison");
    private static final Field SESSION_OVERLAY_FIELD = sessionField("overlay");
    private static final Field SESSION_CAMERA_FIELD =
            sessionField("cameraFocusController");
    private static final Method SESSION_DETACH_METHOD = sessionMethod(
            "detachAndCloseRunPayload", Throwable.class);
    private static final Field HARNESS_COMPARATOR_FIELD =
            harnessField("activeHeadlessComparator");
    private static final Field HARNESS_STRUCTURAL_FIELD =
            harnessField("activeStructuralComparator");
    private static final Field HARNESS_SPECIAL_ROWS_FIELD =
            harnessField("activeSpecialRows");
    private static final Field HARNESS_SPECIAL_DRIVER_FIELD =
            harnessField("activeSpecialDriver");
    private static final Field HARNESS_PASS_BINDER_FIELD =
            harnessField("activeSpecialPassBinder");
    private static final Field HARNESS_DYNAMIC_ART_FIELD =
            harnessField("activeDynamicArtComparison");
    private static final Field HARNESS_BOUNDARY_FIELD =
            harnessField("activeBoundaryProbe");
    private static final Field HARNESS_SLOT_PROBE_FIELD =
            harnessField("slotOccupancyProbe");
    private static final Field LIVE_FIXTURE_MOVIE_FIELD =
            liveFixtureField("movie");

    @Test
    void installedRootModelIncludesTheRealPreparedLaunchGraph() {
        Set<String> required = Set.of(
                "session", "entry", "movie", "preparedRuns",
                "harnessOwner", "fixture");
        List<String> actual = Arrays.stream(InstalledRoots.class.getDeclaredFields())
                .filter(field -> required.contains(field.getName()))
                .map(field -> field.getName() + ":" + field.getType().getName())
                .sorted()
                .toList();

        assertEquals(List.of(
                "entry:" + TraceEntry.class.getName(),
                "fixture:" + TraceReplayFixture.class.getName(),
                "harnessOwner:" + AbstractRunChainTest.class.getName(),
                "movie:" + Bk2Movie.class.getName(),
                "preparedRuns:com.openggf.tests.trace.runs."
                        + "TestTraceRunActivePayloadPerformance$PreparedRunSet",
                "session:" + TraceSessionLauncher.class.getName()), actual);
    }

    @Test
    void allSegmentsStayWithinFixedDescriptorAndInstalledCaps()
            throws Exception {
        Path projectRoot = Path.of(System.getProperty("project.basedir", "."))
                .toAbsolutePath().normalize();
        List<RunSource> sources = discoverSources(projectRoot);
        assertEquals(67, sources.getFirst().manifest().segments().size(),
                "benchmark must keep sampling the reviewed 67-segment run");

        warmAndReleasePlanners(sources);
        DescriptorMeasurement descriptor = measureDescriptors(sources);
        forcedGcHeapBytes();
        int fdBefore = linuxOpenFileDescriptorCount();
        InstalledMeasurement installed = measureInstalled(sources);
        forcedGcHeapBytes();
        int fdAfter = linuxOpenFileDescriptorCount();

        double reductionPercent = 100.0 * (1.0
                - installed.maxBytes()
                        / (double) FIXED_WARMED_EAGER_BASELINE_BYTES);
        System.out.printf(
                "TRACE_ACTIVE_PAYLOAD_BENCH eager_retained_bytes=%d "
                        + "descriptor_retained_bytes=%d max_installed_bytes=%d "
                        + "reduction_percent=%.2f max_segment=%s%n",
                FIXED_WARMED_EAGER_BASELINE_BYTES, descriptor.retainedBytes(),
                installed.maxBytes(), reductionPercent, installed.maxSegment());

        assertEquals(67, descriptor.s3kSegmentCount());
        assertTrue(descriptor.retainedBytes() <= MAX_DESCRIPTOR_RETAINED_BYTES,
                () -> "descriptor retained heap " + descriptor.retainedBytes()
                        + " exceeds " + MAX_DESCRIPTOR_RETAINED_BYTES);
        assertTrue(installed.maxBytes() <= MAX_INSTALLED_BYTES,
                () -> "installed retained heap " + installed.maxBytes()
                        + " exceeds " + MAX_INSTALLED_BYTES + " at "
                        + installed.maxSegment());
        assertTrue(reductionPercent >= MIN_REDUCTION_PERCENT,
                () -> "installed reduction " + reductionPercent
                        + "% is below " + MIN_REDUCTION_PERCENT + "%");
        assertTrue(installed.sampledS1Special(),
                "the installed samples must include a real S1 special driver");
        assertTrue(installed.sampledS2Special(),
                "the installed samples must include a real S2 special driver");
        assertTrue(installed.sampledPassBinder(),
                "the S2 sample must retain a real recorded pass binder");
        if (fdBefore >= 0 && fdAfter >= 0) {
            assertTrue(fdAfter <= fdBefore + 8,
                    () -> "Linux fd smoke check grew from " + fdBefore
                            + " to " + fdAfter);
        }
    }

    private DescriptorMeasurement measureDescriptors(List<RunSource> sources)
            throws IOException {
        long baseline = forcedGcHeapBytes();
        DescriptorSet descriptors = planDescriptorSet(sources);
        long retained = forcedGcHeapBytes() - baseline;
        DescriptorMeasurement result = new DescriptorMeasurement(
                descriptors.s3k().size(), retained);
        descriptors.fence();
        Reference.reachabilityFence(sources);
        return result;
    }

    private InstalledMeasurement measureInstalled(List<RunSource> sources)
            throws Exception {
        long baseline = forcedGcHeapBytes();
        PreparedRunSet preparedRuns = prepareRunSet(sources);
        List<SegmentTarget> targets = new ArrayList<>(69);
        for (int index = 0; index < preparedRuns.s3k().segments().size(); index++) {
            targets.add(new SegmentTarget("s3k", index,
                    preparedRuns.s3k().segments().get(index),
                    preparedRuns.s3k()));
        }
        targets.add(firstSpecial("s1", preparedRuns.s1()));
        targets.add(firstSpecial("s2", preparedRuns.s2()));

        long maxBytes = Long.MIN_VALUE;
        String maxSegment = "none";
        boolean sampledS1 = false;
        boolean sampledS2 = false;
        boolean sampledBinder = false;
        for (SegmentTarget target : targets) {
            InstalledSample sample = measureInstalledSegment(
                    target, preparedRuns, baseline);
            if (sample.retainedBytes() > maxBytes) {
                maxBytes = sample.retainedBytes();
                maxSegment = target.label();
            }
            sampledS1 |= target.game().equals("s1");
            sampledS2 |= target.game().equals("s2");
            sampledBinder |= sample.passBinderInstalled();
            forcedGcHeapBytes();
        }
        preparedRuns.fence();
        Reference.reachabilityFence(targets);
        Reference.reachabilityFence(sources);
        return new InstalledMeasurement(
                maxBytes, maxSegment, sampledS1, sampledS2, sampledBinder);
    }

    private InstalledSample measureInstalledSegment(
            SegmentTarget target,
            PreparedRunSet preparedRuns,
            long baseline) throws Exception {
        ActiveSegmentPayload lease = openHeadlessPayload(
                target.descriptor(), target.index());
        InstalledRoots roots = null;
        try {
            TraceSessionLauncher session = newBenchmarkRunSession(
                    target.run(), lease);
            TraceReplayFixture fixture = newLiveFixture(target.run().movie());
            TraceData trace = lease.trace();
            TraceRunSpecialStageRows rows = lease.specialStageRows();
            roots = rows == null
                    ? ordinaryRoots(session, fixture, lease, trace,
                            target, preparedRuns)
                    : specialRoots(session, fixture, lease, trace, rows,
                            target, preparedRuns);
            long retained = forcedGcHeapBytes() - baseline;
            roots.fenceAfterSample();
            return new InstalledSample(
                    retained, roots.passBinderInstalled);
        } finally {
            if (roots != null) {
                roots.detach();
            } else {
                Throwable failure = detachAndCloseHeadlessPayload(null);
                if (failure != null) {
                    throw new IllegalStateException(
                            "could not close benchmark harness payload", failure);
                }
            }
        }
    }

    private InstalledRoots ordinaryRoots(
            TraceSessionLauncher session,
            TraceReplayFixture fixture,
            ActiveSegmentPayload lease,
            TraceData trace,
            SegmentTarget target,
            PreparedRunSet preparedRuns) throws IllegalAccessException {
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null);
        TraceStructuralRowComparator structural =
                new TraceStructuralRowComparator(
                        trace, ToleranceConfig.DEFAULT, 0,
                        List::of, () -> null);
        TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, trace.frameCount());
        TraceRunReplayWalker.BoundaryProbe observer = boundaryProbe();
        RetainingSlotProbe slotProbe = new RetainingSlotProbe(trace);
        slotProbeFactory = (ignoredTrace, ignoredLabel) -> slotProbe;
        attachHeadlessComparator(
                observer, comparator, trace, target.index(), () -> { });
        installHeadlessPayloadAliases(
                observer, comparator, structural,
                null, null, null, dynamicArt);
        TraceCameraFocusController camera = new TraceCameraFocusController(
                comparator, () -> null, () -> null, () -> null,
                SonicConfigurationService.getInstance(), () -> false);
        TraceHudOverlay overlay = new TraceHudOverlay(
                comparator, camera::currentLabel, () -> null);
        installSessionAliases(
                session, fixture, comparator, structural, observer,
                null, null, null, dynamicArt, overlay, camera);
        InstalledRoots roots = new InstalledRoots(
                session, target.run().entry(), target.run().movie(),
                preparedRuns, this, fixture, false);
        assertInstalledOwnerGraph(
                roots, target, lease, observer, comparator, structural,
                null, null, null, dynamicArt, overlay, camera, slotProbe);
        return roots;
    }

    private InstalledRoots specialRoots(
            TraceSessionLauncher session,
            TraceReplayFixture fixture,
            ActiveSegmentPayload lease,
            TraceData trace,
            TraceRunSpecialStageRows rows,
            SegmentTarget target,
            PreparedRunSet preparedRuns) throws IllegalAccessException {
        TraceRunSpecialStageRowDriver driver =
                new TraceRunSpecialStageRowDriver(rows, trace);
        SpecialStageRunObjectsPassBinder binder =
                rows.newRunObjectsPassBinder().orElse(null);
        if (binder != null) {
            assertTrue(binder.hasRemaining(),
                    "the representative S2 pass binder must own recorded passes");
        }
        TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt =
                new TraceRunReplayWalker.DynamicArtSegmentComparison(
                        trace, rows.rowCount(), rows.normalizedDynamicArtRows());
        TraceRunReplayWalker.BoundaryProbe observer = boundaryProbe();
        installHeadlessPayloadAliases(
                observer, null, null, driver, rows, binder, dynamicArt);
        SpecialStageTraceHudOverlay overlay =
                new SpecialStageTraceHudOverlay(
                        new SpecialHud(driver), () -> "SPECIAL",
                        () -> null, () -> null);
        installSessionAliases(
                session, fixture, null, null, observer,
                rows, driver, binder, dynamicArt, overlay, null);
        InstalledRoots roots = new InstalledRoots(
                session, target.run().entry(), target.run().movie(),
                preparedRuns, this, fixture, binder != null);
        assertInstalledOwnerGraph(
                roots, target, lease, observer, null, null,
                rows, driver, binder, dynamicArt, overlay, null, null);
        return roots;
    }

    private static DescriptorSet planDescriptorSet(List<RunSource> sources)
            throws IOException {
        return new DescriptorSet(
                TraceRunReplayWalker.planDescriptors(
                        sources.get(0).manifest(), sources.get(0).directory()),
                TraceRunReplayWalker.planDescriptors(
                        sources.get(1).manifest(), sources.get(1).directory()),
                TraceRunReplayWalker.planDescriptors(
                        sources.get(2).manifest(), sources.get(2).directory()));
    }

    private static PreparedRunSet prepareRunSet(List<RunSource> sources)
            throws IOException {
        return new PreparedRunSet(
                prepareRun(sources.get(0)),
                prepareRun(sources.get(1)),
                prepareRun(sources.get(2)));
    }

    private static PreparedRun prepareRun(RunSource source) throws IOException {
        TraceCatalog.PreparedDescriptorRunLaunch prepared =
                TraceCatalog.prepareDescriptorRunLaunch(source.entry());
        return new PreparedRun(
                source.entry(), prepared.movie(), prepared.segments());
    }

    private static void warmAndReleasePlanners(List<RunSource> sources)
            throws IOException {
        for (RunSource source : sources) {
            List<TraceRunReplayWalker.SegmentPlan> eager =
                    TraceRunReplayWalker.plan(
                            source.manifest(), source.directory());
            Reference.reachabilityFence(eager);
            eager = null;
            forcedGcHeapBytes();
            List<TraceRunSegmentDescriptor> descriptors =
                    TraceRunReplayWalker.planDescriptors(
                            source.manifest(), source.directory());
            Reference.reachabilityFence(descriptors);
            descriptors = null;
            forcedGcHeapBytes();
        }
    }

    private static List<RunSource> discoverSources(Path projectRoot) {
        List<TraceEntry> catalog = TraceCatalog.scan(
                projectRoot.resolve("src/test/resources/traces"));
        return List.of(
                source(catalog, "s3k", S3K_RUN_ID),
                source(catalog, "s1", "s1-ghz-maze-roundtrip"),
                source(catalog, "s2", "s2-ehz-halfpipe-roundtrip"));
    }

    private static RunSource source(
            List<TraceEntry> catalog, String game, String runId) {
        return catalog.stream()
                .filter(TraceEntry::isRun)
                .filter(entry -> game.equals(entry.gameId()))
                .filter(entry -> runId.equals(entry.runManifest().runId()))
                .findFirst()
                .map(RunSource::new)
                .orElseThrow(() -> new IllegalStateException(
                        "catalog did not discover run " + game + "/" + runId));
    }

    private static SegmentTarget firstSpecial(
            String game, PreparedRun run) {
        List<TraceRunSegmentDescriptor> descriptors = run.segments();
        for (int index = 0; index < descriptors.size(); index++) {
            if ("special_stage".equals(
                    descriptors.get(index).segment().kind())) {
                return new SegmentTarget(game, index,
                        descriptors.get(index), run);
            }
        }
        throw new IllegalStateException("no representative special segment for " + game);
    }

    private static TraceSessionLauncher newBenchmarkRunSession(
            PreparedRun run, ActiveSegmentPayload lease) throws Exception {
        var constructor = TraceSessionLauncher.class.getDeclaredConstructor(
                TraceEntry.class, Bk2Movie.class, List.class,
                ActiveSegmentPayload.class,
                TraceReplaySessionBootstrap.ConfigSnapshot.class);
        constructor.setAccessible(true);
        TraceSessionLauncher session = constructor.newInstance(
                run.entry(), run.movie(), run.segments(), null, null);
        SESSION_PAYLOAD_FIELD.set(session, lease);
        return session;
    }

    private static TraceReplayFixture newLiveFixture(Bk2Movie movie) {
        try {
            var constructor = LiveEngineFixture.class
                    .getDeclaredConstructor(Bk2Movie.class);
            constructor.setAccessible(true);
            return constructor.newInstance(movie);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(
                    "could not construct the real headless run fixture", failure);
        }
    }

    private static void installSessionAliases(
            TraceSessionLauncher session,
            TraceReplayFixture fixture,
            LiveTraceComparator comparator,
            TraceStructuralRowComparator structural,
            TraceRunReplayWalker.BoundaryProbe boundary,
            TraceRunSpecialStageRows rows,
            TraceRunSpecialStageRowDriver driver,
            SpecialStageRunObjectsPassBinder binder,
            TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt,
            Object overlay,
            TraceCameraFocusController camera) throws IllegalAccessException {
        SESSION_FIXTURE_FIELD.set(session, fixture);
        SESSION_COMPARATOR_FIELD.set(session, comparator);
        SESSION_STRUCTURAL_FIELD.set(session, structural);
        SESSION_BOUNDARY_FIELD.set(session, boundary);
        SESSION_SPECIAL_ROWS_FIELD.set(session, rows);
        SESSION_SPECIAL_DRIVER_FIELD.set(session, driver);
        SESSION_PASS_BINDER_FIELD.set(session, binder);
        SESSION_DYNAMIC_ART_FIELD.set(session, dynamicArt);
        SESSION_OVERLAY_FIELD.set(session, overlay);
        SESSION_CAMERA_FIELD.set(session, camera);
    }

    private static void assertInstalledOwnerGraph(
            InstalledRoots roots,
            SegmentTarget target,
            ActiveSegmentPayload lease,
            TraceRunReplayWalker.BoundaryProbe boundary,
            LiveTraceComparator comparator,
            TraceStructuralRowComparator structural,
            TraceRunSpecialStageRows rows,
            TraceRunSpecialStageRowDriver driver,
            SpecialStageRunObjectsPassBinder binder,
            TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt,
            Object overlay,
            TraceCameraFocusController camera,
            RetainingSlotProbe slotProbe) throws IllegalAccessException {
        assertSame(target.run().entry(), roots.entry);
        assertSame(target.run().movie(), roots.movie);
        assertSame(target.run().entry(), SESSION_ENTRY_FIELD.get(roots.session));
        assertSame(target.run().movie(), SESSION_MOVIE_FIELD.get(roots.session));
        assertEquals(target.run().segments(),
                SESSION_SEGMENTS_FIELD.get(roots.session));
        assertSame(lease, SESSION_PAYLOAD_FIELD.get(roots.session));
        assertSame(roots.fixture, SESSION_FIXTURE_FIELD.get(roots.session));
        assertSame(target.run().movie(),
                LIVE_FIXTURE_MOVIE_FIELD.get(roots.fixture));
        assertSame(comparator, SESSION_COMPARATOR_FIELD.get(roots.session));
        assertSame(structural, SESSION_STRUCTURAL_FIELD.get(roots.session));
        assertSame(boundary, SESSION_BOUNDARY_FIELD.get(roots.session));
        assertSame(rows, SESSION_SPECIAL_ROWS_FIELD.get(roots.session));
        assertSame(driver, SESSION_SPECIAL_DRIVER_FIELD.get(roots.session));
        assertSame(binder, SESSION_PASS_BINDER_FIELD.get(roots.session));
        assertSame(dynamicArt, SESSION_DYNAMIC_ART_FIELD.get(roots.session));
        assertSame(overlay, SESSION_OVERLAY_FIELD.get(roots.session));
        assertSame(camera, SESSION_CAMERA_FIELD.get(roots.session));
        assertSame(comparator,
                HARNESS_COMPARATOR_FIELD.get(roots.harnessOwner));
        assertSame(structural,
                HARNESS_STRUCTURAL_FIELD.get(roots.harnessOwner));
        assertSame(rows,
                HARNESS_SPECIAL_ROWS_FIELD.get(roots.harnessOwner));
        assertSame(driver,
                HARNESS_SPECIAL_DRIVER_FIELD.get(roots.harnessOwner));
        assertSame(binder,
                HARNESS_PASS_BINDER_FIELD.get(roots.harnessOwner));
        assertSame(dynamicArt,
                HARNESS_DYNAMIC_ART_FIELD.get(roots.harnessOwner));
        assertSame(boundary,
                HARNESS_BOUNDARY_FIELD.get(roots.harnessOwner));
        assertSame(slotProbe,
                HARNESS_SLOT_PROBE_FIELD.get(roots.harnessOwner));
    }

    private static Field sessionField(String name) {
        try {
            Field field = TraceSessionLauncher.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Field harnessField(String name) {
        try {
            Field field = AbstractRunChainTest.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Field liveFixtureField(String name) {
        try {
            Field field = LiveEngineFixture.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Method sessionMethod(String name, Class<?>... parameterTypes) {
        try {
            Method method = TraceSessionLauncher.class.getDeclaredMethod(
                    name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
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

    private static int linuxOpenFileDescriptorCount() throws IOException {
        Path descriptors = Path.of("/proc/self/fd");
        if (!Files.isDirectory(descriptors)) {
            return -1;
        }
        try (var entries = Files.list(descriptors)) {
            return Math.toIntExact(entries.count());
        }
    }

    private static long forcedGcHeapBytes() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private record RunSource(TraceEntry entry) {
        private Path directory() {
            return entry.runDir();
        }

        private TraceRunManifest manifest() {
            return entry.runManifest();
        }
    }

    private record SegmentTarget(
            String game,
            int index,
            TraceRunSegmentDescriptor descriptor,
            PreparedRun run) {
        private String label() {
            return game + "-" + index + "-" + descriptor.segment().dir();
        }
    }

    private record DescriptorMeasurement(int s3kSegmentCount, long retainedBytes) {
    }

    private record InstalledMeasurement(
            long maxBytes,
            String maxSegment,
            boolean sampledS1Special,
            boolean sampledS2Special,
            boolean sampledPassBinder) {
    }

    private record InstalledSample(
            long retainedBytes, boolean passBinderInstalled) {
    }

    private record PreparedRun(
            TraceEntry entry,
            Bk2Movie movie,
            List<TraceRunSegmentDescriptor> segments) {
        private PreparedRun {
            segments = List.copyOf(segments);
        }

        private void fence() {
            Reference.reachabilityFence(entry);
            Reference.reachabilityFence(movie);
            Reference.reachabilityFence(segments);
            Reference.reachabilityFence(this);
        }
    }

    private record PreparedRunSet(
            PreparedRun s3k,
            PreparedRun s1,
            PreparedRun s2) {
        private void fence() {
            s3k.fence();
            s1.fence();
            s2.fence();
            Reference.reachabilityFence(this);
        }
    }

    private record DescriptorSet(
            List<TraceRunSegmentDescriptor> s3k,
            List<TraceRunSegmentDescriptor> s1,
            List<TraceRunSegmentDescriptor> s2) {
        private DescriptorSet {
            s3k = List.copyOf(s3k);
            s1 = List.copyOf(s1);
            s2 = List.copyOf(s2);
        }

        private void fence() {
            Reference.reachabilityFence(s3k);
            Reference.reachabilityFence(s1);
            Reference.reachabilityFence(s2);
            Reference.reachabilityFence(this);
        }
    }

    private static final class InstalledRoots {
        private TraceSessionLauncher session;
        private TraceEntry entry;
        private Bk2Movie movie;
        private PreparedRunSet preparedRuns;
        private AbstractRunChainTest harnessOwner;
        private TraceReplayFixture fixture;
        private final boolean passBinderInstalled;

        private InstalledRoots(
                TraceSessionLauncher session,
                TraceEntry entry,
                Bk2Movie movie,
                PreparedRunSet preparedRuns,
                AbstractRunChainTest harnessOwner,
                TraceReplayFixture fixture,
                boolean passBinderInstalled) {
            this.session = session;
            this.entry = entry;
            this.movie = movie;
            this.preparedRuns = preparedRuns;
            this.harnessOwner = harnessOwner;
            this.fixture = fixture;
            this.passBinderInstalled = passBinderInstalled;
        }

        private void fenceAfterSample() {
            Reference.reachabilityFence(session);
            Reference.reachabilityFence(entry);
            Reference.reachabilityFence(movie);
            Reference.reachabilityFence(preparedRuns);
            Reference.reachabilityFence(harnessOwner);
            Reference.reachabilityFence(fixture);
            Reference.reachabilityFence(this);
        }

        private void detach() {
            Throwable failure = null;
            if (session != null) {
                try {
                    failure = (Throwable) SESSION_DETACH_METHOD.invoke(
                            session, failure);
                } catch (IllegalAccessException reflectionFailure) {
                    failure = reflectionFailure;
                } catch (InvocationTargetException reflectionFailure) {
                    failure = reflectionFailure.getCause();
                }
            }
            if (harnessOwner != null) {
                failure = harnessOwner.detachAndCloseHeadlessPayload(failure);
            }
            session = null;
            entry = null;
            movie = null;
            preparedRuns = null;
            harnessOwner = null;
            fixture = null;
            if (failure != null) {
                throw new AssertionError(
                        "could not detach installed benchmark graph", failure);
            }
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

    private static final class SpecialHud implements TraceHudModel {
        private final TraceRunSpecialStageRowDriver driver;

        private SpecialHud(TraceRunSpecialStageRowDriver driver) {
            this.driver = driver;
        }

        @Override public int errorCount() { return 0; }
        @Override public int warningCount() { return 0; }
        @Override public int laggedFrames() { return 0; }
        @Override public int recentActionMask() { return 0; }
        @Override public int recentInputMask() { return 0; }
        @Override public boolean recentStartPressed() { return false; }
        @Override public List<MismatchEntry> recentMismatches() { return List.of(); }
        @Override public boolean hasRecordingDesync() { return false; }
        @Override public boolean isComplete() { return driver.isComplete(); }
    }
}
