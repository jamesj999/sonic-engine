package com.openggf.tests.trace.s1;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameServices;
import com.openggf.game.SpecialStageInputMapper;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageComparisonState;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.tests.trace.RecordedInputRows;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Headless replay driver for a Sonic 1 special-stage (maze) BizHawk trace.
 * Modeled on {@code S3kSpecialStageReplayHarness}, trimmed further for the
 * S1 provider's single-player surface: there is no second controller to
 * forward, and {@code setLagCompensation} is a no-op scaffold
 * ({@code Sonic1SpecialStageProvider.java:173-175}) so there is nothing to
 * disable before trace-paced replay. The boot call uses the two-arg
 * {@code initializeStage(int, SpecialStageStartupPolicy)} with
 * {@code TRACE_ACCURATE} so the ROM's observable pre-physics hold is stepped
 * frame-by-frame instead of fast-forwarded (see the ctor).
 *
 * <h2>Bootstrap pattern</h2>
 * <p>The special-stage runtime is booted through the production
 * {@link Sonic1SpecialStageProvider}, exactly as the live GameLoop does. The
 * surrounding fixture (see {@link AbstractS1SpecialStageTraceReplayTest})
 * is responsible for installing a ROM + engine services before this harness
 * is constructed.
 * <ol>
 *   <li>{@code GraphicsManager.getInstance().initHeadless()} -- GL calls
 *       become no-ops so the manager's pattern/renderer setup is safe
 *       without a GL context.</li>
 *   <li>Load {@code s1.gen} into a {@link com.openggf.data.Rom} and install
 *       it via {@code TestEnvironment.configureRomFixture(rom)} (selects the
 *       {@code Sonic1GameModule}, wires {@code GameServices.rom()} and a
 *       fresh gameplay mode).</li>
 *   <li>Set {@code MAIN_CHARACTER_CODE="sonic"} on
 *       {@code GameServices.configuration()} (done in this ctor, BEFORE
 *       {@code initializeStage} runs). The S1 module has no sidekick in the
 *       special stage, so {@code SIDEKICK_CHARACTER_CODE} is left
 *       unset.</li>
 *   <li>{@code provider.initializeStage(index, TRACE_ACCURATE)} -&gt;
 *       {@code manager.reset()} + {@code manager.initialize(index)} (loads
 *       the maze layout/art from ROM) without fast-forwarding the pre-physics
 *       hold.</li>
 * </ol>
 *
 * <h2>Input injection</h2>
 * <p>Per stepped frame the harness overrides the {@link InputHandler}'s
 * logical snapshot with the recorded BK2 row via
 * {@link RecordedInputSnapshots#fromBk2}, maps it with
 * {@link SpecialStageInputMapper}, forwards only P1 held/pressed to the
 * provider (S1's maze is single-player; the interface's P2 handler default is
 * a no-op, so calling it would be dead code), then ticks
 * {@code provider.update()}. Trace data is only ever used as an input source
 * and pacing signal -- no engine state is hydrated from the trace
 * (comparison-only invariant).
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
 * <p>This harness exposes ONLY {@link #stepFrame(int)}: there is no ROM
 * {@code RunObjects}-pass binder to model, so the test loop simply skips lag
 * rows (they advance nothing engine-side) and steps every other row through
 * {@code update()}.
 */
final class S1SpecialStageReplayHarness {

    private final RecordedInputRows recordedInputs;
    private final InputHandler inputHandler;
    private final Sonic1SpecialStageProvider provider;

    S1SpecialStageReplayHarness(Path bk2, int bk2FrameOffset, int specialStageIndex)
            throws IOException {
        // Recorded maze runs are captured solo (Sonic). Set the standard team
        // config before initializeStage() runs resolvePlayerCharacter().
        GameServices.configuration()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");

        Bk2Movie movie = new Bk2MovieLoader().load(Objects.requireNonNull(bk2, "bk2"));
        this.recordedInputs = new RecordedInputRows(movie, bk2FrameOffset);
        this.inputHandler = new InputHandler();
        this.provider = new Sonic1SpecialStageProvider();
        // TRACE_ACCURATE leaves the ROM's 44-VBlank-tick pre-physics hold
        // armed (Sonic1SpecialStageManager.SS_STARTUP_HOLD_TICKS) so the
        // recorded trace's frozen pre-roll rows are observable frame-by-frame
        // instead of being fast-forwarded away, mirroring
        // Sonic2SpecialStageProvider's TRACE_ACCURATE precedent.
        this.provider.initializeStage(specialStageIndex, SpecialStageStartupPolicy.TRACE_ACCURATE);
    }

    /**
     * Steps one special-stage logic frame using the BK2 row for
     * {@code traceFrame}, diffing against the previous physical BK2 row for
     * press-edge detection. Callers must NOT invoke this for a lag row -- a
     * lag row advances nothing engine-side; the caller should skip it
     * (consume the trace row without stepping) instead.
     */
    void stepFrame(int traceFrame) {
        runProductionRow(PlcLifecyclePhase.SPECIAL_STAGE,
                () -> recordedInputs.withLogicalOverride(
                        traceFrame, inputHandler, () -> {
                            SpecialStageInputMapper.MappedInput mapped =
                                    SpecialStageInputMapper.map(
                                            inputHandler.logical());
                            provider.handleInput(
                                    mapped.p1Held(), mapped.p1Pressed());
                            provider.update();
                        }));
    }

    void stepLagRow() {
        runProductionRow(PlcLifecyclePhase.LAG, () -> {
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

    /** Read-only comparison snapshot of the current engine SS state. */
    Sonic1SpecialStageComparisonState capture() {
        return provider.getManager().captureComparisonState();
    }

    boolean isFinished() {
        return provider.isFinished();
    }
}
