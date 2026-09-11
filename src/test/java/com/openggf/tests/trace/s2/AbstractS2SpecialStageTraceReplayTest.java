package com.openggf.tests.trace.s2;

import com.openggf.data.Rom;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState.PlayerState;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageIntro;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.trace.TraceFixtureRoot;
import com.openggf.tests.trace.TraceReportWriter;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.DynamicArtSpecialStageComparator;
import com.openggf.trace.DynamicArtSpillNormalization;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.SpecialStageTraceData;
import com.openggf.trace.SpecialStageExpectedState;
import com.openggf.trace.SpecialStageTraceFrame;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.SpecialStageRunObjectsPassBinder.CompletedPass;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.SpecialStageTraceFrame.CharacterState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

import static com.openggf.tests.trace.TraceFieldComparisons.bool;
import static com.openggf.tests.trace.TraceFieldComparisons.cmp;
import static com.openggf.tests.trace.TraceFieldComparisons.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless replay comparator for a Sonic 2 special-stage trace. Drives the
 * production {@link com.openggf.game.sonic2.Sonic2SpecialStageProvider} through
 * {@link S2SpecialStageReplayHarness}, comparing each stepped frame against the
 * recorded ROM trace and emitting a divergence report to
 * the session-owned {@code special-stage} report directory (or the legacy
 * {@code target/trace-reports} default when run without a session).
 *
 * <h2>Replay semantics</h2>
 * <ul>
 *   <li><b>ROM-pass-paced after control unlock.</b> Startup retains the verified
 *       VBlank/non-lag pacing. Once {@code SpecialStage_Started} is observed,
 *       each observation executes exactly its ordered {@code run_objects_end}
 *       events: zero events means zero engine updates and two events means two.
 *       Each pass identifies the current and previous BK2 rows sampled by its
 *       preceding {@code Vint_S2SS}; held and pressed inputs are derived from
 *       those movie rows. Raw auxiliary held values are diagnostics only and
 *       are rejected if they disagree with the identified BK2 rows.</li>
 *   <li><b>Started boundary pass.</b> The observation that first exposes
 *       {@code SpecialStage_Started} follows Obj5F's terminal pre-start object
 *       pass. Replay completes that already-pending pass without a new VInt;
 *       recorder pass sequence 0 begins with the following VInt sample.</li>
 *   <li><b>Comparison-only.</b> Trace values are read for input + expectation
 *       only; engine state is never hydrated from the trace.</li>
 *   <li><b>Atomic object-pass expectations.</b> A VBlank CSV row can bisect
 *       the following ROM {@code RunObjects} pass. When the recorder provides
 *       ordered {@code run_objects_end} events, the pass binder selects the
 *       latest completed atomic result visible at that observation; player,
 *       ring, and Tails-control fields come from that snapshot, while track and
 *       finish/results fields remain on their CSV observation.</li>
 *   <li><b>Finish boundary.</b> {@code compareEnd = stageFinishedFrame().orElse(
 *       frameCount())}. A Tier-1 {@code finished_transition_frame} check asserts
 *       the engine's {@code isFinished()} first becomes true exactly at the
 *       final-checkpoint logical observation. The later Obj6F
 *       {@code results_started} event and results tail are recorded but not
 *       compared.</li>
 * </ul>
 *
 * <h2>Release-ratcheted comparator surface</h2>
 * <p>Every mismatch below is a report ERROR. Every field is a comparison-only
 * read of {@link Sonic2SpecialStageComparisonState}.
 * <ul>
 *   <li>Per-player {@code present}, {@code ss_x}, {@code ss_y},
 *       {@code ss_z}, {@code angle}, {@code routine} (mapped ROM byte → engine
 *       {@code RoutineState} name), {@code hurt} ({@code routine_secondary==2});
 *       per-player {@code sonic_rings}/{@code tails_rings},
 *       {@code combined_rings}, {@code speed_factor}, {@code current_segment},
 *       {@code track_anim_frame}, {@code swap_positions_flag}, per-player
 *       {@code hurt_timer}/{@code slide_timer}/{@code flip_timer},
 *       {@code player_anim_frame_timer}, refresh-gated decoded
 *       {@code rings_togo_bcd}, {@code finished}, and the
 *       {@code finished_transition_frame} boundary check.</li>
 *   <li>{@code track_drawing_index}, {@code track_duration_timer}
 *       (ROM counts down from the {@code SSAnim_Base_Duration} for the current
 *       speed factor; engine counts up — compared via
 *       {@code duration - romTimer == engineCounter}), and
 *       {@code tails_control_counter}.</li>
 * </ul>
 * <p>Player timers are compared only from atomic {@code run_objects_end}
 * snapshots: a raw VBlank row can interrupt the Obj09→Obj10 scan and contain
 * Sonic's post-pass timer beside Tails's pre-pass timer.</p>
 *
 * <p>The pipeline writes a complete report and
 * {@link #assertNoReleaseBlockingDivergences} rejects any comparator divergence.
 */
@ExtendWith(SessionInvocationExtension.class)
public abstract class AbstractS2SpecialStageTraceReplayTest {

    /** Location of the committed MVP trace (also used by the determinism test). */
    static final Path TRACE_DIRECTORY =
            Path.of("src", "test", "resources", "traces", "s2", "special_stage");

    /** {@code SSAnim_Base_Duration} (s2.asm), indexed by {@code (speed_factor>>1)&7}. */
    private static final int[] ANIM_BASE_DURATIONS = {60, 30, 15, 10, 8, 6, 5, 0};

    /**
     * ROM Obj09/Obj10 (SS Sonic/Tails) routine index → engine
     * {@code RoutineState.name()}. From s2.asm {@code Obj09_Index}: 0 Init,
     * 2 MdNormal, 4 MdJump, 6 invalid, 8 MdAir.
     */
    private static final Map<Integer, String> ROUTINE_NAMES = Map.of(
            0, "INIT",
            2, "NORMAL",
            4, "JUMPING",
            8, "AIRBORNE");

    private static final String ABSENT = "absent";
    private static final int CONTEXT_RADIUS = 8;

    /**
     * Iterations of {@code Pal_FadeToWhite}, the ROM's first act once the
     * special stage's {@code SS_Check_Rings_flag} breaks the main loop
     * (docs/s2disasm/s2.asm:6725-6745). The routine loads {@code d4} with
     * {@code $15} and runs one {@code VintID_Fade} V-blank per {@code dbf}
     * iteration (s2.asm:3570-3581) — {@code $15 + 1} of them. {@code Vint_Fade}
     * never reaches {@code ProcessDMAQueue} (only the real per-mode handlers do:
     * s2.asm:781, 899, 1000, 1046, 1083, 1138), so the terminal pass's queued
     * player art survives the whole fade, and then the interrupts-disabled
     * results-screen setup that follows (s2.asm:3546-3600 region: {@code move
     * #$2700,sr}, ClearScreen, PalLoad_Now, LoadPLC2, NemDec, clearRAM), until
     * the results loop asks for {@code VintID_Level} and waits on it
     * (s2.asm:6800-6806). That first {@code Vint_Level} is the retirement
     * V-blank.
     */
    static final int PAL_FADE_TO_WHITE_FRAMES = 0x15 + 1;

    /** Directory of the trace to replay. */
    protected abstract Path traceDirectory();

    @Test
    void replayProducesFaithfulReport() throws Exception {
        File romFile = RomTestUtils.ensureSonic2RomAvailable();
        assumeTrue(romFile != null && Files.exists(Path.of("s2.gen")),
                "s2.gen ROM required for S2 special-stage trace replay");

        Path dir = TraceFixtureRoot.resolve(traceDirectory());
        SegmentContext context = segmentContext(dir);
        SpecialStageTraceData trace =
                SpecialStageTraceData.load(dir, context.openingDynamicArtLedger());

        // Pipeline assertion: the trace loads with the expected profile + frames.
        assertEquals("s2_special_stage", trace.metadata().traceProfile(),
                "SS trace must carry the s2_special_stage profile");
        assertTrue(trace.frameCount() > 0, "SS trace should have frames");

        S2SpecialStageReplayHarness harness = bootHarness(trace, context, romFile);
        DivergenceReport report = compareReplay(trace, harness);

        int ssIndex = specialStageIndex(trace);
        TestSessionOutputPaths.ReportAllocation allocation = writeReport(report, ssIndex);

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

    // ==================== Segment context ====================

    /**
     * Everything a special-stage trace directory does not carry itself.
     *
     * <p>A standalone trace owns its BK2 and opens with an empty dynamic-art
     * ledger. A special-stage segment recorded inside a run owns neither: the
     * movie lives once at the run root, and the segment inherits whatever
     * transfers were still outstanding when the preceding level segment ended.
     * Both are read from the sibling {@code run_manifest.json}, keyed on the
     * segment's own directory name — the recorder's own continuity record, not
     * a route, zone or frame.
     *
     * <p>A run segment also carries its own place in the run, because a segment
     * whose opening ledger is non-empty inherits engine state from the segments
     * before it: see {@link S2SpecialStagePredecessorReplay}.
     *
     * @param movieDirectory directory holding {@code metadata.source_bk2}
     * @param openingDynamicArtLedger transfers outstanding at segment entry
     * @param manifest the run manifest, or null for a standalone trace
     * @param segmentIndex this segment's manifest index, or -1 when standalone
     */
    record SegmentContext(
            Path movieDirectory,
            List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger,
            TraceRunManifest manifest,
            int segmentIndex) {

        SegmentContext(Path movieDirectory,
                       List<DynamicArtTransfer.Descriptor> openingDynamicArtLedger) {
            this(movieDirectory, openingDynamicArtLedger, null, -1);
        }
    }

    static SegmentContext segmentContext(Path traceDir) throws IOException {
        Path runRoot = traceDir.getParent();
        Path manifestPath = runRoot == null
                ? null : runRoot.resolve("run_manifest.json");
        if (manifestPath == null || !Files.exists(manifestPath)) {
            return new SegmentContext(traceDir, List.of());
        }
        TraceRunManifest manifest = TraceRunManifest.load(manifestPath);
        String segmentDir = traceDir.getFileName().toString();
        for (int index = 0; index < manifest.segments().size(); index++) {
            TraceRunManifest.Segment segment = manifest.segments().get(index);
            if (segmentDir.equals(segment.dir())) {
                return new SegmentContext(
                        runRoot, segment.dynamicArtInitialLedgerDescriptors(),
                        manifest, index);
            }
        }
        throw new IllegalStateException(
                "No run_manifest.json segment named '" + segmentDir
                        + "' in " + manifestPath);
    }

    // ==================== Boot ====================

    /**
     * Boots the special stage, first replaying any run segments whose engine
     * state this one inherits (see {@link S2SpecialStagePredecessorReplay}).
     * The prefix runs after the ROM/module install and before the special-stage
     * provider is created, so the dynamic-art lifecycle the prefix populated is
     * the one the stage starts from.
     */
    static S2SpecialStageReplayHarness bootHarness(SpecialStageTraceData trace,
                                                   SegmentContext context,
                                                   File romFile) throws IOException {
        S2SpecialStageReplayHarness harness =
                bootHarness(trace, context.movieDirectory(), romFile, context);
        return harness;
    }

    static S2SpecialStageReplayHarness bootHarness(SpecialStageTraceData trace,
                                                   Path dir,
                                                   File romFile) throws IOException {
        return bootHarness(trace, dir, romFile, null);
    }

    private static S2SpecialStageReplayHarness bootHarness(
            SpecialStageTraceData trace,
            Path dir,
            File romFile,
            SegmentContext context) throws IOException {
        // Headless graphics so the SS manager's pattern/renderer setup is safe
        // without a GL context.
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        // Installs the Sonic2GameModule, wires GameServices.rom(), and rebuilds a
        // fresh gameplay mode. Resets configuration to defaults, so team config is
        // (re)applied inside the harness ctor afterwards.
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        if (context != null && context.manifest() != null) {
            S2SpecialStagePredecessorReplay.replayInheritedPrefix(
                    context.movieDirectory(), context.manifest(),
                    context.segmentIndex());
        }

        int offset = trace.metadata().bk2FrameOffset();
        int ssIndex = specialStageIndex(trace);
        Path bk2 = dir.resolve(trace.metadata().sourceBk2());
        return new S2SpecialStageReplayHarness(bk2, offset, ssIndex);
    }

    // ==================== Comparator ====================

    static DivergenceReport compareReplay(SpecialStageTraceData trace,
                                          S2SpecialStageReplayHarness harness) {
        OptionalInt ssFinished = trace.stageFinishedFrame();
        OptionalInt ssFinishedObserved = trace.stageFinishedObservedFrame();
        int compareEnd = ssFinished.orElse(trace.frameCount());
        Set<Integer> ringsToGoRefreshFrames = ringsToGoRefreshFrames(trace);

        List<FrameComparison> comparisons = new ArrayList<>();
        // One replay owns one dynamic-art delivery-id origin.
        DynamicArtSpecialStageComparator dynamicArt =
                new DynamicArtSpecialStageComparator();
        // Rebind recorder submission edges that spilled past a frame boundary
        // (lag rows) back to the pass that produced them; the engine publishes
        // each RunObjects pass atomically. See DynamicArtSpillNormalization.
        Map<Integer, TraceEvent.DynamicArtTransferState> expectedDynamicArtRows =
                normalizedDynamicArtRows(trace);
        boolean[] dynamicArtCompared = new boolean[trace.frameCount()];
        int firstEngineFinished = -1;
        SpecialStageRunObjectsPassBinder passBinder =
                new SpecialStageRunObjectsPassBinder(
                        trace.runObjectsEndSnapshots(),
                        trace.frameCount(),
                        frame -> !trace.getFrame(frame).lag()
                                || (ssFinishedObserved.isPresent()
                                && frame == ssFinishedObserved.getAsInt()),
                        trace.metadata().bk2FrameOffset(),
                        harness.movieFrames());
        int passPacingStart = passPacingStart(trace, Integer.MAX_VALUE);
        Map<Integer, TraceEvent.StateSnapshot> preStartPassesBySampleFrame =
                preStartPassesBySampleFrame(trace, passPacingStart);
        int terminalPreStartSequence = terminalPreStartPassSequence(trace);
        int entryFrame = specialStageEntryFrame(trace);
        int stageStateVisibleFrame = stageStateVisibleFrame(trace, entryFrame);

        for (int f = 0; f < compareEnd; f++) {
            SpecialStageTraceFrame tf = trace.getFrame(f);
            List<CompletedPass> completedPasses = passBinder.passesForObservation(f);
            if (f < passPacingStart) {
                if (tf.lag()) {
                    harness.stepLagRow();
                    addDynamicArtComparison(
                            comparisons, trace, expectedDynamicArtRows, harness, f,
                            new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
                    continue;
                }
                if (f >= entryFrame) {
                    harness.stepFrame(f);
                } else {
                    // Observed before the ROM's special-stage entry ran: no
                    // special-stage code executed on this V-int. See
                    // specialStageEntryFrame.
                    harness.stepIdleRow(false);
                }
                if (f < stageStateVisibleFrame) {
                    // Special-stage RAM does not exist yet. See
                    // stageStateVisibleFrame.
                    addDynamicArtComparison(
                            comparisons, trace, expectedDynamicArtRows, harness, f,
                            new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
                    continue;
                }
            } else {
                harness.stepPasses(
                        completedPasses,
                        terminalPreStartSequence,
                        tf.lag(),
                        f);
                if (tf.lag()) {
                    addDynamicArtComparison(
                            comparisons, trace, expectedDynamicArtRows, harness, f,
                            new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
                    continue;
                }
            }
            Sonic2SpecialStageComparisonState state = harness.capture();
            if (firstEngineFinished < 0 && state.finished()) {
                firstEngineFinished = f;
            }

            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            List<TraceEvent> passEnd = f >= passPacingStart
                    ? passBinder.latestCompleted()
                            .map(CompletedPass::snapshot)
                            .<List<TraceEvent>>map(List::of)
                            .orElseGet(List::of)
                    : preStartPassStartedOn(preStartPassesBySampleFrame, f);
            SpecialStageExpectedState expected =
                    SpecialStageExpectedState.from(tf, passEnd);
            addManagerFields(fields, expected, state, false,
                    ringsToGoRefreshFrames.contains(f));
            addPlayerFields(fields, "sonic", expected.sonic(), state.sonic(),
                    expected.hasRunObjectsEnd());
            addPlayerFields(fields, "tails", expected.tails(), state.tails(),
                    expected.hasRunObjectsEnd());
            addDynamicArtComparison(
                    comparisons, trace, expectedDynamicArtRows, harness, f, fields,
                    dynamicArtCompared, dynamicArt);
        }

        // Tier-1 finish transition: step the recorded finish frame so we can
        // observe whether the engine flips isFinished() at the same frame.
        if (ssFinished.isPresent()) {
            int sf = ssFinished.getAsInt();
            String finishTransitionActual = "never";
            if (sf >= 0 && sf < trace.frameCount()) {
                List<CompletedPass> completedPasses = passBinder.passesForObservation(sf);
                if (sf < passPacingStart) {
                    if (trace.getFrame(sf).lag()) {
                        harness.stepLagRow();
                    } else {
                        harness.stepFrame(sf);
                    }
                } else {
                    harness.stepPasses(
                            completedPasses, terminalPreStartSequence,
                            trace.getFrame(sf).lag(),
                            sf);
                }
                addDynamicArtComparison(
                        comparisons, trace, expectedDynamicArtRows, harness, sf,
                        new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
            }
            // The recorder labels finish with the last logical non-lag frame,
            // while publishing the finish-causing pass at the following raw lag
            // observation. The engine must still be unfinished immediately
            // before that pass, and must become finished because of it.
            boolean finishedBeforeTerminal = firstEngineFinished >= 0 || harness.isFinished();
            if (finishedBeforeTerminal) {
                finishTransitionActual = "before-terminal-pass@" + sf;
            }
            int observed = ssFinishedObserved.orElse(sf);
            if (observed <= sf || observed >= trace.frameCount()) {
                throw new IllegalStateException(
                        "stage finish must have one later raw observation; logical="
                                + sf + " observed=" + observed);
            }
            for (int frame = sf + 1; frame < observed; frame++) {
                harness.stepIdleRow(trace.getFrame(frame).lag());
                addDynamicArtComparison(
                        comparisons, trace, expectedDynamicArtRows, harness, frame,
                        new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
            }
            List<CompletedPass> finishPasses = passBinder.passesForObservation(observed);
            if (finishPasses.size() != 1) {
                throw new IllegalStateException(
                        "stage finish observation must own exactly one completed pass; got "
                                + finishPasses.size());
            }
            CompletedPass terminalPass = finishPasses.get(0);
            harness.stepPasses(
                    List.of(terminalPass), terminalPreStartSequence,
                    trace.getFrame(observed).lag(),
                    observed);
            Sonic2SpecialStageComparisonState terminalState = harness.capture();
            if (!finishedBeforeTerminal && harness.isFinished()) {
                finishTransitionActual = String.valueOf(sf);
            }
            if (passBinder.hasRemaining()) {
                throw new IllegalStateException(
                        "run_objects_end passes remain after the terminal finish observation");
            }
            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            String expected = String.valueOf(sf);
            fields.put("finished_transition_frame",
                    cmp("finished_transition_frame", expected, finishTransitionActual,
                            Severity.ERROR));
            comparisons.add(new FrameComparison(sf, fields));

            SpecialStageExpectedState terminalExpected = SpecialStageExpectedState.from(
                    trace.getFrame(observed), List.of(terminalPass.snapshot()));
            Map<String, FieldComparison> terminalFields = new LinkedHashMap<>();
            addRingsToGoField(terminalFields, terminalExpected, terminalState);
            addDynamicArtComparison(
                    comparisons, trace, expectedDynamicArtRows, harness, observed, terminalFields,
                    dynamicArtCompared, dynamicArt);
        }

        if (trace.metadata().hasPerFrameDynamicArtTransferState()) {
            int fadeRowsLeft = ssFinished.isPresent() ? PAL_FADE_TO_WHITE_FRAMES : 0;
            for (int frame = 0; frame < trace.frameCount(); frame++) {
                if (dynamicArtCompared[frame]) {
                    continue;
                }
                PlcLifecyclePhase phase = PlcLifecyclePhase.SPECIAL_STAGE_RESULTS;
                if (fadeRowsLeft > 0 && !trace.getFrame(frame).lag()) {
                    phase = PlcLifecyclePhase.PALETTE_FADE;
                    fadeRowsLeft--;
                }
                harness.stepIdleRow(trace.getFrame(frame).lag(), phase);
                addDynamicArtComparison(
                        comparisons, trace, expectedDynamicArtRows, harness, frame,
                        new LinkedHashMap<>(), dynamicArtCompared, dynamicArt);
            }
        }
        return new DivergenceReport(comparisons);
    }

    /**
     * Pre-start {@code RunObjects} passes indexed by the observation whose
     * {@code Vint_S2SS} sampled their input.
     *
     * <p>The pre-start wait loop runs one {@code WaitForVint} and one
     * {@code jsr (RunObjects).l} per iteration (docs/s2disasm/s2.asm:6674-6691),
     * and the engine publishes that pass inside the same displayed frame. The
     * observation's own atomic {@code run_objects_end} snapshot is therefore the
     * expectation for its player/ring/Tails-control fields — the raw VBlank row
     * is not, because it can bisect the pass's Obj09→Obj10 scan and hold one
     * player's post-pass state beside the other's pre-pass state. That is the
     * rule this comparator already applies once the recurring loop takes over;
     * the pre-start half of the same loop gets it for the same reason.
     */
    private static Map<Integer, TraceEvent.StateSnapshot> preStartPassesBySampleFrame(
            SpecialStageTraceData trace, int passPacingStart) {
        Map<Integer, TraceEvent.StateSnapshot> bySample = new LinkedHashMap<>();
        for (TraceEvent.StateSnapshot snapshot : trace.runObjectsEndSnapshots()) {
            if (snapshot.frame() >= passPacingStart) {
                continue;
            }
            if (numericTraceValue(requireField(snapshot, "pass_sequence")) == 0) {
                // Sequence 0 is not a wait-loop iteration. The special-stage
                // entry runs exactly one RunObjects before the fade
                // (docs/s2disasm/s2.asm:6660-6672): after the SSObjectsManager
                // duration loop's last WaitForVint it does
                // Obj5A_CreateRingsToGoText, SS_ScrollBG, RunObjects,
                // BuildSprites and RunPLC_RAM before Pal_FadeFromWhite. That
                // whole span overruns its displayed frame, so the row that
                // sampled its input still holds clean pre-pass state -- it is
                // not a bisected row, and the engine's own startup pass
                // publishes at the same boundary. Its results belong to the
                // observation the binder already gives it.
                continue;
            }
            Object sample = snapshot.fields().get("input_sample_frame");
            if (sample == null) {
                continue;
            }
            bySample.put(numericTraceValue(sample), snapshot);
        }
        return bySample;
    }

    private static List<TraceEvent> preStartPassStartedOn(
            Map<Integer, TraceEvent.StateSnapshot> bySampleFrame, int frame) {
        TraceEvent.StateSnapshot snapshot = bySampleFrame.get(frame);
        return snapshot == null ? List.of() : List.of(snapshot);
    }

    /**
     * Delegates to the shared owner so this lane and the complete-run chain's
     * special-stage interior compare a segment identically however it was
     * reached.
     */
    static Map<Integer, TraceEvent.DynamicArtTransferState> normalizedDynamicArtRows(
            SpecialStageTraceData trace) {
        return DynamicArtSpillNormalization.forSpecialStage(trace);
    }

    private static void addDynamicArtComparison(
            List<FrameComparison> comparisons,
            SpecialStageTraceData trace,
            Map<Integer, TraceEvent.DynamicArtTransferState> expectedDynamicArt,
            S2SpecialStageReplayHarness harness,
            int frame,
            Map<String, FieldComparison> fields,
            boolean[] compared,
            DynamicArtSpecialStageComparator dynamicArt) {
        if (trace.metadata().hasPerFrameDynamicArtTransferState()) {
            if (frame == trace.frameCount() - 1) {
                harness.finishDynamicArtSegment();
            }
            fields.putAll(dynamicArt.compare(
                    expectedDynamicArt.getOrDefault(frame,
                            trace.dynamicArtTransferStateForFrame(frame)),
                    harness.captureDynamicArt()).fields());
            compared[frame] = true;
        }
        if (!fields.isEmpty()) {
            comparisons.add(new FrameComparison(frame, fields));
        }
    }

    private static void addManagerFields(Map<String, FieldComparison> fields,
                                         SpecialStageExpectedState expected,
                                         Sonic2SpecialStageComparisonState state,
                                         boolean finishedExpected,
                                         boolean ringsToGoRefreshActive) {
        SpecialStageTraceFrame tf = expected.csv();
        SpecialStageExpectedState.RunObjectsEndState pass = expected.runObjectsEnd();
        int speedFactor = pass != null ? pass.speedFactor() : tf.speedFactor();
        int currentSegment = pass != null ? pass.currentSegment() : tf.currentSegment();
        int trackAnimFrame = pass != null ? pass.trackAnimFrame() : tf.trackAnimFrame();
        int trackDrawingIndex = pass != null ? pass.trackDrawingIndex() : tf.trackDrawingIndex();
        int trackDurationTimer = pass != null ? pass.trackDurationTimer() : tf.trackDurationTimer();
        // Tier-1
        fields.put("speed_factor",
                cmp("speed_factor", str(speedFactor), str(state.speedFactor()), Severity.ERROR));
        fields.put("current_segment",
                cmp("current_segment", str(currentSegment), str(state.currentSegmentIndex()), Severity.ERROR));
        fields.put("track_anim_frame",
                cmp("track_anim_frame", str(trackAnimFrame), str(state.trackAnimFrame()), Severity.ERROR));
        fields.put("combined_rings",
                cmp("combined_rings", str(expected.combinedRings()), str(state.combinedRings()), Severity.ERROR));
        fields.put("finished",
                cmp("finished", String.valueOf(finishedExpected), String.valueOf(state.finished()), Severity.ERROR));

        fields.put("track_drawing_index",
                cmp("track_drawing_index", str(trackDrawingIndex), str(state.drawingIndex()), Severity.ERROR));
        int expectedCounter = mapTrackDurationElapsed(speedFactor, trackDurationTimer);
        fields.put("track_duration_timer",
                cmp("track_duration_timer", str(expectedCounter), str(state.trackFrameDelayCounter()), Severity.ERROR));
        fields.put("tails_control_counter",
                cmp("tails_control_counter", str(expected.tailsControlCounter()), str(state.tailsControlCounter()), Severity.ERROR));
        int playerAnimFrameTimer = pass != null
                ? pass.playerAnimFrameTimer() : tf.playerAnimFrameTimer();
        fields.put("player_anim_frame_timer",
                cmp("player_anim_frame_timer", str(playerAnimFrameTimer),
                        str(state.playerAnimFrameTimer()), Severity.ERROR));
        if (ringsToGoRefreshActive) {
            addRingsToGoField(fields, expected, state);
        }
        int swapPositionsFlag = pass != null ? pass.swapPositionsFlag() : tf.swapPositionsFlag();
        fields.put("swap_positions_flag",
                cmp("swap_positions_flag", str(swapPositionsFlag),
                        str(state.swapPositionsFlag()), Severity.ERROR));
    }

    private static void addRingsToGoField(
            Map<String, FieldComparison> fields,
            SpecialStageExpectedState expected,
            Sonic2SpecialStageComparisonState state) {
        SpecialStageExpectedState.RunObjectsEndState pass = expected.runObjectsEnd();
        int ringsToGoBcd = pass != null
                ? pass.ringsToGoBcd() : expected.csv().ringsToGoBcd();
        fields.put("rings_togo_bcd",
                cmp("rings_togo_bcd", str(decodeRingsToGoBcd(ringsToGoBcd)),
                        str(state.ringsToGo()), Severity.ERROR));
    }

    /** Focused comparison seam used by atomic RunObjects-end mapping tests. */
    static Map<String, FieldComparison> compareExpectedFrame(
            SpecialStageExpectedState expected,
            Sonic2SpecialStageComparisonState state) {
        return compareExpectedFrame(expected, state, false);
    }

    static Map<String, FieldComparison> compareExpectedFrame(
            SpecialStageExpectedState expected,
            Sonic2SpecialStageComparisonState state,
            boolean ringsToGoRefreshActive) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        addManagerFields(fields, expected, state, false, ringsToGoRefreshActive);
        addPlayerFields(fields, "sonic", expected.sonic(), state.sonic(),
                expected.hasRunObjectsEnd());
        addPlayerFields(fields, "tails", expected.tails(), state.tails(),
                expected.hasRunObjectsEnd());
        return fields;
    }

    /** Decodes the ROM's packed three-digit {@code SS_RingsToGoBCD} word. */
    static int decodeRingsToGoBcd(int packedBcd) {
        if ((packedBcd & ~0x0FFF) != 0) {
            throw new IllegalArgumentException(
                    "rings-to-go BCD has bits outside 12-bit field: 0x"
                            + Integer.toHexString(packedBcd));
        }
        int hundreds = (packedBcd >> 8) & 0xF;
        int tens = (packedBcd >> 4) & 0xF;
        int units = packedBcd & 0xF;
        if (hundreds > 9 || tens > 9 || units > 9) {
            throw new IllegalArgumentException(
                    "rings-to-go field is not packed BCD: 0x"
                            + Integer.toHexString(packedBcd));
        }
        return hundreds * 100 + tens * 10 + units;
    }

    static boolean isRingsToGoRefreshFrame(SpecialStageTraceData trace, int frame) {
        return ringsToGoRefreshFrames(trace).contains(frame);
    }

    /**
     * Sequence of the terminal pre-start {@code RunObjects} pass, or -1 when
     * the stream holds no pre-start pass at all.
     *
     * <p>{@code SpecialStage_MainLoop} is two loops, not one
     * (docs/s2disasm/s2.asm:6674-6721). The pre-start loop runs its own
     * {@code jsr (RunObjects).l} and only then tests
     * {@code SpecialStage_Started} at the bottom (s2.asm:6691-6692), so the
     * flag Obj5F set (s2.asm:9745) is not observed until after that pass
     * returned. Every pass therefore records the flag as sampled by the
     * {@code Vint_S2SS ReadJoypads} that preceded it, and the first pass whose
     * sample already saw the flag set is by construction the first iteration of
     * the recurring loop; the pass before it is the terminal pre-start pass
     * that set it. Neither boundary is read off a frame index.
     *
     * <p>The engine models the same split: pre-start iterations execute their
     * pass inline at their own V-int, while the terminal one is held pending so
     * the recurring loop's binding has a slot
     * ({@code Sonic2SpecialStageManager.completeTerminalPreStartPassWithoutVint}).
     * Replay therefore consumes the recorded terminal pass through that
     * boundary rather than binding recurring-loop input to it.
     */
    static int terminalPreStartPassSequence(SpecialStageTraceData trace) {
        int terminal = -1;
        for (TraceEvent.StateSnapshot snapshot : passesInSequenceOrder(trace)) {
            if (startedAtInputSample(snapshot) != 0) {
                break;
            }
            terminal = numericTraceValue(requireField(snapshot, "pass_sequence"));
        }
        return terminal;
    }

    /**
     * The observation from which replay is paced by recorded object passes: the
     * publication observation of the first recurring-loop pass. See
     * {@link #terminalPreStartPassSequence} for why that pass is identified by
     * its own {@code SpecialStage_Started} input sample.
     *
     * <p>A stream recorded before the pre-start hook existed carries no
     * pre-start pass; there the first recorded pass is already the recurring
     * loop's and this reduces to that pass's observation just the same.
     */
    static int passPacingStart(SpecialStageTraceData trace, int absent) {
        for (TraceEvent.StateSnapshot snapshot : passesInSequenceOrder(trace)) {
            if (startedAtInputSample(snapshot) != 0) {
                return snapshot.frame();
            }
        }
        return absent;
    }

    private static List<TraceEvent.StateSnapshot> passesInSequenceOrder(
            SpecialStageTraceData trace) {
        return trace.runObjectsEndSnapshots().stream()
                .sorted(java.util.Comparator.comparingInt(snapshot ->
                        numericTraceValue(requireField(snapshot, "pass_sequence"))))
                .toList();
    }

    private static int startedAtInputSample(TraceEvent.StateSnapshot snapshot) {
        return numericTraceValue(requireField(snapshot, "started_at_input_sample"));
    }

    private static Object requireField(TraceEvent.StateSnapshot snapshot, String name) {
        Object value = snapshot.fields().get(name);
        if (value == null) {
            throw new IllegalStateException(
                    "run_objects_end is missing " + name + " at frame " + snapshot.frame());
        }
        return value;
    }

    /**
     * The first observation on which the ROM ran special-stage code.
     *
     * <p>The special-stage entry runs {@code Pal_FadeToWhite} and only then
     * masks interrupts to set up the VDP, DMA-fill VRAM, clear the object and
     * shared RAM, decompress the stage and run the entry PLC (s2.asm:6543-6625).
     * That whole initialization span blocks, so the recorder reads it as one
     * run of lag observations, and the executed V-ints immediately before it
     * are the fade's -- exactly
     * {@link Sonic2SpecialStageIntro#PRE_ROLL_FRAMES} of them, because
     * {@code Pal_FadeToWhite} loads {@code d4=$15} and uses {@code dbf}
     * (s2.asm:3568-3578).
     *
     * <p>Any observation earlier than that window belongs to the game mode the
     * entry code was called from and executes no special-stage code, so replay
     * must not tick the special-stage runtime for it. Whether such an
     * observation is present is a property of where the capture begins, not of
     * the ROM: a standalone capture opens on the frame the entry code was
     * reached, while a run segment leaves that frame with the preceding
     * segment.
     */
    static int specialStageEntryFrame(SpecialStageTraceData trace) {
        int initializationStart = -1;
        for (int f = 0; f < trace.frameCount(); f++) {
            if (trace.getFrame(f).lag()) {
                initializationStart = f;
                break;
            }
        }
        if (initializationStart < 0) {
            throw new IllegalStateException(
                    "special-stage trace has no blocking initialization observation");
        }
        int entryFrame =
                initializationStart - Sonic2SpecialStageIntro.PRE_ROLL_FRAMES;
        if (entryFrame < 0) {
            throw new IllegalStateException(
                    "special-stage trace opens inside Pal_FadeToWhite: blocking"
                            + " initialization begins at " + initializationStart);
        }
        return entryFrame;
    }

    /**
     * The first observation whose special-stage columns actually hold
     * special-stage state.
     *
     * <p>The entry code masks interrupts before it clears {@code Object_RAM},
     * {@code SS_Shared_RAM} and the sprite table and then loads the stage
     * (s2.asm:6588-6625). Until that initialization completes, the recorder's
     * special-stage addresses still hold whatever the previous game mode left
     * there — a run segment captured out of a level opens with that level's
     * Obj01 bytes sitting in the player slots. Those observations are stepped
     * for their pacing, but comparing engine special-stage state against them
     * compares against another mode's RAM.
     *
     * <p>The whole initialization runs with interrupts masked or blocking, so
     * the recorder reads it as one run of lag observations: state becomes
     * visible on the first executed V-int after it, which is the ROM's first
     * {@code SSTrack_drawing_index} wait (s2.asm:6626-6631).
     */
    private static int stageStateVisibleFrame(SpecialStageTraceData trace, int entryFrame) {
        int f = entryFrame;
        while (f < trace.frameCount() && !trace.getFrame(f).lag()) {
            f++;
        }
        while (f < trace.frameCount() && trace.getFrame(f).lag()) {
            f++;
        }
        if (f >= trace.frameCount()) {
            throw new IllegalStateException(
                    "special-stage trace never leaves its initialization block");
        }
        return f;
    }

    /**
     * Selects only observations where the ROM cell is known to have been
     * refreshed. Clearing {@code SS_TriggerRingsToGo} enables Obj5A's
     * calculation, so the first completed object pass strictly after that
     * transition is comparable. Later passes are not: a ring in a later SST
     * slot can change the live total after Obj5A already stored the BCD cell.
     * A rising {@code SS_Check_Rings_flag} is itself an explicit refresh gate
     * (s2.asm:71411-71582,71814-71842).
     */
    private static Set<Integer> ringsToGoRefreshFrames(SpecialStageTraceData trace) {
        List<TriggerSample> triggerSamples = new ArrayList<>();
        List<Integer> checkRingsFlags = new ArrayList<>();
        List<Integer> finishObservedFrames = new ArrayList<>();
        // SS_TriggerRingsToGo and SS_Check_Rings_flag both live in
        // SS_Shared_RAM, which the entry code clears only after it masks
        // interrupts. A sample taken before that reports the byte the previous
        // game mode left behind rather than this stage's cell, so those
        // observations read as the cleared value the ROM is about to write.
        // See stageStateVisibleFrame.
        int stageStateVisibleFrame =
                stageStateVisibleFrame(trace, specialStageEntryFrame(trace));
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            if (frame < stageStateVisibleFrame) {
                checkRingsFlags.add(0);
                continue;
            }
            checkRingsFlags.add(trace.getFrame(frame).checkRingsFlag());

            for (TraceEvent event : trace.getEventsForFrame(frame)) {
                if (!(event instanceof TraceEvent.StateSnapshot snapshot)) {
                    continue;
                }
                if ("message_state".equals(snapshot.fields().get("type"))) {
                    Object rawTrigger = snapshot.fields().get("trigger_rings_to_go");
                    if (rawTrigger != null) {
                        triggerSamples.add(new TriggerSample(
                                frame, numericTraceValue(rawTrigger)));
                    }
                }
                if ("stage_finished".equals(snapshot.fields().get("type"))) {
                    Object rawObserved = snapshot.fields().get("observed_frame");
                    if (rawObserved == null) {
                        throw new IllegalStateException(
                                "stage_finished is missing observed_frame");
                    }
                    finishObservedFrames.add(numericTraceValue(rawObserved));
                }
            }
        }

        List<PassSample> passSamples = new ArrayList<>();
        for (TraceEvent.StateSnapshot snapshot : trace.runObjectsEndSnapshots()) {
            Object rawCursor = snapshot.fields().get("completion_cursor_frame");
            if (rawCursor == null) {
                throw new IllegalStateException(
                        "run_objects_end is missing completion_cursor_frame at frame "
                                + snapshot.frame());
            }
            passSamples.add(new PassSample(
                    snapshot.frame(), numericTraceValue(rawCursor)));
        }
        return discoverRingsToGoRefreshFrames(
                triggerSamples,
                passSamples,
                checkRingsFlags,
                finishObservedFrames);
    }

    record TriggerSample(int frame, int value) {
    }

    /**
     * A completed {@code run_objects_end} pass: the frame the row is published
     * on, and the frame its object work actually completed against.
     */
    record PassSample(int frame, int completionCursorFrame) {
    }

    static Set<Integer> discoverRingsToGoRefreshFrames(
            List<TriggerSample> triggerSamples,
            List<PassSample> completedPasses,
            List<Integer> checkRingsFlags,
            List<Integer> finishObservedFrames) {
        if (triggerSamples.isEmpty() || triggerSamples.get(0).value() != 0xFF) {
            throw new IllegalStateException(
                    "rings-to-go artifact is missing initial FF trigger sample");
        }

        List<Integer> triggerClearFrames = new ArrayList<>();
        int previousTrigger = 0xFF;
        int previousSampleFrame = -1;
        for (TriggerSample sample : triggerSamples) {
            if (sample.frame() <= previousSampleFrame) {
                throw new IllegalStateException(
                        "duplicate or out-of-order rings-to-go trigger sample at frame "
                                + sample.frame());
            }
            previousSampleFrame = sample.frame();
            if (sample.value() != 0 && sample.value() != 0xFF) {
                throw new IllegalStateException(
                        "ambiguous rings-to-go trigger value " + sample.value()
                                + " at frame " + sample.frame());
            }
            if (sample.value() == previousTrigger) {
                continue;
            }
            if (previousTrigger == 0xFF && sample.value() == 0) {
                triggerClearFrames.add(sample.frame());
            } else if (previousTrigger != 0 || sample.value() != 0xFF) {
                throw new IllegalStateException(
                        "ambiguous rings-to-go trigger transition at frame "
                                + sample.frame());
            }
            previousTrigger = sample.value();
        }
        if (triggerClearFrames.isEmpty() || previousTrigger != 0) {
            throw new IllegalStateException(
                    "rings-to-go artifact is missing a completed FF-to-00 trigger clear");
        }

        Set<Integer> refreshFrames = new LinkedHashSet<>();
        for (int gateFrame : triggerClearFrames) {
            // Obj5A_RingsNeeded reads SS_TriggerRingsToGo during the object
            // pass, so a pass that completed on the same frame the cleared
            // trigger was first observed still ran with the trigger set and
            // left SS_RingsToGoBCD at its stale value (s2.asm:71568-71574).
            // Only a pass whose completion cursor is strictly past the
            // observation recomputed the cell.
            int refreshFrame = completedPasses.stream()
                    .filter(pass -> pass.completionCursorFrame() > gateFrame)
                    .mapToInt(PassSample::frame)
                    .min()
                    .orElseThrow(() -> new IllegalStateException(
                            "rings-to-go trigger clear at frame " + gateFrame
                                    + " has no following completed pass"));
            long passCountAtObservation = completedPasses.stream()
                    .filter(pass -> pass.frame() == refreshFrame)
                    .count();
            if (passCountAtObservation != 1) {
                throw new IllegalStateException(
                        "rings-to-go refresh observation " + refreshFrame
                                + " owns multiple completed passes ("
                                + passCountAtObservation + ")");
            }
            if (!refreshFrames.add(refreshFrame)) {
                throw new IllegalStateException(
                        "multiple rings-to-go trigger clears map to pass frame "
                                + refreshFrame);
            }
        }

        List<Integer> checkRiseFrames = new ArrayList<>();
        int previousCheckRings = 0;
        for (int frame = 0; frame < checkRingsFlags.size(); frame++) {
            int checkRings = checkRingsFlags.get(frame);
            if (previousCheckRings == 0 && checkRings != 0) {
                checkRiseFrames.add(frame);
            }
            previousCheckRings = checkRings;
        }
        if (checkRiseFrames.size() != 1) {
            throw new IllegalStateException(
                    "rings-to-go artifact requires exactly one terminal check-ring rise; got "
                            + checkRiseFrames.size());
        }
        if (finishObservedFrames.size() != 1) {
            throw new IllegalStateException(
                    "rings-to-go artifact requires exactly one stage_finished observation; got "
                            + finishObservedFrames.size());
        }
        int checkRiseFrame = checkRiseFrames.get(0);
        int finishObservedFrame = finishObservedFrames.get(0);
        if (checkRiseFrame != finishObservedFrame) {
            throw new IllegalStateException(
                    "terminal check-ring rise frame " + checkRiseFrame
                            + " does not match stage_finished observed frame "
                            + finishObservedFrame);
        }
        refreshFrames.add(checkRiseFrame);
        return refreshFrames;
    }

    private static int numericTraceValue(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(raw);
        return text.startsWith("0x") || text.startsWith("0X")
                ? Integer.parseUnsignedInt(text.substring(2), 16)
                : Integer.parseInt(text);
    }

    static int mapTrackDurationElapsed(int speedFactor, int rawDurationTimer) {
        if (speedFactor == 0 && rawDurationTimer == 0) {
            return 0;
        }
        int duration = ANIM_BASE_DURATIONS[(speedFactor >> 1) & 7];
        return duration - rawDurationTimer;
    }

    private static void addPlayerFields(Map<String, FieldComparison> fields,
                                        String prefix,
                                        CharacterState tc,
                                        PlayerState ps,
                                        boolean atomicPassEnd) {
        boolean tracePresent = tc != null && tc.present();
        boolean engPresent = ps != null;
        fields.put(prefix + "_present",
                cmp(prefix + "_present", bool(tracePresent), bool(engPresent), Severity.ERROR));
        if (!tracePresent && !engPresent) {
            return;
        }

        String expRoutine = tracePresent ? mapRoutine(tc.routine()) : ABSENT;
        String engRoutine = engPresent ? ps.routine() : ABSENT;
        fields.put(prefix + "_routine",
                cmp(prefix + "_routine", expRoutine, engRoutine, Severity.ERROR));

        fields.put(prefix + "_ss_x", cmp(prefix + "_ss_x",
                intOr(tracePresent, tracePresent ? signedWord(tc.ssX()) : 0),
                intOr(engPresent, engPresent ? ps.ssX() : 0), Severity.ERROR));
        fields.put(prefix + "_ss_y", cmp(prefix + "_ss_y",
                intOr(tracePresent, tracePresent ? signedWord(tc.ssY()) : 0),
                intOr(engPresent, engPresent ? ps.ssY() : 0), Severity.ERROR));
        fields.put(prefix + "_ss_z", cmp(prefix + "_ss_z",
                intOr(tracePresent, tracePresent ? tc.ssZ() : 0),
                intOr(engPresent, engPresent ? ps.ssZ() : 0), Severity.ERROR));
        fields.put(prefix + "_angle", cmp(prefix + "_angle",
                intOr(tracePresent, tracePresent ? tc.angle() : 0),
                intOr(engPresent, engPresent ? ps.angle() : 0), Severity.ERROR));

        boolean traceHurt = tracePresent && tc.routineSecondary() == 2;
        boolean engHurt = engPresent && ps.routineSecondary() == 2;
        fields.put(prefix + "_hurt",
                cmp(prefix + "_hurt", bool(traceHurt), bool(engHurt), Severity.ERROR));
        fields.put(prefix + "_rings", cmp(prefix + "_rings",
                intOr(tracePresent, tracePresent ? tc.ringsBinary() : 0),
                intOr(engPresent, engPresent ? ps.rings() : 0), Severity.ERROR));
        // A VBlank CSV sample can bisect RunObjects between Obj09 and Obj10
        // (the committed trace does so at f160 and f183). Player-owned timers
        // are therefore compared only against the recorder's atomic pass-end
        // snapshot, never against a mixed raw observation.
        if (atomicPassEnd) {
            fields.put(prefix + "_hurt_timer", cmp(prefix + "_hurt_timer",
                    intOr(tracePresent, tracePresent ? tc.hurtTimer() : 0),
                    intOr(engPresent, engPresent ? ps.hurtTimer() : 0), Severity.ERROR));
            fields.put(prefix + "_slide_timer", cmp(prefix + "_slide_timer",
                    intOr(tracePresent, tracePresent ? tc.slideTimer() : 0),
                    intOr(engPresent, engPresent ? ps.slideTimer() : 0), Severity.ERROR));
            fields.put(prefix + "_flip_timer", cmp(prefix + "_flip_timer",
                    intOr(tracePresent, tracePresent ? tc.flipTimer() : 0),
                    intOr(engPresent, engPresent ? ps.flipTimer() : 0), Severity.ERROR));
        }
    }

    /**
     * Maps a raw 68000 word to the signed value used by the ROM's track-space
     * coordinate arithmetic. {@code ss_x_pos}/{@code ss_y_pos} are the integer
     * words of 16.16 positions and are consumed by sign branches and signed
     * multiplies; {@code ss_z_pos} is a separate positive depth word and is not
     * routed through this mapping (s2.asm:69290-69303, 69324-69374,
     * 69450-69458, 69718-69750).
     */
    private static int signedWord(int rawWord) {
        return (short) rawWord;
    }

    private static String mapRoutine(int romRoutineByte) {
        String mapped = ROUTINE_NAMES.get(romRoutineByte);
        return mapped != null
                ? mapped
                : String.format("ROM_0x%02X", romRoutineByte & 0xFF);
    }

    private static String intOr(boolean present, int value) {
        return present ? Integer.toString(value) : ABSENT;
    }

    // ==================== Report output ====================

    static TestSessionOutputPaths.ReportAllocation writeReport(
            DivergenceReport report, int ssIndex) throws IOException {
        return TraceReportWriter.writeSpecialStageReport(
                report, "special-stage", SessionInvocationExtension.SessionInvocation.current(),
                "s2-" + ssIndex, "s2_special_stage_" + ssIndex, CONTEXT_RADIUS);
    }

    static Path reportDir() {
        return TestSessionOutputPaths.traceReports();
    }

    static int specialStageIndex(SpecialStageTraceData trace) {
        Integer index = trace.metadata().specialStageIndex();
        return index != null ? index : 0;
    }
}
