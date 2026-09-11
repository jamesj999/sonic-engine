package com.openggf;

import com.openggf.game.GameMode;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLevelIterationAdmissionController {
    private TraceSessionLauncher previousTraceSession;

    @BeforeEach
    void clearActiveTraceSession() throws Exception {
        previousTraceSession = TraceSessionLauncher.active();
        setActiveTraceSession(null);
    }

    @AfterEach
    void restoreActiveTraceSession() throws Exception {
        setActiveTraceSession(previousTraceSession);
    }

    @Test
    void admittedSeamlessTransitionCompletesThroughLiveBoundaryReporter() {
        var controller = new LevelIterationAdmissionController();
        var level = mock(LevelManager.class);
        var recording = mock(UserRecordingRuntimeControls.class);
        Runnable startPendingTitle = mock(Runnable.class);
        Runnable deactivateHardwareTiming = mock(Runnable.class);
        var request = SeamlessLevelTransitionRequest.builder(
                SeamlessLevelTransitionRequest.TransitionType.MUTATE_ONLY)
                .build();
        when(level.consumeSeamlessTransitionRequest()).thenReturn(request);
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()));
        List<RewindBoundary> boundaries = new ArrayList<>();
        context.setRewindBoundaryReporter(boundaries::add);

        LevelFrameResult result = controller.admit(
                GameMode.LEVEL,
                () -> false,
                () -> LevelFrameResult.SETUP_ONLY,
                level,
                context,
                false,
                recording,
                startPendingTitle,
                () -> { },
                deactivateHardwareTiming);

        assertEquals(LevelFrameResult.GAMEPLAY_FRAME, result);
        var admissionOrder = inOrder(level, startPendingTitle);
        admissionOrder.verify(level).applySeamlessTransition(request);
        admissionOrder.verify(startPendingTitle).run();
        verify(deactivateHardwareTiming).run();

        @SuppressWarnings("unchecked")
        Consumer<Boolean> updateAudio = mock(Consumer.class);
        Runnable finishPlayback = mock(Runnable.class);
        assertTrue(controller.completePendingBoundary(
                true, updateAudio, finishPlayback, () -> context));

        verify(updateAudio).accept(true);
        verify(finishPlayback).run();
        assertEquals(
                List.of(RewindBoundary.SEAMLESS_LEVEL_TRANSITION),
                boundaries);
        assertFalse(controller.completePendingBoundary(
                true, updateAudio, finishPlayback, () -> context),
                "a completed seamless boundary must not be reported twice");
    }

    @Test
    void visualTraceOwnsEscapeDuringTitleCardPresentation()
            throws Exception {
        TraceSessionLauncher session = new TraceSessionLauncher(
                null, null,
                List.<com.openggf.trace.replay.runs.TraceRunSegmentDescriptor>of(),
                null, null);
        session.beginTitleCardPresentation(
                new TraceSessionLauncher.TitleCardPresentation() {
                    @Override
                    public void prepareLevel() {
                    }

                    @Override
                    public void enterTitleCard() {
                    }
                });

        assertTrue(LevelIterationAdmissionController.shouldVisualTraceOwnEscape(
                GameMode.TITLE_CARD, session, true));
        assertFalse(LevelIterationAdmissionController.shouldVisualTraceOwnEscape(
                GameMode.TITLE_CARD, session, false));
    }

    @Test
    void visualTraceTitleCardReleaseBecomesAOneShotSetupBarrier()
            throws Exception {
        TraceSessionLauncher session = new TraceSessionLauncher(
                null, null,
                List.<com.openggf.trace.replay.runs.TraceRunSegmentDescriptor>of(),
                null, null);
        session.beginTitleCardPresentation(
                new TraceSessionLauncher.TitleCardPresentation() {
                    @Override
                    public void prepareLevel() {
                    }

                    @Override
                    public void enterTitleCard() {
                    }
                });
        var controller = new LevelIterationAdmissionController();

        LevelFrameResult release = controller.admit(
                GameMode.TITLE_CARD,
                () -> true,
                () -> LevelFrameResult.GAMEPLAY_FRAME,
                mock(LevelManager.class),
                new GameplayModeContext(
                        new WorldSession(new Sonic2GameModule())),
                false,
                mock(UserRecordingRuntimeControls.class),
                () -> { }, () -> { }, () -> { });

        assertEquals(LevelFrameResult.SETUP_ONLY, release,
                "S2 must not fall through into an uncontrolled level frame");
        assertTrue(session.beginReplayBootstrapAfterTitleCardIfReady(
                GameMode.LEVEL));
        assertFalse(session.beginReplayBootstrapAfterTitleCardIfReady(
                GameMode.LEVEL));
    }

    @Test
    void visualTraceSetupOnlyTitleCardReleaseStillArmsReplayBootstrap()
            throws Exception {
        TraceSessionLauncher session = new TraceSessionLauncher(
                null, null,
                List.<com.openggf.trace.replay.runs.TraceRunSegmentDescriptor>of(),
                null, null);
        session.beginTitleCardPresentation(
                new TraceSessionLauncher.TitleCardPresentation() {
                    @Override
                    public void prepareLevel() {
                    }

                    @Override
                    public void enterTitleCard() {
                    }
                });
        var controller = new LevelIterationAdmissionController();

        LevelFrameResult release = controller.admit(
                GameMode.TITLE_CARD,
                () -> true,
                () -> LevelFrameResult.SETUP_ONLY,
                mock(LevelManager.class),
                new GameplayModeContext(
                        new WorldSession(new Sonic2GameModule())),
                false,
                mock(UserRecordingRuntimeControls.class),
                () -> { }, () -> { }, () -> { });

        assertEquals(LevelFrameResult.SETUP_ONLY, release);
        assertTrue(session.beginReplayBootstrapAfterTitleCardIfReady(
                GameMode.LEVEL),
                "S1/S3K setup-only release must still arm prepared replay");
    }

    private static void setActiveTraceSession(TraceSessionLauncher session)
            throws Exception {
        Field field = TraceSessionLauncher.class.getDeclaredField("activeSession");
        field.setAccessible(true);
        field.set(null, session);
    }
}
