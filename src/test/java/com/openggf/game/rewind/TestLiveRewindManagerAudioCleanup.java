package com.openggf.game.rewind;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.presentation.AudioPresentationCommandQueue;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.PresentationMode;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.rewind.AudioSourceDescriptor;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameMode;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLiveRewindManagerAudioCleanup {
    private SonicConfigurationService config;
    private AudioManager audio;
    private AudioTestFixtures.RecordingAudioBackend backend;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new AudioTestFixtures.RecordingAudioBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, false);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, false);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MIN_STEPS, 0.25);
        audio.resetState();
        SessionManager.clear();
    }

    @Test
    void defaultLiveRewindStepsOneFramePerHeldVisualFrame() throws Exception {
        TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        InputHandler input = new InputHandler();
        assertEquals(5, controller.currentFrame());

        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);

        assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

        assertEquals(4, controller.currentFrame());
    }

    @Test
    void heldLiveRewindFreezesFadePresentationUntilReleaseCleanup() throws Exception {
        FadeManager fadeManager = TestEnvironment.activeGameplayMode().getFadeManager();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        InputHandler input = new InputHandler();
        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);

        assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

        assertTrue(fadeManager.isReversePresentationActive());

        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_RELEASE);
        assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

        assertFalse(fadeManager.isReversePresentationActive());
    }

    @Test
    void failedReleaseRetainsLiveHostStateAndRetriesBeforeGameplayResumes()
            throws Exception {
        audio.setBackend(new NullAudioBackend());
        FadeManager fadeManager =
                TestEnvironment.activeGameplayMode().getFadeManager();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        InputHandler input = new InputHandler();
        int rewindKey =
                config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
        input.handleKeyEvent(rewindKey, GLFW_PRESS);
        assertTrue(manager.handleRealtimeRewindInput(
                GameMode.LEVEL, false, input));
        input.handleKeyEvent(rewindKey, GLFW_RELEASE);
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        assertTrue(manager.handleRealtimeRewindInput(
                        GameMode.LEVEL, false, input),
                "failed release must consume the frame instead of resuming gameplay");
        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue(fadeManager.isReversePresentationActive());
        assertTrue((boolean) getField(manager, "rewinding"));
        assertRetryReusesTheExactPreparedRelease(
                () -> assertTrue(manager.handleRealtimeRewindInput(
                        GameMode.LEVEL, false, input)));

        assertFalse(manager.handleRealtimeRewindInput(
                        GameMode.LEVEL, false, input),
                "a later owner boundary should finish the retained release");
        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(fadeManager.isReversePresentationActive());
        assertFalse((boolean) getField(manager, "rewinding"));
    }

    @Test
    void unsupportedModeReleaseFailureKeepsGameplayFrozenAndRetainsClearOwner()
            throws Exception {
        audio.setBackend(new NullAudioBackend());
        FadeManager fadeManager =
                TestEnvironment.activeGameplayMode().getFadeManager();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        LiveRewindInputSource inputSource =
                (LiveRewindInputSource) getField(manager, "inputSource");
        InputHandler heldInput = new InputHandler();
        heldInput.handleKeyEvent(
                config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);
        assertTrue(manager.handleRealtimeRewindInput(
                GameMode.LEVEL, false, heldInput));
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        assertTrue(manager.handleRealtimeRewindInput(
                        GameMode.TITLE_SCREEN, false, new InputHandler()),
                "failed unsupported-mode cleanup must not resume gameplay");
        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue(fadeManager.isReversePresentationActive());
        assertTrue((boolean) getField(manager, "rewinding"));
        assertRetryReusesTheExactPreparedRelease(
                () -> assertTrue(manager.handleRealtimeRewindInput(
                        GameMode.TITLE_SCREEN, false, new InputHandler())));
        assertEquals(controller, getField(manager, "rewindController"));
        assertEquals(inputSource, getField(manager, "inputSource"));

        assertFalse(manager.handleRealtimeRewindInput(
                        GameMode.TITLE_SCREEN, false, new InputHandler()),
                "the retained clear owner must finish on a later boundary");
        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(fadeManager.isReversePresentationActive());
        assertFalse((boolean) getField(manager, "rewinding"));
        assertEquals(null, getField(manager, "rewindController"));
    }

    @Test
    void failedLevelLoadReleaseDoesNotResetHistoryBeforeRetry()
            throws Exception {
        assertBoundaryReleaseFailureIsGated(
                RewindBoundary.LEVEL_LOAD, 0, 0);
    }

    @Test
    void failedSeamlessReleaseDoesNotRerootHistoryBeforeRetry()
            throws Exception {
        assertBoundaryReleaseFailureIsGated(
                RewindBoundary.SEAMLESS_LEVEL_TRANSITION, 4, 4);
    }

    @Test
    void levelLoadBoundaryRetriesFreshSourceBearingAudioBeforeFrameZeroReroot()
            throws Exception {
        assertFreshBoundaryAudioRelease(
                RewindBoundary.LEVEL_LOAD, 0);
    }

    @Test
    void seamlessBoundaryRetriesFreshSourceBearingAudioBeforeCurrentFrameReroot()
            throws Exception {
        assertFreshBoundaryAudioRelease(
                RewindBoundary.SEAMLESS_LEVEL_TRANSITION, 4);
    }

    @Test
    void tapeCoastDelaysTransientAudioCleanupUntilCoastEnds() throws Exception {
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ENABLED, true);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MIN_STEPS, 2.0);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_ACCELERATION, 1.0);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_DECELERATION, 0.5);
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_TAPE_COAST_MAX_STEPS, 3.0);
        TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(8);
        installTestController(manager, controller);
        InputHandler input = new InputHandler();
        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);
        assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

        input.handleKeyEvent(config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_RELEASE);

        assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));
        assertTrue(audio.isReverseAudioPresentationActive(),
                "release should keep reverse presentation active while coast still has rewind steps");

        int coastSteps = 0;
        while (manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input)) {
            assertTrue(coastSteps++ < 4096,
                    "the rewind coast must drain within a bounded number of steps");
        }
        assertFalse(audio.isReverseAudioPresentationActive(),
                "transient cleanup should run after the coast has fully ended");
    }

    @Test
    void releasingHeldRewindWhileAnOverrideIsStillActiveDoesNotEndItEarly() throws Exception {
        // Real backend (not the call-recording fake): the bug only surfaces
        // through the actual music-override-stack pop/restore state machine,
        // which NullAudioBackend/RecordingAudioBackend don't implement. Always
        // restore the class's shared fake backend afterward so this AudioManager
        // singleton doesn't leak a heavier real backend into other test classes
        // sharing the same JVM/fork.
        HeadlessSmpsAudioBackend realBackend = new HeadlessSmpsAudioBackend(config, null);
        realBackend.init();
        audio.setBackend(realBackend);
        try {
            int zoneMusicId = 0x82;
            int extraLifeMusicId = 0x2A;
            AudioTestFixtures.StubSmpsLoader loader =
                    new AudioTestFixtures.StubSmpsLoader();
            loader.musicResults.put(zoneMusicId, persistentSource(zoneMusicId));
            loader.musicResults.put(
                    extraLifeMusicId, persistentSource(extraLifeMusicId));
            SmpsSequencerConfig sourceConfig = smpsConfig();
            audio.setAudioProfile(new AudioTestFixtures.StubAudioProfile(loader) {
                @Override public SmpsSequencerConfig getSequencerConfig() {
                    return sourceConfig;
                }
                @Override public int getExtraLifeMusicId() {
                    return extraLifeMusicId;
                }
            });
            audio.setRom(new Rom());
            audio.playMusic(zoneMusicId);
            // The 1-up jingle is the only music that saves the song underneath
            // it, so it is the only way to hold a live save slot across a
            // rewind release.
            audio.playMusic(extraLifeMusicId);
            audio.presentFrame(PresentationMode.SILENT);

            TestEnvironment.activeGameplayMode();
            LiveRewindManager manager = new LiveRewindManager(config);
            RewindController controller = new TestControllerBuilder().atFrame(8);
            installTestController(manager, controller);

            InputHandler input = new InputHandler();
            int rewindKey = config.getInt(SonicConfiguration.LIVE_REWIND_KEY);
            input.handleKeyEvent(rewindKey, GLFW_PRESS);
            assertTrue(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

            input.handleKeyEvent(rewindKey, GLFW_RELEASE);
            assertFalse(manager.handleRealtimeRewindInput(GameMode.LEVEL, false, input));

            var after = audio.captureLogicalSnapshot().presentation();
            assertEquals(extraLifeMusicId,
                    after.activeMusic().sourceDescriptor().id(),
                    "releasing a held rewind mid-jingle must not end the jingle early");
            assertFalse(after.overrideStack().isEmpty(),
                    "the saved zone music must remain saved while the jingle is still playing");
            // A restore request only queues a deferred pop; the bug is issuing
            // it at all here, which this observes directly rather than
            // depending on when the next drain happens to run.
            assertFalse(after.pendingRestore(),
                    "release cleanup must not queue a music-stack pop while the override is still legitimately active");
        } finally {
            audio.setAudioProfile(null);
            audio.setBackend(backend);
        }
    }

    private static SmpsSequencerConfig smpsConfig() {
        return new SmpsSequencerConfig.Builder().build();
    }

    @Test
    void leavingLevelWhileRewindingStopsAllPresentationAudio() throws Exception {
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(4),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                audio);
        setField(manager, "rewindController", controller);
        setField(manager, "rewinding", true);
        audio.beginReverseAudioPresentation();

        manager.handleRealtimeRewindInput(GameMode.TITLE_SCREEN, false, new InputHandler());

        assertFalse(audio.isReverseAudioPresentationActive());
        assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .voiceIdentities().isEmpty());
        assertEquals(java.util.List.of(), backend.calls,
                "cleanup must not dispatch to the retired backend presenter");
    }

    @Test
    void pendingNonRewindableTransitionStopsPresentationAudioLikeModeExit() throws Exception {
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(4),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                audio);
        setField(manager, "rewindController", controller);
        setField(manager, "rewinding", true);
        audio.beginReverseAudioPresentation();

        // currentGameMode is still LEVEL (e.g. a special-stage entry fade is in
        // flight) but the composite freeze predicate the GameLoop caller computes
        // has flipped true -- the widened gate must reject exactly like the
        // mode != LEVEL case above, reusing the same clear() teardown.
        boolean engaged = manager.handleRealtimeRewindInput(GameMode.LEVEL, true, new InputHandler());

        assertFalse(engaged, "engagement must be rejected while a transition is pending");
        assertFalse(audio.isReverseAudioPresentationActive());
        assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .voiceIdentities().isEmpty());
        assertEquals(java.util.List.of(), backend.calls,
                "cleanup must not dispatch to the retired backend presenter");
        assertFalse((boolean) getField(manager, "rewinding"));
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    /**
     * Producer-side replacement for the prepare/commit/rollback/discard counts
     * the deleted {@code FailingReleaseBackend} carried. A failed release must
     * retain the exact prepared restore for the retry: if a retry discarded and
     * re-prepared it, the producer would mint fresh identity tokens (and leak
     * the voices the discarded preparation recreated). Runs one further failing
     * attempt through {@code retry} and compares the whole transaction
     * fingerprint across it.
     */
    private void assertRetryReusesTheExactPreparedRelease(Runnable retry) {
        AudioPresentationProducer.TransactionFingerprint afterFirstFailure =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertTrue(afterFirstFailure
                        .preparedSelectedRestoreIdentity().token() != 0,
                "the failed release must hold a prepared restore to retain");

        AudioManagerTestDiagnostics.failNextReverseRelease(audio);
        retry.run();

        assertEquals(afterFirstFailure,
                AudioManagerTestDiagnostics.producerFingerprint(audio),
                "a retry must reuse the exact prepared token, selection, "
                        + "resolver, history and cursor rather than "
                        + "re-preparing them");
        assertTrue(audio.isReverseAudioPresentationActive(),
                "the second failed attempt must still retain the release");
    }

    private void assertBoundaryReleaseFailureIsGated(
            RewindBoundary boundary,
            int expectedRetriedFrame,
            int expectedRetriedEarliestFrame) throws Exception {
        audio.setBackend(new NullAudioBackend());
        FadeManager fadeManager =
                TestEnvironment.activeGameplayMode().getFadeManager();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller = new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        LiveRewindInputSource inputSource =
                (LiveRewindInputSource) getField(manager, "inputSource");
        InputHandler heldInput = new InputHandler();
        heldInput.handleKeyEvent(
                config.getInt(SonicConfiguration.LIVE_REWIND_KEY), GLFW_PRESS);
        assertTrue(manager.handleRealtimeRewindInput(
                GameMode.LEVEL, false, heldInput));
        int frameBefore = controller.currentFrame();
        int earliestBefore = controller.earliestAvailableFrame();
        int inputCountBefore = inputSource.frameCount();
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        manager.markBoundary(boundary);

        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue(fadeManager.isReversePresentationActive());
        assertTrue((boolean) getField(manager, "rewinding"));
        assertRetryReusesTheExactPreparedRelease(
                () -> manager.markBoundary(boundary));
        assertEquals(frameBefore, controller.currentFrame(),
                "failed release must not move the controller boundary");
        assertEquals(earliestBefore, controller.earliestAvailableFrame(),
                "failed release must not reroot controller history");
        assertEquals(inputCountBefore, inputSource.frameCount(),
                "failed release must not reset or trim input history");

        manager.markBoundary(boundary);

        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(fadeManager.isReversePresentationActive());
        assertFalse((boolean) getField(manager, "rewinding"));
        assertEquals(expectedRetriedFrame, controller.currentFrame());
        assertEquals(expectedRetriedEarliestFrame,
                controller.earliestAvailableFrame());
    }

    private void assertFreshBoundaryAudioRelease(
            RewindBoundary boundary, int expectedRootFrame) throws Exception {
        final int oldMusic = 0x80;
        final int oldSfx = 0xA0;
        final int freshMusic = 0x81;
        final int freshSfx = 0xA1;
        AudioTestFixtures.StubSmpsLoader loader =
                new AudioTestFixtures.StubSmpsLoader();
        for (int musicId : new int[] {
                oldMusic, freshMusic}) {
            loader.musicResults.put(
                    musicId, persistentSource(musicId));
        }
        for (int sfxId : new int[] {
                oldSfx, freshSfx}) {
            loader.sfxResults.put(
                    sfxId, persistentSource(sfxId));
        }
        SmpsSequencerConfig sourceConfig = smpsConfig();
        GameAudioProfile profile =
                new AudioTestFixtures.StubAudioProfile(loader) {
                    @Override
                    public SmpsSequencerConfig getSequencerConfig() {
                        return sourceConfig;
                    }
                };
        audio.setAudioProfile(profile);
        audio.setRom(new Rom());
        audio.setBackend(new HeadlessSmpsAudioBackend(config, null));
        applyAndPresentSourcePair(oldMusic, oldSfx);

        TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(config);
        RewindController controller =
                new TestControllerBuilder().atFrame(5);
        installTestController(manager, controller);
        LiveRewindInputSource inputSource =
                (LiveRewindInputSource) getField(manager, "inputSource");
        InputHandler heldInput = new InputHandler();
        heldInput.handleKeyEvent(
                config.getInt(SonicConfiguration.LIVE_REWIND_KEY),
                GLFW_PRESS);
        assertTrue(manager.handleRealtimeRewindInput(
                GameMode.LEVEL, false, heldInput));

        controller.commitDeferredAudioRestore();
        audio.playMusic(freshMusic);
        assertTrue(audio.playSfx(freshSfx));
        submitRawPcm(audio, new byte[] {0, 31, 0, 31}, 48_000);
        AudioLogicalSnapshot beforeBoundary =
                audio.captureLogicalSnapshot();
        assertPresentationSourcePair(
                beforeBoundary, oldMusic, oldSfx);
        assertNull(beforeBoundary.presentation().rawPcmVoiceId(),
                "queued raw PCM must not publish before the owner boundary");
        assertEquals(3, pendingPresentationCommands(),
                "fresh music, SFX, and raw PCM must remain queued");
        AudioPresentationProducer.TransactionFingerprint
                producerBeforeBoundary =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        int frameBefore = controller.currentFrame();
        int earliestBefore = controller.earliestAvailableFrame();
        int inputCountBefore = inputSource.frameCount();
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        manager.markBoundary(boundary);

        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue((boolean) getField(manager, "rewinding"));
        assertEquals(frameBefore, controller.currentFrame(),
                "failed fresh release must not move the controller boundary");
        assertEquals(earliestBefore, controller.earliestAvailableFrame(),
                "failed fresh release must not reroot controller history");
        assertEquals(inputCountBefore, inputSource.frameCount(),
                "failed fresh release must not trim input history");
        assertSourcePair(
                audio.captureLogicalSnapshot(), freshMusic, freshSfx);
        assertRawPcmSource(audio.captureLogicalSnapshot());
        AudioLogicalSnapshot selectedFresh =
                (AudioLogicalSnapshot) getField(
                        audio, "deferredReverseLogicalSnapshot");
        assertSourcePair(selectedFresh, freshMusic, freshSfx);
        assertRawPcmSource(selectedFresh);
        AudioPresentationProducer.TransactionFingerprint
                producerAfterFailure =
                AudioManagerTestDiagnostics.producerFingerprint(audio);
        assertEquals(producerBeforeBoundary.clock(),
                producerAfterFailure.clock(),
                "boundary drain must not advance the producer clock");
        assertEquals(producerBeforeBoundary.history(),
                producerAfterFailure.history(),
                "boundary drain must neither emit nor append an audible packet");
        assertEquals(0, pendingPresentationCommands(),
                "successfully captured commands must not resurrect next frame");
        assertTrue(producerAfterFailure
                        .preparedSelectedRestoreIdentity().token() != 0,
                "failed publication must retain the fresh producer token");

        assertTrue(audio.preparePostBoundaryReverseRelease(),
                "a preparation retry must reuse the retained fresh token");
        assertEquals(producerAfterFailure.preparedSelectedRestoreIdentity(),
                AudioManagerTestDiagnostics.producerFingerprint(audio)
                        .preparedSelectedRestoreIdentity());

        assertFalse(manager.retryPendingRelease(),
                "retry should finish the retained boundary release");

        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse((boolean) getField(manager, "rewinding"));
        assertEquals(expectedRootFrame, controller.currentFrame());
        assertEquals(expectedRootFrame,
                controller.earliestAvailableFrame());
        assertSourcePair(
                audio.captureLogicalSnapshot(), freshMusic, freshSfx);
        assertRawPcmSource(audio.captureLogicalSnapshot());
        assertEquals(0, pendingPresentationCommands());

        AudioLogicalSnapshot beforeNextFrame =
                audio.captureLogicalSnapshot();
        audio.presentFrame(PresentationMode.SILENT);
        AudioLogicalSnapshot afterNextFrame =
                audio.captureLogicalSnapshot();
        assertSourcePair(afterNextFrame, freshMusic, freshSfx);
        assertRawPcmSource(afterNextFrame);
        assertEquals(beforeNextFrame.presentation().nextVoiceId(),
                afterNextFrame.presentation().nextVoiceId(),
                "an empty next-frame drain must not allocate duplicate voices");
        assertEquals(beforeNextFrame.presentation().voices().size(),
                afterNextFrame.presentation().voices().size(),
                "an empty next-frame drain must not resurrect fresh commands");
        assertEquals(beforeNextFrame.presentation().rawPcmVoiceId(),
                afterNextFrame.presentation().rawPcmVoiceId());
        assertEquals(0, pendingPresentationCommands());

        assertTrue(controller.recordExternalStep());
        audio.beginReverseAudioPresentation();
        assertTrue(controller.stepBackward());
        controller.commitDeferredAudioRestore();
        AudioLogicalSnapshot selectedRoot =
                (AudioLogicalSnapshot) getField(
                        audio, "deferredReverseLogicalSnapshot");
        assertSourcePair(selectedRoot, freshMusic, freshSfx);
        assertRawPcmSource(selectedRoot);
        audio.endReverseAudioPresentation();
    }

    private void applyAndPresentSourcePair(int musicId, int sfxId) {
        audio.playMusic(musicId);
        assertTrue(audio.playSfx(sfxId));
        audio.presentFrame(PresentationMode.SILENT);
    }

    private static void assertSourcePair(
            AudioLogicalSnapshot snapshot,
            int musicId, int sfxId) {
        assertPresentationSourcePair(snapshot, musicId, sfxId);
    }

    private static void assertPresentationSourcePair(
            AudioLogicalSnapshot snapshot,
            int musicId, int sfxId) {
        assertEquals(AudioSourceDescriptor.baseMusic(musicId),
                snapshot.presentation().activeMusic()
                        .sourceDescriptor());
        assertTrue(hasSfxSource(
                        snapshot.presentation().smpsLogical(), sfxId),
                "producer snapshot must retain source-bearing SFX "
                        + Integer.toHexString(sfxId));
    }

    private static void assertRawPcmSource(
            AudioLogicalSnapshot snapshot) {
        Long rawVoiceId = snapshot.presentation().rawPcmVoiceId();
        assertNotNull(rawVoiceId,
                "fresh producer snapshot must retain queued raw PCM");
        assertTrue(snapshot.presentation().voices().stream()
                        .map(com.openggf.audio.presentation
                                .PresentationVoiceSnapshot.Sample.class::cast)
                        .anyMatch(sample -> sample.voiceId() == rawVoiceId),
                "raw PCM slot must resolve to a retained producer voice");
    }

    private int pendingPresentationCommands() throws Exception {
        return ((AudioPresentationCommandQueue) getField(
                audio, "shadowCommands")).size();
    }

    private static void submitRawPcm(
            AudioManager audio, byte[] pcm, int sampleRate)
            throws Exception {
        Method method = AudioManager.class.getDeclaredMethod(
                "submitShadowRawPcmForTesting",
                byte[].class, int.class);
        method.setAccessible(true);
        method.invoke(audio, pcm, sampleRate);
    }

    private static boolean hasSfxSource(
            SmpsDriverSnapshot driver, int sfxId) {
        return driver != null
                && driver.sequencers().stream()
                .anyMatch(entry -> entry.sfx()
                        && entry.source().id() == sfxId);
    }

    private static java.util.List<String> sourcesOf(
            SmpsDriverSnapshot driver) {
        return driver == null ? java.util.List.of()
                : driver.sequencers().stream()
                .map(entry -> entry.sfx() + ":"
                        + entry.source().kind() + ":"
                        + Integer.toHexString(entry.source().id()))
                .toList();
    }

    private static AbstractSmpsData persistentSource(int id) {
        int sourceId = id;
        byte[] data = new byte[2_049];
        for (int index = 1; index < data.length; index += 2) {
            data[index] = (byte) 0x80;
            data[index + 1] = 0x7F;
        }
        return new AbstractSmpsData(data, 0) {
            {
                setId(sourceId);
            }

            @Override
            protected void parseHeader() {
                channels = 1;
                fmPointers = new int[] {1};
                fmKeyOffsets = new int[] {0};
                fmVolumeOffsets = new int[] {0};
            }

            @Override
            public byte[] getVoice(int voiceId) {
                return new byte[25];
            }

            @Override
            public byte[] getPsgEnvelope(int envelopeId) {
                return new byte[] {0};
            }

            @Override
            public int read16(int offset) {
                return 0;
            }

            @Override
            public int getBaseNoteOffset() {
                return 0;
            }
        };
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void installTestController(LiveRewindManager manager, RewindController controller) throws Exception {
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

    private static final class TestControllerBuilder {
        RewindController atFrame(int frame) {
            RewindController controller = new RewindController(
                    new RewindRegistry(),
                    new InMemoryKeyframeStore(),
                    new FakeInputSource(frame + 10),
                    in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                    2,
                    AudioManager.getInstance());
            for (int i = 0; i < frame; i++) {
                controller.recordExternalStep();
            }
            return controller;
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
