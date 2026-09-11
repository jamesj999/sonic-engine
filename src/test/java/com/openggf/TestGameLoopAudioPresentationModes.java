package com.openggf;

import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.LiveCaptureAudioHandle;
import com.openggf.audio.NullAudioBackend;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.FrameSampleSink;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.game.GameMode;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.rewind.LiveRewindManager;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGameLoopAudioPresentationModes {
    private GameLoop loop;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        InputHandler input = mock(InputHandler.class);
        when(input.logical()).thenReturn(
                com.openggf.control.LogicalInputSnapshot.neutral());
        loop = new GameLoop(input);
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void normalOuterFrameIsForward() {
        assertEquals(PresentationMode.FORWARD,
                loop.presentationModeForOuterFrame(false, false));
    }

    @Test
    void modalAndFrameStepOuterFramesAreSilent() {
        assertEquals(PresentationMode.SILENT,
                loop.presentationModeForOuterFrame(true, false));
        assertEquals(PresentationMode.SILENT,
                loop.presentationModeForOuterFrame(false, true));
    }

    @Test
    void ordinaryPauseOuterFrameIsSilent() {
        loop.toggleUserPause();
        assertEquals(PresentationMode.SILENT,
                loop.presentationModeForOuterFrame(false, false));
    }

    @ParameterizedTest
    @EnumSource(GameMode.class)
    void everyProductionModeEntersTheSharedOuterFrameBoundary(GameMode mode)
            throws Exception {
        var presented = new ArrayList<PresentationMode>();
        loop.setAudioPresentationProbe(presented::add);
        replaceField(loop, "currentGameMode", mode);
        if (mode == GameMode.LEVEL || mode == GameMode.TITLE_CARD) {
            // Any of isNonRewindableTransitionPending()'s flags will do: this
            // only needs the gameplay tick frozen so the fixture never reaches
            // Camera.updatePosition() with no level loaded.
            replaceField(loop, "endingTransitionPending", true);
        } else if (mode == GameMode.BONUS_STAGE) {
            replaceField(loop, "bonusStageTransitionPending", true);
        }

        loop.step();
        Engine.presentOuterAudioFrame(loop, false, false);

        var parity = AudioManagerTestDiagnostics.shadowParitySnapshot(
                AudioManager.getInstance());
        assertEquals(1, parity.presentedFrames(), mode.name());
        assertEquals(1, parity.forwardFrames(), mode.name());
        assertEquals(java.util.List.of(PresentationMode.FORWARD), presented,
                mode.name());
    }

    @Test
    void modalPickerAndFrameStepDriveRealBoundaryAsSilence() throws Exception {
        Engine.presentOuterAudioFrame(loop, true, false);
        Engine.presentOuterAudioFrame(loop, false, true);

        var parity = AudioManagerTestDiagnostics.shadowParitySnapshot(
                AudioManager.getInstance());
        assertEquals(2, parity.presentedFrames());
        assertEquals(2, parity.silentFrames());
    }

    @Test
    void outerAudioBoundaryCreditsAggregateAudioMetric() {
        PerformanceProfiler profiler = PerformanceProfiler.getInstance();
        Map<String, Long> samples = new HashMap<>();
        AtomicLong frameNanos = new AtomicLong();
        AtomicLong probeWork = new AtomicLong();
        try {
            profiler.reset();
            profiler.setEnabled(true);
            profiler.setAllocationTrackingEnabled(false);
            profiler.setSampleSink(new FrameSampleSink() {
                @Override
                public void frameSample(String section, long elapsedNanos) {
                    samples.put(section, elapsedNanos);
                }

                @Override
                public void frameComplete(long elapsedNanos) {
                    frameNanos.set(elapsedNanos);
                }
            });
            profiler.beginFrame();
            profiler.beginSection("update");
            loop.setAudioPresentationProbe(ignored -> {
                for (int i = 0; i < 100_000; i++) {
                    probeWork.incrementAndGet();
                }
            });

            Engine.presentOuterAudioFrame(loop, false, false);

            profiler.endSection("update");
            profiler.endFrame();
            assertTrue(samples.getOrDefault("audio", 0L) > 0L);
            assertTrue(samples.getOrDefault("update", 0L) > 0L);
            assertTrue(samples.get("audio") + samples.get("update")
                    <= frameNanos.get());
            assertEquals(100_000L, probeWork.get());
        } finally {
            profiler.setSampleSink(null);
            profiler.setAllocationTrackingEnabled(true);
            profiler.reset();
        }
    }

    @Test
    void realNineStepFastForwardHasOneOuterPresentationPacket()
            throws Exception {
        InputHandler input = mock(InputHandler.class);
        loop.setInputHandler(input);
        loop.setGameMode(GameMode.LEGAL_DISCLAIMER);
        UserRecordingRuntimeControls controls =
                mock(UserRecordingRuntimeControls.class);
        when(controls.shouldPumpFastForward()).thenReturn(true);
        replaceField(loop, "userRecordingControls", controls);
        AudioManager audio = AudioManager.getInstance();
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.FPS, 1);
        audio.setBackend(new SixHertzBackend());
        LiveCaptureAudioHandle capture =
                AudioManagerTestDiagnostics.attachPresentationCapture(audio, 1);

        loop.step();
        Engine.presentOuterAudioFrame(loop, false, false);

        verify(input, times(9)).refreshLogicalSnapshot();
        short[] actual = new short[12];
        assertEquals(6, capture.drainPresentationFrame(actual));
        assertArrayEquals(new short[12], actual);
        assertEquals(6, capture.totalStereoFrames());
        assertEquals(6, capture.clockSnapshot().totalSamplesProduced());
        assertEquals(1, AudioManagerTestDiagnostics
                .shadowParitySnapshot(audio).presentedFrames());
        capture.close();
    }

    @Test
    void heldRewindDrivesReverseThroughRealBoundary() throws Exception {
        replaceField(loop, "currentGameMode", GameMode.LEGAL_DISCLAIMER);
        AudioManager.getInstance().beginReverseAudioPresentation();

        loop.step();
        Engine.presentOuterAudioFrame(loop, false, false);

        assertEquals(1, AudioManagerTestDiagnostics.shadowParitySnapshot(
                AudioManager.getInstance()).reverseFrames());
    }

    @Test
    void heldTraceRewindEarlyReturnStillPresentsReverseExactlyOnce()
            throws Exception {
        TraceSessionLauncher launcher = mock(TraceSessionLauncher.class);
        when(launcher.handleRealtimeRewindInput(
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(InputHandler.class)))
                .thenReturn(true);
        replaceStaticField(
                TraceSessionLauncher.class, "activeSession", launcher);
        replaceField(loop, "currentGameMode", GameMode.LEVEL);
        var modes = new ArrayList<PresentationMode>();
        loop.setAudioPresentationProbe(modes::add);
        AudioManager.getInstance().beginReverseAudioPresentation();
        try {
            loop.step();
            Engine.presentOuterAudioFrame(loop, false, false);
        } finally {
            replaceStaticField(
                    TraceSessionLauncher.class, "activeSession", null);
        }

        assertEquals(java.util.List.of(PresentationMode.REVERSE), modes);
        assertEquals(1, AudioManagerTestDiagnostics.shadowParitySnapshot(
                AudioManager.getInstance()).reverseFrames());
        verify(launcher).handleRealtimeRewindInput(
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(InputHandler.class));
    }

    @Test
    void heldLiveRewindEarlyReturnStillPresentsReverseExactlyOnce()
            throws Exception {
        LiveRewindManager live = mock(LiveRewindManager.class);
        when(live.handleRealtimeRewindInput(
                org.mockito.ArgumentMatchers.any(GameMode.class),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(InputHandler.class)))
                .thenReturn(true);
        replaceField(loop, "liveRewindManager", live);
        replaceField(loop, "currentGameMode", GameMode.LEVEL);
        var modes = new ArrayList<PresentationMode>();
        loop.setAudioPresentationProbe(modes::add);
        AudioManager.getInstance().beginReverseAudioPresentation();

        loop.step();
        Engine.presentOuterAudioFrame(loop, false, false);

        assertEquals(java.util.List.of(PresentationMode.REVERSE), modes);
        assertEquals(1, AudioManagerTestDiagnostics.shadowParitySnapshot(
                AudioManager.getInstance()).reverseFrames());
        verify(live).handleRealtimeRewindInput(
                org.mockito.ArgumentMatchers.eq(GameMode.LEVEL),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.any(InputHandler.class));
    }

    private static void replaceField(
            Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void replaceStaticField(
            Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static final class SixHertzBackend extends NullAudioBackend {
        @Override
        public int outputSampleRate() {
            return 6;
        }
    }

}
