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
 * LBZ1 collapsing building/pillar helper spawned by rival Knuckles.
 *
 * <p>ROM reference: {@code loc_6285A}. The ROM positions four helpers at
 * x=$3BC0/$3B80/$3B40/$3B00, y=$01A0, moves each upward under
 * {@code Obj_Wait}, and routes through {@code Obj_BossExpControl1} to emit
 * visible boss-explosion sprites.
 */
public final class CutsceneKnucklesLbz1CollapseChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int[][] START_POSITIONS = {
            {0x3BC0, 0x01A0},
            {0x3B80, 0x01A0},
            {0x3B40, 0x01A0},
            {0x3B00, 0x01A0}
    };
    private static final int EXPLOSION_TIMER = 0x20;
    private static final int EXPLOSION_INTERVAL = 3;
    private static final int EXPLOSION_X_RANGE = 0x20;
    private static final int EXPLOSION_Y_RANGE = 0x20;

    private final CutsceneKnucklesLbz1Instance parent;
    private int subtype;
    private int x;
    private int y;
    private int explosionTimer = EXPLOSION_TIMER;
    private int explosionIntervalCounter;

    CutsceneKnucklesLbz1CollapseChild(CutsceneKnucklesLbz1Instance parent, int subtype) {
        super(new ObjectSpawn(
                START_POSITIONS[subtype & 3][0],
                START_POSITIONS[subtype & 3][1],
                0,
                subtype & 3,
                0,
                false,
                0), "CutsceneKnuxLBZ1CollapseChild");
        this.parent = parent;
        this.subtype = subtype & 3;
        this.x = START_POSITIONS[this.subtype][0];
        this.y = START_POSITIONS[this.subtype][1];
        this.explosionIntervalCounter = 0;
    }

    CutsceneKnucklesLbz1CollapseChild(ObjectSpawn spawn) {
        this(null, spawn != null ? spawn.subtype() : 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CutsceneKnucklesLbz1Instance liveParent = CutsceneKnucklesLbz1RewindLinks.singleLiveParent(ctx);
        if (liveParent == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        int capturedSubtype = spawn != null ? spawn.subtype() : subtype;
        return new CutsceneKnucklesLbz1CollapseChild(liveParent, capturedSubtype);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (parent == null || parent.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        y -= 4;
        tickBossExplosionControl();
        updateDynamicSpawn(x, y);
    }

    private void tickBossExplosionControl() {
        explosionIntervalCounter--;
        if (explosionIntervalCounter >= 0) {
            return;
        }

        explosionTimer--;
        if (explosionTimer <= 0) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }

        int random = services().rng().nextRaw();
        int xOffset = (random & ((EXPLOSION_X_RANGE * 2) - 1)) - EXPLOSION_X_RANGE;
        int yOffset = ((random >> 8) & ((EXPLOSION_Y_RANGE * 2) - 1)) - EXPLOSION_Y_RANGE;
        services().playSfx(Sonic3kSfx.EXPLODE.id);
        spawnChild(() -> new S3kBossExplosionChild(x + xOffset, y + yOffset));
        explosionIntervalCounter = EXPLOSION_INTERVAL - 1;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM controller object is invisible; it only moves the explosion stream.
    }
}
