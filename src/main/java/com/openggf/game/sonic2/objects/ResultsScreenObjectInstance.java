package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.save.SaveReason;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.objects.AbstractResultsScreen;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.ZeroScalarArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * End of Act Results Screen (Object 3A).
 * <p>
 * Displays "SONIC GOT THROUGH ACT X" with time/ring bonus counters.
 * Based on ROM's Obj3A implementation in s2.asm.
 * <p>
 * States:
 * 1. SLIDE_IN: Text elements slide into position
 * 2. TALLY: Bonus counters tick down
 * 3. WAIT: Brief pause after tally
 * 4. TRANSITION: Load next level
 */
public class ResultsScreenObjectInstance extends AbstractResultsScreen
        implements ZeroScalarArgsRewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(ResultsScreenObjectInstance.class.getName());

    // Time bonus table from s2.asm (TimeBonuses), indexed by (seconds / 15)
    private static final int[] TIME_BONUSES = {
            5000, 5000, 1000, 500, 400, 400, 300, 300,
            200, 200, 200, 200, 100, 100, 100, 100,
            50, 50, 50, 50, 0
    };
    private static final int PERFECT_BONUS_POINTS = 5000;

    // Obj3A master sub-object slide geometry. Row 0 of Obj3A_SubObjectMetadata
    // (docs/s2disasm/s2.asm:28159) is the row loc_140CE writes over the
    // spawner's own slot, so it is the object whose routine-2 handler
    // (loc_14102) decides when the whole screen leaves the slide.
    // spriteScreenPositionX(0-96) and spriteScreenPositionXCentered(0) expand
    // through docs/s2disasm/s2.macros.asm:276,280 with
    // sprite_left_boundary = $80 and screen_width = 320
    // (docs/s2disasm/s2.constants.asm:1051,1061).
    private static final int ROM_MASTER_START_X = 0x80 + (0 - 96);
    private static final int ROM_MASTER_TARGET_X = 0x80 + (320 / 2);
    /** Obj34_MoveTowardsTargetPosition: moveq #$10,d0 (docs/s2disasm/s2.asm:27494). */
    private static final int ROM_SLIDE_STEP_PIXELS = 0x10;
    /**
     * The one object pass that ROM {@code Obj3A} spends in the routine bumped
     * by {@code loc_1419C} before {@code loc_14270} executes. Structural, from
     * the routine split itself, not measured from any recording.
     */
    private static final int ROM_EXIT_ROUTINE_PASS = 1;

    // Bonus values
    private int timeBonus;
    private int ringBonus;
    private int totalBonus;
    private boolean perfectBonus;
    private int perfectBonusRemaining;

    // Input data. Non-final so GenericFieldCapturer captures them and restoreObjectRewindState
    // reapplies them after rewind recreate rebuilds this screen with placeholder ctor args
    // (these gameplay-derived values are not encoded in the ObjectSpawn).
    private int elapsedTimeSeconds;
    private int ringCount;
    private int actNumber;
    private boolean allRingsCollected;

    // Screen-space positions for text elements (center of 320x224 screen)
    private static final int TEXT_Y_GOT_THROUGH = 56;
    private static final int TEXT_Y_ACT = 74;
    private static final int TEXT_Y_TIME_BONUS = 112;
    private static final int TEXT_Y_RING_BONUS = 128;
    private static final int TEXT_Y_TOTAL = 160;

    private int lastTimeBonus = Integer.MIN_VALUE;
    private int lastRingBonus = Integer.MIN_VALUE;
    private int lastTotalBonus = Integer.MIN_VALUE;
    private int lastPerfectBonus = Integer.MIN_VALUE;
    /** True once Obj3A routine 0 has observed an empty PLC queue. */
    private boolean plcReadinessPassed;
    private final Pattern blankDigit = new Pattern();

    public ResultsScreenObjectInstance(int elapsedTimeSeconds, int ringCount, int actNumber,
            boolean allRingsCollected) {
        super("results_screen");
        this.elapsedTimeSeconds = elapsedTimeSeconds;
        this.ringCount = ringCount;
        this.actNumber = actNumber;
        this.allRingsCollected = allRingsCollected;

        calculateBonuses();
        LOGGER.info(
                "Results screen created: act=" + actNumber + ", timeBonus=" + timeBonus + ", ringBonus=" + ringBonus +
                        ", total=" + totalBonus + ", perfect=" + perfectBonus);
    }

    private void calculateBonuses() {
        // Time bonus: index by (total seconds / 15)
        int index = elapsedTimeSeconds / 15;
        if (index < 0) {
            index = 0;
        } else if (index >= TIME_BONUSES.length) {
            index = TIME_BONUSES.length - 1;
        }
        timeBonus = TIME_BONUSES[index];

        // Ring bonus: rings * 10 (s2.asm Load_EndOfAct)
        ringBonus = ringCount * 10;

        // Perfect bonus: 5000 if all ring objects were collected in the act
        perfectBonus = allRingsCollected;
        perfectBonusRemaining = perfectBonus ? PERFECT_BONUS_POINTS : 0;

        // Total starts at 0 and counts up as bonuses tally
        totalBonus = 0;
    }

    @Override
    public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        if (!plcReadinessPassed) {
            Sonic2PlcService plcService = services().gameService(Sonic2PlcService.class);
            if (plcService != null && plcService.isBusy()) {
                this.frameCounter = vIntRunCount;
                return;
            }
            plcReadinessPassed = true;
        }
        super.update(vIntRunCount, player);
    }

    @Override
    protected TallyResult performTallyStep() {
        boolean anyRemaining = false;
        int totalIncrement = 0;

        // Decrement time bonus
        int[] timeResult = decrementBonus(timeBonus);
        timeBonus = timeResult[0];
        totalIncrement += timeResult[1];
        if (timeResult[1] > 0) anyRemaining = true;

        // Decrement ring bonus
        int[] ringResult = decrementBonus(ringBonus);
        ringBonus = ringResult[0];
        totalIncrement += ringResult[1];
        if (ringResult[1] > 0) anyRemaining = true;

        // Decrement perfect bonus
        int[] perfectResult = decrementBonus(perfectBonusRemaining);
        perfectBonusRemaining = perfectResult[0];
        totalIncrement += perfectResult[1];
        if (perfectResult[1] > 0) anyRemaining = true;

        // Update total
        totalBonus += totalIncrement;

        return tallyResult(anyRemaining, totalIncrement);
    }

    /**
     * ROM {@code Obj3A} has no slide-in duration constant. The master object
     * sits in routine 2 ({@code loc_14102}) stepping through
     * {@code Obj34_MoveTowardsTargetPosition} and advances to routine $A only
     * once {@code x_pixel} equals {@code titlecard_x_target}
     * (docs/s2disasm/s2.asm:27847-27853). The length is therefore derived from
     * the row-0 start/target and the routine's fixed 16px step:
     * (288 - 32) / 16 = 16 frames. The shared base class's 60-frame default is
     * not a ROM value.
     */
    @Override
    protected int getSlideDuration() {
        return (ROM_MASTER_TARGET_X - ROM_MASTER_START_X) / ROM_SLIDE_STEP_PIXELS;
    }

    @Override
    protected int getWaitDuration() {
        return totalBonus >= 1000 ? 0x12C : 0xB4;
    }

    /**
     * ROM {@code Obj3A} splits the post-tally wait and the level-order handoff
     * across two object passes. {@code loc_1419C}
     * (docs/s2disasm/s2.asm:27906-27913) decrements
     * {@code anim_frame_duration}, and on the pass that reaches zero it only
     * does {@code addq.b #2,routine(a0)} and falls into
     * {@code BranchTo18_DisplaySprite} — it never reaches {@code loc_14270}.
     * {@code loc_14270} (:27987-28004), which resolves {@code LevelOrder} and
     * writes {@code Level_Inactive_flag}, therefore runs on the FOLLOWING
     * pass. The shared base fires {@code onExitReady()} on the expiry pass
     * itself, which is one pass early; hold it for exactly one pass here.
     *
     * <p>Sonic 1's {@code GotThroughCard} has the identical two-routine shape
     * ({@code Got_Wait} bumps the routine and branches to {@code .display};
     * {@code Got_NextLevel} sets {@code f_restart} the next pass —
     * docs/s1disasm/_incObj/3A Got Through Card.asm:110-115,193-210). That site
     * is left unchanged here; only the S2 object is modelled in this change.
     */
    @Override
    protected void updateWait() {
        if (stateTimer >= getWaitDuration() + ROM_EXIT_ROUTINE_PASS) {
            state = STATE_EXIT;
            onExitReady();
        }
    }

    @Override
    protected void onExitReady() {
        triggerFadeToBlack();
    }

    private void triggerFadeToBlack() {
        LOGGER.info("Results screen complete, starting fade to black");

        // Persist progression before the level transition
        services().requestSessionSave(SaveReason.PROGRESSION_SAVE);

        // ROM loc_1429C writes move.w #1,(Level_Inactive_flag).w from inside
        // RunObjects (docs/s2disasm/s2.asm:28003). Level_MainLoop tests the
        // flag in the instruction immediately after jsr (RunObjects).l and
        // branches back to Level (:5095-5097), so this pass never reaches
        // JmpTo_DeformBgLayer (:5098) and Level's ClearPLC + Pal_FadeToBlack
        // (:4764-4765) run with no further RunObjects at all — Pal_FadeToBlack
        // is move.w #$15,d4 plus a dbf loop of WaitForVint + UpdateAllColours
        // + RunPLC_RAM (:3369-3382). Raising the engine's equivalent here stops
        // the post-act fade running as ordinary gameplay frames.
        var levelManager = services().levelManager();
        if (levelManager != null) {
            levelManager.setLevelInactiveForTransition(true);
        }

        // Start fade to black, then transition to next level when fade completes
        var fadeManager = services().fadeManager();
        var marker = services().nativeFadeLifecycle().beginNativeBlockingFade();
        fadeManager.startFadeToBlack(marker.wrapCompletion(() -> {
            // Mark this object as done
            setDestroyed(true);

            // Use existing LevelManager helper to advance to next act
            if (services().currentLevel() != null) {
                services().advanceToNextLevel();
            }

            // The ROM does not fade back in here. `Level:` runs
            // ClearPLC + Pal_FadeToBlack (docs/s2disasm/s2.asm:4764-4765) and
            // then, with no Pal_FadeFromBlack anywhere between `Level:`
            // (:4757) and `Level_MainLoop` (:5087), writes the level palette
            // straight to the active palette with `bsr.w PalLoad_Now` (:4881).
            // So the title card appears at full intensity on the first V-blank
            // of `Level_TtlCard`'s loop (:4914-4915). Running a reveal fade
            // here instead holds PALETTE_FADE for its whole duration and
            // pushes the first title-card-owned V-blank that many rows late.
            // This is the same correction, and the same mechanism, as the
            // special-stage results screen's clearOverlayForImmediatePaletteLoad
            // (GameLoop#enterResultsScreen).
            //
            // That V-blank's ProcessDMAQueue does NOT retire the outgoing act's
            // last player DPLC pair, as an earlier version of this comment
            // claimed. `Level:`'s own VDP setup zeroes the queue head first --
            // unguarded, so the shipped ROM runs it -- at s2.asm:4857-4858,
            // which lies between `Level:` (:4757) and the title-card loop
            // (:4914-4915). ProcessDMAQueue stops on a zero first word
            // (s2.asm:1772-1790), so the ROM DISCARDS that pair; it never
            // transfers. The timing argument above is unaffected.
            fadeManager.clearOverlayForImmediatePaletteLoad();
        }));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        Camera camera = services().camera();
        if (camera == null) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getResultsRenderer();
        if (renderer == null) {
            // Fallback to placeholder boxes if renderer not available
            appendPlaceholderRenderCommands(commands);
            return;
        }

        // Screen-space rendering - convert screen coords to world coords.
        // xOffset() is (viewportWidth - 320) / 2; 0 at native 320 (byte-identical).
        int worldBaseX = camera.getX() + xOffset();
        int worldBaseY = camera.getY();

        // All elements use ROM-accurate 16 pixels/frame slide speed
        // From Obj3A_SubObjectMetadata in s2.asm - all elements start sliding at frame 0
        // Elements sliding from LEFT move right (+16/frame)
        // Elements sliding from RIGHT move left (-16/frame, calculated as offset reduction)

        // Render text elements using ROM art
        // Frame indices from MapUnc_EOLTitleCards:
        // 0 = "SONIC GOT", 3 = "THROUGH", 4 = "ACT", 6-8 = act numbers
        // 10 = "TIME BONUS", 11 = "RING BONUS", 9 = "TOTAL", 14 = "PERFECT"

        // "SONIC GOT" (frame 0) - slides from left: start=-96, target=160, distance=256
        int gotOffset = getSlideOffset(256);  // starts 256 pixels left of target
        int gotX = SCREEN_CENTER_X - gotOffset;
        renderer.drawFrameIndex(0, worldBaseX + gotX, worldBaseY + TEXT_Y_GOT_THROUGH, false, false);

        // "THROUGH" (frame 3) - slides from right: start=384, target=128, distance=256
        int throughOffset = getSlideOffset(256);
        int throughX = (SCREEN_CENTER_X - 32) + throughOffset;
        renderer.drawFrameIndex(3, worldBaseX + throughX, worldBaseY + TEXT_Y_ACT, false, false);

        // "ACT" (frame 4) - slides from right: start=448, target=192, distance=256
        int actOffset = getSlideOffset(256);
        int actX = (SCREEN_CENTER_X + 32) + actOffset;
        renderer.drawFrameIndex(4, worldBaseX + actX, worldBaseY + TEXT_Y_ACT, false, false);

        // Act number (frame 6-8) - slides from right: start=504, target=248, distance=256
        int actFrame = 5 + actNumber; // actNumber is 1-based, so act 1 = frame 6
        int actNumOffset = getSlideOffset(256);
        int actNumX = (SCREEN_CENTER_X + 88) + actNumOffset;
        renderer.drawFrameIndex(actFrame, worldBaseX + actNumX, worldBaseY + 62, false, false);

        // Bonus displays - all start sliding at frame 0 along with title elements
        // Update bonus patterns for display (numbers show initial values until tally changes them)
        updateBonusPatterns(renderManager);

        // "TIME BONUS" (frame 10) - slides from right: start=672, target=160, distance=512
        int timeBonusOffset = getSlideOffset(512);
        renderer.drawFrameIndex(10, worldBaseX + SCREEN_CENTER_X + timeBonusOffset, worldBaseY + TEXT_Y_TIME_BONUS, false, false);

        // "RING BONUS" (frame 11) - slides from right: start=688, target=160, distance=528
        int ringBonusOffset = getSlideOffset(528);
        renderer.drawFrameIndex(11, worldBaseX + SCREEN_CENTER_X + ringBonusOffset, worldBaseY + TEXT_Y_RING_BONUS, false, false);

        // "PERFECT" (frame 14) - slides from right: start=704, target=160, distance=544
        if (perfectBonus) {
            int perfectOffset = getSlideOffset(544);
            renderer.drawFrameIndex(14, worldBaseX + SCREEN_CENTER_X + perfectOffset, worldBaseY + 144, false, false);
        }

        // "TOTAL" (frame 9) - slides from right: start=720, target=160, distance=560
        int totalOffset = getSlideOffset(560);
        renderer.drawFrameIndex(9, worldBaseX + SCREEN_CENTER_X + totalOffset, worldBaseY + TEXT_Y_TOTAL, false, false);
    }

    private void updateBonusPatterns(ObjectRenderManager renderManager) {
        PatternSpriteRenderer renderer = renderManager.getResultsRenderer();
        if (renderer == null) {
            return;
        }
        Pattern[] digitPatterns = renderManager.getResultsHudDigitPatterns();
        if (digitPatterns == null || digitPatterns.length < 20) {
            return;
        }
        ObjectSpriteSheet resultsSheet = renderManager.getResultsSheet();
        if (resultsSheet == null) {
            return;
        }
        Pattern[] patterns = resultsSheet.getPatterns();
        if (patterns == null || patterns.length < Sonic2Constants.RESULTS_BONUS_DIGIT_TILES) {
            return;
        }

        int currentPerfect = perfectBonus ? perfectBonusRemaining : 0;
        if (timeBonus == lastTimeBonus
                && ringBonus == lastRingBonus
                && totalBonus == lastTotalBonus
                && currentPerfect == lastPerfectBonus) {
            return;
        }

        ensureWritableDigitPatterns(patterns);
        writeBonusValue(patterns, 0, totalBonus, digitPatterns);
        writeBonusValue(patterns, Sonic2Constants.RESULTS_BONUS_DIGIT_GROUP_TILES, timeBonus, digitPatterns);
        writeBonusValue(patterns, Sonic2Constants.RESULTS_BONUS_DIGIT_GROUP_TILES * 2, ringBonus, digitPatterns);
        if (perfectBonus) {
            writeBonusValue(patterns, Sonic2Constants.RESULTS_BONUS_DIGIT_GROUP_TILES * 3, perfectBonusRemaining,
                    digitPatterns);
        } else {
            clearBonusValue(patterns, Sonic2Constants.RESULTS_BONUS_DIGIT_GROUP_TILES * 3);
        }

        GraphicsManager graphicsManager = services().graphicsManager();
        renderer.updatePatternRange(graphicsManager, 0, Sonic2Constants.RESULTS_BONUS_DIGIT_TILES);

        lastTimeBonus = timeBonus;
        lastRingBonus = ringBonus;
        lastTotalBonus = totalBonus;
        lastPerfectBonus = currentPerfect;
    }

    private void ensureWritableDigitPatterns(Pattern[] patterns) {
        for (int i = 0; i < Sonic2Constants.RESULTS_BONUS_DIGIT_TILES; i++) {
            if (patterns[i] == null) {
                patterns[i] = new Pattern();
            }
        }
    }

    private void clearBonusValue(Pattern[] dest, int startIndex) {
        for (int i = 0; i < Sonic2Constants.RESULTS_BONUS_DIGIT_GROUP_TILES; i++) {
            int target = startIndex + i;
            if (target < dest.length) {
                dest[target].copyFrom(blankDigit);
            }
        }
    }

    private void writeBonusValue(Pattern[] dest, int startIndex, int value, Pattern[] digits) {
        int[] divisors = { 1000, 100, 10, 1 };
        boolean hasDigit = false;
        for (int i = 0; i < divisors.length; i++) {
            int divisor = divisors[i];
            int digit = value / divisor;
            value %= divisor;
            int tileIndex = startIndex + (i * 2);
            // Always show the last digit (ones place), even if value is 0
            boolean isLastDigit = (i == divisors.length - 1);
            if (digit != 0 || hasDigit || isLastDigit) {
                hasDigit = true;
                copyDigit(dest, tileIndex, digit, digits);
            } else {
                dest[tileIndex].copyFrom(blankDigit);
                dest[tileIndex + 1].copyFrom(blankDigit);
            }
        }
    }

    private void copyDigit(Pattern[] dest, int destIndex, int digit, Pattern[] digits) {
        int srcIndex = digit * 2;
        if (srcIndex + 1 >= digits.length || destIndex + 1 >= dest.length) {
            return;
        }
        dest[destIndex].copyFrom(digits[srcIndex]);
        dest[destIndex + 1].copyFrom(digits[srcIndex + 1]);
    }

    /**
     * Fallback placeholder rendering when ROM art is not available.
     */
    private void appendPlaceholderRenderCommands(List<GLCommand> commands) {
        Camera camera = services().camera();
        if (camera == null) {
            return;
        }

        int worldBaseX = camera.getX() + xOffset();
        int worldBaseY = camera.getY();

        // All elements use ROM-accurate 16 pixels/frame slide, starting at frame 0

        // "SONIC GOT THROUGH" placeholder - slides from left
        int gotOffset = getSlideOffset(256);
        int gotThroughX = worldBaseX + SCREEN_CENTER_X - gotOffset;
        renderPlaceholderBox(commands, gotThroughX, worldBaseY + TEXT_Y_GOT_THROUGH, 80, 16, 0.2f, 0.6f, 1.0f);

        // "ACT X" placeholder - slides from right
        int actOffset = getSlideOffset(256);
        int actX = worldBaseX + SCREEN_CENTER_X + actOffset;
        renderPlaceholderBox(commands, actX, worldBaseY + TEXT_Y_ACT, 48, 16, 0.2f, 0.8f, 0.4f);

        // Bonus text - all slide from right starting at frame 0
        int timeBonusOffset = getSlideOffset(512);
        int ringBonusOffset = getSlideOffset(528);
        int totalOffset = getSlideOffset(560);

        renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40 + timeBonusOffset, worldBaseY + TEXT_Y_TIME_BONUS, 80, 12, 1.0f, 1.0f, 0.4f);
        renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40 + ringBonusOffset, worldBaseY + TEXT_Y_RING_BONUS, 80, 12, 1.0f, 0.8f, 0.2f);
        renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40 + totalOffset, worldBaseY + TEXT_Y_TOTAL, 80, 12, 1.0f, 0.4f, 0.4f);
    }

    public int getTimeBonus() {
        return timeBonus;
    }

    public int getRingBonus() {
        return ringBonus;
    }

    public int getTotalBonus() {
        return totalBonus;
    }

    public boolean isPerfect() {
        return perfectBonus;
    }

    @Override
    protected void playTickSound() {
        try {
            services().playSfx(Sonic2Sfx.BLIP.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    @Override
    protected void playTallyEndSound() {
        try {
            services().playSfx(Sonic2Sfx.TALLY_END.id);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }
}
