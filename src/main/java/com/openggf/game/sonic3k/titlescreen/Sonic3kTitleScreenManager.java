package com.openggf.game.sonic3k.titlescreen;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameServices;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kSmpsConstants;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeSimulationMode;
import com.openggf.game.timing.RomWorkBudgetScheduler;
import java.io.IOException;
import com.openggf.audio.AudioManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.glClearColor;

/**
 * Main state machine for the Sonic 3&amp;K title screen.
 *
 * <p>Implements {@link TitleScreenProvider} with a multi-phase animation flow:
 * <ol>
 *   <li><b>SEGA phase</b> (~3s): Shows first animation frame, fades from black, plays SEGA sound</li>
 *   <li><b>Palette transition</b> (~8 frames): Pal_Title gradually transitions colors to black</li>
 *   <li><b>Sonic animation</b> (12 frames over ~3.5s): Full-screen plane animation with
 *       per-frame art/palette/mapping changes</li>
 *   <li><b>White flash</b> (~8 frames): Flash screen white, load final scene</li>
 *   <li><b>Interactive menu</b>: Banner bounces in, sprites animate, menu selection</li>
 * </ol>
 *
 * <p>During the animation phases the entire screen is a single 40x28 nametable that changes
 * completely each animation frame. After the animation completes, the screen switches to a
 * two-plane setup (Plane A foreground + Plane B background) with overlay sprites for the
 * banner, "&amp; KNUCKLES", menu text, copyright, Sonic finger/wink, and Tails plane.
 *
 * <p>Singleton pattern, following the same approach as S2's {@code TitleScreenManager}.
 */
public class Sonic3kTitleScreenManager implements TitleScreenProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kTitleScreenManager.class.getName());

    private static Sonic3kTitleScreenManager instance;
    private Runnable exitToLevelHandler = () -> {};

    private final SonicConfigurationService configService = GameServices.configuration();
    private final Sonic3kTitleScreenDataLoader dataLoader = new Sonic3kTitleScreenDataLoader();
    private final PatternDesc reusableDesc = new PatternDesc();

    private State state = State.INACTIVE;

    // Screen dimensions
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    // Nametable dimensions
    private static final int MAP_WIDTH = 40;
    private static final int MAP_HEIGHT = 28;

    // -----------------------------------------------------------------------
    // Widescreen helpers
    // -----------------------------------------------------------------------

    /**
     * Returns the current projection viewport width in game pixels.
     * At native 320 this equals SCREEN_WIDTH exactly.
     */
    private int viewportWidth() {
        try {
            int w = GameServices.graphics().getProjectionWidth();
            return w > 0 ? w : SCREEN_WIDTH;
        } catch (Exception ignored) {
            return SCREEN_WIDTH;
        }
    }

    /**
     * Horizontal offset that shifts the native-320 content block to the centre
     * of the current viewport.  Zero at native (byte-identical); positive at
     * wider resolutions.
     *
     * <p>Package-visible for unit tests.
     */
    int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    // -----------------------------------------------------------------------
    // Internal phase state machine
    // -----------------------------------------------------------------------

    /**
     * Internal phases for the S3K title screen animation sequence.
     * These map to the coarser {@link TitleScreenProvider.State} values.
     */
    private enum Phase {
        /** Fade from black, show first animation frame. */
        SEGA_FADE_IN,
        /** Hold for ~3 seconds, play SEGA sound. */
        SEGA_HOLD,
        /** Pal_Title transition to black. */
        PAL_TRANSITION,
        /** 12-frame Sonic running animation. */
        SONIC_ANIMATION,
        /** Flash screen white, load final scene. */
        WHITE_FLASH,
        /** Banner bounce + menu selection. */
        INTERACTIVE,
        /** Fade to black before exiting. */
        FADE_OUT,
        /** Fade complete, ready to exit. */
        EXITING
    }

    private Phase phase = Phase.SEGA_FADE_IN;

    // -----------------------------------------------------------------------
    // Timing constants
    // -----------------------------------------------------------------------

    /** Duration of the SEGA fade-in from black (frames). */
    private static final int SEGA_FADE_DURATION = 16;

    /** Duration of the SEGA hold (frames, ~3 seconds at 60fps). */
    private static final int SEGA_HOLD_DURATION = 180;

    /**
     * Number of palette transition steps from Pal_Title.
     * Each step writes 14 bytes (7 colors) to palette line 0 colors 0-6.
     * The data is 112 bytes = 8 blocks of 14 bytes.
     */
    private static final int PAL_TRANSITION_STEPS = 8;

    /** Bytes per palette transition step (7 colors × 2 bytes each). */
    private static final int PAL_TRANSITION_BYTES_PER_STEP = 14;

    /** Current step in the palette transition. */
    private int palTransitionStep = 0;

    /**
     * Reload value written to {@code Title_anim_delay} by {@code TitleAnim_FlipBuffer}
     * (skdisasm/sonic3k.asm:5746 and :5749, {@code move.b #4-1,(Title_anim_delay).w}).
     *
     * <p>The ROM owns no per-frame duration table for the title Sonic animation.
     * {@code TitleAnim_FlipBuffer} runs as V_int routine 4 once per
     * {@code Wait_TitleS3K} iteration (sonic3k.asm:5533-5536): when the counter
     * reads zero it flips the makeshift double buffer and reloads 3, otherwise it
     * decrements (loc_43AC, sonic3k.asm:5771-5772).
     * {@code Iterate_TitleSonicFrame} then advances to the next frame only on the
     * iteration where the counter reads 1 (sonic3k.asm:5793). Reload-3 plus that
     * single-value test gives a uniform four-iteration cadence for every step of
     * {@code SonicFrameIndex}, not the varying durations a hardware capture shows.
     * The variation visible on real hardware comes from the synchronous
     * {@code Kos_Decomp} inside {@code TitleSonic_LoadFrame} (sonic3k.asm:5834)
     * overrunning a frame for the larger frames -- a decompression-timing effect,
     * which under the hardware-timing trace contract belongs to the Kosinski
     * pipeline, never to a transcribed duration table.
     */
    private static final int TITLE_ANIM_DELAY_RELOAD = 4 - 1;

    /** Duration of the white flash (frames). */
    private static final int WHITE_FLASH_DURATION = 8;

    /** Duration of the exit fade-to-black (frames, ~21 = standard Mega Drive fade). */
    private static final int EXIT_FADE_DURATION = 21;

    /**
     * SonicFrameIndex table: animation frame indices to advance through.
     * Values 1-0xB, terminated by 0xFF meaning animation complete.
     */
    private static final int[] SONIC_FRAME_INDEX_TABLE = {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 0xA, 0xB, 0xFF
    };

    /** Frame index representing the final static scene (frame D = 0xD = 13). */
    private static final int FINAL_FRAME_INDEX = 0xD;

    /**
     * The one frame whose art the title loop queues ahead of time:
     * {@code loc_4040} runs {@code Queue_Kos} on {@code ArtKos_S3TitleSonic8}
     * into {@code RAM_start} before {@code Wait_TitleS3K} starts
     * (sonic3k.asm:5525-5528), and {@code TitleSonic_LoadFrame} takes the
     * {@code loc_4446} DMA path for it instead of decompressing
     * ({@code cmpi.w #7,d7 / beq.s loc_4446}, sonic3k.asm:5832-5833).
     * {@code Process_Kos_Queue} is resumable across V-ints, so the ROM never
     * stalls on it; the engine only requires it to be ready when frame 7 loads.
     */
    private static final int QUEUED_ART_FRAME = 7;

    /**
     * Frames 8 to B are the only ones {@code TitleSonic_LoadFrame} decompresses
     * synchronously ({@code bcs.s loc_4466} skips frames below 7, whose
     * {@code ArtKos_S3TitleSonic1} art is already in VRAM). The art each frame
     * decodes is the {@code TitleSonic_Frames} entry indexed by the frame
     * number (sonic3k.asm:5823-5827): entry 8 is {@code ArtKos_S3TitleSonic9},
     * 9 is {@code SonicA}, A is {@code SonicB}, B is {@code SonicC}.
     */
    private static final int LAST_SYNCHRONOUS_DECODE_FRAME = 0xB;

    private static int romFrameArtAddress(int frame) {
        return switch (frame) {
            case 7 -> Sonic3kConstants.ART_KOS_TITLE_SONIC8_ADDR;
            case 8 -> Sonic3kConstants.ART_KOS_TITLE_SONIC9_ADDR;
            case 9 -> Sonic3kConstants.ART_KOS_TITLE_SONIC_A_ADDR;
            case 0xA -> Sonic3kConstants.ART_KOS_TITLE_SONIC_B_ADDR;
            case 0xB -> Sonic3kConstants.ART_KOS_TITLE_SONIC_C_ADDR;
            default -> throw new IllegalArgumentException("frame " + frame + " loads no Kosinski art");
        };
    }

    // Kosinski work the ROM title loop performs (Wait_TitleS3K / TitleSonic_LoadFrame).
    private HardwareTimingService titleTiming;
    private S3kKosDecompressionQueue titleKosQueue;
    /** The frame-7 art queued at {@code loc_4040}, until frame 7 consumes it. */
    private HardwareWorkHandle queuedFrameArt;
    /** The synchronous decode of the frame being loaded, while the loop is stalled on it. */
    private HardwareWorkHandle pendingFrameArt;
    /** Title-loop iterations spent inside a synchronous decode (missed V-ints). */
    private int stalledFrames;

    // -----------------------------------------------------------------------
    // Phase timers and animation state
    // -----------------------------------------------------------------------

    private int phaseTimer = 0;
    private int frameCounter = 0;

    /** Current index into SONIC_FRAME_INDEX_TABLE. */
    private int animTableIndex = 0;

    /** Current animation frame index (1-based, 1 through 0xD). */
    private int currentAnimFrame = 1;

    /**
     * The frame whose art and palette are actually in VRAM. While
     * {@code TitleSonic_LoadFrame} is still decompressing the next frame the
     * display keeps showing this one: the new mappings, palette and art only
     * land after the decode (sonic3k.asm:5846-5871), never piecemeal.
     */
    private int displayedAnimFrame = 1;

    /**
     * ROM {@code Title_anim_delay} (sonic3k.constants.asm:953). Reloaded by
     * {@code TitleAnim_FlipBuffer} and decremented once per title-loop iteration;
     * {@code Iterate_TitleSonicFrame} advances the frame when it reads 1.
     */
    private int animFrameTimer = 0;

    /** Whether SEGA sound has been played. */
    private boolean segaSoundPlayed = false;

    /**
     * Whether the SEGA chant has already been stopped, modelling the ROM's
     * "have we passed {@code loc_3FE4} yet". {@code Wait_SegaS3K} is left
     * either by its own timeout or by a Start press, and both exits run the
     * single {@code cmd_StopSEGA} at sonic3k.asm:5498-5500. Every later skip
     * is a Start press inside {@code Wait_TitleS3K}, whose branch to
     * {@code loc_4090} issues no sound command at all (:5541-5546).
     * {@code segaSoundPlayed} cannot answer this: it records that the chant
     * once started, never that it has since been stopped.
     */
    private boolean segaChantStopped = false;

    /** Whether title music has been started. */
    private boolean musicPlaying = false;

    // -----------------------------------------------------------------------
    // Banner bounce physics (from disassembly Obj_TitleBanner)
    // -----------------------------------------------------------------------

    /**
     * Banner position in 16.16 fixed point (32-bit signed).
     * Initial value 0xFFA00000 = -96.0 in 16.16.
     */
    private int bannerPos32 = 0xFFA00000;

    /** Banner velocity (signed 16-bit, treated as 8.8 fixed point via <<8 shift). */
    private short bannerVel = 0x0400;

    /** Whether the banner has settled to its rest position. */
    private boolean bannerSettled = false;

    // -----------------------------------------------------------------------
    // V_scroll for interactive phase
    // -----------------------------------------------------------------------

    /** Vertical scroll value (increments to 16 after banner settles). */
    private int vScroll = 0;

    /** Target vScroll value. */
    private static final int VSCROLL_TARGET = 16;

    // -----------------------------------------------------------------------
    // Menu selection
    // -----------------------------------------------------------------------

    /** Menu item: 0 = "1 PLAYER", 1 = "COMPETITION". */
    private int menuSelection = 0;

    // -----------------------------------------------------------------------
    // Sprite state (lightweight, same pattern as S2 AnimatedSprite)
    // -----------------------------------------------------------------------

    private final AnimatedSprite bannerSprite = new AnimatedSprite();
    private final AnimatedSprite andKnucklesSprite = new AnimatedSprite();
    private final AnimatedSprite selectionSprite = new AnimatedSprite();
    private final AnimatedSprite copyrightSprite = new AnimatedSprite();
    private final AnimatedSprite tmSprite = new AnimatedSprite();
    private final AnimatedSprite sonicFingerSprite = new AnimatedSprite();
    private final AnimatedSprite sonicWinkSprite = new AnimatedSprite();
    private final AnimatedSprite tailsPlaneSprite = new AnimatedSprite();

    // ----- Sonic Finger animation (Animate_Sprite format) -----
    // From Ani_TitleSonicFinger: duration=5 (advance every 6 frames), then frame indices.
    // Frame 4 = empty/invisible. Sequence: wait, wag 3 times (0-1-0-1-0-1), wait, loop.
    private static final int FINGER_ANIM_DELAY = 5; // advance every (5+1)=6 frames
    private static final int[] FINGER_ANIM_FRAMES = {
            4, 4, 4, 4, 4, 4, 0, 4, 1, 4, 0, 4, 1, 4, 0, 4, 1, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4
    };
    private int fingerAnimIndex = 0;  // index into FINGER_ANIM_FRAMES
    private int fingerAnimTimer = 0;  // counts up to FINGER_ANIM_DELAY

    // ----- Sonic Wink animation (Animate_SpriteIrregularDelay format) -----
    // From Ani_TitleSonicWink: pairs of (frame, delay), $FF = loop.
    // Runs continuously and independently of the finger animation.
    private static final int[] WINK_ANIM_DATA = {
            2, 1,     // frame 2 for (1+1)=2 ticks
            3, 7,     // frame 3 for (7+1)=8 ticks
            2, 5,     // frame 2 for (5+1)=6 ticks
            4, 0x67,  // frame 4 (empty) for ($67+1)=104 ticks
            4, 0x2F   // frame 4 (empty) for ($2F+1)=48 ticks
    };
    private int winkDataIndex = 0;  // index into WINK_ANIM_DATA (by pairs)
    private int winkAnimTimer = 0;  // counts up to current delay

    // Tails plane animation: cycles frames 0-5
    private static final int TAILS_PLANE_ANIM_DURATION = 2;
    private int tailsPlaneAnimTimer = 0;
    private int tailsPlaneAnimFrame = 0;

    // Tails plane movement: VDP x from 0 to $240, then flip and return
    // VDP y=$C0 going right, $D0 going left (screen = VDP - 128)
    private int tailsPlaneVdpX = 0;
    private boolean tailsPlaneGoingRight = true;
    private boolean tailsPlaneHFlip = false;

    // Banner palette cycling (Pal_TitleWaterRot)
    // From disassembly: timer resets to 9 (10 frames per cycle), advances by 4 bytes,
    // wraps with AND $1C = 8 unique positions (0, 4, 8, $C, $10, $14, $18, $1C)
    private int waterRotIndex = 0;
    private int waterRotTimer = 9;
    private static final int WATER_ROT_CYCLE_FRAMES = 10;

    // -----------------------------------------------------------------------
    // Sprite rendering
    // -----------------------------------------------------------------------

    // Mapping frame lists for each sprite type
    private List<SpriteMappingFrame> bannerFrames;
    private List<SpriteMappingFrame> andKnucklesFrames;
    private List<SpriteMappingFrame> selectionFrames;
    private List<SpriteMappingFrame> copyrightFrames;
    private List<SpriteMappingFrame> sonicAnimFrames;
    private List<SpriteMappingFrame> tailsPlaneFrames;

    // Sprite renderers (one per sprite type since they share the same pattern array
    // but have different mapping frame lists)
    private PatternSpriteRenderer bannerRenderer;
    private PatternSpriteRenderer andKnucklesRenderer;
    private PatternSpriteRenderer selectionRenderer;
    private PatternSpriteRenderer copyrightRenderer;
    private PatternSpriteRenderer sonicAnimRenderer;
    private PatternSpriteRenderer tailsPlaneRenderer;

    private boolean spritesInitialized = false;

    // -----------------------------------------------------------------------
    // Constructor and singleton
    // -----------------------------------------------------------------------

    public Sonic3kTitleScreenManager() {
    }

    public static synchronized Sonic3kTitleScreenManager getInstance() {
        if (instance == null) {
            instance = new Sonic3kTitleScreenManager();
        }
        return instance;
    }

    // -----------------------------------------------------------------------
    // TitleScreenProvider implementation
    // -----------------------------------------------------------------------

    @Override
    public void initialize() {
        LOGGER.info("Initializing S3K title screen");

        // Load data if not already loaded
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }

        // Force palette re-upload on next draw
        dataLoader.resetCache();

        // Reset state
        state = State.INTRO_TEXT_FADE_IN;
        phase = Phase.SEGA_FADE_IN;
        phaseTimer = 0;
        frameCounter = 0;
        animTableIndex = 0;
        currentAnimFrame = 1;
        displayedAnimFrame = 1;
        animFrameTimer = 0;
        segaSoundPlayed = false;
        segaChantStopped = false;
        musicPlaying = false;
        palTransitionStep = 0;

        // Reset banner physics
        bannerPos32 = 0xFFA00000; // -96.0 in 16.16
        bannerVel = 0x0400;
        bannerSettled = false;

        // Reset vScroll
        vScroll = 0;

        // Reset menu
        menuSelection = 0;

        // Reset sprites
        bannerSprite.reset();
        andKnucklesSprite.reset();
        selectionSprite.reset();
        copyrightSprite.reset();
        tmSprite.reset();
        sonicFingerSprite.reset();
        sonicWinkSprite.reset();
        tailsPlaneSprite.reset();

        // Reset finger/wink animation
        fingerAnimIndex = 0;
        fingerAnimTimer = 0;
        winkDataIndex = 0;
        winkAnimTimer = 0;

        // Reset Tails plane
        tailsPlaneAnimTimer = 0;
        tailsPlaneAnimFrame = 0;
        tailsPlaneVdpX = 0;
        tailsPlaneGoingRight = true;
        tailsPlaneHFlip = false;

        // Reset palette cycling
        waterRotIndex = 0;
        waterRotTimer = 9; // Counts down from 9 (10-frame period)

        // Reset sprite renderers
        spritesInitialized = false;

        // Cache the first animation frame
        if (dataLoader.isDataLoaded()) {
            dataLoader.cacheAnimationFrame(1);
        }

        LOGGER.info("S3K title screen initialized, entering SEGA_FADE_IN phase");
    }

    @Override
    public void update(InputHandler input) {
        switch (phase) {
            case SEGA_FADE_IN -> updateSegaFadeIn(input);
            case SEGA_HOLD -> updateSegaHold(input);
            case PAL_TRANSITION -> updatePalTransition(input);
            case SONIC_ANIMATION -> updateSonicAnimation(input);
            case WHITE_FLASH -> updateWhiteFlash(input);
            case INTERACTIVE -> updateInteractive(input);
            case FADE_OUT -> updateFadeOut();
            case EXITING -> { }
        }
        frameCounter++;
    }

    @Override
    public void draw() {
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Ensure sprite art is cached
        dataLoader.cacheToGpu();

        // Initialize sprite renderers if needed
        if (!spritesInitialized && dataLoader.getSpritePatterns() != null) {
            initSpriteRenderers(gm);
        }

        switch (phase) {
            case SEGA_FADE_IN, SEGA_HOLD, PAL_TRANSITION, SONIC_ANIMATION ->
                    drawAnimationPhase(gm);
            case WHITE_FLASH -> drawWhiteFlash(gm);
            case INTERACTIVE -> drawInteractivePhase(gm);
            case FADE_OUT -> drawFadeOut(gm);
            case EXITING -> { /* Screen is black, GameLoop handles transition */ }
        }
    }

    @Override
    public void setClearColor() {
        // On the Mega Drive, palette line 0 color 0 is the background color —
        // it fills the screen behind everything. Transparent pixels (color 0)
        // in tile patterns show this background color.
        byte[] palData = dataLoader.getAnimPaletteData(displayedAnimFrame);
        if (palData != null && palData.length >= 2) {
            // Read color 0 from palette line 0 (first 2 bytes, big-endian)
            // Mega Drive format: 0x0BGR where B,G,R are nibbles (0-E, 8 levels)
            int color0 = ((palData[0] & 0xFF) << 8) | (palData[1] & 0xFF);
            float r = ((color0 >> 1) & 0x7) / 7.0f;
            float g = ((color0 >> 5) & 0x7) / 7.0f;
            float b = ((color0 >> 9) & 0x7) / 7.0f;
            glClearColor(r, g, b, 1.0f);
        } else {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    @Override
    public void reset() {
        GameServices.audio().stopSegaPcm();
        endTitleLoopKosWork();
        stalledFrames = 0;
        state = State.INACTIVE;
        phase = Phase.SEGA_FADE_IN;
        phaseTimer = 0;
        frameCounter = 0;
        musicPlaying = false;
        segaSoundPlayed = false;
        segaChantStopped = false;
        spritesInitialized = false;

        // Defensive cleanup in case some earlier flow left a generic FadeManager
        // overlay active before the title screen was reset.
        GameServices.fade().cancel();

        LOGGER.info("S3K title screen reset to inactive");
    }

    @Override
    public State getState() {
        return state;
    }

    @Override
    public boolean isExiting() {
        return state == State.EXITING;
    }

    @Override
    public boolean isActive() {
        return state != State.INACTIVE;
    }

    @Override
    public TitleScreenAction consumeExitAction() {
        return menuSelection == 0 ? TitleScreenAction.ONE_PLAYER : TitleScreenAction.TWO_PLAYER;
    }

    @Override
    public void setExitToLevelHandler(Runnable handler) {
        this.exitToLevelHandler = handler != null ? handler : () -> {};
    }

    // -----------------------------------------------------------------------
    // Phase update methods
    // -----------------------------------------------------------------------

    private void updateSegaFadeIn(InputHandler input) {
        phaseTimer++;

        if (phaseTimer >= SEGA_FADE_DURATION) {
            phase = Phase.SEGA_HOLD;
            phaseTimer = 0;
            state = State.INTRO_TEXT_HOLD;
            LOGGER.fine("S3K title screen entered SEGA_HOLD phase");
        }
    }

    private void updateSegaHold(InputHandler input) {
        phaseTimer++;

        if (checkSkipToInteractive(input)) {
            return;
        }

        // Play SEGA sound immediately (disassembly plays it right after Pal_FadeFromBlack)
        if (!segaSoundPlayed) {
            segaSoundPlayed = true;
            GameServices.audio().playMusic(Sonic3kSmpsConstants.CMD_SEGA);
        }

        if (checkSkipToInteractive(input)) {
            return;
        }

        if (phaseTimer >= SEGA_HOLD_DURATION) {
            GameServices.audio().playMusic(Sonic3kSmpsConstants.CMD_STOP_SEGA);
            segaChantStopped = true;
            phase = Phase.PAL_TRANSITION;
            phaseTimer = 0;
            state = State.INTRO_TEXT_FADE_OUT;
            LOGGER.fine("S3K title screen entered PAL_TRANSITION phase");
        }
    }

    /**
     * Updates the palette transition phase.
     *
     * <p>From the disassembly (sonic3k.asm lines 5501-5512): each VSync, reads
     * 14 bytes from {@code Pal_Title} and writes them to palette line 0 colors 0-6.
     * This gradually changes the SEGA screen background to black while leaving
     * the SEGA text (colors 7+) unchanged. Completes when color 0 becomes $0000.
     */
    private void updatePalTransition(InputHandler input) {
        if (checkSkipToInteractive(input)) {
            return;
        }

        // Apply one palette transition step per frame (matching the disassembly's
        // VSync loop that writes 14 bytes per iteration)
        byte[] transitionData = dataLoader.getPalTransitionData();
        if (transitionData != null && palTransitionStep < PAL_TRANSITION_STEPS) {
            applyPalTransitionStep(transitionData, palTransitionStep);
            palTransitionStep++;
        }

        if (palTransitionStep >= PAL_TRANSITION_STEPS) {
            enterSonicAnimation();
        }
    }

    /** Transition complete (background now black): loc_4040, sonic3k.asm:5520-5529. */
    private void enterSonicAnimation() {
        phase = Phase.SONIC_ANIMATION;
        phaseTimer = 0;
        animTableIndex = 0;
        currentAnimFrame = SONIC_FRAME_INDEX_TABLE[0];
        animFrameTimer = 0;
        state = State.FADE_IN;

        // Cache the first animation frame art/palette
        presentAnimationFrame(currentAnimFrame);
        beginTitleLoopKosWork();

        // Play title music
        if (!musicPlaying) {
            musicPlaying = true;
            GameServices.audio().playMusic(Sonic3kMusic.TITLE.id);
        }

        LOGGER.fine("S3K title screen entered SONIC_ANIMATION phase");
    }

    /**
     * Opens the title loop's own Kosinski work: the title screen runs outside a
     * gameplay session, so it owns a timing service of its own, paced by the
     * configured normal-play load-time profile, and queues frame 7's art as
     * {@code loc_4040} does.
     */
    private void beginTitleLoopKosWork() {
        titleTiming = new HardwareTimingService(
                RomWorkBudgetScheduler.oneWorkUnitAt(HardwareServiceBoundary.POST_OBJECTS),
                resolveLoadTimeProfile());
        titleKosQueue = new S3kKosDecompressionQueue(titleTiming);
        pendingFrameArt = null;
        stalledFrames = 0;
        queuedFrameArt = queueTitleFrameArt(QUEUED_ART_FRAME);
    }

    /** Normal-play load-time profile for the title loop's Kosinski work. */
    protected LoadTimeProfile resolveLoadTimeProfile() {
        LoadTimeSimulationMode mode = LoadTimeSimulationMode.parse(
                configService.getString(SonicConfiguration.LOAD_TIME_SIMULATION));
        return GameServices.module().createLoadTimeProfile(mode, LOGGER::warning);
    }

    private HardwareWorkHandle queueTitleFrameArt(int frame) {
        try {
            return titleKosQueue.queueStandardKos(
                    GameServices.rom().getRom(), romFrameArtAddress(frame),
                    S3kKosRamDestinations.RAM_START);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to queue title Sonic frame " + frame + " art", exception);
        }
    }

    /** One title-loop service: {@code Process_Kos_Queue} before {@code Wait_VSync} (sonic3k.asm:5531-5533). */
    private void serviceTitleLoopKosWork() {
        titleTiming.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
        titleKosQueue.afterTimingService(HardwareServiceBoundary.PRE_MAIN_LOOP);
    }

    /**
     * {@code TitleSonic_LoadFrame} (sonic3k.asm:5822-5871) for the frame just
     * selected by {@code Iterate_TitleSonicFrame}. Frames with Kosinski work
     * present their art only once it is ready; a synchronous decode that is
     * not ready stalls the loop (see {@link #updateSonicAnimation}).
     */
    private void loadTitleSonicFrame(int frame) {
        HardwareWorkHandle art = null;
        if (frame == QUEUED_ART_FRAME) {
            art = queuedFrameArt;
            queuedFrameArt = null;
        } else if (frame > QUEUED_ART_FRAME && frame <= LAST_SYNCHRONOUS_DECODE_FRAME) {
            art = queueTitleFrameArt(frame);
        }
        if (art != null && !titleKosQueue.isReady(art)) {
            pendingFrameArt = art;
            return;
        }
        if (art != null) {
            titleKosQueue.claim(art);
        }
        presentAnimationFrame(frame);
    }

    /** The decoded frame's art, mappings and palette reach VRAM together. */
    private void presentAnimationFrame(int frame) {
        dataLoader.cacheAnimationFrame(frame);
        displayedAnimFrame = frame;
    }

    int displayedAnimFrame() {
        return displayedAnimFrame;
    }

    private void endTitleLoopKosWork() {
        titleTiming = null;
        titleKosQueue = null;
        queuedFrameArt = null;
        pendingFrameArt = null;
    }

    int currentAnimFrame() {
        return currentAnimFrame;
    }

    int stalledFrames() {
        return stalledFrames;
    }

    /** Skips the SEGA and palette phases so a test can step the Sonic animation directly. */
    void enterSonicAnimationForTest() {
        enterSonicAnimation();
    }

    /**
     * Applies one step of the Pal_Title transition to palette line 0.
     * Each step overwrites colors 0-6 (14 bytes) with data from the transition table.
     */
    private void applyPalTransitionStep(byte[] transitionData, int step) {
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        int offset = step * PAL_TRANSITION_BYTES_PER_STEP;
        if (offset + PAL_TRANSITION_BYTES_PER_STEP > transitionData.length) {
            return;
        }

        // Get the current palette line 0 from the frame 1 palette
        byte[] frame1Pal = dataLoader.getAnimPaletteData(1);
        if (frame1Pal == null || frame1Pal.length < Palette.PALETTE_SIZE_IN_ROM) {
            return;
        }

        // Copy full palette line 0 (32 bytes) then overwrite colors 0-6
        byte[] line0Data = new byte[Palette.PALETTE_SIZE_IN_ROM];
        System.arraycopy(frame1Pal, 0, line0Data, 0, Palette.PALETTE_SIZE_IN_ROM);

        // Overwrite colors 0-6 (14 bytes) with transition data
        System.arraycopy(transitionData, offset, line0Data, 0, PAL_TRANSITION_BYTES_PER_STEP);

        S3kFrontendPaletteUploader.cacheLineFromBytes(gm, line0Data, 0);
    }

    private void updateSonicAnimation(InputHandler input) {
        // Process_Kos_Queue at the top of every Wait_TitleS3K iteration
        // (sonic3k.asm:5531): the one service point of the title loop.
        serviceTitleLoopKosWork();
        if (pendingFrameArt != null) {
            // TitleSonic_LoadFrame's Kos_Decomp (sonic3k.asm:5834) is a
            // synchronous 68000 call: until it returns no V-int is serviced,
            // so no input is polled and nothing in the title loop advances.
            // Each stalled iteration is one missed V-int. The profile's
            // serviceFrames count the services from the iteration that started
            // the decode, so a job costing N services stalls N-1 iterations and
            // an IMMEDIATE job (NONE) stalls none.
            if (!titleKosQueue.isReady(pendingFrameArt)) {
                stalledFrames++;
                return;
            }
            titleKosQueue.claim(pendingFrameArt);
            pendingFrameArt = null;
            presentAnimationFrame(currentAnimFrame);
        }
        if (checkSkipToInteractive(input)) {
            return;
        }

        // TitleAnim_FlipBuffer, V_int routine 4 (sonic3k.asm:5744-5772). Zero
        // reloads the delay (and, on hardware, flips the nametable buffer and
        // copies Target_palette over Normal_palette); anything else decrements.
        // The buffer flip itself is not modelled here -- the engine draws the
        // cached frame directly rather than alternating two nametables.
        if (animFrameTimer == 0) {
            animFrameTimer = TITLE_ANIM_DELAY_RELOAD;
        } else {
            animFrameTimer--;
        }

        // Iterate_TitleSonicFrame (sonic3k.asm:5792-5800): advance only on the
        // iteration where Title_anim_delay reads exactly 1.
        if (animFrameTimer == 1) {
            animTableIndex++;

            if (animTableIndex >= SONIC_FRAME_INDEX_TABLE.length) {
                // Animation complete - shouldn't happen (0xFF triggers below)
                transitionToWhiteFlash();
                return;
            }

            int nextFrame = SONIC_FRAME_INDEX_TABLE[animTableIndex];
            if (nextFrame == 0xFF) {
                // Animation complete
                transitionToWhiteFlash();
                return;
            }

            currentAnimFrame = nextFrame;
            loadTitleSonicFrame(nextFrame);
        }
    }

    private void updateWhiteFlash(InputHandler input) {
        phaseTimer++;

        if (phaseTimer >= WHITE_FLASH_DURATION) {
            transitionToInteractive();
        }
    }

    /**
     * Updates the exit fade-to-black phase.
     *
     * <p>We handle the full exit transition ourselves rather than relying on
     * the GameLoop's {@code exitTitleScreen()} → FadeManager → callback chain,
     * because title transitions may run across differently scoped fade managers.
     *
     * <p>When our visual fade completes, we directly reset, set the game mode
     * to LEVEL, and load the first zone.
     */
    private void updateFadeOut() {
        phaseTimer++;
        if (phaseTimer >= EXIT_FADE_DURATION) {
            LOGGER.info("S3K title screen exit fade complete, loading level");

            // Reset title screen state
            state = State.INACTIVE;
            phase = Phase.EXITING;
            spritesInitialized = false;

            // Cancel any stale FadeManager overlays
            GameServices.fade().cancel();

            // Transition directly to LEVEL mode and load the first zone
            try {
                exitToLevelHandler.run();
            } catch (Exception e) {
                LOGGER.severe("Failed to load level after title screen: " + e.getMessage());
            }
        }
    }

    private void updateInteractive(InputHandler input) {
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);
        int upKey = configService.getInt(SonicConfiguration.UP);
        int downKey = configService.getInt(SonicConfiguration.DOWN);

        // Menu navigation
        if ((input.isKeyPressed(upKey) || input.logical().menuUp()) && menuSelection > 0) {
            menuSelection--;
            selectionSprite.mappingFrame = menuSelection;
            GameServices.audio().playSfx(Sonic3kSfx.SWITCH.id);
        }
        if ((input.isKeyPressed(downKey) || input.logical().menuDown()) && menuSelection < 1) {
            menuSelection++;
            selectionSprite.mappingFrame = menuSelection;
            GameServices.audio().playSfx(Sonic3kSfx.SWITCH.id);
        }

        // Start pressed - 1 PLAYER hands off through GameLoop routing, while the
        // competition path keeps the provider-owned fade sequence.
        if (confirmPressed(input, jumpKey)) {
            if (menuSelection == 0) {
                state = State.EXITING;
                LOGGER.info("S3K title screen handing off to GameLoop for 1 PLAYER");
                return;
            }
            phase = Phase.FADE_OUT;
            phaseTimer = 0;
            // State stays ACTIVE during our fade — we only set EXITING once
            // the visual fade is complete, so GameLoop can hand off immediately.
            GameServices.audio().fadeOutMusic();
            LOGGER.info("S3K title screen starting exit fade (menu selection: " + menuSelection + ")");
            return;
        }

        // Update banner bounce physics
        if (!bannerSettled) {
            updateBannerBounce();
        }

        // Update vScroll — starts immediately during bounce (matching disassembly:
        // V_scroll_value increments every frame of Obj_TitleBanner_Main/Display)
        if (vScroll < VSCROLL_TARGET) {
            vScroll++;
        }

        // Update Tails plane movement and animation
        updateTailsPlane();

        // Update Sonic finger and wink animations (both run simultaneously)
        updateSonicFinger();
        updateSonicWink();

        // Update banner palette cycling
        updateWaterRotCycling();
    }

    // -----------------------------------------------------------------------
    // Animation helpers
    // -----------------------------------------------------------------------

    /**
     * Checks if Start is pressed and skips directly to the interactive phase.
     *
     * @return true if skip occurred
     */
    private boolean checkSkipToInteractive(InputHandler input) {
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);
        if (confirmPressed(input, jumpKey)) {
            transitionToWhiteFlash();
            return true;
        }
        return false;
    }

    /**
     * Keyboard Jump press or gamepad confirm (Start / any face action button),
     * matching {@link com.openggf.game.MasterTitleScreen}'s gamepad-aware confirm gate.
     */
    private static boolean confirmPressed(InputHandler input, int jumpKey) {
        return input.isKeyPressed(jumpKey) || input.logical().menuAccept();
    }

    private void transitionToWhiteFlash() {
        endTitleLoopKosWork();
        // ROM: Wait_SegaS3K's Start press and its timeout share the one
        // cmd_StopSEGA at sonic3k.asm:5498-5500, so this stop belongs only to a
        // skip taken while the chant is still playing. A skip taken later is a
        // Start press inside Wait_TitleS3K, which branches to loc_4090 without
        // any sound command (:5541-5546). Gating on segaSoundPlayed instead
        // issued a second stop-all after the title music had started at :5529,
        // silencing it for the rest of the title screen.
        if (!segaChantStopped) {
            GameServices.audio().playMusic(Sonic3kSmpsConstants.CMD_STOP_SEGA);
            segaChantStopped = true;
        }
        phase = Phase.WHITE_FLASH;
        phaseTimer = 0;
        state = State.FADE_IN;

        // Load final frame
        currentAnimFrame = FINAL_FRAME_INDEX;
        displayedAnimFrame = FINAL_FRAME_INDEX;
        dataLoader.cacheFinalScene();

        // Play title music if not already playing
        if (!musicPlaying) {
            musicPlaying = true;
            GameServices.audio().playMusic(Sonic3kMusic.TITLE.id);
        }

        LOGGER.fine("S3K title screen entered WHITE_FLASH phase");
    }

    private void transitionToInteractive() {
        phase = Phase.INTERACTIVE;
        state = State.ACTIVE;

        // Load the final scene art (frame D with full 4-line palette)
        dataLoader.cacheFinalScene();

        // Initialize interactive sprites
        initInteractiveSprites();

        LOGGER.fine("S3K title screen entered INTERACTIVE phase");
    }

    private void initInteractiveSprites() {
        // Banner: starts off-screen below, bounces into position
        bannerSprite.active = true;
        bannerSprite.mappingFrame = 0; // Main banner frame

        // &Knuckles: follows banner Y
        andKnucklesSprite.active = true;
        andKnucklesSprite.mappingFrame = 0;

        // TM symbol: positioned relative to banner
        tmSprite.active = true;
        tmSprite.mappingFrame = 1; // TM frame in banner mappings

        // Selection menu
        selectionSprite.active = true;
        selectionSprite.mappingFrame = menuSelection;

        // Copyright text
        copyrightSprite.active = true;
        copyrightSprite.mappingFrame = 0;

        // Sonic finger and wink: both run simultaneously from the start
        // They are separate sprites at different positions on Sonic's body
        sonicFingerSprite.active = true;
        sonicFingerSprite.mappingFrame = FINGER_ANIM_FRAMES[0];
        fingerAnimIndex = 0;
        fingerAnimTimer = 0;

        sonicWinkSprite.active = true;
        sonicWinkSprite.mappingFrame = WINK_ANIM_DATA[0]; // first frame
        winkDataIndex = 0;
        winkAnimTimer = 0;

        // Tails plane: flies across screen
        tailsPlaneSprite.active = true;
        tailsPlaneSprite.mappingFrame = 0;
        tailsPlaneVdpX = 0;
        tailsPlaneGoingRight = true;
        tailsPlaneHFlip = false;

        // Reset banner physics
        bannerPos32 = 0xFFA00000; // -96.0 in 16.16
        bannerVel = 0x0400;
        bannerSettled = false;
    }

    // -----------------------------------------------------------------------
    // Banner bounce physics (from disassembly Obj_TitleBanner)
    // -----------------------------------------------------------------------

    /**
     * Bounce flag from previous frame. Mirrors the disassembly's $34(a0) byte:
     * 0 when position &lt; 0, -1 when position &gt;= 0. Velocity is halved when
     * this flag changes between frames (i.e., position crosses zero).
     */
    private byte bannerBounceFlag = 0;

    /**
     * Updates banner bounce physics per frame.
     *
     * <p>Faithful port of {@code Obj_TitleBanner_Main} from the disassembly
     * (sonic3k.asm lines 6007-6056). Position is 16.16 fixed point stored as a
     * 32-bit signed int. Velocity is signed 16-bit. Each frame:
     * <ol>
     *   <li>Save previous bounce flag</li>
     *   <li>pos += vel &lt;&lt; 8</li>
     *   <li>If posInt &lt; 0: gravity = +$40. If posInt &gt;= 0: gravity = -$40, set bounce flag</li>
     *   <li>vel += gravity</li>
     *   <li>If bounce flag changed: vel &gt;&gt;= 1 (halve on zero-crossing)</li>
     *   <li>Settled when posInt == 0 and vel == -$5B</li>
     * </ol>
     */
    private void updateBannerBounce() {
        // d2 = previous frame's bounce flag
        byte prevFlag = bannerBounceFlag;

        // pos += vel << 8 (extend 16-bit vel to 32-bit, shift left 8)
        bannerPos32 += ((int) bannerVel) << 8;

        // d0 = high word of position (integer part, signed)
        short posInt = (short) (bannerPos32 >> 16);

        // Clear flag for this frame
        bannerBounceFlag = 0;

        // d1 = gravity direction
        short gravity = 0x40;

        if (posInt < 0) {
            // Position below center: accelerate upward, flag stays 0
            // (falls through to apply gravity)
        } else if (posInt == 0 && bannerVel == (short) -0x5B) {
            // Settled: pos=0, vel=-0x5B
            bannerSettled = true;
            bannerPos32 = 0;
            bannerVel = 0;
            LOGGER.fine("Banner settled at frame " + frameCounter);
            return;
        } else {
            // Position at or above center (and not settled): set flag, reverse gravity
            bannerBounceFlag = -1;
            gravity = -0x40;
        }

        // Apply gravity
        bannerVel += gravity;

        // If bounce flag changed from previous frame (position crossed zero): halve velocity
        if (bannerBounceFlag != prevFlag) {
            bannerVel >>= 1; // Arithmetic shift right (same as 68000 ASR)
        }
    }

    /**
     * Calculates the banner screen Y from its physics position.
     *
     * <p>VDP y_pos = 0xD4 - posInteger. Screen Y = VDP - 128.
     *
     * @return screen Y position
     */
    private int getBannerScreenY() {
        short posInt = (short) (bannerPos32 >> 16);
        int vdpY = 0xD4 - posInt;
        return vdpY - 128;
    }

    /**
     * Calculates the "&amp; KNUCKLES" screen Y.
     *
     * <p>VDP y = banner_vdp_y + 0x5C. Screen Y = VDP - 128.
     */
    private int getAndKnucklesScreenY() {
        short posInt = (short) (bannerPos32 >> 16);
        int bannerVdpY = 0xD4 - posInt;
        int andKnucklesVdpY = bannerVdpY + 0x5C;
        return andKnucklesVdpY - 128;
    }

    // -----------------------------------------------------------------------
    // Sprite animation updates
    // -----------------------------------------------------------------------

    private void updateTailsPlane() {
        // Move across screen (from disassembly Obj_TitleTailsPlane)
        // Going right: VDP x increments from 0 to $240, y=$C0
        // Going left: VDP x decrements from $240 to 0, y=$D0, H-flip set
        if (tailsPlaneGoingRight) {
            tailsPlaneVdpX++;
            if (tailsPlaneVdpX >= 0x240) {
                tailsPlaneGoingRight = false;
                tailsPlaneHFlip = true;
            }
        } else {
            tailsPlaneVdpX--;
            if (tailsPlaneVdpX <= 0) {
                tailsPlaneGoingRight = true;
                tailsPlaneHFlip = false;
            }
        }

        // Animate propeller
        tailsPlaneAnimTimer++;
        if (tailsPlaneAnimTimer >= TAILS_PLANE_ANIM_DURATION) {
            tailsPlaneAnimTimer = 0;
            tailsPlaneAnimFrame++;
            if (tailsPlaneAnimFrame >= 6) {
                tailsPlaneAnimFrame = 0;
            }
            tailsPlaneSprite.mappingFrame = tailsPlaneAnimFrame;
        }
    }

    /**
     * Updates the Sonic finger animation using the standard Animate_Sprite format.
     *
     * <p>Duration byte = 5 means advance every (5+1) = 6 frames. The frame index
     * sequence plays continuously and loops at $FF. Frame 4 = empty/invisible.
     */
    private void updateSonicFinger() {
        fingerAnimTimer++;
        if (fingerAnimTimer > FINGER_ANIM_DELAY) {
            fingerAnimTimer = 0;
            fingerAnimIndex++;
            if (fingerAnimIndex >= FINGER_ANIM_FRAMES.length) {
                fingerAnimIndex = 0; // $FF = loop
            }
            sonicFingerSprite.mappingFrame = FINGER_ANIM_FRAMES[fingerAnimIndex];
        }
    }

    /**
     * Updates the Sonic wink animation using the Animate_SpriteIrregularDelay format.
     *
     * <p>Each entry is a (frame, delay) pair. The delay value means the frame is shown
     * for (delay+1) ticks. Loops at $FF. Frame 4 = empty/invisible.
     */
    private void updateSonicWink() {
        winkAnimTimer++;
        // Current delay is WINK_ANIM_DATA[winkDataIndex + 1]
        int currentDelay = WINK_ANIM_DATA[winkDataIndex + 1];
        if (winkAnimTimer > currentDelay) {
            winkAnimTimer = 0;
            winkDataIndex += 2; // advance to next (frame, delay) pair
            if (winkDataIndex >= WINK_ANIM_DATA.length) {
                winkDataIndex = 0; // $FF = loop
            }
            sonicWinkSprite.mappingFrame = WINK_ANIM_DATA[winkDataIndex];
        }
    }

    /**
     * Updates the palette cycling for the banner water shimmer effect.
     *
     * <p>From the disassembly (Obj_TitleBanner_Display): every 10 frames, reads
     * 4 bytes (2 Mega Drive colors) from {@code Pal_TitleWaterRot} and writes
     * them to palette line 3 at byte offset $1A (color indices 13-14).
     * The offset advances by 4 and wraps with AND $1C (8 unique positions).
     */
    private void updateWaterRotCycling() {
        byte[] waterRotData = dataLoader.getWaterRotData();
        if (waterRotData == null || waterRotData.length < 4) {
            return;
        }

        waterRotTimer--;
        if (waterRotTimer < 0) {
            waterRotTimer = WATER_ROT_CYCLE_FRAMES - 1; // Reset to 9 (10-frame period)
            waterRotIndex = (waterRotIndex + 4) & 0x1C; // Advance by 4, wrap at $1C

            // Apply the 2 cycling colors to palette line 3
            applyWaterRotPalette(waterRotData);
        }
    }

    /**
     * Applies 2 colors from water rotation data to palette line 2 (0-indexed), colors 13-14.
     *
     * <p>The disassembly writes to {@code Target_palette_line_3+$1A}. In the S3K constants,
     * {@code Target_palette_line_3 = Target_palette+$40}, which is the THIRD palette line
     * (1-based numbering), i.e. palette index 2 (0-based). This is the background palette,
     * which contains the water gradient colors.
     */
    private void applyWaterRotPalette(byte[] waterRotData) {
        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        // Get the current palette line 2 (0-indexed) data from the frame D palette
        byte[] palDData = dataLoader.getFrameDPaletteData();
        if (palDData == null || palDData.length < 96) {
            return;
        }

        // Palette line 2 (0-indexed) starts at byte 64 (2 * 32) in the 128-byte palette data
        byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
        System.arraycopy(palDData, 2 * Palette.PALETTE_SIZE_IN_ROM, lineData, 0,
                Palette.PALETTE_SIZE_IN_ROM);

        // Overwrite colors 13-14 (byte offset $1A = 26) with cycling data
        if (waterRotIndex + 3 < waterRotData.length) {
            lineData[26] = waterRotData[waterRotIndex];
            lineData[27] = waterRotData[waterRotIndex + 1];
            lineData[28] = waterRotData[waterRotIndex + 2];
            lineData[29] = waterRotData[waterRotIndex + 3];
        }

        // Re-upload palette line 2 (0-indexed)
        S3kFrontendPaletteUploader.cacheLineFromBytes(gm, lineData, 2);
    }

    // -----------------------------------------------------------------------
    // Drawing methods
    // -----------------------------------------------------------------------

    /**
     * Draws during the SEGA/animation phases: renders the current animation
     * frame as a full-screen 40x28 nametable.
     */
    private void drawAnimationPhase(GraphicsManager gm) {
        int[] nametable = dataLoader.getAnimationMapping(displayedAnimFrame);
        if (nametable == null || nametable.length == 0) {
            return;
        }

        int animPatternBase = dataLoader.getAnimPatternBase();
        // xOffset() is 0 at native 320 — byte-identical at native width.
        int ox = xOffset();

        gm.beginPatternBatch();
        int mapSize = MAP_WIDTH * MAP_HEIGHT;
        for (int row = 0; row < MAP_HEIGHT; row++) {
            for (int col = 0; col < MAP_WIDTH; col++) {
                int idx = row * MAP_WIDTH + col;
                if (idx >= nametable.length || idx >= mapSize) {
                    continue;
                }
                int word = nametable[idx];
                if (word == 0) {
                    continue;
                }
                // Extract pattern fields from nametable word
                int tileIndex = word & 0x7FF;
                reusableDesc.set(word);
                gm.renderPatternWithId(animPatternBase + tileIndex, reusableDesc, ox + col * 8, row * 8);
            }
        }
        gm.flushPatternBatch();

        // Apply fade overlay for SEGA fade-in only.
        // PAL_TRANSITION uses actual per-color palette modification (not an overlay)
        // so the SEGA text stays white while the background goes dark.
        // Width extended to viewportWidth() so side-bars are covered at widescreen;
        // at native 320 viewportWidth() == SCREEN_WIDTH — byte-identical.
        if (phase == Phase.SEGA_FADE_IN) {
            float fadeAmount = 1.0f - (float) phaseTimer / SEGA_FADE_DURATION;
            if (fadeAmount > 0.0f) {
                gm.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI, -1,
                        GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                        0.0f, 0.0f, 0.0f, fadeAmount,
                        0, 0, viewportWidth(), SCREEN_HEIGHT
                ));
            }
        }
    }

    /**
     * Draws the white flash transition and the final frame underneath.
     */
    private void drawWhiteFlash(GraphicsManager gm) {
        // Draw the final animation frame underneath
        int[] nametable = dataLoader.getAnimationMapping(FINAL_FRAME_INDEX);
        if (nametable != null && nametable.length > 0) {
            int animPatternBase = dataLoader.getAnimPatternBase();
            // xOffset() is 0 at native 320 — byte-identical at native width.
            int ox = xOffset();
            gm.beginPatternBatch();
            for (int row = 0; row < MAP_HEIGHT; row++) {
                for (int col = 0; col < MAP_WIDTH; col++) {
                    int idx = row * MAP_WIDTH + col;
                    if (idx >= nametable.length) {
                        continue;
                    }
                    int word = nametable[idx];
                    if (word == 0) {
                        continue;
                    }
                    int tileIndex = word & 0x7FF;
                    reusableDesc.set(word);
                    gm.renderPatternWithId(animPatternBase + tileIndex, reusableDesc, ox + col * 8, row * 8);
                }
            }
            gm.flushPatternBatch();
        }

        // White flash overlay, fading out.
        // Width extended to viewportWidth() so side-bars are also covered;
        // at native 320 viewportWidth() == SCREEN_WIDTH — byte-identical.
        float flashAlpha = 1.0f - (float) phaseTimer / WHITE_FLASH_DURATION;
        if (flashAlpha > 0.0f) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI, -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    1.0f, 1.0f, 1.0f, flashAlpha,
                    0, 0, viewportWidth(), SCREEN_HEIGHT
            ));
        }
    }

    /**
     * Draws the exit fade-to-black: renders the interactive scene with a
     * progressively opaque black overlay.
     */
    private void drawFadeOut(GraphicsManager gm) {
        // Draw the interactive scene underneath
        drawInteractivePhase(gm);

        // Black overlay, fading in.
        // Width extended to viewportWidth() so side-bars are also covered;
        // at native 320 viewportWidth() == SCREEN_WIDTH — byte-identical.
        float fadeAlpha = (float) phaseTimer / EXIT_FADE_DURATION;
        if (fadeAlpha > 0.0f) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI, -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    0.0f, 0.0f, 0.0f, Math.min(1.0f, fadeAlpha),
                    0, 0, viewportWidth(), SCREEN_HEIGHT
            ));
        }
    }

    /**
     * Draws the interactive phase: background plane, foreground plane, and all sprites.
     */
    private void drawInteractivePhase(GraphicsManager gm) {
        // xOffset() is 0 at native 320 — byte-identical at native width.
        int ox = xOffset();

        // 0. Fill the widescreen side bands with a flat colour from the picture's
        //    top-left tile so they read as sky rather than black bars. No-op at
        //    native 320 (no bands).
        drawBackgroundBands(gm);

        // 1. Render Plane B (background) — centred over the bands.
        renderPlaneB(gm);

        // 2. Render Tails plane sprite (no priority, renders behind Plane A)
        if (tailsPlaneSprite.active && tailsPlaneRenderer != null && tailsPlaneRenderer.isReady()) {
            int tailsScreenX = ox + tailsPlaneVdpX - 128;
            int tailsVdpY = tailsPlaneGoingRight ? 0xC0 : 0xD0;
            int tailsScreenY = tailsVdpY - 128;
            gm.beginPatternBatch();
            tailsPlaneRenderer.drawFrameIndex(tailsPlaneSprite.mappingFrame,
                    tailsScreenX, tailsScreenY, tailsPlaneHFlip, false);
            gm.flushPatternBatch();
        }

        // 3. Render Plane A (final Sonic frame, shifted by vScroll) — centered
        renderPlaneA(gm);

        // 4. Render sprites in VDP priority order (back to front in painter's algorithm).
        // On VDP, lower priority values = drawn in front. From the disassembly:
        //   Tails plane: priority $380 (furthest back, already drawn above)
        //   Finger/Wink: priority $180 (behind banner)
        //   Banner/TM/&Knuckles: priority $80 (in front of finger)
        //   Selection/Copyright: priority $80 (frontmost)
        gm.beginPatternBatch();

        // Sonic finger wag — priority $180, drawn BEHIND the banner
        // VDP x=$148, y=($DC - vScroll); ox centres on the viewport.
        if (sonicFingerSprite.active && sonicAnimRenderer != null && sonicAnimRenderer.isReady()) {
            int fingerScreenX = ox + 0x148 - 128; // native: 200
            int fingerScreenY = 0xDC - vScroll - 128;
            sonicAnimRenderer.drawFrameIndex(sonicFingerSprite.mappingFrame,
                    fingerScreenX, fingerScreenY);
        }

        // Sonic wink — priority $180, drawn BEHIND the banner
        // VDP x=$F8, y=($C8 - vScroll)
        if (sonicWinkSprite.active && sonicAnimRenderer != null && sonicAnimRenderer.isReady()) {
            int winkScreenX = ox + 0xF8 - 128; // native: 120
            int winkScreenY = 0xC8 - vScroll - 128;
            sonicAnimRenderer.drawFrameIndex(sonicWinkSprite.mappingFrame,
                    winkScreenX, winkScreenY);
        }

        // Banner — priority $80, drawn IN FRONT of finger/wink
        if (bannerSprite.active && bannerRenderer != null && bannerRenderer.isReady()) {
            int bannerScreenX = ox + 0x120 - 128; // native: 160
            int bannerScreenY = getBannerScreenY();
            bannerRenderer.drawFrameIndex(0, bannerScreenX, bannerScreenY);

            // TM symbol — VDP x=$188, y=$EC (fixed position)
            if (tmSprite.active && bannerSettled) {
                int tmScreenX = ox + 0x188 - 128; // native: 264
                int tmScreenY = 0xEC - 128;  // 108
                bannerRenderer.drawFrameIndex(1, tmScreenX, tmScreenY);
            }
        }

        // & KNUCKLES — priority $80
        if (andKnucklesSprite.active && andKnucklesRenderer != null && andKnucklesRenderer.isReady()) {
            int andKnucklesScreenX = ox + 0x120 - 128; // native: 160
            int andKnucklesScreenY = getAndKnucklesScreenY();
            andKnucklesRenderer.drawFrameIndex(0, andKnucklesScreenX, andKnucklesScreenY);
        }

        // Menu selection — VDP x=$F0, y=$140
        if (selectionSprite.active && selectionRenderer != null && selectionRenderer.isReady()) {
            int selScreenX = ox + 0xF0 - 128; // native: 112
            int selScreenY = 0x140 - 128; // 192
            selectionRenderer.drawFrameIndex(selectionSprite.mappingFrame,
                    selScreenX, selScreenY);
        }

        // Copyright text — VDP x=$158, y=$14C
        if (copyrightSprite.active && copyrightRenderer != null && copyrightRenderer.isReady()) {
            int copyScreenX = ox + 0x158 - 128; // native: 216
            int copyScreenY = 0x14C - 128; // 204
            copyrightRenderer.drawFrameIndex(0, copyScreenX, copyScreenY);
        }

        gm.flushPatternBatch();
    }

    /**
     * Renders Plane B (background) for the interactive phase.
     * Uses the Enigma-decoded MapEni_S3TitleBg nametable.
     */
    /**
     * Renders Plane B (background) for the interactive phase.
     * NOT affected by vScroll — on VDP, V_scroll_value is written to VSRAM
     * word 0 (Plane A only). Plane B has its own V_scroll (word 1) which stays at 0.
     *
     * <p><b>Widescreen:</b> the fixed 40×28 picture is centred (pillarboxed).
     * Edge-tiling the picture to fill the viewport was tried but smeared the
     * cloud/skyline edge columns. Instead {@link #drawBackgroundBands} fills the
     * side bands with a single flat colour from the picture's top-left tile, so
     * they read as sky rather than black bars. At native 320 the centred frame
     * exactly fills the viewport (xOffset 0) — byte-identical.
     */
    private void renderPlaneB(GraphicsManager gm) {
        int[] bgMap = dataLoader.getBackgroundMapping();
        if (bgMap == null || bgMap.length == 0) {
            return;
        }

        int animPatternBase = dataLoader.getAnimPatternBase();
        // xOffset() is 0 at native 320 — byte-identical at native width.
        int ox = xOffset();

        gm.beginPatternBatch();
        for (int row = 0; row < MAP_HEIGHT; row++) {
            for (int col = 0; col < MAP_WIDTH; col++) {
                int idx = row * MAP_WIDTH + col;
                if (idx >= bgMap.length) {
                    continue;
                }
                int word = bgMap[idx];
                if (word == 0) {
                    continue;
                }
                int tileIndex = word & 0x7FF;
                reusableDesc.set(word);
                gm.renderPatternWithId(animPatternBase + tileIndex, reusableDesc, ox + col * 8, row * 8);
            }
        }
        gm.flushPatternBatch();
    }

    /**
     * Fills the widescreen side bands (outside the centred 320 frame) with a
     * single flat colour taken from the picture's top-left tile, so the bands
     * read as sky instead of black bars. One colour for the whole band avoids
     * the cloud smear that tiling or per-row sampling produced. No-op at native
     * 320 (no bands).
     */
    private void drawBackgroundBands(GraphicsManager gm) {
        int ox = xOffset();
        if (ox <= 0) {
            return; // native width — no side bands
        }
        float[] c = topLeftBackgroundColor();
        if (c == null) {
            return;
        }
        int vw = viewportWidth();
        int rightStart = ox + MAP_WIDTH * 8;
        // Left band and right band, full viewport height, behind everything.
        gm.registerCommand(new GLCommand(GLCommand.CommandType.RECTI, -1,
                c[0], c[1], c[2], 0, 0, ox, SCREEN_HEIGHT));
        gm.registerCommand(new GLCommand(GLCommand.CommandType.RECTI, -1,
                c[0], c[1], c[2], rightStart, 0, vw, SCREEN_HEIGHT));
    }

    /**
     * Resolves the flat side-band colour from the background picture's top-left
     * tile (its corner pixel through the frame-D palette).
     *
     * @return {0..1, 0..1, 0..1} float RGB, or null if the tile is transparent
     *         or unavailable (bands left to the clear colour)
     */
    private float[] topLeftBackgroundColor() {
        int[] bgMap = dataLoader.getBackgroundMapping();
        byte[] palD = dataLoader.getFrameDPaletteData();
        Pattern[] pats = dataLoader.getFrameDPatterns();
        if (bgMap == null || bgMap.length == 0 || palD == null || pats == null) {
            return null;
        }
        int word = bgMap[0]; // top-left tile
        if (word == 0) {
            return null; // transparent corner
        }
        int tileIndex = word & 0x7FF;
        if (tileIndex >= pats.length || pats[tileIndex] == null) {
            return null;
        }
        int palLine = (word >> 13) & 0x3;
        int colorIndex = pats[tileIndex].getPixel(0, 0) & 0x0F;
        int off = palLine * Palette.PALETTE_SIZE_IN_ROM + colorIndex * 2;
        if (off < 0 || off + 1 >= palD.length) {
            return null;
        }
        // Mega Drive 0BGR (3 bits each): R=bits1-3 of byte1, G=bits5-7 of byte1, B=bits1-3 of byte0.
        int b0 = palD[off] & 0xFF;
        int b1 = palD[off + 1] & 0xFF;
        float r = ((b1 >> 1) & 0x07) / 7.0f;
        float g = ((b1 >> 5) & 0x07) / 7.0f;
        float b = ((b0 >> 1) & 0x07) / 7.0f;
        return new float[] {r, g, b};
    }

    /**
     * Renders Plane A (final Sonic foreground) for the interactive phase.
     * Shifted up by vScroll to match the VDP V_scroll_value behaviour.
     */
    private void renderPlaneA(GraphicsManager gm) {
        int[] fgMap = dataLoader.getAnimationMapping(FINAL_FRAME_INDEX);
        if (fgMap == null || fgMap.length == 0) {
            return;
        }

        int animPatternBase = dataLoader.getAnimPatternBase();
        // Center the foreground plane on the viewport.
        // xOffset() is 0 at native 320 — byte-identical at native width.
        int ox = xOffset();

        gm.beginPatternBatch();
        for (int row = 0; row < MAP_HEIGHT; row++) {
            int drawY = row * 8 - vScroll;
            for (int col = 0; col < MAP_WIDTH; col++) {
                int idx = row * MAP_WIDTH + col;
                if (idx >= fgMap.length) {
                    continue;
                }
                int word = fgMap[idx];
                if (word == 0) {
                    continue;
                }
                int tileIndex = word & 0x7FF;
                reusableDesc.set(word);
                gm.renderPatternWithId(animPatternBase + tileIndex, reusableDesc, ox + col * 8, drawY);
            }
        }
        gm.flushPatternBatch();
    }

    // -----------------------------------------------------------------------
    // Sprite renderer initialization
    // -----------------------------------------------------------------------

    /**
     * Initializes sprite renderers for each sprite type.
     * Each type gets its own renderer backed by the shared sprite pattern array.
     */
    private void initSpriteRenderers(GraphicsManager gm) {
        bannerFrames = Sonic3kTitleScreenMappings.createBannerFrames();
        andKnucklesFrames = Sonic3kTitleScreenMappings.createAndKnucklesFrames();
        selectionFrames = Sonic3kTitleScreenMappings.createSelectionFrames();
        copyrightFrames = Sonic3kTitleScreenMappings.createCopyrightFrame();
        sonicAnimFrames = Sonic3kTitleScreenMappings.createSonicAnimFrames();
        tailsPlaneFrames = Sonic3kTitleScreenMappings.createTailsPlaneFrames();

        int spriteBase = dataLoader.getSpritePatternBase();

        bannerRenderer = createSpriteRenderer(bannerFrames, spriteBase, gm);
        andKnucklesRenderer = createSpriteRenderer(andKnucklesFrames, spriteBase, gm);
        selectionRenderer = createSpriteRenderer(selectionFrames, spriteBase, gm);
        copyrightRenderer = createSpriteRenderer(copyrightFrames, spriteBase, gm);
        sonicAnimRenderer = createSpriteRenderer(sonicAnimFrames, spriteBase, gm);
        tailsPlaneRenderer = createSpriteRenderer(tailsPlaneFrames, spriteBase, gm);

        spritesInitialized = true;
        LOGGER.info("S3K title screen sprite renderers initialized");
    }

    private PatternSpriteRenderer createSpriteRenderer(
            List<SpriteMappingFrame> frames, int patternBase, GraphicsManager gm) {
        ObjectSpriteSheet sheet = new ObjectSpriteSheet(
                dataLoader.getSpritePatterns(),
                frames,
                -1,  // paletteIndex = -1 for absolute mode (each piece has its own palette)
                1    // frameDelay
        );
        PatternSpriteRenderer renderer = new PatternSpriteRenderer(sheet);
        renderer.ensurePatternsCached(gm, patternBase);
        return renderer;
    }

    // -----------------------------------------------------------------------
    // Inner state class
    // -----------------------------------------------------------------------

    /**
     * Lightweight animated sprite state for the S3K title screen.
     * Same pattern as S2's AnimatedSprite inner class.
     */
    private static class AnimatedSprite {
        int x;
        int y;
        int mappingFrame;
        boolean active;

        void reset() {
            x = 0;
            y = 0;
            mappingFrame = 0;
            active = false;
        }
    }
}
