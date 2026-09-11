package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/**
 * ROM {@code Child1_CNZMinibossExplosion -> Obj_CreateBossExplosion}, subtype 6.
 *
 * <p>The controller occupies its own SST slot and emits three randomly offset
 * explosion children at the native three-dispatch cadence. Arena impacts must
 * retain this controller because its {@code Random_Number} calls determine the
 * later CNZ balloon bob phases.
 */
final class CnzMinibossBlockExplosionControllerChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int RANGE = 0x10;
    private static final int DISPATCH_INTERVAL = 3;
    private static final int INITIAL_TIMER = 4;

    private int timer = INITIAL_TIMER;
    // Obj_CreateBossExplosion tail-jumps into Obj_Wait with a zeroed $2E,
    // so the first controller callback fires on its creation dispatch.
    private int interval;

    CnzMinibossBlockExplosionControllerChild(int centreX, int centreY) {
        super(new ObjectSpawn(centreX, centreY, 0, 6, 0, false, 0),
                "CNZMinibossBlockExplosionController");
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzMinibossBlockExplosionControllerChild(ctx.spawn().x(), ctx.spawn().y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (--interval >= 0) {
            return;
        }
        emitExplosion();
    }

    void dispatchCreation() {
        emitExplosion();
    }

    private void emitExplosion() {
        if (--timer <= 0) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        int random = services().rng().nextRaw();
        int x = spawn.x() + (random & ((RANGE * 2) - 1)) - RANGE;
        int y = spawn.y() + ((random >>> 8) & ((RANGE * 2) - 1)) - RANGE;
        services().playSfx(Sonic3kSfx.EXPLODE.id);
        spawnChild(() -> new S3kBossExplosionChild(x, y));
        interval = DISPATCH_INTERVAL - 1;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Controller SST has no mapping of its own.
    }
}
