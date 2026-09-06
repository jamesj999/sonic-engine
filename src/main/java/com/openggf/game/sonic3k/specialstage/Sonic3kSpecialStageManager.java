package com.openggf.game.sonic3k.specialstage;

import com.openggf.audio.GameMusic;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;

import java.io.IOException;
import java.util.logging.Logger;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Core coordinator for the Sonic 3&K Blue Ball special stage.
 * <p>
 * Manages the complete lifecycle: grid loading, player movement, collision
 * detection, sphere-to-ring conversion, perspective rendering, HUD, banner,
 * and stage completion.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm SpecialStage (line 10585)
 */
public class Sonic3kSpecialStageManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSpecialStageManager.class.getName());

    // ==================== Core State ====================

    private int currentStage;
    private boolean initialized;
    private boolean finished;
    private boolean emeraldCollected;
    private boolean superEmeraldMode;
    private int ringsCollected;
    private int spheresLeft;
    private int frameCounter;

    // ==================== Subsystems ====================

    private final Sonic3kSpecialStageGrid grid = new Sonic3kSpecialStageGrid();
    private final Sonic3kSpecialStagePlayer player = new Sonic3kSpecialStagePlayer();
    private final Sonic3kSpecialStageCollision collision = new Sonic3kSpecialStageCollision();
    private final Sonic3kSpecialStageRingConverter ringConverter = new Sonic3kSpecialStageRingConverter();
    private final Sonic3kSpecialStageCollisionQueue collisionQueue = new Sonic3kSpecialStageCollisionQueue();
    private final Sonic3kSpecialStagePerspective perspective = new Sonic3kSpecialStagePerspective();
    private final Sonic3kSpecialStageBackground background = new Sonic3kSpecialStageBackground();
    private final Sonic3kSpecialStageHud hud = new Sonic3kSpecialStageHud();
    private final Sonic3kSpecialStageBanner banner = new Sonic3kSpecialStageBanner();

    private Sonic3kSpecialStageRenderer renderer;
    private Sonic3kSpecialStagePalette palette;
    private Sonic3kSpecialStageDataLoader dataLoader;

    // ==================== Input State ====================

    private int heldButtons;
    private int pressedButtons;

    // ==================== Banner State ====================

    /** Banner phase: 0=sliding in, 1=displaying, 2=sliding out, 3=done, 4=re-entering */
    private int bannerPhase;
    private int bannerTimer;
    private int bannerOffset;

    // ==================== Clear Sequence State ====================

    /**
     * Clear routine state machine.
     * 0 = normal play, 1 = fly-away timer, 2 = emerald loading,
     * 3 = emerald approach, 4 = complete
     */
    private int clearRoutine;
    private int clearTimer;
    private int emeraldTimer;
    private int emeraldInteractIndex;
    /**
     * The {@code Queue_Kos_Module} workload submitted for the Chaos/Super
     * Emerald art (ROM {@code loc_9C52}, sonic3k.asm:12608-12610).
     *
     * <p>The wait it drives is not a frame count. {@code loc_9C5C}
     * (sonic3k.asm:12613-12620) tests {@code Kos_modules_left}, a byte owned
     * entirely by the module state machine
     * ({@code Process_Kos_Module_Queue_Init} / {@code Process_Kos_Module_Queue},
     * sonic3k.asm:2694-2790): Init sets it to the archive's module count, and
     * each module costs one {@code Process_Kos_Module_Queue} call to hand to
     * the decompression FIFO (2735-2742) plus one to observe
     * {@code Kos_decomp_queue_count == 0}, clear bit 7 and DMA (2745-2758).
     * The engine therefore submits the same archive to {@link S3kKosModuleQueue}
     * and reads the same predicate; how long the FIFO child takes to
     * decompress is 68000 main-loop budget ({@code Process_Kos_Queue},
     * sonic3k.asm:2840, bookmarked and resumed across V-ints at 2818-2830),
     * which is exactly the hardware timing the recorded timing port supplies.
     *
     * <p>The ordinal is the rewind-stable identity of that submission; the
     * handle is rebound from the timing ledger after a restore, the pattern
     * {@code Sonic3kHCZEvents.rebindHardwareWorkAfterRewind} already uses.
     */
    private HardwareWorkHandle emeraldArtWork;
    private long emeraldArtWorkOrdinal = -1;

    // ==================== Remaining rings from sphere conversion ====================
    private int ringsLeft;
    /** Whether the exit spin animation has been started. */
    private boolean exitSpinStarted;
    /** Palette fade delay counter (ROM: Pal_fade_delay, counts down from 2). */
    private int palFadeDelay;
    /** Whether music has been sped up (first speed increase). */
    private boolean musicSpedUp;

    // ==================== Ring Animation ====================
    /** Ring animation timer (counts down from 7, resets). */
    private int ringAnimTimer;
    /** Ring animation frame: 0, 1, or 2 (cycles every 8 game frames). */
    private int ringAnimFrame;

    // ==================== Tails (P2) ====================
    private final Sonic3kSpecialStageTailsAI tailsAI = new Sonic3kSpecialStageTailsAI();
    /** Tails animation frame timer (same format as Sonic's). */
    private int tailsAnimTimer;
    /** Tails current mapping frame. */
    private int tailsMappingFrame;
    /** Tails tails animation timer (overflows to advance frame). */
    private int tailsTailsAnimTimer;
    /** Tails tails current mapping frame (cycles 1-14). */
    private int tailsTailsMappingFrame = 1;
    /** Tails jump state: 0=ground, 0x80=normal, 0x81=spring. */
    private int tailsJumping;
    /** Tails jump height (same format as Sonic's). */
    private long tailsJumpHeight;
    /** Tails jump velocity. */
    private long tailsJumpVelocity;
    /** P2 held buttons from input. */
    private int p2HeldButtons;
    /** Whether Tails P2 is active (Sonic & Tails mode). */
    private boolean tailsEnabled = true;
    /** Current player character selection (resolved from config on init). */
    private PlayerCharacter playerCharacter = PlayerCharacter.SONIC_AND_TAILS;

    // ==================== Entry Fade Hold ====================

    /**
     * True until this manager's first post-{@link #initialize(int)} call to
     * {@link #update()} has run. ROM: the special-stage object's first
     * per-frame routine execution (loc_903E, sonic3k.asm:11445) happens
     * synchronously inside the boot sequence's own {@code Process_Sprites}
     * call (sonic3k.asm:10717) -- BEFORE the first {@code Wait_VSync}
     * (sonic3k.asm:10725) that starts real input polling. That pre-boot call
     * is modeled as this manager's first stepped {@code update()} running
     * normally; {@link #postBootFadeHoldFrames} then models the frames that
     * follow it during which the ROM does NOT call {@code Process_Sprites}
     * at all.
     */
    private boolean firstUpdateCall;

    /**
     * Remaining real, input-polled frames during which the ROM does not
     * execute the special-stage object's per-frame routine at all, so
     * {@code Special_stage_rate_timer} (and everything else the object
     * touches) does not advance. Two ROM waits stack back to back after the
     * boot's pre-call:
     * <ul>
     *   <li>{@code Pal_FadeFromWhite} (sonic3k.asm:10735, routine at
     *       5139-5150): {@code moveq #$15,d4 / dbf d4,loc_3C8E} runs 22
     *       {@code Wait_VSync} iterations, calling only palette-fade helpers
     *       -- no {@code Process_Sprites}.</li>
     *   <li>{@code loc_84C2}'s own leading {@code Wait_VSync}
     *       (sonic3k.asm:10741) before ITS first {@code Process_Sprites}
     *       call (sonic3k.asm:10744) -- one more real frame with no object
     *       update.</li>
     * </ul>
     * Total 23 frames; this field only counts the 22 held AFTER
     * {@link #firstUpdateCall} consumes the first (pre-boot-equivalent)
     * call, so it initializes to 22 and the 23rd hold frame is the 1 leading
     * {@code loc_84C2} {@code Wait_VSync} counted alongside it.
     * <p>
     * Pinned by BizHawk RAM trace {@code s3-knux-multibonus-ss.bk2}
     * (segment 12): {@code Special_stage_rate_timer} reads a flat 0x707
     * across CSV rows 135-157 (23 rows, the first interactive row plus this
     * 22-frame hold) and only starts decrementing at row 158.
     */
    /**
     * {@code Pal_FadeToWhite}'s own iteration count: {@code move.w #$15,d4}
     * with a trailing {@code dbf d4} runs $16 = 22 {@code Wait_VSync}
     * iterations (sonic3k.asm:5232-5242). Identical to the count
     * {@link #postBootFadeHoldFrames} takes from {@code Pal_FadeFromWhite}
     * (sonic3k.asm:5139-5150), which is written the same way.
     */
    private static final int PAL_FADE_TO_WHITE_FRAMES = 22;

    /**
     * Remaining real, input-polled frames the ROM spends in the special
     * stage's ENTRY fade, before any special-stage state exists at all.
     * {@code SpecialStage} (sonic3k.asm:10585) opens with
     * {@code bsr.w Pal_FadeToWhite} (sonic3k.asm:10591); that routine
     * (sonic3k.asm:5232-5242) is {@code move.w #$15,d4 / ... bsr.w Wait_VSync
     * ... dbf d4,loc_3D3A}, so it blocks for 22 real frames calling only the
     * palette helpers -- no {@code Process_Sprites}, and
     * {@code Special_stage_rate} / {@code Special_stage_rate_timer} are not
     * written until sonic3k.asm:10701-10707, after the stage load. The engine
     * performs entry and {@code initializeStage} in one frame, so without this
     * hold the manager's first 22 stepped frames land on the ROM's fade-to-
     * white frames and {@code Special_stage_rate_timer} runs 22 frames ahead
     * of the ROM for the whole stage.
     * <p>
     * Same shape and the same derivation as {@link #postBootFadeHoldFrames}
     * (which models the mirror-image {@code Pal_FadeFromWhite}, also 22
     * {@code Wait_VSync} iterations), and the same
     * {@code SpecialStageStartupPolicy} split S1 already uses for
     * {@code Sonic1SpecialStageManager.SS_STARTUP_HOLD_TICKS}: FAST callers
     * fast-forward it inside {@code initializeStage}, TRACE_ACCURATE callers
     * frame-step it.
     */
    private int preBootFadeHoldFrames;

    private int postBootFadeHoldFrames;

    // Debug state
    private boolean spriteDebugMode;

    public Sonic3kSpecialStageManager() {}

    /**
     * Initialize the special stage with the given stage index.
     * ROM: SpecialStage (sonic3k.asm:10585) + sub_85B0 (line 10809)
     *
     * @param stageIndex stage number (0-7)
     * @throws IOException if ROM data loading fails
     */
    public void initialize(int stageIndex) throws IOException {
        LOGGER.info("Initializing S3K special stage " + stageIndex);
        this.currentStage = stageIndex;
        this.initialized = true;
        this.finished = false;
        this.emeraldCollected = false;
        this.superEmeraldMode = false;
        this.ringsCollected = 0;
        this.frameCounter = 0;
        this.clearRoutine = 0;
        this.clearTimer = 0;
        this.emeraldTimer = 0;
        this.emeraldInteractIndex = -1;
        this.emeraldArtWork = null;
        this.emeraldArtWorkOrdinal = -1;
        this.ringsLeft = 0;
        this.exitSpinStarted = false;
        this.firstUpdateCall = true;
        this.preBootFadeHoldFrames = PAL_FADE_TO_WHITE_FRAMES;
        this.postBootFadeHoldFrames = 22;
        this.palFadeDelay = 0;
        this.musicSpedUp = false;

        // Resolve character from configuration
        this.playerCharacter = resolvePlayerCharacter();
        this.tailsEnabled = (playerCharacter == PlayerCharacter.SONIC_AND_TAILS);
        GameStateManager state = GameServices.gameState();
        this.superEmeraldMode = resolveSuperEmeraldMode(state);

        this.bannerPhase = 0;
        this.bannerTimer = 0;
        this.bannerOffset = 0;

        collisionQueue.clear();

        // Initialize renderer
        if (renderer == null) {
            renderer = new Sonic3kSpecialStageRenderer(GameServices.graphics());
        }
        renderer.resetStageGeometryCache();
        palette = new Sonic3kSpecialStagePalette();

        // Load ROM data and cache art patterns
        if (Sonic3kSpecialStageRomOffsets.areOffsetsVerified()) {
            loadRomData();
        } else {
            LOGGER.warning("S3K SS ROM offsets not verified — using placeholder rendering");
            player.initialize(ANGLE_NORTH, 0x1000, 0x1000, false);
        }

        spheresLeft = grid.countBlueSpheres();

        // Initialize subsystems
        background.initialize(player.getXPos(), player.getYPos());
        hud.initialize();
        banner.initialize();
        tailsAI.initialize();
        tailsAnimTimer = 0;
        tailsMappingFrame = 0;
        tailsJumping = 0;
        tailsJumpHeight = 0;
        tailsJumpVelocity = 0;
        tailsTailsAnimTimer = 0;
        tailsTailsMappingFrame = 1;
    }

    static boolean resolveSuperEmeraldMode(GameStateManager state) {
        boolean superEmeraldMode = state.hasAllEmeralds() && !state.hasAllSuperEmeralds();
        if (superEmeraldMode) {
            // ROM progression converts the Chaos Emeralds before the first
            // Super Emerald is awarded; leaving this stage must not re-enable
            // the Chaos-Emerald transformation route.
            state.setEmeraldsConverted(true);
        }
        return superEmeraldMode;
    }

    /** Pattern ID base for special stage art (avoids conflicts with level patterns). */
    private static final int SS_PATTERN_BASE = PatternAtlasRange.SPECIAL_STAGE_PLAYFIELD.base();

    /**
     * Load all ROM data: layouts, art, palettes, perspective maps.
     * Caches decompressed patterns into the graphics atlas.
     */
    private void loadRomData() throws IOException {
        dataLoader = Sonic3kSpecialStageDataLoader.create();
        GraphicsManager gm = GameServices.graphics();

        // Load layout from ROM.
        // Stages 0-7: S3 layouts (Lockon data, uncompressed)
        // Stages 8-15: SK layouts (Kosinski compressed Set 1)
        com.openggf.data.Rom rom = GameServices.rom().getRom();
        byte[] stageData;
        if (currentStage < 8) {
            // S3 layouts
            long layoutAddr = Sonic3kSpecialStageRomOffsets.LAYOUT_S3_STAGE_1
                    + (long) currentStage * Sonic3kSpecialStageRomOffsets.LAYOUT_STAGE_SIZE;
            stageData = rom.readBytes(layoutAddr,
                    Sonic3kSpecialStageRomOffsets.LAYOUT_STAGE_SIZE);
        } else {
            // SK layouts (Kosinski compressed)
            byte[] skCompressed = dataLoader.getCompressedLayoutSet(0);
            int skStage = (currentStage - 8) % 8;
            int offset = skStage * Sonic3kSpecialStageRomOffsets.LAYOUT_STAGE_SIZE;
            stageData = new byte[Sonic3kSpecialStageRomOffsets.LAYOUT_STAGE_SIZE];
            if (offset + stageData.length <= skCompressed.length) {
                System.arraycopy(skCompressed, offset, stageData, 0, stageData.length);
            }
        }
        int[] params = grid.loadFromLayoutData(stageData);
        player.initialize(params[0], params[1], params[2], false);
        spheresLeft = grid.countBlueSpheres();
        ringsLeft = params[3]; // ROM: Special_stage_rings_left from layout trailer
        LOGGER.info("Loaded S3K SS layout for stage " + currentStage +
                ": angle=0x" + Integer.toHexString(params[0]) +
                " pos=(" + Integer.toHexString(params[1]) + "," +
                Integer.toHexString(params[2]) + ")" +
                " spheres=" + spheresLeft);

        // Cache art patterns into the graphics atlas
        int nextBase = SS_PATTERN_BASE;

        // Floor layout art (checkerboard tiles)
        Pattern[] floorPatterns = dataLoader.getLayoutArt();
        renderer.setFloorPatternBase(nextBase);
        for (int i = 0; i < floorPatterns.length; i++) {
            gm.cachePatternTexture(floorPatterns[i], nextBase + i);
        }
        nextBase += floorPatterns.length;
        LOGGER.fine("Cached " + floorPatterns.length + " floor patterns");

        // Sphere art
        Pattern[] spherePatterns = dataLoader.getSphereArt();
        renderer.setSpherePatternBase(nextBase);
        for (int i = 0; i < spherePatterns.length; i++) {
            gm.cachePatternTexture(spherePatterns[i], nextBase + i);
        }
        nextBase += spherePatterns.length;

        // Ring art
        Pattern[] ringPatterns = dataLoader.getRingArt();
        renderer.setRingPatternBase(nextBase);
        for (int i = 0; i < ringPatterns.length; i++) {
            gm.cachePatternTexture(ringPatterns[i], nextBase + i);
        }
        nextBase += ringPatterns.length;

        // Background art
        Pattern[] bgPatterns = dataLoader.getBgArt();
        renderer.setBgPatternBase(nextBase);
        for (int i = 0; i < bgPatterns.length; i++) {
            gm.cachePatternTexture(bgPatterns[i], nextBase + i);
        }
        nextBase += bgPatterns.length;

        // Shadow art
        Pattern[] shadowPatterns = dataLoader.getShadowArt();
        renderer.setShadowPatternBase(nextBase);
        for (int i = 0; i < shadowPatterns.length; i++) {
            gm.cachePatternTexture(shadowPatterns[i], nextBase + i);
        }
        nextBase += shadowPatterns.length;

        // "Get Blue Spheres" text art + arrow art
        // ROM loads GBS art at ART_TILE_GET_BLUE_SPHERES (0x055F) and
        // arrow art at offset 0x199 from that base.
        Pattern[] gbsPatterns = dataLoader.getGetBlueSphereArt();
        Pattern[] gbsArrowPatterns = dataLoader.getGbsArrowArt();
        renderer.setGetBlueSpherePatternBase(nextBase);
        for (int i = 0; i < gbsPatterns.length; i++) {
            gm.cachePatternTexture(gbsPatterns[i], nextBase + i);
        }
        // Load arrow art at offset 0x199 from the GBS base
        for (int i = 0; i < gbsArrowPatterns.length; i++) {
            gm.cachePatternTexture(gbsArrowPatterns[i], nextBase + 0x199 + i);
        }
        nextBase += 0x199 + gbsArrowPatterns.length;

        // Digits art (HUD numbers)
        Pattern[] digitsPatterns = dataLoader.getDigitsArt();
        renderer.setDigitsPatternBase(nextBase);
        for (int i = 0; i < digitsPatterns.length; i++) {
            gm.cachePatternTexture(digitsPatterns[i], nextBase + i);
        }
        nextBase += digitsPatterns.length;

        // Icons art (HUD icons)
        Pattern[] iconsPatterns = dataLoader.getIconsArt();
        renderer.setIconsPatternBase(nextBase);
        for (int i = 0; i < iconsPatterns.length; i++) {
            gm.cachePatternTexture(iconsPatterns[i], nextBase + i);
        }
        nextBase += iconsPatterns.length;

        // Player art (Sonic by default)
        Pattern[] playerPatterns = loadPlayerArt();
        renderer.setPlayerPatternBase(nextBase);
        for (int i = 0; i < playerPatterns.length; i++) {
            gm.cachePatternTexture(playerPatterns[i], nextBase + i);
        }
        nextBase += playerPatterns.length;

        // Load perspective maps and pass to renderer
        byte[] perspData = dataLoader.getPerspectiveMaps();
        perspective.loadMaps(perspData);
        renderer.setPerspectiveMaps(perspData);

        // Load floor map (Enigma-decompressed layout map - 9 frames of 40x28 tiles)
        byte[] floorMap = dataLoader.getLayoutEnigmaMap();
        renderer.setFloorMapData(floorMap);

        // Load BG map (Enigma-decompressed starfield - 64x32 tiles)
        byte[] bgMap = dataLoader.getBgEnigmaMap();
        renderer.setBgMapData(bgMap);

        // Load player mapping + DPLC data for sprite rendering.
        // Mapping/DPLC data immediately follows the uncompressed art in ROM.
        long playerMapAddr;
        switch (playerCharacter) {
            case KNUCKLES:
                playerMapAddr = Sonic3kSpecialStageRomOffsets.ART_UNC_KNUCKLES
                        + Sonic3kSpecialStageRomOffsets.ART_UNC_KNUCKLES_SIZE;
                break;
            case TAILS_ALONE:
                playerMapAddr = Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS
                        + Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_SIZE;
                break;
            default:
                playerMapAddr = Sonic3kSpecialStageRomOffsets.ART_UNC_SONIC
                        + Sonic3kSpecialStageRomOffsets.ART_UNC_SONIC_SIZE;
                break;
        }
        byte[] playerMapData = rom.readBytes(playerMapAddr, 400);
        renderer.setSonicMappingData(playerMapData, playerMapData);

        // Load banner mapping data (Map_GetBlueSpheres at ROM 0x8F5E, ~76 bytes)
        byte[] bannerMapData = rom.readBytes(0x8F5E, 76);
        renderer.setBannerMappingData(bannerMapData);

        // Load HUD number map and template
        byte[] hudNumMap = dataLoader.getHudNumberMap();
        renderer.setHudNumberMap(hudNumMap);
        byte[] hudTemplate = dataLoader.getHudDisplayMap();
        renderer.setHudTemplate(hudTemplate);

        // Load emerald art (KosinskiM compressed)
        Pattern[] emeraldPatterns = superEmeraldMode
                ? dataLoader.getSuperEmeraldArt()
                : dataLoader.getChaosEmeraldArt();
        renderer.setEmeraldPatternBase(nextBase);
        for (int i = 0; i < emeraldPatterns.length; i++) {
            gm.cachePatternTexture(emeraldPatterns[i], nextBase + i);
        }
        nextBase += emeraldPatterns.length;

        // Load Tails sidekick body art (only for Sonic & Tails mode)
        if (tailsEnabled) {
            byte[] tailsArtData = dataLoader.getTailsArt();
            int tailsPatternCount = tailsArtData.length / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] tailsPatterns = new Pattern[tailsPatternCount];
            for (int i = 0; i < tailsPatternCount; i++) {
                tailsPatterns[i] = new Pattern();
                byte[] sub = new byte[Pattern.PATTERN_SIZE_IN_ROM];
                System.arraycopy(tailsArtData, i * Pattern.PATTERN_SIZE_IN_ROM,
                        sub, 0, Pattern.PATTERN_SIZE_IN_ROM);
                tailsPatterns[i].fromSegaFormat(sub);
            }
            renderer.setTailsPatternBase(nextBase);
            for (int i = 0; i < tailsPatterns.length; i++) {
                gm.cachePatternTexture(tailsPatterns[i], nextBase + i);
            }
            nextBase += tailsPatterns.length;

            // Load Tails sidekick mapping data (follows art in Lockon ROM)
            long tailsMapAddr = Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS
                    + Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_SIZE;
            byte[] tailsMapData = rom.readBytes(tailsMapAddr, 400);
            renderer.setTailsMappingData(tailsMapData, tailsMapData);
        }

        // Load tails appendage art (needed for sidekick Tails or Tails as main player)
        if (tailsEnabled || playerCharacter == PlayerCharacter.TAILS_ALONE) {
            byte[] tailsTailsArtData = dataLoader.getTailsTailsArt();
            int ttPatternCount = tailsTailsArtData.length / Pattern.PATTERN_SIZE_IN_ROM;
            Pattern[] ttPatterns = new Pattern[ttPatternCount];
            for (int i = 0; i < ttPatternCount; i++) {
                ttPatterns[i] = new Pattern();
                byte[] sub = new byte[Pattern.PATTERN_SIZE_IN_ROM];
                System.arraycopy(tailsTailsArtData, i * Pattern.PATTERN_SIZE_IN_ROM,
                        sub, 0, Pattern.PATTERN_SIZE_IN_ROM);
                ttPatterns[i].fromSegaFormat(sub);
            }
            renderer.setTailsTailsPatternBase(nextBase);
            for (int i = 0; i < ttPatterns.length; i++) {
                gm.cachePatternTexture(ttPatterns[i], nextBase + i);
            }
            nextBase += ttPatterns.length;

            // Load tails appendage mapping (follows tails tails art in Lockon ROM)
            long ttMapAddr = Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_TAILS
                    + Sonic3kSpecialStageRomOffsets.ART_UNC_TAILS_TAILS_SIZE;
            byte[] ttMapData = rom.readBytes(ttMapAddr, 300);
            renderer.setTailsTailsMappingData(ttMapData);
        }
        // Load scalar table
        byte[] scalars = dataLoader.getScalarTable();
        // Scalars are used by the 3D projection system

        // Load and apply palettes to the graphics manager
        // In the combined S3K ROM, SK_alone_flag=0 and SK_special_stage_flag=0
        // for the first playthrough (chaos emeralds), so use S3 palettes (skMode=false).
        // skMode=true would be for S&K standalone or super emerald stages.
        // Use SK palettes for Super Emerald stages and stages 8+ (S&K layout set)
        boolean skPalettes = superEmeraldMode || currentStage >= 8;
        palette.initialize(dataLoader, currentStage & 7,
                playerCharacter == PlayerCharacter.KNUCKLES, skPalettes);
        com.openggf.level.Palette[] palLines = palette.getPalettes();
        Sonic3kSpecialStagePaletteUploader.cacheAll(gm, palLines);
        LOGGER.fine("Cached " + palLines.length + " SS palette lines");

        // Mark art as loaded
        renderer.setArtLoaded(true);

        LOGGER.info("S3K SS art loaded: " + (nextBase - SS_PATTERN_BASE) + " total patterns");
    }

    /**
     * Load player art based on current character.
     */
    private Pattern[] loadPlayerArt() throws IOException {
        byte[] artData;
        switch (playerCharacter) {
            case KNUCKLES:
                artData = dataLoader.getKnucklesArt();
                break;
            case TAILS_ALONE:
                artData = dataLoader.getTailsArt();
                break;
            default:
                artData = dataLoader.getSonicArt();
                break;
        }
        // Convert raw uncompressed art to Pattern array
        int patternCount = artData.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = new byte[Pattern.PATTERN_SIZE_IN_ROM];
            System.arraycopy(artData, i * Pattern.PATTERN_SIZE_IN_ROM,
                    subArray, 0, Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }
        return patterns;
    }

    /**
     * Consume the entry fade-to-white hold without stepping engine frames.
     * The {@code SpecialStageStartupPolicy.FAST} half of the same split
     * {@code Sonic1SpecialStageProvider} applies to
     * {@code Sonic1SpecialStageManager.advanceToEntryPresentation()}: ordinary
     * interactive entry has no external frame source pacing the ROM's blocking
     * {@code Pal_FadeToWhite} loop, so the hold is retired inside
     * {@code initializeStage}; a BK2-driven (TRACE_ACCURATE) caller drives the
     * 22 real frames itself and must not have them skipped.
     */
    public void advanceThroughEntryFade() {
        preBootFadeHoldFrames = 0;
    }

    /**
     * Returns whether {@code SpecialStage} has reached its reveal boundary: the
     * entry {@code Pal_FadeToWhite} has elapsed, so {@code mus_SpecialStage}
     * (sonic3k.asm:10731) and {@code Pal_FadeFromWhite} may start while the
     * post-boot hold still keeps the stage frozen.
     */
    public boolean isEntryPresentationReady() {
        return initialized && preBootFadeHoldFrames <= 0;
    }

    /** Returns whether the entry {@code Pal_FadeToWhite} is still running. */
    public boolean isEntryFadeToWhiteActive() {
        return initialized && preBootFadeHoldFrames > 0;
    }

    /**
     * Update the special stage by one frame.
     * ROM: loc_84C2 (sonic3k.asm:10737) - main loop
     */
    public void update() {
        if (!initialized || finished) {
            return;
        }

        // Entry fade-to-white hold (see preBootFadeHoldFrames javadoc): the
        // ROM has not reached SSInit yet, so no special-stage state exists on
        // these frames -- not even the stepped-frame counter.
        if (preBootFadeHoldFrames > 0) {
            preBootFadeHoldFrames--;
            return;
        }

        frameCounter++;

        // Entry fade hold (see postBootFadeHoldFrames javadoc): the ROM does
        // not run the special-stage object's per-frame routine at all for a
        // stretch of real, input-polled frames right after entry
        // (Pal_FadeFromWhite, sonic3k.asm:10735, plus loc_84C2's own leading
        // Wait_VSync, sonic3k.asm:10741, before its first Process_Sprites
        // call at sonic3k.asm:10744). frameCounter above still advances --
        // it is comparator-facing stepped-frame bookkeeping, not a ROM RAM
        // field -- but nothing else in this method (player/tails/collision/
        // banner/HUD/background) may observe these frames.
        if (firstUpdateCall) {
            firstUpdateCall = false;
        } else if (postBootFadeHoldFrames > 0) {
            postBootFadeHoldFrames--;
            return;
        }

        // Banner state machine
        boolean bannerTriggeredAdvance = banner.update();
        if (bannerTriggeredAdvance) {
            if (player.getVelocity() == 0) {
                player.setAdvancing(true);
                player.setStarted(true);
            }
        }

        // Player movement (includes speed timer, input, velocity, position)
        player.update(heldButtons, pressedButtons);

        // Grid collision is the TAIL of the movement routine, not a separate
        // later pass: the ROM's per-frame player routine loc_903E calls
        // sub_9580 (sonic3k.asm:11467) which moves the player and then falls
        // into its own jump gate -- tst.b (Special_stage_jumping).w / bmi.s
        // locret_972C (sonic3k.asm:12074-12075) -- before bsr.s sub_972E
        // (sonic3k.asm:12078), the cell-collision routine. Only AFTERWARDS,
        // at loc_911E (sonic3k.asm:11520-11527), does the jump physics land
        // the player and write 0 to Special_stage_jumping.
        //
        // The ordering is load-bearing on the landing frame. When the jump
        // height goes non-negative, sub_9580 has already run for that frame
        // and still observed jumping = $80, so the ROM SKIPS the cell check
        // and consumes the landed-on sphere one frame later. Running the
        // check after updateJump() instead consumes it on the landing frame
        // itself -- one frame early -- and symmetrically misses the check on
        // the jump-launch frame, where the ROM still runs it because
        // Special_stage_jumping is not set until loc_90EE/loc_911E.
        processCollisionIfEligible();

        player.updateJump(pressedButtons);

        // Update Tails (P2)
        if (tailsEnabled) {
            // Tails AI: replay P1 input with delay, or use P2 controller
            int tailsInput = tailsAI.update(heldButtons, player.getJumping(), p2HeldButtons);

            // Tails jump — uses delayed input from AI (4 frames behind Sonic)
            if ((tailsInput & 0x70) != 0 && tailsJumping == 0) {
                // Check if this is a spring jump or normal jump
                if (tailsAI.shouldAutoSpringJump()) {
                    tailsJumpVelocity = Sonic3kSpecialStageConstants.SPRING_JUMP_VELOCITY;
                    tailsJumping = 0x81;
                    GameServices.audio().playSfx(
                            com.openggf.game.sonic3k.audio.Sonic3kSfx.SPRING.id);
                } else {
                    tailsJumpVelocity = -0x100000; // Normal jump
                    tailsJumping = 0x80;
                    GameServices.audio().playSfx(
                            com.openggf.game.sonic3k.audio.Sonic3kSfx.JUMP.id);
                }
            }
            // Tails jump physics
            if ((tailsJumping & 0x80) != 0) {
                tailsJumpHeight += tailsJumpVelocity;
                if (tailsJumpHeight >= 0) {
                    tailsJumpHeight = 0;
                    tailsJumpVelocity = 0;
                    tailsJumping = 0;
                } else {
                    tailsJumpVelocity += (long) player.getRate() << 4;
                }
            }

            // Tails body animation (same cycle as Sonic)
            tailsAnimTimer = (tailsAnimTimer + (player.getVelocity() >> 5)) & 0xFFFF;
            int frameIdx = (tailsAnimTimer >> 8) & 0xFF;
            if ((frameIdx & 0x80) != 0) frameIdx = (frameIdx + 12) & 0xFF;
            if (frameIdx >= 12) frameIdx = (frameIdx - 12) & 0xFF;
            tailsAnimTimer = (frameIdx << 8) | (tailsAnimTimer & 0xFF);

            int[] animTable = ((tailsJumping & 0x80) != 0)
                    ? Sonic3kSpecialStageConstants.ANIM_JUMP_P2
                    : Sonic3kSpecialStageConstants.ANIM_WALKING;
            if (frameIdx >= animTable.length) frameIdx = animTable.length - 1;
            tailsMappingFrame = animTable[frameIdx];

        }

        // Tails appendage animation — runs for sidekick or Tails as main player
        if (tailsEnabled || playerCharacter == PlayerCharacter.TAILS_ALONE) {
            // ROM: adds 0x2AAA + velocity to 16-bit timer, advances frame on overflow
            tailsTailsAnimTimer = (tailsTailsAnimTimer + 0x2AAA + Math.max(0, player.getVelocity())) & 0xFFFF;
            if (tailsTailsAnimTimer < 0x2AAA + Math.max(0, player.getVelocity())) {
                // Overflow occurred — advance frame
                tailsTailsMappingFrame++;
                if (tailsTailsMappingFrame >= 15) tailsTailsMappingFrame = 1;
            }
        }

        // Update music speed when rate increases.
        // ROM: Change_Music_Tempo writes to zTempoSpeedup in the Z80 driver,
        // which is exactly our SmpsSequencer.speedMultiplier.
        // The ROM value = (0x20 - (rate>>8)) * 2 + 8.
        // Rate 0x1000→40, 0x1400→32, 0x1800→24, 0x2000→8.
        if (player.didRateJustIncrease()) {
            int tempo = player.calculateMusicTempo();
            GameServices.audio().setSpeedMultiplier(tempo);
            musicSpedUp = true;
        }

        // Collision response queue (ring/sphere animations)
        collisionQueue.update(grid, this::onBlueSphereAnimComplete,
                player.getXPos(), player.getYPos());

        // Ring rotation animation (ROM: Animate_SSRings, sonic3k.asm:12723)
        // Cycles through 3 frames (0, 1, 2) every 8 game frames
        ringAnimTimer--;
        if (ringAnimTimer < 0) {
            ringAnimTimer = 7;
            ringAnimFrame++;
            if (ringAnimFrame >= 3) {
                ringAnimFrame = 0;
            }
        }

        // Clear sequence
        if (clearRoutine > 0) {
            updateClearSequence();
        }

        // During exit spin, fade palette to white.
        // ROM: Pal_fade_delay counts down from 2, calls Pal_ToWhite when < 0,
        // then resets to 2. This calls every 3 frames. Each call increments
        // each color channel by one Mega Drive step (3-bit: 7 steps to max).
        // In 0-255 range: step = ceil(255/7) = 37. Full white in ~21 frames.
        if (exitSpinStarted && player.getFadeTimer() > 0) {
            palFadeDelay--;
            if (palFadeDelay < 0 && palette != null) {
                palFadeDelay = 2;
                // ROM Pal_AddColor2: increments only ONE channel per color per call.
                // Checks R first, then G, then B. Only the first non-maxed channel
                // is incremented. One MD step = 255/7 ≈ 37 in 0-255 range.
                int step = 37;
                palette.fadeTowardWhiteOneStep(step);
                Sonic3kSpecialStagePaletteUploader.cacheAll(GameServices.graphics(), palette.getPalettes());
            }
        }

        // Finish stage after the exit spin animation completes.
        // fadeTimer goes 1→0x61 (spinning), then resets to 0 when aligned.
        if (exitSpinStarted && player.getFadeTimer() == 0) {
            finished = true;
            // Reset music speed on exit
            if (musicSpedUp) {
                GameServices.audio().setSpeedMultiplier(1);
            }
        }

        // Update perspective animation frame
        perspective.updateAnimFrame(player);

        // Palette rotation — cycles palette line 3 colors to animate the floor
        // Skip during exit spin so the fade-to-white isn't overwritten
        if (palette != null && !exitSpinStarted) {
            palette.updateRotation(
                    perspective.getAnimFrame(),
                    perspective.getPaletteFrame(),
                    player.getTurning() < 0);
            // Push updated palette line 3 to graphics manager each frame
            Sonic3kSpecialStagePaletteUploader.cacheLine(GameServices.graphics(), palette.getPalette(3), 3);
        }

        // Background scroll
        background.update(player);

        // HUD update
        hud.update(spheresLeft, ringsCollected);
    }

    /**
     * Render the special stage.
     */
    public void draw() {
        if (!initialized) {
            return;
        }

        if (renderer != null) {
            renderer.render(this);
        }
    }

    /**
     * Handle player 1 input.
     */
    public void handleInput(int heldButtons, int pressedButtons) {
        if (!initialized || finished) {
            return;
        }
        this.heldButtons = heldButtons;
        this.pressedButtons = pressedButtons;
    }

    /**
     * Handle player 2 input (Tails).
     */
    public void handlePlayer2Input(int heldButtons, int logicalButtons) {
        this.p2HeldButtons = heldButtons;
    }

    // ==================== Collision Processing ====================

    /**
     * The tail of the ROM's movement routine sub_9580: skip the cell check
     * while airborne or during the clear sequence, otherwise run it.
     * ROM: sonic3k.asm:12074-12078 --
     * {@code tst.b (Special_stage_jumping).w / bmi.s locret_972C /
     * tst.b (Special_stage_clear_routine).w / bne.s locret_972C /
     * bsr.s sub_972E}. {@code bmi} tests bit 7, so both the normal ($80) and
     * spring ($81) jump states suppress it.
     *
     * <p>Must be called from the sub_9580 position in the frame -- before the
     * jump physics at loc_911E clears {@code Special_stage_jumping} -- see the
     * call site in {@link #update()}.
     *
     * <p>The cell check is also the LAST thing sub_9580 does, so every earlier
     * exit from that routine suppresses it for the frame -- the fade-out
     * rotation's {@code rts} (sonic3k.asm:11922), the mid-turn
     * {@code bne.w locret_972C} (sonic3k.asm:11949), and the bumper's
     * different-cell unlock {@code rts} (loc_96CE, sonic3k.asm:12039).
     * {@link Sonic3kSpecialStagePlayer#reachedCellCheck()} reports whether the
     * routine got that far.
     */
    private void processCollisionIfEligible() {
        if (!player.reachedCellCheck()) {
            return;
        }
        if ((player.getJumping() & 0x80) == 0 && clearRoutine == 0 && !exitSpinStarted) {
            processCollision();
        }
    }

    /**
     * Process collision at the player's current position.
     * ROM: sub_972E (sonic3k.asm:12088)
     */
    private void processCollision() {
        var result = collision.checkCollision(grid, player);

        switch (result.result) {
            case NONE:
                break;

            case BLUE_SPHERE:
                collisionQueue.addBlueSphere(result.gridIndex);
                // loc_97BE requests the sound even when Find_SStageCollisionResponseSlot
                // failed; animation admission does not gate audio (sonic3k.asm:12131-12142).
                GameServices.audio().playSfx(Sonic3kSfx.BLUE_SPHERE.id);
                break;

            case RED_SPHERE:
                if (!exitSpinStarted) {
                    player.setFailed(true);
                    player.setFadeTimer(1);
                    exitSpinStarted = true;
                    emeraldCollected = false;
                    GameServices.audio().playSfx(Sonic3kSfx.GOAL.id);
                }
                break;

            case BUMPER:
                player.activateBumperLock(result.gridIndex);
                GameServices.audio().playSfx(Sonic3kSfx.BUMPER.id);
                break;

            case RING:
                if (collisionQueue.addRing(result.gridIndex)) {
                    collectRing(result.gridIndex);
                }
                break;

            case SPRING:
                player.springJump();
                GameServices.audio().playSfx(Sonic3kSfx.SPRING.id);
                break;

            case EMERALD:
                collectEmerald();
                break;
        }
    }

    /**
     * Collect a ring at the given grid index.
     * ROM: loc_9822 (sonic3k.asm:12173)
     */
    private void collectRing(int gridIndex) {
        // Ring queue entry already added by caller

        // Track remaining rings from sphere conversion
        if (ringsLeft > 0) {
            ringsLeft--;
            if (ringsLeft == 0) {
                // All rings collected — play PERFECT SFX and show PERFECT banner
                GameServices.audio().playSfx(Sonic3kSfx.PERFECT.id);
                banner.triggerReEntry(); // Shows "PERFECT" text
            }
        }

        ringsCollected++;

        // Extra life thresholds
        if (ringsCollected == EXTRA_LIFE_THRESHOLD_CONTINUE) {
            GameServices.audio().playSfx(Sonic3kSfx.CONTINUE.id);
        } else if (ringsCollected == EXTRA_LIFE_THRESHOLD_1
                || ringsCollected == EXTRA_LIFE_THRESHOLD_2) {
            GameServices.audio().playSfx(Sonic3kSfx.RING_LOSS.id);
        } else {
            // ROM: loc_984C seeds sfx_RingRight before the Blue_spheres_stage_flag
            // threshold branch and always calls Play_SFX with that ID
            // (skdisasm/sonic3k.asm:12189-12222), exactly as GiveRing does in a
            // level (sonic3k.asm:35456). The left/right alternation is the Z80
            // driver's, keyed on that raw id (Z80 Sound Driver.asm:1919-1925),
            // so the audio manager alternates this request the same way.
            GameServices.audio().playSfx(Sonic3kSfx.RING_RIGHT.id);
        }
    }

    /**
     * Collect the emerald (player walked into emerald cell).
     * ROM: loc_9CE6 (sonic3k.asm:12664)
     */
    private void collectEmerald() {
        // Mark emerald collected in game state
        GameStateManager gameState = GameServices.gameState();
        if (currentStage < EMERALD_COUNT) {
            emeraldCollected = true;
            if (superEmeraldMode) {
                gameState.markSuperEmeraldCollected(currentStage);
            } else {
                gameState.markEmeraldCollected(currentStage);
            }
        }

        clearRoutine = 4; // Skip to completion
        player.setFadeTimer(1);
        exitSpinStarted = true;
        // Don't set finished=true here — let the spin animation play first.
        // finished will be set when fadeTimer completes its cycle.
        GameServices.audio().playSfx(Sonic3kSfx.GOAL.id);
    }

    /**
     * Called when a blue sphere's collection animation completes.
     * Triggers the sphere-to-ring conversion algorithm.
     */
    private void onBlueSphereAnimComplete(int gridIndex) {
        // Decrement sphere count
        spheresLeft--;

        // Check for stage clear
        if (spheresLeft <= 0) {
            spheresLeft = 0;
            clearRoutine = 1;
            player.setClearRoutineActive(true);
        }

        // Attempt sphere-to-ring conversion
        var convResult = ringConverter.convert(grid, gridIndex);
        if (convResult.converted) {
            spheresLeft -= convResult.blueSpheresConverted;
            if (spheresLeft < 0) spheresLeft = 0;

            // Check for stage clear again after conversion
            if (spheresLeft == 0 && clearRoutine == 0) {
                clearRoutine = 1;
                player.setClearRoutineActive(true);
            }

            GameServices.audio().playSfx(Sonic3kSfx.RING_LOSS.id);
        }
    }

    // Banner is now handled by Sonic3kSpecialStageBanner class

    // ==================== Clear Sequence ====================

    /**
     * Update the stage clear sequence.
     * ROM: sub_9B62 (sonic3k.asm:12530)
     */
    private void updateClearSequence() {
        switch (clearRoutine) {
            case 1: // Fly-away timer
                updateClearFlyaway();
                break;
            case 2: // Wait for emerald art loading
                updateClearEmeraldLoad();
                break;
            case 3: // Player approaches emerald
                updateClearEmeraldApproach();
                break;
        }
    }

    /**
     * Clear routine state 1: fly-away animation.
     * ROM: loc_9B7C (sonic3k.asm:12530)
     */
    private void updateClearFlyaway() {
        if (clearTimer >= CLEAR_TIMER_COMPLETE) {
            // Advance to next state
            clearRoutine = 2;
            // Clear the grid and place emerald
            placeEmerald();
            return;
        }

        clearTimer += 2;
        if (clearTimer == 2) {
            // ROM: sfx_AllSpheres (0x66) plays as SFX over the music.
            // Music continues until the chaos emerald jingle replaces it later.
            GameServices.audio().playSfx(Sonic3kSfx.ALL_SPHERES.id);
        }

        // Accelerate timer after thresholds
        if (clearTimer >= CLEAR_TIMER_ACCEL_1) {
            clearTimer++;
        }
        if (clearTimer >= CLEAR_TIMER_ACCEL_2) {
            clearTimer++;
        }
    }

    /**
     * Place the chaos emerald on the grid ahead of the player.
     * ROM: loc_9BA6 (sonic3k.asm:12553)
     */
    private void placeEmerald() {
        grid.clearAll();

        // Calculate emerald position: ahead of player
        int sin = Sonic3kSpecialStagePlayer.getSine(player.getAngle());
        int cos = Sonic3kSpecialStagePlayer.getCosine(player.getAngle());
        int emeraldX = player.getXPos() - (sin * 8);
        int emeraldY = player.getYPos() - (cos * 8);

        int emeraldIndex = Sonic3kSpecialStageGrid.positionToIndex(emeraldX, emeraldY);
        grid.setCellByIndex(emeraldIndex, superEmeraldMode ? CELL_SUPER_EMERALD : CELL_CHAOS_EMERALD);
        emeraldInteractIndex = emeraldIndex;

        player.setVelocity(CLEAR_VELOCITY);
        emeraldTimer = EMERALD_TIMER_INIT;

        queueEmeraldArtModule();
    }

    /**
     * ROM {@code loc_9C28}-{@code loc_9C52} (sonic3k.asm:12595-12610) picks the
     * Chaos or Super Emerald KosM archive on {@code SK_special_stage_flag},
     * loads {@code tiles_to_bytes(ArtTile_SStage_Emerald)} into d2 and tail-jumps
     * into {@code Queue_Kos_Module} (2668). With the module FIFO empty that call
     * runs {@code Process_Kos_Module_Queue_Init} straight away (2671, 2694),
     * publishing {@code Kos_modules_left} for {@code loc_9C5C} to poll.
     */
    private void queueEmeraldArtModule() {
        S3kKosModuleQueue queue = emeraldArtQueue();
        if (queue == null) {
            // No S3K runtime art coordinator is installed (non-S3K harnesses and
            // unit fixtures for the SS grid/renderer). Nothing was submitted, so
            // Kos_modules_left reads zero and loc_9C5C releases immediately.
            emeraldArtWork = null;
            emeraldArtWorkOrdinal = -1;
            return;
        }
        try {
            emeraldArtWork = queue.queue(
                    GameServices.rom().getRom(),
                    (int) (superEmeraldMode
                            ? Sonic3kSpecialStageRomOffsets.ART_KOSM_SUPER_EMERALD
                            : Sonic3kSpecialStageRomOffsets.ART_KOSM_CHAOS_EMERALD),
                    ART_TILE_EMERALD);
            emeraldArtWorkOrdinal = emeraldArtWork.ordinal();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue the special-stage emerald art module", e);
        }
    }

    /**
     * Retires the emerald archive once {@code Kos_modules_left} has reached
     * zero. ROM does the equivalent inside {@code Process_Kos_Module_Queue}
     * itself: the last module's DMA (sonic3k.asm:2758-2768) shifts the archive
     * out of {@code Kos_module_queue} (2778-2788). The engine already installed
     * the decompressed emerald patterns when the stage was built (see
     * {@code initialize}'s {@code getChaosEmeraldArt}/{@code getSuperEmeraldArt}
     * upload), so claiming here releases the ledger slot rather than delivering
     * new art.
     */
    private void claimEmeraldArtModule(S3kKosModuleQueue queue) {
        if (queue == null || emeraldArtWorkOrdinal < 0) {
            return;
        }
        if (emeraldArtWork == null) {
            emeraldArtWork = GameServices.hardwareTiming()
                    .pendingHandle(HardwareWorkKind.KOS_MODULE_QUEUE,
                            emeraldArtWorkOrdinal)
                    .orElse(null);
        }
        if (emeraldArtWork != null && queue.isReady(emeraldArtWork)) {
            queue.claim(emeraldArtWork);
        }
        emeraldArtWork = null;
        emeraldArtWorkOrdinal = -1;
    }

    private S3kKosModuleQueue emeraldArtQueue() {
        if (!(GameServices.runtimeArtCoordinatorOrNull()
                instanceof S3kRuntimeArtCoordinator coordinator)) {
            return null;
        }
        return coordinator.moduleQueue();
    }

    /**
     * Clear routine state 2: wait for emerald art to load.
     * ROM: loc_9C5C (sonic3k.asm:12613-12620) -- {@code tst.b
     * Kos_modules_left; bne locret_9C7E} polls every frame and does nothing
     * else (clear_timer and emerald_timer both untouched) until the queued
     * Kosinski module finishes.
     */
    private void updateClearEmeraldLoad() {
        S3kKosModuleQueue queue = emeraldArtQueue();
        if (queue != null && queue.modulesLeft()) {
            return;
        }
        claimEmeraldArtModule(queue);
        clearTimer = 0;
        emeraldTimer--;
        if (emeraldTimer <= 0) {
            clearRoutine = 3;
            GameServices.audio().playMusic(GameMusic.EMERALD);
        }
    }

    /**
     * Clear routine state 3: player approaches and collects emerald.
     * Handled by normal collision detection (EMERALD case).
     * ROM: loc_9C80 (sonic3k.asm:12629)
     */
    private void updateClearEmeraldApproach() {
        // Collision detection handles emerald collection
        // Check if player has reached the emerald
        int playerIndex = Sonic3kSpecialStageGrid.positionToIndex(
                player.getXPos(), player.getYPos());
        if (playerIndex == emeraldInteractIndex) {
            int combined = player.getXPos() | player.getYPos();
            if ((combined & CELL_ALIGN_MASK) == 0) {
                collectEmerald();
            }
        }
    }

    // ==================== Lifecycle ====================

    Sonic3kSpecialStageSnapshot captureRewindSnapshot() {
        if (!initialized) {
            throw new IllegalStateException("Cannot capture S3K special-stage rewind state before initialization");
        }
        GameStateManager gameState = GameServices.gameStateOrNull();
        return new Sonic3kSpecialStageSnapshot(
                currentStage,
                initialized,
                finished,
                emeraldCollected,
                superEmeraldMode,
                ringsCollected,
                spheresLeft,
                ringsLeft,
                frameCounter,
                heldButtons,
                pressedButtons,
                p2HeldButtons,
                clearRoutine,
                clearTimer,
                emeraldTimer,
                emeraldInteractIndex,
                emeraldArtWorkOrdinal,
                exitSpinStarted,
                palFadeDelay,
                musicSpedUp,
                ringAnimTimer,
                ringAnimFrame,
                bannerPhase,
                bannerTimer,
                bannerOffset,
                tailsAnimTimer,
                tailsMappingFrame,
                tailsTailsAnimTimer,
                tailsTailsMappingFrame,
                tailsJumping,
                tailsJumpHeight,
                tailsJumpVelocity,
                tailsEnabled,
                playerCharacter,
                spriteDebugMode,
                useSkLayouts,
                firstUpdateCall,
                preBootFadeHoldFrames,
                postBootFadeHoldFrames,
                gameState != null ? gameState.capture() : null,
                grid.captureRewindSnapshot(),
                player.captureRewindSnapshot(),
                tailsAI.captureRewindSnapshot(),
                collisionQueue.captureRewindSnapshot(),
                ringConverter.captureRewindSnapshot(),
                perspective.captureRewindSnapshot(),
                background.captureRewindSnapshot(),
                hud.captureRewindSnapshot(),
                banner.captureRewindSnapshot(),
                palette != null ? palette.captureRewindSnapshot() : null);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot snapshot) {
        if (!initialized || snapshot == null || !snapshot.initialized()) {
            throw new IllegalStateException("Cannot restore S3K special-stage rewind state before initialization");
        }
        currentStage = snapshot.currentStage();
        initialized = snapshot.initialized();
        finished = snapshot.finished();
        emeraldCollected = snapshot.emeraldCollected();
        superEmeraldMode = snapshot.superEmeraldMode();
        ringsCollected = snapshot.ringsCollected();
        spheresLeft = snapshot.spheresLeft();
        ringsLeft = snapshot.ringsLeft();
        frameCounter = snapshot.frameCounter();
        heldButtons = snapshot.heldButtons();
        pressedButtons = snapshot.pressedButtons();
        p2HeldButtons = snapshot.p2HeldButtons();
        clearRoutine = snapshot.clearRoutine();
        clearTimer = snapshot.clearTimer();
        emeraldTimer = snapshot.emeraldTimer();
        emeraldInteractIndex = snapshot.emeraldInteractIndex();
        emeraldArtWorkOrdinal = snapshot.emeraldArtWorkOrdinal();
        emeraldArtWork = null;
        exitSpinStarted = snapshot.exitSpinStarted();
        palFadeDelay = snapshot.palFadeDelay();
        musicSpedUp = snapshot.musicSpedUp();
        ringAnimTimer = snapshot.ringAnimTimer();
        ringAnimFrame = snapshot.ringAnimFrame();
        bannerPhase = snapshot.bannerPhase();
        bannerTimer = snapshot.bannerTimer();
        bannerOffset = snapshot.bannerOffset();
        tailsAnimTimer = snapshot.tailsAnimTimer();
        tailsMappingFrame = snapshot.tailsMappingFrame();
        tailsTailsAnimTimer = snapshot.tailsTailsAnimTimer();
        tailsTailsMappingFrame = snapshot.tailsTailsMappingFrame();
        tailsJumping = snapshot.tailsJumping();
        tailsJumpHeight = snapshot.tailsJumpHeight();
        tailsJumpVelocity = snapshot.tailsJumpVelocity();
        tailsEnabled = snapshot.tailsEnabled();
        playerCharacter = snapshot.playerCharacter();
        spriteDebugMode = snapshot.spriteDebugMode();
        useSkLayouts = snapshot.useSkLayouts();
        firstUpdateCall = snapshot.firstUpdateCall();
        preBootFadeHoldFrames = snapshot.preBootFadeHoldFrames();
        postBootFadeHoldFrames = snapshot.postBootFadeHoldFrames();

        GameStateManager gameState = GameServices.gameStateOrNull();
        if (gameState != null && snapshot.gameState() != null) {
            gameState.restore(snapshot.gameState());
        }
        grid.restoreRewindSnapshot(snapshot.grid());
        player.restoreRewindSnapshot(snapshot.player());
        tailsAI.restoreRewindSnapshot(snapshot.tailsAi());
        collisionQueue.restoreRewindSnapshot(snapshot.collisionQueue());
        ringConverter.restoreRewindSnapshot(snapshot.ringConverter());
        perspective.restoreRewindSnapshot(snapshot.perspective());
        background.restoreRewindSnapshot(snapshot.background());
        hud.restoreRewindSnapshot(snapshot.hud());
        banner.restoreRewindSnapshot(snapshot.banner());
        if (palette != null && snapshot.palette() != null) {
            palette.restoreRewindSnapshot(snapshot.palette());
            recacheRestoredPaletteForRewind();
        }
        restoreAudioSpeedForRewind();
    }

    private void recacheRestoredPaletteForRewind() {
        if (palette == null) {
            return;
        }
        try {
            Sonic3kSpecialStagePaletteUploader.cacheAll(GameServices.graphics(), palette.getPalettes());
        } catch (IllegalStateException ignored) {
            // Headless unit tests can exercise snapshot restore without graphics services.
        }
    }

    private void restoreAudioSpeedForRewind() {
        try {
            GameServices.audio().setSpeedMultiplier(musicSpedUp ? player.calculateMusicTempo() : 1);
        } catch (IllegalStateException ignored) {
            // Headless unit tests can exercise snapshot restore without audio services.
        }
    }

    public boolean isFinished() {
        return finished;
    }

    /**
     * Captures a read-only per-frame comparison snapshot of this manager and its player
     * for trace replay comparison (multi-stage trace run spec addition #3). Pure read,
     * no mutators, no caching. See {@link Sonic3kSpecialStageComparisonState}.
     */
    public Sonic3kSpecialStageComparisonState captureComparisonState() {
        return new Sonic3kSpecialStageComparisonState(
                player.getXPos(),
                player.getYPos(),
                player.getAngle(),
                player.getVelocity(),
                player.getTurning(),
                player.getJumping(),
                player.getFadeTimer(),
                player.isStarted(),
                spheresLeft,
                ringsCollected,
                ringsLeft,
                frameCounter,
                clearRoutine,
                clearTimer,
                finished,
                emeraldCollected);
    }

    public void reset() {
        if (renderer != null) {
            renderer.resetStageGeometryCache();
        }
        initialized = false;
        finished = false;
        emeraldCollected = false;
        superEmeraldMode = false;
        ringsCollected = 0;
        spheresLeft = 0;
        currentStage = 0;
        frameCounter = 0;
        clearRoutine = 0;
        clearTimer = 0;
        emeraldTimer = 0;
        ringsLeft = 0;
        bannerPhase = 0;
        bannerTimer = 0;
        bannerOffset = 0;
        heldButtons = 0;
        pressedButtons = 0;
        collisionQueue.clear();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public boolean hasEmeraldCollected() {
        return emeraldCollected;
    }

    public void setEmeraldCollected(boolean collected) {
        this.emeraldCollected = collected;
    }

    public int getRingsCollected() {
        return ringsCollected;
    }

    public int getSpheresLeft() {
        return spheresLeft;
    }

    public int getRingsLeft() {
        return ringsLeft;
    }

    // ==================== Accessors for Renderer ====================

    public Sonic3kSpecialStageGrid getGrid() {
        return grid;
    }

    public Sonic3kSpecialStageBackground getBackground() {
        return background;
    }

    public Sonic3kSpecialStageBanner getBanner() {
        return banner;
    }

    public Sonic3kSpecialStagePlayer getPlayer() {
        return player;
    }

    public int getBannerOffset() {
        return bannerOffset;
    }

    public int getBannerPhase() {
        return bannerPhase;
    }

    public int getClearTimer() {
        return clearTimer;
    }

    public int getClearRoutine() {
        return clearRoutine;
    }

    public int getFrameCounter() {
        return frameCounter;
    }

    public int getRingAnimFrame() {
        return ringAnimFrame;
    }

    public int getTailsMappingFrame() {
        return tailsMappingFrame;
    }

    public boolean isTailsEnabled() {
        return tailsEnabled;
    }

    public PlayerCharacter getPlayerCharacter() {
        return playerCharacter;
    }

    private PlayerCharacter resolvePlayerCharacter() {
        try {
            return S3kRuntimeStates.resolvePlayerCharacter(
                    GameServices.zoneRuntimeRegistry(),
                    GameServices.configuration());
        } catch (Exception e) {
            LOGGER.fine("Falling back to Sonic & Tails player character: " + e.getMessage());
            return PlayerCharacter.SONIC_AND_TAILS;
        }
    }

    public long getTailsJumpHeight() {
        return tailsJumpHeight;
    }

    public int getTailsTailsMappingFrame() {
        return tailsTailsMappingFrame;
    }

    // ==================== Debug Methods ====================

    /** Whether we're using the SK layout set (stages 8-15) vs S3 (0-7). */
    private boolean useSkLayouts = false;

    /**
     * Debug: advance to the next stage within the current layout set.
     * X key in special stage mode.
     */
    public void debugNextStage() {
        int nextStage;
        if (useSkLayouts) {
            nextStage = ((currentStage - 8 + 1) % 8) + 8;
        } else {
            nextStage = (currentStage + 1) % 8;
        }
        LOGGER.info("Debug: switching to stage " + nextStage
                + (useSkLayouts ? " (SK)" : " (S3)"));
        try {
            reset();
            initialize(nextStage);
        } catch (java.io.IOException e) {
            LOGGER.severe("Failed to load stage " + nextStage + ": " + e.getMessage());
        }
    }

    /**
     * Debug: toggle between S3 and SK layout sets.
     * Z key in special stage mode.
     */
    public void debugToggleLayoutSet() {
        useSkLayouts = !useSkLayouts;
        int newStage = useSkLayouts ? 8 : 0;
        LOGGER.info("Debug: switching to " + (useSkLayouts ? "SK" : "S3")
                + " layout set, stage " + newStage);
        try {
            reset();
            initialize(newStage);
        } catch (java.io.IOException e) {
            LOGGER.severe("Failed to load stage " + newStage + ": " + e.getMessage());
        }
    }

    public boolean isSpriteDebugMode() {
        return spriteDebugMode;
    }

    public void toggleSpriteDebugMode() {
        spriteDebugMode = !spriteDebugMode;
    }

    public void cyclePlaneDebugMode() {
        // TODO Phase 4
    }

    public com.openggf.game.SpecialStageDebugProvider getDebugProvider() {
        return null;
    }

    public boolean isAlignmentTestMode() {
        return false;
    }

    public void toggleAlignmentTestMode() {}

    public void adjustAlignmentOffset(int delta) {}

    public void adjustAlignmentSpeed(double delta) {}

    public void toggleAlignmentStepMode() {}

    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {}

    public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {}

    public void setLagCompensation(double factor) {}
}
