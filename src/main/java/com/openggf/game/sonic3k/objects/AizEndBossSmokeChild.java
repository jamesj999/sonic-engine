package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * AIZ end boss smoke trail (ROM: loc_6995A).
 *
 * <p>Spawned by bomb projectiles every 4 frames. Short-lived visual effect.
 *
 * <p>Two animation types:
 * - Moving (xVel != 0): frames $12, $13, $14 (ROM: byte_69E2F)
 * - Stationary (xVel == 0): frames $18, $19, $1A (ROM: byte_69E38)
 */
public class AizEndBossSmokeChild extends AbstractObjectInstance implements RewindRecreatable {

    private static final int SMOKE_DURATION = 14; // Approximate duration from animation
    private final AizEndBossInstance boss;
    // posX/posY/moving are non-final so the rewind field capturer reapplies them
    // after the recreate hook rebuilds the smoke. This object has a null spawn,
    // so its position cannot be derived from spawn data.
    private int posX;
    private int posY;
    private boolean moving;
    private int animTimer;
    private int mappingFrame;

    public AizEndBossSmokeChild(AizEndBossInstance boss, int x, int y, boolean moving) {
        super(null, "AIZEndBossSmoke");
        this.boss = boss;
        this.posX = x;
        this.posY = y;
        this.moving = moving;
        this.animTimer = 0;
        this.mappingFrame = moving ? 0x12 : 0x18;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null) {
            return null;
        }
        AizEndBossSmokeChild restored = new AizEndBossSmokeChild(restoredBoss, 0, 0, false);
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        animTimer++;
        if (animTimer >= SMOKE_DURATION) {
            setDestroyed(true);
            return;
        }

        // Animate smoke (ROM: byte_69E2F / byte_69E38)
        if (moving) {
            if (animTimer < 4) mappingFrame = 0x12;
            else if (animTimer < 8) mappingFrame = 0x13;
            else mappingFrame = 0x14;
        } else {
            if (animTimer < 4) mappingFrame = 0x18;
            else if (animTimer < 8) mappingFrame = 0x19;
            else mappingFrame = 0x1A;
        }
    }

    @Override
    public int getX() { return posX; }

    @Override
    public int getY() { return posY; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(mappingFrame, posX, posY, false, false);
    }

    @Override
    public boolean isHighPriority() { return false; }

    @Override
    public int getPriorityBucket() { return 2; }
}
