package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;

import java.util.List;

/** Priority-bearing SST parts from {@code ChildObjDat_6D7C0}. */
public final class MgzEndBossRenderChild extends AbstractBossChild implements RewindRecreatable {
    public static final int ROLE_STATIC_BACK = 0;
    public static final int ROLE_ANGLED = 1;
    public static final int ROLE_POD = 2;
    public static final int ROLE_DRILL_HEAD = 3;
    public static final int ROLE_LOWER_FRONT = 4;
    public static final int ROLE_LOWER_BACK = 5;
    public static final int ROLE_FLAME_FRONT = 6;
    public static final int ROLE_FLAME_BACK = 7;
    public static final int ROLE_FIRST = ROLE_STATIC_BACK;
    public static final int ROLE_LAST = ROLE_FLAME_BACK;

    private int role;

    public MgzEndBossRenderChild(MgzDrillingRobotnikInstance boss, int role) {
        super(boss, "MGZEndBossPart", priorityFor(role), 0);
        this.role = role;
    }

    private static int priorityFor(int role) {
        return switch (role) {
            case ROLE_STATIC_BACK -> 7; // word_6D77C: $380
            case ROLE_POD, ROLE_DRILL_HEAD -> 5; // ship / word_6D788: $280
            case ROLE_LOWER_FRONT, ROLE_FLAME_FRONT -> 3; // word_6D79A/6D7A0: $180
            case ROLE_LOWER_BACK -> 7; // loc_6CEB0 subtype != 4: $380
            case ROLE_FLAME_BACK -> 6; // loc_6CF20 parent subtype != 0: $300
            default -> 6; // word_6D782: $300
        };
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!shouldUpdate(vIntRunCount)) return;
        syncPositionWithParent();
        updateDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (parent instanceof MgzDrillingRobotnikInstance boss && !boss.isDestroyed()) {
            boss.appendCompositeChild(role);
        }
    }

    @Override
    public boolean isHighPriority() {
        // Child1_MakeRoboShip3 copies the parent's art_tile, then
        // Child_SyncDraw (sonic3k.asm:138841-138854) mirrors bit 7 from that
        // parent. Obj_MGZ2DrillingRobotnik's surprise path only loads
        // ObjDat_MGZDrillBoss (142440), while Obj_MGZEndBoss additionally sets
        // bit 7 at loc_6C354 (142754). The pod must therefore remain behind
        // high-priority terrain during the Act 2 surprise, but render above it
        // for the actual end boss.
        return role == ROLE_POD && parent.isHighPriority();
    }

    @Override
    public int getPriorityBucket() {
        if (parent instanceof MgzDrillingRobotnikInstance boss) {
            return boss.compositePriority(role);
        }
        return priorityFor(role);
    }

    public int role() {
        return role;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MgzDrillingRobotnikInstance restoredBoss = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MgzDrillingRobotnikInstance.class);
        if (restoredBoss == null) return null;
        MgzEndBossRenderChild restored = new MgzEndBossRenderChild(restoredBoss, role);
        restoredBoss.rewindAttachRenderChild(restored);
        return restored;
    }
}
