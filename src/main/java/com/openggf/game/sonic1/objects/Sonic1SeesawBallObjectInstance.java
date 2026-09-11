package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.GenericFieldCapturer;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Seesaw spikeball child object (part of Object 0x5E) from Star Light Zone.
 * <p>
 * A spikeball that sits on one end of the seesaw. When the seesaw tilts,
 * the ball launches into the air and falls back. When the ball lands,
 * it can launch a standing player with the spring sound effect.
 * <p>
 * The ball has touch collision (obColType = $8B) that can hurt the player.
 * <p>
 * Disassembly reference: docs/s1disasm/_incObj/5E Seesaw.asm (See_Spikeball through See_SpikeFall)
 */
public class Sonic1SeesawBallObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    // Ball Y offsets per parent seesaw frame (See_Speeds)
    // dc.w -8, -$1C, -$2F, -$1C, -8
    // Index: frame + (leftSide ? 2 : 0)
    private static final int[] BALL_Y_OFFSETS = {-8, -0x1C, -0x2F, -0x1C, -8};

    // Launch velocities (from loc_117FC through loc_11822)
    // Angle delta = 1: move.w #-$818,d1 / move.w #-$114,d2
    private static final int LAUNCH_Y_SMALL = -0x818;
    private static final int LAUNCH_X_SMALL = -0x114;

    // Angle delta = 2: move.w #-$AF0,d1 / move.w #-$CC,d2
    private static final int LAUNCH_Y_MEDIUM = -0xAF0;
    private static final int LAUNCH_X_MEDIUM = -0xCC;

    // Angle delta = 2 AND see_speed >= $A00: move.w #-$E00,d1 / move.w #-$A0,d2
    private static final int LAUNCH_Y_HEAVY = -0xE00;
    private static final int LAUNCH_X_HEAVY = -0xA0;

    // Threshold for heavy landing: cmpi.w #$A00,objoff_38(a1)
    private static final int HEAVY_LANDING_THRESHOLD = 0xA00;

    // From disassembly: move.b #$8B,obColType(a0) — enemy type $8, size $B
    private static final int COLLISION_FLAGS = 0x8B;

    // From disassembly: move.b #$C,obActWid(a0)
    private static final int WIDTH_PIXELS = 0x0C;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Standard gravity (ObjectFall: addi.w #$38,obVelY(a0))
    private static final int GRAVITY = 0x38;

    // Ball X offset from seesaw center: addi.w #$28,obX(a0)
    private static final int BALL_X_OFFSET = 0x28;

    // Ball states matching disassembly routines
    private enum State {
        RESTING,  // See_MoveSpike (routine 8) - sitting on seesaw
        FLYING    // See_SpikeFall (routine $A) - in the air
    }

    private State state = State.RESTING;

    // Parent seesaw reference (see_parent = objoff_3C)
    private final Sonic1SeesawObjectInstance parent;

    // Position tracking - 16.16 fixed-point
    private int xPos;
    private int yPos;
    private int xVel;
    private int yVel;

    // Original reference positions (see_origX = objoff_30, see_origY = objoff_34)
    // Un-finaled for rewind: the ball's getSpawn() reports its RUNTIME position, not the
    // seesaw origin, so the generic field capturer reapplies the captured origX/origY
    // values before graph recreate matches the restored parent.
    private int origX;
    private int origY;

    // Stored angle state (see_frame = objoff_3A)
    private int storedFrame;

    // Palette animation: ROM uses obFrame to toggle between red/silver ball
    // move.b #1,obFrame(a0) — initialized to silver (frame 1)
    private int displayFrame = 1;

    Sonic1SeesawBallObjectInstance() {
        super(new ObjectSpawn(0, 0, 0x5E, 0, 0, false, 0), "SeesawBall");
        this.parent = null;
    }

    public Sonic1SeesawBallObjectInstance(
            Sonic1SeesawObjectInstance parent,
            int seesawX, int seesawY,
            boolean flipped
    ) {
        super(new ObjectSpawn(seesawX, seesawY, 0x5E, 0, 0, false, 0), "SeesawBall");

        this.parent = parent;

        // From See_Spikeball:
        // move.w obX(a0),see_origX(a0)
        this.origX = seesawX;
        // move.w obY(a0),see_origY(a0)
        this.origY = seesawY;

        // addi.w #$28,obX(a0)
        int initialX = seesawX + BALL_X_OFFSET;

        if (flipped) {
            // subi.w #$50,obX(a0) — move to the other side
            initialX = seesawX + BALL_X_OFFSET - 0x50;
            // move.b #2,see_frame(a0)
            storedFrame = 2;
        } else {
            storedFrame = 0;
        }

        this.xPos = initialX << 16;
        this.yPos = seesawY << 16;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        seedCapturedScalars(ctx);
        Sonic1SeesawObjectInstance restoredParent =
                findParentForRewind(ctx, origX, origY);
        if (restoredParent == null) {
            return null;
        }
        Sonic1SeesawBallObjectInstance restored =
                new Sonic1SeesawBallObjectInstance(restoredParent, origX, origY, storedFrame == 2);
        restoredParent.adoptBallForRewind(restored);
        return restored;
    }

    private void seedCapturedScalars(RewindRecreateContext ctx) {
        if (ctx == null || ctx.state() == null || ctx.state().compactGenericState() == null) {
            return;
        }
        GenericFieldCapturer.restoreObjectSubclassScalarsCompact(this, ctx.state().compactGenericState());
    }

    private static Sonic1SeesawObjectInstance findParentForRewind(
            RewindRecreateContext ctx,
            int capturedOrigX,
            int capturedOrigY) {
        if (ctx == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof Sonic1SeesawObjectInstance seesaw) || seesaw.isDestroyed()) {
                continue;
            }
            if (seesaw.getSpawn().subtype() != 0 || seesaw.hasLiveBallForRewind()) {
                continue;
            }
            if (seesaw.getSpawn().x() == capturedOrigX
                    && seesaw.getSpawn().y() == capturedOrigY) {
                return seesaw;
            }
        }
        return null;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(xPos >> 16, yPos >> 16);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case RESTING -> updateResting();
            case FLYING -> updateFlying();
        }
    }

    /**
     * Ball is resting on seesaw - check if parent angle changed.
     * From See_MoveSpike (routine 8):
     * <pre>
     *   movea.l see_parent(a0),a1
     *   moveq   #0,d0
     *   move.b  see_frame(a0),d0   ; ball's stored frame
     *   sub.b   see_frame(a1),d0   ; parent's target frame
     *   beq.s   loc_1183E          ; no change -> position on seesaw
     *   bcc.s   loc_117FC          ; ball frame > parent frame
     *   neg.b   d0                 ; make positive
     * </pre>
     */
    private void updateResting() {
        int parentFrame = parent.getTargetFrame();
        int angleDelta = storedFrame - parentFrame;

        if (angleDelta == 0) {
            // No change - position ball on seesaw surface
            positionOnSeesaw();
            return;
        }

        // Angle changed - calculate launch velocity
        int absDelta = Math.abs(angleDelta);

        int launchY, launchX;
        if (absDelta == 1) {
            // cmpi.b #1,d0 / beq.s loc_11822
            launchY = LAUNCH_Y_SMALL;
            launchX = LAUNCH_X_SMALL;
        } else {
            // cmpi.w #$A00,objoff_38(a1) / blt.s loc_11822
            int parentStoredVel = parent.getStoredPlayerYVel();
            if (parentStoredVel >= HEAVY_LANDING_THRESHOLD) {
                launchY = LAUNCH_Y_HEAVY;
                launchX = LAUNCH_X_HEAVY;
            } else {
                launchY = LAUNCH_Y_MEDIUM;
                launchX = LAUNCH_X_MEDIUM;
            }
        }

        // move.w d1,obVelY(a0)
        yVel = launchY;
        // move.w d2,obVelX(a0)
        xVel = launchX;

        // Negate X velocity if ball is on left side of seesaw
        // sub.w see_origX(a0),d0 / bcc.s loc_11838 / neg.w obVelX(a0)
        int currentX = xPos >> 16;
        if (currentX < origX) {
            xVel = -xVel;
        }

        // addq.b #2,obRoutine(a0) — enter flying state
        state = State.FLYING;

        // bra.s See_SpikeFall (docs/s1disasm/_incObj/5E SLZ Seesaw.asm:168-169):
        // See_MoveSpike falls straight through to See_SpikeFall on the SAME
        // object tick, so the ball already applies its launch ObjectFall step(s)
        // (and the ascending double-gravity past the apex) on the launch frame.
        // Returning here instead left the ball stationary for one frame, so the
        // whole flight — and the landing that springs the standing player — lagged
        // ROM by ~2 ObjectFall steps (SLZ3 f814: ROM springs at f814, engine f816).
        updateFlying();
    }

    /**
     * Position ball on seesaw surface based on parent's visual mapping frame.
     * From loc_1183E:
     * <pre>
     *   lea     (See_Speeds).l,a2
     *   moveq   #0,d0
     *   move.b  obFrame(a1),d0     ; parent's mapping frame
     *   move.w  #$28,d2            ; X offset
     *   move.w  obX(a0),d1
     *   sub.w   see_origX(a0),d1
     *   bcc.s   loc_1185C          ; ball is on right side
     *   neg.w   d2                 ; ball on left: negate X offset
     *   addq.w  #2,d0              ; and add 2 to Y offset index
     *   loc_1185C:
     *   add.w   d0,d0              ; word index
     *   move.w  see_origY(a0),d1
     *   add.w   (a2,d0.w),d1       ; Y = origY + See_Speeds[index]
     *   move.w  d1,obY(a0)
     *   add.w   see_origX(a0),d2
     *   move.w  d2,obX(a0)
     * </pre>
     */
    private void positionOnSeesaw() {
        int parentMappingFrame = parent.getMappingFrame();

        int offsetIndex = parentMappingFrame;
        int xOffset = BALL_X_OFFSET;

        int currentX = xPos >> 16;
        if (currentX < origX) {
            xOffset = -BALL_X_OFFSET;
            offsetIndex += 2;
        }

        // Clamp to array bounds
        if (offsetIndex < 0) offsetIndex = 0;
        if (offsetIndex >= BALL_Y_OFFSETS.length) offsetIndex = BALL_Y_OFFSETS.length - 1;

        // Set positions
        yPos = (origY + BALL_Y_OFFSETS[offsetIndex]) << 16;
        xPos = (origX + xOffset) << 16;
    }

    /**
     * Ball is flying - apply gravity and check for landing.
     * From See_SpikeFall (routine $A):
     * <pre>
     *   tst.w   obVelY(a0)        ; falling down?
     *   bpl.s   loc_1189A         ; if yes, check landing
     *   bsr.w   ObjectFall        ; apply gravity + movement
     *   move.w  see_origY(a0),d0
     *   subi.w  #$2F,d0           ; upper bound
     *   cmp.w   obY(a0),d0
     *   bgt.s   locret_11898      ; above upper bound -> return (1 call)
     *   bsr.w   ObjectFall        ; below upper bound -> 2nd gravity call
     * </pre>
     */
    private void updateFlying() {
        if (yVel < 0) {
            // Ascending
            objectFall();

            // Double gravity when below upper bound
            int upperBound = origY - 0x2F;
            int currentY = yPos >> 16;
            if (currentY >= upperBound) {
                objectFall();
            }
        } else {
            // Descending (loc_1189A)
            objectFall();
            checkLanding();
        }
    }

    /**
     * ObjectFall: apply velocity to position and add gravity to Y velocity.
     * ROM ObjectFall pattern:
     *   ext.l d0 / asl.l #8,d0 / add.l d0,obX(a0) (for both X and Y)
     *   addi.w #$38,obVelY(a0)
     */
    private void objectFall() {
        // Apply velocity (8.8 to 16.16)
        xPos += (xVel << 8);
        yPos += (yVel << 8);
        // Apply gravity
        yVel += GRAVITY;
    }

    /**
     * Check if ball has landed on seesaw surface.
     * From loc_1189A:
     * <pre>
     *   bsr.w   ObjectFall
     *   movea.l see_parent(a0),a1
     *   lea     (See_Speeds).l,a2
     *   moveq   #0,d0
     *   move.b  obFrame(a1),d0
     *   move.w  obX(a0),d1
     *   sub.w   see_origX(a0),d1
     *   bcc.s   loc_118BA
     *   addq.w  #2,d0
     *   loc_118BA:
     *   add.w   d0,d0
     *   move.w  see_origY(a0),d1
     *   add.w   (a2,d0.w),d1
     *   cmp.w   obY(a0),d1
     *   bgt.s   locret_11938       ; haven't reached surface yet
     * </pre>
     */
    private void checkLanding() {
        int parentMappingFrame = parent.getMappingFrame();

        int offsetIndex = parentMappingFrame;
        int currentX = xPos >> 16;
        if (currentX < origX) {
            offsetIndex += 2;
        }

        if (offsetIndex < 0) offsetIndex = 0;
        if (offsetIndex >= BALL_Y_OFFSETS.length) offsetIndex = BALL_Y_OFFSETS.length - 1;

        int landingY = origY + BALL_Y_OFFSETS[offsetIndex];
        int currentY = yPos >> 16;

        if (currentY < landingY) {
            return; // Haven't reached surface
        }

        // Ball has landed
        // Snap to landing Y
        yPos = landingY << 16;

        // Determine which end we landed on (See_Spring)
        // moveq #2,d1 / tst.w obVelX(a0) / bmi.s See_Spring / moveq #0,d1
        int landingAngle = (xVel < 0) ? 2 : 0;

        // move.b d1,objoff_3A(a1) — set parent's target frame
        parent.setTargetFrame(landingAngle);
        // move.b d1,see_frame(a0) — update our stored frame
        storedFrame = landingAngle;

        // cmp.b obFrame(a1),d1 / beq.s loc_1192C
        // Only launch player if landing causes visual change
        int oldMappingFrame = parent.getMappingFrame();
        if (landingAngle != oldMappingFrame) {
            // bclr #3,obStatus(a1) / beq.s loc_1192C
            // Check if player is standing on seesaw (obStatus bit 3)
            if (parent.isPlayerStanding()) {
                launchStandingPlayer();
            }
        }

        // clr.w obVelX(a0) / clr.w obVelY(a0)
        xVel = 0;
        yVel = 0;
        // subq.b #2,obRoutine(a0) — return to resting state
        state = State.RESTING;
    }

    /**
     * Launch player standing on the seesaw.
     * From See_Spring (disassembly lines 250-259):
     * <pre>
     *   clr.b   ob2ndRout(a1)
     *   move.b  #2,obRoutine(a1)
     *   lea     (v_player).w,a2
     *   move.w  obVelY(a0),obVelY(a2)
     *   neg.w   obVelY(a2)              ; launch player with inverse ball Y vel
     *   bset    #1,obStatus(a2)         ; set airborne
     *   bclr    #3,obStatus(a2)         ; clear standing-on-object
     *   clr.b   objoff_3C(a2)           ; clear object reference
     *   move.b  #id_Spring,obAnim(a2)   ; spring animation ($10)
     *   move.b  #2,obRoutine(a2)
     *   move.w  #sfx_Spring,d0
     *   jsr     (QueueSound2).l
     * </pre>
     */
    private void launchStandingPlayer() {
        AbstractPlayableSprite player = parent.getStandingPlayer();
        if (player == null) {
            return;
        }

        // move.w obVelY(a0),obVelY(a2) / neg.w obVelY(a2)
        player.setYSpeed((short) -yVel);

        // bset #1,obStatus(a2) — set airborne
        player.setAir(true);

        // bclr #3,obStatus(a2) — clear standing-on-object
        player.setOnObject(false);

        // clr.b objoff_3C(a2) — clear jumping flag
        player.setJumping(false);

        // move.b #id_Spring,obAnim(a2) — spring animation
        player.setAnimationId(Sonic1AnimationIds.SPRING);

        // Clear parent's standing bit
        parent.clearPlayerStanding();

        // move.w #sfx_Spring,d0 / jsr (QueueSound2).l
        try {
            services().playSfx(Sonic1Sfx.SPRING.id);
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    // ---- TouchResponseProvider ----

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Position ----

    @Override
    public int getX() {
        return (xPos >> 16) - WIDTH_PIXELS;
    }

    @Override
    public int getY() {
        return (yPos >> 16) - 0x0C;
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_SEESAW_BALL);
        if (renderer == null) return;

        // Frame 1 (silver) is the default; original ROM uses obFrame=1 set in See_Spikeball
        renderer.drawFrameIndex(displayFrame, xPos >> 16, yPos >> 16, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // Ball persists as long as parent does
        return !isDestroyed() && !parent.isDestroyed();
    }
}
