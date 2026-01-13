package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.Pattern;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpriteSheet;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;

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
public class ResultsScreenObjectInstance extends AbstractResultsScreen {
    private static final Logger LOGGER = Logger.getLogger(ResultsScreenObjectInstance.class.getName());

    // Time bonus table from s2.asm (TimeBonuses), indexed by (seconds / 15)
    private static final int[] TIME_BONUSES = {
            5000, 5000, 1000, 500, 400, 400, 300, 300,
            200, 200, 200, 200, 100, 100, 100, 100,
            50, 50, 50, 50, 0
    };
    private static final int PERFECT_BONUS_POINTS = 5000;

    // Bonus values
    private int timeBonus;
    private int ringBonus;
    private int totalBonus;
    private boolean perfectBonus;
    private int perfectBonusRemaining;

    // Input data
    private final int elapsedTimeSeconds;
    private final int ringCount;
    private final int actNumber;
    private final boolean allRingsCollected;

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

        return new TallyResult(anyRemaining, totalIncrement);
    }

    @Override
    protected void onExitReady() {
        triggerLevelTransition();
    }

    private void triggerLevelTransition() {
        LOGGER.info("Results screen complete, triggering level transition");

        // Mark this object as done
        setDestroyed(true);

        // Use existing LevelManager helper to advance to next act
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager != null) {
            try {
                levelManager.advanceToNextLevel();
            } catch (java.io.IOException e) {
                LOGGER.severe("Failed to load next level: " + e.getMessage());
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return;
        }

        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null) {
            return;
        }

        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getResultsRenderer();
        if (renderer == null) {
            // Fallback to placeholder boxes if renderer not available
            appendPlaceholderRenderCommands(commands);
            return;
        }

        // Screen-space rendering - convert screen coords to world coords
        int worldBaseX = camera.getX();
        int worldBaseY = camera.getY();

        // Calculate slide-in progress (0.0 to 1.0)
        float slideAlpha = getSlideAlpha();

        // Render text elements using ROM art
        // Frame indices from MapUnc_EOLTitleCards:
        // 0 = "SONIC GOT", 3 = "THROUGH", 4 = "ACT", 6-8 = act numbers
        // 10 = "TIME BONUS", 11 = "RING BONUS", 9 = "TOTAL", 14 = "PERFECT"

        // "SONIC GOT" - slides from left (frame 0)
        int gotX = (int) (slideAlpha * SCREEN_CENTER_X);
        renderer.drawFrameIndex(0, worldBaseX + gotX, worldBaseY + TEXT_Y_GOT_THROUGH, false, false);

        // "THROUGH" - slides from left (frame 3)
        int throughX = (int) (slideAlpha * (SCREEN_CENTER_X - 32));
        renderer.drawFrameIndex(3, worldBaseX + throughX, worldBaseY + TEXT_Y_ACT, false, false);

        // "ACT" - slides from right (frame 4)
        int actX = SCREEN_CENTER_X + 32 + (int) ((1 - slideAlpha) * 120);
        renderer.drawFrameIndex(4, worldBaseX + actX, worldBaseY + TEXT_Y_ACT, false, false);

        // Act number (frame 6 = "1", 7 = "2", 8 = "3")
        int actFrame = 5 + actNumber; // actNumber is 1-based, so act 1 = frame 6
        int actNumX = SCREEN_CENTER_X + 88 + (int) ((1 - slideAlpha) * 120);
        renderer.drawFrameIndex(actFrame, worldBaseX + actNumX, worldBaseY + 62, false, false);

        // Bonus display - only show after slide-in complete
        if (state >= STATE_TALLY) {
            updateBonusPatterns(renderManager);
            // "TIME BONUS" (frame 10)
            renderer.drawFrameIndex(10, worldBaseX + SCREEN_CENTER_X, worldBaseY + TEXT_Y_TIME_BONUS, false, false);

            // "RING BONUS" (frame 11)
            renderer.drawFrameIndex(11, worldBaseX + SCREEN_CENTER_X, worldBaseY + TEXT_Y_RING_BONUS, false, false);

            // "TOTAL" (frame 9)
            renderer.drawFrameIndex(9, worldBaseX + SCREEN_CENTER_X, worldBaseY + TEXT_Y_TOTAL, false, false);

            // "PERFECT" (frame 14) - only show if perfect bonus earned
            if (perfectBonus) {
                renderer.drawFrameIndex(14, worldBaseX + SCREEN_CENTER_X, worldBaseY + 144, false, false);
            }
        }
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

        GraphicsManager graphicsManager = GraphicsManager.getInstance();
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
        Camera camera = Camera.getInstance();
        if (camera == null) {
            return;
        }

        int worldBaseX = camera.getX();
        int worldBaseY = camera.getY();
        float slideAlpha = getSlideAlpha();

        // "SONIC GOT THROUGH" placeholder
        int gotThroughX = worldBaseX + (int) (slideAlpha * SCREEN_CENTER_X);
        renderPlaceholderBox(commands, gotThroughX, worldBaseY + TEXT_Y_GOT_THROUGH, 80, 16, 0.2f, 0.6f, 1.0f);

        // "ACT X" placeholder
        int actX = worldBaseX + SCREEN_CENTER_X + (int) ((1 - slideAlpha) * 120);
        renderPlaceholderBox(commands, actX, worldBaseY + TEXT_Y_ACT, 48, 16, 0.2f, 0.8f, 0.4f);

        if (state >= STATE_TALLY) {
            renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40, worldBaseY + TEXT_Y_TIME_BONUS, 80, 12,
                    1.0f, 1.0f, 0.4f);
            renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40, worldBaseY + TEXT_Y_RING_BONUS, 80, 12,
                    1.0f, 0.8f, 0.2f);
            renderPlaceholderBox(commands, worldBaseX + SCREEN_CENTER_X - 40, worldBaseY + TEXT_Y_TOTAL, 80, 12, 1.0f,
                    0.4f, 0.4f);
        }
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
}
