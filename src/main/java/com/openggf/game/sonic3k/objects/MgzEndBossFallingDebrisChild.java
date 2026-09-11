package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** ROM {@code loc_6CF9E/loc_6CFB2} light-gravity boss debris SST. */
public final class MgzEndBossFallingDebrisChild extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int[] FRAMES = {0, 1, 2, 0, 0, 1, 0, 2, 0, 1};
    private static final int[][] VELOCITIES = {
            {-0x400, -0x400}, {0x400, -0x400}, {-0x80, -0x400}, {0x80, -0x400},
            {-0x300, -0x200}, {0x300, -0x200}, {-0x200, -0x300}, {0x200, -0x300},
            {-0x80, -0x200}, {0x80, -0x200}
    };
    private static final int GRAVITY = 0x18;
    private static final int OFFSCREEN_MARGIN = 0x40;

    private int index;
    private boolean flipX;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;

    public MgzEndBossFallingDebrisChild(ObjectSpawn spawn) {
        super(spawn, "MGZEndBossFallingDebris");
        index = Math.floorMod(spawn.subtype(), FRAMES.length);
        flipX = (spawn.renderFlags() & 1) != 0;
        xFixed = spawn.x() << 8;
        yFixed = spawn.y() << 8;
        xVel = flipX ? -VELOCITIES[index][0] : VELOCITIES[index][0];
        yVel = VELOCITIES[index][1];
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        xFixed += xVel;
        yFixed += yVel;
        yVel += GRAVITY;
        int cameraBottom = services().camera().getY() + viewportHeight();
        if (getY() > cameraBottom + OFFSCREEN_MARGIN) ObjectLifetimeOps.expireDynamic(this);
    }

    @Override public int getX() { return xFixed >> 8; }
    @Override public int getY() { return yFixed >> 8; }
    @Override public int getPriorityBucket() { return 2; }
    @Override public boolean isHighPriority() { return true; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS_DEBRIS);
        if (renderer != null) renderer.drawFrameIndex(FRAMES[index], getX(), getY(), xVel < 0, false);
    }
}
