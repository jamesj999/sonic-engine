package com.openggf.tools;

import com.openggf.LevelFrameResult;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.GameModule;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareSubmissionFingerprint;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.game.timing.RecordedCompletionAuthority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRecordingFrameDriverHardwareTiming {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void pausedDriverRowTraversesOnlyVintService() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<String> events = observe(context);
        context.getGameStateManager().setGamePaused(true);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));

        LevelFrameResult result =
                driver.stepFrame(false, false, false, false, false);

        assertEquals(LevelFrameResult.PAUSED, result);
        assertEquals(List.of("VINT_SERVICE"), events);
    }

    @Test
    void setupOnlyDriverRowDoesNotInventABoundary() throws Exception {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<String> events = observe(context);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        LevelManager level = mock(LevelManager.class);
        when(level.consumePendingInitialProcessSpritesPass()).thenReturn(true);
        setField(driver, "levelManager", level);

        LevelFrameResult result =
                driver.stepFrame(false, false, false, false, false);

        assertEquals(LevelFrameResult.SETUP_ONLY, result);
        assertEquals(List.of(), events);
    }

    @Test
    void inputOnlyAdvanceConsumesNoProductionBoundary() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<String> events = observe(context);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        driver.setBk2Movie(oneFrameMovie(), 0);

        driver.consumeRecordingFrameInputOnly();

        assertEquals(List.of(), events);
    }

    @Test
    void pausedSuppressedRowsPollStartAndExposeTheLaterUnpauseEdge() throws Exception {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        context.getGameStateManager().setGamePaused(true);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        LevelManager level = mock(LevelManager.class);
        when(level.getObjectManager()).thenReturn(mock(ObjectManager.class));
        setField(driver, "levelManager", level);
        Bk2Movie movie = new Bk2Movie(
                Path.of("pause-loop-test.bk2"),
                "logkey",
                Map.of(),
                List.of(
                        new Bk2FrameInput(0, 0, 0, true, "start"),
                        new Bk2FrameInput(1, 0, 0, true, "held start"),
                        new Bk2FrameInput(2, 0, 0, false, "released"),
                        new Bk2FrameInput(3, 0, 0, true, "unpause")),
                4);
        driver.setBk2Movie(movie, 1);

        driver.skipFrameFromRecording();
        assertTrue(context.getGameStateManager().isGamePaused(),
                "held Start is not a second press inside Pause_Loop");
        driver.skipFrameFromRecording();
        assertTrue(context.getGameStateManager().isGamePaused());
        driver.skipFrameFromRecording();

        assertFalse(context.getGameStateManager().isGamePaused(),
                "VInt_10 polls the released row, so the later Start is an unpause edge");
    }

    @Test
    void beginTraceRowForwardsRawFrameRatherThanTraceIndex() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        RecordedCompletionAuthority authority = mock(RecordedCompletionAuthority.class);
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(HardwareTimingSchedule.empty());
        TraceHardwareTimingBoundaryObserver observer =
                new TraceHardwareTimingBoundaryObserver(port);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        driver.installHardwareTimingReplayObserver(observer);

        driver.beginTraceRow(7, 0x1234);

        assertEquals(0x1234, port.capture().rawFrameLatch());
    }

    @Test
    void sharedDriverFixtureOwnsTheHardwareTimingReplayLifecycle() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        RecordedCompletionAuthority authority = mock(RecordedCompletionAuthority.class);
        when(authority.pendingSubmissions()).thenReturn(List.of());
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(HardwareTimingSchedule.empty());
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        TraceReplayDrive.DriverFixture fixture =
                new TraceReplayDrive.DriverFixture(driver);

        fixture.installHardwareTimingReplay(port);

        assertTrue(context.getRewindRegistry().capture().entries()
                .containsKey(HardwareTimingReplayPort.REWIND_KEY));
        fixture.beginTraceRow(7, 0x1234);
        assertEquals(0x1234, port.capture().rawFrameLatch());
        fixture.enterHardwareTimingGap();
        assertNull(port.capture().rawFrameLatch());
        fixture.verifyHardwareTimingSegmentEdges();
        fixture.handoffHardwareTimingReplay(HardwareTimingSchedule.empty());

        fixture.closeHardwareTimingReplayRun();
        fixture.closeHardwareTimingReplayRun();

        assertTrue(port.capture().runComplete());
        assertFalse(context.getRewindRegistry().capture().entries()
                .containsKey(HardwareTimingReplayPort.REWIND_KEY));
        assertSame(HardwareTimingBoundaryObserver.NO_OP,
                context.hardwareTimingBoundaryObserver());
        verify(authority, times(1)).endRecordedAdmission();
    }

    @Test
    void sharedDriveBeginsEachRepresentedRawFrameExactlyOnce() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        RecordedCompletionAuthority authority = mock(RecordedCompletionAuthority.class);
        HardwareTimingReplayPort port =
                spy(new HardwareTimingReplayPort(authority));
        port.install(HardwareTimingSchedule.empty());
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        driver.installHardwareTimingReplayObserver(
                new TraceHardwareTimingBoundaryObserver(port));
        driver.setBk2Movie(oneFrameMovie(), 0);
        TraceData trace = mock(TraceData.class);
        when(trace.getFrame(3)).thenReturn(
                TraceFrame.of(0x1234, 0,
                        (short) 0, (short) 0,
                        (short) 0, (short) 0, (short) 0,
                        (byte) 0, false, false, 0));

        TraceReplayDrive.driveOneFrame(
                trace,
                driver,
                TraceReplayBootstrap.ReplayStartState.DEFAULT,
                TraceExecutionPhase.ADVANCE_ONLY,
                3);

        verify(port, times(1)).beginRawFrame(0x1234);
    }

    @Test
    void vblankOnlyRowAdmitsExactPreparedCurrentRowCompletionWithoutMoreWork()
            throws Exception {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        RecordedCompletionAuthority authority =
                context.hardwareTiming().beginRecordedAdmission();
        CountingPreparation preparation = new CountingPreparation(new byte[] {35});
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                0x36800E,
                0x100,
                0x5000,
                1,
                "KosM",
                1,
                false,
                preparation);
        HardwareCompletionEdge edge = new HardwareCompletionEdge(
                0x18CA,
                com.openggf.game.timing.HardwareServiceBoundary.PRE_MAIN_LOOP,
                submission.kind(),
                0,
                HardwareSubmissionFingerprint.compute(submission));
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(new HardwareTimingSchedule(List.of(edge)));
        HardwareWorkHandle handle = context.hardwareTiming().submit(submission);
        context.hardwareTiming().service(
                com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS);
        int preparationSteps = preparation.steps();
        TraceHardwareTimingBoundaryObserver observer =
                new TraceHardwareTimingBoundaryObserver(port);
        context.setHardwareTimingBoundaryObserver(observer);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        LevelManager level = mock(LevelManager.class);
        when(level.getObjectManager()).thenReturn(mock(ObjectManager.class));
        setField(driver, "levelManager", level);
        driver.installHardwareTimingReplayObserver(observer);
        driver.setBk2Movie(oneFrameMovie(), 0);
        driver.beginTraceRow(0, 0x18CA);

        driver.skipFrameFromRecording();

        assertTrue(context.hardwareTiming().isReady(handle));
        assertEquals(List.of(handle), context.hardwareTiming().pendingHandles());
        assertEquals(preparationSteps, preparation.steps(),
                "the suppressed completion must not advance production preparation");
        assertEquals(1, port.capture().consumedIdentities().size());
    }

    @Test
    void heldS3kTitleCardSkipSurroundsActualProviderScanWithBoundaries()
            throws Exception {
        TitleCardProvider provider = mock(TitleCardProvider.class);
        GameModule module = spy(new Sonic3kGameModule());
        doReturn(provider).when(module).getTitleCardProvider();
        doReturn(null).when(module).getLevelEventProvider();
        TestEnvironment.configureGameModuleFixture(module);
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        List<String> events = observe(context);
        when(provider.advancesOnHeldLevelCounter()).thenReturn(true);
        doAnswer(ignored -> {
            events.add("TITLE_CARD_SCAN");
            return null;
        }).when(provider).update();
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        LevelManager level = mock(LevelManager.class);
        when(level.getObjectManager()).thenReturn(mock(ObjectManager.class));
        setField(driver, "levelManager", level);
        driver.setBk2Movie(oneFrameMovie(), 0);

        driver.skipFrameFromRecording();

        assertEquals(List.of(
                "VINT_SERVICE",
                "TITLE_CARD_SCAN",
                "POST_OBJECTS",
                "PRE_MAIN_LOOP"), events);
    }

    private static List<String> observe(GameplayModeContext context) {
        List<String> events = new ArrayList<>();
        context.setHardwareTimingBoundaryObserver(
                boundary -> events.add(boundary.name()));
        return events;
    }

    private static Bk2Movie oneFrameMovie() {
        return new Bk2Movie(
                Path.of("hardware-timing-test.bk2"),
                "logkey",
                Map.of(),
                List.of(new Bk2FrameInput(0, 0, 0, false, "")),
                1);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record CountingPreparationSnapshot(byte[] payload)
            implements HardwareWorkPreparationSnapshot {
        private CountingPreparationSnapshot {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new CountingPreparation(payload);
        }
    }

    private static final class CountingPreparation
            implements HardwareWorkPreparation {
        private final byte[] payload;
        private boolean prepared;
        private int steps;

        private CountingPreparation(byte[] payload) {
            this.payload = payload.clone();
        }

        @Override
        public boolean stepOneWorkUnit() {
            steps++;
            prepared = true;
            return true;
        }

        @Override
        public boolean isPrepared() {
            return prepared;
        }

        @Override
        public byte[] preparedPayload() {
            if (!prepared) {
                throw new IllegalStateException("payload requested before preparation");
            }
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new CountingPreparationSnapshot(payload);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            prepared = true;
        }

        private int steps() {
            return steps;
        }
    }
}
