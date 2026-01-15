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

    /**
     * Depth decrement values from disassembly (loc_3512A in s2.asm).
     * ROM uses FIXED values that do NOT vary with speed factor:
     * - $CCCC (52428) when SSTrack_drawing_index == 4
     * - $CCCD (52429) when SSTrack_drawing_index != 4
     *
     * The drawing index cycles at different rates based on speed factor, which naturally
     * creates different total decrements per animation frame. But since there are also
     * different numbers of animation frames per second, objects approach at the same
     * real-time rate regardless of speed factor (~48 depth units per second).
     */

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
     * @param speedFactor Current speed factor from track animator
     * @param drawingIndex4 True if SSTrack_drawing_index == 4 (affects depth decrement)
     */
    public abstract void update(int currentTrackFrame, boolean trackFlipped, int speedFactor, boolean drawingIndex4);

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
     * Decrements the depth value using fixed-point math.
     *
     * From disassembly (loc_3512A in s2.asm):
     * - When SSTrack_drawing_index == 4: subtract $CCCC
     * - Otherwise: subtract $CCCD
     *
     * Note: The depth decrement is FIXED and does NOT vary with speed factor.
     * Track animation speed affects how fast the track moves visually, but
     * objects always approach at the same rate for consistent collision timing.
     *
     * @param drawingIndex4 True if SSTrack_drawing_index == 4
     * @param speedFactor Unused - kept for API compatibility but ROM doesn't scale by speed
     */
    public void decrementDepth(boolean drawingIndex4, int speedFactor) {
        if (depthFixed > 0) {
            // ROM uses FIXED depth decrement values (from loc_3512A in s2.asm):
            // - $CCCC when SSTrack_drawing_index == 4
            // - $CCCD otherwise
            //
            // The total decrement per animation frame varies with speed factor:
            // - speedFactor=12 (duration=5): 4 × $CCCD + 1 × $CCCC = 262144 (~4.0)
            // - speedFactor=6 (duration=10): 9 × $CCCD + 1 × $CCCC = 524289 (~8.0)
            //
            // But since there are fewer animation frames per second at lower speed factors,
            // the depth decrement per second is the same:
            // - speedFactor=12: 12 frames/sec × 4.0 = 48.0 depth/sec
            // - speedFactor=6: 6 frames/sec × 8.0 = 48.0 depth/sec
            //
            // This means objects approach at the same real-time rate regardless of speed factor.
            long decrement = drawingIndex4 ? 0xCCCC : 0xCCCD;
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
