package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.boss.AbstractBossChild;

import java.util.List;

/** HCZ end-boss blade impact explosion (ROM loc_6B77C / loc_6B7A2). */
public final class HczEndBossBladeImpactExplosion extends AbstractBossChild
        implements TouchResponseProvider, RewindRecreatable {
    private static final int HURT_COLLISION = 0x8B;
    private static final int FRAME_DELAY = 7;
    private static final int NON_HURTING_FRAME = 3;
    private static final int FINAL_FRAME = 5;

    private final HczEndBossInstance boss;
    private boolean initialized;
    private int mappingFrame;
    private int frameTimer = FRAME_DELAY;

    public HczEndBossBladeImpactExplosion(HczEndBossInstance boss, int x, int y) {
        super(boss, "HCZEndBossBladeImpactExplosion", 3, 0);
        this.boss = boss;
        currentX = x;
        currentY = y;
        updateDynamicSpawn();
    }

    @Override
    public HczEndBossBladeImpactExplosion recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null
                ? null
                : new HczEndBossBladeImpactExplosion(restoredBoss, currentX, currentY);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }
        if (boss.isDefeatSignal()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        // loc_6B77C publishes mapping frame 0 on its setup dispatch. The next
        // slot dispatch begins byte_6BF02's pre-decrement animation.
        if (!initialized) {
            initialized = true;
            return;
        }
        frameTimer--;
        if (frameTimer >= 0) {
            return;
        }
        frameTimer = FRAME_DELAY;
        mappingFrame++;
        if (mappingFrame >= FINAL_FRAME) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    @Override
    public int getCollisionFlags() {
        return mappingFrame < NON_HURTING_FRAME && !isDestroyed() ? HURT_COLLISION : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // loc_6B7A2 calls Add_SpriteToCollisionResponseList directly before
        // Draw_Sprite; it is not gated by a display/render flag.
        return false;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || services().renderManager().getExplosionRenderer() == null) {
            return;
        }
        services().renderManager().getExplosionRenderer()
                .drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }
}
