package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * HCZ1-to-HCZ2 retained-controller bubble ({@code loc_6A710}).
 *
 * <p>The miniboss end-sign controller creates one of these every frame while
 * its retained player carriers descend into Act 2. Each bubble falls at two
 * pixels per frame and oscillates around {@code Camera_X_pos+$A0} using the
 * same acceleration routine as the player carriers.
 *
 * <p>ROM: {@code loc_6A710}, {@code loc_6A79C}, and {@code sub_6A916}
 * (sonic3k.asm:139950-140002, 140185-140220).
 */
public final class HczTransitionBubbleInstance extends AbstractObjectInstance
        implements SpawnCoordinateZeroScalarArgsRewindRecreatable {
    private static final int OBJECT_ID = 0;
    private static final int X_ACCELERATION = 0x100;
    private static final int Y_VELOCITY = 0x200;
    private static final int LIFETIME_FRAMES = 0xBF;
    private static final int RENDER_HALF_SIZE = 0x10;
    private static final int PRIORITY_BUCKET = 5; // ObjDat3_6AD24 priority $280.

    private int x;
    private int y;
    private int xSub;
    private int ySub;
    private int xVelocity;
    private int targetX;
    private int mappingFrame;
    private int animationStep;
    private int lifetime;
    private boolean targetSide;
    private boolean hidden;
    private boolean initialized;

    /**
     * @param x initial ROM {@code x_pos}
     * @param y initial ROM {@code y_pos}
     * @param targetX oscillation axis ({@code Camera_X_pos+$A0})
     * @param mappingFrame one of the four tiny Map_Bubbler frames
     * @param secondAnimationStep initial {@code anim_frame} bit from Random_Number
     */
    public HczTransitionBubbleInstance(
            int x, int y, int targetX, int mappingFrame, boolean secondAnimationStep) {
        super(new ObjectSpawn(x, y, OBJECT_ID, 0, 0, false, 0), "HCZTransitionBubble");
        this.x = x;
        this.y = y;
        this.targetX = targetX;
        this.mappingFrame = mappingFrame & 0x03;
        this.animationStep = secondAnimationStep ? 1 : 0;
        this.targetSide = x < targetX;
        this.xVelocity = initialXVelocity(x - targetX);
        this.lifetime = LIFETIME_FRAMES;
    }

    /** Probe-compatible construction path for the generic dynamic rewind recreator. */
    private HczTransitionBubbleInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y(), 0, 0, false);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // CreateChild6_Simple can place this child after its controller in the
        // same RunObjects pass. loc_6A710 initializes and draws, but does not
        // enter loc_6A79C movement until the child's following dispatch.
        if (!initialized) {
            initialized = true;
            return;
        }

        // loc_6A79C consumes the render_flags bit produced by the preceding
        // Render_Sprites pass before it performs movement.
        if (!isWithinRenderSpriteBounds(RENDER_HALF_SIZE, RENDER_HALF_SIZE)) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }

        updateHorizontalVelocity();
        SubpixelMotion.State motion = new SubpixelMotion.State(
                x, y, xSub, ySub, xVelocity, Y_VELOCITY);
        SubpixelMotion.moveSprite2(motion);
        x = motion.x;
        y = motion.y;
        xSub = motion.xSub;
        ySub = motion.ySub;

        // byte_6AE46..byte_6AE52: {delay 0, selected frame, empty frame $16,
        // restart}. Random_Number selects whether the first active dispatch
        // advances to the empty frame or restarts on the visible frame.
        if (animationStep == 0) {
            animationStep = 1;
            hidden = true;
        } else {
            animationStep = 0;
            hidden = false;
        }

        lifetime--;
        if (lifetime < 0) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    private void updateHorizontalVelocity() {
        boolean currentSide = x < targetX;
        int acceleration = currentSide ? X_ACCELERATION : -X_ACCELERATION;
        xVelocity = (short) (xVelocity + acceleration);
        if (currentSide != targetSide) {
            xVelocity = (short) (xVelocity + acceleration);
        }
        targetSide = currentSide;
    }

    /**
     * Ports the instructions around ROM {@code sub_6A940}. The helper first
     * writes its carrier-style velocity, but {@code loc_6A710} then shifts the
     * helper's absolute doubled-distance result in {@code d0} and overwrites
     * {@code x_vel}. That leaves every bubble with a positive initial velocity.
     */
    private static int initialXVelocity(int targetDelta) {
        return (short) (Math.abs(targetDelta * 2) << 4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || hidden) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.BUBBLER);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, x, y, false, false);
        }
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_SIZE;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    int getXVelocityForTest() {
        return xVelocity;
    }

    boolean isHiddenForTest() {
        return hidden;
    }
}
