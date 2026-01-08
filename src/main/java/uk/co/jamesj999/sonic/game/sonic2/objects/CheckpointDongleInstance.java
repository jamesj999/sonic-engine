package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

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
public class CheckpointDongleInstance extends AbstractObjectInstance {
    private static final int INITIAL_LIFETIME = 0x20;
    private static final int ANGLE_DECREMENT = 0x10;
    private static final int SWING_RADIUS = 0x0C00;
    private static final int DONGLE_FRAME = 2; // Mapping frame for dongle

    private final CheckpointObjectInstance parent;
    private final int centerX;
    private final int centerY;
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
        this.currentX = centerX;
        this.currentY = centerY;
    }

    private static ObjectSpawn createDummySpawn(CheckpointObjectInstance parent) {
        return new ObjectSpawn(parent.getCenterX(), parent.getCenterY(), 0x79, 0, 0, false, 0);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
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
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
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
}
