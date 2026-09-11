package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;

import java.util.List;

/** ROM {@code ChildObjDat_6BDCA -> loc_6B456/loc_6B47A}. */
final class HczEndBossWaterSurfaceChild extends AbstractBossChild implements RewindRecreatable {
    private int xOffset;

    HczEndBossWaterSurfaceChild(HczEndBossInstance boss, int xOffset) {
        super(boss, "HCZEndBossWaterSurface", 3, 0);
        this.xOffset = xOffset;
    }

    @Override
    public HczEndBossWaterSurfaceChild recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null ? null : new HczEndBossWaterSurfaceChild(restoredBoss, xOffset);
    }

    @Override
    protected boolean tracksViaChildComponents() {
        return false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }
        HczEndBossWaterColumn column = activeColumn();
        if (column == null || column.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        currentX = column.getX() + xOffset;
        currentY = column.getWaterSurfaceYForChildren() - 4;
        updateDynamicSpawn();
    }

    private HczEndBossWaterColumn activeColumn() {
        return services().objectManager().activeObjectsOfType(HczEndBossWaterColumn.class).stream()
                .filter(column -> !column.isDestroyed())
                .findFirst()
                .orElse(null);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Folded into HczEndBossWaterColumn's consolidated render pass.
    }
}
