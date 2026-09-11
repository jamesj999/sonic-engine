package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Three flying MGZ end-boss fragments spawned at defeat.
 *
 * <p>ROM: {@code ChildObjDat_6D822 -> loc_6CFBE}. The child uses
 * {@code Map_MGZEndBoss} frames $2E-$30 and {@code Set_IndexedVelocity}
 * starting at velocity index $28.
 */
public class MgzEndBossDefeatDebrisChild extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int[][] OFFSETS = {
            {0x0C, -0x14},
            {-0x10, 0x08},
            {0x14, 0x08},
    };
    private static final int[][] VELOCITIES = {
            {0x300, -0x300},
            {-0x400, -0x300},
            {0x400, -0x300},
    };
    private static final int FIRST_FRAME = 0x2E;
    private static final int SUBPIXEL_SHIFT = 8;
    private static final int OFFSCREEN_MARGIN = 0x40;

    private int index;
    private boolean flipX;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;

    public MgzEndBossDefeatDebrisChild(int parentX, int parentY, int index, boolean flipX) {
        this(spawnFor(parentX, parentY, index, flipX));
    }

    public MgzEndBossDefeatDebrisChild(ObjectSpawn spawn) {
        super(spawn, "MGZEndBossDefeatDebris");
        this.index = normalizedIndex(spawn.subtype());
        this.flipX = (spawn.renderFlags() & 1) != 0;
        this.xFixed = spawn.x() << SUBPIXEL_SHIFT;
        this.yFixed = spawn.y() << SUBPIXEL_SHIFT;
        this.xVel = renderOffsetX(VELOCITIES[this.index][0], this.flipX);
        this.yVel = VELOCITIES[this.index][1];
    }

    private static ObjectSpawn spawnFor(int parentX, int parentY, int index, boolean flipX) {
        int normalizedIndex = normalizedIndex(index);
        return new ObjectSpawn(
                parentX + renderOffsetX(OFFSETS[normalizedIndex][0], flipX),
                parentY + OFFSETS[normalizedIndex][1],
                0, normalizedIndex, flipX ? 1 : 0, false, 0);
    }

    private static int normalizedIndex(int index) {
        return Math.max(0, Math.min(index, OFFSETS.length - 1));
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        xFixed += xVel;
        yFixed += yVel;
        int cameraX = services().camera().getX();
        int cameraY = services().camera().getY();
        int x = getX();
        int y = getY();
        if (x < cameraX - OFFSCREEN_MARGIN || x > cameraX + viewportWidth() + OFFSCREEN_MARGIN
                || y < cameraY - OFFSCREEN_MARGIN || y > cameraY + viewportHeight() + OFFSCREEN_MARGIN) {
            setDestroyed(true);
        }
    }

    @Override
    public int getX() {
        return xFixed >> SUBPIXEL_SHIFT;
    }

    @Override
    public int getY() {
        return yFixed >> SUBPIXEL_SHIFT;
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat3_6D7A8 priority word $100.
        return 2;
    }

    @Override
    public boolean isHighPriority() {
        // make_art_tile(ArtTile_MGZEndBossDebris,2,1).
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS);
        if (renderer != null) {
            renderer.drawFrameIndex(FIRST_FRAME + index, getX(), getY(), flipX, false);
        }
    }

    private static int renderOffsetX(int value, boolean flipX) {
        return flipX ? -value : value;
    }
}
