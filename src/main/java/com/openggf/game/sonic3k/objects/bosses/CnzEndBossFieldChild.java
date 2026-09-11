package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code ChildObjDat_6EDE4 -> loc_6EADA} magnetic field lobe. */
final class CnzEndBossFieldChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private final CnzEndBossInstance boss;
    private final int xOffset;
    private int centreX;
    private int centreY;
    private int frame = 9;
    private int animIndex;
    private int animTimer;
    private static final int[] FRAMES = {9, 6, 9, 7};
    private static final int[] DELAYS = {0, 0, 2, 0};

    CnzEndBossFieldChild(CnzEndBossInstance boss, int xOffset) {
        super(new ObjectSpawn(boss.getCentreX() + xOffset, boss.getCentreY() + 0x54,
                0, xOffset < 0 ? 0 : 2, 0, false, 0), "CNZEndBossField");
        this.boss = boss;
        this.xOffset = xOffset;
    }

    @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossInstance restoredBoss = CnzEndBossRewindLinks.boss(ctx);
        if (restoredBoss == null) return null;
        return new CnzEndBossFieldChild(restoredBoss, ctx.spawn().subtype() == 0 ? -0x0C : 0x0C);
    }
    @Override public int getX() { return centreX - 0x10; }
    @Override public int getY() { return centreY - 0x40; }
    public int getCentreX() { return centreX; }
    public int getCentreY() { return centreY; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!boss.magneticFieldActive() || boss.defeatStarted()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        centreX = boss.getCentreX() + xOffset;
        centreY = boss.getCentreY() + 0x54;
        boss.playGravityMachineSfx(vIntRunCount);
        if (--animTimer < 0) {
            animIndex = (animIndex + 1) & 3;
            frame = FRAMES[animIndex];
            animTimer = DELAYS[animIndex];
        }
        updateDynamicSpawn(centreX, centreY);
    }

    @Override public int getCollisionFlags() { return 0xAB; }
    @Override public int getCollisionProperty() { return 0; }
    @Override public int getPriorityBucket() { return 2; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
        if (renderer != null && renderer.isReady()) renderer.drawFrameIndex(frame, centreX, centreY, false, false);
    }
}
