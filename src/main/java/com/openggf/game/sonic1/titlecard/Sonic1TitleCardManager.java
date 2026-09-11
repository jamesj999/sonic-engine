package com.openggf.game.sonic1.titlecard;

import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.GameServices;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.titlecard.TitleCardElement;
import com.openggf.game.titlecard.TitleCardMappings;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.PaletteFadePresentation;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.graphics.TitleCardSpriteRenderer;
import com.openggf.level.Pattern;
import com.openggf.util.PatternDecompressor;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages the Sonic 1 title card display.
 *
 * <p>Sonic 1 title cards are simpler than Sonic 2: 4 sprite elements
 * (zone name, "ZONE", act number, oval decoration) slide in from off-screen
 * over a black background, hold until the level's queued art has finished
 * decompressing, then slide out at double speed. No background planes
 * (blue/yellow/red) are used.
 *
 * <p>From the disassembly (Object 34 - "34 Title Cards.asm"):
 * <ul>
 *   <li>Routine 0 (Card_CheckSBZ3): Initialize 4 elements with ConData positions</li>
 *   <li>Routine 2 (Card_ChkPos): Slide to card_mainX at 16px/frame</li>
 *   <li>Routine 4/6 (Card_Wait): Wait obTimeFrame (60) frames, then slide to
 *       card_finalX at 32px/frame. Nothing reaches this routine until after
 *       Level_TtlCardLoop has exited and the level has faded in, so the 60
 *       frames are spent inside Level_MainLoop with gameplay running.</li>
 * </ul>
 *
 * <p>In the original game, the title card loop (Level_TtlCardLoop) runs until
 * every element has reached its target <em>and</em> v_plc_buffer is empty, then
 * level loading continues. The elements continue running in the background
 * during level load, eventually sliding out and being deleted.
 *
 * <p>State machine:
 * <pre>
 * SLIDE_IN -> DISPLAY -> SLIDE_OUT -> COMPLETE
 * </pre>
 *
 * <p>control is released at the start of SLIDE_OUT. The elements slide off-screen
 * as an overlay while the player can already move.
 */
public class Sonic1TitleCardManager implements TitleCardProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1TitleCardManager.class.getName());

    private static Sonic1TitleCardManager instance;

    /** Pattern base ID for S1 title card art (high to avoid conflicts with S2's 0x40000) */
    private static final int PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();

    /** Native game width (320-pixel frame everything is authored for). */
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;

    /**
     * Returns the configured viewport width in game pixels.
     * At native 320 equals SCREEN_WIDTH exactly (xOffset == 0 — byte-identical).
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
     * Horizontal offset to centre the 320-wide title-card composition in the
     * configured viewport.  Zero at native 320 — byte-identical.
     */
    private int xOffset() {
        return (viewportWidth() - SCREEN_WIDTH) / 2;
    }

    /**
     * Duration of PalFadeIn_Alt: 22 VBlank periods ({@code move.w #22-1,d4}), each
     * transferring the palette built so far and then running FadeIn_AddColor once
     * over palette lines 1-3 ({@code move.w #$202F,(v_pfade_start).w},
     * docs/s1disasm/sonic.asm:2965-2966). Line 0 (Sonic, HUD, title card) was
     * loaded directly with PalLoad and never fades.
     */
    static final int PALETTE_FADE_FRAMES = PaletteFadePresentation.ROM_FADE_FRAMES;

    // Current state
    private Sonic1TitleCardState state = Sonic1TitleCardState.COMPLETE;
    private int stateTimer = 0;

    // Current zone/act
    private int currentZone = 0;
    private int currentAct = 0;

    // Elements
    private final List<TitleCardElement> elements = new ArrayList<>();

    // Art data
    private Pattern[] patterns;
    private boolean artLoaded = false;
    private boolean artCached = false;

    public Sonic1TitleCardManager() {}

    public static synchronized Sonic1TitleCardManager getInstance() {
        if (instance == null) {
            instance = new Sonic1TitleCardManager();
        }
        return instance;
    }

    @Override
    public void initialize(int zoneIndex, int actIndex) {
        this.currentZone = zoneIndex;
        this.currentAct = actIndex;
        this.state = Sonic1TitleCardState.SLIDE_IN;
        this.stateTimer = 0;

        if (!artLoaded) {
            loadArt();
        }

        // Force GPU re-upload on each initialize
        artCached = false;

        createElements();

        LOGGER.info("S1 title card initialized for zone " + zoneIndex + " act " + actIndex);
    }

    /**
     * Creates the 4 animated elements based on zone configuration data.
     *
     * <p>From Card_ItemData in the disassembly:
     * <ol>
     *   <li>Zone name: Y=$D0, routine=2, frame=zone-specific</li>
     *   <li>ZONE text: Y=$E4, routine=2, frame=6</li>
     *   <li>Act number: Y=$EA, routine=2, frame=7 (adjusted by act)</li>
     *   <li>Oval: Y=$E0, routine=2, frame=$A (10)</li>
     * </ol>
     */
    private void createElements() {
        elements.clear();

        int configIndex = Sonic1TitleCardMappings.getConfigIndex(currentZone, currentAct);
        int[] conData = Sonic1TitleCardMappings.getConData(configIndex);

        // conData layout: {zoneName_startX, zoneName_targetX,
        //                  zone_startX, zone_targetX,
        //                  act_startX, act_targetX,
        //                  oval_startX, oval_targetX}

        int zoneNameFrame = Sonic1TitleCardMappings.getZoneNameFrame(currentZone, currentAct);

        // Zone name element
        elements.add(new TitleCardElement(
                zoneNameFrame,
                conData[0], conData[1],
                Sonic1TitleCardMappings.Y_ZONE_NAME,
                0, 0x80));

        // "ZONE" text element
        elements.add(new TitleCardElement(
                Sonic1TitleCardMappings.FRAME_ZONE,
                conData[2], conData[3],
                Sonic1TitleCardMappings.Y_ZONE_TEXT,
                0, 0x40));

        // Act number element (hidden for Final Zone where startX == targetX)
        boolean hideAct = Sonic1TitleCardMappings.shouldHideActNumber(currentZone, currentAct);
        if (!hideAct) {
            int actFrame = Sonic1TitleCardMappings.getActFrame(currentAct);
            elements.add(new TitleCardElement(
                    actFrame,
                    conData[4], conData[5],
                    Sonic1TitleCardMappings.Y_ACT,
                    0, 0x30));
        }

        // Oval decoration element
        elements.add(new TitleCardElement(
                Sonic1TitleCardMappings.FRAME_OVAL,
                conData[6], conData[7],
                Sonic1TitleCardMappings.Y_OVAL,
                0, 0x40));

        // Extend each element's off-screen entry/exit endpoint so the elements
        // slide fully on/off a wider-than-320 viewport. Zero at native 320 —
        // byte-identical. Without this, the centred (xOffset-shifted) elements
        // do not fully leave the screen at widescreen widths.
        int edgeMargin = Math.max(0, viewportWidth() - SCREEN_WIDTH);
        for (TitleCardElement element : elements) {
            element.setEdgeMargin(edgeMargin);
        }
    }

    private void loadArt() {
        try {
            RomManager romManager = GameServices.rom();
            if (!romManager.isRomAvailable()) {
                LOGGER.warning("ROM not available for S1 title card art");
                return;
            }
            Rom rom = romManager.getRom();

            patterns = PatternDecompressor.nemesis(rom,
                    Sonic1Constants.ART_NEM_TITLE_CARD_ADDR, "S1 TitleCard");

            if (patterns == null) {
                patterns = new Pattern[0];
            }

            LOGGER.info("Loaded " + patterns.length + " S1 title card patterns");
            artLoaded = true;

        } catch (Exception e) {
            LOGGER.warning("Failed to load S1 title card art: " + e.getMessage());
            artLoaded = false;
        }
    }


    private void ensureArtCached(GraphicsManager graphicsManager) {
        if (artCached || !artLoaded || patterns == null) {
            return;
        }

        if (graphicsManager == null) {
            return;
        }

        for (int i = 0; i < patterns.length; i++) {
            if (patterns[i] != null) {
                graphicsManager.cachePatternTexture(patterns[i], PATTERN_BASE + i);
            }
        }
        LOGGER.info("Cached " + patterns.length + " S1 title card patterns to GPU");

        artCached = true;
    }

    @Override
    public void update() {
        stateTimer++;

        switch (state) {
            case SLIDE_IN -> updateSlideIn();
            case DISPLAY -> updateDisplay();
            case SLIDE_OUT -> updateSlideOut();
            case COMPLETE -> {}
        }
        applyPaletteFadePresentation();
    }

    /**
     * FadeIn_AddColor passes visible on the frame drawn after this update, or -1
     * when the level palette is not fading. Each PalFadeIn_Alt iteration waits for
     * VBlank (transferring the palette built so far) before applying the next
     * step, so the N-th fade frame shows N-1 steps: the first is black on lines
     * 1-3 and the 22nd shows all 21 steps, which is the full palette. The frame
     * that leaves DISPLAY (timer 0) is black as well and is still covered by the
     * card's black plane in {@link #draw}; without both the level flashes for one
     * frame between the card and the fade.
     */
    static int paletteFadeStepsAt(Sonic1TitleCardState state, int stateTimer) {
        if (state != Sonic1TitleCardState.SLIDE_OUT || stateTimer >= PALETTE_FADE_FRAMES) {
            return -1;
        }
        // stateTimer 0 is the frame that left DISPLAY: the card's black plane is
        // no longer drawn, so lines 1-3 must already read black (the ROM's
        // Level_Delay VBlanks transfer black lines before PalFadeIn_Alt starts).
        return Math.max(0, stateTimer - 1);
    }

    private void applyPaletteFadePresentation() {
        GraphicsManager graphicsManager = graphicsOrNull();
        if (graphicsManager == null) {
            return;
        }
        int steps = paletteFadeStepsAt(state, stateTimer);
        if (steps < 0) {
            graphicsManager.clearPaletteFadePresentation();
            return;
        }
        graphicsManager.setPaletteFadePresentation(
                PaletteFadePresentation.Mode.FROM_BLACK, steps, PaletteFadePresentation.LINES_1_TO_3);
    }

    private static GraphicsManager graphicsOrNull() {
        try {
            return GameServices.graphics();
        } catch (RuntimeException ignored) {
            // Focused tests drive the state machine without an engine session.
            return null;
        }
    }

    private void updateSlideIn() {
        for (TitleCardElement element : elements) {
            element.updateSlideIn();
        }

        if (elements.stream().allMatch(TitleCardElement::isAtTarget)) {
            state = Sonic1TitleCardState.DISPLAY;
            stateTimer = 0;
        }
    }

    private void updateDisplay() {
        if (!plcQueueBusy()) {
            state = Sonic1TitleCardState.SLIDE_OUT;
            stateTimer = 0;
        }
    }

    /**
     * S1's locked title-card loop holds until the level's queued PLCs finish
     * decompressing as well as until the elements arrive: {@code
     * Level_TtlCardLoop} ("stay on them until PLCs have finished") re-loops
     * while {@code v_plc_buffer} is non-empty (docs/s1disasm/sonic.asm:
     * 2814-2842). The card's length is therefore the queued art's drain time,
     * not a constant.
     *
     * <p>Element arrival plus an empty queue is the loop's <em>whole</em> exit
     * condition; there is no minimum hold. The ROM's {@code
     * move.w #1*60,obTimeFrame(a1)} belongs to {@code Card_Wait}, routine 4/6
     * (docs/s1disasm/_incObj/34 Title Cards.asm:74,118-122), which the routine
     * bump at docs/s1disasm/sonic.asm:2971-2974 only reaches after the loop,
     * the four {@code Level_Delay} frames and {@code PalFadeIn_Alt} — i.e.
     * inside {@code Level_MainLoop} with gameplay already running. Gating the
     * pre-release loop on it made the level start late whenever the queued art
     * drained faster than the slide-in plus 60 frames.
     */
    private boolean plcQueueBusy() {
        if (SessionManager.getCurrentWorldSession() == null) {
            return false;
        }
        Sonic1PlcService plcService =
                GameServices.module().getGameService(Sonic1PlcService.class);
        return plcService != null && plcService.isBusy();
    }

    /**
     * SLIDE_OUT has two phases:
     * <ol>
     *   <li>Fade phase (frames 1–22, after the covered release frame 0):
     *       PalFadeIn_Alt fades palette lines 1-3 in from black (blue, then green,
     *       then red) over 22 frames, revealing the level; see
     *       {@link #paletteFadeStepsAt}. Elements remain stationary.</li>
     *   <li>Exit phase (frame 22+): Elements slide back to their start positions
     *       at 32 px/frame, matching Card_ChkPos2 in the disassembly.</li>
     * </ol>
     *
     * <p>During this state, the level is visible behind the elements
     * (shouldReleaseControl() returns true, isOverlayActive() returns true).
     */
    private void updateSlideOut() {
        if (stateTimer == PALETTE_FADE_FRAMES) {
            // Fade just finished — kick off element exit movement
            for (TitleCardElement element : elements) {
                element.startExit();
            }
        }

        if (stateTimer > PALETTE_FADE_FRAMES) {
            for (TitleCardElement element : elements) {
                element.updateSlideOut();
            }

            if (elements.stream().allMatch(TitleCardElement::hasExited)) {
                state = Sonic1TitleCardState.COMPLETE;
                stateTimer = 0;
            }
        }
    }

    // Card_ChangeArt's explosion/animal AddPLC pair is ROM object lifecycle, not
    // presentation: it runs from the fixed title-card slot under ExecuteObjects
    // after Level_StartGame regardless of whether the sprites are drawn
    // (docs/s1disasm/sonic.asm:2969-2995,
    // docs/s1disasm/_incObj/34 Title Cards.asm:122-168). It is owned by
    // Sonic1FixedTitleCardManager so a headless load submits it on the same
    // logical frame as a presented one.

    @Override
    public void draw() {
        GraphicsManager graphicsManager = GameServices.graphics();
        ensureArtCached(graphicsManager);
        if (graphicsManager == null) {
            return;
        }

        // Black background that hides the level during SLIDE_IN and DISPLAY: in
        // Level_TtlCardLoop the level art is still decompressing, lines 1-3 of CRAM
        // hold black and no level object or HUD exists yet. The frame that leaves
        // DISPLAY (SLIDE_OUT, timer 0) is the release iteration: the engine rebuilds
        // the foreground tilemap and the background plane still holds the last
        // pre-fade composite, so it stays covered too, matching the black planes the
        // ROM's Level_Delay VBlanks show. From the next frame on the level is drawn
        // and its palette fades in through the CRAM upload path
        // (applyPaletteFadePresentation), so no overlay is drawn over it.
        if (state == Sonic1TitleCardState.SLIDE_IN || state == Sonic1TitleCardState.DISPLAY
                || (state == Sonic1TitleCardState.SLIDE_OUT && stateTimer == 0)) {
            // Span the full viewport so no level bleeds through on wider screens.
            // viewportWidth()==SCREEN_WIDTH at native 320 — byte-identical.
            graphicsManager.registerCommand(new GLCommand(
                    GLCommand.CommandType.RECTI, -1,
                    0.0f, 0.0f, 0.0f,
                    0, 0, viewportWidth(), SCREEN_HEIGHT));
        }

        // Render sprite elements in reverse order: VDP sprite priority means earlier
        // elements (lower index) have HIGHER priority and render ON TOP. Since OpenGL
        // uses painter's algorithm (last draw wins), we draw the last element first
        // (oval behind) and the first element last (zone name on top).
        graphicsManager.beginPatternBatch();

        for (int i = elements.size() - 1; i >= 0; i--) {
            TitleCardElement element = elements.get(i);
            if (element.isVisible()) {
                renderElement(graphicsManager, element);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    private void renderElement(GraphicsManager graphicsManager, TitleCardElement element) {
        if (!artLoaded || patterns == null) {
            return;
        }

        int frameIndex = element.getFrameIndex();
        if (frameIndex < 0) {
            return;
        }

        TitleCardMappings.SpritePiece[] pieces = Sonic1TitleCardMappings.getFrame(frameIndex);
        // xOffset() centres the 320-wide composition in the viewport.
        // At native 320 xOffset()==0 — byte-identical.
        int centerX = element.getCurrentX() + xOffset();
        int centerY = element.getY();

        // Render pieces in reverse order so that earlier pieces (higher VDP sprite
        // priority) are drawn last and appear on top. This matters for the oval
        // decoration where border pieces (indices 0-7) must render over fill
        // pieces (indices 8-12).
        for (int i = pieces.length - 1; i >= 0; i--) {
            // S1 tile indices are 0-based (vramBase=0), unlike S2/S3K which
            // include a VRAM base offset.
            TitleCardSpriteRenderer.renderSpritePiece(
                    graphicsManager, pieces[i], centerX, centerY,
                    0, PATTERN_BASE, patterns.length);
        }
    }

    /**
     * Returns true if player control should be released.
     * control is released at the start of SLIDE_OUT, matching S1's behavior
     * where the level becomes playable while title card elements slide off-screen.
     */
    @Override
    public boolean shouldReleaseControl() {
        return state == Sonic1TitleCardState.SLIDE_OUT ||
               state == Sonic1TitleCardState.COMPLETE;
    }

    /**
     * Returns true if the title card overlay should still be drawn.
     * The overlay remains visible during SLIDE_OUT as elements slide off-screen
     * over the visible level.
     */
    @Override
    public boolean isOverlayActive() {
        return state == Sonic1TitleCardState.SLIDE_OUT;
    }

    @Override
    public boolean isComplete() {
        return state == Sonic1TitleCardState.COMPLETE;
    }

    /**
     * S1 ROM: title card is a blocking routine (TitleCard in sonic.asm).
     * Player physics does NOT run until the title card completes.
     */
    @Override
    public boolean shouldRunPlayerPhysics() {
        return false;
    }

    @Override
    public boolean shouldRunLevelObjectsDuringLockedPhase() {
        return false;
    }

    @Override
    public int levelObjectPreludePassesAtRelease() {
        return 1;
    }

    @Override
    public boolean shouldRunPlayerPreludeAtRelease() {
        return true;
    }

    @Override
    public void reset() {
        GraphicsManager graphicsManager = graphicsOrNull();
        if (graphicsManager != null) {
            graphicsManager.clearPaletteFadePresentation();
        }
        state = Sonic1TitleCardState.COMPLETE;
        stateTimer = 0;
        elements.clear();
    }

    @Override
    public int getCurrentZone() {
        return currentZone;
    }

    @Override
    public int getCurrentAct() {
        return currentAct;
    }

    public Sonic1TitleCardState getState() {
        return state;
    }
}
