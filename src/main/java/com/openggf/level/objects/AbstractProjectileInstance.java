package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Base class for simple projectile objects (missiles, energy balls, shrapnel).
 * <p>
 * Encapsulates the common projectile pattern found across all three games:
 * <ul>
 *   <li>Subpixel motion tracking via {@link SubpixelMotion.State}</li>
 *   <li>Optional gravity (per-frame Y velocity accumulation)</li>
 *   <li>Off-screen destruction with configurable margin</li>
 *   <li>Touch response collision (HURT category by default)</li>
 * </ul>
 * <p>
 * Subclasses must implement {@link #appendRenderCommands} and may override
 * {@link #updateMotion()} for custom motion patterns (e.g., X-only movement,
 * gravity-before-move ordering) or {@link #updateExtra(int, PlayableEntity)}
 * for additional per-frame logic (animation, parent tracking).
 */
public abstract class AbstractProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider {

    protected int currentX;
    protected int currentY;
    protected final SubpixelMotion.State motionState;
    protected int gravity;
    protected int collisionSizeIndex;
    protected int offScreenMargin;
    protected boolean touchCollisionActive = true;
    protected boolean deferSameFrameUpdateAfterSpawn = false;

    /**
     * Creates a projectile with the given motion parameters.
     *
     * @param spawn             spawn record for this object
     * @param name              display name for debugging
     * @param xVel              initial X velocity in subpixels (0x100 = 1 px/frame)
     * @param yVel              initial Y velocity in subpixels
     * @param gravity           gravity in subpixels/frame² (0 = no gravity)
     * @param collisionSizeIndex collision size index (lower 6 bits of obColType)
     * @param offScreenMargin   pixel margin beyond camera bounds before destruction
     */
    protected AbstractProjectileInstance(ObjectSpawn spawn, String name,
            int xVel, int yVel, int gravity, int collisionSizeIndex,
            int offScreenMargin) {
        super(spawn, name);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.gravity = gravity;
        this.collisionSizeIndex = collisionSizeIndex;
        this.offScreenMargin = offScreenMargin;
        this.motionState = new SubpixelMotion.State(currentX, currentY, 0, 0, xVel, yVel);
    }

    /**
     * Convenience constructor using the default off-screen margin of 48 pixels.
     */
    protected AbstractProjectileInstance(ObjectSpawn spawn, String name,
            int xVel, int yVel, int gravity, int collisionSizeIndex) {
        this(spawn, name, xVel, yVel, gravity, collisionSizeIndex, 48);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        updateMotion();
        currentX = motionState.x;
        currentY = motionState.y;

        if (!isOnScreen(offScreenMargin)) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }

        updateExtra(vIntRunCount, player);
    }

    /**
     * Applies velocity (and optionally gravity) to the motion state.
     * <p>
     * Default implementation uses {@link SubpixelMotion#moveSprite} when gravity
     * is non-zero (move-then-gravity, matching S3K/SpeedToPos+gravity order) and
     * {@link SubpixelMotion#moveSprite2} otherwise.
     * <p>
     * Override for custom motion patterns such as:
     * <ul>
     *   <li>Gravity-before-move (S1 ObjectFall order)</li>
     *   <li>X-only movement ({@link SubpixelMotion#moveX})</li>
     * </ul>
     */
    protected void updateMotion() {
        if (gravity != 0) {
            SubpixelMotion.moveSprite(motionState, gravity);
        } else {
            SubpixelMotion.moveSprite2(motionState);
        }
    }

    /**
     * Hook for subclass-specific per-frame logic (animation, parent tracking, etc.).
     * Called after motion update and off-screen check. Default is no-op.
     */
    protected void updateExtra(int vIntRunCount, PlayableEntity player) {
        // Default no-op
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getCollisionFlags() {
        // HURT category ($80) + size index
        return touchCollisionActive ? 0x80 | (collisionSizeIndex & 0x3F) : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        return deferSameFrameUpdateAfterSpawn;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }
}
