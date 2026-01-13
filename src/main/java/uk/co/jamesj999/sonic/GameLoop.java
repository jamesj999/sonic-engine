package uk.co.jamesj999.sonic;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.debug.DebugObjectArtViewer;
import uk.co.jamesj999.sonic.debug.DebugSpecialStageSprites;
import uk.co.jamesj999.sonic.game.GameMode;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.CheckpointState;
import uk.co.jamesj999.sonic.game.sonic2.LevelGamestate;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.game.sonic2.objects.SpecialStageResultsScreenObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteCollisionManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.TimerManager;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * Standalone game loop that can run independently of the rendering system.
 * This enables headless testing of game logic without requiring OpenGL context.
 *
 * <p>The GameLoop manages:
 * <ul>
 *   <li>Audio updates</li>
 *   <li>Timer updates</li>
 *   <li>Input processing</li>
 *   <li>Game mode transitions (level ↔ special stage)</li>
 *   <li>Sprite collision and movement</li>
 *   <li>Camera updates</li>
 *   <li>Level updates</li>
 * </ul>
 *
 * <p>For headless testing, create a GameLoop with a mock InputHandler
 * and call {@link #step()} to advance one frame.
 */
public class GameLoop {
    private static final Logger LOGGER = Logger.getLogger(GameLoop.class.getName());

    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final SpriteManager spriteManager = SpriteManager.getInstance();
    private final SpriteCollisionManager spriteCollisionManager = SpriteCollisionManager.getInstance();
    private final Camera camera = Camera.getInstance();
    private final TimerManager timerManager = TimerManager.getInstance();
    private final LevelManager levelManager = LevelManager.getInstance();
    private final Sonic2SpecialStageManager specialStageManager = Sonic2SpecialStageManager.getInstance();

    private InputHandler inputHandler;
    private GameMode currentGameMode = GameMode.LEVEL;

    // Saved camera position for returning from special stage
    private short savedCameraX = 0;
    private short savedCameraY = 0;

    // Special stage results screen
    private SpecialStageResultsScreenObjectInstance resultsScreen;
    private int ssRingsCollected;
    private boolean ssEmeraldCollected;
    private int ssStageIndex;
    private int resultsFrameCounter = 0;

    // Listener for game mode changes (used by Engine to update projection)
    private GameModeChangeListener gameModeChangeListener;

    /**
     * Callback interface for game mode changes.
     */
    public interface GameModeChangeListener {
        void onGameModeChanged(GameMode oldMode, GameMode newMode);
    }

    public GameLoop() {
    }

    public GameLoop(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public void setInputHandler(InputHandler inputHandler) {
        this.inputHandler = inputHandler;
    }

    public InputHandler getInputHandler() {
        return inputHandler;
    }

    public void setGameModeChangeListener(GameModeChangeListener listener) {
        this.gameModeChangeListener = listener;
    }

    public GameMode getCurrentGameMode() {
        return currentGameMode;
    }

    /**
     * Advances the game by one frame. This is the main update loop.
     * Call this method at your target FPS (typically 60fps).
     */
    public void step() {
        if (inputHandler == null) {
            throw new IllegalStateException("InputHandler must be set before calling step()");
        }

        AudioManager.getInstance().update();
        timerManager.update();
        DebugOverlayManager.getInstance().updateInput(inputHandler);
        DebugObjectArtViewer.getInstance().updateInput(inputHandler);

        // Check for Special Stage toggle (HOME by default)
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))) {
            handleSpecialStageDebugKey();
        }

        if (currentGameMode == GameMode.SPECIAL_STAGE) {
            // END: Debug complete special stage with emerald (for testing results screen)
            if (inputHandler.isKeyPressed(KeyEvent.VK_END)) {
                debugCompleteSpecialStageWithEmerald();
            }

            // DEL: Debug fail special stage (for testing results screen without emerald)
            if (inputHandler.isKeyPressed(KeyEvent.VK_DELETE)) {
                debugFailSpecialStage();
            }

            // F12: Toggle sprite frame debug viewer (shows all animation frames)
            if (inputHandler.isKeyPressed(KeyEvent.VK_F12)) {
                specialStageManager.toggleSpriteDebugMode();
            }

            // Handle sprite debug viewer navigation
            if (specialStageManager.isSpriteDebugMode()) {
                DebugSpecialStageSprites debugSprites = DebugSpecialStageSprites.getInstance();
                // Left/Right: Change page within current graphics set
                if (inputHandler.isKeyPressed(KeyEvent.VK_RIGHT)) {
                    debugSprites.nextPage();
                }
                if (inputHandler.isKeyPressed(KeyEvent.VK_LEFT)) {
                    debugSprites.previousPage();
                }
                // Up/Down: Cycle between graphics sets
                if (inputHandler.isKeyPressed(KeyEvent.VK_DOWN)) {
                    debugSprites.nextSet();
                }
                if (inputHandler.isKeyPressed(KeyEvent.VK_UP)) {
                    debugSprites.previousSet();
                }
            }

            updateSpecialStageInput();
            specialStageManager.update();

            // Check for special stage completion or failure
            if (specialStageManager.isFinished()) {
                var result = specialStageManager.getResultState();
                boolean completed = (result == Sonic2SpecialStageManager.ResultState.COMPLETED);
                boolean gotEmerald = completed && specialStageManager.hasEmeraldCollected();
                enterResultsScreen(gotEmerald);
            }
        } else if (currentGameMode == GameMode.SPECIAL_STAGE_RESULTS) {
            // Update results screen
            resultsFrameCounter++;
            if (resultsScreen != null) {
                resultsScreen.update(resultsFrameCounter, null);
                if (resultsScreen.isComplete()) {
                    exitResultsScreen();
                }
            }
        } else {
            boolean freezeForArtViewer = DebugOverlayManager.getInstance()
                    .isEnabled(uk.co.jamesj999.sonic.debug.DebugOverlayToggle.OBJECT_ART_VIEWER);
            if (!freezeForArtViewer) {
                spriteCollisionManager.update(inputHandler);
                camera.updatePosition();
                levelManager.update();

                // Check if a checkpoint star requested a special stage
                if (levelManager.consumeSpecialStageRequest()) {
                    enterSpecialStage();
                }
            }

            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ACT))) {
                try {
                    levelManager.nextAct();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ZONE))) {
                try {
                    levelManager.nextZone();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        inputHandler.update();
    }

    /**
     * Handles the special stage debug key (HOME by default).
     * When in level mode, enters the next special stage.
     * When in special stage mode, exits to results screen (as failure).
     * When in results screen mode, skips back to level.
     */
    private void handleSpecialStageDebugKey() {
        if (currentGameMode == GameMode.LEVEL) {
            enterSpecialStage();
        } else if (currentGameMode == GameMode.SPECIAL_STAGE) {
            enterResultsScreen(false);
        } else if (currentGameMode == GameMode.SPECIAL_STAGE_RESULTS) {
            exitResultsScreen();
        }
    }

    /**
     * Debug function: Immediately completes the special stage with emerald collected.
     * Simulates successful completion with the ring requirement met.
     * Press END key during special stage to trigger.
     */
    private void debugCompleteSpecialStageWithEmerald() {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Force emerald collection state
        specialStageManager.setEmeraldCollected(true);

        // Get the ring requirement for this stage and set rings to meet it
        int stageIndex = specialStageManager.getCurrentStage();
        int ringRequirement = getDebugRingRequirement(stageIndex);

        LOGGER.info("DEBUG: Completing Special Stage " + (stageIndex + 1) +
                " with emerald (forcing " + ringRequirement + " rings)");

        // Enter results screen with emerald collected and simulated ring count
        enterResultsScreenWithDebugRings(true, ringRequirement);
    }

    /**
     * Debug method to fail special stage and go directly to results screen.
     * Press DEL key during special stage to trigger.
     */
    private void debugFailSpecialStage() {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        int stageIndex = specialStageManager.getCurrentStage();
        int smallRingCount = 15; // A small amount of rings to show ring bonus tally

        LOGGER.info("DEBUG: Failing Special Stage " + (stageIndex + 1) +
                " (with " + smallRingCount + " rings)");

        // Enter results screen without emerald and with small ring count
        enterResultsScreenWithDebugRings(false, smallRingCount);
    }

    /**
     * Gets the ring requirement for a stage (for debug purposes).
     * Uses the final checkpoint requirement from the original game.
     */
    private int getDebugRingRequirement(int stageIndex) {
        // Ring requirements at final checkpoint (checkpoint 3) for each stage
        // From s2.asm Ring_Requirement_Table (solo mode)
        int[][] requirements = {
                {30, 60, 90, 120},   // Stage 1
                {40, 80, 120, 160},  // Stage 2
                {50, 100, 140, 180}, // Stage 3
                {50, 100, 140, 180}, // Stage 4
                {60, 110, 160, 200}, // Stage 5
                {70, 120, 180, 220}, // Stage 6
                {80, 140, 200, 240}  // Stage 7
        };
        if (stageIndex >= 0 && stageIndex < requirements.length) {
            return requirements[stageIndex][3]; // Final checkpoint requirement
        }
        return 100; // Default fallback
    }

    /**
     * Enters results screen with a specific ring count (for debug).
     */
    private void enterResultsScreenWithDebugRings(boolean emeraldCollected, int ringsCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Store special stage results for the results screen
        ssRingsCollected = ringsCollected;
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = specialStageManager.getCurrentStage();

        // Mark emerald as collected now (so it shows in results screen)
        if (emeraldCollected) {
            GameStateManager gsm = GameStateManager.getInstance();
            gsm.markEmeraldCollected(ssStageIndex);
            LOGGER.info("DEBUG: Collected emerald " + (ssStageIndex + 1) + "! Total: " + gsm.getEmeraldCount());
        }

        // Reset special stage manager
        specialStageManager.reset();

        // Transition to results mode
        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.SPECIAL_STAGE_RESULTS;
        resultsFrameCounter = 0;

        // Create results screen with current emerald count
        int totalEmeralds = GameStateManager.getInstance().getEmeraldCount();
        resultsScreen = new SpecialStageResultsScreenObjectInstance(
                ssRingsCollected, ssEmeraldCollected, ssStageIndex, totalEmeralds);

        // Play act clear music
        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_ACT_CLEAR);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("DEBUG: Entered Special Stage Results Screen (rings=" + ssRingsCollected +
                ", emerald=" + ssEmeraldCollected + ")");
    }

    /**
     * Enters the special stage from level mode.
     * Uses GameStateManager to track which stage to enter (cycles 0-6).
     */
    public void enterSpecialStage() {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        GameStateManager gsm = GameStateManager.getInstance();
        int stageIndex = gsm.consumeCurrentSpecialStageIndexAndAdvance();

        try {
            // Save camera position for when we return
            savedCameraX = camera.getX();
            savedCameraY = camera.getY();

            specialStageManager.reset();
            specialStageManager.initialize(stageIndex);

            GameMode oldMode = currentGameMode;
            currentGameMode = GameMode.SPECIAL_STAGE;

            // Set camera to origin for special stage rendering (uses screen coordinates)
            camera.setX((short) 0);
            camera.setY((short) 0);

            AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_SPECIAL_STAGE);

            // Notify listener of mode change
            if (gameModeChangeListener != null) {
                gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
            }

            LOGGER.info("Entered Special Stage " + (stageIndex + 1) + " (H32 mode: 256x224)");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Special Stage " + (stageIndex + 1), e);
        }
    }

    /**
     * Enters the results screen after special stage completion/failure.
     * @param emeraldCollected true if an emerald was collected
     */
    private void enterResultsScreen(boolean emeraldCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Store special stage results for the results screen
        ssRingsCollected = specialStageManager.getRingsCollected();
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = specialStageManager.getCurrentStage();

        // Mark emerald as collected now (so it shows in results screen)
        if (emeraldCollected) {
            GameStateManager gsm = GameStateManager.getInstance();
            gsm.markEmeraldCollected(ssStageIndex);
            LOGGER.info("Collected emerald " + (ssStageIndex + 1) + "! Total: " + gsm.getEmeraldCount());
        }

        // Reset special stage manager
        specialStageManager.reset();

        // Transition to results mode
        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.SPECIAL_STAGE_RESULTS;
        resultsFrameCounter = 0;

        // Create results screen with current emerald count
        int totalEmeralds = GameStateManager.getInstance().getEmeraldCount();
        resultsScreen = new SpecialStageResultsScreenObjectInstance(
                ssRingsCollected, ssEmeraldCollected, ssStageIndex, totalEmeralds);

        // Play act clear music
        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_ACT_CLEAR);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered Special Stage Results Screen (rings=" + ssRingsCollected +
                ", emerald=" + ssEmeraldCollected + ")");
    }

    /**
     * Exits the results screen and returns to the level.
     */
    private void exitResultsScreen() {
        if (currentGameMode != GameMode.SPECIAL_STAGE_RESULTS) {
            return;
        }

        // Clean up results screen
        resultsScreen = null;

        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.LEVEL;

        // Restore level palettes (special stage overwrites them)
        levelManager.reloadLevelPalettes();

        // Restore camera position
        camera.setX(savedCameraX);
        camera.setY(savedCameraY);

        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null) mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);

        if (sprite instanceof AbstractPlayableSprite playable) {
            CheckpointState checkpointState = levelManager.getCheckpointState();

            if (checkpointState != null && checkpointState.isActive()) {
                checkpointState.restoreToPlayer(playable, camera);
            }

            LevelGamestate gamestate = levelManager.getLevelGamestate();
            if (gamestate != null) {
                gamestate.setRings(0);
            }
        }

        // Restore zone music
        int zoneMusicId = levelManager.getCurrentLevelMusicId();
        if (zoneMusicId >= 0) {
            AudioManager.getInstance().playMusic(zoneMusicId);
            LOGGER.fine("Restored zone music: 0x" + Integer.toHexString(zoneMusicId));
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Exited Results Screen, returned to level at checkpoint");
    }

    /**
     * Gets the current results screen object (for rendering).
     * @return the results screen object, or null if not in results mode
     */
    public SpecialStageResultsScreenObjectInstance getResultsScreen() {
        return resultsScreen;
    }

    private void updateSpecialStageInput() {
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);

        int heldButtons = 0;
        int pressedButtons = 0;

        if (inputHandler.isKeyDown(leftKey)) {
            heldButtons |= 0x04;
        }
        if (inputHandler.isKeyDown(rightKey)) {
            heldButtons |= 0x08;
        }

        if (inputHandler.isKeyPressed(jumpKey)) {
            pressedButtons |= 0x70;
        }
        if (inputHandler.isKeyDown(jumpKey)) {
            heldButtons |= 0x70;
        }

        specialStageManager.handleInput(heldButtons, pressedButtons);
    }

    /**
     * Gets the saved camera X position (for special stage return).
     */
    public short getSavedCameraX() {
        return savedCameraX;
    }

    /**
     * Gets the saved camera Y position (for special stage return).
     */
    public short getSavedCameraY() {
        return savedCameraY;
    }

    /**
     * Sets the saved camera position. Used when entering special stage.
     */
    public void setSavedCameraPosition(short x, short y) {
        this.savedCameraX = x;
        this.savedCameraY = y;
    }
}
