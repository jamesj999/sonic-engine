package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Shared movement/animation state exposed by each Caterkiller segment to its child.
 * In the disassembly this is the parent object pointer chain (a1 = cat_parent(a0)).
 */
interface CaterkillerParentState {
    int getSecondaryState();
    int getInertia();
    int getXVelocity();
    int getAnimControl();
    int readRingBuffer(int index);
    void writeRingBuffer(int index, int value);
}

/**
 * Caterkiller body segment (routines 4/6/8 in the disassembly).
 * Each body segment follows its parent (the segment ahead of it) by reading
 * Y-delta values from the parent's ring buffer and applying the parent's velocity
 * with an additional inertia offset.
 * <p>
 * Body segments use obColType = $CB: the $C0 flag means "hurt player on contact"
 * (Sonic takes damage even when rolling into a body segment - this is ROM-accurate).
 * <p>
 * There are two alternating types of body segment:
 * <ul>
 *   <li>BodySeg1 (routines 4 and 8): follows parent, no independent animation</li>
 *   <li>BodySeg2 (routine 6): follows parent AND has independent leg animation</li>
 * </ul>
 * <p>
 * Based on docs/s1disasm/_incObj/78 Caterkiller.asm (Cat_BodySeg1, Cat_BodySeg2).
 */
public class Sonic1CaterkillerBodyInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, CaterkillerParentState, RewindRecreatable {

    // Collision size index from disassembly (obColType low bits = $0B).
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Fragment velocities from Cat_FragSpeed: dc.w -$200, -$180, $180, $200.
    // ROM indexes with Cat_FragSpeed-2 + obRoutine, so body routines 4/6/8
    // select entries 1/2/3. The head routine 2 selects entry 0 in the head class.
    private static final int[] FRAG_X_SPEEDS = { -0x200, -0x180, 0x180, 0x200 };

    // Frag Y velocity: move.w #-$400,obVelY(a0)
    private static final int FRAG_Y_VELOCITY = -0x400;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Ring buffer marker for direction change
    private static final int DIRECTION_CHANGE_MARKER = 0x80;

    // Cat_Main gives body segments obActWid = 8 and copies obRender without bit 4,
    // so BuildSprites uses an 8 px X half-width and the default 32 px Y band.
    private static final int RENDER_HALF_WIDTH = 8;
    private static final int ASSUMED_RENDER_HALF_HEIGHT = 32;

    int currentX;
    int currentY;
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private boolean facingLeft;
    private boolean deleting;
    private boolean destroyed;
    private boolean fragmenting;
    private boolean renderOnScreen;
    private int deleteVIntRunCount = -1;

    // Body segment type: true if this is a BodySeg2 (has independent animation)
    private boolean isAnimatedSegment;

    // Index into Cat_FragSpeed for fragment X velocity (based on routine index)
    private int fragSpeedIndex;

    // Ring buffer read pointer (cat_parent low byte)
    private int ringBufferIndex;

    // Root head reference used for lifecycle/despawn checks.
    private Sonic1CaterkillerBadnikInstance head;
    // Immediate parent in the segment chain (head for seg1, seg1 for seg2, etc.).
    private CaterkillerParentState parentState;

    // Velocity state
    private int xVelocity;
    private int yVelocity;
    private int inertia;

    // Animation state (for BodySeg2 only)
    private int animAngle;
    private int secondaryState; // mirrors parent's ob2ndRout

    // Copy of parent's objoff_2B for animation control
    private int animControl;
    // Per-segment ring buffer (objoff_2C+0..15). Child segments read from this.
    private final byte[] ringBuffer = new byte[16];

    Sonic1CaterkillerBodyInstance() {
        this(null, null, 0, 0, false, false, 0, 0);
    }

    /**
     * Creates a Caterkiller body segment.
     *
     * @param head            the head object (parent chain root)
     * @param x               initial X position
     * @param y               initial Y position
     * @param facingLeft      initial facing direction (from obStatus bit 0)
     * @param isAnimated      true for BodySeg2 (routine 6), false for BodySeg1 (routine 4/8)
     * @param segmentIndex    0-based index (0=first body, 1=second, 2=third/tail)
     * @param ringBufferStart initial ring buffer read index
     * @param levelManager    level manager reference
     */
    public Sonic1CaterkillerBodyInstance(
            Sonic1CaterkillerBadnikInstance head,
            CaterkillerParentState parentState,
            int x, int y, boolean facingLeft,
            boolean isAnimated, int segmentIndex,
            int ringBufferStart) {
        super(new ObjectSpawn(x, y, 0x78, 0, 0, false, 0), "CaterkillerBody");
        this.head = head;
        this.parentState = parentState;
        this.currentX = x;
        this.currentY = y;
        this.facingLeft = facingLeft;
        this.isAnimatedSegment = isAnimated;
        this.ringBufferIndex = ringBufferStart;
        this.deleting = false;
        this.destroyed = false;
        this.fragmenting = false;
        this.renderOnScreen = true;
        this.xVelocity = 0;
        this.yVelocity = 0;
        this.inertia = 0;
        this.animAngle = 0;
        this.secondaryState = 0;
        this.animControl = 0;

        // Body routines are 4/6/8, which map to Cat_FragSpeed entries 1/2/3.
        this.fragSpeedIndex = segmentIndex + 1;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn capturedSpawn = ctx.spawn();
        Sonic1CaterkillerBadnikInstance restoredHead = nearestLiveCaterkillerHeadForRewind(ctx);
        if (capturedSpawn == null || restoredHead == null) {
            return null;
        }
        Sonic1CaterkillerBodyInstance restored = new Sonic1CaterkillerBodyInstance(
                restoredHead, restoredHead, capturedSpawn.x(), capturedSpawn.y(),
                false, false, 0, 0);
        restoredHead.adoptBodySegmentForRewind(restored);
        return restored;
    }

    void relinkForRewind(
            Sonic1CaterkillerBadnikInstance restoredHead,
            CaterkillerParentState restoredParentState) {
        this.head = restoredHead;
        this.parentState = restoredParentState;
    }

    boolean isLinkedToHead(Sonic1CaterkillerBadnikInstance candidateHead) {
        return head == candidateHead;
    }

    private static Sonic1CaterkillerBadnikInstance nearestLiveCaterkillerHeadForRewind(
            RewindRecreateContext ctx) {
        ObjectServices services = ctx.objectServices();
        ObjectManager objectManager = services != null ? services.objectManager() : null;
        ObjectSpawn capturedSpawn = ctx.spawn();
        if (objectManager == null) {
            return null;
        }
        Sonic1CaterkillerBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof Sonic1CaterkillerBadnikInstance head) || head.isDestroyed()) {
                continue;
            }
            if (capturedSpawn == null) {
                return head;
            }
            long dx = head.getX() - capturedSpawn.x();
            long dy = head.getY() - capturedSpawn.y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = head;
            }
        }
        return best;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) {
            return;
        }

        if (deleting) {
            if (vIntRunCount > deleteVIntRunCount) {
                markDestroyed();
            }
            return;
        }

        if (fragmenting) {
            updateFragment();
            return;
        }

        // Cat_BodySeg1/Cat_BodySeg2:
        // - Parent routine $C -> fragment
        // - Parent routine $A / deleted -> delete
        if (head.isFragmenting()) {
            startFragmenting();
            return;
        }
        if (head.shouldDeleteBodySegments(vIntRunCount)) {
            deleting = true;
            deleteVIntRunCount = vIntRunCount;
            return;
        }

        // Copy animation control from immediate parent
        animControl = parentState.getAnimControl();

        if (isAnimatedSegment) {
            // Cat_BodySeg2: check animation, then fall through to Cat_BodySeg1
            updateBodySeg2Animation();
        }

        // Cat_BodySeg1: follow parent movement
        updateBodySeg1Movement();
    }

    /**
     * Cat_BodySeg2 (routine 6): Independent leg animation for animated body segments.
     * Copies objoff_2B from parent. If bit 7 set (animating), reads from Ani_Cat table
     * at a phase offset. Advances obAngle by 4 each frame; if the value 4 entries ahead
     * is $FF, skips an extra 4.
     */
    private void updateBodySeg2Animation() {
        if ((animControl & 0x80) == 0) {
            return; // Not animating
        }

        int tableIndex = animAngle & 0x7F;
        animAngle += 4;

        // tst.b 4(a1,d0.w) / bpl.s Cat_AniBody
        // Check if the value 4 entries ahead is negative ($FF)
        int lookAheadIndex = (tableIndex + 4) & 0x7F;
        if (lookAheadIndex < Sonic1CaterkillerBadnikInstance.ANI_CAT.length
                && Sonic1CaterkillerBadnikInstance.ANI_CAT[lookAheadIndex] < 0) {
            animAngle += 4; // addq.b #4,obAngle(a0) - skip past $FF marker
        }
    }

    /**
     * Cat_BodySeg1 (routines 4/8): Follow parent by applying parent's velocity + inertia,
     * then read Y-delta from parent's ring buffer.
     * <p>
     * From disassembly:
     * <pre>
     * move.w obInertia(a1),obInertia(a0)
     * move.w obVelX(a1),d0
     * add.w  obInertia(a0),d0
     * move.w d0,obVelX(a0)
     * </pre>
     */
    private void updateBodySeg1Movement() {
        // Copy secondary state from immediate parent
        secondaryState = parentState.getSecondaryState();

        if (secondaryState == 0) {
            // Head is in wait state - no movement
            return;
        }

        // Copy inertia/velocity from immediate parent and compute effective velocity
        inertia = parentState.getInertia();
        int parentXVelocity = parentState.getXVelocity();
        xVelocity = parentXVelocity + inertia;

        // SpeedToPos: apply velocity to position
        int effectiveVel = xVelocity;
        // btst #0,obStatus(a0) / beq.s / neg.w d0
        // Negate when bit 0 = 1 (xFlip / facing right)
        if (!facingLeft) {
            effectiveVel = -effectiveVel;
        }

        int oldXWhole = currentX;
        motion.x = currentX;
        motion.xVel = effectiveVel;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // swap d3 / cmp.w obX(a0),d3 / beq.s loc_16C64
        // If X didn't change, skip terrain following
        if (currentX == oldXWhole) {
            return;
        }

        // Read Y-delta from parent ring buffer at this segment's read pointer
        int bufferIndex = ringBufferIndex;
        int yDelta = parentState.readRingBuffer(bufferIndex);

        if ((yDelta & 0xFF) == (DIRECTION_CHANGE_MARKER & 0xFF)) {
            // Direction change marker ($80): reverse direction and advance ring buffer
            // REV01: neg.w obX+2(a0) / beq.s / btst #0 / beq.s / cmpi.w #-$C0 / bne.s
            writeRingBuffer(bufferIndex, yDelta);
            motion.xSub = (-motion.xSub) & 0xFFFF;
            if (motion.xSub != 0 && !facingLeft && xVelocity == -0xC0) {
                // subq.w #1,obX(a0) - adjust when bit 0 = 1 (facing right)
                currentX--;
                ringBufferIndex = (ringBufferIndex + 1) & 0x0F;
                writeRingBuffer(ringBufferIndex, 0);
            }

            // bchg #0,obStatus(a0) - reverse direction
            facingLeft = !facingLeft;
            ringBufferIndex = (ringBufferIndex + 1) & 0x0F;
        } else {
            // Normal Y-delta: apply to position
            // ext.w d1 / add.w d1,obY(a0)
            currentY += (byte) yDelta; // sign-extend byte to int

            // Store the same delta for this segment's child.
            writeRingBuffer(bufferIndex, yDelta);

            // Advance ring buffer pointer
            ringBufferIndex = (ringBufferIndex + 1) & 0x0F;
        }
    }

    /**
     * Starts fragment mode when the head is destroyed.
     * Each segment gets a unique X velocity from Cat_FragSpeed and bounces.
     */
    private void startFragmenting() {
        fragmenting = true;

        // Get fragment X velocity based on segment routine index
        int fragIndex = Math.min(fragSpeedIndex, FRAG_X_SPEEDS.length - 1);
        int fragXVel = FRAG_X_SPEEDS[fragIndex];

        // btst #0,obStatus(a0) / beq.s / neg.w d0
        // Negate when bit 0 = 1 (xFlip / facing right)
        if (!facingLeft) {
            fragXVel = -fragXVel;
        }

        xVelocity = fragXVel;
        yVelocity = FRAG_Y_VELOCITY;
    }

    /**
     * Fragment physics: ObjectFall (gravity) + bounce off floor.
     * From loc_16CC0 in disassembly.
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

        // Floor bounce when falling (tst.w obVelY(a0) / bmi.s .nofloor)
        if (yVelocity >= 0) {
            var floorResult = ObjectTerrainUtils.checkFloorDist(
                    currentX, currentY, 8);
            if (floorResult.foundSurface() && floorResult.distance() < 0) {
                currentY += floorResult.distance();
                yVelocity = FRAG_Y_VELOCITY; // move.w #-$400,obVelY(a0)
            }
        }

        // loc_16CE0: tst.b obRender(a0) / bpl.w Cat_ChkGone.
        if (!renderOnScreen) {
            markDestroyed();
            return;
        }
        updateCaterkillerRenderFlag();
    }

    /**
     * Returns the current mapping frame index for rendering.
     * <p>
     * For animated segments (BodySeg2), uses Ani_Cat lookup when animating.
     * For non-animated segments (BodySeg1), uses base body frame 8.
     * During fragmentation, uses base frame with legs stripped.
     */
    int getMappingFrame() {
        if (fragmenting) {
            // andi.b #$F8,obFrame(a0) - clear low 3 bits → base body frame
            return 8; // Body frame 8 (no Y animation)
        }

        if (isAnimatedSegment && (animControl & 0x80) != 0) {
            // Animated segment: look up in Ani_Cat, add 8 for body offset
            int tableIndex = animAngle & 0x7F;
            if (tableIndex < Sonic1CaterkillerBadnikInstance.ANI_CAT.length) {
                int frameVal = Sonic1CaterkillerBadnikInstance.ANI_CAT[tableIndex] & 0xFF;
                if (frameVal != 0xFF) {
                    // addq.b #8,d0 - offset to body frames
                    return frameVal + 8;
                }
            }
            return 8;
        }

        // Non-animated or not currently animating: static body frame
        // obFrame was set to 8 on creation (move.b #8,obFrame(a1))
        return 8;
    }

    @Override
    public int getCollisionFlags() {
        if (destroyed || deleting || fragmenting) {
            return 0; // No collision when destroyed or fragmenting
        }
        // S1 React_Caterkiller behavior: body contact hurts Sonic even while rolling.
        // Use HURT category so touch response doesn't route through attack-bounce handling.
        return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed || fragmenting) {
            return;
        }
        // S1 React_Caterkiller sets bit 7 on the contacted segment, which propagates
        // through the chain and transitions the Caterkiller into fragment behavior.
        head.triggerFragmentFromBodyHit();
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(currentX, currentY, 0x78, 0, 0, false, 0);
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
    public boolean isPersistent() {
        if (destroyed) {
            return false;
        }
        if (deleting) {
            return true;
        }
        if (fragmenting) {
            return renderOnScreen;
        }
        // Body segments persist as long as the head's slot is still live.
        // ROM Cat_BodySeg1 .chkBroken detects the head's Cat_Despawn routine $A
        // and branches to .delete, which (FixBugs=0 in REV01) sets the segment's
        // own routine $A and FALLS THROUGH to DisplaySprite — so each body
        // segment displays for the SAME frame the head sets routine $A, then
        // deletes via Cat_Delete on the next frame, the same frame the head's
        // own Cat_Delete runs. Head + bodies therefore DeleteObject together one
        // frame after Cat_Despawn (docs/s1disasm/_incObj/78 Badnik -
        // Caterkiller.asm:139-152,368-391).
        //
        // The head's off-screen Cat_Despawn maps to head.isDeleting() being true
        // for one frame before head.isDestroyed(). Gating persistence on
        // !head.isDeleting() here culled the off-screen body segments via the
        // generic out_of_range pass one frame early (the frame the head set
        // deleting, before this segment's own update() could latch its deferred
        // delete), freeing their SST slots a frame ahead of the head. Persist
        // while the head's slot is live; update() then latches this segment's
        // own deferred delete so it frees on the same frame as the head.
        return !head.isDestroyed();
    }

    private void updateCaterkillerRenderFlag() {
        renderOnScreen = isWithinRenderSpriteBounds(RENDER_HALF_WIDTH, ASSUMED_RENDER_HALF_HEIGHT);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5); // obPriority = 5 (behind head at priority 4)
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.CATERKILLER);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // S1: default art faces left. hFlip when facing right (obStatus bit 0 set).
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, 8, 8, 1f, 0.5f, 0f);
        String label = (isAnimatedSegment ? "CatSeg2" : "CatSeg1")
                + " rb=" + ringBufferIndex
                + (fragmenting ? " FRAG" : "");
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.ORANGE);
    }

    boolean isFragmenting() {
        return fragmenting;
    }

    void markDestroyed() {
        destroyed = true;
        setDestroyed(true);
    }

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

    @Override
    public int readRingBuffer(int index) {
        return ringBuffer[index & 0x0F];
    }

    @Override
    public void writeRingBuffer(int index, int value) {
        ringBuffer[index & 0x0F] = (byte) value;
    }
}
