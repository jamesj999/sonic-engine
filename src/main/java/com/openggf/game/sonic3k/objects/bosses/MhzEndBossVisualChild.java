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
 * Parent-positioned visual layers for S3K {@code Obj_MHZEndBoss}.
 *
 * <p>ROM references: {@code ChildObjDat_7697C -> loc_764D0} and
 * {@code ChildObjDat_76976 -> loc_76502}. These children refresh to the
 * parent position through {@code sub_7675C}; the first layer also promotes to
 * high-priority art when parent {@code $38} bit 3 is set.
 */
public final class MhzEndBossVisualChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int ARENA_ANCHORED_FLAG_OFFSET = 0x38;
    private static final int ARENA_ANCHORED_FLAG = 0x08;
    private static final int CHILD_DRAW_SPRITE2_DELETE_FLAG = 0x10;
    private static final int RENDER_HALF_WIDTH = 0x80;
    private static final int RENDER_HALF_HEIGHT = 0x80;

    @RewindTransient(reason = "Structural parent link; visual position and flip derive from parent state.")
    private final MhzEndBossInstance parent;
    private int mappingFrame;
    private int priorityBucket;
    private boolean promoteOnArenaAnchor;
    private int x;
    private int y;
    private boolean highPriority;

    private MhzEndBossVisualChild() {
        this(placeholderParentForRewindProbe(), 0, 0, false);
    }

    public MhzEndBossVisualChild(
            MhzEndBossInstance parent,
            int mappingFrame,
            int priorityBucket,
            boolean promoteOnArenaAnchor) {
        super(new ObjectSpawn(parent.getX(), parent.getY(), Sonic3kObjectIds.MHZ_END_BOSS, 0, 0, false, 0),
                "MHZEndBossVisual");
        this.parent = parent;
        this.mappingFrame = mappingFrame;
        this.priorityBucket = priorityBucket;
        this.promoteOnArenaAnchor = promoteOnArenaAnchor;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzEndBossInstance liveParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MhzEndBossInstance.class);
        return liveParent != null ? new MhzEndBossVisualChild(liveParent, 0, 0, false) : null;
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
        if ((parent.getCustomFlag(ARENA_ANCHORED_FLAG_OFFSET) & CHILD_DRAW_SPRITE2_DELETE_FLAG) != 0) {
            setDestroyed(true);
            return;
        }
        refreshFromParent();
        if (promoteOnArenaAnchor
                && (parent.getCustomFlag(ARENA_ANCHORED_FLAG_OFFSET) & ARENA_ANCHORED_FLAG) != 0) {
            highPriority = true;
        }
        updateDynamicSpawn(x, y);
    }

    private void refreshFromParent() {
        x = parent.getX();
        y = parent.getY();
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
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer == null) {
            return;
        }
        boolean flipX = (parent.getState().renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, flipX, false);
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
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
        return highPriority;
    }
}
