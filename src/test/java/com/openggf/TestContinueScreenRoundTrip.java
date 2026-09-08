package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.game.*;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

/** Exercises the production GameLoop mode/fade/reload path, not just provider counters. */
class TestContinueScreenRoundTrip {
    @Nested @RequiresRom(SonicGame.SONIC_1)
    class Sonic1 {
        @Test void acceptedContinueReloadsAct() throws Exception { roundTrip(SonicGame.SONIC_1); }
    }
    @Nested @RequiresRom(SonicGame.SONIC_2)
    class Sonic2 {
        @Test void acceptedContinueReloadsAct() throws Exception { roundTrip(SonicGame.SONIC_2); }
    }
    @Nested @RequiresRom(SonicGame.SONIC_3K)
    class Sonic3k {
        @Test void acceptedContinueReloadsAct() throws Exception { roundTrip(SonicGame.SONIC_3K); }
    }

    private static void roundTrip(SonicGame game) throws Exception {
        var shared = SharedLevel.load(game, 0, 0);
        try {
            HeadlessTestFixture.builder().withSharedLevel(shared).build();
            var input = new InputHandler();
            var loop = new GameLoop(input);
            loop.setGameMode(GameMode.LEVEL);
            var state = GameServices.gameState();
            while (state.getLives() > 0) state.loseLife();
            state.addContinue();
            state.addContinue();
            state.addScore(1200);
            var level = GameServices.level();
            level.requestGameOverExit(GameOverExit.CONTINUE_SCREEN);
            for (int i = 0; i < 120 && loop.getCurrentGameMode() != GameMode.CONTINUE_SCREEN; i++) loop.step();
            assertEquals(GameMode.CONTINUE_SCREEN, loop.getCurrentGameMode());
            assertNotNull(loop.getContinueScreenProvider());
            for (int i = 0; i < 100; i++) loop.step();
            int start = GameServices.configuration().getInt(SonicConfiguration.START);
            input.handleKeyEvent(start, GLFW_PRESS);
            loop.step();
            input.handleKeyEvent(start, GLFW_RELEASE);
            assertTrue(loop.getContinueScreenProvider().isAccepted());
            for (int i = 0; i < 180 && loop.getContinueScreenProvider() != null; i++) loop.step();
            assertNull(loop.getContinueScreenProvider(), "departure and fade must reach reload");
            assertEquals(3, state.getLives());
            assertEquals(1, state.getContinues());
            assertEquals(0, state.getScore());
            assertEquals(0, level.getCurrentZone());
            assertTrue(loop.getCurrentGameMode() == GameMode.LEVEL || loop.getCurrentGameMode() == GameMode.TITLE_CARD);
            assertFalse(level.isLevelInactiveForTransition());
        } finally {
            shared.dispose();
        }
    }
}
