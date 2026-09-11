package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/** Real {@code Child6_CreateBossExplosion} subtype-4 controller slot. */
public final class CnzEndBossExplosionControllerChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int RANGE = 0x20;
    private static final int CONSTANT_TIMER = 0x80;
    private CnzEndBossRobotnikShipChild ship;
    private int centreX;
    private int centreY;
    private int remaining = CONSTANT_TIMER;
    private int interval;

    CnzEndBossExplosionControllerChild(CnzEndBossRobotnikShipChild ship, int subtype) {
        this(ship.getCentreX(), ship.getCentreY(), subtype);
        this.ship = ship;
    }

    CnzEndBossExplosionControllerChild(int centreX, int centreY, int subtype) {
        super(new ObjectSpawn(centreX, centreY, 0, subtype, 0, false, 0),
                "CNZEndBossExplosionController");
        this.centreX = centreX;
        this.centreY = centreY;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossRobotnikShipChild restoredShip = CnzEndBossRewindLinks.ship(ctx);
        return restoredShip == null ? null
                : new CnzEndBossExplosionControllerChild(restoredShip, ctx.spawn().subtype());
    }

    @Override public int getX() { return centreX; }
    @Override public int getY() { return centreY; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (ship != null) {
            if (ship.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            refreshFromShip();
        }
        updateDynamicSpawn(centreX, centreY);
        if (--interval >= 0) return;
        if ((remaining & 0x80) == 0 && --remaining <= 0) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        int random = services().rng().nextRaw();
        int x = centreX + (random & (RANGE * 2 - 1)) - RANGE;
        int y = centreY + ((random >>> 16) & (RANGE * 2 - 1)) - RANGE;
        services().playSfx(Sonic3kSfx.EXPLODE.id);
        spawnChild(() -> new S3kBossExplosionChild(x, y));
        interval = 2;
    }

    private void refreshFromShip() {
        centreX = ship.getCentreX();
        centreY = ship.getCentreY();
    }

    @Override public boolean isPersistent() { return true; }

    @Override public int getPriorityBucket() { return 5; }

    @Override public void appendRenderCommands(List<GLCommand> commands) { }
}
