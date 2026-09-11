package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;

import java.util.List;

/** ROM {@code Child6_IncLevX}/{@code Child6_DecLevY} gradual camera controller. */
public final class CnzEndBossBoundaryController extends AbstractObjectInstance
        implements RewindRecreatable {
    private enum Axis { MAX_X_UP, MIN_Y_DOWN, MIN_X_DOWN }
    private static final int ACCELERATION = 0x4000;
    private Axis axis;
    private int target;
    private int accumulator;

    private CnzEndBossBoundaryController(int x, int y, Axis axis, int target) {
        super(new ObjectSpawn(x, y, 0, axis.ordinal(), 0, false, target), "CNZEndBossBoundaryController");
        this.axis = axis;
        this.target = target;
    }

    public static CnzEndBossBoundaryController increaseMaxX(int x, int y, int target) {
        return new CnzEndBossBoundaryController(x, y, Axis.MAX_X_UP, target);
    }

    public static CnzEndBossBoundaryController decreaseMinY(int x, int y, int target) {
        return new CnzEndBossBoundaryController(x, y, Axis.MIN_Y_DOWN, target);
    }

    public static CnzEndBossBoundaryController decreaseMinX(int x, int y, int target) {
        return new CnzEndBossBoundaryController(x, y, Axis.MIN_X_DOWN, target);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Axis restoredAxis = Axis.values()[Math.max(0, Math.min(ctx.spawn().subtype(), Axis.values().length - 1))];
        return new CnzEndBossBoundaryController(ctx.spawn().x(), ctx.spawn().y(), restoredAxis,
                ctx.spawn().rawYWord());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        accumulator += ACCELERATION;
        int step = accumulator >>> 16;
        if (axis == Axis.MAX_X_UP) {
            int next = (services().camera().getMaxX() & 0xFFFF) + step;
            if (next >= target) {
                services().camera().setMaxX((short) target);
                ObjectLifetimeOps.expireDynamic(this);
            } else services().camera().setMaxX((short) next);
        } else if (axis == Axis.MIN_Y_DOWN) {
            int next = (services().camera().getMinY() & 0xFFFF) - step;
            if (next <= target) {
                services().camera().setMinY((short) target);
                ObjectLifetimeOps.expireDynamic(this);
            } else services().camera().setMinY((short) next);
        } else {
            int next = (services().camera().getMinX() & 0xFFFF) - step;
            if (next <= target) {
                services().camera().setMinX((short) target);
                ObjectLifetimeOps.expireDynamic(this);
            } else services().camera().setMinX((short) next);
        }
    }

    @Override public boolean isPersistent() { return true; }
    @Override public void appendRenderCommands(List<GLCommand> commands) { }
}
