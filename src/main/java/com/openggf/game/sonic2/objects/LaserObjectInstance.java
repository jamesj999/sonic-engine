package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AudioConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xB9 - Laser from WFZ that shoots down the Tornado.
 * <p>
 * A fast horizontal laser beam placed in WFZ level layout. The laser waits
 * at its spawn position until it scrolls on screen, then fires leftward
 * at high speed (-0x1000 subpixels/frame = -16 pixels/frame) while playing
 * the SndID_LargeLaser (0xEF) sound effect.
 * <p>
 * Has no collision_flags (0) so it does not interact with the player through
 * the touch response system. The laser is a visual/narrative element in the
 * WFZ scripted sequence where the Wing Fortress fires at the Tornado.
 * <p>
 * State machine (routine field):
 * <ul>
 *   <li>Routine 0: Init - LoadSubObject sets up mappings/art/render/priority/width/collision</li>
 *   <li>Routine 2: Wait - Waits until on screen, then transitions to firing state</li>
 *   <li>Routine 4: Fire - Moves leftward via ObjectMove, deletes when past camera</li>
 * </ul>
 * <p>
 * Subtypes: 0x76 (SubObjData index into SubObjData_Index table).
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 79779-79829 (ObjB9)
 * <p>
 * SubObjData: mappings=ObjB9_MapUnc_3BB18, art_tile=make_art_tile(ArtTile_ArtNem_WfzHrzntlLazer,2,1),
 * render_flags=level_fg, priority=1, width=$60, collision=0.
 */
public class LaserObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    /**
     * Horizontal velocity when firing.
     * From disassembly: move.w #-$1000,x_vel(a0)
     * In 8.8 fixed-point: -0x1000 = -16 pixels per frame.
     */
    private static final int X_VELOCITY = -0x1000;

    /**
     * Deletion margin: laser is deleted when x_pos < Camera_X_pos - 0x40.
     * From disassembly: subi.w #$40,d1 / cmp.w d1,d0 / blt.w JmpTo65_DeleteObject
     */
    private static final int DELETE_MARGIN = 0x40;
    private static final int ON_SCREEN_HALF_WIDTH = 0x60;
    private static final int ON_SCREEN_HALF_HEIGHT = 0x20;

    /** Current state: 0=init, 2=waiting for on-screen, 4=firing */
    private int routine;

    private int currentX;
    private int currentY;

    /** 16.8 fixed-point fractional X accumulator for sub-pixel movement */
    private int xPosFrac;

    /** Whether the laser sound has been played (fires once on transition to routine 4) */
    private boolean soundPlayed;

    public LaserObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Laser");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xPosFrac = 0;
        this.soundPlayed = false;
        // Routine 0 (Init) is handled implicitly: LoadSubObject just sets up rendering
        // data and advances routine to 2. We skip straight to routine 2 since the engine
        // handles art setup separately.
        this.routine = 2;
    }

    @Override
    public LaserObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new LaserObjectInstance(ctx.spawn());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority=1
        return RenderPriority.clamp(1);
    }

    @Override
    public boolean isHighPriority() {
        // ROM: make_art_tile(ArtTile_ArtNem_WfzHrzntlLazer,2,1)
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        switch (routine) {
            case 2 -> updateWaitForOnScreen();
            case 4 -> updateFiring();
            default -> { /* should not occur */ }
        }

        // ROM: loc_3BAF8 - shared deletion check after every routine
        // move.w x_pos(a0),d0
        // move.w (Camera_X_pos).w,d1
        // subi.w #$40,d1
        // cmp.w d1,d0
        // blt.w JmpTo65_DeleteObject
        // jmpto JmpTo45_DisplaySprite
        Camera camera = services().camera();
        int deleteThreshold = camera.getX() - DELETE_MARGIN;
        if ((short) currentX < (short) deleteThreshold) {
            setDestroyed(true);
        }
    }

    /**
     * Routine 2: Wait until the laser scrolls on screen.
     * ROM: loc_3BAD2
     * _btst #render_flags.on_screen,render_flags(a0)
     * _bne.s + (branch if on screen)
     * bra.w loc_3BAF8 (skip to delete check if off screen)
     *
     * When on screen: advance to routine 4, set x_vel, play sound.
     */
    private void updateWaitForOnScreen() {
        if (!isWithinSolidContactBounds()) {
            return;
        }
        // ROM: addq.b #2,routine(a0) => routine 4
        routine = 4;

        // ROM: move.w #-$1000,x_vel(a0)
        // (velocity is applied in updateFiring via ObjectMove)

        // ROM: moveq #signextendB(SndID_LargeLaser),d0
        //      jsrto JmpTo12_PlaySound
        if (!soundPlayed) {
            services().playSfx(Sonic2AudioConstants.SFX_LARGE_LASER);
            soundPlayed = true;
        }
    }

    /**
     * Routine 4: Laser is firing - move leftward.
     * ROM: loc_3BAF0
     * jsrto JmpTo26_ObjectMove  (applies x_vel to x_pos in subpixels)
     * bra.w loc_3BAF8
     */
    private void updateFiring() {
        // ObjectMove: x_pos += x_vel (8.8 fixed-point)
        xPosFrac += X_VELOCITY;
        currentX += xPosFrac >> 8;
        xPosFrac &= 0xFF;
        // Subpixel wrap: X_VELOCITY is -0x1000 so xPosFrac always stays 0,
        // and currentX decreases by exactly 16 pixels per frame.
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_LASER);
        if (renderer == null) return;

        // Single mapping frame (index 0), no flip
        renderer.drawFrameIndex(0, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw center cross at object position
        ctx.drawCross(currentX, currentY, 4, 1.0f, 0.2f, 0.2f);

        // Show object info label
        String state = switch (routine) {
            case 2 -> "WAIT";
            case 4 -> "FIRE";
            default -> "?";
        };
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("B9 Laser [%s] x=%d", state, currentX),
                DebugColor.RED);

        // Draw width extent lines (width_pixels = $60 = 96, so +/- 96 from center)
        // Actually the mapping spans -$48 to $48 (72+72=144px), but width_pixels=96
        // is used for the on-screen check culling, not rendering extent.
        ctx.drawCross(currentX - 0x48, currentY, 2, 1.0f, 0.5f, 0.5f);
        ctx.drawCross(currentX + 0x48, currentY, 2, 1.0f, 0.5f, 0.5f);
    }
}
