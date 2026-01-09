package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
public class ResultsScreenObjectInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(ResultsScreenObjectInstance.class.getName());

    // States
    private static final int STATE_SLIDE_IN = 0;
    private static final int STATE_TALLY = 1;
    private static final int STATE_WAIT = 2;
    private static final int STATE_TRANSITION = 3;

    // Timing constants (in frames)
    private static final int SLIDE_DURATION = 60; // Frames for slide-in animation
    private static final int WAIT_DURATION = 180; // 3 seconds after tally (B4 hex)
    private static final int TALLY_DECREMENT = 10; // Amount to subtract per tick

    // Bonus calculation thresholds (seconds)
    private static final int[][] TIME_BONUS_TABLE = {
            { 30, 50000 }, { 45, 10000 }, { 60, 5000 }, { 90, 4000 },
            { 120, 3000 }, { 180, 2000 }, { 240, 1000 }, { 300, 500 }
    };

    private int state = STATE_SLIDE_IN;
    private int stateTimer = 0;

    // Bonus values
    private int timeBonus;
    private int ringBonus;
    private int totalBonus;
    private boolean perfectBonus;

    // Input data
    private final int elapsedTimeSeconds;
    private final int ringCount;
    private final int actNumber;
    private final boolean allRingsCollected;

    // Screen-space positions for text elements (center of 320x224 screen)
    private static final int SCREEN_CENTER_X = 160;
    private static final int TEXT_Y_GOT_THROUGH = 56;
    private static final int TEXT_Y_ACT = 74;
    private static final int TEXT_Y_TIME_BONUS = 112;
    private static final int TEXT_Y_RING_BONUS = 128;
    private static final int TEXT_Y_TOTAL = 160;

    // Current slide positions
    private int slideProgress = 0;

    public ResultsScreenObjectInstance(int elapsedTimeSeconds, int ringCount, int actNumber, boolean allRingsCollected) {
        super(null, "results_screen");
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
        // Time bonus - ROM's Load_EndOfAct logic
        timeBonus = 0;
        for (int[] threshold : TIME_BONUS_TABLE) {
            if (elapsedTimeSeconds < threshold[0]) {
                timeBonus = threshold[1];
                break;
            }
        }

        // Ring bonus: rings * 100
        ringBonus = ringCount * 100;

        // Perfect bonus: 50000 if all ring objects were collected in the act
        perfectBonus = allRingsCollected;

        // Total starts as sum of all bonuses
        totalBonus = timeBonus + ringBonus + (perfectBonus ? 50000 : 0);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        stateTimer++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_TRANSITION -> triggerLevelTransition();
        }
    }

    private void updateSlideIn() {
        slideProgress = Math.min(stateTimer, SLIDE_DURATION);

        if (stateTimer >= SLIDE_DURATION) {
            state = STATE_TALLY;
            stateTimer = 0;
        }
    }

    private void updateTally() {
        boolean anyRemaining = false;

        // Decrement time bonus
        if (timeBonus > 0) {
            int decrement = Math.min(TALLY_DECREMENT, timeBonus);
            timeBonus -= decrement;
            anyRemaining = true;
        }

        // Decrement ring bonus
        if (ringBonus > 0) {
            int decrement = Math.min(TALLY_DECREMENT, ringBonus);
            ringBonus -= decrement;
            anyRemaining = true;
        }

        // Play tick sound every 4 frames
        if (anyRemaining && (stateTimer & 3) == 0) {
            try {
                AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Blip);
            } catch (Exception e) {
                // Ignore audio errors
            }
        }

        // Check if tally complete
        if (!anyRemaining) {
            // Play tally end sound
            try {
                AudioManager.getInstance().playSfx(Sonic2Constants.SndID_TallyEnd);
            } catch (Exception e) {
                // Ignore audio errors
            }
            state = STATE_WAIT;
            stateTimer = 0;
        }
    }

    private void updateWait() {
        if (stateTimer >= WAIT_DURATION) {
            state = STATE_TRANSITION;
        }
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
        float slideAlpha = (float) slideProgress / SLIDE_DURATION;

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
        float slideAlpha = (float) slideProgress / SLIDE_DURATION;

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

    private void renderPlaceholderBox(List<GLCommand> commands, int x, int y, int width, int height,
            float r, float g, float b) {
        // Draw a simple colored rectangle as placeholder
        // Top edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        // Bottom edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
        // Left edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x, y + height, 0, 0));
        // Right edge
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x + width, y + height, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0); // Highest priority - draw on top
    }

    @Override
    public boolean isHighPriority() {
        return true; // Always render on top of other objects
    }

    @Override
    public int getX() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getX() + SCREEN_CENTER_X : SCREEN_CENTER_X;
    }

    @Override
    public int getY() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getY() + 112 : 112;
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
