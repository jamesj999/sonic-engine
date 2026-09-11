package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.ButtonVineTriggerManager;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x81 - MCZ Drawbridge (Mystic Cave Zone).
 * <p>
 * A long rotatable bridge that opens/closes when triggered by a ButtonVine switch.
 * The drawbridge consists of 8 stacked log segments that rotate as a unit.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 56420-56617 (Obj81 code)
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 0-3: Switch ID (0-15) - which ButtonVine_Trigger to monitor</li>
 * </ul>
 * <p>
 * <b>Status flags:</b>
 * <ul>
 *   <li>X-flip (bit 0): Initial direction (-0x100 vs +0x100)</li>
 *   <li>Y-flip (bit 1): Inverted vertical orientation</li>
 * </ul>
 * <p>
 * <b>Rotation mechanics (from disassembly):</b>
 * <ul>
 *   <li>Angle stored as signed byte, range -0x40 to +0x40 (or 0x00 to 0x80 when rotating)</li>
 *   <li>Direction value (objoff_34) is ±0x100, but only high byte added to angle</li>
 *   <li>Rotation completes when angle reaches 0x00 or 0x80 (signed: 0 or -128)</li>
 * </ul>
 * <p>
 * <b>Collision (full LRB SolidObject, selected by angle in loc_2A18A,
 * docs/s2disasm/s2.asm:56982-57000):</b>
 * <ul>
 *   <li>Raised (vertical wall, angle $40 / $C0..$FF): d1/d2/d3 = $13/$40/$41
 *       => halfWidth 19, airHalfHeight 64, groundHalfHeight 65.</li>
 *   <li>Lowered (flat platform, angle $00 / $80 / mid-rotation): d1/d2/d3 =
 *       $4B/8/9 => halfWidth 75, airHalfHeight 8, groundHalfHeight 9.</li>
 * </ul>
 */
public class MCZDrawbridgeObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(MCZDrawbridgeObjectInstance.class.getName());

    // Number of log segments in the drawbridge (child sprites)
    private static final int NUM_LOG_SEGMENTS = 8;

    // Angle completion targets
    // ROM: tst.b angle(a0) / beq.s loc_2A154 (angle == 0)
    // ROM: cmpi.b #$80,angle(a0) / bne.s loc_2A180 (angle == 0x80)
    private static final int ANGLE_COMPLETE_ZERO = 0x00;
    private static final int ANGLE_COMPLETE_180 = 0x80;  // -128 as signed byte

    // Collision parameters. Obj81 selects d1/d2/d3 (x_radius/halfWidth,
    // y_radius/airHalfHeight, y_radius+1/groundHalfHeight) by ANGLE, not by a
    // bridge-down boolean, in loc_2A18A (docs/s2disasm/s2.asm:56982-57000).
    //
    // RAISED / vertical wall (loc_2A18A defaults; reached when angle == $40 or
    // angle in $C0..$FF): move.w #$13,d1 / move.w #$40,d2 / move.w #$41,d3
    //   => halfWidth=$13=19, airHalfHeight=$40=64, groundHalfHeight=$41=65.
    // This is the "long invisible vertical barrier" (ObjPtr_MCZDrawbridge
    // comment, docs/s2disasm/s2.asm:30044). The object-header width_pixels=8
    // (Obj81_Init, s2.asm:56892) is display-culling only and is NOT the solid
    // d1 — using it as halfWidth let the player run straight through the wall.
    private static final SolidObjectParams PARAMS_RAISED = new SolidObjectParams(0x13, 0x40, 0x41);
    // LOWERED / flat platform (loc_2A1A8; reached when angle == 0 or $80 or any
    // mid-rotation angle): move.w #$4B,d1 / move.w #8,d2 / move.w #9,d3
    //   => halfWidth=$4B=75, airHalfHeight=8, groundHalfHeight=9.
    private static final SolidObjectParams PARAMS_LOWERED = new SolidObjectParams(0x4B, 0x08, 0x09);

    // Initial Y offset when spawned (bridge starts offset from spawn position)
    // ROM: subi.w #$48,y_pos(a0) (line 56457)
    private static final int INITIAL_Y_OFFSET = -0x48;

    // X position offset when bridge is down
    // ROM: addi.w #$48,x_pos(a0) or subi.w #$48,x_pos(a0)
    private static final int DOWN_X_OFFSET = 0x48;

    // State variables
    private int switchId;                 // ButtonVine trigger ID (0-15)
    private int originalX;                // Original spawn X position (objoff_30)
    private int originalY;                // Original spawn Y position (objoff_32)
    private int direction;                // Movement direction: -0x100 (left) or +0x100 (right) (objoff_34)
    private boolean xFlipped;             // X-flip status flag
    private boolean yFlipped;             // Y-flip status flag

    // Angle as 16-bit word (only high byte used for rotation)
    // ROM: angle(a0) is a word but rotation logic treats it as byte
    private int angle;                    // Current rotation angle (signed byte range)
    private boolean isMoving;             // objoff_36: True when bridge is rotating
    private boolean bridgeDown;           // True when bridge has completed lowering

    // Current collision X position (can differ from original when down)
    private int collisionX;
    private int collisionY;

    // Positions of the 8 log segments (world coordinates)
    private final int[] logX = new int[NUM_LOG_SEGMENTS];
    private final int[] logY = new int[NUM_LOG_SEGMENTS];

    // ROM Obj81_Init allocates a SECOND SST slot for its multi-sprite log child
    // (see reserveMultiSpriteChildSlot). Tracked so it is allocated exactly once.
    private boolean childSlotReserved;

    public MCZDrawbridgeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Extract switch ID from subtype (bits 0-3)
        // ROM: move.b subtype(a0),d0 / andi.w #$F,d0
        this.switchId = spawn.subtype() & 0x0F;

        // Store original position for reset (objoff_30 and objoff_32)
        // ROM: move.w x_pos(a0),objoff_30(a0) / move.w y_pos(a0),objoff_32(a0)
        this.originalX = spawn.x();
        this.originalY = spawn.y();

        // Check flip flags from render_flags/status
        // ROM: btst #0,render_flags(a0) for X-flip, btst #1 for Y-flip
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.yFlipped = (spawn.renderFlags() & 0x02) != 0;

        // Set direction based on X-flip (objoff_34)
        // ROM (line 56471-56474): move.w #$100,d1 / btst #0,status(a0) / beq.s + / neg.w d1
        // ROM (line 56476): move.w d1,objoff_34(a0)
        this.direction = xFlipped ? -0x100 : 0x100;

        // Set initial angle based on Y-flip
        // ROM (line 56458-56463): move.b #-$40,angle(a0) / btst #1,status(a0) / beq.s +
        //                        / addi.w #$90,y_pos(a0) / move.b #$40,angle(a0)
        if (yFlipped) {
            this.angle = 0x40;  // +64
            // Y-flipped bridges start lower (add 0x90 to Y)
            this.collisionY = originalY + 0x90 + INITIAL_Y_OFFSET;
        } else {
            this.angle = -0x40;  // -64 (0xC0 as unsigned byte)
            this.collisionY = originalY + INITIAL_Y_OFFSET;
        }

        // Initial collision X position
        this.collisionX = originalX;

        // State flags
        this.isMoving = false;
        this.bridgeDown = false;

        LOGGER.fine(() -> String.format(
                "MCZDrawbridge init: pos=(%d,%d), switchId=%d, direction=%d, xFlip=%b, yFlip=%b, angle=%d",
                originalX, originalY, switchId, direction, xFlipped, yFlipped, angle));

        // Calculate initial segment positions
        updateSegmentPositions();
    }

    @Override
    public MCZDrawbridgeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MCZDrawbridgeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return collisionX;
    }

    @Override
    public int getY() {
        return collisionY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        reserveMultiSpriteChildSlot();

        // Check ButtonVine trigger
        // ROM (line 56498-56501): lea (ButtonVine_Trigger).w,a2 / moveq #0,d0
        //                         / move.b subtype(a0),d0 / btst #0,(a2,d0.w)
        boolean triggered = ButtonVineTriggerManager.getTrigger(switchId);

        if (triggered && !isMoving && !bridgeDown) {
            // Start lowering the bridge
            // ROM (line 56502-56504): tst.b objoff_36(a0) / bne.s + / move.b #1,objoff_36(a0)
            isMoving = true;
            services().playSfx(Sonic2Sfx.DRAWBRIDGE_MOVE.id);

            // Special case: if X-flip is set, immediately adjust X position
            // ROM (line 56508-56511): cmpi.b #status.npc.no_balancing|status.npc.x_flip,status(a0)
            //                         / bne.s + / move.w objoff_30(a0),x_pos(a0) / subi.w #$48,x_pos(a0)
            if (xFlipped) {
                collisionX = originalX - DOWN_X_OFFSET;
            }
        }

        if (isMoving) {
            // ROM checks completion BEFORE incrementing angle:
            // ROM (line 56516-56520): tst.b angle(a0) / beq.s loc_2A154
            //                         / cmpi.b #$80,angle(a0) / bne.s loc_2A180
            int unsignedAngle = angle & 0xFF;
            if (unsignedAngle == ANGLE_COMPLETE_ZERO || unsignedAngle == ANGLE_COMPLETE_180) {
                // Bridge reached horizontal target
                isMoving = false;
                bridgeDown = true;
                services().playSfx(Sonic2Sfx.DRAWBRIDGE_DOWN.id);

                // ROM: move.w objoff_32(a0),y_pos(a0) - restore Y to original
                collisionY = originalY;

                // ROM: move.w #$48,d1 / cmpi.b #$80,angle(a0) / bne.s + / neg.w d1
                //      move.w objoff_30(a0),x_pos(a0) / add.w d1,x_pos(a0)
                int xOffset = (unsignedAngle == ANGLE_COMPLETE_180) ? -DOWN_X_OFFSET : DOWN_X_OFFSET;
                if (!xFlipped) {
                    collisionX = originalX + xOffset;
                }
            } else {
                // Still rotating: increment angle
                // ROM (line 56534-56536): move.w objoff_34(a0),d0 / add.w d0,angle(a0)
                int angleStep = direction >> 8;  // High byte of direction (±1)
                angle = (byte)(angle + angleStep);
            }
        }

        // Update segment positions based on current angle
        updateSegmentPositions();
    }

    /**
     * Reserves the second SST slot ROM {@code Obj81_Init} consumes for the
     * drawbridge's multi-sprite log child.
     *
     * <p>ROM (docs/s2disasm/s2.asm:56973-56998): on its first execution Obj81
     * calls {@code AllocateObjectAfterCurrent} and, on success, writes its own
     * id ($81) into that slot with {@code render_flags.multi_sprite} set and
     * eight sub-sprites. That child is display-only — the Obj81 entry point
     * branches straight to {@code DisplaySprite3} for it
     * (docs/s2disasm/s2.asm:56929-56938) — and is released only by the parent's
     * out-of-range tail, which deletes {@code objoff_3C} and then itself
     * (docs/s2disasm/s2.asm:57019-57023).
     *
     * <p>This engine instance already draws all eight log segments itself, so
     * only the slot needs modelling. Every on-screen drawbridge therefore holds
     * two SST slots, not one. Without this, MCZ's whole dynamic-slot map ran one
     * slot low per loaded drawbridge, which changes which slot every later
     * {@code FindFreeObj} allocation lands in — including the monitor-contents
     * icon, whose first-execution frame depends on whether its slot sorts above
     * or below the monitor shell's in the ascending {@code ExecuteObjects} walk.
     *
     * <p>Allocation happens on the first {@code update()} because ROM runs
     * {@code Obj81_Init} inside {@code ExecuteObjects}, after {@code ObjPosLoad}
     * has placed the parent. {@code allocateChildSlotsAfter} is the engine's
     * {@code AllocateObjectAfterCurrent}: it scans forward from the parent slot
     * and yields -1 when object RAM is full, matching the ROM's
     * {@code bne.s Obj81_BridgeUp} allocation-failure branch (the child is then
     * simply never created).
     */
    private void reserveMultiSpriteChildSlot() {
        if (childSlotReserved) {
            return;
        }
        int parentSlot = getSlotIndex();
        if (parentSlot < 0 || spawn == null) {
            return;
        }
        var svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        childSlotReserved = true;
        svc.objectManager().allocateChildSlotsAfter(spawn, 1, parentSlot);
    }

    /**
     * Update the positions of all 8 log segments based on the current rotation angle.
     * Uses sine/cosine to calculate positions along the rotating bridge.
     * <p>
     * ROM Reference: s2.asm lines 56599-56638 (loc_2A1EA subroutine)
     * <p>
     * The ROM uses 16.16 fixed-point accumulation per segment:
     * <pre>
     *   CalcSine → d0=sin (±256), d1=cos (±256)
     *   swap d0/d1 → shift to high word (×65536)
     *   asr.l #4  → net step per segment = sin×4096 (fixed-point)
     *   Each iteration: accumulated >> 16 gives integer offset
     *   Result: segment i offset = (i+1) × sin / 16
     * </pre>
     * With sin=256 at 90°: 256/16 = 16px per segment (one LOG_SPACING).
     * The sign/direction is encoded in the sine/cosine via the angle value.
     * <p>
     * Base position is objoff_30/objoff_32 (original spawn position, NOT the
     * collision position which has a -$48 offset).
     */
    private void updateSegmentPositions() {
        int sineAngle = angle & 0xFF;

        int sin = calcSine(sineAngle);
        int cos = calcCosine(sineAngle);

        // ROM: move.w objoff_30(a0),d3 / move.w objoff_32(a0),d2
        // Base is the ORIGINAL spawn position (not the collision position)
        int baseX = originalX;
        int baseY = originalY;

        for (int i = 0; i < NUM_LOG_SEGMENTS; i++) {
            int step = i + 1;
            // ROM: accumulated = step × (sin << 12), integer = accumulated >> 16 = step × sin >> 4
            int offsetX = (cos * step) >> 4;
            int offsetY = (sin * step) >> 4;

            logX[i] = baseX + offsetX;
            logY[i] = baseY + offsetY;
        }
    }

    /**
     * Calculate sine value for angle (0-255 maps to 0-360 degrees).
     */
    private int calcSine(int angle) {
        return TrigLookupTable.sinHex(angle);
    }

    /**
     * Calculate cosine value for angle (0-255 maps to 0-360 degrees).
     */
    private int calcCosine(int angle) {
        return TrigLookupTable.cosHex(angle);
    }

    // SolidObjectProvider implementation

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM loc_2A18A (docs/s2disasm/s2.asm:56982-56996) selects the solid
        // bounding box from the CURRENT angle byte, not a bridge-down flag:
        //   d1/d2/d3 default to $13/$40/$41 (raised vertical wall);
        //   move.b angle(a0),d0
        //   beq.s   loc_2A1A8   ; angle == $00  -> wide flat (loc_2A1A8)
        //   cmpi.b  #$40,d0
        //   beq.s   loc_2A1B4   ; angle == $40  -> keep raised defaults
        //   cmpi.b  #-$40,d0
        //   bhs.s   loc_2A1B4   ; angle >= $C0  -> keep raised defaults
        //   ; else (angle $01..$3F or $41..$BF, incl. $80) fall through to
        //   ; loc_2A1A8 -> wide flat ($4B/8/9).
        int a = angle & 0xFF;
        boolean raised = (a == 0x40) || (a >= 0xC0);
        return raised ? PARAMS_RAISED : PARAMS_LOWERED;
    }

    @Override
    public boolean isTopSolidOnly() {
        // ROM Obj81 calls plain JmpTo22_SolidObject (docs/s2disasm/s2.asm:57000),
        // i.e. a full LRB SolidObject. In its raised state it is a "long
        // invisible vertical barrier" (ObjPtr_MCZDrawbridge comment,
        // docs/s2disasm/s2.asm:30044) that stops the player horizontally via
        // SolidObject_LeftRight -> SolidObject_StopCharacter (zeroes inertia +
        // x_vel and pushes x_pos out, docs/s2disasm/s2.asm:35407-35444).
        // It is NOT a top-solid-only platform.
        return false;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    /**
     * ROM: Obj81 calls plain JmpTo22_SolidObject (docs/s2disasm/s2.asm:57000).
     * A top-landing reaches SolidObject_Landed via SolidObject_TopBottom, whose
     * "cmpi.w #$10,d3 / blo.s SolidObject_Landed" (unsigned lower-than $10,
     * docs/s2disasm/s2.asm:35488-35494) covers d3 (relY) == 0. So distY==0 is an
     * accepted landing. Allow zero-distance landings here to match ROM.
     */
    @Override
    public boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return true;
    }

    /**
     * ROM: Obj81 uses the full SolidObject routine (JmpTo22_SolidObject,
     * docs/s2disasm/s2.asm:57000), not PlatformObject_ChkYRange
     * (s2.asm:35696-35712). A top landing is resolved by SolidObject_Landed,
     * whose formula (playerY - relY + 3) is already implemented by
     * resolveContactInternal; the PlatformObject absolute snap
     * (anchorY - groundHalfHeight - yRadius - 1) must not be applied.
     */
    @Override
    public boolean usesPlatformObjectLandingSnap() {
        return false;
    }

    @Override
    public boolean nonRollingLandingPublishesWalk(PlayableEntity player) {
        // Obj81 calls the full SolidObject routine. Its SolidObject_Landed tail
        // reaches Sonic_ResetOnFloor, whose entry writes Walk before testing
        // Status_Roll (docs/s2disasm/s2.asm:35488-35509,38123-38129).
        return true;
    }

    // SolidObjectListener implementation

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special handling needed
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Get renderer from art provider
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MCZ_DRAWBRIDGE);
        if (renderer == null) return;

        // Render each log segment using frame index 1 (frame 0 is empty/pivot)
        // ROM: sub-sprites use mapping frame 1 without flip - orientation is
        // handled by the sine/cosine position calculation, not by flipping tiles
        for (int i = 0; i < NUM_LOG_SEGMENTS; i++) {
            renderer.drawFrameIndex(1, logX[i], logY[i], false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);  // Priority 5 from disassembly
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw pivot point (yellow)
        int pivotX = originalX;
        int pivotY = originalY + INITIAL_Y_OFFSET;
        if (yFlipped) {
            pivotY += 0x90;
        }
        ctx.drawLine(pivotX - 4, pivotY, pivotX + 4, pivotY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(pivotX, pivotY - 4, pivotX, pivotY + 4, 1.0f, 1.0f, 0.0f);

        // Draw log segment positions (cyan crosses)
        for (int i = 0; i < NUM_LOG_SEGMENTS; i++) {
            ctx.drawLine(logX[i] - 4, logY[i], logX[i] + 4, logY[i], 0.0f, 1.0f, 1.0f);
            ctx.drawLine(logX[i], logY[i] - 4, logX[i], logY[i] + 4, 0.0f, 1.0f, 1.0f);
        }

        // Draw collision bounds (green)
        SolidObjectParams params = getSolidParams();
        int halfWidth = params.halfWidth();
        int airHalfHeight = params.airHalfHeight();
        int groundHalfHeight = params.groundHalfHeight();

        int left = collisionX - halfWidth;
        int right = collisionX + halfWidth;
        int top = collisionY - airHalfHeight;
        int bottom = collisionY + groundHalfHeight;

        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 0.0f, 0.7f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.0f, 0.7f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 0.0f, 0.7f, 0.0f);

        // Draw angle indicator
        String angleText = String.format("A:%02X", angle & 0xFF);
        // (Text rendering not available in GLCommand, but could add later)
    }

}
