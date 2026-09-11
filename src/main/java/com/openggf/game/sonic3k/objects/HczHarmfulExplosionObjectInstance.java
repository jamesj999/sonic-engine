package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;

import java.util.List;

/**
 * Standalone harmful HCZ explosion created by Jawz. The HCZ end boss uses the
 * same ROM explosion routine through its parent-owned child representation.
 *
 * <p>ROM: {@code HCZEndBossExplosion_Init/Main},
 * {@code HCZEndBossExplosion_ObjData}, and {@code HCZEndBossExplosion_Anim}
 * ({@code docs/skdisasm/sonic3k.asm:141531-141548,142183-142188,142381-142383}).
 */
public final class HczHarmfulExplosionObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateRewindRecreatable {

    // HCZEndBossExplosion_ObjData: collision_flags = $8B (HURT, size index $0B).
    private static final int HURT_COLLISION_FLAGS = 0x8B;
    // HCZEndBossExplosion_Anim: delay 7, frames 0, 0, 1, 2, 3, 4, then $F4.
    private static final int FRAME_DELAY = 7;
    private static final int NON_HURTING_FRAME = 3;
    private static final int FINAL_FRAME = 5;
    // HCZEndBossExplosion_ObjData: priority $80.
    private static final int PRIORITY_BUCKET = 1;

    private boolean initialized;
    private int mappingFrame;
    private int frameTimer = FRAME_DELAY;

    public HczHarmfulExplosionObjectInstance(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "HCZHarmfulExplosion");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // The setup dispatch publishes mapping frame 0 and falls through to
        // Draw_And_Touch_Sprite; animation begins on the next SST dispatch.
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
        return !isDestroyed() && mappingFrame < NON_HURTING_FRAME
                ? HURT_COLLISION_FLAGS
                : 0;
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
        // HCZEndBossExplosion_Main calls Add_SpriteToCollisionResponseList
        // directly before Draw_Sprite while mapping_frame < 3.
        return false;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || services().renderManager() == null
                || services().renderManager().getExplosionRenderer() == null) {
            return;
        }
        services().renderManager().getExplosionRenderer()
                .drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }
}
