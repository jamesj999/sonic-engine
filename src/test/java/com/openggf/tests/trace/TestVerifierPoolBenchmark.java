package com.openggf.tests.trace;

import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proof-of-concept for the time-attack verifier's <b>pooled warm-level</b>
 * architecture (docs/architecture/designs/2026-07-04-time-attack-security-design.md §6).
 *
 * <p>Models the real service shape: verification jobs are routed by fingerprint
 * to a worker that already holds the matching level in memory (§6.2). Instead of
 * reloading and re-decoding a level from ROM per job, each level is decoded once
 * and then serviced <em>warm</em> — the {@code SharedLevel} reuse path rebuilds
 * only the gameplay session (reset-to-snapshot) and replays the recorded BK2
 * input stream, skipping {@code loadZoneAndAct} entirely. Per-frame trace
 * validation is excluded (that is the trace suite's job, not a verifier's).
 *
 * <p>The run:
 * <ol>
 *   <li>Builds a corpus of green S1 complete-run + S2 level-select traces.</li>
 *   <li>Picks {@value #JOB_COUNT} verification jobs at random (fixed seed) from
 *       the corpus, and routes them to their level (group-by-level = the pool).</li>
 *   <li>Decodes each distinct level once (the pool warm-up), holds it in memory,
 *       then services every job against the warm level.</li>
 *   <li>Reports: pool warm-up cost, resident memory holding all levels, warm
 *       per-job latency + throughput, and the naive per-job-reload counterfactual.</li>
 * </ol>
 *
 * Not a CI gate. Run with {@code mvn "-Dtest=TestVerifierPoolBenchmark" test}.
 * S3K is omitted until its traces pass. Traces that cannot be warm-reused
 * (power-on / fresh-load-required) or fail to replay are skipped and logged.
 */
@Tag("performance-measurement")
public class TestVerifierPoolBenchmark {

    private static final int JOB_COUNT = 100;
    private static final long SEED = 20260707L;

    /** A verifiable level: which game, its trace dir, and the engine zone/act. */
    private record Level(String game, String dir, int zone, int act) {
        SonicGame sonicGame() {
            return "s1".equals(game) ? SonicGame.SONIC_1 : SonicGame.SONIC_2;
        }
        Path traceDir() {
            return Path.of("src/test/resources/traces", game, dir);
        }
        String label() {
            return game + "/" + dir;
        }
    }

    /** Green S1 complete-run acts + S2 level-select acts (S3K deferred until green). */
    private static List<Level> corpus() {
        List<Level> c = new ArrayList<>();
        // S1 complete-run: GHZ=0 MZ=1 SYZ=2 LZ=3 SLZ=4 SBZ=5, acts 0..2
        String[][] s1 = {
            {"ghz1_completerun", "0", "0"}, {"ghz2_completerun", "0", "1"}, {"ghz3_completerun", "0", "2"},
            {"mz1_completerun", "1", "0"}, {"mz2_completerun", "1", "1"}, {"mz3_completerun", "1", "2"},
            {"syz1_completerun", "2", "0"}, {"syz2_completerun", "2", "1"}, {"syz3_completerun", "2", "2"},
            {"lz1_completerun", "3", "0"}, {"lz2_completerun", "3", "1"}, {"lz3_completerun", "3", "2"},
            {"slz1_completerun", "4", "0"}, {"slz2_completerun", "4", "1"}, {"slz3_completerun", "4", "2"},
            {"sbz1_completerun", "5", "0"}, {"sbz2_completerun", "5", "1"}, {"sbz3_completerun", "5", "2"},
        };
        for (String[] e : s1) c.add(new Level("s1", e[0], Integer.parseInt(e[1]), Integer.parseInt(e[2])));
        // S2 level-select: EHZ=0 CPZ=1 ARZ=2 CNZ=3 HTZ=4 MCZ=5 OOZ=6 MTZ=7 SCZ=8 WFZ=9 DEZ=10
        String[][] s2 = {
            {"ehz1_fullrun", "0", "0"},
            {"cpz", "1", "0"}, {"cpz2", "1", "1"},
            {"arz", "2", "0"}, {"arz2", "2", "1"},
            {"cnz", "3", "0"}, {"cnz2", "3", "1"},
            {"htz", "4", "0"}, {"htz2", "4", "1"},
            {"mcz", "5", "0"}, {"mcz2", "5", "1"},
            {"ooz", "6", "0"}, {"ooz2", "6", "1"},
            {"mtz", "7", "0"}, {"mtz2", "7", "1"}, {"mtz3", "7", "2"},
            {"scz", "8", "0"}, {"wfz", "9", "0"}, {"dez_ending", "10", "0"},
        };
        for (String[] e : s2) c.add(new Level("s2", e[0], Integer.parseInt(e[1]), Integer.parseInt(e[2])));
        return c;
    }

    @Test
    public void poolServesRandomJobsWarm() {
        List<Level> corpus = corpus().stream()
                .filter(l -> Files.isDirectory(l.traceDir())
                        && Files.exists(l.traceDir().resolve("metadata.json"))
                        && hasPayload(l.traceDir(), "physics.csv"))
                .toList();
        Assumptions.assumeTrue(!corpus.isEmpty(), "No trace assets present.");

        // Pick JOB_COUNT random jobs (uniform over corpus, with replacement) and
        // route them to their level (group-by-level = fingerprint/level routing).
        // Sorted by game so the active ROM/module switches at most once per game.
        Random rng = new Random(SEED);
        Map<Level, Integer> jobsPerLevel = new LinkedHashMap<>();
        for (int i = 0; i < JOB_COUNT; i++) {
            Level pick = corpus.get(rng.nextInt(corpus.size()));
            jobsPerLevel.merge(pick, 1, Integer::sum);
        }
        List<Map.Entry<Level, Integer>> groups = new ArrayList<>(jobsPerLevel.entrySet());
        groups.sort(Comparator.comparing((Map.Entry<Level, Integer> e) -> e.getKey().game())
                .thenComparingInt(e -> e.getKey().zone()).thenComparingInt(e -> e.getKey().act()));

        List<SharedLevel> held = new ArrayList<>(); // hold every level -> resident in memory
        long warmupNanos = 0;
        long jobNanos = 0;
        long framesTotal = 0;
        int jobsRun = 0;
        int jobsFailed = 0;
        int levelsSkipped = 0;
        List<Long> jobMillis = new ArrayList<>();
        List<JobRec> jobRecs = new ArrayList<>();

        for (Map.Entry<Level, Integer> group : groups) {
            Level lvl = group.getKey();
            int jobs = group.getValue();
            SharedLevel sl;
            TraceData trace;
            TraceMetadata meta;
            Path bk2;
            try {
                // --- Pool warm-up: decode this level once, then hold it in memory. ---
                long w0 = System.nanoTime();
                sl = SharedLevel.load(lvl.sonicGame(), lvl.zone(), lvl.act());
                warmupNanos += System.nanoTime() - w0;
                held.add(sl);

                trace = TraceData.load(lvl.traceDir());
                meta = trace.metadata();
                if (TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace)) {
                    // Power-on traces cannot warm-reuse a pre-loaded level; a real
                    // worker would still hold the level but re-seed per job. Skip.
                    System.out.printf("  skip %-16s (fresh-load-required, not warm-reusable)%n", lvl.label());
                    levelsSkipped++;
                    continue;
                }
                bk2 = resolveBk2File(lvl.traceDir(), meta);
                if (bk2 == null) {
                    System.out.printf("  skip %-16s (no BK2)%n", lvl.label());
                    levelsSkipped++;
                    continue;
                }
            } catch (IOException | RuntimeException e) {
                System.out.printf("  skip %-16s (warm-up failed: %s)%n", lvl.label(), e.getMessage());
                levelsSkipped++;
                continue;
            }

            int startFrame = TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace);
            boolean applyStart = TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace);
            for (int j = 0; j < jobs; j++) {
                try {
                    long j0 = System.nanoTime();
                    // Reset-to-snapshot: rebuild only the gameplay session against the
                    // warm level (SharedLevel reuse path skips loadZoneAndAct), then
                    // replay the recorded inputs with NO per-frame validation.
                    TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);
                    HeadlessTestFixture.Builder fb = HeadlessTestFixture.builder()
                            .withSharedLevel(sl)
                            .withRecording(bk2)
                            .withRecordingStartFrame(startFrame);
                    if (applyStart) {
                        fb.startPosition(meta.startX(), meta.startY()).startPositionIsCentre();
                    }
                    HeadlessTestFixture fixture = fb.build();
                    TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
                    int startIndex = TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1)
                            .replayStart().startingTraceIndex();
                    long bootstrapMs = (System.nanoTime() - j0) / 1_000_000L;

                    long r0 = System.nanoTime();
                    int frames = replayInputs(trace, fixture, startIndex);
                    long replayMs = (System.nanoTime() - r0) / 1_000_000L;

                    long ms = (System.nanoTime() - j0) / 1_000_000L;
                    jobNanos += System.nanoTime() - j0;
                    jobMillis.add(ms);
                    jobRecs.add(new JobRec(lvl.label(), j, frames, bootstrapMs, replayMs));
                    framesTotal += frames;
                    jobsRun++;
                } catch (Exception e) {
                    jobsFailed++;
                }
            }
        }

        long residentMb = settledUsedHeapMb();
        report(corpus.size(), held.size(), levelsSkipped, jobsRun, jobsFailed,
                warmupNanos, jobNanos, framesTotal, residentMb, jobMillis);
        reportPerJob(jobRecs);

        assertTrue(jobsRun > 0, "no verification jobs ran");
    }

    /** One serviced job: level, sequence-within-level (0 = first-touch), frames, split timings. */
    private record JobRec(String level, int seq, int frames, long bootstrapMs, long replayMs) {
        long totalMs() {
            return bootstrapMs + replayMs;
        }
        double replayFps() {
            return replayMs > 0 ? frames * 1000.0 / replayMs : Double.NaN;
        }
    }

    private void reportPerJob(List<JobRec> recs) {
        if (recs.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("--- 8 slowest jobs (total time) ---");
        System.out.printf("%-16s %4s %8s %10s %10s %9s%n",
                "level", "seq", "frames", "bootstrap", "replay ms", "replay fps");
        recs.stream()
                .sorted(Comparator.comparingLong(JobRec::totalMs).reversed())
                .limit(8)
                .forEach(r -> System.out.printf("%-16s %4d %8d %8d ms %8d ms %9.0f%n",
                        r.level(), r.seq(), r.frames(), r.bootstrapMs(), r.replayMs(), r.replayFps()));

        System.out.println();
        System.out.println("--- per-level replay throughput (all jobs on that level) ---");
        System.out.printf("%-16s %5s %10s %10s %9s%n", "level", "jobs", "avg frames", "avg ms", "fps");
        Map<String, List<JobRec>> byLevel = new LinkedHashMap<>();
        for (JobRec r : recs) {
            byLevel.computeIfAbsent(r.level(), k -> new ArrayList<>()).add(r);
        }
        byLevel.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> avgReplayFps(e.getValue())))
                .forEach(e -> {
                    List<JobRec> g = e.getValue();
                    double avgFrames = g.stream().mapToInt(JobRec::frames).average().orElse(0);
                    double avgReplayMs = g.stream().mapToLong(JobRec::replayMs).average().orElse(0);
                    System.out.printf("%-16s %5d %10.0f %8.0f ms %9.0f%n",
                            e.getKey(), g.size(), avgFrames, avgReplayMs, avgReplayFps(g));
                });

        System.out.println();
        // First-touch (seq 0) vs warm-repeat (seq > 0) replay fps: isolates JIT/first-run cost.
        double firstFps = avgReplayFps(recs.stream().filter(r -> r.seq() == 0).toList());
        double repeatFps = avgReplayFps(recs.stream().filter(r -> r.seq() > 0).toList());
        long firstN = recs.stream().filter(r -> r.seq() == 0).count();
        System.out.printf("first-touch jobs (seq 0): %d, avg replay %.0f fps%n", firstN, firstFps);
        System.out.printf("warm-repeat jobs (seq>0): %d, avg replay %.0f fps%n",
                recs.size() - firstN, repeatFps);
    }

    private static double avgReplayFps(List<JobRec> g) {
        long frames = g.stream().mapToLong(JobRec::frames).sum();
        long ms = g.stream().mapToLong(JobRec::replayMs).sum();
        return ms > 0 ? frames * 1000.0 / ms : 0;
    }

    /**
     * Focused probe: load ONE heavy zone once, then run many successive warm
     * jobs on it, printing each job's replay fps. If fps degrades monotonically,
     * the warm reset-to-snapshot path is leaking per-frame work (state not being
     * cleared between jobs) rather than the zone simply being expensive.
     */
    @Test
    public void warmReuseDegradationProbe() throws Exception {
        // s2/mtz = Metropolis act 1 (zone 7, act 0): object-dense, showed the worst
        // successive-job degradation in the pool run.
        Level lvl = new Level("s2", "mtz", 7, 0);
        Assumptions.assumeTrue(Files.isDirectory(lvl.traceDir())
                && hasPayload(lvl.traceDir(), "physics.csv"), "s2/mtz assets absent");

        SharedLevel sl = SharedLevel.load(lvl.sonicGame(), lvl.zone(), lvl.act());
        TraceData trace = TraceData.load(lvl.traceDir());
        TraceMetadata meta = trace.metadata();
        Assumptions.assumeTrue(!TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace),
                "s2/mtz requires fresh load");
        Path bk2 = resolveBk2File(lvl.traceDir(), meta);
        Assumptions.assumeTrue(bk2 != null, "no BK2 for s2/mtz");
        int startFrame = TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace);
        boolean applyStart = TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace);

        System.out.println();
        System.out.println("=== warm-reuse degradation probe: s2/mtz, 10 successive warm jobs ===");
        System.out.println("Sizes are measured AFTER each job. Whichever grows ~linearly is the leak.");
        System.out.printf("%4s %10s %9s | %s%n", "job", "replay ms", "fps", "registry/collection sizes");
        for (int j = 0; j < 10; j++) {
            TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);
            HeadlessTestFixture.Builder fb = HeadlessTestFixture.builder()
                    .withSharedLevel(sl).withRecording(bk2).withRecordingStartFrame(startFrame);
            if (applyStart) {
                fb.startPosition(meta.startX(), meta.startY()).startPositionIsCentre();
            }
            HeadlessTestFixture fixture = fb.build();
            TraceReplaySessionBootstrap.applyStartPositionAndGroundSnap(trace, fixture);
            int startIndex = TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1)
                    .replayStart().startingTraceIndex();
            long r0 = System.nanoTime();
            int frames = replayInputs(trace, fixture, startIndex);
            long replayMs = (System.nanoTime() - r0) / 1_000_000L;
            assertTrue(frames > 0, "warm reuse probe must replay at least one frame per job");
            System.out.printf("%4d %8d ms %9.0f | %s%n",
                    j, replayMs, replayMs > 0 ? frames * 1000.0 / replayMs : 0, sizesSnapshot(fixture));
        }
    }

    /** Dump sizes of every candidate per-session collection, to find the one that grows. */
    private static String sizesSnapshot(HeadlessTestFixture fixture) {
        StringBuilder sb = new StringBuilder();
        // Public counters
        add(sb, "sprites", () -> com.openggf.game.GameServices.sprites().getAllSprites().size());
        add(sb, "objSlots", () -> com.openggf.game.GameServices.level().getObjectManager().getActiveObjectSlotCount());
        add(sb, "objPeak", () -> com.openggf.game.GameServices.level().getObjectManager().getPeakObjectSlotCount());
        var gm = fixture.gameplayMode();
        add(sb, "specialFx", () -> gm.getSpecialRenderEffectRegistry().activeEffectCount());
        add(sb, "advRender", () -> gm.getAdvancedRenderModeController().size());
        // Reflective reads of private collection fields
        add(sb, "rewindEntries", () -> reflectSize(gm.getRewindRegistry(), "entries"));
        add(sb, "rewindCbs", () -> reflectSize(gm.getRewindRegistry(), "postRestoreCallbacks"));
        add(sb, "palWrites", () -> reflectSize(gm.getPaletteOwnershipRegistry(), "writes"));
        add(sb, "aniChannels", () -> gm.getAnimatedTileChannelGraph().recordedPhaseCount());
        add(sb, "layoutQueued", () -> reflectSize(gm.getZoneLayoutMutationPipeline(), "queued"));
        return sb.toString();
    }

    private interface IntSupplier { int get() throws Exception; }

    private static void add(StringBuilder sb, String name, IntSupplier s) {
        int v;
        try {
            v = s.get();
        } catch (Exception e) {
            v = -1;
        }
        if (sb.length() > 0) sb.append(' ');
        sb.append(name).append('=').append(v);
    }

    /** Reads the size() of a named Collection/Map field via reflection (walks up the class hierarchy). */
    private static int reflectSize(Object owner, String field) throws Exception {
        if (owner == null) return -2;
        Class<?> c = owner.getClass();
        java.lang.reflect.Field f = null;
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField(field);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return -3;
        f.setAccessible(true);
        Object v = f.get(owner);
        if (v instanceof java.util.Map<?, ?> m) return m.size();
        if (v instanceof java.util.Collection<?> col) return col.size();
        return -4;
    }

    /** The verifier hot loop: drive frames from the recording, count them, no validation. */
    private static int replayInputs(TraceData trace, HeadlessTestFixture fixture, int startIndex) {
        int frames = 0;
        for (int i = startIndex; i < trace.frameCount(); i++) {
            TraceFrame expected = trace.getFrame(i);
            TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
            TraceExecutionPhase phase = TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
            if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                fixture.skipFrameFromRecording();
            } else {
                fixture.stepFrameFromRecording();
            }
            frames++;
        }
        return frames;
    }

    private void report(int corpusSize, int levelsHeld, int levelsSkipped,
                        int jobsRun, int jobsFailed, long warmupNanos, long jobNanos,
                        long framesTotal, long residentMb, List<Long> jobMillis) {
        double warmupSec = warmupNanos / 1e9;
        double jobSec = jobNanos / 1e9;
        double avgJobMs = jobMillis.stream().mapToLong(Long::longValue).average().orElse(0);
        List<Long> sorted = jobMillis.stream().sorted().toList();
        long minMs = sorted.isEmpty() ? 0 : sorted.get(0);
        long maxMs = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        long p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        double throughput = jobSec > 0 ? jobsRun / jobSec : 0;
        double avgWarmupMs = levelsHeld > 0 ? warmupSec * 1000 / levelsHeld : 0;
        // Counterfactual: without the pool, every job reloads its level from ROM.
        double naiveSec = jobsRun * (avgWarmupMs + avgJobMs) / 1000.0;

        System.out.println();
        System.out.println("=== Verifier pooled-warm-level POC (S1 + S2) ===");
        System.out.printf("corpus levels present      : %d%n", corpusSize);
        System.out.printf("levels decoded & held warm : %d  (%d skipped)%n", levelsHeld, levelsSkipped);
        System.out.printf("resident heap (all levels) : %d MB  (~%.1f MB/level)%n",
                residentMb, levelsHeld > 0 ? (double) residentMb / levelsHeld : 0);
        System.out.println();
        System.out.printf("pool warm-up (decode once) : %.2f s total, ~%.0f ms/level%n", warmupSec, avgWarmupMs);
        System.out.printf("%d jobs serviced warm       : %.2f s total%n", jobsRun, jobSec);
        System.out.printf("  per-job latency          : avg %.0f ms, p50 %d ms, min %d ms, max %d ms%n",
                avgJobMs, p50, minMs, maxMs);
        System.out.printf("  throughput               : %.1f verifications/sec/core%n", throughput);
        System.out.printf("  frames replayed          : %,d  (avg %,d/job)%n",
                framesTotal, jobsRun > 0 ? framesTotal / jobsRun : 0);
        if (jobsFailed > 0) System.out.printf("  jobs failed/skipped      : %d%n", jobsFailed);
        System.out.println();
        System.out.printf("warm pool total            : %.2f s  (warm-up %.2f + jobs %.2f)%n",
                warmupSec + jobSec, warmupSec, jobSec);
        System.out.printf("naive reload-per-job total : %.2f s  (each job re-decodes from ROM)%n", naiveSec);
        if (naiveSec > 0) {
            System.out.printf("  => keeping levels warm is ~%.1fx faster for this job mix%n",
                    naiveSec / Math.max(0.001, warmupSec + jobSec));
        }
    }

    private static long settledUsedHeapMb() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
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
