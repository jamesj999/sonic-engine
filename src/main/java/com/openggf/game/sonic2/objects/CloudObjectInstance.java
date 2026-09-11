package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xB3 - Clouds (placeable object) from Sky Chase Zone.
 * <p>
 * Decorative clouds that scroll leftward at varying speeds based on subtype.
 * Each frame, the cloud moves by its own x_vel (leftward) plus the Tornado's
 * velocity (which pushes everything with the auto-scrolling camera).
 * Deleted when scrolling behind the left edge of the screen.
 * <p>
 * Subtypes determine cloud size and speed:
 * <ul>
 *   <li>0x5E: Large cloud, x_vel = -0x80, mapping frame 0</li>
 *   <li>0x60: Medium cloud, x_vel = -0x40, mapping frame 1</li>
 *   <li>0x62: Small cloud, x_vel = -0x20, mapping frame 2</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79108-79152 (ObjB3)
 * <p>
 * SubObjData: mappings=ObjB3_MapUnc_3B32C, art_tile=ArtTile_ArtNem_Clouds (palette 2),
 * render_flags=level_fg, priority=6, width=$30, collision=0.
 */
public class CloudObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Velocity lookup table indexed by (subtype - 0x5E) / 2
    // From disassembly: word_3B30C
    // dc.w -$80, -$40, -$20
    private static final int[] VELOCITY_TABLE = { -0x80, -0x40, -0x20 };

    // Subtype base value (first valid subtype)
    // From disassembly: subi.b #$5E,d0
    private static final int SUBTYPE_BASE = 0x5E;

    private int xVelocity;
    private int mappingFrame;
    private final SubpixelMotion.State motionState;

    public CloudObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Cloud");

        // ROM: ObjB3_Init
        // moveq #0,d0 / move.b subtype(a0),d0 / subi.b #$5E,d0
        // move.w word_3B30C(pc,d0.w),x_vel(a0)
        // lsr.w #1,d0 / move.b d0,mapping_frame(a0)
        int subtypeOffset = (spawn.subtype() & 0xFF) - SUBTYPE_BASE;
        int index = subtypeOffset / 2;
        if (index >= 0 && index < VELOCITY_TABLE.length) {
            this.xVelocity = VELOCITY_TABLE[index];
            this.mappingFrame = index;
        } else {
            // Fallback for unexpected subtypes
            this.xVelocity = VELOCITY_TABLE[0];
            this.mappingFrame = 0;
        }
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, this.xVelocity, 0);
    }

    @Override
    public CloudObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CloudObjectInstance(ctx.spawn());
    }

    @Override
    public int getX() {
        return motionState.x;
    }

    @Override
    public int getY() {
        return motionState.y;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority=6
        return RenderPriority.clamp(6);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: ObjB3_Main
        // jsrto JmpTo26_ObjectMove  -> applies x_vel to x_pos (256 subpixels)
        // move.w (Tornado_Velocity_X).w,d0 / add.w d0,x_pos(a0)
        // bra.w Obj_DeleteBehindScreen

        SubpixelMotion.speedToPos(motionState);

        // Add Tornado velocity (whole pixels, not subpixel)
        int tornadoVelX = services().parallaxManager().getTornadoVelocityX();
        motionState.x += tornadoVelX;

        // Obj_DeleteBehindScreen: delete if x_pos is behind camera
        Camera camera = services().camera();
        int cameraCoarse = (camera.getX() - 0x80) & 0xFF80;
        int objCoarse = motionState.x & 0xFF80;
        if ((short) (objCoarse - cameraCoarse) < 0) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CLOUDS);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, motionState.x, motionState.y, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(motionState.x, motionState.y, 4, 0.8f, 0.8f, 1.0f);
        ctx.drawWorldLabel(motionState.x, motionState.y, -1,
                String.format("B3 sub%02X f%d vx%d", spawn.subtype() & 0xFF, mappingFrame, xVelocity),
                DebugColor.CYAN);
    }
}
