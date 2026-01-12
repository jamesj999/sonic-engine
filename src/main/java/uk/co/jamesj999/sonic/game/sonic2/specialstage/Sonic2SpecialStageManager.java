package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
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

        // Pass intro to renderer
        renderer.setIntro(intro);

        LOGGER.fine("Intro sequence initialized with ring requirement: " + ringReq);
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
        int playerPatternBase = trackPatternBase + 512;

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

        // Only update players when intro allows input
        if (intro == null || intro.isInputEnabled()) {
            updatePlayers();
        }
    }

    private void updatePlayers() {
        if (sonicPlayer != null && tailsPlayer != null) {
            sonicPlayer.update(heldButtons, pressedButtons);
            int delayedInput = sonicPlayer.getControlRecordEntry(8);
            tailsPlayer.update(delayedInput, 0);
        } else if (sonicPlayer != null) {
            sonicPlayer.update(heldButtons, pressedButtons);
        } else if (tailsPlayer != null) {
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

        renderer.renderPlayers();

        // Render intro UI (banner and messages) on top
        if (intro != null && !intro.isComplete()) {
            renderer.renderIntroUI();
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
}
