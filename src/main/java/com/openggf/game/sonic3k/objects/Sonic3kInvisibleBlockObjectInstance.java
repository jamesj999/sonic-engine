package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Object 0x28 - InvisibleBlock (Sonic 3 &amp; Knuckles).
 * <p>
 * Provides solid collision without visual representation. Its collision volume
 * is available through the dedicated object-debug overlay.
 * <p>
 * ROM: Obj_InvisibleBlock (sonic3k.asm)
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>Upper nibble (bits 7-4): width = ((subtype &amp; 0xF0) + 0x10) / 2 = ((n + 1) * 8)</li>
 *   <li>Lower nibble (bits 3-0): height = ((subtype &amp; 0x0F) + 1) * 8</li>
 * </ul>
 * <p>
 * Collision detection adds 11 to the half-width (ROM: addi.w #$B,d1),
 * and uses height + 1 for the ground half-height (ROM: addq.w #1,d3).
 * Calls SolidObjectFull2 for full solid object collision.
 */
public class Sonic3kInvisibleBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_InvisibleBlock} is installed from the S3K object pointer table at
     * {@code $0001EC18} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:42661).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0001}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0001;
    }


    /** Gray color for debug wireframe rendering. */
    private static final float DEBUG_R = 0.5f;
    private static final float DEBUG_G = 0.5f;
    private static final float DEBUG_B = 0.5f;

    private int halfWidth;
    private int halfHeight;

    public Sonic3kInvisibleBlockObjectInstance(ObjectSpawn spawn) {
        this(spawn, "InvisibleBlock");
    }

    protected Sonic3kInvisibleBlockObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        int subtype = spawn.subtype();
        // ROM: andi.w #$F0,d0 / addi.w #$10,d0 / lsr.w #1,d0
        // Simplifies to: ((upperNibble + 1) * 8)
        this.halfWidth = (((subtype >> 4) & 0xF) + 1) * 8;
        // ROM: andi.w #$F,d1 / addq.w #1,d1 / lsl.w #3,d1
        // = ((lowerNibble + 1) * 8)
        this.halfHeight = ((subtype & 0xF) + 1) * 8;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: addi.w #$B,d1 (width + 11 for collision detection)
        // d2 = height_pixels (air half-height)
        // d3 = height_pixels + 1 (ground half-height)
        int d1 = halfWidth + 11;
        int d2 = halfHeight;
        int d3 = halfHeight + 1;
        return SolidObjectParams.of(d1, d2, d3);
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Every S3K invisible-block variant calls SolidObjectFull2. Its X
        // entry gate rejects with bhi, so relX == width * 2 is still a valid
        // contact (sonic3k.asm:41065-41067,43268-43574).
        return true;
    }

    @Override
    public boolean bypassesOffscreenSolidGate() {
        // Obj_InvisibleBlock and both hurt variants jump directly through
        // SolidObjectFull2. Unlike SolidObjectFull_1P, that entry never tests
        // render_flags bit 7 before falling into SolidObject_cont
        // (sonic3k.asm:41065-41067,43268-43574).
        return true;
    }

    @Override
    public boolean zeroXSpeedStopsOnLeftSideContact() {
        // SolidObjectFull2 reaches SolidObject_cont directly. On the left
        // branch, x_vel >= 0 falls through loc_1E056 and clears both x_vel and
        // ground_vel; only a negative velocity is treated as moving away
        // (sonic3k.asm:41468-41483). In particular, x_vel == 0 must still
        // discard residual ground_vel before applying the side separation.
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // The normal, horizontal-hurt, and vertical-hurt routines all finish
        // with the same coarse Sprite_OnScreen_Test-style range check before
        // returning (loc_1EC6C/loc_1F45E/loc_1F606). Clearing the respawn bit
        // here is important as well as freeing the SST slot: Load_Sprites may
        // then reconsider the placement if the camera backs up.
        if (!isInRangeAt(getX())) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity,
                               SolidContact contact, int frameCounter) {
        // No special behavior - standard collision handled by ObjectManager.
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.w #$200,priority(a0) -> bucket 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj_InvisibleBlock and its hurt variants are invisible.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int centerX = spawn.x();
        int centerY = spawn.y();

        ctx.drawRect(centerX, centerY, halfWidth, halfHeight,
                DEBUG_R, DEBUG_G, DEBUG_B);

        // Draw center crosshair
        int crossHalf = Math.min(halfWidth, halfHeight) / 2;
        if (crossHalf > 0) {
            ctx.drawCross(centerX, centerY, crossHalf,
                    DEBUG_R, DEBUG_G, DEBUG_B);
        }
    }
}
