package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
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

    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();

    private Sonic2SpecialStageDataLoader dataLoader;
    private Rom rom;

    private boolean initialized = false;
    private int currentStage = 0;

    public static final int H32_WIDTH = 256;
    public static final int H32_HEIGHT = 224;

    private Sonic2TrackAnimator trackAnimator;

    private byte[] levelLayouts;
    private byte[][] trackFrames;
    private byte[] backgroundArt;
    private byte[] backgroundMainMappings;
    private byte[] backgroundLowerMappings;
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
        if (rom == null) {
            rom = new Rom();
            rom.open(configService.getString(SonicConfiguration.ROM_FILENAME));
        }

        if (dataLoader == null) {
            dataLoader = new Sonic2SpecialStageDataLoader(rom);
        }

        this.currentStage = stageIndex;

        LOGGER.info("Initializing Special Stage " + (stageIndex + 1));

        loadData();
        setupPalettes();
        setupPatterns();
        setupRenderer();
        setupTrackAnimator();
        setupPlayers();

        initialized = true;

        LOGGER.info("Special Stage " + (stageIndex + 1) + " initialized successfully");
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

        int maxTrackTile = 372;
        for (int i = trackPatterns.length; i < maxTrackTile; i++) {
            Pattern placeholder = new Pattern();
            int shade = (i * 3) % 16;
            if (shade == 0) shade = 1;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    placeholder.setPixel(x, y, (byte) shade);
                }
            }
            graphicsManager.cachePatternTexture(placeholder, trackPatternBase + i);
        }
        LOGGER.fine("Cached " + trackPatterns.length + " real + " + (maxTrackTile - trackPatterns.length) + " placeholder track patterns");

        Pattern[] playerPatterns = dataLoader.getPlayerArtPatterns();
        for (int i = 0; i < playerPatterns.length; i++) {
            graphicsManager.cachePatternTexture(playerPatterns[i], playerPatternBase + i);
        }
        LOGGER.fine("Cached " + playerPatterns.length + " player patterns");

        renderer.setPlayerPatternBase(playerPatternBase);

        LOGGER.info("Special Stage art loaded: " + bgPatterns.length + " bg, " +
                    maxTrackTile + " track, " + playerPatterns.length + " player patterns");
    }

    private void setupRenderer() {
        renderer = new Sonic2SpecialStageRenderer(graphicsManager);
        renderer.setPatternBases(backgroundPatternBase, trackPatternBase);
        LOGGER.fine("Special Stage renderer initialized");
    }

    private void setupTrackAnimator() throws IOException {
        trackAnimator = new Sonic2TrackAnimator(dataLoader);

        trackAnimator.initializeWithMockLayout();

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

        boolean frameChanged = trackAnimator.update();

        if (frameChanged || decodedTrackFrame == null) {
            decodeCurrentTrackFrame();
        }

        if (trackAnimator.isStageComplete()) {
            trackAnimator.resetStageComplete();
        }

        updatePlayers();
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
     * @param held Bitmask of currently held buttons
     * @param pressed Bitmask of newly pressed buttons this frame
     */
    public void handleInput(int held, int pressed) {
        this.heldButtons = held;
        this.pressedButtons |= pressed;
    }

    /**
     * Decodes the current track frame if needed.
     */
    private void decodeCurrentTrackFrame() {
        int frameIndex = trackAnimator.getCurrentTrackFrameIndex();
        boolean flipped = trackAnimator.isCurrentSegmentFlipped();

        if (frameIndex == lastDecodedFrameIndex && flipped == lastDecodedFlipped && decodedTrackFrame != null) {
            return;
        }

        if (trackFrames != null && frameIndex >= 0 && frameIndex < trackFrames.length) {
            byte[] frameData = trackFrames[frameIndex];
            decodedTrackFrame = Sonic2TrackFrameDecoder.decodeFrame(frameData, flipped);
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

        renderer.renderBackground(backgroundMainMappings, 0, 0);

        int trackFrameIndex = trackAnimator.getCurrentTrackFrameIndex();
        renderer.renderTrack(trackFrameIndex, decodedTrackFrame);

        renderer.renderPlaceholderPlayers();
    }

    /**
     * Gets the current track frame index (0-55).
     */
    public int getCurrentTrackFrameIndex() {
        if (!initialized || trackAnimator == null) return 0;
        return trackAnimator.getCurrentTrackFrameIndex();
    }

    /**
     * Gets the raw track frame data for the current animation state.
     */
    public byte[] getCurrentTrackFrameData() {
        if (!initialized || trackFrames == null || trackAnimator == null) return null;
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
        skydomeScrollTable = null;
        palettes = null;

        sonicPlayer = null;
        tailsPlayer = null;
        players.clear();

        heldButtons = 0;
        pressedButtons = 0;
    }
}

