package com.openggf.game.sonic3k.specialstage;

import com.openggf.audio.GameMusic;
import com.openggf.data.RomByteReader;
import com.openggf.data.PaletteLoader;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ResultsScreen;
import com.openggf.game.sonic3k.Sonic3kObjectArt;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * S3K special stage results screen (Chaos Emerald path).
 * <p>
 * Displayed after completing a Blue Sphere special stage entered via giant rings.
 * Shows ring bonus, time bonus (if perfect), collected emerald indicators with
 * flicker animation, and "GOT A CHAOS EMERALD" message if earned.
 * <p>
 * ROM: {@code Obj_SpecialStage_Results} (sonic3k.asm lines 63296-64164).
 * Implements 6-state machine matching ROM routines 0, 2, 4, 6, 8, A.
 * <p>
 * Key differences from level results ({@code S3kResultsScreenObjectInstance}):
 * <ul>
 *   <li>White background (from Pal_Results backdrop), not overlaid on level</li>
 *   <li>Ring bonus = rings x 10; time bonus = 5000 if perfect, else 0</li>
 *   <li>Emerald collection indicators with 3-state flicker</li>
 *   <li>Continue icon if >= 50 rings collected</li>
 *   <li>Two-phase display: initial elements, then emerald reveal text</li>
 * </ul>
 */
public class S3kSpecialStageResultsScreen implements ResultsScreen {
    private static final Logger LOG = Logger.getLogger(S3kSpecialStageResultsScreen.class.getName());

    // ---- State machine ----
    private static final int STATE_INIT = 0;
    private static final int STATE_PRE_TALLY = 1;
    private static final int STATE_POST_TALLY = 2;
    private static final int STATE_EMERALD_CHECK = 3;
    private static final int STATE_EMERALD_REVEAL = 4;
    private static final int STATE_EXIT = 5;

    // ---- ROM-accurate timing ----
    private static final int PRE_TALLY_WAIT = 360;       // 6*60 frames (ROM line 63329)
    private static final int MUSIC_TRIGGER_COUNTER = 289; // Play music when countdown == 289 (ROM line 63359)
    private static final int POST_TALLY_WAIT = 120;       // 2*60 frames (ROM line 63396)
    private static final int CONTINUE_WAIT = 270;         // 270 frames if >= 50 rings (ROM line 54005/63414)
    private static final int EMERALD_REVEAL_WAIT = 240;   // 4*60 frames (ROM line 63494/54064)
    private static final int EXIT_WAIT = 240;             // 4*60 frames (same as reveal)

    // ---- Slide speeds ----
    private static final int SLIDE_IN_SPEED = 16;    // moveq #$10,d1 (ROM line 63848/62847)
    private static final int SLIDE_OUT_SPEED = 32;   // move.w #$20 (ROM line 64075/62836)

    // ---- Ring threshold ----
    private static final int CONTINUE_RING_THRESHOLD = 50;

    // ---- VDP offset ----
    private static final int VDP_OFFSET = 128;

    // ---- Pattern caching ----
    private static final int PATTERN_BASE = PatternAtlasRange.SPECIAL_STAGE_RESULTS.base();

    // ---- Digit rendering (shared with LevResults_DisplayScore) ----
    private static final int[] DIVISORS = {1000000, 100000, 10000, 1000, 100, 10, 1};

    // ---- art_tile offsets ----
    // Map_Results frames reference tiles at level results VRAM positions ($520, $578, $6CA).
    // SS results loads art at different positions ($4F1, $523, $5B8, $6BC).
    // These offsets compensate, matching the ROM's per-object art_tile values.

    // Digit frames (0-10) ref $520+, art at $5B8. ROM art_tile=$98 ($5B8-$520)
    private static final int DIGIT_TILE_OFFSET = 0x98;
    // Char name frame ($13) refs $578+, art at $4F1. ROM art_tile=-$87 ($4F1-$578)
    private static final int CHAR_NAME_TILE_OFFSET = -(0x578 - 0x4F1); // -135
    private static final int PAL3_ADDITION = 3;
    private static final int TEXT_PALETTE_INDEX = 1;
    private static final int GREEN_PALETTE_INDEX = 3;
    private static final int SCORE_DIGITS_VRAM_START = 0x6E4;
    private static final int SCORE_DIGIT_COUNT = 7;
    private static final int SCORE_DIGIT_TILE_COUNT = SCORE_DIGIT_COUNT * 2;
    private static final int[] SCORE_DIVISORS = {1_000_000, 100_000, 10_000, 1_000, 100, 10, 1};
    private static final int SCORE_VALUE_X_ADJUST = -8;

    // ---- Input parameters ----
    private final int ringsCollected;
    private final boolean gotEmerald;
    private final int totalEmeraldCount;
    private final PlayerCharacter character;

    // ---- Tally ----
    private int ringBonus;
    private int timeBonus;

    // ---- State ----
    private int state = STATE_INIT;
    private int stateTimer;
    private int frameCounter;
    private boolean complete;
    private boolean musicPlayed;

    // ---- Elements ----
    private final List<ResultsElement> phase1Elements = new ArrayList<>();
    private final List<ResultsElement> phase2Elements = new ArrayList<>();
    private final List<CleanupSlider> cleanupSliders = new ArrayList<>();
    private final com.openggf.level.PatternDesc reusablePatternDesc = new com.openggf.level.PatternDesc();

    // ---- Continue icon ----
    private boolean showContinueIcon;
    private final int continueFrame;
    private final int continueX;
    private final int continueY;

    // ---- Emerald flicker ----
    private int emeraldFlickerCounter;

    // ---- Art/rendering ----
    private Pattern[] combinedPatterns;
    private Pattern[] sourceDigitPatterns;
    private ObjectSpriteSheet spriteSheet;
    private PatternSpriteRenderer renderer;
    private Palette[] resultsPalettes;
    private boolean artLoaded;
    private boolean paletteLoaded;
    private boolean artCached;
    private int lastScoreValue = Integer.MIN_VALUE;

    public S3kSpecialStageResultsScreen(int ringsCollected, boolean gotEmerald,
                                         int stageIndex, int totalEmeraldCount,
                                         PlayerCharacter character) {
        this.ringsCollected = ringsCollected;
        this.gotEmerald = gotEmerald;
        this.totalEmeraldCount = totalEmeraldCount;
        this.character = character;

        // ROM lines 63320-63327: bonus calculation
        this.ringBonus = ringsCollected * 10;
        this.timeBonus = gotEmerald ? 5000 : 0;

        // Fade out music immediately (ROM line 63011)
        fadeOutMusic();

        // Load art
        loadArt();

        // Create initial elements
        createPhase1Elements();

        // Continue icon: mapping frame based on Player_mode (ROM lines 64042-64048)
        this.continueFrame = getContinueFrame();
        this.continueX = 0x17C - VDP_OFFSET;
        this.continueY = 0x14C - VDP_OFFSET;

        LOG.fine(() -> String.format("S3K SS results: rings=%d gotEmerald=%b totalEmeralds=%d ringBonus=%d timeBonus=%d",
                ringsCollected, gotEmerald, totalEmeraldCount, ringBonus, timeBonus));
    }

    // ================================================================
    // State machine
    // ================================================================

    @Override
    public void update(int frameCounter, Object context) {
        this.frameCounter = frameCounter;
        stateTimer++;

        // Emerald flicker: 3-state counter (ROM lines 63203-63210)
        emeraldFlickerCounter++;
        if (emeraldFlickerCounter >= 3) {
            emeraldFlickerCounter = 0;
        }

        // Slide all active elements toward their targets
        slideElements(phase1Elements);
        slideElements(phase2Elements);

        switch (state) {
            case STATE_INIT -> updateInit();
            case STATE_PRE_TALLY -> updatePreTally();
            case STATE_POST_TALLY -> updatePostTally();
            case STATE_EMERALD_CHECK -> updateEmeraldCheck();
            case STATE_EMERALD_REVEAL -> updateEmeraldReveal();
            case STATE_EXIT -> updateExit();
        }
    }

    private void updateInit() {
        // One-shot: advance immediately (ROM: routine 0 sets timer and advances)
        state = STATE_PRE_TALLY;
        stateTimer = 0;
    }

    /**
     * ROM routine 2 (loc_2E410): 360-frame countdown, then tally.
     * Music at counter == 289 (71 frames into the wait).
     */
    private void updatePreTally() {
        int countdown = PRE_TALLY_WAIT - stateTimer;

        if (countdown > 0) {
            // Still in pre-tally wait
            if (!musicPlayed && countdown == MUSIC_TRIGGER_COUNTER) {
                musicPlayed = true;
                playMusic(GameMusic.ACT_CLEAR);
            }
            return;
        }

        // Tally phase: decrement bonuses by 10/frame
        int totalIncrement = 0;
        if (timeBonus > 0) {
            int dec = Math.min(10, timeBonus);
            timeBonus -= dec;
            totalIncrement += dec;
        }
        if (ringBonus > 0) {
            int dec = Math.min(10, ringBonus);
            ringBonus -= dec;
            totalIncrement += dec;
        }

        if (totalIncrement > 0) {
            GameServices.gameState().addScore(totalIncrement);
            // Tick sound every 4 frames (ROM line 63382-63384)
            if ((frameCounter & 3) == 0) {
                playSfx(Sonic3kSfx.SWITCH.id);
            }
        } else {
            // Tally complete (ROM line 63393-63397)
            playSfx(Sonic3kSfx.REGISTER.id);
            state = STATE_POST_TALLY;
            stateTimer = 0;
        }
    }

    /**
     * ROM routine 4 (loc_2E4D6/loc_2D506): post-tally wait, then continue icon
     * check and immediate advance to EMERALD_CHECK.
     * The 270-frame continue-icon display happens in EMERALD_CHECK via its timer
     * guard, matching ROM where routine 6 counts down $2E before checking emeralds.
     */
    private void updatePostTally() {
        if (stateTimer <= POST_TALLY_WAIT) {
            return;
        }

        // After 120-frame wait: spawn continue icon if >= 50 rings, then advance
        // ROM: loc_2E4EA spawns icon, sets 270f timer, then falls through to routine 6
        if (ringsCollected >= CONTINUE_RING_THRESHOLD) {
            showContinueIcon = true;
            playSfx(Sonic3kSfx.CONTINUE.id);
        }

        // Advance to EMERALD_CHECK — the 270f continue wait runs there (ROM: routine 6 at loc_2E534)
        state = STATE_EMERALD_CHECK;
        stateTimer = 0;
    }

    /**
     * ROM routine 6 (loc_2E512/loc_2D53A): optional continue-icon wait, then
     * check if emerald was earned. S3-only / SK-alone Chaos Emerald path.
     */
    private void updateEmeraldCheck() {
        // ROM: loc_2E534 counts down $2E (270f continue wait) before emerald check
        if (showContinueIcon && stateTimer <= CONTINUE_WAIT) {
            return;
        }

        // ROM: Only Sonic/Knuckles can trigger the emerald reveal (Tails excluded in S3)
        // For Chaos Emerald path: if failed or not all 7 emeralds → exit immediately
        boolean canReveal = gotEmerald && totalEmeraldCount >= 7
                && (character == PlayerCharacter.SONIC_AND_TAILS
                    || character == PlayerCharacter.SONIC_ALONE
                    || character == PlayerCharacter.KNUCKLES);

        if (!canReveal) {
            // ROM line 63483/54041: move.b #$C,(Game_mode).w
            complete = true;
            return;
        }

        // Create cleanup objects to slide bonus text off-screen (ROM lines 63468-63478)
        if (cleanupSliders.isEmpty()) {
            createCleanupSliders();
        }

        // Update cleanup sliders
        var iter = cleanupSliders.iterator();
        while (iter.hasNext()) {
            CleanupSlider slider = iter.next();
            if (slider.timer > 0) {
                slider.timer--;
            } else {
                slider.x += SLIDE_OUT_SPEED;
                if (slider.x > 576) {
                    iter.remove();
                }
            }
        }

        // When all cleanup objects are done, advance (ROM: tst.w $30(a0) / beq.s)
        if (cleanupSliders.isEmpty()) {
            state = STATE_EMERALD_REVEAL;
            stateTimer = 0;
            createPhase2Elements();
        }
    }

    /**
     * ROM routine 8 (loc_2E5C0/loc_2D590): display emerald reveal text.
     */
    private void updateEmeraldReveal() {
        if (stateTimer >= EMERALD_REVEAL_WAIT) {
            state = STATE_EXIT;
            stateTimer = 0;
        }
    }

    /**
     * ROM routine A (loc_2E5E0/loc_2D5DC): final wait then exit.
     */
    private void updateExit() {
        if (stateTimer >= EXIT_WAIT) {
            complete = true;
        }
    }

    @Override
    public boolean isComplete() {
        return complete;
    }

    // ================================================================
    // Test accessors (package-private) — expose tally/visibility outputs
    // computed by the constructor and update() state machine so unit tests
    // can validate them without rendering or reflection.
    // ================================================================

    /** Current ring-bonus countdown value (rings x 10 at construction). */
    int ringBonusForTest() {
        return ringBonus;
    }

    /** Current time-bonus countdown value (5000 if perfect at construction, else 0). */
    int timeBonusForTest() {
        return timeBonus;
    }

    /** Phase-1 element index of the continue-prompt label (loc_2EBxx element 5). */
    private static final int CONTINUE_LABEL_INDEX = 5;
    /** Phase-1 element index of the failure message (loc_2EAC8 element 13). */
    private static final int FAIL_MESSAGE_INDEX = 13;
    /** Phase-1 element index of the character name (loc_2EAD8 element 14). */
    private static final int CHAR_NAME_INDEX = 14;
    /** Phase-1 element index of the "SUPER SONIC" label (loc_2EBCC element 18). */
    private static final int SUPER_TEXT_INDEX = 18;

    /** Whether the continue-prompt label is visible (rings &gt;= continue threshold). */
    boolean continueLabelVisibleForTest() {
        return phase1ElementVisible(CONTINUE_LABEL_INDEX);
    }

    /** Whether the failure message is visible (no emerald earned). */
    boolean failMessageVisibleForTest() {
        return phase1ElementVisible(FAIL_MESSAGE_INDEX);
    }

    /** Whether the character-name label is visible (emerald earned). */
    boolean charNameVisibleForTest() {
        return phase1ElementVisible(CHAR_NAME_INDEX);
    }

    /** Whether the "SUPER SONIC" label is visible (emerald earned + all 7). */
    boolean superTextVisibleForTest() {
        return phase1ElementVisible(SUPER_TEXT_INDEX);
    }

    private boolean phase1ElementVisible(int index) {
        return index >= 0 && index < phase1Elements.size() && phase1Elements.get(index).visible;
    }

    // ================================================================
    // Element creation
    // ================================================================

    /**
     * Creates 19 elements from ObjDat2_2E834 (ROM lines 63731-63788).
     * Positions in VDP coordinates (screen coords = VDP - 128).
     */
    private void createPhase1Elements() {
        int charXOffset = getCharXOffset();
        int charFrameAdj = getCharFrameAdj();

        // SS results uses +$1A frame adjustment for label elements (ROM: loc_2EA1E).
        // Knuckles adds an additional +5 (ROM lines 63954-63956).
        int labelFrameAdj = 0x1A;
        if (character == PlayerCharacter.KNUCKLES) labelFrameAdj += 5;

        // All-7-emeralds shifts for character name (ROM lines 63954-63956)
        int all7Shift = (totalEmeraldCount >= 7 && gotEmerald) ? -0x10 : 0;

        // --- Elements 0-5: Score, bonuses, continue ---
        phase1Elements.add(new ResultsElement(ElemType.SCORE_ROW, 0x120, 0x4E0, 0x100,
                0x17 + labelFrameAdj, 0x60));
        phase1Elements.add(labelWithPaletteRemap(0xC0, 0x4C0, 0x118, 0x18 + labelFrameAdj, 0x58,
                TEXT_PALETTE_INDEX, GREEN_PALETTE_INDEX));
        phase1Elements.add(new ResultsElement(ElemType.RING_BONUS, 0x178, 0x578, 0x118, 1, 0x40));
        phase1Elements.add(labelWithPaletteRemap(0xC0, 0x500, 0x128, 0x19 + labelFrameAdj, 0x40,
                TEXT_PALETTE_INDEX, GREEN_PALETTE_INDEX));
        phase1Elements.add(new ResultsElement(ElemType.TIME_BONUS, 0x178, 0x5B8, 0x128, 1, 0x40));
        ResultsElement continueElem = labelWithPaletteRemap(0xC0, 0x540, 0x138, 0x1A + labelFrameAdj, 0x48,
                TEXT_PALETTE_INDEX, GREEN_PALETTE_INDEX);
        continueElem.visible = (ringsCollected >= CONTINUE_RING_THRESHOLD);
        phase1Elements.add(continueElem);

        // --- Elements 6-12: Emerald indicators (loc_2EAA6, art_tile=0) ---
        addEmerald(0x120, 0xD0, 0x1B, 0);
        addEmerald(0x110, 0xE8, 0x1C, 1);
        addEmerald(0x130, 0xE8, 0x1D, 2);
        addEmerald(0x100, 0xD0, 0x1E, 3);
        addEmerald(0x140, 0xD0, 0x1F, 4);
        addEmerald(0xF0, 0xE8, 0x20, 5);
        addEmerald(0x150, 0xE8, 0x21, 6);

        // --- Element 13: Failure message (loc_2EAC8) ---
        ResultsElement failMsg = new ResultsElement(ElemType.LABEL,
                0x120, 0x460, 0xA0, 0x22, 0x60, PAL3_ADDITION);
        failMsg.visible = !gotEmerald;
        phase1Elements.add(failMsg);

        // --- Element 14: Character name (loc_2EAD8/loc_2EAF6) ---
        // art_tile = -$87 ($FF79): tile offset applied in renderElement via isCharNameFrame()
        int charNameTargetX = 0xD4 + charXOffset + all7Shift;
        int charNameStartX = 0x394 + charXOffset + all7Shift;
        // Char name uses palette 0 (orange) — art_tile -$87 wraps palette back to 0.
        ResultsElement charName = new ResultsElement(ElemType.LABEL,
                charNameTargetX, charNameStartX, 0x98, 0x13 + charFrameAdj, 0x48,
                character == PlayerCharacter.KNUCKLES ? 1 : 0);
        charName.visible = gotEmerald;
        phase1Elements.add(charName);

        // --- Element 15: "GOT THEM ALL" (loc_2EB30) ---
        int gotAllTargetX = 0x124 - charXOffset + all7Shift;
        int gotAllStartX = 0x3E4 - charXOffset + all7Shift;
        ResultsElement gotAll = new ResultsElement(ElemType.LABEL,
                gotAllTargetX, gotAllStartX, 0x98, 0x23, 0x48, PAL3_ADDITION);
        gotAll.visible = gotEmerald;
        phase1Elements.add(gotAll);

        // --- Element 16: Emerald type label (loc_2EB64) ---
        int chaosEmShift = (totalEmeraldCount >= 7 && gotEmerald) ? -8 : 0;
        ResultsElement chaosEm = new ResultsElement(ElemType.LABEL,
                0x120 + chaosEmShift, 0x460 + chaosEmShift, 0xB0, 0x24, 0x64, PAL3_ADDITION);
        chaosEm.visible = gotEmerald;
        phase1Elements.add(chaosEm);

        // --- Element 17: "NOW" (loc_2EBA4) ---
        ResultsElement nowText = new ResultsElement(ElemType.LABEL,
                0x114 - charXOffset, 0x3D4 - charXOffset, 0x98, 0x25, 0x20, PAL3_ADDITION);
        nowText.visible = gotEmerald && totalEmeraldCount >= 7;
        phase1Elements.add(nowText);

        // --- Element 18: "SUPER SONIC" (loc_2EBCC) ---
        ResultsElement superText = new ResultsElement(ElemType.LABEL,
                0x118, 0x458, 0xB0, 0x26, 0x10, PAL3_ADDITION);
        superText.visible = gotEmerald && totalEmeraldCount >= 7;
        phase1Elements.add(superText);
    }

    private void addEmerald(int x, int y, int frame, int slot) {
        ResultsElement elem = new ResultsElement(ElemType.EMERALD, x, x, y, frame, slot);
        elem.visible = GameServices.gameState().hasEmerald(slot);
        phase1Elements.add(elem);
    }

    /**
     * Creates 6 emerald reveal text elements from ObjDat2_2E918 (ROM lines 63789-63807).
     */
    private void createPhase2Elements() {
        int charXOffset = getCharXOffset();
        int charFrameAdj = getCharFrameAdj();

        // Phase 2 reveal elements use the mapping palettes in the combined-cart path.
        phase2Elements.add(new ResultsElement(ElemType.LABEL,
                0xC0 + charXOffset, 0x3C0 + charXOffset, 0x98, 0x27, 0x38));
        phase2Elements.add(new ResultsElement(ElemType.LABEL, // char name = orange
                0x100 + charXOffset, 0x400 + charXOffset, 0x98, 0x13 + charFrameAdj, 0x48,
                character == PlayerCharacter.KNUCKLES ? 1 : 0));
        phase2Elements.add(new ResultsElement(ElemType.LABEL,
                0x150 - charXOffset, 0x450 - charXOffset, 0x98, 0x3A, 0x30));
        phase2Elements.add(new ResultsElement(ElemType.LABEL,
                0xC0 + charXOffset, 0x440 + charXOffset, 0xB0, 0x28, 0x20));
        phase2Elements.add(new ResultsElement(ElemType.LABEL,
                0xE8, 0x468, 0xB0, 0x12, 0x50, character == PlayerCharacter.KNUCKLES ? 1 : 0));
        phase2Elements.add(new ResultsElement(ElemType.LABEL, // char name = orange
                0x138 + charXOffset, 0x4B8 + charXOffset, 0xB0, 0x13 + charFrameAdj, 0x48,
                character == PlayerCharacter.KNUCKLES ? 1 : 0));
    }

    /**
     * Creates cleanup "slide-out" objects that push bonus text off-screen.
     * ROM: 5 objects at loc_2EC1E (lines 63468-63477 / 54026-54035).
     * Timer values: 0, 0, 4, 0, 4 for staggered exit.
     * Slider X initialized from the current position of the elements they represent.
     */
    private void createCleanupSliders() {
        int[] timers = {0, 0, 4, 0, 4};
        for (int i = 0; i < timers.length; i++) {
            // Initialize slider X from the corresponding phase1 element's current position
            int startX = (i < phase1Elements.size()) ? phase1Elements.get(i).currentX : 0;
            cleanupSliders.add(new CleanupSlider(startX, timers[i]));
        }

        // Mark the first 6 phase1 elements (score/bonus labels) for slide-out
        for (int i = 0; i < Math.min(6, phase1Elements.size()); i++) {
            ResultsElement elem = phase1Elements.get(i);
            if (elem.type != ElemType.EMERALD) {
                elem.sliding_out = true;
            }
        }
    }

    // ================================================================
    // Element helpers
    // ================================================================

    private ResultsElement label(int targetX, int startX, int y, int frame, int width) {
        return new ResultsElement(ElemType.LABEL, targetX, startX, y, frame, width);
    }

    private ResultsElement labelWithPaletteRemap(int targetX, int startX, int y, int frame, int width,
                                                 int fromPalette, int toPalette) {
        return new ResultsElement(ElemType.LABEL, targetX, startX, y, frame, width,
                0, fromPalette, toPalette);
    }

    private void slideElements(List<ResultsElement> elements) {
        for (ResultsElement elem : elements) {
            if (elem.visible && !elem.sliding_out) {
                elem.slideIn();
            }
            if (elem.sliding_out) {
                elem.currentX += SLIDE_OUT_SPEED;
                if (elem.currentX > 576) {
                    elem.visible = false;
                }
            }
        }
    }

    /** sub_2EC80 character X offset (ROM lines 64113-64131) */
    private int getCharXOffset() {
        return switch (character) {
            case KNUCKLES -> -0x18;
            case TAILS_ALONE -> 4;
            default -> 0;
        };
    }

    /** sub_2EC80 character frame adjustment (ROM lines 64113-64131) */
    private int getCharFrameAdj() {
        return switch (character) {
            case KNUCKLES -> 3;
            case TAILS_ALONE -> 2;
            default -> 0;
        };
    }

    /** Continue icon mapping frame (ROM lines 64042-64048) */
    private int getContinueFrame() {
        return switch (character) {
            case TAILS_ALONE -> 0x2A;
            case KNUCKLES -> 0x2B;
            default -> 0x29;
        };
    }

    // ================================================================
    // Rendering
    // ================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!artLoaded || renderer == null) return;
        updateDynamicScorePatterns();
        if (!artCached) ensureArtCached();
        if (!artCached || !renderer.isReady()) return;

        for (ResultsElement elem : phase1Elements) {
            if (!elem.visible) continue;
            renderElement(elem);
        }

        for (ResultsElement elem : phase2Elements) {
            if (!elem.visible) continue;
            renderElement(elem);
        }

        if (showContinueIcon) {
            // ROM: btst #3,(Level_frame_counter+1).w — 8-frame-on, 8-frame-off blink
            if (((frameCounter >> 3) & 1) != 0) {
                // Continue icon: no art_tile override, uses piece palette
                renderMappingFrameWithTileOffset(continueFrame, continueX, continueY, 0, -1);
            }
        }
    }

    private void renderElement(ResultsElement elem) {
        switch (elem.type) {
            case SCORE_ROW -> renderScoreRow(elem);
            case RING_BONUS -> renderBonusDigits(elem, ringBonus);
            case TIME_BONUS -> renderBonusDigits(elem, timeBonus);
            case EMERALD -> renderEmerald(elem);
            default -> {
                int tileOffset = isCharNameFrame(elem.mappingFrame) ? CHAR_NAME_TILE_OFFSET : 0;
                if (elem.paletteRemapFrom >= 0) {
                    renderMappingFrameWithPaletteRemap(elem.mappingFrame, elem.screenX(), elem.screenY(),
                            tileOffset, elem.paletteRemapFrom, elem.paletteRemapTo);
                } else if (elem.paletteAdd != 0) {
                    renderMappingFrameWithPalAdd(elem.mappingFrame, elem.screenX(), elem.screenY(),
                            tileOffset, elem.paletteAdd);
                } else {
                    renderMappingFrameWithTileOffset(elem.mappingFrame, elem.screenX(), elem.screenY(),
                            tileOffset, -1);
                }
            }
        }
    }

    private void renderScoreRow(ResultsElement elem) {
        if (frameIndexOutOfRange(elem.mappingFrame)) {
            return;
        }
        var frame = spriteSheet.getFrame(elem.mappingFrame);
        if (frame == null) {
            return;
        }
        var pieces = frame.pieces();
        for (int i = 0; i < pieces.size(); i++) {
            int paletteOverride = -1;
            int pieceWorldX = elem.screenX();
            if (i <= 1 && pieces.get(i).paletteIndex() == TEXT_PALETTE_INDEX) {
                paletteOverride = GREEN_PALETTE_INDEX;
            } else if (i == 2 || i == 3) {
                pieceWorldX += SCORE_VALUE_X_ADJUST;
            }
            renderMappingPiece(pieces.get(i), pieceWorldX, elem.screenY(), 0, paletteOverride);
        }
    }

    private boolean frameIndexOutOfRange(int frameIndex) {
        return frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount();
    }

    private boolean isCharNameFrame(int frame) {
        return frame >= 0x13 && frame <= 0x16; // $13=Sonic, $14=Miles, $15=Tails, $16=Knuckles
    }

    private void renderEmerald(ResultsElement elem) {
        // 3-state flicker: draw when counter != 0 (visible 2/3 of frames)
        if (emeraldFlickerCounter == 0) return;
        // Emeralds use their own palette from the mapping piece data (palette 2)
        renderMappingFrameWithTileOffset(elem.mappingFrame, elem.screenX(), elem.screenY(),
                0, -1);
    }

    /**
     * Render a 7-digit bonus value with leading zero suppression.
     * ROM: LevResults_DisplayScore (sonic3k.asm lines 62789-62815).
     * <p>
     * Digit mapping frames (1-10) reference tiles at $520+ but general art is loaded
     * at $5B8 for SS results. ROM compensates with art_tile=$98 ($5B8-$520).
     */
    private void renderBonusDigits(ResultsElement elem, int value) {
        int x = elem.screenX() - 0x38;
        int y = elem.screenY();
        boolean hasNonZero = false;
        int remaining = value;

        for (int i = 0; i < 7; i++) {
            int digit = remaining / DIVISORS[i];
            remaining %= DIVISORS[i];
            if (digit != 0) hasNonZero = true;

            int frameIdx;
            if (hasNonZero || i == 6) {
                frameIdx = digit + 1;
            } else {
                frameIdx = 0;
            }

            if (frameIdx > 0) {
                renderMappingFrameWithTileOffset(frameIdx, x + i * 8, y, DIGIT_TILE_OFFSET, -1);
            }
        }
    }

    private void updateDynamicScorePatterns() {
        if (combinedPatterns == null || sourceDigitPatterns == null) {
            return;
        }

        int score = Math.max(0, Math.min(GameServices.gameState().getScore(), 9_999_999));
        if (score == lastScoreValue) {
            return;
        }

        int scoreDigitStart = SCORE_DIGITS_VRAM_START - Sonic3kConstants.VRAM_SS_RESULTS_BASE;
        if (scoreDigitStart < 0 || scoreDigitStart + SCORE_DIGIT_TILE_COUNT > combinedPatterns.length) {
            return;
        }

        ensurePatternSlots(combinedPatterns, scoreDigitStart, SCORE_DIGIT_TILE_COUNT);
        writeScoreValue(combinedPatterns, scoreDigitStart, score, sourceDigitPatterns);

        if (artCached && renderer != null) {
            GraphicsManager graphicsManager = GameServices.graphics();
            if (graphicsManager != null) {
                renderer.updatePatternRange(graphicsManager, scoreDigitStart, SCORE_DIGIT_TILE_COUNT);
            }
        }

        lastScoreValue = score;
    }

    /**
     * Render a mapping frame with a per-call tile index offset and palette override.
     * Used for elements that need art_tile compensation (digits, character name).
     */
    private void renderMappingFrameWithTileOffset(int frameIndex, int worldX, int worldY,
                                                    int tileOffset, int paletteOverride) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount()) return;
        var frame = spriteSheet.getFrame(frameIndex);
        if (frame == null) return;

        for (var piece : frame.pieces()) {
            renderMappingPiece(piece, worldX, worldY, tileOffset, paletteOverride);
        }
    }

    /**
     * Like renderMappingFrameWithTileOffset but ADDS palAdd to each piece's palette
     * (matching ROM art_tile addition behavior where palette bits wrap mod 4).
     */
    private void renderMappingFrameWithPalAdd(int frameIndex, int worldX, int worldY,
                                               int tileOffset, int palAdd) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount()) return;
        var frame = spriteSheet.getFrame(frameIndex);
        if (frame == null) return;
        var gm = GameServices.graphics();
        if (gm == null) return;

        for (var piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            boolean pieceHFlip = piece.hFlip();
            boolean pieceVFlip = piece.vFlip();
            int palIdx = (piece.paletteIndex() + palAdd) & 0x3;

            for (int col = 0; col < widthTiles; col++) {
                for (int row = 0; row < heightTiles; row++) {
                    int tileIdx = piece.tileIndex() + tileOffset + (col * heightTiles + row);
                    int patternId = PATTERN_BASE + tileIdx;
                    int drawX = worldX + piece.xOffset()
                            + (pieceHFlip ? (widthTiles - 1 - col) : col) * 8;
                    int drawY = worldY + piece.yOffset()
                            + (pieceVFlip ? (heightTiles - 1 - row) : row) * 8;

                    int descIndex = patternId & 0x7FF;
                    if (piece.priority()) descIndex |= 0x8000;
                    if (pieceHFlip) descIndex |= 0x800;
                    if (pieceVFlip) descIndex |= 0x1000;
                    descIndex |= (palIdx & 0x3) << 13;

                    com.openggf.level.PatternDesc desc = configureReusablePatternDesc(descIndex);
                    gm.renderPatternWithId(patternId, desc, drawX, drawY);
                }
            }
        }
    }

    private void renderMappingFrameWithPaletteRemap(int frameIndex, int worldX, int worldY,
                                                     int tileOffset, int fromPalette, int toPalette) {
        if (frameIndex < 0 || frameIndex >= spriteSheet.getFrameCount()) return;
        var frame = spriteSheet.getFrame(frameIndex);
        if (frame == null) return;
        var gm = GameServices.graphics();
        if (gm == null) return;

        for (var piece : frame.pieces()) {
            int widthTiles = piece.widthTiles();
            int heightTiles = piece.heightTiles();
            boolean pieceHFlip = piece.hFlip();
            boolean pieceVFlip = piece.vFlip();
            int palIdx = piece.paletteIndex() == fromPalette ? toPalette : piece.paletteIndex();

            for (int col = 0; col < widthTiles; col++) {
                for (int row = 0; row < heightTiles; row++) {
                    int tileIdx = piece.tileIndex() + tileOffset + (col * heightTiles + row);
                    int patternId = PATTERN_BASE + tileIdx;
                    int drawX = worldX + piece.xOffset()
                            + (pieceHFlip ? (widthTiles - 1 - col) : col) * 8;
                    int drawY = worldY + piece.yOffset()
                            + (pieceVFlip ? (heightTiles - 1 - row) : row) * 8;

                    int descIndex = patternId & 0x7FF;
                    if (piece.priority()) descIndex |= 0x8000;
                    if (pieceHFlip) descIndex |= 0x800;
                    if (pieceVFlip) descIndex |= 0x1000;
                    descIndex |= (palIdx & 0x3) << 13;

                    com.openggf.level.PatternDesc desc = configureReusablePatternDesc(descIndex);
                    gm.renderPatternWithId(patternId, desc, drawX, drawY);
                }
            }
        }
    }

    private void renderMappingPiece(com.openggf.level.render.SpriteMappingPiece piece,
                                    int worldX, int worldY, int tileOffset, int paletteOverride) {
        var gm = GameServices.graphics();
        if (gm == null) return;

        int widthTiles = piece.widthTiles();
        int heightTiles = piece.heightTiles();
        boolean pieceHFlip = piece.hFlip();
        boolean pieceVFlip = piece.vFlip();

        for (int col = 0; col < widthTiles; col++) {
            for (int row = 0; row < heightTiles; row++) {
                int tileIdx = piece.tileIndex() + tileOffset + (col * heightTiles + row);
                int patternId = PATTERN_BASE + tileIdx;
                int drawX = worldX + piece.xOffset()
                        + (pieceHFlip ? (widthTiles - 1 - col) : col) * 8;
                int drawY = worldY + piece.yOffset()
                        + (pieceVFlip ? (heightTiles - 1 - row) : row) * 8;

                int palIdx = (paletteOverride >= 0) ? paletteOverride : piece.paletteIndex();
                int descIndex = patternId & 0x7FF;
                if (piece.priority()) descIndex |= 0x8000;
                if (pieceHFlip) descIndex |= 0x800;
                if (pieceVFlip) descIndex |= 0x1000;
                descIndex |= (palIdx & 0x3) << 13;

                com.openggf.level.PatternDesc desc = configureReusablePatternDesc(descIndex);
                gm.renderPatternWithId(patternId, desc, drawX, drawY);
            }
        }
    }

    com.openggf.level.PatternDesc configureReusablePatternDesc(int descIndex) {
        reusablePatternDesc.set(descIndex);
        return reusablePatternDesc;
    }

    private void ensurePatternSlots(Pattern[] patterns, int startIndex, int count) {
        int endIndex = Math.min(patterns.length, startIndex + count);
        for (int i = Math.max(0, startIndex); i < endIndex; i++) {
            if (patterns[i] == null) {
                patterns[i] = new Pattern();
            }
        }
    }

    private void writeScoreValue(Pattern[] destination, int startIndex, int score, Pattern[] digits) {
        int remaining = score;
        boolean hasDigit = false;

        for (int i = 0; i < SCORE_DIVISORS.length; i++) {
            int digit = remaining / SCORE_DIVISORS[i];
            remaining %= SCORE_DIVISORS[i];
            boolean isLastDigit = i == SCORE_DIVISORS.length - 1;
            int tileIndex = startIndex + (i * 2);
            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                copyDigit(destination, tileIndex, digit, digits);
            } else {
                destination[tileIndex].clear();
                destination[tileIndex + 1].clear();
            }
        }
    }

    private void copyDigit(Pattern[] destination, int destinationIndex, int digit, Pattern[] digits) {
        int sourceIndex = digit * 2;
        if (sourceIndex + 1 >= digits.length || destinationIndex + 1 >= destination.length) {
            return;
        }
        destination[destinationIndex].copyFrom(digits[sourceIndex]);
        destination[destinationIndex + 1].copyFrom(digits[sourceIndex + 1]);
    }

    // ================================================================
    // Art loading
    // ================================================================

    private void loadArt() {
        try {
            var rom = GameServices.rom().getRom();
            RomByteReader reader = RomByteReader.fromRom(rom);
            Sonic3kObjectArt objectArt = new Sonic3kObjectArt(null, reader);

            combinedPatterns = objectArt.loadSSResultsArt(character);
            sourceDigitPatterns = loadUncompressedPatterns(rom,
                    Sonic3kConstants.ART_UNC_HUD_DIGITS_ADDR,
                    Sonic3kConstants.ART_UNC_HUD_DIGITS_SIZE);
            List<SpriteMappingFrame> rawMappings = objectArt.loadResultsMappings();

            if (combinedPatterns != null && !rawMappings.isEmpty()) {
                // Art is loaded at $4F1 base (VRAM_SS_RESULTS_BASE), adjust mapping tile indices
                List<SpriteMappingFrame> adjustedMappings = Sonic3kObjectArt.adjustTileIndices(
                        rawMappings, -Sonic3kConstants.VRAM_SS_RESULTS_BASE);
                spriteSheet = new ObjectSpriteSheet(combinedPatterns, adjustedMappings, 0, 1);
                renderer = new PatternSpriteRenderer(spriteSheet);
                artLoaded = true;
            } else {
                LOG.warning("Failed to load SS results screen art");
            }

            // Load Pal_Results (ROM line 63110-63117)
            loadPalette(rom);
        } catch (Exception e) {
            LOG.warning("Failed to load SS results screen art: " + e.getMessage());
        }
    }

    private Pattern[] loadUncompressedPatterns(com.openggf.data.Rom rom, int addr, int size) throws Exception {
        byte[] data = rom.readBytes(addr, size);
        int patternCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
        Pattern[] patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] tileData = Arrays.copyOfRange(data,
                    i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(tileData);
        }
        return patterns;
    }

    /**
     * Loads Pal_Results (128 bytes = 4 palette lines x 16 colors x 2 bytes).
     * ROM: sonic3k.asm lines 63110-63117.
     */
    private void loadPalette(com.openggf.data.Rom rom) {
        try {
            byte[] paletteData = rom.readBytes(Sonic3kConstants.PAL_RESULTS_ADDR, 128);
            resultsPalettes = PaletteLoader.fromBytes(paletteData);
            paletteLoaded = true;
        } catch (Exception e) {
            LOG.warning("Failed to load SS results palette: " + e.getMessage());
        }
    }

    /**
     * Lazily caches patterns and palettes on the GL thread.
     * Called from appendRenderCommands() since GraphicsManager may not be
     * available at construction time (different thread).
     */
    private void ensureArtCached() {
        if (artCached) return;
        GraphicsManager gm = GameServices.graphics();
        if (gm == null) return;

        // Cache patterns
        if (renderer != null) {
            renderer.ensurePatternsCached(gm, PATTERN_BASE);
        }

        // Cache palettes (ROM line 63110: Pal_Results → all 4 palette lines)
        if (paletteLoaded && resultsPalettes != null) {
            Sonic3kSpecialStagePaletteUploader.cacheAll(gm, resultsPalettes);
        }

        artCached = true;
    }

    // ================================================================
    // Audio helpers
    // ================================================================

    private void fadeOutMusic() {
        try {
            GameServices.audio().fadeOutMusic();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fade out music for S3K special-stage results", e);
        }
    }

    private void playMusic(GameMusic music) {
        try {
            GameServices.audio().playMusic(music);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to play S3K special-stage results music " + music, e);
        }
    }

    private void playSfx(int id) {
        try {
            GameServices.audio().playSfx(id);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to play S3K special-stage results SFX " + id, e);
        }
    }

    // ================================================================
    // Inner types
    // ================================================================

    private enum ElemType { LABEL, SCORE_ROW, RING_BONUS, TIME_BONUS, EMERALD }

    private static class ResultsElement {
        final ElemType type;
        final int targetX;
        final int startX;
        final int y;
        final int mappingFrame;
        final int widthPixels;
        final int paletteAdd;
        final int paletteRemapFrom;
        final int paletteRemapTo;
        int currentX;
        boolean visible = true;
        boolean sliding_out;

        ResultsElement(ElemType type, int targetX, int startX, int y,
                       int mappingFrame, int widthPixels) {
            this(type, targetX, startX, y, mappingFrame, widthPixels, 0, -1, -1);
        }

        ResultsElement(ElemType type, int targetX, int startX, int y,
                       int mappingFrame, int widthPixels, int paletteAdd) {
            this(type, targetX, startX, y, mappingFrame, widthPixels, paletteAdd, -1, -1);
        }

        ResultsElement(ElemType type, int targetX, int startX, int y,
                       int mappingFrame, int widthPixels, int paletteAdd,
                       int paletteRemapFrom, int paletteRemapTo) {
            this.type = type;
            this.targetX = targetX;
            this.startX = startX;
            this.y = y;
            this.mappingFrame = mappingFrame;
            this.widthPixels = widthPixels;
            this.paletteAdd = paletteAdd;
            this.paletteRemapFrom = paletteRemapFrom;
            this.paletteRemapTo = paletteRemapTo;
            this.currentX = startX;
        }

        void slideIn() {
            if (currentX == targetX) return;
            if (currentX < targetX) {
                currentX = Math.min(currentX + SLIDE_IN_SPEED, targetX);
            } else {
                currentX = Math.max(currentX - SLIDE_IN_SPEED, targetX);
            }
        }

        int screenX() { return currentX - VDP_OFFSET; }
        int screenY() { return y - VDP_OFFSET; }
    }

    private static class CleanupSlider {
        int x;
        int timer;

        CleanupSlider(int x, int timer) {
            this.x = x;
            this.timer = timer;
        }
    }
}
