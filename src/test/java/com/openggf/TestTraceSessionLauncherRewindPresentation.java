package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioManagerTestDiagnostics;
import com.openggf.audio.NullAudioBackend;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.GameMode;
import com.openggf.game.rewind.InMemoryKeyframeStore;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.PlaybackController;
import com.openggf.game.rewind.RewindController;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.TraceData;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.catalog.TraceEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceSessionLauncherRewindPresentation {
    private SonicConfigurationService config;
    private AudioManager audio;
    private RecordingReverseAudioBackend backend;

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        config = SonicConfigurationService.getInstance();
        audio = AudioManager.getInstance();
        audio.resetState();
        backend = new RecordingReverseAudioBackend();
        audio.setBackend(backend);
        // Defensive: never inherit a live GameLoop from an Engine another test
        // in this reused fork constructed and did not release.
        Engine.clearGlobalInstance();
    }

    @AfterEach
    void tearDown() throws Exception {
        // This class installs itself as TraceSessionLauncher.activeSession. If
        // an assertion fails between the two teardown attempts the launcher is
        // left installed with teardownPending=true, and any later test in the
        // same fork that pumps an engine frame would run its
        // GameLoop.returnToMasterTitle() hand-back — a GL call with no context,
        // i.e. a JVM abort attributed to the wrong class.
        setStaticField(TraceSessionLauncher.class, "activeSession", null);
        Engine.clearGlobalInstance();
        audio.resetState();
        SessionManager.clear();
    }

    @Test
    void traceRealtimeRewindFreezesFadePresentationUntilReleaseCleanup() throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                AudioManager.getInstance());
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        setField(launcher, "rewindController", rewindController);
        setField(launcher, "rewindPlaybackController", new PlaybackController(rewindController));
        setField(launcher, "comparator", mock(LiveTraceComparator.class));
        setField(launcher, "rewindMovieBaseFrame", 0);
        setField(launcher, "rewindTraceBaseFrame", 0);
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        FadeManager fadeManager = gameplayMode.getFadeManager();
        InputHandler input = new InputHandler();
        input.handleKeyEvent(config.getInt(SonicConfiguration.TRACE_REWIND_KEY), GLFW_PRESS);

        assertTrue(launcher.handleRealtimeRewindInput(false, input));

        assertTrue(fadeManager.isReversePresentationActive());
        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());

        input.handleKeyEvent(config.getInt(SonicConfiguration.TRACE_REWIND_KEY), GLFW_RELEASE);
        assertFalse(launcher.handleRealtimeRewindInput(false, input));

        assertFalse(fadeManager.isReversePresentationActive());
        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(AudioManagerTestDiagnostics.producerFingerprint(audio)
                .reverseActive());
    }

    @Test
    void failedReleaseRetainsTraceHostAndPlaybackUntilLaterOwnerBoundary()
            throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                audio);
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        PlaybackController playbackController =
                new PlaybackController(rewindController);
        setField(launcher, "rewindController", rewindController);
        setField(launcher, "rewindPlaybackController", playbackController);
        setField(launcher, "comparator", mock(LiveTraceComparator.class));
        setField(launcher, "rewindMovieBaseFrame", 0);
        setField(launcher, "rewindTraceBaseFrame", 0);
        FadeManager fadeManager =
                TestEnvironment.activeGameplayMode().getFadeManager();
        InputHandler input = new InputHandler();
        int rewindKey =
                config.getInt(SonicConfiguration.TRACE_REWIND_KEY);
        input.handleKeyEvent(rewindKey, GLFW_PRESS);
        assertTrue(launcher.handleRealtimeRewindInput(false, input));
        input.handleKeyEvent(rewindKey, GLFW_RELEASE);
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        assertTrue(launcher.handleRealtimeRewindInput(false, input),
                "failed release must keep Trace gameplay frozen");
        assertTrue(audio.isReverseAudioPresentationActive());
        assertTrue(fadeManager.isReversePresentationActive());
        assertEquals(PlaybackController.State.REWINDING,
                playbackController.state());
        assertTrue((boolean) getField(launcher, "realtimeRewinding"));

        assertFalse(launcher.handleRealtimeRewindInput(false, input));
        assertFalse(audio.isReverseAudioPresentationActive());
        assertFalse(fadeManager.isReversePresentationActive());
        assertEquals(PlaybackController.State.PLAYING,
                playbackController.state());
        assertFalse((boolean) getField(launcher, "realtimeRewinding"));
        assertEquals(0, backend.totalCalls(),
                "the release is producer-owned; the backend sees nothing");
    }

    @Test
    void failedTeardownReleaseRetainsActiveSessionUntilLaterRetry()
            throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                audio);
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        setField(launcher, "rewindController", rewindController);
        setField(launcher, "rewindPlaybackController",
                new PlaybackController(rewindController));
        setField(launcher, "comparator", mock(LiveTraceComparator.class));
        setStaticField(TraceSessionLauncher.class, "activeSession", launcher);
        audio.beginReverseAudioPresentation();
        audio.restoreLogicalSnapshot(audio.captureLogicalSnapshot());
        assertTrue(audio.commitDeferredReverseLogicalRestore());
        AudioManagerTestDiagnostics.failNextReverseRelease(audio);

        invokeTeardown(launcher);

        assertEquals(launcher, TraceSessionLauncher.active(),
                "failed teardown release must retain the active retry host");
        assertTrue(audio.isReverseAudioPresentationActive());
        assertEquals(rewindController, getField(launcher, "rewindController"));

        invokeTeardown(launcher);

        assertEquals(null, TraceSessionLauncher.active());
        assertFalse(audio.isReverseAudioPresentationActive());
        assertEquals(0, backend.totalCalls(),
                "the release is producer-owned; the backend sees nothing");
    }

    /**
     * Wave 3, Fix 2: the same softlock {@code LiveRewindManager} was gated against
     * (commit {@code 26fb7debd}) also reaches Trace Test Mode's realtime rewind, which
     * that commit deliberately left untouched ("its own realtime-rewind path was not
     * covered by this investigation"). A held rewind in Trace Test Mode during a
     * special/bonus-stage/ending/zone-act transition window (still {@code GameMode.LEVEL}
     * throughout) could keep walking backward through pre-transition history and desync
     * the trace comparator the same way. Mirrors
     * {@code TestLiveRewindManagerAudioCleanup}'s equivalent pending-transition case.
     */
    @Test
    void pendingNonRewindableTransitionRejectsRealtimeRewindAndTearsDownPresentation() throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                2,
                AudioManager.getInstance());
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        setField(launcher, "rewindController", rewindController);
        setField(launcher, "rewindPlaybackController", new PlaybackController(rewindController));
        setField(launcher, "comparator", mock(LiveTraceComparator.class));
        setField(launcher, "rewindMovieBaseFrame", 0);
        setField(launcher, "rewindTraceBaseFrame", 0);
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        FadeManager fadeManager = gameplayMode.getFadeManager();
        InputHandler input = new InputHandler();
        input.handleKeyEvent(config.getInt(SonicConfiguration.TRACE_REWIND_KEY), GLFW_PRESS);

        // pending=false: engage normally first, matching the report's "already held
        // when the transition fires" worst case.
        assertTrue(launcher.handleRealtimeRewindInput(false, input));
        assertTrue(fadeManager.isReversePresentationActive());

        // pending=true (still held): must reject and tear down, not keep walking
        // backward through pre-transition history.
        assertFalse(launcher.handleRealtimeRewindInput(true, input));
        assertFalse(fadeManager.isReversePresentationActive(),
                "a pending non-rewindable transition must tear down the reverse fade "
                        + "presentation, not leave it active while the frame is rejected");
        assertFalse(audio.isReverseAudioPresentationActive(),
                "rejecting on a pending transition must run the same cleanup as a normal "
                        + "release, not silently leave the audio reverse-presentation stuck");
    }

    @Test
    void traceBoundaryExternalFrameRerootsRewindBuffer() throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(20),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                3,
                AudioManager.getInstance());
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        setField(launcher, "rewindController", rewindController);
        setStaticField(TraceSessionLauncher.class, "activeSession", launcher);
        var admission = new LevelIterationAdmissionController();
        var level = mock(LevelManager.class);
        var request = SeamlessLevelTransitionRequest.builder(
                SeamlessLevelTransitionRequest.TransitionType.MUTATE_ONLY)
                .build();
        when(level.consumeSeamlessTransitionRequest()).thenReturn(request);
        Runnable startPendingTitle = mock(Runnable.class);

        admission.admit(
                GameMode.LEVEL,
                () -> false,
                () -> LevelFrameResult.SETUP_ONLY,
                level,
                TestEnvironment.activeGameplayMode(),
                false,
                mock(UserRecordingRuntimeControls.class),
                startPendingTitle,
                () -> { },
                () -> { });
        verify(level).applySeamlessTransition(request);
        verify(startPendingTitle).run();

        assertTrue(admission.completePendingBoundary(
                true,
                ignored -> { },
                () -> { },
                TestEnvironment::activeGameplayMode));

        assertEquals(6, rewindController.currentFrame());
        assertEquals(6, rewindController.earliestAvailableFrame());
        assertFalse(rewindController.stepBackward(),
                "Trace rewind must not cross a seamless transition boundary");
    }

    @Test
    void inFrameTraceBoundaryRecordsCompletedFrameBeforeRerooting() throws Exception {
        TraceSessionLauncher launcher = newLauncher();
        RewindController rewindController = new RewindController(
                new RewindRegistry(),
                new InMemoryKeyframeStore(),
                new FakeInputSource(20),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                3,
                AudioManager.getInstance());
        for (int i = 0; i < 5; i++) {
            rewindController.recordExternalStep();
        }
        setField(launcher, "rewindController", rewindController);

        launcher.recordExternalRewindFrame(true);

        assertEquals(6, rewindController.currentFrame());
        assertEquals(6, rewindController.earliestAvailableFrame());
        assertFalse(rewindController.stepBackward(),
                "the completed in-frame transition must replace pre-act trace history");

        launcher.recordExternalRewindFrame(false);

        assertTrue(rewindController.stepBackward(),
                "trace rewind must remain available among post-transition frames");
        assertEquals(6, rewindController.currentFrame());
    }

    private static TraceSessionLauncher newLauncher() throws Exception {
        Constructor<TraceSessionLauncher> constructor = TraceSessionLauncher.class.getDeclaredConstructor(
                TraceEntry.class,
                TraceData.class,
                Bk2Movie.class,
                TraceReplaySessionBootstrap.ConfigSnapshot.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null, null);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setStaticField(
            Class<?> target, String fieldName, Object value) throws Exception {
        Field field = target.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void invokeTeardown(TraceSessionLauncher launcher)
            throws Exception {
        Method method = TraceSessionLauncher.class.getDeclaredMethod("teardown");
        method.setAccessible(true);
        method.invoke(launcher);
    }

    private static Object getField(Object target, String fieldName)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
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

    /**
     * The backend is a pure source-construction collaborator now: reverse
     * presentation, history and release are producer-owned, so a Trace host
     * release must leave it untouched.
     */
    private static final class RecordingReverseAudioBackend extends NullAudioBackend {
        private final List<String> calls = new ArrayList<>();

        int totalCalls() {
            return calls.size();
        }

        @Override
        public void update() {
            calls.add("update");
        }

        @Override
        public void stopPlayback() {
            calls.add("stopPlayback");
        }

        @Override
        public void stopAllSfx() {
            calls.add("stopAllSfx");
        }

        @Override
        public void restoreMusic() {
            calls.add("restoreMusic");
        }
    }
}
