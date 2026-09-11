package com.openggf.tests.trace.s3k;

import com.openggf.data.Rom;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.trace.TraceFixtureRoot;
import com.openggf.tests.trace.TraceReportWriter;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceData;
import com.openggf.game.sonic3k.specialstage.S3kSpecialStageTraceFrame;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.timing.HardwareTimingStreamLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static com.openggf.tests.trace.TraceFieldComparisons.bool;
import static com.openggf.tests.trace.TraceFieldComparisons.cmp;
import static com.openggf.tests.trace.TraceFieldComparisons.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless replay comparator for a Sonic 3&amp;K special-stage (blue
 * spheres) trace. Drives the production
 * {@link com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider}
 * through {@link S3kSpecialStageReplayHarness}, comparing each stepped frame
 * against the recorded ROM trace and emitting a divergence report to
 * the session-owned {@code special-stage} report directory (or the legacy
 * {@code target/trace-reports} default when run without a session).
 * Modeled on {@code AbstractS2SpecialStageTraceReplayTest}, simplified for
 * the S3K SS's single ROM pacing model (no RunObjects-pass binder, no lag
 * compensator to disable).
 *
 * <h2>Replay semantics</h2>
 * <ul>
 *   <li><b>VBlank-paced.</b> Every non-lag trace row is stepped through
 *       {@link S3kSpecialStageReplayHarness#stepFrame(int)}; lag rows are
 *       skipped (consumed without stepping) since they advance nothing
 *       engine-side.</li>
 *   <li><b>Comparison-only.</b> Trace values are read for input + expectation
 *       only; engine state is never hydrated from the trace.</li>
 *   <li><b>Finish boundary.</b> The engine's {@code finished} flag flips only
 *       when the exit-spin animation completes ({@code fade_timer} rises
 *       from 0 to nonzero, then returns to 0 --
 *       {@code Sonic3kSpecialStageManager.java:604-625}), on BOTH the
 *       success (emerald collected) and failure (landed on a red sphere,
 *       {@code Sonic3kSpecialStageManager.java:699-706}) exit paths. The
 *       trace's {@code clear_routine} column reaching its ROM terminal state
 *       ({@link #CLEAR_ROUTINE_TERMINAL}) is NOT that boundary: on the
 *       success path {@code clear_routine} jumps to its terminal value the
 *       instant the player reaches the emerald cell
 *       ({@code Sonic3kSpecialStageManager.collectEmerald()},
 *       ROM {@code sub_9B62}/sonic3k.asm:12530-12664), roughly 96+ frames
 *       before {@code finished} actually flips, and the failure path never
 *       touches {@code clear_routine} at all. A Tier-1
 *       {@code finished_transition_frame} check therefore anchors on
 *       {@link #exitSpinCompletionFrame} (the trace's own {@code fade_timer}
 *       0&rarr;nonzero&rarr;0 cycle) and asserts the engine's
 *       {@code captureComparisonState().finished()} first becomes true at
 *       that frame.</li>
 * </ul>
 *
 * <h2>Release-ratcheted comparator surface</h2>
 * <p>Every mismatch below is a report ERROR, one comparison-only read of
 * {@link Sonic3kSpecialStageComparisonState} per CSV column:
 * {@code player_x}/{@code player_y}/{@code angle}/{@code velocity}/
 * {@code turning}/{@code jumping}/{@code fade_timer}/{@code started}/
 * {@code spheres_left}/{@code ring_count}/{@code rings_left}/
 * {@code clear_routine}/{@code clear_timer}, plus {@code frame_counter}
 * (stepped non-lag row count vs the engine's own counter) and the
 * {@code finished_transition_frame} boundary check.
 *
 * <p>{@code rate}, {@code rate_timer}, and {@code anim_frame} are recorded as
 * WARNING-severity rows: the comparison state has no engine-side counterpart
 * for these columns yet (multi-stage trace run spec addition #3 MVP), so
 * they are surfaced in the report for a follow-up campaign rather than
 * silently dropped.
 *
 * <p>The pipeline writes a complete report and
 * {@link #assertNoReleaseBlockingDivergences} rejects any comparator ERROR.
 */
@ExtendWith(SessionInvocationExtension.class)
public abstract class AbstractS3kSpecialStageTraceReplayTest {

    /** Location of the committed trace (when one exists). */
    static final Path TRACE_DIRECTORY =
            Path.of("src", "test", "resources", "traces", "s3k", "special_stage");

    /**
     * ROM clear-routine terminal state (sub_9B62, sonic3k.asm:12530-12664):
     * 0=normal play, 1=fly-away timer, 2=emerald-art-load wait,
     * 3=emerald approach, 4=complete. Set the instant the player reaches the
     * emerald cell in {@code collectEmerald()}
     * (Sonic3kSpecialStageManager.java:777). Documented for reference only
     * (compared per-frame like every other {@code clear_routine} column
     * value) -- it is NOT the finish-boundary anchor: it precedes the
     * engine's {@code finished} flip by 96+ frames on the success path, and
     * the failure (red-sphere) exit path never sets {@code clear_routine} at
     * all. See {@link #exitSpinCompletionFrame} for the actual boundary.
     */
    private static final int CLEAR_ROUTINE_TERMINAL = 4;

    private static final String ABSENT = "absent";
    private static final int CONTEXT_RADIUS = 8;

    /** Directory of the trace to replay. */
    protected abstract Path traceDirectory();

    @Test
    void replayProducesFaithfulReport() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(romFile != null,
                "s3k.gen ROM required for S3K special-stage trace replay");

        Path dir = TraceFixtureRoot.resolve(traceDirectory());
        assumeTrue(Files.exists(dir.resolve("metadata.json")),
                "No S3K special-stage trace committed yet at " + dir);

        S3kSpecialStageTraceData trace = S3kSpecialStageTraceData.load(dir);

        // Pipeline assertion: the trace loads with the expected profile + frames.
        assertEquals("s3k_special_stage", trace.metadata().traceProfile(),
                "SS trace must carry the s3k_special_stage profile");
        assertTrue(trace.frameCount() > 0, "SS trace should have frames");

        S3kSpecialStageReplayHarness harness = bootHarness(trace, dir, romFile);
        DivergenceReport report = compareReplay(trace, harness);

        // Write the divergence report BEFORE closing hardware timing. The close
        // below throws on an unconsumed edge, and it used to run first, so a
        // timing failure discarded the comparison that had already completed:
        // the shared legacy report directory held nothing, and nine classes read as though
        // they never reached frame comparison at all. They had -- each has a
        // real physics divergence, and the unconsumed edge is its downstream
        // symptom (the stage never reaches clearRoutine 2, so the emerald art
        // module is never queued and the recorded completion has nothing to
        // match). Writing first costs nothing and keeps the actual first error
        // visible. Neither assertion is weakened: the close still throws and
        // the test still fails.
        int ssIndex = specialStageIndex(trace);
        TestSessionOutputPaths.ReportAllocation allocation = writeReport(report, ssIndex);

        // Every recorded hardware-timing edge must have been consumed by a
        // matching production submission (kind, ordinal and submission
        // fingerprint). An emerald art module the engine never queued -- or
        // queued from the wrong ROM address, size or VRAM destination -- leaves
        // its edge unconsumed and fails here rather than silently drifting.
        harness.closeHardwareTiming();

        // Pipeline assertion: the report file was written where consumers expect.
        assertTrue(Files.exists(allocation.physicalPath()),
                "report JSON should be written to " + allocation.physicalPath());

        assertNoReleaseBlockingDivergences(report);
    }

    /**
     * Release-gate ratchet for Tier-1 fields, which use ERROR severity.
     */
    protected void assertNoReleaseBlockingDivergences(DivergenceReport report) {
        assertFalse(report.hasErrors(), report.toAssertionSummary());
    }

    // ==================== Boot ====================

    static S3kSpecialStageReplayHarness bootHarness(S3kSpecialStageTraceData trace,
                                                     Path dir,
                                                     File romFile) throws IOException {
        // Headless graphics so the SS manager's pattern/renderer setup is safe
        // without a GL context.
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        // Installs the Sonic3kGameModule, wires GameServices.rom(), and rebuilds a
        // fresh gameplay mode. Resets configuration to defaults, so team config is
        // (re)applied inside the harness ctor afterwards.
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        int offset = trace.metadata().bk2FrameOffset();
        int ssIndex = specialStageIndex(trace);
        Path bk2 = resolveSourceBk2(dir, trace.metadata().sourceBk2());
        S3kSpecialStageReplayHarness harness =
                new S3kSpecialStageReplayHarness(bk2, offset, ssIndex);
        harness.installHardwareTiming(
                HardwareTimingStreamLoader.load(dir, trace.metadata()));
        return harness;
    }

    /**
     * Locates the movie named by {@code metadata.source_bk2}. A standalone
     * trace keeps its movie beside its own {@code metadata.json}; a segment of
     * a multi-segment run has no movie of its own, because the run commits one
     * copy at the run root beside {@code run_manifest.json} and every segment
     * indexes into it from its own {@code bk2_frame_offset}. This mirrors the
     * placement {@code AbstractTraceReplayTest#resolveBk2File} already accepts
     * for level and bonus segments; it is file location only, and reads
     * nothing from the run manifest.
     */
    static Path resolveSourceBk2(Path traceDir, String sourceBk2) {
        Path inSegment = traceDir.resolve(sourceBk2);
        if (Files.exists(inSegment)) {
            return inSegment;
        }
        Path runRoot = traceDir.getParent();
        return runRoot == null ? inSegment : runRoot.resolve(sourceBk2);
    }

    // ==================== Comparator ====================

    static DivergenceReport compareReplay(S3kSpecialStageTraceData trace,
                                          S3kSpecialStageReplayHarness harness) {
        OptionalInt finishFrame = exitSpinCompletionFrame(trace);
        int compareEnd = trace.frameCount();
        int startFrame = firstInteractiveFrame(trace);

        List<FrameComparison> comparisons = new ArrayList<>();
        int firstEngineFinished = -1;
        int steppedNonLagCount = 0;

        for (int f = startFrame; f < compareEnd; f++) {
            S3kSpecialStageTraceFrame tf = trace.getFrame(f);
            if (tf.lag()) {
                continue;
            }
            harness.stepFrame(f);
            steppedNonLagCount++;

            Sonic3kSpecialStageComparisonState state = harness.capture();
            if (firstEngineFinished < 0 && state.finished()) {
                firstEngineFinished = f;
            }

            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            addFields(fields, tf, state, steppedNonLagCount);
            comparisons.add(new FrameComparison(f, fields));
        }

        if (finishFrame.isPresent()) {
            int ff = finishFrame.getAsInt();
            String actual = firstEngineFinished >= 0 ? String.valueOf(firstEngineFinished) : "never";
            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            fields.put("finished_transition_frame",
                    cmp("finished_transition_frame", String.valueOf(ff), actual, Severity.ERROR));
            comparisons.add(new FrameComparison(ff, fields));
        }

        return new DivergenceReport(comparisons);
    }

    private static void addFields(Map<String, FieldComparison> fields,
                                  S3kSpecialStageTraceFrame tf,
                                  Sonic3kSpecialStageComparisonState state,
                                  int steppedNonLagCount) {
        // Tier-1. player_x/player_y are raw unsigned u16 on both sides (the
        // grid is toroidal and the ROM word wraps through 0x8000 constantly,
        // Sonic3kSpecialStagePlayer.xPos/yPos are masked & 0xFFFF) -- do NOT
        // route these through signedWord(), unlike velocity below.
        fields.put("player_x", cmp("player_x",
                str(tf.xPos()), str(state.playerX()), Severity.ERROR));
        fields.put("player_y", cmp("player_y",
                str(tf.yPos()), str(state.playerY()), Severity.ERROR));
        fields.put("angle", cmp("angle", str(tf.angle()), str(state.angle()), Severity.ERROR));
        fields.put("velocity", cmp("velocity",
                str(signedWord(tf.velocity())), str(state.velocity()), Severity.ERROR));
        fields.put("turning", cmp("turning",
                str(signedByte(tf.turning())), str(state.turning()), Severity.ERROR));
        fields.put("jumping", cmp("jumping", str(tf.jumping()), str(state.jumping()), Severity.ERROR));
        fields.put("fade_timer",
                cmp("fade_timer", str(tf.fadeTimer()), str(state.fadeTimer()), Severity.ERROR));
        fields.put("started", cmp("started", bool(tf.started()), bool(state.started()), Severity.ERROR));
        fields.put("spheres_left",
                cmp("spheres_left", str(tf.spheresLeft()), str(state.spheresLeft()), Severity.ERROR));
        fields.put("ring_count",
                cmp("ring_count", str(tf.ringCount()), str(state.ringsCollected()), Severity.ERROR));
        fields.put("rings_left",
                cmp("rings_left", str(tf.ringsLeft()), str(state.ringsLeft()), Severity.ERROR));
        fields.put("clear_routine",
                cmp("clear_routine", str(tf.clearRoutine()), str(state.clearRoutine()), Severity.ERROR));
        fields.put("clear_timer",
                cmp("clear_timer", str(tf.clearTimer()), str(state.clearTimer()), Severity.ERROR));
        fields.put("frame_counter",
                cmp("frame_counter", str(steppedNonLagCount), str(state.frameCounter()), Severity.ERROR));

        // Recorded-not-compared for MVP (multi-stage trace run spec addition
        // #3): Sonic3kSpecialStageComparisonState has no counterpart field
        // yet for these three columns. Recorded as WARNING so a follow-up
        // campaign can wire real comparands (Sonic3kSpecialStagePlayer
        // exposes getMappingFrame() if anim_frame mapping is wanted) without
        // the columns silently disappearing from the report.
        fields.put("rate", cmp("rate", str(tf.rate()), ABSENT, Severity.WARNING));
        fields.put("rate_timer", cmp("rate_timer", str(tf.rateTimer()), ABSENT, Severity.WARNING));
        fields.put("anim_frame", cmp("anim_frame", str(tf.animFrame()), ABSENT, Severity.WARNING));
    }

    /**
     * The first trace row at which the special stage's ROM boot sequence has
     * settled into steady, interactive state -- observing a ROM pre-start
     * hold, not hydrating engine state from the trace.
     *
     * <p>ROM {@code SpecialStage} (sonic3k.asm:10585-10725) entry is a single
     * synchronous 68000 call: {@code Pal_FadeToWhite} runs first
     * (sonic3k.asm:10591, interrupts still enabled -- BizHawk records these
     * rows as non-lag even though {@code Special_stage_*} RAM is still
     * whatever a PRIOR special-stage attempt left it at, since the
     * {@code clearRAM Stat_table,$100} wipe (sonic3k.asm:10607) that zeroes
     * the entire {@code Special_stage_*} block -- confirmed by RAM-layout
     * arithmetic: {@code Stat_table}/{@code Pos_table_P2}
     * (sonic3k.constants.asm:328-333) is exactly the 0x100-byte
     * {@code Pos_table_P2} region that hosts every {@code Special_stage_*}
     * field at sonic3k.constants.asm:1012-1057 -- hasn't run yet). Immediately
     * after, {@code move #$2700,sr} (sonic3k.asm:10592) masks interrupts, and
     * the routine spends dozens of real frames inside back-to-back
     * {@code Nem_Decomp}/{@code Eni_Decomp}/{@code Kos_Decomp} calls
     * (sonic3k.asm:10632-10695) loading special-stage art with no controller
     * poll -- BizHawk marks every one of those frames lag. {@code Wait_VSync}
     * (sonic3k.asm:10725) is the first frame that reads input again, landing
     * the RAM state the engine's synchronous, instant-load
     * {@code Sonic3kSpecialStageManager#initialize(int)} already produces at
     * construction (matching layout-driven x/y/angle, cleared
     * {@code clear_routine}, etc.).
     *
     * <p>The trace's own {@code lag} column is the ROM-observable signal for
     * this hold -- not a hardcoded frame count: this returns the first row
     * whose {@code lag} is {@code false} immediately after having seen at
     * least one {@code lag=true} row (the decompression block). Traces with
     * no leading lag run (already opened post-boot) fall back to frame 0.
     */
    private static int firstInteractiveFrame(S3kSpecialStageTraceData trace) {
        boolean sawLag = false;
        for (int f = 0; f < trace.frameCount(); f++) {
            boolean lag = trace.getFrame(f).lag();
            if (lag) {
                sawLag = true;
            } else if (sawLag) {
                return f;
            }
        }
        return 0;
    }

    /**
     * The trace frame where the exit-spin animation completes: the first
     * return of {@code fade_timer} to 0 after its first 0&rarr;nonzero rise.
     * Covers both exit paths -- {@code fade_timer} is set to 1 by
     * {@code collectEmerald()} on success (Sonic3kSpecialStageManager.java:778)
     * and by the {@code RED_SPHERE} case on failure
     * (Sonic3kSpecialStageManager.java:701) -- and matches the engine's own
     * finish condition ({@code exitSpinStarted && fadeTimer == 0},
     * Sonic3kSpecialStageManager.java:619-625).
     *
     * <p>The scan starts at {@link #firstInteractiveFrame} and skips
     * {@code lag} rows: rows before that point are the pre-boot ROM hold
     * described there (stale leftover {@code Special_stage_fade_timer} from
     * a prior attempt, sonic3k.asm:10585-10725), and lag rows are recorded
     * with every field zero-filled rather than a real RAM read (the recorder
     * does not read {@code mainmemory} on a lag row -- see
     * {@code s3k_complete_run_recorder.lua}'s {@code write_ss_row}), so a
     * zero-filled lag-row {@code fade_timer} must never be read as a genuine
     * ROM return-to-zero.
     */
    private static OptionalInt exitSpinCompletionFrame(S3kSpecialStageTraceData trace) {
        int spinStartFrame = -1;
        int previousFadeTimer = 0;
        for (int f = firstInteractiveFrame(trace); f < trace.frameCount(); f++) {
            S3kSpecialStageTraceFrame tf = trace.getFrame(f);
            if (tf.lag()) {
                continue;
            }
            int fadeTimer = tf.fadeTimer();
            if (spinStartFrame < 0) {
                if (previousFadeTimer == 0 && fadeTimer != 0) {
                    spinStartFrame = f;
                }
            } else if (fadeTimer == 0) {
                return OptionalInt.of(f);
            }
            previousFadeTimer = fadeTimer;
        }
        return OptionalInt.empty();
    }

    /**
     * Maps a raw 68000 word to the signed value used by the ROM's SS-space
     * velocity arithmetic. NOT used for player_x/player_y: those are
     * unsigned u16 on both the trace and engine sides (the SS grid is
     * toroidal, so the word wraps through 0x8000 constantly).
     */
    private static int signedWord(int rawWord) {
        return (short) rawWord;
    }

    /**
     * Maps a raw 68000 byte to the signed value used by the ROM's
     * {@code Special_stage_turning} field. It is written with {@code move.b}
     * as either {@code #4} (left) or {@code #-4} (right) (sonic3k.asm:12000,
     * 12005), i.e. a genuinely signed byte -- Sonic3kSpecialStagePlayer's
     * {@code turning} int mirrors that (TURN_LEFT=4, TURN_RIGHT=-4). The
     * trace CSV records the raw unsigned hex byte (e.g. {@code fc} for -4),
     * which parses to 252 without sign-extension, producing a spurious
     * mismatch against the engine's native -4 for the identical ROM state.
     */
    private static int signedByte(int rawByte) {
        return (byte) rawByte;
    }

    // ==================== Report output ====================

    static TestSessionOutputPaths.ReportAllocation writeReport(
            DivergenceReport report, int ssIndex) throws IOException {
        return TraceReportWriter.writeSpecialStageReport(
                report, "special-stage", SessionInvocationExtension.SessionInvocation.current(),
                "s3k-" + ssIndex, "s3k_special_stage_" + ssIndex, CONTEXT_RADIUS);
    }

    static Path reportDir() {
        return TestSessionOutputPaths.traceReports();
    }

    static int specialStageIndex(S3kSpecialStageTraceData trace) {
        Integer index = trace.metadata().specialStageIndex();
        return index != null ? index : 0;
    }
}
