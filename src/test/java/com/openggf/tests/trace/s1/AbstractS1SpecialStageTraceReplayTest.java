package com.openggf.tests.trace.s1;

import com.openggf.data.Rom;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageComparisonState;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceData;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageTraceFrame;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.tests.trace.TraceFixtureRoot;
import com.openggf.tests.trace.TraceReportWriter;
import com.openggf.trace.DivergenceReport;
import com.openggf.trace.DynamicArtSpecialStageComparator;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
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

import static com.openggf.tests.trace.TraceFieldComparisons.bool;
import static com.openggf.tests.trace.TraceFieldComparisons.cmp;
import static com.openggf.tests.trace.TraceFieldComparisons.str;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Headless replay comparator for a Sonic 1 special-stage (maze) trace. Drives
 * the production
 * {@link com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider}
 * through {@link S1SpecialStageReplayHarness}, comparing each stepped frame
 * against the recorded ROM trace and emitting a divergence report to
 * the session-owned {@code special-stage} report directory (or the legacy
 * {@code target/trace-reports} default when run without a session).
 * Modeled on {@code AbstractS3kSpecialStageTraceReplayTest}, remapped for the
 * S1 maze's single-player physics-only trace columns.
 *
 * <h2>Replay semantics</h2>
 * <ul>
 *   <li><b>VBlank-paced.</b> Every non-lag trace row is stepped through
 *       {@link S1SpecialStageReplayHarness#stepFrame(int)}; lag rows are
 *       skipped (consumed without stepping) since they advance nothing
 *       engine-side.</li>
 *   <li><b>Multi-frame lag boundary (torn-capture guard).</b> A non-lag row
 *       that immediately precedes a run of two or more lag frames MAY be a
 *       partial RAM snapshot (the 68k still mid-iteration when that VBlank
 *       sample fired, so its columns straddle two game-logic steps), but the
 *       lag-run shape alone is only a hint -- {@code isTornLagBoundaryRow}
 *       independently verifies the row's own fields before dropping it: the
 *       early-tick velocity fields must already show the next step's values
 *       while the late-tick position/angle fields still show the previous
 *       step's, matching how the ROM actually orders those writes within one
 *       tick. A candidate row that precedes such a lag run but is internally
 *       coherent is scored normally, not dropped. A verified-torn row is
 *       still <em>stepped</em> (the engine's cumulative game-logic advances
 *       must stay aligned -- it re-syncs at the next compared row) but is
 *       omitted from the report, since no coherent engine frame can equal a
 *       torn row on every field at once. Not a frame-number carve-out -- see
 *       {@code isTornLagBoundaryRow}.</li>
 *   <li><b>Comparison-only.</b> Trace values are read for input + expectation
 *       only; engine state is never hydrated from the trace.</li>
 *   <li><b>Terminal exit boundary.</b> The S1 maze trace has no in-segment
 *       completion marker analogous to S3K's {@code fade_timer} 0&rarr;
 *       nonzero&rarr;0 cycle -- the recorded segment simply ends at the ROM's
 *       {@code $10} mode exit (ROM leaves the special-stage game mode via the
 *       exit ramp, {@code v_ssrotate} -&gt; {@code $1800},
 *       {@code sonic.asm} {@code SS_MainLoop} exit). Instead of an anchor
 *       frame lookup, a single {@code exit_state_at_end} check is appended
 *       after the loop at the last trace frame index, asserting that by the
 *       final captured engine state the exit sequence has been raised
 *       ({@code state.exitTriggered() || state.finished()}). MVP
 *       red-allowed.</li>
 * </ul>
 *
 * <h2>Release-ratcheted comparator surface</h2>
 * <p>Every mismatch below is a report ERROR (Tier-1), one comparison-only
 * read of {@link Sonic1SpecialStageComparisonState} per CSV column, with
 * equality checked after masking BOTH sides (sign-agnostic):
 * <ul>
 *   <li>{@code x_pos}/{@code y_pos}: {@code tf.xPos() & 0xFFFFFFFFL} vs
 *       {@code state.sonicPosX() & 0xFFFFFFFFL} (compared as
 *       {@code Long.toString}); same for {@code y_pos}/{@code sonicPosY}.</li>
 *   <li>{@code vel_x}/{@code vel_y}/{@code inertia}: {@code & 0xFFFF} both
 *       sides.</li>
 *   <li>{@code status_facing_left}: {@code (tf.status() & 0x1) != 0} vs
 *       {@code state.sonicFacingLeft()}; {@code status_airborne}:
 *       {@code (tf.status() & 0x2) != 0} vs {@code state.sonicAirborne()}.
 *       Only bits 0-1 are modeled here; the raw trace {@code status} column
 *       retains the full byte so a follow-up campaign can wire additional
 *       bits without re-recording.</li>
 *   <li>{@code ss_angle}/{@code ss_rotate}/{@code bg_anim}: {@code & 0xFFFF}
 *       both sides (engine fields may be updated with signed arithmetic; ROM
 *       word equality is what matters). These are special-stage <em>globals</em>
 *       ({@code v_ssangle}/{@code v_ssrotate}) and are compared on every
 *       stepped row, including the pre-start hold, where both sides read 0.</li>
 *   <li>{@code rings}: <b>direct from 0</b> -- expected {@code tf.rings() &
 *       0xFFFF} vs actual {@code state.ringsCollected()}. {@code GM_Special}'s
 *       setup block clears the ring counter unconditionally
 *       ({@code clr.w (v_rings).w}, {@code docs/s1disasm/sonic.asm:3280})
 *       before the maze runs, so the live {@code v_rings} is a fresh count
 *       from 0 directly comparable to the engine's {@code ringsCollected}.
 *       (An earlier delta-vs-frame-0 basis was wrong: frame 0 is recorded
 *       during the pre-start fade and still holds the <em>pre-clear</em>
 *       leftover {@code v_rings} of the previous zone -- {@code $55}/85 in the
 *       committed maze trace -- so subtracting it drove every live value
 *       negative and masked it into a huge {@code u16}. See the pre-start-hold
 *       note below; {@code rings} is only compared once setup has run.)</li>
 *   <li>{@code emeralds}: expected boolean
 *       {@code tf.emeralds() != trace.getFrame(0).emeralds()} vs actual
 *       {@code state.emeraldCollected()} (emeralds persist across stages, so
 *       the frame-0 baseline detects a newly-collected emerald in this run).</li>
 * </ul>
 *
 * <h2>Recorded ROM pre-start hold (comparison-only observation)</h2>
 * <p>{@code GM_Special} runs {@code PaletteWhiteOut}
 * ({@code docs/s1disasm/sonic.asm:3224}) <em>before</em> its instant setup
 * block clears the previous zone's object RAM
 * ({@code clearRAM v_objspace}, {@code sonic.asm:3243}), clears the ring
 * counter ({@code sonic.asm:3280}), sets the stage rotation speed
 * ({@code move.w #ss_rotatespeed,(v_ssrotate).w}, {@code $40},
 * {@code sonic.asm:3268}), and finally lets {@code SS_MainLoop}'s first
 * {@code jsr (ExecuteObjects).l} tick Obj09 for the first time
 * ({@code sonic.asm:3306}). So while the fade is still running, the
 * object-owned physics columns ({@code x_pos}/{@code y_pos}/{@code vel_x}/
 * {@code vel_y}/{@code inertia}/{@code status} bits) and the ring counter
 * hold <em>pre-clear leftover</em> RAM from the preceding zone, not live
 * special-stage state -- the committed trace's frame-0 {@code x_pos}
 * {@code $25AB0300} / {@code vel_x} -34 / {@code status $07} is the previous
 * GHZ Sonic curled into the giant ring, which a standalone special-stage
 * segment cannot reproduce.
 * <p><b>{@code v_ssrotate} is NOT monotonic</b> -- {@code ss_rotate == 0}
 * alone does not uniquely mark the pre-setup fade. Besides the {@code $40}
 * write at setup, {@code SonicSS_ExitStage} resets it straight to 0
 * ({@code move.w #0,(v_ssrotate).w}, {@code docs/s1disasm/_incObj/09 Sonic in
 * Special Stage.asm:394}) once the exit spin-up reaches {@code $3000}, and
 * an R-block touch can negate it ({@code neg.w (v_ssrotate).w}, same file,
 * line 923). Both of those DO occur in the committed maze trace: the emerald
 * pickup's sparkle-animation completion arms
 * {@code SonicSS_ExitStage} (see {@code SS_AniEmeraldSparks},
 * {@code docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:453}), whose
 * spin-up eventually resets {@code v_ssrotate} to 0 during the exit ramp's
 * 60-tick post-reset window. The same {@code move.w #0,(v_ssrotate).w}
 * instruction (asm:394) ALSO pins {@code v_ssangle} to {@code $4000}
 * ({@code move.w #$4000,(v_ssangle).w}, asm:395) in the exact same
 * instruction pair, so that window reads {@code ss_rotate == 0} with
 * {@code ss_angle == $4000} -- distinct from the pre-setup fade, where both
 * globals read 0 together (neither has been written yet). The gate below
 * therefore requires {@code ss_rotate == 0 AND ss_angle == 0} rather than
 * {@code ss_rotate == 0} alone; it is latent-but-live for THIS trace (which
 * ends before the exit ramp reaches that 60-tick window) rather than
 * accidental-by-route, since the companion check generalizes to any recorded
 * segment that runs the exit ramp to completion without a zone/route/frame
 * carve-out.
 *
 * <p>The pipeline writes a complete report and
 * {@link #assertNoReleaseBlockingDivergences} rejects any comparator ERROR.
 */
@ExtendWith(SessionInvocationExtension.class)
public abstract class AbstractS1SpecialStageTraceReplayTest {

    /** Location of the committed trace (when one exists). */
    static final Path TRACE_DIRECTORY =
            Path.of("src", "test", "resources", "traces", "s1", "special_stage");

    private static final int CONTEXT_RADIUS = 8;

    /** Directory of the trace to replay. */
    protected abstract Path traceDirectory();

    @Test
    void replayProducesFaithfulReport() throws Exception {
        File romFile = RomTestUtils.ensureSonic1RomAvailable();
        assumeTrue(romFile != null,
                "s1.gen ROM required for S1 special-stage trace replay");

        Path dir = TraceFixtureRoot.resolve(traceDirectory());
        assumeTrue(Files.exists(dir.resolve("metadata.json")),
                "No S1 special-stage trace committed yet at " + dir);

        Sonic1SpecialStageTraceData trace = Sonic1SpecialStageTraceData.load(dir);

        // Pipeline assertion: the trace loads with the expected profile + frames.
        assertEquals("s1_special_stage", trace.metadata().traceProfile(),
                "SS trace must carry the s1_special_stage profile");
        assertTrue(trace.frameCount() > 0, "SS trace should have frames");

        S1SpecialStageReplayHarness harness = bootHarness(trace, dir, romFile);
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

    // ==================== Boot ====================

    static S1SpecialStageReplayHarness bootHarness(Sonic1SpecialStageTraceData trace,
                                                    Path dir,
                                                    File romFile) throws IOException {
        // Headless graphics so the SS manager's pattern/renderer setup is safe
        // without a GL context.
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();

        Rom rom = new Rom();
        rom.open(romFile.getAbsolutePath());
        // Installs the Sonic1GameModule, wires GameServices.rom(), and rebuilds a
        // fresh gameplay mode. Resets configuration to defaults, so team config is
        // (re)applied inside the harness ctor afterwards.
        TestEnvironment.configureRomFixture(rom);
        GraphicsManager.getInstance().initHeadless();

        int offset = trace.metadata().bk2FrameOffset();
        int ssIndex = specialStageIndex(trace);
        return new S1SpecialStageReplayHarness(
                resolveSourceBk2(dir, trace.metadata().sourceBk2()), offset, ssIndex);
    }

    /**
     * Resolves the recorded input movie named by {@code metadata.source_bk2}.
     * A standalone fixture stores the BK2 beside its own metadata; a run
     * segment ({@code <run>/<segment>/}) shares one movie stored once at the
     * run root, with the segment's {@code bk2_frame_offset} indexing into it.
     * Falls back to the shared {@code _movies/} store used by the generic
     * trace tests. This resolves an <em>input source</em> only -- the movie is
     * controller data, never engine state, so the comparison-only invariant
     * (hard rule 4) is untouched.
     */
    static Path resolveSourceBk2(Path dir, String sourceBk2) {
        Path local = dir.resolve(sourceBk2);
        if (Files.exists(local)) {
            return local;
        }
        Path runRoot = dir.getParent();
        if (runRoot != null) {
            Path atRunRoot = runRoot.resolve(sourceBk2);
            if (Files.exists(atRunRoot)) {
                return atRunRoot;
            }
            Path shared = runRoot.resolve("_movies").resolve(sourceBk2);
            if (Files.exists(shared)) {
                return shared;
            }
        }
        return local;
    }

    // ==================== Comparator ====================

    static DivergenceReport compareReplay(Sonic1SpecialStageTraceData trace,
                                          S1SpecialStageReplayHarness harness) {
        int compareEnd = trace.frameCount();
        int baselineEmeralds = trace.getFrame(0).emeralds();

        List<FrameComparison> comparisons = new ArrayList<>();
        // One replay owns one dynamic-art delivery-id origin.
        DynamicArtSpecialStageComparator dynamicArt =
                new DynamicArtSpecialStageComparator();
        Sonic1SpecialStageComparisonState lastState = null;
        int lastFrame = -1;

        for (int f = 0; f < compareEnd; f++) {
            Sonic1SpecialStageTraceFrame tf = trace.getFrame(f);
            if (tf.lag()) {
                harness.stepLagRow();
                addDynamicArtComparison(
                        comparisons, trace, harness, f, new LinkedHashMap<>(), dynamicArt);
                continue;
            }
            harness.stepFrame(f);

            Sonic1SpecialStageComparisonState state = harness.capture();
            lastState = state;
            lastFrame = f;

            // Torn-capture guard (see class javadoc "Multi-frame lag boundary"
            // note and isTornLagBoundaryRow's javadoc): a non-lag row that
            // precedes a run of two or more lag frames is dropped ONLY when
            // its own fields independently verify the tear (early-tick
            // velocity fields already advanced, late-tick position/angle
            // fields still previous) -- not merely because a lag run follows
            // it. The engine is still stepped above (its cumulative
            // game-logic advances must stay aligned; it re-syncs exactly at
            // the next compared row), but a verified-torn row is omitted from
            // the report rather than scored against a whole engine frame.
            if (isTornLagBoundaryRow(trace, f, compareEnd)) {
                addDynamicArtComparison(
                        comparisons, trace, harness, f, new LinkedHashMap<>(), dynamicArt);
                continue;
            }

            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            addFields(fields, tf, state, baselineEmeralds);
            addDynamicArtComparison(
                    comparisons, trace, harness, f, fields, dynamicArt);
        }

        if (lastState != null) {
            String actual = String.valueOf(lastState.exitTriggered() || lastState.finished());
            Map<String, FieldComparison> fields = new LinkedHashMap<>();
            fields.put("exit_state_at_end",
                    cmp("exit_state_at_end", "true", actual, Severity.ERROR));
            comparisons.add(new FrameComparison(lastFrame, fields));
        }

        return new DivergenceReport(comparisons);
    }

    private static void addDynamicArtComparison(
            List<FrameComparison> comparisons,
            Sonic1SpecialStageTraceData trace,
            S1SpecialStageReplayHarness harness,
            int frame,
            Map<String, FieldComparison> fields,
            DynamicArtSpecialStageComparator dynamicArt) {
        if (trace.metadata().hasPerFrameDynamicArtTransferState()) {
            if (frame == trace.frameCount() - 1) {
                harness.finishDynamicArtSegment();
            }
            fields.putAll(dynamicArt.compare(
                    trace.dynamicArtTransferStateForFrame(frame),
                    harness.captureDynamicArt()).fields());
        }
        if (!fields.isEmpty()) {
            comparisons.add(new FrameComparison(frame, fields));
        }
    }

    /**
     * True when non-lag trace row {@code f} is the immediate predecessor of a
     * lag run of length &ge; 2 AND that row is independently verified to be a
     * torn (partial) RAM capture -- not merely inferred from the lag-run
     * structure alone.
     *
     * <p>BizHawk flags a frame as a lag frame when the console did not complete
     * a full game-loop iteration (input was not polled) during that emulated
     * frame. A single lag frame is harmless: the preceding sample is a
     * completed frame and the lag frame merely re-samples the same settled RAM.
     * A run of two or more consecutive lag frames COULD mean one game-logic
     * iteration spanned three or more VBlank sample points, letting the Lua RAM
     * snapshot taken at the VBlank immediately <em>before</em> the burst catch
     * that iteration's writes partway through -- but the lag-run shape alone is
     * only a hint of that, not proof, so this method also checks the row's own
     * field values for the internal inconsistency such tearing would produce:
     * {@code vel_x}/{@code vel_y}/{@code inertia} are written earlier in
     * {@code SonicSS_Display}'s tick (by {@code SonicSS_Jump}/{@code
     * SonicSS_Move}/{@code SonicSS_Fall}, docs/s1disasm/_incObj/09 Sonic in
     * Special Stage.asm:96-106) than the position stores and the
     * {@code v_ssangle} update ({@code SpeedToPos} at asm:114 --
     * {@code move.l d2,obX} then {@code move.l d3,obY},
     * {@code _incObj/sub ObjectFall & SpeedToPos.asm:47-48} -- followed by the
     * {@code v_ssangle} update at asm:117-119).
     *
     * <p>{@code v_ssangle} is the <em>last</em> of those writes, and
     * {@code SonicSS_Display} performs it unconditionally
     * ({@code d0 = v_ssangle + v_ssrotate}). So whenever {@code v_ssrotate} is
     * non-zero the angle must differ from the previous tick's in any coherent
     * sample, and an unchanged angle next to already-advanced velocities can
     * only be a sample straddling the tick. That single witness covers every
     * tear point inside the tick, whereas also demanding {@code y_pos} still be
     * previous would only catch tears landing before the {@code obY} store and
     * would misclassify the (equally real) later ones as coherent rows. The
     * {@code ss_rotate != 0} qualifier is what keeps the angle a valid witness:
     * the pre-setup fade and the exit ramp's {@code v_ssrotate = 0} window
     * ({@code sonic.asm:3268}, {@code _incObj/09 asm:394}) legitimately hold
     * the angle still, and are excluded rather than misread as tears.
     * {@code x_pos} is not used at all -- a longword store is two word writes
     * on the 68k, so it can be caught half-written and equal neither step.
     *
     * <p>Only a row satisfying BOTH the lag-run shape AND this field-level
     * inconsistency is dropped; a row that merely precedes a &ge;2 lag run but
     * is internally coherent is scored normally. Observed instances: the
     * committed maze trace's frame 1767 (tear before the {@code obY} store --
     * vel_x/vel_y/inertia {@code $FB1C}/{@code $016A}/{@code $FE28} &rarr;
     * {@code $FB01}/{@code $018A}/{@code $FE34}, y_pos/ss_angle
     * {@code $519CA00}/{@code $E640} unchanged), and the complete-run
     * segments' {@code ss_2} frame 3120 and {@code ss_4} frame 1148 (tear
     * after both position stores -- x_pos and ss_angle still previous, y_pos
     * and the velocities already advanced; in both the engine's coherent frame
     * matches the settled post-lag row exactly on x_pos, y_pos and ss_angle).
     * None is a coherent ROM frame, and none is reproducible by any single
     * engine tick.
     */
    private static boolean isTornLagBoundaryRow(Sonic1SpecialStageTraceData trace,
                                                int f, int compareEnd) {
        if (f + 2 >= compareEnd
                || !trace.getFrame(f + 1).lag()
                || !trace.getFrame(f + 2).lag()) {
            return false;
        }
        if (f == 0) {
            return false;
        }
        Sonic1SpecialStageTraceFrame prev = trace.getFrame(f - 1);
        Sonic1SpecialStageTraceFrame cur = trace.getFrame(f);

        boolean velocityAlreadyAdvanced = (cur.velX() & 0xFFFF) != (prev.velX() & 0xFFFF)
                || (cur.velY() & 0xFFFF) != (prev.velY() & 0xFFFF)
                || (cur.inertia() & 0xFFFF) != (prev.inertia() & 0xFFFF);
        // v_ssangle is the LAST of the compared per-tick writes (updated at
        // asm:117-119, after SpeedToPos at asm:114) and SonicSS_Display
        // updates it unconditionally: d0 = v_ssangle + v_ssrotate. So while
        // v_ssrotate is non-zero the angle MUST differ from the previous
        // tick's in any coherent sample, and an unchanged angle alongside
        // already-advanced velocities can only be a sample taken after the
        // early-tick velocity writes and before that final angle write --
        // regardless of which of SpeedToPos's two position stores
        // (obX at asm:47, obY at asm:48) the tear happened to fall between.
        boolean lateTickAngleStillPrevious = (cur.ssRotate() & 0xFFFF) != 0
                && (cur.ssAngle() & 0xFFFF) == (prev.ssAngle() & 0xFFFF);

        return velocityAlreadyAdvanced && lateTickAngleStillPrevious;
    }

    private static void addFields(Map<String, FieldComparison> fields,
                                  Sonic1SpecialStageTraceFrame tf,
                                  Sonic1SpecialStageComparisonState state,
                                  int baselineEmeralds) {
        // Recorded ROM pre-start hold marker (see class javadoc
        // "Recorded ROM pre-start hold" section): GM_Special sets
        // v_ssrotate = ss_rotatespeed ($40) exactly once, in its instant
        // setup block after PaletteWhiteOut (docs/s1disasm/sonic.asm:3268).
        // v_ssrotate is NOT monotonic afterward -- SonicSS_ExitStage resets
        // it straight to 0 on the exit ramp's $3000 threshold
        // (_incObj/09 Sonic in Special Stage.asm:394) and an R-block can
        // negate it (asm:923) -- so ss_rotate == 0 alone does not uniquely
        // identify the pre-setup fade among the compared rows. The SAME
        // exit-ramp reset instruction pins v_ssangle to $4000 in the same
        // breath (asm:395), while the pre-setup fade leaves v_ssangle at its
        // own untouched-since-init 0, so requiring ss_angle == 0 alongside
        // ss_rotate == 0 disambiguates the two zero-rotate states without a
        // route/frame carve-out. While that holds, v_objspace/v_rings are
        // still uncleared (sonic.asm:3243,3286) and Obj09 has not been
        // executed (first ExecuteObjects at sonic.asm:3306), so the
        // object-owned physics columns and the ring counter carry leftover
        // previous-zone RAM, not live special-stage state, and are omitted
        // from the report until setup has run.
        boolean preStartHold = (tf.ssRotate() & 0xFFFF) == 0 && (tf.ssAngle() & 0xFFFF) == 0;

        // Special-stage globals: cleared/set by GM_Special's setup and read as
        // 0 by both sides during the fade -- always comparable.
        fields.put("ss_angle", cmp("ss_angle",
                str(tf.ssAngle() & 0xFFFF), str(state.ssAngle() & 0xFFFF), Severity.ERROR));
        fields.put("ss_rotate", cmp("ss_rotate",
                str(tf.ssRotate() & 0xFFFF), str(state.ssRotate() & 0xFFFF), Severity.ERROR));
        fields.put("bg_anim", cmp("bg_anim",
                str(tf.bgAnim() & 0xFFFF), str(state.bgAnimState() & 0xFFFF), Severity.ERROR));

        // Emeralds persist across special stages (not cleared by setup), so the
        // frame-0 baseline detects a newly-collected emerald in this run.
        boolean expectedEmeraldCollected = tf.emeralds() != baselineEmeralds;
        fields.put("emeralds", cmp("emeralds",
                bool(expectedEmeraldCollected), bool(state.emeraldCollected()), Severity.ERROR));

        if (preStartHold) {
            return;
        }

        // ---- Obj09-owned physics (live only once ExecuteObjects has run) ----
        // Tier-1. x_pos/y_pos keep the manager's full 16.16 fixed-point
        // longword layout; mask both sides & 0xFFFFFFFFL and compare as
        // Long.toString so a sign-extended long on either side still
        // compares equal (sign-agnostic).
        fields.put("x_pos", cmp("x_pos",
                Long.toString(tf.xPos() & 0xFFFFFFFFL),
                Long.toString(state.sonicPosX() & 0xFFFFFFFFL), Severity.ERROR));
        fields.put("y_pos", cmp("y_pos",
                Long.toString(tf.yPos() & 0xFFFFFFFFL),
                Long.toString(state.sonicPosY() & 0xFFFFFFFFL), Severity.ERROR));

        fields.put("vel_x", cmp("vel_x",
                str(tf.velX() & 0xFFFF), str(state.sonicVelX() & 0xFFFF), Severity.ERROR));
        fields.put("vel_y", cmp("vel_y",
                str(tf.velY() & 0xFFFF), str(state.sonicVelY() & 0xFFFF), Severity.ERROR));
        fields.put("inertia", cmp("inertia",
                str(tf.inertia() & 0xFFFF), str(state.sonicInertia() & 0xFFFF), Severity.ERROR));

        // Only bits 0-1 of the raw ROM status byte are modeled by the
        // comparison state today; the trace's full status column is kept for
        // a future bit-coverage campaign.
        fields.put("status_facing_left", cmp("status_facing_left",
                bool((tf.status() & 0x1) != 0), bool(state.sonicFacingLeft()), Severity.ERROR));
        fields.put("status_airborne", cmp("status_airborne",
                bool((tf.status() & 0x2) != 0), bool(state.sonicAirborne()), Severity.ERROR));

        // Direct from 0: GM_Special clears v_rings (docs/s1disasm/sonic.asm:3280)
        // before the maze runs, so the live recorded count is a fresh tally
        // from 0 directly comparable to the engine's ringsCollected.
        fields.put("rings", cmp("rings",
                str(tf.rings() & 0xFFFF), str(state.ringsCollected()), Severity.ERROR));
    }

    // ==================== Report output ====================

    static TestSessionOutputPaths.ReportAllocation writeReport(
            DivergenceReport report, int ssIndex) throws IOException {
        return TraceReportWriter.writeSpecialStageReport(
                report, "special-stage", SessionInvocationExtension.SessionInvocation.current(),
                "s1-" + ssIndex, "s1_special_stage_" + ssIndex, CONTEXT_RADIUS);
    }

    static Path reportDir() {
        return TestSessionOutputPaths.traceReports();
    }

    static int specialStageIndex(Sonic1SpecialStageTraceData trace) {
        Integer index = trace.metadata().specialStageIndex();
        return index != null ? index : 0;
    }
}
