package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.game.rewind.schema.RewindCaptureContext;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Masher (0x5C) - Leaping piranha fish Badnik from EHZ.
 * Jumps up and down from a waterfall spawn point.
 */
public class MasherBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x09; // collision_flags(a0)
    private static final int INITIAL_Y_VEL = -0x400; // move.w #-$400,y_vel(a0)
    private static final int JUMP_Y_VEL = -0x500; // move.w #-$500,y_vel(a0)
    private static final int GRAVITY = 0x18; // addi.w #$18,y_vel(a0)
    private static final int JUMP_HEIGHT = 0x00C0; // subi.w #$C0,d0

    private final SubpixelMotion.State motionState;
    private int initialYPos; // Obj5C_initial_y_pos / objoff_30

    public MasherBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Masher", Sonic2BadnikConfig.DESTRUCTION);
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, INITIAL_Y_VEL);
        this.initialYPos = spawn.y();
        this.xVelocity = 0;
        this.yVelocity = INITIAL_Y_VEL;
    }

    @Override
    public MasherBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MasherBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        // Obj5C_Main: ObjectMove first, then gravity, then clamp against the saved
        // bottom y position. The move.w to y_pos keeps y_sub intact for the next jump.
        SubpixelMotion.speedToPosY(motionState);
        motionState.yVel += GRAVITY;
        if (motionState.y > initialYPos) {
            motionState.y = initialYPos;
            motionState.yVel = JUMP_Y_VEL;
        }

        currentX = motionState.x;
        currentY = motionState.y;
        xVelocity = motionState.xVel;
        yVelocity = motionState.yVel;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        int threshold = initialYPos - JUMP_HEIGHT;

        if (currentY <= threshold) {
            // Ani_obj5C anim 1: fast 0,1 toggle while near the top of the leap.
            animFrame = (vIntRunCount >> 2) & 1;
        } else if (yVelocity >= 0) {
            // Ani_obj5C anim 2: static closed mouth while falling.
            animFrame = 0;
        } else {
            // Ani_obj5C anim 0: slower 0,1 toggle while rising from the water.
            animFrame = (vIntRunCount >> 3) & 1;
        }
    }

    @Override
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        super.hydrateFromRomSnapshot(snapshot);
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xSub = snapshot.xSub();
        motionState.ySub = snapshot.ySub();
        motionState.xVel = snapshot.xVel();
        motionState.yVel = snapshot.yVel();
        xVelocity = motionState.xVel;
        yVelocity = motionState.yVel;
        if (hasWord(snapshot, 0x30)) {
            initialYPos = snapshot.wordAt(0x30);
        }
    }

    private static boolean hasWord(RomObjectSnapshot snapshot, int offset) {
        return snapshot.wordFields().containsKey(offset)
                || snapshot.byteFields().containsKey(offset)
                || snapshot.byteFields().containsKey(offset + 1);
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return captureCompleteRewindState(RewindCaptureContext.none());
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState(RewindCaptureContext context) {
        return captureCompleteRewindState(context);
    }

    private PerObjectRewindSnapshot captureCompleteRewindState(RewindCaptureContext context) {
        PerObjectRewindSnapshot base = super.captureRewindState(context);
        return base.withBadnikSubclassExtra(new PerObjectRewindSnapshot.MasherRewindExtra(
                motionState.x,
                motionState.y,
                motionState.xSub,
                motionState.ySub,
                motionState.xVel,
                motionState.yVel,
                initialYPos));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        restoreCompleteRewindState(snapshot, RewindCaptureContext.none());
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot, RewindCaptureContext context) {
        restoreCompleteRewindState(snapshot, context);
    }

    private void restoreCompleteRewindState(PerObjectRewindSnapshot snapshot,
            RewindCaptureContext context) {
        super.restoreRewindState(snapshot, context);
        if (snapshot.badnikSubclassExtra() instanceof PerObjectRewindSnapshot.MasherRewindExtra extra) {
            motionState.x = extra.motionX();
            motionState.y = extra.motionY();
            motionState.xSub = extra.motionXSub();
            motionState.ySub = extra.motionYSub();
            motionState.xVel = extra.motionXVel();
            motionState.yVel = extra.motionYVel();
            initialYPos = extra.initialYPos();
            currentX = motionState.x;
            currentY = motionState.y;
            xVelocity = motionState.xVel;
            yVelocity = motionState.yVel;
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.standardEnemy();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MASHER);
        if (renderer == null) return;

        // Render current animation frame
        boolean hFlip = false;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
