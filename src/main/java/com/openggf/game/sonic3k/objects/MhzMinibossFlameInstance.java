package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ miniboss flame child spawned by {@code ChildObjDat_75E84} / {@code loc_757C0}.
 */
final class MhzMinibossFlameInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int COLLISION_FLAGS = 0x9A;
    private static final int COLLISION_PROPERTY = 0x16;
    private static final int[] RAW_MAPPING_FRAME = {
            0x16, 0x16, 0x16, 0x16, 0x16, 0x17, 0x16, 0x16, 0x16, 0x16, 0x16,
            0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16
    };
    private static final int[] PRIORITY_BUCKET = {
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 3, 3, 3, 3, 3, 5, 5, 5, 3, 5, 5
    };
    private static final int[][] OFFSETS = {
            { 0x0B, 0x1C, 0x01, 0x1C },
            { 0x0B, 0x1C, 0x01, 0x1C },
            { 0x0B, 0x1C, 0x01, 0x1C },
            { 0x0B, 0x1C, 0x01, 0x1C },
            { -0x08, 0x1C, -0x01, 0x1C },
            { -0x18, 0x12, -0x11, 0x12 },
            { -0x08, 0x1C, -0x01, 0x1C },
            { -0x08, 0x1C, -0x01, 0x1C },
            { -0x11, 0x1C, -0x0A, 0x1C },
            { -0x08, 0x1C, -0x01, 0x1C },
            { -0x11, 0x1C, -0x0A, 0x1C },
            { -0x08, 0x1C, 0x0B, 0x1C },
            { -0x08, 0x1B, 0x0B, 0x1B },
            { -0x08, 0x1C, 0x0B, 0x1C },
            { -0x08, 0x1C, 0x0B, 0x1C },
            { -0x08, 0x1C, 0x0B, 0x1C },
            { -0x08, 0x1C, -0x01, 0x1C },
            { -0x10, 0x1C, -0x09, 0x1C },
            { -0x11, 0x1C, -0x0A, 0x1C },
            { -0x08, 0x1C, 0x0B, 0x1C },
            { 0x0C, 0x1C, 0x06, 0x1C },
            { 0x0B, 0x1C, 0x01, 0x1C }
    };

    private MhzMinibossInstance parent;
    // Non-final so the generic field capturer reapplies it after a rewind
    // recreate (childIndex 0/1 is not spawn-derivable: both flames share subtype 0).
    private int childIndex;
    private int x;
    private int y;
    private int mappingFrame;
    private int priorityBucket;

    MhzMinibossFlameInstance(MhzMinibossInstance parent, int childIndex) {
        this(new ObjectSpawn(parent.getX(), parent.getY(), Sonic3kObjectIds.MHZ_MINIBOSS, 0, 0, false, 0),
                parent, childIndex);
    }

    MhzMinibossFlameInstance(ObjectSpawn spawn, MhzMinibossInstance parent, int childIndex) {
        super(spawn, "MHZMinibossFlame");
        this.parent = parent;
        this.childIndex = childIndex & 1;
        refreshFromParent();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MhzMinibossInstance liveParent = findNearestLiveParentForRewind(ctx);
        if (liveParent == null) {
            return null;
        }
        return new MhzMinibossFlameInstance(ctx.spawn(), liveParent, 0);
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
    public int getCollisionFlags() {
        return visibleAndTouchable() ? COLLISION_FLAGS : 0;
    }

    @Override
    public int getCollisionProperty() {
        return COLLISION_PROPERTY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }
        refreshFromParent();
    }

    private void refreshFromParent() {
        int parentFrame = Math.max(0, Math.min(parent.getMappingFrameForChildSprites(), RAW_MAPPING_FRAME.length - 1));
        mappingFrame = RAW_MAPPING_FRAME[parentFrame];
        priorityBucket = PRIORITY_BUCKET[parentFrame];
        int offsetIndex = childIndex * 2;
        int dx = OFFSETS[parentFrame][offsetIndex];
        int dy = OFFSETS[parentFrame][offsetIndex + 1];
        x = parent.getX() + dx;
        y = parent.getY() + dy;
    }

    private boolean visibleAndTouchable() {
        return parent != null && !parent.isDestroyed() && (parent.getCustomFlag(0x38) & 0x40) == 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visibleAndTouchable()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MINIBOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    private static MhzMinibossInstance findNearestLiveParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        MhzMinibossInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof MhzMinibossInstance candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (spawn == null) {
                return candidate;
            }
            long dx = candidate.getX() - spawn.x();
            long dy = candidate.getY() - spawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }
}
