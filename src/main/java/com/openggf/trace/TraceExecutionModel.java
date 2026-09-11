package com.openggf.trace;

@FunctionalInterface
public interface TraceExecutionModel {

    TraceExecutionPhase phaseFor(TraceFrame previous, TraceFrame current);

    /**
     * True when the recorder row it describes was sampled without any V-blank
     * having elapsed since the previous row: neither the gameplay frame counter
     * nor the V-blank counter advanced.
     *
     * <p>This is a refinement of the same hardware-timing classification
     * {@link #phaseFor} already performs, and separates the two shapes that
     * both classify as {@link TraceExecutionPhase#VBLANK_ONLY}:
     * <ul>
     *   <li>a real lag V-blank -- {@code Vint_routine} was still 0 so
     *       {@code V_Int} branched to {@code Vint_Lag}
     *       (docs/s2disasm/s2.asm:481-484, 529; docs/s1disasm/sonic.asm:709
     *       {@code VBlank_Lag}). {@code Vint_runcount} is still bumped at
     *       {@code VintRet} (s2.asm:512), so the V-blank counter advances; and</li>
     *   <li>a row on which <em>no</em> V-blank ran at all. The main loop
     *       iteration overran its V-blank, so the very next row consumes two
     *       V-blank counter ticks for one gameplay tick.</li>
     * </ul>
     * Only the first shape is a publication boundary for dynamic art; the
     * second means the iteration that follows is still mid-flight at the
     * sample instant.
     */
    static boolean isVblankStarvedRow(TraceFrame previous, TraceFrame current) {
        return previous != null
                && current != null
                && hasAuthoritativeVblankCounter(previous)
                && hasAuthoritativeVblankCounter(current)
                && !gameplayCounterAdvanced(previous, current)
                && !vblankCounterAdvanced(previous, current);
    }

    /**
     * True when the main-loop iteration sampled by {@code current} was still in
     * flight when {@code next} was sampled: a V-blank elapsed between the two
     * rows, but the gameplay frame counter did not advance.
     *
     * <p>The gameplay frame counter is bumped at the very top of the loop, in
     * the instruction after {@code WaitForVBlank} returns (S1
     * {@code addq.w #1,(v_framecount).w}, docs/s1disasm/sonic.asm:3001-3002;
     * S2 {@code Level_frame_counter}). A row on which it did not advance while
     * the V-blank counter did is therefore a lag V-blank taken <em>inside</em>
     * the previous row's iteration -- {@code Vint_routine}/
     * {@code v_vblank_routine} was still 0 because the loop had not yet reached
     * the {@code move.b} that re-arms it (sonic.asm:3000).
     *
     * <p>That places the iteration's loop tail after the sample that closed
     * {@code current}. In S1 the tail is where {@code RunPLC} lives
     * (sonic.asm:3032), reached only after {@code ExecuteObjects} (3010),
     * {@code DeformLayers} (3025), {@code BuildSprites} (3028),
     * {@code ObjPosLoad} (3029) and {@code PaletteCycle} (3031) -- the whole of
     * the loop's cost. Had the loop already run {@code RunPLC}, only
     * {@code OscillateNumDo}, {@code SynchroAnimate} and
     * {@code SignpostArtLoad} would have stood between it and the re-arm at
     * 3000, so the V-blank that fired would not have been a lag one.
     *
     * <p>Like {@link #isVblankStarvedRow} this is a hardware-timing
     * classification of the recorded row shape: it carries no gameplay,
     * queue-identity or art value.
     */
    static boolean isIterationHeldIntoNextRow(TraceFrame current, TraceFrame next) {
        return current != null
                && next != null
                && hasAuthoritativeVblankCounter(current)
                && hasAuthoritativeVblankCounter(next)
                && current.gameplayFrameCounter() >= 0
                && next.gameplayFrameCounter() >= 0
                && !gameplayCounterAdvanced(current, next)
                && vblankCounterAdvanced(current, next);
    }

    static TraceExecutionModel forGame(String game) {
        if (game == null) {
            throw new IllegalArgumentException("Unsupported trace game: null");
        }
        return switch (game) {
            case "s1" -> TraceExecutionModel::deriveSonic1Phase;
            case "s2" -> TraceExecutionModel::deriveSonic2Phase;
            case "s3", "s3k" -> TraceExecutionModel::deriveSonic3kPhase;
            default -> throw new IllegalArgumentException("Unsupported trace game: " + game);
        };
    }

    private static TraceExecutionPhase deriveSonic1Phase(
            TraceFrame previous, TraceFrame current) {
        // Sonic 1 increments v_framecount only after WaitForVBla returns, while
        // the lag-only VBla_00 handler still advances the VBlank counter.
        return deriveCounterDrivenPhase(previous, current);
    }

    private static TraceExecutionPhase deriveSonic2Phase(
            TraceFrame previous, TraceFrame current) {
        // Sonic 2 follows the same phase split with different ROM symbols:
        // WaitForVint -> Level_frame_counter on full frames, Vint_Lag for
        // VBlank-only frames that still bump Vint_runcount.
        return deriveCounterDrivenPhase(previous, current);
    }

    private static TraceExecutionPhase deriveSonic3kPhase(
            TraceFrame previous, TraceFrame current) {
        if (previous == null) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        if (!hasAuthoritativeVblankCounter(current)) {
            return deriveLegacyHeuristic(previous, current);
        }
        if (gameplayCounterAdvanced(previous, current)) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        if (lagCounterAdvanced(previous, current)) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        if (vblankCounterAdvanced(previous, current) && current.stateEquals(previous)) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        return TraceExecutionPhase.FULL_LEVEL_FRAME;
    }

    private static TraceExecutionPhase deriveCounterDrivenPhase(
            TraceFrame previous, TraceFrame current) {
        if (previous == null) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        // Pre-v3 checked-in traces do not carry authoritative VBlank counters,
        // so keep their classification semantics inside this single model.
        if (!hasAuthoritativeVblankCounter(current)) {
            return deriveLegacyHeuristic(previous, current);
        }
        if (gameplayCounterAdvanced(previous, current)) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        if (vblankCounterAdvanced(previous, current)) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        // gameplay_frame_counter is the authoritative signal for "game logic
        // ticked" (S1: v_framecount in WaitForVBla, S2: Level_frame_counter in
        // WaitForVint). When gfc didn't advance, Level_MainLoop did not
        // complete an iteration even though the recorder ran -- this is a
        // lag frame and the engine must skip physics.
        //
        // We reach this branch when both gameplay_frame_counter and
        // vblank_counter are unchanged. The recorder for v9.x S1/S2 traces
        // writes the high word of v_vbla_count/Vint_runcount (always 0 for
        // sub-65k frame counts), so vblank deltas cannot distinguish lag
        // from real frames in checked-in S1/S2 traces. gameplay_frame_counter
        // -- read as a 16-bit word from the correct address -- is reliable.
        // Treat a gfc plateau as a lag frame regardless of vblank state.
        if (current.gameplayFrameCounter() >= 0 && previous.gameplayFrameCounter() >= 0) {
            return TraceExecutionPhase.VBLANK_ONLY;
        }
        return TraceExecutionPhase.FULL_LEVEL_FRAME;
    }

    private static boolean hasAuthoritativeVblankCounter(TraceFrame current) {
        return current.vblankCounter() >= 0;
    }

    private static boolean gameplayCounterAdvanced(TraceFrame previous, TraceFrame current) {
        return current.gameplayFrameCounter() != previous.gameplayFrameCounter();
    }

    private static boolean vblankCounterAdvanced(TraceFrame previous, TraceFrame current) {
        return current.vblankCounter() != previous.vblankCounter();
    }

    private static boolean lagCounterAdvanced(TraceFrame previous, TraceFrame current) {
        return current.lagCounter() >= 0
                && previous.lagCounter() >= 0
                && current.lagCounter() > previous.lagCounter();
    }

    private static TraceExecutionPhase deriveLegacyHeuristic(
            TraceFrame previous, TraceFrame current) {
        if (!current.stateEquals(previous)) {
            return TraceExecutionPhase.FULL_LEVEL_FRAME;
        }
        return current.xSpeed() != 0 || current.ySpeed() != 0
                || current.gSpeed() != 0 || current.air()
                ? TraceExecutionPhase.VBLANK_ONLY
                : TraceExecutionPhase.FULL_LEVEL_FRAME;
    }
}
