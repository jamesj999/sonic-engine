package com.openggf.game.sonic2.credits;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.Sonic2PlayerArt;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.DynamicPatternBank;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.data.compression.EnigmaReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * ROM-accurate state machine for the Sonic 2 ending cutscene sequence.
 * <p>
 * Modeled 1:1 on the ROM's ObjCA (ending controller) 8 routines from s2.asm,
 * with ObjCC (tornado) as a 6-sub-state inner machine, plus ObjCB (clouds),
 * ObjCD (birds), ObjCE (jumping character), and ObjCF (plane helixes).
 * <p>
 * The cutscene plays after defeating the Death Egg Robot:
 * <ol>
 *   <li>Photo loop: 4 photos, each with white-to-target palette fade</li>
 *   <li>CHARACTER_APPEAR: character floats on cleared screen</li>
 *   <li>CAMERA_SCROLL: reveals sky background</li>
 *   <li>MAIN_ENDING: tornado approach, birds, rotation, departure, camera pan</li>
 *   <li>TRIGGER_CREDITS: terminal state</li>
 * </ol>
 * <p>
 * This is a self-contained renderer; it does not use the game's level/object system.
 * All rendering happens in {@link #draw()} using PatternDesc-based tile rendering.
 */
public class Sonic2EndingCutsceneManager {

    private static final Logger LOGGER = Logger.getLogger(Sonic2EndingCutsceneManager.class.getName());
    private final Sonic2EndingDynamicArtDecisionSink dynamicArtDecisionSink;

    public Sonic2EndingCutsceneManager() {
        this(Sonic2EndingDynamicArtDecisionSink.NONE);
    }

    Sonic2EndingCutsceneManager(
            Sonic2EndingDynamicArtDecisionSink dynamicArtDecisionSink) {
        this.dynamicArtDecisionSink = java.util.Objects.requireNonNull(
                dynamicArtDecisionSink, "dynamicArtDecisionSink");
    }

    // ========================================================================
    // Cutscene states — maps 1:1 to ObjCA routines 0/$2/$4/$6/$8/$A/$C/$E
    // ========================================================================

    /**
     * States of the ending cutscene, corresponding to ObjCA routine progression.
     * The photo loop (INIT through PHOTO_LOAD) cycles 4 times before advancing.
     */
    public enum CutsceneState {
        /** Initial wait used by Tails ending path before skipping photo loop. */
        TAILS_BOOT_WAIT,
        /** ObjCA routine 0: spawn palette changer, set timer. */
        INIT,
        /** ObjCA routine 2: wait for palette fade timer 1. */
        PALETTE_WAIT_1,
        /** ObjCA routine 4: spawn second palette changer. */
        PALETTE_SETUP_2,
        /** ObjCA routine 6: wait for palette fade timer 2. */
        PALETTE_WAIT_2,
        /** ObjCA routine 8: load next photo to VRAM; if photos remain loop, else advance. */
        PHOTO_LOAD,
        /** ObjCA routine $A: character floats/walks on cleared screen. */
        CHARACTER_APPEAR,
        /** ObjCA routine $C: DEZ scroll reveals sky background. */
        CAMERA_SCROLL,
        /** ObjCA routine $E: ObjCC manages tornado sequence (6 sub-states). */
        MAIN_ENDING,
        /** Credits_Trigger set — terminal state. */
        TRIGGER_CREDITS
    }

    /** ObjCC tornado sub-states during MAIN_ENDING. */
    private enum TornadoSubState {
        /** Sub-state 0: Tornado approaches from left using ObjB2 body only. */
        APPROACH,
        /** Sub-state 2: Birds spawn, character on tornado, clouds horizontal. */
        BIRDS_AND_HOLD,
        /** Sub-state 4: 28-step rotation with position/frame tables. */
        ROTATION,
        /** Sub-state 6: Character departure, spawn ObjCE/ObjCF. */
        DEPARTURE,
        /** Sub-state 8: Camera pan with 7 delta pairs. */
        CAMERA_PAN,
        /** Sub-state $A: Super Sonic final position sequence. */
        SUPER_FINAL
    }

    /** ObjCD bird behavior states. */
    private enum BirdState {
        FLY_RIGHT,
        HOMING,
        EXIT_LEFT
    }

    // ========================================================================
    // Screen dimensions
    // ========================================================================

    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /** ROM: Camera_BG_Y_pos initial value ($C8 = 200). Fixed for entire ending. */
    private static final int INITIAL_BG_Y_POS = 0xC8;

    // ========================================================================
    // Photo sequence constants
    // ========================================================================

    private static final int[] PHOTO_MAP_ADDRS = {
            Sonic2Constants.MAP_ENG_ENDING1_ADDR,
            Sonic2Constants.MAP_ENG_ENDING2_ADDR,
            Sonic2Constants.MAP_ENG_ENDING3_ADDR,
            Sonic2Constants.MAP_ENG_ENDING4_ADDR
    };
    private static final int PHOTO_COUNT = 4;
    private static final int PHOTO_WIDTH_TILES = 12;
    private static final int PHOTO_HEIGHT_TILES = 9;
    private static final int PHOTO_X = 112;
    private static final int PHOTO_Y = 64;
    private static final int ENDING_PLANE_WIDTH_TILES = 23;
    private static final int ENDING_PLANE_HEIGHT_TILES = 15;
    private static final int ENDING_PLANE_PLANE_X = 22 * 8; // planeLoc(64,22,33)
    private static final int ENDING_PLANE_PLANE_Y = 33 * 8; // planeLoc(64,22,33)

    // ========================================================================
    // State fields
    // ========================================================================

    private CutsceneState state = CutsceneState.INIT;
    private int frameCounter;
    private int stateFrameCounter;
    private boolean palTiming;

    // Art loader
    private Sonic2EndingArt endingArt;
    private Sonic2EndingArt.EndingRoutine routine;

    // Photo sequence state
    private int photoIndex;
    private int[][] photoMaps;
    private int[] endingPlaneMap;

    // Palette fade state — ROM starts Normal_palette as all-white ($EEE) and runs
    // PaletteFadeFrom every frame to fade toward Target_palette.
    private Palette[] displayPalettes;
    private boolean paletteFadeActive;
    // ROM ObjC9 fades only specific palette lines. Track which lines to fade.
    private int paletteFadeStartLine;
    private int paletteFadeEndLine;
    // ROM PaletteFadeTo increments Normal toward $EEE (white) for photo fade-out.
    private boolean paletteFadeToWhite;
    // ROM: ObjC9 uses fadeinTime delay between fade steps and fadeinAmount total steps.
    // Photo fades: fadeinTime=8, fadeinAmount=7 (8 steps total); char/bg: fadeinTime=4, fadeinAmount=7.
    private int paletteFadeDelay;
    private int paletteFadeDelayTimer;

    // Photos palette (Pal_AD1E) — loaded into Target line 0 during photo display.
    // ROM: ObjC9 subtype 4 loads Pal_AD1E into Target offset 0, length $F (line 0).
    private Palette photosPalette;

    // CHARACTER_APPEAR state — ROM spawns real Sonic/Tails object with Map_Sonic/Map_Tails
    // Float2 animation: SonAni_Float2 dc.b 7,$54,$55,$56,$57,$58,$FF
    //                    TailsAni_Float2 dc.b 9,$6E,$6F,$70,$71,$72,$FF
    // Walk animation (Super Sonic): SonAni_Walk dc.b $FF,0,1,2,3,4,5,$FF
    private static final int[] SONIC_FLOAT2_FRAMES = {0x54, 0x55, 0x56, 0x57, 0x58};
    private static final int SONIC_FLOAT2_SPEED = 8; // speed byte 7 + 1
    private static final int[] TAILS_FLOAT2_FRAMES = {0x6E, 0x6F, 0x70, 0x71, 0x72};
    private static final int TAILS_FLOAT2_SPEED = 10; // speed byte 9 + 1
    private static final int[] SONIC_WALK_FRAMES = {0, 1, 2, 3, 4, 5};
    private static final int SONIC_WALK_SPEED = 4; // Approximation for inertia=$1000

    // Pilot animation sequences (ROM: byte_A6A2 / byte_A6B6 in ObjB2_Animate_Pilot).
    // Frame values are character DPLC frame indices passed to LoadSonic/TailsDynPLC_Part2.
    // ROM: When main=Sonic → pilot=Tails; When main=Tails → pilot=Sonic.
    private static final int[] SONIC_PILOT_FRAMES = {0x2D, 0x2E, 0x2F, 0x30};
    private static final int[] TAILS_PILOT_FRAMES = {
            0x10, 0x10, 0x10, 0x10,   1, 2, 3, 2, 1, 1,
            0x10, 0x10, 0x10, 0x10,   1, 2, 3, 2, 1, 1,
            4, 4, 1, 1
    };
    private static final int PILOT_ANIM_DELAY = 8; // ROM: objoff_37 resets to 8

    // Player sprite rendering (for CHARACTER_APPEAR / CAMERA_SCROLL / BIRDS_AND_HOLD)
    private List<SpriteMappingFrame> playerMappingFrames;
    private int playerArtTile;
    private List<SpriteDplcFrame> playerDplcFrames;
    private DynamicPatternBank playerPatternBank;
    private Pattern[] playerSourceArt;
    private int lastPlayerDplcFrame = -1;
    private int[] charAnimFrames;
    private int charAnimSpeed;
    private int charAnimIndex;
    private int charAnimTimer;
    // charAppearPhase removed: both palettes now fade simultaneously from start

    // Pilot character rendering (ObjB2_Animate_Pilot)
    // ROM: The pilot is the OTHER character sitting in the Tornado cockpit.
    // Sonic as main → Tails pilots; Tails as main → Sonic pilots.
    // Uses standard Map_Sonic/Map_Tails (NOT Map_objB2_b which belongs to Obj5D).
    private List<SpriteMappingFrame> pilotMappingFrames; // Map_Sonic or Map_Tails
    private List<SpriteDplcFrame> pilotDplcFrames;       // Other character's DPLC table
    private DynamicPatternBank pilotPatternBank;
    private Pattern[] pilotSourceArt;
    private int pilotArtTile;
    private int lastPilotDplcFrame = -1;
    private int pilotAnimTimer;     // ROM: objoff_37, counts down from 8
    private int pilotAnimIndex;     // ROM: objoff_36, index into pilot frame sequence
    private int[] pilotAnimSequence; // Sonic pilot: {$2D,$2E,$2F,$30}; Tails pilot: 24-entry

    // CAMERA_SCROLL state
    private float scrollProgress;

    // Background vertical scroll tracking (ROM: Camera_BG_Y_pos)
    // Starts at $C8 (200) during CHARACTER_APPEAR, increments during CAMERA_SCROLL
    private int bgYPos;

    // MAIN_ENDING: ObjCC tornado state
    private TornadoSubState tornadoSubState;
    private int tornadoTimer;
    private int tornadoXSub;
    private int tornadoYSub;
    private int tornadoXVel;
    private int tornadoYVel;
    private boolean cutSceneFlag;
    private int objCcSpawnTimer;
    private boolean objCcActive;

    // Tornado rotation state (Sub-state 4)
    private int rotationStep;
    private int rotationFrameTimer;
    private int rotationDisplayFrame; // ObjCF frame to render (set before step increment)

    // Departure state (Sub-state 6)
    private int departureTimer;
    private int superDepartureStep;
    private int superDepartureTick;

    // Camera pan state (Sub-state 8)
    private int cameraPanStep;
    private int cameraPanFrameTimer;
    private int horizScrollOffset;
    private int vscrollOffset;
    private boolean standingFlag;

    // Super final state (Sub-state $A)
    private int superFinalStep;

    // Character on tornado position
    private int charOnTornadoX;
    private int charOnTornadoY;

    // ObjCE jumping character
    private boolean objCeActive;
    private int objCeX;
    private int objCeY;
    private int objCeSavedX;
    private int objCeSavedY;
    private int objCeFrame;
    private int objCeTimer;
    private int objCePhase;
    private int objCeJumpStep;

    // ObjCF plane helixes
    private boolean objCfHelixActive;
    private int objCfHelixX;
    private int objCfHelixY;
    private int objCfHelixSavedX;
    private int objCfHelixSavedY;
    private int objCfHelixFrame;
    private int objCfHelixAnimTimer;

    // ObjCD birds
    private final List<Bird> birds = new ArrayList<>();
    private int birdSpawnCounter;
    private int birdSpawnDelay;
    private boolean birdsSpawning;

    // ObjCB clouds
    private final List<Cloud> clouds = new ArrayList<>();
    private int cloudSpawnTimer;

    // ROM-parsed sprite mappings
    private List<SpriteMappingFrame> objCfFrames;
    private List<SpriteMappingFrame> animalFrames;
    private List<SpriteMappingFrame> tornadoFrames;
    private List<SpriteMappingFrame> cloudFrames;

    private final PatternDesc reusableDesc = new PatternDesc();

    /**
     * Mega Drive color levels (0-7). Each MD color component uses values 0,$2,$4,$6,$8,$A,$C,$E
     * which map to these 0-255 scaled values via: {@code (level * 255 + 3) / 7}.
     */
    private static final int[] MD_COLOR_LEVELS = {0, 36, 73, 109, 146, 182, 219, 255};

    // ========================================================================
    // Inner data classes for entity tracking
    // ========================================================================

    private static class Bird {
        int xSub, ySub;
        int xVel, yVel;
        int initialYVel;
        int targetY;
        BirdState birdState;
        int stateTimer;
        int animFrame;
        int animTimer;

        Bird(int xSub, int ySub, int xVel, int yVel, int targetY) {
            this.xSub = xSub;
            this.ySub = ySub;
            this.xVel = xVel;
            this.yVel = yVel;
            this.initialYVel = yVel;
            this.targetY = targetY;
            this.birdState = BirdState.FLY_RIGHT;
        }
    }

    private static class Cloud {
        int xSub, ySub;
        int xVel, yVel;
        int frame;
        boolean horizontal;

        Cloud(int xSub, int ySub, int xVel, int yVel, int frame, boolean horizontal) {
            this.xSub = xSub;
            this.ySub = ySub;
            this.xVel = xVel;
            this.yVel = yVel;
            this.frame = frame;
            this.horizontal = horizontal;
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    /**
     * Initializes the ending cutscene: loads art, palettes, plays music, and
     * starts the photo loop.
     *
     * @param rom the ROM to load ending art from
     */
    public void initialize(Rom rom) {
        routine = Sonic2EndingArt.determineEndingRoutine();
        palTiming = isPalTiming();
        endingArt = new Sonic2EndingArt();
        endingArt.loadArt(rom, routine);
        endingArt.loadPalettes(rom, routine);
        endingArt.cacheToGpu();

        // ROM: Normal_palette filled with $0EEE0EEE (all white) at EndingSequence init
        initDisplayPalettes();

        // Load Photos palette (Pal_AD1E, 32 bytes) for ObjC9 subtype 4 simulation.
        // ROM: ObjCA_Init spawns ObjC9 subtype 4 which copies Pal_AD1E into
        // Target_palette offset 0 (line 0), length $F. Photos use palette line 0.
        try {
            byte[] photosPalData = rom.readBytes(Sonic2Constants.PAL_ENDING_PHOTOS_ADDR, 32);
            photosPalette = new Palette();
            photosPalette.fromSegaFormat(photosPalData);
        } catch (Exception e) {
            LOGGER.warning("Failed to load photos palette: " + e.getMessage());
        }

        // Decode all 4 photo Enigma maps
        photoMaps = new int[PHOTO_COUNT][];
        for (int i = 0; i < PHOTO_COUNT; i++) {
            photoMaps[i] = loadEnigmaMap(rom, PHOTO_MAP_ADDRS[i],
                    Sonic2Constants.ART_TILE_ENDING_PICS, "EndingPhoto" + (i + 1));
        }
        int endingPlaneMapAddr = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                ? Sonic2Constants.MAP_ENG_ENDING_SONIC_PLANE_ADDR
                : Sonic2Constants.MAP_ENG_ENDING_TAILS_PLANE_ADDR;
        endingPlaneMap = loadEnigmaMap(
                rom,
                endingPlaneMapAddr,
                Sonic2Constants.ART_TILE_ENDING_FINAL_TORNADO,
                "EndingPlaneBackdrop");

        // Parse ROM sprite mappings for accurate cutscene rendering
        try {
            RomByteReader reader = RomByteReader.fromRom(rom);
            objCfFrames = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJCF_ADDR);
            animalFrames = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJ28_A_ADDR);
            tornadoFrames = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_OBJB2_A_ADDR);
            cloudFrames = S2SpriteDataLoader.loadMappingFrames(reader, Sonic2Constants.MAP_UNC_CLOUD_ADDR);

            // Load player mappings for CHARACTER_APPEAR rendering.
            // ROM: ObjCA routine $A spawns real Sonic/Tails object using standard Map_Sonic/Map_Tails.
            int playerMapAddr = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                    ? Sonic2Constants.MAP_UNC_TAILS_ADDR
                    : Sonic2Constants.MAP_UNC_SONIC_ADDR;
            playerMappingFrames = S2SpriteDataLoader.loadMappingFrames(reader, playerMapAddr);
            playerArtTile = endingArt.getPlayerArtTile();

            // Load player DPLCs for per-frame art loading.
            // MapUnc_Sonic mapping pieces reference DPLC-relative tile indices,
            // not absolute offsets into the full ArtUnc_Sonic art.
            int playerDplcAddr = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                    ? Sonic2Constants.MAP_R_UNC_TAILS_ADDR
                    : Sonic2Constants.MAP_R_UNC_SONIC_ADDR;
            playerDplcFrames = Sonic2PlayerArt.parseDplcFrames(reader, playerDplcAddr);

            // Create DynamicPatternBank for DPLC-driven player art rendering
            playerSourceArt = endingArt.getPlayerPatterns();
            int bankSize = Sonic2PlayerArt.resolveBankSize(playerDplcFrames, playerMappingFrames);
            playerPatternBank = new DynamicPatternBank(
                    Sonic2EndingArt.PATTERN_BASE_VRAM + playerArtTile, bankSize);

            // Load pilot mappings and DPLC (opposite character for cockpit overlay).
            // ROM: ObjB2_Animate_Pilot loads DPLC for the other character's animation frames,
            // then the real player character object renders using standard Map_Sonic/Map_Tails.
            // Map_objB2_b is NOT the pilot mapping — it belongs to Obj5D.
            int pilotMapAddr = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                    ? Sonic2Constants.MAP_UNC_SONIC_ADDR    // pilot=Sonic when main=Tails
                    : Sonic2Constants.MAP_UNC_TAILS_ADDR;   // pilot=Tails when main=Sonic
            pilotMappingFrames = S2SpriteDataLoader.loadMappingFrames(reader, pilotMapAddr);
            pilotArtTile = endingArt.getPilotArtTile();
            int pilotDplcAddr = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                    ? Sonic2Constants.MAP_R_UNC_SONIC_ADDR   // pilot=Sonic when main=Tails
                    : Sonic2Constants.MAP_R_UNC_TAILS_ADDR;  // pilot=Tails when main=Sonic
            pilotDplcFrames = Sonic2PlayerArt.parseDplcFrames(reader, pilotDplcAddr);
            pilotSourceArt = endingArt.getPilotPatterns();
            int pilotBankSize = Sonic2PlayerArt.resolveBankSize(pilotDplcFrames, pilotMappingFrames);
            pilotPatternBank = new DynamicPatternBank(
                    Sonic2EndingArt.PATTERN_BASE_VRAM + pilotArtTile, pilotBankSize);

            // Set pilot animation sequence based on which character is the pilot
            pilotAnimSequence = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                    ? SONIC_PILOT_FRAMES   // pilot=Sonic
                    : TAILS_PILOT_FRAMES;  // pilot=Tails
            // ObjB2's freshly allocated object RAM leaves objoff_37 at zero.
            // The first main update decrements it to $FF and immediately runs
            // the Part2 DPLC decision; only later expiries reset it to eight.
            pilotAnimTimer = 0;
            pilotAnimIndex = 0;

            LOGGER.info("Parsed sprite mappings: ObjCF=" + objCfFrames.size()
                    + " Obj28_a=" + animalFrames.size()
                    + " ObjB2_a=" + tornadoFrames.size()
                    + " PilotMap=" + pilotMappingFrames.size()
                    + " ObjB3=" + cloudFrames.size()
                    + " Player=" + playerMappingFrames.size()
                    + " PlayerDPLC=" + playerDplcFrames.size()
                    + " PilotDPLC=" + pilotDplcFrames.size());
            if (playerMappingFrames.size() != playerDplcFrames.size()) {
                LOGGER.warning("Player mapping/DPLC frame count MISMATCH: "
                        + playerMappingFrames.size() + " mappings vs "
                        + playerDplcFrames.size() + " DPLCs — DPLC may fail for out-of-range frames");
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to parse sprite mappings: " + e.getMessage());
        }

        // Play ending music
        GameServices.audio().playMusic(Sonic2AudioConstants.MUS_ENDING);

        // Start photo loop at photo 0
        photoIndex = 0;
        frameCounter = 0;
        stateFrameCounter = 0;
        if (routine == Sonic2EndingArt.EndingRoutine.TAILS) {
            // ROM tails ending path bypasses the photo loop and waits #$100
            // before entering character appear setup.
            state = CutsceneState.TAILS_BOOT_WAIT;
        } else {
            enterInit();
        }

        LOGGER.info("Ending cutscene initialized with routine=" + routine);
    }

    // ========================================================================
    // Update
    // ========================================================================

    /**
     * Per-frame update. Advances the cutscene state machine.
     */
    public void update() {
        frameCounter++;
        stateFrameCounter++;

        // ROM: PaletteFadeFrom runs every frame in the ending main loop
        if (paletteFadeActive) {
            runPaletteFadeStep();
        }

        switch (state) {
            case TAILS_BOOT_WAIT -> updateTailsBootWait();
            case INIT -> { /* immediate transition done in enterInit() */ }
            case PALETTE_WAIT_1 -> updatePaletteWait1();
            case PALETTE_SETUP_2 -> updatePaletteSetup2();
            case PALETTE_WAIT_2 -> updatePaletteWait2();
            case PHOTO_LOAD -> updatePhotoLoad();
            case CHARACTER_APPEAR -> updateCharacterAppear();
            case CAMERA_SCROLL -> updateCameraScroll();
            case MAIN_ENDING -> updateMainEnding();
            case TRIGGER_CREDITS -> { /* terminal */ }
        }
    }

    // ========================================================================
    // Draw
    // ========================================================================

    /**
     * Renders the current cutscene state.
     */
    public void draw() {
        if (endingArt == null || !endingArt.isInitialized()) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        switch (state) {
            case INIT, PALETTE_WAIT_1, PALETTE_SETUP_2, PALETTE_WAIT_2, PHOTO_LOAD ->
                    drawPhotoSequence(gm);
            case TAILS_BOOT_WAIT -> { }
            case CHARACTER_APPEAR -> drawCharacterAppear(gm);
            case CAMERA_SCROLL -> drawCameraScroll(gm);
            case MAIN_ENDING -> drawMainEnding(gm);
            default -> { }
        }
    }

    // ========================================================================
    // Public accessors
    // ========================================================================

    /** Returns whether the cutscene is complete and credits should begin. */
    public boolean isDone() {
        return state == CutsceneState.TRIGGER_CREDITS;
    }

    /** Returns the current cutscene state (for debugging/testing). */
    public CutsceneState getState() {
        return state;
    }

    /**
     * Returns whether the cutscene is in a sky phase where the background should
     * show sky colors. ROM: VDP register $8720 = palette 2, color 0.
     * Both character and background palettes fade simultaneously from the start
     * of CHARACTER_APPEAR, so the sky is visible immediately.
     */
    public boolean isSkyPhase() {
        return state == CutsceneState.CHARACTER_APPEAR
                || state == CutsceneState.CAMERA_SCROLL
                || state == CutsceneState.MAIN_ENDING;
    }

    /**
     * Returns whether the level background (DEZ star field) should be rendered
     * behind the cutscene sprites. Active during CHARACTER_APPEAR, CAMERA_SCROLL,
     * and MAIN_ENDING. Background palette fades in from the start of
     * CHARACTER_APPEAR.
     */
    public boolean needsLevelBackground() {
        return state == CutsceneState.CHARACTER_APPEAR
                || state == CutsceneState.CAMERA_SCROLL
                || state == CutsceneState.MAIN_ENDING;
    }

    /**
     * Returns the current background vertical scroll value (ROM: Vscroll_Factor_BG).
     * Starts at $C8 during CHARACTER_APPEAR, increments during CAMERA_SCROLL.
     */
    public int getBackgroundVscroll() {
        return bgYPos;
    }

    /**
     * Returns the backdrop color override for the BG shader during the ending.
     * <p>
     * The BG shader normally reads the level's stored palette for its backdrop,
     * but during the ending the cutscene fades display palettes from white to
     * target independently. Without this override, the star field backdrop
     * stays at full color while the rest of the scene fades.
     *
     * @return {r, g, b} in [0..1] from display palette line 2 color 0, or null
     */
    public float[] getBackdropColorOverride() {
        if (displayPalettes != null && displayPalettes.length > 2 && displayPalettes[2] != null) {
            Palette.Color c = displayPalettes[2].getColor(0);
            return new float[]{c.rFloat(), c.gFloat(), c.bFloat()};
        }
        return null;
    }

    /**
     * Sets the OpenGL clear color for the ending cutscene.
     * Uses palette line 2 color 0 from display palettes (starts white, fades to target).
     * ROM reference: VDP register $8720 = palette 2, color 0.
     */
    public void setClearColor() {
        // Prefer display palettes (starts white, fades toward target)
        if (displayPalettes != null && displayPalettes.length > 2 && displayPalettes[2] != null) {
            Palette.Color bgColor = displayPalettes[2].getColor(0);
            org.lwjgl.opengl.GL11.glClearColor(bgColor.rFloat(), bgColor.gFloat(), bgColor.bFloat(), 1.0f);
            return;
        }
        // Fallback to target palettes
        if (endingArt != null && endingArt.getEndingPalettes() != null) {
            Palette[] palettes = endingArt.getEndingPalettes();
            if (palettes.length > 2 && palettes[2] != null) {
                Palette.Color bgColor = palettes[2].getColor(0);
                org.lwjgl.opengl.GL11.glClearColor(bgColor.rFloat(), bgColor.gFloat(), bgColor.bFloat(), 1.0f);
                return;
            }
        }
        org.lwjgl.opengl.GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    // ========================================================================
    // Photo loop: INIT -> PALETTE_WAIT_1 -> PALETTE_SETUP_2 -> PALETTE_WAIT_2
    //             -> PHOTO_LOAD (cycles 4 times, then CHARACTER_APPEAR)
    // ========================================================================

    /**
     * Enters the INIT state for the current photo.
     * ROM reference: ObjCA routine 0 (ObjCA_Init) spawns ObjC9 subtype 4
     * which calls PaletteFadeFrom to fade Normal_palette toward Target_palette.
     * Normal_palette is only filled with $0EEE once at EndingSequence start,
     * NOT reset per photo.
     */
    private void enterInit() {
        state = CutsceneState.INIT;
        stateFrameCounter = 0;

        // ROM: ObjC9 subtype 4 copies Pal_AD1E (Photos palette) into Target line 0.
        // Photos use palette line 0, so this sets the colors for photo display.
        // The fade-down (loc_1344C) then decrements Normal line 0 toward Target line 0.
        // Lines 1-3 of Normal are NOT faded by this ObjC9, so they stay white.
        copyPhotosPaletteToTargetLine0();

        // Enable palette fade for line 0 ONLY (ROM ObjC9 subtype 4: offset 0, length $F)
        // PaletteFadeFrom: decrement Normal toward Target (white → photo colors)
        // ROM C9PalInfo subtype 4: fadeinTime=8, fadeinAmount=7 (8 steps, 1 per 8 frames)
        paletteFadeActive = true;
        paletteFadeToWhite = false;
        paletteFadeStartLine = 0;
        paletteFadeEndLine = 0;
        paletteFadeDelay = 8;
        paletteFadeDelayTimer = 8;

        // Immediately transition to PALETTE_WAIT_1 (ROM: routine 0 → routine 2)
        state = CutsceneState.PALETTE_WAIT_1;
        stateFrameCounter = 0;
    }

    private void updatePaletteWait1() {
        int duration = palTiming
                ? Sonic2CreditsData.PALETTE_WAIT_1_50FPS
                : Sonic2CreditsData.PALETTE_WAIT_1_60FPS;
        if (stateFrameCounter >= duration) {
            state = CutsceneState.PALETTE_SETUP_2;
            stateFrameCounter = 0;
        }
    }

    private void updateTailsBootWait() {
        if (stateFrameCounter >= Sonic2CreditsData.TAILS_BOOT_WAIT) {
            enterCharacterAppear();
        }
    }

    private void updatePaletteSetup2() {
        // ROM: ObjCA routine 4 spawns ObjC9 subtype 6 which runs PaletteFadeTo
        // (loc_1348A). PaletteFadeTo increments Normal toward $EEE (white),
        // fading the photo out to white before the next photo loads.
        // ROM C9PalInfo subtype 6: fadeinTime=8, fadeinAmount=7 (8 steps, 1 per 8 frames)
        paletteFadeActive = true;
        paletteFadeToWhite = true;
        paletteFadeStartLine = 0;
        paletteFadeEndLine = 0;
        paletteFadeDelay = 8;
        paletteFadeDelayTimer = 8;

        state = CutsceneState.PALETTE_WAIT_2;
        stateFrameCounter = 0;
    }

    private void updatePaletteWait2() {
        if (stateFrameCounter >= Sonic2CreditsData.PALETTE_WAIT_2) {
            state = CutsceneState.PHOTO_LOAD;
            stateFrameCounter = 0;
        }
    }

    private void updatePhotoLoad() {
        // Current photo displayed; advance to next
        photoIndex++;

        if (photoIndex < PHOTO_COUNT) {
            // Loop back for next photo with fresh white fade
            enterInit();
        } else {
            // All 4 photos done -> CHARACTER_APPEAR
            enterCharacterAppear();
        }
    }

    /**
     * Draws the current photo using Enigma-decoded tile data.
     * Photos are 12x9 tile grids rendered at screen position (112, 64).
     */
    private void drawPhotoSequence(GraphicsManager gm) {
        if (photoIndex < 0 || photoIndex >= PHOTO_COUNT) {
            return;
        }
        int[] map = photoMaps[photoIndex];
        if (map == null || map.length == 0) {
            return;
        }

        gm.beginPatternBatch();

        for (int ty = 0; ty < PHOTO_HEIGHT_TILES; ty++) {
            for (int tx = 0; tx < PHOTO_WIDTH_TILES; tx++) {
                int idx = ty * PHOTO_WIDTH_TILES + tx;
                if (idx >= map.length) continue;
                int word = map[idx];
                if (word == 0) continue;
                reusableDesc.set(word);
                int patternId = Sonic2EndingArt.PATTERN_BASE_PICS
                        + reusableDesc.getPatternIndex() - Sonic2Constants.ART_TILE_ENDING_PICS;
                gm.renderPatternWithId(patternId, reusableDesc,
                        PHOTO_X + tx * 8, PHOTO_Y + ty * 8);
            }
        }

        gm.flushPatternBatch();
    }

    // ========================================================================
    // CHARACTER_APPEAR (ObjCA routine $A)
    // ========================================================================

    private void enterCharacterAppear() {
        state = CutsceneState.CHARACTER_APPEAR;
        stateFrameCounter = 0;

        // Character at X=$A0 (160), Y=$50 (80) using Map_Sonic/Map_Tails
        // ROM: SonAni_Float2 for Sonic/Tails, SonAni_Walk for Super Sonic
        switch (routine) {
            case SONIC -> {
                charAnimFrames = SONIC_FLOAT2_FRAMES;
                charAnimSpeed = SONIC_FLOAT2_SPEED;
            }
            case SUPER_SONIC -> {
                charAnimFrames = SONIC_WALK_FRAMES;
                charAnimSpeed = SONIC_WALK_SPEED;
            }
            case TAILS -> {
                charAnimFrames = TAILS_FLOAT2_FRAMES;
                charAnimSpeed = TAILS_FLOAT2_SPEED;
            }
        }
        charAnimIndex = 0;
        charAnimTimer = 0;
        publishNormalPlayerDecision();

        // ROM: EndingSequence sets Camera_BG_Y_pos = $C8
        bgYPos = INITIAL_BG_Y_POS;

        // ROM: ObjCA routine $A spawns both character and background ObjC9 faders
        // simultaneously. Load both palettes and start fading them together.
        loadCharacterPaletteToTarget();
        loadBackgroundPaletteToTarget();
        // ROM C9PalInfo: fadeinTime=4, fadeinAmount=7 (both char and bg)
        paletteFadeActive = true;
        paletteFadeStartLine = 0;
        paletteFadeEndLine = 3;
        paletteFadeDelay = 4;
        paletteFadeDelayTimer = 4;

        LOGGER.fine("Cutscene: entering CHARACTER_APPEAR");
    }

    private void updateCharacterAppear() {
        // Animate player sprite using Float2 (Sonic/Tails) or Walk (Super Sonic) frames
        charAnimTimer++;
        if (charAnimTimer >= charAnimSpeed) {
            charAnimTimer = 0;
            charAnimIndex = (charAnimIndex + 1) % charAnimFrames.length;
        }
        publishNormalPlayerDecision();

        // ROM: Camera_BG_Y_pos stays fixed at $C8 during CHARACTER_APPEAR.
        // The "falling" visual comes from Camera_Y_pos_diff=$100 through SwScrl_DEZ parallax,
        // not from incrementing Camera_BG_Y_pos.

        if (stateFrameCounter >= Sonic2CreditsData.CHARACTER_APPEAR_DURATION) {
            enterCameraScroll();
        }
    }

    private void drawCharacterAppear(GraphicsManager gm) {
        gm.beginPatternBatch();
        int frameIdx = charAnimFrames[charAnimIndex];
        drawPlayerFrame(gm, frameIdx, 0xA0, 0x50);
        gm.flushPatternBatch();
    }

    // ========================================================================
    // CAMERA_SCROLL (ObjCA routine $C)
    // ========================================================================

    private void enterCameraScroll() {
        state = CutsceneState.CAMERA_SCROLL;
        stateFrameCounter = 0;
        scrollProgress = 0.0f;

        LOGGER.fine("Cutscene: entering CAMERA_SCROLL");
    }

    private void updateCameraScroll() {
        // ROM: SetHorizVertiScrollFlagsBG adds Camera_Y_pos_diff << 8 to Camera_BG_Y_pos
        // With Camera_Y_pos_diff=$100, this increments ~1px/frame (256 subpixels in 8.8 fixed-point)
        bgYPos++;

        // Character continues animating during camera scroll
        charAnimTimer++;
        if (charAnimTimer >= charAnimSpeed) {
            charAnimTimer = 0;
            charAnimIndex = (charAnimIndex + 1) % charAnimFrames.length;
        }
        publishNormalPlayerDecision();

        // Spawn vertical clouds during camera scroll for falling sensation
        spawnVerticalCloudIfNeeded();
        updateClouds();

        int duration = Sonic2CreditsData.CAMERA_SCROLL_DURATION;

        scrollProgress = Math.min(1.0f, (float) stateFrameCounter / duration);

        if (stateFrameCounter >= duration) {
            enterMainEnding();
        }
    }

    private void drawCameraScroll(GraphicsManager gm) {
        // Background transitions from white to sky via palette fade (setClearColor)
        // Character still visible floating at center, clouds drifting upward
        gm.beginPatternBatch();
        drawClouds(gm);
        int frameIdx = charAnimFrames[charAnimIndex];
        drawPlayerFrame(gm, frameIdx, 0xA0, 0x50);
        gm.flushPatternBatch();
    }

    // ========================================================================
    // MAIN_ENDING (ObjCA routine $E) — ObjCC tornado sub-state machine
    // ========================================================================

    private void enterMainEnding() {
        state = CutsceneState.MAIN_ENDING;
        stateFrameCounter = 0;
        tornadoSubState = TornadoSubState.APPROACH;
        objCcActive = false;
        objCcSpawnTimer = getObjCcSpawnDelay();
        cutSceneFlag = false;

        // Reset scroll offsets
        horizScrollOffset = 0;
        vscrollOffset = 0;
        standingFlag = false;

        // Reset entity lists
        birds.clear();
        clouds.clear();
        birdSpawnCounter = 0;
        birdSpawnDelay = 0;
        birdsSpawning = false;
        cloudSpawnTimer = 0;

        // Reset ObjCE/ObjCF
        objCeActive = false;
        objCfHelixActive = false;

        LOGGER.fine("Cutscene: entering MAIN_ENDING");
    }

    private void updateMainEnding() {
        if (!objCcActive) {
            // ROM ObjCA routine $E waits before spawning ObjCC.
            charAnimTimer++;
            if (charAnimTimer >= charAnimSpeed) {
                charAnimTimer = 0;
                charAnimIndex = (charAnimIndex + 1) % charAnimFrames.length;
            }
            publishNormalPlayerDecision();
            spawnCloudIfNeeded();
            objCcSpawnTimer--;
            if (objCcSpawnTimer <= 0) {
                startObjCc();
            }
        } else {
            // Update tornado sub-state machine
            switch (tornadoSubState) {
                case APPROACH -> updateTornadoApproach();
                case BIRDS_AND_HOLD -> updateBirdsAndHold();
                case ROTATION -> updateRotation();
                case DEPARTURE -> updateDeparture();
                case CAMERA_PAN -> updateCameraPan();
                case SUPER_FINAL -> updateSuperFinal();
            }
        }

        // Update all active entities
        if (objCcActive && (tornadoSubState == TornadoSubState.APPROACH
                || tornadoSubState == TornadoSubState.BIRDS_AND_HOLD)) {
            updatePilotAnimation();
        }
        updateBirds();
        updateClouds();
        updateObjCe();
        updateObjCfHelix();

        // Check global credits trigger
        int creditsTrigger = palTiming
                ? Sonic2CreditsData.CREDITS_TRIGGER_50FPS
                : Sonic2CreditsData.CREDITS_TRIGGER_60FPS;
        if (frameCounter >= creditsTrigger) {
            state = CutsceneState.TRIGGER_CREDITS;
            LOGGER.info("Ending cutscene complete, triggering credits");
        }
    }

    private int getObjCcSpawnDelay() {
        if (routine == Sonic2EndingArt.EndingRoutine.TAILS) {
            return palTiming
                    ? Sonic2CreditsData.OBJCC_SPAWN_DELAY_TAILS_50FPS
                    : Sonic2CreditsData.OBJCC_SPAWN_DELAY_TAILS_60FPS;
        }
        return Sonic2CreditsData.OBJCC_SPAWN_DELAY;
    }

    private void startObjCc() {
        objCcActive = true;

        // ObjCC init: tornado starts off-screen left
        tornadoSubState = TornadoSubState.APPROACH;
        tornadoXSub = -0x10 << 8;
        tornadoYSub = 0xC0 << 8;
        tornadoXVel = Sonic2CreditsData.PLANE_X_SPEED;
        tornadoYVel = Sonic2CreditsData.PLANE_Y_SPEED;
        tornadoTimer = 0;
        cutSceneFlag = false;
    }

    // --- ObjCC Sub-state 0: Tornado Approach ---

    private void updateTornadoApproach() {
        // Continue animating the player's Float2/Walk animation during approach
        charAnimTimer++;
        if (charAnimTimer >= charAnimSpeed) {
            charAnimTimer = 0;
            charAnimIndex = (charAnimIndex + 1) % charAnimFrames.length;
        }

        // ROM: ObjCA routine $E calls loc_AB9C (cloud spawner) every frame.
        // Clouds spawn from the start of MAIN_ENDING, not just during BIRDS_AND_HOLD.
        spawnCloudIfNeeded();

        tornadoXSub += tornadoXVel;
        tornadoYSub += tornadoYVel;

        if ((tornadoXSub >> 8) >= Sonic2CreditsData.PLANE_TARGET_X) {
            // Snap to target, clear velocities
            tornadoXSub = Sonic2CreditsData.PLANE_TARGET_X << 8;
            tornadoXVel = 0;
            tornadoYVel = 0;

            // Set hold timer and CutScene flag
            tornadoTimer = palTiming
                    ? Sonic2CreditsData.PLANE_HOLD_FRAMES_50FPS
                    : Sonic2CreditsData.PLANE_HOLD_FRAMES_60FPS;
            cutSceneFlag = true;

            // Position character on tornado
            int tornadoY = tornadoYSub >> 8;
            charOnTornadoX = tornadoXSub >> 8;
            charOnTornadoY = tornadoY - (routine == Sonic2EndingArt.EndingRoutine.TAILS ? 0x18 : 0x1C);

            // Begin bird spawning
            birdsSpawning = true;
            birdSpawnCounter = Sonic2CreditsData.BIRD_SPAWN_COUNT;
            birdSpawnDelay = 0;

            // The real player switches from Float2 to Wait. This direct
            // runtime decision replaces the renderer-owned reload.
            publishNormalPlayerDecision(1);

            tornadoSubState = TornadoSubState.BIRDS_AND_HOLD;
            LOGGER.fine("Tornado arrived, entering BIRDS_AND_HOLD");
        } else {
            publishNormalPlayerDecision();
        }
    }

    // --- ObjCC Sub-state 2: Birds + Character on Tornado ---

    private void updateBirdsAndHold() {
        // Spawn birds at random intervals
        if (birdsSpawning && birdSpawnCounter > 0) {
            birdSpawnDelay--;
            if (birdSpawnDelay <= 0) {
                int nextDelay = Sonic2Rng.currentEndingSeedDelay(GameServices.rng(), 0x0F);
                spawnBird();
                birdSpawnCounter--;
                birdSpawnDelay = nextDelay;
            }
        }

        // Spawn clouds continuously while CutScene flag set (horizontal mode)
        if (cutSceneFlag) {
            spawnCloudIfNeeded();
        }

        // Super Sonic drifts: addi.l #$8000,x_pos; addq.w #1,y_pos
        if (routine == Sonic2EndingArt.EndingRoutine.SUPER_SONIC) {
            if (charOnTornadoX < 0xC0) {
                charOnTornadoX++;
            }
            if (charOnTornadoY < 0x90) {
                charOnTornadoY++;
            }
        }

        tornadoTimer--;
        if (tornadoTimer <= 0) {
            // Switch ObjCC to ObjCF mappings; adjust position
            // ROM: subi.w #$14,x_pos(a0); addi.w #$14,y_pos(a0)
            tornadoXSub -= 0x14 << 8;
            tornadoYSub += 0x14 << 8;

            rotationStep = 0;
            rotationFrameTimer = Sonic2CreditsData.ROTATION_FRAME_DELAY;
            // ROM: mapping_frame set at transition (7=Sonic, 0=Super, $18=Tails)
            rotationDisplayFrame = switch (routine) {
                case SONIC -> Sonic2CreditsData.TORNADO_FRAMES_SONIC[0];
                case SUPER_SONIC -> Sonic2CreditsData.TORNADO_FRAMES_SUPER[0];
                case TAILS -> Sonic2CreditsData.TORNADO_FRAMES_TAILS[0];
            };
            tornadoSubState = TornadoSubState.ROTATION;
            LOGGER.fine("Entering ROTATION");
        }
    }

    // --- ObjCC Sub-state 4: Tornado Rotation ---

    private void updateRotation() {
        rotationFrameTimer--;
        if (rotationFrameTimer <= 0) {
            rotationFrameTimer = Sonic2CreditsData.ROTATION_FRAME_DELAY;

            // ROM: read step index BEFORE incrementing (frame/position use pre-increment value)
            if (rotationStep >= Sonic2CreditsData.ROTATION_STEPS) {
                // Non-Super: move character off-screen
                if (routine != Sonic2EndingArt.EndingRoutine.SUPER_SONIC) {
                    charOnTornadoX = 0x200;
                    charOnTornadoY = 0;
                }

                superDepartureStep = 0;
                superDepartureTick = 0;

                departureTimer = Sonic2CreditsData.DEPARTURE_TIMER;
                tornadoSubState = TornadoSubState.DEPARTURE;
                LOGGER.fine("Entering DEPARTURE");
                return;
            }

            // Update display frame from frame table for current step (before increment)
            rotationDisplayFrame = switch (routine) {
                case SONIC -> Sonic2CreditsData.TORNADO_FRAMES_SONIC[rotationStep];
                case SUPER_SONIC -> Sonic2CreditsData.TORNADO_FRAMES_SUPER[rotationStep];
                case TAILS -> Sonic2CreditsData.TORNADO_FRAMES_TAILS[rotationStep];
            };

            // Update tornado position from path table for current step
            if (rotationStep < Sonic2CreditsData.TORNADO_PATH.length) {
                int[] pos = Sonic2CreditsData.TORNADO_PATH[rotationStep];
                tornadoXSub = pos[0] << 8;
                tornadoYSub = pos[1] << 8;
            }

            // ROM: addq.w #1,objoff_32(a0) — increment AFTER reading
            rotationStep++;
        }
        // ROM: position only updates on timer expiry, not every frame
    }

    // --- ObjCC Sub-state 6: Character Departure ---

    private void updateDeparture() {
        // Super Sonic path update cadence mirrors ObjCC_State6:
        // objoff_31 decrements each frame, resets by +3 when negative.
        if (routine == Sonic2EndingArt.EndingRoutine.SUPER_SONIC) {
            superDepartureTick--;
            if (superDepartureTick < 0) {
                superDepartureTick += 3;
                if (superDepartureStep < Sonic2CreditsData.SUPER_SONIC_PATH.length) {
                    int[] pos = Sonic2CreditsData.SUPER_SONIC_PATH[superDepartureStep];
                    if (superDepartureStep < 3) {
                        // d0 < $C branch updates MainCharacter path only.
                        charOnTornadoX = pos[0];
                        charOnTornadoY = pos[1];
                    } else {
                        // d0 >= $C branch updates ObjCC position/frame and moves MainCharacter off-screen.
                        charOnTornadoX = 0x200;
                        charOnTornadoY = 0;
                        tornadoXSub = pos[0] << 8;
                        tornadoYSub = pos[1] << 8;
                        int frameIdx = Math.min(superDepartureStep, Sonic2CreditsData.SUPER_SONIC_FRAMES.length - 1);
                        rotationDisplayFrame = Sonic2CreditsData.SUPER_SONIC_FRAMES[frameIdx];
                    }
                    superDepartureStep++;
                }
            }
        }

        departureTimer--;
        if (departureTimer <= 0) {
            // ROM loc_A720: spawn ObjCF (always) and ObjCE (non-super) at transition
            // from State 6 to State 8.
            spawnDepartureChildObjects();

            // Transition to camera pan.
            cameraPanStep = 0;
            cameraPanFrameTimer = Sonic2CreditsData.CAMERA_PAN_FRAME_DELAY;
            tornadoSubState = TornadoSubState.CAMERA_PAN;
            LOGGER.fine("Entering CAMERA_PAN");
        }
    }

    private void spawnDepartureChildObjects() {
        objCfHelixActive = true;
        objCfHelixX = 0x10F;
        objCfHelixY = 0x15E;
        objCfHelixSavedX = objCfHelixX;
        objCfHelixSavedY = objCfHelixY;
        objCfHelixFrame = 5; // ObjCF_Init mapping_frame
        objCfHelixAnimTimer = 0;

        if (routine != Sonic2EndingArt.EndingRoutine.SUPER_SONIC) {
            objCeActive = true;
            objCeX = 0xE8;
            objCeY = 0x118;
            objCeSavedX = objCeX;
            objCeSavedY = objCeY;
            objCeFrame = (routine == Sonic2EndingArt.EndingRoutine.TAILS) ? 0xF : 0xC;
            objCePhase = 0; // ObjCE routine 2: follow until standing flag
            objCeTimer = 0;
            objCeJumpStep = 0;
        }
    }

    // --- ObjCC Sub-state 8: Camera Pan ---

    private void updateCameraPan() {
        cameraPanFrameTimer--;
        if (cameraPanFrameTimer <= 0) {
            cameraPanFrameTimer = Sonic2CreditsData.CAMERA_PAN_FRAME_DELAY;

            if (cameraPanStep < Sonic2CreditsData.CAMERA_PAN_STEPS) {
                int[] delta = Sonic2CreditsData.CAMERA_PAN_DELTAS[cameraPanStep];
                horizScrollOffset += delta[0];
                vscrollOffset += delta[1];
                cameraPanStep++;
            }

            if (cameraPanStep >= Sonic2CreditsData.CAMERA_PAN_STEPS) {
                standingFlag = true;

                if (routine == Sonic2EndingArt.EndingRoutine.SUPER_SONIC) {
                    superFinalStep = 0;
                    tornadoSubState = TornadoSubState.SUPER_FINAL;
                    LOGGER.fine("Entering SUPER_FINAL");
                }
                // Non-super: stay here, credits trigger via global timer
            }
        }
    }

    // --- ObjCC Sub-state $A: Super Sonic Final ---

    private void updateSuperFinal() {
        if (superFinalStep < Sonic2CreditsData.SUPER_FINAL_PATH.length) {
            int[] pos = Sonic2CreditsData.SUPER_FINAL_PATH[superFinalStep];
            charOnTornadoX = pos[0];
            charOnTornadoY = pos[1];
            superFinalStep++;
        }
        // Credits trigger via global timer
    }

    // --- Pilot animation (ObjB2_Animate_Pilot) ---

    /**
     * Updates pilot character animation in the tornado cockpit.
     * ROM: ObjB2_Animate_Pilot decrements objoff_37 each frame. When it goes
     * negative, it resets to 8, advances objoff_36 (frame index in sequence),
     * and calls LoadSonic/TailsDynPLC_Part2 with the sequence frame value.
     * Called during APPROACH and BIRDS_AND_HOLD sub-states.
     */
    private void updatePilotAnimation() {
        if (pilotAnimSequence == null) return;

        pilotAnimTimer--;
        if (pilotAnimTimer < 0) {
            pilotAnimTimer = PILOT_ANIM_DELAY;
            pilotAnimIndex++;
            if (pilotAnimIndex >= pilotAnimSequence.length) {
                pilotAnimIndex = 0;
            }
            publishPilotDecision(
                    pilotAnimSequence[pilotAnimIndex]);
        }
    }

    // ========================================================================
    // Entity management
    // ========================================================================

    private void spawnBird() {
        Sonic2Rng.EndingBirdSpawn spawn = Sonic2Rng.nextEndingBirdSpawn(GameServices.rng());
        int bx = spawn.x() << 8;
        int by = spawn.y() << 8;
        int yVel = spawn.yVel();
        int targetY = by >> 8;
        birds.add(new Bird(bx, by, spawn.xVel(), yVel, targetY));
    }

    private void updateBirds() {
        var it = birds.iterator();
        while (it.hasNext()) {
            Bird bird = it.next();
            bird.stateTimer++;

            // Animate: Ani_objCD speed 5, frames 0->1 loop
            bird.animTimer++;
            if (bird.animTimer >= Sonic2CreditsData.BIRD_ANIM_SPEED) {
                bird.animTimer = 0;
                bird.animFrame = (bird.animFrame + 1) % 2;
            }

            switch (bird.birdState) {
                case FLY_RIGHT -> {
                    bird.xSub += bird.xVel;
                    bird.ySub += bird.yVel;
                    if (bird.stateTimer >= Sonic2CreditsData.BIRD_STATE0_FRAMES) {
                        bird.birdState = BirdState.HOMING;
                        bird.stateTimer = 0;
                        bird.xVel = 0;
                    }
                }
                case HOMING -> {
                    int currentY = bird.ySub >> 8;
                    if (currentY < bird.targetY) {
                        bird.yVel += 4;
                    } else if (currentY > bird.targetY) {
                        bird.yVel -= 4;
                    }
                    bird.ySub += bird.yVel;
                    if (bird.stateTimer >= Sonic2CreditsData.BIRD_STATE1_FRAMES) {
                        bird.birdState = BirdState.EXIT_LEFT;
                        bird.stateTimer = 0;
                        bird.xVel = -Sonic2CreditsData.BIRD_X_VEL;
                        bird.yVel = bird.initialYVel;
                    }
                }
                case EXIT_LEFT -> {
                    bird.xSub += bird.xVel;
                    bird.ySub += bird.yVel;
                    if (bird.stateTimer >= Sonic2CreditsData.BIRD_STATE2_FRAMES) {
                        it.remove();
                    }
                }
            }
        }
    }

    /**
     * Spawns vertical clouds (drifting upward) — used during CAMERA_SCROLL to
     * create a falling sensation. ROM: ObjCB initial mode has y_vel upward and
     * spawns at Y=$100 with random X.
     */
    private void spawnVerticalCloudIfNeeded() {
        cloudSpawnTimer--;
        if (cloudSpawnTimer >= 0) return;

        if (clouds.size() >= 12) return;

        cloudSpawnTimer = Sonic2Rng.currentEndingSeedDelay(GameServices.rng(), 0x1F);
        Sonic2Rng.EndingCloudSpawn spawn = Sonic2Rng.nextEndingCloudSpawn(GameServices.rng(), false);
        clouds.add(new Cloud(spawn.x() << 8, spawn.y() << 8,
                spawn.xVel(), spawn.yVel(), spawn.frame(), false));
    }

    /**
     * Spawns horizontal clouds (drifting left) — used during MAIN_ENDING after
     * the CutScene flag is set. ROM: ObjCB with CutScene+$34 flag converts
     * y_vel to x_vel for leftward drift.
     */
    private void spawnCloudIfNeeded() {
        cloudSpawnTimer--;
        if (cloudSpawnTimer >= 0) return;

        if (clouds.size() >= 12) return;

        cloudSpawnTimer = Sonic2Rng.currentEndingSeedDelay(GameServices.rng(), 0x1F);
        if (cutSceneFlag) {
            Sonic2Rng.EndingCloudSpawn spawn = Sonic2Rng.nextEndingCloudSpawn(GameServices.rng(), true);
            clouds.add(new Cloud(spawn.x() << 8, spawn.y() << 8,
                    spawn.xVel(), spawn.yVel(), spawn.frame(), true));
        } else {
            Sonic2Rng.EndingCloudSpawn spawn = Sonic2Rng.nextEndingCloudSpawn(GameServices.rng(), false);
            clouds.add(new Cloud(spawn.x() << 8, spawn.y() << 8,
                    spawn.xVel(), spawn.yVel(), spawn.frame(), false));
        }
    }

    private void updateClouds() {
        var it = clouds.iterator();
        while (it.hasNext()) {
            Cloud cloud = it.next();
            // ROM ObjCB routine 2 checks CutScene+$34 and, once set, transitions
            // cloud motion from vertical to horizontal by moving y_vel into x_vel.
            if (!cloud.horizontal && cutSceneFlag) {
                cloud.horizontal = true;
                cloud.xVel = cloud.yVel;
                cloud.yVel = 0;
            }
            cloud.xSub += cloud.xVel;
            cloud.ySub += cloud.yVel;

            if (cloud.horizontal) {
                if ((cloud.xSub >> 8) < -0x20) it.remove();
            } else {
                if ((cloud.ySub >> 8) < 0) it.remove();
            }
        }
    }

    private void updateObjCe() {
        if (!objCeActive) return;

        if (objCePhase == 0) {
            // ROM loc_A90E: while parent not standing, object position follows
            // scroll buffer offsets using saved base coords.
            objCeX = objCeSavedX + horizScrollOffset;
            objCeY = objCeSavedY - vscrollOffset;
            if (standingFlag) {
                // ROM ObjCE loc_A902: switch to jump routine and clear timer.
                // First delta is applied next frame in loc_A936 when timer goes negative.
                objCePhase = 1;
                objCeTimer = 0;
                objCeJumpStep = 0;
                // Preserve the current compensated screen position as the jump origin.
                // ROM updates x_pos/y_pos directly in loc_A90E before switching routines.
                objCeSavedX = objCeX;
                objCeSavedY = objCeY;
            }
            return;
        }

        // ROM loc_A936: subq.w #1,objoff_3C; bpl -> hold current frame/position.
        // When negative, reset timer to 4 and consume next delta pair.
        objCeTimer--;
        if (objCeTimer < 0) {
            objCeTimer = 4;
            applyObjCeJumpStep();
        }
        objCeX = objCeSavedX;
        objCeY = objCeSavedY;
    }

    private void applyObjCeJumpStep() {
        int[][] deltas = (routine == Sonic2EndingArt.EndingRoutine.TAILS)
                ? Sonic2CreditsData.CHAR_JUMP_DELTAS_TAILS
                : Sonic2CreditsData.CHAR_JUMP_DELTAS_SONIC;
        if (objCeJumpStep >= deltas.length) {
            return;
        }
        objCeSavedX += deltas[objCeJumpStep][0];
        objCeSavedY += deltas[objCeJumpStep][1];
        objCeFrame++;
        objCeJumpStep++;
    }

    private void updateObjCfHelix() {
        if (!objCfHelixActive) return;

        // ROM ObjCF_Animate branches directly to loc_A90E every frame, so
        // helix coordinates always include scroll-buffer compensation.
        objCfHelixX = objCfHelixSavedX + horizScrollOffset;
        objCfHelixY = objCfHelixSavedY - vscrollOffset;

        // Ani_objCF anim 2: speed 1, frames 5→6 looping for continuous rotor spin.
        objCfHelixAnimTimer++;
        if (objCfHelixAnimTimer >= 2) {
            objCfHelixAnimTimer = 0;
            objCfHelixFrame = (objCfHelixFrame == 5) ? 6 : 5;
        }
    }

    // ========================================================================
    // MAIN_ENDING rendering
    // ========================================================================

    private void drawMainEnding(GraphicsManager gm) {
        gm.beginPatternBatch();

        // ROM ObjCC_Init decodes MapEng_Ending*TornadoPlane and VInt subroutine 4
        // writes it to Plane A. Render this backdrop before sprite objects.
        if (objCcActive) {
            drawEndingPlaneBackdrop(gm);
        }

        // Clouds (behind everything)
        drawClouds(gm);

        if (!objCcActive) {
            int frameIdx = charAnimFrames[charAnimIndex];
            drawPlayerFrame(gm, frameIdx, 0xA0, 0x50);
            gm.flushPatternBatch();
            return;
        }

        // Tornado
        drawTornado(gm);

        // Player character rendering depends on tornado sub-state:
        // APPROACH: ROM still shows the real MainCharacter at Float2 position (0xA0, 0x50)
        // BIRDS_AND_HOLD: character on tornado using Wait animation (MapUnc_Sonic frame 1)
        // ROTATION: ObjCF composite frame already includes character — DON'T draw separately
        // DEPARTURE/CAMERA_PAN: character off-screen (non-Super), tornado rendered in drawTornado
        // SUPER_FINAL: Super Sonic final positioning
        if (tornadoSubState == TornadoSubState.APPROACH) {
            // Character continues floating at center until tornado arrives
            int frameIdx = charAnimFrames[charAnimIndex];
            drawPlayerFrame(gm, frameIdx, 0xA0, 0x50);
        } else if (tornadoSubState == TornadoSubState.BIRDS_AND_HOLD
                && charOnTornadoX > -64 && charOnTornadoX < SCREEN_WIDTH
                && charOnTornadoY > -64 && charOnTornadoY < SCREEN_HEIGHT) {
            // ROM: real player object with Wait animation, positioned on tornado.
            // SonAni_Wait: $FF, 1, $FF → frame 1; TailsAni_Wait: same.
            int waitFrame = 1;
            drawPlayerFrame(gm, waitFrame, charOnTornadoX, charOnTornadoY);
        } else if (tornadoSubState == TornadoSubState.SUPER_FINAL
                && charOnTornadoX > -64 && charOnTornadoX < SCREEN_WIDTH
                && charOnTornadoY > -64 && charOnTornadoY < SCREEN_HEIGHT) {
            // ROM: ObjCC uses ArtTile_ArtKos_LevelArt ($0000) after switching to
            // ObjCF mappings — tile indices in ObjCF_MapUnc are absolute VRAM refs.
            int frame = getCharacterFrameForCurrentState();
            if (frame >= 0) {
                drawObjCfFrame(gm, frame, charOnTornadoX, charOnTornadoY, -1, 0);
            }
        }

        // Birds
        drawBirds(gm);

        // ObjCE jumping character — ROM: art_tile = ArtTile_ArtKos_LevelArt ($0000)
        // Uses ObjCF_MapUnc mappings with absolute tile indices.
        if (objCeActive && objCeFrame >= 0 && objCfFrames != null && objCeFrame < objCfFrames.size()) {
            drawObjCfFrame(gm, objCeFrame, objCeX, objCeY, -1, 0);
        }

        // ObjCF plane helixes — render after ObjCE so rotor stays visible on top.
        if (objCfHelixActive) {
            drawObjCfFrame(gm, objCfHelixFrame, objCfHelixX, objCfHelixY, -1, 0);
        }

        gm.flushPatternBatch();
    }

    private void drawEndingPlaneBackdrop(GraphicsManager gm) {
        if (endingPlaneMap == null || endingPlaneMap.length == 0) {
            return;
        }

        int originX = ENDING_PLANE_PLANE_X + horizScrollOffset;
        int originY = ENDING_PLANE_PLANE_Y - vscrollOffset;
        int maxWords = ENDING_PLANE_WIDTH_TILES * ENDING_PLANE_HEIGHT_TILES;
        int words = Math.min(maxWords, endingPlaneMap.length);

        for (int i = 0; i < words; i++) {
            int word = endingPlaneMap[i];
            if (word == 0) continue;
            reusableDesc.set(word);

            int tx = i % ENDING_PLANE_WIDTH_TILES;
            int ty = i / ENDING_PLANE_WIDTH_TILES;
            int drawX = originX + tx * 8;
            int drawY = originY + ty * 8;
            if (drawX < -8 || drawX >= SCREEN_WIDTH || drawY < -8 || drawY >= SCREEN_HEIGHT) {
                continue;
            }

            int patternId = Sonic2EndingArt.PATTERN_BASE_VRAM + reusableDesc.getPatternIndex();
            gm.renderPatternWithId(patternId, reusableDesc, drawX, drawY);
        }
    }

    private int getCharacterFrameForCurrentState() {
        if (tornadoSubState == TornadoSubState.ROTATION && rotationDisplayFrame >= 0) {
            return rotationDisplayFrame;
        }
        if (tornadoSubState == TornadoSubState.SUPER_FINAL) {
            return 0x17;
        }
        // DEPARTURE: character is off-screen for non-Super (charOnTornadoX=0x200),
        // so this frame is only used for Super Sonic departure.
        // ROM: ObjCC keeps last rotation mapping_frame; Super follows byte_A748.
        if (rotationDisplayFrame >= 0) {
            return rotationDisplayFrame;
        }
        return switch (routine) {
            case SUPER_SONIC -> 4;
            default -> 0xB;
        };
    }

    private void drawTornado(GraphicsManager gm) {
        int tx = tornadoXSub >> 8;
        int ty = tornadoYSub >> 8;

        if (tx < -64 || tx > SCREEN_WIDTH + 64 || ty < -64 || ty > SCREEN_HEIGHT + 64) {
            return;
        }

        switch (tornadoSubState) {
            case APPROACH -> {
                // ObjB2 body during approach.
                // ROM: make_art_tile(ArtTile_ArtNem_Tornado, 0, 1) — palette 0, priority 1
                // ROM Ani_objB2_a: anim 0 = frames 0,1,2,3 (Sonic); anim 1 = frames 4,5,6,7 (Tails)
                // Pilot DPLC decisions and bank updates are owned by update.
                ensurePilotPatternBankCached(gm);
                if (tornadoFrames != null && !tornadoFrames.isEmpty()) {
                    int animBase = (routine == Sonic2EndingArt.EndingRoutine.TAILS) ? 4 : 0;
                    int animFrame = animBase + (frameCounter % 4);
                    if (animFrame >= tornadoFrames.size()) animFrame = animBase;
                    int basePattern = Sonic2EndingArt.PATTERN_BASE_VRAM
                            + Sonic2Constants.ART_TILE_ENDING_TORNADO;
                    drawMappingFrame(gm, tornadoFrames, animFrame, tx, ty, basePattern, 0);
                }
            }
            case BIRDS_AND_HOLD -> {
                // ObjB2 body during hold. sub_A524 repositions MainCharacter to tornado.
                // ObjB2_Animate_Pilot state was prepared during update.
                ensurePilotPatternBankCached(gm);
                if (tornadoFrames != null && !tornadoFrames.isEmpty()) {
                    int animBase = (routine == Sonic2EndingArt.EndingRoutine.TAILS) ? 4 : 0;
                    int animFrame = animBase + (frameCounter % 4);
                    if (animFrame >= tornadoFrames.size()) animFrame = animBase;
                    int basePattern = Sonic2EndingArt.PATTERN_BASE_VRAM
                            + Sonic2Constants.ART_TILE_ENDING_TORNADO;
                    drawMappingFrame(gm, tornadoFrames, animFrame, tx, ty, basePattern, 0);
                }
            }
            case ROTATION -> {
                // ObjCF mappings during rotation — ROM calls DisplaySprite
                int frame = getObjCfTornadoFrame();
                if (frame >= 0) {
                    drawObjCfFrame(gm, frame, tx, ty, -1, 0);
                }
            }
            case DEPARTURE, CAMERA_PAN -> {
                // ROM: ObjCC ALWAYS calls DisplaySprite after every state handler via
                // unconditional jmpto JmpTo5_DisplaySprite at the end of ObjCC_Main.
                // Sprites render at screen coordinates — they do NOT move with BG scroll.
                // CAMERA_PAN changes Horiz_Scroll_Buf/Vscroll_Factor_FG (BG/FG scroll),
                // not sprite positions.
                int frame = getObjCfTornadoFrame();
                if (frame >= 0) {
                    drawObjCfFrame(gm, frame, tx, ty, -1, 0);
                }
            }
            case SUPER_FINAL -> {
                // ROM: Super Sonic final state — tornado still rendered at screen coords
                int frame = getObjCfTornadoFrame();
                if (frame >= 0) {
                    drawObjCfFrame(gm, frame, tx, ty, -1, 0);
                }
            }
        }
    }

    private int getObjCfTornadoFrame() {
        if (tornadoSubState == TornadoSubState.ROTATION && rotationDisplayFrame >= 0) {
            return rotationDisplayFrame;
        }
        // Post-rotation: ROM keeps mapping_frame from the last rotation step.
        // rotationDisplayFrame retains this value after rotation ends.
        if (rotationDisplayFrame >= 0) {
            return rotationDisplayFrame;
        }
        // Fallback (shouldn't reach here): last frame per character
        return switch (routine) {
            case SUPER_SONIC -> 4;
            default -> 0xB;
        };
    }

    private void drawClouds(GraphicsManager gm) {
        if (cloudFrames == null || cloudFrames.isEmpty()) return;

        for (Cloud cloud : clouds) {
            int cx = cloud.xSub >> 8;
            int cy = cloud.ySub >> 8;
            if (cx < -64 || cx > SCREEN_WIDTH + 64 || cy < -64 || cy > SCREEN_HEIGHT + 64) {
                continue;
            }
            int frameIdx = cloud.frame % cloudFrames.size();
            int basePattern = Sonic2EndingArt.PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_CLOUDS;
            // ROM: ObjCB_Init overrides ObjB3's palette 2 with palette_mask ($6000 = palette 3)
            drawMappingFrame(gm, cloudFrames, frameIdx, cx, cy, basePattern, 3);
        }
    }

    private void drawBirds(GraphicsManager gm) {
        if (animalFrames == null || animalFrames.isEmpty()) return;

        for (Bird bird : birds) {
            int bx = bird.xSub >> 8;
            int by = bird.ySub >> 8;
            if (bx < -32 || bx > SCREEN_WIDTH + 32 || by < -32 || by > SCREEN_HEIGHT + 32) {
                continue;
            }
            int frameIdx = bird.animFrame % animalFrames.size();
            int basePattern = Sonic2EndingArt.PATTERN_BASE_VRAM + Sonic2Constants.ART_TILE_ENDING_ANIMAL_2;
            drawMappingFrame(gm, animalFrames, frameIdx, bx, by, basePattern, -1);
        }
    }

    // ========================================================================
    // ROM mapping frame rendering
    // ========================================================================

    /**
     * Draws a player sprite frame (Map_Sonic/Map_Tails) at the given screen position.
     * ROM: CHARACTER_APPEAR uses real Sonic/Tails object with ArtTile_ArtUnc_Sonic ($0780)
     * or ArtTile_ArtUnc_Tails ($07A0). Player art is cached at PATTERN_BASE_VRAM + artTile.
     */
    private void drawPlayerFrame(GraphicsManager gm, int frameIndex, int x, int y) {
        if (playerMappingFrames == null || frameIndex < 0 || frameIndex >= playerMappingFrames.size()) {
            return;
        }
        // The update state machine already chose and prepared this DPLC bank.
        // Drawing may cache the prepared presentation state, but never owns a
        // semantic decision or changes a dynamic-art cursor.
        if (playerPatternBank != null) {
            playerPatternBank.ensureCached(gm);
        }
        int basePattern = playerPatternBank != null
                ? playerPatternBank.getBasePatternIndex()
                : Sonic2EndingArt.PATTERN_BASE_VRAM + playerArtTile;
        drawMappingFrame(gm, playerMappingFrames, frameIndex, x, y, basePattern, 0);
    }

    private void publishNormalPlayerDecision() {
        if (charAnimFrames == null || charAnimFrames.length == 0) {
            return;
        }
        publishNormalPlayerDecision(charAnimFrames[charAnimIndex]);
    }

    private void publishNormalPlayerDecision(int frameIndex) {
        SpriteDplcFrame dplcFrame = playerDplcFrame(frameIndex, "Player");
        if (dplcFrame == null) {
            return;
        }
        dynamicArtDecisionSink.observe(new Sonic2EndingDynamicArtDecisionSink.Decision(
                DynamicArtLifecycleService.DecisionKind.NORMAL_OBJECT,
                normalPlayerOwner(), frameIndex, dplcFrame));
        preparePlayerPatternBank(frameIndex, dplcFrame);
    }

    private void publishPilotDecision(int frameIndex) {
        SpriteDplcFrame dplcFrame = pilotDplcFrame(frameIndex);
        if (dplcFrame == null) {
            return;
        }
        dynamicArtDecisionSink.observe(new Sonic2EndingDynamicArtDecisionSink.Decision(
                DynamicArtLifecycleService.DecisionKind.DIRECT_PART2,
                pilotOwner(), frameIndex, dplcFrame));
        preparePilotPatternBank(frameIndex, dplcFrame);
    }

    private String normalPlayerOwner() {
        return routine == Sonic2EndingArt.EndingRoutine.TAILS ? "tails" : "sonic";
    }

    private String pilotOwner() {
        return routine == Sonic2EndingArt.EndingRoutine.TAILS ? "sonic" : "tails";
    }

    private SpriteDplcFrame playerDplcFrame(int frameIndex, String label) {
        if (playerDplcFrames == null || frameIndex < 0
                || frameIndex >= playerDplcFrames.size()) {
            if (playerDplcFrames != null) {
                LOGGER.warning(label + " DPLC frame " + frameIndex
                        + " out of range (max " + playerDplcFrames.size() + ")");
            }
            return null;
        }
        return playerDplcFrames.get(frameIndex);
    }

    private SpriteDplcFrame pilotDplcFrame(int frameIndex) {
        if (pilotDplcFrames == null || frameIndex < 0
                || frameIndex >= pilotDplcFrames.size()) {
            return null;
        }
        return pilotDplcFrames.get(frameIndex);
    }

    private void preparePlayerPatternBank(int frameIndex, SpriteDplcFrame dplcFrame) {
        if (frameIndex == lastPlayerDplcFrame || playerPatternBank == null
                || dplcFrame.requests().isEmpty()) {
            return;
        }
        playerPatternBank.consumeRuntimeArtState(dplcFrame.requests(), playerSourceArt);
        lastPlayerDplcFrame = frameIndex;
    }

    private void preparePilotPatternBank(int frameIndex, SpriteDplcFrame dplcFrame) {
        if (frameIndex == lastPilotDplcFrame || pilotPatternBank == null
                || dplcFrame.requests().isEmpty()) {
            return;
        }
        pilotPatternBank.consumeRuntimeArtState(dplcFrame.requests(), pilotSourceArt);
        lastPilotDplcFrame = frameIndex;
    }

    private void ensurePilotPatternBankCached(GraphicsManager graphicsManager) {
        if (pilotPatternBank != null) {
            pilotPatternBank.ensureCached(graphicsManager);
        }
    }

    /**
     * Draws an ObjCF mapping frame at the given screen position.
     * ROM: piece tile indices are offsets from the object's art_tile, so the
     * base must include the art_tile offset (e.g. ART_TILE_ENDING_MINI_TORNADO
     * for ObjCF/ObjCC, ART_TILE_ENDING_CHARACTER for ObjCE).
     */
    private void drawObjCfFrame(GraphicsManager gm, int frameIndex, int originX, int originY,
                                  int paletteOverride, int artTile) {
        if (objCfFrames == null || frameIndex < 0 || frameIndex >= objCfFrames.size()) {
            return;
        }
        SpriteMappingFrame frame = objCfFrames.get(frameIndex);
        List<SpriteMappingPiece> pieces = frame.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            boolean piecePriority = piece.priority();
            SpritePieceRenderer.renderPiece(
                    piece, originX, originY,
                    Sonic2EndingArt.PATTERN_BASE_VRAM + artTile, paletteOverride,
                    false, false,
                    (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                        int descIndex = patternIdx & 0x7FF;
                        if (piecePriority) descIndex |= 0x8000;
                        if (pieceHFlip) descIndex |= 0x800;
                        if (pieceVFlip) descIndex |= 0x1000;
                        descIndex |= (palIdx & 0x3) << 13;
                        reusableDesc.set(descIndex);
                        gm.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                    });
        }
    }

    /**
     * Generic mapping frame renderer for ObjB2 (tornado), ObjB3 (clouds), Obj28 (animals).
     */
    private void drawMappingFrame(GraphicsManager gm, List<SpriteMappingFrame> frames,
                                   int frameIndex, int originX, int originY,
                                    int basePatternIdx, int paletteOverride) {
        if (frames == null || frameIndex < 0 || frameIndex >= frames.size()) {
            return;
        }
        SpriteMappingFrame frame = frames.get(frameIndex);
        List<SpriteMappingPiece> pieces = frame.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            SpriteMappingPiece piece = pieces.get(i);
            boolean piecePriority = piece.priority();
            SpritePieceRenderer.renderPiece(
                    piece, originX, originY,
                    basePatternIdx, paletteOverride,
                    false, false,
                    (patternIdx, pieceHFlip, pieceVFlip, palIdx, drawX, drawY) -> {
                        int descIndex = patternIdx & 0x7FF;
                        if (piecePriority) descIndex |= 0x8000;
                        if (pieceHFlip) descIndex |= 0x800;
                        if (pieceVFlip) descIndex |= 0x1000;
                        descIndex |= (palIdx & 0x3) << 13;
                        reusableDesc.set(descIndex);
                        gm.renderPatternWithId(patternIdx, reusableDesc, drawX, drawY);
                    });
        }
    }

    // ========================================================================
    // Enigma map loading
    // ========================================================================

    private int[] loadEnigmaMap(Rom rom, int address, int startingArtTile, String name) {
        try {
            byte[] compressed = rom.readBytes(address, 1024);
            try (ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
                 ReadableByteChannel channel = Channels.newChannel(bais)) {
                byte[] decompressed = EnigmaReader.decompress(channel, startingArtTile);

                int wordCount = decompressed.length / 2;
                int[] map = new int[wordCount];
                ByteBuffer buf = ByteBuffer.wrap(decompressed);
                for (int i = 0; i < wordCount; i++) {
                    map[i] = buf.getShort() & 0xFFFF;
                }

                LOGGER.fine("Loaded " + name + " Enigma map: " + wordCount + " tiles");
                return map;
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to load " + name + " Enigma map: " + e.getMessage());
            return new int[0];
        }
    }

    private static boolean isPalTiming() {
        String region = GameServices.configuration().getString(SonicConfiguration.REGION);
        return "PAL".equalsIgnoreCase(region);
    }

    // ========================================================================
    // Palette fade system
    // ========================================================================

    /**
     * Initializes display palettes as all-white, matching ROM's EndingSequence init
     * where Normal_palette is filled with $0EEE (white) for every color entry.
     */
    private void initDisplayPalettes() {
        Palette[] targetPalettes = endingArt.getEndingPalettes();
        if (targetPalettes == null) return;

        displayPalettes = new Palette[targetPalettes.length];
        for (int i = 0; i < displayPalettes.length; i++) {
            displayPalettes[i] = new Palette();
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                displayPalettes[i].setColor(c,
                        new Palette.Color((byte) 0xFF, (byte) 0xFF, (byte) 0xFF));
            }
        }

        cacheDisplayPalettes();
        // Don't start fading yet — enterInit() will enable fade with proper line range.
        // ROM: ObjC9 is spawned by ObjCA_Init, not at EndingSequence init.
        paletteFadeActive = false;
        paletteFadeStartLine = 0;
        paletteFadeEndLine = 0;
        LOGGER.fine("Display palettes initialized as all-white for fade-from-white effect");
    }

    /**
     * Runs one step of PaletteFadeFrom/PaletteFadeTo: changes each color component
     * by one MD level toward the target palette. Only steps every {@code paletteFadeDelay}
     * frames, matching ROM's ObjC9 fadeinTime counter. Deactivates when all colors match.
     */
    private void runPaletteFadeStep() {
        Palette[] targetPalettes = endingArt.getEndingPalettes();
        if (displayPalettes == null || targetPalettes == null) {
            paletteFadeActive = false;
            return;
        }

        // ROM: ObjC9_Main counts down fadeinTime_left; fade only runs when it hits 0.
        paletteFadeDelayTimer--;
        if (paletteFadeDelayTimer > 0) {
            return;
        }
        paletteFadeDelayTimer = paletteFadeDelay;

        boolean anyChanged = false;

        // ROM: ObjC9 only fades the palette line range specified by its start_offset/length.
        // During photos, only line 0 fades. Lines 1-3 stay white (unchanged Normal).
        int startLine = Math.max(0, paletteFadeStartLine);
        int endLine = Math.min(Math.min(paletteFadeEndLine, displayPalettes.length - 1),
                targetPalettes.length - 1);

        for (int line = startLine; line <= endLine; line++) {
            if (displayPalettes[line] == null || targetPalettes[line] == null) continue;
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                Palette.Color display = displayPalettes[line].getColor(c);
                Palette.Color target = targetPalettes[line].getColor(c);

                int targetR = paletteFadeToWhite ? 0xFF : (target.r & 0xFF);
                int targetG = paletteFadeToWhite ? 0xFF : (target.g & 0xFF);
                int targetB = paletteFadeToWhite ? 0xFF : (target.b & 0xFF);
                int newR = fadeColorStep(display.r & 0xFF, targetR);
                int newG = fadeColorStep(display.g & 0xFF, targetG);
                int newB = fadeColorStep(display.b & 0xFF, targetB);

                if (newR != (display.r & 0xFF) || newG != (display.g & 0xFF)
                        || newB != (display.b & 0xFF)) {
                    display.r = (byte) newR;
                    display.g = (byte) newG;
                    display.b = (byte) newB;
                    anyChanged = true;
                }
            }
        }

        if (anyChanged) {
            cacheDisplayPalettes();
        } else {
            paletteFadeActive = false;
            LOGGER.fine("Palette fade from white complete");
        }
    }

    /**
     * Fades a single color component by one MD level toward the target.
     */
    private static int fadeColorStep(int current, int target) {
        if (current == target) return current;
        if (current > target) {
            for (int i = MD_COLOR_LEVELS.length - 1; i >= 0; i--) {
                if (MD_COLOR_LEVELS[i] < current) {
                    return Math.max(MD_COLOR_LEVELS[i], target);
                }
            }
            return target;
        } else {
            for (int i = 0; i < MD_COLOR_LEVELS.length; i++) {
                if (MD_COLOR_LEVELS[i] > current) {
                    return Math.min(MD_COLOR_LEVELS[i], target);
                }
            }
            return target;
        }
    }

    /**
     * Copies the Photos palette (Pal_AD1E) into Target palette line 0.
     * ROM: ObjC9 subtype 4 at ObjCA_Init does this every photo cycle.
     * C9PalInfo: loc_1344C, Pal_AD1E, offset 0, length $F (line 0 only).
     */
    private void copyPhotosPaletteToTargetLine0() {
        if (photosPalette == null || endingArt == null) return;
        Palette[] targetPalettes = endingArt.getEndingPalettes();
        if (targetPalettes == null || targetPalettes.length == 0) return;

        for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
            Palette.Color src = photosPalette.getColor(c);
            targetPalettes[0].setColor(c, new Palette.Color(src.r, src.g, src.b));
        }
        LOGGER.fine("Target palette line 0 set to Photos palette (ObjC9 subtype 4)");
    }

    /**
     * Loads character-specific palette into Target palette at the CHARACTER_APPEAR
     * transition. ROM: d0=8 (Sonic) → subtype 8 = Pal_AC7E lines 0-1,
     * d0=$C (Super) → subtype $C = Pal_AD3E line 0, d0=$E (Tails) → subtype $E = Pal_AC9E lines 0-1.
     */
    private void loadCharacterPaletteToTarget() {
        if (endingArt == null) return;
        Palette[] targetPalettes = endingArt.getEndingPalettes();
        if (targetPalettes == null || targetPalettes.length < 2) return;

        // Photos palette overwrote Target line 0. Restore character palette from ROM.
        // ROM subtypes: 8 (Sonic) → Pal_AC7E 64B lines 0-1;
        //               $C (Super) → Pal_AD3E 32B line 0;
        //               $E (Tails) → Pal_AC9E 64B lines 0-1.
        try {
            com.openggf.data.Rom rom = com.openggf.game.GameServices.rom().getRom();
            switch (routine) {
                case SONIC -> {
                    loadPaletteLinesToTarget(rom, Sonic2Constants.PAL_ENDING_SONIC_ADDR, 64,
                            targetPalettes, 0);
                    paletteFadeStartLine = 0;
                    paletteFadeEndLine = 1;
                }
                case SUPER_SONIC -> {
                    loadPaletteLinesToTarget(rom, Sonic2Constants.PAL_ENDING_SUPER_SONIC_ADDR, 32,
                            targetPalettes, 0);
                    paletteFadeStartLine = 0;
                    paletteFadeEndLine = 0;
                }
                case TAILS -> {
                    loadPaletteLinesToTarget(rom, Sonic2Constants.PAL_ENDING_TAILS_ADDR, 64,
                            targetPalettes, 0);
                    paletteFadeStartLine = 0;
                    paletteFadeEndLine = 1;
                }
            }
            // ROM C9PalInfo subtypes 8/$C/$E: fadeinTime=4, fadeinAmount=7
            paletteFadeActive = true;
            paletteFadeToWhite = false;
            paletteFadeDelay = 4;
            paletteFadeDelayTimer = 4;
            LOGGER.fine("Target palette restored to character palette for " + routine);
        } catch (Exception e) {
            LOGGER.warning("Failed to reload character palette: " + e.getMessage());
        }
    }

    /**
     * Loads palette data from ROM into Target palette lines starting at the given line.
     * @param size number of bytes (32 = 1 line, 64 = 2 lines)
     * @param startLine first Target palette line to write to
     */
    private void loadPaletteLinesToTarget(com.openggf.data.Rom rom, int addr, int size,
                                           Palette[] targetPalettes, int startLine) throws IOException {
        byte[] data = rom.readBytes(addr, size);
        int lineCount = size / 32;
        for (int line = 0; line < lineCount; line++) {
            int targetLine = startLine + line;
            if (targetLine >= targetPalettes.length) break;
            Palette pal = new Palette();
            pal.fromSegaFormat(Arrays.copyOfRange(data, line * 32, (line + 1) * 32));
            for (int c = 0; c < Palette.PALETTE_SIZE; c++) {
                Palette.Color src = pal.getColor(c);
                targetPalettes[targetLine].setColor(c, new Palette.Color(src.r, src.g, src.b));
            }
        }
    }

    /**
     * Loads Background palette (Pal_ACDE, 64 bytes) into Target palette lines 2-3.
     * ROM: ObjC9 subtype $A at routine $A. C9PalInfo: loc_1344C, Pal_ACDE, $40, $1F.
     */
    private void loadBackgroundPaletteToTarget() {
        if (endingArt == null) return;
        Palette[] targetPalettes = endingArt.getEndingPalettes();
        if (targetPalettes == null || targetPalettes.length <= 3) return;

        try {
            com.openggf.data.Rom rom = com.openggf.game.GameServices.rom().getRom();
            // Pal_ACDE: 64 bytes → Target lines 2-3
            loadPaletteLinesToTarget(rom, Sonic2Constants.PAL_ENDING_BACKGROUND_ADDR, 64,
                    targetPalettes, 2);
            LOGGER.fine("Target palette lines 2-3 updated with Background data (ObjC9 subtype $A)");
        } catch (Exception e) {
            LOGGER.warning("Failed to load background palette: " + e.getMessage());
        }
    }

    private void cacheDisplayPalettes() {
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode() || displayPalettes == null) return;
        for (int i = 0; i < displayPalettes.length; i++) {
            if (displayPalettes[i] != null) {
                gm.cachePaletteTexture(displayPalettes[i], i);
            }
        }
    }
}
