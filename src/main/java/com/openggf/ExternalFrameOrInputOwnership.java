package com.openggf;

import com.openggf.game.session.EngineContext;

import java.util.Objects;

/** Production-owned semantic boundary for exclusive frame and input drive. */
public final class ExternalFrameOrInputOwnership {
    private ExternalFrameOrInputOwnership() {
    }

    public static boolean active(EngineContext engineServices) {
        Objects.requireNonNull(engineServices, "engineServices");
        return TraceSessionLauncher.active() != null
                || engineServices.playbackDebug().hasActiveOrScheduledSession();
    }
}
