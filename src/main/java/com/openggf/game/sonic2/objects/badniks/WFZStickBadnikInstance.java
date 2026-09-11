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
 * Object 0xBF - unused rotating stick badnik from Wing Fortress Zone.
 *
 * <p>ROM reference: ObjBF at s2.asm:80180-80213. Init is just
 * {@code LoadSubObject}; main runs {@code AnimateSprite} with
 * {@code Ani_objBF = dc.b 1,0,1,2,$FF}, then {@code MarkObjGone}.
 */
public class WFZStickBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x04;
    private static final int WIDTH_PIXELS = 4;
    private static final int[] ANIMATION_FRAMES = {0, 1, 2};
    private static final int ANIMATION_DELAY = 1;

    private int animationIndex;

    public WFZStickBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "WFZStick", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.animFrame = 0;
        this.animTimer = ANIMATION_DELAY;
        this.animationIndex = 0;
    }

    @Override
    public WFZStickBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WFZStickBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        // ObjBF has no movement logic; MarkObjGone/offscreen lifetime is handled by ObjectManager.
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (animTimer-- > 0) {
            return;
        }
        animTimer = ANIMATION_DELAY;
        animationIndex = (animationIndex + 1) % ANIMATION_FRAMES.length;
        animFrame = ANIMATION_FRAMES[animationIndex];
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
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzUnusedBadnik,3,1)
        return true;
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
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_STICK);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }

    public int getAnimFrameForTesting() {
        return animFrame;
    }
}
