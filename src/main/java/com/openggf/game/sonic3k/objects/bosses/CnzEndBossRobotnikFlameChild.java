package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code Child1_MakeRoboShipFlame}, frame 6 at adjusted offset {@code ($1E,0)}. */
public final class CnzEndBossRobotnikFlameChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private final CnzEndBossRobotnikShipChild ship;
    private int centreX;
    private int centreY;
    private boolean visible;

    CnzEndBossRobotnikFlameChild(CnzEndBossRobotnikShipChild ship) {
        super(new ObjectSpawn(ship.getCentreX() + 0x1E, ship.getCentreY(),
                0, 0, 0, false, 0), "CNZEndBossRobotnikFlame");
        this.ship = ship;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossRobotnikShipChild restoredShip = CnzEndBossRewindLinks.ship(ctx);
        return restoredShip == null ? null : new CnzEndBossRobotnikFlameChild(restoredShip);
    }

    @Override public int getX() { return centreX - 8; }
    @Override public int getY() { return centreY - 4; }
    @Override public boolean isPersistent() { return true; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (ship.isDestroyed() || !ship.isEscaping()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        int offset = ship.isFacingRight() ? 0x1E : -0x1E;
        centreX = ship.getCentreX() + offset;
        centreY = ship.getCentreY();
        visible = (vIntRunCount & 1) == 0;
        updateDynamicSpawn(centreX, centreY);
    }

    @Override public int getPriorityBucket() { return 5; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visible || isDestroyed()) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer != null) {
            renderer.drawFrameIndex(6, centreX, centreY, ship.isFacingRight(), false);
        }
    }
}
