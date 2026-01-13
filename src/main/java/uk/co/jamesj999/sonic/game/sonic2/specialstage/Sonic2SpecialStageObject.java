package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Base class for Special Stage objects (rings, bombs, emeralds).
 *
 * Special stage objects use a depth-based perspective system where objects
 * start far away and approach the player. Their screen position is calculated
 * using perspective data that maps depth and angle to X/Y coordinates.
 *
 * Key fields (matching original disassembly):
 * - angle: Position around the track (0-255, where 0x40 is bottom center)
 * - objoff_30 (depth): Depth value that decreases as object approaches
 * - anim: Current animation index (0-9 for perspective sizes, 10 for effects)
 */
public abstract class Sonic2SpecialStageObject {

    /** Object states */
    public enum State {
        ACTIVE,
        COLLECTED,
        EXPLODING,
        REMOVED
    }

    /** Current object state */
    protected State state = State.ACTIVE;

    /** Position around the track (0-255) */
    protected int angle;

    /** Depth value as 16.16 fixed-point (high word = integer part) */
    protected long depthFixed;

    /** Base depth decrement per frame at speedFactor=12 (from disassembly: $CCCC in 16.16 format) */
    private static final long BASE_DEPTH_DECREMENT = 0xCCCC;
    private static final int BASE_DURATION = 5;  // Duration at speedFactor=12

    /** Duration table from SSAnim_Base_Duration (ROM offset 0x000B46) */
    private static final int[] ANIM_BASE_DURATIONS = { 60, 30, 15, 10, 8, 6, 5, 0 };

    /** Screen X position (calculated from perspective data) */
    protected int screenX;

    /** Screen Y position (calculated from perspective data) */
    protected int screenY;

    /** Current animation index (0-9 for sizes, 10 for effects) */
    protected int animIndex;

    /** Animation frame within current animation */
    protected int animFrame;

    /** Animation frame delay counter */
    protected int animTimer;

    /** Whether the object is visible on screen */
    protected boolean onScreen;

    /** Whether the object should be drawn with priority */
    protected boolean highPriority;

    /**
     * Initializes the object with starting depth and angle.
     * @param depth Integer depth value (will be converted to fixed-point)
     * @param angle Track angle (0-255)
     */
    public void initialize(int depth, int angle) {
        // Convert integer depth to 16.16 fixed-point
        this.depthFixed = (long) depth << 16;
        this.angle = angle;
        this.animIndex = 0;
        this.animFrame = 0;
        this.animTimer = 0;
        this.state = State.ACTIVE;
        this.onScreen = false;
        this.highPriority = false;
    }

    /**
     * Updates the object state for one frame.
     *
     * @param currentTrackFrame Current track mapping frame (0-55)
     * @param trackFlipped Whether the track is flipped (left turn)
     * @param speedFactor Current speed factor from track animator (affects depth decrement rate)
     */
    public abstract void update(int currentTrackFrame, boolean trackFlipped, int speedFactor);

    /**
     * Updates the screen position using perspective data.
     * Must be called each frame after depth is updated.
     */
    public void updateScreenPosition(Sonic2PerspectiveData perspectiveData, int currentTrackFrame, boolean trackFlipped) {
        int depth = getDepth();
        if (depth <= 0) {
            onScreen = false;
            return;
        }

        // Get perspective entry for current frame and depth
        Sonic2PerspectiveData.PerspectiveEntry entry =
            perspectiveData.getEntry(currentTrackFrame, depth);

        if (entry == null) {
            onScreen = false;
            return;
        }

        // Check visibility angle range
        if (!entry.isAngleVisible(angle, trackFlipped)) {
            onScreen = false;
            return;
        }

        // Calculate screen position
        int[] pos = entry.calculateScreenPosition(angle, trackFlipped);
        screenX = pos[0];
        screenY = pos[1];
        onScreen = true;

        // Determine animation index based on depth
        // Lower depth = closer = larger sprite (higher anim index)
        animIndex = calculateAnimIndex();
    }

    /**
     * Calculates the animation index based on current depth.
     * Returns 0-9 for perspective sizes.
     *
     * From disassembly byte_35180 lookup table - maps depth to anim index:
     * Depths 0-2: anim 9
     * Depths 3-4: anim 8
     * Depths 5-6: anim 7
     * Depths 7-8: anim 6
     * Depths 9-10: anim 5
     * ...etc
     */
    protected int calculateAnimIndex() {
        int depth = getDepth();
        // Use lookup table from disassembly (byte_35180)
        // This maps depth values (0-30) to anim indices (0-9)
        if (depth <= 0) return 9;
        if (depth >= 30) return 0;

        // Table from disassembly (depths 0-31 map to anim 9-0):
        // 9,9,9,8,8,7,7,6,6,5,5,4,4,3,3,3,2,2,2,1,1,1,1,1,1,1,1,1,1,1,0,0
        int[] animLookup = {
            9, 9, 9, 8, 8, 7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 3,
            2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0
        };

        if (depth < animLookup.length) {
            return animLookup[depth];
        }
        return 0; // Furthest for very large depths
    }

    /**
     * Calculates depth decrement based on current speed factor.
     * This ensures objects move at the correct rate relative to track animation.
     *
     * Original game at speedFactor=12, duration=5:
     * - Objects decrement $CCCC per frame
     * - Over 5 frames (one track step), depth decreases by ~4 units
     *
     * When speedFactor changes, we scale proportionally to maintain this ratio.
     *
     * @param speedFactor Current speed factor from track animator (0-14)
     * @return The depth decrement value in 16.16 fixed-point
     */
    public static long calculateDepthDecrement(int speedFactor) {
        int duration = getFrameDuration(speedFactor);
        // Scale inversely with duration: slower track (larger duration) = slower objects
        // At speedFactor=12, duration=5, decrement=$CCCC
        // At speedFactor=6, duration=10, decrement=$CCCC*5/10=$6666
        return (BASE_DEPTH_DECREMENT * BASE_DURATION) / duration;
    }

    /**
     * Gets the frame duration for a given speed factor.
     *
     * @param speedFactor The speed factor (0-14)
     * @return The number of frames per track animation step
     */
    private static int getFrameDuration(int speedFactor) {
        int index = (speedFactor >> 1) & 0x7;
        if (index < ANIM_BASE_DURATIONS.length && ANIM_BASE_DURATIONS[index] > 0) {
            return ANIM_BASE_DURATIONS[index];
        }
        return BASE_DURATION; // Default to 5 frames
    }

    /**
     * Decrements the depth value using fixed-point math.
     * The rate is derived from the current speed factor to stay synchronized
     * with track animation.
     *
     * @param drawingIndex4 True if SSTrack_drawing_index == 4
     * @param speedFactor Current speed factor from track animator
     */
    public void decrementDepth(boolean drawingIndex4, int speedFactor) {
        if (depthFixed > 0) {
            long decrement = calculateDepthDecrement(speedFactor);
            depthFixed -= decrement;
            if (depthFixed < 0) {
                depthFixed = 0;
            }
        }
    }

    /**
     * Gets the integer depth value (high word of fixed-point).
     */
    public int getDepth() {
        return (int) (depthFixed >> 16);
    }

    /**
     * Checks if this object should be tested for collision.
     * In the original game, collision only happens when anim == 8.
     */
    public boolean isCollidable() {
        return state == State.ACTIVE && animIndex == 8;
    }

    /**
     * Checks if this object should be removed from the object list.
     */
    public boolean shouldRemove() {
        return state == State.REMOVED;
    }

    /**
     * Marks the object for removal.
     */
    public void markForRemoval() {
        state = State.REMOVED;
    }

    // Getters

    public State getState() {
        return state;
    }

    public int getAngle() {
        return angle;
    }

    public int getScreenX() {
        return screenX;
    }

    public int getScreenY() {
        return screenY;
    }

    public int getAnimIndex() {
        return animIndex;
    }

    public int getAnimFrame() {
        return animFrame;
    }

    public boolean isOnScreen() {
        return onScreen;
    }

    public boolean isHighPriority() {
        return highPriority;
    }

    /**
     * Returns whether this object is a ring.
     */
    public abstract boolean isRing();

    /**
     * Returns whether this object is a bomb.
     */
    public abstract boolean isBomb();

    /**
     * Returns whether this object is an emerald.
     */
    public abstract boolean isEmerald();
}
