package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;

/**
 * Base class for lightweight gravity-affected debris/fragment children.
 * Spawned by break-apart effects (BreakObjectToPieces, etc.) when objects shatter.
 *
 * <p>Each fragment receives initial velocity and falls with gravity until offscreen,
 * then deletes itself. Subclasses provide only rendering via {@code appendRenderCommands()}.
 *
 * <p>ROM pattern: MoveSprite (velocity + gravity) then MarkObjGone (delete when offscreen).
 * Used by rock fragments, cork floor pieces, breakable wall debris, etc.
 */
public abstract class GravityDebrisChild extends AbstractObjectInstance {

    protected final SubpixelMotion.State motionState;
    protected int gravity;

    /**
     * @param spawn   spawn point (initial position)
     * @param name    debug name for this fragment type
     * @param xVel    initial X velocity in subpixels (0x100 = 1 px/frame)
     * @param yVel    initial Y velocity in subpixels (negative = upward)
     * @param gravity gravity in subpixels per frame (typically 0x18 or 0x38)
     */
    protected GravityDebrisChild(ObjectSpawn spawn, String name,
                                 int xVel, int yVel, int gravity) {
        super(spawn, name);
        this.gravity = gravity;
        this.motionState = new SubpixelMotion.State(
                spawn.x(), spawn.y(), 0, 0, xVel, yVel);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        SubpixelMotion.moveSprite(motionState, gravity);
        if (!isOnScreen()) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    @Override
    public int getX() {
        return motionState.x;
    }

    @Override
    public int getY() {
        return motionState.y;
    }

    /**
     * Most debris fragments render in front of high-priority FG tiles.
     * ROM: {@code ori.w #high_priority,art_tile(a1)} in BreakObjectToPieces.
     * Subclasses can override to return false if not high-priority.
     */
    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(motionState.x, motionState.y);
    }
}
