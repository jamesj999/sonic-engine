package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Balkiry (0xAC) - Jet badnik from Sky Chase Zone.
 * Flies at constant speed across screen. Spawns a jet exhaust child (0x9C).
 * Position is adjusted each frame by Tornado velocity (loc_36776).
 *
 * Based on disassembly ObjAC (s2.asm lines 76732-76771).
 *
 * Init:
 *   - LoadSubObject (mappings, art tile, render flags, priority=4, width=$20, collision=8)
 *   - mapping_frame = 1
 *   - x_vel = -$300 (normal) or -$500 (if y_flip set in subtype)
 *   - Stores Ani_obj9C pointer for jet animation
 *   - Spawns child Obj9C (BalkiryJet) via loc_37ABE
 *
 * Main:
 *   - ObjectMove (apply x_vel/y_vel to position)
 *   - loc_36776 (add Tornado_Velocity_X/Y to position)
 *   - Obj_DeleteBehindScreen
 */
public class BalkiryBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    // From subObjData: collision = 8
    private static final int COLLISION_SIZE_INDEX = 0x08;

    // From disassembly: move.w #-$300,x_vel(a0)
    private static final int X_VEL_NORMAL = -0x300;
    // From disassembly: move.w #-$500,x_vel(a0) (when y_flip set)
    private static final int X_VEL_FAST = -0x500;

    // Subpixel position accumulators (16.16 fixed point for ObjectMove)
    private int subPixelX;
    private int subPixelY;
    private boolean initialized;

    public BalkiryBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Balkiry", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.subPixelX = 0;
        this.subPixelY = 0;

        // ROM: bclr #render_flags.y_flip,render_flags(a0) / beq.s + / move.w #-$500,x_vel(a0)
        // y_flip bit in render_flags indicates fast variant
        boolean yFlip = (spawn.renderFlags() & 0x02) != 0;
        this.xVelocity = yFlip ? X_VEL_FAST : X_VEL_NORMAL;
        this.yVelocity = 0;

        // Balkiry always faces left (flies left across screen)
        this.facingLeft = true;

        // ROM: move.b #1,mapping_frame(a0)
        this.animFrame = 1;

        // ROM: ObjAC_Init falls through to loc_37ABE (jet spawn) in the same frame.
        spawnJetChild();
    }

    @Override
    public BalkiryBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BalkiryBadnikInstance(ctx.spawn());
    }

    @Override
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        super.hydrateFromRomSnapshot(snapshot);
        this.subPixelX = snapshot.xSub() & 0xFF;
        this.subPixelY = snapshot.ySub() & 0xFF;
        this.initialized = snapshot.routine() >= 2;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            initialized = true;
            return;
        }
        // ROM: JmpTo26_ObjectMove - apply velocity to position (subpixel precision)
        // x_pos += x_vel (as 16.16 fixed point)
        subPixelX += xVelocity;
        currentX += subPixelX >> 8;
        subPixelX &= 0xFF;

        subPixelY += yVelocity;
        currentY += subPixelY >> 8;
        subPixelY &= 0xFF;

        // ROM: loc_36776 - add Tornado_Velocity_X/Y to position
        ParallaxManager pm = services().parallaxManager();
        currentX += pm.getTornadoVelocityX();
        currentY += pm.getTornadoVelocityY();
    }

    /**
     * Spawn the jet exhaust child object (Obj9C / BalkiryJet).
     * ROM: loc_37ABE - AllocateObjectAfterCurrent, set up child with:
     *   id = ObjID_BalkiryJet (0x9C)
     *   mapping_frame = 6
     *   subtype = $1A
     *   objoff_2C = parent address
     *   x_pos/y_pos = parent position
     *   objoff_2E = animation pointer (Ani_obj9C)
     *   objoff_32 = parent id
     */
    private void spawnJetChild() {
        // Create jet child spawn - subtype $1A selects Obj9C_SubObjData
        final ObjectSpawn jetSpawn = new ObjectSpawn(
                currentX, currentY,
                Sonic2ObjectIds.BALKIRY_JET,
                0x1A, // subtype for jet exhaust
                spawn.renderFlags(),
                false,
                spawn.rawYWord());

        spawnFreeChild(() -> new BalkiryJetObjectInstance(jetSpawn, this));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Balkiry has no animation state machine - always displays mapping_frame 1
        animFrame = 1;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.standardEnemy();
    }

    @Override
    public int getPriorityBucket() {
        // From subObjData: priority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BALKIRY);
        if (renderer == null) return;

        // Balkiry art faces left by default (flies left), hFlip when facing right
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }
}
