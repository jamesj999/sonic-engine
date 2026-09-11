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

/** ROM {@code Child1_MakeRoboHead -> Obj_RobotnikHead}. */
public final class CnzEndBossRobotnikHeadChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private final CnzEndBossRobotnikShipChild ship;
    private int centreX;
    private int centreY;
    private int frame;
    private int rawAnimationFrame;
    private int animationTimer = 5;

    CnzEndBossRobotnikHeadChild(CnzEndBossRobotnikShipChild ship) {
        super(new ObjectSpawn(ship.getCentreX(), ship.getCentreY() - 0x1C,
                0, 0, 0, false, 0), "CNZEndBossRobotnikHead");
        this.ship = ship;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossRobotnikShipChild restoredShip = CnzEndBossRewindLinks.ship(ctx);
        return restoredShip == null ? null : new CnzEndBossRobotnikHeadChild(restoredShip);
    }

    @Override public int getX() { return centreX - 0x10; }
    @Override public int getY() { return centreY - 8; }
    @Override public boolean isPersistent() { return true; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (ship.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        centreX = ship.getCentreX();
        centreY = ship.getCentreY() - 0x1C;
        if (--animationTimer < 0) {
            rawAnimationFrame ^= 1;
            animationTimer = 5;
        }
        if (ship.parentDefeated()) {
            frame = 3;
        } else if (ship.parentHurt()) {
            frame = 2;
        } else {
            frame = rawAnimationFrame;
        }
        updateDynamicSpawn(centreX, centreY);
    }

    int frameForTest() { return frame; }

    @Override public int getPriorityBucket() { return 5; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer != null) {
            renderer.drawFrameIndex(frame, centreX, centreY, ship.isFacingRight(), false);
        }
    }
}
