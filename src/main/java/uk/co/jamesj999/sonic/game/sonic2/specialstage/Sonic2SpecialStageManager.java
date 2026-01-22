package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Palette;
import uk.co.jamesj999.sonic.level.Pattern;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.awt.TextRenderer;

import uk.co.jamesj999.sonic.graphics.GLCommand;

import java.awt.Color;
import java.awt.Font;
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
    private SpecialStageBackgroundRenderer bgRenderer;
    private int frameCounter = 0;

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
    private TextRenderer alignmentTextRenderer;
    private TextRenderer lagCompensationTextRenderer;

    // Current ring requirement for the active checkpoint (for "rings to go"
    // display)
    private int currentRingRequirement = 0;

    // Frame timing diagnostics
    private long lastFrameTime = 0;
    private int frameSampleCount = 0;
    private long frameSampleSum = 0;
    private static final int FRAME_SAMPLE_SIZE = 60;

    // Lag compensation diagnostics (wall-clock based)
    private long diagnosticWallStartTime = 0;
    private int diagnosticUpdateCount = 0;
    private int diagnosticTrackAdvances = 0;

    /**
     * Lag compensation factor to simulate original hardware lag frames.
     *
     * The original Mega Drive special stage VBlank handler (Vint_S2SS) does heavy
     * DMA transfers (~3500 bytes: palette, sprites, H-scroll, 1/4 plane table).
     * When this can't complete in one VBlank period, Vint_Lag runs instead,
     * which does NOT run the main game loop - effectively skipping that entire
     * frame.
     *
     * Our Java implementation runs at consistent 60fps with no lag, making the
     * entire simulation appear ~35% faster than the original hardware. This factor
     * compensates by skipping a proportional number of update frames entirely.
     *
     * Value of 0.35 means ~35% of frames are "lag frames" (entire update skipped):
     * - Original theoretical: 60 updates/sec
     * - Original with lag: ~39 effective updates/sec
     * - This affects track animation, player movement, object speed, everything
     */
    private double lagCompensation = 0.35;
    private double lagAccumulator = 0.0;

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

        // Pre-spawn segment 0 objects immediately at stage start
        // In the original game, objects are already in the pipeline when the stage
        // begins.
        // Without this, objects don't spawn until the first drawingIndex==4 transition,
        // causing them to appear too late.
        if (trackAnimator != null) {
            int segmentType = trackAnimator.getCurrentSegmentType();
            objectManager.processSegment(0, segmentType);
            LOGGER.fine("Pre-spawned segment 0 objects at initialization");
        }

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
            AudioManager.getInstance().fadeOutMusic();
            LOGGER.info("Music fade requested - fading special stage music");
        });

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
            AudioManager.getInstance().playSfx(GameSound.ERROR);
            LOGGER.info("Checkpoint FAILED: needed " + ringRequirement + ", had " + ringsCollected);
        } else {
            // Play checkpoint sound for success
            AudioManager.getInstance().playSfx(GameSound.CHECKPOINT);
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
        if (!checkpointRainbowPaletteActive || palettes == null || graphicsManager == null) {
            return;
        }

        // Only update every 8 frames (matches original: andi.b #7,d0; bne.s +)
        if ((frameCounter & 7) != 0) {
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
        Sonic2SpecialStageSpriteDebug debugSprites = Sonic2SpecialStageSpriteDebug.getInstance();
        debugSprites.setPlayerPatternBase(playerPatternBase);
        debugSprites.setHudPatternBase(hudPatternBase, hudPatterns.length);
        debugSprites.setStartPatternBase(startPatternBase, startPatterns.length);
        debugSprites.setMessagesPatternBase(messagesPatternBase, messagesPatterns.length);
    }

    private void setupRenderer() throws IOException {
        renderer = new Sonic2SpecialStageRenderer(graphicsManager);
        // Pattern bases are set in setupPatterns() after they have valid values

        // Initialize shader-based background renderer
        GL2 gl = graphicsManager.getGraphics();
        if (gl != null) {
            bgRenderer = new SpecialStageBackgroundRenderer();
            bgRenderer.init(gl);
            LOGGER.fine("Special Stage background renderer initialized with shader");
        } else {
            LOGGER.warning("GL context not available, background renderer not initialized");
        }

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
     *
     * Lag compensation: The original Mega Drive experiences lag frames during
     * the special stage due to heavy VBlank processing. When a lag frame occurs,
     * the entire game update is skipped. We simulate this by skipping a
     * proportional
     * number of update calls entirely.
     */
    public void update() {
        if (!initialized) {
            return;
        }

        if (alignmentTestMode) {
            updateAlignmentTest();
            return;
        }

        // Lag compensation: simulate original hardware lag frames
        // by skipping entire update frames proportionally
        lagAccumulator += lagCompensation;
        if (lagAccumulator >= 1.0) {
            lagAccumulator -= 1.0;
            // Still update lastFrameTime to avoid FPS diagnostic skew
            lastFrameTime = System.nanoTime();
            return; // Skip this entire frame (simulate lag)
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

        // Lag compensation diagnostics - track actual timing
        if (diagnosticWallStartTime == 0) {
            diagnosticWallStartTime = System.currentTimeMillis();
        }
        diagnosticUpdateCount++;

        // Increment drawing index, cycling based on current frame duration.
        // In ROM: drawing_index increments each VBlank, resets when >= frame_timer
        // (duration).
        // At speedFactor=12, duration=5, so drawing_index cycles 0-4.
        // At speedFactor=6, duration=10, so drawing_index cycles 0-9.
        // drawingIndex==4 is special: it's when $CCCC is used instead of $CCCD for
        // depth decrement.
        int duration = getAlignmentFrameDuration();
        drawingIndex = (drawingIndex + 1) % Math.max(1, duration);

        // Update skydome scroll based on current track animation state
        updateSkydomeScroll();

        // Update vertical scroll for parallax effects (bobbing, rise/drop)
        updateVScroll();

        // Update intro sequence
        if (intro != null) {
            intro.update();
        }

        // Track animation always runs (even during intro)
        boolean frameChanged = trackAnimator.update();

        // Track advances for diagnostic
        if (frameChanged) {
            diagnosticTrackAdvances++;
        }

        // Log diagnostic every 5 seconds
        long elapsedMs = System.currentTimeMillis() - diagnosticWallStartTime;
        if (elapsedMs >= 5000) {
            double seconds = elapsedMs / 1000.0;
            double updatesPerSec = diagnosticUpdateCount / seconds;
            double trackPerSec = diagnosticTrackAdvances / seconds;

            LOGGER.warning(String.format(
                    "DIAGNOSTIC: %.1f updates/sec (expect 60), %.1f track/sec (expect 12), " +
                            "speedFactor=%d, duration=%d",
                    updatesPerSec, trackPerSec,
                    trackAnimator.getSpeedFactor(),
                    getAlignmentFrameDuration()));

            // Reset counters
            diagnosticWallStartTime = System.currentTimeMillis();
            diagnosticUpdateCount = 0;
            diagnosticTrackAdvances = 0;
        }

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

        tryStartPendingCheckpoint();

        // Update checkpoint animation
        if (checkpoint != null && checkpoint.isActive()) {
            boolean checkpointStep = frameChanged;
            boolean checkpointComplete = checkpoint.update(checkpointStep);
            if (checkpointComplete) {
                handleCheckpointAnimationComplete();
            }
        }

        // Update rainbow palette cycling (runs every frame, but only changes every 8
        // frames)
        updateRainbowPaletteCycle();
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
     *
     * IMPORTANT: The drawing index (this.drawingIndex) cycles 0-4 every frame,
     * matching the ROM's SSTrack_drawing_index behavior. This is used for:
     * 1. Determining depth decrement value ($CCCC when index==4, $CCCD otherwise)
     * 2. Triggering segment spawning when index reaches 4
     */
    private void updateObjects() {
        if (objectManager == null || trackAnimator == null) {
            return;
        }

        // Use the class field drawingIndex which cycles 0-4 every frame (like ROM's
        // VBlank handler)
        // Note: this.drawingIndex is incremented in update() before this method is
        // called

        // Process new segment when drawing_index reaches 4 and segment changed
        if (this.drawingIndex == 4 && lastDrawingIndex != 4) {
            int segmentIndex = trackAnimator.getCurrentSegmentIndex();
            int segmentType = trackAnimator.getCurrentSegmentType();
            objectManager.processSegment(segmentIndex, segmentType);
        }
        lastDrawingIndex = this.drawingIndex;

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
     * - Rings CAN be collected
     * - Bombs CANNOT hit (player is invulnerable)
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
        lastFrameTime = System.nanoTime();
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
            alignmentDecodedTrackFrame = Sonic2TrackFrameDecoder.decodeFrame(frameData, false, runDiag);
            alignmentLastDecodedFrameIndex = frameIndex;
        }
    }

    /**
     * Renders the Special Stage.
     */
    public void draw() {
        if (!initialized || renderer == null) {
            return;
        }

        if (alignmentTestMode) {
            drawAlignmentTest();
            return;
        }

        boolean renderPlaneB = planeDebugMode.renderPlaneB();
        boolean renderPlaneA = planeDebugMode.renderPlaneA();

        if (renderPlaneB) {
            // Use shader-based background rendering if available
            if (bgRenderer != null && bgRenderer.isInitialized()) {
                // Capture current scroll values for use in lambda
                final int currentScrollX = skydomeScrollX;
                final float currentVScrollBG = (float) vScrollBG;

                // 1. Begin Tile Pass (Bind FBO) - queued as command for proper ordering
                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.beginTilePass(gl, H32_HEIGHT);
                }));

                // 2. Render background tiles to FBO
                graphicsManager.beginPatternBatch();
                renderer.renderBackgroundToFBO(combinedBackgroundMappings);
                graphicsManager.flushPatternBatch();

                // 3. End Tile Pass (Unbind FBO)
                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.endTilePass(gl);
                }));

                // 4. Update H-scroll and render with shader (vScrollBG applies vertical
                // parallax)
                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.setUniformHScroll(currentScrollX);
                    bgRenderer.renderWithShader(gl, currentVScrollBG);
                }));
            } else {
                // Fallback to CPU-based rendering
                renderer.renderBackground(combinedBackgroundMappings, skydomeScrollX, vScrollBG);
            }
        }

        if (renderPlaneA) {
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

    private void drawAlignmentTest() {
        boolean renderPlaneB = planeDebugMode.renderPlaneB();
        boolean renderPlaneA = planeDebugMode.renderPlaneA();

        if (renderPlaneB) {
            if (bgRenderer != null && bgRenderer.isInitialized()) {
                final int currentScrollX = skydomeScrollX;
                final float currentVScrollBG = (float) vScrollBG;

                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.beginTilePass(gl, H32_HEIGHT);
                }));

                graphicsManager.beginPatternBatch();
                renderer.renderBackgroundToFBO(combinedBackgroundMappings);
                graphicsManager.flushPatternBatch();

                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.endTilePass(gl);
                }));

                graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                    bgRenderer.setUniformHScroll(currentScrollX);
                    bgRenderer.renderWithShader(gl, currentVScrollBG);
                }));
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

    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        if (!alignmentTestMode) {
            return;
        }

        if (alignmentTextRenderer == null) {
            alignmentTextRenderer = new TextRenderer(new Font("SansSerif", Font.PLAIN, 12), true, true);
        }

        alignmentTextRenderer.beginRendering(viewportWidth, viewportHeight);

        int y = viewportHeight - 14;
        drawOutlined(alignmentTextRenderer, "SS ALIGNMENT TEST (F4 to exit)", 8, y, Color.WHITE);
        y -= 14;
        int gateIndexBase = 0;
        for (int i = 0; i < ANIM_STRAIGHT.length; i++) {
            if (ANIM_STRAIGHT[i] == CHECKPOINT_TRIGGER_FRAME) {
                gateIndexBase = i;
                break;
            }
        }
        int gateIndex = Math.floorMod(gateIndexBase + alignmentTriggerOffsetFrames, ANIM_STRAIGHT.length);
        drawOutlined(alignmentTextRenderer,
                "Gate offset (frames): " + alignmentTriggerOffsetFrames +
                        "  Gate frame: " + gateIndex + "/" + (ANIM_STRAIGHT.length - 1) +
                        "  Map: 0x" + String.format("%02X", ANIM_STRAIGHT[gateIndex]),
                8, y, Color.WHITE);
        y -= 14;
        drawOutlined(alignmentTextRenderer,
                String.format("Speed scale: %.2fx", alignmentRainbowSpeedScale),
                8, y, Color.WHITE);
        y -= 14;
        drawOutlined(alignmentTextRenderer,
                "Arrows: LEFT/RIGHT gate offset, UP/DOWN speed",
                8, y, Color.WHITE);
        y -= 14;
        drawOutlined(alignmentTextRenderer,
                "Step mode: " + (alignmentStepByTrackFrame ? "TRACK" : "VINT") +
                        "  TrackFrame: " + alignmentTrackFrameIndex +
                        "  DrawIdx: " + alignmentDrawingIndex,
                8, y, Color.WHITE);

        alignmentTextRenderer.endRendering();
    }

    private void drawOutlined(TextRenderer textRenderer, String text, int x, int y, Color color) {
        textRenderer.setColor(Color.BLACK);
        textRenderer.draw(text, x - 1, y);
        textRenderer.draw(text, x + 1, y);
        textRenderer.draw(text, x, y - 1);
        textRenderer.draw(text, x, y + 1);
        textRenderer.draw(text, x - 1, y - 1);
        textRenderer.draw(text, x + 1, y - 1);
        textRenderer.draw(text, x - 1, y + 1);
        textRenderer.draw(text, x + 1, y + 1);
        textRenderer.setColor(color);
        textRenderer.draw(text, x, y);
    }

    /**
     * Renders the lag compensation overlay showing current settings.
     * Displayed when not in alignment test mode.
     */
    public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        if (alignmentTestMode) {
            return;
        }

        if (lagCompensationTextRenderer == null) {
            lagCompensationTextRenderer = new TextRenderer(new Font("SansSerif", Font.PLAIN, 12), true, true);
        }

        lagCompensationTextRenderer.beginRendering(viewportWidth, viewportHeight);

        // Position at bottom-left of screen
        int y = 14;

        // Calculate effective updates per second: base 60 * (1 - lagComp)
        double effectiveUpdates = 60.0 * (1.0 - lagCompensation);

        drawOutlined(lagCompensationTextRenderer,
                String.format("Lag: %.0f%% (~%.0f upd/s)  F6/F7", lagCompensation * 100, effectiveUpdates),
                8, y, Color.YELLOW);

        lagCompensationTextRenderer.endRendering();
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

    /**
     * Gets the lag compensation factor.
     * 
     * @return Value between 0.0 and 0.5 representing proportion of frames skipped
     */
    public double getLagCompensation() {
        return lagCompensation;
    }

    /**
     * Sets the lag compensation factor.
     * Value of 0.35 means ~35% of frames are "lag frames" (entire update skipped).
     * Range: 0.0 (no compensation) to 0.5 (half the frames are lag).
     */
    public void setLagCompensation(double factor) {
        this.lagCompensation = Math.max(0.0, Math.min(0.5, factor));
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
        lagAccumulator = 0.0;

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

        // Shader-based background renderer cleanup
        if (bgRenderer != null) {
            GL2 gl = graphicsManager.getGraphics();
            if (gl != null) {
                bgRenderer.cleanup(gl);
            }
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
        Sonic2SpecialStageSpriteDebug.getInstance().setEnabled(spriteDebugMode);
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
    public uk.co.jamesj999.sonic.game.SpecialStageDebugProvider getDebugProvider() {
        if (!initialized) {
            return null;
        }
        return Sonic2SpecialStageSpriteDebug.getInstance();
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
        public void collectRing() {
            ringsCollected++;
        }

        /**
         * Loses rings from a bomb hit (BCD-style subtraction).
         * Returns the number of rings lost.
         */
        public int loseRingsFromBombHit() {
            if (ringsCollected == 0) {
                return 0;
            }

            int ringsLost;
            if (ringsCollected >= 10) {
                // Lose exactly 10 rings
                ringsLost = 10;
                ringsCollected -= 10;
            } else {
                // Lose all remaining rings
                ringsLost = ringsCollected;
                ringsCollected = 0;
            }

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

