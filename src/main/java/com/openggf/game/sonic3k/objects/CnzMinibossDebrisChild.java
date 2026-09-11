package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * CNZ Act 1 miniboss break-apart debris.
 *
 * <p>ROM: {@code Obj_CNZMinibossDebris} at sonic3k.asm:145365. Spawned by
 * {@code Child6_CNZMinibossMakeDebris} when {@code Obj_CNZMinibossEnd} runs.
 * The pieces use {@code byte_6E022} offsets, {@code CNZMinibossDebris_Frames},
 * {@code Set_IndexedVelocity(d0=0)}, then {@code Obj_FlickerMove}.
 */
public final class CnzMinibossDebrisChild extends GravityDebrisChild implements RewindRecreatable {
    private static final int[][] OFFSETS = {
            {-0x10, 0x00},
            {0x10, 0x00},
            {0x00, 0x14},
            {0x00, 0x1C},
            {0x00, 0x24},
            {0x00, 0x2C},
            {0x00, 0x34},
            {0x00, 0x3C},
            {0x00, 0x48},
    };

    private static final int[] FRAMES = {
            0x12, 0x13, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x15,
    };

    private static final int[][] VELOCITIES = {
            {-0x100, -0x100},
            {0x100, -0x100},
            {-0x200, -0x200},
            {0x200, -0x200},
            {-0x300, -0x200},
            {0x300, -0x200},
            {-0x200, -0x200},
            {0x000, -0x200},
            {-0x400, -0x300},
    };

    private int mappingFrame;
    private int flickerCounter;

    public CnzMinibossDebrisChild(int parentX, int parentY, int index) {
        super(new ObjectSpawn(
                        parentX + OFFSETS[index][0],
                        parentY + OFFSETS[index][1],
                        0, index * 2, 0, false, 0),
                "CNZMinibossDebris",
                VELOCITIES[index][0],
                VELOCITIES[index][1],
                0x38);
        this.mappingFrame = FRAMES[index];
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        return new CnzMinibossDebrisChild(spawn.x(), spawn.y(), (spawn.subtype() & 0xFF) / 2);
    }

    @Override
    public void update(int vIntRunCount, com.openggf.game.PlayableEntity player) {
        super.update(vIntRunCount, player);
        flickerCounter++;
    }

    @Override
    public int getPriorityBucket() {
        return 2;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || (flickerCounter & 1) == 0) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_MINIBOSS);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }
}
