package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x5B - Blocks that form a staircase (SLZ).
 * <p>
 * A parent object that manages 4 platform pieces which rise/fall in a
 * coordinated staircase pattern when triggered by the player. The parent
 * handles the state machine and movement logic; child pieces read their
 * Y offsets from the parent.
 * <p>
 * State machine (subtype & 0x07):
 * <ul>
 *   <li>Type 0: Wait for player contact on top, 30-frame countdown, advance to type 1</li>
 *   <li>Type 1: Rise/fall — each piece gets an interpolated Y offset from a counter.
 *       Counter increments each frame. piece[0]=counter, piece[1]=75%, piece[2]=50%, piece[3]=25%.</li>
 *   <li>Type 2: Wait for player contact from below (d4 < 0), 60-frame countdown
 *       with 4-frame oscillation pattern, advance to type 3</li>
 *   <li>Type 3: Same as type 1 (continued movement after oscillation)</li>
 * </ul>
 * <p>
 * Multi-piece structure:
 * <ul>
 *   <li>4 platform pieces spaced 32 pixels apart horizontally</li>
 *   <li>When not flipped: initial offsets are $38, $39, $3A, $3B into parent's Y offset array</li>
 *   <li>When flipped (obStatus bit 0): initial offsets are $3B, $3A, $39, $38 (reversed)</li>
 *   <li>Each piece is a 32x32 block using level tile art (tile $21, palette 2)</li>
 * </ul>
 * <p>
 * Subtypes found in SLZ: 0x00 (wait-top), 0x02 (wait-bottom).
 * <p>
 * Reference: docs/s1disasm/_incObj/5B Staircase.asm
 */
public class Sonic1StaircaseObjectInstance extends AbstractObjectInstance
        implements MultiPieceSolidProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: NUM_PIECES = dbf loop count (3) + 1
    private static final int NUM_PIECES = 4;

    // From disassembly: addi.w #$20,d2 — piece spacing
    private static final int PIECE_SPACING = 0x20;

    // From disassembly: move.b #$10,obActWid(a1)
    private static final int PIECE_ACTIVE_WIDTH = 0x10;

    // From disassembly Stair_Solid:
    //   addi.w #$B,d1 → half_width = obActWid + $B = $10 + $B = $1B
    //   move.w #$10,d2 → top height
    //   move.w #$11,d3 → bottom height
    private static final int PIECE_HALF_WIDTH = 0x1B;
    private static final int PIECE_TOP_HEIGHT = 0x10;
    private static final int PIECE_BOTTOM_HEIGHT = 0x11;

    // From disassembly: move.b #3,obPriority(a1)
    private static final int PRIORITY = 3;

    // From disassembly Stair_Type00: move.w #$1E,objoff_34(a0)
    private static final int TOP_CONTACT_DELAY = 0x1E; // 30 frames

    // From disassembly Stair_Type02: move.w #$3C,objoff_34(a0)
    private static final int BOTTOM_CONTACT_DELAY = 0x3C; // 60 frames

    // From disassembly Stair_Type01: cmpi.b #$80,(a1) — max counter value
    private static final int MAX_COUNTER = 0x80;

    // Collision parameters (shared by all pieces)
    private static final SolidObjectParams PIECE_PARAMS =
            SolidObjectParams.of(PIECE_HALF_WIDTH, PIECE_TOP_HEIGHT, PIECE_BOTTOM_HEIGHT);

    // State
    private int state;              // subtype & 0x07 — incremented by state machine
    private int timer;              // objoff_34 — countdown timer
    private boolean playerOnTop;    // objoff_36 == 1 (standing on top)
    private boolean playerBelow;    // objoff_36 < 0 (d4 negative from SolidObject)
    private int baseX;              // stair_origX
    private int baseY;              // stair_origY
    private boolean xFlip;          // obStatus bit 0

    // Y offsets for each piece: objoff_38..objoff_3B stored as bytes
    // Index 0 is the "master" counter, 1-3 are interpolated
    private final int[] yOffsets = new int[NUM_PIECES];

    // Per-piece assignment offsets (which yOffset index each piece reads)
    private final int[] pieceToOffset = new int[NUM_PIECES];

    // Contact tracking — simple flags set by callbacks, cleared each update().
    // Matches ROM's objoff_36: set by Stair_Solid, read/cleared by Stair_Move.
    private boolean contactTop;
    private boolean contactBottom;

    // objoff_36 propagation-latency model (see update()): an object-to-object
    // standing transfer propagates the trigger one frame later than a fresh
    // landing/walk-on from terrain or air. prevPlayerOnObject is the approach
    // standing-on-object state captured each frame; the deferred* flags hold the
    // one-frame transfer defer buffer.
    private boolean prevPlayerOnObject;
    private boolean deferredTrigTop;
    private boolean deferredTrigBottom;

    // ROM Stair_Main allocates 3 child blocks via FindNextFreeObj, in slots ABOVE
    // the parent (docs/s1disasm/_incObj/5B SLZ Staircase.asm:39-60; sub
    // FindFreeObj.asm:32-48 scans forward from the parent). The engine folds the
    // parent + 3 children into this single instance, so we reserve those 3 child
    // slots to keep the SST slot landscape ROM-faithful AND execute this
    // consolidated object from the HIGHEST child slot. ROM runs the child blocks'
    // Stair_Solid AFTER any lower-slot object the player also interacts with that
    // frame — notably the SLZ Fan (Obj5D), which loads into a slot between the
    // staircase parent and its children and pushes the rider's x_pos BEFORE the
    // child block re-checks the ride bounds. Executing at the parent slot ran the
    // ride re-seat BEFORE the fan push, keeping the rider on the block one frame
    // too long at the walk-off edge (SLZ2 f2554: engine re-seats y 0212 vs ROM
    // 0211). Pattern mirrors AizGiantRideVineObjectInstance.getExecutionSlotIndex.
    private static final int CHILD_SLOT_COUNT = NUM_PIECES - 1; // parent + 3 children
    private boolean childSlotsReserved;
    private int executionSlot = -1;

    public Sonic1StaircaseObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Staircase");
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.state = spawn.subtype() & 0x07;
        this.timer = 0;
        this.playerOnTop = false;
        this.playerBelow = false;

        // From disassembly Stair_Main:
        //   moveq #$38,d3 / moveq #1,d4 (not flipped)
        //   moveq #$3B,d3 / moveq #-1,d4 (flipped)
        // d3 is stored in objoff_37 for each piece, d4 is the increment direction.
        // This determines which byte in objoff_38..objoff_3B each piece reads.
        // The offset $38 maps to yOffsets[0], $39 to yOffsets[1], etc.
        int startOffset = xFlip ? 3 : 0;
        int direction = xFlip ? -1 : 1;
        for (int i = 0; i < NUM_PIECES; i++) {
            pieceToOffset[i] = startOffset + (i * direction);
        }

        updateDynamicSpawn(baseX, baseY + yOffsets[0]);
    }

    @Override
    public int getX() {
        return baseX;
    }

    @Override
    public int getY() {
        return baseY;
    }

    @Override
    public int getExecutionSlotIndex() {
        // Execute from the highest reserved child slot once allocated so the
        // ride re-seat runs after lower-slot objects (the SLZ Fan) per ROM's
        // child-block-after-fan slot order. Falls back to the parent slot before
        // the children are reserved (this object's first frame).
        return executionSlot >= 0 ? executionSlot : super.getExecutionSlotIndex();
    }

    private void reserveChildSlots() {
        if (childSlotsReserved || getSlotIndex() < 0) {
            return;
        }
        childSlotsReserved = true;
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null || spawn == null) {
            return;
        }
        // ROM FindNextFreeObj scans forward from the parent slot
        // (docs/s1disasm/_incObj/sub FindFreeObj.asm:32-48).
        //
        // Key the reservation by the stable placement spawn (the field, not
        // getSpawn()). getSpawn() returns the per-frame dynamicSpawn that
        // updateDynamicSpawn() rebuilds each time the staircase moves, so using
        // it as the reservation key would NOT match the placement spawn the
        // ObjectManager passes to freeAllReservedChildSlots on unload
        // (instanceToSpawn maps the instance to the construction-time spawn).
        // That identity mismatch leaked the 3 reserved child slots every time a
        // staircase scrolled out of range -- with five SLZ1 staircases reloading
        // across the act this exhausted the 96-slot dynamic pool, starving
        // ObjPosLoad's FindFreeObj so the SLZ fireball maker (Obj13) reloaded a
        // chunk late and its fireball reached the player ~55 frames behind ROM.
        // ChainedStomper, RingInstance, AizGiantRideVine and MGZSwingingPlatform
        // all already key by the stable spawn field.
        int[] childSlots = svc.objectManager().allocateChildSlotsAfter(
                spawn, CHILD_SLOT_COUNT, getSlotIndex());
        if (childSlots.length > 0 && childSlots[childSlots.length - 1] >= 0) {
            executionSlot = childSlots[childSlots.length - 1];
        }
    }
    // MultiPieceSolidProvider implementation

    @Override
    public int getPieceCount() {
        return NUM_PIECES;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        // From disassembly: pieces are spaced 32 pixels apart, X always increases
        return baseX + (pieceIndex * PIECE_SPACING);
    }

    @Override
    public int getPieceY(int pieceIndex) {
        // ROM: Stair_Move falls through into Stair_Solid (no rts between them),
        // so ALL pieces (including the parent at piece 0) update their Y from
        // the offset array. Each piece reads its assigned offset index.
        int offsetIndex = pieceToOffset[pieceIndex];
        return baseY + yOffsets[offsetIndex];
    }

    @Override
    public SolidObjectParams getPieceParams(int pieceIndex) {
        return PIECE_PARAMS;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return PIECE_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Original uses full SolidObject (not top-solid only)
        return false;
    }

    @Override
    public int getPieceLandingHalfWidth(int pieceIndex) {
        // ROM Stair_Solid passes obActWid (=0x10, half the 0x20 piece spacing) as
        // the Solid_Landed width (docs/s1disasm/_incObj/5B SLZ Staircase.asm:80;
        // sub SolidObject.asm:307-315), NOT the full collision half-width 0x1B used
        // for the side/top box. The narrow 0x10 window makes adjacent pieces'
        // landing windows contiguous and non-overlapping, so exactly one piece
        // owns the player's standing as it walks across.
        return PIECE_ACTIVE_WIDTH;
    }

    @Override
    public boolean usesPieceScopedStandingBits() {
        // ROM folds the parent + 3 child blocks into 4 separate SST slots, each
        // running its own Stair_Solid -> SolidObject independently
        // (docs/s1disasm/_incObj/5B SLZ Staircase.asm:39-96). As the player walks
        // across the staircase, each piece's narrow Solid_Landed window
        // (obActWid*2 = the 0x20 piece spacing) hands the standing latch to the
        // next piece via Solid_ResetFloor (sub SolidObject.asm:305-375). This
        // folded single-instance must reproduce that per-piece hand-off so the
        // rider tracks the correct piece's interpolated height (piece0/parent at
        // 100% vs piece1 at 75%), not stay stuck on the first piece it boarded.
        return true;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The folded children move every Stair_Type01 pass, rebuilding the
        // dynamic spawn while each native child SST retains its status bit.
        return true;
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
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact,
                               int frameCounter) {
        if (contact.standing() || contact.touchTop()) {
            contactTop = true;
        }
        if (contact.touchBottom()) {
            contactBottom = true;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM Stair_Main allocates child blocks via FindNextFreeObj on the parent's
        // first ExecuteObjects pass; mirror that here so the consolidated instance
        // executes from the child slot range (after the fan).
        reserveChildSlots();

        // Read and clear contact flags (set by callbacks since last update).
        // ROM equivalent: Stair_Solid writes objoff_36, Stair_Move reads it.
        boolean touchTop = contactTop;
        boolean touchBottom = contactBottom;
        contactTop = false;
        contactBottom = false;

        // ROM objoff_36 propagation latency modelled by approach state.
        //
        // A piece's Stair_Solid sets the PARENT's objoff_36 from its own
        // Status_OnObj (docs/s1disasm/_incObj/5B SLZ Staircase.asm:90-93), and the
        // parent's Stair_Move reads objoff_36 (lines 104-119). The relevant timing
        // is when the piece first establishes the player's standonobject:
        //
        //  - Fresh contact from AIR or from TERRAIN: the piece's Solid_Landed sets
        //    standonobject and obStatus bit3 the SAME frame the player's foot
        //    enters the landing window, so objoff_36 propagates that frame (aux
        //    ground truth: SLZ1 f902 airborne land -> objoff_36=1 at f902; SLZ1
        //    f3020 terrain walk-on onto slot 0x47 with status on-object=False the
        //    prior frame -> objoff_36=1 at f3020). No extra latency.
        //  - Object-to-object transfer (player already standing on a DIFFERENT
        //    solid object, status on-object=True): walking off the source's edge
        //    (SolidObject .leave clears the source bit) and the destination piece
        //    re-catching via Solid_ResetFloor lands the new piece's objoff_36
        //    propagation ONE frame later (aux: SLZ1 f2835 transfer from slot 0x69
        //    (on a staircase, on-object=True) onto slot 0x61 -> parent objoff_36=1
        //    only at f2836).
        //
        // The engine's update()-then-checkpoint order already gives the base +1
        // (the checkpoint sets the contact flag, the next update reads it). Add the
        // extra +1 only for the object-to-object transfer case via a one-frame
        // defer, keyed to whether the player was standing on an object on the
        // prior frame (prevPlayerOnObject). Approach from terrain/air uses the
        // base +1 unchanged.
        boolean trigTop;
        boolean trigBottom;
        if (prevPlayerOnObject) {
            trigTop = deferredTrigTop;
            trigBottom = deferredTrigBottom;
            deferredTrigTop = touchTop;
            deferredTrigBottom = touchBottom;
        } else {
            trigTop = touchTop;
            trigBottom = touchBottom;
            deferredTrigTop = false;
            deferredTrigBottom = false;
        }
        prevPlayerOnObject = playerEntity != null && playerEntity.isOnObject();

        // Run state machine (subtype & 0x07 dispatch)
        switch (state & 0x07) {
            case 0 -> updateType00(trigTop);
            case 1, 3 -> updateType01();
            case 2 -> updateType02(trigBottom);
            default -> {} // Subtypes 4-7 unused in SLZ placement data
        }

        updateDynamicSpawn(baseX, baseY + yOffsets[0]);
    }

    /**
     * Type 0 (Stair_Type00): Wait for player contact on TOP, 30-frame countdown.
     * <pre>
     *   tst.w   objoff_34(a0)        ; timer active?
     *   bne.s   loc_10FC0            ; yes, branch to decrement
     *   cmpi.b  #1,objoff_36(a0)     ; player standing on top?
     *   bne.s   locret_10FBE         ; no, return
     *   move.w  #$1E,objoff_34(a0)   ; start 30-frame timer
     *                                ; falls through to locret_10FBE (rts) — NO decrement
     *
     * loc_10FC0:                     ; only reached when timer was already active
     *   subq.w  #1,objoff_34(a0)
     *   bne.s   locret_10FBE
     *   addq.b  #1,obSubtype(a0)     ; advance to type 1
     * </pre>
     * ROM parity: when the timer is first set (timer was 0 and contact is detected),
     * the routine returns immediately WITHOUT decrementing. Decrement only runs on
     * subsequent frames where the timer was already non-zero (docs/s1disasm/_incObj/5B
     * SLZ Staircase.asm:104-119).
     */
    private void updateType00(boolean touchTop) {
        if (timer == 0) {
            if (touchTop) {
                // cmpi.b #1,objoff_36(a0) — player on top; set timer and return
                // move.w #$1E,objoff_34(a0) + falls through to rts (loc_10FBE)
                timer = TOP_CONTACT_DELAY;
                return; // ROM: timer set on this frame, no decrement until next frame
            }
            return; // No contact and no active timer; nothing to do
        }
        // loc_10FC0: timer was already active — decrement it
        timer--;
        if (timer == 0) {
            // addq.b #1,obSubtype(a0)
            state++;
        }
        playerOnTop = false;
    }

    /**
     * Type 1/3 (Stair_Type01): Rise — increment counter and apply interpolation.
     * <pre>
     *   lea     objoff_38(a0),a1
     *   cmpi.b  #$80,(a1)        ; max counter reached?
     *   beq.s   locret_11038     ; yes, stop
     *   addq.b  #1,(a1)          ; increment counter
     *   moveq   #0,d1
     *   move.b  (a1)+,d1         ; d1 = counter
     *   swap    d1               ; d1 = counter << 16
     *   lsr.l   #1,d1            ; d1 = counter/2 << 16
     *   move.l  d1,d2            ; d2 = 50%
     *   lsr.l   #1,d1            ; d1 = counter/4 << 16
     *   move.l  d1,d3
     *   add.l   d2,d3            ; d3 = 75%
     *   swap    d1 / swap d2 / swap d3
     *   move.b  d3,(a1)+ → yOffsets[1] = 75%
     *   move.b  d2,(a1)+ → yOffsets[2] = 50%
     *   move.b  d1,(a1)+ → yOffsets[3] = 25%
     * </pre>
     */
    private void updateType01() {
        if (yOffsets[0] >= MAX_COUNTER) {
            return; // Counter maxed out
        }
        yOffsets[0]++;
        applyStaircaseInterpolation();
    }

    /**
     * Type 2 (Stair_Type02): Wait for player contact from BELOW, 60-frame countdown
     * with oscillation.
     * <pre>
     *   tst.w   objoff_34(a0)        ; timer active?
     *   bne.s   loc_10FE0            ; yes, process
     *   tst.b   objoff_36(a0)        ; d4 from SolidObject negative?
     *   bpl.s   locret_10FDE         ; no, return
     *   move.w  #$3C,objoff_34(a0)   ; start 60-frame timer
     *
     * loc_10FE0:
     *   subq.w  #1,objoff_34(a0)
     *   bne.s   loc_10FEC            ; not zero yet, oscillate
     *   addq.b  #1,obSubtype(a0)     ; advance to type 3
     *
     * loc_10FEC (oscillation):
     *   lea     objoff_38(a0),a1
     *   move.w  objoff_34(a0),d0
     *   lsr.b   #2,d0               ; divide by 4
     *   andi.b  #1,d0               ; toggle bit
     *   move.b  d0,(a1)+            ; piece 0
     *   eori.b  #1,d0               ; flip
     *   move.b  d0,(a1)+            ; piece 1
     *   eori.b  #1,d0               ; flip
     *   move.b  d0,(a1)+            ; piece 2
     *   eori.b  #1,d0               ; flip
     *   move.b  d0,(a1)+            ; piece 3
     * </pre>
     */
    /**
     * ROM parity: when the timer is first set (timer was 0 and bottom contact detected),
     * the routine returns immediately WITHOUT decrementing (docs/s1disasm/_incObj/5B
     * SLZ Staircase.asm:122-137; move.w #$3C + falls through to locret_10FDE rts).
     */
    private void updateType02(boolean touchBottom) {
        if (timer == 0) {
            if (touchBottom) {
                // tst.b objoff_36(a0) / bpl.s — trigger on negative d4 (bottom contact)
                // move.w #$3C,objoff_34(a0) + falls through to locret_10FDE (rts)
                timer = BOTTOM_CONTACT_DELAY;
                return; // ROM: timer set on this frame, no decrement until next frame
            }
            return; // No contact and no active timer; nothing to do
        }
        // loc_10FE0: timer was already active — decrement it
        timer--;
        if (timer == 0) {
            // addq.b #1,obSubtype(a0)
            state++;
            // Clear oscillation offsets when transitioning
            for (int i = 0; i < NUM_PIECES; i++) {
                yOffsets[i] = 0;
            }
        } else {
            // Oscillation pattern: checkerboard toggling every 4 frames
            // lsr.b #2,d0 / andi.b #1,d0
            int baseBit = (timer >> 2) & 1;
            yOffsets[0] = baseBit;
            yOffsets[1] = baseBit ^ 1;
            yOffsets[2] = baseBit;
            yOffsets[3] = baseBit ^ 1;
        }
        playerBelow = false;
    }

    /**
     * Applies staircase interpolation from the disassembly using fixed-point arithmetic.
     * <p>
     * The original code uses 16.16 fixed point:
     * <pre>
     *   moveq #0,d1 / move.b (a1)+,d1 / swap d1  → d1 = counter << 16
     *   lsr.l #1,d1                                → d1 = counter/2 << 16
     *   move.l d1,d2                               → d2 = 50%
     *   lsr.l #1,d1                                → d1 = counter/4 << 16
     *   move.l d1,d3 / add.l d2,d3                 → d3 = 75%
     *   swap d1 / swap d2 / swap d3                → extract high words
     * </pre>
     * Result: yOffsets[1]=75%, yOffsets[2]=50%, yOffsets[3]=25% of counter.
     */
    private void applyStaircaseInterpolation() {
        int counter = yOffsets[0]; // Master piece offset (100%)

        // Convert to 16.16 fixed-point
        // moveq #0,d1 / move.b (a1)+,d1 → d1 is unsigned byte
        // swap d1 → d1 = counter << 16
        long d1 = ((long) (counter & 0xFF)) << 16;

        // lsr.l #1,d1 → logical shift right (unsigned)
        long d2 = d1 >>> 1;         // d2 = 50%
        d1 = d1 >>> 1;              // d1 = 50%
        d1 = d1 >>> 1;              // d1 = 25%
        long d3 = d1 + d2;          // d3 = 75%

        // swap extracts high word
        yOffsets[1] = (int) ((d3 >> 16) & 0xFF);
        yOffsets[2] = (int) ((d2 >> 16) & 0xFF);
        yOffsets[3] = (int) ((d1 >> 16) & 0xFF);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_STAIRCASE);
        if (renderer == null) return;

        // Render all 4 pieces at their computed positions
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceX = getPieceX(i);
            int pieceY = getPieceY(i);
            // Frame 0 is the 32x32 stair block
            renderer.drawFrameIndex(0, pieceX, pieceY, false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // From disassembly: out_of_range.w DeleteObject,stair_origX(a0)
        // Uses stored original X for range check
        if (isDestroyed()) {
            return false;
        }
        return isInRangeAt(baseX);
    }

    // Package-visible for testing
    int getState() { return state; }
    int getTimer() { return timer; }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        for (int i = 0; i < NUM_PIECES; i++) {
            int pieceX = getPieceX(i);
            int pieceY = getPieceY(i);
            ctx.drawRect(pieceX, pieceY, PIECE_HALF_WIDTH, PIECE_TOP_HEIGHT,
                    0.6f, 0.8f, 0.3f);
        }
    }
}
