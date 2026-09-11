package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSpecialStageModuleConfig {

    @BeforeEach
    public void setUp() {
        SessionManager.clear();
    }

    @AfterEach
    public void tearDown() {
        GameModuleRegistry.reset();
        SessionManager.clear();
    }

    private GameStateManager recreateGameState(com.openggf.game.GameModule module) {
        SessionManager.clear();
        GameModuleRegistry.setCurrent(module);
        TestEnvironment.activeGameplayMode();
        return GameServices.gameState();
    }

    @Test
    public void sonic1ModuleConfiguresSixStagesAndEmeralds() {
        GameStateManager gameState = recreateGameState(new Sonic1GameModule());

        assertEquals(6, gameState.getSpecialStageCount());
        assertEquals(6, gameState.getChaosEmeraldCount());

        for (int i = 0; i < 6; i++) {
            assertEquals(i, gameState.consumeCurrentSpecialStageIndexAndAdvance());
            gameState.markEmeraldCollected(i);
        }

        assertEquals(0, gameState.consumeCurrentSpecialStageIndexAndAdvance());
        assertTrue(gameState.hasAllEmeralds());
    }

    @Test
    public void switchingBackToSonic2RestoresSevenStageConfig() {
        GameStateManager gameState = recreateGameState(new Sonic1GameModule());
        for (int i = 0; i < 6; i++) {
            gameState.markEmeraldCollected(i);
        }
        assertTrue(gameState.hasAllEmeralds());

        gameState = recreateGameState(new Sonic2GameModule());

        assertEquals(7, gameState.getSpecialStageCount());
        assertEquals(7, gameState.getChaosEmeraldCount());
        assertFalse(gameState.hasAllEmeralds());
        assertEquals(0, gameState.consumeCurrentSpecialStageIndexAndAdvance());
    }

    @Test
    public void resetRestoresSonic2StageConfigThroughCompatibilityPath() {
        GameStateManager gameState = recreateGameState(new Sonic1GameModule());
        for (int i = 0; i < 6; i++) {
            gameState.markEmeraldCollected(i);
        }
        assertTrue(gameState.hasAllEmeralds());

        GameModuleRegistry.reset();
        SessionManager.clear();
        TestEnvironment.activeGameplayMode();
        gameState = GameServices.gameState();

        assertEquals(7, gameState.getSpecialStageCount());
        assertEquals(7, gameState.getChaosEmeraldCount());
        assertFalse(gameState.hasAllEmeralds());
        assertEquals(0, gameState.consumeCurrentSpecialStageIndexAndAdvance());
    }
}


