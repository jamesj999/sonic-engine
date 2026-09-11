package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.rewind.LiveRewindManager;

/** Routes a completed level frame to the rewind host that owns the session. */
final class LevelRewindFrameRecorder {
    private LevelRewindFrameRecorder() {
    }

    static void record(
            TraceSessionLauncher traceSession,
            LiveRewindManager liveRewindManager,
            GameMode gameMode,
            boolean nonRewindableTransitionPending,
            InputHandler input,
            boolean seamlessTransitionCompleted) {
        if (traceSession != null) {
            traceSession.recordExternalRewindFrame(seamlessTransitionCompleted);
            return;
        }
        liveRewindManager.recordExternalFrame(
                gameMode,
                nonRewindableTransitionPending,
                input,
                seamlessTransitionCompleted);
    }
}
