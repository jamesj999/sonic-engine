package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Robotnik head child for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM reference: {@code ChildObjDat_7699C -> Obj_RobotnikHead4}. The child
 * follows the parent at offset {@code (0,-$1C)}, uses {@code Map_RobotnikShip}
 * frames 0/1/2/3, inherits child priority, and deletes when parent {@code $38}
 * bit 5 is set during the post-capsule ship handoff.
 */
public final class MhzEndBossRobotnikHeadChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int Y_OFFSET = -0x1C;
    private static final int PARENT_FLAGS_OFFSET = 0x38;
    private static final int DELETE_WHEN_PARENT_FLAG = 0x20;
    private static final int HEAD_FRAME_HURT = 2;
    private static final int HEAD_FRAME_DEFEATED = 3;
    private static final int HEAD_ANIMATION_DELAY = 5;
    private static final int RENDER_HALF_WIDTH = 0x10;
    private static final int RENDER_HALF_HEIGHT = 0x08;

    @RewindTransient(reason = "Structural parent link; position, priority, and hurt state derive from parent boss.")
    private final MhzEndBossInstance parent;
    private int x;
    private int y;
    private int animationTimer;
    private int rawMappingFrame;
    private int mappingFrame;

    private MhzEndBossRobotnikHeadChild() {
        this(placeholderParentForRewindProbe());
    }

    public MhzEndBossRobotnikHeadChild(MhzEndBossInstance parent) {
        super(new ObjectSpawn(parent.getX(), parent.getY() + Y_OFFSET,
                        Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                "MHZEndBossRobotnikHead");
        this.parent = parent;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossInstance.class);
        return liveParent != null ? new MhzEndBossRobotnikHeadChild(liveParent) : null;
    }

    private static MhzEndBossInstance placeholderParentForRewindProbe() {
        return new MhzEndBossInstance(new ObjectSpawn(
                -0xC0,
                0,
                Sonic3kObjectIds.MHZ_END_BOSS,
                0,
                0,
                false,
                0));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        refreshFromParent();
        if ((parent.getCustomFlag(PARENT_FLAGS_OFFSET) & DELETE_WHEN_PARENT_FLAG) != 0) {
            setDestroyed(true);
            return;
        }
        updateMappingFrame();
        updateDynamicSpawn(x, y);
    }

    private void refreshFromParent() {
        x = parent.getX();
        y = parent.getY() + Y_OFFSET;
    }

    private void updateMappingFrame() {
        updateRawAnimationFrame();
        if (parent.getState().defeated) {
            mappingFrame = HEAD_FRAME_DEFEATED;
            return;
        }
        if (parent.getState().invulnerable) {
            mappingFrame = HEAD_FRAME_HURT;
            return;
        }
        mappingFrame = rawMappingFrame;
    }

    private void updateRawAnimationFrame() {
        animationTimer++;
        if (animationTimer > HEAD_ANIMATION_DELAY) {
            animationTimer = 0;
            rawMappingFrame ^= 1;
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
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer == null) {
            return;
        }
        boolean flipX = (parent.getState().renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, flipX, false);
    }

    @Override
    public int getPriorityBucket() {
        return parent.getPriorityBucket();
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    @Override
    public boolean isHighPriority() {
        return parent.isHighPriority();
    }
}
