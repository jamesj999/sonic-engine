package com.openggf;

import com.openggf.game.session.EngineServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import com.openggf.control.GamepadInputManager;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.FrameRateResolver;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.dataselect.DataSelectAction;
import com.openggf.game.dataselect.DataSelectActionType;
import com.openggf.game.dataselect.DataSelectExitTransition;
import com.openggf.game.session.EngineContext;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.GameModule;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.BonusStageState;
import com.openggf.game.EndingPhase;
import com.openggf.game.EndingProvider;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.TitleScreenProvider.TitleScreenAction;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameRng;
import com.openggf.game.dataselect.DataSelectPresentationProvider;
import com.openggf.game.dataselect.DataSelectSessionController;
import com.openggf.game.launch.LaunchProfile;
import com.openggf.game.launch.LaunchProfileApplier;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.game.launch.MasterTitleLaunchCoordinator;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic3k.dataselect.S3kDataSelectProfile;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.dataselect.S3kDataSelectManager;
import com.openggf.graphics.FadeManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.recording.UserRecordingRuntimeControls;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.lwjgl.glfw.GLFW.*;

/**
 * Tests for GameLoop class - the core game logic that can run headlessly.
 * These tests verify game state transitions and mode switching
 * without requiring an OpenGL context.
 */
public class TestGameLoop {

    @Test
    void palFrameCadenceUsesFiftyHz() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.REGION, "PAL");
        config.setConfigValue(SonicConfiguration.FPS, 60);
        assertEquals(50, FrameRateResolver.effective(config));
    }

    private GameLoop gameLoop;
    private InputHandler mockInputHandler;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService.getInstance().clearSessionOverrides();
        SonicConfigurationService.getInstance().resolveDisplayAspect();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        mockInputHandler = mock(InputHandler.class);
        when(mockInputHandler.logical()).thenReturn(com.openggf.control.LogicalInputSnapshot.neutral());
        gameLoop = new GameLoop(mockInputHandler);
    }

    @AfterEach
    public void tearDown() {
        SonicConfigurationService.getInstance().clearSessionOverrides();
        SonicConfigurationService.getInstance().resolveDisplayAspect();
        gameLoop = null;
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    private static GameModule neutralGameModule() {
        GameModule module = mock(GameModule.class);
        when(module.createRuntimeArtCoordinator(any())).thenReturn(RuntimeArtCoordinator.NONE);
        return module;
    }

    // ==================== Value Object Tests ====================

    @Test
    void selectedTeam_valueObjectPreservesMainAndSidekicks() {
        com.openggf.game.save.SelectedTeam team = new com.openggf.game.save.SelectedTeam("knuckles", java.util.List.of("tails"));
        assertEquals("knuckles", team.mainCharacter());
        assertEquals(java.util.List.of("tails"), team.sidekicks());
    }

    // ==================== Initialization Tests ====================

    @Test
    public void testGameLoopConstructorWithInputHandler() {
        GameLoop loop = new GameLoop(mockInputHandler);
        assertEquals(mockInputHandler, loop.getInputHandler(), "Input handler should be set via constructor");
    }

    @Test
    public void testSetInputHandler() {
        GameLoop loop = new GameLoop();
        assertNull(loop.getInputHandler(), "Input handler should be null initially");

        loop.setInputHandler(mockInputHandler);
        assertEquals(mockInputHandler, loop.getInputHandler(), "Input handler should be set");
    }

    @Test
    public void testStepWithoutInputHandlerThrows() {
        GameLoop loop = new GameLoop();
        assertThrows(IllegalStateException.class, loop::step);
    }

    @Test
    public void pauseKeyDoesNotToggleUserPauseInDataSelectMode() {
        InputHandler inputHandler = new InputHandler();
        GameLoop loop = new GameLoop(inputHandler);
        loop.setGameMode(GameMode.DATA_SELECT);

        int pauseKey = SonicConfigurationService.getInstance().getInt(SonicConfiguration.PAUSE_KEY);
        inputHandler.handleKeyEvent(pauseKey, GLFW_PRESS);
        loop.step();

        assertFalse(loop.isUserPaused(),
                "Engine pause input should be ignored in menu modes so data-select cannot appear frozen");
    }

    @Test
    public void gamepadStartDoesNotToggleUserPauseInDataSelectMode() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler inputHandler = new InputHandler(InputBindingFactory.supplier(config), source);
        GameLoop loop = new GameLoop(inputHandler);
        loop.setGameMode(GameMode.DATA_SELECT);

        source.setDevices(GamepadStateSource.DeviceState.connected(
                0, "pad", buttons(GLFW_GAMEPAD_BUTTON_START), 0f, 0f));
        loop.step();

        assertFalse(loop.isUserPaused(),
                "Gamepad Start should be ignored in menu modes so data-select cannot appear frozen");
    }

    private static boolean[] buttons(int... pressedButtons) {
        boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
        for (int button : pressedButtons) {
            buttons[button] = true;
        }
        return buttons;
    }

    private static final class FakeGamepadStateSource implements GamepadStateSource {
        private final List<DeviceState> devices = new java.util.ArrayList<>();

        void setDevices(DeviceState... devices) {
            this.devices.clear();
            this.devices.addAll(List.of(devices));
        }

        @Override
        public List<DeviceState> pollDevices() {
            return List.copyOf(devices);
        }
    }

    @Test
    public void userPauseIndicatorIsNotDebugViewGated() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/Engine.java"));
        int userPaused = source.indexOf("gameLoop.isUserPaused()");
        int screenshotSuppression = source.indexOf("isUserPauseScreenshotSuppressionKeyPressed(inputHandler)");
        int visibility = source.indexOf("shouldRenderUserPauseIndicator(");
        int needsOverlay = source.indexOf("debugViewEnabled || playbackHud || userPauseIndicatorVisible");
        int render = source.indexOf("renderUserPauseIndicator();");

        assertTrue(userPaused >= 0, "Engine display path must observe GameLoop user pause state");
        assertTrue(screenshotSuppression > userPaused,
                "Screenshot keys should update pause indicator suppression before overlay visibility is resolved");
        assertTrue(visibility > screenshotSuppression,
                "Pause indicator visibility should account for screenshot suppression before overlay setup");
        assertTrue(needsOverlay > userPaused,
                "The pause overlay should contribute to overlay setup independently of DEBUG_VIEW_ENABLED");
        assertTrue(render > needsOverlay,
                "The user-pause indicator should render from the release-visible overlay path");
    }

    @Test
    public void userPauseIndicatorPlacementUsesLogicalBottomRightCorner() {
        Engine.PauseIndicatorPlacement placement = Engine.userPauseIndicatorPlacement(
                320, 224, 54, 11);

        assertEquals(258, placement.x());
        assertEquals(205, placement.y());
    }

    @Test
    public void userPauseIndicatorPlacementClampsInsideNarrowLogicalViewport() {
        Engine.PauseIndicatorPlacement placement = Engine.userPauseIndicatorPlacement(
                48, 40, 54, 11);

        assertEquals(0, placement.x());
        assertEquals(21, placement.y());
    }

    @Test
    public void userPauseIndicatorAlphaSmoothlyFadesInAndOutEveryHalfSecond() {
        assertEquals(255, Engine.userPauseIndicatorAlpha(0));
        assertEquals(128, Engine.userPauseIndicatorAlpha(250_000_000L), 1);
        assertEquals(0, Engine.userPauseIndicatorAlpha(500_000_000L), 1);
        assertEquals(128, Engine.userPauseIndicatorAlpha(750_000_000L), 1);
        assertEquals(255, Engine.userPauseIndicatorAlpha(1_000_000_000L));
    }

    @Test
    public void userPauseIndicatorHidesForOneSecondAfterScreenshotKey() {
        long keyPressNanos = 3_000_000_000L;
        long hiddenUntilNanos = Engine.userPauseIndicatorHiddenUntilAfterScreenshotKey(keyPressNanos);

        assertFalse(Engine.shouldRenderUserPauseIndicator(true, keyPressNanos, hiddenUntilNanos));
        assertFalse(Engine.shouldRenderUserPauseIndicator(true, keyPressNanos + 999_999_999L, hiddenUntilNanos));
        assertTrue(Engine.shouldRenderUserPauseIndicator(true, keyPressNanos + 1_000_000_000L, hiddenUntilNanos));
        assertFalse(Engine.shouldRenderUserPauseIndicator(false, keyPressNanos + 1_000_000_000L, hiddenUntilNanos));
    }

    @Test
    public void userPauseIndicatorScreenshotSuppressionUsesF12AndPrintScreenEdges() {
        InputHandler input = new InputHandler();

        assertFalse(Engine.isUserPauseScreenshotSuppressionKeyPressed(input));

        input.handleKeyEvent(GLFW_KEY_F12, GLFW_PRESS);
        assertTrue(Engine.isUserPauseScreenshotSuppressionKeyPressed(input));

        input.update();
        assertFalse(Engine.isUserPauseScreenshotSuppressionKeyPressed(input));

        input.handleKeyEvent(GLFW_KEY_F12, GLFW_RELEASE);
        input.update();
        input.handleKeyEvent(GLFW_KEY_PRINT_SCREEN, GLFW_PRESS);
        assertTrue(Engine.isUserPauseScreenshotSuppressionKeyPressed(input));
    }

    @Test
    public void traceRealtimeRewindRunsBeforePreparedPlaybackAdmission() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        int rewind = source.indexOf("TraceSessionLauncher.active().handleRealtimeRewindInput(");
        int admission = source.indexOf("LevelFrameResult admission = levelIterationAdmission.admit");
        int bridge = source.indexOf("syncPlaybackInputBridge();");
        assertTrue(rewind >= 0, "GameLoop must handle trace realtime rewind");
        assertTrue(bridge >= 0, "GameLoop must bridge playback input");
        assertTrue(rewind < bridge,
                "Rewind release must seek/play the playback timeline before forced input is sampled");
        assertTrue(admission > bridge,
                "prepared playback Start and held input must be published before ROM admission");
    }

    @Test
    public void levelAndBonusTraceSuppressionIsClassifiedBeforeGenericTimers()
            throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        int classification = source.indexOf(
                "playbackDebugManager.shouldSkipCurrentGameplayTick();");
        int timers = source.indexOf("timerManager.update();", classification);
        String prefix = source.substring(Math.max(0, classification - 280), classification);

        assertTrue(prefix.contains("currentGameMode == GameMode.LEVEL"));
        assertTrue(prefix.contains("currentGameMode == GameMode.BONUS_STAGE"),
                "bonus-stage trace rows must classify suppression before generic timers");
        assertTrue(classification >= 0 && timers > classification,
                "trace suppression must be known before TimerManager advances");
    }

    @Test
    public void userRecordingPlaybackPolicyObservesAppliedMovieFrameBeforeCursorAdvance() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/LevelIterationAdmissionController.java"));

        int capture = source.indexOf("appliedFrame = playback.getCursorFrame();");
        int advance = source.indexOf("playback.onLevelFrameAdvanced();", capture);
        int policy = source.indexOf("recordingControls.afterPlaybackFrame(\n" +
                        "                appliedFrame,", advance);
        assertTrue(capture >= 0 && advance > capture,
                "the admission controller must capture the BK2 frame before advancing the cursor");
        assertTrue(policy > advance,
                "target/completion policy must classify the frame that was just applied");
    }

    @Test
    public void userRecordingPlaybackPolicyRunsForLevelBoundaryReturns() throws Exception {
        var playback = mock(com.openggf.debug.playback.PlaybackDebugManager.class);
        var recordingControls = mock(UserRecordingRuntimeControls.class);
        when(playback.getCursorFrame()).thenReturn(4);
        when(playback.getMovieFrameCount()).thenReturn(8);
        when(playback.isSessionPlaying()).thenReturn(true);
        LevelIterationAdmissionController admission = new LevelIterationAdmissionController();

        admission.finishPlaybackBoundary(true, playback, recordingControls);

        var ordered = inOrder(playback, recordingControls);
        ordered.verify(playback).getCursorFrame();
        ordered.verify(playback).onLevelFrameAdvanced();
        ordered.verify(recordingControls).afterPlaybackFrame(4, true, false);

        admission.finishPlaybackBoundary(false, playback, recordingControls);
        verify(recordingControls, times(2)).afterPlaybackFrame(4, true, false);

        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        assertTrue(source.contains("levelIterationAdmission.finishPlaybackBoundary("),
                "GameLoop boundary exits must delegate to the admission controller");
    }

    @Test
    public void userRecordingBoundaryDoesNotClassifyCursorZeroBeforeAnyMovieFrameApplied() throws Exception {
        var playback =
                mock(com.openggf.debug.playback.PlaybackDebugManager.class);
        var recordingControls = mock(UserRecordingRuntimeControls.class);
        when(playback.getCursorFrame()).thenReturn(0);
        when(playback.getMovieFrameCount()).thenReturn(2);
        when(playback.isSessionPlaying()).thenReturn(true);
        LevelIterationAdmissionController admission = new LevelIterationAdmissionController();

        admission.finishPlaybackBoundary(false, playback, recordingControls);

        verifyNoInteractions(recordingControls);
        verify(playback, never()).onLevelFrameAdvanced();
    }

    @Test
    public void endingFadeFreezesGameplayUntilEndingModeEnters() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));

        assertTrue(source.contains("private boolean endingTransitionPending"),
                "Ending fade must have a pending flag so LEVEL mode does not keep simulating under the white fade");

        int nonRewindablePredicate = source.indexOf("private boolean isNonRewindableTransitionPending()");
        int nonRewindablePredicateEnd = source.indexOf("private void stepInternal(", nonRewindablePredicate);
        assertTrue(nonRewindablePredicate >= 0 && nonRewindablePredicateEnd > nonRewindablePredicate,
                "isNonRewindableTransitionPending() shared predicate must exist");
        String nonRewindablePredicateBody =
                source.substring(nonRewindablePredicate, nonRewindablePredicateEnd);
        assertTrue(nonRewindablePredicateBody.contains("|| endingTransitionPending"),
                "the shared transition-freeze predicate (also consulted by LiveRewindManager) must "
                        + "fold in the ending fade's pending flag");

        int updateLevel = source.indexOf("private boolean updateLevelMode(");
        int updateLevelEnd = source.indexOf("private void updateBonusStageMode(", updateLevel);
        assertTrue(updateLevel >= 0 && updateLevelEnd > updateLevel, "updateLevelMode method must exist");
        String levelBody = source.substring(updateLevel, updateLevelEnd);
        assertTrue(levelBody.contains(
                        "boolean freezeForNonRewindableTransition = isNonRewindableTransitionPending();"),
                "LEVEL mode should include ending fade (via the shared transition-freeze predicate) "
                        + "in its gameplay-freeze flags");
        assertTrue(levelBody.contains("&& !freezeForNonRewindableTransition"),
                "LEVEL mode must skip LevelFrameStep while the ending fade callback is pending");

        int startEnding = source.indexOf("private void startEndingFade()");
        int startEndingEnd = source.indexOf("private void doEnterEnding()", startEnding);
        assertTrue(startEnding >= 0 && startEndingEnd > startEnding, "startEndingFade method must exist");
        String startEndingBody = source.substring(startEnding, startEndingEnd);
        assertTrue(startEndingBody.contains("endingTransitionPending = true;"),
                "Starting the ending fade should freeze gameplay until doEnterEnding runs");

        int doEnterEnding = startEndingEnd;
        int doEnterEndingEnd = source.indexOf("private void updateEnding()", doEnterEnding);
        assertTrue(doEnterEndingEnd > doEnterEnding, "doEnterEnding method must precede updateEnding");
        String doEnterEndingBody = source.substring(doEnterEnding, doEnterEndingEnd);
        assertTrue(doEnterEndingBody.contains("endingTransitionPending = false;"),
                "Entering ending mode should clear the pending freeze");
    }

    @Test
    public void returnToMasterTitleTearsDownUserRecordingSessionsBeforeLeavingLevel() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        int methodStart = source.indexOf("void returnToMasterTitle()");
        int methodEnd = source.indexOf("private void startEscapeToMasterTitleTransition()", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart, "returnToMasterTitle method must exist");
        String methodBody = source.substring(methodStart, methodEnd);

        assertTrue(methodBody.contains("userRecordingSessionLauncher.stopActiveRecording"),
                "Escape/return-to-title must finalize active user recordings");
        assertTrue(methodBody.contains("userRecordingSessionLauncher.endPlaybackSession"),
                "Escape/return-to-title must end user recording playback and forced BK2 input");
        assertTrue(methodBody.indexOf("userRecordingSessionLauncher.stopActiveRecording")
                        < methodBody.indexOf("masterTitleLaunchCoordinator.returnToMasterTitle()"),
                "Recording teardown must run before gameplay is handed back to master title");
        assertTrue(methodBody.indexOf("userRecordingSessionLauncher.endPlaybackSession")
                        < methodBody.indexOf("masterTitleLaunchCoordinator.returnToMasterTitle()"),
                "Playback teardown must run before gameplay is handed back to master title");
    }

    @Test
    public void playbackStartInputFeedsGameplayPauseEdge() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        int stepInternal = source.indexOf("private void stepInternal(");
        int prepareAdmission = source.indexOf(
                "private boolean prepareAdmittedIteration(", stepInternal);
        assertTrue(stepInternal >= 0 && prepareAdmission > stepInternal,
                "stepInternal method must exist");
        int prepareAdmissionEnd = source.indexOf(
                "private void updateSpecialStageMode(", prepareAdmission);
        assertTrue(prepareAdmissionEnd > prepareAdmission,
                "prepareAdmittedIteration method must exist");
        String admissionBody = source.substring(prepareAdmission, prepareAdmissionEnd);

        assertTrue(admissionBody.contains("playbackDebugManager.isCurrentForcedStartPress()"),
                "Recorded P1 Start must route to the same gameplay pause edge as live Start");
    }

    @Test
    public void setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));
        int step = source.indexOf("private boolean prepareAdmittedIteration(");
        int end = source.indexOf("private void updateSpecialStageMode(", step);
        String body = source.substring(step, end);

        int admission = body.indexOf("LevelFrameResult admission = levelIterationAdmission.admit");
        int boundary = body.indexOf("levelIterationAdmission.completePendingBoundary", admission);
        int traceCamera = body.indexOf("traceCameraFocusController.tick", admission);
        assertTrue(admission >= 0 && boundary > admission && traceCamera > boundary,
                "seamless boundary completion and trace-camera input must follow setup admission");

        assertTrue(body.indexOf("levelIterationAdmission.admit(")
                        < body.indexOf("levelIterationAdmission.completePendingBoundary"),
                "a seamless load must be admitted before its pending boundary can complete");
    }

    @Test
    public void stepInternalDelegatesBootScreensToExtractedController() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));

        // Boot-screen update sequencing now lives in BootScreenModeController.
        assertTrue(source.contains("bootScreenModeController.updateLegalDisclaimer("),
                "GameLoop must delegate the legal-disclaimer frame to BootScreenModeController");
        assertTrue(source.contains("bootScreenModeController.updateMasterTitle("),
                "GameLoop must delegate the master-title frame to BootScreenModeController");

        // The screen-update body must no longer be inlined in the dispatcher.
        int stepStart = source.indexOf("private void stepInternal()");
        int firstHandler = source.indexOf("private void updateSpecialStageMode()");
        assertTrue(stepStart >= 0 && firstHandler > stepStart,
                "stepInternal and the extracted handlers must both exist");
        String stepBody = source.substring(stepStart, firstHandler);
        assertFalse(stepBody.contains("masterScreen.update(inputHandler)"),
                "stepInternal must not inline the master-title screen update after extraction");
        assertFalse(stepBody.contains("disclaimer.update(inputHandler)"),
                "stepInternal must not inline the legal-disclaimer screen update after extraction");
    }

    @Test
    public void stepInternalDelegatesGameplayModesToCohesiveHandlers() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/GameLoop.java"));

        // Each large mode group is owned by a dedicated extracted handler rather
        // than the monolithic dispatcher.
        for (String handler : new String[] {
                "private void updateSpecialStageMode()",
                "private void updateSpecialStageResultsMode()",
                "private boolean updateTitleCardMode(",
                "private void updateTitleScreenMode()",
                "private void updateLevelSelectMode()",
                "private void updateDataSelectMode()",
                "private boolean updateLevelMode(",
                "private void updateBonusStageMode(" }) {
            assertTrue(source.contains(handler),
                    "GameLoop must own the extracted mode handler: " + handler);
        }

        // The canonical gameplay frame step and bonus-stage glowing-sphere
        // bootstrap must no longer be inlined inside stepInternal itself.
        int stepStart = source.indexOf("private void stepInternal()");
        int firstHandler = source.indexOf("private void updateSpecialStageMode()");
        String stepBody = source.substring(stepStart, firstHandler);
        assertFalse(stepBody.contains("LevelFrameStep.execute("),
                "stepInternal must route the gameplay frame step through extracted mode handlers");
        assertFalse(stepBody.contains("ZONE_GLOWING_SPHERE"),
                "stepInternal must not inline the bonus-stage bootstrap check");
    }

    @Test
    public void testMasterTitleScreenStepDoesNotRequireGameplayRuntime() {
        SessionManager.clear();

        InputHandler inputHandler = mock(InputHandler.class);
        MasterTitleScreen masterTitleScreen = mock(MasterTitleScreen.class);
        GameLoop loop = new GameLoop(inputHandler);
        loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        loop.setMasterTitleScreenSupplier(() -> masterTitleScreen);

        assertDoesNotThrow(loop::step);
        verify(masterTitleScreen).update(inputHandler);
        verify(inputHandler).update();
    }

    @Test
    public void testMasterTitleScreenSelectionStartsBootstrapFadeWithoutGameplayRuntime() {
        SessionManager.clear();

        InputHandler inputHandler = mock(InputHandler.class);
        MasterTitleScreen masterTitleScreen = mock(MasterTitleScreen.class);
        when(masterTitleScreen.isGameSelected()).thenReturn(true);
        when(masterTitleScreen.getSelectedGameId()).thenReturn("s1");

        FadeManager fadeManager = EngineServices.current().graphics().getFadeManager();
        fadeManager.cancel();

        GameLoop loop = new GameLoop(inputHandler);
        loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        loop.setMasterTitleScreenSupplier(() -> masterTitleScreen);
        loop.setMasterTitleExitHandler(gameId -> fail("Master title exit should wait for fade completion"));

        assertDoesNotThrow(loop::step);
        assertTrue(fadeManager.isActive(), "Bootstrap fade should start while no gameplay mode exists");
    }

    @Test
    void escapeHoldFadesAudioAndScreenBeforeReturningToMasterTitle() throws Exception {
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(input);
        AudioManager audioManager = mock(AudioManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        AtomicBoolean returnedToMasterTitle = new AtomicBoolean(false);

        when(fadeManager.isActive()).thenReturn(false);
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(loop, "audioManager", audioManager);
        setPrivateField(loop, "fadeManager", fadeManager);
        loop.setReturnToMasterTitleHandler(() -> returnedToMasterTitle.set(true));

        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        for (int frame = 0; frame < EscapeToMasterTitleController.HOLD_FRAMES; frame++) {
            loop.getEscapeToMasterTitleController().update(GameMode.LEVEL, input);
            input.update();
        }

        verify(audioManager).fadeOutMusic();
        verify(fadeManager).startFadeToBlack(any());
        assertFalse(returnedToMasterTitle.get(), "return should wait for fade-to-black callback");

        assertNotNull(fadeCallback.get());
        fadeCallback.get().run();
        assertTrue(returnedToMasterTitle.get());
    }

    @Test
    void escapeHoldOnMasterTitleFadesAudioAndScreenBeforeExitingApplication() throws Exception {
        SessionManager.clear();

        AudioManager audioManager = mock(AudioManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        com.openggf.graphics.GraphicsManager graphicsManager = mock(com.openggf.graphics.GraphicsManager.class);
        EngineContext engineContext = new EngineContext(
                SonicConfigurationService.getInstance(),
                graphicsManager,
                audioManager,
                com.openggf.data.RomManager.getInstance(),
                com.openggf.debug.PerformanceProfiler.getInstance(),
                com.openggf.debug.DebugOverlayManager.getInstance(),
                com.openggf.debug.playback.PlaybackDebugManager.getInstance(),
                com.openggf.game.RomDetectionService.getInstance(),
                com.openggf.game.CrossGameFeatureProvider.getInstance());
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(engineContext, input);
        MasterTitleScreen masterTitleScreen = mock(MasterTitleScreen.class);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        AtomicBoolean exitedApplication = new AtomicBoolean(false);

        when(fadeManager.isActive()).thenReturn(false);
        when(graphicsManager.getFadeManager()).thenReturn(fadeManager);
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        loop.setMasterTitleScreenSupplier(() -> masterTitleScreen);
        loop.setApplicationExitHandler(() -> exitedApplication.set(true));

        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        for (int frame = 0; frame < EscapeToMasterTitleController.HOLD_FRAMES; frame++) {
            loop.step();
        }

        verify(audioManager).fadeOutMusic();
        verify(fadeManager).startFadeToBlack(any());
        assertFalse(exitedApplication.get(), "application exit should wait for fade-to-black callback");

        assertNotNull(fadeCallback.get());
        fadeCallback.get().run();
        assertTrue(exitedApplication.get());
    }

    @Test
    void escapePromptProgressRendersWithRectanglesInsteadOfTextBar() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/openggf/Engine.java"));
        int promptRenderer = source.indexOf("private void renderEscapeToMasterTitlePrompt");
        int progressRenderer = source.indexOf("private void renderEscapeProgressBar");
        int nextMethod = source.indexOf("private boolean updateDisplayShaderInput()");

        assertTrue(promptRenderer >= 0, "Engine must render the Escape prompt overlay");
        assertTrue(progressRenderer > promptRenderer, "Escape progress must have a rectangle renderer");
        assertTrue(nextMethod > progressRenderer, "Escape progress renderer must stay in the overlay section");
        String escapeOverlaySource = source.substring(promptRenderer, nextMethod);
        assertTrue(escapeOverlaySource.contains("GLCommand.CommandType.RECTI"),
                "Escape progress should render with rectangle commands");
        assertFalse(escapeOverlaySource.contains("buildEscapeProgressBar"),
                "Escape progress should not render as an ASCII text bar");
    }

    @Test
    public void userMasterTitleExitClearsStaleOverridesAppliesProfileAndResolvesBeforeHandler() throws Exception {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();
        config.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "SUPER_32_9");

        TrackingLaunchProfileStore store = new TrackingLaunchProfileStore(config,
                new LaunchProfile(true, "s1", true, "WIDE_16_9", "sonic", "none"));
        installLaunchCoordinator(config, store);

        AtomicReference<String> handled = new AtomicReference<>();
        gameLoop.setMasterTitleExitHandler(gameId -> {
            handled.set(gameId);
            assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
            assertEquals(true, config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
            assertEquals("s1", config.getString(SonicConfiguration.CROSS_GAME_SOURCE));
            assertEquals(400, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        });

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s2", false);

        assertEquals("s2", handled.get());
        assertEquals(MasterTitleScreen.GameEntry.SONIC_2, store.loadedEntry);
    }

    @Test
    public void programmaticMasterTitleExitClearsOverridesSkipsProfileAndResolvesBeforeHandler() throws Exception {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();
        config.setSessionOverride(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        config.setSessionOverride(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        config.setSessionOverride(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();

        TrackingLaunchProfileStore store = new TrackingLaunchProfileStore(config,
                new LaunchProfile(true, "s3k", true, "WIDE_16_9", "knuckles", "none"));
        installLaunchCoordinator(config, store);

        gameLoop.setMasterTitleExitHandler(gameId -> {
            assertEquals("s3k", gameId);
            assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            assertEquals("tails", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
            assertFalse(config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
            assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        });

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s3k", true);

        assertNull(store.loadedEntry, "programmatic launch must not load/apply a launch profile");
    }

    @Test
    public void programmaticLaunchAfterUserLaunchSeesBaseMapValuesNotPreviousSessionProfile() throws Exception {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        TrackingLaunchProfileStore store = new TrackingLaunchProfileStore(config,
                new LaunchProfile(true, "s1", true, "WIDE_16_9", "sonic", "none"));
        installLaunchCoordinator(config, store);

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s2", false);
        assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertEquals(400, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));

        gameLoop.setMasterTitleExitHandler(gameId -> {
            assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            assertEquals("tails", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
            assertFalse(config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
            assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        });

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s1", true);
    }

    @Test
    public void failedMasterTitleExitRestoresMasterTitleAndClearsLaunchProfileState() throws Exception {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();

        TrackingLaunchProfileStore store = new TrackingLaunchProfileStore(config,
                new LaunchProfile(true, "s1", true, "WIDE_16_9", "knuckles", "none"));
        MasterTitleLaunchCoordinator coordinator = installLaunchCoordinator(config, store);
        SessionManager.clear();
        gameLoop.setGameplayMode(null);
        setPrivateField(gameLoop, "currentGameMode", GameMode.MASTER_TITLE_SCREEN);
        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "fadeManager", fadeManager);
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        coordinator.setPendingLaunchCallback(() -> callbackRan.set(true));

        gameLoop.setMasterTitleExitHandler(gameId -> {
            assertEquals("s2", gameId);
            assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE),
                    "profile is sanitized and applied before the engine attempts startup");
            gameLoop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        });

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s2", false);
        invokePrivateMethod(gameLoop, "runAfterStepMasterTitleLaunchCallbackIfPresent");

        assertEquals(GameMode.MASTER_TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("tails", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertFalse(config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertFalse(callbackRan.get(), "failed startup must not run the staged trace launch callback");
        assertNull(getPrivateField(coordinator, "pendingLaunchCallback"));
        assertNull(getPrivateField(coordinator, "afterStepLaunchCallback"));
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    public void successfulDirectGameplayExitCanFallbackFromMasterTitleToLevel() throws Exception {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.resolveDisplayAspect();

        TrackingLaunchProfileStore store = new TrackingLaunchProfileStore(config,
                new LaunchProfile(true, "s1", true, "WIDE_16_9", "sonic", "none"));
        MasterTitleLaunchCoordinator coordinator = installLaunchCoordinator(config, store);
        SessionManager.clear();
        gameLoop.setGameplayMode(null);
        setPrivateField(gameLoop, "currentGameMode", GameMode.MASTER_TITLE_SCREEN);
        AtomicBoolean callbackRan = new AtomicBoolean(false);
        coordinator.setPendingLaunchCallback(() -> callbackRan.set(true));

        gameLoop.setMasterTitleExitHandler(gameId -> {
            assertEquals("s2", gameId);
            gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());
        });

        invokePrivateMethod(gameLoop, "doExitMasterTitleScreen",
                new Class<?>[] {String.class, boolean.class}, "s2", false);

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        assertEquals("sonic", config.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));
        assertEquals(true, config.getBoolean(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED));
        assertEquals("s1", config.getString(SonicConfiguration.CROSS_GAME_SOURCE));
        assertEquals(400, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertFalse(callbackRan.get(), "callback should be staged until the end-of-step hook runs");

        invokePrivateMethod(gameLoop, "runAfterStepMasterTitleLaunchCallbackIfPresent");

        assertTrue(callbackRan.get(), "successful direct gameplay launch should keep callback staging");
        assertNull(getPrivateField(coordinator, "pendingLaunchCallback"));
        assertNull(getPrivateField(coordinator, "afterStepLaunchCallback"));
    }

    @Test
    public void returnToMasterTitleClearsSessionOverridesAndResolvesBeforeHandler() {
        SonicConfigurationService config = EngineServices.current().configuration();
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        config.setSessionOverride(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        config.resolveDisplayAspect();

        gameLoop.setReturnToMasterTitleHandler(() -> {
            assertFalse(config.hasSessionOverride(SonicConfiguration.DISPLAY_ASPECT));
            assertEquals("NATIVE_4_3", config.getString(SonicConfiguration.DISPLAY_ASPECT));
            assertEquals(320, config.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        });

        gameLoop.returnToMasterTitle();
    }

    @Test
    public void testSetGameplayModeNullClearsCachedFadeManagerSoBootstrapTakesOver() throws Exception {
        // Regression: trace test mode left GameLoop holding a reference to
        // the destroyed gameplay mode's FadeManager after teardown, so the next
        // master-title fade-to-black ran on an orphaned manager that the UI
        // pipeline never ticked — leaving the user stuck on the master title.
        FadeManager gameplayFade = (FadeManager) getPrivateField(gameLoop, "fadeManager");
        assertNotNull(gameplayFade, "Test setup binds a gameplay mode, so fadeManager should be cached");

        SessionManager.clear();
        gameLoop.setGameplayMode(null);

        assertNull(getPrivateField(gameLoop, "fadeManager"),
                "After gameplay mode teardown the cached FadeManager must be cleared");
        assertNull(getPrivateField(gameLoop, "gameplayMode"),
                "After gameplay mode teardown the cached gameplay mode must be cleared");

        FadeManager bootstrapFade = EngineServices.current().graphics().getFadeManager();
        Method resolve = GameLoop.class.getDeclaredMethod("resolveFadeManager");
        resolve.setAccessible(true);
        assertSame(bootstrapFade, resolve.invoke(gameLoop),
                "resolveFadeManager must fall back to the bootstrap FadeManager once gameplay is gone");
    }

    @Test
    public void testSetGameplayModeRejectsTornDownContextWithNonNullManagers() throws Exception {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        gameLoop.setGameplayMode(gameplayMode);
        assertNotNull(getPrivateField(gameLoop, "camera"),
                "Test setup should bind runtime managers before teardown");

        gameplayMode.tearDownManagers();
        gameLoop.setGameplayMode(gameplayMode);

        assertNull(getPrivateField(gameLoop, "gameplayMode"),
                "Torn-down gameplay contexts must not stay cached even if manager fields remain non-null");
        assertNull(getPrivateField(gameLoop, "camera"),
                "Torn-down gameplay contexts must not rebind stale camera references");
        assertNull(getPrivateField(gameLoop, "fadeManager"),
                "Torn-down gameplay contexts must not rebind stale fade managers");
    }

    @Test
    public void testModuleScopedTitleCardProviderCanBeResetAfterModuleSwitch() {
        GameModule module1 = new Sonic1GameModule();
        TitleCardProvider provider1 = module1.getTitleCardProvider();
        TestEnvironment.configureGameModuleFixture(module1);

        GameLoop loop = new GameLoop(mockInputHandler);
        assertSame(provider1, loop.getTitleCardProvider());

        GameModule module2 = new Sonic2GameModule();
        TitleCardProvider provider2 = module2.getTitleCardProvider();
        TestEnvironment.configureGameModuleFixture(module2);

        loop.resetModuleScopedProviders();

        assertSame(provider2, loop.getTitleCardProvider(),
                "module reset must clear cached title-card provider so the new module supplies it");
    }

    @Test
    void traceReplayActivationPreservesPresentationAndRetainedModuleState() {
        Sonic2GameModule module = spy(new Sonic2GameModule());
        @SuppressWarnings("unchecked")
        RewindSnapshottable<Object> retained = mock(RewindSnapshottable.class);
        when(retained.key()).thenReturn("test-retained-plc");
        when(module.rewindAdapters()).thenReturn(List.of(retained));
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(module.getTitleCardProvider()).thenReturn(titleCard);
        TestEnvironment.configureGameModuleFixture(module);
        GameLoop loop = new GameLoop(mockInputHandler);
        assertSame(titleCard, loop.getTitleCardProvider());
        GameplayModeContext presentation =
                SessionManager.getCurrentGameplayMode();
        presentation.dynamicArtLifecycle().observeRamDplc(
                "sonic", 1,
                List.of(new com.openggf.level.render.TileLoadRequest(0, 1)),
                0x1000, 0xF000);
        assertFalse(presentation.dynamicArtLifecycle()
                .capture().ledger().isEmpty());
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE,
                presentation.hardwareTiming().admissionPolicy());

        GameplayModeContext replay = presentation;
        replay.activateRecordedHardwareAdmission();

        assertSame(presentation, replay);
        assertTrue(presentation.isGameplayRuntimeReady());
        verify(titleCard, never()).reset();
        verify(retained, never()).resetForMissingSnapshot();
        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                replay.hardwareTiming().admissionPolicy());
        assertTrue(replay.isGameplayRuntimeReady());
        assertFalse(replay.dynamicArtLifecycle().capture().ledger().isEmpty(),
                "production dynamic-art state must remain in the same context");
        assertSame(replay, SessionManager.getCurrentGameplayMode());
    }

    // ==================== Game Mode Listener Tests ====================

    @Test
    public void testGameModeChangeListenerReceivesOldAndNewModes() {
        GameLoop.GameModeChangeListener listener = mock(GameLoop.GameModeChangeListener.class);
        gameLoop.setGameModeChangeListener(listener);

        gameLoop.setGameMode(GameMode.DATA_SELECT);
        gameLoop.setGameMode(GameMode.LEVEL);

        InOrder notifications = inOrder(listener);
        notifications.verify(listener).onGameModeChanged(GameMode.LEVEL, GameMode.DATA_SELECT);
        notifications.verify(listener).onGameModeChanged(GameMode.DATA_SELECT, GameMode.LEVEL);
        verifyNoMoreInteractions(listener);
    }

    // ==================== Mode Transition Guard Tests ====================

    @Test
    public void testGameModeStartsInLevelMode() {
        // When starting, should be in LEVEL mode
        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode(), "Should be in LEVEL mode");

        // Verify no results screen is active
        assertNull(gameLoop.getResultsScreen(), "Results screen should be null initially");
    }

    @Test
    public void testResolveBonusStageDebugShortcutShiftB() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertEquals(BonusStageType.GUMBALL, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageDebugShortcutCtrlB() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertEquals(BonusStageType.GLOWING_SPHERE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageDebugShortcutAltB() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_ALT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertEquals(BonusStageType.SLOT_MACHINE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageDebugShortcutRequiresExactlyOneModifier() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertEquals(BonusStageType.NONE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageDebugShortcutIgnoresPlainB() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);

        assertEquals(BonusStageType.NONE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    /**
     * Shift and Ctrl already answered from the logical override here; Alt now
     * answers from the same source, so the shortcut cannot pick a stage from a
     * mixture of recorded and live modifiers.
     *
     * <p>No production caller evaluates this with an override installed today --
     * {@code stepInternal:994} reaches it before any override window opens, and
     * every window (LiveRewindStepper, RecordingFrameDriver, TraceSessionLauncher)
     * both opens and closes later in the same frame. So this pins the contract,
     * not a reachable defect. The reachable reader of the same override is
     * {@code updateSpecialStageInput()}, inside TraceSessionLauncher's window.
     */
    @Test
    public void testResolveBonusStageDebugShortcutReadsAltFromTheLogicalOverride() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);
        handler.setLogicalOverride(LogicalInputSnapshot.neutral()
                .withDebugInput(false, false, false, true, false));

        assertEquals(BonusStageType.SLOT_MACHINE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageDebugShortcutIgnoresLiveAltWhileAnOverrideIsInstalled() {
        InputHandler handler = new InputHandler();
        handler.handleKeyEvent(GLFW_KEY_LEFT_ALT, GLFW_PRESS);
        handler.handleKeyEvent(GLFW_KEY_B, GLFW_PRESS);
        handler.setLogicalOverride(LogicalInputSnapshot.neutral()
                .withDebugInput(false, false, false, false, false));

        assertEquals(BonusStageType.NONE, GameLoopDebugShortcuts.resolveBonusStageDebugShortcut(handler));
    }

    @Test
    public void testResolveBonusStageBootstrapSpawnForPachinko() {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());

        ObjectSpawn spawn = GameLoop.resolveBonusStageBootstrapSpawn(BonusStageType.GLOWING_SPHERE);

        assertNotNull(spawn);
        assertEquals(0x78, spawn.x());
        assertEquals(0x0F30, spawn.y());
        assertEquals(Sonic3kObjectIds.PACHINKO_ENERGY_TRAP, spawn.objectId());
    }

    @Test
    public void testResolveBonusStageBootstrapSpawnOnlyForPachinko() {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());

        assertNull(GameLoop.resolveBonusStageBootstrapSpawn(BonusStageType.GUMBALL));
        assertNull(GameLoop.resolveBonusStageBootstrapSpawn(BonusStageType.SLOT_MACHINE));
        assertNull(GameLoop.resolveBonusStageBootstrapSpawn(BonusStageType.NONE));
    }

    @Test
    public void testExitTitleCardAppliesDeferredBonusStageSetupWithoutSavedState() throws Exception {
        BonusStageProvider provider = mock(BonusStageProvider.class);

        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_CARD);
        setPrivateField(gameLoop, "postTitleCardDestination",
                Enum.valueOf(PostTitleCardDestination.class, "BONUS_STAGE"));
        setPrivateField(gameLoop, "deferredBonusProvider", provider);
        setPrivateField(gameLoop, "deferredBonusType", BonusStageType.SLOT_MACHINE);
        setPrivateField(gameLoop, "deferredBonusState", null);

        invokePrivateMethod(gameLoop, "exitTitleCard");

        verify(provider).onDeferredSetupComplete();
        assertNull(getPrivateField(gameLoop, "deferredBonusProvider"), "Deferred provider should be cleared after setup");
        assertNull(getPrivateField(gameLoop, "deferredBonusState"), "Deferred saved state should stay cleared after setup");
        assertEquals(GameMode.BONUS_STAGE, gameLoop.getCurrentGameMode(), "GameLoop should switch to bonus stage mode");
    }

    @Test
    public void testBonusStageExitFadeIsNotRestartedAfterProviderCompletedFade() {
        BonusStageProvider provider = mock(BonusStageProvider.class);
        when(provider.hasCompletedExitFadeToBlack()).thenReturn(true);

        assertFalse(GameLoop.shouldStartBonusStageExitFade(provider), "provider-owned GOAL fade should be reused instead of starting a second generic fade");
    }

    @Test
    void testExitDataSelectDispatchesPendingAction() throws Exception {
        SessionManager.clear();

        StubDataSelectProvider provider = new StubDataSelectProvider(new DataSelectAction(
                DataSelectActionType.NEW_SLOT_START, 2, 0, 0,
                new SelectedTeam("sonic", List.of("tails"))));
        GameModule module = neutralGameModule();
        when(module.getDataSelectProvider()).thenReturn(provider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();

        AtomicReference<DataSelectAction> handled = new AtomicReference<>();
        gameLoop.setDataSelectActionHandler(handled::set);
        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "fadeManager", fadeManager);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());

        invokePrivateMethod(gameLoop, "exitDataSelect");

        assertNotNull(fadeCallback.get());
        fadeCallback.get().run();
        assertNotNull(handled.get());
        assertEquals(DataSelectActionType.NEW_SLOT_START, handled.get().type());
        assertEquals(2, handled.get().slot());
        assertEquals(new SelectedTeam("sonic", List.of("tails")), handled.get().team());
    }

    @Test
    void testExitDataSelectDispatchesPendingActionFromNativeS3kPresentationProvider() throws Exception {
        SessionManager.clear();

        Sonic3kGameModule module = new Sonic3kGameModule();
        DataSelectProvider provider = module.getDataSelectProvider();
        assertInstanceOf(DataSelectPresentationProvider.class, provider);
        assertInstanceOf(S3kDataSelectManager.class, ((DataSelectPresentationProvider) provider).delegate(),
                "S3K data select should resolve to the native S3K manager");
        ((DataSelectPresentationProvider) provider).controller().queuePendingAction(new DataSelectAction(
                DataSelectActionType.NEW_SLOT_START, 3, 0, 0,
                new SelectedTeam("sonic", List.of("tails"))));

        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        AtomicReference<DataSelectAction> handled = new AtomicReference<>();
        gameLoop.setDataSelectActionHandler(handled::set);
        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "fadeManager", fadeManager);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());

        invokePrivateMethod(gameLoop, "exitDataSelect");

        assertNotNull(fadeCallback.get());
        fadeCallback.get().run();
        assertNotNull(handled.get(), "Wrapped S3K presentation should still dispatch queued actions");
        assertEquals(DataSelectActionType.NEW_SLOT_START, handled.get().type());
        assertEquals(3, handled.get().slot());
    }

    @Test
    void testExitDataSelectStartsFadeBeforeDispatchingGameplayAction() throws Exception {
        SessionManager.clear();

        StubDataSelectProvider provider = new StubDataSelectProvider(new DataSelectAction(
                DataSelectActionType.LOAD_SLOT, 2, 3, 1,
                new SelectedTeam("tails", List.of())));
        GameModule module = neutralGameModule();
        when(module.getDataSelectProvider()).thenReturn(provider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();

        AtomicReference<DataSelectAction> handled = new AtomicReference<>();
        gameLoop.setDataSelectActionHandler(handled::set);

        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());

        invokePrivateMethod(gameLoop, "exitDataSelect");

        assertNull(handled.get(), "gameplay action should wait for fade completion");
        verify(fadeManager).startFadeToBlack(any());
        assertNotNull(fadeCallback.get(), "fade callback should capture the deferred Data Select launch");
        assertEquals(com.openggf.game.DataSelectProvider.State.EXITING, provider.getState(),
                "Data Select should stay in its exiting state until fade completion");

        fadeCallback.get().run();

        assertNotNull(handled.get());
        assertEquals(DataSelectActionType.LOAD_SLOT, handled.get().type());
        assertEquals(2, handled.get().slot());
        verify(fadeManager).startFadeFromBlack(isNull(), eq(0));
        assertEquals(com.openggf.game.DataSelectProvider.State.INACTIVE, provider.getState(),
                "Data Select should reset only after the fade callback runs");
    }

    @Test
    void testExitDataSelectRunsHostTransitionAudioBeforeTheRomVisualFade() throws Exception {
        DataSelectExitTransition transition = new DataSelectExitTransition(0xAF, 0x28, 6, 1);
        StubDataSelectProvider provider = new StubDataSelectProvider(new DataSelectAction(
                DataSelectActionType.LOAD_SLOT, 2, 0, 0,
                new SelectedTeam("sonic", List.of())), transition);
        GameModule module = neutralGameModule();
        when(module.getDataSelectProvider()).thenReturn(provider);
        SessionManager.openGameplaySession(module);

        AudioManager audio = mock(AudioManager.class);
        FadeManager fade = mock(FadeManager.class);
        setPrivateField(gameLoop, "audioManager", audio);
        setPrivateField(gameLoop, "fadeManager", fade);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fade).startFadeToBlack(any());
        gameLoop.setDataSelectActionHandler(action -> { });

        invokePrivateMethod(gameLoop, "exitDataSelect");

        InOrder order = inOrder(audio, fade);
        order.verify(audio).playSfx(0xAF);
        order.verify(audio).fadeOutMusic(0x28, 6);
        order.verify(fade).startFadeToBlack(any());

        assertNotNull(fadeCallback.get());
        fadeCallback.get().run();
        verify(fade).startFadeFromBlack(isNull(), eq(1));
    }

    @Test
    void testExitDataSelectRestoresScreenWhenGameplayLaunchFailsAfterFade() throws Exception {
        SessionManager.clear();

        StubDataSelectProvider provider = new StubDataSelectProvider(new DataSelectAction(
                DataSelectActionType.LOAD_SLOT, 2, 3, 1,
                new SelectedTeam("tails", List.of())));
        GameModule module = neutralGameModule();
        when(module.getDataSelectProvider()).thenReturn(provider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();

        AtomicReference<DataSelectAction> handled = new AtomicReference<>();
        gameLoop.setDataSelectActionHandler(action -> {
            handled.set(action);
            gameLoop.setGameMode(GameMode.LEVEL);
            throw new RuntimeException("save loader failed");
        });

        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "fadeManager", fadeManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.DATA_SELECT);

        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());

        invokePrivateMethod(gameLoop, "exitDataSelect");

        assertNotNull(fadeCallback.get(), "gameplay launch should still wait for fade completion");
        assertDoesNotThrow(() -> fadeCallback.get().run(),
                "launch failures should be handled inside the fade callback");

        assertNotNull(handled.get());
        assertEquals(DataSelectActionType.LOAD_SLOT, handled.get().type());
        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode(),
                "failed launch should restore the Data Select mode even after partial gameplay setup");
        assertEquals(com.openggf.game.DataSelectProvider.State.ACTIVE, provider.getState(),
                "Data Select should remain active instead of resetting inactive on launch failure");
        assertEquals("Unable to load selected save.", provider.launchErrorMessage().orElseThrow());
        verify(fadeManager).startFadeFromBlack(isNull(), eq(0));
    }

    @Test
    void testExitDataSelectDoesNotResetWhileFadeAlreadyActive() throws Exception {
        SessionManager.clear();

        StubDataSelectProvider provider = new StubDataSelectProvider(new DataSelectAction(
                DataSelectActionType.LOAD_SLOT, 2, 3, 1,
                new SelectedTeam("tails", List.of())));
        GameModule module = neutralGameModule();
        when(module.getDataSelectProvider()).thenReturn(provider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();

        gameLoop.setDataSelectActionHandler(action -> fail("No dispatch should occur while fade is already active"));

        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(true);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitDataSelect");

        assertEquals(com.openggf.game.DataSelectProvider.State.EXITING, provider.getState(),
                "Provider should remain in EXITING while the fade is still active");
        verify(fadeManager, never()).startFadeToBlack(any());
    }

    @Test
    void testDoExitBonusStageDoesNotWriteSaveForActiveSlot() throws Exception {
        SessionManager.clear();

        String gameCode = "test_bonus_stage_return";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        GameModule module = neutralGameModule();
        when(module.getSaveSnapshotProvider()).thenReturn((reason, ctx) -> Map.of("marker", "bonus"));
        when(module.getTitleCardProvider()).thenReturn(null);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of()), 0, 0);
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module, saveContext);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        when(levelManager.getCurrentLevelMusicId()).thenReturn(-1);
        when(levelManager.getCheckpointState()).thenReturn(null);
        when(levelManager.getFeatureZoneId()).thenReturn(0);
        when(levelManager.getFeatureActId()).thenReturn(0);

        com.openggf.sprites.managers.SpriteManager spriteManager = mock(com.openggf.sprites.managers.SpriteManager.class);
        when(spriteManager.getSprite(anyString())).thenReturn(null);
        when(spriteManager.getSidekicks()).thenReturn(List.of());

        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "spriteManager", spriteManager);
        setPrivateField(gameLoop, "camera", mock(com.openggf.camera.Camera.class));
        setPrivateField(gameLoop, "fadeManager", mock(FadeManager.class));

        BonusStageProvider provider = mock(BonusStageProvider.class);
        when(provider.getRewards()).thenReturn(BonusStageProvider.BonusStageRewards.none());
        BonusStageState savedState = new BonusStageState(
                0, 0, 0, 0, -1, 0, 0, 0,
                0, 0, 0, 0, (byte) 0, (byte) 0, 0, 0L, 0);

        Method method = GameLoop.class.getDeclaredMethod(
                "doExitBonusStage", BonusStageProvider.class, BonusStageState.class);
        method.setAccessible(true);
        method.invoke(gameLoop, provider, savedState);

        assertTrue(Files.notExists(saveDir.resolve("slot1.json")));
        deleteRecursively(saveDir);
    }

    @Test
    void testDoExitResultsScreenWritesSaveForActiveSlot() throws Exception {
        SessionManager.clear();

        String gameCode = "test_special_stage_return";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        GameModule module = neutralGameModule();
        when(module.getSaveSnapshotProvider()).thenReturn((reason, ctx) -> Map.of("marker", "special"));
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of()), 0, 0);
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        SessionManager.openGameplaySession(module, saveContext);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        when(levelManager.getCurrentLevel()).thenReturn(mock(com.openggf.level.AbstractLevel.class));
        when(levelManager.getCurrentZone()).thenReturn(0);
        when(levelManager.getCurrentAct()).thenReturn(0);
        when(levelManager.getCurrentLevelMusicId()).thenReturn(-1);
        when(levelManager.getCheckpointState()).thenReturn(null);
        when(levelManager.hasBigRingReturn()).thenReturn(false);

        com.openggf.sprites.managers.SpriteManager spriteManager = mock(com.openggf.sprites.managers.SpriteManager.class);
        when(spriteManager.getSprite(anyString())).thenReturn(null);
        when(spriteManager.getSidekicks()).thenReturn(List.of());

        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "spriteManager", spriteManager);
        setPrivateField(gameLoop, "camera", mock(com.openggf.camera.Camera.class));
        setPrivateField(gameLoop, "fadeManager", mock(FadeManager.class));
        setPrivateField(gameLoop, "resultsScreen", mock(com.openggf.game.ResultsScreen.class));
        setPrivateField(gameLoop, "currentGameMode", GameMode.SPECIAL_STAGE_RESULTS);

        invokePrivateMethod(gameLoop, "doExitResultsScreen");

        com.openggf.game.save.SessionSaveRequests.flushPendingSaves(); // in-game saves write off-frame
        assertTrue(Files.exists(saveDir.resolve("slot1.json")));
        deleteRecursively(saveDir);
    }

    @Test
    void testStepDoesNotWriteSaveForActiveSlotOnSeamlessTransition() throws Exception {
        SessionManager.clear();
        gameLoop.setGameplayMode(null);

        String gameCode = "test_seamless_transition";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        GameModule module = neutralGameModule();
        when(module.getSaveSnapshotProvider()).thenReturn((reason, ctx) -> Map.of("marker", "seamless"));
        when(module.getTitleCardProvider()).thenReturn(null);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of()), 0, 0);
        SessionManager.openGameplaySession(module, saveContext);

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        com.openggf.sprites.managers.SpriteManager spriteManager = mock(com.openggf.sprites.managers.SpriteManager.class);
        SeamlessLevelTransitionRequest request = SeamlessLevelTransitionRequest
                .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(1, 0)
                .build();
        when(levelManager.consumeSeamlessTransitionRequest()).thenReturn(request);
        when(levelManager.consumeInLevelTitleCardRequest()).thenReturn(false);

        bindMockGameplay(levelManager, spriteManager, mock(FadeManager.class));

        setPrivateField(gameLoop, "currentGameMode", GameMode.LEVEL);

        gameLoop.step();

        verify(levelManager).applySeamlessTransition(request);
        assertTrue(Files.notExists(saveDir.resolve("slot1.json")));
        deleteRecursively(saveDir);
    }

    @Test
    void testStepWritesSaveForS2CreditsTransition() throws Exception {
        SessionManager.clear();
        gameLoop.setGameplayMode(null);

        String gameCode = "test_s2_credits_transition";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        GameModule module = neutralGameModule();
        EndingProvider endingProvider = mock(EndingProvider.class);
        when(module.getSaveSnapshotProvider()).thenReturn((reason, ctx) -> Map.of("marker", "credits"));
        when(module.getEndingProvider()).thenReturn(endingProvider);
        when(endingProvider.saveReasonOnEndingStart()).thenReturn(
                java.util.Optional.of(com.openggf.game.save.SaveReason.PROGRESSION_SAVE));
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of()), 10, 0);
        SessionManager.openGameplaySession(module, saveContext);

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        com.openggf.sprites.managers.SpriteManager spriteManager = mock(com.openggf.sprites.managers.SpriteManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(levelManager.consumeSeamlessTransitionRequest()).thenReturn(null);
        when(levelManager.consumeInLevelTitleCardRequest()).thenReturn(false);
        when(levelManager.consumeTitleCardRequest()).thenReturn(false);
        when(levelManager.consumeRespawnRequest()).thenReturn(false);
        when(levelManager.consumeNextActRequest()).thenReturn(false);
        when(levelManager.consumeNextZoneRequest()).thenReturn(false);
        when(levelManager.consumeZoneActRequest()).thenReturn(false);
        when(levelManager.consumeCreditsRequest()).thenReturn(true);
        when(fadeManager.isActive()).thenReturn(false);

        bindMockGameplay(levelManager, spriteManager, fadeManager);

        setPrivateField(gameLoop, "currentGameMode", GameMode.LEVEL);
        setPrivateField(gameLoop, "audioManager", mock(com.openggf.audio.AudioManager.class));

        gameLoop.step();

        com.openggf.game.save.SessionSaveRequests.flushPendingSaves(); // in-game saves write off-frame
        assertTrue(Files.exists(saveDir.resolve("slot1.json")));
        deleteRecursively(saveDir);
    }

    @Test
    void testDoEnterEndingDoesNotWriteSaveForActiveSlot() throws Exception {
        SessionManager.clear();

        String gameCode = "test_ending_clear";
        Path saveDir = Path.of("saves").resolve(gameCode);
        deleteRecursively(saveDir);

        EndingProvider endingProvider = mock(EndingProvider.class);
        when(endingProvider.getCurrentPhase()).thenReturn(EndingPhase.CUTSCENE);

        GameModule module = neutralGameModule();
        when(module.getEndingProvider()).thenReturn(endingProvider);
        when(module.getSaveSnapshotProvider()).thenReturn(
                (reason, ctx) -> Map.of("clear", ctx.saveSessionContext().isClear(), "marker", "ending"));
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                gameCode, 1, new SelectedTeam("sonic", List.of()), 0, 0);
        SessionManager.openGameplaySession(module, saveContext);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        invokePrivateMethod(gameLoop, "doEnterEnding");

        Path slotFile = saveDir.resolve("slot1.json");
        assertTrue(Files.notExists(slotFile));
        assertFalse(saveContext.isClear());
        verify(endingProvider).initialize();
        deleteRecursively(saveDir);
    }

    @Test
    void doEnterEndingStartsRevealWhenPreviousFadeIsHoldingWhite() throws Exception {
        SessionManager.clear();

        EndingProvider endingProvider = mock(EndingProvider.class);
        when(endingProvider.getCurrentPhase()).thenReturn(EndingPhase.CUTSCENE);

        GameModule module = neutralGameModule();
        when(module.getEndingProvider()).thenReturn(endingProvider);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);

        SaveSessionContext saveContext = SaveSessionContext.forSlot(
                "test_ending_hold_white_reveal", 1, new SelectedTeam("sonic", List.of()), 10, 0);
        SessionManager.openGameplaySession(module, saveContext);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(true);
        when(fadeManager.getState()).thenReturn(FadeManager.FadeState.HOLD_WHITE);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "doEnterEnding");

        verify(endingProvider).initialize();
        verify(fadeManager).startFadeFromWhite(any());
        assertEquals(GameMode.ENDING_CUTSCENE, gameLoop.getCurrentGameMode());
    }

    @Test
    void testDoExitTitleScreenRoutesOnePlayerToNativeDataSelect() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        assertEquals(1, nativeDelegate.initializeCalls);
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testDoExitTitleScreenRoutesS1OnePlayerToDonatedDataSelectWhenPresentationResolvesToS3k() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_SOURCE, "s3k");

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                S3kDataSelectManager::new,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S1);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        assertInstanceOf(S3kDataSelectManager.class, dataSelect.delegate(),
                "Donated S1 data select should resolve to the native S3K manager");

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testDoExitTitleScreenRoutesS2OnePlayerToDonatedDataSelectWhenPresentationResolvesToS3k() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                S3kDataSelectManager::new,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        assertInstanceOf(S3kDataSelectManager.class, dataSelect.delegate(),
                "Donated S2 data select should resolve to the native S3K manager");

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testDoExitTitleScreenRoutesTwoPlayerAwayFromDataSelect() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.TWO_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        assertEquals(0, nativeDelegate.initializeCalls);
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
    }

    @Test
    void testDoExitTitleScreenRoutesToLevelWhenPresentationIsNotS3k() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        com.openggf.game.TitleScreenProvider titleScreen = mock(com.openggf.game.TitleScreenProvider.class);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(titleScreen).reset();
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        assertTrue(dataSelect.isActive(), "Provider presence alone should not trigger Data Select");
    }

    @Test
    void testDoExitTitleScreenDoesNotUseGenericFadeToBlackWhenRoutingToLevel() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        com.openggf.game.TitleScreenProvider titleScreen = mock(com.openggf.game.TitleScreenProvider.class);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(titleScreen).reset();
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager, never()).startFadeToBlack(any());
    }

    @Test
    void testDoExitTitleScreenStartsFadeFromBlackWhenRoutingToLevel() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        com.openggf.game.TitleScreenProvider titleScreen = mock(com.openggf.game.TitleScreenProvider.class);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(titleScreen).reset();
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    void testRealSonic2TitleScreenAdvertisesOnePlayerExitAction() {
        Sonic2GameModule module = new Sonic2GameModule();

        assertEquals(TitleScreenAction.ONE_PLAYER,
                module.getTitleScreenProvider().consumeExitAction());
    }

    @Test
    void testRealSonic1TitleScreenAdvertisesOnePlayerExitAction() {
        Sonic1GameModule module = new Sonic1GameModule();

        assertEquals(TitleScreenAction.ONE_PLAYER,
                module.getTitleScreenProvider().consumeExitAction());
    }

    @Test
    void testTitleScreenExitHandlerUsesExplicitRouteResolutionWithoutStartingSecondFade() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenProvider.TitleScreenAction.OPTIONS);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        gameLoop.getTitleScreenProvider();
        titleScreen.triggerExitHandler();

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager, never()).startFadeToBlack(any());
        assertTrue(dataSelect.isActive(), "Explicit route resolution should prevent OPTIONS from entering Data Select");
    }

    @Test
    void testTitleScreenExitHandlerEntersDataSelectThroughBlackFade() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_SOURCE, "s3k");

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        gameLoop.getTitleScreenProvider();
        titleScreen.triggerExitHandler();

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        assertEquals(0, nativeDelegate.initializeCalls);

        fadeCallback.get().run();

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        assertEquals(1, nativeDelegate.initializeCalls);
        verify(fadeManager).startFadeFromBlack(any());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testTitleScreenExitHandlerRoutesLevelThroughBlackFadeWhenDonationDisabled() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S1);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        gameLoop.getTitleScreenProvider();
        titleScreen.triggerExitHandler();

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());

        fadeCallback.get().run();

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    void testExitTitleScreenRoutesLevelWithoutGenericFade() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_SOURCE, "s3k");

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.OPTIONS);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager, never()).startFadeToBlack(any());
    }

    @Test
    void testExitTitleScreenRoutesLevelThroughBlackFadeWhenDonationDisabled() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitTitleScreen");

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());

        fadeCallback.get().run();

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    void testExitTitleScreenDoesNotRouteToDonatedDataSelectWhenCrossGameFeaturesDisabled() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, true);
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_SOURCE, "s3k");
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S1);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitTitleScreen");

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
        assertEquals(0, nativeDelegate.initializeCalls);

        fadeCallback.get().run();

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    void testExitTitleScreenRoutesDataSelectThroughBlackFade() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitTitleScreen");

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        verify(fadeManager).startFadeToBlack(any());
        assertEquals(0, nativeDelegate.initializeCalls);

        fadeCallback.get().run();

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        assertEquals(1, nativeDelegate.initializeCalls);
        verify(fadeManager).startFadeFromBlack(any());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testExitTitleScreenDoesNotStartS2PreviewWarmupWhenRoutingToDonatedDataSelect() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        var warmupManager = mock(com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager.class,
                withSettings().extraInterfaces(Sonic2GameModule.S2DataSelectImageWarmup.class));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S2);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        when(module.getGameService(com.openggf.game.sonic2.dataselect.S2DataSelectImageCacheManager.class))
                .thenReturn(warmupManager);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        try (var donor = mockStatic(com.openggf.game.CrossGameFeatureProvider.class)) {
            donor.when(com.openggf.game.CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);

            invokePrivateMethod(gameLoop, "exitTitleScreen");

            assertNotNull(fadeCallback.get());
            assertEquals(0, nativeDelegate.initializeCalls);
            verify((Sonic2GameModule.S2DataSelectImageWarmup) warmupManager, never()).ensureGenerationStarted();

            fadeCallback.get().run();

            assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
            assertEquals(1, nativeDelegate.initializeCalls);
            verify((Sonic2GameModule.S2DataSelectImageWarmup) warmupManager, never()).ensureGenerationStarted();
        }

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        assertEquals(1, nativeDelegate.initializeCalls);
        verify(fadeManager).startFadeFromBlack(any());
    }

    @Test
    void testExitTitleScreenDoesNotRestartDataSelectFadeWhileActive() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false, true);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        invokePrivateMethod(gameLoop, "exitTitleScreen");
        invokePrivateMethod(gameLoop, "exitTitleScreen");

        verify(fadeManager, times(1)).startFadeToBlack(any());
        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertEquals(0, nativeDelegate.initializeCalls);
    }

    @Test
    void testTitleScreenExitHandlerRoutesDataSelectThroughBlackFade() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        TrackingNativeDataSelectProvider nativeDelegate = new TrackingNativeDataSelectProvider();
        DataSelectPresentationProvider dataSelect = new DataSelectPresentationProvider(
                nativeDelegate,
                new DataSelectSessionController(new S3kDataSelectProfile()));
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        FadeManager fadeManager = mock(FadeManager.class);
        when(fadeManager.isActive()).thenReturn(false);
        AtomicReference<Runnable> fadeCallback = new AtomicReference<>();
        doAnswer(invocation -> {
            fadeCallback.set(invocation.getArgument(0));
            return null;
        }).when(fadeManager).startFadeToBlack(any());
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", fadeManager);

        GameModuleRegistry.setCurrent(module);
        gameLoop.getTitleScreenProvider();
        titleScreen.triggerExitHandler();

        assertEquals(GameMode.TITLE_SCREEN, gameLoop.getCurrentGameMode());
        assertNotNull(fadeCallback.get());
        assertEquals(0, nativeDelegate.initializeCalls);

        fadeCallback.get().run();

        assertEquals(GameMode.DATA_SELECT, gameLoop.getCurrentGameMode());
        assertEquals(1, nativeDelegate.initializeCalls);
        verify(fadeManager).startFadeFromBlack(any());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
    }

    @Test
    void testDoExitTitleScreenDefaultsUnknownActionToOtherInsteadOfDataSelect() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);

        com.openggf.game.TitleScreenProvider titleScreen = mock(com.openggf.game.TitleScreenProvider.class);
        StubDataSelectProvider dataSelect = new StubDataSelectProvider(DataSelectAction.none());
        GameModule module = neutralGameModule();
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getDataSelectProvider()).thenReturn(dataSelect);
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S3K);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S3K);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);

        invokePrivateMethod(gameLoop, "doExitTitleScreen");

        assertEquals(GameMode.LEVEL, gameLoop.getCurrentGameMode());
        verify(levelManager).loadZoneAndActForFreshRuntime(0, 0);
        assertTrue(dataSelect.isActive(), "Unknown title actions must fail closed instead of entering Data Select");
    }

    @Test
    void testTitleScreenExitHandlerUsesOverlayPathWhenLevelSelectOverlayApplies() throws Exception {
        SessionManager.clear();
        com.openggf.configuration.SonicConfigurationService.getInstance()
                .setConfigValue(com.openggf.configuration.SonicConfiguration.LEVEL_SELECT_ON_STARTUP, true);

        StubTitleScreenProvider titleScreen = new StubTitleScreenProvider(TitleScreenAction.ONE_PLAYER);
        titleScreen.supportsLevelSelectOverlay = true;
        GameModule module = neutralGameModule();
        com.openggf.game.LevelSelectProvider levelSelect = mock(com.openggf.game.LevelSelectProvider.class);
        when(module.getTitleScreenProvider()).thenReturn(titleScreen);
        when(module.getLevelSelectProvider()).thenReturn(levelSelect);
        when(module.getDataSelectProvider()).thenReturn(new StubDataSelectProvider(DataSelectAction.none()));
        when(module.getGameId()).thenReturn(com.openggf.game.GameId.S1);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        SessionManager.openGameplaySession(module);
        gameLoop.setGameplayMode(TestEnvironment.activeGameplayMode());

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        setPrivateField(gameLoop, "levelManager", levelManager);
        setPrivateField(gameLoop, "currentGameMode", GameMode.TITLE_SCREEN);
        setPrivateField(gameLoop, "fadeManager", mock(FadeManager.class));

        GameModuleRegistry.setCurrent(module);
        gameLoop.getTitleScreenProvider();
        titleScreen.triggerExitHandler();

        assertEquals(GameMode.LEVEL_SELECT, gameLoop.getCurrentGameMode());
        verify(levelManager, never()).loadZoneAndActForFreshRuntime(anyInt(), anyInt());
        verify(levelSelect).initializeFromTitleScreen();
        verify(levelSelect, never()).initialize();
        verify((FadeManager) getPrivateField(gameLoop, "fadeManager"), never()).startFadeToBlack(any());
    }

    private MasterTitleLaunchCoordinator installLaunchCoordinator(SonicConfigurationService config,
                                                                  TrackingLaunchProfileStore store)
            throws Exception {
        MasterTitleLaunchCoordinator coordinator = new MasterTitleLaunchCoordinator(
                config, store, new LaunchProfileApplier(config));
        setPrivateField(gameLoop, "masterTitleLaunchCoordinator", coordinator);
        return coordinator;
    }

    private static void invokePrivateMethod(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static void invokePrivateMethod(Object target, String methodName, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        method.invoke(target, args);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private void bindMockGameplay(com.openggf.level.LevelManager levelManager,
                                  com.openggf.sprites.managers.SpriteManager spriteManager,
                                  FadeManager fadeManager) {
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        gameplayMode.tearDownManagers();
        when(fadeManager.key()).thenReturn("fademanager");
        when(fadeManager.capture()).thenReturn(new com.openggf.graphics.FadeManagerSnapshot(
                FadeManager.FadeState.NONE,
                0,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                FadeManager.FadeType.BLACK,
                0,
                0,
                1,
                0.0f,
                0,
                false,
                false));
        when(spriteManager.rewindSnapshottable()).thenReturn(
                new com.openggf.game.rewind.RewindSnapshottable<com.openggf.game.rewind.snapshot.SpriteManagerSnapshot>() {
            @Override
            public String key() {
                return "sprites";
            }

            @Override
            public com.openggf.game.rewind.snapshot.SpriteManagerSnapshot capture() {
                return new com.openggf.game.rewind.snapshot.SpriteManagerSnapshot(
                        0,
                        new com.openggf.game.rewind.snapshot.SpriteManagerSnapshot.SpriteEntry[0]);
            }

            @Override
            public void restore(com.openggf.game.rewind.snapshot.SpriteManagerSnapshot snapshot) {
            }
        });
        gameplayMode.attachGameplayManagers(
                new com.openggf.camera.Camera(),
                new com.openggf.timer.TimerManager(),
                new com.openggf.game.GameStateManager(),
                fadeManager,
                new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());
        gameplayMode.attachLevelManagers(
                new com.openggf.level.WaterSystem(),
                new com.openggf.level.ParallaxManager(),
                mock(com.openggf.physics.TerrainCollisionManager.class),
                mock(com.openggf.physics.CollisionSystem.class),
                spriteManager,
                levelManager);
        gameplayMode.attachSharedRegistries(
                new com.openggf.game.zone.ZoneRuntimeRegistry(),
                new com.openggf.game.palette.PaletteOwnershipRegistry(),
                new com.openggf.game.animation.AnimatedTileChannelGraph(),
                new com.openggf.game.render.SpecialRenderEffectRegistry(),
                new com.openggf.game.render.AdvancedRenderModeController(),
                new com.openggf.game.mutation.ZoneLayoutMutationPipeline());
        gameLoop.setGameplayMode(gameplayMode);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> ownerClass, String nestedTypeName, String constantName)
            throws Exception {
        Class<?> nestedType = Class.forName(ownerClass.getName() + "$" + nestedTypeName);
        return Enum.valueOf((Class<? extends Enum>) nestedType.asSubclass(Enum.class), constantName);
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static final class StubDataSelectProvider extends com.openggf.game.dataselect.AbstractDataSelectProvider {
        private final DataSelectExitTransition exitTransition;

        private StubDataSelectProvider(DataSelectAction action) {
            this(action, DataSelectExitTransition.defaultTransition());
        }

        private StubDataSelectProvider(DataSelectAction action, DataSelectExitTransition exitTransition) {
            this.pendingAction = action;
            this.state = State.EXITING;
            this.exitTransition = exitTransition;
        }

        @Override
        public DataSelectExitTransition exitTransition() {
            return exitTransition;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void update(InputHandler input) {
        }

        @Override
        public void draw() {
        }

        @Override
        public void setClearColor() {
        }

        @Override
        public void reset() {
            state = State.INACTIVE;
            pendingAction = DataSelectAction.none();
        }

        @Override
        public State getState() {
            return state;
        }

        @Override
        public boolean isExiting() {
            return state == State.EXITING;
        }

        @Override
        public boolean isActive() {
            return state != State.INACTIVE;
        }
    }

    private static final class StubTitleScreenProvider implements TitleScreenProvider {
        private final TitleScreenAction exitAction;
        private boolean supportsLevelSelectOverlay;
        private boolean exiting;
        private Runnable exitHandler = () -> {};

        private StubTitleScreenProvider(TitleScreenAction exitAction) {
            this.exitAction = exitAction;
        }

        private void triggerExitHandler() {
            exitHandler.run();
        }

        private void setExiting(boolean exiting) {
            this.exiting = exiting;
        }

        @Override
        public TitleScreenAction consumeExitAction() {
            return exitAction;
        }

        @Override
        public boolean supportsLevelSelectOverlay() {
            return supportsLevelSelectOverlay;
        }

        @Override
        public void initialize() {
        }

        @Override
        public void update(InputHandler input) {
        }

        @Override
        public void draw() {
        }

        @Override
        public void setClearColor() {
        }

        @Override
        public void reset() {
        }

        @Override
        public State getState() {
            return State.ACTIVE;
        }

        @Override
        public boolean isExiting() {
            return exiting;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void setExitToLevelHandler(Runnable handler) {
            exitHandler = handler != null ? handler : () -> {};
        }
    }

    private static final class TrackingNativeDataSelectProvider
            extends com.openggf.game.dataselect.AbstractDataSelectProvider {
        private int initializeCalls;

        @Override
        public void initialize() {
            initializeCalls++;
            state = State.FADE_IN;
        }

        @Override
        public void update(InputHandler input) {
        }

        @Override
        public void draw() {
        }

        @Override
        public void setClearColor() {
        }

        @Override
        public void reset() {
            state = State.INACTIVE;
        }

        @Override
        public State getState() {
            return state;
        }

        @Override
        public boolean isExiting() {
            return state == State.EXITING;
        }

        @Override
        public boolean isActive() {
            return state != State.INACTIVE;
        }
    }

    private static final class TrackingLaunchProfileStore extends LaunchProfileStore {
        private final LaunchProfile profile;
        private MasterTitleScreen.GameEntry loadedEntry;

        private TrackingLaunchProfileStore(SonicConfigurationService configService, LaunchProfile profile) {
            super(configService);
            this.profile = profile;
        }

        @Override
        public LaunchProfile load(MasterTitleScreen.GameEntry entry) {
            loadedEntry = entry;
            return profile;
        }
    }
}
