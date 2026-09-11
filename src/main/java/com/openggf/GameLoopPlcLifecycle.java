package com.openggf;

import com.openggf.game.EndingProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.graphics.FadeManager;

/** Bridges GameLoop-owned modes and fades to the session PLC lifecycle. */
final class GameLoopPlcLifecycle {
    private GameLoopPlcLifecycle() {
    }

    static void runPhase(PlcLifecycleFrame frame, PlcLifecyclePhase phase, Runnable body) {
        frame.claim(phase);
        body.run();
        prepare(frame, phase);
    }

    static void prepare(PlcLifecycleFrame frame, PlcLifecyclePhase phase) {
        if (frame.isOwnedBy(phase)) {
            frame.prepareAfterLoop(phase);
        }
    }

    static PlcLifecyclePhase endingPhase(EndingProvider provider) {
        if (provider == null) {
            return PlcLifecyclePhase.ENDING;
        }
        return provider.plcLifecyclePhaseOverride().orElseGet(() ->
                switch (provider.getCurrentPhase()) {
                    case CREDITS_TEXT -> PlcLifecyclePhase.CREDITS_TEXT;
                    case CREDITS_DEMO -> PlcLifecyclePhase.CREDITS_DEMO;
                    case POST_CREDITS, FINISHED -> PlcLifecyclePhase.POST_CREDITS;
                    case CUTSCENE -> PlcLifecyclePhase.ENDING;
                });
    }

    static void startToBlack(GameplayModeContext context, FadeManager fade, Runnable completion) {
        fade.startFadeToBlack(wrap(context, completion));
    }

    static void startFromBlack(GameplayModeContext context, FadeManager fade, Runnable completion) {
        fade.startFadeFromBlack(wrap(context, completion));
    }

    static void startToWhite(GameplayModeContext context, FadeManager fade, Runnable completion) {
        fade.startFadeToWhite(wrap(context, completion));
        // Pal_FadeToWhite's first act is a V-int wait, before any colour update
        // (docs/s2disasm/s2.asm:3571-3582). The frame that decided to fade has
        // already spent its V-int on the loop iteration that raised the
        // decision -- for the special stage exit that is the RunObjects pass
        // which set SS_Check_Rings_flag (s2.asm:6714-6725) -- so the first fade
        // step belongs to the next V-int, not to this one.
        fade.deferFirstStepToNextVint();
    }

    /**
     * Starts the same {@code Pal_FadeToWhite} window for a caller whose
     * iteration has already run its {@link FadeManager#update()} tick.
     *
     * <p>The deferral in {@link #startToWhite} models the fact that the
     * deciding iteration's V-int belongs to the loop the fade is leaving, not
     * to {@code Pal_FadeToWhite}'s first {@code WaitForVint}
     * (docs/s2disasm/s2.asm:3571-3582). It does that by swallowing the fade
     * tick of the iteration the fade is started in -- which is only the
     * deciding iteration's tick when the caller runs <em>before</em> that tick.
     * A caller that runs after it has already forgone the deciding iteration's
     * colour step by construction, so deferring again would swallow the first
     * real one too and stretch a 22-V-int routine
     * ({@code move.w #$15,d4} + {@code dbf}, s2.asm:3573-3581) over 23 rows.
     */
    static void startToWhiteAfterFrameFadeTick(
            GameplayModeContext context, FadeManager fade, Runnable completion) {
        fade.startFadeToWhite(wrap(context, completion));
    }

    static void startFromWhite(GameplayModeContext context, FadeManager fade, Runnable completion) {
        fade.startFadeFromWhite(wrap(context, completion));
    }

    private static Runnable wrap(GameplayModeContext context, Runnable completion) {
        Runnable callback = completion != null ? completion : () -> { };
        if (context == null || !context.isGameplayRuntimeReady()) {
            return callback;
        }
        return context.plcFrameLifecycle().beginNativeBlockingFade().wrapCompletion(callback);
    }
}
