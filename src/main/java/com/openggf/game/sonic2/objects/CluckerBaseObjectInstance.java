package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * CluckerBase (Object 0xAD) - Wall-mounted turret platform from WFZ.
 * A simple solid object that the Clucker badnik sits on.
 *
 * ROM Reference: s2.asm lines 76778-76806 (ObjAD)
 *
 * Disassembly behavior:
 *   - Init: LoadSubObject with subtype 0x42 -> ObjAD_SubObjData
 *     (mappings=ObjAD_Obj98_MapUnc_395B4, art=ArtNem_WfzScratch, priority=4, width_pixels=$18)
 *     Sets mapping_frame = $C (frame 12)
 *   - Main: SolidObject with d1=$1B, d2=8, d3=8, then MarkObjGone
 *
 * Collision: d1=$1B (27 half-width), d2=8 (top half-height), d3=8 (bottom half-height)
 * No collision_flags (not a touchable enemy).
 */
public class CluckerBaseObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // From disassembly ObjAD_Main: move.w #$1B,d1 / move.w #8,d2 / move.w #8,d3
    private static final int SOLID_HALF_WIDTH = 0x1B;
    private static final int SOLID_TOP_HEIGHT = 0x08;
    private static final int SOLID_BOTTOM_HEIGHT = 0x08;

    // Frame 12 (Map_objAE_010C) = CluckerBase platform sprite
    private static final int MAPPING_FRAME = 12;

    private boolean xFlipped;

    public CluckerBaseObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CluckerBase");
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public CluckerBaseObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CluckerBaseObjectInstance(ctx.spawn());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_TOP_HEIGHT, SOLID_BOTTOM_HEIGHT);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity,
                               SolidContact contact, int frameCounter) {
        // No special behavior - standard solid collision handled by ObjectManager
    }

    @Override
    public int getPriorityBucket() {
        // ObjAD_SubObjData: priority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CLUCKER);
        if (renderer == null) return;

        renderer.drawFrameIndex(MAPPING_FRAME, spawn.x(), spawn.y(), xFlipped, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(spawn.x(), spawn.y(), SOLID_HALF_WIDTH, SOLID_TOP_HEIGHT, 0.5f, 0.5f, 1f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -2, "CluckerBase", DebugColor.CYAN);
    }
}
