package com.openggf.game.recording;

import com.openggf.GameLoop;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.RomManager;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameMode;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.RomDetectionService;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.game.recording.menu.UserRecordingMenu;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F10;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F9;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestUserRecordingControls {
    @TempDir
    Path tempDir;

    /**
     * The GameLoop constructor installs its EngineContext process-wide via
     * EngineServices.configure, so the mocked context built by
     * playbackTakeoverEndsSessionBeforePlaybackInputBridgeCanForceInput (with a
     * mock RomDetectionService) would otherwise stay current for every later
     * class in the fork; TestHeaderNameRomDetectors then saw registry detection
     * silently return empty while the singleton path detected the ROM.
     */
    @AfterEach
    void restoreEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void holdReachesLauncherExactlyAtSixtyFrames() {
        Fixture fixture = new Fixture();
        InputHandler input = recordingChord();

        for (int frame = 0; frame < 59; frame++) {
            fixture.controls.updateLevelControlInput(input);
            assertEquals(0, fixture.beginRecordingCalls);
            assertTrue(fixture.controls.hudState().visible());
        }

        fixture.controls.updateLevelControlInput(input);

        assertEquals(1, fixture.beginRecordingCalls);
        assertEquals(60, fixture.controls.recordHoldFrames());
    }

    @Test
    void releaseAtFiftyNineCancelsHoldWithoutStarting() {
        Fixture fixture = new Fixture();
        InputHandler input = recordingChord();

        for (int frame = 0; frame < 59; frame++) {
            fixture.controls.updateLevelControlInput(input);
        }
        input.handleKeyEvent(fixture.recordKey, GLFW_RELEASE);
        fixture.controls.updateLevelControlInput(input);

        assertEquals(0, fixture.beginRecordingCalls);
        assertEquals(0, fixture.controls.recordHoldFrames());
        assertFalse(fixture.controls.hudState().visible());
    }

    @Test
    void plainRecordStopsActiveRecording() {
        Fixture fixture = new Fixture();
        fixture.activeRecording = true;
        InputHandler input = new InputHandler();
        input.handleKeyEvent(fixture.recordKey, GLFW_PRESS);

        fixture.controls.updateLevelControlInput(input);

        assertEquals(UserRecordingStopReason.USER_STOPPED, fixture.stopReason);
        assertFalse(fixture.activeRecording);
    }

    @Test
    void fastForwardRenderSuppressionClearsOnDesyncAndCompletion() {
        Fixture fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(120, true, true);
        fixture.playbackState = UserRecordingPlaybackState.PLAYING;

        assertTrue(fixture.controls.shouldSuppressSceneRendering());

        fixture.desynced = true;
        fixture.controls.afterPlaybackFrame(42, false, false);

        assertEquals(UserRecordingPlaybackState.PAUSED_ON_DESYNC, fixture.playbackState);
        assertTrue(fixture.enginePausedForPlayback);
        assertFalse(fixture.controls.shouldSuppressSceneRendering());

        fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(120, true, true);
        fixture.playbackState = UserRecordingPlaybackState.PLAYING;
        fixture.controls.afterPlaybackFrame(99, true, true);

        assertEquals(UserRecordingPlaybackState.PAUSED_AT_COMPLETION, fixture.playbackState);
        assertFalse(fixture.controls.shouldSuppressSceneRendering());
    }

    @Test
    void targetPauseWaitsUntilAppliedMovieFrameReachesTarget() {
        Fixture fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(10, true, false);
        fixture.playbackState = UserRecordingPlaybackState.PLAYING;

        fixture.controls.afterPlaybackFrame(9, false, false);

        assertEquals(UserRecordingPlaybackState.PLAYING, fixture.playbackState);
        assertFalse(fixture.enginePausedForPlayback);

        fixture.controls.afterPlaybackFrame(10, false, false);

        assertEquals(UserRecordingPlaybackState.PAUSED_AT_TARGET, fixture.playbackState);
        assertTrue(fixture.enginePausedForPlayback);
    }

    @Test
    void playbackHudSurfacesVerifierStatusAsNonBlockingWarning() {
        Fixture fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(120, true, true);
        fixture.playbackState = UserRecordingPlaybackState.PLAYING;
        fixture.currentPlaybackFrame = 42;
        fixture.playbackFrameCount = 120;
        fixture.verificationResult = UserRecordingVerificationResult.missingSidecar(0);

        UserRecordingHudState hud = fixture.controls.hudState();

        assertTrue(hud.visible());
        assertEquals("PLAYBACK FF 42/120", hud.primaryText());
        assertEquals("VERIFY missing-sidecar", hud.secondaryText());
        assertTrue(hud.amberWarning());
        assertFalse(hud.redWarning());
    }

    @Test
    void playbackHudShowsCleanVerifierStatusWithoutWarning() {
        Fixture fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(120, true, false);
        fixture.playbackState = UserRecordingPlaybackState.PLAYING;
        fixture.currentPlaybackFrame = 5;
        fixture.playbackFrameCount = 120;
        fixture.verificationResult = UserRecordingVerificationResult.clean(5);

        UserRecordingHudState hud = fixture.controls.hudState();

        assertEquals("PLAYBACK 5/120", hud.primaryText());
        assertEquals("VERIFY clean", hud.secondaryText());
        assertFalse(hud.amberWarning());
        assertFalse(hud.redWarning());
    }

    @Test
    void pausedOnDesyncPlaybackHudSurfacesFirstMismatchVerifierStatus() {
        Fixture fixture = new Fixture();
        fixture.playbackOptions = new UserRecordingPlaybackOptions(120, true, true);
        fixture.playbackState = UserRecordingPlaybackState.PAUSED_ON_DESYNC;
        fixture.currentPlaybackFrame = 44;
        fixture.playbackFrameCount = 120;
        fixture.verificationResult = UserRecordingVerificationResult.firstMismatch(
                45, 44, "p1CentreX", "100", "321");

        UserRecordingHudState hud = fixture.controls.hudState();

        assertTrue(hud.visible());
        assertEquals("PLAYBACK FF 44/120", hud.primaryText());
        assertEquals("VERIFY first-mismatch(frame=44, field=p1CentreX, expected=100, actual=321)",
                hud.secondaryText());
        assertTrue(hud.amberWarning());
        assertFalse(hud.redWarning());
    }

    @Test
    void masterTitleShiftRecordUsesConfiguredRecordKey() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.RECORDING_RECORD_KEY, GLFW_KEY_F10);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> new UserRecordingMenu(
                gameId, List.of(), font, (entry, options) -> { }));
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        screen.update(input);
        assertFalse(screen.isUserRecordingMenuOpenForTest());

        input.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        input.update();
        input.handleKeyEvent(GLFW_KEY_F10, GLFW_PRESS);
        screen.update(input);

        assertTrue(screen.isUserRecordingMenuOpenForTest());
    }

    @Test
    void defaultRecordKeyUsesF9InsteadOfPlaneSwitcherF10() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);

        int recordKey = config.getInt(SonicConfiguration.RECORDING_RECORD_KEY);

        assertEquals(GLFW_KEY_F9, recordKey);
        assertNotEquals(GLFW_KEY_F10, recordKey);
    }

    @Test
    void playbackTakeoverEndsSessionBeforePlaybackInputBridgeCanForceInput() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.PAUSE_KEY, GLFW_KEY_ENTER);
        PlaybackDebugManager playback = mock(PlaybackDebugManager.class);
        AtomicBoolean sessionEnded = new AtomicBoolean(false);
        when(playback.isDriving(any())).thenAnswer(invocation -> !sessionEnded.get());
        when(playback.getCurrentForcedInputMask()).thenReturn(AbstractPlayableSprite.INPUT_RIGHT);
        when(playback.isCurrentForcedJumpPress()).thenReturn(true);
        doAnswer(invocation -> {
            sessionEnded.set(true);
            return null;
        }).when(playback).endSession();

        GameLoop loop = new GameLoop(new EngineContext(
                config,
                mock(GraphicsManager.class),
                mock(AudioManager.class),
                mock(RomManager.class),
                mock(PerformanceProfiler.class),
                mock(DebugOverlayManager.class),
                playback,
                mock(RomDetectionService.class),
                mock(CrossGameFeatureProvider.class)));
        loop.toggleUserPause();

        Object launcher = getPrivateField(loop, "userRecordingSessionLauncher");
        setPrivateField(launcher, "activePlaybackOptions", new UserRecordingPlaybackOptions(10, true, false));
        setPrivateField(launcher, "activePlaybackState", UserRecordingPlaybackState.PAUSED_ON_DESYNC);

        SpriteManager sprites = mock(SpriteManager.class);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(sprites.getSprite("sonic")).thenReturn(player);
        setPrivateField(loop, "spriteManager", sprites);

        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_ENTER, GLFW_PRESS);

        assertTrue(invokeBoolean(loop, "handlePlaybackTakeoverBeforePlaybackInputBridge", input));
        invokeVoid(loop, "syncPlaybackInputBridge");

        assertTrue(sessionEnded.get());
        assertFalse(loop.isUserPaused());
        verify(sprites, never()).setPlaybackInputSuppressed(true);
        verify(player, never()).setForcedInputMask(AbstractPlayableSprite.INPUT_RIGHT);
        verify(player, never()).setForcedJumpPress(true);
    }

    private InputHandler recordingChord() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F10, GLFW_PRESS);
        return input;
    }

    private static boolean invokeBoolean(Object target, String methodName, InputHandler input) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, InputHandler.class);
        method.setAccessible(true);
        return (boolean) method.invoke(target, input);
    }

    private static void invokeVoid(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private final class Fixture implements UserRecordingRuntimeControls.Runtime {
        final int recordKey = GLFW_KEY_F10;
        boolean activeRecording;
        int beginRecordingCalls;
        UserRecordingStopReason stopReason;
        UserRecordingPlaybackOptions playbackOptions;
        UserRecordingPlaybackState playbackState = UserRecordingPlaybackState.STOPPED;
        UserRecordingVerificationResult verificationResult;
        int currentPlaybackFrame;
        int playbackFrameCount;
        boolean desynced;
        boolean enginePausedForPlayback;
        final UserRecordingRuntimeControls controls = new UserRecordingRuntimeControls(this);

        @Override
        public int recordKey() {
            return recordKey;
        }

        @Override
        public GameMode currentGameMode() {
            return GameMode.LEVEL;
        }

        @Override
        public boolean traceOrDebugSurfaceOwnsRecordingInput() {
            return false;
        }

        @Override
        public boolean hasActiveRecording() {
            return activeRecording;
        }

        @Override
        public void beginRecordingFromCurrentLevel() {
            beginRecordingCalls++;
        }

        @Override
        public void stopActiveRecording(UserRecordingStopReason reason) {
            stopReason = reason;
            activeRecording = false;
        }

        @Override
        public UserRecordingPlaybackOptions activePlaybackOptions() {
            return playbackOptions;
        }

        @Override
        public UserRecordingPlaybackState activePlaybackState() {
            return playbackState;
        }

        @Override
        public boolean playbackHasDesynced() {
            return desynced;
        }

        @Override
        public UserRecordingVerificationResult activePlaybackVerificationResult() {
            return verificationResult;
        }

        @Override
        public int currentPlaybackFrame() {
            return currentPlaybackFrame;
        }

        @Override
        public int playbackFrameCount() {
            return playbackFrameCount;
        }

        @Override
        public void updatePlaybackState(UserRecordingPlaybackState state) {
            playbackState = state;
        }

        @Override
        public void pauseEngineForPlayback() {
            enginePausedForPlayback = true;
        }

        @Override
        public void endPlaybackDebugSession() {
        }
    }
}
