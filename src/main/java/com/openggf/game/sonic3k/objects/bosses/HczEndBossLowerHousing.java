package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code loc_6B75C}: lower HCZ end-boss housing child. */
final class HczEndBossLowerHousing extends AbstractBossChild implements RewindRecreatable {
    private static final int Y_OFFSET = 0x1C;
    private static final int MAPPING_FRAME = 1;

    HczEndBossLowerHousing(HczEndBossInstance boss) {
        super(boss, "HCZEndBossLowerHousing", 2, 0);
    }

    @Override
    public HczEndBossLowerHousing recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null ? null : new HczEndBossLowerHousing(restoredBoss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }
        currentX = parent.getX();
        currentY = parent.getY() + Y_OFFSET;
        flipX = ((HczEndBossInstance) parent).isFacingRight();
        updateDynamicSpawn();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (renderer != null && renderer.isReady() && !isDestroyed()) {
            renderer.drawFrameIndex(MAPPING_FRAME, currentX, currentY, flipX, false);
        }
    }
}
