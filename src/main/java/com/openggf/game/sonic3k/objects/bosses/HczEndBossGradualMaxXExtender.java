package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;

import java.util.List;

/** ROM {@code Child6_IncLevX} / {@code Obj_IncLevEndXGradual}. */
public final class HczEndBossGradualMaxXExtender extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int ACCELERATION = 0x4000;

    private int targetMaxX;
    private int accumulator;

    public HczEndBossGradualMaxXExtender(int x, int y, int targetMaxX) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, y), "HCZEndBossMaxXExtender");
        this.targetMaxX = targetMaxX;
    }

    @Override
    public HczEndBossGradualMaxXExtender recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn restoredSpawn = ctx.spawn();
        return restoredSpawn == null ? null : new HczEndBossGradualMaxXExtender(
                restoredSpawn.x(), restoredSpawn.y(), targetMaxX);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        advanceMaxX();
    }

    /** Runs the allocation-frame dispatch when the reserved slot is already behind the live cursor. */
    public void dispatchCreation() {
        advanceMaxX();
    }

    private void advanceMaxX() {
        var camera = services().camera();
        accumulator += ACCELERATION;
        int step = accumulator >>> 16;
        int nextMaxX = (camera.getMaxX() & 0xFFFF) + step;
        if (nextMaxX >= targetMaxX) {
            camera.setMaxX((short) targetMaxX);
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        camera.setMaxX((short) nextMaxX);
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }
}
