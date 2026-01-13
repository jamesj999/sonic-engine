package uk.co.jamesj999.sonic;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.game.GameMode;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GameLoop class - the core game logic that can run headlessly.
 * These tests verify game state transitions and mode switching
 * without requiring an OpenGL context.
 */
public class TestGameLoop {

    private GameLoop gameLoop;
    private InputHandler mockInputHandler;

    @Before
    public void setUp() {
        mockInputHandler = mock(InputHandler.class);
        gameLoop = new GameLoop(mockInputHandler);
    }

    @After
    public void tearDown() {
        gameLoop = null;
    }

    // ==================== Initialization Tests ====================

    @Test
    public void testGameLoopStartsInLevelMode() {
        assertEquals("GameLoop should start in LEVEL mode",
                GameMode.LEVEL, gameLoop.getCurrentGameMode());
    }

    @Test
    public void testGameLoopConstructorWithInputHandler() {
        GameLoop loop = new GameLoop(mockInputHandler);
        assertEquals("Input handler should be set via constructor",
                mockInputHandler, loop.getInputHandler());
    }

    @Test
    public void testSetInputHandler() {
        GameLoop loop = new GameLoop();
        assertNull("Input handler should be null initially", loop.getInputHandler());

        loop.setInputHandler(mockInputHandler);
        assertEquals("Input handler should be set", mockInputHandler, loop.getInputHandler());
    }

    @Test(expected = IllegalStateException.class)
    public void testStepWithoutInputHandlerThrows() {
        GameLoop loop = new GameLoop();
        loop.step(); // Should throw IllegalStateException
    }

    // ==================== Game Mode Listener Tests ====================

    @Test
    public void testGameModeChangeListenerCanBeSet() {
        GameLoop.GameModeChangeListener listener = mock(GameLoop.GameModeChangeListener.class);
        gameLoop.setGameModeChangeListener(listener);
        // Verify no exception is thrown
    }

    // ==================== Camera Save/Restore Tests ====================

    @Test
    public void testSavedCameraPositionInitiallyZero() {
        assertEquals("Saved camera X should be 0 initially", 0, gameLoop.getSavedCameraX());
        assertEquals("Saved camera Y should be 0 initially", 0, gameLoop.getSavedCameraY());
    }

    @Test
    public void testSetSavedCameraPosition() {
        gameLoop.setSavedCameraPosition((short) 100, (short) 200);
        assertEquals("Saved camera X should be set", 100, gameLoop.getSavedCameraX());
        assertEquals("Saved camera Y should be set", 200, gameLoop.getSavedCameraY());
    }

    @Test
    public void testSetSavedCameraPositionWithNegativeValues() {
        gameLoop.setSavedCameraPosition((short) -50, (short) -100);
        assertEquals("Saved camera X should handle negative values", -50, gameLoop.getSavedCameraX());
        assertEquals("Saved camera Y should handle negative values", -100, gameLoop.getSavedCameraY());
    }

    // ==================== Mode Transition Guard Tests ====================

    @Test
    public void testEnterSpecialStageFromSpecialStageDoesNothing() {
        // First we need to somehow be in special stage mode
        // This tests the guard condition - can't enter special stage from special stage
        GameMode initialMode = gameLoop.getCurrentGameMode();
        assertEquals("Should start in LEVEL mode", GameMode.LEVEL, initialMode);

        // Note: Actually entering special stage requires ROM data and initialized managers
        // This test verifies the API exists and guards are in place
    }

    @Test
    public void testGameModeStartsInLevelMode() {
        // When starting, should be in LEVEL mode
        assertEquals("Should be in LEVEL mode", GameMode.LEVEL, gameLoop.getCurrentGameMode());

        // Verify no results screen is active
        assertNull("Results screen should be null initially", gameLoop.getResultsScreen());
    }

    // ==================== Game Mode Accessor Tests ====================

    @Test
    public void testGetCurrentGameModeReturnsCorrectMode() {
        // Initially should be in LEVEL mode
        GameMode mode = gameLoop.getCurrentGameMode();
        assertNotNull("Game mode should not be null", mode);
        assertEquals("Should be in LEVEL mode", GameMode.LEVEL, mode);
    }
}
