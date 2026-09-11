package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.rewind.LiveRewindManager;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class TestLevelRewindFrameRecorder {

    @Test
    void inFrameBoundaryRerootsTheLiveRecorderAfterTheCompletedFrame() {
        LiveRewindManager live = mock(LiveRewindManager.class);
        InputHandler input = new InputHandler();

        LevelRewindFrameRecorder.record(
                null, live, GameMode.LEVEL, false, input, true);

        verify(live).recordExternalFrame(GameMode.LEVEL, false, input, true);
    }

    @Test
    void inFrameBoundaryUsesTheTraceOwnedRecorderWhenAVisualTraceIsActive() {
        TraceSessionLauncher trace = mock(TraceSessionLauncher.class);
        LiveRewindManager live = mock(LiveRewindManager.class);

        LevelRewindFrameRecorder.record(
                trace, live, GameMode.LEVEL, false, new InputHandler(), true);

        verify(trace).recordExternalRewindFrame(true);
        verify(live, never()).recordExternalFrame(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }
}
