package com.openggf.tests.trace.s3k;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameTestStep;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.SpecialStageInputMapper;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageComparisonState;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.tests.trace.RecordedInputRows;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Headless replay driver for a Sonic 3&amp;K special-stage (blue spheres)
 * BizHawk trace. Modeled on {@code S2SpecialStageReplayHarness}, but trimmed
 * to the S3K provider's simpler surface: {@code initializeStage(int)} takes
 * no startup-policy argument (single-arg, throws {@link IOException}) and the
 * provider exposes no lag-compensation setter to disable -- there is nothing
 * to zero before trace-paced replay.
 *
 * <h2>Bootstrap pattern</h2>
 * <p>The special-stage runtime is booted through the production
 * {@link Sonic3kSpecialStageProvider}, exactly as the live GameLoop does. The
 * surrounding fixture (see {@link AbstractS3kSpecialStageTraceReplayTest})
 * is responsible for installing a ROM + engine services before this harness
 * is constructed.
 * <ol>
 *   <li>{@code GraphicsManager.getInstance().initHeadless()} -- GL calls
 *       become no-ops so the manager's pattern/renderer setup is safe
 *       without a GL context.</li>
 *   <li>Load {@code s3k.gen} into a {@link com.openggf.data.Rom} and install
 *       it via {@code TestEnvironment.configureRomFixture(rom)} (selects the
 *       {@code Sonic3kGameModule}, wires {@code GameServices.rom()} and a
 *       fresh gameplay mode).</li>
 *   <li>Set {@code MAIN_CHARACTER_CODE="sonic"} + {@code SIDEKICK_CHARACTER_CODE
 *       ="tails"} on {@code GameServices.configuration()} (done in this ctor,
 *       BEFORE {@code initializeStage} runs) so the manager's
 *       {@code resolvePlayerCharacter()} resolves the recorded solo-Sonic
 *       route.</li>
 *   <li>{@code provider.initializeStage(index)} -&gt; {@code manager.reset()}
 *       + {@code manager.initialize(index)} (loads the SS layout/art from
 *       ROM), then {@code manager.advanceThroughEntryFade()} because the
 *       comparator never steps the entry {@code Pal_FadeToWhite} rows.</li>
 * </ol>
 *
 * <h2>Input injection</h2>
 * <p>Per stepped frame the harness overrides the {@link InputHandler}'s
 * logical snapshot with the recorded BK2 row via
 * {@link RecordedInputSnapshots#fromBk2}, maps it with
 * {@link SpecialStageInputMapper}, forwards P1 held/pressed and P2 held/logical
 * to the provider, then ticks {@code provider.update()}. Trace data is only
 * ever used as an input source and pacing signal -- no engine state is
 * hydrated from the trace (comparison-only invariant).
 *
 * <h2>BK2 indexing and the press-edge rule</h2>
 * <p>Trace frame {@code f} maps to absolute BK2 input-log row
 * {@code bk2FrameOffset + f} (0-based into {@link Bk2Movie#getFrames()}). The
 * {@code previous} row handed to {@code fromBk2} is ALWAYS the immediately
 * preceding <em>physical</em> BK2 row ({@code bk2FrameOffset + f - 1}), never
 * "the last frame the harness stepped". This keeps press-edge detection
 * ({@code held & ~previousHeld}) aligned with the ROM, whose V-int reads the
 * true prior controller state regardless of lag frames.
 *
 * <h2>VBlank pacing only</h2>
 * <p>Unlike the S2 SS harness, this harness exposes ONLY
 * {@link #stepFrame(int)}: there is no ROM {@code RunObjects}-pass binder to
 * model, so the test loop simply skips lag rows (they advance nothing
 * engine-side) and steps every other row through {@code update()}.
 */
final class S3kSpecialStageReplayHarness {

    private final RecordedInputRows recordedInputs;
    private final InputHandler inputHandler;
    private final Sonic3kSpecialStageProvider provider;
    private TraceHardwareTimingBoundaryObserver timingObserver;
    private HardwareTimingReplayPort timingPort;

    S3kSpecialStageReplayHarness(Path bk2, int bk2FrameOffset, int specialStageIndex)
            throws IOException {
        // Recorded blue-spheres runs are captured solo (Sonic). Set the
        // standard team config before initializeStage() runs
        // resolvePlayerCharacter().
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        Bk2Movie movie = new Bk2MovieLoader().load(Objects.requireNonNull(bk2, "bk2"));
        this.recordedInputs = new RecordedInputRows(movie, bk2FrameOffset);
        this.inputHandler = new InputHandler();
        this.provider = new Sonic3kSpecialStageProvider();
        this.provider.initializeStage(specialStageIndex);
        // The comparator starts at the first interactive row after the ROM's
        // masked-interrupt load (AbstractS3kSpecialStageTraceReplayTest#
        // firstInteractiveFrame), so the entry Pal_FadeToWhite rows that
        // precede it are never stepped here: retire the manager's hold for
        // them, which live play and the GameLoop-driven run chains step for
        // real.
        this.provider.getManager().advanceThroughEntryFade();
    }

    /**
     * Steps one special-stage logic frame using the BK2 row for
     * {@code traceFrame}, diffing against the previous physical BK2 row for
     * press-edge detection. Callers must NOT invoke this for a lag row -- a
     * lag row advances nothing engine-side; the caller should skip it
     * (consume the trace row without stepping) instead.
     */
    void stepFrame(int traceFrame) {
        if (timingObserver != null) {
            timingObserver.beginRawFrame(traceFrame);
        }
        recordedInputs.withLogicalOverride(traceFrame, inputHandler, () ->
                LevelFrameTestStep.executeHardwareTimedObjectScan(
                        LevelFrameContext.from(SessionManager.getCurrentGameplayMode()),
                        () -> {
                            SpecialStageInputMapper.MappedInput mapped =
                                    SpecialStageInputMapper.map(inputHandler.logical());
                            provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
                            provider.handlePlayer2Input(
                                    mapped.p2Held(), mapped.p2Logical());
                            provider.update();
                        }));
    }

    /**
     * Installs the fixture's recorded hardware-timing stream, the sanctioned
     * comparison-only input that releases already-submitted, production-created
     * Kosinski work (hard rule 4). The SS loop reaches the same
     * {@code POST_OBJECTS}/{@code PRE_MAIN_LOOP} boundaries production does
     * (ROM SpecialStage loop tail, sonic3k.asm:10752-10753), so the emerald
     * archive's module and its decompression child are released exactly where
     * the recording observed them.
     */
    void installHardwareTiming(HardwareTimingSchedule schedule) {
        if (!schedule.hasRecordedInput()) {
            return;
        }
        timingPort = new HardwareTimingReplayPort(
                SessionManager.getCurrentGameplayMode()
                        .activateRecordedHardwareAdmission());
        timingPort.install(schedule);
        timingObserver = new TraceHardwareTimingBoundaryObserver(timingPort);
        SessionManager.getCurrentGameplayMode()
                .setHardwareTimingBoundaryObserver(timingObserver);
    }

    void closeHardwareTiming() {
        if (timingPort != null) {
            timingPort.verifyRunComplete();
            SessionManager.getCurrentGameplayMode().setHardwareTimingBoundaryObserver(null);
            timingPort = null;
            timingObserver = null;
        }
    }

    /** Read-only comparison snapshot of the current engine SS state. */
    Sonic3kSpecialStageComparisonState capture() {
        return provider.getManager().captureComparisonState();
    }

    boolean isFinished() {
        return provider.isFinished();
    }
}
