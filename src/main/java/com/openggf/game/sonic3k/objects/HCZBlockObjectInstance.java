package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Object 0x40 - HCZ Block (Hydrocity Zone).
 * <p>
 * Static full-solid block with four ROM-defined width variants selected directly
 * by subtype. The block uses HCZ level art at {@code ArtTile_HCZMisc + $A} with
 * palette line 2 and otherwise just delegates collision to the generic solid-object
 * pipeline.
 * <p>
 * ROM references: Obj_HCZBlock (sonic3k.asm:43233), byte_1F38A, Map_HCZBlock.
 */
public class HCZBlockObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable, SolidObjectProvider, SolidObjectListener,
        RomObjectCodePointerProvider {

    private static final String ART_KEY = Sonic3kObjectArtKeys.HCZ_BLOCK;
    private static final int PRIORITY = 5; // ROM: move.w #$280,priority(a0)
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0001;

    // byte_1F38A: {halfWidth, halfHeight}
    private static final int[][] SIZE_TABLE = {
            {0x10, 0x10},
            {0x20, 0x10},
            {0x30, 0x10},
            {0x40, 0x10}
    };

    private int x;
    private int y;
    private int mappingFrame;
    private int halfWidth;
    private int halfHeight;

    public HCZBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZBlock");
        this.x = spawn.x();
        this.y = spawn.y();

        int sizeIndex = Math.min(spawn.subtype() & 0xFF, SIZE_TABLE.length - 1);
        this.mappingFrame = sizeIndex;
        this.halfWidth = SIZE_TABLE[sizeIndex][0];
        this.halfHeight = SIZE_TABLE[sizeIndex][1];
    }

    @Override
    public HCZBlockObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZBlockObjectInstance(ctx.spawn());
    }

    @Override
    public int romObjectCodePointerHighWord() {
        // loc_1F3CA is the routine word copied by TailsCPU_UpdateObjInteract.
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    public boolean airborneStaleStandingBitReturnsNoContact(PlayableEntity player) {
        // SolidObjectFull2_1P's loc_1DCF0 clears the retained standing bit and
        // returns immediately when another object (such as Obj_MonitorBreak)
        // has already set Status_InAir.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity playerEntity, int collisionHalfWidth) {
        // ROM: Solid_Landed uses width_pixels for ridden top-surface retention, not d1.
        return halfWidth;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // ROM has no per-contact behavior beyond SolidObjectFull2.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, x, y, false, false);
        }
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }
}
