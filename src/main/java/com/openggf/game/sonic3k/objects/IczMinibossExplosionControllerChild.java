package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/** Native ICZ miniboss {@code Child6_CreateBossExplosion} controller SST. */
final class IczMinibossExplosionControllerChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int RANGE = 0x20;
    private static final int DISPATCH_INTERVAL = 3;
    private static final int INITIAL_TIMER = 0x20;

    private final IczMinibossInstance parent;
    private int remaining = INITIAL_TIMER;
    // Obj_CreateBossExplosion tail-jumps through zeroed Obj_Wait, so the
    // controller's first SST dispatch emits immediately.
    private int interval;

    IczMinibossExplosionControllerChild(IczMinibossInstance parent, int centreX, int centreY) {
        super(new ObjectSpawn(centreX, centreY, 0, 0, 0, false, 0),
                "ICZMinibossExplosionController");
        this.parent = parent;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        IczMinibossInstance restoredParent =
                RewindRecreateObjectLinks.nearestLiveObject(ctx, IczMinibossInstance.class);
        return restoredParent == null ? null
                : new IczMinibossExplosionControllerChild(
                        restoredParent, ctx.spawn().x(), ctx.spawn().y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (--interval >= 0) {
            return;
        }
        if (--remaining <= 0) {
            parent.onDefeatExplosionControllerFinished();
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
    public int getPriorityBucket() {
        return 5;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Controller SST has no mapping of its own.
    }
}
