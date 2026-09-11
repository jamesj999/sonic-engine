package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * MHZ end-boss defeat fragment.
 *
 * <p>ROM reference: {@code ChildObjDat_769B0 -> loc_766CA}. The child uses
 * {@code Set_IndexedVelocity} with base index $08 and then runs
 * {@code Obj_FlickerMove}.
 *
 * <p>{@code Set_IndexedVelocity} (asm 179163-179177) computes its
 * {@code Obj_VelocityIndex} row as {@code d0(=8) + subtype(a0)*2}, and
 * {@code CreateChild6_Simple} (asm 177119-177139, the routine that spawns
 * these six fragments) seeds each child's raw ROM {@code subtype} byte as
 * {@code 0,2,4,6,8,10} (not {@code 0..5}) via its {@code d2}
 * init-then-{@code addq.w #2,d2} loop. So the *effective* table stride per
 * spawn-order index is {@code 8 + (index*2)*2 = 8 + index*4} -- byte offsets
 * 8,12,16,20,24,28 into {@code Obj_VelocityIndex} (ROM 0x0852F4), i.e. rows
 * 2-7, not rows 4-9. Verified directly against ROM bytes (not just the
 * disassembly transcription) via
 * {@code RomOffsetFinder --game s3k search-rom "FF 00 FF 00 ..."}.
 */
public final class MhzEndBossDefeatFragmentChild extends AbstractObjectInstance
        implements RewindRecreatable {
    private static final int[] MAPPING_FRAMES = {0x12, 0x13, 0x14, 0x15, 0x16, 0x17};
    private static final int[] PRIORITY_BUCKETS = {4, 4, 6, 6, 4, 4};
    private static final int[][] VELOCITIES = {
            {-0x200, -0x200},
            {0x200, -0x200},
            {-0x300, -0x200},
            {0x300, -0x200},
            {-0x200, -0x200},
            {0, -0x200},
    };
    private static final int GRAVITY = 0x38;
    private static final int SUBPIXEL_SHIFT = 8;

    private int subtype;
    private int xVel;
    private int yVel;
    private int xFixed;
    private int yFixed;
    private int flickerCounter;

    /**
     * Parent-linked construction entry retained for tests and legacy callers.
     * Rewind restore now uses {@link #recreateForRewind(RewindRecreateContext)}
     * and lets compact restore reapply the captured parent-derived {@code xVel}.
     */
    public static MhzEndBossDefeatFragmentChild forRewindRecreate(
            MhzEndBossInstance parent, int subtype) {
        return new MhzEndBossDefeatFragmentChild(parent, subtype);
    }

    MhzEndBossDefeatFragmentChild(MhzEndBossInstance parent, int subtype) {
        this(fragmentSpawn(parent.getX(), parent.getY(), subtype), subtype,
                parentDerivedXVelocity(parent, subtype));
    }

    MhzEndBossDefeatFragmentChild(ObjectSpawn spawn) {
        this(fragmentSpawn(spawn.x(), spawn.y(), spawn.subtype()), spawn.subtype(),
                VELOCITIES[validatedSubtype(spawn.subtype())][0]);
    }

    private MhzEndBossDefeatFragmentChild(ObjectSpawn spawn, int subtype, int xVel) {
        super(spawn, "MHZEndBossDefeatFragment");
        if (subtype < 0 || subtype >= MAPPING_FRAMES.length) {
            throw new IllegalArgumentException("Invalid MHZ end-boss defeat fragment subtype: " + subtype);
        }
        this.subtype = subtype;
        this.xFixed = spawn.x() << SUBPIXEL_SHIFT;
        this.yFixed = spawn.y() << SUBPIXEL_SHIFT;
        this.xVel = xVel;
        this.yVel = VELOCITIES[subtype][1];
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        if (spawn == null) {
            spawn = fragmentSpawn(0, 0, 0);
        }
        return new MhzEndBossDefeatFragmentChild(spawn);
    }

    private static ObjectSpawn fragmentSpawn(int x, int y, int subtype) {
        return new ObjectSpawn(x, y, Sonic3kObjectIds.MHZ_END_BOSS, subtype, 0, false, 0);
    }

    private static int parentDerivedXVelocity(MhzEndBossInstance parent, int subtype) {
        int validSubtype = validatedSubtype(subtype);
        int velocityX = VELOCITIES[validSubtype][0];
        if ((parent.getState().renderFlags & 1) != 0) {
            velocityX = -velocityX;
        }
        return velocityX;
    }

    private static int validatedSubtype(int subtype) {
        if (subtype < 0 || subtype >= MAPPING_FRAMES.length) {
            throw new IllegalArgumentException("Invalid MHZ end-boss defeat fragment subtype: " + subtype);
        }
        return subtype;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        xFixed += xVel;
        yFixed += yVel;
        yVel += GRAVITY;
        flickerCounter++;
        deleteIfOutsideRomFlickerBounds();
    }

    private void deleteIfOutsideRomFlickerBounds() {
        var services = tryServices();
        if (services == null || services.camera() == null) {
            return;
        }
        int x = getX();
        int y = getY();
        int cameraX = Short.toUnsignedInt(services.camera().getX());
        int cameraY = Short.toUnsignedInt(services.camera().getY());
        int coarseDeltaX = (x & 0xFF80) - (cameraX & 0xFF80);
        int deltaY = y - cameraY + 0x80;
        if (Integer.compareUnsigned(coarseDeltaX & 0xFFFF, 0x280) > 0
                || Integer.compareUnsigned(deltaY & 0xFFFF, 0x200) > 0) {
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
        return PRIORITY_BUCKETS[subtype];
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || (flickerCounter & 1) == 0) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_END_BOSS);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(MAPPING_FRAMES[subtype], getX(), getY(), false, false);
        }
    }
}
