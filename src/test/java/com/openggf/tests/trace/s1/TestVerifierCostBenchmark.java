package com.openggf.tests.trace.s1;

import com.openggf.game.GameServices;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost benchmark for the time-attack <b>replay verifier</b>
 * (docs/architecture/designs/2026-07-04-time-attack-security-design.md §6).
 *
 * <p>The trace-replay suite measures engine sim <em>plus</em> heavy per-frame
 * validation (full physics-field diff against physics.csv, aux-state parse,
 * snapshot capture, per-frame string formatting). A verifier does none of that:
 * per §6.2 it replays the recorded input masks from the canonical start state
 * and only derives {@code finishFrame - firstInputFrame} at the end. This test
 * runs the <em>same</em> bootstrap and the <em>same</em> {@code
 * stepFrameFromRecording()} hot loop as {@link
 * com.openggf.tests.trace.AbstractTraceReplayTest}, but strips everything after
 * the frame step — so its timing is the true verifier cost, not the
 * validation-inflated ceiling.
 *
 * <p>It reports, per complete-run trace:
 * <ul>
 *   <li><b>setup ms</b> — config + level-load-from-ROM + bootstrap. This is the
 *       fixed per-job cost paid only if the worker is <em>not</em> pooled/warm.</li>
 *   <li><b>replay ms / fps / xRealtime</b> — the pure input-replay hot loop.
 *       This is the marginal cost on a warm, level-pooled worker.</li>
 *   <li><b>heap MB</b> — used JVM heap after the level is loaded (proxy for the
 *       resident footprint of one warm verifier worker).</li>
 * </ul>
 *
 * Not a CI gate: assertions only sanity-check that frames actually replayed.
 * Run with {@code mvn "-Dtest=TestVerifierCostBenchmark" test}.
 */
@RequiresRom(SonicGame.SONIC_1)
@Tag("performance-measurement")
public class TestVerifierCostBenchmark {

    /** Complete-run S1 traces of full-act length — the closest analog to a time-attack run. */
    private static final List<String> TRACES = List.of(
            "ghz1_completerun",
            "slz1_completerun",
            "mz1_completerun",
            "lz1_completerun");

    private record Result(String name, int frames, long setupMs, long replayMs,
                          double fps, double xRealtime, long heapMb) {}

    @Test
    public void benchmarkVerifierCost() throws Exception {
        // Warm the JIT / class loading with one discarded run so the reported
        // numbers reflect a steady-state (pooled) worker, not first-touch.
        runOnce("ghz1_completerun", true);

        System.out.println();
        System.out.println("=== Replay-verifier cost (pure input replay, no trace validation) ===");
        System.out.printf("%-18s %8s %9s %9s %8s %10s %8s%n",
                "trace", "frames", "setup ms", "replay ms", "fps", "xRealtime", "heap MB");

        boolean anyRan = false;
        for (String name : TRACES) {
            Result r = runOnce(name, false);
            if (r == null) {
                System.out.printf("%-18s   (trace assets absent — skipped)%n", name);
                continue;
            }
            anyRan = true;
            System.out.printf("%-18s %8d %9d %9d %8.0f %9.1fx %8d%n",
                    r.name(), r.frames(), r.setupMs(), r.replayMs(),
                    r.fps(), r.xRealtime(), r.heapMb());
            assertTrue(r.frames() > 100, "expected a full-act replay, got " + r.frames() + " frames");
            assertTrue(r.fps() > 100, "headless replay implausibly slow: " + r.fps() + " fps");
        }
        Assumptions.assumeTrue(anyRan, "No complete-run trace assets present; nothing benchmarked.");
    }

    /**
     * Faithful mirror of the non-S3K replay path in AbstractTraceReplayTest,
     * minus all per-frame validation. Returns null if the trace assets are absent.
     */
    private Result runOnce(String traceName, boolean warmup) throws Exception {
        Path traceDir = Path.of("src/test/resources/traces/s1", traceName);
        if (!Files.isDirectory(traceDir)
                || !Files.exists(traceDir.resolve("metadata.json"))
                || !hasPayload(traceDir, "physics.csv")) {
            return null;
        }

        TraceData trace = TraceData.load(traceDir);
        TraceMetadata meta = trace.metadata();
        Path bk2Path = resolveBk2File(traceDir, meta);
        if (bk2Path == null) {
            return null;
        }

        boolean freshLoad = TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace);
        SharedLevel sharedLevel = freshLoad ? null : SharedLevel.load(SonicGame.SONIC_1, 0, 0);
        try {
            // ---- SETUP (fixed per-job cost: config + ROM level load + bootstrap) ----
            long t0 = System.nanoTime();
            TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

            HeadlessTestFixture.Builder fb = HeadlessTestFixture.builder()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
            if (sharedLevel != null) {
                fb.withSharedLevel(sharedLevel);
            } else {
                fb.withZoneAndAct(0, 0);
            }
            if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)) {
                fb.startPosition(meta.startX(), meta.startY()).startPositionIsCentre();
            }
            HeadlessTestFixture fixture = fb.build();
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
            long setupMs = (System.nanoTime() - t0) / 1_000_000L;

            long heapMb = usedHeapMb();

            // ---- REPLAY (the verifier's actual hot loop: input drive + physics step) ----
            int startIndex = boot.replayStart().startingTraceIndex();
            int frames = 0;
            int firstInputFrame = -1;
            int finishFrame = -1;
            long r0 = System.nanoTime();
            for (int i = startIndex; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                // O(1) counter read — the step/skip decision the real replay makes.
                // This is NOT the field-diff validation; that is what we are excluding.
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                int input = (phase == TraceExecutionPhase.VBLANK_ONLY)
                        ? fixture.skipFrameFromRecording()
                        : fixture.stepFrameFromRecording();
                // Verifier derives its own first-input / finish frames (§6.2); cheap.
                if (firstInputFrame < 0 && (input & 0x0F00) != 0) {
                    firstInputFrame = frames;
                }
                frames++;
            }
            long replayMs = (System.nanoTime() - r0) / 1_000_000L;
            // Silence unused-warning intent: finishFrame would be read from the
            // signpost/act-complete trigger in a real verifier; here the loop
            // length stands in for it since these traces run to act completion.
            finishFrame = frames;

            double fps = replayMs > 0 ? frames * 1000.0 / replayMs : Double.NaN;
            double xRealtime = replayMs > 0 ? (frames / 60.0) / (replayMs / 1000.0) : Double.NaN;
            return warmup
                    ? null
                    : new Result(traceName, frames, setupMs, replayMs, fps, xRealtime, heapMb);
        } finally {
            if (sharedLevel != null) {
                sharedLevel.dispose();
            } else {
                TestEnvironment.resetAll();
            }
        }
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    /**
     * Retained footprint of a <em>ready-to-run</em> level session — the thing a
     * warm verifier worker would hold resident to reset-to-snapshot instead of
     * reloading. Measured as a settled-GC heap delta from an empty (level-less)
     * baseline, across a spread of S1 zones (small GHZ .. water-heavy LZ).
     *
     * <p>The dynamic gameplay keyframe that gets re-applied per job is a small
     * subset of this (object slots + player + camera + managers state, KBs to a
     * low MB); the measured delta is dominated by the static loaded assets
     * (tilemap, decoded patterns, collision arrays) that stay resident across
     * all jobs. So this delta is the honest "one level held warm" cost.
     */
    @Test
    public void benchmarkPerLevelFootprint() throws Exception {
        // Engine floor: S1 module + ROM up (via @RequiresRom), no level loaded.
        // NOTE: never call TestEnvironment.resetAll() here — it resets
        // GameModuleRegistry to the S2 default and the reload picks up wrong
        // handlers. The fixture's build() uses the lighter resetPerTest(), which
        // preserves the active module and drops the prior level.
        long floorMb = settledUsedHeapMb();

        // zone indices: GHZ=0, MZ=1, SYZ=2, LZ=3, SLZ=4, SBZ=5 (act 0 each)
        int[][] zones = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {4, 0}, {5, 0}};
        String[] names = {"GHZ1", "MZ1", "SYZ1", "LZ1", "SLZ1", "SBZ1"};

        System.out.println();
        System.out.println("=== Per-level retained footprint (warm worker holding one ready level) ===");
        System.out.printf("engine floor (module up, no level): %d MB%n", floorMb);
        System.out.printf("%-8s %14s%n", "zone", "retained MB");

        long sum = 0;
        int n = 0;
        long max = 0;
        for (int i = 0; i < zones.length; i++) {
            // build() replaces the prior level; after settled GC only one level
            // is retained, so (used - floor) isolates one loaded level session.
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(zones[i][0], zones[i][1])
                    .build();
            assertTrue(fixture.sprite() != null && GameServices.level().getCurrentLevel() != null);
            long retained = Math.max(0, settledUsedHeapMb() - floorMb);
            System.out.printf("%-8s %14d%n", names[i], retained);
            sum += retained;
            max = Math.max(max, retained);
            n++;
        }
        double avg = n > 0 ? (double) sum / n : 0;
        System.out.printf("avg=%.0f MB  max=%d MB  over %d zones%n", avg, max, n);
        System.out.printf("Extrapolated 'all levels held warm' (static assets only):%n");
        System.out.printf("  S1 ~19 acts: ~%.0f MB   S2 ~20 acts: ~%.0f MB   S3K ~28 acts: ~%.0f MB%n",
                avg * 19, avg * 20, avg * 28);
        assertTrue(avg > 0, "expected a measurable per-level footprint");
    }

    /** Settle the heap (a few GC passes) and return used MB. */
    private static long settledUsedHeapMb() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return usedHeapMb();
    }

    private static boolean hasPayload(Path dir, String fileName) {
        return Files.exists(dir.resolve(fileName)) || Files.exists(dir.resolve(fileName + ".gz"));
    }

    private static Path resolveBk2File(Path traceDir, TraceMetadata meta) throws IOException {
        if (meta != null && meta.sourceBk2() != null && !meta.sourceBk2().isBlank()) {
            Path shared = traceDir.getParent().resolve("_movies").resolve(meta.sourceBk2());
            if (Files.exists(shared)) {
                return shared;
            }
        }
        try (var files = Files.list(traceDir)) {
            return files.filter(p -> p.toString().endsWith(".bk2")).findFirst().orElse(null);
        }
    }
}
