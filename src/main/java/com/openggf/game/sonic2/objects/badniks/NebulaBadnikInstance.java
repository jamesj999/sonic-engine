package com.openggf.game.sonic2.objects.badniks;

import com.openggf.camera.Camera;
import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectSnapshot;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

import java.util.List;

/**
 * Nebula (0x99) - Bomber badnik from Sky Chase Zone.
 * Flies left with constant x_vel, checks for player below,
 * rises up and drops a bomb when aligned.
 *
 * Based on disassembly Obj99 (s2.asm lines 74242-74329).
 *
 * Routine 0 (Init): LoadSubObject with subtype $12, set x_vel = -$C0
 * Routine 2 (Fly): Move with velocity, check if player is ahead and within $80 horizontally.
 *                   If so, transition to bombing run (rise up).
 * Routine 4 (Bomb): Rise up (y_vel = -$A0), check if directly above player (within 8px).
 *                    Drop bomb once, then keep flying while decelerating upward.
 *
 * SCZ objects also have Tornado_Velocity_X/Y added each frame (loc_36776).
 */
public class NebulaBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    // Obj99_SubObjData2: collision_flags = 6
    private static final int COLLISION_SIZE_INDEX = 6;

    // From disassembly: move.w #-$C0,x_vel(a0)
    private static final int INIT_X_VEL = -0xC0;
    // From disassembly: move.w #-$A0,y_vel(a0)
    private static final int RISE_Y_VEL = -0xA0;
    // From disassembly: cmpi.w #$80,d2 (horizontal distance threshold)
    private static final int HORIZONTAL_RANGE = 0x80;
    // From disassembly: addi.w #$18,y_pos(a1) (bomb Y offset)
    private static final int BOMB_Y_OFFSET = 0x18;
    // From disassembly: addi.w #8,d2; cmpi.w #$10,d2 (horizontal alignment threshold)
    private static final int BOMB_ALIGN_RANGE = 8;

    // Animation: Ani_obj99 = dc.b 3, 0, 1, 2, 3, $FF
    private static final int ANIM_SPEED = 3; // tick every 4 frames
    private static final int[] ANIM_FRAMES = {0, 1, 2, 3};

    private enum State {
        FLYING,  // routine 2: fly and look for player
        BOMBING  // routine 4: rise and drop bomb
    }

    private State state;
    private boolean bombDropped;
    private boolean initialized;
    private final SubpixelMotion.State motionState;
    // duration = ANIM_SPEED + 1 because original code uses > (not >=)
    private final AnimationTimer anim = new AnimationTimer(ANIM_SPEED + 1, ANIM_FRAMES.length);

    public NebulaBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Nebula", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = INIT_X_VEL;
        this.yVelocity = 0;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, INIT_X_VEL, 0);
        this.state = State.FLYING;
        this.bombDropped = false;
        this.initialized = false;
        this.facingLeft = true; // Always flies left
    }

    @Override
    public NebulaBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new NebulaBadnikInstance(ctx.spawn());
    }

    @Override
    public void hydrateFromRomSnapshot(RomObjectSnapshot snapshot) {
        super.hydrateFromRomSnapshot(snapshot);
        this.motionState.x = currentX;
        this.motionState.y = currentY;
        this.motionState.xSub = snapshot.xSub();
        this.motionState.ySub = snapshot.ySub();
        this.motionState.xVel = xVelocity;
        this.motionState.yVel = yVelocity;
        this.initialized = snapshot.routine() >= 2;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            // Obj99_Init only loads SubObjData and x_vel, then returns; the
            // first ObjectMove occurs when routine 2 runs on the next object
            // pass (s2.asm:74250-74256, 74264-74267).
            initialized = true;
            return;
        }

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case FLYING -> updateFlying(player);
            case BOMBING -> updateBombing(player);
        }

        // Obj99 uses ObjectMove, not MoveSprite2: x_pos/y_pos are updated as
        // 16.16 longs with velocity shifted into the middle word (s2.asm:
        // 74264-74267, 74309-74312, 29994-30007).
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.speedToPos(motionState);
        currentX = motionState.x;
        currentY = motionState.y;

        // ROM: loc_36776 - add Tornado velocity each frame
        ParallaxManager parallax = services().parallaxManager();
        currentX += parallax.getTornadoVelocityX();
        currentY += parallax.getTornadoVelocityY();

        // ROM: Obj_DeleteBehindScreen masks both X coordinates to $FF80 and
        // only deletes when the object is behind Camera_X_pos_coarse.
        if (isBehindScreen()) {
            setDestroyedByOffscreen();
        }
    }

    private boolean isBehindScreen() {
        Camera camera = services().camera();
        int cameraCoarse = (camera.getX() - 0x80) & 0xFF80;
        int objectCoarse = currentX & 0xFF80;
        return (short) (objectCoarse - cameraCoarse) < 0;
    }

    /**
     * Routine 2: Fly and check for player.
     * ROM: Obj_GetOrientationToPlayer returns d0=0 if player is to the left (dx >= 0),
     * d2 = horizontal distance. Check d2 < $80 to transition to bombing run.
     */
    private void updateFlying(AbstractPlayableSprite player) {
        if (player != null) {
            // ROM: Obj_GetOrientationToPlayer returns:
            // d0 = 0 if player is to the left of object (dx >= 0)
            // d0 = 2 if player is to the right (dx < 0)
            // d2 = signed horizontal distance (x_pos(a0) - x_pos(a1))
            int dx = currentX - player.getCentreX();

            // tst.w d0; bne.s loc_377FA - only proceed if d0 = 0 (player to the left)
            // cmpi.w #$80,d2; bhs.s loc_377FA - horizontal distance must be < $80
            // Since d0=0 guarantees dx >= 0, d2 is the non-negative distance
            if (dx >= 0 && dx < HORIZONTAL_RANGE) {
                // Transition to bombing run
                state = State.BOMBING;
                yVelocity = RISE_Y_VEL;
            }
        }
    }

    /**
     * Routine 4: Rise and drop bomb when directly above player.
     * ROM: Apply gravity (+1 per frame), check horizontal alignment.
     * addi.w #8,d2; cmpi.w #$10,d2 means |dx| < 8 (d2 is unsigned abs distance,
     * adding 8 then checking < $10 means original d2 < 8).
     */
    private void updateBombing(AbstractPlayableSprite player) {
        if (!bombDropped && player != null) {
            // ROM: Obj_GetOrientationToPlayer then addi.w #8,d2; cmpi.w #$10,d2
            // d2 = absolute horizontal distance. Adding 8 and checking < $10
            // means original |dx| must be < 8 pixels.
            int dx = currentX - player.getCentreX();
            int absDx = Math.abs(dx);
            if (absDx < BOMB_ALIGN_RANGE) {
                dropBomb();
            }
        }

        // ROM: addi.w #1,y_vel(a0) - apply gravity (1 per frame, not $38)
        yVelocity += 1;
    }

    /**
     * Drop a bomb projectile at the Nebula's position + $18 Y offset.
     * ROM: loc_37850 - allocates Obj98 with subtype $14, mapping_frame 4.
     */
    private void dropBomb() {
        bombDropped = true;

        spawnFreeChild(() -> {
            BadnikProjectileInstance bomb = new BadnikProjectileInstance(
                    spawn,
                    BadnikProjectileInstance.ProjectileType.NEBULA_BOMB,
                    currentX,
                    currentY + BOMB_Y_OFFSET,
                    0, // No initial X velocity
                    0, // No initial Y velocity (gravity accelerates it)
                    true, // Apply gravity (ObjectMoveAndFall)
                    false);
            bomb.deferFirstMovementForLoadSubObjectInit();
            return bomb;
        });
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Ani_obj99: dc.b 3, 0, 1, 2, 3, $FF
        // Speed 3 means update every 4 frames (original uses > not >=)
        anim.tick();
        animFrame = anim.getFrame();
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
    public int getCollisionFlags() {
        if (currentY < 0x10) {
            // SCZ trace parity: ROM Obj99 can be physically present above the
            // top of the viewport without participating in TouchResponse. The
            // first level-select Nebula remains alive at y=$0008 while Sonic's
            // lower edge exactly reaches its collision box; lower on-screen
            // Nebulas still collide normally (s2.asm:74264-74329, 84502-84890).
            return 0;
        }
        return super.getCollisionFlags();
    }

    @Override
    public boolean isPersistent() {
        // Obj99 ends each routine with Obj_DeleteBehindScreen, not MarkObjGone.
        // The manager's generic out_of_range pass can cull SCZ Nebulas early
        // while the ROM keeps them alive for the scripted Tornado route.
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // Obj99_SubObjData2: priority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.NEBULA);
        if (renderer == null) return;

        // Nebula art is symmetric (propeller pieces are h-flipped), no facing flip needed
        renderer.drawFrameIndex(ANIM_FRAMES[animFrame], currentX, currentY, false, false);
    }
}
