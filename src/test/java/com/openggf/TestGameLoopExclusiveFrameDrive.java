package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGameLoopExclusiveFrameDrive {
    private PlaybackDebugManager playback;
    private EngineContext context;

    @BeforeEach
    void setUp() {
        context = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(context);
        playback = PlaybackDebugManager.getInstance();
        playback.endSession();
    }

    @AfterEach
    void tearDown() {
        playback.endSession();
    }

    @Test
    void activePlaybackIsAnExternalOwnerEvenOutsideGameplayModes() {
        GameLoop loop = new GameLoop(new InputHandler());
        loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);

        assertFalse(loop.externalFrameOrInputOwnerActive());

        playback.startSession(movie(), 0);

        assertTrue(loop.externalFrameOrInputOwnerActive());
        assertTrue(ExternalFrameOrInputOwnership.active(context));
        playback.endSession();
        assertFalse(loop.externalFrameOrInputOwnerActive());
    }

    @Test
    void scheduledPlaybackAlsoReservesFrameAndInputOwnership() {
        GameLoop loop = new GameLoop(new InputHandler());

        playback.scheduleSessionAtNextLevelLoad(movie(), 0);

        assertTrue(loop.externalFrameOrInputOwnerActive());
        playback.cancelScheduledLevelLoadSession();
        assertFalse(loop.externalFrameOrInputOwnerActive());
    }

    private static Bk2Movie movie() {
        return new Bk2Movie(
                Path.of("exclusive-frame-drive.bk2"), "logkey", Map.of(),
                List.of(new Bk2FrameInput(0, 0, 0, false, "neutral")),
                1);
    }
}
