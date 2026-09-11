package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Falling half of {@link LbzGateLaserObjectInstance}.
 *
 * <p>ROM reference: {@code sub_293D0}, {@code loc_29416}, and {@code loc_2941C}
 * ({@code sonic3k.asm:56988-57029}).
 */
public final class LbzGateLaserBeamInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {
    private static final int WIDTH_PIXELS = 0x1C;
    private static final int HEIGHT_PIXELS = 0x04;
    private static final int FALL_STEP = 4;
    private static final int OFFSCREEN_X = 0x7FF0;

    private int currentX;
    private int currentY;
    private int targetY;
    private int renderFlags;
    private int mappingFrame;
    private int collisionFlags;
    private int priorityBucket;

    public LbzGateLaserBeamInstance(ObjectSpawn spawn, int currentX, int currentY,
            int targetY, int renderFlags, int mappingFrame, int collisionFlags, int priorityBucket) {
        super(spawn, "LBZGateLaserBeam");
        this.currentX = currentX;
        this.currentY = currentY;
        this.targetY = targetY;
        this.renderFlags = renderFlags;
        this.mappingFrame = mappingFrame;
        this.collisionFlags = collisionFlags;
        this.priorityBucket = priorityBucket;
        updateDynamicSpawn(currentX, currentY);
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
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        if (((vIntRunCount + 1) & 1) == 0) {
            renderFlags ^= 0x02;
        }

        int oldY = currentY;
        currentY = (currentY + FALL_STEP) & 0xFFFF;
        if (oldY >= targetY) {
            currentX = OFFSCREEN_X;
            collisionFlags = 0;
        }
        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_GATE_LASER);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, currentX, currentY,
                    (renderFlags & 0x01) != 0,
                    (renderFlags & 0x02) != 0);
        }
    }

    @Override
    public int getCollisionFlags() {
        return isDestroyed() ? 0 : collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean publishesTouchResponseListEntryThisFrame() {
        return !isDestroyed() && collisionFlags != 0;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priorityBucket);
    }

    public int targetYForTesting() {
        return targetY;
    }

    public int mappingFrameForTesting() {
        return mappingFrame;
    }

    public int renderFlagsForTesting() {
        return renderFlags;
    }
}
