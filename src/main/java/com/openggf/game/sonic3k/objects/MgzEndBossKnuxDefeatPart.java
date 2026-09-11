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

/** ChildObjDat_6D822 / loc_6CFBE defeat flicker parts. */
final class MgzEndBossKnuxDefeatPart extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int[][] OFFSETS = {{0x0C, -0x14}, {-0x10, 8}, {0x14, 8}};
    /** Obj_VelocityIndex at byte offset $28 + subtype*2 (subtypes 0,2,4). */
    private static final int[][] VELOCITIES = {{0x300, -0x300}, {-0x400, -0x300}, {0x400, -0x300}};
    private int xFixed, yFixed, xVel, yVel, frame, flicker;

    MgzEndBossKnuxDefeatPart(ObjectSpawn spawn) {
        super(spawn, "MGZKnuxDefeatPart");
        int index = Math.floorMod(spawn.subtype() >> 1, 3);
        xFixed = (spawn.x() + OFFSETS[index][0]) << 8;
        yFixed = (spawn.y() + OFFSETS[index][1]) << 8;
        xVel = VELOCITIES[index][0];
        yVel = VELOCITIES[index][1];
        frame = 0x2E + index;
    }

    @Override public void update(int vIntRunCount, PlayableEntity player) {
        xFixed += xVel; yFixed += yVel;
        CameraBounds bounds = cameraBounds();
        if (getX() < bounds.left || getX() > bounds.right || getY() < bounds.top || getY() > bounds.bottom) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }
    @Override public void appendRenderCommands(List<GLCommand> commands) {
        if ((flicker++ & 1) == 0) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS);
        if (renderer != null && renderer.isReady()) renderer.drawFrameIndex(frame, getX(), getY(), false, false);
    }
    @Override public int getX() { return xFixed >> 8; }
    @Override public int getY() { return yFixed >> 8; }
    @Override public int getPriorityBucket() { return getSpawn().subtype() == 0 ? 6 : 4; }
    @Override public boolean isHighPriority() { return true; }

    private CameraBounds cameraBounds() {
        int cx = services().camera().getX();
        int cy = services().camera().getY();
        return new CameraBounds(cx - 0x80, cx + 0x280, cy - 0x80, cy + 0x180);
    }
    private record CameraBounds(int left, int right, int top, int bottom) { }
}
