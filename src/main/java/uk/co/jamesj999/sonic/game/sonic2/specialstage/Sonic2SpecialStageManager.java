package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.debug.DebugSpecialStageSprites;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Manages Sonic 2 Special Stage state and rendering.
 *
 * The Special Stage uses a different rendering mode than normal levels:
 * - H32 mode (256 pixels wide instead of 320)
 * - Per-scanline horizontal scroll for the background "skydome" effect
 * - Pseudo-3D track rendering using pre-rendered mapping frames
 *
 * This manager handles:
 * - Loading special stage data from ROM
 * - Managing track animation state
 * - Rendering the background and track
 * - (Future) Player physics and object collision
 */
public class Sonic2SpecialStageManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageManager.class.getName());
    private static Sonic2SpecialStageManager instance;

    /**
     * Result state for special stage completion.
     */
    public enum ResultState {
        RUNNING,
        COMPLETED,
        FAILED
    }

    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();

    private Sonic2SpecialStageDataLoader dataLoader;
    private Rom rom;

    private boolean initialized = false;
    private int currentStage = 0;
    private ResultState resultState = ResultState.RUNNING;
    private boolean emeraldCollected = false;

    public static final int H32_WIDTH = 256;
    public static final int H32_HEIGHT = 224;

    private Sonic2TrackAnimator trackAnimator;

    private byte[] levelLayouts;
    private byte[][] trackFrames;
    private byte[] backgroundArt;
    private byte[] backgroundMainMappings;
    private byte[] backgroundLowerMappings;
    private byte[] combinedBackgroundMappings; // Combined: lower (rows 0-15) + main (rows 16-31)
    private byte[] skydomeScrollTable;
    private Palette[] palettes;

    private static final int SS_PATTERN_BASE = 0x1000;
    private int backgroundPatternBase;
    private int trackPatternBase;
    private int playerPatternBase;

    // Debug mode for viewing all sprite frames
    private boolean spriteDebugMode = false;

    private Sonic2SpecialStageRenderer renderer;
    private int frameCounter = 0;

    private int[] decodedTrackFrame;
    private int lastDecodedFrameIndex = -1;
    private boolean lastDecodedFlipped = false;

    private Sonic2SpecialStagePlayer sonicPlayer;
    private Sonic2SpecialStagePlayer tailsPlayer;
    private List<Sonic2SpecialStagePlayer> players = new ArrayList<>();

    private int heldButtons = 0;
    private int pressedButtons = 0;

    // Intro sequence
    private Sonic2SpecialStageIntro intro;
    private int hudPatternBase;
    private int startPatternBase;
    private int messagesPatternBase;

    // Object system (Phase 4)
    private Sonic2SpecialStageObjectManager objectManager;
    private Sonic2PerspectiveData perspectiveData;
    private int ringPatternBase;
    private int bombPatternBase;
    private int starsPatternBase;      // For ring sparkle animation
    private int explosionPatternBase;  // For bomb explosion animation
    private int emeraldPatternBase;    // For chaos emerald

    // Track state for object spawning
    private int lastDrawingIndex = -1;

    // Checkpoint system
    private Sonic2SpecialStageCheckpoint checkpoint;

    // Current ring requirement for the active checkpoint (for "rings to go" display)
    private int currentRingRequirement = 0;

    // Frame timing diagnostics
    private long lastFrameTime = 0;
    private int frameSampleCount = 0;
    private long frameSampleSum = 0;
    private static final int FRAME_SAMPLE_SIZE = 60;

    private Sonic2SpecialStageManager() {
    }

    public static synchronized Sonic2SpecialStageManager getInstance() {
        if (instance == null) {
            instance = new Sonic2SpecialStageManager();
        }
        return instance;
    }

    /**
     * Initializes the Special Stage manager with the given stage index.
     *
     * @param stageIndex The special stage to load (0-6)
     * @throws IOException If data loading fails
     */
    public void initialize(int stageIndex) throws IOException {
        // Reset any partial state from previous initialization attempts
        reset();

        try {
            // Get ROM from centralized RomManager
            rom = RomManager.getInstance().getRom();

            if (dataLoader == null) {
                dataLoader = new Sonic2SpecialStageDataLoader(rom);
            }

            this.currentStage = stageIndex;

            LOGGER.info("Initializing Special Stage " + (stageIndex + 1));

            loadData();
            setupPalettes();
            setupRenderer();
            setupPatterns();
            setupTrackAnimator();
            setupPlayers();
            setupIntro();
            setupObjectSystem();

            initialized = true;

            LOGGER.info("Special Stage " + (stageIndex + 1) + " initialized successfully");
        } catch (IOException e) {
            // Clean up partial state on failure
            LOGGER.severe("Failed to initialize Special Stage " + (stageIndex + 1) + ": " + e.getMessage());
            reset();
            throw e;
        }
    }

    /**
     * Sets up the intro sequence for the current stage.
     */
    private void setupIntro() {
        intro = new Sonic2SpecialStageIntro();

        // Get ring requirement for checkpoint 0 (first quarter)
        // Solo mode if only one player character, team mode if Sonic & Tails
        boolean teamMode = (sonicPlayer != null && tailsPlayer != null);
        int ringReq = 0;
        try {
            ringReq = dataLoader.getRingRequirement(currentStage, 0, teamMode);
        } catch (IOException e) {
            LOGGER.warning("Failed to load ring requirement: " + e.getMessage());
            ringReq = 30; // Default fallback
        }

        intro.initialize(currentStage, ringReq);

        // Store current ring requirement for "rings to go" display
        currentRingRequirement = ringReq;

        // Pass intro to renderer
        renderer.setIntro(intro);

        LOGGER.fine("Intro sequence initialized with ring requirement: " + ringReq);
    }

    /**
     * Sets up the object system (rings, bombs, perspective data).
     */
    private void setupObjectSystem() throws IOException {
        // Load perspective data
        perspectiveData = new Sonic2PerspectiveData();
        perspectiveData.load(dataLoader);

        // Initialize object manager
        objectManager = new Sonic2SpecialStageObjectManager(dataLoader);
        objectManager.initialize(currentStage);

        // Load ring and bomb art
        Pattern[] ringPatterns = dataLoader.getRingArtPatterns();
        ringPatternBase = messagesPatternBase + dataLoader.getMessagesArtPatterns().length;
        for (int i = 0; i < ringPatterns.length; i++) {
            graphicsManager.cachePatternTexture(ringPatterns[i], ringPatternBase + i);
        }
        LOGGER.fine("Cached " + ringPatterns.length + " ring patterns at base 0x" +
                   Integer.toHexString(ringPatternBase));

        Pattern[] bombPatterns = dataLoader.getBombArtPatterns();
        bombPatternBase = ringPatternBase + ringPatterns.length;
        for (int i = 0; i < bombPatterns.length; i++) {
            graphicsManager.cachePatternTexture(bombPatterns[i], bombPatternBase + i);
        }
        LOGGER.fine("Cached " + bombPatterns.length + " bomb patterns at base 0x" +
                   Integer.toHexString(bombPatternBase));

        // Load stars art (for ring sparkle animation)
        Pattern[] starsPatterns = dataLoader.getStarsArtPatterns();
        starsPatternBase = bombPatternBase + bombPatterns.length;
        for (int i = 0; i < starsPatterns.length; i++) {
            graphicsManager.cachePatternTexture(starsPatterns[i], starsPatternBase + i);
        }
        LOGGER.fine("Cached " + starsPatterns.length + " stars patterns at base 0x" +
                   Integer.toHexString(starsPatternBase));

        // Load explosion art (for bomb explosion animation)
        Pattern[] explosionPatterns = dataLoader.getExplosionArtPatterns();
        explosionPatternBase = starsPatternBase + starsPatterns.length;
        for (int i = 0; i < explosionPatterns.length; i++) {
            graphicsManager.cachePatternTexture(explosionPatterns[i], explosionPatternBase + i);
        }
        LOGGER.fine("Cached " + explosionPatterns.length + " explosion patterns at base 0x" +
                   Integer.toHexString(explosionPatternBase));

        // Load emerald art (for chaos emerald at stage end)
        Pattern[] emeraldPatterns = dataLoader.getEmeraldArtPatterns();
        emeraldPatternBase = explosionPatternBase + explosionPatterns.length;
        for (int i = 0; i < emeraldPatterns.length; i++) {
            graphicsManager.cachePatternTexture(emeraldPatterns[i], emeraldPatternBase + i);
        }
        LOGGER.fine("Cached " + emeraldPatterns.length + " emerald patterns at base 0x" +
                   Integer.toHexString(emeraldPatternBase));

        // Pass pattern bases to renderer
        renderer.setObjectPatternBases(ringPatternBase, bombPatternBase);
        renderer.setEffectPatternBases(starsPatternBase, explosionPatternBase);
        renderer.setEmeraldPatternBase(emeraldPatternBase);
        renderer.setObjectManager(objectManager);
        renderer.setPerspectiveData(perspectiveData);

        lastDrawingIndex = -1;

        // Setup checkpoint system
        setupCheckpointSystem();

        LOGGER.fine("Object system initialized");
    }

    /**
     * Sets up the checkpoint system and callback.
     */
    private void setupCheckpointSystem() {
        checkpoint = new Sonic2SpecialStageCheckpoint();
        renderer.setCheckpoint(checkpoint);

        // Set up music fade callback for when checkpoint fails
        checkpoint.setOnMusicFadeRequested(() -> {
            // Fade out the special stage music
            AudioManager.getInstance().stopMusic();
            LOGGER.info("Music fade requested - stopping special stage music");
        });

        // Set up checkpoint callback
        objectManager.setCheckpointCallback(new Sonic2SpecialStageObjectManager.CheckpointCallback() {
            @Override
            public void onCheckpoint(int checkpointNumber, int ringsCollected) {
                handleCheckpointReached(checkpointNumber, ringsCollected);
            }

            @Override
            public void onEmerald() {
                handleEmeraldReached();
            }
        });

        LOGGER.fine("Checkpoint system initialized");
    }

    /**
     * Handles checkpoint reached event from the object manager.
     *
     * @param checkpointNumber The checkpoint number (1-4)
     * @param ringsCollected Current rings collected
     */
    private void handleCheckpointReached(int checkpointNumber, int ringsCollected) {
        // Determine if this is team mode or solo mode
        boolean teamMode = (sonicPlayer != null && tailsPlayer != null);

        // Get ring requirement for this checkpoint (quarter = checkpointNumber - 1)
        int quarter = checkpointNumber - 1;
        if (quarter < 0) quarter = 0;
        if (quarter > 3) quarter = 3;

        int ringRequirement;
        try {
            ringRequirement = dataLoader.getRingRequirement(currentStage, quarter, teamMode);
        } catch (IOException e) {
            LOGGER.warning("Failed to get ring requirement: " + e.getMessage());
            ringRequirement = 30; // Fallback
        }

        // Is this the final checkpoint (checkpoint 4 / quarter 3)?
        boolean isFinalCheckpoint = (checkpointNumber >= 4);

        // Trigger the checkpoint animation and check result
        Sonic2SpecialStageCheckpoint.Result result = checkpoint.triggerCheckpoint(
                checkpointNumber, ringRequirement, ringsCollected, isFinalCheckpoint);

        // Play appropriate sound
        if (result == Sonic2SpecialStageCheckpoint.Result.FAILED) {
            // Play error sound for failure (SndID_Error = $ED)
            AudioManager.getInstance().playSfx(GameSound.ERROR);
            LOGGER.info("Checkpoint FAILED: needed " + ringRequirement + ", had " + ringsCollected);
        } else {
            // Play checkpoint sound for success
            AudioManager.getInstance().playSfx(GameSound.CHECKPOINT);
            LOGGER.info("Checkpoint PASSED: needed " + ringRequirement + ", had " + ringsCollected);

            // Update ring requirement for next checkpoint (for "rings to go" display)
            if (!isFinalCheckpoint) {
                try {
                    currentRingRequirement = dataLoader.getRingRequirement(currentStage, quarter + 1, teamMode);
                    LOGGER.fine("Next checkpoint requirement: " + currentRingRequirement);
                } catch (IOException e) {
                    LOGGER.warning("Failed to get next ring requirement: " + e.getMessage());
                }
            }
        }

        // Handle result
        if (result == Sonic2SpecialStageCheckpoint.Result.STAGE_COMPLETE) {
            // Final checkpoint passed - will award emerald
            LOGGER.info("Stage complete! Emerald will be awarded.");
        } else if (result == Sonic2SpecialStageCheckpoint.Result.FAILED) {
            // Stage failed - will eject player
            LOGGER.info("Stage failed! Player will be ejected.");
            // Note: actual ejection happens after message animation completes
        }
    }

    /**
     * Handles emerald reached event from the object manager.
     * Configures the spawned emerald object with ring requirements and manager reference.
     */
    private void handleEmeraldReached() {
        LOGGER.info("Emerald marker reached - configuring emerald object");

        // Get the emerald object that was just spawned
        Sonic2SpecialStageEmerald emerald = objectManager.getActiveEmerald();
        if (emerald != null) {
            // Set the ring requirement for collection check
            emerald.setRingRequirement(currentRingRequirement);
            // Set the manager reference so emerald can check ring count and end stage
            emerald.setManager(this);
            LOGGER.info("Emerald configured with ring requirement: " + currentRingRequirement);
        }
    }

    private void loadData() throws IOException {
        LOGGER.fine("Loading Special Stage data from ROM...");

        levelLayouts = dataLoader.getLevelLayouts();
        LOGGER.fine("Level layouts: " + levelLayouts.length + " bytes");

        trackFrames = dataLoader.getTrackFrames();
        LOGGER.fine("Track frames: " + trackFrames.length + " frames loaded");

        backgroundMainMappings = dataLoader.getBackgroundMainMappings();
        LOGGER.fine("Background main mappings: " + backgroundMainMappings.length + " bytes");

        backgroundLowerMappings = dataLoader.getBackgroundLowerMappings();
        LOGGER.fine("Background lower mappings: " + backgroundLowerMappings.length + " bytes");

        // Combine background mappings: Main goes first (rows 0-15), then Lower (rows
        // 16-31)
        // Correct order per s2.asm: MapEng_SpecialBack ($A000) then
        // MapEng_SpecialBackBottom
        int expectedMainSize = 32 * 16 * 2; // 1024 bytes for rows 0-15
        int expectedLowerSize = 32 * 16 * 2; // 1024 bytes for rows 16-31
        combinedBackgroundMappings = new byte[expectedMainSize + expectedLowerSize];

        // Copy Main mappings to rows 0-15
        int mainCopyLen = Math.min(backgroundMainMappings.length, expectedMainSize);
        System.arraycopy(backgroundMainMappings, 0, combinedBackgroundMappings, 0, mainCopyLen);

        // Copy Lower mappings to rows 16-31
        int lowerCopyLen = Math.min(backgroundLowerMappings.length, expectedLowerSize);
        System.arraycopy(backgroundLowerMappings, 0, combinedBackgroundMappings, expectedMainSize, lowerCopyLen);

        LOGGER.fine("Combined background mappings: " + combinedBackgroundMappings.length + " bytes");

        skydomeScrollTable = dataLoader.getSkydomeScrollTable();
        LOGGER.fine("Skydome scroll table: " + skydomeScrollTable.length + " bytes");
    }

    private void setupPalettes() {
        palettes = Sonic2SpecialStagePalette.createPalettes(currentStage);

        for (int i = 0; i < palettes.length; i++) {
            graphicsManager.cachePaletteTexture(palettes[i], i);
        }

        LOGGER.fine("Special Stage palettes cached");
    }

    private void setupPatterns() throws IOException {
        backgroundPatternBase = SS_PATTERN_BASE;
        trackPatternBase = SS_PATTERN_BASE + 256;
        playerPatternBase = trackPatternBase + 512;

        Pattern[] bgPatterns = dataLoader.getBackgroundArtPatterns();
        for (int i = 0; i < bgPatterns.length; i++) {
            graphicsManager.cachePatternTexture(bgPatterns[i], backgroundPatternBase + i);
        }
        LOGGER.fine("Cached " + bgPatterns.length + " background patterns");

        Pattern[] trackPatterns = dataLoader.getTrackArtPatterns();
        for (int i = 0; i < trackPatterns.length; i++) {
            graphicsManager.cachePatternTexture(trackPatterns[i], trackPatternBase + i);
        }
        LOGGER.fine("Cached " + trackPatterns.length + " track patterns at base 0x" +
                Integer.toHexString(trackPatternBase) + " (range 0x" +
                Integer.toHexString(trackPatternBase) + "-0x" +
                Integer.toHexString(trackPatternBase + trackPatterns.length - 1) + ")");

        Pattern[] playerPatterns = dataLoader.getPlayerArtPatterns();
        for (int i = 0; i < playerPatterns.length; i++) {
            graphicsManager.cachePatternTexture(playerPatterns[i], playerPatternBase + i);
        }
        LOGGER.fine("Cached " + playerPatterns.length + " player patterns");

        // Load HUD and START banner art for intro sequence
        hudPatternBase = playerPatternBase + playerPatterns.length;
        Pattern[] hudPatterns = dataLoader.getHudArtPatterns();
        for (int i = 0; i < hudPatterns.length; i++) {
            graphicsManager.cachePatternTexture(hudPatterns[i], hudPatternBase + i);
        }
        LOGGER.fine("Cached " + hudPatterns.length + " HUD patterns");

        startPatternBase = hudPatternBase + hudPatterns.length;
        Pattern[] startPatterns = dataLoader.getStartArtPatterns();
        for (int i = 0; i < startPatterns.length; i++) {
            graphicsManager.cachePatternTexture(startPatterns[i], startPatternBase + i);
        }
        LOGGER.fine("Cached " + startPatterns.length + " START banner patterns");

        messagesPatternBase = startPatternBase + startPatterns.length;
        Pattern[] messagesPatterns = dataLoader.getMessagesArtPatterns();
        for (int i = 0; i < messagesPatterns.length; i++) {
            graphicsManager.cachePatternTexture(messagesPatterns[i], messagesPatternBase + i);
        }
        LOGGER.fine("Cached " + messagesPatterns.length + " Messages patterns");

        // Now set the pattern bases on the renderer (after they have valid values)
        renderer.setPatternBases(backgroundPatternBase, trackPatternBase);
        renderer.setPlayerPatternBase(playerPatternBase);
        renderer.setIntroPatternBases(hudPatternBase, startPatternBase, messagesPatternBase);

        LOGGER.fine("Special Stage art loaded: " + bgPatterns.length + " bg, " +
                trackPatterns.length + " track, " + playerPatterns.length + " player, " +
                hudPatterns.length + " HUD, " + startPatterns.length + " START, " +
                messagesPatterns.length + " Messages patterns");

        // Update debug sprite viewer with all pattern bases
        DebugSpecialStageSprites debugSprites = DebugSpecialStageSprites.getInstance();
        debugSprites.setPlayerPatternBase(playerPatternBase);
        debugSprites.setHudPatternBase(hudPatternBase, hudPatterns.length);
        debugSprites.setStartPatternBase(startPatternBase, startPatterns.length);
        debugSprites.setMessagesPatternBase(messagesPatternBase, messagesPatterns.length);
    }

    private void setupRenderer() {
        renderer = new Sonic2SpecialStageRenderer(graphicsManager);
        // Pattern bases are set in setupPatterns() after they have valid values
        LOGGER.fine("Special Stage renderer initialized");
    }

    private void setupTrackAnimator() throws IOException {
        trackAnimator = new Sonic2TrackAnimator(dataLoader);

        // Use the real stage layout data from ROM
        trackAnimator.initialize(currentStage);

        lastDecodedFrameIndex = -1;
        decodedTrackFrame = null;

        LOGGER.fine("Track animator initialized");
    }

    private void setupPlayers() {
        players.clear();
        sonicPlayer = null;
        tailsPlayer = null;

        String characterCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (characterCode == null) {
            characterCode = "sonic";
        }
        characterCode = characterCode.toLowerCase();

        switch (characterCode) {
            case "tails":
                tailsPlayer = new Sonic2SpecialStagePlayer(
                        Sonic2SpecialStagePlayer.PlayerType.TAILS, true);
                players.add(tailsPlayer);
                LOGGER.fine("Special Stage: Tails alone");
                break;

            case "sonic_and_tails":
                sonicPlayer = new Sonic2SpecialStagePlayer(
                        Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
                players.add(sonicPlayer);

                tailsPlayer = new Sonic2SpecialStagePlayer(
                        Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
                players.add(tailsPlayer);

                sonicPlayer.setOtherPlayer(tailsPlayer);
                tailsPlayer.setOtherPlayer(sonicPlayer);
                LOGGER.fine("Special Stage: Sonic and Tails");
                break;

            case "sonic":
            default:
                sonicPlayer = new Sonic2SpecialStagePlayer(
                        Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
                players.add(sonicPlayer);
                LOGGER.fine("Special Stage: Sonic alone");
                break;
        }

        renderer.setPlayers(players);
    }

    /**
     * Updates the Special Stage state for one frame.
     */
    public void update() {
        if (!initialized) {
            return;
        }

        // Frame timing diagnostic - measure actual FPS
        long now = System.nanoTime();
        if (lastFrameTime != 0) {
            long delta = now - lastFrameTime;
            frameSampleSum += delta;
            frameSampleCount++;
            if (frameSampleCount >= FRAME_SAMPLE_SIZE) {
                double avgMs = (frameSampleSum / (double) frameSampleCount) / 1_000_000.0;
                double actualFps = 1000.0 / avgMs;
                LOGGER.fine(String.format("Actual FPS: %.1f (%.2f ms/frame)", actualFps, avgMs));
                frameSampleCount = 0;
                frameSampleSum = 0;
            }
        }
        lastFrameTime = now;

        frameCounter++;

        // Update intro sequence
        if (intro != null) {
            intro.update();
        }

        // Track animation always runs (even during intro)
        boolean frameChanged = trackAnimator.update();

        if (frameChanged || decodedTrackFrame == null) {
            decodeCurrentTrackFrame();
        }

        if (trackAnimator.isStageComplete()) {
            trackAnimator.resetStageComplete();
        }

        // Only update players and objects when intro allows input
        if (intro == null || intro.isInputEnabled()) {
            updatePlayers();
            updateObjects();
        }

        // Update checkpoint animation
        if (checkpoint != null && checkpoint.isActive()) {
            boolean checkpointComplete = checkpoint.update();
            if (checkpointComplete) {
                handleCheckpointAnimationComplete();
            }
        }
    }

    /**
     * Called when a checkpoint animation sequence completes.
     */
    private void handleCheckpointAnimationComplete() {
        Sonic2SpecialStageCheckpoint.Result lastResult = checkpoint.getLastResult();

        if (lastResult == Sonic2SpecialStageCheckpoint.Result.FAILED) {
            // Stage failed - mark as failed and prepare for ejection
            markFailed();
            LOGGER.info("Checkpoint animation complete - stage FAILED, ejecting player");
        } else if (lastResult == Sonic2SpecialStageCheckpoint.Result.STAGE_COMPLETE) {
            // Stage complete - mark as completed with emerald
            markCompleted(true);
            LOGGER.info("Checkpoint animation complete - stage COMPLETE with emerald!");
        } else if (lastResult == Sonic2SpecialStageCheckpoint.Result.PASSED) {
            // Checkpoint passed - reset "rings to go" display and show next requirement
            if (objectManager != null) {
                objectManager.resetRingsToGoEnabled();
            }

            // Show "GET XX RINGS" message for the next checkpoint
            if (intro != null && currentRingRequirement > 0) {
                intro.showRingRequirementMessage(currentRingRequirement);
                LOGGER.info("Checkpoint animation complete - PASSED, showing next requirement: " +
                           currentRingRequirement + " rings");
            }
        }
    }

    /**
     * Updates objects (rings, bombs) and handles segment transitions.
     */
    private void updateObjects() {
        if (objectManager == null || trackAnimator == null) {
            return;
        }

        // Check for segment transition (drawing index reaches 4)
        int drawingIndex = trackAnimator.getCurrentFrameInSegment() % 5;

        // Process new segment when drawing_index reaches 4 and segment changed
        if (drawingIndex == 4 && lastDrawingIndex != 4) {
            int segmentIndex = trackAnimator.getCurrentSegmentIndex();
            int segmentType = trackAnimator.getCurrentSegmentType();
            objectManager.processSegment(segmentIndex, segmentType);
        }
        lastDrawingIndex = drawingIndex;

        // Update all active objects
        int currentFrame = trackAnimator.getCurrentTrackFrameIndex();
        boolean flipped = trackAnimator.getEffectiveFlipState();
        int speedFactor = trackAnimator.getSpeedFactor();
        objectManager.update(currentFrame, flipped, speedFactor);

        // Update screen positions using perspective data
        if (perspectiveData != null) {
            for (Sonic2SpecialStageObject obj : objectManager.getActiveObjects()) {
                obj.updateScreenPosition(perspectiveData, currentFrame, flipped);
            }
        }

        // Check collisions between players and objects
        checkObjectCollisions();
    }

    /**
     * Checks collisions between players and objects (rings/bombs).
     *
     * Original game collision (Obj61_TestCollision in s2.asm):
     * - Only checks when animIndex == 8 (closest perspective)
     * - Compares player angle to object angle with ±10 threshold
     * - Uses circular/wraparound arithmetic for angle comparison
     *
     * Invulnerability behavior:
     * - During hurt animation (routineSecondary != 0): NO collision with anything
     * - During invulnerability (ssDplcTimer > 0, routineSecondary == 0):
     *   - Rings CAN be collected
     *   - Bombs CANNOT hit (player is invulnerable)
     * - Multiple bombs hitting same frame: Each plays sound (accurate to original)
     */
    private void checkObjectCollisions() {
        if (objectManager == null) {
            return;
        }

        // Collision threshold: ±10 angle units (0x0A), matching original game
        final int ANGLE_THRESHOLD = 10;

        for (Sonic2SpecialStageObject obj : objectManager.getActiveObjects()) {
            // Only test collidable objects (animIndex == 8)
            if (!obj.isCollidable()) {
                continue;
            }

            int objAngle = obj.getAngle() & 0xFF;

            // Test against each player
            for (Sonic2SpecialStagePlayer player : players) {
                if (player == null) {
                    continue;
                }

                // Original game checks routine_secondary != 0 (hurt animation) for ALL objects
                // During hurt animation, no collision with anything
                if (player.isHurt()) {
                    continue;
                }

                // For bombs only: also skip if invulnerable (ssDplcTimer > 0)
                // Rings CAN be collected during invulnerability
                if (obj.isBomb() && player.isInvulnerable()) {
                    continue;
                }

                // Original game compares angles, not screen coordinates
                // Check if player angle is within ±threshold of object angle
                int playerAngle = player.getAngle() & 0xFF;

                // Calculate angle difference with wraparound handling
                int diff = (playerAngle - objAngle) & 0xFF;

                // Check if within threshold (accounting for 0/255 wraparound)
                // diff <= threshold means player is slightly ahead of object
                // diff >= 256-threshold means player is slightly behind (wrapped)
                if (diff <= ANGLE_THRESHOLD || diff >= (256 - ANGLE_THRESHOLD)) {
                    handleObjectCollision(obj, player);
                }
            }
        }
    }

    /**
     * Handles a collision between a player and an object.
     */
    private void handleObjectCollision(Sonic2SpecialStageObject obj, Sonic2SpecialStagePlayer player) {
        if (obj.isRing()) {
            Sonic2SpecialStageRing ring = (Sonic2SpecialStageRing) obj;
            ring.collect();
            objectManager.collectRing();
            AudioManager.getInstance().playSfx(GameSound.RING);
            LOGGER.fine("Collected ring! Total: " + objectManager.getRingsCollected());
        } else if (obj.isBomb()) {
            Sonic2SpecialStageBomb bomb = (Sonic2SpecialStageBomb) obj;
            bomb.explode();
            player.triggerHit();
            // Original game plays SndID_SlowSmash for bomb explosion
            AudioManager.getInstance().playSfx(GameSound.SLOW_SMASH);
            // Ring spill sound plays when rings are actually lost
            int ringsLost = objectManager.loseRingsFromBombHit();
            if (ringsLost > 0) {
                AudioManager.getInstance().playSfx(GameSound.RING_SPILL);
            }
            LOGGER.fine("Hit bomb! Lost " + ringsLost + " rings. Remaining: " +
                       objectManager.getRingsCollected());
        }
    }

    private void updatePlayers() {
        // Get the global animation frame timer from the track animator
        int animTimer = trackAnimator.getPlayerAnimFrameTimer();

        if (sonicPlayer != null && tailsPlayer != null) {
            sonicPlayer.setGlobalAnimFrameTimer(animTimer);
            sonicPlayer.update(heldButtons, pressedButtons);
            int delayedInput = sonicPlayer.getControlRecordEntry(8);
            tailsPlayer.setGlobalAnimFrameTimer(animTimer);
            tailsPlayer.update(delayedInput, 0);
        } else if (sonicPlayer != null) {
            sonicPlayer.setGlobalAnimFrameTimer(animTimer);
            sonicPlayer.update(heldButtons, pressedButtons);
        } else if (tailsPlayer != null) {
            tailsPlayer.setGlobalAnimFrameTimer(animTimer);
            tailsPlayer.update(heldButtons, pressedButtons);
        }

        pressedButtons = 0;
    }

    /**
     * Handles input for the Special Stage.
     * Call this from the input handler with the current button state.
     *
     * @param held    Bitmask of currently held buttons
     * @param pressed Bitmask of newly pressed buttons this frame
     */
    public void handleInput(int held, int pressed) {
        this.heldButtons = held;
        this.pressedButtons |= pressed;
    }

    private boolean diagnosticDone = false;
    private boolean flipDiagnosticDone = false;

    /**
     * Decodes the current track frame if needed.
     */
    private void decodeCurrentTrackFrame() {
        int frameIndex = trackAnimator.getCurrentTrackFrameIndex();
        boolean flipped = trackAnimator.getEffectiveFlipState();

        if (frameIndex == lastDecodedFrameIndex && flipped == lastDecodedFlipped && decodedTrackFrame != null) {
            return;
        }

        if (trackFrames != null && frameIndex >= 0 && frameIndex < trackFrames.length) {
            byte[] frameData = trackFrames[frameIndex];

            // Diagnostic logging disabled - set to true to enable verbose frame decode logging
            boolean runDiag = false;

            decodedTrackFrame = Sonic2TrackFrameDecoder.decodeFrame(frameData, flipped, runDiag);
            lastDecodedFrameIndex = frameIndex;
            lastDecodedFlipped = flipped;

            if (frameCounter % 60 == 0) {
                LOGGER.fine("Decoded track frame " + frameIndex +
                        " (flipped=" + flipped + "), segment " +
                        trackAnimator.getCurrentSegmentIndex() +
                        ", type " + trackAnimator.getCurrentSegmentType());
            }
        }
    }

    /**
     * Renders the Special Stage.
     */
    public void draw() {
        if (!initialized || renderer == null) {
            return;
        }

        renderer.renderBackground(combinedBackgroundMappings, 0, 0);

        int trackFrameIndex = trackAnimator.getCurrentTrackFrameIndex();
        renderer.renderTrack(trackFrameIndex, decodedTrackFrame);

        // Render objects (rings, bombs) between track and players
        renderer.renderObjects();

        renderer.renderPlayers();

        // Render intro UI (banner and messages) on top
        if (intro != null && !intro.isComplete()) {
            renderer.renderIntroUI();
        }

        // Render ring counter HUD (after intro completes)
        if (intro == null || intro.isComplete()) {
            renderer.renderRingCounter(objectManager != null ? objectManager.getRingsCollected() : 0);

            // Render "rings to go" counter if:
            // 1. Not in checkpoint animation
            // 2. The display has been enabled (by encountering a $FC marker)
            if ((checkpoint == null || !checkpoint.isActive()) &&
                    objectManager != null && objectManager.isRingsToGoEnabled()) {
                int ringsCollected = objectManager.getRingsCollected();
                int ringsToGo = currentRingRequirement - ringsCollected;
                renderer.renderRingsToGoHUD(ringsToGo, frameCounter);
            }
        }

        // Render checkpoint UI (messages and hand) when active
        if (checkpoint != null && checkpoint.isActive()) {
            renderer.renderCheckpointUI();
        }
    }

    /**
     * Gets the current track frame index (0-55).
     */
    public int getCurrentTrackFrameIndex() {
        if (!initialized || trackAnimator == null)
            return 0;
        return trackAnimator.getCurrentTrackFrameIndex();
    }

    /**
     * Gets the raw track frame data for the current animation state.
     */
    public byte[] getCurrentTrackFrameData() {
        if (!initialized || trackFrames == null || trackAnimator == null)
            return null;
        int frameIndex = trackAnimator.getCurrentTrackFrameIndex();
        if (frameIndex >= 0 && frameIndex < trackFrames.length) {
            return trackFrames[frameIndex];
        }
        return null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public Sonic2TrackAnimator getTrackAnimator() {
        return trackAnimator;
    }

    public Sonic2SpecialStagePlayer getSonicPlayer() {
        return sonicPlayer;
    }

    public Sonic2SpecialStagePlayer getTailsPlayer() {
        return tailsPlayer;
    }

    public List<Sonic2SpecialStagePlayer> getPlayers() {
        return players;
    }

    /**
     * Resets the Special Stage manager state.
     */
    public void reset() {
        // Stop any playing music when resetting
        AudioManager.getInstance().stopMusic();

        initialized = false;
        currentStage = 0;

        trackAnimator = null;
        decodedTrackFrame = null;
        lastDecodedFrameIndex = -1;
        lastDecodedFlipped = false;

        levelLayouts = null;
        trackFrames = null;
        backgroundArt = null;
        backgroundMainMappings = null;
        backgroundLowerMappings = null;
        combinedBackgroundMappings = null;
        skydomeScrollTable = null;
        palettes = null;

        sonicPlayer = null;
        tailsPlayer = null;
        players.clear();

        heldButtons = 0;
        pressedButtons = 0;

        intro = null;
        hudPatternBase = 0;
        startPatternBase = 0;
        messagesPatternBase = 0;

        // Object system
        if (objectManager != null) {
            objectManager.reset();
        }
        objectManager = null;
        perspectiveData = null;
        ringPatternBase = 0;
        bombPatternBase = 0;
        starsPatternBase = 0;
        explosionPatternBase = 0;
        emeraldPatternBase = 0;
        lastDrawingIndex = -1;

        // Checkpoint system
        if (checkpoint != null) {
            checkpoint.reset();
        }
        checkpoint = null;
        currentRingRequirement = 0;

        resultState = ResultState.RUNNING;
        emeraldCollected = false;
        diagnosticDone = false;
        flipDiagnosticDone = false;
    }

    /**
     * Gets the intro sequence manager.
     */
    public Sonic2SpecialStageIntro getIntro() {
        return intro;
    }

    /**
     * Checks if the intro sequence is still playing.
     */
    public boolean isIntroPlaying() {
        return intro != null && !intro.isComplete();
    }

    /**
     * Gets the current result state.
     */
    public ResultState getResultState() {
        return resultState;
    }

    /**
     * Checks if the special stage has finished (completed or failed).
     */
    public boolean isFinished() {
        return resultState == ResultState.COMPLETED || resultState == ResultState.FAILED;
    }

    /**
     * Marks the stage as failed (e.g., hit too many bombs, time over).
     */
    public void markFailed() {
        this.resultState = ResultState.FAILED;
    }

    /**
     * Marks the stage as completed.
     * 
     * @param gotEmerald true if the emerald was collected
     */
    public void markCompleted(boolean gotEmerald) {
        this.resultState = ResultState.COMPLETED;
        this.emeraldCollected = gotEmerald;
    }

    /**
     * Sets whether an emerald was collected (for gameplay logic to call).
     */
    public void setEmeraldCollected(boolean collected) {
        this.emeraldCollected = collected;
    }

    /**
     * Checks if an emerald was collected in this run.
     */
    public boolean hasEmeraldCollected() {
        return emeraldCollected;
    }

    /**
     * Toggles the sprite debug mode which shows all 18 animation frames.
     */
    public void toggleSpriteDebugMode() {
        spriteDebugMode = !spriteDebugMode;
        DebugSpecialStageSprites.getInstance().setEnabled(spriteDebugMode);
        LOGGER.info("Sprite debug mode: " + (spriteDebugMode ? "ON" : "OFF"));
    }

    /**
     * Checks if sprite debug mode is active.
     */
    public boolean isSpriteDebugMode() {
        return spriteDebugMode;
    }

    /**
     * Gets the player pattern base for debug rendering.
     */
    public int getPlayerPatternBase() {
        return playerPatternBase;
    }

    /**
     * Gets the HUD pattern base for debug rendering.
     */
    public int getHudPatternBase() {
        return hudPatternBase;
    }

    /**
     * Gets the START banner pattern base for debug rendering.
     */
    public int getStartPatternBase() {
        return startPatternBase;
    }

    /**
     * Gets the messages pattern base for debug rendering.
     */
    public int getMessagesPatternBase() {
        return messagesPatternBase;
    }

    /**
     * Gets the object manager.
     */
    public Sonic2SpecialStageObjectManager getObjectManager() {
        return objectManager;
    }

    /**
     * Gets the perspective data.
     */
    public Sonic2PerspectiveData getPerspectiveData() {
        return perspectiveData;
    }

    /**
     * Gets the current ring count.
     */
    public int getRingsCollected() {
        return objectManager != null ? objectManager.getRingsCollected() : 0;
    }

    /**
     * Gets the checkpoint manager.
     */
    public Sonic2SpecialStageCheckpoint getCheckpoint() {
        return checkpoint;
    }

    /**
     * Checks if a checkpoint animation is currently playing.
     */
    public boolean isCheckpointActive() {
        return checkpoint != null && checkpoint.isActive();
    }

    /**
     * Gets the emerald pattern base for rendering emerald sprites.
     */
    public int getEmeraldPatternBase() {
        return emeraldPatternBase;
    }

    /**
     * Gets the data loader for accessing special stage art patterns.
     */
    public Sonic2SpecialStageDataLoader getDataLoader() {
        return dataLoader;
    }
}
