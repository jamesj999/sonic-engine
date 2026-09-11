package com.openggf.trace.replay.runs;

import com.openggf.GameLoop;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.GameServices;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.SpecialStageInputMapper;

import java.util.List;

/**
 * Builds the {@link GameLoop.SpecialStageObservationPacing} for one recorded
 * special-stage observation, shared by every replay path that owns a recorded
 * {@code RunObjects} pass stream.
 *
 * <p>{@code SS_MainLoop} sets {@code VintID_S2SS}, waits on it, and only then
 * runs {@code RunObjects} (docs/s2disasm/s2.asm:6697-6698, 6721), so the
 * special-stage loop is paced by 68K pass duration rather than one pass per
 * V-blank: a single V-blank observation owns 0..n completed object passes. Both
 * the run-chain harness and the production {@code TraceSessionLauncher} replay
 * path must therefore run this observation's passes inside its single V-blank
 * body, or the passes a slow observation owns are never executed at all.
 *
 * <p><b>Comparison-only (rule 4).</b> Every field consumed here is scheduling
 * structure, never a gameplay value:
 * <ul>
 *   <li>the pass list for an observation, and each pass's
 *       {@code completion_cursor_frame}, say <em>when</em> engine-owned work
 *       runs and when its queued DMA surfaces;</li>
 *   <li>{@code started_at_input_sample} classifies which of
 *       {@code SpecialStage_MainLoop}'s two loops a pass belongs to
 *       (docs/s2disasm/s2.asm:6674-6721) — a control-flow discriminator that
 *       selects a publication boundary;</li>
 *   <li>{@code input_sample_bk2_frame} identifies which <em>BK2 movie</em> rows
 *       the ROM's {@code ReadJoypads} consumed; the button values themselves
 *       come from the movie, which is recorded controller input, not trace
 *       state. The pass record's own button copies are diagnostics the binder
 *       validates and this class never reads.</li>
 * </ul>
 * Nothing here carries a player, object, track, ring or checkpoint value into
 * the engine, and it cannot create engine work the stage did not already own.
 */
public final class SpecialStageRecordedPassPacing {

    private SpecialStageRecordedPassPacing() {
    }

    /**
     * Pacing for one observation: run each recorded pass with the controller
     * sample the ROM's {@code ReadJoypads} actually consumed for it. Mirrors
     * {@code S2SpecialStageReplayHarness.stepPassBody}.
     */
    public static GameLoop.SpecialStageObservationPacing forObservation(
            Bk2Movie movie,
            List<SpecialStageRunObjectsPassBinder.CompletedPass> passes,
            int observationFrame) {
        return new GameLoop.SpecialStageObservationPacing() {
            @Override
            public int passCount() {
                return passes.size();
            }

            @Override
            public void runPass(int index, SpecialStageProvider provider) {
                if (isPreStartPass(passes.get(index))) {
                    // The pre-start loop's terminal pass. Its own
                    // started_at_input_sample is still clear, because the
                    // pre-start loop tests SpecialStage_Started only after the
                    // pass returns (docs/s2disasm/s2.asm:6689-6692) -- the same
                    // predicate TraceRunSpecialStageRows.passPacedFromRow uses
                    // to find the recurring loop's first pass, so the last pass
                    // still reading zero is by construction the pre-start one.
                    // The ROM copies Ctrl_1/Ctrl_2 BEFORE that loop's
                    // WaitForVint (s2.asm:6675-6676), so it owns no post-V-int
                    // controller sample for the recurring loop's binding path;
                    // publish it through the startup boundary instead. Without
                    // this it binds as a recurring pass and every later
                    // special-stage pass runs one V-int behind the track clock.
                    // Mirrors S2SpecialStageReplayHarness.stepPasses.
                    ((Sonic2SpecialStageProvider) provider).getManager()
                            .completeTerminalPreStartPassWithoutVint();
                    return;
                }
                applyPassInput(index, provider);
                provider.update();
            }

            @Override
            public void applyPassInput(int index, SpecialStageProvider provider) {
                var pass = passes.get(index);
                SpecialStageInputMapper.MappedInput mapped = SpecialStageInputMapper.map(
                        RecordedInputSnapshots.fromBk2(
                                movie.getFrame(pass.inputSampleBk2Frame()),
                                movie.getFrame(pass.previousInputSampleBk2Frame())));
                provider.handleInput(mapped.p1Held(), mapped.p1Pressed());
                provider.handlePlayer2Input(mapped.p2Held(), mapped.p2Logical());
                provider.bindPendingRecurringPassInput(
                        mapped.p1Held(), mapped.p1Pressed(),
                        mapped.p2Held(), mapped.p2Logical());
            }

            @Override
            public void afterPass(int index) {
                if (passes.get(index).completionCursorFrame() >= observationFrame) {
                    return;
                }
                // The pass returned before this observation's V-int on
                // hardware, so that V-blank already ran ProcessDMAQueue
                // (docs/s2disasm/s2.asm:1769) over its queued work:
                // submissions and completions surface together on the bound
                // row, while a later pass's work in this same observation
                // stays pending. Identical to the standalone harness's rule in
                // S2SpecialStageReplayHarness.stepPasses.
                var lifecycle = GameServices.dynamicArtLifecycleOrNull();
                if (lifecycle != null && lifecycle.isRunActive()) {
                    lifecycle.serviceVblankBeforeBoundObservation();
                }
            }
        };
    }

    /**
     * True while a recorded pass's own {@code Vint_S2SS ReadJoypads} sample had
     * not yet seen {@code SpecialStage_Started} (docs/s2disasm/s2.asm:9745), so
     * the pass belongs to {@code SpecialStage_MainLoop}'s pre-start loop rather
     * than its recurring loop (s2.asm:6674-6721).
     */
    public static boolean isPreStartPass(
            SpecialStageRunObjectsPassBinder.CompletedPass pass) {
        Object raw = pass.snapshot().fields().get("started_at_input_sample");
        if (raw == null) {
            throw new IllegalStateException(
                    "run_objects_end is missing started_at_input_sample at frame "
                            + pass.snapshot().frame());
        }
        if (raw instanceof Number number) {
            return number.intValue() == 0;
        }
        String text = String.valueOf(raw);
        return (text.startsWith("0x") || text.startsWith("0X")
                ? Integer.parseUnsignedInt(text.substring(2), 16)
                : Integer.parseInt(text)) == 0;
    }
}
