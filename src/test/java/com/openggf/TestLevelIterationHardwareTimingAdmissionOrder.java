package com.openggf;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameMode;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.graphics.FadeManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.timer.TimerManager;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingScheduleTestFactory;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.openggf.game.timing.HardwareServiceBoundary.VINT_SERVICE;
import static com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLevelIterationHardwareTimingAdmissionOrder {
    private static final byte[] ABC_KOSM = {
            0x00, 0x03,
            0x17, 0x00,
            'A', 'B', 'C',
            0x00, 0x00, 0x00
    };

    @TempDir
    Path tempDir;

    @Test
    void pausedAdmissionActivatesCurrentRowBeforeVintService() throws Exception {
        Harness harness = harness(1);
        harness.context().getGameStateManager().setGamePaused(true);
        harness.port().beginRawFrame(0);
        var controller = new LevelIterationAdmissionController();
        var level = mock(com.openggf.level.LevelManager.class);

        LevelFrameResult admission = controller.admit(
                GameMode.LEVEL,
                () -> false,
                () -> LevelFrameResult.SETUP_ONLY,
                level,
                harness.context(),
                false,
                mock(UserRecordingRuntimeControls.class),
                () -> { },
                () -> harness.observer().beginRawFrame(1),
                harness.observer()::enterUnrepresentedGap);
        assertEquals(LevelFrameResult.PAUSED, admission);

        LevelFrameTestStep.serviceVBlankOnly(
                LevelFrameContext.from(harness.context()));

        assertTrue(harness.context().hardwareTiming().isReady(harness.handle()));
        assertEquals(1, harness.port().capture().rawFrameLatch());
    }

    @Test
    void seamlessAdmissionDeactivatesStaleRowBeforeTransitionVint() throws Exception {
        Harness harness = harness(0);
        harness.port().beginRawFrame(0);
        var controller = new LevelIterationAdmissionController();
        var level = mock(com.openggf.level.LevelManager.class);
        var request = SeamlessLevelTransitionRequest.builder(
                SeamlessLevelTransitionRequest.TransitionType.MUTATE_ONLY)
                .build();
        when(level.consumeSeamlessTransitionRequest()).thenReturn(request);

        controller.admit(
                GameMode.LEVEL,
                () -> false,
                () -> LevelFrameResult.SETUP_ONLY,
                level,
                harness.context(),
                false,
                mock(UserRecordingRuntimeControls.class),
                () -> { },
                () -> harness.observer().beginRawFrame(1),
                harness.observer()::enterUnrepresentedGap);
        LevelFrameTestStep.serviceVBlankOnly(
                LevelFrameContext.from(harness.context()));

        assertFalse(harness.context().hardwareTiming().isReady(harness.handle()));
        assertEquals(null, harness.port().capture().rawFrameLatch());
    }

    @Test
    void lockedTitleCardDeactivatesStaleRowBeforeItsHardwareScan() throws Exception {
        Harness harness = harness(0);
        harness.port().beginRawFrame(0);
        var controller = new LevelIterationAdmissionController();

        controller.admit(
                GameMode.TITLE_CARD,
                () -> {
                    LevelFrameTestStep.executeHardwareTimedObjectScan(
                            LevelFrameContext.from(harness.context()),
                            () -> { });
                    return false;
                },
                () -> LevelFrameResult.SETUP_ONLY,
                mock(com.openggf.level.LevelManager.class),
                harness.context(),
                false,
                mock(UserRecordingRuntimeControls.class),
                () -> { },
                () -> harness.observer().beginRawFrame(1),
                harness.observer()::enterUnrepresentedGap);

        assertFalse(harness.context().hardwareTiming().isReady(harness.handle()));
        assertEquals(null, harness.port().capture().rawFrameLatch());
    }

    private Harness harness(int edgeRawFrame) throws Exception {
        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic3kGameModule()),
                HardwareReadinessAdmissionPolicy.RECORDED,
                Map.of(
                        com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED,
                        com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        HardwareReadinessAdmissionPolicy.LIVE,
                        com.openggf.game.timing.HardwareWorkKind.NEMESIS_PLC_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED));
        context.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                new GameStateManager(),
                new FadeManager(),
                new GameRng(GameRng.Flavour.S3K),
                new DefaultSolidExecutionRegistry());
        Path romPath = tempDir.resolve("timing-admission-kosm.gen");
        Files.write(romPath, ABC_KOSM);
        HardwareWorkHandle handle;
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romPath.toString()));
            handle = S3kRuntimeArtCoordinator.from(context.runtimeArtCoordinator()).moduleQueue().queue(rom, 0, 0x200);
            for (int frame = 0;
                    frame < 16 && !hasPreparedPayload(context, handle);
                    frame++) {
                context.serviceHardwareTimingBoundary(
                        com.openggf.game.timing.HardwareServiceBoundary.PRE_MAIN_LOOP);
                context.serviceHardwareTimingBoundary(POST_OBJECTS);
            }
        }
        assertTrue(hasPreparedPayload(context, handle),
                "runtime-owned direct/module queues must prepare the scheduled parent");
        HardwareCompletionEdge edge = new HardwareCompletionEdge(
                edgeRawFrame,
                VINT_SERVICE,
                handle.kind(),
                handle.ordinal(),
                handle.submissionFingerprint());
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(
                context.recordedCompletionAuthority());
        port.install(HardwareTimingScheduleTestFactory.withAdmissionPolicies(
                List.of(edge),
                Map.of(
                        com.openggf.game.timing.HardwareWorkKind.KOS_MODULE_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED,
                        com.openggf.game.timing.HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        HardwareReadinessAdmissionPolicy.LIVE,
                        com.openggf.game.timing.HardwareWorkKind.NEMESIS_PLC_QUEUE,
                        HardwareReadinessAdmissionPolicy.RECORDED)));
        TraceHardwareTimingBoundaryObserver observer =
                new TraceHardwareTimingBoundaryObserver(port);
        context.setHardwareTimingBoundaryObserver(observer);
        return new Harness(context, handle, port, observer);
    }

    private static boolean hasPreparedPayload(
            GameplayModeContext context, HardwareWorkHandle handle) {
        return context.hardwareTiming().capture().jobs().stream()
                .filter(job -> job.handle().equals(handle))
                .findFirst()
                .orElseThrow()
                .preparedPayload() != null;
    }

    private record Harness(
            GameplayModeContext context,
            HardwareWorkHandle handle,
            HardwareTimingReplayPort port,
            TraceHardwareTimingBoundaryObserver observer) {
    }
}
