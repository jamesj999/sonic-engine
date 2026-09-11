package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.GameStateManager;
import com.openggf.game.NoOpResultsScreen;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.graphics.FadeManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A finished special stage re-raises the results transition every frame, so the
 * exit fade-to-white must only be taken over once -- including on the frame it
 * parks in HOLD_WHITE with its completion still pending.
 */
class TestGameLoopSpecialStageResultsHandoff {
    private GameplayModeContext context;
    private GameLoop loop;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        context = SessionManager.getCurrentGameplayMode();
        loop = new GameLoop(new InputHandler());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void finishedStageEntersResultsOnceAcrossTheExitFadeHold() throws Exception {
        FinishedProvider provider = new FinishedProvider();
        loop.doEnterSpecialStage(provider, 0, false);
        completeActiveFade();

        // Mirrors updateSpecialStageMode(): the fade advances first, then the
        // still-finished provider raises the results transition again.
        for (int frame = 0; frame < 64 && loop.getCurrentGameMode() == GameMode.SPECIAL_STAGE; frame++) {
            context.getFadeManager().update();
            enterResultsScreen();
        }

        assertEquals(GameMode.SPECIAL_STAGE_RESULTS, loop.getCurrentGameMode());
        assertEquals(1, provider.resultsResets,
                "the results screen must be set up exactly once per finished special stage");
        assertEquals(FadeManager.FadeState.NONE, context.getFadeManager().getState(),
                "results entry reveals the screen with no fade: both ROMs write the"
                        + " results palette straight to the active palette"
                        + " (docs/s2disasm/s2.asm:6762-6763 PalLoad_Now,"
                        + " docs/s1disasm/sonic.asm:3382-3383 PalLoad)");
        assertSame(provider, loop.getActiveSpecialStageProvider());
    }

    private void enterResultsScreen() throws Exception {
        Method method = GameLoop.class.getDeclaredMethod("enterResultsScreen", boolean.class);
        method.setAccessible(true);
        try {
            method.invoke(loop, false);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    private void completeActiveFade() {
        for (int frame = 0; frame < 64 && context.getFadeManager().isActive(); frame++) {
            context.getFadeManager().update();
        }
    }

    private static class FinishedProvider implements SpecialStageProvider {
        private int resultsResets;

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
        public void resetForResults() {
            resultsResets++;
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
        }

        @Override
        public void draw() {
        }

        @Override
        public void handleInput(int heldButtons, int pressedButtons) {
        }

        @Override
        public boolean isFinished() {
            return true;
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
