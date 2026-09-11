package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Base class for floating points popup objects (Obj29) shared across S1 and S2.
 * <p>
 * Physics matches ROM Obj29_Init/Obj29_Main:
 * <ul>
 *   <li>Initial y_vel = -$300 (-768 subpixels = -3 pixels/frame upward)</li>
 *   <li>Each frame: position += velocity, then velocity += $18 (gravity)</li>
 *   <li>Deleted when y_vel >= 0 (about to fall back down)</li>
 * </ul>
 * Subclasses provide game-specific score-to-frame mapping via {@link #getFrameForScore(int)}.
 */
public abstract class AbstractPointsObjectInstance extends AbstractObjectInstance
        implements SpawnServicesDefaultArgsRewindRecreatable {
    /** ROM: move.w #-$300,y_vel(a0) */
    protected static final int INITIAL_Y_VEL = -0x300;
    /** ROM: addi.w #$18,y_vel(a0) */
    protected static final int GRAVITY = 0x18;

    protected final PatternSpriteRenderer renderer;
    protected int currentX;
    protected int ySubpixel;   // 8.8 fixed-point Y position (high byte = pixel)
    protected int yVel;        // Y velocity in subpixels
    protected int scoreFrame;

    protected AbstractPointsObjectInstance(ObjectSpawn spawn, String name,
                                           ObjectServices services, int points) {
        super(spawn, name);
        this.renderer = services.renderManager().getPointsRenderer();
        this.currentX = spawn.x();
        this.ySubpixel = spawn.y() << 8;
        this.yVel = INITIAL_Y_VEL;
        this.scoreFrame = getFrameForScore(points);
    }

    /**
     * Returns the frame index corresponding to the given score value.
     * Each game has its own mapping of point values to art frames.
     */
    protected abstract int getFrameForScore(int score);

    /**
     * Sets the score display by looking up the frame for the given point value.
     */
    public void setScore(int points) {
        this.scoreFrame = getFrameForScore(points);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // ROM: tst.w y_vel(a0) / bpl.w DeleteObject
        if (yVel >= 0) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        // ROM: bsr.w ObjectMove - apply velocity to position
        ySubpixel += yVel;
        // ROM: addi.w #$18,y_vel(a0) - apply gravity
        yVel += GRAVITY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || renderer == null) {
            return;
        }
        renderer.drawFrameIndex(scoreFrame, currentX, ySubpixel >> 8, false, false);
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        return ySubpixel >> 8;
    }
}
