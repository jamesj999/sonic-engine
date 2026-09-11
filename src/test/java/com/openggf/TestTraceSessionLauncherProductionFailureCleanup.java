package com.openggf;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.control.InputHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class TestTraceSessionLauncherProductionFailureCleanup {

    private GameplayModeContext gameplayMode;
    private GameLoop gameLoop;

    @BeforeEach
    void setUp() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        gameplayMode = SessionManager.getCurrentGameplayMode();
        gameLoop = mock(GameLoop.class);
        when(gameLoop.getCurrentGameMode()).thenReturn(GameMode.LEVEL);
        when(gameLoop.getInputHandler()).thenReturn(new InputHandler());
        installCurrentLoop(gameLoop);
    }

    @AfterEach
    void tearDown() {
        Engine.clearGlobalInstance();
        GameServices.playbackDebug().endSession();
        SessionManager.clear();
    }

    @Test
    void runtimeFailureAbortsAuthorityAndReturnsToTitleWithoutStrictClose() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        AtomicBoolean payloadClosed = new AtomicBoolean();
        TraceSessionLauncher session = activeSession(fixture, payloadClosed);
        RuntimeException primary = new RuntimeException("production body failed");

        TraceSessionLauncher.runProductionIterationIfActive(() -> {
            throw primary;
        });

        assertNull(TraceSessionLauncher.active());
        assertTrue(payloadClosed.get());
        assertFalse(gameplayMode.isGameplayRuntimeReady());
        verify(fixture).abortHardwareTimingReplayRun();
        verify(gameLoop, atLeastOnce()).setTraceCameraFocusController(null);
        verify(gameLoop).returnToMasterTitle();
        verify(fixture, org.mockito.Mockito.never())
                .closeHardwareTimingReplayRun();
    }

    @Test
    void fatalErrorKeepsCleanupFailureSuppressedAndRethrowsOriginal() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        IllegalStateException cleanupFailure =
                new IllegalStateException("abort detach failed");
        doThrow(cleanupFailure).when(fixture).abortHardwareTimingReplayRun();
        AtomicBoolean payloadClosed = new AtomicBoolean();
        TraceSessionLauncher session = activeSession(fixture, payloadClosed);
        AssertionError primary = new AssertionError("fatal production failure");

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> TraceSessionLauncher.runProductionIterationIfActive(() -> {
                    throw primary;
                }));

        assertSame(primary, thrown);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertTrue(payloadClosed.get());
        assertNull(TraceSessionLauncher.active());
        assertFalse(gameplayMode.isGameplayRuntimeReady());
        verify(gameLoop).returnToMasterTitle();
    }

    @Test
    void postFinishComparisonFailureIsContainedAndAbortsSession() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        TraceSessionLauncher session = activeSession(
                fixture, new AtomicBoolean());
        armPendingDynamicPublication(session);

        TraceSessionLauncher.runProductionIterationIfActive(() -> { });

        assertNull(TraceSessionLauncher.active());
        assertFalse(gameplayMode.isGameplayRuntimeReady());
        verify(fixture).abortHardwareTimingReplayRun();
        verify(gameLoop).returnToMasterTitle();
    }

    @Test
    void bodyFailureClosesTheRealPayloadBeforePostFinishPublication() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        AtomicBoolean payloadClosed = new AtomicBoolean();
        TraceSessionLauncher session = activeSession(fixture, payloadClosed);
        armPendingDynamicPublication(session);
        RuntimeException primary = new RuntimeException("production body failed");

        TraceSessionLauncher.runProductionIterationIfActive(() -> {
            throw primary;
        });

        assertEquals(0, primary.getSuppressed().length,
                "a failed body never reaches post-finish publication");
        assertTrue(payloadClosed.get());
        assertNull(TraceSessionLauncher.active());
        verify(fixture).abortHardwareTimingReplayRun();
    }

    @Test
    void observedStepAssertionAbortsRealSessionAndRethrowsOriginal() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        AtomicBoolean payloadClosed = new AtomicBoolean();
        TraceSessionLauncher session = activeSession(fixture, payloadClosed);
        TraceRunPlaybackCoordinator coordinator =
                (TraceRunPlaybackCoordinator) field(session, "runCoordinator");
        AssertionError primary = new AssertionError("observed-step assertion");
        when(coordinator.beforeAdmission(any())).thenThrow(primary);
        when(coordinator.abort(anyString())).thenReturn(List.of());

        AssertionError thrown = assertThrows(AssertionError.class,
                () -> session.runAdvanceTickIfActive(GameMode.LEVEL, 0));

        assertSame(primary, thrown);
        assertTrue(payloadClosed.get());
        assertNull(TraceSessionLauncher.active());
        verify(fixture).abortHardwareTimingReplayRun();
        verify(gameLoop).returnToMasterTitle();
    }

    @Test
    void completedObservedStepDirectlyAbortsWhenCompletionCleanupFails() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        RuntimeException primary =
                new RuntimeException("completion hardware close failed");
        IllegalStateException cleanup =
                new IllegalStateException("completion abort failed");
        doThrow(primary).when(fixture).closeHardwareTimingReplayRun();
        doThrow(cleanup).when(fixture).abortHardwareTimingReplayRun();
        AtomicBoolean payloadClosed = new AtomicBoolean();
        TraceSessionLauncher session = activeSession(fixture, payloadClosed);
        LiveTraceComparator comparator = mock(LiveTraceComparator.class);
        when(comparator.isComplete()).thenReturn(true);
        setField(session, "comparator", comparator);
        TraceRunPlaybackCoordinator coordinator =
                (TraceRunPlaybackCoordinator) field(session, "runCoordinator");
        AtomicReference<TraceRunPlaybackCoordinator.Phase> phase =
                new AtomicReference<>(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT);
        when(coordinator.phase()).thenAnswer(invocation -> phase.get());
        when(coordinator.afterProduction(any())).thenAnswer(invocation -> {
            phase.set(TraceRunPlaybackCoordinator.Phase.COMPLETE);
            return List.of(
                    new TraceRunPlaybackCoordinator.CloseSegment(0),
                    new TraceRunPlaybackCoordinator.CompleteRun(0));
        });
        when(coordinator.abort(anyString())).thenReturn(List.of());

        session.runAdvanceTickIfActive(GameMode.LEVEL, 0);

        assertTrue(payloadClosed.get());
        assertNull(TraceSessionLauncher.active());
        assertSame(cleanup, primary.getSuppressed()[0]);
        verify(gameLoop).returnToMasterTitle();
    }

    private static TraceSessionLauncher activeSession(
            TraceReplayFixture fixture, AtomicBoolean payloadClosed) {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadata("s2", 0, 0),
                List.of(TraceFrame.executionTestFrame(0, 0, 0, 0)));
        TraceRunManifest.Segment segment = new TraceRunManifest.Segment(
                "active", "level", null, 0, 1, 0, 0, null, null);
        Bk2Movie movie = new Bk2Movie(
                Path.of("production-failure.bk2"), "logkey", Map.of(),
                List.of(new Bk2FrameInput(0, 0, 0, false, "row zero")), 1);
        TraceSessionLauncher session = TestRunPayloads.session(
                null, movie,
                List.of(new TraceRunReplayWalker.SegmentPlan(
                        segment, trace, null, null)),
                TraceReplaySessionBootstrap.snapshotGameplayConfig(),
                payloadClosed);
        TraceRunFrameDriver driver = new TraceRunFrameDriver();
        TraceRunPlaybackCoordinator coordinator =
                mock(TraceRunPlaybackCoordinator.class);
        when(coordinator.phase()).thenReturn(
                TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT);
        when(coordinator.currentSegmentIndex()).thenReturn(0);
        setField(session, "runFrameDriver", driver);
        setField(session, "runCoordinator", coordinator);
        setField(session, "fixture", fixture);
        SessionManager.getCurrentGameplayMode()
                .installTraceRunFrameDriver(driver);
        GameServices.playbackDebug().startSession(movie, 0);
        setStaticField(TraceSessionLauncher.class, "activeSession", session);
        return session;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void armPendingDynamicPublication(
            TraceSessionLauncher session) {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(
                        TraceFrame.executionTestFrame(0, 0, 0, 0),
                        TraceFrame.executionTestFrame(1, 1, 1, 1)),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0, List.of(), List.of())),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1, List.of(), List.of()))));
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null);
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "row zero"), false);
        setField(session, "comparator", comparator);
    }

    private static void installCurrentLoop(GameLoop loop) throws Exception {
        Engine engine = mock(Engine.class);
        Field loopField = Engine.class.getDeclaredField("gameLoop");
        loopField.setAccessible(true);
        loopField.set(engine, loop);
        setStaticField(Engine.class, "instance", engine);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setStaticField(
            Class<?> owner, String name, Object value) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
