package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Objects 0xC3/0xC4 - WFZ Tornado smoke.
 *
 * <p>ROM reference: ObjC3 at s2.asm:80638-80675. ObjC4 points to the same
 * routine. The smoke uses explosion mappings/art, drifts up-left at -$100/-$100,
 * advances frames every eight ticks, then deletes after frame 4.
 */
public class TornadoSmokeObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int ANIM_DELAY = 7;
    private static final int MAX_FRAME = 4;
    private static final int PRIORITY = 5;

    private int currentX;
    private int currentY;
    private int xPosFixed8;
    private int yPosFixed8;
    private int xVel;
    private int yVel;
    private int mappingFrame;
    private int frameTimer;
    private boolean initialized;

    public TornadoSmokeObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    public TornadoSmokeObjectInstance(ObjectSpawn spawn, Integer forcedRandomOffset) {
        super(spawn, "TornadoSmoke");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        if (forcedRandomOffset != null) {
            initializeWithOffset(forcedRandomOffset);
        }
    }

    @Override
    public TornadoSmokeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new TornadoSmokeObjectInstance(ctx.spawn());
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            initializeWithOffset(Sonic2Rng.nextTornadoSmokeOffset(services().rng()));
            return;
        }

        xPosFixed8 += xVel;
        yPosFixed8 += yVel;
        currentX = xPosFixed8 >> 8;
        currentY = yPosFixed8 >> 8;

        frameTimer--;
        if (frameTimer >= 0) {
            return;
        }

        frameTimer = ANIM_DELAY;
        mappingFrame++;
        if (mappingFrame > MAX_FRAME) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    private void initializeWithOffset(int randomOffset) {
        if (initialized) {
            return;
        }
        initialized = true;
        currentX = spawn.x() - randomOffset;
        currentY = spawn.y() + 0x10;
        xPosFixed8 = currentX << 8;
        yPosFixed8 = currentY << 8;
        xVel = -0x100;
        yVel = -0x100;
        mappingFrame = 0;
        frameTimer = ANIM_DELAY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.EXPLOSION);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(currentX, currentY, 3, 1.0f, 0.4f, 0.2f);
        ctx.drawWorldLabel(currentX, currentY, -1, "C3 f" + mappingFrame, DebugColor.ORANGE);
    }

    int getMappingFrameForTesting() {
        return mappingFrame;
    }
}
