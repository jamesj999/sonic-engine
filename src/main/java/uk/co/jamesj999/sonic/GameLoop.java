package uk.co.jamesj999.sonic;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.Control.InputHandler;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.debug.DebugObjectArtViewer;
import uk.co.jamesj999.sonic.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import uk.co.jamesj999.sonic.game.GameMode;
import uk.co.jamesj999.sonic.game.GameModuleRegistry;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.LevelEventProvider;
import uk.co.jamesj999.sonic.game.LevelState;
import uk.co.jamesj999.sonic.game.RespawnState;
import uk.co.jamesj999.sonic.game.ResultsScreen;
import uk.co.jamesj999.sonic.game.SpecialStageProvider;
import uk.co.jamesj999.sonic.game.TitleCardProvider;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.debug.PerformanceProfiler;
import uk.co.jamesj999.sonic.game.sonic2.objects.SpecialStageResultsScreenObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;
import uk.co.jamesj999.sonic.level.LevelManager;

import java.awt.event.KeyEvent;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.graphics.FadeManager;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Standalone game loop that can run independently of the rendering system.
 * This enables headless testing of game logic without requiring OpenGL context.
 *
 * <p>
 * The GameLoop manages:
 * <ul>
 * <li>Audio updates</li>
 * <li>Timer updates</li>
 * <li>Input processing</li>
 * <li>Game mode transitions (level ↔ special stage)</li>
 * <li>Sprite collision and movement</li>
 * <li>Camera updates</li>
 * <li>Level updates</li>
 * </ul>
 *
 * <p>
 * For headless testing, create a GameLoop with a mock InputHandler
 * and call {@link #step()} to advance one frame.
 */
public class GameLoop {
    private static final Logger LOGGER = Logger.getLogger(GameLoop.class.getName());

    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final SpriteManager spriteManager = SpriteManager.getInstance();
    private final Camera camera = Camera.getInstance();
    private final TimerManager timerManager = GameServices.timers();
    private final LevelManager levelManager = LevelManager.getInstance();
    private final PerformanceProfiler profiler = PerformanceProfiler.getInstance();
    // Direct reference to Sonic2SpecialStageManager for debug features and Sonic
    // 2-specific logic.
    // Future games should use GameModule.getSpecialStageProvider() for
    // game-agnostic code.
    private final Sonic2SpecialStageManager specialStageManager = Sonic2SpecialStageManager.getInstance();

    // Title card provider - lazily initialized when GameModule is available
    private TitleCardProvider titleCardProvider;

    private InputHandler inputHandler;
    private GameMode currentGameMode = GameMode.LEVEL;

    // Special stage results screen
    private ResultsScreen resultsScreen;
    private int ssRingsCollected;
    private boolean ssEmeraldCollected;
    private int ssStageIndex;
    private int resultsFrameCounter = 0;

    // Flag to track when returning from special stage (for title card exit
    // handling)
    private boolean returningFromSpecialStage = false;

    // Flag to freeze level updates during special stage entry transition
    private boolean specialStageTransitionPending = false;

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

    /**
     * Gets the title card provider, lazily initializing it from the current
     * GameModule.
     * 
     * @return the title card provider
     */
    private TitleCardProvider getTitleCardProviderLazy() {
        if (titleCardProvider == null) {
            titleCardProvider = GameModuleRegistry.getCurrent().getTitleCardProvider();
        }
        return titleCardProvider;
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

        profiler.beginSection("audio");
        AudioManager.getInstance().update();
        profiler.endSection("audio");

        profiler.beginSection("timers");
        timerManager.update();
        profiler.endSection("timers");

        profiler.beginSection("input");
        GameServices.debugOverlay().updateInput(inputHandler);
        DebugObjectArtViewer.getInstance().updateInput(inputHandler);

        // Check for Special Stage toggle (HOME by default)
        if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_KEY))) {
            handleSpecialStageDebugKey();
        }

        if (currentGameMode == GameMode.SPECIAL_STAGE) {
            // Debug complete special stage with emerald (for testing results screen)
            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_COMPLETE_KEY))) {
                debugCompleteSpecialStageWithEmerald();
            }

            // Debug fail special stage (for testing results screen without emerald)
            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_FAIL_KEY))) {
                debugFailSpecialStage();
            }

            // Toggle sprite frame debug viewer (shows all animation frames)
            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_SPRITE_DEBUG_KEY))) {
                specialStageManager.toggleSpriteDebugMode();
            }

            // Cycle special stage plane visibility (A/B/both/off)
            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.SPECIAL_STAGE_PLANE_DEBUG_KEY))) {
                specialStageManager.cyclePlaneDebugMode();
            }

            // Handle sprite debug viewer navigation (uses configured movement keys)
            if (specialStageManager.isSpriteDebugMode()) {
                Sonic2SpecialStageSpriteDebug debugSprites = Sonic2SpecialStageSpriteDebug.getInstance();
                // Left/Right: Change page within current graphics set
                if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.RIGHT))) {
                    debugSprites.nextPage();
                }
                if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.LEFT))) {
                    debugSprites.previousPage();
                }
                // Up/Down: Cycle between graphics sets
                if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.DOWN))) {
                    debugSprites.nextSet();
                }
                if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.UP))) {
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
        } else if (currentGameMode == GameMode.TITLE_CARD) {
            // Update title card animation
            getTitleCardProviderLazy().update();

            // From disassembly lines 5073-5078: control is released at the START of
            // TEXT_WAIT,
            // not when the title card is complete. This allows the player to move while the
            // text is still visible on screen.
            if (getTitleCardProviderLazy().shouldReleaseControl()) {
                exitTitleCard();
                // Continue to LEVEL mode processing this frame (fall through)
            } else {
                // Still in locked phase - run physics without input
                // This allows Sonic to settle onto the ground while title card is visible,
                // preventing camera jitter when title card ends
                spriteManager.updateWithoutInput();
                // Force camera to snap to player position during title card (no smooth
                // scrolling)
                camera.updatePosition(true);
                return; // Don't process LEVEL mode logic yet
            }
        }

        profiler.endSection("input");

        // LEVEL mode (or just transitioned from TITLE_CARD)
        if (currentGameMode == GameMode.LEVEL) {
            // Continue updating title card overlay if still active
            // (TEXT_WAIT and TEXT_EXIT phases where player can move but text is still
            // visible)
            if (getTitleCardProviderLazy().isOverlayActive()) {
                getTitleCardProviderLazy().update();
            }
            // Check if a title card was requested (new level loaded)
            if (levelManager.consumeTitleCardRequest()) {
                enterTitleCard(levelManager.getTitleCardZone(), levelManager.getTitleCardAct());
                return; // Skip normal level update this frame
            }

            // Check for transition requests that need fade-to-black
            FadeManager fadeManager = FadeManager.getInstance();
            if (!fadeManager.isActive()) {
                if (levelManager.consumeRespawnRequest()) {
                    startRespawnFade();
                    return;
                }
                if (levelManager.consumeNextActRequest()) {
                    startNextActFade();
                    return;
                }
                if (levelManager.consumeNextZoneRequest()) {
                    startNextZoneFade();
                    return;
                }
            }

            boolean freezeForArtViewer = GameServices.debugOverlay()
                    .isEnabled(uk.co.jamesj999.sonic.debug.DebugOverlayToggle.OBJECT_ART_VIEWER);
            // Freeze level updates during special stage entry transition
            boolean freezeForSpecialStage = specialStageTransitionPending;
            if (!freezeForArtViewer && !freezeForSpecialStage) {
                profiler.beginSection("physics");
                spriteManager.update(inputHandler);
                profiler.endSection("physics");

                // Dynamic level events update boundary targets (game-specific)
                LevelEventProvider levelEvents = GameModuleRegistry.getCurrent().getLevelEventProvider();
                if (levelEvents != null) {
                    levelEvents.update();
                }

                profiler.beginSection("camera");
                // Ease boundaries toward targets at 2px/frame
                camera.updateBoundaryEasing();
                camera.updatePosition();
                profiler.endSection("camera");

                profiler.beginSection("objects");
                levelManager.update();
                profiler.endSection("objects");

                // Check if a checkpoint star requested a special stage
                if (levelManager.consumeSpecialStageRequest()) {
                    enterSpecialStage();
                }
            }

            // Debug keys for level transitions (use request system for fade)
            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ACT))) {
                levelManager.requestNextAct();
            }

            if (inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.NEXT_ZONE))) {
                levelManager.requestNextZone();
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
     * Debug function: Immediately completes the special stage with emerald
     * collected.
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
                { 30, 60, 90, 120 }, // Stage 1
                { 40, 80, 120, 160 }, // Stage 2
                { 50, 100, 140, 180 }, // Stage 3
                { 50, 100, 140, 180 }, // Stage 4
                { 60, 110, 160, 200 }, // Stage 5
                { 70, 120, 180, 220 }, // Stage 6
                { 80, 140, 200, 240 } // Stage 7
        };
        if (stageIndex >= 0 && stageIndex < requirements.length) {
            return requirements[stageIndex][3]; // Final checkpoint requirement
        }
        return 100; // Default fallback
    }

    /**
     * Enters results screen with a specific ring count (for debug).
     * Uses fade-to-white transition like the normal path.
     */
    private void enterResultsScreenWithDebugRings(boolean emeraldCollected, int ringsCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = FadeManager.getInstance();
        if (fadeManager.isActive()) {
            return;
        }

        // Store special stage results for the results screen
        ssRingsCollected = ringsCollected;
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = specialStageManager.getCurrentStage();

        // Mark emerald as collected now (so it shows in results screen)
        if (emeraldCollected) {
            GameStateManager gsm = GameServices.gameState();
            gsm.markEmeraldCollected(ssStageIndex);
            LOGGER.info("DEBUG: Collected emerald " + (ssStageIndex + 1) + "! Total: " + gsm.getEmeraldCount());
        }

        // Start fade-to-white, then show results when complete
        fadeManager.startFadeToWhite(() -> {
            doEnterResultsScreenDebug();
        });

        LOGGER.info("DEBUG: Starting fade-to-white to exit Special Stage");
    }

    /**
     * Actually enters the results screen after fade-to-white completes (debug
     * version).
     */
    private void doEnterResultsScreenDebug() {
        // Reset special stage manager
        specialStageManager.reset();

        // Transition to results mode
        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.SPECIAL_STAGE_RESULTS;
        resultsFrameCounter = 0;

        // Create results screen with current emerald count
        int totalEmeralds = GameServices.gameState().getEmeraldCount();
        resultsScreen = new SpecialStageResultsScreenObjectInstance(
                ssRingsCollected, ssEmeraldCollected, ssStageIndex, totalEmeralds);

        // Play act clear music
        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_ACT_CLEAR);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        // Start fade-from-white to reveal the results screen
        FadeManager.getInstance().startFadeFromWhite(null);

        LOGGER.info("DEBUG: Entered Special Stage Results Screen (rings=" + ssRingsCollected +
                ", emerald=" + ssEmeraldCollected + ")");
    }

    /**
     * Enters the special stage from level mode.
     * Uses GameStateManager to track which stage to enter (cycles 0-6).
     * Performs fade-to-white transition before entering.
     */
    public void enterSpecialStage() {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = FadeManager.getInstance();
        if (fadeManager.isActive()) {
            return;
        }

        // Freeze level updates during transition (ROM-accurate: level stops updating)
        specialStageTransitionPending = true;

        // Clear power-ups before entering special stage
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null)
            mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            playable.clearPowerUps();
        }

        // Play special stage entry sound
        AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_SPECIAL_STAGE_ENTRY);

        // Fade out the current music gradually (ROM: MusID_FadeOut / zFadeOutMusic)
        // This preserves the SFX we just started, unlike stopMusic() which silences all
        AudioManager.getInstance().fadeOutMusic();

        // Determine which stage to enter
        GameStateManager gsm = GameServices.gameState();
        final int stageIndex = gsm.consumeCurrentSpecialStageIndexAndAdvance();

        // Start fade-to-white, then enter special stage when complete
        fadeManager.startFadeToWhite(() -> {
            doEnterSpecialStage(stageIndex);
        });

        LOGGER.info("Starting fade-to-white for Special Stage " + (stageIndex + 1));
    }

    /**
     * Actually enters the special stage after fade-to-white completes.
     * Called by the fade callback.
     */
    private void doEnterSpecialStage(int stageIndex) {
        // Clear the transition freeze flag (now we're in special stage mode)
        specialStageTransitionPending = false;

        try {
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

            // Start fade-from-white to reveal the special stage
            FadeManager.getInstance().startFadeFromWhite(null);

            LOGGER.info("Entered Special Stage " + (stageIndex + 1) + " (H32 mode: 256x224)");
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize Special Stage " + (stageIndex + 1), e);
        }
    }

    /**
     * Enters the results screen after special stage completion/failure.
     * Performs fade-to-white transition before showing results.
     * 
     * @param emeraldCollected true if an emerald was collected
     */
    private void enterResultsScreen(boolean emeraldCollected) {
        if (currentGameMode != GameMode.SPECIAL_STAGE) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = FadeManager.getInstance();
        if (fadeManager.isActive()) {
            return;
        }

        // Store special stage results for the results screen
        ssRingsCollected = specialStageManager.getRingsCollected();
        ssEmeraldCollected = emeraldCollected;
        ssStageIndex = specialStageManager.getCurrentStage();

        // Mark emerald as collected now (so it shows in results screen)
        if (emeraldCollected) {
            GameStateManager gsm = GameServices.gameState();
            gsm.markEmeraldCollected(ssStageIndex);
            LOGGER.info("Collected emerald " + (ssStageIndex + 1) + "! Total: " + gsm.getEmeraldCount());
        }

        // Start fade-to-white, then show results when complete
        fadeManager.startFadeToWhite(() -> {
            doEnterResultsScreen();
        });

        LOGGER.info("Starting fade-to-white to exit Special Stage");
    }

    /**
     * Actually enters the results screen after fade-to-white completes.
     */
    private void doEnterResultsScreen() {
        // Reset special stage provider
        SpecialStageProvider ssProvider = GameModuleRegistry.getCurrent().getSpecialStageProvider();
        if (ssProvider != null) {
            ssProvider.reset();
        }

        // Transition to results mode
        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.SPECIAL_STAGE_RESULTS;
        resultsFrameCounter = 0;

        // Create results screen with current emerald count via provider
        int totalEmeralds = GameServices.gameState().getEmeraldCount();
        if (ssProvider != null) {
            resultsScreen = ssProvider.createResultsScreen(
                    ssRingsCollected, ssEmeraldCollected, ssStageIndex, totalEmeralds);
        }

        // Play act clear music
        AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_ACT_CLEAR);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        // Start fade-from-white to reveal the results screen
        FadeManager.getInstance().startFadeFromWhite(null);

        LOGGER.info("Entered Special Stage Results Screen (rings=" + ssRingsCollected +
                ", emerald=" + ssEmeraldCollected + ")");
    }

    /**
     * Exits the results screen and shows the title card before returning to the
     * level.
     * Performs fade-to-black transition before showing title card.
     */
    private void exitResultsScreen() {
        if (currentGameMode != GameMode.SPECIAL_STAGE_RESULTS) {
            return;
        }

        // Don't start another fade if one is already in progress
        FadeManager fadeManager = FadeManager.getInstance();
        if (fadeManager.isActive()) {
            return;
        }

        // Play the special stage exit sound (same as entry sound)
        AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_SPECIAL_STAGE_ENTRY);

        // Start fade-to-white, then show title card when complete
        fadeManager.startFadeToWhite(() -> {
            doExitResultsScreen();
        });

        LOGGER.info("Starting fade-to-white to exit Results Screen");
    }

    /**
     * Actually exits the results screen after fade-to-black completes.
     */
    private void doExitResultsScreen() {
        // Clean up results screen
        resultsScreen = null;

        // Restore level palettes (special stage overwrites them) - needed for title
        // card
        levelManager.reloadLevelPalettes();

        // Consume any pending title card request to prevent double title card
        // (we're manually entering the title card below)
        levelManager.consumeTitleCardRequest();

        // Set flag so exitTitleCard knows to restore checkpoint state
        returningFromSpecialStage = true;

        // Enter title card mode for the current zone/act
        int zoneIndex = levelManager.getCurrentZone();
        int actIndex = levelManager.getCurrentAct();
        enterTitleCardFromResults(zoneIndex, actIndex);

        LOGGER.info("Exited Results Screen, entering Title Card for zone " + zoneIndex + " act " + actIndex);
    }

    /**
     * Enters the title card from the results screen context.
     * Similar to enterTitleCard but allows entry from SPECIAL_STAGE_RESULTS mode.
     * Restores the player to their checkpoint position before showing title card.
     */
    private void enterTitleCardFromResults(int zoneIndex, int actIndex) {
        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.TITLE_CARD;

        // Restore player to checkpoint state BEFORE title card starts
        // This prevents the player from falling/dying during the title card animation
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null)
            mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            RespawnState checkpointState = levelManager.getCheckpointState();

            if (checkpointState != null && checkpointState.isActive()) {
                // Restore player and camera position from checkpoint (ROM-accurate)
                checkpointState.restoreToPlayer(playable, camera);
            } else {
                // No checkpoint - camera will follow player at level start position
                camera.updatePosition(true);
            }

            // Freeze all movement during title card
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
            playable.setAir(false);
            // Clear death/hurt state to prevent dying during title card
            playable.setDead(false);
            playable.setHurt(false);
            playable.setDeathCountdown(0);
            playable.setInvulnerableFrames(0);
            playable.setRolling(false);

            // Reset rings to 0 when returning from special stage
            LevelState gamestate = levelManager.getLevelGamestate();
            if (gamestate != null) {
                gamestate.setRings(0);
            }
        }

        // Initialize the title card manager
        getTitleCardProviderLazy().initialize(zoneIndex, actIndex);

        // Start zone music immediately when title card begins (not at the end)
        int zoneMusicId = levelManager.getCurrentLevelMusicId();
        if (zoneMusicId >= 0) {
            AudioManager.getInstance().playMusic(zoneMusicId);
            LOGGER.fine("Started zone music at title card: 0x" + Integer.toHexString(zoneMusicId));
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
    }

    /**
     * Enters the title card for the current zone/act.
     * Called when a level first loads or after player respawns.
     *
     * @param zoneIndex Zone index (0-10)
     * @param actIndex  Act index (0-2)
     */
    public void enterTitleCard(int zoneIndex, int actIndex) {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.TITLE_CARD;

        // Freeze the player during title card - full state reset
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null)
            mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            // Freeze all movement
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
            playable.setAir(false);
            // Clear death/hurt state to prevent dying during title card
            playable.setDead(false);
            playable.setHurt(false);
            playable.setDeathCountdown(0);
            playable.setInvulnerableFrames(0);
            playable.setRolling(false);
        }

        // Initialize the title card manager
        getTitleCardProviderLazy().initialize(zoneIndex, actIndex);

        // Snap camera to player position immediately so it's correct from the start
        // Normal updates during the title card will keep it settled
        camera.updatePosition(true);

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered Title Card for zone " + zoneIndex + " act " + actIndex);
    }

    /**
     * Exits the title card and returns to level mode.
     * Note: We do NOT reset the title card manager here because the overlay
     * (TEXT_WAIT and TEXT_EXIT phases) still needs to run. The title card
     * will reset itself when it reaches COMPLETE state, or when a new
     * title card is initialized.
     */
    private void exitTitleCard() {
        if (currentGameMode != GameMode.TITLE_CARD) {
            return;
        }

        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.LEVEL;

        // Don't reset title card - overlay phases (TEXT_WAIT, TEXT_EXIT) still need to
        // run
        // getTitleCardProviderLazy().reset();

        if (returningFromSpecialStage) {
            // Returning from special stage - checkpoint was already restored in
            // enterTitleCardFromResults()
            returningFromSpecialStage = false;
            LOGGER.info("Exited Title Card, returned to level from special stage at checkpoint");
        } else {
            // Normal title card exit (level start)
            // Physics has been running during title card, so player is already settled
            LOGGER.info("Exited Title Card, starting level");
        }

        // Notify listener of mode change
        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }
    }

    // ==================== Level Transition Methods with Fade ====================

    /**
     * Starts the fade-to-black transition for death respawn.
     */
    private void startRespawnFade() {
        LOGGER.info("Starting fade-to-black for respawn");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        AudioManager.getInstance().fadeOutMusic();

        // Start fade-to-black, then respawn when complete
        FadeManager.getInstance().startFadeToBlack(() -> {
            doRespawn();
        });
    }

    /**
     * Actually performs the respawn after fade-to-black completes.
     */
    private void doRespawn() {
        // Reload the current level (with title card)
        levelManager.loadCurrentLevel();

        // Start fade-from-black to reveal the title card
        FadeManager.getInstance().startFadeFromBlack(null);

        LOGGER.info("Respawned player, entering title card");
    }

    /**
     * Starts the fade-to-black transition for next act.
     */
    private void startNextActFade() {
        LOGGER.info("Starting fade-to-black for next act");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        AudioManager.getInstance().fadeOutMusic();

        // Start fade-to-black, then load next act when complete
        FadeManager.getInstance().startFadeToBlack(() -> {
            doNextAct();
        });
    }

    /**
     * Actually loads the next act after fade-to-black completes.
     */
    private void doNextAct() {
        try {
            levelManager.nextAct();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load next act", e);
        }

        // Start fade-from-black to reveal the title card
        FadeManager.getInstance().startFadeFromBlack(null);

        LOGGER.info("Loaded next act");
    }

    /**
     * Starts the fade-to-black transition for next zone.
     */
    private void startNextZoneFade() {
        LOGGER.info("Starting fade-to-black for next zone");

        // Fade out current music (ROM: s2.asm:4757 - level entry with title card)
        AudioManager.getInstance().fadeOutMusic();

        // Start fade-to-black, then load next zone when complete
        FadeManager.getInstance().startFadeToBlack(() -> {
            doNextZone();
        });
    }

    /**
     * Actually loads the next zone after fade-to-black completes.
     */
    private void doNextZone() {
        try {
            levelManager.nextZone();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load next zone", e);
        }

        // Start fade-from-black to reveal the title card
        FadeManager.getInstance().startFadeFromBlack(null);

        LOGGER.info("Loaded next zone");
    }

    /**
     * Gets the title card provider (for rendering).
     * 
     * @return the title card provider
     */
    public TitleCardProvider getTitleCardProvider() {
        return getTitleCardProviderLazy();
    }

    /**
     * Gets the current results screen object (for rendering).
     * 
     * @return the results screen object, or null if not in results mode
     */
    public ResultsScreen getResultsScreen() {
        return resultsScreen;
    }

    private void updateSpecialStageInput() {
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int upKey = configService.getInt(SonicConfiguration.UP);
        int downKey = configService.getInt(SonicConfiguration.DOWN);
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);

        SpecialStageProvider ssProvider = GameModuleRegistry.getCurrent().getSpecialStageProvider();
        if (ssProvider == null) {
            return;
        }

        if (inputHandler.isKeyPressed(KeyEvent.VK_F4)) {
            ssProvider.toggleAlignmentTestMode();
        }

        // Lag compensation adjustment (F6 decrease, F7 increase)
        if (inputHandler.isKeyPressed(KeyEvent.VK_F6)) {
            adjustLagCompensation(-0.05);
        }
        if (inputHandler.isKeyPressed(KeyEvent.VK_F7)) {
            adjustLagCompensation(0.05);
        }

        if (ssProvider.isAlignmentTestMode()) {
            if (inputHandler.isKeyPressed(leftKey)) {
                ssProvider.adjustAlignmentOffset(-1);
            }
            if (inputHandler.isKeyPressed(rightKey)) {
                ssProvider.adjustAlignmentOffset(1);
            }
            if (inputHandler.isKeyPressed(upKey)) {
                ssProvider.adjustAlignmentSpeed(0.1);
            }
            if (inputHandler.isKeyPressed(downKey)) {
                ssProvider.adjustAlignmentSpeed(-0.1);
            }
            if (inputHandler.isKeyPressed(KeyEvent.VK_SPACE)) {
                ssProvider.toggleAlignmentStepMode();
            }
            return;
        }

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

        ssProvider.handleInput(heldButtons, pressedButtons);
    }

    /**
     * Adjusts the lag compensation factor for the entire special stage simulation.
     * The lag compensation simulates original Mega Drive hardware lag frames,
     * affecting track animation, player movement, object speed, and all other
     * timing.
     *
     * @param delta Amount to adjust (positive = more lag compensation = slower
     *              simulation)
     */
    private void adjustLagCompensation(double delta) {
        SpecialStageProvider ssProvider = GameModuleRegistry.getCurrent().getSpecialStageProvider();
        if (ssProvider == null || !ssProvider.isInitialized()) {
            return;
        }

        double current = ssProvider.getLagCompensation();
        double newValue = current + delta;
        ssProvider.setLagCompensation(newValue);

        // Calculate effective simulation rate for display
        // Base is 60 fps. With lag compensation, effective = 60 * (1 - lagComp)
        double effectiveUpdates = 60.0 * (1.0 - ssProvider.getLagCompensation());

        LOGGER.info(String.format("Lag compensation: %.0f%% (effective ~%.1f updates/sec)",
                ssProvider.getLagCompensation() * 100, effectiveUpdates));
    }
}

