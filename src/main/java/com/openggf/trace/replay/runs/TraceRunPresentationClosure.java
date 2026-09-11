package com.openggf.trace.replay.runs;

import com.openggf.GameLoop;
import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;

import java.util.Objects;

/** Shared host closure for a presentation row whose mode body is suppressed. */
public final class TraceRunPresentationClosure {

    private TraceRunPresentationClosure() {
    }

    public static void execute(
            GameLoop loop,
            TraceRunFrameDriver.Step step) {
        Objects.requireNonNull(loop, "loop");
        Objects.requireNonNull(step, "step");
        if (step.disposition()
                != TraceRunFrameDriver.Disposition
                        .PRESENTATION_SUPPRESSED_CLOSURE) {
            throw new IllegalArgumentException(
                    "presentation closure requires a suppressed disposition");
        }
        GameplayModeContext context = Objects.requireNonNull(
                SessionManager.getCurrentGameplayMode(),
                "gameplay mode");
        // Counter visibility and handler ownership are deliberately offset at
        // a recorded overrun boundary. The row whose sample does not yet show
        // Vint_runcount advancing still closes its real mode V-int (including
        // the active fade and queue service). The following advancing sample
        // represents the carried lag closure, so it must not advance the fade
        // a second time. PlcFrameLifecycleCoordinator documents the matching
        // ProcessDMAQueue-before-VintRet ordering used by both harnesses.
        if (step.observedVblankCounterAdvance()) {
            context.plcFrameLifecycle().runSuppressedLagIteration(
                    frame -> serviceLagClosure(context, frame));
        } else {
            context.plcFrameLifecycle().runLogicalIteration(
                    context::updateFade,
                    frame -> serviceLagClosure(context, frame));
        }
        if (step.commitDeferredBoundaryAfterClosure()) {
            loop.commitDeferredTraceRunModeBoundaryIfReady();
        }
    }

    private static Void serviceLagClosure(
            GameplayModeContext context,
            com.openggf.game.resources.PlcFrameLifecycleCoordinator
                    .PlcLifecycleFrame frame) {
        LevelFrameStep.serviceVBlankOnly(
                LevelFrameContext.from(context), frame,
                PlcLifecyclePhase.LAG);
        return null;
    }
}
