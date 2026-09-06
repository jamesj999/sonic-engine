package com.openggf.game.sonic2.specialstage;

import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;

import com.openggf.audio.GameSound;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;

import com.openggf.debug.GlyphBatchRenderer;
import com.openggf.debug.FontSize;

import com.openggf.graphics.GLCommandable;

import com.openggf.debug.DebugColor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.openggf.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

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
    private final BackgroundCommandPool backgroundCommandPool = new BackgroundCommandPool();

    /**
     * Result state for special stage completion.
     */
    public enum ResultState {
        RUNNING,
        COMPLETED,
        FAILED
    }

    enum PlayerBootstrapPhase {
        WAIT_FIRST_DRAWING_WRAP,
        WAIT_SECOND_DURATION_WRAP,
        INITIALIZED;

        PlayerBootstrapPhase afterDrawingIndexWrap() {
            return switch (this) {
                case WAIT_FIRST_DRAWING_WRAP -> WAIT_SECOND_DURATION_WRAP;
                case WAIT_SECOND_DURATION_WRAP, INITIALIZED -> INITIALIZED;
            };
        }
    }

    private final SonicConfigurationService configService;
    private final GraphicsManager graphicsManager;
    private final Sonic2SpecialStageSpriteDebug debugSprites;

    private Sonic2SpecialStageDataLoader dataLoader;
    private Rom rom;

    private boolean initialized = false;
    private int currentStage = 0;
    private ResultState resultState = ResultState.RUNNING;
    private boolean emeraldCollected = false;

    public static final int H32_WIDTH = 256;
    public static final int H32_HEIGHT = 224;

    private Sonic2TrackAnimator trackAnimator;
    // Vint_S2SS copies SS_New_Speed_Factor into SS_Cur_Speed_Factor. The
    // initial request is 12; Obj59 later requests zero on failure
    // (s2.asm:960-975, 6640, 72417-72423). Zero is a value, not a sentinel.
    private boolean speedPromotionPending;
    private int pendingSpeedFactor;
    // One-shot exposure of the already-constructed Obj09/Obj10 instances at
    // the ROM object-creation boundary, independent of speed promotion state.
    private boolean initialPlayerSpawnPending;
    // The ids are written before either player routine runs. RunObjects executes
    // only after the two startup waits have observed their drawing-index wraps
    // (s2.asm:6628-6631, 6644-6663).
    private PlayerBootstrapPhase playerBootstrapPhase = PlayerBootstrapPhase.WAIT_FIRST_DRAWING_WRAP;

    private byte[] levelLayouts;
    private byte[][] trackFrames;
    private byte[] backgroundArt;
    private byte[] backgroundMainMappings;
    private byte[] backgroundLowerMappings;
    private byte[] combinedBackgroundMappings; // Combined: lower (rows 0-15) + main (rows 16-31)
    private byte[] skydomeScrollTable;
    private Palette[] palettes;

    private static final int SS_PATTERN_BASE = PatternAtlasRange.LEVEL_TILES.base() + 0x1000;
    private int backgroundPatternBase;
    private int trackPatternBase;
    private int playerPatternBase;

    // Debug mode for viewing all sprite frames
    private boolean spriteDebugMode = false;

    private Sonic2SpecialStageRenderer renderer;
    private SpecialStageBackgroundRenderer bgRenderer;
    private long backgroundStageGeneration;
    private int frameCounter = 0;
    /** Last executed logic/render phase. */
    private int renderFrameCounter = 0;

    private enum PlaneDebugMode {
        BOTH("Plane A + Plane B"),
        PLANE_A_ONLY("Plane A only"),
        PLANE_B_ONLY("Plane B only"),
        NONE("Planes off");

        private final String label;

        PlaneDebugMode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        PlaneDebugMode next() {
            int nextIndex = (ordinal() + 1) % values().length;
            return values()[nextIndex];
        }

        boolean renderPlaneA() {
            return this == BOTH || this == PLANE_A_ONLY;
        }

        boolean renderPlaneB() {
            return this == BOTH || this == PLANE_B_ONLY;
        }
    }

    private PlaneDebugMode planeDebugMode = PlaneDebugMode.BOTH;

    private int[] decodedTrackFrame;
    private int lastDecodedFrameIndex = -1;
    private boolean lastDecodedFlipped = false;

    private Sonic2SpecialStagePlayer sonicPlayer;
    private Sonic2SpecialStagePlayer tailsPlayer;
    private List<Sonic2SpecialStagePlayer> players = new ArrayList<>();

    private int heldButtons = 0;
    private int pressedButtons = 0;
    private int p2HeldButtons = 0;
    private int p2LogicalButtons = 0;
    private boolean recurringMainPassPending;
    private boolean introFirstRecurringPassDeferred;
    private int pendingMainHeldButtons;
    private int pendingMainPressedButtons;
    private int pendingMainP2HeldButtons;
    private int pendingMainP2LogicalButtons;
    private boolean pendingMainCheckpointStep;
    private int previousPhysicalHeldButtons;
    private int previousPhysicalPressedButtons;
    private int previousPhysicalP2HeldButtons;
    private int previousPhysicalP2LogicalButtons;
    private int tailsControlCounter = 0;
    private final int[] tailsCtrlRecordBuf = new int[16];
    // The ROM exposes one global byte, SS_Swap_Positions_Flag. Obj09 clears it,
    // either player's jump toggles it, and both players read it while changing
    // depth (s2.asm:69058, 69247-69253, 69505-69518).
    private int swapPositionsFlag;

    // Intro sequence
    private Sonic2SpecialStageIntro intro;
    private int hudPatternBase;
    private int startPatternBase;
    private int messagesPatternBase;
    private int tailsTextPatternBase;

    // Object system (Phase 4)
    private Sonic2SpecialStageObjectManager objectManager;
    private Sonic2PerspectiveData perspectiveData;
    private int ringPatternBase;
    private int bombPatternBase;
    private int starsPatternBase; // For ring sparkle animation
    private int explosionPatternBase; // For bomb explosion animation
    private int emeraldPatternBase; // For chaos emerald
    private int shadowFlatPatternBase; // Horizontal shadow art
    private int shadowDiagPatternBase; // Diagonal shadow art
    private int shadowSidePatternBase; // Vertical shadow art

    // Track state for object spawning
    private int lastDrawingIndex = -1;

    // Checkpoint system
    private Sonic2SpecialStageCheckpoint checkpoint;
    private boolean checkpointRainbowPaletteActive = false;
    private int rainbowPaletteCycleIndex = 0; // Cycles 0-3 for color cycling
    private boolean pendingCheckpoint = false;
    private int pendingCheckpointNumber = 0;
    private int pendingRingRequirement = 0;
    private int pendingRingsCollected = 0;
    private boolean pendingFinalCheckpoint = false;
    private boolean alignmentTestMode = false;
    private boolean alignmentTestSavedRainbowPalette = false;
    private Sonic2SpecialStageCheckpoint alignmentCheckpoint;
    private boolean alignmentPendingCheckpoint = false;
    private int alignmentFrameIndex = 0;
    private int alignmentFrameTimer = 0;
    private int alignmentTrackFrameIndex = -1;
    private int alignmentLastDecodedFrameIndex = -1;
    private int[] alignmentDecodedTrackFrame;
    private int alignmentDrawingIndex = 0;
    private int alignmentTriggerOffsetFrames = 0;
    private double alignmentRainbowSpeedScale = 1.0;
    private double alignmentRainbowSpeedAccumulator = 0.0;
    private boolean alignmentStepByTrackFrame = false;
    private GlyphBatchRenderer alignmentTextRenderer;

    // Current ring requirement for the active checkpoint (for "rings to go"
    // display)
    private int currentRingRequirement = 0;

    // Frame timing diagnostics
    private long lastFrameTime = 0;
    private int frameSampleCount = 0;
    private long frameSampleSum = 0;
    private static final int FRAME_SAMPLE_SIZE = 60;

    // Fine timing diagnostics (wall-clock based)
    private long diagnosticWallStartTime = 0;
    private int diagnosticUpdateCount = 0;
    private int diagnosticTrackAdvances = 0;
    private DiagnosticClock diagnosticClock = DiagnosticClock.SYSTEM;
    private Boolean fineDiagnosticsOverride;
    private boolean diagnosticEpochActive;
    private boolean liveLagSimulationEnabled = true;
    /** Whether {@code SSInitPalAndData}'s palette upload has happened (see setupPalettes). */
    private boolean stagePalettesUploaded;
    /**
     * Live reproduction of the masked-interrupt entry load
     * ({@link Sonic2SpecialStageLagModel#ENTRY_LOAD_LAG_FRAMES}). Only
     * {@link #armLiveEntryLoadHold()} sets it; neither startup policy does,
     * so ordinary play skips the span and trace replay takes it from the
     * recorded lag rows.
     */
    private int liveEntryLoadHoldFrames;

    // Skydome scroll state (accumulated horizontal scroll for background)
    private int skydomeScrollX = 0;
    private boolean alternateScrollBuffer = false; // SS_Alternate_HorizScroll_Buf
    private boolean lastAlternateScrollBuffer = false; // SS_Last_Alternate_HorizScroll_Buf
    private int drawingIndex = 0; // SSTrack_drawing_index (0-4, increments each frame)
    private int lastAnimFrame = 0; // SSTrack_last_anim_frame - frame index at last update

    // Vertical scroll state (Vscroll_Factor_BG)
    private int vScrollBG = 0;

    // Debug tracking for H-scroll
    private int hScrollDebugTotal = 0;
    private int hScrollDebugFrames = 0;
    private int lastDebugSegmentIndex = -1;

    // Background scroll delta lookup table (off_6DEE from disassembly)
    // Each entry is 5 values corresponding to drawing_index 0-4
    // Index is byte offset / 2 into the word-sized offset table
    private static final int[][] BG_SCROLL_DELTA_TABLE = {
            { 2, 2, 2, 2, 2 }, // Index 0 (byte_6E04)
            { 4, 4, 5, 4, 5 }, // Index 1 (byte_6E09)
            { 11, 11, 11, 11, 12 }, // Index 2 (byte_6E0E)
            { 0, 0, 1, 0, 0 }, // Index 3 (byte_6E13)
            { 1, 1, 1, 1, 1 }, // Index 4 (byte_6E18)
            { 9, 9, 8, 9, 9 }, // Index 5 (byte_6E1D)
            { 9, 9, 9, 9, 10 }, // Index 6 (byte_6E22)
            { 7, 7, 6, 7, 7 }, // Index 7 (byte_6E27)
            { 0, 1, 1, 1, 0 }, // Index 8 (byte_6E2C)
            { 4, 3, 3, 3, 4 }, // Index 9 (byte_6E31)
            { 0, 0, -1, 0, 0 } // Index 10 (byte_6E36) - $FF = -1 for wrapping effect
    };

    // Maps byte offset to table index: offset / 2
    // Rise frame table indices (byte offsets into off_6DEE)
    // -1 means skip (no scroll this frame)
    private static final int[] VSCROLL_RISE_TABLE_INDICES = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // Frames 0-9: skip
            4, 4, 1, 2, 2, 2, 2, 2, 2, 5, // Frames 10-19 (8/2=4, 2/2=1, 4/2=2, 10/2=5)
            6, 7, 9, 8 // Frames 20-23 (12/2=6, 14/2=7, 18/2=9, 16/2=8)
    };

    // Checkpoint rainbow palette - static states (SSRainbowPaletteColors at
    // word_35548)
    // OFF: dark red when no checkpoint active, ON: bright red when entering
    // checkpoint
    private static final int[] CHECKPOINT_RAINBOW_PALETTE_ON = { 0x0EE, 0x0CC, 0x088 };
    private static final int[] CHECKPOINT_RAINBOW_PALETTE_OFF = { 0x0EE, 0x088, 0x044 };

    // Checkpoint rainbow palette cycling colors (PalCycle_SS at
    // word_54C4-word_54C8)
    // Cycles every 8 frames through: Red -> Green -> Yellow -> Magenta
    private static final int[][] CHECKPOINT_RAINBOW_CYCLE_COLORS = {
            { 0x0EE, 0x0CC, 0x088 }, // Index 0: Red shades
            { 0x0E0, 0x0C0, 0x080 }, // Index 1: Green shades
            { 0xEE0, 0xCC0, 0x880 }, // Index 2: Yellow shades
            { 0xE0E, 0xC0C, 0x808 } // Index 3: Magenta shades
    };
    private static final int RAINBOW_CYCLE_FRAME_INTERVAL = 8; // Cycles every 8 frames
    // Checkpoint gate trigger uses MapSpec_Straight4..MapSpec_Drop1 range
    // (Obj5A_Init).
    private static final int CHECKPOINT_TRIGGER_FRAME = 0x14; // Straight4
    private static final int CHECKPOINT_TRIGGER_OFFSET = 1; // Offset within straight animation (alignment tuned)

    // Drop frame table indices
    private static final int[] VSCROLL_DROP_TABLE_INDICES = {
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, // Frames 0-10: skip
            8, 9, 7, 6, 5, 2, 2, 2, 2, 2, 2, 1, 0 // Frames 11-23 (16/2=8, 18/2=9, etc.)
    };

    // Straight frame table indices - pattern {6, 6, $14, $14} = {3, 3, 10, 10}
    // (byte offset 6 / 2 = 3, byte offset $14 / 2 = 10)
    private static final int[] VSCROLL_STRAIGHT_TABLE_INDICES = {
            3, 3, 10, 10, 3, 3, 10, 10, 3, 3, 10, 10, 3, 3, 10, 10
    };

    // H-scroll table indices for turn segments (frames 0-11)
    // Based on disassembly d1 values: 0, 2, 4 which map to table indices 0, 1, 2
    // Frame 0: d1=0 → index 0
    // Frame 1: d1=2 → index 1
    // Frames 2-9: d1=4 → index 2
    // Frame 10: d1=2 → index 1
    // Frame 11: d1=0 → index 0
    private static final int[] HSCROLL_TURN_TABLE_INDICES = {
            0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 1, 0
    };

    public Sonic2SpecialStageManager() {
        this(new Sonic2SpecialStageSpriteDebug(), null, null);
    }

    public Sonic2SpecialStageManager(Sonic2SpecialStageSpriteDebug debugSprites) {
        this(debugSprites, null, null);
    }

    Sonic2SpecialStageManager(Sonic2SpecialStageSpriteDebug debugSprites,
                              SonicConfigurationService configService,
                              GraphicsManager graphicsManager) {
        this.debugSprites = debugSprites;
        this.configService = configService;
        this.graphicsManager = graphicsManager;
    }

    void setDiagnosticClockForTesting(DiagnosticClock diagnosticClock) {
        this.diagnosticClock = java.util.Objects.requireNonNull(diagnosticClock);
    }

    void setFineDiagnosticsOverrideForTesting(Boolean fineDiagnosticsOverride) {
        this.fineDiagnosticsOverride = fineDiagnosticsOverride;
    }

    void runTimingDiagnosticsForTesting(boolean trackAdvanced) {
        updateTimingDiagnostics(true, trackAdvanced);
    }

    private SonicConfigurationService configuration() {
        return configService != null ? configService : GameServices.configuration();
    }

    private GraphicsManager graphicsManager() {
        return graphicsManager != null ? graphicsManager : GameServices.graphics();
    }

    private GraphicsManager graphicsManagerOrNull() {
        GraphicsManager resolved;
        try {
            resolved = graphicsManager != null ? graphicsManager : GameServices.graphics();
        } catch (IllegalStateException ignored) {
            return null;
        }
        return (resolved.isHeadlessMode() || resolved.isGlInitialized()) ? resolved : null;
    }

    /**
     * Initializes the Special Stage manager with the given stage index.
     *
     * @param stageIndex The special stage to load (0-6)
     * @throws IOException If data loading fails
     */
    public void initialize(int stageIndex) throws IOException {
        // Reset any partial state from previous initialization attempts
        prepareForInitialization();

        try {
            // Get ROM from centralized RomManager
            rom = GameServices.rom().getRom();

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

    void prepareForInitialization() {
        reset();
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
        GraphicsManager graphicsManager = graphicsManager();

        // Load perspective data
        perspectiveData = new Sonic2PerspectiveData();
        perspectiveData.load(dataLoader);

        // Initialize object manager
        objectManager = new Sonic2SpecialStageObjectManager(dataLoader);
        objectManager.initialize(currentStage);

        // Load ring and bomb art
        Pattern[] ringPatterns = dataLoader.getRingArtPatterns();
        ringPatternBase = tailsTextPatternBase + dataLoader.getTailsTextArtPatterns().length;
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

        // Load shadow art (3 types: flat, diagonal, side)
        // Shadow uses palette line 3 as per original game (obj63.asm: make_art_tile
        // with pal=3)
        Pattern[] shadowFlatPatterns = dataLoader.getShadowHorizPatterns();
        shadowFlatPatternBase = emeraldPatternBase + emeraldPatterns.length;
        for (int i = 0; i < shadowFlatPatterns.length; i++) {
            graphicsManager.cachePatternTexture(shadowFlatPatterns[i], shadowFlatPatternBase + i);
        }
        LOGGER.fine("Cached " + shadowFlatPatterns.length + " flat shadow patterns at base 0x" +
                Integer.toHexString(shadowFlatPatternBase));

        Pattern[] shadowDiagPatterns = dataLoader.getShadowDiagPatterns();
        shadowDiagPatternBase = shadowFlatPatternBase + shadowFlatPatterns.length;
        for (int i = 0; i < shadowDiagPatterns.length; i++) {
            graphicsManager.cachePatternTexture(shadowDiagPatterns[i], shadowDiagPatternBase + i);
        }
        LOGGER.fine("Cached " + shadowDiagPatterns.length + " diagonal shadow patterns at base 0x" +
                Integer.toHexString(shadowDiagPatternBase));

        Pattern[] shadowSidePatterns = dataLoader.getShadowVertPatterns();
        shadowSidePatternBase = shadowDiagPatternBase + shadowDiagPatterns.length;
        for (int i = 0; i < shadowSidePatterns.length; i++) {
            graphicsManager.cachePatternTexture(shadowSidePatterns[i], shadowSidePatternBase + i);
        }
        LOGGER.fine("Cached " + shadowSidePatterns.length + " side shadow patterns at base 0x" +
                Integer.toHexString(shadowSidePatternBase));

        // Pass pattern bases to renderer
        renderer.setObjectPatternBases(ringPatternBase, bombPatternBase);
        renderer.setEffectPatternBases(starsPatternBase, explosionPatternBase);
        renderer.setEmeraldPatternBase(emeraldPatternBase);
        renderer.setShadowPatternBases(shadowFlatPatternBase, shadowDiagPatternBase, shadowSidePatternBase);
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
            // Fade out the special stage music gradually (preserves any SFX playing)
            GameServices.audio().fadeOutMusic();
            LOGGER.info("Music fade requested - fading special stage music");
        });

        // The ROM's checkpoint ring check reads the ring words live at the end of
        // the rainbow (loc_35978, s2.asm:71843-71853), so rings picked up while the
        // rainbow plays still count towards the requirement.
        checkpoint.setLiveRingCount(this::getRingsCollected);

        checkpoint.setOnCheckpointResolved((result, checkpointNumber, ringRequirement,
                ringsCollected, isFinalCheckpoint) -> {
            handleCheckpointResolved(result, checkpointNumber, ringRequirement,
                    ringsCollected, isFinalCheckpoint);
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
     * @param ringsCollected   Current rings collected
     */
    private void handleCheckpointReached(int checkpointNumber, int ringsCollected) {
        // Determine if this is team mode or solo mode
        boolean teamMode = (sonicPlayer != null && tailsPlayer != null);

        // Get ring requirement for this checkpoint (quarter = checkpointNumber - 1)
        int quarter = checkpointNumber - 1;
        if (quarter < 0)
            quarter = 0;
        if (quarter > 3)
            quarter = 3;

        int ringRequirement;
        try {
            ringRequirement = dataLoader.getRingRequirement(currentStage, quarter, teamMode);
        } catch (IOException e) {
            LOGGER.warning("Failed to get ring requirement: " + e.getMessage());
            ringRequirement = 30; // Fallback
        }

        // Is this the final checkpoint (checkpoint 4 / quarter 3)?
        boolean isFinalCheckpoint = (checkpointNumber >= 4);

        pendingCheckpoint = true;
        pendingCheckpointNumber = checkpointNumber;
        pendingRingRequirement = ringRequirement;
        pendingRingsCollected = ringsCollected;
        pendingFinalCheckpoint = isFinalCheckpoint;
        LOGGER.fine("Queued checkpoint " + checkpointNumber + " until straight gate frame");
    }

    /**
     * Handles emerald reached event from the object manager.
     * Configures the spawned emerald object with ring requirements and manager
     * reference.
     * Also loads the per-stage emerald palette colors.
     */
    private void handleEmeraldReached() {
        LOGGER.info("Emerald marker reached - configuring emerald object");

        // Load and apply the per-stage emerald palette colors
        // From disassembly: loc_35F76 loads 3 colors from SS Emerald.bin into palette
        // line 3
        // at offsets $16, $18, $1A (color indices 11, 12, 13)
        applyEmeraldPalette();

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

    /**
     * Applies the per-stage emerald palette colors to palette line 3.
     * The emerald art uses colors 11-13 of palette line 3, which are loaded
     * from SS Emerald.bin per-stage when the emerald spawns.
     */
    private void applyEmeraldPalette() {
        GraphicsManager graphicsManager = graphicsManagerOrNull();
        if (palettes == null || graphicsManager == null) {
            return;
        }

        int[] emeraldColors = Sonic2SpecialStagePalette.getEmeraldColors(currentStage);
        if (emeraldColors == null || emeraldColors.length != 3) {
            LOGGER.warning("Failed to load emerald palette colors for stage " + (currentStage + 1));
            return;
        }

        // Apply the 3 emerald colors to palette line 3 at indices 11, 12, 13
        Palette palette = palettes[3];
        palette.setColor(11, Sonic2SpecialStagePalette.genesisColorToPaletteColor(emeraldColors[0]));
        palette.setColor(12, Sonic2SpecialStagePalette.genesisColorToPaletteColor(emeraldColors[1]));
        palette.setColor(13, Sonic2SpecialStagePalette.genesisColorToPaletteColor(emeraldColors[2]));

        // Update the cached palette texture
        graphicsManager.cachePaletteTexture(palette, 3);

        LOGGER.info("Applied emerald palette for stage " + (currentStage + 1) + ": " +
                String.format("%04X, %04X, %04X", emeraldColors[0], emeraldColors[1], emeraldColors[2]));
    }

    /**
     * Handles checkpoint result after the rainbow animation completes.
     */
    private void handleCheckpointResolved(Sonic2SpecialStageCheckpoint.Result result, int checkpointNumber,
            int ringRequirement, int ringsCollected, boolean isFinalCheckpoint) {
        applyCheckpointRainbowPalette(false);

        if (result == Sonic2SpecialStageCheckpoint.Result.FAILED) {
            // Play error sound for failure (SndID_Error = $ED)
            GameServices.audio().playSfx(GameSound.ERROR);
            LOGGER.info("Checkpoint FAILED: needed " + ringRequirement + ", had " + ringsCollected);
        } else {
            // Play checkpoint sound for success
            GameServices.audio().playSfx(GameSound.CHECKPOINT);
            LOGGER.info("Checkpoint PASSED: needed " + ringRequirement + ", had " + ringsCollected);

            // Update ring requirement for next checkpoint (for "rings to go" display)
            if (!isFinalCheckpoint) {
                boolean teamMode = (sonicPlayer != null && tailsPlayer != null);
                int quarter = Math.max(0, Math.min(3, checkpointNumber - 1));
                try {
                    currentRingRequirement = dataLoader.getRingRequirement(currentStage, quarter + 1, teamMode);
                    LOGGER.fine("Next checkpoint requirement: " + currentRingRequirement);
                } catch (IOException e) {
                    LOGGER.warning("Failed to get next ring requirement: " + e.getMessage());
                }
            }
        }

        if (result == Sonic2SpecialStageCheckpoint.Result.STAGE_COMPLETE) {
            // Final checkpoint passed - will award emerald
            LOGGER.info("Stage complete! Emerald will be awarded.");
        } else if (result == Sonic2SpecialStageCheckpoint.Result.FAILED) {
            // Stage failed - will eject player
            LOGGER.info("Stage failed! Player will be ejected.");
            // Note: actual ejection happens after message animation completes
        }
    }

    private void applyCheckpointRainbowPalette(boolean bright) {
        GraphicsManager graphicsManager = graphicsManagerOrNull();
        if (palettes == null || graphicsManager == null) {
            return;
        }
        if (checkpointRainbowPaletteActive == bright) {
            return;
        }

        // ROM uses palette line 4 (index 3) for checkpoint rainbow, modifying colors
        // 11-13
        // Ring art intentionally uses only colors 0-10, so rainbow cycling shouldn't
        // affect rings
        int[] colors = bright ? CHECKPOINT_RAINBOW_PALETTE_ON : CHECKPOINT_RAINBOW_PALETTE_OFF;
        Palette palette = palettes[3];
        palette.setColor(11, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[0]));
        palette.setColor(12, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[1]));
        palette.setColor(13, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[2]));

        graphicsManager.cachePaletteTexture(palette, 3);
        checkpointRainbowPaletteActive = bright;

        // Reset cycle index when rainbow state changes
        if (bright) {
            rainbowPaletteCycleIndex = 0;
        }
    }

    /**
     * Updates the checkpoint rainbow palette cycling.
     * Based on PalCycle_SS in s2.asm (lines 6859-6873).
     * Cycles through Red -> Green -> Yellow -> Magenta every 8 frames
     * while the checkpoint rainbow animation is active.
     */
    private void updateRainbowPaletteCycle() {
        GraphicsManager graphicsManager = graphicsManagerOrNull();
        if (!checkpointRainbowPaletteActive || palettes == null || graphicsManager == null) {
            return;
        }

        // Only update every 8 frames (matches original: andi.b #7,d0; bne.s +)
        if ((renderFrameCounter & 7) != 0) {
            return;
        }

        // Get current cycle colors and advance index
        int[] colors = CHECKPOINT_RAINBOW_CYCLE_COLORS[rainbowPaletteCycleIndex];
        rainbowPaletteCycleIndex = (rainbowPaletteCycleIndex + 1) & 3; // Wrap 0-3

        // Apply colors to palette 3, indices 11-13 (ROM-accurate)
        Palette palette = palettes[3];
        palette.setColor(11, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[0]));
        palette.setColor(12, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[1]));
        palette.setColor(13, Sonic2SpecialStagePalette.genesisColorToPaletteColor(colors[2]));

        graphicsManager.cachePaletteTexture(palette, 3);
    }

    private void tryStartPendingCheckpoint() {
        if (!pendingCheckpoint || checkpoint == null || checkpoint.isActive() || trackAnimator == null) {
            return;
        }

        if (trackAnimator.getCurrentSegmentType() != SEGMENT_STRAIGHT) {
            return;
        }

        int gateIndexBase = 0;
        for (int i = 0; i < ANIM_STRAIGHT.length; i++) {
            if (ANIM_STRAIGHT[i] == CHECKPOINT_TRIGGER_FRAME) {
                gateIndexBase = i;
                break;
            }
        }
        int gateIndex = Math.floorMod(gateIndexBase + CHECKPOINT_TRIGGER_OFFSET, ANIM_STRAIGHT.length);
        if (trackAnimator.getCurrentFrameInSegment() != gateIndex) {
            return;
        }

        applyCheckpointRainbowPalette(true);
        checkpoint.beginCheckpoint(pendingCheckpointNumber, pendingRingRequirement,
                pendingRingsCollected, pendingFinalCheckpoint);
        pendingCheckpoint = false;
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

        // Combine background mappings for 32-row VRAM plane:
        // From disassembly SSPlaneB_Background (line 9155):
        // MapEng_SpecialBackBottom -> planeLoc(32,0,0) = row 0 (TOP of VRAM)
        // MapEng_SpecialBack -> planeLoc(32,0,16) = row 16 (BOTTOM of VRAM)
        // VDP row 0 is at the TOP of the screen.
        int expectedLowerSize = 32 * 16 * 2; // 1024 bytes for rows 0-15 (Lower/Bottom)
        int expectedMainSize = 32 * 16 * 2; // 1024 bytes for rows 16-31 (Main)
        combinedBackgroundMappings = new byte[expectedLowerSize + expectedMainSize];

        // Copy Lower (Bottom) mappings to rows 0-15 (top of screen)
        int lowerCopyLen = Math.min(backgroundLowerMappings.length, expectedLowerSize);
        System.arraycopy(backgroundLowerMappings, 0, combinedBackgroundMappings, 0, lowerCopyLen);

        // Copy Main mappings to rows 16-31 (bottom of screen)
        int mainCopyLen = Math.min(backgroundMainMappings.length, expectedMainSize);
        System.arraycopy(backgroundMainMappings, 0, combinedBackgroundMappings, expectedLowerSize, mainCopyLen);

        LOGGER.fine("Combined background mappings: " + combinedBackgroundMappings.length + " bytes");

        skydomeScrollTable = dataLoader.getSkydomeScrollTable();
        LOGGER.fine("Skydome scroll table: " + skydomeScrollTable.length + " bytes");
    }

    private void setupPalettes() {
        palettes = Sonic2SpecialStagePalette.createPalettes(currentStage);
        // SSInitPalAndData (s2.asm:10232, called at 6639) runs after
        // Pal_FadeToWhite and the masked-interrupt load, so the level's
        // palette stays in the shared lines through the fade over the level;
        // the upload happens when the intro leaves PRE_ROLL.
        stagePalettesUploaded = false;
    }

    /** {@code SSInitPalAndData}: the stage palette replaces the level's. */
    private void uploadStagePalettes() {
        GraphicsManager graphicsManager = graphicsManagerOrNull();
        if (graphicsManager != null && palettes != null) {
            for (int i = 0; i < palettes.length; i++) {
                graphicsManager.cachePaletteTexture(palettes[i], i);
            }
        }
        stagePalettesUploaded = true;
    }

    private void setupPatterns() throws IOException {
        GraphicsManager graphicsManager = graphicsManager();
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

        tailsTextPatternBase = messagesPatternBase + messagesPatterns.length;
        Pattern[] tailsTextPatterns = dataLoader.getTailsTextArtPatterns();
        for (int i = 0; i < tailsTextPatterns.length; i++) {
            graphicsManager.cachePatternTexture(tailsTextPatterns[i], tailsTextPatternBase + i);
        }
        LOGGER.fine("Cached " + tailsTextPatterns.length + " TAILS text patterns");

        // Now set the pattern bases on the renderer (after they have valid values)
        renderer.setPatternBases(backgroundPatternBase, trackPatternBase);
        renderer.setPlayerPatternBase(playerPatternBase);
        renderer.setIntroPatternBases(hudPatternBase, startPatternBase, messagesPatternBase);
        renderer.setTailsTextPatternBase(tailsTextPatternBase);

        LOGGER.fine("Special Stage art loaded: " + bgPatterns.length + " bg, " +
                trackPatterns.length + " track, " + playerPatterns.length + " player, " +
                hudPatterns.length + " HUD, " + startPatterns.length + " START, " +
                messagesPatterns.length + " Messages patterns");

        // Update debug sprite viewer with all pattern bases
        debugSprites.setPlayerPatternBase(playerPatternBase);
        debugSprites.setHudPatternBase(hudPatternBase, hudPatterns.length);
        debugSprites.setStartPatternBase(startPatternBase, startPatterns.length);
        debugSprites.setMessagesPatternBase(messagesPatternBase, messagesPatterns.length);
    }

    private void setupRenderer() throws IOException {
        GraphicsManager graphicsManager = graphicsManager();
        renderer = new Sonic2SpecialStageRenderer(graphicsManager);
        renderer.beginStaticBackgroundStage(backgroundStageGeneration);
        renderer.onRenderContextGenerationChanged(graphicsManager.getPatternAtlas());
        // Pattern bases are set in setupPatterns() after they have valid values

        // Initialize shader-based background renderer
        bgRenderer = new SpecialStageBackgroundRenderer(graphicsManager);
        bgRenderer.init();
        LOGGER.fine("Special Stage background renderer initialized with shader");

        LOGGER.fine("Special Stage renderer initialized");
    }

    private void setupTrackAnimator() throws IOException {
        trackAnimator = new Sonic2TrackAnimator(dataLoader);

        // Use the real stage layout data from ROM
        trackAnimator.initialize(currentStage);
        requestSpeedFactor(12);

        lastDecodedFrameIndex = -1;
        decodedTrackFrame = null;
        Sonic2TrackFrameDecoder.invalidateDecodedFrameCache();

        LOGGER.fine("Track animator initialized");
    }

    /** Models a write to SS_New_Speed_Factor for the following Vint_S2SS. */
    void requestSpeedFactor(int speedFactor) {
        pendingSpeedFactor = Math.max(0, Math.min(14, speedFactor));
        speedPromotionPending = true;
    }

    /**
     * Vint_S2SS promotes the requested factor before its timer work
     * (s2.asm:960-975). Keeping this at the VInt boundary prevents Obj59's
     * RunObjects write from changing the current pass retroactively.
     */
    private void promotePendingSpeedFactorAtVint() {
        if (!speedPromotionPending) {
            return;
        }
        trackAnimator.setSpeedFactor(pendingSpeedFactor);
        speedPromotionPending = false;
    }

    /**
     * Test-only seam exposing {@link #setupPlayers()} to same-package tests
     * that construct the manager without a renderer. The resulting test team
     * is active/spawned immediately because there is no initialized intro
     * timeline to advance through the ROM creation boundary.
     */
    void setupPlayersForTest() {
        setupPlayers();
        initializePlayerScalarStateFromRomObjectRoutines();
        setPlayersSpawned(true);
        initialPlayerSpawnPending = false;
        playerBootstrapPhase = PlayerBootstrapPhase.INITIALIZED;
    }

    private void setupPlayers() {
        players.clear();
        sonicPlayer = null;
        tailsPlayer = null;
        initialPlayerSpawnPending = true;
        playerBootstrapPhase = PlayerBootstrapPhase.WAIT_FIRST_DRAWING_WRAP;

        String characterCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(configuration());
        if (characterCode == null) {
            characterCode = "sonic";
        }
        characterCode = characterCode.toLowerCase();
        boolean tailsSidekick = ActiveGameplayTeamResolver.resolveSidekicks(configuration())
                .stream().map(String::toLowerCase).anyMatch("tails"::equals);

        if ("tails".equals(characterCode)) {
            tailsPlayer = new Sonic2SpecialStagePlayer(
                    Sonic2SpecialStagePlayer.PlayerType.TAILS, true, this);
            players.add(tailsPlayer);
            LOGGER.fine("Special Stage: Tails alone");
        } else if (tailsSidekick) {
            sonicPlayer = new Sonic2SpecialStagePlayer(
                    Sonic2SpecialStagePlayer.PlayerType.SONIC, true, this);
            players.add(sonicPlayer);

            tailsPlayer = new Sonic2SpecialStagePlayer(
                    Sonic2SpecialStagePlayer.PlayerType.TAILS, false, this);
            players.add(tailsPlayer);

            sonicPlayer.setOtherPlayer(tailsPlayer);
            tailsPlayer.setOtherPlayer(sonicPlayer);
            LOGGER.fine("Special Stage: Sonic and Tails");
        } else {
            sonicPlayer = new Sonic2SpecialStagePlayer(
                    Sonic2SpecialStagePlayer.PlayerType.SONIC, true, this);
            players.add(sonicPlayer);
            LOGGER.fine("Special Stage: Sonic alone");
        }

        if (renderer != null) {
            renderer.setPlayers(players);
        }
    }

    private void setPlayersSpawned(boolean spawned) {
        for (Sonic2SpecialStagePlayer player : players) {
            player.setSpawned(spawned);
        }
    }

    private void initializePlayerScalarStateFromRomObjectRoutines() {
        // RunObjects scans the main-character slot before the sidekick slot
        // (s2.asm:29805-29846).
        if (sonicPlayer != null) {
            swapPositionsFlag = 0;
            sonicPlayer.initializeScalarStateFromRomObjectRoutine();
        }
        if (tailsPlayer != null) {
            tailsPlayer.initializeScalarStateFromRomObjectRoutine();
        }
        primeDynamicArtDedupBaselines();
    }

    /**
     * Reproduces the {@code LastLoadedDPLC} writes each special-stage player
     * object's init routine performs.
     *
     * <p>Obj09's init writes {@code #1} to {@code Sonic_LastLoadedDPLC}
     * (s2.asm:69095), Obj10's writes {@code #1} to {@code Tails_LastLoadedDPLC}
     * (s2.asm:70378), and the Obj88 tail Obj10's init runs writes {@code #1} to
     * {@code TailsTails_LastLoadedDPLC} (s2.asm:70403). Those registers are the
     * ROM's own per-owner DPLC dedup: {@code LoadSSPlayerDynPLC} compares
     * {@code mapping_frame} against the register and skips the transfer only on
     * an exact match (s2.asm:69209-69213), and
     * {@code LoadSSTailsTailsDynPLC} does the same against its own register
     * (s2.asm:70586-70588). Because init rewrites them, a value a previous
     * special-stage instance left behind can never suppress the first transfer
     * of the next one.
     */
    private void primeDynamicArtDedupBaselines() {
        DynamicArtLifecycleService lifecycle =
                GameServices.dynamicArtLifecycleOrNull();
        if (lifecycle == null || !lifecycle.isRunActive()) {
            return;
        }
        if (sonicPlayer != null) {
            lifecycle.primeDplcDedupBaseline("ss-sonic", 1);
        }
        if (tailsPlayer != null) {
            lifecycle.primeDplcDedupBaseline("ss-tails", 1);
            lifecycle.primeDplcDedupBaseline("ss-tails-tails", 1);
        }
    }

    /**
     * Advances the ROM startup-wait latch on a semantic drawing-index wrap and
     * reports when the caller must run the ordered final bootstrap pass. Package
     * visibility permits ROM-free lifecycle/rewind tests to exercise the same
     * field transition used by {@link #update()}.
     */
    boolean observePlayerBootstrapDrawingWrap(boolean drawingIndexWrapped) {
        if (!drawingIndexWrapped || playerBootstrapPhase == PlayerBootstrapPhase.INITIALIZED) {
            return false;
        }
        PlayerBootstrapPhase nextPhase = playerBootstrapPhase.afterDrawingIndexWrap();
        if (nextPhase == PlayerBootstrapPhase.INITIALIZED) {
            return true;
        }
        playerBootstrapPhase = nextPhase;
        return false;
    }

    /** Completes the player-slot portion of the ordered final bootstrap pass. */
    void completePlayerScalarInitializationBootstrap() {
        initializePlayerScalarStateFromRomObjectRoutines();
        playerBootstrapPhase = PlayerBootstrapPhase.INITIALIZED;
    }

    /** Updates the Special Stage state for one frame. */
    public void update() {
        if (!initialized) {
            return;
        }

        if (alignmentTestMode) {
            updateAlignmentTest();
            return;
        }

        frameCounter++;

        Sonic2SpecialStageIntro.Phase entryPhase = intro != null
                ? intro.getCurrentPhase()
                : Sonic2SpecialStageIntro.Phase.GAMEPLAY;
        if (!stagePalettesUploaded && entryPhase != Sonic2SpecialStageIntro.Phase.PRE_ROLL) {
            uploadStagePalettes();
        }
        if (liveEntryLoadHoldFrames > 0
                && entryPhase == Sonic2SpecialStageIntro.Phase.ROM_STARTUP) {
            // The masked-interrupt entry load sits between the last
            // Pal_FadeToWhite wait and the first startup wait (s2.asm:6557-6645):
            // no V-int runs, so this frame observes nothing, exactly like a
            // recorded lag row (see ENTRY_LOAD_LAG_FRAMES).
            liveEntryLoadHoldFrames--;
            pressedButtons = 0;
            updateTimingDiagnostics(false, false);
            return;
        }

        int liveSpeedFactor = trackAnimator != null ? trackAnimator.getSpeedFactor() : 0;
        int liveSegmentType = trackAnimator != null
                ? trackAnimator.getCurrentSegmentType()
                : Sonic2SpecialStageConstants.SEGMENT_STRAIGHT;
        // Pal_FadeToWhite rows run Vint_Fade alone (s2.asm:1068-1070) and never
        // lag, so the empirical stage-runtime model starts after PRE_ROLL.
        if (liveLagSimulationEnabled
                && entryPhase != Sonic2SpecialStageIntro.Phase.PRE_ROLL
                && Sonic2SpecialStageLagModel.shouldSkipLiveUpdate(
                        frameCounter, liveSpeedFactor, liveSegmentType)) {
            // Approximate the retail Vint_Lag outcome for interactive play.
            // Externally paced traces disable this branch and admit recorded
            // lag rows at the scheduling boundary instead.
            pressedButtons = 0;
            updateTimingDiagnostics(false, false);
            return;
        }

        // Vint_runcount advances at VintRet even when intervening VInts took the
        // lag path. Publish the current successful VInt phase before RunObjects
        // consumes global blink/palette cadence (s2.asm:507-508,71616-71633).
        renderFrameCounter = frameCounter;

        // The recorder can observe VInt/draw/stream/scroll state before the later
        // RunObjects phase completes. Publish only that prior RunObjects work at
        // the start of the next executed logical update (s2.asm:6674-6690).

        // Capture once after the pending pass: intro object execution may cross a
        // presentation phase, but current VInt gating stays fixed for this tick.
        Sonic2SpecialStageIntro.Phase introPhase = intro != null
                ? intro.getCurrentPhase()
                : Sonic2SpecialStageIntro.Phase.GAMEPLAY;
        boolean preRoll = introPhase == Sonic2SpecialStageIntro.Phase.PRE_ROLL;
        boolean fadeFromWhite = introPhase == Sonic2SpecialStageIntro.Phase.FADE_FROM_WHITE;
        boolean runtimeFrozen = preRoll || fadeFromWhite;

        boolean drawingIndexWrapped = false;
        if (!runtimeFrozen) {
            if (initialPlayerSpawnPending) {
                // Obj09/Obj10 ids become observable before this first
                // Vint_S2SS tick (s2.asm:6628-6631).
                setPlayersSpawned(true);
                initialPlayerSpawnPending = false;
            }

            promotePendingSpeedFactorAtVint();

            // Vint_S2SS owns the duration countdown/reload. SSTrack_Draw owns
            // animation-frame advancement and is invoked separately below.
            trackAnimator.tickVintTimer();

            // Increment drawing index, cycling based on current frame duration.
            // In ROM: drawing_index increments each VBlank, resets when >= frame_timer
            // (duration).
            // At speedFactor=12, duration=5, so drawing_index cycles 0-4.
            // At speedFactor=6, duration=10, so drawing_index cycles 0-9.
            // drawingIndex==4 is special: it's when $CCCC is used instead of $CCCD for
            // depth decrement.
            int duration = getAlignmentFrameDuration();
            int previousDrawingIndex = drawingIndex;
            drawingIndex = (drawingIndex + 1) % Math.max(1, duration);
            drawingIndexWrapped = drawingIndex < previousDrawingIndex;
        }

        // PRE_ROLL and Pal_FadeFromWhite are synchronous wait loops. Their phase
        // counters advance here, but DROP/WAIT presentation objects advance only
        // from the deferred recurring RunObjects pass.
        if (runtimeFrozen && intro != null) {
            intro.update();
        }

        boolean playerBootstrapWaitTick = !runtimeFrozen
                && playerBootstrapPhase != PlayerBootstrapPhase.INITIALIZED;
        boolean firstDrawingWaitTick = playerBootstrapWaitTick
                && playerBootstrapPhase == PlayerBootstrapPhase.WAIT_FIRST_DRAWING_WRAP;
        boolean secondDurationWaitTick = !runtimeFrozen
                && playerBootstrapPhase == PlayerBootstrapPhase.WAIT_SECOND_DURATION_WRAP;
        boolean recurringVintTick = !runtimeFrozen
                && playerBootstrapPhase == PlayerBootstrapPhase.INITIALIZED;

        boolean currentTrackFrameChanged = false;
        if (firstDrawingWaitTick && drawingIndexWrapped) {
            // The first startup loop calls SSTrack_Draw only after it observes
            // drawing index zero (s2.asm:6644-6650).
            currentTrackFrameChanged = trackAnimator.drawTrackFrame(drawingIndex);
        }
        if (secondDurationWaitTick) {
            // The duration loop calls SSTrack_Draw and SSObjectsManager after
            // every wait; only a zero drawing index advances the track frame
            // (s2.asm:6651-6658,7026-7091).
            currentTrackFrameChanged |= trackAnimator.drawTrackFrame(drawingIndex);
        }
        if (recurringVintTick) {
            currentTrackFrameChanged |= trackAnimator.drawTrackFrame(drawingIndex);
        }

        if (!runtimeFrozen && (currentTrackFrameChanged || decodedTrackFrame == null)) {
            decodeCurrentTrackFrame();
        }
        if (secondDurationWaitTick || recurringVintTick) {
            streamSpecialStageObjects();
        }

        boolean startupPlayerInitializationTick = playerBootstrapWaitTick
                && observePlayerBootstrapDrawingWrap(drawingIndexWrapped);
        if (startupPlayerInitializationTick) {
            // After the final SSObjectsManager stream pass, RunObjects scans the
            // Obj09/Obj10 player slots before the later ring/bomb slots
            // (s2.asm:29805-29846, 6651-6663). Apply player scalar init first,
            // then execute/project/collide active special-stage objects once.
            completePlayerScalarInitializationBootstrap();
            // That first player-slot scan runs Obj09/Obj10's display code,
            // which ends in LoadSSSonicDynPLC / LoadSSTailsDynPLC /
            // LoadSSTailsTailsDynPLC (docs/s2disasm/s2.asm:69194, 70493,
            // 70575). Their "already loaded" latch was cleared with the rest
            // of the special-stage object RAM, so the bootstrap pass is where
            // the players' first art transfer is queued -- not the first
            // recurring V-int pass.
            if (tailsPlayer != null) {
                // ROM Obj88 (tails' tails) animates on this same startup pass
                // (s2.asm:70549-70563); see tickTailsTailsStartupAnimation.
                tailsPlayer.tickTailsTailsStartupAnimation();
            }
            publishPlayerDynamicArt();
            // The startup sequence then waits on a DMA-queue-only V-int
            // (s2.asm:6665-6668 -> Vint_CtrlDMA, s2.asm:998-1001), so that
            // V-blank retires what the pass just queued even though the
            // Pal_FadeFromWhite loop around it never polls the joypad.
            PlcFrameLifecycleCoordinator plcFrameLifecycle =
                    GameServices.plcFrameLifecycleOrNull();
            if (plcFrameLifecycle != null) {
                plcFrameLifecycle.markNextVblankServicesDmaQueue();
            }
            executeActiveSpecialStageObjects();
            introFirstRecurringPassDeferred = true;
            intro.beginFadeFromWhite();
        } else if (recurringVintTick) {
            // ROM order within one main-loop iteration is SSTrack_Draw ->
            // SSSetGeometryOffsets -> SSLoadCurrentPerspective ->
            // SSObjectsManager -> SS_ScrollBG -> RunObjects
            // (docs/s2disasm/s2.asm:6679-6688). The pass therefore executes in
            // the same iteration whose VInt advanced SSTrack_drawing_index and
            // whose SSObjectsManager allocated this iteration's objects, so a
            // freshly streamed object takes its first depth decrement here.
            executePendingRecurringMainPass();
            scheduleRecurringMainPass(currentTrackFrameChanged);
            if (introFirstRecurringPassDeferred) {
                // The first post-fade wait-loop iteration is the slow one: its
                // full track redraw overruns the frame (the recorder's first
                // post-fade row is a lag row) and its results only become
                // visible at the following observation, like the engine's
                // scheduled-pass pipeline already models. Every later
                // pre-start iteration completes within its own frame.
                introFirstRecurringPassDeferred = false;
            } else if (!intro.isSpecialStageStarted() && !intro.isTerminalPreStartPassPending()) {
                // Before SpecialStage_Started, the wait loop's RunObjects pass
                // completes within the same displayed frame as the WaitForVint
                // return that started it (s2.asm:6674-6688): the recorder's
                // end-of-frame rows show each pass's results on the row that
                // scheduled it (stage-1 fixture rows 160-178, where only the
                // slow first post-fade iteration lands a row later — invisible
                // here because its mapping frame 0 art is already loaded).
                // Deferring to the next observation ran every pre-start pass
                // one non-lag row late and shifted all player DPLC edges. The
                // Obj5F terminal pass stays deferred: its completion boundary
                // is owned by completeTerminalPreStartPassWithoutVint.
                executePendingRecurringMainPass();
            }
        }

        if (!runtimeFrozen) {
            updateSkydomeScroll();
            updateVScroll();
        }

        updateTimingDiagnostics(true, currentTrackFrameChanged);

        if (!runtimeFrozen && trackAnimator.isStageComplete()) {
            trackAnimator.resetStageComplete();
        }

        latchCurrentPhysicalInputForNextVint();
    }

    private void executePendingRecurringMainPass() {
        if (!recurringMainPassPending) {
            return;
        }
        recurringMainPassPending = false;
        updatePlayers(
                pendingMainHeldButtons,
                pendingMainPressedButtons,
                pendingMainP2HeldButtons,
                pendingMainP2LogicalButtons);
        if (intro != null) {
            intro.update();
        }
        executeActiveSpecialStageObjects();

        tryStartPendingCheckpoint();
        if (checkpoint != null && checkpoint.isActive()) {
            boolean checkpointComplete = checkpoint.update(pendingMainCheckpointStep);
            if (checkpointComplete) {
                handleCheckpointAnimationComplete();
            }
        }
        updateRainbowPaletteCycle();

        pendingMainHeldButtons = 0;
        pendingMainPressedButtons = 0;
        pendingMainP2HeldButtons = 0;
        pendingMainP2LogicalButtons = 0;
        pendingMainCheckpointStep = false;
    }

    /**
     * Completes the already-pending main-thread object pass without executing a
     * new VInt. A VBlank observation can expose {@code SpecialStage_Started}
     * after Obj5F's terminal pre-start {@code RunObjects} pass completes even
     * though no following {@code Vint_S2SS} ran yet (s2.asm:6674-6694,
     * 9734-9746). Deterministic replay uses this semantic boundary; all state is
     * evolved natively from the pending pass and no trace state is copied in.
     */
    public void completeTerminalPreStartPassWithoutVint() {
        if (!recurringMainPassPending
                || intro == null
                || !intro.isTerminalPreStartPassPending()) {
            Sonic2SpecialStageSnapshot.IntroSnapshot introState = intro != null
                    ? intro.captureRewindSnapshot() : null;
            throw new IllegalStateException(
                    "cannot complete terminal pre-start pass outside Obj5F WAIT2 boundary"
                            + ": pending=" + recurringMainPassPending
                            + ", intro=" + introState);
        }
        executePendingRecurringMainPass();
        // The recurring loop owns a following pass slot. Its exact controller
        // sample is bound by the replay harness before execution; no VInt-owned
        // track state advances at this completion boundary.
        scheduleRecurringMainPass(false);
    }

    private void scheduleRecurringMainPass(boolean checkpointStep) {
        recurringMainPassPending = true;
        // Before SpecialStage_Started, main copies Ctrl_1/2 before WaitForVint;
        // once Obj5F advances the semantic intro gate, the gameplay loop copies
        // the freshly read word after the wait. Keep those two ROM loops distinct
        // (s2.asm:6674-6688, 6694-6721, 837-875, 9745).
        boolean gameplayControls = intro != null && intro.isSpecialStageStarted();
        pendingMainHeldButtons = gameplayControls ? heldButtons : previousPhysicalHeldButtons;
        pendingMainPressedButtons = gameplayControls
                // ReadJoypads derives press from the last VInt it executed. A
                // mapper edge across a lag-skipped release/re-press must not
                // override that raw comparison (s2.asm:1361-1387).
                ? currentExecutedRawPressedButtons()
                : previousPhysicalPressedButtons;
        pendingMainP2HeldButtons = gameplayControls ? p2HeldButtons : previousPhysicalP2HeldButtons;
        pendingMainP2LogicalButtons = gameplayControls
                ? p2LogicalButtons
                : previousPhysicalP2LogicalButtons;
        applyPauseOnlyControlMask();
        pendingMainCheckpointStep = checkpointStep;
    }

    /**
     * The recurring SS loop copies the raw pad words after V-int, but Obj59's
     * {@code SS_Pause_Only_flag} changes that copy to Start-only while the
     * emerald sequence owns the stage (s2.asm:6706-6721, 72287-72291).
     */
    private void applyPauseOnlyControlMask() {
        if (objectManager == null) {
            return;
        }
        Sonic2SpecialStageEmerald emerald = objectManager.getActiveEmerald();
        if (emerald == null || !emerald.restrictsControlsToStart()) {
            return;
        }
        pendingMainHeldButtons &= 0x80;
        pendingMainPressedButtons &= 0x80;
        pendingMainP2HeldButtons &= 0x80;
        pendingMainP2LogicalButtons &= 0x80;
    }

    private void latchCurrentPhysicalInputForNextVint() {
        // ReadJoypads compares held state at executed VInts. Physical mapper
        // edges whose release or press occurred only on skipped updates are not
        // separate ROM observations (s2.asm:1361-1387).
        int effectivePressedButtons = currentExecutedRawPressedButtons();
        previousPhysicalHeldButtons = heldButtons;
        previousPhysicalPressedButtons = effectivePressedButtons;
        previousPhysicalP2HeldButtons = p2HeldButtons;
        previousPhysicalP2LogicalButtons = p2LogicalButtons;
        pressedButtons = 0;
    }

    private int currentExecutedRawPressedButtons() {
        return heldButtons & ~previousPhysicalHeldButtons;
    }

    private void updateTimingDiagnostics(boolean countUpdate, boolean trackAdvanced) {
        if (!fineDiagnosticsEnabled()) {
            if (diagnosticEpochActive || hasDiagnosticTimingState()) {
                clearDiagnosticTimingState();
            }
            return;
        }
        if (!diagnosticEpochActive) {
            clearDiagnosticTimingState();
            diagnosticEpochActive = true;
        }

        long now = diagnosticClock.nanoTime();
        if (countUpdate && lastFrameTime != 0) {
            long delta = now - lastFrameTime;
            frameSampleSum += delta;
            frameSampleCount++;
            if (frameSampleCount >= FRAME_SAMPLE_SIZE) {
                double avgMs = (frameSampleSum / (double) frameSampleCount) / 1_000_000.0;
                if (avgMs > 0.0 && Double.isFinite(avgMs)) {
                    double actualFps = 1000.0 / avgMs;
                    LOGGER.fine(String.format(Locale.ROOT,
                            "Actual FPS: %.1f (%.2f ms/frame)", actualFps, avgMs));
                }
                frameSampleCount = 0;
                frameSampleSum = 0;
            }
        }
        lastFrameTime = now;

        if (!countUpdate) {
            return;
        }

        long wallNow = diagnosticClock.currentTimeMillis();
        if (diagnosticWallStartTime == 0) {
            diagnosticWallStartTime = wallNow;
        }
        diagnosticUpdateCount++;
        if (trackAdvanced) {
            diagnosticTrackAdvances++;
        }

        long elapsedMs = wallNow - diagnosticWallStartTime;
        if (elapsedMs >= 5000) {
            double seconds = elapsedMs / 1000.0;
            double updatesPerSec = diagnosticUpdateCount / seconds;
            double trackPerSec = diagnosticTrackAdvances / seconds;
            LOGGER.fine(String.format(Locale.ROOT,
                    "DIAGNOSTIC: %.1f updates/sec (expect 60), %.1f track/sec (expect 12), " +
                            "speedFactor=%d, duration=%d",
                    updatesPerSec, trackPerSec,
                    trackAnimator != null ? trackAnimator.getSpeedFactor() : 12,
                    getAlignmentFrameDuration()));
            diagnosticWallStartTime = wallNow;
            diagnosticUpdateCount = 0;
            diagnosticTrackAdvances = 0;
        }
    }

    private boolean fineDiagnosticsEnabled() {
        return fineDiagnosticsOverride != null
                ? fineDiagnosticsOverride
                : LOGGER.isLoggable(Level.FINE);
    }

    private boolean hasDiagnosticTimingState() {
        return diagnosticWallStartTime != 0
                || diagnosticUpdateCount != 0
                || diagnosticTrackAdvances != 0
                || lastFrameTime != 0
                || frameSampleCount != 0
                || frameSampleSum != 0;
    }

    private void clearDiagnosticTimingState() {
        diagnosticWallStartTime = 0;
        diagnosticUpdateCount = 0;
        diagnosticTrackAdvances = 0;
        lastFrameTime = 0;
        frameSampleCount = 0;
        frameSampleSum = 0;
        diagnosticEpochActive = false;
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

    /** SSObjectsManager-equivalent stream/allocation work (s2.asm:6935-7001). */
    private void streamSpecialStageObjects() {
        if (objectManager == null || trackAnimator == null) {
            return;
        }

        // this.drawingIndex was advanced by this iteration's VInt above, exactly
        // as SSObjectsManager reads SSTrack_drawing_index after WaitForVint. The
        // objects allocated here take their first depth decrement in this same
        // iteration's RunObjects pass, executed just below.
        if (this.drawingIndex == 4 && lastDrawingIndex != 4) {
            int segmentIndex = trackAnimator.getCurrentSegmentIndex();
            int segmentType = trackAnimator.getCurrentSegmentType();
            objectManager.processSegment(segmentIndex, segmentType);
            lastDrawingIndex = this.drawingIndex;
            return;
        }
        lastDrawingIndex = this.drawingIndex;
    }

    /** Later-slot RunObjects-equivalent active object execution. */
    private void executeActiveSpecialStageObjects() {
        if (objectManager == null || trackAnimator == null) {
            return;
        }

        // Update all active objects
        int currentFrame = trackAnimator.getCurrentTrackFrameIndex();
        boolean flipped = trackAnimator.getEffectiveFlipState();
        int speedFactor = trackAnimator.getSpeedFactor();
        boolean drawingIndex4 = (this.drawingIndex == 4);
        objectManager.update(currentFrame, flipped, speedFactor, drawingIndex4);

        // Update screen positions using perspective data
        if (perspectiveData != null) {
            for (Sonic2SpecialStageObject obj : objectManager.getActiveObjects()) {
                obj.updateScreenPosition(perspectiveData, currentFrame, flipped);
            }
        }

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
     * - During invulnerability (player countdown > 0, routineSecondary == 0):
     * - Rings CAN be collected
     * - Bombs CANNOT hit (player is invulnerable)
     * - Multiple bombs hitting same frame: Each plays sound (accurate to original)
     */
    private void checkObjectCollisions() {
        if (objectManager == null) {
            return;
        }

        checkObjectCollisions(objectManager.getActiveObjects());
    }

    private void checkObjectCollisions(List<Sonic2SpecialStageObject> objects) {
        if (objectManager == null) {
            return;
        }

        List<Sonic2SpecialStagePlayer> collisionCandidates = orderedCollisionCandidates();

        for (Sonic2SpecialStageObject obj : objects) {
            // Only test collidable objects (animIndex == 8)
            if (!obj.isCollidable()) {
                continue;
            }

            int objAngle = obj.getAngle() & 0xFF;
            // Obj60 loads d6=$A; Obj61 loads d6=8 before the shared collision
            // helper (s2.asm:70674-70676, 70767-70769).
            int angleThreshold = obj.isBomb() ? 8 : 10;

            // Test against each player
            for (Sonic2SpecialStagePlayer player : collisionCandidates) {
                if (player == null) {
                    continue;
                }

                // The shared helper both call sites use (Obj60's ring test with
                // d6=$A and Obj61's bomb test with d6=8) rejects a player unless
                // its routine is the normal on-track one:
                //
                //   tst.b   id(a1)            ; slot occupied
                //   cmpi.b  #2,routine(a1)    ; Obj09_MdNormal / Obj10_MdNormal
                //   bne.s   loc_3511A
                //   tst.b   routine_secondary(a1)
                //   bne.s   loc_3511A
                //
                // (s2.asm:70841-70846). Routine 4 (MdJump) and routine 8 (MdAir)
                // are therefore immune to both rings and bombs while the player
                // is off the track, which is also why neither Obj09_MdJump nor
                // Obj09_MdAir calls SSPlayer_Collision (s2.asm:69297-69322).
                if (player.getRoutine()
                        != Sonic2SpecialStagePlayer.RoutineState.NORMAL) {
                    continue;
                }

                // Original game checks routine_secondary != 0 (hurt animation) for ALL objects
                // During hurt animation, no collision with anything
                if (player.isHurt()) {
                    continue;
                }

                // For bombs only: also skip during player-owned post-hit invulnerability.
                // Rings CAN be collected during invulnerability
                if (obj.isBomb() && player.isInvulnerable()) {
                    continue;
                }

                // Original game compares angles, not screen coordinates
                // Check if player angle is within ±threshold of object angle
                int playerAngle = player.getAngle() & 0xFF;

                // Calculate angle difference with wraparound handling
                int diff = (playerAngle - objAngle) & 0xFF;

                // ROM accepts object-d6 <= player < object+d6: the negative
                // boundary is inclusive, while the positive boundary is not
                // (s2.asm:70846-70856).
                if (diff < angleThreshold || diff >= (256 - angleThreshold)) {
                    if (handleObjectCollision(obj, player)) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Obj61_TestCollision compares the players' unsigned {@code ss_z_pos} words.
     * Sonic is tested first only when his value is strictly lower; Tails wins
     * ties and all greater-than cases (s2.asm:70813-70836).
     */
    private List<Sonic2SpecialStagePlayer> orderedCollisionCandidates() {
        Sonic2SpecialStagePlayer sonic = null;
        Sonic2SpecialStagePlayer tails = null;
        for (Sonic2SpecialStagePlayer player : players) {
            if (player == null) {
                continue;
            }
            if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.SONIC) {
                sonic = player;
            } else if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.TAILS) {
                tails = player;
            }
        }

        if (sonic == null) {
            return tails == null ? List.of() : List.of(tails);
        }
        if (tails == null) {
            return List.of(sonic);
        }

        int sonicDepth = sonic.getSSZPos() & 0xFFFF;
        int tailsDepth = tails.getSSZPos() & 0xFFFF;
        return Integer.compareUnsigned(sonicDepth, tailsDepth) < 0
                ? List.of(sonic, tails)
                : List.of(tails, sonic);
    }

    /**
     * Handles a collision between a player and an object.
     */
    private boolean handleObjectCollision(Sonic2SpecialStageObject obj, Sonic2SpecialStagePlayer player) {
        if (obj.isRing()) {
            Sonic2SpecialStageRing ring = (Sonic2SpecialStageRing) obj;
            if (!ring.collect()) {
                return false;
            }
            objectManager.collectRing(player);
            GameServices.audio().playSfx(GameSound.RING);
            LOGGER.fine("Collected ring! Total: " + objectManager.getRingsCollected());
            return true;
        } else if (obj.isBomb()) {
            Sonic2SpecialStageBomb bomb = (Sonic2SpecialStageBomb) obj;
            if (!bomb.explode()) {
                return false;
            }
            player.triggerHit();
            // Original game plays SndID_SlowSmash for bomb explosion
            GameServices.audio().playSfx(GameSound.SLOW_SMASH);
            // SSPlayer_Collision only plays SndID_RingSpill and enters the hurt
            // state here; it never touches the ring count. The rings are taken
            // by Obj5B_Init, and Obj5B is not created until SSHurt_Animation
            // sees ss_hurt_timer reach 8 -- one RunObjects pass later
            // (s2.asm:69154-69171, 71266-71298). See applyPendingRingSpills.
            if (player.getRings() > 0) {
                GameServices.audio().playSfx(GameSound.RING_SPILL);
            }
            LOGGER.fine("Hit bomb! Rings pending spill. Current: " +
                    objectManager.getRingsCollected());
            return true;
        }
        return false;
    }

    /**
     * Updates the skydome scroll offset based on track animation state.
     * Implements SSPlaneB_SetHorizOffset from the original game (s2.asm line 9238).
     *
     * The scroll delta is applied during turning segments to create the illusion
     * of the background dome rotating as the track curves.
     */
    private void updateSkydomeScroll() {
        if (trackAnimator == null) {
            return;
        }

        // Get CURRENT flip state - this is what the original checks in
        // SS_Alternate_HorizScroll_Buf
        boolean currentFlipState = trackAnimator.getEffectiveFlipState();

        int segmentType = trackAnimator.getCurrentSegmentType();

        // Debug: Track segment changes and log H-scroll totals
        int currentSegmentIndex = trackAnimator.getCurrentSegmentIndex();
        if (currentSegmentIndex != lastDebugSegmentIndex) {
            if (lastDebugSegmentIndex >= 0 && hScrollDebugFrames > 0) {
                LOGGER.info(String.format("H-SCROLL SEGMENT %d: total=%d, frames=%d, scrollX=%d",
                        lastDebugSegmentIndex, hScrollDebugTotal, hScrollDebugFrames, skydomeScrollX));
            }
            lastDebugSegmentIndex = currentSegmentIndex;
            hScrollDebugTotal = 0;
            hScrollDebugFrames = 0;
        }

        // Only apply scroll during turning segments (types 0, 1, 2)
        // Straight (3) and StraightThenTurn (4) return immediately
        if (segmentType == SEGMENT_STRAIGHT || segmentType == SEGMENT_STRAIGHT_THEN_TURN) {
            // Save current state for next frame
            lastAlternateScrollBuffer = alternateScrollBuffer;
            alternateScrollBuffer = currentFlipState;
            lastAnimFrame = trackAnimator.getCurrentFrameInSegment();
            return;
        }

        // Get current frame (matches SSTrack_last_anim_frame in the original loop)
        int currentFrame = trackAnimator.getCurrentFrameInSegment();

        // Only apply scroll during turn portion (frames 0-11)
        // Frames >= 12 are rise/drop/exit which don't get H-scroll
        // Use CURRENT frame for boundary check to avoid off-by-one at transitions
        if (currentFrame >= HSCROLL_TURN_TABLE_INDICES.length) {
            // No scroll for frames >= 12
            lastAlternateScrollBuffer = alternateScrollBuffer;
            alternateScrollBuffer = currentFlipState;
            lastAnimFrame = currentFrame;
            return;
        }

        // Use CURRENT animation frame for table lookup (matches SSTrack_last_anim_frame
        // in ROM loop)
        int tableIndex = HSCROLL_TURN_TABLE_INDICES[currentFrame];

        // Get delta from the pre-defined table using drawingIndex
        int deltaIndex = drawingIndex % 5;
        int delta = BG_SCROLL_DELTA_TABLE[tableIndex][deltaIndex];

        // Negate delta when using alternate buffer (flipped/left turn)
        // From disassembly: negate is applied when SS_Alternate_HorizScroll_Buf is set
        // This is the CURRENT flip state, not the previous frame's
        if (currentFlipState) {
            delta = -delta;
        }

        // Apply delta - the original subtracts from scroll value
        skydomeScrollX -= delta;

        // Debug: track total delta for this segment
        hScrollDebugTotal += delta; // Track the raw delta (before subtraction)
        hScrollDebugFrames++;

        // Save current state for next frame
        lastAlternateScrollBuffer = alternateScrollBuffer;
        alternateScrollBuffer = currentFlipState;
        lastAnimFrame = currentFrame;
    }

    /**
     * Updates the vertical scroll (vScrollBG) based on track animation state.
     * Implements SSTrack_SetVscroll from the original game (s2.asm line 9316).
     *
     * The original uses a two-level lookup:
     * 1. Animation frame determines table index (into BG_SCROLL_DELTA_TABLE)
     * 2. Drawing index (0-4) selects specific delta from the 5-entry table
     *
     * Effects:
     * - STRAIGHT segments: Subtle bobbing up/down (~1-2 pixels)
     * - TURN_THEN_RISE: Background scrolls up (vScrollBG decreases)
     * - TURN_THEN_DROP: Background scrolls down (vScrollBG increases)
     * - Other segments: No vertical scroll
     */
    private void updateVScroll() {
        if (trackAnimator == null) {
            return;
        }

        int segmentType = trackAnimator.getCurrentSegmentType();
        int frameInSegment = trackAnimator.getCurrentFrameInSegment();
        // drawingIndex cycles 0-4, selecting which delta value from the 5-entry table
        int deltaIndex = drawingIndex % 5;

        int tableIndex = -1; // -1 = skip this frame

        switch (segmentType) {
            case SEGMENT_TURN_THEN_RISE:
                if (frameInSegment >= 0 && frameInSegment < VSCROLL_RISE_TABLE_INDICES.length) {
                    tableIndex = VSCROLL_RISE_TABLE_INDICES[frameInSegment];
                }
                if (tableIndex >= 0 && tableIndex < BG_SCROLL_DELTA_TABLE.length) {
                    int delta = BG_SCROLL_DELTA_TABLE[tableIndex][deltaIndex];
                    vScrollBG -= delta; // Rise = subtract (background moves up)
                }
                break;

            case SEGMENT_TURN_THEN_DROP:
                if (frameInSegment >= 0 && frameInSegment < VSCROLL_DROP_TABLE_INDICES.length) {
                    tableIndex = VSCROLL_DROP_TABLE_INDICES[frameInSegment];
                }
                if (tableIndex >= 0 && tableIndex < BG_SCROLL_DELTA_TABLE.length) {
                    int delta = BG_SCROLL_DELTA_TABLE[tableIndex][deltaIndex];
                    vScrollBG += delta; // Drop = add (background moves down)
                }
                break;

            case SEGMENT_STRAIGHT:
                // Straight: Subtle bobbing effect using small delta values
                // Table index 3 = {0,0,1,0,0}, table index 10 = {0,0,-1,0,0}
                // The -1 wraps in 256-pixel space to effectively add 1
                // Net effect: very subtle oscillation of ~1-2 pixels
                if (frameInSegment >= 0 && frameInSegment < VSCROLL_STRAIGHT_TABLE_INDICES.length) {
                    tableIndex = VSCROLL_STRAIGHT_TABLE_INDICES[frameInSegment];
                }
                if (tableIndex >= 0 && tableIndex < BG_SCROLL_DELTA_TABLE.length) {
                    int delta = BG_SCROLL_DELTA_TABLE[tableIndex][deltaIndex];
                    vScrollBG -= delta; // Straight always subtracts
                }
                break;

            case SEGMENT_TURN_THEN_STRAIGHT:
            case SEGMENT_STRAIGHT_THEN_TURN:
            default:
                // No vertical scroll for transition segments
                break;
        }
    }


    private void updatePlayers(
            int capturedHeldButtons,
            int capturedPressedButtons,
            int capturedP2HeldButtons,
            int capturedP2LogicalButtons) {
        // Get the global animation frame timer from the track animator
        int animTimer = trackAnimator.getPlayerAnimFrameTimer();

        if (sonicPlayer != null && tailsPlayer != null) {
            sonicPlayer.setGlobalAnimFrameTimer(animTimer);
            sonicPlayer.update(capturedHeldButtons, capturedPressedButtons);
            System.arraycopy(tailsCtrlRecordBuf, 0, tailsCtrlRecordBuf, 1, tailsCtrlRecordBuf.length - 1);
            // loc_33908 records the whole Ctrl_1_Logical *word* -- held byte in
            // bits 8-15, press byte in bits 0-7 -- with a single
            // "move.w (Ctrl_1_Logical).w,-(a1)" (s2.asm:69069). Recording only
            // the held byte loses the press edge, and SSTailsCPU_Control's
            // "move.w (a1),(Ctrl_2_Logical).w" (s2.asm:70482) republishes both
            // bytes, so Obj10_MdNormal's Ctrl_2_Press_Logical jump test
            // (s2.asm:70426-70429) would never fire.
            tailsCtrlRecordBuf[0] = ((capturedHeldButtons & 0xFF) << 8)
                    | (capturedPressedButtons & 0xFF);
            // SSTailsCPU_Control is reached from Obj10_MdNormal only, and only
            // on its non-hurt path: Obj10_MdNormal tests routine_secondary and
            // branches to Obj10_Hurt before calling it (s2.asm:70408-70412).
            // While Tails is in Obj10_MdJump / Obj10_MdAir / Obj10_Hurt nothing
            // overwrites Ctrl_2_Logical after the main loop's own
            // "move.w (Ctrl_2).w,(Ctrl_2_Logical).w" (s2.asm:6716-6717,6741),
            // so SSPlayer_ChgJumpDir reads the real port-2 pad rather than the
            // delayed record buffer -- and the buffer clear and
            // Tails_control_counter decrement that live inside
            // SSTailsCPU_Control (s2.asm:70453-70481) do not run either. The
            // unconditional part is the buffer *shift*, which sits in Obj09
            // ahead of its routine jump (s2.asm:69048,69063-69069) and so runs
            // whatever Tails is doing.
            boolean tailsCpuControlRuns =
                    tailsPlayer.getRoutine()
                            == Sonic2SpecialStagePlayer.RoutineState.NORMAL
                    && !tailsPlayer.isHurt();
            int delayedHeld = capturedP2LogicalButtons;
            int delayedPressed = 0;
            if (tailsCpuControlRuns) {
                int recorded = tailsCtrlRecordBuf[tailsCtrlRecordBuf.length - 1];
                delayedHeld = (recorded >> 8) & 0xFF;
                delayedPressed = recorded & 0xFF;
                if ((capturedP2HeldButtons & 0x7F) != 0) {
                    // fixBugs = 0 (s2.asm:27): the clearing loop's
                    // "move.l d0,(a1)" pair never advances a1, so only the two
                    // newest words of SS_Ctrl_Record_Buf are zeroed and the
                    // rest of the record survives (s2.asm:70460-70470).
                    tailsCtrlRecordBuf[0] = 0;
                    tailsCtrlRecordBuf[1] = 0;
                    tailsControlCounter = 0xB4;
                    delayedHeld = capturedP2LogicalButtons;
                    delayedPressed = 0;
                } else if (tailsControlCounter > 0) {
                    tailsControlCounter--;
                    delayedHeld = capturedP2LogicalButtons;
                    delayedPressed = 0;
                }
            }
            tailsPlayer.setGlobalAnimFrameTimer(animTimer);
            tailsPlayer.update(delayedHeld, delayedPressed);
        } else if (sonicPlayer != null) {
            sonicPlayer.setGlobalAnimFrameTimer(animTimer);
            sonicPlayer.update(capturedHeldButtons, capturedPressedButtons);
        } else if (tailsPlayer != null) {
            tailsPlayer.setGlobalAnimFrameTimer(animTimer);
            tailsPlayer.update(capturedHeldButtons, capturedPressedButtons);
        }
        applyPendingRingSpills();
        publishPlayerDynamicArt();
    }

    /**
     * Runs Obj5B_Init for any player whose SSHurt_Animation just created a ring
     * spill on this pass.
     *
     * <p>SSHurt_Animation adds 8 to {@code ss_hurt_timer} every hurt tick and
     * only allocates Obj5B on the tick where the result is exactly 8 -- the
     * first tick after SSPlayer_Collision entered the hurt state -- and only
     * when the owning player still holds rings (s2.asm:69138-69171). Obj5B is
     * allocated into the special-stage object region, which RunObjects scans
     * after the Obj09/Obj10 player slots, so its Init subtracts the rings on
     * that same pass (s2.asm:71266-71298). Deducting them in the touch response
     * instead would take them a pass early.
     */
    private void applyPendingRingSpills() {
        applyPendingRingSpill(sonicPlayer);
        applyPendingRingSpill(tailsPlayer);
    }

    private void applyPendingRingSpill(Sonic2SpecialStagePlayer player) {
        if (player == null || objectManager == null) {
            return;
        }
        if (!player.isHurt() || player.getHurtTimer() != 8) {
            return;
        }
        objectManager.loseRingsFromBombHit(player);
    }

    private void publishPlayerDynamicArt() {
        DynamicArtLifecycleService lifecycle =
                GameServices.dynamicArtLifecycleOrNull();
        if (lifecycle == null || !lifecycle.isRunActive()
                || dataLoader == null) {
            return;
        }
        Sonic2SpecialStageDataLoader.PlayerDplcPlans plans;
        try {
            plans = dataLoader.getPlayerDplcPlans();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "unable to load ROM-backed special-stage player DPLC plans", e);
        }
        // Every Obj09/Obj10 routine tail branches to LoadSSSonicDynPLC /
        // LoadSSTailsDynPLC, whose prologue skips the DisplaySprite + DPLC tail
        // outright while the post-hurt ss_dplc_timer blink is on an odd count
        // (docs/s2disasm/s2.asm:69193-69200, 70492-70499). Obj88 re-reads its
        // parent's counter (s2.asm:70575-70581), so Tails and his tails always
        // skip the same passes.
        if (sonicPlayer != null && sonicPlayer.dplcLoadRuns()) {
            publishSpecialStageOwner(
                    lifecycle, "ss-sonic", sonicPlayer.getMappingFrame(),
                    dplcRequests(plans.sonic(), sonicPlayer.getMappingFrame()),
                    0x5CA0);
        }
        if (tailsPlayer != null && tailsPlayer.dplcLoadRuns()) {
            publishSpecialStageOwner(
                    lifecycle, "ss-tails", tailsPlayer.getMappingFrame(),
                    dplcRequests(plans.tails(), tailsPlayer.getMappingFrame()),
                    0x6000);
            if (tailsPlayer.tailsTailsDplcLoadRuns()
                    && tailsPlayer.shouldRenderTailsTails()) {
                publishSpecialStageOwner(
                        lifecycle, "ss-tails-tails",
                        tailsPlayer.getTailsTailsMappingFrame(),
                        dplcRequests(plans.tailsTails(),
                                tailsPlayer.getTailsTailsMappingFrame()),
                        0x62C0);
            }
        }
    }

    private static List<com.openggf.level.render.TileLoadRequest> dplcRequests(
            List<com.openggf.level.render.SpriteDplcFrame> frames,
            int mappingFrame) {
        if (mappingFrame < 0 || mappingFrame >= frames.size()) {
            return List.of();
        }
        return frames.get(mappingFrame).requests();
    }

    private static void publishSpecialStageOwner(
            DynamicArtLifecycleService lifecycle,
            String owner,
            int mappingFrame,
            List<com.openggf.level.render.TileLoadRequest> requests,
            int vramDestination) {
        lifecycle.observeRamDplc(
                com.openggf.game.GameId.S2,
                owner, mappingFrame, requests, 0xFF0000, vramDestination);
    }

    private void enterAlignmentTestMode() {
        alignmentFrameIndex = 0;
        alignmentFrameTimer = 0;
        alignmentTrackFrameIndex = -1;
        alignmentLastDecodedFrameIndex = -1;
        alignmentDecodedTrackFrame = null;
        alignmentDrawingIndex = 0;
        alignmentRainbowSpeedAccumulator = 0.0;
        alignmentPendingCheckpoint = true;
        alignmentTriggerOffsetFrames = CHECKPOINT_TRIGGER_OFFSET;

        alignmentTestSavedRainbowPalette = checkpointRainbowPaletteActive;

        alignmentStepByTrackFrame = true;
        alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();

        if (renderer != null) {
            renderer.setCheckpoint(alignmentCheckpoint);
        }
    }

    private void exitAlignmentTestMode() {
        alignmentCheckpoint = null;
        alignmentDecodedTrackFrame = null;
        alignmentTrackFrameIndex = -1;
        alignmentLastDecodedFrameIndex = -1;
        alignmentFrameIndex = 0;
        alignmentFrameTimer = 0;
        alignmentDrawingIndex = 0;
        alignmentRainbowSpeedAccumulator = 0.0;
        alignmentStepByTrackFrame = false;
        alignmentPendingCheckpoint = false;

        if (renderer != null) {
            renderer.setCheckpoint(checkpoint);
        }

        applyCheckpointRainbowPalette(alignmentTestSavedRainbowPalette);
    }

    private void updateAlignmentTest() {
        alignmentDrawingIndex = (alignmentDrawingIndex + 1) % 5;

        int duration = getAlignmentFrameDuration();
        alignmentFrameTimer++;
        boolean frameAdvanced = false;
        if (alignmentFrameTimer >= duration) {
            alignmentFrameTimer = 0;
            alignmentFrameIndex = (alignmentFrameIndex + 1) % ANIM_STRAIGHT.length;
            frameAdvanced = true;
        }

        int gateIndexBase = 0;
        for (int i = 0; i < ANIM_STRAIGHT.length; i++) {
            if (ANIM_STRAIGHT[i] == CHECKPOINT_TRIGGER_FRAME) {
                gateIndexBase = i;
                break;
            }
        }
        int gateIndex = Math.floorMod(gateIndexBase + alignmentTriggerOffsetFrames, ANIM_STRAIGHT.length);

        if (frameAdvanced && alignmentFrameIndex == 0 &&
                alignmentCheckpoint != null && !alignmentCheckpoint.isActive() &&
                !alignmentPendingCheckpoint) {
            alignmentPendingCheckpoint = true;
        }

        if (frameAdvanced && alignmentPendingCheckpoint &&
                alignmentFrameIndex == gateIndex &&
                alignmentCheckpoint != null && !alignmentCheckpoint.isActive()) {
            alignmentPendingCheckpoint = false;
            alignmentCheckpoint.beginRainbowOnly();
            applyCheckpointRainbowPalette(true);
            alignmentRainbowSpeedAccumulator = 0.0;
        }

        alignmentTrackFrameIndex = ANIM_STRAIGHT[alignmentFrameIndex];
        decodeAlignmentTrackFrame();

        if (alignmentCheckpoint != null && alignmentCheckpoint.isActive()) {
            boolean shouldStep = alignmentStepByTrackFrame ? frameAdvanced : (alignmentDrawingIndex == 4);
            if (shouldStep) {
                alignmentRainbowSpeedAccumulator += alignmentRainbowSpeedScale;
                while (alignmentRainbowSpeedAccumulator >= 1.0) {
                    boolean complete = alignmentCheckpoint.update(true);
                    alignmentRainbowSpeedAccumulator -= 1.0;
                    if (complete) {
                        applyCheckpointRainbowPalette(false);
                        break;
                    }
                }
            }
        }

        // Keep lastFrameTime updated to avoid huge FPS deltas on exit.
        updateTimingDiagnostics(false, false);
    }

    private int getAlignmentFrameDuration() {
        int speedFactor = (trackAnimator != null) ? trackAnimator.getSpeedFactor() : 6;
        int index = (speedFactor >> 1) & 0x7;
        if (index < 0 || index >= ANIM_BASE_DURATIONS.length) {
            return 1;
        }
        int duration = ANIM_BASE_DURATIONS[index];
        return duration > 0 ? duration : 1;
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

    public void handlePlayer2Input(int held, int logical) {
        this.p2HeldButtons = held;
        this.p2LogicalButtons = logical;
    }

    /**
     * Replaces only the controller sample owned by the recurring pass that is
     * already pending execution. Deterministic replay uses this after matching
     * a ROM {@code RunObjects_End} completion to its preceding VInt sample; no
     * player, object, track, or checkpoint state is copied from the trace.
     */
    public void bindPendingRecurringPassInput(
            int p1Held, int p1Pressed, int p2Held, int p2Logical) {
        if (!recurringMainPassPending) {
            throw new IllegalStateException("no recurring main pass is pending input binding");
        }
        pendingMainHeldButtons = p1Held & 0xFF;
        pendingMainPressedButtons = p1Pressed & 0xFF;
        pendingMainP2HeldButtons = p2Held & 0xFF;
        pendingMainP2LogicalButtons = p2Logical & 0xFF;
        applyPauseOnlyControlMask();
    }

    public void toggleAlignmentTestMode() {
        alignmentTestMode = !alignmentTestMode;
        if (alignmentTestMode) {
            enterAlignmentTestMode();
        } else {
            exitAlignmentTestMode();
        }
    }

    public boolean isAlignmentTestMode() {
        return alignmentTestMode;
    }

    public void adjustAlignmentOffset(int delta) {
        alignmentTriggerOffsetFrames += delta;
        alignmentTriggerOffsetFrames = Math.max(-15, Math.min(15, alignmentTriggerOffsetFrames));
    }

    public void adjustAlignmentSpeed(double delta) {
        alignmentRainbowSpeedScale = Math.max(0.1, Math.min(4.0, alignmentRainbowSpeedScale + delta));
    }

    public void toggleAlignmentStepMode() {
        alignmentStepByTrackFrame = !alignmentStepByTrackFrame;
        alignmentRainbowSpeedAccumulator = 0.0;
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

            // Diagnostic logging disabled - set to true to enable verbose frame decode
            // logging
            boolean runDiag = false;

            decodedTrackFrame = runDiag
                    ? Sonic2TrackFrameDecoder.decodeFrame(frameData, flipped, true)
                    : Sonic2TrackFrameDecoder.decodeFrameCached(frameIndex, frameData, flipped);
            lastDecodedFrameIndex = frameIndex;
            lastDecodedFlipped = flipped;

            if (renderFrameCounter % 60 == 0) {
                LOGGER.fine("Decoded track frame " + frameIndex +
                        " (flipped=" + flipped + "), segment " +
                        trackAnimator.getCurrentSegmentIndex() +
                        ", type " + trackAnimator.getCurrentSegmentType());
            }
        }
    }

    private void decodeAlignmentTrackFrame() {
        int frameIndex = alignmentTrackFrameIndex;
        if (frameIndex < 0) {
            return;
        }
        if (frameIndex == alignmentLastDecodedFrameIndex && alignmentDecodedTrackFrame != null) {
            return;
        }
        if (trackFrames != null && frameIndex >= 0 && frameIndex < trackFrames.length) {
            byte[] frameData = trackFrames[frameIndex];
            boolean runDiag = false;
            alignmentDecodedTrackFrame = runDiag
                    ? Sonic2TrackFrameDecoder.decodeFrame(frameData, false, true)
                    : Sonic2TrackFrameDecoder.decodeFrameCached(frameIndex, frameData, false);
            alignmentLastDecodedFrameIndex = frameIndex;
        }
    }

    private Sonic2SpecialStageRenderer.RingHudState currentRingHudState() {
        boolean sonicActive = false;
        boolean tailsActive = false;
        int sonicRings = 0;
        int tailsRings = 0;
        for (Sonic2SpecialStagePlayer player : players) {
            if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.SONIC) {
                sonicActive = true;
                sonicRings = player.getRings();
            } else if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.TAILS) {
                tailsActive = true;
                tailsRings = player.getRings();
            }
        }
        int totalRings = objectManager != null ? objectManager.getRingsCollected()
                : sonicRings + tailsRings;
        return new Sonic2SpecialStageRenderer.RingHudState(
                sonicActive, tailsActive, sonicRings, tailsRings, totalRings);
    }

    /**
     * Renders the Special Stage.
     */
    public void draw() {
        if (!initialized || renderer == null) {
            return;
        }

        GraphicsManager graphicsManager = graphicsManager();

        if (alignmentTestMode) {
            drawAlignmentTest();
            return;
        }

        boolean renderPlaneB = planeDebugMode.renderPlaneB();
        boolean renderPlaneA = planeDebugMode.renderPlaneA();
        renderer.setFrameCounter(renderFrameCounter);

        if (renderPlaneB) {
            // Use shader-based background rendering if available
            if (bgRenderer != null && bgRenderer.isInitialized()) {
                // Capture current scroll values for use in lambda
                final int currentScrollX = skydomeScrollX;
                final float currentVScrollBG = (float) vScrollBG;

                queueStaticBackgroundRebuildIfNeeded(graphicsManager);

                // 4. Update H-scroll and render with shader (vScrollBG applies vertical
                // parallax)
                graphicsManager.registerCommand(backgroundCommandPool.obtainShader(
                        bgRenderer, currentScrollX, currentVScrollBG));
            } else {
                // Fallback to CPU-based rendering
                renderer.renderBackground(combinedBackgroundMappings, skydomeScrollX, vScrollBG);
            }
        }

        if (renderPlaneA && decodedTrackFrame != null && decodedTrackFrame.length > 0) {
            int trackFrameIndex = trackAnimator.getCurrentTrackFrameIndex();
            renderer.renderTrack(trackFrameIndex, decodedTrackFrame);
        }

        // Render objects (rings, bombs) between track and players
        renderer.renderObjects();

        renderer.renderPlayers();

        // Render intro UI (banner and messages) on top
        if (intro != null && !intro.isComplete()) {
            renderer.renderIntroUI();
        }

        // Render ring counter HUD (after intro completes)
        if (intro == null || intro.isComplete()) {
            renderer.renderRingCounter(currentRingHudState());

            // Render "rings to go" counter if:
            // 1. Not in checkpoint animation
            // 2. The display has been enabled (by encountering a $FC marker)
            if ((checkpoint == null || !checkpoint.isActive()) &&
                    objectManager != null && objectManager.isRingsToGoEnabled()) {
                int ringsCollected = objectManager.getRingsCollected();
                int ringsToGo = currentRingRequirement - ringsCollected;
                renderer.renderRingsToGoHUD(ringsToGo, renderFrameCounter);
            }
        }

        // Render checkpoint UI (messages and hand) when active
        if (checkpoint != null && checkpoint.isActive()) {
            renderer.renderCheckpointUI();
        }
    }

    private void drawAlignmentTest() {
        GraphicsManager graphicsManager = graphicsManager();
        boolean renderPlaneB = planeDebugMode.renderPlaneB();
        boolean renderPlaneA = planeDebugMode.renderPlaneA();

        if (renderPlaneB) {
            if (bgRenderer != null && bgRenderer.isInitialized()) {
                final int currentScrollX = skydomeScrollX;
                final float currentVScrollBG = (float) vScrollBG;

                queueStaticBackgroundRebuildIfNeeded(graphicsManager);

                graphicsManager.registerCommand(backgroundCommandPool.obtainShader(
                        bgRenderer, currentScrollX, currentVScrollBG));
            } else {
                renderer.renderBackground(combinedBackgroundMappings, skydomeScrollX, vScrollBG);
            }
        }

        if (renderPlaneA) {
            renderer.renderTrack(alignmentTrackFrameIndex, alignmentDecodedTrackFrame);
        }

        if (alignmentCheckpoint != null && alignmentCheckpoint.isActive()) {
            renderer.renderCheckpointUI();
        }
    }

    private void queueStaticBackgroundRebuildIfNeeded(GraphicsManager graphicsManager) {
        renderer.onRenderContextGenerationChanged(graphicsManager.getPatternAtlas());
        if (!renderer.needsStaticBackgroundRebuild(combinedBackgroundMappings, palettes)) {
            return;
        }

        // Set up the FBO projection before creating the batch so its Y coordinates
        // are calculated for the static 256x256 target.
        bgRenderer.beginFBOProjection();
        graphicsManager.registerCommand(backgroundCommandPool.obtainBegin(bgRenderer));
        renderer.rebuildBackgroundToFBO(combinedBackgroundMappings, palettes);
        bgRenderer.endFBOProjection();
        graphicsManager.registerCommand(backgroundCommandPool.obtainEnd(bgRenderer));
    }

    /** Bounded owner for deferred Plane-B background commands. */
    static final class BackgroundCommandPool {
        private final ArrayDeque<BackgroundCommand> available = new ArrayDeque<>();
        private SpecialStageBackgroundRenderer activeTilePassRenderer;

        BackgroundCommand obtainShader(SpecialStageBackgroundRenderer renderer,
                                       int hScroll, float vScroll) {
            BackgroundCommand command = obtain();
            command.configureShader(renderer, hScroll, vScroll);
            return command;
        }

        BackgroundCommand obtainBegin(SpecialStageBackgroundRenderer renderer) {
            BackgroundCommand command = obtain();
            command.configure(renderer, BackgroundCommand.Kind.BEGIN);
            return command;
        }

        BackgroundCommand obtainEnd(SpecialStageBackgroundRenderer renderer) {
            BackgroundCommand command = obtain();
            command.configure(renderer, BackgroundCommand.Kind.END);
            return command;
        }

        private BackgroundCommand obtain() {
            BackgroundCommand command = available.pollLast();
            if (command == null) {
                command = new BackgroundCommand(this);
            }
            command.leased = true;
            return command;
        }

        private void release(BackgroundCommand command) {
            available.addLast(command);
        }

        private void armTilePass(SpecialStageBackgroundRenderer renderer) {
            activeTilePassRenderer = renderer;
        }

        private boolean isTilePassArmed(SpecialStageBackgroundRenderer renderer) {
            return activeTilePassRenderer == renderer;
        }

        private void disarmTilePass(SpecialStageBackgroundRenderer renderer) {
            if (activeTilePassRenderer == renderer) {
                activeTilePassRenderer = null;
            }
        }
    }

    static final class BackgroundCommand implements GLCommandable {
        private enum Kind { SHADER, BEGIN, END }

        private final BackgroundCommandPool owner;
        private SpecialStageBackgroundRenderer renderer;
        private Kind kind;
        private int hScroll;
        private float vScroll;
        private boolean leased;

        private BackgroundCommand(BackgroundCommandPool owner) {
            this.owner = owner;
        }

        private void configureShader(SpecialStageBackgroundRenderer renderer,
                                     int hScroll, float vScroll) {
            configure(renderer, Kind.SHADER);
            this.hScroll = hScroll;
            this.vScroll = vScroll;
        }

        private void configure(SpecialStageBackgroundRenderer renderer, Kind kind) {
            this.renderer = renderer;
            this.kind = kind;
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                if (renderer == null) {
                    return;
                }
                switch (kind) {
                    case SHADER -> {
                        renderer.setUniformHScroll(hScroll);
                        renderer.renderWithShader(vScroll);
                    }
                    case BEGIN -> {
                        renderer.beginTilePass(H32_HEIGHT);
                        owner.armTilePass(renderer);
                    }
                    case END -> {
                        try {
                            renderer.endTilePass();
                        } finally {
                            owner.disarmTilePass(renderer);
                        }
                    }
                }
            } finally {
                discard();
            }
        }

        @Override
        public void unwindAfterFailure(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (kind == Kind.END && owner.isTilePassArmed(renderer)) {
                execute(cameraX, cameraY, cameraWidth, cameraHeight);
            } else {
                discard();
            }
        }

        @Override
        public void discard() {
            if (!leased) {
                return;
            }
            leased = false;
            renderer = null;
            owner.release(this);
        }

        int hScroll() {
            return hScroll;
        }

        float vScroll() {
            return vScroll;
        }
    }

    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        if (!alignmentTestMode) {
            return;
        }

        if (alignmentTextRenderer == null) {
            alignmentTextRenderer = new GlyphBatchRenderer();
            alignmentTextRenderer.init(null);
        }

        alignmentTextRenderer.updateViewport(viewportWidth, viewportHeight);
        alignmentTextRenderer.begin();

        int y = viewportHeight - 14;
        alignmentTextRenderer.drawTextOutlined("SS ALIGNMENT TEST (F4 to exit)", 8, y, DebugColor.WHITE, FontSize.SMALL);
        y -= 14;
        int gateIndexBase = 0;
        for (int i = 0; i < ANIM_STRAIGHT.length; i++) {
            if (ANIM_STRAIGHT[i] == CHECKPOINT_TRIGGER_FRAME) {
                gateIndexBase = i;
                break;
            }
        }
        int gateIndex = Math.floorMod(gateIndexBase + alignmentTriggerOffsetFrames, ANIM_STRAIGHT.length);
        alignmentTextRenderer.drawTextOutlined(
                "Gate offset (frames): " + alignmentTriggerOffsetFrames +
                        "  Gate frame: " + gateIndex + "/" + (ANIM_STRAIGHT.length - 1) +
                        "  Map: 0x" + String.format("%02X", ANIM_STRAIGHT[gateIndex]),
                8, y, DebugColor.WHITE, FontSize.SMALL);
        y -= 14;
        alignmentTextRenderer.drawTextOutlined(
                String.format("Speed scale: %.2fx", alignmentRainbowSpeedScale),
                8, y, DebugColor.WHITE, FontSize.SMALL);
        y -= 14;
        alignmentTextRenderer.drawTextOutlined(
                "Arrows: LEFT/RIGHT gate offset, UP/DOWN speed",
                8, y, DebugColor.WHITE, FontSize.SMALL);
        y -= 14;
        alignmentTextRenderer.drawTextOutlined(
                "Step mode: " + (alignmentStepByTrackFrame ? "TRACK" : "VINT") +
                        "  TrackFrame: " + alignmentTrackFrameIndex +
                        "  DrawIdx: " + alignmentDrawingIndex,
                8, y, DebugColor.WHITE, FontSize.SMALL);

        alignmentTextRenderer.end();
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

    /** Returns whether the ROM startup has reached its fade-from-white boundary. */
    public boolean isEntryPresentationReady() {
        if (!initialized || intro == null) {
            return false;
        }
        return switch (intro.getCurrentPhase()) {
            case FADE_FROM_WHITE, DROP, WAIT1, WAIT2, MESSAGE_FLYOUT, GAMEPLAY -> true;
            case PRE_ROLL, ROM_STARTUP -> false;
        };
    }

    /**
     * Returns whether the ROM's {@code Pal_FadeToWhite} (the PRE_ROLL window)
     * is still running, during which the display shows the level's last frame.
     */
    public boolean isEntryFadeToWhiteActive() {
        return initialized && intro != null
                && intro.getCurrentPhase() == Sonic2SpecialStageIntro.Phase.PRE_ROLL;
    }

    /**
     * Arms the live reproduction of the entry load
     * ({@link Sonic2SpecialStageLagModel#ENTRY_LOAD_LAG_FRAMES}); it elapses
     * from the first ROM_STARTUP update. Reserved for an accurate
     * presentation mode: no startup policy arms it today.
     */
    public void armLiveEntryLoadHold() {
        liveEntryLoadHoldFrames = Sonic2SpecialStageLagModel.ENTRY_LOAD_LAG_FRAMES;
    }

    public int getLiveEntryLoadHoldFrames() {
        return liveEntryLoadHoldFrames;
    }

    /** Compresses hidden startup while preserving the normal manager update path. */
    public void advanceToEntryPresentation() {
        advanceToEntryPresentation(256);
    }

    void advanceToEntryPresentation(int maxUpdates) {
        Sonic2SpecialStageIntro.Phase phase = intro.getCurrentPhase();
        if (phase != Sonic2SpecialStageIntro.Phase.PRE_ROLL
                && phase != Sonic2SpecialStageIntro.Phase.ROM_STARTUP) {
            throw new IllegalStateException("Cannot fast-forward special-stage startup from " + phase);
        }
        for (int update = 0; update < maxUpdates && !isEntryPresentationReady(); update++) {
            update();
        }
        if (!isEntryPresentationReady()) {
            throw new IllegalStateException("Special-stage startup did not reach reveal boundary from "
                    + intro.getCurrentPhase() + " within " + maxUpdates + " updates");
        }
    }

    /** Enables live pacing for positive values and disables it for trace pacing. */
    public void setLagCompensation(double factor) {
        liveLagSimulationEnabled = factor > 0.0;
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

    /** {@code SSTrack_drawing_index}, exposed for startup-cadence tests. */
    int getDrawingIndex() {
        return drawingIndex;
    }

    int getSwapPositionsFlag() {
        return swapPositionsFlag & 0xFF;
    }

    void setSwapPositionsFlag(boolean set) {
        swapPositionsFlag = set ? 0xFF : 0;
    }

    void toggleSwapPositionsFlag() {
        swapPositionsFlag ^= 0xFF;
    }

    /**
     * Resets the Special Stage manager state.
     */
    public void reset() {
        // Audio is owned by the transition that resets this manager: the entry
        // has already queued SndID_SpecStageEntry and MusID_FadeOut
        // (s2.asm:6543-6546) and stopping playback here would cut both, and
        // the results/level owners start their own music.
        initialized = false;
        liveLagSimulationEnabled = true;
        liveEntryLoadHoldFrames = 0;
        stagePalettesUploaded = false;
        backgroundStageGeneration++;
        if (renderer != null) {
            renderer.beginStaticBackgroundStage(backgroundStageGeneration);
        }
        Sonic2TrackFrameDecoder.invalidateDecodedFrameCache();
        currentStage = 0;
        trackAnimator = null;
        speedPromotionPending = false;
        pendingSpeedFactor = 0;
        initialPlayerSpawnPending = false;
        playerBootstrapPhase = PlayerBootstrapPhase.WAIT_FIRST_DRAWING_WRAP;
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

        // Detach renderer-owned ordering scratch before the authoritative
        // collections below are cleared or discarded.
        if (renderer != null) {
            renderer.setPlayers(null);
            renderer.setObjectManager(null);
        }

        sonicPlayer = null;
        tailsPlayer = null;
        players.clear();

        heldButtons = 0;
        pressedButtons = 0;
        p2HeldButtons = 0;
        p2LogicalButtons = 0;
        recurringMainPassPending = false;
        pendingMainHeldButtons = 0;
        pendingMainPressedButtons = 0;
        pendingMainP2HeldButtons = 0;
        pendingMainP2LogicalButtons = 0;
        pendingMainCheckpointStep = false;
        previousPhysicalHeldButtons = 0;
        previousPhysicalPressedButtons = 0;
        previousPhysicalP2HeldButtons = 0;
        previousPhysicalP2LogicalButtons = 0;
        tailsControlCounter = 0;
        java.util.Arrays.fill(tailsCtrlRecordBuf, 0);
        swapPositionsFlag = 0;

        intro = null;
        hudPatternBase = 0;
        startPatternBase = 0;
        messagesPatternBase = 0;
        tailsTextPatternBase = 0;

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
        shadowFlatPatternBase = 0;
        shadowDiagPatternBase = 0;
        shadowSidePatternBase = 0;
        lastDrawingIndex = -1;

        // Checkpoint system
        if (checkpoint != null) {
            checkpoint.reset();
        }
        checkpoint = null;
        checkpointRainbowPaletteActive = false;
        rainbowPaletteCycleIndex = 0;
        pendingCheckpoint = false;
        pendingCheckpointNumber = 0;
        pendingRingRequirement = 0;
        pendingRingsCollected = 0;
        pendingFinalCheckpoint = false;
        alignmentTestMode = false;
        alignmentCheckpoint = null;
        alignmentDecodedTrackFrame = null;
        alignmentTrackFrameIndex = -1;
        alignmentLastDecodedFrameIndex = -1;
        alignmentFrameIndex = 0;
        alignmentFrameTimer = 0;
        alignmentDrawingIndex = 0;
        alignmentTestSavedRainbowPalette = false;
        currentRingRequirement = 0;

        resultState = ResultState.RUNNING;
        emeraldCollected = false;
        diagnosticDone = false;
        flipDiagnosticDone = false;
        clearDiagnosticTimingState();

        // Shader-based background renderer cleanup
        if (bgRenderer != null) {
            bgRenderer.cleanup();
            bgRenderer = null;
        }

        // Skydome scroll state
        skydomeScrollX = 0;
        vScrollBG = 0;
        alternateScrollBuffer = false;
        lastAlternateScrollBuffer = false;
        drawingIndex = 0;
        lastAnimFrame = 0;
        planeDebugMode = PlaneDebugMode.BOTH;
    }

    public void cyclePlaneDebugMode() {
        planeDebugMode = planeDebugMode.next();
        LOGGER.info("Special Stage plane debug: " + planeDebugMode.label());
    }

    /**
     * Gets the intro sequence manager.
     */
    public Sonic2SpecialStageIntro getIntro() {
        return intro;
    }

    int getSkydomeScrollXForTest() {
        return skydomeScrollX;
    }

    int getVScrollBGForTest() {
        return vScrollBG;
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

    Sonic2SpecialStageSnapshot captureRewindSnapshot() {
        ArrayList<Sonic2SpecialStageSnapshot.PlayerSnapshot> playerSnapshots = new ArrayList<>();
        for (Sonic2SpecialStagePlayer player : players) {
            playerSnapshots.add(player.captureRewindSnapshot());
        }
        return new Sonic2SpecialStageSnapshot(
                initialized,
                currentStage,
                resultState,
                emeraldCollected,
                frameCounter,
                renderFrameCounter,
                heldButtons,
                pressedButtons,
                p2HeldButtons,
                p2LogicalButtons,
                recurringMainPassPending,
                introFirstRecurringPassDeferred,
                pendingMainHeldButtons,
                pendingMainPressedButtons,
                pendingMainP2HeldButtons,
                pendingMainP2LogicalButtons,
                pendingMainCheckpointStep,
                previousPhysicalHeldButtons,
                previousPhysicalPressedButtons,
                previousPhysicalP2HeldButtons,
                previousPhysicalP2LogicalButtons,
                tailsControlCounter,
                tailsCtrlRecordBuf,
                swapPositionsFlag,
                lastDrawingIndex,
                checkpointRainbowPaletteActive,
                rainbowPaletteCycleIndex,
                pendingCheckpoint,
                pendingCheckpointNumber,
                pendingRingRequirement,
                pendingRingsCollected,
                pendingFinalCheckpoint,
                currentRingRequirement,
                spriteDebugMode,
                planeDebugMode,
                alignmentTestMode,
                alignmentTestSavedRainbowPalette,
                alignmentPendingCheckpoint,
                alignmentFrameIndex,
                alignmentFrameTimer,
                alignmentTrackFrameIndex,
                alignmentDrawingIndex,
                alignmentTriggerOffsetFrames,
                alignmentRainbowSpeedScale,
                alignmentRainbowSpeedAccumulator,
                alignmentStepByTrackFrame,
                liveLagSimulationEnabled,
                liveEntryLoadHoldFrames,
                diagnosticWallStartTime,
                diagnosticUpdateCount,
                diagnosticTrackAdvances,
                lastFrameTime,
                frameSampleCount,
                frameSampleSum,
                skydomeScrollX,
                alternateScrollBuffer,
                lastAlternateScrollBuffer,
                drawingIndex,
                speedPromotionPending,
                pendingSpeedFactor,
                initialPlayerSpawnPending,
                playerBootstrapPhase,
                lastAnimFrame,
                vScrollBG,
                hScrollDebugTotal,
                hScrollDebugFrames,
                lastDebugSegmentIndex,
                palettes,
                trackAnimator != null ? trackAnimator.captureRewindSnapshot() : null,
                Sonic2SpecialStageSnapshot.PlayerTopologySnapshot.capture(players, sonicPlayer, tailsPlayer),
                playerSnapshots,
                intro != null ? intro.captureRewindSnapshot() : null,
                objectManager != null ? objectManager.captureRewindSnapshot() : null,
                checkpoint != null ? checkpoint.captureRewindSnapshot() : null,
                alignmentCheckpoint != null ? alignmentCheckpoint.captureRewindSnapshot() : null);
    }

    void restoreRewindSnapshot(Sonic2SpecialStageSnapshot snapshot) {
        initialized = snapshot.initialized;
        currentStage = snapshot.currentStage;
        resultState = snapshot.resultState;
        emeraldCollected = snapshot.emeraldCollected;
        frameCounter = snapshot.frameCounter;
        renderFrameCounter = snapshot.renderFrameCounter;
        heldButtons = snapshot.heldButtons;
        pressedButtons = snapshot.pressedButtons;
        p2HeldButtons = snapshot.p2HeldButtons;
        p2LogicalButtons = snapshot.p2LogicalButtons;
        recurringMainPassPending = snapshot.recurringMainPassPending;
        introFirstRecurringPassDeferred = snapshot.introFirstRecurringPassDeferred;
        pendingMainHeldButtons = snapshot.pendingMainHeldButtons;
        pendingMainPressedButtons = snapshot.pendingMainPressedButtons;
        pendingMainP2HeldButtons = snapshot.pendingMainP2HeldButtons;
        pendingMainP2LogicalButtons = snapshot.pendingMainP2LogicalButtons;
        pendingMainCheckpointStep = snapshot.pendingMainCheckpointStep;
        previousPhysicalHeldButtons = snapshot.previousPhysicalHeldButtons;
        previousPhysicalPressedButtons = snapshot.previousPhysicalPressedButtons;
        previousPhysicalP2HeldButtons = snapshot.previousPhysicalP2HeldButtons;
        previousPhysicalP2LogicalButtons = snapshot.previousPhysicalP2LogicalButtons;
        tailsControlCounter = snapshot.tailsControlCounter;
        System.arraycopy(snapshot.tailsCtrlRecordBuf, 0, tailsCtrlRecordBuf, 0,
                Math.min(tailsCtrlRecordBuf.length, snapshot.tailsCtrlRecordBuf.length));
        swapPositionsFlag = snapshot.swapPositionsFlag;
        lastDrawingIndex = snapshot.lastDrawingIndex;
        checkpointRainbowPaletteActive = snapshot.checkpointRainbowPaletteActive;
        rainbowPaletteCycleIndex = snapshot.rainbowPaletteCycleIndex;
        pendingCheckpoint = snapshot.pendingCheckpoint;
        pendingCheckpointNumber = snapshot.pendingCheckpointNumber;
        pendingRingRequirement = snapshot.pendingRingRequirement;
        pendingRingsCollected = snapshot.pendingRingsCollected;
        pendingFinalCheckpoint = snapshot.pendingFinalCheckpoint;
        currentRingRequirement = snapshot.currentRingRequirement;
        spriteDebugMode = snapshot.spriteDebugMode;
        if (debugSprites != null) {
            debugSprites.setEnabled(spriteDebugMode);
        }
        planeDebugMode = (PlaneDebugMode) snapshot.planeDebugMode;
        alignmentTestMode = snapshot.alignmentTestMode;
        alignmentTestSavedRainbowPalette = snapshot.alignmentTestSavedRainbowPalette;
        alignmentPendingCheckpoint = snapshot.alignmentPendingCheckpoint;
        alignmentFrameIndex = snapshot.alignmentFrameIndex;
        alignmentFrameTimer = snapshot.alignmentFrameTimer;
        alignmentTrackFrameIndex = snapshot.alignmentTrackFrameIndex;
        alignmentLastDecodedFrameIndex = -1;
        alignmentDecodedTrackFrame = null;
        alignmentDrawingIndex = snapshot.alignmentDrawingIndex;
        alignmentTriggerOffsetFrames = snapshot.alignmentTriggerOffsetFrames;
        alignmentRainbowSpeedScale = snapshot.alignmentRainbowSpeedScale;
        alignmentRainbowSpeedAccumulator = snapshot.alignmentRainbowSpeedAccumulator;
        alignmentStepByTrackFrame = snapshot.alignmentStepByTrackFrame;
        liveLagSimulationEnabled = snapshot.liveLagSimulationEnabled;
        liveEntryLoadHoldFrames = snapshot.liveEntryLoadHoldFrames;
        diagnosticWallStartTime = snapshot.diagnosticWallStartTime;
        diagnosticUpdateCount = snapshot.diagnosticUpdateCount;
        diagnosticTrackAdvances = snapshot.diagnosticTrackAdvances;
        lastFrameTime = snapshot.lastFrameTime;
        frameSampleCount = snapshot.frameSampleCount;
        frameSampleSum = snapshot.frameSampleSum;
        diagnosticEpochActive = false;
        skydomeScrollX = snapshot.skydomeScrollX;
        alternateScrollBuffer = snapshot.alternateScrollBuffer;
        lastAlternateScrollBuffer = snapshot.lastAlternateScrollBuffer;
        drawingIndex = snapshot.drawingIndex;
        speedPromotionPending = snapshot.speedPromotionPending;
        pendingSpeedFactor = snapshot.pendingSpeedFactor;
        initialPlayerSpawnPending = snapshot.initialPlayerSpawnPending;
        playerBootstrapPhase = snapshot.playerBootstrapPhase;
        lastAnimFrame = snapshot.lastAnimFrame;
        vScrollBG = snapshot.vScrollBG;
        hScrollDebugTotal = snapshot.hScrollDebugTotal;
        hScrollDebugFrames = snapshot.hScrollDebugFrames;
        lastDebugSegmentIndex = snapshot.lastDebugSegmentIndex;
        decodedTrackFrame = null;
        lastDecodedFrameIndex = -1;
        lastDecodedFlipped = false;
        palettes = Sonic2SpecialStageSnapshot.clonePalettes(snapshot.palettes);
        recacheRestoredPalettes();
        if (trackAnimator != null && snapshot.trackAnimator != null) {
            trackAnimator.restoreRewindSnapshot(snapshot.trackAnimator);
        }
        restorePlayerTopologyForRewind(snapshot.playerTopology, snapshot.players);
        if (intro != null && snapshot.intro != null) {
            intro.restoreRewindSnapshot(snapshot.intro);
        }
        if (objectManager != null && snapshot.objectManager != null) {
            objectManager.restoreRewindSnapshot(snapshot.objectManager, this);
        }
        if (checkpoint != null && snapshot.checkpoint != null) {
            checkpoint.restoreRewindSnapshot(snapshot.checkpoint);
        }
        if (snapshot.alignmentCheckpoint != null) {
            if (alignmentCheckpoint == null) {
                alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();
            }
            alignmentCheckpoint.restoreRewindSnapshot(snapshot.alignmentCheckpoint);
        } else {
            alignmentCheckpoint = null;
        }
        reconstructDerivedTrackFramesAfterRestore();
        if (renderer != null) {
            renderer.setPlayers(players);
            renderer.setIntro(intro);
            renderer.setCheckpoint(alignmentTestMode && alignmentCheckpoint != null
                    ? alignmentCheckpoint
                    : checkpoint);
        }
    }

    private void reconstructDerivedTrackFramesAfterRestore() {
        decodedTrackFrame = null;
        lastDecodedFrameIndex = -1;
        lastDecodedFlipped = false;
        alignmentDecodedTrackFrame = null;
        alignmentLastDecodedFrameIndex = -1;

        if (trackAnimator != null) {
            decodeCurrentTrackFrame();
        }
        if (alignmentTestMode) {
            decodeAlignmentTrackFrame();
        }
    }

    private void recacheRestoredPalettes() {
        // A restore into PRE_ROLL lands before SSInitPalAndData: the level's
        // palette must stay in the shared lines until the intro leaves it.
        stagePalettesUploaded = false;
        if (intro != null && intro.getCurrentPhase() == Sonic2SpecialStageIntro.Phase.PRE_ROLL) {
            return;
        }
        uploadStagePalettes();
    }

    void restorePlayerTopologyForRewind(
            Sonic2SpecialStageSnapshot.PlayerTopologySnapshot topology,
            java.util.List<Sonic2SpecialStageSnapshot.PlayerSnapshot> playerSnapshots) {
        if (topology.slots().size() != players.size() || topology.slots().size() != playerSnapshots.size()) {
            throw new IllegalStateException("Sonic 2 special-stage player count changed during rewind restore");
        }
        for (int i = 0; i < players.size(); i++) {
            Sonic2SpecialStagePlayer player = players.get(i);
            player.setSwapPositionsOwner(this);
            Sonic2SpecialStageSnapshot.PlayerSlotSnapshot slot = topology.slots().get(i);
            if (player.getPlayerType() != slot.type() || player.isMainCharacter() != slot.mainCharacter()) {
                throw new IllegalStateException("Sonic 2 special-stage player topology changed during rewind restore");
            }
            player.restoreRewindSnapshot(playerSnapshots.get(i));
        }
        sonicPlayer = topology.sonicSlotIndex() >= 0 ? players.get(topology.sonicSlotIndex()) : null;
        tailsPlayer = topology.tailsSlotIndex() >= 0 ? players.get(topology.tailsSlotIndex()) : null;
        if (topology.playersLinked()) {
            sonicPlayer.setOtherPlayer(tailsPlayer);
            tailsPlayer.setOtherPlayer(sonicPlayer);
        } else {
            if (sonicPlayer != null) sonicPlayer.setOtherPlayer(null);
            if (tailsPlayer != null) tailsPlayer.setOtherPlayer(null);
        }
        if (renderer != null) {
            renderer.setPlayers(players);
        }
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
        debugSprites.setEnabled(spriteDebugMode);
        LOGGER.info("Sprite debug mode: " + (spriteDebugMode ? "ON" : "OFF"));
    }

    /**
     * Checks if sprite debug mode is active.
     */
    public boolean isSpriteDebugMode() {
        return spriteDebugMode;
    }

    /**
     * Gets the debug provider for sprite viewing.
     * 
     * @return the debug provider, or null if not initialized
     */
    public SpecialStageDebugProvider getDebugProvider() {
        if (!initialized) {
            return null;
        }
        return debugSprites;
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
     * Ring count belonging to a single playable — the engine's equivalent of the
     * ROM's per-player {@code Ring_count} / {@code Ring_count_2P} words.
     *
     * <p>The special-stage exit copies those two words into two independent
     * bonus countdowns, {@code move.w (Ring_count).w,(Bonus_Countdown_1).w} and
     * {@code move.w (Ring_count_2P).w,(Bonus_Countdown_2).w}
     * (docs/s2disasm/s2.asm:6784-6785), so the results tally needs the split and
     * not the combined total {@link #getRingsCollected()} reports.
     *
     * @param type which playable's count to read
     * @return that playable's rings, or 0 when it is not present in the stage
     */
    public int getRingsCollected(Sonic2SpecialStagePlayer.PlayerType type) {
        for (Sonic2SpecialStagePlayer player : players) {
            if (player.getPlayerType() == type) {
                return player.getRings();
            }
        }
        return 0;
    }

    /**
     * Assembles a read-only snapshot of manager/animator/player state for a trace
     * replay harness to compare against a recorded ROM trace. Pure read — no
     * mutation, no caching. {@code trackAnimator} is null until the stage is fully
     * loaded, so its fields fall back to the ROM's pre-initialization state
     * (speed factor 0, segment/frame/delay counters 0; s2.asm:6640, 960-975).
     */
    public Sonic2SpecialStageComparisonState captureComparisonState() {
        int speedFactorValue;
        int currentSegmentIndexValue;
        int trackAnimFrameValue;
        int trackFrameDelayCounterValue;
        int playerAnimFrameTimerValue;
        if (trackAnimator != null) {
            speedFactorValue = trackAnimator.getSpeedFactor();
            currentSegmentIndexValue = trackAnimator.getCurrentSegmentIndex();
            trackAnimFrameValue = trackAnimator.getCurrentFrameInSegment();
            trackFrameDelayCounterValue = trackAnimator.getFrameDelayCounter();
            playerAnimFrameTimerValue = trackAnimator.getPlayerAnimFrameTimer();
        } else {
            speedFactorValue = 0;
            currentSegmentIndexValue = 0;
            trackAnimFrameValue = 0;
            trackFrameDelayCounterValue = 0;
            playerAnimFrameTimerValue = 0;
        }

        int combinedRings = getRingsCollected();
        int ringsToGo = Math.max(0, currentRingRequirement - combinedRings);

        return new Sonic2SpecialStageComparisonState(
                speedFactorValue,
                currentSegmentIndexValue,
                trackAnimFrameValue,
                drawingIndex,
                trackFrameDelayCounterValue,
                playerAnimFrameTimerValue,
                ringsToGo,
                combinedRings,
                tailsControlCounter,
                getSwapPositionsFlag(),
                isFinished(),
                toComparisonPlayerState(sonicPlayer),
                toComparisonPlayerState(tailsPlayer));
    }

    private static Sonic2SpecialStageComparisonState.PlayerState toComparisonPlayerState(
            Sonic2SpecialStagePlayer player) {
        if (player == null || !player.isSpawned()) {
            return null;
        }
        return new Sonic2SpecialStageComparisonState.PlayerState(
                player.getSSXPos(),
                player.getSSYPos(),
                player.getSSZPos(),
                player.getAngle(),
                player.getRoutine().name(),
                player.isHurt() ? 2 : 0,
                player.getAnim(),
                player.getAnimFrame(),
                player.getRings(),
                player.getHurtTimer(),
                player.getSlideTimer(),
                player.getFlipTimer());
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


    static final class Sonic2SpecialStageObjectManager {
        private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageObjectManager.class.getName());

        /** Marker bytes */
        public static final int MARKER_END = 0xFF;
        public static final int MARKER_CHECKPOINT = 0xFE;
        public static final int MARKER_EMERALD = 0xFD;

        /** Object type bit in first byte */
        public static final int OBJECT_TYPE_BOMB_BIT = 0x40;

        /** Distance mask for first byte */
        public static final int DISTANCE_MASK = 0x3F;

        /** Animation lengths per segment type (from Ani_SSTrack_Len) */
        private static final int[] SEGMENT_ANIM_LENGTHS = { 24, 24, 12, 16, 11 };

        private final Sonic2SpecialStageDataLoader dataLoader;

        /** Raw object location data (decompressed from ROM) */
        private byte[] objectLocationData;

        /** Per-stage offsets into the object data */
        private int[] stageOffsets;

        /** Current read position in object data */
        private int currentPosition;

        /** Current stage index */
        private int currentStage;

        /** Last segment that had objects spawned */
        private int lastProcessedSegment = -1;

        /** Active special stage objects (rings and bombs) */
        private final List<Sonic2SpecialStageObject> activeObjects = new ArrayList<>();

        /** Ring counter */
        private int ringsCollected = 0;

        /** Total rings for "perfect" tracking */
        private int perfectRingsTotal = 0;

        /** Current special act (checkpoint number 0-3) */
        private int currentSpecialAct = 0;

        /** Flags for checkpoint handling */
        private boolean noCheckpointFlag = false;
        private boolean noCheckpointMsgFlag = false;

        /** Flag to control "rings to go" display - hidden until first $FC marker */
        private boolean ringsToGoEnabled = false;

        /** Whether an emerald was spawned */
        private boolean emeraldSpawned = false;

        /** Callback interface for checkpoint events */
        public interface CheckpointCallback {
            /**
             * Called when a checkpoint marker is encountered.
             * @param checkpointNumber The checkpoint number (1-4)
             * @param ringsCollected Current rings collected
             */
            void onCheckpoint(int checkpointNumber, int ringsCollected);

            /**
             * Called when an emerald marker is encountered.
             */
            void onEmerald();
        }

        /** Callback for checkpoint events */
        private CheckpointCallback checkpointCallback;

        public Sonic2SpecialStageObjectManager(Sonic2SpecialStageDataLoader dataLoader) {
            this.dataLoader = dataLoader;
        }

        /**
         * Sets the callback for checkpoint events.
         */
        public void setCheckpointCallback(CheckpointCallback callback) {
            this.checkpointCallback = callback;
        }

        /**
         * Initializes the object manager for the specified stage.
         */
        public void initialize(int stageIndex) throws IOException {
            this.currentStage = stageIndex;
            this.currentPosition = 0;
            this.lastProcessedSegment = -1;
            this.ringsCollected = 0;
            this.perfectRingsTotal = 0;
            this.currentSpecialAct = 0;
            this.noCheckpointFlag = false;
            this.noCheckpointMsgFlag = false;
            this.emeraldSpawned = false;
            activeObjects.clear();

            // Load object location data
            objectLocationData = dataLoader.getObjectLocations();

            // Parse stage offsets from the data
            parseStageOffsets();

            // Set read position to current stage
            if (stageIndex >= 0 && stageIndex < SPECIAL_STAGE_COUNT && stageOffsets != null) {
                currentPosition = stageOffsets[stageIndex];
            }

            LOGGER.info("Object manager initialized for stage " + (stageIndex + 1) +
                       ", data offset: " + currentPosition);
        }

        /**
         * Parses the stage offset table from the beginning of the object location data.
         * Format: 7 words (big-endian) pointing to each stage's object stream.
         */
        private void parseStageOffsets() {
            if (objectLocationData == null || objectLocationData.length < SPECIAL_STAGE_COUNT * 2) {
                LOGGER.warning("Invalid object location data");
                stageOffsets = null;
                return;
            }

            stageOffsets = new int[SPECIAL_STAGE_COUNT];
            for (int i = 0; i < SPECIAL_STAGE_COUNT; i++) {
                int offset = ((objectLocationData[i * 2] & 0xFF) << 8) |
                            (objectLocationData[i * 2 + 1] & 0xFF);
                stageOffsets[i] = offset;
                LOGGER.fine("Stage " + (i + 1) + " object data offset: 0x" + Integer.toHexString(offset));
            }
        }

        /**
         * Processes objects for a segment transition.
         * This should be called when SSTrack_drawing_index == 4 and a new segment begins.
         *
         * @param segmentIndex The current segment index
         * @param segmentType The segment animation type (0-4)
         * @return List of newly spawned objects
         */
        public List<Sonic2SpecialStageObject> processSegment(int segmentIndex, int segmentType) {
            List<Sonic2SpecialStageObject> newObjects = new ArrayList<>();

            // Only process once per segment
            if (segmentIndex == lastProcessedSegment) {
                return newObjects;
            }
            lastProcessedSegment = segmentIndex;

            if (objectLocationData == null || currentPosition >= objectLocationData.length) {
                return newObjects;
            }

            // Get segment animation length for depth calculation
            int segmentAnimLength = getSegmentAnimLength(segmentType);
            int depthOffset = segmentAnimLength * 4;

            LOGGER.fine("Processing segment " + segmentIndex +
                       " (type=" + segmentType + ", depthOffset=" + depthOffset + ")");

            // Read objects until we hit a marker
            while (currentPosition < objectLocationData.length) {
                int firstByte = objectLocationData[currentPosition] & 0xFF;

                // Check for negative value (marker)
                if ((firstByte & 0x80) != 0) {
                    // This is a marker byte
                    currentPosition++;
                    handleMarker(firstByte, newObjects);
                    break; // Exit after processing marker
                }

                // Regular object entry
                currentPosition++;
                if (currentPosition >= objectLocationData.length) break;

                int angleByte = objectLocationData[currentPosition] & 0xFF;
                currentPosition++;

                // Parse object type and distance
                boolean isBomb = (firstByte & OBJECT_TYPE_BOMB_BIT) != 0;
                int distanceIndex = firstByte & DISTANCE_MASK;

                // Calculate depth value (objoff_30)
                int depth = (distanceIndex * 4) + depthOffset;

                // Create and add the object
                Sonic2SpecialStageObject obj;
                if (isBomb) {
                    obj = new Sonic2SpecialStageBomb();
                } else {
                    obj = new Sonic2SpecialStageRing();
                    perfectRingsTotal++;
                }

                obj.initialize(depth, angleByte);
                activeObjects.add(obj);
                newObjects.add(obj);

                LOGGER.fine("Spawned " + (isBomb ? "bomb" : "ring") +
                           " at angle=" + angleByte + ", depth=" + depth);
            }

            return newObjects;
        }

        /**
         * Handles a marker byte in the object stream.
         */
        private void handleMarker(int marker, List<Sonic2SpecialStageObject> newObjects) {
            // Convert to signed for comparison (matching assembly's bmi check)
            if (marker == MARKER_END) {
                // $FF: End of segment's objects - just return
                LOGGER.fine("End marker ($FF) at segment");
                return;
            }

            if (marker == MARKER_CHECKPOINT) {
                // $FE: Checkpoint marker
                LOGGER.info("Checkpoint marker ($FE) - act " + currentSpecialAct);
                handleCheckpoint();
                return;
            }

            if (marker == MARKER_EMERALD) {
                // $FD: Emerald marker
                LOGGER.info("Emerald marker ($FD)");
                handleEmerald(newObjects);
                return;
            }

            // $FC and below: No-checkpoint marker
            // This enables the "rings to go" counter display but does NOT trigger checkpoint animation
            LOGGER.fine("No-checkpoint marker (0x" + Integer.toHexString(marker) + ")");
            noCheckpointFlag = true;
            noCheckpointMsgFlag = false;

            // Enable the "rings to go" display (matches Obj5A_RingsMessageInit clearing flags)
            ringsToGoEnabled = true;
            LOGGER.fine("Rings to go display enabled");
            // NOTE: Unlike $FE, this does NOT call handleCheckpoint() - it just sets flags
        }

        /**
         * Handles checkpoint marker processing.
         */
        private void handleCheckpoint() {
            // Increment the special act counter
            currentSpecialAct++;

            LOGGER.info("Checkpoint " + currentSpecialAct + " reached with " + ringsCollected + " rings");

            // Notify callback if set
            if (checkpointCallback != null) {
                checkpointCallback.onCheckpoint(currentSpecialAct, ringsCollected);
            }
        }

        /**
         * Handles emerald marker processing.
         * Spawns the emerald object that appears at the end of the stage.
         */
        private void handleEmerald(List<Sonic2SpecialStageObject> newObjects) {
            emeraldSpawned = true;

            // Create emerald object at depth 54 ($36), angle 0x40 (bottom center)
            Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
            emerald.initialize(54, 0x40);  // Initial values from disassembly
            activeObjects.add(emerald);  // Add to active list so it gets updated
            newObjects.add(emerald);

            LOGGER.info("Emerald object spawned at depth 54, angle 0x40");

            // Notify callback if set
            if (checkpointCallback != null) {
                checkpointCallback.onEmerald();
            }
        }

        /**
         * Gets the active emerald object if one exists.
         */
        public Sonic2SpecialStageEmerald getActiveEmerald() {
            for (Sonic2SpecialStageObject obj : activeObjects) {
                if (obj.isEmerald()) {
                    return (Sonic2SpecialStageEmerald) obj;
                }
            }
            return null;
        }

        /**
         * Gets the animation length for a segment type.
         */
        private int getSegmentAnimLength(int segmentType) {
            if (segmentType >= 0 && segmentType < SEGMENT_ANIM_LENGTHS.length) {
                return SEGMENT_ANIM_LENGTHS[segmentType];
            }
            return 16; // Default to STRAIGHT length
        }

        /**
         * Updates all active objects.
         * Called each frame to update object positions and animations.
         *
         * @param currentTrackFrame Current track mapping frame (0-55)
         * @param trackFlipped Whether the track is flipped (left turn)
         * @param speedFactor Current speed factor from track animator
         * @param drawingIndex4 True if SSTrack_drawing_index == 4 (affects depth decrement rate)
         */
        public void update(int currentTrackFrame, boolean trackFlipped, int speedFactor, boolean drawingIndex4) {
            // Update each active object
            for (int i = activeObjects.size() - 1; i >= 0; i--) {
                Sonic2SpecialStageObject obj = activeObjects.get(i);
                obj.update(currentTrackFrame, trackFlipped, speedFactor, drawingIndex4);

                // Remove objects that are done (collected or off-screen)
                if (obj.shouldRemove()) {
                    activeObjects.remove(i);
                }
            }
        }

        /**
         * Gets all active objects for rendering.
         */
        public List<Sonic2SpecialStageObject> getActiveObjects() {
            return activeObjects;
        }

        /**
         * Collects a ring and increments the counter.
         */
        public void collectRing(Sonic2SpecialStagePlayer player) {
            player.collectRing();
            ringsCollected++;
        }

        /**
         * Loses rings from a bomb hit (BCD-style subtraction).
         * Returns the number of rings lost.
         */
        public int loseRingsFromBombHit(Sonic2SpecialStagePlayer player) {
            int ringsLost = player.loseRingsFromBombHit();
            ringsCollected -= ringsLost;
            return ringsLost;
        }

        /**
         * Gets the current ring count.
         */
        public int getRingsCollected() {
            return ringsCollected;
        }

        /**
         * Gets the total rings spawned (for perfect bonus tracking).
         */
        public int getPerfectRingsTotal() {
            return perfectRingsTotal;
        }

        /**
         * Gets the current special act (checkpoint number).
         */
        public int getCurrentSpecialAct() {
            return currentSpecialAct;
        }

        /**
         * Checks if an emerald was spawned in this stage.
         */
        public boolean isEmeraldSpawned() {
            return emeraldSpawned;
        }

        /**
         * Checks if the "rings to go" display is enabled.
         * This is false until the first $FC marker is encountered.
         */
        public boolean isRingsToGoEnabled() {
            return ringsToGoEnabled;
        }

        /**
         * Resets the "rings to go" display enabled flag.
         * Called after passing a checkpoint to hide the display until the next $FC marker.
         */
        public void resetRingsToGoEnabled() {
            ringsToGoEnabled = false;
        }

        Sonic2SpecialStageSnapshot.ObjectManagerSnapshot captureRewindSnapshot() {
            ArrayList<Sonic2SpecialStageSnapshot.ObjectSnapshot> objects = new ArrayList<>();
            for (Sonic2SpecialStageObject object : activeObjects) {
                objects.add(object.captureRewindSnapshot());
            }
            return new Sonic2SpecialStageSnapshot.ObjectManagerSnapshot(
                    objectLocationData,
                    stageOffsets,
                    currentPosition,
                    currentStage,
                    lastProcessedSegment,
                    ringsCollected,
                    perfectRingsTotal,
                    currentSpecialAct,
                    noCheckpointFlag,
                    noCheckpointMsgFlag,
                    ringsToGoEnabled,
                    emeraldSpawned,
                    objects);
        }

        void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectManagerSnapshot snapshot,
                                   Sonic2SpecialStageManager owner) {
            objectLocationData = Sonic2SpecialStageSnapshot.cloneByteArray(snapshot.objectLocationData());
            stageOffsets = Sonic2SpecialStageSnapshot.cloneIntArray(snapshot.stageOffsets());
            currentPosition = snapshot.currentPosition();
            currentStage = snapshot.currentStage();
            lastProcessedSegment = snapshot.lastProcessedSegment();
            ringsCollected = snapshot.ringsCollected();
            perfectRingsTotal = snapshot.perfectRingsTotal();
            currentSpecialAct = snapshot.currentSpecialAct();
            noCheckpointFlag = snapshot.noCheckpointFlag();
            noCheckpointMsgFlag = snapshot.noCheckpointMsgFlag();
            ringsToGoEnabled = snapshot.ringsToGoEnabled();
            emeraldSpawned = snapshot.emeraldSpawned();

            activeObjects.clear();
            for (Sonic2SpecialStageSnapshot.ObjectSnapshot objectSnapshot : snapshot.activeObjects()) {
                Sonic2SpecialStageObject object = switch (objectSnapshot.type()) {
                    case RING -> {
                        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
                        ring.restoreRewindSnapshot(objectSnapshot);
                        yield ring;
                    }
                    case BOMB -> {
                        Sonic2SpecialStageBomb bomb = new Sonic2SpecialStageBomb();
                        bomb.restoreRewindSnapshot(objectSnapshot);
                        yield bomb;
                    }
                    case EMERALD -> {
                        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
                        emerald.restoreRewindSnapshot(objectSnapshot, owner);
                        yield emerald;
                    }
                };
                activeObjects.add(object);
            }
        }

        /**
         * Resets the manager state.
         */
        public void reset() {
            currentPosition = 0;
            lastProcessedSegment = -1;
            ringsCollected = 0;
            perfectRingsTotal = 0;
            currentSpecialAct = 0;
            noCheckpointFlag = false;
            noCheckpointMsgFlag = false;
            ringsToGoEnabled = false;
            emeraldSpawned = false;
            activeObjects.clear();
        }
    }
}

interface DiagnosticClock {
    DiagnosticClock SYSTEM = new DiagnosticClock() {
        @Override
        public long nanoTime() {
            return System.nanoTime();
        }

        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    };

    long nanoTime();

    long currentTimeMillis();
}
