package uk.co.jamesj999.sonic.game.sonic2.specialstage;

/**
 * Special Stage bomb object (Obj61 in disassembly).
 *
 * Bomb animation (from Ani_obj61):
 * - 10 perspective sizes (animation indices 0-9)
 * - Each size is a single frame with delay $0B (11 frames)
 * - Animation $A: Explosion sequence when hit
 *
 * Bombs are collidable only when animIndex == 8 (closest perspective size
 * that triggers collision test in original game).
 */
public class Sonic2SpecialStageBomb extends Sonic2SpecialStageObject {

    /** Animation frame delay for idle bomb */
    private static final int ANIM_DELAY = 11;

    /** Explosion animation index */
    private static final int EXPLOSION_ANIM = 10;

    /** Explosion animation frames (3 frames: $A, $B, $C from Ani_obj61) */
    private static final int EXPLOSION_FRAME_COUNT = 3;

    /** Explosion animation frame delay (2 from byte_36502) */
    private static final int EXPLOSION_DELAY = 2;

    @Override
    public void initialize(int depth, int angle) {
        super.initialize(depth, angle);
    }

    @Override
    public void update(int currentTrackFrame, boolean trackFlipped, int speedFactor, boolean drawingIndex4) {
        if (state == State.REMOVED) {
            return;
        }

        // Handle explosion animation for hit bombs
        if (state == State.EXPLODING) {
            updateExplosionAnimation();
            return;
        }

        // Decrement depth (object approaches) - ROM uses fixed rate based on drawing index
        decrementDepth(drawingIndex4, speedFactor);

        // Check if object has passed the player
        if (getDepth() <= 0) {
            markForRemoval();
            return;
        }

        // Bomb has a simple idle animation (single frame per size)
        // The animTimer counts up but doesn't change the frame
        animTimer++;
        if (animTimer >= ANIM_DELAY) {
            animTimer = 0;
        }
    }

    /**
     * Updates the explosion animation when the bomb is hit.
     */
    private void updateExplosionAnimation() {
        animTimer++;
        if (animTimer >= EXPLOSION_DELAY) {
            animTimer = 0;
            animFrame++;
            if (animFrame >= EXPLOSION_FRAME_COUNT) {
                markForRemoval();
            }
        }
    }

    /**
     * Called when the bomb is hit by a player.
     * Triggers the explosion animation.
     */
    public void explode() {
        if (state != State.ACTIVE) {
            return;
        }

        state = State.EXPLODING;
        animIndex = EXPLOSION_ANIM;
        animFrame = 0;
        animTimer = 0;
    }

    @Override
    public boolean isRing() {
        return false;
    }

    @Override
    public boolean isBomb() {
        return true;
    }

    @Override
    public boolean isEmerald() {
        return false;
    }

    /**
     * Gets the mapping frame index for the current animation state.
     * Used for rendering the correct sprite.
     *
     * @return The mapping frame index (0-12)
     */
    public int getMappingFrame() {
        if (state == State.EXPLODING) {
            // Explosion animation - mapping frames 10-12
            return 10 + Math.min(animFrame, 2);
        }

        // Normal perspective - single frame per size
        return Math.min(animIndex, 9);
    }

    /**
     * Returns true if this bomb is showing the explosion animation.
     */
    public boolean isExploding() {
        return state == State.EXPLODING;
    }
}
