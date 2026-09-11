package com.openggf.game.sonic1.objects.badniks;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.DestructionEffects;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.ArrayList;
import java.util.List;

/**
 * Caterkiller (0x78) - Segmented worm Badnik from Marble Zone and Scrap Brain Zone.
 * <p>
 * The Caterkiller is a multi-part enemy: a head with 3 trailing body segments.
 * The head walks along terrain, pausing periodically, while body segments follow
 * using a ring buffer of Y-deltas recorded by the head. Body segments alternate
 * between having legs (BodySeg2, animated) and not (BodySeg1, static).
 * <p>
 * The head uses a non-standard animation system: instead of the normal S1 animation
 * driver, it uses a direct lookup table (Ani_Cat) indexed by obAngle, advancing by 4
 * each frame. The table values (0-7) index different Y-offset head frames for a
 * bobbing effect.
 * <p>
 * Critically, body segments have obColType = $CB: the $C0 bit means they HURT the
 * player on any contact (even rolling/spinning). Only the head ($0B) can be destroyed
 * by rolling. This is a deliberate ROM behavior.
 * <p>
 * When a body segment is touched, Caterkiller enters "fragment" mode - each segment
 * gets a unique X velocity and bounces around independently.
 * If the head is destroyed normally, body segments delete with it.
 * <p>
 * Based on docs/s1disasm/_incObj/78 Caterkiller.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Cat_Main): Fall until hitting floor, then init art/segments</li>
 *   <li>2 (Cat_Head): Main head behavior with secondary routines:
 *     <ul>
 *       <li>ob2ndRout=0 (.wait): Decrement timer, then start moving</li>
 *       <li>ob2ndRout=2 (loc_16B02): Walk with terrain following, record Y-deltas</li>
 *     </ul>
 *   </li>
 *   <li>$A (Cat_Delete): Cleanup</li>
 *   <li>$C (loc_16CC0): Fragment mode - bounce around after destruction</li>
 * </ul>
 */
public class Sonic1CaterkillerBadnikInstance extends AbstractBadnikInstance
        implements CaterkillerParentState, RewindRecreatable {

    // From disassembly: obColType = $B (enemy, collision size index $B)
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // From disassembly: obHeight = 7, obWidth = 8
    private static final int Y_RADIUS = 7;

    // Walking velocity: move.w #-$C0,obVelX(a0)
    private static final int WALK_VELOCITY = 0xC0;

    // Inertia for body segment trailing: move.w #$40,obInertia(a0)
    private static final int BODY_INERTIA = 0x40;

    // Wait timer initial value: move.b #7,objoff_2A(a0)
    private static final int INITIAL_WAIT_TIMER = 7;

    // Move timer: move.b #$10,objoff_2A(a0) (16 frames)
    private static final int MOVE_TIMER = 0x10;

    // Wait timer between moves: move.b #7,objoff_2A(a0)
    private static final int INTER_MOVE_WAIT = 7;

    // Body segment spacing: moveq #$C,d5
    private static final int SEGMENT_SPACING = 0x0C;

    // Number of body segments: moveq #2,d1 → dbf → 3 iterations
    private static final int BODY_SEGMENT_COUNT = 3;

    // Cat_Main sets obActWid = 8 and obRender bit 4 remains clear, so BuildSprites
    // uses the exact X half-width plus the default assumed 32 px Y band.
    private static final int RENDER_HALF_WIDTH = 8;
    private static final int ASSUMED_RENDER_HALF_HEIGHT = 32;

    // Floor detection thresholds from loc_16B02:
    // cmpi.w #-8,d1 / blt.s .loc_16B70 / cmpi.w #$C,d1 / bge.s .loc_16B70
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Fragment velocities from Cat_FragSpeed: indexed by (obRoutine-2)
    // For head (routine 2): Cat_FragSpeed-2+2 → first entry → -$200
    // But head uses loc_16C96 which reads Cat_FragSpeed-2(pc,d0.w) where d0=obRoutine
    // Head routine=2: Cat_FragSpeed-2+2 = Cat_FragSpeed[0] = -$200
    private static final int HEAD_FRAG_X_VELOCITY = -0x200;

    // Fragment Y velocity: move.w #-$400,obVelY(a0)
    private static final int FRAG_Y_VELOCITY = -0x400;

    // Animation angle increment: addq.b #4,obAngle(a0)
    private static final int ANIM_ANGLE_STEP = 4;

    /**
     * Ani_Cat: Non-standard animation table from docs/s1disasm/_anim/Caterkiller.asm.
     * 128 bytes. Values 0-7 index mapping frames (head Y-offset variants).
     * $FF marks end of half-cycle (resets bit 7 of objoff_2B).
     * Accessed as: Ani_Cat[obAngle & 0x7F] → frame index.
     */
    static final int[] ANI_CAT = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,  // 0x00-0x0F
        1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3,  // 0x10-0x1F
        4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 6, 6, 6, 6, 6,  // 0x20-0x2F
        6, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 0xFF, 7, 7, 0xFF,  // 0x30-0x3F
        7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 6,  // 0x40-0x4F
        6, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4,  // 0x50-0x5F
        4, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1,  // 0x60-0x6F
        1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF, 0, 0, 0xFF  // 0x70-0x7F
    };

    // Secondary routine states (ob2ndRout)
    private static final int STATE_WAIT = 0;
    private static final int STATE_MOVE = 1;

    // Instance state
    private boolean initialized;
    private int secondaryState;
    private int waitTimer;        // objoff_2A
    private int animControl;      // objoff_2B: bit 7 = animating, bit 4 = alternating flag
    private int animAngle;        // obAngle: animation table index
    private int currentDisplayFrame; // Computed mapping frame from last animation update
    private int inertia;          // obInertia: body trailing offset
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int fallVelocity;
    private boolean fragmenting;
    private boolean deleting;
    private boolean renderOnScreen;
    private int bodyDeletionEarliestFrame;

    // Ring buffer for Y-deltas (objoff_2C through objoff_2C+15)
    private final byte[] ringBuffer = new byte[16];

    // Ring buffer write pointer (cat_parent for head = low byte only)
    private int ringBufferWriteIndex;

    // Child body segment objects
    private final List<Sonic1CaterkillerBodyInstance> bodySegments = new ArrayList<>();

    public Sonic1CaterkillerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Caterkiller");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // S1: obStatus bit 0 set = xFlip (facing right in screen terms)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.initialized = false;
        this.secondaryState = STATE_WAIT;
        this.waitTimer = INITIAL_WAIT_TIMER;
        this.animControl = 0;
        this.animAngle = 0;
        this.currentDisplayFrame = 0;
        this.inertia = 0;
        this.fallVelocity = 0;
        this.fragmenting = false;
        this.deleting = false;
        this.renderOnScreen = true;
        this.bodyDeletionEarliestFrame = -1;
        this.ringBufferWriteIndex = 0;
    }

    @Override
    public Sonic1CaterkillerBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic1CaterkillerBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (deleting) {
            setDestroyedByOffscreen();
            return;
        }
        if (fragmenting) {
            updateFragment();
            return;
        }

        if (!initialized) {
            initialize();
            if (!initialized) {
                return; // Still falling — ObjectFall not yet landed
            }
            // ROM fallthrough: Cat_Main does NOT have an rts after init.
            // It falls through directly into Cat_Head, which dispatches to
            // .wait (decrementing the timer on the init frame).
            // Using sequential if-checks (not switch) so that when updateWait()
            // transitions to STATE_MOVE, execution falls through to updateMove(),
            // matching the ROM's .wait → .move → loc_16B02 fallthrough.
        }

        // Cat_Head: check if status bit 7 set (indicating body segment triggered fragmentation)
        // tst.b obStatus(a0) / bmi.w loc_16C96
        // In our implementation, this is handled via the destroyed flag

        // ROM uses jsr Cat_Index2(pc,d1.w) which dispatches to .wait or loc_16B02.
        // .wait can fall through to .move, which falls through to loc_16B02.
        // Use sequential if-checks so state transitions fall through naturally.
        if (secondaryState == STATE_WAIT) {
            updateWait();
        }
        if (secondaryState == STATE_MOVE) {
            updateMove();
        }

        // Cat_ChkGone sets routine $A and returns; Cat_Delete frees the slot on
        // the next object pass (docs/s1disasm/_incObj/78 Badnik - Caterkiller.asm).
        if (!isInRange()) {
            deleting = true;
        }

    }

    /**
     * Routine 0: Cat_Main - Fall under gravity until hitting floor.
     * Then initialize art, collision, and spawn 3 body segments.
     */
    private void initialize() {
        // ObjectFall: apply velocity, then gravity
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = fallVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        fallVelocity = motion.yVel;

        // ObjFloorDist: check floor from feet
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // tst.w d1 / bpl.s locret_16950
        if (!floorResult.foundSurface() || floorResult.distance() >= 0) {
            return; // Still falling
        }

        // Floor found: snap to it
        currentY += floorResult.distance();  // add.w d1,obY(a0)
        fallVelocity = 0;                     // clr.w obVelY(a0)
        initialized = true;                   // addq.b #2,obRoutine(a0)

        // Spawn body segments
        spawnBodySegments();

        // move.b #7,objoff_2A(a0) - initial wait timer
        waitTimer = INITIAL_WAIT_TIMER;
        // clr.b cat_parent(a0) - clear ring buffer write index
        ringBufferWriteIndex = 0;
    }

    /**
     * Spawns 3 body segments behind the head, spaced 12px apart.
     * From Cat_Loop in disassembly.
     * <p>
     * Segments alternate between routine 4 (BodySeg1) and routine 6 (BodySeg2):
     * <pre>
     * move.b d6,obRoutine(a1) ; d6 starts at 4
     * addq.b #2,d6           ; alternate: 4, 6, 8 (but 8 maps to BodySeg1 again)
     * </pre>
     */
    private void spawnBodySegments() {
        ObjectServices svc = tryServices();
        var objectManager = svc != null ? svc.objectManager() : null;
        if (objectManager == null) {
            return;
        }

        int segX = currentX;
        int spacing = SEGMENT_SPACING;
        // ROM: btst #0,obStatus(a0) / beq.s .noflip / neg.w d5
        // Negate spacing when bit 0 is SET (xFlip/facing right)
        if (!facingLeft) {
            spacing = -spacing;
        }

        // d6 starts at 4, increments by 2: 4, 6, 8
        // Routine 4 → BodySeg1, 6 → BodySeg2, 8 → BodySeg1
        boolean[] isAnimated = { false, true, false };

        // Ring buffer indices: d4 starts at 4, increments by 4: 4, 8, 12
        int ringBufIdx = 4;
        CaterkillerParentState parentState = this;

        // ROM: Cat_Loop uses FindNextFreeObj (jsr (FindNextFreeObj).l) to
        // allocate body segment slots sequentially AFTER the head's slot.
        // This preserves slot locality (head=N, body1=N+1, body2=N+2, body3=N+3),
        // keeping child slots out of the global pool and matching the ROM's
        // slot assignment for subsequent objects.
        int prevSlot = getSlotIndex();

        for (int i = 0; i < BODY_SEGMENT_COUNT; i++) {
            segX += spacing;

            final int segXFinal = segX;
            final boolean animated = isAnimated[i];
            final int segmentIndex = i;
            final int ringBufIdxFinal = ringBufIdx;
            final CaterkillerParentState parentStateFinal = parentState;
            final int prevSlotFinal = prevSlot;

            Sonic1CaterkillerBodyInstance body = spawnFreeChild(() -> {
                Sonic1CaterkillerBodyInstance segment = new Sonic1CaterkillerBodyInstance(
                        this, parentStateFinal, segXFinal, currentY, facingLeft,
                        animated, segmentIndex, ringBufIdxFinal);
                // Allocate slot after previous segment (FindNextFreeObj parity)
                ObjectLifetimeOps.assignFindNextFreeChildSlot(objectManager, segment, prevSlotFinal);
                return segment;
            });
            bodySegments.add(body);
            if (body.getSlotIndex() >= 0) {
                prevSlot = body.getSlotIndex();
            }
            parentState = body;

            ringBufIdx += 4;
        }
    }

    /**
     * ob2ndRout=0 (.wait): Decrement timer. When expired, start moving.
     * <pre>
     * subq.b #1,objoff_2A(a0)
     * bmi.s  .move
     * rts
     * </pre>
     */
    private void updateWait() {
        waitTimer--;
        if (waitTimer >= 0) {
            return; // bmi.s .move - wait until negative
        }

        // Timer expired: transition to move
        secondaryState = STATE_MOVE;
        waitTimer = MOVE_TIMER;   // move.b #$10,objoff_2A(a0)

        // move.w #-$C0,obVelX(a0) / move.w #$40,obInertia(a0)
        xVelocity = -WALK_VELOCITY;
        inertia = BODY_INERTIA;

        // bchg #4,objoff_2B(a0) / bne.s loc_16AFC
        // Toggle bit 4 of animControl. If it WAS set (now clear), bne is taken.
        boolean wasBit4Set = (animControl & 0x10) != 0;
        animControl ^= 0x10;

        if (!wasBit4Set) {
            // bne.s not taken: bit 4 was clear, now set
            // clr.w obVelX(a0) / neg.w obInertia(a0)
            xVelocity = 0;
            inertia = -inertia;
        }

        // bset #7,objoff_2B(a0) - start animation
        animControl |= 0x80;
    }

    /**
     * ob2ndRout=2 (loc_16B02): Walk with terrain following.
     * Apply velocity, check floor, record Y-delta in ring buffer.
     * After move timer expires, return to wait state.
     * <p>
     * REV01 version used (has subpixel handling and direction change fixes).
     */
    private void updateMove() {
        waitTimer--;
        if (waitTimer < 0) {
            // .loc_16B5E: return to wait state
            secondaryState = STATE_WAIT;
            waitTimer = INTER_MOVE_WAIT;
            xVelocity = 0;
            inertia = 0;
            return;
        }

        // If not moving (xVelocity == 0), skip position update
        if (xVelocity == 0) {
            return;
        }

        // SpeedToPos: apply velocity with subpixel precision
        int effectiveVel = xVelocity;
        // btst #0,obStatus(a0) / beq.s .noflip / neg.w d0
        // Negate when bit 0 = 1 (xFlip / facing right)
        if (!facingLeft) {
            effectiveVel = -effectiveVel;
        }

        int oldXWhole = currentX;
        motion.x = currentX;
        motion.xVel = effectiveVel;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // swap d3 / cmp.w obX(a0),d3 / beq.s .notmoving
        if (currentX == oldXWhole) {
            return; // No whole-pixel movement
        }

        // ObjFloorDist: check floor
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        int floorDist = floorResult.foundSurface() ? floorResult.distance() : 100;

        // cmpi.w #-8,d1 / blt.s .loc_16B70 / cmpi.w #$C,d1 / bge.s .loc_16B70
        if (floorDist < FLOOR_MIN_DIST || floorDist >= FLOOR_MAX_DIST) {
            // Edge detected: reverse direction
            handleEdgeDetected(floorDist);
            return;
        }

        // Snap to floor: add.w d1,obY(a0)
        currentY += floorDist;

        // Record Y-delta in ring buffer
        ringBuffer[ringBufferWriteIndex] = (byte) floorDist;
        ringBufferWriteIndex = (ringBufferWriteIndex + 1) & 0x0F;
    }

    /**
     * Handles edge/wall detection during movement.
     * REV01 version: neg.w obX+2(a0) for subpixel reversal.
     * <pre>
     * .loc_16B70:
     *   moveq  #0,d0
     *   move.b cat_parent(a0),d0
     *   move.b #$80,objoff_2C(a0,d0.w)    ; write direction change marker
     *   neg.w  obX+2(a0)                   ; negate subpixel
     *   beq.s  .loc_1730A
     *   btst   #0,obStatus(a0)
     *   beq.s  .loc_1730A
     *   subq.w #1,obX(a0)
     *   addq.b #1,cat_parent(a0)
     *   ...clear next entry...
     * .loc_1730A:
     *   bchg   #0,obStatus(a0)
     *   ...
     *   addq.b #1,cat_parent(a0)
     *   andi.b #$F,cat_parent(a0)
     * </pre>
     */
    private void handleEdgeDetected(int floorDist) {
        // Write direction change marker to ring buffer
        ringBuffer[ringBufferWriteIndex] = (byte) 0x80;

        // REV01: negate subpixel
        // neg.w obX+2(a0) / beq.s .loc_1730A / btst #0,obStatus(a0) / beq.s .loc_1730A
        motion.xSub = (-motion.xSub) & 0xFFFF;
        if (motion.xSub != 0 && !facingLeft) {
            // subq.w #1,obX(a0) - adjust when bit 0 = 1 (facing right)
            currentX--;
            ringBufferWriteIndex = (ringBufferWriteIndex + 1) & 0x0F;
            ringBuffer[ringBufferWriteIndex] = 0;
        }

        // Reverse direction
        facingLeft = !facingLeft;

        // Advance ring buffer
        ringBufferWriteIndex = (ringBufferWriteIndex + 1) & 0x0F;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (fragmenting || !initialized) {
            return;
        }

        // Head animation from Cat_Head:
        // move.b objoff_2B(a0),d1
        // bpl.s  .display                ; if bit 7 clear, skip animation
        if ((animControl & 0x80) == 0) {
            return;
        }

        // lea (Ani_Cat).l,a1
        // move.b obAngle(a0),d0
        // andi.w #$7F,d0
        int tableIndex = animAngle & 0x7F;

        // addq.b #4,obAngle(a0)
        animAngle += ANIM_ANGLE_STEP;

        // move.b (a1,d0.w),d0 / bpl.s .animate
        if (tableIndex < ANI_CAT.length) {
            int frameVal = ANI_CAT[tableIndex];
            if (frameVal == 0xFF) {
                // bclr #7,objoff_2B(a0) - end animation cycle
                animControl &= ~0x80;
                // bra.s .display - keep previous frame
            } else {
                // .animate: andi.b #$10,d1 / add.b d1,d0 / move.b d0,obFrame(a0)
                int offset = animControl & 0x10;
                currentDisplayFrame = frameVal + offset;
            }
        }
    }

    /**
     * Returns the current head mapping frame.
     * Computed during updateAnimation from the Ani_Cat lookup table.
     * During fragmentation, resets to base frame 0.
     */
    private int getHeadMappingFrame() {
        if (fragmenting) {
            // andi.b #$F8,obFrame(a0) - strip low 3 bits → base head frame 0
            return 0;
        }
        return currentDisplayFrame;
    }

    /**
     * Fragment physics for the head: ObjectFall + floor bounce.
     * From loc_16C96 and loc_16CC0.
     */
    private void updateFragment() {
        // ObjectFall: apply velocity, then gravity (uses pre-gravity velocity for movement).
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = xVelocity;
        motion.yVel = yVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentX = motion.x;
        currentY = motion.y;
        yVelocity = motion.yVel;

        // Floor bounce when falling
        if (yVelocity >= 0) {
            TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
            if (floorResult.foundSurface() && floorResult.distance() < 0) {
                currentY += floorResult.distance();
                yVelocity = FRAG_Y_VELOCITY;
            }
        }

        // Cat_Fragment .displayOrDelete (docs/s1disasm/_incObj/78 Badnik -
        // Caterkiller.asm:206-208): tst.b obRender(a0) / bpl.w Cat_Despawn.
        // An off-screen fragment routes to Cat_Despawn, which clears bit 7 of
        // the object's v_objstate respawn-table entry (bclr #7,2(a2,d0.w),
        // Caterkiller.asm:139-148) before DeleteObject — exactly the
        // RememberState off-screen unload, NOT a permanent kill. Using
        // setDestroyedByOffscreen() (respawnable, clears the placement counter
        // bit) instead of setDestroyed(true) lets the layout entry re-create
        // the Caterkiller when the camera returns. setDestroyed(true) kept the
        // REV01 bset counter bit latched, so a Caterkiller the player frag-hit
        // then walked away from never respawned (MZ2 @0x200 caterkiller: the
        // placement bit-2 collision blocked its f3031 respawn, cascading the
        // scattered-ring + Basaran slot allocation and dropping the f4610
        // Basaran bounce).
        if (!renderOnScreen) {
            setDestroyedByOffscreen();
            return;
        }
        updateCaterkillerRenderFlag();
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    protected void destroyBadnik(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        deleting = true;
        ObjectServices objectServices = tryServices();
        int currentFrame = objectServices != null && objectServices.objectManager() != null
                // Body segments compare their deletion latch against the VBlank
                // counter passed into update(...). Using the gameplay frame
                // counter here causes lag-frame traces to delete the body chain
                // too early because frameCounter and vblaCounter diverge.
                ? objectServices.objectManager().getVblaCounter()
                : 0;
        bodyDeletionEarliestFrame = currentFrame + 2;
        // ROM parity: explosion inherits our slot (in-place obID change).
        int mySlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        setDestroyed(true);
        DestructionEffects.destroyBadnik(currentX, currentY, spawn, mySlot,
                player, services(), getDestructionConfig());
    }

    /**
     * Starts fragment mode for the head.
     * From loc_16C96:
     * <pre>
     * moveq  #0,d0
     * move.b obRoutine(a0),d0
     * move.w Cat_FragSpeed-2(pc,d0.w),d0
     * btst   #0,obStatus(a0)
     * beq.s  loc_16CAA
     * neg.w  d0
     * move.w d0,obVelX(a0)
     * move.w #-$400,obVelY(a0)
     * move.b #$C,obRoutine(a0)
     * andi.b #$F8,obFrame(a0)
     * </pre>
     */
    private void startHeadFragment() {
        fragmenting = true;
        int fragXVel = HEAD_FRAG_X_VELOCITY;
        // btst #0,obStatus(a0) / beq.s / neg.w d0
        // Negate when bit 0 = 1 (xFlip / facing right)
        if (!facingLeft) {
            fragXVel = -fragXVel;
        }
        xVelocity = fragXVel;
        yVelocity = FRAG_Y_VELOCITY;
    }

    /**
     * Cat_Delete parity for dynamic children: when head is removed (destroyed or off-screen),
     * mark all spawned body segments for deletion so they cannot linger in dynamic lists.
     */
    private void markBodySegmentsForDeletion() {
        for (Sonic1CaterkillerBodyInstance body : bodySegments) {
            body.markDestroyed();
        }
        bodySegments.clear();
    }

    void adoptBodySegmentForRewind(Sonic1CaterkillerBodyInstance restoredBody) {
        bodySegments.removeIf(body -> body == null || body.isDestroyed() || !body.isLinkedToHead(this));
        bodySegments.remove(restoredBody);
        bodySegments.add(restoredBody);

        // Dynamic body entries are captured/restored in runtime spawn order
        // (head, segment0, segment1, segment2). Compact scalars are restored
        // later, so build the structural parent chain from that restore order.
        CaterkillerParentState parent = this;
        for (Sonic1CaterkillerBodyInstance body : bodySegments) {
            body.relinkForRewind(this, parent);
            parent = body;
        }
    }

    @Override
    public void onUnload() {
        deleting = true;
        if (bodyDeletionEarliestFrame < 0 && !fragmenting) {
            markBodySegmentsForDeletion();
        }
    }

    /**
     * Triggered by Caterkiller body contact (S1 React_Caterkiller).
     * This starts fragment behavior without awarding points or spawning explosions.
     */
    void triggerFragmentFromBodyHit() {
        if (deleting || isDestroyed() || fragmenting) {
            return;
        }
        startHeadFragment();
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public boolean isPersistent() {
        if (deleting) {
            return false;
        }
        if (fragmenting) {
            return renderOnScreen;
        }
        return !isDestroyed() && isOnScreenX(160);
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // Caterkiller owns its Cat_Head out_of_range tail because the fragment
        // entry branch must run before any off-screen delete decision.
        return false;
    }

    private void updateCaterkillerRenderFlag() {
        renderOnScreen = isWithinRenderSpriteBounds(RENDER_HALF_WIDTH, ASSUMED_RENDER_HALF_HEIGHT);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // obPriority = 4
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (deleting) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.CATERKILLER);
        if (renderer == null) return;

        int frame = getHeadMappingFrame();
        // S1: default art faces left. hFlip when facing right (obStatus bit 0 set).
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        super.appendDebugRenderCommands(ctx);
        String label = "CatHead st=" + secondaryState
                + " wt=" + waitTimer
                + " rb=" + ringBufferWriteIndex
                + (fragmenting ? " FRAG" : "");
        ctx.drawWorldLabel(currentX, currentY, -12, label, DebugColor.YELLOW);
    }

    // ---- Package-private accessors for body segments ----

    @Override
    public int getSecondaryState() {
        return secondaryState;
    }

    @Override
    public int getInertia() {
        return inertia;
    }

    @Override
    public int getXVelocity() {
        return xVelocity;
    }

    @Override
    public int getAnimControl() {
        return animControl;
    }

    boolean isDeleting() {
        return deleting;
    }

    boolean isFragmenting() {
        return fragmenting;
    }

    boolean shouldDeleteBodySegments(int frameCounter) {
        if (!deleting && !isDestroyed()) {
            return false;
        }
        return bodyDeletionEarliestFrame < 0 || frameCounter >= bodyDeletionEarliestFrame;
    }

    /**
     * Reads a value from the Y-delta ring buffer at the given index.
     * Body segments call this to read the head's recorded terrain deltas.
     */
    @Override
    public int readRingBuffer(int index) {
        return ringBuffer[index & 0x0F];
    }

    /**
     * Writes a value to the Y-delta ring buffer at the given index.
     * Body segments write their own Y-deltas for segments behind them.
     */
    void writeRingBuffer(int index, byte value) {
        ringBuffer[index & 0x0F] = value;
    }

    /**
     * Writes a value to the Y-delta ring buffer at the given index.
     * Convenience overload accepting int.
     */
    @Override
    public void writeRingBuffer(int index, int value) {
        ringBuffer[index & 0x0F] = (byte) value;
    }
}
