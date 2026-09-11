package com.openggf.game.rewind;

import com.openggf.tests.TestSessionOutputPaths;

import com.openggf.audio.AudioBenchmarkMemoryProbe;
import com.openggf.audio.AudioManager;
import com.openggf.audio.rewind.AudioKeyframeStore;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless benchmark harness for the rewind framework.
 *
 * <p>Runs a real reference trace through the engine in two passes
 * (framework off / framework on) and reports per-phase timing
 * characteristics. Excluded from default CI via the
 * {@code openggf.rewind.benchmark.run} system property — opt in with
 * {@code mvn test "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true"}.
 */
@RequiresRom(SonicGame.SONIC_2)
@EnabledIfSystemProperty(named = "openggf.rewind.benchmark.run", matches = "true")
public class RewindBenchmark {

    private static final ClassValue<StructuralSizePlan> STRUCTURAL_SIZE_PLANS =
            new ClassValue<>() {
                @Override
                protected StructuralSizePlan computeValue(Class<?> type) {
                    return createStructuralSizePlan(type);
                }
            };

    private static final Path TRACE_DIR =
            Path.of("src/test/resources/traces/s2/ehz1_fullrun");
    private static final int DEFAULT_KEYFRAME_INTERVAL = 60;
    private static final long MAX_CAPTURE_MEAN_NS = 1_000_000L;
    private static final long MAX_RESTORE_MEAN_NS = 1_500_000L;
    private static final long MAX_SEEK_MEAN_NS = 10_000_000L;
    private static final long MAX_BYTES_PER_KEYFRAME = 128L * 1024L;
    private static final long MIN_LONGTAIL_CLEAN_FRAMES = 1200L;
    private static final long DEFAULT_MAX_AUDIO_CAPTURE_MEAN_NS = 250_000L;
    private static final long DEFAULT_MAX_AUDIO_RESTORE_MEAN_NS = 250_000L;
    private static final long DEFAULT_MAX_AUDIO_REPLAY_MEAN_NS = 2_000_000L;
    private static final long DEFAULT_MAX_AUDIO_ALLOCATED_BYTES = 512L * 1024L;

    /** Aggregate phase results, dumped to JSON at end of test. */
    private final Map<String, BenchmarkResults> results = new LinkedHashMap<>();

    /** BK2 movie loaded once per benchmark run for random-access input replay. */
    private Bk2Movie movie;

    @AfterEach
    void cleanup() {
        TestEnvironment.resetAll();
    }

    @Test
    public void runBenchmark() throws IOException {
        Assumptions.assumeTrue(Files.isDirectory(TRACE_DIR),
                "Trace directory not found: " + TRACE_DIR);

        // Load BK2 once for random-access input replay across phases.
        movie = loadMovie();

        // Phase 1: forward overhead (framework off + framework on)
        runPhase1ForwardOverhead();

        // Phase 2: capture cost
        runPhase2CaptureCost();

        // Phase 3: restore cost
        runPhase3RestoreCost();

        // Phase 4: cold seek
        runPhase4ColdSeek();

        // Phase 5: hot seek
        runPhase5HotSeek();

        // Phase 6: memory
        runPhase6Memory();

        // Long-tail determinism gate
        runLongTailDeterminismGate();

        // Audio logical rewind phases and counters
        runPhase7AudioRewind();

        // Conservative guardrails. These are intentionally loose enough for
        // noisy developer machines, but tight enough to catch order-of-magnitude
        // regressions and accidental full-level keyframe copies.
        assertBenchmarkBounds();

        // JSON output
        dumpJson();

        // Print human-readable stdout summary
        for (var e : results.entrySet()) {
            System.out.printf("Phase: %s%n", e.getKey());
            BenchmarkResults r = e.getValue();
            BenchmarkResults.PhaseStats s = r.overall();
            if (e.getKey().equals("phase6.memory")) {
                System.out.printf(
                        "  storedKeyframes=%d bytesPerStoredKeyframe=%d retainedResidentBytes=%d wall=%dns%n",
                        s.sampleCount(), s.meanNs(), s.maxNs(),
                        r.totalWallTimeNs());
            } else if (e.getKey().equals("longtail.determinism")) {
                System.out.printf(
                        "  longestCleanRewindFrames=%d wall=%dns%n",
                        s.meanNs(), r.totalWallTimeNs());
            } else {
                System.out.printf(
                        "  samples=%d mean=%dns p50=%dns p95=%dns p99=%dns max=%dns wall=%dns%n",
                        s.sampleCount(), s.meanNs(), s.p50Ns(), s.p95Ns(), s.p99Ns(), s.maxNs(),
                        r.totalWallTimeNs());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Phase 1: forward playback overhead
    // -------------------------------------------------------------------------

    private void runPhase1ForwardOverhead() throws IOException {
        // Pass 1: framework OFF. Drive engine via fixture.stepFrameFromRecording().
        BenchmarkTiming offTiming = new BenchmarkTiming(20_000);
        long offWallStart = System.nanoTime();
        HeadlessTestFixture fixture1 = buildFixture();
        try {
            int frameCount = Math.min(2000, fixture1.runner().getRecordingFramesRemaining());
            for (int i = 0; i < frameCount; i++) {
                long t0 = System.nanoTime();
                fixture1.stepFrameFromRecording();
                offTiming.record(System.nanoTime() - t0);
            }
        } finally {
            TestEnvironment.resetAll();
        }
        long offWall = System.nanoTime() - offWallStart;

        // Pass 2: framework ON. Drive engine via PlaybackController.tick().
        BenchmarkTiming onTiming = new BenchmarkTiming(20_000);
        long onWallStart = System.nanoTime();
        HeadlessTestFixture fixture2 = buildFixture();
        try {
            GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
            var inputs = new TraceFixtureInputSource(movie);
            var stepper = new TraceFixtureEngineStepper(fixture2);
            gameplayMode.installPlaybackController(inputs, stepper, keyframeInterval());
            int frameCount = Math.min(2000, fixture2.runner().getRecordingFramesRemaining());
            for (int i = 0; i < frameCount; i++) {
                long t0 = System.nanoTime();
                gameplayMode.getPlaybackController().tick();
                onTiming.record(System.nanoTime() - t0);
            }
        } finally {
            TestEnvironment.resetAll();
        }
        long onWall = System.nanoTime() - onWallStart;

        results.put("phase1.forward.off",
                new BenchmarkResults("phase1.forward.off",
                        offTiming.summarize(), offWall, Map.of()));
        results.put("phase1.forward.on",
                new BenchmarkResults("phase1.forward.on",
                        onTiming.summarize(), onWall, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Phase 2: capture cost
    // -------------------------------------------------------------------------

    private void runPhase2CaptureCost() throws IOException {
        BenchmarkTiming overallTiming = new BenchmarkTiming(1000);
        long wallStart = System.nanoTime();
        HeadlessTestFixture fixture = buildFixture();
        try {
            // Advance to a representative state
            int warmup = Math.min(600, fixture.runner().getRecordingFramesRemaining());
            for (int i = 0; i < warmup; i++) fixture.stepFrameFromRecording();
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var registry = gm.getRewindRegistry();

            // For 1000 iterations, capture + discard
            for (int i = 0; i < 1000; i++) {
                long t0 = System.nanoTime();
                registry.capture();
                overallTiming.record(System.nanoTime() - t0);
            }
        } finally {
            TestEnvironment.resetAll();
        }
        long wall = System.nanoTime() - wallStart;
        results.put("phase2.capture",
                new BenchmarkResults("phase2.capture",
                        overallTiming.summarize(), wall, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Phase 3: restore cost
    // -------------------------------------------------------------------------

    private void runPhase3RestoreCost() throws IOException {
        BenchmarkTiming overallTiming = new BenchmarkTiming(500);
        long wallStart = System.nanoTime();
        HeadlessTestFixture fixture = buildFixture();
        try {
            int warmup = Math.min(600, fixture.runner().getRecordingFramesRemaining());
            for (int i = 0; i < warmup; i++) fixture.stepFrameFromRecording();
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var registry = gm.getRewindRegistry();

            // Capture once, then time repeated restores from that snapshot.
            CompositeSnapshot baseline = registry.capture();
            for (int i = 0; i < 500; i++) {
                long t0 = System.nanoTime();
                registry.restore(baseline);
                overallTiming.record(System.nanoTime() - t0);
            }
        } finally {
            TestEnvironment.resetAll();
        }
        long wall = System.nanoTime() - wallStart;
        results.put("phase3.restore",
                new BenchmarkResults("phase3.restore",
                        overallTiming.summarize(), wall, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Phase 4: cold seek
    // -------------------------------------------------------------------------

    private void runPhase4ColdSeek() throws IOException {
        BenchmarkTiming overall = new BenchmarkTiming(50);
        long wallStart = System.nanoTime();
        HeadlessTestFixture fixture = buildFixture();
        try {
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var inputs = new TraceFixtureInputSource(movie);
            var stepper = new TraceFixtureEngineStepper(fixture);
            gm.installPlaybackController(inputs, stepper, keyframeInterval());
            var rc = gm.getRewindController();

            // Step forward to populate keyframes
            int stepCount = Math.min(1200, fixture.runner().getRecordingFramesRemaining());
            for (int i = 0; i < stepCount; i++) rc.step();

            // Cold seek: each seekTo invalidates the segment cache internally
            int[] targets = {60, 240, 540, 720, 900, 1080};
            // Iterate 8x to get enough samples per target.
            for (int rep = 0; rep < 8; rep++) {
                for (int target : targets) {
                    long t0 = System.nanoTime();
                    rc.seekTo(target);
                    overall.record(System.nanoTime() - t0);
                }
            }
        } finally {
            TestEnvironment.resetAll();
        }
        long wall = System.nanoTime() - wallStart;
        results.put("phase4.cold-seek",
                new BenchmarkResults("phase4.cold-seek",
                        overall.summarize(), wall, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Phase 5: hot seek
    // -------------------------------------------------------------------------

    private void runPhase5HotSeek() throws IOException {
        BenchmarkTiming withinSegment = new BenchmarkTiming(500);
        BenchmarkTiming acrossSegment = new BenchmarkTiming(50);
        long wallStart = System.nanoTime();
        HeadlessTestFixture fixture = buildFixture();
        try {
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var inputs = new TraceFixtureInputSource(movie);
            var stepper = new TraceFixtureEngineStepper(fixture);
            gm.installPlaybackController(inputs, stepper, keyframeInterval());
            var rc = gm.getRewindController();

            // Step forward 1200 frames so we have plenty of segments.
            int stepCount = Math.min(1200, fixture.runner().getRecordingFramesRemaining());
            for (int i = 0; i < stepCount; i++) rc.step();

            // Hot scrub: stepBackward 30 times within the same segment
            for (int i = 0; i < 30; i++) {
                long t0 = System.nanoTime();
                rc.stepBackward();
                withinSegment.record(System.nanoTime() - t0);
            }
            // Crossing a segment boundary
            long t0 = System.nanoTime();
            rc.stepBackward();
            acrossSegment.record(System.nanoTime() - t0);
            // Continue within previous segment
            for (int i = 0; i < 29; i++) {
                long t1 = System.nanoTime();
                rc.stepBackward();
                withinSegment.record(System.nanoTime() - t1);
            }
            // Cross another boundary
            long t2 = System.nanoTime();
            rc.stepBackward();
            acrossSegment.record(System.nanoTime() - t2);
        } finally {
            TestEnvironment.resetAll();
        }
        long wall = System.nanoTime() - wallStart;
        results.put("phase5.hot-seek.within-segment",
                new BenchmarkResults("phase5.hot-seek.within-segment",
                        withinSegment.summarize(), wall, Map.of()));
        results.put("phase5.hot-seek.across-segment",
                new BenchmarkResults("phase5.hot-seek.across-segment",
                        acrossSegment.summarize(), wall, Map.of()));
    }

    // -------------------------------------------------------------------------
    // Phase 6: memory measurement
    // -------------------------------------------------------------------------

    private void runPhase6Memory() throws IOException {
        long wallStart = System.nanoTime();
        Map<String, Long> retainedSubsystemBytes = new LinkedHashMap<>();
        int frameCount = 0;
        int storedKeyframeCount = 0;
        long retainedBytes = 0L;
        HeadlessTestFixture fixture = buildFixture();
        try {
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var inputs = new TraceFixtureInputSource(movie);
            var stepper = new TraceFixtureEngineStepper(fixture);
            gm.installPlaybackController(inputs, stepper, keyframeInterval());
            var rc = gm.getRewindController();
            var registry = gm.getRewindRegistry();
            IdentityHashMap<Object, Boolean> retainedSeen = new IdentityHashMap<>();

            int stepCount = Math.min(1200, fixture.runner().getRecordingFramesRemaining());
            retainedBytes += measureRetainedKeyframe(
                    registry.capture(), retainedSeen, retainedSubsystemBytes);
            storedKeyframeCount++;

            for (int i = 0; i < stepCount; i++) {
                rc.step();
                int frame = i + 1;
                if (frame % keyframeInterval() == 0) {
                    retainedBytes += measureRetainedKeyframe(
                            registry.capture(), retainedSeen, retainedSubsystemBytes);
                    storedKeyframeCount++;
                }
            }
            frameCount = stepCount;
        } finally {
            TestEnvironment.resetAll();
        }
        long wall = System.nanoTime() - wallStart;
        long intervalKeyframes = frameCount / keyframeInterval();
        long bytesPerStoredKeyframe = storedKeyframeCount == 0
                ? 0L
                : retainedBytes / storedKeyframeCount;

        System.out.printf("Phase 6 memory:%n" +
                        "  retainedFrames=%d intervalKeyframes=%d storedKeyframesIncludingInitial=%d%n" +
                        "  retainedResidentBytes=%d bytesPerStoredKeyframe=%d%n",
                frameCount, intervalKeyframes, storedKeyframeCount,
                retainedBytes, bytesPerStoredKeyframe);
        for (var e : retainedSubsystemBytes.entrySet()) {
            System.out.printf("    %-30s %10d bytes%s%n",
                    e.getKey(), e.getValue(),
                    e.getValue() < 0 ? " (unknown)" : "");
        }
        System.out.printf("  retainedResidentMB=%.2f%n",
                retainedBytes / 1_048_576.0);

        // Store the per-key bytes as PhaseStats with bytes-as-meanNs (semantic
        // hack to fit the JSON output schema)
        Map<String, BenchmarkResults.PhaseStats> perKey = new LinkedHashMap<>();
        for (var e : retainedSubsystemBytes.entrySet()) {
            long bytes = e.getValue();
            perKey.put(e.getKey(),
                    new BenchmarkResults.PhaseStats(1, bytes, bytes, bytes, bytes, bytes));
        }
        results.put("phase6.memory",
                new BenchmarkResults("phase6.memory",
                        new BenchmarkResults.PhaseStats(storedKeyframeCount, bytesPerStoredKeyframe,
                                retainedBytes, retainedBytes, retainedBytes,
                                retainedBytes),
                        wall, perKey));
    }

    private static long measureRetainedKeyframe(
            CompositeSnapshot snapshot,
            IdentityHashMap<Object, Boolean> retainedSeen,
            Map<String, Long> retainedSubsystemBytes) {
        long containerBytes = estimateCompositeContainerSizeShared(snapshot, retainedSeen);
        retainedSubsystemBytes.merge("composite-container", containerBytes, Long::sum);
        long keyframeBytes = containerBytes;
        for (int i = 0; i < snapshot.size(); i++) {
            long bytes = estimateStructuralSizeShared(snapshot.valueAt(i), retainedSeen);
            retainedSubsystemBytes.merge(snapshot.keyAt(i), bytes, Long::sum);
            keyframeBytes += bytes;
        }
        return keyframeBytes;
    }

    // -------------------------------------------------------------------------
    // Phase 7: audio logical rewind
    // -------------------------------------------------------------------------

    private void runPhase7AudioRewind() {
        AudioManager audio = AudioManager.getInstance();
        audio.resetState();
        AudioPhaseFixture fixture = buildAudioPhaseFixture(audio);

        BenchmarkTiming captureTiming = new BenchmarkTiming(500);
        long captureWallStart = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            long t0 = System.nanoTime();
            audio.captureLogicalSnapshot();
            captureTiming.record(System.nanoTime() - t0);
        }
        long captureWall = System.nanoTime() - captureWallStart;
        results.put("phase7.audio.capture-logical",
                new BenchmarkResults("phase7.audio.capture-logical",
                        captureTiming.summarize(), captureWall, Map.of(),
                        Map.of("timelineEntries", fixture.timelineEntries(),
                                "targetFrame", fixture.targetFrame())));

        BenchmarkTiming restoreTiming = new BenchmarkTiming(500);
        long restoreWallStart = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            long t0 = System.nanoTime();
            audio.restoreLogicalSnapshot(fixture.snapshot());
            restoreTiming.record(System.nanoTime() - t0);
        }
        long restoreWall = System.nanoTime() - restoreWallStart;
        results.put("phase7.audio.restore-logical",
                new BenchmarkResults("phase7.audio.restore-logical",
                        restoreTiming.summarize(), restoreWall, Map.of(),
                        Map.of("timelineEntries", fixture.timelineEntries(),
                                "targetFrame", fixture.targetFrame())));

        BenchmarkTiming replayTiming = new BenchmarkTiming(500);
        int[] replayedCommands = {0};
        AudioBenchmarkMemoryProbe.RunResult memory = AudioBenchmarkMemoryProbe.create().measureTimedRun(() -> {
            for (int i = 0; i < 500; i++) {
                long t0 = System.nanoTime();
                replayedCommands[0] = fixture.keyframes().replayToLogicalState(audio, fixture.targetFrame());
                replayTiming.record(System.nanoTime() - t0);
            }
        });
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("targetFrame", fixture.targetFrame());
        counters.put("timelineEntries", fixture.timelineEntries());
        counters.put("replayedCommands", (long) replayedCommands[0]);
        counters.put("allocatedBytes", memory.allocatedBytes());
        counters.put("allocatedBytesSupported", memory.allocatedBytesSupported() ? 1L : 0L);
        counters.put("heapUsedDeltaBytes", memory.heapUsedDeltaBytes());
        counters.put("gcCountDelta", memory.gcCountDelta());
        counters.put("gcTimeDeltaMs", memory.gcTimeDeltaMs());
        results.put("phase7.audio.replay-logical",
                new BenchmarkResults("phase7.audio.replay-logical",
                        replayTiming.summarize(), memory.elapsedNanos(), Map.of(), counters));

        audio.resetState();
    }

    private static AudioPhaseFixture buildAudioPhaseFixture(AudioManager audio) {
        audio.beginCommandTimelineFrame(0);
        audio.playMusic(1);
        audio.playSfx("benchmark-start");

        AudioKeyframeStore keyframes = new AudioKeyframeStore();
        keyframes.capture(0, audio);
        for (int frame = 1; frame <= 120; frame++) {
            audio.beginCommandTimelineFrame(frame);
            if (frame % 2 == 0) {
                audio.playSfx("benchmark-sfx");
            }
            if (frame % 30 == 0) {
                audio.setSpeedShoes(frame % 60 == 0);
            }
            if (frame % 45 == 0) {
                audio.setSpeedMultiplier(frame % 90 == 0 ? 2 : 1);
            }
        }
        return new AudioPhaseFixture(
                audio.captureLogicalSnapshot(),
                keyframes,
                120,
                audio.commandTimeline().entries().size());
    }

    private record AudioPhaseFixture(
            com.openggf.audio.rewind.AudioLogicalSnapshot snapshot,
            AudioKeyframeStore keyframes,
            long targetFrame,
            long timelineEntries) {
    }

    static long estimateStructuralSize(Object obj) {
        return align8(estimateStructuralSize(obj, new IdentityHashMap<>()));
    }

    static long estimateStructuralSizeShared(Object obj, IdentityHashMap<Object, Boolean> seen) {
        return align8(estimateStructuralSize(obj, seen));
    }

    static int keyframeInterval() {
        return Math.max(1, Integer.getInteger(
                "openggf.rewind.benchmark.keyframeInterval",
                DEFAULT_KEYFRAME_INTERVAL));
    }

    private static long estimateStructuralSize(Object obj, IdentityHashMap<Object, Boolean> seen) {
        if (obj == null) {
            return 0L;
        }
        Class<?> cls = obj.getClass();
        if (seen.put(obj, Boolean.TRUE) != null) {
            // The owning record/array/map has already charged its reference
            // slot. A referent retained elsewhere in the same object graph
            // must therefore contribute no additional resident bytes.
            return 0L;
        }
        if (isScalar(cls)) {
            return scalarSize(obj, cls);
        }
        if (obj instanceof CompositeSnapshot snapshot) {
            long bytes = estimateCompositeContainer(snapshot, seen);
            for (int i = 0; i < snapshot.size(); i++) {
                bytes += estimateStructuralSize(snapshot.valueAt(i), seen);
            }
            return align8(bytes);
        }
        if (obj instanceof AnimatedTileChannelSnapshot snapshot
                && snapshot.compactLayoutOrNull() != null) {
            return estimateAnimatedTileChannelSnapshot(snapshot, seen);
        }
        if (obj instanceof LevelSnapshot snapshot) {
            return estimateLevelSnapshotSize(snapshot, seen);
        }
        if (cls.isArray()) {
            return estimateArray(obj, seen);
        }
        if (obj instanceof String s) {
            return align8(40L + (long) s.length() * 2L);
        }
        if (obj instanceof Class<?> c) {
            return align8(24L + (long) c.getName().length() * 2L);
        }
        if (obj instanceof Enum<?> e) {
            return align8(24L + (long) e.name().length() * 2L);
        }
        if (obj instanceof Map<?, ?> map) {
            return estimateMap(map, seen);
        }
        if (obj instanceof Collection<?> collection) {
            long bytes = 24L + (long) collection.size() * 8L;
            for (Object value : collection) {
                bytes += estimateStructuralSize(value, seen);
            }
            return align8(bytes);
        }
        if (cls.isRecord()) {
            long bytes = 16L;
            for (StructuralSizeComponent component : STRUCTURAL_SIZE_PLANS.get(cls).components()) {
                try {
                    Object value = component.accessor().invoke(obj);
                    Class<?> type = component.type();
                    bytes += type.isPrimitive() ? primitiveSize(type) : 8L;
                    if (!type.isPrimitive()) {
                        bytes += estimateStructuralSize(value, seen);
                    }
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Failed to estimate record component "
                                    + cls.getName() + "." + component.name(), e);
                }
            }
            return align8(bytes);
        }
        return 32L;
    }

    static long estimateCompositeContainerSizeShared(
            CompositeSnapshot snapshot, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(snapshot, Boolean.TRUE) != null) {
            return 0L;
        }
        return align8(estimateCompositeContainer(snapshot, seen));
    }

    private static long estimateCompositeContainer(
            CompositeSnapshot snapshot, IdentityHashMap<Object, Boolean> seen) {
        // CompositeSnapshot + its eager array-backed AbstractMap view and
        // unmodifiable wrapper. These are fixed-size and contain no per-key nodes.
        long bytes = 96L;
        bytes += align8(16L + (long) snapshot.size() * 8L);
        bytes += estimateCompositeLayout(snapshot.layout(), seen);
        return align8(bytes);
    }

    private static long estimateCompositeLayout(
            CompositeSnapshotLayout layout, IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(layout, Boolean.TRUE) != null) {
            return 0L;
        }
        int size = layout.size();
        // Layout object, ordered key array, HashMap, and one HashMap node/index
        // value per key. The layout is shared across captures and charged once.
        long bytes = 40L;
        bytes += align8(16L + (long) size * 8L);
        bytes += 48L + (long) size * 32L;
        int tableCapacity = hashTableCapacityForEntries(size);
        if (tableCapacity > 0) {
            bytes += align8(16L + (long) tableCapacity * 8L);
        }
        for (int i = 0; i < size; i++) {
            bytes += estimateStructuralSize(layout.keyAt(i), seen);
            bytes += estimateStructuralSize(Integer.valueOf(i), seen);
        }
        return align8(bytes);
    }

    private static long estimateAnimatedTileChannelSnapshot(
            AnimatedTileChannelSnapshot snapshot, IdentityHashMap<Object, Boolean> seen) {
        // Record header + public Map component reference.
        long bytes = 24L;
        Map<String, Integer> compactMap = snapshot.lastPhaseByChannel();
        if (seen.put(compactMap, Boolean.TRUE) == null) {
            // Compact AbstractMap facade and its snapshot-owned long[] payload.
            bytes += 56L;
            bytes += align8(16L + (long) snapshot.compactPayloadLength() * Long.BYTES);
        }
        bytes += estimateAnimatedTileChannelLayout(snapshot.compactLayoutOrNull(), seen);
        return align8(bytes);
    }

    private static long estimateAnimatedTileChannelLayout(
            AnimatedTileChannelSnapshot.Layout layout,
            IdentityHashMap<Object, Boolean> seen) {
        if (seen.put(layout, Boolean.TRUE) != null) {
            return 0L;
        }
        int size = layout.size();
        // Layout object, ordered key array, HashMap/table/nodes, then retained
        // key and boxed-index referents. The immutable layout is shared by all
        // captures from one graph layout generation and charged exactly once.
        long bytes = 40L;
        bytes += align8(16L + (long) size * 8L);
        bytes += 48L + (long) size * 32L;
        int tableCapacity = hashTableCapacityForEntries(size);
        if (tableCapacity > 0) {
            bytes += align8(16L + (long) tableCapacity * 8L);
        }
        for (int index = 0; index < size; index++) {
            bytes += estimateStructuralSize(layout.keyAt(index), seen);
            bytes += estimateStructuralSize(Integer.valueOf(index), seen);
        }
        return align8(bytes);
    }

    private static int hashTableCapacityForEntries(int entryCount) {
        if (entryCount == 0) {
            return 0;
        }
        int requestedCapacity = entryCount < 3
                ? entryCount + 1
                : (int) Math.ceil(entryCount / 0.75d);
        int tableCapacity = 1;
        while (tableCapacity < requestedCapacity) {
            tableCapacity <<= 1;
        }
        return tableCapacity;
    }

    private static StructuralSizePlan createStructuralSizePlan(Class<?> type) {
        var recordComponents = type.getRecordComponents();
        StructuralSizeComponent[] components =
                new StructuralSizeComponent[recordComponents.length];
        for (int i = 0; i < recordComponents.length; i++) {
            var component = recordComponents[i];
            Method accessor = component.getAccessor();
            if (!accessor.trySetAccessible()) {
                throw new IllegalStateException(
                        "Cannot access record component "
                                + type.getName() + "." + component.getName());
            }
            components[i] = new StructuralSizeComponent(
                    component.getName(), component.getType(), accessor);
        }
        return new StructuralSizePlan(components);
    }

    private record StructuralSizePlan(StructuralSizeComponent[] components) {
    }

    private record StructuralSizeComponent(String name, Class<?> type, Method accessor) {
    }

    private static long estimateLevelSnapshotSize(LevelSnapshot snapshot, IdentityHashMap<Object, Boolean> seen) {
        long bytes = 16L;
        bytes += 8L;  // epochAtCapture
        bytes += shallowArraySizeShared(snapshot.blocks(), seen);
        bytes += shallowArraySizeShared(snapshot.chunks(), seen);
        bytes += sharedReferenceSize(snapshot.mapData(), seen);  // CoW keeps the payload shared
        bytes += 4L;  // frameCounter
        bytes += 1L;  // respawnRequested
        return align8(bytes);
    }

    private static long shallowArraySizeShared(Object array, IdentityHashMap<Object, Boolean> seen) {
        if (array == null) {
            return 0L;
        }
        if (seen.put(array, Boolean.TRUE) != null) {
            return 8L;
        }
        return shallowArraySize(array);
    }

    private static long sharedReferenceSize(Object value, IdentityHashMap<Object, Boolean> seen) {
        if (value == null) {
            return 0L;
        }
        seen.put(value, Boolean.TRUE);
        return 8L;
    }

    private static long shallowArraySize(Object array) {
        if (array == null) {
            return 0L;
        }
        int len = java.lang.reflect.Array.getLength(array);
        Class<?> component = array.getClass().getComponentType();
        long elementSize = component.isPrimitive() ? primitiveSize(component) : 8L;
        return align8(16L + (long) len * elementSize);
    }

    private static long estimateArray(Object array, IdentityHashMap<Object, Boolean> seen) {
        int len = java.lang.reflect.Array.getLength(array);
        Class<?> component = array.getClass().getComponentType();
        long bytes = 16L;
        if (component.isPrimitive()) {
            bytes += (long) len * primitiveSize(component);
        } else {
            bytes += (long) len * 8L;
            for (int i = 0; i < len; i++) {
                bytes += estimateStructuralSize(java.lang.reflect.Array.get(array, i), seen);
            }
        }
        return align8(bytes);
    }

    private static long estimateMap(Map<?, ?> map, IdentityHashMap<Object, Boolean> seen) {
        long bytes = 48L + (long) map.size() * 32L;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            bytes += 16L;
            bytes += estimateStructuralSize(entry.getKey(), seen);
            bytes += estimateStructuralSize(entry.getValue(), seen);
        }
        return align8(bytes);
    }

    private static boolean isScalar(Class<?> cls) {
        return cls.isPrimitive()
                || cls == Boolean.class
                || cls == Byte.class
                || cls == Short.class
                || cls == Character.class
                || cls == Integer.class
                || cls == Float.class
                || cls == Long.class
                || cls == Double.class;
    }

    private static long scalarSize(Object value, Class<?> cls) {
        Class<?> type = cls.isPrimitive() ? cls : primitiveTypeForBox(cls);
        return align8(16L + primitiveSize(type));
    }

    private static Class<?> primitiveTypeForBox(Class<?> cls) {
        if (cls == Boolean.class) return boolean.class;
        if (cls == Byte.class) return byte.class;
        if (cls == Short.class) return short.class;
        if (cls == Character.class) return char.class;
        if (cls == Integer.class) return int.class;
        if (cls == Float.class) return float.class;
        if (cls == Long.class) return long.class;
        if (cls == Double.class) return double.class;
        return int.class;
    }

    private static long primitiveSize(Class<?> type) {
        if (type == boolean.class || type == byte.class) return 1L;
        if (type == short.class || type == char.class) return 2L;
        if (type == int.class || type == float.class) return 4L;
        if (type == long.class || type == double.class) return 8L;
        throw new IllegalArgumentException("Not a primitive type: " + type);
    }

    private static long align8(long bytes) {
        return (bytes + 7L) & ~7L;
    }

    private void assertBenchmarkBounds() {
        assertMeanAtMost("phase2.capture", MAX_CAPTURE_MEAN_NS);
        assertMeanAtMost("phase3.restore", MAX_RESTORE_MEAN_NS);
        assertMeanAtMost("phase4.cold-seek", MAX_SEEK_MEAN_NS);
        assertMeanAtMost("phase5.hot-seek.within-segment", MAX_SEEK_MEAN_NS);
        assertMeanAtMost("phase6.memory", MAX_BYTES_PER_KEYFRAME);
        BenchmarkResults longtail = results.get("longtail.determinism");
        assertTrue(longtail != null
                        && longtail.overall().meanNs() >= MIN_LONGTAIL_CLEAN_FRAMES,
                "longtail.determinism must remain clean for at least "
                        + MIN_LONGTAIL_CLEAN_FRAMES + " frames");
        assertAudioBenchmarkBounds(results);
    }

    private void assertMeanAtMost(String phase, long max) {
        BenchmarkResults result = results.get(phase);
        assertTrue(result != null, "missing benchmark phase " + phase);
        assertTrue(result.overall().meanNs() <= max,
                phase + " mean " + result.overall().meanNs() + " exceeds " + max);
    }

    static void assertAudioBenchmarkBoundsForTests(Map<String, BenchmarkResults> results) {
        assertAudioBenchmarkBounds(results);
    }

    private static void assertAudioBenchmarkBounds(Map<String, BenchmarkResults> results) {
        if (!Boolean.getBoolean("openggf.rewind.benchmark.audioBudgets")) {
            return;
        }
        assertMeanAtMost(results, "phase7.audio.capture-logical",
                Long.getLong("openggf.rewind.benchmark.audio.maxCaptureMeanNs",
                        DEFAULT_MAX_AUDIO_CAPTURE_MEAN_NS));
        assertMeanAtMost(results, "phase7.audio.restore-logical",
                Long.getLong("openggf.rewind.benchmark.audio.maxRestoreMeanNs",
                        DEFAULT_MAX_AUDIO_RESTORE_MEAN_NS));
        assertMeanAtMost(results, "phase7.audio.replay-logical",
                Long.getLong("openggf.rewind.benchmark.audio.maxReplayMeanNs",
                        DEFAULT_MAX_AUDIO_REPLAY_MEAN_NS));
        BenchmarkResults replay = results.get("phase7.audio.replay-logical");
        assertTrue(replay != null, "missing benchmark phase phase7.audio.replay-logical");
        if (replay.counters().getOrDefault("allocatedBytesSupported", 0L) == 1L) {
            long maxAllocatedBytes = Long.getLong(
                    "openggf.rewind.benchmark.audio.maxAllocatedBytes",
                    DEFAULT_MAX_AUDIO_ALLOCATED_BYTES);
            long allocatedBytes = replay.counters().getOrDefault("allocatedBytes", 0L);
            assertTrue(allocatedBytes <= maxAllocatedBytes,
                    "phase7.audio.replay-logical allocatedBytes " + allocatedBytes
                            + " exceeds " + maxAllocatedBytes);
        }
    }

    private static void assertMeanAtMost(Map<String, BenchmarkResults> results, String phase, long max) {
        BenchmarkResults result = results.get(phase);
        assertTrue(result != null, "missing benchmark phase " + phase);
        assertTrue(result.overall().meanNs() <= max,
                phase + " mean " + result.overall().meanNs() + " exceeds " + max);
    }

    // -------------------------------------------------------------------------
    // Long-tail determinism gate
    // -------------------------------------------------------------------------

    private void runLongTailDeterminismGate() throws IOException {
        long wallStart = System.nanoTime();
        int longestCleanRewind = 0;
        String firstDivergentKey = null;
        int divergenceFrame = -1;

        HeadlessTestFixture fixture = buildFixture();
        try {
            GameplayModeContext gm = SessionManager.getCurrentGameplayMode();
            var inputs = new TraceFixtureInputSource(movie);
            var stepper = new TraceFixtureEngineStepper(fixture);
            gm.installPlaybackController(inputs, stepper, keyframeInterval());
            var rc = gm.getRewindController();
            var registry = gm.getRewindRegistry();

            // Forward run: capture every-frame snapshots into a baseline list
            int targetFrame = Math.min(1200, fixture.runner().getRecordingFramesRemaining());
            java.util.List<CompositeSnapshot> baseline =
                    new java.util.ArrayList<>(targetFrame + 1);
            baseline.add(registry.capture());
            for (int i = 0; i < targetFrame; i++) {
                rc.step();
                baseline.add(registry.capture());
            }

            // Test increasing scrub distances:
            int[] scrubDistances = {60, 120, 300, 600, 900, 1200};
            for (int n : scrubDistances) {
                int origin = rc.currentFrame();
                int back = Math.max(0, origin - n);
                rc.seekTo(back);
                // Replay forward to origin, checking for divergence at EACH replay step.
                int firstDivergeFrame = -1;
                String firstDivergeKeyPerFrame = null;
                while (rc.currentFrame() < origin) {
                    rc.step();
                    if (firstDivergeFrame < 0) {
                        CompositeSnapshot midSnap = registry.capture();
                        String mid = compareForCoveredKeys(
                                baseline.get(rc.currentFrame()), midSnap);
                        if (mid != null) {
                            firstDivergeFrame = rc.currentFrame();
                            firstDivergeKeyPerFrame = mid;
                        }
                    }
                }
                if (firstDivergeFrame >= 0) {
                    System.out.printf(
                            "  per-frame: first diverge at frame %d (offset +%d from seek-target %d), key='%s'%n",
                            firstDivergeFrame, firstDivergeFrame - back, back,
                            firstDivergeKeyPerFrame);
                }
                CompositeSnapshot afterReplay = registry.capture();
                String mismatch = compareForCoveredKeys(baseline.get(origin), afterReplay);
                if (mismatch != null) {
                    firstDivergentKey = mismatch;
                    divergenceFrame = origin;
                    System.out.printf(
                            "Long-tail determinism gate: divergence after %d-frame rewind" +
                            " on key '%s' at frame %d%n", n, mismatch, origin);
                    break;
                }
                longestCleanRewind = n;
                System.out.printf("Long-tail determinism gate: clean rewind of %d frames%n", n);
            }
        } finally {
            TestEnvironment.resetAll();
        }

        long wall = System.nanoTime() - wallStart;
        System.out.printf(
                "Long-tail result: longestCleanRewind=%d frames (%.2f sec @ 60fps)%n",
                longestCleanRewind, longestCleanRewind / 60.0);
        if (firstDivergentKey != null) {
            System.out.printf("  firstDivergentKey=%s at frame %d%n",
                    firstDivergentKey, divergenceFrame);
        }

        results.put("longtail.determinism",
                new BenchmarkResults("longtail.determinism",
                        new BenchmarkResults.PhaseStats(longestCleanRewind, longestCleanRewind,
                                longestCleanRewind, longestCleanRewind, longestCleanRewind,
                                longestCleanRewind),
                        wall, Map.of()));
    }

    /**
     * Returns the first mismatching key, or null if both snapshots agree on
     * all v1.5-covered keys.
     */
    private static String compareForCoveredKeys(CompositeSnapshot a, CompositeSnapshot b) {
        String[] coveredKeys = {
                "camera", "gamestate", "gamerng", "timermanager", "fademanager",
                "oscillation", "level", "object-manager",
                "parallax", "water", "zone-runtime", "palette-ownership",
                "animated-tile-channels", "special-render", "advanced-render-mode",
                "mutation-pipeline", "solid-execution",
                "level-event", "rings", "s2-plc-art",
                "sprites"
        };
        // Diagnostic: collect ALL divergent keys, not just the first.
        java.util.List<String> divergent = new java.util.ArrayList<>();
        for (String key : coveredKeys) {
            Object av = a.get(key);
            Object bv = b.get(key);
            if (av == null && bv == null) continue;
            if (av == null || bv == null) {
                divergent.add(key + "(one-side-null)");
                continue;
            }
            if (!keyEquals(key, av, bv)) {
                divergent.add(key);
                // Print only the differing components to keep output focused.
                System.out.println("  " + key + " differs at:");
                java.util.List<String> diffs = new java.util.ArrayList<>();
                collectDiffs(key, av, bv, diffs);
                for (String d : diffs) {
                    System.out.println("    " + d);
                }
            }
        }
        if (divergent.isEmpty()) return null;
        if (divergent.size() > 1) {
            System.out.println("  Divergent keys (all): " + divergent);
        }
        return divergent.get(0);
    }

    /**
     * Per-key equality. Custom comparators for keys whose snapshot records
     * contain non-content-equal references; default uses a record-aware deep
     * equality that handles array fields via Arrays.equals (Java records
     * auto-generate .equals() with array reference equality, which makes
     * naive .equals() report false negatives for any record with array
     * fields).
     */
    private static boolean keyEquals(String key, Object a, Object b) {
        return switch (key) {
            case "level" -> compareLevel(a, b);
            case "object-manager" -> compareObjectManager(a, b);
            default -> recordsContentEqual(a, b);
        };
    }

    /**
     * Deep content-equality for arbitrary records, with proper handling for
     * primitive arrays, object arrays (deep-equals), and nested records.
     */
    private static boolean recordsContentEqual(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getClass() != b.getClass()) return false;
        Class<?> cls = a.getClass();
        if (!cls.isRecord()) return java.util.Objects.equals(a, b);
        for (StructuralSizeComponent component : STRUCTURAL_SIZE_PLANS.get(cls).components()) {
            try {
                Object av = component.accessor().invoke(a);
                Object bv = component.accessor().invoke(b);
                if (!fieldContentEqual(av, bv)) return false;
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(
                        "Failed to read record component " + component.name(), e);
            }
        }
        return true;
    }

    /**
     * Walks two values recursively and collects path-based diff strings for
     * each differing leaf. Only emits a small number of diffs (caps at 20)
     * to keep output bounded.
     */
    private static void collectDiffs(String path, Object av, Object bv,
                                     java.util.List<String> diffs) {
        if (diffs.size() >= 20) return;
        if (av == bv) return;
        if (av == null || bv == null) {
            diffs.add(path + ": A=" + av + " B=" + bv);
            return;
        }
        if (av.getClass() != bv.getClass()) {
            diffs.add(path + ": class A=" + av.getClass().getSimpleName()
                    + " B=" + bv.getClass().getSimpleName());
            return;
        }
        Class<?> cls = av.getClass();
        if (cls.isRecord()) {
            for (StructuralSizeComponent component : STRUCTURAL_SIZE_PLANS.get(cls).components()) {
                try {
                    Object aV = component.accessor().invoke(av);
                    Object bV = component.accessor().invoke(bv);
                    if (!fieldContentEqual(aV, bV)) {
                        collectDiffs(path + "." + component.name(), aV, bV, diffs);
                    }
                } catch (ReflectiveOperationException ignored) {}
            }
            return;
        }
        if (cls.isArray()) {
            // For arrays of records (e.g. List<PerSlotEntry>), walk pairwise.
            int len = java.lang.reflect.Array.getLength(av);
            int blen = java.lang.reflect.Array.getLength(bv);
            if (len != blen) {
                diffs.add(path + ": length A=" + len + " B=" + blen);
                return;
            }
            for (int i = 0; i < len; i++) {
                Object ai = java.lang.reflect.Array.get(av, i);
                Object bi = java.lang.reflect.Array.get(bv, i);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "[" + i + "]", ai, bi, diffs);
                }
            }
            return;
        }
        if (av instanceof java.util.List<?> al && bv instanceof java.util.List<?> bl) {
            if (al.size() != bl.size()) {
                diffs.add(path + ": list-length A=" + al.size() + " B=" + bl.size());
                return;
            }
            for (int i = 0; i < al.size(); i++) {
                Object ai = al.get(i);
                Object bi = bl.get(i);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "[" + i + "]", ai, bi, diffs);
                }
            }
            return;
        }
        if (av instanceof java.util.Map<?, ?> am && bv instanceof java.util.Map<?, ?> bm) {
            if (!am.keySet().equals(bm.keySet())) {
                diffs.add(path + ": map-keys A=" + am.keySet() + " B=" + bm.keySet());
                return;
            }
            for (Object key : am.keySet()) {
                Object ai = am.get(key);
                Object bi = bm.get(key);
                if (!fieldContentEqual(ai, bi)) {
                    collectDiffs(path + "." + key, ai, bi, diffs);
                }
            }
            return;
        }
        // Leaf scalar / other
        diffs.add(path + ": A=" + av + " B=" + bv);
    }

    /**
     * Renders an object's content with proper array formatting (so diff dumps
     * can be visually compared). For records, walks components and formats
     * each value via fieldContentString.
     */
    private static String recordContentString(Object o) {
        if (o == null) return "null";
        Class<?> cls = o.getClass();
        if (!cls.isRecord()) return fieldContentString(o);
        StringBuilder sb = new StringBuilder(cls.getSimpleName()).append('[');
        boolean first = true;
        for (StructuralSizeComponent component : STRUCTURAL_SIZE_PLANS.get(cls).components()) {
            try {
                Object v = component.accessor().invoke(o);
                if (!first) sb.append(", ");
                first = false;
                sb.append(component.name()).append('=').append(fieldContentString(v));
            } catch (ReflectiveOperationException ignored) {}
        }
        return sb.append(']').toString();
    }

    private static String fieldContentString(Object v) {
        if (v == null) return "null";
        Class<?> cls = v.getClass();
        if (cls.isArray()) {
            Class<?> elem = cls.getComponentType();
            if (elem == byte.class)    return Arrays.toString((byte[]) v);
            if (elem == short.class)   return Arrays.toString((short[]) v);
            if (elem == int.class)     return Arrays.toString((int[]) v);
            if (elem == long.class)    return Arrays.toString((long[]) v);
            if (elem == float.class)   return Arrays.toString((float[]) v);
            if (elem == double.class)  return Arrays.toString((double[]) v);
            if (elem == char.class)    return Arrays.toString((char[]) v);
            if (elem == boolean.class) return Arrays.toString((boolean[]) v);
            return Arrays.deepToString((Object[]) v);
        }
        if (cls.isRecord()) return recordContentString(v);
        return v.toString();
    }

    private static boolean fieldContentEqual(Object av, Object bv) {
        if (av == bv) return true;
        if (av == null || bv == null) return false;
        Class<?> cls = av.getClass();
        if (cls.isArray()) {
            Class<?> elem = cls.getComponentType();
            if (elem == byte.class)    return Arrays.equals((byte[]) av,    (byte[]) bv);
            if (elem == short.class)   return Arrays.equals((short[]) av,   (short[]) bv);
            if (elem == int.class)     return Arrays.equals((int[]) av,     (int[]) bv);
            if (elem == long.class)    return Arrays.equals((long[]) av,    (long[]) bv);
            if (elem == float.class)   return Arrays.equals((float[]) av,   (float[]) bv);
            if (elem == double.class)  return Arrays.equals((double[]) av,  (double[]) bv);
            if (elem == char.class)    return Arrays.equals((char[]) av,    (char[]) bv);
            if (elem == boolean.class) return Arrays.equals((boolean[]) av, (boolean[]) bv);
            Object[] aa = (Object[]) av;
            Object[] ba = (Object[]) bv;
            if (aa.length != ba.length) return false;
            for (int i = 0; i < aa.length; i++) {
                if (!fieldContentEqual(aa[i], ba[i])) return false;
            }
            return true;
        }
        if (cls.isRecord()) return recordsContentEqual(av, bv);
        if (av instanceof java.util.List<?> al && bv instanceof java.util.List<?> bl) {
            if (al.size() != bl.size()) return false;
            for (int i = 0; i < al.size(); i++) {
                if (!fieldContentEqual(al.get(i), bl.get(i))) return false;
            }
            return true;
        }
        if (av instanceof java.util.Map<?, ?> am && bv instanceof java.util.Map<?, ?> bm) {
            if (!am.keySet().equals(bm.keySet())) return false;
            for (Object key : am.keySet()) {
                if (!fieldContentEqual(am.get(key), bm.get(key))) return false;
            }
            return true;
        }
        return java.util.Objects.equals(av, bv);
    }

    private static boolean compareLevel(Object a, Object b) {
        if (!(a instanceof LevelSnapshot la) ||
            !(b instanceof LevelSnapshot lb)) {
            return false;
        }
        // Epoch is a restore-side copy-on-write generation counter. Multiple
        // seeks can legitimately advance it beyond the original forward run
        // while the level content remains identical.
        return Arrays.equals(la.blocks(), lb.blocks())
            && Arrays.equals(la.chunks(), lb.chunks())
            && Arrays.equals(la.mapData(), lb.mapData());
    }

    private static boolean compareObjectManager(Object a, Object b) {
        if (!(a instanceof ObjectManagerSnapshot oa) ||
            !(b instanceof ObjectManagerSnapshot ob)) {
            return false;
        }
        if (!Arrays.equals(oa.usedSlotsBits(), ob.usedSlotsBits())) return false;
        if (oa.frameCounter() != ob.frameCounter()) return false;
        if (oa.vblaCounter() != ob.vblaCounter()) return false;
        return fieldContentEqual(oa.slots(), ob.slots())
                && fieldContentEqual(oa.childSpawns(), ob.childSpawns())
                && fieldContentEqual(oa.dynamicObjects(), ob.dynamicObjects())
                && fieldContentEqual(oa.placement(), ob.placement());
    }

    // -------------------------------------------------------------------------
    // JSON output
    // -------------------------------------------------------------------------

    private void dumpJson() throws IOException {
        Path outputDir = TestSessionOutputPaths.diagnostics("rewind");
        Files.createDirectories(outputDir);
        Path output = outputDir.resolve("rewind-benchmark-results.json");

        String json = toJson(TRACE_DIR.toString(), keyframeInterval(), results);
        Files.writeString(output, json);
        System.out.printf("Benchmark results written to %s%n", output);
    }

    static String toJsonForTests(String trace, int keyframeInterval, Map<String, BenchmarkResults> results) {
        return toJson(trace, keyframeInterval, results);
    }

    private static String toJson(String trace, int keyframeInterval, Map<String, BenchmarkResults> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"trace\": \"").append(escapeJson(trace)).append("\",\n");
        sb.append("  \"keyframeInterval\": ").append(keyframeInterval).append(",\n");
        sb.append("  \"phases\": {\n");
        int i = 0;
        for (var e : results.entrySet()) {
            if (i++ > 0) sb.append(",\n");
            BenchmarkResults r = e.getValue();
            sb.append("    \"").append(escapeJson(e.getKey())).append("\": {\n");
            sb.append("      \"unit\": \"").append(phaseUnit(e.getKey())).append("\",\n");
            appendPhaseStats(sb, r.overall());
            sb.append(",\n      \"totalWallTimeNs\": ").append(r.totalWallTimeNs());
            if (!r.perSubsystem().isEmpty()) {
                sb.append(",\n      \"perSubsystem\": {\n");
                int j = 0;
                for (var ps : r.perSubsystem().entrySet()) {
                    if (j++ > 0) sb.append(",\n");
                    sb.append("        \"").append(escapeJson(ps.getKey())).append("\": {");
                    BenchmarkResults.PhaseStats s = ps.getValue();
                    sb.append(" \"sampleCount\": ").append(s.sampleCount());
                    sb.append(", \"meanNs\": ").append(s.meanNs());
                    sb.append(", \"p50Ns\": ").append(s.p50Ns());
                    sb.append(", \"p95Ns\": ").append(s.p95Ns());
                    sb.append(", \"p99Ns\": ").append(s.p99Ns());
                    sb.append(", \"maxNs\": ").append(s.maxNs());
                    sb.append(" }");
                }
                sb.append("\n      }");
            }
            if (!r.counters().isEmpty()) {
                sb.append(",\n      \"counters\": {\n");
                int j = 0;
                for (var counter : r.counters().entrySet()) {
                    if (j++ > 0) sb.append(",\n");
                    sb.append("        \"").append(escapeJson(counter.getKey())).append("\": ")
                            .append(counter.getValue());
                }
                sb.append("\n      }");
            }
            sb.append("\n    }");
        }
        sb.append("\n  }\n}\n");
        return sb.toString();
    }

    private static void appendPhaseStats(StringBuilder sb, BenchmarkResults.PhaseStats s) {
        sb.append("      \"sampleCount\": ").append(s.sampleCount()).append(",\n");
        sb.append("      \"meanNs\": ").append(s.meanNs()).append(",\n");
        sb.append("      \"p50Ns\": ").append(s.p50Ns()).append(",\n");
        sb.append("      \"p95Ns\": ").append(s.p95Ns()).append(",\n");
        sb.append("      \"p99Ns\": ").append(s.p99Ns()).append(",\n");
        sb.append("      \"maxNs\": ").append(s.maxNs());
    }

    private static String phaseUnit(String phaseName) {
        if (phaseName.equals("phase6.memory")) {
            return "bytes";
        }
        if (phaseName.equals("longtail.determinism")) {
            return "frames";
        }
        return "nanoseconds";
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private HeadlessTestFixture buildFixture() throws IOException {
        Path bk2 = findBk2(TRACE_DIR);
        return HeadlessTestFixture.builder()
                .withRecording(bk2)
                .withZoneAndAct(0, 0)
                .build();
    }

    private static Path findBk2(Path traceDir) throws IOException {
        try (var stream = Files.list(traceDir)) {
            return stream.filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No .bk2 file found in " + traceDir));
        }
    }

    // -------------------------------------------------------------------------
    // Inner adapter classes
    // -------------------------------------------------------------------------

    /**
     * EngineStepper that applies a Bk2FrameInput's recorded button state to the
     * underlying HeadlessTestRunner via stepFrame(...). Bypasses the fixture's
     * one-way BK2 cursor so seek-and-replay paths (e.g. RewindController.seekTo
     * followed by step()) drive the engine with the inputs corresponding to
     * the InputSource's reported frame, not the cursor's current position.
     */
    private static final class TraceFixtureEngineStepper implements RewindSeekAwareEngineStepper {
        private final HeadlessTestFixture fixture;

        TraceFixtureEngineStepper(HeadlessTestFixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public com.openggf.LevelFrameResult step(Bk2FrameInput inputs) {
            int p1 = inputs.p1InputMask();
            boolean up    = (p1 & AbstractPlayableSprite.INPUT_UP)    != 0;
            boolean down  = (p1 & AbstractPlayableSprite.INPUT_DOWN)  != 0;
            boolean left  = (p1 & AbstractPlayableSprite.INPUT_LEFT)  != 0;
            boolean right = (p1 & AbstractPlayableSprite.INPUT_RIGHT) != 0;
            boolean jump  = (p1 & AbstractPlayableSprite.INPUT_JUMP)  != 0;
            return fixture.runner().stepFrame(up, down, left, right, jump,
                    inputs.p2InputMask(), inputs.p2StartPressed());
        }

        @Override
        public void restoreToFrame(int frame, Bk2FrameInput inputAtFrame) {
            fixture.runner().primeInputState(inputAtFrame);
        }
    }

    /**
     * Random-access InputSource backed by a loaded Bk2Movie. read(frame) returns
     * the recorded inputs for that exact frame, so seek-and-replay paths feed
     * the engine the original recorded inputs rather than relying on the
     * fixture's one-way BK2 cursor.
     */
    private static final class TraceFixtureInputSource implements InputSource {
        private final Bk2Movie movie;
        private final int frameCount;

        TraceFixtureInputSource(Bk2Movie movie) {
            this.movie = movie;
            this.frameCount = movie.getFrames().size();
        }

        @Override
        public int frameCount() {
            return frameCount;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            if (frame < 0 || frame >= frameCount) {
                return new Bk2FrameInput(frame, 0, 0, false, "benchmark:oor:" + frame);
            }
            return movie.getFrame(frame);
        }
    }

    /** Loads the BK2 movie for this benchmark's trace once. */
    private Bk2Movie loadMovie() throws IOException {
        Path bk2 = findBk2(TRACE_DIR);
        return new Bk2MovieLoader().load(bk2);
    }

}
