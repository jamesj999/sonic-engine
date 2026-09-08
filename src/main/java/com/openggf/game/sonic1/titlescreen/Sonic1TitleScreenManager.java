package com.openggf.game.sonic1.titlescreen;

import com.openggf.control.InputHandler;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.GameServices;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.sonic1.scroll.SwScrlGhz;
import com.openggf.game.titlescreen.SegaPaletteFade;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Palette;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.scroll.M68KMath;

import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.glClearColor;

/**
 * Manages the Sonic 1 Title Screen.
 *
 * <p>Implements the two phases of the title screen:
 * <ol>
 *   <li>"SONIC TEAM PRESENTS" - White text on black background, fade in/hold/fade out</li>
 *   <li>Main Title Screen - Logo overlay, animated Sonic sprite, "PRESS START BUTTON" flashing</li>
 * </ol>
 *
 * <p>From the disassembly:
 * <ul>
 *   <li>Obj0E (TitleSonic): Starts at y=$DE, waits 30 frames, moves up 8px/frame to y=$96, then loops animation</li>
 *   <li>Obj0F (PSBTM): "PRESS START BUTTON" flashes every $1F frames, "TM" is static</li>
 *   <li>Ani_TSon: duration=7, frames 0-7, loops back to frame 2</li>
 *   <li>Ani_PSBTM: duration=$1F, frames 0,1 (visible/blank), loops</li>
 * </ul>
 */
public class Sonic1TitleScreenManager implements TitleScreenProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1TitleScreenManager.class.getName());

    private static Sonic1TitleScreenManager instance;

    private final SonicConfigurationService configService;
    private final Sonic1TitleScreenDataLoader dataLoader = new Sonic1TitleScreenDataLoader();
    private final PatternDesc reusableDesc = new PatternDesc();

    private State state = State.INACTIVE;

    // Frame counter for overall timing
    private int frameCounter = 0;

    // Fade timing. GM_Title fades the assembled title screen in with PaletteFadeIn:
    // 22 VBlank periods (move.w #22-1,d4), each running FadeIn_AddColor once over
    // all 64 colours. Only WaitForVBlank, the fade step and RunPLC run inside that
    // loop; ExecuteObjects, DeformLayers and PalCycle_Title stay frozen until
    // Tit_MainLoop starts.
    private int fadeTimer = 0;
    private static final int FADE_DURATION = SegaPaletteFade.ROM_FADE_FRAMES;

    // Intro text timing: the "SONIC TEAM PRESENTS" screen uses the same
    // PaletteFadeIn / PaletteFadeOut pair (22 frames each) and holds for 96 frames.
    private int introTextTimer = 0;
    private static final int INTRO_TEXT_FADE_DURATION = SegaPaletteFade.ROM_FADE_FRAMES;
    private static final int INTRO_TEXT_HOLD_DURATION = 96;
    private int segaLogoTimer = 0;
    private boolean segaPcmStarted = false;
    private SegaLogoFadePhase segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
    private int segaLogoFadeTimer = 0;
    private final Sonic1TitleScreenDataLoader.SegaLogoPaletteCycleState segaLogoPaletteCycle =
            new Sonic1TitleScreenDataLoader.SegaLogoPaletteCycleState();
    private static final int SEGA_LOGO_FADE_FRAMES = 22;
    private static final int SEGA_LOGO_PALETTE_FRAMES = 76;
    private static final int SEGA_LOGO_PCM_FRAMES = 98;
    private static final int SEGA_LOGO_POST_CHANT_FRAMES = 30;

    private enum SegaLogoFadePhase {
        FADING_IN,
        ACTIVE,
        FADING_OUT
    }

    // Screen dimensions
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    static final int SEGA_LOGO_SCAN_X = 8 * 8;
    static final int SEGA_LOGO_SCAN_Y = 10 * 8;

    // Widescreen helpers ---------------------------------------------------

    /**
     * Returns the current projection viewport width in game pixels.
     * At native 320 this equals SCREEN_WIDTH exactly.
     * Uses GraphicsManager.getProjectionWidth() which is propagated each frame
     * by Engine — same approach as AbstractResultsScreen.xOffset().
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
     */
    private int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    /**
     * Number of 8-px tile columns to fill the viewport.
     * Includes one extra column on each side so the sub-tile H-scroll offset
     * never leaves a visible gap.  At native 320 this equals 42 (matching the
     * existing literal), so the background is byte-identical at native width.
     *
     * <p>Package-visible for unit tests.
     */
    public static int bgTileColumns(int viewportWidth) {
        return (viewportWidth + 7) / 8 + 2;
    }

    // End widescreen helpers -----------------------------------------------

    // Sprite rendering
    private PatternSpriteRenderer sonicSpriteRenderer;
    private PatternSpriteRenderer creditTextSpriteRenderer;
    private PatternSpriteRenderer tmSpriteRenderer;
    private List<SpriteMappingFrame> titleMappingFrames;
    private boolean spritesInitialized = false;

    // TitleSonic (Obj0E) state machine
    // Routine 0: init, Routine 2: delay 30 frames, Routine 4: move up, Routine 6: animate
    private int sonicRoutine = 0;
    private int sonicScreenY = 0xDE; // VDP y position (screen coord, not +128 since we handle directly)
    private int sonicDelayTimer = 29; // 30 frames (0-indexed)
    private int sonicAnimFrame = 0;
    private int sonicAnimTimer = 0;
    private static final int SONIC_ANIM_DURATION = 7; // Duration from Ani_TSon
    private static final int SONIC_FINAL_Y = 0x96;
    private static final int SONIC_X = 0xF0; // From TSon_Main: obX = $F0

    // PSB ("PRESS START BUTTON") state
    private int psbAnimFrame = 0; // 0 = visible, 1 = blank
    private int psbAnimTimer = 0;
    private static final int PSB_FLASH_DURATION = 0x1F; // From Ani_PSBTM
    // PSB position (VDP coords from disassembly, subtract 128 for screen)
    private static final int PSB_X = 0xD0 - 128; // = 80
    private static final int PSB_Y = 0x130 - 128; // = 176

    // TM position
    private static final int TM_X = 0x170 - 128; // = 240
    private static final int TM_Y = 0xF8 - 128; // = 120

    // Plane A split row for sprite layering.
    // The original game uses M_PSB_Limiter (30 blank sprites) to mask TitleSonic
    // below screen Y 104 via VDP per-scanline sprite limit. Screen Y 104 corresponds
    // to Plane A tile row 9: (104 - startPixelY) / 8 = (104 - 32) / 8 = 9.
    private static final int PLANE_A_SPLIT_ROW = 9;

    // Top screen row of the M_PSB_Limiter band. Obj0F frame 2 (v_ttlsonichide) sits
    // at obScreenY = $80+$B0 (screen Y 176) and its first row of ten 4x4 pieces is at
    // piece offset -$48, i.e. screen Y 176-72 = 104. Ten 32px-wide sprites exhaust the
    // VDP's 320-pixel-per-line budget, so every sprite after it in the SAT is dropped
    // on those lines. TitleSonic has obPriority 1 and therefore loses; the "TM" object
    // shares priority 0 but occupies an earlier slot, so it still draws.
    private static final int SPRITE_LIMITER_TOP_Y = 104;

    // Background scroll using GHZ parallax scroll handler
    private int bgCameraX = 0;
    private SwScrlGhz scrollHandler;
    private final int[] horizScrollBuf = new int[M68KMath.VISIBLE_LINES];

    // Palette cycling (water animation)
    private int palCycleTimer = 0;
    private int palCycleFrame = 0;
    private boolean titlePlcPending;
    private static final int PAL_CYCLE_SPEED = 6; // Update every 6 frames (from disassembly)
    private static final int PAL_CYCLE_FRAMES = 4; // 4 cycle frames (32 bytes / 8 bytes per frame)

    // Credit text rendering
    private boolean creditTextCached = false;

    public Sonic1TitleScreenManager() {
        this(null);
    }

    Sonic1TitleScreenManager(SonicConfigurationService configService) {
        this.configService = configService;
    }

    public static Sonic1TitleScreenManager getInstance() {
        if (instance == null) {
            instance = new Sonic1TitleScreenManager();
        }
        return instance;
    }

    private SonicConfigurationService configuration() {
        return configService != null ? configService : GameServices.configuration();
    }

    /**
     * Returns the title screen data loader. Used by the credits text renderer
     * to access the credit text font patterns loaded during title screen init.
     */
    public Sonic1TitleScreenDataLoader getDataLoader() {
        return dataLoader;
    }

    @Override
    public void initialize() {
        LOGGER.info("Initializing Sonic 1 Title Screen");

        // Load all data from ROM
        if (!dataLoader.loadData()) {
            LOGGER.warning("Failed to load S1 title screen data");
            return;
        }

        titlePlcPending = !queueTitlePlc();

        // Reset all state
        frameCounter = 0;
        fadeTimer = 0;
        introTextTimer = 0;
        segaLogoTimer = 0;
        segaPcmStarted = false;
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        segaLogoPaletteCycle.reset();
        creditTextCached = false;
        spritesInitialized = false;
        bgCameraX = 0;
        palCycleTimer = 0;
        palCycleFrame = 0;
        scrollHandler = new SwScrlGhz();

        // Reset TitleSonic
        sonicRoutine = 0;
        sonicScreenY = 0xDE;
        sonicDelayTimer = 29;
        sonicAnimFrame = 0;
        sonicAnimTimer = 0;

        // Reset PSB
        psbAnimFrame = 0;
        psbAnimTimer = 0;

        // Reset data loader cache so patterns get re-uploaded
        dataLoader.resetCache();
        dataLoader.resetSegaLogoPaletteCycle();

        state = State.SEGA_LOGO;

        // Apply title palette
        applyTitlePalette();

        LOGGER.info("Sonic 1 Title Screen initialized");
    }

    private boolean queueTitlePlc() {
        try {
            Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
            if (plcService != null) {
                plcService.replaceQueued(0);
            }
            return true;
        } catch (Exception ignored) {
            // The presentation renderer also runs without a gameplay module in focused tests.
            return false;
        }
    }

    @Override
    public void update(InputHandler input) {
        if (state == State.INACTIVE) {
            return;
        }
        if (titlePlcPending) {
            titlePlcPending = !queueTitlePlc();
            if (titlePlcPending) return;
        }

        frameCounter++;

        int startKey = configuration().getInt(SonicConfiguration.JUMP);

        switch (state) {
            case SEGA_LOGO:
                updateSegaLogo(input, startKey);
                break;

            case INTRO_TEXT_FADE_IN:
                introTextTimer++;
                if (introTextTimer >= INTRO_TEXT_FADE_DURATION) {
                    state = State.INTRO_TEXT_HOLD;
                    introTextTimer = 0;
                }
                // Skip intro on Start/Jump press
                if (confirmPressed(input, startKey)) {
                    skipToMainScreen();
                }
                break;

            case INTRO_TEXT_HOLD:
                introTextTimer++;
                if (introTextTimer >= INTRO_TEXT_HOLD_DURATION) {
                    state = State.INTRO_TEXT_FADE_OUT;
                    introTextTimer = 0;
                }
                if (confirmPressed(input, startKey)) {
                    skipToMainScreen();
                }
                break;

            case INTRO_TEXT_FADE_OUT:
                introTextTimer++;
                if (introTextTimer >= INTRO_TEXT_FADE_DURATION) {
                    transitionToMainScreen();
                }
                if (confirmPressed(input, startKey)) {
                    skipToMainScreen();
                }
                break;

            case FADE_IN:
                // PaletteFadeIn owns the frame here: no object execution, background
                // scroll or palette cycle until the fade loop returns to Tit_MainLoop.
                fadeTimer++;
                if (fadeTimer >= FADE_DURATION) {
                    state = State.ACTIVE;
                    fadeTimer = 0;
                }
                break;

            case ACTIVE:
                updateMainScreen();
                if (confirmPressed(input, startKey)) {
                    state = State.EXITING;
                }
                break;

            case EXITING:
                // GameLoop handles the exit
                break;

            default:
                break;
        }
    }

    private void skipToMainScreen() {
        transitionToMainScreen();
    }

    /**
     * Keyboard Start/Jump press or gamepad confirm (Start / any face action button),
     * matching {@link com.openggf.game.MasterTitleScreen}'s gamepad-aware confirm gate.
     */
    private static boolean confirmPressed(InputHandler input, int startKey) {
        return input.isKeyPressed(startKey) || input.logical().menuAccept();
    }

    private void updateSegaLogo(InputHandler input, int startKey) {
        if (updateSegaLogoFadeIn()) {
            return;
        }
        if (updateSegaLogoFadeOut()) {
            return;
        }

        segaLogoTimer++;
        if (segaLogoTimer <= SEGA_LOGO_PALETTE_FRAMES) {
            dataLoader.advanceSegaLogoPaletteCycle(segaLogoPaletteCycle);
        }
        int chantStart = SEGA_LOGO_PALETTE_FRAMES;
        int skipWindowStart = chantStart + SEGA_LOGO_PCM_FRAMES;
        int autoEnd = skipWindowStart + SEGA_LOGO_POST_CHANT_FRAMES;

        if (!segaPcmStarted && segaLogoTimer >= chantStart) {
            segaPcmStarted = true;
            GameServices.audio().playMusic(com.openggf.game.sonic1.audio.Sonic1SmpsConstants.CMD_SEGA);
        }
        if (segaLogoTimer >= autoEnd || (segaLogoTimer >= skipWindowStart && confirmPressed(input, startKey))) {
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
        state = State.INTRO_TEXT_FADE_IN;
        introTextTimer = 0;
        segaLogoTimer = 0;
        segaPcmStarted = false;
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        return true;
    }

    private void beginSegaLogoFadeOut() {
        if (segaLogoFadePhase == SegaLogoFadePhase.FADING_OUT) {
            return;
        }
        segaLogoFadePhase = SegaLogoFadePhase.FADING_OUT;
        segaLogoFadeTimer = 0;
        GameServices.audio().stopSegaPcm();
    }

    private void transitionToMainScreen() {
        state = State.FADE_IN;
        fadeTimer = 0;
        introTextTimer = 0;
        bgCameraX = 0;
        palCycleTimer = 0;
        palCycleFrame = 0;
        scrollHandler = new SwScrlGhz();

        // GM_Title runs ExecuteObjects, DeformLayers and BuildSprites exactly once
        // before PaletteFadeIn, so the fade-in shows the already-built first frame:
        // TSon_Main advances to TSon_Delay and falls straight through into its first
        // delay tick, and the GHZ deformation is seeded at background X 0.
        updateTitleSonic();
        scrollHandler.update(horizScrollBuf, bgCameraX, 0, frameCounter, 0);

        // Play title music
        GameServices.audio().playMusic(Sonic1Music.TITLE.id);

        LOGGER.info("S1 Title Screen: transitioning to main screen");
    }

    /**
     * Updates the main title screen objects (Sonic animation, BG scroll, palette cycling).
     */
    private void updateMainScreen() {
        // Scroll background right at 2 pixels/frame (from disassembly: addq.w #2,obX)
        bgCameraX += 2;

        // Update parallax scroll handler with current camera position
        // cameraY=0 (no vertical scroll on title screen), actId=0
        scrollHandler.update(horizScrollBuf, bgCameraX, 0, frameCounter, 0);

        // Update palette cycling (water animation)
        updatePaletteCycle();

        // Update TitleSonic (Obj0E state machine)
        updateTitleSonic();

        // Note: PSB ("PRESS START BUTTON") flash is disabled - that text is from the
        // Sonic 1 beta and not present in the final game. Mappings are kept in
        // Sonic1TitleScreenMappings for potential future use.
    }

    /**
     * Updates TitleSonic following the Obj0E state machine from disassembly.
     */
    private void updateTitleSonic() {
        switch (sonicRoutine) {
            case 0: // TSon_Main - advance to delay. There is no rts after the
                    // AnimateSprite call, so the routine falls through into
                    // TSon_Delay and consumes the first delay tick this same frame.
                sonicRoutine = 2;
                // fall through
            case 2: // TSon_Delay - wait until obDelayAni (30-1) goes negative
                sonicDelayTimer--;
                if (sonicDelayTimer < 0) {
                    sonicRoutine = 4; // Move
                }
                break;

            case 4: // Move - move up 8px/frame
                sonicScreenY -= 8;
                if (sonicScreenY <= SONIC_FINAL_Y) {
                    sonicScreenY = SONIC_FINAL_Y;
                    sonicRoutine = 6; // Animate
                }
                break;

            case 6: // Animate - play animation loop
                sonicAnimTimer++;
                if (sonicAnimTimer > SONIC_ANIM_DURATION) {
                    sonicAnimTimer = 0;
                    sonicAnimFrame++;
                    if (sonicAnimFrame > 7) {
                        // afBack, 2: go back 2 script positions from afBack (pos 8)
                        // → position 6 = frame 6. Loops frames 6-7 (finger wag).
                        sonicAnimFrame = 6;
                    }
                }
                break;
        }
    }

    /**
     * Updates the "PRESS START BUTTON" flash animation.
     * Ani_PSBTM: $1F, 0, 1, afEnd → toggles between frame 0 (visible) and 1 (blank) every 0x1F frames
     */
    private void updatePSBFlash() {
        psbAnimTimer++;
        if (psbAnimTimer > PSB_FLASH_DURATION) {
            psbAnimTimer = 0;
            psbAnimFrame = (psbAnimFrame == 0) ? 1 : 0;
        }
    }

    /**
     * Renders Plane B (GHZ background) with per-row parallax scroll from SwScrlGhz.
     * Uses horizScrollBuf (populated by the scroll handler) for per-scanline BG scroll.
     * Each tile row uses the scroll value from its first scanline.
     *
     * <p>Applies VDP vertical scroll (vscrollFactorBG) to offset which rows of the
     * 256-pixel tall nametable are visible. Without this, the wrong portion of the
     * GHZ background is shown (e.g. water instead of mountains on the title screen).
     *
     * <p>Caller must call {@code gm.beginPatternBatch()} before and
     * {@code gm.flushPatternBatch()} after, matching the S2 title screen pattern.
     */
    private void renderPlaneB(GraphicsManager gm) {
        int[] map = dataLoader.getPlaneBMap();
        if (map == null || map.length == 0) {
            return;
        }

        int mapWidth = dataLoader.getPlaneBWidth();
        int mapHeight = dataLoader.getPlaneBHeight();
        if (mapWidth == 0 || mapHeight == 0) {
            return;
        }

        // Apply VDP vertical scroll: vscrollFactorBG determines which row of the
        // nametable appears at the top of the screen. In the VDP, nametable_row =
        // (screen_row + vscroll) mod nametable_height.
        int vscroll = scrollHandler.getVscrollFactorBG() & 0xFFFF;
        int subTileY = vscroll & 7;
        int startTileY = vscroll >> 3;

        // Render visible tile rows: 29 rows (28 + 1 for sub-tile Y offset at top/bottom)
        int visibleRows = Math.min(29, mapHeight);

        for (int ty = 0; ty < visibleRows; ty++) {
            // Stop before the nametable wraps vertically. Without this, the bottom
            // of the screen shows a few rows from the top of the block (sky/clouds)
            // due to VDP torus wrapping. The clear color (palette line 2, color 0)
            // fills the remaining scanlines instead, matching the water/ground below.
            // This wrap also occurs on real hardware but is only ~6 pixels and usually
            // obscured by the foreground logo.
            if (startTileY + ty >= mapHeight) {
                break;
            }
            int mapTileY = startTileY + ty;

            // Screen Y for this tile row, offset by sub-tile V-scroll
            int drawY = ty * 8 - subTileY;
            if (drawY >= SCREEN_HEIGHT) {
                break;
            }

            // Extract BG scroll for this tile row from horizScrollBuf.
            // BG scroll is in the low 16 bits (signed short, negated camera position).
            // Use the screen scanline (not nametable row) for H-scroll lookup.
            int scanline = Math.max(0, drawY);
            if (scanline >= M68KMath.VISIBLE_LINES) {
                break;
            }
            int packed = horizScrollBuf[scanline];
            short bgScroll = (short) (packed & 0xFFFF);
            // Convert from VDP scroll (negative = scroll right) to pixel position
            int bgPixelPos = -(int) bgScroll;

            int subTileX = bgPixelPos & 7;
            int startTileX = bgPixelPos >> 3;

            // bgTileColumns() returns 42 at native 320 — byte-identical at native width.
            int tileColumns = bgTileColumns(viewportWidth());
            for (int screenTile = 0; screenTile < tileColumns; screenTile++) {
                int mapTileX = startTileX + screenTile;
                // Wrap horizontally within the nametable
                mapTileX = ((mapTileX % mapWidth) + mapWidth) % mapWidth;

                int idx = mapTileY * mapWidth + mapTileX;
                if (idx < 0 || idx >= map.length) {
                    continue;
                }
                int word = map[idx];
                if (word == 0) {
                    continue;
                }

                reusableDesc.set(word);
                int patternId = Sonic1TitleScreenDataLoader.GHZ_PATTERN_BASE + reusableDesc.getPatternIndex();
                int drawX = screenTile * 8 - subTileX;
                gm.renderPatternWithId(patternId, reusableDesc, drawX, drawY);
            }
        }
    }

    /**
     * Updates the water palette cycling animation.
     * Every PAL_CYCLE_SPEED frames, advances to the next palette cycle frame.
     * Writes 4 colors from Pal_TitleCyc into palette line 2 starting at color index 2.
     */
    private void updatePaletteCycle() {
        byte[] cycleData = dataLoader.getPaletteCycleData();
        if (cycleData == null || cycleData.length == 0) {
            return;
        }

        palCycleTimer++;
        if (palCycleTimer < PAL_CYCLE_SPEED) {
            return;
        }
        palCycleTimer = 0;
        palCycleFrame = (palCycleFrame + 1) % PAL_CYCLE_FRAMES;

        // Each cycle frame is 4 colors × 2 bytes/color = 8 bytes
        int offset = palCycleFrame * 8;
        if (offset + 8 > cycleData.length) {
            return;
        }

        Palette palLine2 = dataLoader.getTitlePaletteLine(2);
        if (palLine2 == null) {
            return;
        }

        // Write 4 colors into palette line 2, starting at color index 8
        // (matches disassembly: lea (v_palette+$50).w,a1
        //  v_palette+$50 = line 2 start ($40) + $10 = color index 8)
        for (int i = 0; i < 4; i++) {
            Palette.Color color = palLine2.getColor(8 + i);
            color.fromSegaFormat(cycleData, offset + i * 2);
        }

        // Re-upload palette line 2 to GPU
        dataLoader.rechachePaletteLine(GameServices.graphics(), 2);
    }

    @Override
    public void draw() {
        if (state == State.INACTIVE) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();

        if (state == State.SEGA_LOGO) {
            drawSegaLogo(gm);
            return;
        }

        // Ensure palettes are uploaded to GPU (required for all pattern rendering).
        // Fades go through the palette, as PaletteFadeIn / PaletteFadeOut do on the
        // Mega Drive: blue, then green, then red on the way in; red, green, blue out.
        dataLoader.cachePalettesToGpu(gm, paletteFadeMode(), paletteFadeSteps());

        // Intro text phases: render "SONIC TEAM PRESENTS"
        if (state == State.INTRO_TEXT_FADE_IN || state == State.INTRO_TEXT_HOLD ||
                state == State.INTRO_TEXT_FADE_OUT) {
            drawIntroText(gm);
            return;
        }

        // Main title screen phases
        if (!spritesInitialized) {
            initSpriteRenderers(gm);
        }

        // Cache patterns to GPU
        dataLoader.cacheGhzToGpu(gm);
        dataLoader.cacheForegroundToGpu(gm);

        // --- Render Plane B (GHZ background) ---
        gm.beginPatternBatch();
        renderPlaneB(gm);
        gm.flushPatternBatch();
        // Flush Plane B to framebuffer before Plane A starts (matches S2 pattern)
        gm.flushScreenSpace();

        // --- Render Plane A upper portion (behind sprites) ---
        // The original game uses a "sprite line limiter" (M_PSB_Limiter) to mask
        // Sonic's sprite below screen Y 104 via the VDP per-scanline sprite limit.
        // We replicate this by splitting Plane A at tile row 9 (screen Y 104 = startPixelY + 9*8).
        // Rows 0-8 render behind Sonic; rows 9+ render in front.
        gm.beginPatternBatch();
        renderPlaneA(gm, 0, PLANE_A_SPLIT_ROW);
        gm.flushPatternBatch();
        gm.flushScreenSpace();

        // --- Render sprites ---
        gm.beginPatternBatch();

        // TitleSonic sprite (only visible when not in delay state)
        // xOffset() == 0 at native 320 — byte-identical at native width.
        if (sonicRoutine >= 4 && sonicSpriteRenderer != null && sonicSpriteRenderer.isReady()) {
            int screenX = xOffset() + SONIC_X - 128; // VDP to screen coords, centred in viewport
            int screenY = sonicScreenY - 128;
            enableSpriteLimiterScissor(gm);
            sonicSpriteRenderer.drawFrameIndex(sonicAnimFrame, screenX, screenY);
            // Pattern draws are queued commands: they only reach GL in
            // flushScreenSpace(), so the scissor must still be active here.
            gm.flushPatternBatch();
            gm.flushScreenSpace();
            gm.disableScissor();
        }

        gm.flushPatternBatch();

        // --- Render Plane A lower portion (in front of sprites) ---
        // Replicates the visual effect of M_PSB_Limiter masking Sonic below
        // the logo's mid-section (red ribbon / "THE HEDGEHOG" text).
        gm.beginPatternBatch();
        renderPlaneA(gm, PLANE_A_SPLIT_ROW, dataLoader.getPlaneAHeight());
        gm.flushPatternBatch();
        gm.flushScreenSpace();

        // TM uses title foreground patterns, render separately
        gm.beginPatternBatch();

        // Note: "PRESS START BUTTON" is disabled - that text is from the Sonic 1 beta.
        // Mappings and draw method are retained for potential future use.

        // "TM" symbol - always visible
        drawTMSymbol(gm);

        gm.flushPatternBatch();
    }

    /**
     * Palette fade direction for the intro text and main title screen phases.
     * The SEGA screen keeps its own phase tracking in {@link #segaLogoFadeMode()}.
     */
    private SegaPaletteFade.Mode paletteFadeMode() {
        if (state == State.INTRO_TEXT_FADE_IN || state == State.FADE_IN) {
            return SegaPaletteFade.Mode.FROM_BLACK;
        }
        if (state == State.INTRO_TEXT_FADE_OUT) {
            return SegaPaletteFade.Mode.TO_BLACK;
        }
        return SegaPaletteFade.Mode.NONE;
    }

    /**
     * Number of FadeIn_AddColor / FadeOut_DecColor passes visible on the current
     * frame. Each PaletteFadeIn iteration first waits for VBlank (which transfers the
     * palette built so far) and only then applies the next step, so the frame drawn
     * after the N-th update shows N-1 steps: the first fade frame is fully black
     * (or, fading out, still the full palette) and the 22nd frame shows all 21 steps.
     */
    private int paletteFadeSteps() {
        return switch (state) {
            case FADE_IN -> Math.max(0, fadeTimer - 1);
            case INTRO_TEXT_FADE_IN, INTRO_TEXT_FADE_OUT -> Math.max(0, introTextTimer - 1);
            default -> 0;
        };
    }

    private void drawSegaLogo(GraphicsManager gm) {
        dataLoader.cacheSegaLogoToGpu(gm, segaLogoFadeMode(), segaLogoFadeTimer);
        drawSegaLogoScan(gm);
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
                int tileIndex = reusableDesc.getPatternIndex();
                gm.renderPatternWithId(
                        Sonic1TitleScreenDataLoader.SEGA_LOGO_PATTERN_BASE + tileIndex,
                        reusableDesc,
                        xOffset() + tx * 8,
                        ty * 8);
            }
        }
        gm.flushPatternBatch();
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

    private void drawSegaLogoScan(GraphicsManager gm) {
        int[] map = dataLoader.getSegaLogoScanMap();
        if (map == null || map.length == 0) {
            return;
        }
        gm.beginPatternBatch();
        int width = dataLoader.getSegaLogoScanWidth();
        int height = dataLoader.getSegaLogoScanHeight();
        for (int ty = 0; ty < height; ty++) {
            for (int tx = 0; tx < width; tx++) {
                int word = map[ty * width + tx];
                if (word == 0) {
                    continue;
                }
                reusableDesc.set(word);
                int tileIndex = reusableDesc.getPatternIndex();
                gm.renderPatternWithId(
                        Sonic1TitleScreenDataLoader.SEGA_LOGO_PATTERN_BASE + tileIndex,
                        reusableDesc,
                        xOffset() + SEGA_LOGO_SCAN_X + tx * 8,
                        SEGA_LOGO_SCAN_Y + ty * 8);
            }
        }
        gm.flushPatternBatch();
        gm.flushScreenSpace();
    }

    /**
     * Draws the "SONIC TEAM PRESENTS" intro text screen using credit text font sprites.
     */
    private void drawIntroText(GraphicsManager gm) {
        // Cache credit text patterns on first draw
        if (!creditTextCached) {
            dataLoader.cacheCreditTextToGpu(gm);
            creditTextCached = true;
            initCreditTextSpriteRenderer(gm);
        }

        // Render "SONIC TEAM PRESENTS" as a single sprite mapping at screen center
        // Object center position: x=0x120, y=0xF0 (VDP coords) → screen x=160, y=112.
        // At widescreen we place the sprite at the viewport mid-point so it stays centred.
        // At native 320 viewportWidth()/2 == 160 — byte-identical.
        if (creditTextSpriteRenderer != null && creditTextSpriteRenderer.isReady()) {
            gm.beginPatternBatch();
            creditTextSpriteRenderer.drawFrameIndex(0, viewportWidth() / 2, 112);
            gm.flushPatternBatch();
        }
        // The fade in and out of this screen is applied to the palette lines in
        // draw() before this call; no overlay is drawn.
    }

    /**
     * Restricts subsequent drawing to the screen rows above the M_PSB_Limiter band,
     * modelling the VDP per-scanline sprite budget that erases TitleSonic from
     * screen Y {@value #SPRITE_LIMITER_TOP_Y} downwards. Without this, Sonic's torso
     * shows below the bottom edge of the logo, where Plane A has no opaque tiles to
     * hide it.
     */
    private void enableSpriteLimiterScissor(GraphicsManager gm) {
        int vpX = gm.getViewportX();
        int vpY = gm.getViewportY();
        int vpW = gm.getViewportWidth();
        int vpH = gm.getViewportHeight();
        float scaleY = (float) vpH / SCREEN_HEIGHT;
        // Game Y is top-down; GL scissor Y is bottom-up.
        int scissorY = vpY + (int) Math.floor((SCREEN_HEIGHT - SPRITE_LIMITER_TOP_Y) * scaleY);
        int scissorH = Math.max(1, (int) Math.ceil(SPRITE_LIMITER_TOP_Y * scaleY));
        gm.enableScissor(vpX, scissorY, vpW, scissorH);
    }

    /**
     * Renders a row range of the Plane A (title foreground logo) nametable.
     *
     * <p>The nametable is 34 tiles wide by 22 tiles tall, placed starting at VRAM row 4, col 3
     * (offset 0x206 = row*128 + col*2, but we render directly at pixel positions).
     *
     * <p>Called twice per frame to layer Sonic correctly: rows 0 to PLANE_A_SPLIT_ROW
     * render behind Sonic, and rows PLANE_A_SPLIT_ROW to end render in front of him.
     * This replicates the visual effect of the original game's M_PSB_Limiter sprite
     * masking trick.
     *
     * @param startRow first tile row to render (inclusive)
     * @param endRow   last tile row to render (exclusive)
     */
    private void renderPlaneA(GraphicsManager gm, int startRow, int endRow) {
        int[] map = dataLoader.getPlaneAMap();
        if (map == null || map.length == 0) {
            return;
        }

        int mapWidth = dataLoader.getPlaneAWidth();
        int mapHeight = dataLoader.getPlaneAHeight();

        // Plane A logo starts at VDP nametable offset $206 in a 64-wide plane:
        // $206 / $80 = row 4, ($206 % $80) / 2 = col 3 → pixel (24, 32)
        // At native 320 xOffset() == 0, so startPixelX == 24 — byte-identical.
        int startPixelX = xOffset() + 24;
        int startPixelY = 32;

        int clampedEnd = Math.min(endRow, mapHeight);

        for (int ty = startRow; ty < clampedEnd; ty++) {
            for (int tx = 0; tx < mapWidth; tx++) {
                int idx = ty * mapWidth + tx;
                if (idx >= map.length) {
                    break;
                }
                int word = map[idx];
                if (word == 0) {
                    continue;
                }

                reusableDesc.set(word);
                int tileIndex = reusableDesc.getPatternIndex();

                // Map from VDP tile index to our pattern base
                // The Enigma decoder already includes the starting art tile (0x200),
                // so we subtract it to get the local pattern index
                int localIndex = tileIndex - Sonic1Constants.ARTTILE_TITLE_FOREGROUND;
                if (localIndex < 0) {
                    continue;
                }

                int patternId = Sonic1TitleScreenDataLoader.FG_PATTERN_BASE + localIndex;
                int px = startPixelX + tx * 8;
                int py = startPixelY + ty * 8;
                gm.renderPatternWithId(patternId, reusableDesc, px, py);
            }
        }
    }

    /**
     * Draws the "PRESS START BUTTON" text.
     * Uses title foreground patterns at tile indices 0xF0+.
     * Position from disassembly: x=$D0, y=$130 (VDP) → screen (80, 176).
     */
    private void drawPSBText(GraphicsManager gm) {
        // PSB uses ArtTile_Title_Foreground patterns
        // The 6 pieces from Map_PSB frame 0 are at foreground tile offsets 0xF0-0xFD
        // We render them relative to the PSB position
        int baseX = PSB_X;
        int baseY = PSB_Y;

        // Piece data from Map_PSB: {xOff, yOff, width, height, tileOffset}
        int[][] pieces = {
                {0x00, 0x00, 4, 1, 0xF0},
                {0x20, 0x00, 1, 1, 0xF3},
                {0x30, 0x00, 1, 1, 0xF3},
                {0x38, 0x00, 4, 1, 0xF4},
                {0x60, 0x00, 3, 1, 0xF8},
                {0x78, 0x00, 3, 1, 0xFB},
        };

        reusableDesc.set(0); // No flip, palette 0, no priority

        for (int[] piece : pieces) {
            int px = baseX + piece[0];
            int py = baseY + piece[1];
            int width = piece[2];
            int height = piece[3];
            // Tile indices are relative to the object's art_tile (ArtTile_Title_Foreground),
            // so they directly index into the foreground pattern array
            int startTile = piece[4];

            // Render each pattern in the piece (column-major)
            for (int col = 0; col < width; col++) {
                for (int row = 0; row < height; row++) {
                    int localTile = startTile + col * height + row;
                    int patternId = Sonic1TitleScreenDataLoader.FG_PATTERN_BASE + localTile;
                    gm.renderPatternWithId(patternId, reusableDesc, px + col * 8, py + row * 8);
                }
            }
        }
    }

    /**
     * Draws the "TM" symbol.
     * Uses ArtTile_Title_Trademark patterns (separate from foreground).
     * Position from disassembly: x=$170, y=$F8 (VDP) → screen (240, 120).
     */
    private void drawTMSymbol(GraphicsManager gm) {
        dataLoader.cacheTmToGpu(gm);

        // xOffset() == 0 at native 320 — byte-identical at native width.
        int baseX = xOffset() + TM_X;
        int baseY = TM_Y;

        // TM is 1 piece: 2 tiles wide, 1 tile tall, at tile 0 (relative to TM patterns)
        reusableDesc.set(0); // palette 0 in the mapping, but TM uses palette 1 in disassembly
        // The TM sprite in original uses make_art_tile(ArtTile_Title_Trademark, 1, 0)
        // palette line 1. We set palette via the desc.
        reusableDesc.set(0x2000); // palette 1

        // Piece: xOff=-8, yOff=-4, 2 wide, 1 tall
        int px = baseX - 8;
        int py = baseY - 4;
        for (int col = 0; col < 2; col++) {
            int patternId = Sonic1TitleScreenDataLoader.TM_PATTERN_BASE + col;
            gm.renderPatternWithId(patternId, reusableDesc, px + col * 8, py);
        }
    }

    private void initSpriteRenderers(GraphicsManager gm) {
        titleMappingFrames = Sonic1TitleScreenMappings.createFrames();

        // Sonic sprite renderer uses TitleSonic patterns (frames 0-7)
        if (dataLoader.getSonicPatterns() != null && dataLoader.getSonicPatterns().length > 0) {
            ObjectSpriteSheet sonicSheet = new ObjectSpriteSheet(
                    dataLoader.getSonicPatterns(),
                    titleMappingFrames.subList(0, 8), // Frames 0-7 only
                    1, // palette line 1 (from make_art_tile(ArtTile_Title_Sonic, 1, 0))
                    1
            );
            sonicSpriteRenderer = new PatternSpriteRenderer(sonicSheet);
            sonicSpriteRenderer.ensurePatternsCached(gm, Sonic1TitleScreenDataLoader.SPRITE_PATTERN_BASE);
            LOGGER.info("S1 title Sonic sprite renderer initialized with " +
                    dataLoader.getSonicPatterns().length + " patterns");
        }

        spritesInitialized = true;
    }

    private void initCreditTextSpriteRenderer(GraphicsManager gm) {
        if (titleMappingFrames == null) {
            titleMappingFrames = Sonic1TitleScreenMappings.createFrames();
        }

        if (dataLoader.getCreditTextPatterns() != null && dataLoader.getCreditTextPatterns().length > 0) {
            // Credit text renderer uses the "SONIC TEAM PRESENTS" mapping (frame 11)
            // The mapping tile indices reference ArtTile_Sonic_Team_Font patterns
            ObjectSpriteSheet creditSheet = new ObjectSpriteSheet(
                    dataLoader.getCreditTextPatterns(),
                    titleMappingFrames.subList(11, 12), // Frame 11 only
                    0, // palette line 0
                    1
            );
            creditTextSpriteRenderer = new PatternSpriteRenderer(creditSheet);
            creditTextSpriteRenderer.ensurePatternsCached(gm, Sonic1TitleScreenDataLoader.CREDIT_TEXT_PATTERN_BASE);
            LOGGER.info("S1 credit text sprite renderer initialized");
        }
    }

    /**
     * Applies the title palette to the graphics system.
     */
    private void applyTitlePalette() {
        // Palette data is stored in the data loader and used during rendering
        // via PatternDesc nametable words. No explicit GPU upload needed.
    }

    @Override
    public void setClearColor() {
        // During intro text, background is black
        if (state == State.INTRO_TEXT_FADE_IN || state == State.INTRO_TEXT_HOLD ||
                state == State.INTRO_TEXT_FADE_OUT || state == State.SEGA_LOGO) {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            return;
        }
        // Main title screen uses VDP register $8720 = palette line 2, color 0.
        // The backdrop is a CRAM entry, so PaletteFadeIn fades it like every other
        // colour; read it through the same fade step draw() uploads.
        Palette bgPal = dataLoader.resolveTitlePaletteLine(2, paletteFadeMode(), paletteFadeSteps());
        if (bgPal != null) {
            Palette.Color backdrop = bgPal.getColor(0);
            glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
        } else {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    @Override
    public void reset() {
        GameServices.audio().stopSegaPcm();
        state = State.INACTIVE;
        frameCounter = 0;
        fadeTimer = 0;
        introTextTimer = 0;
        segaLogoTimer = 0;
        segaPcmStarted = false;
        segaLogoFadePhase = SegaLogoFadePhase.FADING_IN;
        segaLogoFadeTimer = 0;
        segaLogoPaletteCycle.reset();
        creditTextCached = false;
        spritesInitialized = false;
        sonicRoutine = 0;
        sonicScreenY = 0xDE;
        sonicDelayTimer = 29;
        sonicAnimFrame = 0;
        sonicAnimTimer = 0;
        psbAnimFrame = 0;
        psbAnimTimer = 0;
        bgCameraX = 0;
        palCycleTimer = 0;
        palCycleFrame = 0;
        scrollHandler = null;
        sonicSpriteRenderer = null;
        creditTextSpriteRenderer = null;
        tmSpriteRenderer = null;
        titleMappingFrames = null;
        dataLoader.resetCache();
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

    @Override
    public boolean supportsLevelSelectOverlay() {
        return true;
    }

    /**
     * Renders the title screen foreground art (logo, Sonic sprite, TM) without
     * the scrolling GHZ background.
     *
     * <p>Used by the Sonic 1 level select to display the frozen title screen art
     * behind the level select text. The caller must upload the level select palette
     * to the GPU before calling this, so the art appears with the brown/sepia tint
     * (matching the original game's Pal_LevelSel behaviour).
     *
     * <p>From the disassembly: the original game simply loads Pal_LevelSel and
     * clears the BG VRAM plane. The Plane A tiles and sprites remain in VRAM
     * and are recoloured by the new palette.
     */
    @Override
    public void drawFrozenForLevelSelect() {
        if (!dataLoader.isDataLoaded()) {
            return;
        }

        GraphicsManager gm = GameServices.graphics();

        // Cache patterns to GPU (NOT palettes - level select palette is already active)
        dataLoader.cacheForegroundToGpu(gm);
        dataLoader.cacheTmToGpu(gm);

        if (!spritesInitialized) {
            initSpriteRenderers(gm);
        }

        // Render with the same Plane A split as the normal title screen draw():
        // rows 0-8 behind Sonic, rows 9+ in front. This replicates the VDP
        // sprite line limiter (M_PSB_Limiter) masking effect.

        // Plane A upper portion (behind Sonic)
        gm.beginPatternBatch();
        renderPlaneA(gm, 0, PLANE_A_SPLIT_ROW);
        gm.flushPatternBatch();
        gm.flushScreenSpace();

        // Sonic sprite frozen at whatever position/frame he was on
        // when the user pressed Start. The sonicRoutine >= 4 guard matches
        // the normal draw() — Sonic is invisible during the initial delay.
        gm.beginPatternBatch();
        if (sonicRoutine >= 4 && sonicSpriteRenderer != null && sonicSpriteRenderer.isReady()) {
            // xOffset() == 0 at native 320 — byte-identical at native width.
            int screenX = xOffset() + SONIC_X - 128;
            int screenY = sonicScreenY - 128;
            enableSpriteLimiterScissor(gm);
            sonicSpriteRenderer.drawFrameIndex(sonicAnimFrame, screenX, screenY);
            // Pattern draws are queued commands: they only reach GL in
            // flushScreenSpace(), so the scissor must still be active here.
            gm.flushPatternBatch();
            gm.flushScreenSpace();
            gm.disableScissor();
        }
        gm.flushPatternBatch();

        // Plane A lower portion (in front of Sonic)
        gm.beginPatternBatch();
        renderPlaneA(gm, PLANE_A_SPLIT_ROW, dataLoader.getPlaneAHeight());
        gm.flushPatternBatch();
        gm.flushScreenSpace();

        // TM symbol
        gm.beginPatternBatch();
        drawTMSymbol(gm);
        gm.flushPatternBatch();
    }
}
