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
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xBA - WFZ Wheel (Conveyor Belt Wheel).
 * <p>
 * A static decorative wheel used in Wing Fortress Zone's conveyor belt mechanism.
 * This is a purely visual object with no collision or interactive behavior.
 * <p>
 * The object uses the LoadSubObject pattern with subtype 0x78 selecting its data
 * from the SubObjData_Index table. After initialization, it simply calls MarkObjGone
 * each frame to handle despawning when off-screen.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79835-79860 (ObjBA)
 * <ul>
 *   <li>ObjBA_Init: bra.w LoadSubObject (uses subtype 0x78 to index SubObjData_Index)</li>
 *   <li>ObjBA_Main: jmpto JmpTo39_MarkObjGone (standard off-screen despawn)</li>
 *   <li>ObjBA_SubObjData: mappings=ObjBA_MapUnc_3BB70, art_tile=make_art_tile(ArtTile_ArtNem_WfzConveyorBeltWheel,2,1),
 *       render_flags=level_fg, priority=4, width=$10, collision=0</li>
 * </ul>
 * <p>
 * Mappings: Single frame - one 4x4 piece (32x32 pixels) at offset (-16, -16).
 * <p>
 * Art: ArtNem_WfzConveyorBeltWheel at ROM 0x8D7D8, ArtTile $03EA, palette line 2, priority set.
 */
public class WFZWheelObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    public WFZWheelObjectInstance(ObjectSpawn spawn) {
        super(spawn, "WFZWheel");
    }

    @Override
    public WFZWheelObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new WFZWheelObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: ObjBA_Main just calls MarkObjGone (off-screen despawn handled by engine)
        // No movement, animation, or collision - purely decorative
    }

    @Override
    public int getPriorityBucket() {
        // ROM: ObjBA_SubObjData priority=4
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzConveyorBeltWheel,2,1)
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_CONVEYOR_BELT_WHEEL);
        if (renderer == null) return;

        // Render frame 0 (single mapping frame: 4x4 tile piece at center)
        renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        int y = spawn.y();
        // Draw spawn position cross and object info label
        ctx.drawCross(x, y, 4, 1.0f, 1.0f, 0.0f);
        ctx.drawWorldLabel(x, y, -1, "BA WFZWheel", DebugColor.YELLOW);
    }
}
