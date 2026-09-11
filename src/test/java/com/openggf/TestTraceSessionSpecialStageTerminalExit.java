package com.openggf;

import com.openggf.audio.GameSound;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_1)
class TestTraceSessionSpecialStageTerminalExit {

    @AfterEach
    void tearDown() {
        GameServices.audio().resetState();
        SessionManager.clear();
    }

    @Test
    void standaloneAudioInstallsBaseSoundMapWithoutStartingMusic()
            throws Exception {
        TraceSessionLauncher session = session();
        GameServices.audio().resetState();

        session.configureStandaloneSpecialStageAudio();

        Field soundMap = GameServices.audio().getClass()
                .getDeclaredField("soundMap");
        soundMap.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<GameSound, Integer> installed =
                (Map<GameSound, Integer>) soundMap.get(GameServices.audio());
        assertEquals(GameServices.module().getAudioProfile().getSoundMap(),
                installed);
        assertTrue(GameServices.audio().commandTimeline().entries().stream()
                        .noneMatch(entry -> entry.command()
                                instanceof AudioCommand.PlayMusic),
                "audio routing setup must not start or restart music");
    }

    @Test
    void nativeWhiteHoldDefersTeardownWithoutStartingASecondFade()
            throws Exception {
        TraceSessionLauncher session = session();
        setProductionIterationInProgress(session, true);
        GameServices.fade().holdWhite();

        session.beginSpecialStageTerminalExit();

        assertEquals(FadeManager.FadeState.HOLD_WHITE,
                GameServices.fade().getState());
        assertTrue(teardownPending(session));
        assertFalse(session.shouldSkipCurrentSpecialStageTick(),
                "a structural unit seam without SS data remains neutral");
    }

    @Test
    void idleTerminalStateUsesTheNormalBlackFade() {
        TraceSessionLauncher session = session();

        session.beginSpecialStageTerminalExit();

        assertEquals(FadeManager.FadeState.FADING_TO_BLACK,
                GameServices.fade().getState());
        assertTrue(GameServices.fade().hasPendingCompletion());
    }

    @Test
    void existingCallbackFadeIsNeverOverwritten() {
        TraceSessionLauncher session = session();
        GameServices.fade().startFadeToWhite(() -> { });

        session.beginSpecialStageTerminalExit();

        assertEquals(FadeManager.FadeState.FADING_TO_WHITE,
                GameServices.fade().getState());
        assertTrue(GameServices.fade().hasPendingCompletion());
    }

    private static TraceSessionLauncher session() {
        return new TraceSessionLauncher(null, null,
                List.<com.openggf.trace.replay.runs.TraceRunSegmentDescriptor>of(),
                null, null);
    }

    private static void setProductionIterationInProgress(
            TraceSessionLauncher session, boolean value)
            throws Exception {
        Field field = TraceSessionLauncher.class
                .getDeclaredField("productionIterationInProgress");
        field.setAccessible(true);
        field.setBoolean(session, value);
    }

    private static boolean teardownPending(
            TraceSessionLauncher session) throws Exception {
        Field field = TraceSessionLauncher.class
                .getDeclaredField("teardownPending");
        field.setAccessible(true);
        return field.getBoolean(session);
    }
}
