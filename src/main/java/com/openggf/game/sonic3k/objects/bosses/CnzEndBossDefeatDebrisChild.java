package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code ChildObjDat_6EDF2}, the two flickering defeat fragments. */
final class CnzEndBossDefeatDebrisChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final int SUBPIXEL_SHIFT = 8;
    private static final int[] X_OFFSETS = {-0x14, 0x14};
    private static final int[] FRAMES = {0x0B, 0x0C};
    private static final int[] X_VELOCITIES = {-0x100, 0x100};

    private int subtype;
    private int xFixed;
    private int yFixed;
    private int xVelocity;
    private int yVelocity;
    private int flickerCounter;

    CnzEndBossDefeatDebrisChild(CnzEndBossInstance boss, int xOffset, int xVelocity) {
        this(fragmentSpawn(boss, xOffset < 0 ? 0 : 1));
    }

    private CnzEndBossDefeatDebrisChild(ObjectSpawn spawn) {
        super(spawn, "CNZEndBossDefeatDebris");
        subtype = spawn.subtype() & 1;
        xFixed = spawn.x() << SUBPIXEL_SHIFT;
        yFixed = spawn.y() << SUBPIXEL_SHIFT;
        xVelocity = X_VELOCITIES[subtype];
        yVelocity = -0x100;
    }

    private static ObjectSpawn fragmentSpawn(CnzEndBossInstance boss, int subtype) {
        return new ObjectSpawn(boss.getCentreX() + X_OFFSETS[subtype], boss.getCentreY(), 0,
                subtype, 0, false, 0);
    }

    @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzEndBossDefeatDebrisChild(ctx.spawn());
    }
    @Override public int getX() { return getCentreX() - 0x14; }
    @Override public int getY() { return getCentreY() - 0x14; }
    int getCentreX() { return xFixed >> SUBPIXEL_SHIFT; }
    int getCentreY() { return yFixed >> SUBPIXEL_SHIFT; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        xFixed = S3kBossFlickerMove.integrate(xFixed, xVelocity);
        yFixed = S3kBossFlickerMove.integrate(yFixed, yVelocity);
        yVelocity += 0x38;
        flickerCounter++;
        var objectServices = tryServices();
        if (objectServices != null && objectServices.camera() != null) {
            int cameraX = Short.toUnsignedInt(objectServices.camera().getX());
            int cameraY = Short.toUnsignedInt(objectServices.camera().getY());
            if (S3kBossFlickerMove.isOutsideNativeBounds(
                    getCentreX(), getCentreY(), cameraX, cameraY)) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
        }
        updateDynamicSpawn(getCentreX(), getCentreY());
    }

    @Override public boolean isPersistent() { return true; }
    @Override public int getPriorityBucket() { return 5; }
    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visibleForTest()) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(FRAMES[subtype], getCentreX(), getCentreY(), false, false);
        }
    }

    int frameForTest() { return FRAMES[subtype]; }
    int xVelocityForTest() { return xVelocity; }
    int yVelocityForTest() { return yVelocity; }
    boolean visibleForTest() { return S3kBossFlickerMove.isVisible(flickerCounter); }
}
