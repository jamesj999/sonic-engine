package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** Drill child which raises parent flag $38 bit 2 after the ROM opening wait. */
final class MgzEndBossKnuxDrillChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int READY_TIMER = 0x3F;
    private static final int FIRST_MOTION_TIMER = 0x37;
    private static final int INTER_MOTION_TIMER = 0x1F;
    private static final int SECOND_MOTION_TIMER = 0x2F;
    private final MgzEndBossKnuxInstance parent;
    private int timer = READY_TIMER;
    private boolean readySent;
    private boolean fa82Published;
    private boolean fa8aPublished;
    private int lastParentRoutine = 0x04;
    private int nativeRoutine;
    private int x;
    private int y;

    MgzEndBossKnuxDrillChild(MgzEndBossKnuxInstance parent) {
        super(new ObjectSpawn(parent.getX(), parent.getY() + 0x2D,
                0, 0, 0, false, 0), "MGZEndBossKnuxDrillChild");
        this.parent = parent;
        this.x = parent.getX();
        this.y = parent.getY() + 0x2D;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        x = parent.getX();
        y = parent.getY() + 0x2D;
        int parentRoutine = parent.getNativeRoutineForTesting();
        if (parentRoutine == 0x04 && lastParentRoutine != 0x04) {
            nativeRoutine = 1;
            timer = FIRST_MOTION_TIMER;
            readySent = false;
            fa82Published = false;
            fa8aPublished = false;
        }
        if (nativeRoutine == 1 && !fa82Published) {
            parent.beginFirstDrop(parent.generateFa82MotionSeed());
            spawnCollapseChoreography();
            fa82Published = true;
        }
        if (nativeRoutine == 1 && --timer < 0) {
            nativeRoutine = 2;
            timer = INTER_MOTION_TIMER;
        }
        if (nativeRoutine == 2 && --timer < 0) {
            parent.beginSecondDrop(parent.generateFa8aMotionSeed());
            spawnCollapseChoreography();
            fa8aPublished = true;
            nativeRoutine = 3;
            timer = SECOND_MOTION_TIMER;
        }
        if (nativeRoutine == 3 && --timer < 0) {
            nativeRoutine = 4;
            readySent = true;
            parent.signalDrillChildReady();
        }
        lastParentRoutine = parentRoutine;
        if (parent.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    /** loc_6CACA/loc_6CBCE both create ChildObjDat_6D83E with the seed handoff. */
    private void spawnCollapseChoreography() {
        int cameraY = services().camera().getY();
        boolean highBand = parent.getY() > cameraY + 0x70;
        int emitterY = cameraY + (highBand ? 0xC8 : 0x18);
        spawnChild(() -> new MgzEndBossKnuxCollapseEmitter(parent.getX(), emitterY, highBand));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Logical controller for the nested loc_6C9E8 choreography. The
        // priority-bearing visual SSTs are MgzEndBossRenderChild instances;
        // drawing frame 2 here would duplicate the native angled child.
    }

    @Override public boolean isHighPriority() { return true; }
    @Override public int getPriorityBucket() { return 6; }
    @Override public int getX() { return x; }
    @Override public int getY() { return y; }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MgzEndBossKnuxInstance restoredParent = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MgzEndBossKnuxInstance.class);
        return restoredParent == null ? null : new MgzEndBossKnuxDrillChild(restoredParent);
    }
}
