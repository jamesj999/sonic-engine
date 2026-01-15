package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Special Stage ring object (Obj5B/Obj60 in disassembly).
 *
 * Ring animation (from Ani_obj5B_obj60):
 * - 10 perspective sizes (animation indices 0-9)
 * - Each size has a 5-frame spin animation:
 *   size -> size+$0A -> size+$14 -> size+$0A -> size
 * - Frame delay: 5 frames
 * - Animation $A: Sparkle sequence for collection
 *
 * Rings are collidable only when animIndex == 8 (closest perspective size
 * that triggers collision test in original game).
 */
public class Sonic2SpecialStageRing extends Sonic2SpecialStageObject {

    /** Animation frame delay for spin animation */
    private static final int ANIM_DELAY = 5;

    /** Animation frame delay for sparkle (original game uses 1) */
    private static final int SPARKLE_ANIM_DELAY = 1;

    /** Number of spin frames per size */
    private static final int SPIN_FRAMES = 5;

    /** Sparkle animation index */
    private static final int SPARKLE_ANIM = 10;

    /** Sparkle animation frames (original has 3: $1E, $1F, $20) */
    private static final int SPARKLE_FRAME_COUNT = 3;

    /** Current spin frame within the size animation (0-4) */
    private int spinFrame;

    /** Spin frame offsets for the animation cycle */
    private static final int[] SPIN_OFFSETS = { 0, 0x0A, 0x14, 0x0A, 0 };

    @Override
    public void initialize(int depth, int angle) {
        super.initialize(depth, angle);
        this.spinFrame = 0;
    }

    @Override
    public void update(int currentTrackFrame, boolean trackFlipped, int speedFactor, boolean drawingIndex4) {
        if (state == State.REMOVED) {
            return;
        }

        // Handle sparkle animation for collected rings
        if (state == State.COLLECTED) {
            updateSparkleAnimation();
            return;
        }

        // Decrement depth (object approaches) - ROM uses fixed rate based on drawing index
        decrementDepth(drawingIndex4, speedFactor);

        // Check if object has passed the player
        if (getDepth() <= 0) {
            markForRemoval();
            return;
        }

        // Update spin animation
        animTimer++;
        if (animTimer >= ANIM_DELAY) {
            animTimer = 0;
            spinFrame++;
            if (spinFrame >= SPIN_FRAMES) {
                spinFrame = 0;
            }
        }

        // The actual animation frame is size + spin offset
        animFrame = SPIN_OFFSETS[spinFrame];
    }

    /**
     * Updates the sparkle animation when the ring is collected.
     * Uses faster frame delay (1) compared to spin animation (5).
     */
    private void updateSparkleAnimation() {
        animTimer++;
        if (animTimer >= SPARKLE_ANIM_DELAY) {
            animTimer = 0;
            animFrame++;
            if (animFrame >= SPARKLE_FRAME_COUNT) {
                markForRemoval();
            }
        }
    }

    /**
     * Called when the ring is collected by a player.
     */
    public void collect() {
        if (state != State.ACTIVE) {
            return;
        }

        state = State.COLLECTED;
        animIndex = SPARKLE_ANIM;
        animFrame = 0;
        animTimer = 0;
    }

    @Override
    public boolean isRing() {
        return true;
    }

    @Override
    public boolean isBomb() {
        return false;
    }

    @Override
    public boolean isEmerald() {
        return false;
    }

    /**
     * Gets the mapping frame index for the current animation state.
     * Used for rendering the correct sprite.
     *
     * @return The mapping frame index (0-32)
     */
    public int getMappingFrame() {
        if (state == State.COLLECTED) {
            // Sparkle animation - mapping frames 30-32
            return 30 + Math.min(animFrame, 2);
        }

        // Normal perspective animation - uses spin frame to determine mapping
        return Sonic2SpecialStageSpriteData.getRingMappingFrame(animIndex, spinFrame);
    }

    /**
     * Gets the current spin frame (0-4).
     */
    public int getSpinFrame() {
        return spinFrame;
    }

    /**
     * Returns true if this ring is showing the sparkle animation.
     */
    public boolean isSparkle() {
        return state == State.COLLECTED;
    }
}
