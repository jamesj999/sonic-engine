package com.openggf.game.rewind;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.GameSound;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.AudioPresentationPolicy;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameMode;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3B: held-rewind audio restore deferral. While reverse audio
 * presentation is active, {@link RewindController#stepBackward()} must skip
 * the per-frame logical audio restore (presentation reads PcmHistoryRing
 * instead), and every path that ends the held rewind must land exactly one
 * logical restore whose result is identical to what per-frame restores would
 * have produced at the committed frame.
 */
class TestHeldRewindAudioRestoreDeferral {

    private AudioManager audio;
    private CountingRestoreBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new CountingRestoreBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.endReverseAudioPresentation();
        audio.resetState();
    }

    @Test
    void heldRewindDefersIntermediateRestoresAndCommitsExactlyOnceOnRelease() {
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);

        audio.beginReverseAudioPresentation();
        for (int i = 0; i < 3; i++) {
            assertTrue(controller.stepBackward());
        }
        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "held backward steps must not rebuild logical audio state per frame");

        controller.commitDeferredAudioRestore();
        audio.afterRewindRestore(controller.currentFrame(),
                AudioPresentationPolicy.STOP_TRANSIENT_SFX_RESYNC_MUSIC);

        assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "release must land exactly one logical restore at the committed frame");
        assertEquals(5, controller.currentFrame());

        controller.commitDeferredAudioRestore();
        assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio), "commit must be one-shot");
    }

    @Test
    void stepBackwardWithoutReversePresentationKeepsPerFrameRestores() {
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);

        assertTrue(controller.stepBackward());
        assertTrue(controller.stepBackward());

        assertEquals(2, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "non-held backward stepping (trace tooling) must keep eager restores");
    }

    @Test
    void releasedHeldRewindStateMatchesPerFrameRestoresAndFreshReplay() {
        // Per-frame baseline: reverse presentation inactive -> eager restores.
        RewindController baseline = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            baseline.step();
        }
        for (int i = 0; i < 3; i++) {
            assertTrue(baseline.stepBackward());
        }
        assertEquals(5, baseline.currentFrame());
        AudioLogicalSnapshot perFrameState = audio.captureLogicalSnapshot();

        // Deferred run: identical script, held reverse presentation, single
        // commit on release.
        audio.resetState();
        audio.setBackend(backend);
        RewindController deferred = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            deferred.step();
        }
        audio.beginReverseAudioPresentation();
        for (int i = 0; i < 3; i++) {
            assertTrue(deferred.stepBackward());
        }
        deferred.commitDeferredAudioRestore();
        audio.endReverseAudioPresentation();
        AudioLogicalSnapshot deferredState = audio.captureLogicalSnapshot();

        assertEquals(perFrameState, deferredState,
                "released held rewind must land the exact per-frame-restore logical state");

        // Fresh replay to the committed frame.
        audio.resetState();
        audio.setBackend(backend);
        RewindController fresh = newController(scriptedStepper(), 4);
        for (int i = 0; i < 5; i++) {
            fresh.step();
        }
        // Live forward play only *enqueues* presentation commands; the queue is
        // drained when a frame is presented. The rewind paths above drain it
        // explicitly while staging/restoring, so the fresh run must present one
        // (non-rendering) frame before the comparison, or it would be measured
        // with frames 1..5 still queued. Without this the registry's
        // ring-alternation mirror reads its initial `true` here instead of the
        // `false` the ring sfx at frame 5 produced.
        audio.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot freshState = audio.captureLogicalSnapshot();

        assertEquals(freshState.ringLeft(), deferredState.ringLeft());
        assertEquals(freshState.commandTimelineFrame(), deferredState.commandTimelineFrame());
        assertEquals(freshState.commandTimelineNextOrder(), deferredState.commandTimelineNextOrder());
        assertEquals(freshState.commandEntryCount(), deferredState.commandEntryCount());
        // The presentation snapshot is the whole restored audio state now, so
        // compare the whole record rather than a hand-picked subset.
        assertEquals(freshState.presentation(), deferredState.presentation(),
                "released held rewind must land the exact fresh-replay "
                        + "presentation state");
        // Called out explicitly because it is the one component the manager
        // also tracks independently: AudioPresentationCommandResolver.submitSfx
        // enqueues ResetRingAlternation for every RING_RESOLVED sfx, so the
        // registry's mirror must equal the manager's authoritative alternation
        // rather than merely matching between two rewind paths.
        assertEquals(freshState.ringLeft(),
                freshState.presentation().ringLeft(),
                "the registry ring mirror must track the manager's ring "
                        + "alternation");
        assertEquals(deferredState.ringLeft(),
                deferredState.presentation().ringLeft(),
                "a held-rewind release must publish a registry ring mirror "
                        + "that matches the manager's ring alternation");
    }

    @Test
    void levelLoadDuringHeldRewindKeepsFreshAudioAndDropsStaleDeferredRestore() {
        // Reproduces the fade-completion-mid-hold interleaving:
        // LevelManager.loadLevel -> initAudio (fresh new-level state) ->
        // resetToFrameZero. The re-root must NOT commit the stale pre-rewind
        // logical state over the freshly initialized new-level audio.
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        audio.beginReverseAudioPresentation();
        for (int i = 0; i < 3; i++) {
            assertTrue(controller.stepBackward());
        }
        // Deferred committed value at frame 5 would be ringLeft=false; the
        // fresh-init marker (standing in for initAudio's reinit) sets the
        // distinguishable value true.
        audio.resetRingSound();
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);

        controller.resetToFrameZero();

        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "level-boundary re-root must drop, not commit, the stale deferred restore");
        assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                "freshly initialized new-level audio state must survive the re-root");

        controller.commitDeferredAudioRestore();
        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "dropped deferral must not resurrect on a later commit");
    }

    @Test
    void resetBufferAtCurrentFrameAlsoDropsStaleDeferredRestore() {
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        audio.beginReverseAudioPresentation();
        for (int i = 0; i < 3; i++) {
            assertTrue(controller.stepBackward());
        }
        audio.resetRingSound();
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);

        controller.resetBufferAtCurrentFrame();

        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "seamless-transition re-root must drop, not commit, the stale deferred restore");
        assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                "post-transition audio state must survive the re-root");
    }

    @Test
    void recordExternalStepCommitsDeferredRestoreBeforeResumingForwardPlay() {
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);
        audio.beginReverseAudioPresentation();
        for (int i = 0; i < 3; i++) {
            assertTrue(controller.stepBackward());
        }
        audio.endReverseAudioPresentation();

        assertTrue(controller.recordExternalStep());

        assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "forward resume must commit the deferred restore before recording live frames");
        assertEquals(6, controller.currentFrame());
    }

    @Test
    void seekToSupersedesDeferredTargetWithoutPublishingBeforeRelease() {
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < 8; i++) {
            controller.step();
        }
        AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);
        audio.beginReverseAudioPresentation();
        assertTrue(controller.stepBackward());
        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio));

        controller.seekTo(3);

        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "seek target preparation must remain detached while reverse "
                        + "presentation is held");
        controller.commitDeferredAudioRestore();
        assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "seek must clear the controller deferral without publishing");
        audio.endReverseAudioPresentation();
        assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                "reverse release publishes the prepared seek target once");
    }

    @Test
    void liveRewindManagerReleasePathCommitsDeferredRestoreBeforePresentationCleanup() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        try {
            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = new LiveRewindManager(config);
            RewindController controller = newController(scriptedStepper(), 4);
            for (int i = 0; i < 8; i++) {
                controller.step();
            }
            installTestController(manager, controller);
            InputHandler input = new InputHandler();
            int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);

            AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);
            input.handleKeyEvent(rewindKey, GLFW_PRESS);
            assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
            assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
            assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                    "held live rewind must defer logical restores while reverse presentation runs");

            input.handleKeyEvent(rewindKey, GLFW_RELEASE);
            assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

            assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                    "live rewind release must land exactly one committed logical restore");
            assertEquals(java.util.List.of(), backend.calls,
                    "the restore is producer-owned; the source backend sees nothing");
            assertFalse(audio.isReverseAudioPresentationActive(),
                    "producer cleanup must follow the committed restore");
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            SessionManager.clear();
        }
    }

    @Test
    void liveRewindManagerLevelLoadBoundaryDropsDeferredRestoreBeforePresentationCleanup()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        try {
            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = liveManagerWithControllerAtFrame(config, 8);
            InputHandler input = new InputHandler();
            input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);
            assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
            assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio));
            audio.resetRingSound();
            AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);
            backend.calls.clear();

            manager.markBoundary(RewindBoundary.LEVEL_LOAD);

            assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                    "level-load boundary must publish the fresh boundary state exactly once");
            assertEquals(java.util.List.of(), backend.calls,
                    "the restore is producer-owned; the source backend sees nothing");
            assertFalse(audio.isReverseAudioPresentationActive(),
                    "boundary cleanup must end reverse audio presentation");
            assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                    "fresh boundary audio marker must survive the dropped restore");
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            SessionManager.clear();
        }
    }

    @Test
    void liveRewindManagerLevelLoadBoundaryClearsPcmRewindHistory() throws Exception {
        // Reproduces the reported bug: holding rewind at the very start of a
        // level correctly freezes engine/game state at frame zero, but the
        // raw PCM rewind-history ring is a fixed-duration buffer independent
        // of that logical reset. Unless it is explicitly cleared at the
        // LEVEL_LOAD boundary, a held rewind can keep draining it backward
        // into audio recorded before this level ever loaded.
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        try {
            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = liveManagerWithControllerAtFrame(config, 8);
            long epochBefore = AudioManagerTestDiagnostics
                    .producerFingerprint(audio).history().epoch();

            manager.markBoundary(RewindBoundary.LEVEL_LOAD);

            assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .history().epoch() > epochBefore,
                    "level-load boundary must clear the raw PCM rewind-history ring so held "
                            + "rewind audio cannot play samples recorded before this level started");
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            SessionManager.clear();
        }
    }

    @Test
    void liveRewindManagerArmsPcmHistoryWhileUsableAndDisarmsWhenNot() throws Exception {
        // The whole point of arming/disarming is to avoid recording PCM
        // history when nothing can rewind it back: it must be armed exactly
        // while GameMode.LEVEL + live rewind enabled make held rewind usable,
        // and disarmed the instant either condition stops holding.
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        try {
            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = new LiveRewindManager(config);
            InputHandler input = new InputHandler();

            manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input);
            assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .historyArmed(),
                    "a usable held-rewind session must arm PCM history recording");

            manager.handleRealtimeRewindInput(GameMode.TITLE_SCREEN, false, input);
            assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .historyArmed(),
                    "leaving GameMode.LEVEL must disarm PCM history recording");

            manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input);
            assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .historyArmed(),
                    "re-entering GameMode.LEVEL must re-arm PCM history recording");

            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input);
            assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                            .historyArmed(),
                    "disabling live rewind must disarm PCM history recording even in GameMode.LEVEL");
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            SessionManager.clear();
        }
    }

    @Test
    void liveRewindManagerSeamlessBoundaryDropsDeferredRestoreBeforePresentationCleanup()
            throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        try {
            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = liveManagerWithControllerAtFrame(config, 8);
            InputHandler input = new InputHandler();
            input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);
            assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
            assertEquals(0, AudioManagerTestDiagnostics.logicalRestorePublications(audio));
            audio.resetRingSound();
            AudioManagerTestDiagnostics.resetLogicalRestorePublications(audio);
            backend.calls.clear();

            manager.markBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);

            assertEquals(1, AudioManagerTestDiagnostics.logicalRestorePublications(audio),
                    "seamless boundary must publish the fresh boundary state exactly once");
            assertEquals(java.util.List.of(), backend.calls,
                    "the restore is producer-owned; the source backend sees nothing");
            assertFalse(audio.isReverseAudioPresentationActive(),
                    "boundary cleanup must end reverse audio presentation");
            assertTrue(audio.captureLogicalSnapshot().ringLeft(),
                    "post-transition audio marker must survive the dropped restore");
        } finally {
            config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
            SessionManager.clear();
        }
    }

    private RewindController newController(EngineStepper stepper, int keyframeInterval) {
        return new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(40),
                stepper,
                keyframeInterval,
                audio);
    }

    /**
     * Ring alternation script: frames 1/2/5/6 collect a ring, frame 3 resets
     * the alternation. ringLeft is therefore true at frame 4 and false at
     * frame 5 — distinct values around the committed rewind target.
     */
    private EngineStepper scriptedStepper() {
        return in -> {
            switch (in.frameIndex()) {
                case 1, 2, 5, 6 -> audio.playSfx(GameSound.RING);
                case 3 -> audio.resetRingSound();
                default -> { }
            }
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };
    }

    private static void installTestController(LiveRewindManager manager, RewindController controller)
            throws Exception {
        setField(manager, "installedGameplayMode", TestEnvironment.activeGameplayMode());
        setInstalledStepperKind(manager, "LEVEL_FRAME");
        setField(manager, "inputSource", new LiveRewindInputSource());
        setField(manager, "rewindController", controller);
        setField(manager, "speedController",
                RewindSpeedController.fromConfig(SonicConfigurationService.getInstance()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setInstalledStepperKind(LiveRewindManager manager, String kindName) throws Exception {
        Class<?> kindClass = Class.forName("com.openggf.game.rewind.LiveRewindManager$StepperKind");
        Object kind = Enum.valueOf((Class<? extends Enum>) kindClass.asSubclass(Enum.class), kindName);
        setField(manager, "installedStepperKind", kind);
    }

    private LiveRewindManager liveManagerWithControllerAtFrame(
            SonicConfigurationService config,
            int frame) throws Exception {
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = newController(scriptedStepper(), 4);
        for (int i = 0; i < frame; i++) {
            controller.step();
        }
        installTestController(manager, controller);
        return manager;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class CountingRestoreBackend extends NullAudioBackend {
        final java.util.List<String> calls = new java.util.ArrayList<>();

        @Override
        public void stopAllSfx() {
            calls.add("stopAllSfx");
        }

        @Override
        public void restoreMusic() {
            calls.add("restoreMusic");
        }

    }

    private static final class FakeInputSource implements InputSource {
        private final int frames;

        FakeInputSource(int frames) {
            this.frames = frames;
        }

        @Override
        public int frameCount() {
            return frames;
        }

        @Override
        public Bk2FrameInput read(int frame) {
            return new Bk2FrameInput(frame, 0, 0, false, "fake");
        }
    }
}
