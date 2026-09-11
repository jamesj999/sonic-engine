package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpResultsScreen;
import com.openggf.game.NoOpSpecialStageProvider;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestLiveRewindManagerSpecialStageMode {
    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.LIVE_REWIND_ENABLED, true);
    }

    @AfterEach
    void tearDown() {
        config.resetToDefaults();
        SessionManager.clear();
    }

    @Test
    void specialStageModeIsRejectedWhenProviderDoesNotSupportRewind() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                () -> GameMode.SPECIAL_STAGE,
                () -> NoOpSpecialStageProvider.INSTANCE);

        manager.recordExternalFrame(GameMode.SPECIAL_STAGE, false, new InputHandler());

        assertNull(gameplayMode.getRewindController());
    }

    @Test
    void unsupportedSpecialStageBoundariesDoNotInstallController() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                () -> GameMode.SPECIAL_STAGE,
                () -> NoOpSpecialStageProvider.INSTANCE);

        manager.markBoundary(RewindBoundary.LEVEL_LOAD);
        assertNull(gameplayMode.getRewindController());

        manager.markBoundary(RewindBoundary.SEAMLESS_LEVEL_TRANSITION);
        assertNull(gameplayMode.getRewindController());
    }

    @Test
    void suppliedModeControlsActivationWhenPassedModeDisagrees() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                () -> GameMode.SPECIAL_STAGE,
                () -> NoOpSpecialStageProvider.INSTANCE);

        manager.recordExternalFrame(GameMode.LEVEL, false, new InputHandler());

        assertNull(gameplayMode.getRewindController());
    }

    @Test
    void supportedSpecialStageModeInstallsController() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                () -> GameMode.SPECIAL_STAGE,
                RewindableProvider::new);

        manager.recordExternalFrame(GameMode.SPECIAL_STAGE, false, new InputHandler());

        assertNotNull(gameplayMode.getRewindController());
    }

    @Test
    void supportedSpecialStageReplayUsesSpecialStageStepper() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        RewindableProvider provider = new RewindableProvider();
        InputHandler input = new InputHandler();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                () -> GameMode.SPECIAL_STAGE,
                () -> provider);

        manager.recordExternalFrame(GameMode.SPECIAL_STAGE, false, input);
        RewindController controller = gameplayMode.getRewindController();
        assertNotNull(controller);

        controller.seekTo(0);
        gameplayMode.getPlaybackController().stepForwardOnce();

        assertEquals(1, provider.handleInputCalls);
        assertEquals(1, provider.updateCalls);
    }

    @Test
    void sameContextChangedStepperKindReinstallsController() {
        GameplayModeContext gameplayMode = TestEnvironment.activeGameplayMode();
        AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.LEVEL);
        InputHandler input = new InputHandler();
        LiveRewindManager manager = new LiveRewindManager(
                config,
                mode::get,
                RewindableProvider::new);

        manager.recordExternalFrame(GameMode.LEVEL, false, input);
        RewindController levelController = gameplayMode.getRewindController();
        assertNotNull(levelController);

        mode.set(GameMode.SPECIAL_STAGE);
        manager.markBoundary(RewindBoundary.MODE_ENTER_REWINDABLE);

        RewindController specialStageController = gameplayMode.getRewindController();
        assertNotNull(specialStageController);
        assertNotSame(levelController, specialStageController);
    }

    private static final class RewindableProvider implements SpecialStageProvider {
        private int handleInputCalls;
        private int updateCalls;

        @Override
        public boolean supportsRewind() {
            return true;
        }

        @Override
        public Optional<RewindSnapshottable<?>> rewindAdapter() {
            return Optional.empty();
        }

        @Override
        public boolean hasSpecialStages() {
            return true;
        }

        @Override
        public SpecialStageAccessType getAccessType() {
            return SpecialStageAccessType.GIANT_RING;
        }

        @Override
        public void initializeStage(int stageIndex) throws IOException {
        }

        @Override
        public int getCurrentStage() {
            return 0;
        }

        @Override
        public boolean isEmeraldCollected() {
            return false;
        }

        @Override
        public int getEmeraldIndex() {
            return -1;
        }

        @Override
        public int getRingsCollected() {
            return 0;
        }

        @Override
        public void setEmeraldCollected(boolean collected) {
        }

        @Override
        public boolean isSpriteDebugMode() {
            return false;
        }

        @Override
        public void toggleSpriteDebugMode() {
        }

        @Override
        public void cyclePlaneDebugMode() {
        }

        @Override
        public SpecialStageDebugProvider getDebugProvider() {
            return null;
        }

        @Override
        public boolean isAlignmentTestMode() {
            return false;
        }

        @Override
        public void toggleAlignmentTestMode() {
        }

        @Override
        public void adjustAlignmentOffset(int delta) {
        }

        @Override
        public void adjustAlignmentSpeed(double delta) {
        }

        @Override
        public void toggleAlignmentStepMode() {
        }

        @Override
        public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void setLagCompensation(double factor) {
        }

        @Override
        public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                                 int stageIndex, int totalEmeraldCount) {
            return NoOpResultsScreen.INSTANCE;
        }

        @Override
        public void initialize() throws IOException {
        }

        @Override
        public void update() {
            updateCalls++;
        }

        @Override
        public void draw() {
        }

        @Override
        public void handleInput(int heldButtons, int pressedButtons) {
            handleInputCalls++;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public int consumeStageIndexForEntry(GameStateManager gameState) {
            return 0;
        }
    }
}
