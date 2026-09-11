package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0xBB - removed/unknown WFZ object.
 *
 * <p>ROM reference: ObjBB at s2.asm:79870-79895. Init uses LoadSubObject and
 * main only calls MarkObjGone. Its sub-object data uses ObjBB_MapUnc_3BBA0,
 * ArtTile_ArtNem_Unknown ($03FA, same tile base as WFZ hook), priority 4,
 * width $0C, and collision_flags $09.
 */
public class WFZUnknownBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x09;
    private static final int WIDTH_PIXELS = 0x0C;

    public WFZUnknownBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "WFZUnknown", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrame = 0;
    }

    @Override
    public WFZUnknownBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WFZUnknownBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        // ObjBB_Main only calls MarkObjGone; off-screen lifetime is handled by ObjectManager.
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animFrame = 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_UNKNOWN);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, currentX, currentY, false, false);
    }

    public int getAnimFrameForTesting() {
        return animFrame;
    }
}
