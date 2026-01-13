package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.game.GameStateManager;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Base class for results screens (end-of-act and special stage).
 * <p>
 * Provides shared functionality:
 * - State machine (SLIDE_IN → TALLY → WAIT → EXIT)
 * - Bonus tally mechanics with tick sounds
 * - Slide animation calculations
 * - Score updating
 */
public abstract class AbstractResultsScreen extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(AbstractResultsScreen.class.getName());

    // States
    protected static final int STATE_SLIDE_IN = 0;
    protected static final int STATE_TALLY = 1;
    protected static final int STATE_WAIT = 2;
    protected static final int STATE_EXIT = 3;

    // Default timing constants (can be overridden)
    protected static final int DEFAULT_SLIDE_DURATION = 60;
    protected static final int DEFAULT_WAIT_DURATION = 180;
    protected static final int DEFAULT_TALLY_DECREMENT = 10;
    protected static final int DEFAULT_TALLY_TICK_INTERVAL = 4;

    // Screen dimensions
    protected static final int SCREEN_WIDTH = 320;
    protected static final int SCREEN_HEIGHT = 224;
    protected static final int SCREEN_CENTER_X = SCREEN_WIDTH / 2;

    // State tracking
    protected int state = STATE_SLIDE_IN;
    protected int stateTimer = 0;
    protected int frameCounter = 0;

    // Slide animation
    protected int slideProgress = 0;

    // Completion flag
    protected boolean complete = false;

    protected AbstractResultsScreen(String code) {
        super(null, code);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        this.frameCounter = frameCounter;
        stateTimer++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_EXIT -> complete = true;
        }
    }

    protected void updateSlideIn() {
        slideProgress = Math.min(stateTimer, getSlideDuration());

        if (stateTimer >= getSlideDuration()) {
            state = STATE_TALLY;
            stateTimer = 0;
            onTallyStart();
        }
    }

    /**
     * Override to perform actions when tally begins.
     */
    protected void onTallyStart() {
        // Default: no action
    }

    protected void updateTally() {
        TallyResult result = performTallyStep();

        if (result.totalIncrement > 0) {
            GameStateManager.getInstance().addScore(result.totalIncrement);
        }

        // Play tick sound every N frames while tallying
        if (result.anyRemaining && (stateTimer % getTallyTickInterval()) == 0) {
            playTickSound();
        }

        // Check if tally complete
        if (!result.anyRemaining) {
            playTallyEndSound();
            state = STATE_WAIT;
            stateTimer = 0;
        }
    }

    /**
     * Perform one step of the tally countdown.
     * Subclasses implement this to decrement their specific bonus counters.
     *
     * @return TallyResult indicating if any bonuses remain and total points added
     */
    protected abstract TallyResult performTallyStep();

    protected void updateWait() {
        if (stateTimer >= getWaitDuration()) {
            state = STATE_EXIT;
            onExitReady();
        }
    }

    /**
     * Override to perform actions when exit is triggered.
     */
    protected void onExitReady() {
        // Default: no action
    }

    public boolean isComplete() {
        return complete;
    }

    // Timing getters - override to customize
    protected int getSlideDuration() {
        return DEFAULT_SLIDE_DURATION;
    }

    protected int getWaitDuration() {
        return DEFAULT_WAIT_DURATION;
    }

    protected int getTallyDecrement() {
        return DEFAULT_TALLY_DECREMENT;
    }

    protected int getTallyTickInterval() {
        return DEFAULT_TALLY_TICK_INTERVAL;
    }

    // Audio helpers
    protected void playTickSound() {
        try {
            AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Blip);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    protected void playTallyEndSound() {
        try {
            AudioManager.getInstance().playSfx(Sonic2Constants.SndID_TallyEnd);
        } catch (Exception e) {
            // Ignore audio errors
        }
    }

    // Slide animation helper
    protected float getSlideAlpha() {
        return (float) slideProgress / getSlideDuration();
    }

    // Rendering priority
    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0); // Highest priority - draw on top
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getX() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getX() + SCREEN_CENTER_X : SCREEN_CENTER_X;
    }

    @Override
    public int getY() {
        Camera camera = Camera.getInstance();
        return camera != null ? camera.getY() + SCREEN_HEIGHT / 2 : SCREEN_HEIGHT / 2;
    }

    // Placeholder rendering helpers for subclasses
    protected void renderPlaceholderBox(List<GLCommand> commands, int x, int y, int width, int height,
                                        float r, float g, float b) {
        // Draw a simple outline rectangle as placeholder
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

    protected void renderPlaceholderText(List<GLCommand> commands, int x, int y,
                                         String text, float r, float g, float b) {
        int width = text.length() * 6;
        int height = 12;
        renderPlaceholderBox(commands, x - width / 2, y, width, height, r, g, b);
    }

    /**
     * Decrements a bonus counter by the tally decrement amount.
     *
     * @param currentValue The current bonus value
     * @return Array of [newValue, decrementAmount]
     */
    protected int[] decrementBonus(int currentValue) {
        if (currentValue <= 0) {
            return new int[]{0, 0};
        }
        int decrement = Math.min(getTallyDecrement(), currentValue);
        return new int[]{currentValue - decrement, decrement};
    }

    /**
     * Result of a single tally step.
     */
    protected record TallyResult(boolean anyRemaining, int totalIncrement) {
    }

    // State getter for testing
    public int getState() {
        return state;
    }
}
