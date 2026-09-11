package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Short-lived flame column spawned when the AIZ end boss fully reveals itself.
 *
 * <p>ROM: {@code ChildObjDat_69D36 -> loc_69996 -> loc_699B0}, using frames
 * {@code 0x21, 0x21, 0x22, 0x23} at offset {@code (0, -$30)}.
 */
public class AizEndBossFlameColumnChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int Y_OFFSET = -0x30;
    private static final int[] FRAMES = {0x21, 0x21, 0x22, 0x23};
    private static final int[] FRAME_DURATIONS = {1, 4, 5, 6};
    private final AizEndBossInstance boss;
    private int sequenceIndex;
    private int frameTimer;

    public AizEndBossFlameColumnChild(AizEndBossInstance boss) {
        super(new ObjectSpawn(boss.getX(), boss.getY() + Y_OFFSET, 0, 0, 0, false, 0),
                "AIZEndBossFlameColumn");
        this.boss = boss;
        this.sequenceIndex = 0;
        this.frameTimer = FRAME_DURATIONS[0];
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null ? null : new AizEndBossFlameColumnChild(restoredBoss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        if (boss.isDefeatSignal()) {
            setDestroyed(true);
            return;
        }

        frameTimer--;
        if (frameTimer > 0) {
            return;
        }

        sequenceIndex++;
        if (sequenceIndex >= FRAMES.length) {
            setDestroyed(true);
            return;
        }
        frameTimer = FRAME_DURATIONS[sequenceIndex];
    }

    @Override
    public int getX() {
        return boss.getX();
    }

    @Override
    public int getY() {
        return boss.getY() + Y_OFFSET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || boss.isHidden()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(FRAMES[sequenceIndex], getX(), getY(), boss.isFacingRight(), false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(2);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }
}
