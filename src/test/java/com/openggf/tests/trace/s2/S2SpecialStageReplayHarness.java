package com.openggf.tests.trace.s2;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageInputMapper;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageComparisonState;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageIntro;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageReplayTestBridge;
import com.openggf.trace.SpecialStageRunObjectsPassBinder.CompletedPass;
import com.openggf.tests.trace.RecordedInputRows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Headless replay driver for a Sonic 2 special-stage BizHawk trace.
 *
 * <h2>Bootstrap pattern (Step 1 research)</h2>
 * <p>The special-stage runtime is booted through the production
 * {@link Sonic2SpecialStageProvider}, exactly as the live GameLoop does, rather
 * than a parallel test-only stack. The surrounding fixture is responsible for
 * installing a ROM + engine services before this harness is constructed. The
 * verified boot recipe (mirrored from
 * {@code TestS3kHcz2SpindashStuckRegression} for the ROM install, from
 * {@code Sonic1SpecialStageManagerTest} for the headless graphics install, and
 * from {@code Sonic2SpecialStageComparisonStateTest} for the two-key team
 * config) is:
 * <ol>
 *   <li>{@code GraphicsManager.getInstance().initHeadless()} — GL calls become
 *       no-ops so the manager's pattern/renderer setup is safe without a GL
 *       context.</li>
 *   <li>Load {@code s2.gen} into a {@link com.openggf.data.Rom} and install it
 *       via {@code TestEnvironment.configureRomFixture(rom)} (selects the
 *       {@code Sonic2GameModule}, wires {@code GameServices.rom()} and a fresh
 *       gameplay mode).</li>
 *   <li>Set {@code MAIN_CHARACTER_CODE="sonic"} + {@code SIDEKICK_CHARACTER_CODE
 *       ="tails"} on {@code GameServices.configuration()} (done in this ctor)
 *       so {@code setupPlayers()} spawns the recorded Sonic+Tails team.</li>
 *   <li>{@code provider.initializeStage(index, TRACE_ACCURATE)} →
 *       {@code manager.reset()} + {@code manager.initialize(index)} (loads the
 *       SS data from ROM while retaining observable startup cadence).</li>
 *   <li>{@code provider.setLagCompensation(0)} — retained compatibility
 *       notification; recorded lag rows are admitted by the replay scheduler.</li>
 * </ol>
 *
 * <h2>Input injection (modelled on {@code SpecialStageStepper.step})</h2>
 * <p>Per stepped frame the harness overrides the {@link InputHandler}'s logical
 * snapshot with the recorded BK2 row via
 * {@link RecordedInputSnapshots#fromBk2}, maps it with
 * {@link SpecialStageInputMapper}, forwards P1 held/pressed and P2 held/logical
 * to the provider, then ticks {@code provider.update()}. Trace data is only ever
 * used as an input source and pacing signal — no engine state is hydrated from
 * the trace (comparison-only invariant).
 *
 * <h2>BK2 indexing and the press-edge rule</h2>
 * <p>Trace frame {@code f} maps to absolute BK2 input-log row
 * {@code bk2FrameOffset + f} (0-based into {@link Bk2Movie#getFrames()}). The
 * {@code previous} row handed to {@code fromBk2} is ALWAYS the immediately
 * preceding <em>physical</em> BK2 row ({@code bk2FrameOffset + f - 1}), never
 * "the last frame the harness stepped". This keeps press-edge detection
 * ({@code held & ~previousHeld}) aligned with the ROM, whose V-int reads the
 * true prior controller state regardless of lag frames.
 */
final class S2SpecialStageReplayHarness {

    private final Bk2Movie movie;
    private final RecordedInputRows recordedInputs;
    private final InputHandler inputHandler;
    private final Sonic2SpecialStageProvider provider;
    private final List<Integer> steppedPassSequences = new ArrayList<>();
    private int forceFinishedAfterPassSequenceForTest = -1;

    S2SpecialStageReplayHarness(Path bk2, int bk2FrameOffset, int specialStageIndex)
            throws IOException {
        // Recorded team: the SS trace was captured with Sonic + Tails. Set the
        // standard two-key config before initializeStage() runs setupPlayers().
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        this.movie = new Bk2MovieLoader().load(Objects.requireNonNull(bk2, "bk2"));
        this.recordedInputs = new RecordedInputRows(movie, bk2FrameOffset);
        this.inputHandler = new InputHandler();
        this.provider = new Sonic2SpecialStageProvider();
        this.provider.initializeStage(
                specialStageIndex, SpecialStageStartupPolicy.TRACE_ACCURATE);
        // Disable the interactive approximation; recorded rows own replay pacing.
        this.provider.setLagCompensation(0);
    }

    /**
     * Steps one special-stage logic frame using the BK2 row for
     * {@code traceFrame}, diffing against the previous physical BK2 row for
     * press-edge detection. Callers must NOT invoke this for a lag row — a lag
     * row advances nothing engine-side (the row is simply skipped).
     */
    void stepFrame(int traceFrame) {
        runProductionRow(activePhase(),
                () -> stepFrameBody(traceFrame));
    }

    private void stepFrameBody(int traceFrame) {
        recordedInputs.withLogicalOverride(traceFrame, inputHandler, () -> {
            SpecialStageInputMapper.MappedInput mapped =
                    SpecialStageInputMapper.map(inputHandler.logical());
            provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
            provider.handlePlayer2Input(
                    mapped.p2Held(), mapped.p2Logical());
            provider.update();
        });
    }

    /**
     * Steps one recurring ROM object pass using only the current/previous BK2
     * row identities captured by the preceding Vint_S2SS ReadJoypads call.
     * Auxiliary held values are diagnostics validated by the pass binder; they
     * are never used to drive the engine.
     */
    void stepPass(CompletedPass pass) {
        runProductionRow(activePhase(),
                () -> stepPassBody(pass));
    }

    /**
     * Steps one observation's completed ROM object passes.
     *
     * <p>An observation that executes a {@code RunObjects} pass is never a lag
     * V-blank whatever the recorder's lag heuristic reports: the ROM's special
     * stage loop sets {@code VintID_S2SS} and waits on it immediately before
     * the pass (docs/s2disasm/s2.asm:6694-6706), so {@code V_Int} cannot have
     * branched to {@code Vint_Lag} — that branch is taken only while
     * {@code Vint_routine} is still 0 (s2.asm:483-484). The pass binder already
     * treats such an observation as logical; the phase handed to the lifecycle
     * follows the same fact rather than the raw lag bit, so the terminal
     * finish observation needs no special case of its own.
     */
    void stepPasses(
            List<CompletedPass> passes,
            int terminalPreStartPassSequence,
            boolean lagged,
            int observationFrame) {
        runProductionRow(observationPhase(lagged, !passes.isEmpty(), activePhase()), () -> {
            for (CompletedPass pass : passes) {
                if (pass.sequence() == terminalPreStartPassSequence) {
                    // The pre-start loop's terminal pass is already pending
                    // engine-side, and the ROM copies Ctrl_1/Ctrl_2 *before*
                    // that loop's WaitForVint (docs/s2disasm/s2.asm:6675-6676),
                    // so it owns no post-V-int controller sample for the
                    // recurring loop's binding path to consume. Publish it
                    // through the startup boundary instead.
                    completeTerminalPreStartPassBody();
                } else {
                    stepPassBody(pass);
                }
                if (pass.completionCursorFrame() < observationFrame) {
                    // The pass finished before this observation's V-int on
                    // hardware, so that V-blank already ran ProcessDMAQueue
                    // over its queued work: submissions and completions
                    // surface together on the bound row, while later passes'
                    // work in this same observation stays pending. See
                    // DynamicArtLifecycleService.serviceVblankBeforeBoundObservation.
                    var lifecycle = GameServices.dynamicArtLifecycleOrNull();
                    if (lifecycle != null && lifecycle.isRunActive()) {
                        lifecycle.serviceVblankBeforeBoundObservation();
                    }
                }
            }
        });
    }

    /** Phase for one special-stage observation. See {@link #stepPasses}. */
    static PlcLifecyclePhase observationPhase(
            boolean lagged, boolean ownsCompletedPass) {
        return observationPhase(lagged, ownsCompletedPass,
                PlcLifecyclePhase.SPECIAL_STAGE);
    }

    /**
     * As above, but for a stage whose active (non-lag) rows are not yet the
     * stage's own V-int handler — see {@link #activePhase()}.
     */
    static PlcLifecyclePhase observationPhase(
            boolean lagged, boolean ownsCompletedPass,
            PlcLifecyclePhase activePhase) {
        return lagged && !ownsCompletedPass
                ? PlcLifecyclePhase.LAG
                : activePhase;
    }

    /**
     * The V-int the ROM is actually running for the row about to be stepped.
     *
     * <p>Entering a special stage, the ROM's first act is
     * {@code Pal_FadeToWhite} (docs/s2disasm/s2.asm:6725-6745), whose V-blanks
     * are {@code VintID_Fade}; {@code Vint_Fade} never reaches
     * {@code ProcessDMAQueue} — only the real per-mode handlers do
     * (s2.asm:781, 899, 1000, 1046, 1083, 1138). The stage's own
     * {@code VintID_S2SS} handler (s2.asm:837, its {@code ProcessDMAQueue} at
     * s2.asm:899) is not selected until {@code SpecialStage} has finished
     * loading and reaches its first {@code WaitForVint}
     * (s2.asm:6644-6651). So a queued transfer inherited from the level the
     * stage was entered from survives the whole fade, and every claim before
     * the stage's own handler must be a non-servicing phase.
     *
     * <p>The boundary is read from the engine's own intro state machine, whose
     * {@code PRE_ROLL} phase <em>is</em> that fade
     * ({@link Sonic2SpecialStageIntro#PRE_ROLL_FRAMES} = the routine's
     * {@code d4=$15} {@code dbf}), not from a row count or a recorded row.
     */
    PlcLifecyclePhase activePhase() {
        return provider.getManager().getIntro().getCurrentPhase()
                        == Sonic2SpecialStageIntro.Phase.PRE_ROLL
                ? PlcLifecyclePhase.PALETTE_FADE
                : PlcLifecyclePhase.SPECIAL_STAGE;
    }

    private void stepPassBody(CompletedPass pass) {
        SpecialStageInputMapper.MappedInput mapped = mappedInputForPass(movie, pass);
        provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
        provider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
        provider.bindPendingRecurringPassInput(
                mapped.p1Held(), mapped.p1Pressed(), mapped.p2Held(), mapped.p2Logical());
        provider.update();
        steppedPassSequences.add(pass.sequence());
        if (pass.sequence() == forceFinishedAfterPassSequenceForTest) {
            provider.getManager().markCompleted(true);
        }
    }

    /** Publishes Obj5F's terminal pre-start object pass without a new VInt. */
    void completeTerminalPreStartPass() {
        runProductionRow(activePhase(),
                this::completeTerminalPreStartPassBody);
    }

    private void completeTerminalPreStartPassBody() {
        Sonic2SpecialStageReplayTestBridge.completeTerminalPreStartPassWithoutVint(
                provider.getManager());
    }

    void stepLagRow() {
        runProductionRow(PlcLifecyclePhase.LAG, () -> {
        });
    }

    void stepIdleRow(boolean lagged) {
        stepIdleRow(lagged, activePhase());
    }

    /**
     * Steps one observation that runs no engine work, in {@code activePhase}
     * when the recorder did not read the row as a lag V-blank.
     */
    void stepIdleRow(boolean lagged, PlcLifecyclePhase activePhase) {
        runProductionRow(lagged ? PlcLifecyclePhase.LAG : activePhase, () -> {
        });
    }

    DynamicArtDiagnosticsSnapshot captureDynamicArt() {
        return GameServices.captureDynamicArtDiagnostics();
    }

    void finishDynamicArtSegment() {
        var lifecycle =
                SessionManager.getCurrentGameplayMode().dynamicArtLifecycle();
        if (lifecycle.isComparisonSegmentOpen()) {
            lifecycle.closeComparisonSegment();
        }
    }

    private static void runProductionRow(
            PlcLifecyclePhase phase, Runnable body) {
        SessionManager.getCurrentGameplayMode().plcFrameLifecycle()
                .runLogicalIteration(() -> {
                }, frame -> {
                    frame.claim(phase);
                    body.run();
                    frame.prepareAfterLoop(phase);
                    return null;
                });
    }

    void forceFinishedAfterPassForTest(int sequence) {
        forceFinishedAfterPassSequenceForTest = sequence;
    }

    List<Integer> steppedPassSequencesForTest() {
        return List.copyOf(steppedPassSequences);
    }

    static SpecialStageInputMapper.MappedInput mappedInputForPass(
            Bk2Movie movie, CompletedPass pass) {
        List<Bk2FrameInput> frames = movie.getFrames();
        Bk2FrameInput current = frames.get(pass.inputSampleBk2Frame());
        Bk2FrameInput previous = frames.get(pass.previousInputSampleBk2Frame());
        return SpecialStageInputMapper.map(
                RecordedInputSnapshots.fromBk2(current, previous));
    }

    List<Bk2FrameInput> movieFrames() {
        return movie.getFrames();
    }

    /** Read-only comparison snapshot of the current engine SS state. */
    Sonic2SpecialStageComparisonState capture() {
        return provider.getManager().captureComparisonState();
    }

    boolean isFinished() {
        return provider.isFinished();
    }
}
