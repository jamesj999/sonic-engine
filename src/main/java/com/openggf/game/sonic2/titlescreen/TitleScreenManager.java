package com.openggf.game.sonic2.titlescreen;

import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.audio.Sonic2SmpsConstants;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.titlescreen.SegaPaletteFade;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteFramePiece;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpritePieceRenderer;

import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.glClearColor;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.resources.Sonic2PlcService;

/**
 * Manages the Sonic 2 Title Screen with full intro animation.
 *
 * <p>Implements the Obj0E animation state machine from the disassembly, including:
 * <ul>
 *   <li>Sonic and Tails emerging from behind the emblem</li>
 *   <li>Flashing star sparkle effects at various positions</li>
 *   <li>Falling star after intro completes</li>
 *   <li>Water ripple scroll effect on bottom rows</li>
 *   <li>Star palette cycling</li>
 *   <li>Skip-intro on Start press</li>
 * </ul>
 *
 * <p>From the disassembly (SwScrl_Title):
 * <ul>
 *   <li>Lines 0-159 (rows 0-19): No horizontal scroll</li>
 *   <li>Lines 160-191 (rows 20-23): Scroll at -(Camera_X_pos) >> 2</li>
 *   <li>Lines 192-207 (rows 24-25): Scroll + ripple effect</li>
 * </ul>
 */
public class TitleScreenManager implements TitleScreenProvider {
    private static final Logger LOGGER = Logger.getLogger(TitleScreenManager.class.getName());

    private static TitleScreenManager instance;

    private final SonicConfigurationService configService;
    private final TitleScreenDataLoader dataLoader = new TitleScreenDataLoader();
    private final PatternDesc reusableDesc = new PatternDesc();

    private State state = State.INACTIVE;

    // Scrolling - camera starts at -0x280 per disassembly line 4447
    private int cameraX = -0x280;
    private int frameCounter = 0;

    // Fade timing
    private int fadeTimer = 0;
    private static final int FADE_DURATION = 16;

    // Intro text timing (from disassembly: Pal_FadeFromBlack ~22 frames, hold, Pal_FadeToBlack ~22 frames)
    private int introTextTimer = 0;
    private static final int INTRO_TEXT_FADE_DURATION = 22;
    private static final int INTRO_TEXT_HOLD_DURATION = 96;
    private boolean creditTextCached = false;
    private int segaLogoTimer = 0;
    private boolean segaPcmStarted = false;
    private SegaLogoFadePhase segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
    private int segaLogoFadeTimer = 0;
    private static final int SEGA_LOGO_FADE_FRAMES = 22;
    private static final int SEGA_LOGO_VISUAL_FRAMES = 106;
    private static final int SEGA_LOGO_PCM_WINDOW_FRAMES = 180;
    private static final int SEGA_SONIC_RUN_LEFT_FRAMES = 12;
    private static final int SEGA_SONIC_MID_WIPE_FRAMES = 42;
    private static final int SEGA_SONIC_RUN_RIGHT_START = SEGA_SONIC_RUN_LEFT_FRAMES + SEGA_SONIC_MID_WIPE_FRAMES;
    private static final int SEGA_SONIC_RUN_RIGHT_FRAMES = 12;

    private enum SegaLogoFadePhase {
        FADING_IN,
        ACTIVE,
        FADING_OUT
    }

    record SegaGiantSonicPose(int localFrame, int centerX, boolean hFlip) {
    }

    // Screen dimensions
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    // Scroll boundary (lines 0-159 are static, 160+ scroll)
    private static final int SCROLL_START_ROW = 20;  // line 160 / 8
    // Front logo occlusion starts around row 13 in the center and curves lower
    // toward the edges. Values are in screen pixels for a smoother curve.
    private static final int LOGO_OCCLUSION_BASE_Y = 13 * 8;
    private static final int LOGO_OCCLUSION_CURVE_PIXELS = 16;
    private static final int LOGO_OCCLUSION_COLUMN_WIDTH = 2;

    // Palette fade timing (from ObjC9 palette changer subtype 0 in disassembly)
    // Emblem palette (line 3) fades from black starting at frame 56: 21 steps × 2 frames = 42 frames
    private static final int EMBLEM_FADE_START = 56;
    private static final int EMBLEM_FADE_FRAMES = 42;

    // Sprite rendering
    private PatternSpriteRenderer spriteRenderer;
    private List<SpriteMappingFrame> titleMappingFrames;
    private boolean spritesInitialized = false;
    private boolean titlePlcPending;

    // --- Intro animation state ---
    private boolean introComplete = false;
    private boolean musicPlaying = false;
    private boolean sonicPaletteLoaded = false;

    // Sparkle sound: per-index guard ensures each position plays at most once.
    // Index 0 = init, indices 1-9 = the 9 flashing star positions.
    private final boolean[] sparklePlayedAt = new boolean[10];

    // Animated sprite state
    private final AnimatedSprite sonicSprite = new AnimatedSprite();
    private final AnimatedSprite tailsSprite = new AnimatedSprite();
    private final AnimatedSprite sonicHandSprite = new AnimatedSprite();
    private final AnimatedSprite tailsHandSprite = new AnimatedSprite();
    private final AnimatedSprite flashingStarSprite = new AnimatedSprite();
    private final AnimatedSprite fallingStarSprite = new AnimatedSprite();
    private final AnimatedSprite logoTopSprite = new AnimatedSprite();

    public TitleScreenManager() {
        this(null);
    }

    TitleScreenManager(SonicConfigurationService configService) {
        this.configService = configService;
    }

    private SonicConfigurationService configuration() {
        return configService != null ? configService : GameServices.configuration();
    }

    // -----------------------------------------------------------------------
    // Widescreen helpers
    // -----------------------------------------------------------------------

    /**
     * Current projection viewport width in game pixels (320 native, wider in
     * widescreen). Falls back to {@link #SCREEN_WIDTH} when headless.
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
     * Horizontal offset that centres the 320-wide foreground (logo, sprites,
     * text) in the viewport. Zero at native 320 — byte-identical. The background
     * plane (Plane B) is instead extended to fill the full width, so it does not
     * use this offset.
     */
    private int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    /**
     * Maps a projection-space X span {@code [mdX, mdX+mdW)} to a window-space
     * scissor span {@code [x, x+width)} using the active viewport. Returns null
     * when the span is fully clipped. Pure function — unit-tested.
     *
     * @param projWidth the live projection width the viewport represents
     */
    public static int[] scissorXSpan(int mdX, int mdW, int projWidth, int vpX, int vpW) {
        int clippedX = Math.max(0, mdX);
        int clippedX2 = Math.min(projWidth, mdX + mdW);
        if (clippedX2 <= clippedX) {
            return null;
        }
        float scaleX = (float) vpW / projWidth;
        int x = vpX + (int) Math.floor(clippedX * scaleX);
        int width = Math.max(1, (int) Math.ceil((clippedX2 - clippedX) * scaleX));
        return new int[] {x, width};
    }

    // Animation frame sequences (from Ani_obj0E in disassembly)
    // Ani_obj0E_Sonic: duration=1, frames: 5, 6, 7, end ($FA)
    // (Skipping frame 8 per fixBugs - it's a prototype frame missing the right arm)
    private static final int[] ANIM_SONIC = {5, 6, 7};
    // Ani_obj0E_Tails: duration=1, frames: 0, 1, 2, 3, 4, end ($FA)
    private static final int[] ANIM_TAILS = {0, 1, 2, 3, 4};
    // Ani_obj0E_FlashingStar: duration=1, frames: 0xC, 0xD, 0xE, 0xD, 0xC, end ($FA)
    private static final int[] ANIM_FLASHING_STAR = {0x0C, 0x0D, 0x0E, 0x0D, 0x0C};
    // Ani_obj0E_FallingStar: duration=3, frames: 0xC, 0xF, loop ($FF)
    private static final int[] ANIM_FALLING_STAR = {0x0C, 0x0F};

    // Position arrays from disassembly (VDP coords, subtract 128 for screen coords)
    // Sonic moves every 4 frames through these positions
    private static final int[][] SONIC_POSITIONS = {
            {128 + 136, 128 + 80},
            {128 + 128, 128 + 64},
            {128 + 120, 128 + 48},
            {128 + 118, 128 + 38},
            {128 + 122, 128 + 30},
            {128 + 128, 128 + 26},
            {128 + 132, 128 + 25},
            {128 + 136, 128 + 24},
    };

    // Tails moves every 4 frames through these positions
    private static final int[][] TAILS_POSITIONS = {
            {128 + 87, 128 + 72},
            {128 + 83, 128 + 56},
            {128 + 78, 128 + 44},
            {128 + 76, 128 + 38},
            {128 + 74, 128 + 34},
            {128 + 73, 128 + 33},
            {128 + 72, 128 + 32},
    };

    // Flashing star positions (9 positions)
    private static final int[][] FLASHING_STAR_POSITIONS = {
            {128 + 90,  128 + 114},
            {128 + 240, 128 + 120},
            {128 + 178, 128 + 177},
            {128 + 286, 128 + 34},
            {128 + 64,  128 + 99},
            {128 + 256, 128 + 96},
            {128 + 141, 128 + 187},
            {128 + 64,  128 + 43},
            {128 + 229, 128 + 135},
    };

    // Sonic hand positions (3 positions, cycles every 4 frames)
    private static final int[][] SONIC_HAND_POSITIONS = {
            {128 + 195, 128 + 65},
            {128 + 192, 128 + 66},
            {128 + 193, 128 + 65},
    };

    // Tails hand positions (2 positions, cycles every 4 frames)
    private static final int[][] TAILS_HAND_POSITIONS = {
            {128 + 140, 128 + 80},
            {128 + 141, 128 + 81},
    };

    // Ripple scroll data (from SwScrl_RippleData in disassembly)
    private static final byte[] RIPPLE_DATA = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2,
    };
    private int rippleIndex = 0;

    // CyclingPal_TitleStar palette data (6 colors from "Title Star Cycle.bin")
    // Sega format: 0x0BBB where B = blue, G = green, R = red (each 0-E in steps of 2)
    private static final int[] CYCLING_PAL_TITLE_STAR = {
            0x0E64, 0x0E86, 0x0E64, 0x0EA8, 0x0E64, 0x0ECA,
    };
    private int starCycleIndex = 0;

    // Sonic sub-state tracking
    private int sonicSubState = 0;
    private int sonicAnimFrame = 0;
    private int sonicAnimDuration = 0;

    // Tails sub-state tracking
    private int tailsSubState = 0;
    private int tailsAnimFrame = 0;
    private int tailsAnimDuration = 0;

    // Flashing star sub-state
    private int flashingStarSubState = 0;
    private int flashingStarAnimFrame = 0;
    private int flashingStarAnimDuration = 0;
    private int flashingStarPosIndex = 0;
    private int flashingStarWaitCounter = 0;

    // Falling star
    private int fallingStarAnimFrame = 0;
    private int fallingStarAnimDuration = 0;
    private int fallingStarLifetime = 0;

    // Sonic hand sub-state
    private int sonicHandPosIndex = 0;
    private int sonicHandPosCounter = 0;

    // Tails hand sub-state
    private int tailsHandPosIndex = 0;
    private int tailsHandPosCounter = 0;

    public static synchronized TitleScreenManager getInstance() {
        if (instance == null) {
            instance = new TitleScreenManager();
        }
        return instance;
    }

    @Override
    public void initialize() {
        LOGGER.info("Initializing title screen");

        // Load data if not already loaded
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }

        titlePlcPending = !queueTitlePlc();

        // Force palette re-upload on next draw
        dataLoader.resetCache();

        state = State.SEGA_LOGO;
        fadeTimer = 0;
        introTextTimer = 0;
        segaLogoTimer = 0;
        segaPcmStarted = false;
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        creditTextCached = false;
        cameraX = -0x280;  // From disassembly: move.w #-$280,(Camera_X_pos).w
        frameCounter = 0;
        introComplete = false;
        musicPlaying = false;
        sonicPaletteLoaded = false;
        rippleIndex = 0;
        starCycleIndex = 0;
        spritesInitialized = false;

        // Reset all animated sprites
        sonicSprite.reset();
        tailsSprite.reset();
        sonicHandSprite.reset();
        tailsHandSprite.reset();
        flashingStarSprite.reset();
        fallingStarSprite.reset();
        logoTopSprite.reset();

        // Reset sub-states
        sonicSubState = 0;
        sonicAnimFrame = 0;
        sonicAnimDuration = 0;
        tailsSubState = 0;
        tailsAnimFrame = 0;
        tailsAnimDuration = 0;
        flashingStarSubState = 0;
        flashingStarAnimFrame = 0;
        flashingStarAnimDuration = 0;
        flashingStarPosIndex = 0;
        flashingStarWaitCounter = 0;
        fallingStarAnimFrame = 0;
        fallingStarAnimDuration = 0;
        fallingStarLifetime = 0;
        sonicHandPosIndex = 0;
        sonicHandPosCounter = 0;
        tailsHandPosIndex = 0;
        tailsHandPosCounter = 0;
        java.util.Arrays.fill(sparklePlayedAt, false);

        // Initialize Sonic at starting position (below emblem, hidden)
        sonicSprite.active = true;
        sonicSprite.mappingFrame = 5;
        sonicSprite.x = 128 + 144;
        sonicSprite.y = 128 + 96;

        // Initialize flashing star at first position
        // From disasm Obj0E_FlashingStar_Init: position (128+128, 128+40), counter=4
        flashingStarSprite.active = true;
        flashingStarSprite.mappingFrame = 0x0C;
        flashingStarSprite.x = 128 + 128;
        flashingStarSprite.y = 128 + 40;
        flashingStarSubState = 0; // animate first
        flashingStarWaitCounter = 4; // from disasm: Init sets counter=4 (first wait is shorter)

        // Initialize logo top (non-Japanese = frame $A)
        logoTopSprite.active = true;
        logoTopSprite.mappingFrame = 0x0A;
        logoTopSprite.x = 128 + 160;  // 128 + 320/2
        logoTopSprite.y = 128 + 104;

        LOGGER.info("Title screen initialized, entering SEGA_LOGO state");
    }

    private boolean queueTitlePlc() {
        try {
            Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
            if (plcService != null) {
                plcService.transact(Sonic2PlcService.replaceOperation(0));
            }
            return true;
        } catch (Exception ignored) {
            // The presentation renderer also runs without a gameplay module in focused tests.
            return false;
        }
    }

    @Override
    public void update(InputHandler input) {
        if (titlePlcPending) {
            titlePlcPending = !queueTitlePlc();
            if (titlePlcPending) return;
        }
        switch (state) {
            case SEGA_LOGO -> updateSegaLogo(input);
            case INTRO_TEXT_FADE_IN -> updateIntroTextFadeIn(input);
            case INTRO_TEXT_HOLD -> updateIntroTextHold(input);
            case INTRO_TEXT_FADE_OUT -> updateIntroTextFadeOut(input);
            case FADE_IN -> updateFadeIn(input);
            case ACTIVE -> updateActive(input);
            case INACTIVE, EXITING -> { }
        }
    }

    private void updateSegaLogo(InputHandler input) {
        int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
        if (updateSegaLogoFadeIn()) {
            return;
        }
        if (updateSegaLogoFadeOut()) {
            return;
        }

        segaLogoTimer++;
        if (!segaPcmStarted && segaLogoTimer >= SEGA_LOGO_VISUAL_FRAMES) {
            segaPcmStarted = true;
            GameServices.audio().playMusic(Sonic2SmpsConstants.CMD_SEGA);
        }
        boolean skipable = segaLogoTimer >= SEGA_LOGO_VISUAL_FRAMES;
        boolean autoEnd = segaLogoTimer >= SEGA_LOGO_VISUAL_FRAMES + SEGA_LOGO_PCM_WINDOW_FRAMES;
        if (autoEnd || (skipable && confirmPressed(input, jumpKey))) {
            beginSegaLogoFadeOut();
        }
    }

    private boolean updateSegaLogoFadeIn() {
        if (segaLogoFadePhase != SegaLogoFadePhase.FADING_IN) {
            return false;
        }
        segaLogoFadeTimer++;
        if (segaLogoFadeTimer < SEGA_LOGO_FADE_FRAMES) {
            return true;
        }
        segaLogoFadePhase = SegaLogoFadePhase.ACTIVE;
        segaLogoFadeTimer = 0;
        return false;
    }

    private boolean updateSegaLogoFadeOut() {
        if (segaLogoFadePhase != SegaLogoFadePhase.FADING_OUT) {
            return false;
        }
        segaLogoFadeTimer++;
        if (segaLogoFadeTimer < SEGA_LOGO_FADE_FRAMES) {
            return true;
        }
        beginIntroTextScreen();
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        return true;
    }

    private void beginSegaLogoFadeOut() {
        if (segaLogoFadePhase == SegaLogoFadePhase.FADING_OUT) {
            return;
        }
        GameServices.audio().stopSegaPcm();
        segaLogoFadePhase = SegaLogoFadePhase.FADING_OUT;
        segaLogoFadeTimer = 0;
    }

    private void beginIntroTextScreen() {
        segaLogoTimer = 0;
        segaPcmStarted = false;
        introTextTimer = 0;
        state = State.INTRO_TEXT_FADE_IN;
        playSparkleAtIndex(0);
        LOGGER.info("SEGA screen complete, entering INTRO_TEXT_FADE_IN state");
    }

    /**
     * Keyboard Jump press or gamepad confirm (Start / any face action button),
     * matching {@link com.openggf.game.MasterTitleScreen}'s gamepad-aware confirm gate.
     */
    private static boolean confirmPressed(InputHandler input, int jumpKey) {
        return input.isKeyPressed(jumpKey) || input.logical().menuAccept();
    }

    private void skipIntroText() {
        introTextTimer = 0;
        creditTextCached = false;
        // Force palette re-upload for main title screen
        dataLoader.resetCache();
        state = State.FADE_IN;
        LOGGER.info("Intro text skipped, entering FADE_IN state");
    }

    private void updateIntroTextFadeIn(InputHandler input) {
        int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
        if (confirmPressed(input, jumpKey)) {
            skipIntroText();
            return;
        }
        introTextTimer++;
        if (introTextTimer >= INTRO_TEXT_FADE_DURATION) {
            introTextTimer = 0;
            state = State.INTRO_TEXT_HOLD;
            LOGGER.fine("Intro text entered HOLD state");
        }
    }

    private void updateIntroTextHold(InputHandler input) {
        int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
        if (confirmPressed(input, jumpKey)) {
            skipIntroText();
            return;
        }
        introTextTimer++;
        if (introTextTimer >= INTRO_TEXT_HOLD_DURATION) {
            introTextTimer = 0;
            state = State.INTRO_TEXT_FADE_OUT;
            LOGGER.fine("Intro text entered FADE_OUT state");
        }
    }

    private void updateIntroTextFadeOut(InputHandler input) {
        int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
        if (confirmPressed(input, jumpKey)) {
            skipIntroText();
            return;
        }
        introTextTimer++;
        if (introTextTimer >= INTRO_TEXT_FADE_DURATION) {
            introTextTimer = 0;
            creditTextCached = false;
            // Force palette re-upload for main title screen
            dataLoader.resetCache();
            state = State.FADE_IN;
            LOGGER.info("Intro text complete, entering FADE_IN state");
        }
    }

    private void updateFadeIn(InputHandler input) {
        fadeTimer++;
        // Auto-scroll even during fade-in
        cameraX++;
        frameCounter++;

        updateIntroAnimation(input);

        if (fadeTimer >= FADE_DURATION) {
            state = State.ACTIVE;
            LOGGER.fine("Title screen entered ACTIVE state");
        }
    }

    private void updateActive(InputHandler input) {
        // Auto-scroll background
        cameraX++;
        frameCounter++;

        updateIntroAnimation(input);

        // Check for jump/start press to exit (only after intro completes)
        if (introComplete) {
            int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
            if (confirmPressed(input, jumpKey)) {
                state = State.EXITING;
                LOGGER.info("Title screen exiting");
            }
        }
    }

    /**
     * Main intro animation update, matching the Obj0E state machine from the disassembly.
     */
    private void updateIntroAnimation(InputHandler input) {
        // Check for skip (Start pressed before frame 288)
        if (!introComplete) {
            int jumpKey = configuration().getInt(SonicConfiguration.JUMP);
            if (confirmPressed(input, jumpKey)) {
                skipToFinalState();
                return;
            }
        }

        // Update Sonic state machine
        updateSonic();

        // Update Tails (only active after frame 192)
        if (tailsSprite.active) {
            updateTails();
        }

        // Update flashing star
        if (flashingStarSprite.active) {
            updateFlashingStar();
        }

        // Update Sonic hand
        if (sonicHandSprite.active) {
            updateSonicHand();
        }

        // Update Tails hand
        if (tailsHandSprite.active) {
            updateTailsHand();
        }

        // Update falling star
        if (fallingStarSprite.active) {
            updateFallingStar();
        }

        // Update ripple animation (every 8 frames)
        if ((frameCounter & 7) == 0) {
            rippleIndex--;
        }

        // Star sparkle palette cycling (after intro complete, every 8 frames)
        if (introComplete && (frameCounter & 7) == 0) {
            starCycleIndex += 2;
            if (starCycleIndex >= CYCLING_PAL_TITLE_STAR.length * 2) {
                starCycleIndex = 0;
            }
        }
    }

    private void updateSonic() {
        switch (sonicSubState) {
            case 0 -> { // Obj0E_Sonic_FadeInAndPlayMusic - wait until frame 56
                if (frameCounter >= 56) {
                    sonicSubState = 1;
                    // Play title music
                    if (!musicPlaying) {
                        musicPlaying = true;
                        GameServices.audio().playMusic(Sonic2Music.TITLE.id);
                    }
                }
            }
            case 1 -> { // Obj0E_Sonic_LoadPalette - wait until frame 128
                if (frameCounter >= 128) {
                    sonicSubState = 2;
                    sonicPaletteLoaded = true;
                    sonicSprite.posArrayIndex = 0;
                    sonicSprite.posCounter = 0;
                    // Apply first position from array
                    sonicSprite.x = SONIC_POSITIONS[0][0];
                    sonicSprite.y = SONIC_POSITIONS[0][1];
                }
            }
            case 2 -> { // Obj0E_Sonic_Move - advance position every 4 frames
                sonicSprite.posCounter++;
                if ((sonicSprite.posCounter & 3) == 0) {
                    sonicSprite.posArrayIndex++;
                    if (sonicSprite.posArrayIndex >= SONIC_POSITIONS.length) {
                        // Done moving, start animation
                        sonicSubState = 3;
                        sonicAnimFrame = 0;
                        sonicAnimDuration = 0;
                        sonicSprite.mappingFrame = ANIM_SONIC[0];
                    } else {
                        sonicSprite.x = SONIC_POSITIONS[sonicSprite.posArrayIndex][0];
                        sonicSprite.y = SONIC_POSITIONS[sonicSprite.posArrayIndex][1];
                    }
                }
            }
            case 3 -> { // Obj0E_Animate (Sonic) - play animation script
                sonicAnimDuration++;
                if (sonicAnimDuration > 1) { // duration = 1 (2 frames per step)
                    sonicAnimDuration = 0;
                    sonicAnimFrame++;
                    if (sonicAnimFrame >= ANIM_SONIC.length) {
                        // Animation finished
                        sonicSubState = 4;
                        sonicSprite.mappingFrame = 0x12; // Final static frame
                        // Spawn Sonic's hand
                        sonicHandSprite.active = true;
                        sonicHandSprite.mappingFrame = 9;
                        sonicHandSprite.x = 128 + 197;
                        sonicHandSprite.y = 128 + 63;
                        sonicHandPosIndex = 0;
                        sonicHandPosCounter = 0;
                    } else {
                        sonicSprite.mappingFrame = ANIM_SONIC[sonicAnimFrame];
                    }
                }
            }
            case 4 -> { // Obj0E_Sonic_SpawnTails - wait for frame 192
                if (frameCounter >= 192) {
                    sonicSubState = 5;
                    // Spawn Tails
                    tailsSprite.active = true;
                    tailsSprite.mappingFrame = 1; // anim=1 starts at frame 0 of Tails anim
                    tailsSprite.x = 128 + 88;
                    tailsSprite.y = 128 + 88;
                    tailsSprite.posArrayIndex = 0;
                    tailsSprite.posCounter = 0;
                    tailsSubState = 0; // move state
                    tailsAnimFrame = 0;
                    tailsAnimDuration = 0;
                    // Apply first position
                    tailsSprite.x = TAILS_POSITIONS[0][0];
                    tailsSprite.y = TAILS_POSITIONS[0][1];
                }
            }
            case 5 -> { // Obj0E_Sonic_FlashBackground - wait for frame 288
                if (frameCounter >= 288) {
                    sonicSubState = 6;
                    introComplete = true;
                    // Flash palette line 3 white is handled in rendering
                }
            }
            case 6 -> { // Obj0E_Sonic_SpawnFallingStar - wait for frame 464 (NTSC)
                if (frameCounter >= 464) {
                    sonicSubState = 7;
                    // Spawn falling star
                    fallingStarSprite.active = true;
                    fallingStarSprite.mappingFrame = 0x0C;
                    fallingStarSprite.x = 128 + 240;
                    fallingStarSprite.y = 128 + 0;
                    fallingStarLifetime = 140;
                    fallingStarAnimFrame = 0;
                    fallingStarAnimDuration = 0;
                }
            }
            case 7 -> { // Obj0E_Sonic_MakeStarSparkle - star palette cycling (handled globally)
                // Just display
            }
        }
    }

    private void updateTails() {
        switch (tailsSubState) {
            case 0 -> { // Obj0E_Tails_Move
                tailsSprite.posCounter++;
                if ((tailsSprite.posCounter & 3) == 0) {
                    tailsSprite.posArrayIndex++;
                    if (tailsSprite.posArrayIndex >= TAILS_POSITIONS.length) {
                        // Done moving, start animation
                        tailsSubState = 1;
                        tailsAnimFrame = 0;
                        tailsAnimDuration = 0;
                        // Tails animation: frames 0,1,2,3,4
                        tailsSprite.mappingFrame = ANIM_TAILS[0];
                    } else {
                        tailsSprite.x = TAILS_POSITIONS[tailsSprite.posArrayIndex][0];
                        tailsSprite.y = TAILS_POSITIONS[tailsSprite.posArrayIndex][1];
                    }
                }
            }
            case 1 -> { // Obj0E_Animate (Tails)
                tailsAnimDuration++;
                if (tailsAnimDuration > 1) { // duration = 1
                    tailsAnimDuration = 0;
                    tailsAnimFrame++;
                    if (tailsAnimFrame >= ANIM_TAILS.length) {
                        // Animation finished - spawn hand, stay at last frame
                        tailsSubState = 2;
                        tailsSprite.mappingFrame = ANIM_TAILS[ANIM_TAILS.length - 1];
                        // Spawn Tails' hand
                        tailsHandSprite.active = true;
                        tailsHandSprite.mappingFrame = 0x13;
                        tailsHandSprite.x = 128 + 143;
                        tailsHandSprite.y = 128 + 85;
                        LOGGER.info("Tails hand spawned at frame " + frameCounter +
                                " pos=(" + tailsHandSprite.x + "," + tailsHandSprite.y + ")");
                        tailsHandPosIndex = 0;
                        tailsHandPosCounter = 0;
                    } else {
                        tailsSprite.mappingFrame = ANIM_TAILS[tailsAnimFrame];
                    }
                }
            }
            case 2 -> {
                // Static display
            }
        }
    }

    private void updateFlashingStar() {
        switch (flashingStarSubState) {
            case 0 -> { // Animate (sparkle cycle)
                flashingStarAnimDuration++;
                if (flashingStarAnimDuration > 1) { // duration = 1 (2 frames per step)
                    flashingStarAnimDuration = 0;
                    flashingStarAnimFrame++;
                    if (flashingStarAnimFrame >= ANIM_FLASHING_STAR.length) {
                        // Animation complete ($FA terminator) - advance to Wait sub-state.
                        // Wait counter was already set by Init (4) or Move (6).
                        flashingStarSubState = 1;
                        flashingStarAnimFrame = 0;
                    } else {
                        flashingStarSprite.mappingFrame = ANIM_FLASHING_STAR[flashingStarAnimFrame];
                    }
                }
            }
            case 1 -> { // Wait
                flashingStarWaitCounter--;
                if (flashingStarWaitCounter < 0) {
                    flashingStarSubState = 2; // move to next position
                }
            }
            case 2 -> { // Move to next position
                // From disasm: Obj0E_FlashingStar_Move reads position THEN increments index.
                // addq.w #4,d0 / cmpi.w #$24,d0 / lea Positions-4(pc,d0.w),a1
                // First call: d0=0→4, reads Positions[0]. Ninth call: d0=32→36, 36>=$24 → delete.
                if (flashingStarPosIndex >= FLASHING_STAR_POSITIONS.length) {
                    // All positions visited, delete star
                    flashingStarSprite.active = false;
                    // Force-stop any lingering SFX (sparkle FM channel may not self-terminate)
                    GameServices.audio().stopAllSfx();
                } else {
                    flashingStarSprite.x = FLASHING_STAR_POSITIONS[flashingStarPosIndex][0];
                    flashingStarSprite.y = FLASHING_STAR_POSITIONS[flashingStarPosIndex][1];
                    flashingStarPosIndex++;
                    flashingStarSprite.mappingFrame = ANIM_FLASHING_STAR[0];
                    flashingStarAnimFrame = 0;
                    flashingStarAnimDuration = 0;
                    flashingStarWaitCounter = 6; // from disasm: Move sets counter=6
                    flashingStarSubState = 0; // back to animate

                    // Play sparkle sound (posIndex already incremented, so it's 1-9)
                    playSparkleAtIndex(flashingStarPosIndex);
                }
            }
        }
    }

    private void updateSonicHand() {
        sonicHandPosCounter++;
        if ((sonicHandPosCounter & 3) == 0) {
            sonicHandPosIndex++;
            if (sonicHandPosIndex >= SONIC_HAND_POSITIONS.length) {
                // Done moving, stay at last position
                sonicHandPosIndex = SONIC_HAND_POSITIONS.length - 1;
            }
            sonicHandSprite.x = SONIC_HAND_POSITIONS[sonicHandPosIndex][0];
            sonicHandSprite.y = SONIC_HAND_POSITIONS[sonicHandPosIndex][1];
        }
    }

    private void updateTailsHand() {
        tailsHandPosCounter++;
        if ((tailsHandPosCounter & 3) == 0) {
            tailsHandPosIndex++;
            if (tailsHandPosIndex >= TAILS_HAND_POSITIONS.length) {
                // Done moving, stay at last position
                tailsHandPosIndex = TAILS_HAND_POSITIONS.length - 1;
            }
            tailsHandSprite.x = TAILS_HAND_POSITIONS[tailsHandPosIndex][0];
            tailsHandSprite.y = TAILS_HAND_POSITIONS[tailsHandPosIndex][1];
        }
    }

    private void updateFallingStar() {
        fallingStarLifetime--;
        if (fallingStarLifetime <= 0) {
            fallingStarSprite.active = false;
            return;
        }
        // Move: X -= 2, Y += 1
        fallingStarSprite.x -= 2;
        fallingStarSprite.y += 1;

        // Animate (duration=3: change every 4 frames)
        fallingStarAnimDuration++;
        if (fallingStarAnimDuration > 3) {
            fallingStarAnimDuration = 0;
            fallingStarAnimFrame++;
            if (fallingStarAnimFrame >= ANIM_FALLING_STAR.length) {
                fallingStarAnimFrame = 0; // loop
            }
            fallingStarSprite.mappingFrame = ANIM_FALLING_STAR[fallingStarAnimFrame];
        }
    }

    /**
     * Skip intro animation - set all sprites to their final positions.
     * Matches TitleScreen_SetFinalState from the disassembly.
     */
    private void skipToFinalState() {
        introComplete = true;

        // Sonic final position
        sonicSprite.mappingFrame = 0x12;
        sonicSprite.x = 128 + 136;
        sonicSprite.y = 128 + 24;
        sonicSubState = 7; // skip to star sparkle state

        // Sonic's hand
        sonicHandSprite.active = true;
        sonicHandSprite.mappingFrame = 9;
        sonicHandSprite.x = 128 + 193;
        sonicHandSprite.y = 128 + 65;
        sonicHandPosIndex = SONIC_HAND_POSITIONS.length - 1;

        // Tails final position
        tailsSprite.active = true;
        tailsSprite.mappingFrame = 4;
        tailsSprite.x = 128 + 72;
        tailsSprite.y = 128 + 32;
        tailsSubState = 2;

        // Tails' hand
        tailsHandSprite.active = true;
        tailsHandSprite.mappingFrame = 0x13;
        tailsHandSprite.x = 128 + 141;
        tailsHandSprite.y = 128 + 81;
        tailsHandPosIndex = TAILS_HAND_POSITIONS.length - 1;

        // Flashing star is deleted
        flashingStarSprite.active = false;
        // Force-stop any lingering SFX (sparkle FM channel may not self-terminate)
        GameServices.audio().stopAllSfx();

        // Load Sonic palette
        sonicPaletteLoaded = true;

        // Play title music if not already playing
        if (!musicPlaying) {
            musicPlaying = true;
            GameServices.audio().playMusic(Sonic2Music.TITLE.id);
        }

        // Force palette re-upload to apply Sonic palette
        dataLoader.resetCache();
    }

    @Override
    public void draw() {
        // Ensure data is loaded and cached
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }

        GraphicsManager gm = GameServices.graphics();
        if (gm == null || gm.isHeadlessMode()) {
            return;
        }

        if (state == State.SEGA_LOGO) {
            drawSegaLogo(gm);
            return;
        }

        // Intro text states render their own screen
        if (state == State.INTRO_TEXT_FADE_IN || state == State.INTRO_TEXT_HOLD || state == State.INTRO_TEXT_FADE_OUT) {
            drawIntroText(gm);
            return;
        }

        dataLoader.cacheToGpu();

        // Initialize sprite renderer if needed
        if (!spritesInitialized && dataLoader.getSpritePatterns() != null) {
            initSpriteRenderer(gm);
        }

        // Determine plane visibility based on palette fade timing from the disassembly.
        // Palette line 2 (Plane B background) stays black until frame 288.
        // Palette line 3 (Plane A emblem) starts fading from black at frame 56.
        boolean showBackground = introComplete;
        boolean showEmblem = frameCounter >= EMBLEM_FADE_START || introComplete;

        // Calculate emblem darkening (simulates ObjC9 palette fade from black)
        float emblemDarkness = 0.0f;
        if (showEmblem && !introComplete) {
            int emblemAge = frameCounter - EMBLEM_FADE_START;
            if (emblemAge < EMBLEM_FADE_FRAMES) {
                emblemDarkness = 1.0f - (float) emblemAge / EMBLEM_FADE_FRAMES;
            }
        }

        // --- Render Plane B (background, palette line 2) ---
        if (showBackground) {
            gm.beginPatternBatch();
            renderPlaneBNonRipple(gm);
            gm.flushPatternBatch();
            // Scanline ripple uses immediate GL scissor state, so the non-ripple commands
            // must be flushed before any per-line scissor rendering starts.
            gm.flushScreenSpace();
            renderPlaneBRipple(gm);
        }

        // --- Render behind-logo sprites ---
        // Sonic/Tails emerge behind the SONIC logo, so these must draw before Plane A.
        if (spriteRenderer != null && spriteRenderer.isReady()) {
            gm.beginPatternBatch();
            if (fallingStarSprite.active) {
                drawSprite(fallingStarSprite);
            }
            if (sonicSprite.active && sonicPaletteLoaded && shouldRenderSonicBehindLogo()) {
                drawSprite(sonicSprite);
            }
            if (tailsSprite.active && shouldRenderTailsBehindLogo()) {
                drawSprite(tailsSprite);
            }
            gm.flushPatternBatch();
        }

        // --- Render Plane A (emblem/logo, palette line 3) ---
        if (showEmblem) {
            gm.beginPatternBatch();
            renderPlaneA(gm);
            // Draw logo-top sprite in the back layer so Sonic/Tails remain in front of it.
            if (logoTopSprite.active) {
                drawSpriteHighPriorityPieces(logoTopSprite);
            }
            gm.flushPatternBatch();
        }

        // Emblem fade overlay: darken planes to simulate palette fading from black.
        // Placed AFTER planes but BEFORE sprites so characters are always visible.
        if (emblemDarkness > 0.0f) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI,
                    -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    0.0f, 0.0f, 0.0f, emblemDarkness,
                    0, 0, viewportWidth(), SCREEN_HEIGHT
            ));
        }

        // White flash at frame 288 (disassembly fills palette line 3 with $EEE)
        if (introComplete && frameCounter >= 288 && frameCounter < 296) {
            float flashAlpha = 1.0f - (float) (frameCounter - 288) / 8.0f;
            if (flashAlpha > 0.0f) {
                gm.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                        1.0f, 1.0f, 1.0f, flashAlpha,
                        0, 0, viewportWidth(), SCREEN_HEIGHT
                ));
            }
        }

        // --- Render middle sprites (between upper logo and lower-logo occlusion) ---
        gm.beginPatternBatch();

        if (spriteRenderer != null && spriteRenderer.isReady()) {
            if (sonicSprite.active && sonicPaletteLoaded && !shouldRenderSonicBehindLogo()) {
                drawSprite(sonicSprite);
            }

            if (tailsSprite.active && !shouldRenderTailsBehindLogo()) {
                drawSprite(tailsSprite);
            }
        }

        gm.flushPatternBatch();

        // Re-render the curved lower portion of the logo in front so Sonic/Tails
        // pass behind the lower banner while staying in front of the top.
        if (showEmblem) {
            // Curved occlusion uses per-column scissor + immediate flushes.
            // Flush queued passes first so only the occlusion draw executes under scissor.
            gm.flushScreenSpace();
            renderPlaneAFrontCurvedOcclusion(gm);
        }

        // --- Render top-most sprites ---
        gm.beginPatternBatch();

        if (spriteRenderer != null && spriteRenderer.isReady()) {
            if (sonicHandSprite.active) {
                drawSprite(sonicHandSprite);
            }

            if (tailsHandSprite.active) {
                drawSprite(tailsHandSprite);
            }

            if (flashingStarSprite.active) {
                drawSprite(flashingStarSprite);
            }
        }

        gm.flushPatternBatch();

        // Global fade overlay (for FADE_IN state at startup)
        if (state == State.FADE_IN) {
            float fadeAmount = 1.0f - (float) fadeTimer / FADE_DURATION;
            if (fadeAmount > 0.0f) {
                gm.registerCommand(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        -1,
                        GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                        0.0f, 0.0f, 0.0f, fadeAmount,
                        0, 0, viewportWidth(), SCREEN_HEIGHT
                ));
            }
        }
    }

    private void drawSegaLogo(GraphicsManager gm) {
        dataLoader.cacheSegaLogoToGpu();
        dataLoader.applySegaLogoPaletteForFrame(segaLogoTimer, segaLogoFadeMode(), segaLogoFadeTimer);
        int[] map = dataLoader.getSegaLogoMap();
        if (map == null || map.length == 0) {
            return;
        }
        gm.beginPatternBatch();
        int width = dataLoader.getSegaLogoWidth();
        int height = dataLoader.getSegaLogoHeight();
        for (int ty = 0; ty < height; ty++) {
            for (int tx = 0; tx < width; tx++) {
                int word = map[ty * width + tx];
                if (word == 0) {
                    continue;
                }
                reusableDesc.set(word);
                gm.renderPatternWithId(
                        TitleScreenDataLoader.SEGA_LOGO_PATTERN_BASE + reusableDesc.getPatternIndex(),
                        reusableDesc,
                        xOffset() + tx * 8,
                        ty * 8);
            }
        }
        gm.flushPatternBatch();
        drawSegaGiantSonic(gm);
    }

    private SegaPaletteFade.Mode segaLogoFadeMode() {
        if (segaLogoFadePhase == SegaLogoFadePhase.FADING_IN) {
            return SegaPaletteFade.Mode.FROM_BLACK;
        }
        if (segaLogoFadePhase == SegaLogoFadePhase.FADING_OUT) {
            return SegaPaletteFade.Mode.TO_BLACK;
        }
        return SegaPaletteFade.Mode.NONE;
    }

    private void drawSegaGiantSonic(GraphicsManager gm) {
        SegaGiantSonicPose pose = resolveSegaGiantSonicPose();
        if (pose == null) {
            return;
        }
        dataLoader.cacheSegaGiantSonicToGpu();
        SpriteMappingFrame mapping = dataLoader.getSegaGiantSonicMappingFrame(pose.localFrame());
        if (mapping == null) {
            return;
        }
        int originX = xOffset() + pose.centerX();
        int originY = SCREEN_HEIGHT / 2;
        SpritePieceRenderer.renderPieces(
                mapping.pieces(),
                originX,
                originY,
                TitleScreenDataLoader.SEGA_GIANT_SONIC_PATTERN_BASE,
                2,
                pose.hFlip(),
                false,
                (patternIndex, hFlip, vFlip, paletteIndex, drawX, drawY) ->
                        drawSegaSonicTile(gm, patternIndex, hFlip, vFlip, paletteIndex, drawX, drawY));
    }

    SegaGiantSonicPose resolveSegaGiantSonicPose() {
        if (segaLogoFadePhase != SegaLogoFadePhase.ACTIVE || segaLogoTimer <= 0) {
            return null;
        }
        int frame = segaLogoTimer;
        if (frame <= SEGA_SONIC_RUN_LEFT_FRAMES) {
            int step = Math.max(0, frame - 1);
            return new SegaGiantSonicPose(step & 3, 360 - 32 * (step + 1), true);
        } else if (frame >= SEGA_SONIC_RUN_RIGHT_START
                && frame < SEGA_SONIC_RUN_RIGHT_START + SEGA_SONIC_RUN_RIGHT_FRAMES) {
            int step = frame - SEGA_SONIC_RUN_RIGHT_START;
            return new SegaGiantSonicPose(step & 3, -64 + 32 * (step + 1), false);
        }
        return null;
    }

    private void drawSegaSonicTile(GraphicsManager gm, int patternIndex, boolean hFlip, boolean vFlip,
                                   int paletteIndex, int drawX, int drawY) {
        int tileOffset = patternIndex - TitleScreenDataLoader.SEGA_GIANT_SONIC_PATTERN_BASE;
        if (tileOffset < 0 || tileOffset >= dataLoader.getSegaGiantSonicPatternCount()) {
            return;
        }
        reusableDesc.set(patternIndex & 0x7FF);
        reusableDesc.setHFlip(hFlip);
        reusableDesc.setVFlip(vFlip);
        reusableDesc.setPaletteIndex(paletteIndex);
        reusableDesc.setPriority(true);
        gm.renderPatternWithId(patternIndex, reusableDesc, drawX, drawY);
    }

    /**
     * Draws the "SONIC AND MILES 'TAILS' PROWER IN" intro text screen.
     *
     * <p>From the disassembly (s2.asm lines 4271-4323):
     * The credit text font is Nemesis compressed at ArtNem_CreditText.
     * Each uppercase letter = 2 tiles wide (left half + right half).
     * Text is drawn to Plane A using charset encoding.
     *
     * <p>Character encoding (creditText macro from s2.asm:14602-14610):
     * The charset remapping defines tile indices for each character:
     * <ul>
     *   <li>Uppercase letter: 2 tiles (left half, right half) = 16 pixels</li>
     *   <li>'I': special case, only 1 tile (0x11) = 8 pixels</li>
     *   <li>Space: 1 tile (0x00, blank) = 8 pixels</li>
     *   <li>Apostrophe: 1 tile (0x38) = 8 pixels</li>
     * </ul>
     *
     * <p>Text layout (from off_B2B0):
     * <pre>
     * "SONIC"                 col 15, row 9
     * "AND"                   col 17, row 12
     * "MILES 'TAILS' PROWER"  col 3,  row 15
     * "IN"                    col 18, row 18
     * </pre>
     */
    private void drawIntroText(GraphicsManager gm) {
        // Cache credit text patterns on first draw
        if (!creditTextCached) {
            dataLoader.cacheCreditTextToGpu();
            creditTextCached = true;
        }

        // Render text lines
        gm.beginPatternBatch();
        drawCreditTextLine(gm, "SONIC", 15, 9);
        drawCreditTextLine(gm, "AND", 17, 12);
        drawCreditTextLine(gm, "MILES 'TAILS' PROWER", 3, 15);
        drawCreditTextLine(gm, "IN", 18, 18);
        gm.flushPatternBatch();

        // Apply fade overlay
        float fadeAmount = 0.0f;
        if (state == State.INTRO_TEXT_FADE_IN) {
            fadeAmount = 1.0f - (float) introTextTimer / INTRO_TEXT_FADE_DURATION;
        } else if (state == State.INTRO_TEXT_FADE_OUT) {
            fadeAmount = (float) introTextTimer / INTRO_TEXT_FADE_DURATION;
        }
        if (fadeAmount > 0.0f) {
            gm.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI,
                    -1,
                    GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    0.0f, 0.0f, 0.0f, fadeAmount,
                    0, 0, viewportWidth(), SCREEN_HEIGHT
            ));
        }
    }

    /**
     * Intro text charset tile mapping (from s2.asm lines 14603-14610).
     *
     * <p>Each entry is {upperTile, lowerTile} for the left and right halves of the character.
     * 'I' is special: only 1 tile (0x11), indicated by lowerTile == -1.
     * After 'I', tile indices shift down by 1 since 'I' only uses one slot.
     *
     * <pre>
     * charset '@',"\x3A\1\3\5\7\9\xB\xD\xF\x11\x12\x14\x16\x18\x1A\x1C\x1E\x20\x22\x24\x26\x28\x2A\x2C\x2E\x30\x32"
     * charset 'a',"\2\4\6\8\xA\xC\xE\x10\x11\x13\x15\x17\x19\x1B\x1D\x1F\x21\x23\x25\x27\x29\x2B\x2D\x2F\x31\x33"
     * charset '\H' (apostrophe) → 0x38
     * charset ' ' → 0x00
     * </pre>
     */
    private static final int[][] INTRO_CHARSET = {
            // A-H: sequential pairs starting at tile 1
            {0x01, 0x02}, // A
            {0x03, 0x04}, // B
            {0x05, 0x06}, // C
            {0x07, 0x08}, // D
            {0x09, 0x0A}, // E
            {0x0B, 0x0C}, // F
            {0x0D, 0x0E}, // G
            {0x0F, 0x10}, // H
            // I: single tile (narrow letter, both halves share tile 0x11)
            {0x11, -1},   // I (1 tile only)
            // J-Z: shifted down by 1 because I used only 1 tile
            {0x12, 0x13}, // J
            {0x14, 0x15}, // K
            {0x16, 0x17}, // L
            {0x18, 0x19}, // M
            {0x1A, 0x1B}, // N
            {0x1C, 0x1D}, // O
            {0x1E, 0x1F}, // P
            {0x20, 0x21}, // Q
            {0x22, 0x23}, // R
            {0x24, 0x25}, // S
            {0x26, 0x27}, // T
            {0x28, 0x29}, // U
            {0x2A, 0x2B}, // V
            {0x2C, 0x2D}, // W
            {0x2E, 0x2F}, // X
            {0x30, 0x31}, // Y
            {0x32, 0x33}, // Z
    };

    /**
     * Draws a single line of credit text at the given tile column and row.
     * Each byte from the creditText macro becomes one 8×8 tile in the nametable.
     *
     * <p>Character widths:
     * <ul>
     *   <li>Most letters: 2 tiles (16 px) — uppercase + lowercase halves</li>
     *   <li>'I': 1 tile (8 px) — narrow, only one tile</li>
     *   <li>Space: 1 tile (8 px) — blank tile 0x00</li>
     *   <li>Apostrophe: 1 tile (8 px) — tile 0x38</li>
     * </ul>
     */
    private void drawCreditTextLine(GraphicsManager gm, String text, int startCol, int row) {
        int x = startCol * 8 + xOffset(); // centre the credit text (0 at native)
        int y = row * 8;

        reusableDesc.set(0); // Clear: no flip, palette 0, no priority

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                // Space = 1 tile (8 pixels), tile 0x00 is blank
                x += 8;
                continue;
            }

            if (ch == '\'') {
                // Apostrophe = 1 tile at 0x38 (8 pixels)
                int patternId = TitleScreenDataLoader.CREDIT_TEXT_PATTERN_BASE + 0x38;
                gm.renderPatternWithId(patternId, reusableDesc, x, y);
                x += 8;
                continue;
            }

            if (ch >= 'A' && ch <= 'Z') {
                int letterIndex = ch - 'A';
                int[] tiles = INTRO_CHARSET[letterIndex];
                // Render left (uppercase) tile
                int leftPatternId = TitleScreenDataLoader.CREDIT_TEXT_PATTERN_BASE + tiles[0];
                gm.renderPatternWithId(leftPatternId, reusableDesc, x, y);
                x += 8;

                if (tiles[1] >= 0) {
                    // Render right (lowercase) tile
                    int rightPatternId = TitleScreenDataLoader.CREDIT_TEXT_PATTERN_BASE + tiles[1];
                    gm.renderPatternWithId(rightPatternId, reusableDesc, x, y);
                    x += 8;
                }
                continue;
            }

            // Unknown character - skip 8 pixels
            x += 8;
        }
    }

    private void initSpriteRenderer(GraphicsManager gm) {
        titleMappingFrames = TitleScreenMappings.createFrames();
        ObjectSpriteSheet spriteSheet = new ObjectSpriteSheet(
                dataLoader.getSpritePatterns(),
                titleMappingFrames,
                -1,  // paletteIndex = -1 for absolute mode (each piece has its own palette)
                1    // frameDelay
        );
        spriteRenderer = new PatternSpriteRenderer(spriteSheet);
        spriteRenderer.ensurePatternsCached(gm, TitleScreenDataLoader.SPRITE_PATTERN_BASE);
        spritesInitialized = true;
        LOGGER.info("Title screen sprite renderer initialized with " + titleMappingFrames.size() + " frames");

        // Diagnostic: verify Tails hand sprite (frame 0x13) has enough patterns
        // Frame 19 uses tile 0x2A4 (6 tiles for 2x3), needs at least 0x2AA (682) patterns
        int spriteCount = dataLoader.getSpritePatterns() != null ? dataLoader.getSpritePatterns().length : 0;
        int tailsHandTileStart = 0x2A4;
        int tailsHandTileEnd = tailsHandTileStart + 6; // 2 wide × 3 tall
        if (spriteCount < tailsHandTileEnd) {
            LOGGER.warning("Tails hand sprite needs patterns up to " + tailsHandTileEnd +
                    " but only " + spriteCount + " loaded - Tails hand will NOT render!");
        } else {
            LOGGER.info("Tails hand sprite patterns OK: need " + tailsHandTileEnd + ", have " + spriteCount);
        }
    }

    /**
     * Draws a sprite at its VDP position (subtracting 128 for screen coords).
     */
    private void drawSprite(AnimatedSprite sprite) {
        int screenX = sprite.x - 128 + xOffset(); // centre with the logo (0 at native)
        int screenY = sprite.y - 128;
        spriteRenderer.drawFrameIndex(sprite.mappingFrame, screenX, screenY);
    }

    /**
     * Sonic only uses the behind-logo path during pre-visible setup frames.
     */
    private boolean shouldRenderSonicBehindLogo() {
        return !sonicPaletteLoaded;
    }

    /**
     * Tails should render in the front pass during entry and final poses.
     */
    private boolean shouldRenderTailsBehindLogo() {
        return false;
    }

    /**
     * Draw only high-priority pieces from a sprite mapping frame.
     */
    private void drawSpriteHighPriorityPieces(AnimatedSprite sprite) {
        if (spriteRenderer == null || titleMappingFrames == null) {
            return;
        }
        if (sprite.mappingFrame < 0 || sprite.mappingFrame >= titleMappingFrames.size()) {
            return;
        }

        SpriteMappingFrame frame = titleMappingFrames.get(sprite.mappingFrame);
        if (frame == null || frame.pieces() == null || frame.pieces().isEmpty()) {
            return;
        }

        int screenX = sprite.x - 128 + xOffset(); // centre with the logo (0 at native)
        int screenY = sprite.y - 128;
        List<? extends SpriteFramePiece> pieces = frame.pieces();

        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).priority()) {
                spriteRenderer.drawFramePieceByIndex(sprite.mappingFrame, i, screenX, screenY, false, false);
            }
        }
    }

    /**
     * Renders Plane B non-ripple rows (0-23).
     * Rows 0-19 are static, rows 20-23 scroll with camera.
     */
    private void renderPlaneBNonRipple(GraphicsManager gm) {
        int[] map = dataLoader.getPlaneBMap();
        if (map == null || map.length == 0) {
            return;
        }

        int planeWidth = dataLoader.getPlaneBWidth();    // 64

        // Calculate scroll offset for scrolling rows
        int pixelScroll = -(cameraX) >> 2;
        int tileScrollOffset = pixelScroll >> 3;
        int subTileOffset = pixelScroll & 7;

        // Number of screen tile columns needed to fill the viewport. At native
        // 320 this is 40 — byte-identical; wider viewports reveal more of the
        // 64-tile-wide plane (Plane B is the background and fills the screen
        // rather than being centred).
        int screenCols = (viewportWidth() + 7) / 8;

        for (int ty = 0; ty < 24 && ty * 8 < SCREEN_HEIGHT; ty++) {
            if (ty < SCROLL_START_ROW) {
                // Static rows: render columns left to right, wrapping into the
                // 64-wide plane so the extra widescreen columns are filled.
                int baseIndex = ty * planeWidth;
                for (int tx = 0; tx < screenCols; tx++) {
                    int planeTile = ((tx % planeWidth) + planeWidth) % planeWidth;
                    int idx = baseIndex + planeTile;
                    if (idx < 0 || idx >= map.length) {
                        continue;
                    }
                    int word = map[idx];
                    if (word == 0) {
                        continue;
                    }
                    reusableDesc.set(word);
                    int patternId = TitleScreenDataLoader.PATTERN_BASE + reusableDesc.getPatternIndex();
                    gm.renderPatternWithId(patternId, reusableDesc, tx * 8, ty * 8);
                }
            } else {
                // Scrolling rows (20-23): scroll with camera, no ripple
                int baseIndex = ty * planeWidth;
                for (int screenTile = -1; screenTile < screenCols + 1; screenTile++) {
                    int planeTile = screenTile - tileScrollOffset;
                    planeTile = ((planeTile % planeWidth) + planeWidth) % planeWidth;

                    int idx = baseIndex + planeTile;
                    if (idx < 0 || idx >= map.length) {
                        continue;
                    }
                    int word = map[idx];
                    if (word == 0) {
                        continue;
                    }
                    reusableDesc.set(word);
                    int patternId = TitleScreenDataLoader.PATTERN_BASE + reusableDesc.getPatternIndex();
                    int drawX = screenTile * 8 + subTileOffset;
                    gm.renderPatternWithId(patternId, reusableDesc, drawX, ty * 8);
                }
            }
        }
    }

    /**
     * Renders Plane B ripple rows (24-25, lines 192-207) with per-scanline horizontal scroll.
     *
     * <p>From SwScrl_Title in the disassembly: each of the 16 scanlines in the water area
     * gets its own horizontal scroll value = base_scroll + ripple_data[index]. This creates
     * the wavy water distortion effect.
     *
     * <p>Implementation uses GL scissor to clip rendering to one scanline at a time,
     * allowing each line to have a different horizontal offset.
     */
    private void renderPlaneBRipple(GraphicsManager gm) {
        int[] map = dataLoader.getPlaneBMap();
        if (map == null || map.length == 0) {
            return;
        }

        int planeWidth = dataLoader.getPlaneBWidth();   // 64
        int planeHeight = dataLoader.getPlaneBHeight(); // 28

        // Base scroll for scrolling rows
        int pixelScroll = -(cameraX) >> 2;

        // Number of screen tile columns to fill the viewport (40 at native 320).
        int screenCols = (viewportWidth() + 7) / 8;

        // Ripple index, wrapped
        int currentRippleIndex = rippleIndex & 0x1F;

        // Viewport info for scissor coordinate mapping
        int vpX = gm.getViewportX();
        int vpY = gm.getViewportY();
        int vpW = gm.getViewportWidth();
        int vpH = gm.getViewportHeight();
        float scaleY = (float) vpH / SCREEN_HEIGHT;

        // Render each of the 16 scanlines (lines 192-207) individually
        for (int line = 0; line < 16; line++) {
            int mdY = 192 + line;           // Mega Drive scanline Y (top-down)
            int ty = mdY >> 3;              // Tile row (24 or 25)
            if (ty >= planeHeight) {
                continue;
            }

            // Per-line ripple offset from SwScrl_RippleData
            int rippleIdx = (currentRippleIndex + line) & 0x3F;
            int rippleValue = 0;
            if (rippleIdx < RIPPLE_DATA.length) {
                rippleValue = RIPPLE_DATA[rippleIdx];
            }

            int linePixelScroll = pixelScroll + rippleValue;
            int lineTileScroll = linePixelScroll >> 3;
            int lineSubTile = linePixelScroll & 7;

            // Calculate scissor rectangle in window space (OpenGL Y-up)
            // Game Y=mdY (top-down) → OpenGL Y = SCREEN_HEIGHT - mdY - 1 (bottom-up)
            int scissorY = vpY + (int) ((SCREEN_HEIGHT - mdY - 1) * scaleY);
            int scissorH = Math.max(1, (int) Math.ceil(scaleY));

            gm.enableScissor(vpX, scissorY, vpW, scissorH);

            gm.beginPatternBatch();

            int baseIndex = ty * planeWidth;
            for (int screenTile = -1; screenTile < screenCols + 1; screenTile++) {
                int planeTile = screenTile - lineTileScroll;
                planeTile = ((planeTile % planeWidth) + planeWidth) % planeWidth;

                int idx = baseIndex + planeTile;
                if (idx < 0 || idx >= map.length) {
                    continue;
                }
                int word = map[idx];
                if (word == 0) {
                    continue;
                }
                reusableDesc.set(word);
                int patternId = TitleScreenDataLoader.PATTERN_BASE + reusableDesc.getPatternIndex();
                int drawX = screenTile * 8 + lineSubTile;
                // Render the full tile at the tile row's Y position; scissor clips to one line
                gm.renderPatternWithId(patternId, reusableDesc, drawX, ty * 8);
            }

            gm.flushPatternBatch();
            // Apply scissor clipping to this line by flushing while the scissor
            // state is active. Title screen rendering is screen-space.
            gm.flushScreenSpace();
            gm.disableScissor();
        }
    }

    /**
     * Renders Plane A (logo/emblem). No scrolling, skip transparent tiles (word == 0).
     */
    private void renderPlaneA(GraphicsManager gm) {
        int[] map = dataLoader.getPlaneAMap();
        if (map == null || map.length == 0) {
            return;
        }

        int width = dataLoader.getPlaneAWidth();   // 40
        int height = dataLoader.getPlaneAHeight();  // 28
        int ox = xOffset(); // centre the 320-wide logo (0 at native)

        for (int ty = 0; ty < height && ty * 8 < SCREEN_HEIGHT; ty++) {
            int baseIndex = ty * width;
            for (int tx = 0; tx < width; tx++) {
                int idx = baseIndex + tx;
                if (idx < 0 || idx >= map.length) {
                    continue;
                }
                int word = map[idx];
                if (!planeATileVisible(word)) {
                    continue;
                }
                reusableDesc.set(word);
                int patternId = TitleScreenDataLoader.PATTERN_BASE + reusableDesc.getPatternIndex();
                gm.renderPatternWithId(patternId, reusableDesc, tx * 8 + ox, ty * 8);
            }
        }
    }

    /**
     * Whether a Plane A map word draws this frame.
     *
     * <p>Word 0 is the blank tile. Palette line 0 words are the "@ 1992 SEGA"
     * copyright line ({@link TitleScreenCopyrightText}); in the ROM that line
     * is cleared by {@code clearRAM Normal_palette} during setup and stays black
     * until {@code Obj0E_Sonic_LoadPalette} copies {@code Pal_133EC} into it at
     * frame 128, so the text pops in together with Sonic's palette. The other
     * palette lines fade through {@code ObjC9}, which the emblem gating models.
     */
    private boolean planeATileVisible(int word) {
        if (word == 0) {
            return false;
        }
        int paletteLine = (word >> 13) & 0x03;
        return paletteLine != 0 || sonicPaletteLoaded;
    }

    /**
     * Renders only the lower curved occlusion portion of Plane A.
     *
     * <p>Uses per-column scissor rectangles so the front-occlusion boundary can
     * be curved in pixel space instead of snapping to full tile rows.
     */
    private void renderPlaneAFrontCurvedOcclusion(GraphicsManager gm) {
        int[] map = dataLoader.getPlaneAMap();
        if (map == null || map.length == 0) {
            return;
        }

        int width = dataLoader.getPlaneAWidth();   // 40
        int height = dataLoader.getPlaneAHeight();  // 28
        int ox = xOffset(); // logo is centred, so shift the occlusion with it

        // mdX iterates the logo's own 320-wide space; the curve centre stays at
        // the logo centre. Scissor/draw are shifted into projection space by ox.
        for (int mdX = 0; mdX < SCREEN_WIDTH; mdX += LOGO_OCCLUSION_COLUMN_WIDTH) {
            int mdW = Math.min(LOGO_OCCLUSION_COLUMN_WIDTH, SCREEN_WIDTH - mdX);
            int startY = getLogoOcclusionStartPixel(mdX + (mdW >> 1));
            if (startY >= SCREEN_HEIGHT) {
                continue;
            }

            int tx = mdX >> 3;
            if (tx < 0 || tx >= width) {
                continue;
            }

            if (!enableMdScissorRect(gm, mdX + ox, startY, mdW, SCREEN_HEIGHT - startY)) {
                continue;
            }

            gm.beginPatternBatch();
            int startTileRow = Math.max(0, startY >> 3);
            renderPlaneAColumn(gm, map, width, height, tx, startTileRow);
            gm.flushPatternBatch();
            gm.flushScreenSpace();
            gm.disableScissor();
        }
    }

    private void renderPlaneAColumn(GraphicsManager gm, int[] map, int width, int height, int tx, int startTileRow) {
        int ox = xOffset(); // centre the 320-wide logo (0 at native)
        for (int ty = startTileRow; ty < height && ty * 8 < SCREEN_HEIGHT; ty++) {
            int idx = ty * width + tx;
            if (idx < 0 || idx >= map.length) {
                continue;
            }
            int word = map[idx];
            if (!planeATileVisible(word)) {
                continue;
            }
            reusableDesc.set(word);
            int patternId = TitleScreenDataLoader.PATTERN_BASE + reusableDesc.getPatternIndex();
            gm.renderPatternWithId(patternId, reusableDesc, tx * 8 + ox, ty * 8);
        }
    }

    /**
     * Enables a GL scissor for a projection-space rectangle. {@code mdX} is in
     * projection space (0..viewportWidth), so the X axis is mapped against the
     * live projection width — not a hardcoded 320 — so the curved occlusion
     * clips correctly at widescreen. The Y axis is unchanged (height is fixed).
     */
    private boolean enableMdScissorRect(GraphicsManager gm, int mdX, int mdY, int mdW, int mdH) {
        if (mdW <= 0 || mdH <= 0) {
            return false;
        }

        int clippedY = Math.max(0, mdY);
        int clippedY2 = Math.min(SCREEN_HEIGHT, mdY + mdH);
        if (clippedY2 <= clippedY) {
            return false;
        }

        int vpX = gm.getViewportX();
        int vpY = gm.getViewportY();
        int vpW = gm.getViewportWidth();
        int vpH = gm.getViewportHeight();

        int[] xSpan = scissorXSpan(mdX, mdW, viewportWidth(), vpX, vpW);
        if (xSpan == null) {
            return false;
        }

        float scaleY = (float) vpH / SCREEN_HEIGHT;
        int scissorY = vpY + (int) Math.floor((SCREEN_HEIGHT - clippedY2) * scaleY);
        int scissorH = Math.max(1, (int) Math.ceil((clippedY2 - clippedY) * scaleY));

        gm.enableScissor(xSpan[0], scissorY, xSpan[1], scissorH);
        return true;
    }

    private int getLogoOcclusionStartPixel(int screenX) {
        // Curve around the emblem center. Center stays near row 13; edges dip lower.
        double center = (SCREEN_WIDTH - 1) * 0.5;
        double halfWidth = 104.0;
        double norm = Math.abs((screenX - center) / halfWidth);
        norm = Math.min(1.0, norm);
        int curveOffset = (int) Math.round(norm * norm * LOGO_OCCLUSION_CURVE_PIXELS);
        return LOGO_OCCLUSION_BASE_Y + curveOffset;
    }

    @Override
    public void setClearColor() {
        // During the intro (before frame 288), all palette lines start black.
        // Palette line 2 (Plane B backdrop) stays black until introComplete.
        if (!introComplete) {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            return;
        }
        // After intro, use VDP register $8720 = palette 2, color 0
        if (!dataLoader.isDataLoaded()) {
            dataLoader.loadData();
        }
        Palette bgPal = dataLoader.getBackgroundPalette();
        if (bgPal != null) {
            Palette.Color backdrop = bgPal.getColor(0);
            glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
        } else {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    /**
     * Plays the sparkle sound for a specific index (0=init, 1-9=star positions).
     * Each index can only play once per title screen session, preventing any
     * possibility of repeated playback regardless of state machine behavior.
     */
    private void playSparkleAtIndex(int index) {
        if (index < 0 || index >= sparklePlayedAt.length) {
            LOGGER.warning("Sparkle index out of range: " + index + " at frame " + frameCounter);
            return;
        }
        if (sparklePlayedAt[index]) {
            LOGGER.fine("Sparkle #" + index + " already played, blocked at frame " + frameCounter);
            return;
        }
        sparklePlayedAt[index] = true;
        LOGGER.fine("Playing sparkle #" + index + " at frame " + frameCounter);
        GameServices.audio().playSfx(Sonic2Sfx.SPARKLE.id);
    }

    @Override
    public void reset() {
        GameServices.audio().stopSegaPcm();
        state = State.INACTIVE;
        cameraX = -0x280;
        frameCounter = 0;
        fadeTimer = 0;
        introTextTimer = 0;
        segaLogoTimer = 0;
        segaPcmStarted = false;
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        creditTextCached = false;
        introComplete = false;
        musicPlaying = false;
        sonicPaletteLoaded = false;
        spritesInitialized = false;
        LOGGER.info("Title screen reset to inactive");
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
        return TitleScreenAction.ONE_PLAYER;
    }

    /**
     * Lightweight animated sprite state for the title screen intro.
     */
    private static class AnimatedSprite {
        int x;
        int y;
        int mappingFrame;
        boolean active;
        int posArrayIndex;
        int posCounter;

        void reset() {
            x = 0;
            y = 0;
            mappingFrame = 0;
            active = false;
            posArrayIndex = 0;
            posCounter = 0;
        }
    }
}
