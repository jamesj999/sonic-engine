package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x78 - CPZ Staircase (Obj78)
 *
 * A multi-piece triggered elevator platform used in Chemical Plant Zone.
 * Consists of 4 platform pieces that move in a coordinated staircase pattern
 * when triggered by the player.
 *
 * State machine (subtype & 0x07):
 *   0, 4: Wait for player contact on TOP, then 30-frame countdown, advance to 1/5
 *   1, 3: Move DOWN (yOffset → +128), stop at limit (one-way, no state change)
 *   2, 6: Wait for player contact from BOTTOM, 60-frame countdown with oscillation, advance to 3/7
 *   5, 7: Move UP (yOffset → -128), stop at limit (one-way, no state change)
 *
 * Important: The staircase is ONE-WAY. Once it reaches its destination, it stays there.
 * States 1/3/5/7 do NOT auto-advance to the next state after movement completes.
 *
 * Multi-piece structure:
 *   - 4 platform pieces spaced 32 pixels apart horizontally
 *   - Each piece moves to different Y offsets creating a staircase effect
 *   - Interpolation: piece[0]=100%, piece[1]=75%, piece[2]=50%, piece[3]=25%
 *
 * Shares art with Object 0x6B (CPZ Elevator/Platform).
 */
public class CPZStaircaseObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener, RewindRecreatable {

    // Constants from disassembly
    /** Obj78 allocates parent + 3 children (s2.asm:55967-55995). */
    private static final int CHILD_SLOT_COUNT = 3;

    private boolean childSlotsReserved;

    private static final int NUM_PIECES = 4;
    private static final int PIECE_SPACING = 0x20;  // 32 pixels
    // Collision half-width from disassembly: width_pixels + 0x0B = 0x10 + 0x0B = 0x1B
    // This creates intentional overlap between adjacent pieces (54px collision width
    // with 32px spacing = 22px overlap), allowing smooth walking across pieces.
    private static final int PIECE_HALF_WIDTH = 0x1B;  // 27 pixels (matches original)
    private static final int PIECE_TOP_HEIGHT = 0x10;  // 16 pixels
    private static final int PIECE_BOTTOM_HEIGHT = 0x11;  // 17 pixels

    private static final int TOP_CONTACT_DELAY = 0x1E;  // 30 frames
    private static final int BOTTOM_CONTACT_DELAY = 60;  // 60 frames
    private static final int MAX_Y_OFFSET = 0x80;  // 128 pixels max travel

    // Collision parameters (shared by all pieces)
    private static final SolidObjectParams PIECE_PARAMS =
            SolidObjectParams.of(PIECE_HALF_WIDTH, PIECE_TOP_HEIGHT, PIECE_BOTTOM_HEIGHT);

    // State
    private int state;  // 0-7 (subtype & 0x07)
    private int timer;
    private int baseX;
    private int baseY;
    private boolean xFlip;

    // Y offsets for each piece (piece 0 is the "master", others interpolate from it)
    private final int[] yOffsets = new int[NUM_PIECES];

    // Contact tracking — simple flags set by callbacks, cleared each update().
    private boolean contactTop;
    private boolean contactBottom;

    public CPZStaircaseObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.state = spawn.subtype() & 0x07;
        this.timer = 0;

        // Initialize Y offsets based on initial state
        // States 5, 6, 7 start at the bottom (yOffset = MAX), states 0-4 start at top (yOffset = 0)
        // Subtype meanings:
        //   0x00 (state 0): Wait at top, descend on contact
        //   0x01 (state 1): Descend immediately from top
        //   0x02 (state 2): Wait at bottom (non-moving until triggered from below)
        //   0x04 (state 4): Wait at top (alternate cycle)
        //   0x05 (state 5): Ascend immediately from bottom
        //   0x06 (state 6): Wait at bottom (alternate cycle)
        //   0x07 (state 7): Ascend immediately from bottom
        int initialOffset = 0;
        if (state == 2 || state == 5 || state == 6 || state == 7) {
            // These states expect the platform to be at the bottom position
            initialOffset = MAX_Y_OFFSET;
        }
        yOffsets[0] = initialOffset;

        // Apply initial staircase interpolation
        applyStaircaseInterpolation();

        updateDynamicSpawn(baseX, baseY + yOffsets[0]);
    }

    @Override
    public CPZStaircaseObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CPZStaircaseObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return baseY;
    }

    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        return NUM_PIECES;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        // Pieces are spaced 32 pixels apart horizontally
        // X positions always increase left-to-right, regardless of flip
        // (flip only affects which Y offset is assigned to which position)
        return baseX + (pieceIndex * PIECE_SPACING);
    }

    @Override
    public int getPieceY(int pieceIndex) {
        // Each piece has its own Y offset based on staircase interpolation
        int index = xFlip ? (NUM_PIECES - 1 - pieceIndex) : pieceIndex;
        return baseY + yOffsets[index];
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return PIECE_PARAMS;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Return params for the first piece as default
        return PIECE_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Original Obj78 uses full SolidObject collision (not top-solid only).
        // The overlapping collision boxes (27px half-width, 32px spacing) combined
        // with the "near vertical edge" check in SolidObject allows smooth walking
        // across adjacent pieces while still having solid sides.
        return false;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Each Obj78 child calls the standard S2 SolidObject helper with
        // d1=width_pixels+$B=$1B (s2.asm:56006-56021). SolidObject_cont rejects
        // X only on BHI, so relX==2*d1 remains a zero-distance side contact
        // (s2.asm:35138-35176). CPZ2's right-facing Sonic at x=$152B is exactly
        // the right edge of the child centred at $1510; its child push bit stays
        // set until x=$152C on the following frame.
        return true;
    }

    @Override
    public boolean usesPieceScopedStandingBits() {
        // Obj78 allocates its four steps as four separate SST slots -- the
        // parent plus three children from Obj78_SubObjectLoop
        // (docs/s2disasm/s2.asm:55959-55995). SolidObject's continued-ride
        // branch tests the standing bit in the *object's own* status byte,
        // `btst d6,status(a0)` (docs/s2disasm/s2.asm:35070-35072), so each step
        // owns its own standing bit and a landing on a neighbouring step
        // re-seats the rider onto that step via SolidObject_Landed ->
        // RideObject_SetRide (docs/s2disasm/s2.asm:35619-35626). Folding the
        // four slots into one engine instance must keep the bits piece-scoped
        // or the rider stays latched to the step it first landed on.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // updateDynamicSpawn() tracks the moving parent surface for placement and
        // diagnostics, but ROM keeps SolidObject standing/pushing bits in the live
        // Obj78 SST slot status byte while y_pos changes
        // (docs/s2disasm/s2.asm:56025-56033). Key the folded status latch by this
        // instance, not by the per-frame dynamic spawn record.
        return true;
    }

    @Override
    public boolean preservesRidingPushStatus(PlayableEntity playerEntity) {
        int masterOffset = yOffsets[0];
        if (masterOffset == 0) {
            return false;
        }
        // Obj78's four adjacent pieces are separate ROM solid slots. Preserve
        // the folded push bit when the rider faces any neighbouring step side:
        // child SolidObject slots can leave the current Status_Push bit visible
        // before TailsCPU_Normal samples Tails and the delayed leader status.
        return isFacingAdjacentStepSide(playerEntity, false);
    }

    @Override
    public boolean preservesSidekickCpuPushGraceWhileRiding(PlayableEntity playerEntity) {
        // TailsCPU_Normal tests Tails' current Status_Push before later Obj78
        // child SolidObject calls can refresh or clear the live SST push bits
        // (docs/s2disasm/s2.asm:39291-39294; Obj78 SolidObject at 56006-56021).
        // The lower-neighbouring-step face is already modelled by the ordinary
        // live push-status latch above. The CPU-only bridge covers the opposite
        // folded child-slot ordering case without extending that latch into
        // later lower-step windows.
        return playerEntity != null && playerEntity.isCpuControlled()
                && yOffsets[0] != 0
                && isFacingAdjacentStepSide(playerEntity, false);
    }

    @Override
    public int sidekickCpuPushGraceMinimumFramesWhileRiding(PlayableEntity playerEntity) {
        return preservesSidekickCpuPushGraceWhileRiding(playerEntity) ? 8 : Integer.MAX_VALUE;
    }

    @Override
    public boolean usesSidekickCpuPushBypassObjectOrderStatusDelay(PlayableEntity playerEntity) {
        // Obj78's child SolidObject status can be visible to TailsCPU_Normal at
        // the adjacent object-order status sample even when the final frame
        // trace has the leader pushing on the staircase. This lets the first
        // child-side case branch like ROM without extending the delayed leader
        // push bridge into CPZ2 f5285.
        return playerEntity != null && playerEntity.isCpuControlled()
                && yOffsets[0] != 0
                && nearestPieceIndex(playerEntity.getCentreX()) == 1
                && isFacingAdjacentStepSide(playerEntity, false);
    }

    @Override
    public boolean preservesSidekickDelayedLeaderPushWhileRiding(PlayableEntity playerEntity) {
        // Obj78 runs as four separate SST slots: the parent plus three children
        // allocated after it (docs/s2disasm/s2.asm:55967-55995). Each child calls
        // SolidObject and ORs its contact bits back into the parent accumulator
        // (docs/s2disasm/s2.asm:56006-56021), so TailsCPU_Normal's delayed
        // Sonic_Stat_Record_Buf sample can still see later child-slot push bits
        // while the folded engine object has already reconciled the visible
        // parent state. The first child side has already aged out of that delayed
        // leader window in CPZ2 f5285; keep the bridge to the later child slots.
        return playerEntity != null && playerEntity.isCpuControlled()
                && yOffsets[0] != 0
                && nearestPieceIndex(playerEntity.getCentreX()) >= 2;
    }

    /**
     * Reserves the three child object RAM slots Obj78 allocates for its steps.
     *
     * <p>Obj78 runs as four SST slots: the parent plus three children, taken with
     * {@code AllocateObjectAfterCurrent} so each follows the previous
     * (docs/s2disasm/s2.asm:55967-55995 — {@code moveq #3,d1} then
     * {@code Obj78_SubObjectLoop}). This engine folds all four steps into one
     * instance and draws them from the parent, so the slots must still be reserved
     * or every later dynamic object takes a lower slot number than the ROM gave it.
     *
     * <p>Keyed by the stable placement {@code spawn} field, deliberately not
     * {@code getSpawn()}: {@code update} calls {@code updateDynamicSpawn} every
     * frame the staircase moves, and reserving against that rebuilt record would
     * not match the placement spawn {@code freeAllReservedChildSlots} uses on
     * unload — the identity mismatch that leaked slots for the S1 staircase
     * (see {@code Sonic1StaircaseObjectInstance}, the precedent this copies).
     */
    private void reserveChildSlots() {
        if (childSlotsReserved) {
            return;
        }
        childSlotsReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null || spawn == null) {
            return;
        }
        svc.objectManager().allocateChildSlotsAfter(spawn, CHILD_SLOT_COUNT, getSlotIndex());
    }

    private boolean isFacingAdjacentStepSide(PlayableEntity playerEntity, boolean requireLowerNeighbor) {
        if (playerEntity == null) {
            return false;
        }
        Direction direction = playerEntity.getDirection();
        int step = direction == Direction.RIGHT ? 1 : direction == Direction.LEFT ? -1 : 0;
        if (step == 0) {
            return false;
        }

        int pieceIndex = nearestPieceIndex(playerEntity.getCentreX());
        int neighbourIndex = pieceIndex + step;
        if (neighbourIndex < 0 || neighbourIndex >= NUM_PIECES) {
            return false;
        }
        if (requireLowerNeighbor && getPieceY(neighbourIndex) <= getPieceY(pieceIndex)) {
            return false;
        }

        SolidObjectParams neighbourParams = getPieceParams(neighbourIndex);
        int neighbourX = getPieceX(neighbourIndex);
        int neighbourY = getPieceY(neighbourIndex) + neighbourParams.offsetY();
        int maxVerticalDistance = neighbourParams.airHalfHeight() + playerEntity.getYRadius();
        int relativeY = playerEntity.getCentreY() - neighbourY + 4 + maxVerticalDistance;
        if (relativeY < 0 || relativeY >= maxVerticalDistance * 2) {
            // SolidObject_cont adds the player's y_radius to d2, offsets the
            // relative Y by +4, then rejects values outside [0,2*d2)
            // (s2.asm:35177-35195). A horizontally adjacent step cannot retain
            // its child-slot push bit when the rider is above/below that box.
            return false;
        }
        int playerX = playerEntity.getCentreX();
        return step > 0
                ? playerX >= neighbourX - neighbourParams.halfWidth()
                : playerX <= neighbourX + neighbourParams.halfWidth();
    }

    private int nearestPieceIndex(int x) {
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < NUM_PIECES; i++) {
            int distance = Math.abs(x - getPieceX(i));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void onPieceContact(int pieceIndex, PlayableEntity playerEntity,
                               SolidContact contact, int frameCounter) {
        if (contact.standing() || contact.touchTop()) {
            contactTop = true;
        }
        if (contact.touchBottom()) {
            contactBottom = true;
        }
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing() || contact.touchTop()) {
            contactTop = true;
        }
        if (contact.touchBottom()) {
            contactBottom = true;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        reserveChildSlots();
        boolean touchTop = contactTop;
        boolean touchBottom = contactBottom;
        contactTop = false;
        contactBottom = false;

        // Run state machine
        switch (state) {
            case 0, 4 -> updateWaitTop(touchTop);
            case 1, 3 -> updateRise();
            case 2, 6 -> updateWaitBottom(touchBottom);
            case 5, 7 -> updateDrop();
        }

        // Apply staircase interpolation to all pieces
        applyStaircaseInterpolation();

        updateDynamicSpawn(baseX, baseY + yOffsets[0]);
    }

    /**
     * States 0, 4: Wait for player contact on TOP, then 30-frame countdown.
     */
    private void updateWaitTop(boolean touchTop) {
        if (timer == 0) {
            if (touchTop) {
                // loc_292C8 writes objoff_2C=$1E and returns; loc_292E0
                // decrements only on later frames where the timer was already set.
                timer = TOP_CONTACT_DELAY;
            }
            return;
        }

        timer--;
        if (timer == 0) {
            // Transition to rise state
            state++;
        }
    }

    /**
     * States 1, 3: Smooth downward movement (platforms descend, carrying player down).
     * Despite being called "rise" in disassembly, the Y offset increases.
     * The master piece (piece 0) moves down 1 pixel per frame.
     *
     * Original disassembly (loc_29346):
     *   cmpi.w  #$80,(a1)    ; compare offset with +128
     *   beq.s   return_29386 ; if at max, just return (DON'T advance state)
     *   addq.w  #1,(a1)      ; increment offset (go positive)
     */
    private void updateRise() {
        // Move master piece down (positive Y = down in screen coords)
        // Original compares with #$80 (+128) and does NOT advance state
        if (yOffsets[0] < MAX_Y_OFFSET) {
            yOffsets[0]++;
        }
        // Original does NOT auto-advance state - staircase is one-way
    }

    /**
     * States 2, 6: Wait for player contact from BOTTOM, 60-frame countdown with oscillation.
     * Oscillation visual effect is applied in appendRenderCommands().
     */
    private void updateWaitBottom(boolean touchBottom) {
        if (touchBottom && timer == 0) {
            timer = BOTTOM_CONTACT_DELAY;
        }

        if (timer > 0) {
            timer--;
            if (timer == 0) {
                // Transition to drop state
                state++;
            }
        }
    }

    /**
     * States 5, 7: Smooth upward movement (platforms ascend).
     * The master piece (piece 0) moves up 1 pixel per frame (negative Y direction).
     *
     * Original disassembly (loc_2935E):
     *   cmpi.w  #-$80,(a1)   ; compare offset with -128
     *   beq.s   return_29386 ; if at min, just return (DON'T advance state)
     *   subq.w  #1,(a1)      ; decrement offset (go negative)
     */
    private void updateDrop() {
        // Move master piece up (negative direction = up on screen)
        // Original compares with #-$80 (-128) and does NOT advance state
        if (yOffsets[0] > -MAX_Y_OFFSET) {
            yOffsets[0]--;
        }
        // Original does NOT auto-advance state - staircase is one-way
    }

    /**
     * Applies staircase interpolation from the disassembly using fixed-point arithmetic.
     * The original code uses 16.16 fixed point with swap operations.
     *
     * For positive counter (states 1, 3 - upward shift):
     *   lsr.l #1,d1 (logical shift right)
     * For negative counter (states 5, 7 - downward shift):
     *   asr.l #1,d1 (arithmetic shift right to preserve sign)
     *
     * Result:
     *   piece[0] = 100% of master offset (the reference)
     *   piece[1] = 75% of master offset
     *   piece[2] = 50% of master offset
     *   piece[3] = 25% of master offset
     */
    private void applyStaircaseInterpolation() {
        int counter = yOffsets[0];  // Master piece offset (100%)

        // Convert to 16.16 fixed-point (swap = shift left 16)
        long d1 = ((long) counter) << 16;

        // Apply shifts - use arithmetic shift for negative values
        long d2, d3;
        if (counter >= 0) {
            // States 1, 3: logical shift (lsr)
            d2 = d1 >>> 1;          // counter/2 (50%)
            d1 = d1 >>> 1;          // counter/2
            d1 = d1 >>> 1;          // counter/4 (25%)
        } else {
            // States 5, 7: arithmetic shift (asr) to preserve sign
            d2 = d1 >> 1;           // counter/2 (50%)
            d1 = d1 >> 1;           // counter/2
            d1 = d1 >> 1;           // counter/4 (25%)
        }
        d3 = d1 + d2;               // 75% = 25% + 50%

        // Extract high word (swap back) - shift right 16
        yOffsets[1] = (int)(d3 >> 16);  // 75%
        yOffsets[2] = (int)(d2 >> 16);  // 50%
        yOffsets[3] = (int)(d1 >> 16);  // 25%
        // yOffsets[0] stays as raw counter (100%)
    }

    // Oscillation is handled in appendRenderCommands() for visual effect only

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Use the dedicated CPZ Stair Block renderer (NOT cpzPlatformRenderer which is Obj19)
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CPZ_STAIR_BLOCK);
        if (renderer == null) return;

        // Calculate checkerboard oscillation for states 2, 6 (wait at bottom with countdown)
        // Disassembly: lsr.b #2,d0 / andi.b #1,d0 - toggles every 4 frames
        // Creates checkerboard pattern: pieces 0,2 move together, 1,3 move opposite
        boolean oscillating = (state == 2 || state == 6) && timer > 0;

        // Render all 4 pieces
        // Frame 0 is the 32x32 stair block (4x4 tiles)
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceIndex = xFlip ? (NUM_PIECES - 1 - i) : i;
            int pieceX = baseX + (i * PIECE_SPACING);
            int pieceY = baseY + yOffsets[pieceIndex];

            // Apply checkerboard oscillation pattern during wait states
            // Blocks 0,2 get baseBit value, blocks 1,3 get opposite value
            if (oscillating) {
                int baseBit = (timer >> 2) & 1;  // Toggles every 4 frames
                // pieceIndex determines which group: 0,2 vs 1,3
                int shake = ((pieceIndex & 1) == 0) ? baseBit : (baseBit ^ 1);
                pieceY += shake;
            }

            // Draw the stair block at this position (frame 2 = single 32x32 block)
            renderer.drawFrameIndex(2, pieceX, pieceY, xFlip, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Debug rendering - draw rectangles for each piece
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceIndex = xFlip ? (NUM_PIECES - 1 - i) : i;
            int pieceX = baseX + (i * PIECE_SPACING);
            int pieceY = baseY + yOffsets[pieceIndex];

            int left = pieceX - PIECE_HALF_WIDTH;
            int right = pieceX + PIECE_HALF_WIDTH;
            int top = pieceY - PIECE_TOP_HEIGHT;
            int bottom = pieceY + PIECE_BOTTOM_HEIGHT;

            ctx.drawLine(left, top, right, top, 0.6f, 0.8f, 0.3f);
            ctx.drawLine(right, top, right, bottom, 0.6f, 0.8f, 0.3f);
            ctx.drawLine(right, bottom, left, bottom, 0.6f, 0.8f, 0.3f);
            ctx.drawLine(left, bottom, left, top, 0.6f, 0.8f, 0.3f);
        }
    }

}
