package com.openggf;

import com.openggf.camera.Camera;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.game.timing.RomWorkBudgetScheduler;
import com.openggf.level.LevelManager;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLevelFrameHardwareTimingBoundaries {

    @Test
    void frameRetiresPostObjectsAndDirectTailAfterScreenEvents() {
        List<String> events = new ArrayList<>();
        HardwareTimingService timing = spy(new HardwareTimingService(
                new RomWorkBudgetScheduler(Map.of(
                        HardwareServiceBoundary.VINT_SERVICE, 1,
                        HardwareServiceBoundary.PRE_MAIN_LOOP, 1,
                        HardwareServiceBoundary.POST_OBJECTS, 1))));
        TestPreparation preparation = new TestPreparation(3);
        HardwareWorkHandle handle = timing.submit(submission(preparation));
        LevelEventProvider levelEvents = mock(LevelEventProvider.class);
        doAnswer(ignored -> {
            events.add("screen-events:ready=" + timing.isReady(handle));
            return null;
        }).when(levelEvents).update();
        LevelFrameContext context = context(timing, levelEvents, boundary ->
                events.add("observer:" + boundary + ":ready=" + timing.isReady(handle)));
        LevelManager level = mock(LevelManager.class);
        doAnswer(ignored -> {
            events.add("objects:ready=" + timing.isReady(handle));
            return null;
        }).when(level).updateObjectPositionsWithoutTouches(false);
        doAnswer(ignored -> {
            events.add("oscillation-tail");
            return null;
        }).when(level).advanceGlobalOscillationAtLevelLoopTail();
        Camera camera = mock(Camera.class);
        doAnswer(ignored -> {
            events.add("camera");
            return null;
        }).when(camera).updatePosition();

        LevelFrameResult firstResult = LevelFrameTestStep.execute(
                context,
                level,
                camera,
                () -> events.add("physics"),
                LevelFrameStep.DIRECT_WRAPPER);
        LevelFrameResult secondResult = LevelFrameTestStep.execute(
                context,
                level,
                camera,
                () -> events.add("physics"),
                LevelFrameStep.DIRECT_WRAPPER);

        assertEquals(List.of(
                        "observer:VINT_SERVICE:ready=false",
                        "objects:ready=false",
                        "physics",
                        "camera",
                        "screen-events:ready=false",
                        "oscillation-tail",
                        "observer:POST_OBJECTS:ready=false",
                        "observer:PRE_MAIN_LOOP:ready=true",
                        "observer:VINT_SERVICE:ready=true",
                        "objects:ready=true",
                        "physics",
                        "camera",
                        "screen-events:ready=true",
                        "oscillation-tail",
                        "observer:POST_OBJECTS:ready=true",
                        "observer:PRE_MAIN_LOOP:ready=true"),
                events);
        assertEquals(LevelFrameResult.GAMEPLAY_FRAME, firstResult);
        assertEquals(LevelFrameResult.GAMEPLAY_FRAME, secondResult);
        assertEquals(3, preparation.completedWorkUnits(),
                "the first frame must retire exactly one unit at each canonical boundary");
        verify(timing, times(2)).service(HardwareServiceBoundary.VINT_SERVICE);
        verify(timing, times(2)).service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        verify(timing, times(2)).service(HardwareServiceBoundary.POST_OBJECTS);
    }

    @Test
    void inlineSolidFrameRunsPhysicsBeforeObjectsAndRetiresAtLoopTail() {
        List<String> events = new ArrayList<>();
        HardwareTimingService timing = new HardwareTimingService(
                new RomWorkBudgetScheduler(Map.of(
                        HardwareServiceBoundary.VINT_SERVICE, 1,
                        HardwareServiceBoundary.PRE_MAIN_LOOP, 1,
                        HardwareServiceBoundary.POST_OBJECTS, 1)));
        HardwareWorkHandle handle = timing.submit(submission(3));
        LevelEventProvider levelEvents = mock(LevelEventProvider.class);
        doAnswer(ignored -> {
            events.add("screen-events:ready=" + timing.isReady(handle));
            return null;
        }).when(levelEvents).update();
        LevelFrameContext context = context(timing, levelEvents, boundary ->
                events.add("observer:" + boundary + ":ready=" + timing.isReady(handle)));
        LevelManager level = mock(LevelManager.class);
        when(level.objectsExecuteAfterPlayerPhysics()).thenReturn(true);
        doAnswer(ignored -> {
            events.add("objects:ready=" + timing.isReady(handle));
            return null;
        }).when(level).updateObjectPositionsPostPhysicsWithoutTouches(
                any(), org.mockito.ArgumentMatchers.eq(false));
        doAnswer(ignored -> {
            events.add("oscillation-tail");
            return null;
        }).when(level).advanceGlobalOscillationAtLevelLoopTail();
        Camera camera = mock(Camera.class);
        doAnswer(ignored -> {
            events.add("camera");
            return null;
        }).when(camera).updatePosition();

        LevelFrameResult result = LevelFrameTestStep.execute(
                context,
                level,
                camera,
                () -> events.add("physics"),
                LevelFrameStep.DIRECT_WRAPPER);

        assertEquals(LevelFrameResult.GAMEPLAY_FRAME, result);
        assertEquals(List.of(
                        "observer:VINT_SERVICE:ready=false",
                        "physics",
                        "objects:ready=false",
                        "camera",
                        "screen-events:ready=false",
                        "oscillation-tail",
                        "observer:POST_OBJECTS:ready=false",
                        "observer:PRE_MAIN_LOOP:ready=true"),
                events);
    }

    @Test
    void directTailReadinessIsVisibleToNextFrameObjects() {
        HardwareTimingService timing = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(HardwareServiceBoundary.PRE_MAIN_LOOP));
        var authority = timing.beginRecordedAdmission(Map.of(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                com.openggf.game.timing.HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                com.openggf.game.timing.HardwareReadinessAdmissionPolicy.RECORDED,
                HardwareWorkKind.NEMESIS_PLC_QUEUE,
                com.openggf.game.timing.HardwareReadinessAdmissionPolicy.LIVE));
        HardwareWorkSubmission submission = submission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 1);
        HardwareCompletionEdge edge = new HardwareCompletionEdge(
                0, HardwareServiceBoundary.PRE_MAIN_LOOP,
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0,
                com.openggf.game.timing.HardwareSubmissionFingerprint.compute(submission));
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(new HardwareTimingSchedule(List.of(edge)));
        HardwareWorkHandle direct = timing.submit(submission);
        port.beginRawFrame(0);
        LevelFrameContext context = context(timing,
                new TraceHardwareTimingBoundaryObserver(port));

        List<Boolean> objectReadiness = new ArrayList<>();
        LevelFrameStep.StepWrapper wrapper = (name, step) -> {
            if ("objects".equals(name)) {
                objectReadiness.add(timing.isReady(direct));
            }
            step.run();
        };

        LevelFrameTestStep.execute(context, mock(LevelManager.class),
                mock(Camera.class), () -> { }, wrapper);
        port.beginRawFrame(1);
        LevelFrameTestStep.execute(context, mock(LevelManager.class),
                mock(Camera.class), () -> { }, wrapper);

        assertEquals(List.of(false, true), objectReadiness);
    }

    @Test
    void vblankOnlyPathEmitsOnlyVintService() {
        List<HardwareServiceBoundary> boundaries = new ArrayList<>();
        HardwareTimingService timing = new HardwareTimingService();
        LevelFrameContext context = context(timing, boundaries::add);

        LevelFrameTestStep.serviceVBlankOnly(context);

        assertEquals(List.of(HardwareServiceBoundary.VINT_SERVICE), boundaries);
    }

    @Test
    void setupOnlyPassDoesNotInventHardwareBoundaries() {
        List<HardwareServiceBoundary> boundaries = new ArrayList<>();
        HardwareTimingService timing = new HardwareTimingService();
        LevelFrameContext context = context(timing, boundaries::add);
        LevelManager level = mock(LevelManager.class);
        when(level.consumePendingInitialProcessSpritesPass()).thenReturn(true);

        LevelFrameResult result = LevelFrameTestStep.execute(
                context, level, mock(Camera.class), () -> {
                });

        assertEquals(LevelFrameResult.SETUP_ONLY, result);
        assertTrue(boundaries.isEmpty());
    }

    @Test
    void pausedFrameServicesOnlyVintBoundary() {
        List<HardwareServiceBoundary> boundaries = new ArrayList<>();
        HardwareTimingService timing = new HardwareTimingService();
        GameStateManager gameState = mock(GameStateManager.class);
        when(gameState.applyPauseToggle(true)).thenReturn(true);
        LevelFrameContext context = context(timing, boundaries::add, gameState);

        LevelFrameResult result = LevelFrameTestStep.executeWithPause(
                context,
                mock(LevelManager.class),
                mock(Camera.class),
                () -> {
                },
                true,
                LevelFrameStep.DIRECT_WRAPPER);

        assertEquals(LevelFrameResult.PAUSED, result);
        assertEquals(List.of(HardwareServiceBoundary.VINT_SERVICE), boundaries);
    }

    private static LevelFrameContext context(
            HardwareTimingService timing,
            com.openggf.game.timing.HardwareTimingBoundaryObserver observer) {
        return context(timing, null, observer, null);
    }

    private static LevelFrameContext context(
            HardwareTimingService timing,
            com.openggf.game.timing.HardwareTimingBoundaryObserver observer,
            GameStateManager gameState) {
        return context(timing, null, observer, gameState);
    }

    private static LevelFrameContext context(
            HardwareTimingService timing,
            LevelEventProvider levelEvents,
            com.openggf.game.timing.HardwareTimingBoundaryObserver observer) {
        return context(timing, levelEvents, observer, null);
    }

    private static LevelFrameContext context(
            HardwareTimingService timing,
            LevelEventProvider levelEvents,
            com.openggf.game.timing.HardwareTimingBoundaryObserver observer,
            GameStateManager gameState) {
        return new LevelFrameContext(
                mock(GameModule.class),
                null,
                levelEvents,
                NoOpBonusStageProvider.INSTANCE,
                null,
                gameState,
                null,
                null,
                timing,
                observer,
                null);
    }

    private static HardwareWorkSubmission submission(int workUnits) {
        return submission(new TestPreparation(workUnits));
    }

    private static HardwareWorkSubmission submission(TestPreparation preparation) {
        return submission(HardwareWorkKind.KOS_MODULE_QUEUE, preparation);
    }

    private static HardwareWorkSubmission submission(
            HardwareWorkKind kind,
            int workUnits) {
        return submission(kind, new TestPreparation(workUnits));
    }

    private static HardwareWorkSubmission submission(
            HardwareWorkKind kind,
            TestPreparation preparation) {
        return new HardwareWorkSubmission(
                kind,
                0x1234,
                0x20,
                0x4000,
                1,
                "KosM",
                1,
                false,
                preparation);
    }

    private record PreparationSnapshot(int remainingUnits)
            implements HardwareWorkPreparationSnapshot {
        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new TestPreparation(remainingUnits);
        }
    }

    private static final class TestPreparation implements HardwareWorkPreparation {
        private int remainingUnits;
        private int completedWorkUnits;

        private TestPreparation(int remainingUnits) {
            this.remainingUnits = remainingUnits;
        }

        @Override
        public boolean stepOneWorkUnit() {
            remainingUnits--;
            completedWorkUnits++;
            return true;
        }

        private int completedWorkUnits() {
            return completedWorkUnits;
        }

        @Override
        public boolean isPrepared() {
            return remainingUnits == 0;
        }

        @Override
        public byte[] preparedPayload() {
            return new byte[] {42};
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new PreparationSnapshot(remainingUnits);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            remainingUnits = ((PreparationSnapshot) snapshot).remainingUnits();
        }
    }
}
