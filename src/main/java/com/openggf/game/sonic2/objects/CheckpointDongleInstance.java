package com.openggf.game.sonic2.objects;

import com.openggf.game.rewind.RewindTransient;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Checkpoint dongle - the swinging ball after checkpoint activation.
 * <p>
 * From disassembly (Obj79_MoveDonglyThing):
 * - angle decrements by $10 each frame
 * - angle is offset by $40 before CalcSine
 * - CalcSine returns sin in d0, cos in d1
 * - cos * $C00 >> 16 = X offset
 * - sin * $C00 >> 16 = Y offset
 * </p>
 */
public class CheckpointDongleInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int INITIAL_LIFETIME = 0x20;
    private static final int ANGLE_DECREMENT = 0x10;
    private static final int SWING_RADIUS = 0x0C00;
    private static final int DONGLE_FRAME = 2; // Mapping frame for dongle
    @RewindTransient(reason = "Structural parent link; relinked to the live S2 checkpoint "
            + "with matching captured center on rewind recreate. Scalar orbit state is "
            + "reapplied by the generic field capturer.")
    private final CheckpointObjectInstance parent;
    private int centerX;
    private int centerY;
    private int lifetime;
    private int angle;
    private int currentX;
    private int currentY;

    public CheckpointDongleInstance(CheckpointObjectInstance parent) {
        super(createDummySpawn(parent), "CheckpointDongle");
        this.parent = parent;
        this.centerX = parent.getCenterX();
        this.centerY = parent.getCenterY() - 0x14; // Y offset from ROM
        this.lifetime = INITIAL_LIFETIME;
        this.angle = 0;
        // Obj79_CheckActivation seeds objoff_30/32 but leaves child x_pos/y_pos
        // at RAM default until Obj79_MoveDonglyThing runs.
        this.currentX = 0;
        this.currentY = 0;
    }

    private static ObjectSpawn createDummySpawn(CheckpointObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x79, 0, 0, false, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
            return null;
        }
        CheckpointObjectInstance liveParent =
                findLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
        return liveParent == null ? null : new CheckpointDongleInstance(liveParent);
    }

    static CheckpointObjectInstance findLiveParentForRewind(
            ObjectManager objectManager,
            ObjectSpawn childSpawn) {
        if (objectManager == null || childSpawn == null) {
            return null;
        }
        for (ObjectInstance instance : objectManager.getActiveObjects()) {
            if (instance instanceof CheckpointObjectInstance checkpoint
                    && !checkpoint.isDestroyed()
                    && checkpoint.getCenterX() == childSpawn.x()
                    && checkpoint.getCenterY() == childSpawn.y()) {
                return checkpoint;
            }
        }
        return null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (lifetime <= 0) {
            return;
        }

        lifetime--;

        // ROM: angle(a0) starts at 0, decrements by $10 each frame
        // subi.b #$40, d0 before CalcSine
        int calcAngle = (angle - 0x40) & 0xFF;
        angle = (angle - ANGLE_DECREMENT) & 0xFF;

        // CalcSine: d0 = sin(angle), d1 = cos(angle)
        // ROM uses 256-step angle, we convert to radians
        double radians = calcAngle * Math.PI * 2 / 256.0;
        double sinVal = Math.sin(radians);
        double cosVal = Math.cos(radians);
        // The swap instruction effectively divides by 65536 (moves high word to low)
        // However, we are multiplying a double (0..1) by SWING_RADIUS (12 * 256 =
        // 3072).
        // To get pixel coordinates (12), we need to divide by 256 (>> 8), not 65536 (>>
        // 16).
        int xOffset = (int) (cosVal * SWING_RADIUS) >> 8;
        int yOffset = (int) (sinVal * SWING_RADIUS) >> 8;

        currentX = centerX + xOffset;
        currentY = centerY + yOffset;

        if (lifetime <= 0) {
            // Notify parent to switch to finished animation
            parent.onDongleComplete();
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getCheckpointRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(DONGLE_FRAME, currentX, currentY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }
}
