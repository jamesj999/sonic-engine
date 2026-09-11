package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.rewind.LiveRewindManager;

import java.util.Objects;

/**
 * Owns cross-mode polling while an audio reverse-release transaction retries.
 */
final class RewindReleaseRetryCoordinator {

    private RewindReleaseRetryCoordinator() {
    }

    static boolean consumePendingFrame(
            LiveRewindManager liveRewindManager, InputHandler inputHandler) {
        TraceSessionLauncher trace = TraceSessionLauncher.active();
        boolean consumed = trace != null && trace.retryPendingTeardown();
        if (!consumed) {
            consumed = Objects.requireNonNull(liveRewindManager, "liveRewindManager")
                    .retryPendingRelease();
        }
        if (consumed) Objects.requireNonNull(inputHandler, "inputHandler").update();
        return consumed;
    }
}
