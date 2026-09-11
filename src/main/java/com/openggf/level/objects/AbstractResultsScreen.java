package com.openggf.level.objects;

import com.openggf.camera.Camera;

import com.openggf.game.ResultsScreen;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import java.util.List;
import java.util.logging.Logger;

/**
 * Base class for results screens (end-of-act and special stage).
 * <p>
 * Provides shared functionality:
 * - State machine (SLIDE_IN -> TALLY -> WAIT -> EXIT)
 * - Bonus tally mechanics with tick sounds
 * - Slide animation calculations
 * - Score updating
 */
public abstract class AbstractResultsScreen extends AbstractObjectInstance implements ResultsScreen {
    private static final Logger LOGGER = Logger.getLogger(AbstractResultsScreen.class.getName());

    // States
    protected static final int STATE_SLIDE_IN = 0;
    protected static final int STATE_PRE_TALLY_DELAY = 1; // ROM: $B4 (180) frame delay before tally
    protected static final int STATE_TALLY = 2;
    protected static final int STATE_WAIT = 3;
    protected static final int STATE_EXIT = 4;

    // Default timing constants (can be overridden)
    // From s2.asm: move.w #$B4,anim_frame_duration(a0) = 180 frames
    protected static final int DEFAULT_SLIDE_DURATION = 60;
    protected static final int DEFAULT_PRE_TALLY_DELAY = 180; // $B4 frames - ROM-accurate delay before tally starts
    protected static final int DEFAULT_WAIT_DURATION = 180;
    protected static final int DEFAULT_TALLY_DECREMENT = 10;
    protected static final int DEFAULT_TALLY_TICK_INTERVAL = 4;

    // Movement speed from s2.asm Obj34_MoveTowardsTargetPosition: moveq #$10,d0 =
    // 16 pixels/frame
    protected static final int SLIDE_SPEED_PIXELS_PER_FRAME = 16;

    // Screen dimensions
    protected static final int SCREEN_WIDTH = 320;
    protected static final int SCREEN_HEIGHT = 224;
    protected static final int SCREEN_CENTER_X = SCREEN_WIDTH / 2;

    // Widescreen support — set by setViewportWidth() (used when services() is unavailable);
    // level-object subclasses read the live projection width from GraphicsManager instead.
    private int viewportWidth = SCREEN_WIDTH;

    // State tracking
    protected int state = STATE_SLIDE_IN;
    protected int stateTimer = 0;
    protected int frameCounter = 0;

    // Total frames elapsed since results screen started (never resets)
    // Used for consistent slide-in animation across state changes
    protected int totalFrames = 0;

    // Slide animation
    protected int slideProgress = 0;

    // Completion flag
    protected boolean complete = false;

    protected AbstractResultsScreen(String code) {
        super(null, code);
    }

    /**
     * Sets the projection-space viewport width for widescreen centering.
     *
     * <p>At native width 320 the {@link #xOffset()} returns 0 — byte-identical output.
     */
    @Override
    public void setViewportWidth(int width) {
        this.viewportWidth = Math.max(SCREEN_WIDTH, width);
    }

    /**
     * Horizontal pixel offset to apply to every X position so that native-320 content
     * is centred within the current viewport. Returns 0 at native width 320.
     *
     * <p>When {@link #services()} provides a live {@code GraphicsManager}, uses its
     * projection width (updated by Engine each frame) so level-object results screens
     * react to resolution changes without a per-frame {@link #setViewportWidth} call.
     * Falls back to the explicitly-set {@link #viewportWidth} (used by the
     * SPECIAL_STAGE_RESULTS path which gets the width from Engine before each draw).
     */
    protected int xOffset() {
        try {
            com.openggf.graphics.GraphicsManager gm = services().graphicsManager();
            if (gm != null) {
                return (gm.getProjectionWidth() - SCREEN_WIDTH) / 2;
            }
        } catch (Exception ignored) {
            // services() may throw if called outside a valid context (e.g. tests)
        }
        return (viewportWidth - SCREEN_WIDTH) / 2;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        this.frameCounter = vIntRunCount;
        stateTimer++;
        totalFrames++;

        switch (state) {
            case STATE_SLIDE_IN -> updateSlideIn();
            case STATE_PRE_TALLY_DELAY -> updatePreTallyDelay();
            case STATE_TALLY -> updateTally();
            case STATE_WAIT -> updateWait();
            case STATE_EXIT -> complete = true;
        }
    }

    protected void updateSlideIn() {
        slideProgress = Math.min(stateTimer, getSlideDuration());

        if (stateTimer >= getSlideDuration()) {
            state = STATE_PRE_TALLY_DELAY;
            stateTimer = 0;
            onSlideInComplete();
        }
    }

    /**
     * Override to perform actions when slide-in completes (before pre-tally delay).
     */
    protected void onSlideInComplete() {
        // Default: no action
    }

    /**
     * ROM-accurate delay before tally begins.
     * From s2.asm loc_1419C / Obj6F_TimedDisplay:
     * subq.w #1,anim_frame_duration(a0)
     * bne.s BranchTo18_DisplaySprite
     * addq.b #2,routine(a0)
     */
    protected void updatePreTallyDelay() {
        if (stateTimer >= getPreTallyDelay()) {
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
            services().gameState().addScore(result.totalIncrement);
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

    /**
     * Bridge method to satisfy {@link ResultsScreen} interface.
     * Delegates to the concrete update method with player cast.
     */
    @Override
    public void update(int frameCounter, Object context) {
        PlayableEntity player = (context instanceof PlayableEntity) ? (PlayableEntity) context
                : null;
        update(frameCounter, player);
    }

    // Timing getters - override to customize
    protected int getSlideDuration() {
        return DEFAULT_SLIDE_DURATION;
    }

    protected int getPreTallyDelay() {
        return DEFAULT_PRE_TALLY_DELAY;
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

    // Audio helpers - each game provides its own SFX IDs
    protected abstract void playTickSound();

    protected abstract void playTallyEndSound();

    // Slide animation helper (legacy - uses duration-based alpha)
    protected float getSlideAlpha() {
        return (float) slideProgress / getSlideDuration();
    }

    /**
     * Calculate remaining offset for an element sliding in from off-screen.
     * Uses ROM-accurate 16 pixels/frame speed.
     *
     * @param startOffset The initial distance from target (e.g., 352 for element
     *                    starting at 320+352)
     * @return The current offset from target (0 when element has reached its
     *         position)
     */
    protected int getSlideOffset(int startOffset) {
        int pixelsMoved = totalFrames * SLIDE_SPEED_PIXELS_PER_FRAME;
        return Math.max(0, startOffset - pixelsMoved);
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
        Camera camera = services().camera();
        return camera != null ? camera.getX() + SCREEN_CENTER_X : SCREEN_CENTER_X;
    }

    @Override
    public int getY() {
        Camera camera = services().camera();
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
            return new int[] { 0, 0 };
        }
        int decrement = Math.min(getTallyDecrement(), currentValue);
        return new int[] { currentValue - decrement, decrement };
    }

    /**
     * Result of a single tally step.
     */
    protected record TallyResult(boolean anyRemaining, int totalIncrement) {
    }

    /**
     * Factory method for creating TallyResult from subclasses in other packages.
     * (Java restricts direct protected constructor access across packages.)
     */
    protected TallyResult tallyResult(boolean anyRemaining, int totalIncrement) {
        return new TallyResult(anyRemaining, totalIncrement);
    }

    // State getter for testing
    public int getState() {
        return state;
    }
}
