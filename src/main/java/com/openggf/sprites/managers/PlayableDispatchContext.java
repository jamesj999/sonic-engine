package com.openggf.sprites.managers;

/**
 * Internal input/cadence envelope shared by counted and initial playable-slot
 * dispatches.
 */
record PlayableDispatchContext(
        ProcessSpritesEpoch epoch,
        InitialPlayableInput input,
        boolean initialAssemblySlot,
        boolean runCpuControllers,
        boolean useEffectiveRuntimeInput,
        boolean testButton,
        boolean speedUp,
        boolean slowDown,
        boolean debugModePressed,
        boolean superSonicDebugPressed,
        boolean sweepTemporarySidekicks) {

    static PlayableDispatchContext initial(
            ProcessSpritesEpoch epoch,
            InitialPlayableInput input) {
        return new PlayableDispatchContext(
                epoch, input, true, false, true,
                false, false, false, false, false, false);
    }

    static PlayableDispatchContext ordinary(
            ProcessSpritesEpoch epoch,
            InitialPlayableInput input,
            boolean testButton,
            boolean speedUp,
            boolean slowDown,
            boolean debugModePressed,
            boolean superSonicDebugPressed) {
        return new PlayableDispatchContext(
                epoch, input, false, true, true,
                testButton, speedUp, slowDown,
                debugModePressed, superSonicDebugPressed, true);
    }

    static PlayableDispatchContext ordinaryWithoutInput(ProcessSpritesEpoch epoch) {
        return new PlayableDispatchContext(
                epoch, InitialPlayableInput.nativeNeutral(), false, false, false,
                false, false, false, false, false, false);
    }
}
