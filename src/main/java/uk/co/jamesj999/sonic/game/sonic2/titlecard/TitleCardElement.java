package uk.co.jamesj999.sonic.game.sonic2.titlecard;

/**
 * Represents an individual animated element of the title card.
 * Each element slides from a start position to a target position, then back out.
 *
 * <p>Based on Obj34_TitleCardData from s2.asm:
 * <ul>
 *   <li>Zone name: slides from right (320) to center (160)</li>
 *   <li>"ZONE": slides from left (-88) to right (200)</li>
 *   <li>Act number: slides from left (-24) to right (264)</li>
 *   <li>Bottom bar: slides from right (552) to position (232)</li>
 *   <li>Left swoosh: slides from left (0) to position (112)</li>
 * </ul>
 */
public class TitleCardElement {

    /** Slide speed during entry: 16 pixels per frame */
    public static final int SLIDE_SPEED_IN = 16;

    /** Slide speed during exit: 32 pixels per frame (default) */
    public static final int SLIDE_SPEED_OUT = 32;

    /**
     * Background exit speed: 17 pixels per frame.
     * Original game scrolls background VRAM by $20 (32) per frame over 9 frames,
     * covering $F0 to -$30 (240 to -48 = 288 units). Our background travels
     * 152 pixels, so 152/9 ≈ 17 pixels/frame to match the 9-frame timing.
     */
    public static final int BACKGROUND_EXIT_SPEED = 17;

    private final int frameIndex;     // Mapping frame to render
    private final int startX;         // X position to start from / exit to
    private final int targetX;        // X position to slide to
    private final int startY;         // Y position to start from / exit to (for vertical animation)
    private final int targetY;        // Y position to slide to (for vertical animation)
    private final int delayFrames;    // Frames to wait before appearing
    private final int widthPixels;    // Width for calculating exit position
    private final boolean animatesVertically; // True if element animates in Y direction
    private final int exitSpeed;      // Custom exit speed (pixels per frame)

    private int currentX;             // Current X position
    private int currentY;             // Current Y position
    private int delayCounter;         // Countdown for appearance delay
    private boolean active;           // Has started animating
    private boolean atTarget;         // Has reached target position
    private boolean exited;           // Has exited screen

    /**
     * Creates a new title card element with horizontal animation only.
     *
     * @param frameIndex   Mapping frame index from TitleCardMappings
     * @param startX       X position to start from (and exit to)
     * @param targetX      X position to slide to
     * @param y            Y position (constant throughout animation)
     * @param delayFrames  Frames to wait before starting animation
     * @param widthPixels  Width of element in pixels
     */
    public TitleCardElement(int frameIndex, int startX, int targetX, int y,
                            int delayFrames, int widthPixels) {
        this(frameIndex, startX, targetX, y, y, delayFrames, widthPixels, false, SLIDE_SPEED_OUT);
    }

    /**
     * Creates a new title card element with vertical animation.
     *
     * @param frameIndex   Mapping frame index from TitleCardMappings
     * @param startX       X position (constant for vertical animation)
     * @param targetX      X position (constant for vertical animation)
     * @param startY       Y position to start from (and exit to)
     * @param targetY      Y position to slide to
     * @param delayFrames  Frames to wait before starting animation
     * @param widthPixels  Width of element in pixels
     * @param animatesVertically True if element animates vertically
     */
    public TitleCardElement(int frameIndex, int startX, int targetX, int startY, int targetY,
                            int delayFrames, int widthPixels, boolean animatesVertically) {
        this(frameIndex, startX, targetX, startY, targetY, delayFrames, widthPixels,
             animatesVertically, SLIDE_SPEED_OUT);
    }

    /**
     * Creates a new title card element with full customization including exit speed.
     *
     * @param frameIndex   Mapping frame index from TitleCardMappings
     * @param startX       X position to start from (and exit to)
     * @param targetX      X position to slide to
     * @param startY       Y position to start from (and exit to)
     * @param targetY      Y position to slide to
     * @param delayFrames  Frames to wait before starting animation
     * @param widthPixels  Width of element in pixels
     * @param animatesVertically True if element animates vertically
     * @param exitSpeed    Custom exit speed in pixels per frame
     */
    public TitleCardElement(int frameIndex, int startX, int targetX, int startY, int targetY,
                            int delayFrames, int widthPixels, boolean animatesVertically,
                            int exitSpeed) {
        this.frameIndex = frameIndex;
        this.startX = startX;
        this.targetX = targetX;
        this.startY = startY;
        this.targetY = targetY;
        this.delayFrames = delayFrames;
        this.widthPixels = widthPixels;
        this.animatesVertically = animatesVertically;
        this.exitSpeed = exitSpeed;

        reset();
    }

    /**
     * Resets the element to its initial state.
     */
    public void reset() {
        this.currentX = startX;
        this.currentY = startY;
        this.delayCounter = delayFrames;
        this.active = false;
        this.atTarget = false;
        this.exited = false;
    }

    /**
     * Updates the element during the slide-in phase.
     * Elements wait for their delay, then slide toward target.
     */
    public void updateSlideIn() {
        if (exited) return;

        // Wait for delay
        if (delayCounter > 0) {
            delayCounter--;
            return;
        }

        active = true;

        // Move toward target
        if (!atTarget) {
            if (animatesVertically) {
                // Vertical animation
                int direction = Integer.compare(targetY, currentY);
                currentY += direction * SLIDE_SPEED_IN;
                if ((direction > 0 && currentY >= targetY) ||
                    (direction < 0 && currentY <= targetY)) {
                    currentY = targetY;
                    atTarget = true;
                }
            } else {
                // Horizontal animation
                int direction = Integer.compare(targetX, currentX);
                currentX += direction * SLIDE_SPEED_IN;
                if ((direction > 0 && currentX >= targetX) ||
                    (direction < 0 && currentX <= targetX)) {
                    currentX = targetX;
                    atTarget = true;
                }
            }
        }
    }

    /**
     * Updates the element during the slide-out phase.
     * Elements slide back to their start position using their configured exit speed.
     */
    public void updateSlideOut() {
        if (exited) return;

        if (animatesVertically) {
            // Vertical animation - use configured exit speed
            int direction = Integer.compare(startY, currentY);
            currentY += direction * exitSpeed;
            if ((direction > 0 && currentY >= startY) ||
                (direction < 0 && currentY <= startY)) {
                currentY = startY;
                exited = true;
            }
        } else {
            // Horizontal animation - use configured exit speed
            int direction = Integer.compare(startX, currentX);
            currentX += direction * exitSpeed;
            // Check if reached or passed exit position
            if ((direction > 0 && currentX >= startX) ||
                (direction < 0 && currentX <= startX)) {
                currentX = startX;
                exited = true;
            }
        }
    }

    /**
     * Marks this element as ready to begin its exit animation.
     * Called when the cascading exit sequence reaches this element.
     * The element won't actually move until updateSlideOut() is called.
     */
    public void startExit() {
        // Element is now in exit mode - ready for updateSlideOut() calls
        // This method exists to support the cascading exit where elements
        // start their exits at different times
    }

    /**
     * Returns true if this element has reached its target position.
     */
    public boolean isAtTarget() {
        return atTarget;
    }

    /**
     * Returns true if this element has exited the screen.
     */
    public boolean hasExited() {
        return exited;
    }

    /**
     * Returns true if this element is visible (active and not exited).
     */
    public boolean isVisible() {
        return active && !exited;
    }

    /**
     * Gets the mapping frame index for this element.
     */
    public int getFrameIndex() {
        return frameIndex;
    }

    /**
     * Gets the current X position.
     */
    public int getCurrentX() {
        return currentX;
    }

    /**
     * Gets the current Y position (for animated elements) or target Y (for static).
     */
    public int getCurrentY() {
        return currentY;
    }

    /**
     * Gets the target Y position.
     * For backward compatibility with elements that don't animate vertically.
     */
    public int getY() {
        return targetY;
    }

    /**
     * Gets the width in pixels.
     */
    public int getWidthPixels() {
        return widthPixels;
    }

    // ========== Factory methods for standard title card elements ==========

    /**
     * Creates the zone name element.
     * Slides from right (off-screen) to center.
     *
     * @param zoneIndex Zone index to determine which mapping frame to use
     * @return Zone name element
     */
    public static TitleCardElement createZoneName(int zoneIndex) {
        int frameIndex = TitleCardMappings.getZoneFrame(zoneIndex);
        // From disassembly: start=320+128=448 (VDP), target=160, y=56, delay=0x1B (27)
        return new TitleCardElement(frameIndex, 448, 160, 56, 0x1B, 0x80);
    }

    /**
     * Creates the "ZONE" text element.
     * Slides from left to right.
     *
     * @return ZONE text element
     */
    public static TitleCardElement createZoneText() {
        // From disassembly: start=-88, target=200, y=80, delay=0x1C (28)
        return new TitleCardElement(TitleCardMappings.FRAME_ZONE, -88, 200, 80, 0x1C, 0x40);
    }

    /**
     * Creates the act number element.
     * Slides from left to right.
     *
     * @param actNumber Act number (0, 1, or 2)
     * @return Act number element
     */
    public static TitleCardElement createActNumber(int actNumber) {
        int frameIndex = TitleCardMappings.getActFrame(actNumber);
        // From disassembly: start=-24, target=264, y=80, delay=0x1C (28)
        return new TitleCardElement(frameIndex, -24, 264, 80, 0x1C, 0x18);
    }

    /**
     * Creates the bottom yellow bar element ("SONIC THE HEDGEHOG").
     * Slides from right.
     *
     * @return Bottom bar element
     */
    public static TitleCardElement createBottomBar() {
        // From disassembly: start=320+232=552 (VDP), target=232, y=160, delay=8
        return new TitleCardElement(TitleCardMappings.FRAME_STH, 552, 232, 160, 8, 0x48);
    }

    /**
     * Creates the left red stripes element.
     * Slides from left.
     *
     * @return Left swoosh element
     */
    public static TitleCardElement createLeftSwoosh() {
        // From disassembly: start=0, target=112, y=112, delay=0x15 (21)
        return new TitleCardElement(TitleCardMappings.FRAME_RED_STRIPES, 0, 112, 112, 0x15, 8);
    }

    /**
     * Creates the blue background element.
     * Slides from top of screen, covers Y=0 to Y=152 (above yellow bar).
     * This is a placeholder element - it has no sprite, just controls the blue box position.
     *
     * <p>Exit timing: Original game's Obj34_BackgroundOut scrolls VRAM from $F0 to -$30
     * (subtracting $20 per frame), taking 9 frames. We use BACKGROUND_EXIT_SPEED to
     * match this 9-frame duration.
     *
     * @return Blue background element
     */
    public static TitleCardElement createBlueBackground() {
        // Blue box covers top portion (Y=0 to Y=152)
        // Animates vertically from above screen (startY=-152) to visible (targetY=0)
        // frameIndex -1 indicates this is a background-only element with no sprite
        // Uses BACKGROUND_EXIT_SPEED (17 px/frame) to match original 9-frame exit timing
        return new TitleCardElement(-1, 0, 0, -152, 0, 0, 0, true, BACKGROUND_EXIT_SPEED);
    }
}
