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
            // F12: Toggle sprite frame debug viewer (shows all animation frames)
            if (inputHandler.isKeyPressed(KeyEvent.VK_F12)) {
                specialStageManager.toggleSpriteDebugMode();
            }

            // Handle sprite debug viewer page navigation (Left/Right arrows)
            if (specialStageManager.isSpriteDebugMode()) {
                DebugSpecialStageSprites debugSprites = DebugSpecialStageSprites.getInstance();
                if (inputHandler.isKeyPressed(KeyEvent.VK_RIGHT)) {
                    debugSprites.nextPage();
                }
                if (inputHandler.isKeyPressed(KeyEvent.VK_LEFT)) {
                    debugSprites.previousPage();
                }
            }

            updateSpecialStageInput();
            specialStageManager.update();

            // Check for special stage completion or failure
            if (specialStageManager.isFinished()) {
                var result = specialStageManager.getResultState();
                boolean completed = (result == Sonic2SpecialStageManager.ResultState.COMPLETED);
                boolean gotEmerald = completed && specialStageManager.hasEmeraldCollected();
                exitSpecialStage(completed, gotEmerald);
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
     * When in special stage mode, exits back to level (as failure).
     */
    private void handleSpecialStageDebugKey() {
        if (currentGameMode == GameMode.LEVEL) {
            enterSpecialStage();
        } else if (currentGameMode == GameMode.SPECIAL_STAGE) {
            exitSpecialStage(false, false);
        }
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
     * Exits the special stage and returns to the level.
     * @param completed true if the stage was completed (reached end)
     * @param emeraldCollected true if an emerald was collected
     */
    public void exitSpecialStage(boolean completed, boolean emeraldCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        if (emeraldCollected) {
            GameStateManager gsm = GameStateManager.getInstance();
            int emeraldIndex = specialStageManager.getCurrentStage();
            gsm.markEmeraldCollected(emeraldIndex);

            LOGGER.info("Collected emerald " + (emeraldIndex + 1) + "! Total: " + gsm.getEmeraldCount());
        }

        specialStageManager.reset();
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

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Exited Special Stage, returned to level at checkpoint");
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
