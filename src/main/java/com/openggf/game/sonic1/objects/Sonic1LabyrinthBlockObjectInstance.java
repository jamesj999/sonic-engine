package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TrigLookupTable;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x61 - Labyrinth Zone blocks (LZ).
 * <p>
 * Solid blocks with 8 different movement subtypes, used exclusively in Labyrinth Zone.
 * These include blocks that sink when stood on, rising platforms, falling blocks,
 * blocks that respond to player contact, and cork blocks that float on water.
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>High nybble (bits 4-7): Size/appearance index (from LBlk_Var table)</li>
 *   <li>Low nybble (bits 0-3): Movement behavior type (0x0-0x7)</li>
 * </ul>
 * <p>
 * LBlk_Var table (halfWidth, halfHeight per high-nybble variant):
 * <ul>
 *   <li>0: $10, $10 - 32x32 sinking block</li>
 *   <li>1: $20, $0C - 64x24 rising platform</li>
 *   <li>2: $10, $10 - 32x32 cork (floats on water)</li>
 *   <li>3: $10, $10 - 32x32 block</li>
 * </ul>
 * <p>
 * Movement subtypes:
 * <ul>
 *   <li>0x0: Stationary</li>
 *   <li>0x1: Wait for Sonic to stand on, then 30-frame timer -> advance to type 2</li>
 *   <li>0x2: Fall with gravity until floor, then become stationary</li>
 *   <li>0x3: Wait for Sonic to stand on, then 30-frame timer -> advance to type 4</li>
 *   <li>0x4: Rise until ceiling, then become stationary</li>
 *   <li>0x5: Wait for Sonic to push (side contact), then advance to type 6</li>
 *   <li>0x6: Fall with gravity until floor, then become stationary (same as type 2)</li>
 *   <li>0x7: Float with water level (rise/sink to match water, clamped to floor/ceiling)</li>
 * </ul>
 * <p>
 * loc_12180 sink effect: For blocks that start with {@code lblk_untouched=1}
 * (subtypes 1-6), when Sonic stands on them they gradually sink down using
 * a sine-based offset before the timer triggers their main movement.
 * <p>
 * Reference: docs/s1disasm/_incObj/61 LZ Blocks.asm
 */
public class Sonic1LabyrinthBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // ---- LBlk_Var table: {halfWidth, halfHeight} indexed by (subtype >> 4) & 0x7 ----
    // From disassembly: dc.b $10,$10 / $20,$C / $10,$10 / $10,$10
    private static final int[][] LBLK_VAR = {
            {0x10, 0x10}, // subtype 0x: 32x32 sinkblock
            {0x20, 0x0C}, // subtype 1x: 64x24 rising platform
            {0x10, 0x10}, // subtype 2x: 32x32 cork
            {0x10, 0x10}, // subtype 3x: 32x32 block
    };

    // From disassembly: move.b #3,obPriority(a0)
    private static final int PRIORITY = 3;

    // Type 01/03: move.w #30,lblk_time(a0) — 30-frame (half second) delay
    private static final int STAND_DELAY_FRAMES = 30;

    // Type 02/06: addq.w #8,obVelY(a0) — gravity acceleration per frame
    private static final int FALL_GRAVITY = 8;

    // Type 04: subq.w #8,obVelY(a0) — upward acceleration per frame
    private static final int RISE_ACCEL = 8;

    // Type 07: cmpi.w #-2,d0 / cmpi.w #2,d0 — max vertical speed for water tracking
    private static final int WATER_TRACK_MAX_SPEED = 2;

    // loc_12180: cmpi.b #$40,objoff_3E(a0) — max sine angle for sink effect
    private static final int SINK_MAX_ANGLE = 0x40;

    // loc_12180: addq.b #4,objoff_3E(a0) / subq.b #4,objoff_3E(a0)
    private static final int SINK_ANGLE_STEP = 4;

    // loc_12180: move.w #$400,d1 — amplitude multiplier for sine-based sink
    private static final int SINK_AMPLITUDE = 0x400;

    // Dynamic position
    private int x;
    private int y;

    // Saved base positions (lblk_origX = objoff_34, lblk_origY = objoff_30)
    private int origX;
    private int origY;

    // Visual properties
    private int halfWidth;    // obActWid
    private int halfHeight;   // obHeight
    private int mappingFrame; // obFrame

    // lblk_time (objoff_36): countdown timer for delayed activation
    private int delayTimer;

    // lblk_untouched (objoff_38): flag - block has not been stood on yet
    private boolean untouched;

    // objoff_3E: sine angle for the gradual sink effect (loc_12180)
    private int sinkAngle;

    // objoff_3F: solid contact result from SolidObject (0=none, 1=side, -1=top)
    private int solidContactResult;
    private boolean playerStanding;

    // Movement subtype (low nybble, may change during gameplay)
    private int moveType;

    // Y velocity for falling/rising types
    private int yVelocity;

    // 16.16 subpixel state for SpeedToPos Y position updates.
    private final SubpixelMotion.State fallMotion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    public Sonic1LabyrinthBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LabyrinthBlock");

        int fullSubtype = spawn.subtype() & 0xFF;

        // LBlk_Var lookup: lsr.w #3,d0 / andi.w #$E,d0 -> varIndex = (subtype >> 4) & 7
        // Then lea LBlk_Var(pc,d0.w),a2 reads 2 bytes (halfWidth, halfHeight)
        int varIndex = (fullSubtype >> 4) & 0x07;
        if (varIndex >= LBLK_VAR.length) {
            varIndex = 0;
        }
        this.halfWidth = LBLK_VAR[varIndex][0];
        this.halfHeight = LBLK_VAR[varIndex][1];

        // lsr.w #1,d0 -> frame = varIndex (after the shift/mask, d0 already was varIndex*2,
        // then lsr.w #1 gives varIndex)
        this.mappingFrame = varIndex;

        this.x = spawn.x();
        this.y = spawn.y();
        this.origX = spawn.x();
        this.origY = spawn.y();

        this.delayTimer = 0;
        this.sinkAngle = 0;
        this.solidContactResult = 0;
        this.playerStanding = false;
        this.yVelocity = 0;

        // andi.b #$F,d0 -> moveType = low nybble
        this.moveType = fullSubtype & 0x0F;

        // Subtypes 1-6 start with lblk_untouched = 1
        // beq.s LBlk_Action (subtype 0 -> no untouched flag)
        // cmpi.b #7,d0 / beq.s LBlk_Action (subtype 7 -> no untouched flag)
        this.untouched = (moveType != 0 && moveType != 7);

        updateDynamicSpawn(x, y);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }
    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // LBlk_Action: save X on stack, dispatch movement, then SolidObject + sink effect
        int prevX = x;

        // Dispatch movement by subtype
        applyMovement();

        SolidCheckpointBatch batch = checkpointAll();
        updateContactState(batch);

        // loc_12180: Gradual sink effect for untouched blocks while Sonic stands on them
        applySinkEffect();

        updateDynamicSpawn(x, y);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_LABYRINTH_BLOCK);
        if (renderer == null) return;
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly:
        // moveq #0,d1 / move.b obActWid(a0),d1 / addi.w #$B,d1 -> d1 = halfWidth + 11
        // moveq #0,d2 / move.b obHeight(a0),d2 -> d2 = halfHeight
        // move.w d2,d3 / addq.w #1,d3 -> d3 = halfHeight + 1
        // bsr.w SolidObject
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Move reads the block SST's obActWid byte from LBlk_Var; the
        // extra $B belongs only to SolidObject's collision width.
        return halfWidth;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.fullSolid(usesStickyContactBuffer(), true, false);
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // LBlk_Action calls SolidObject, which stores the standing/pushing bits
        // in this block's own SST status(a0) byte (`bset #5,obStatus(a0)` in
        // Solid_AlignToSide, `bclr #5,obStatus(a0)` in Solid_NotPushing --
        // docs/s1disasm/_incObj/sub SolidObject.asm:240-241, 262-263). The
        // moving subtypes (LBlk_Sink / LBlk_Rise / LBlk_SideSink / LBlk_OnWater)
        // rebuild the engine's dynamic spawn every frame the block travels, so a
        // spawn-keyed latch is written under one position and looked up under
        // the next -- Solid_NoCollision then never finds the object's pushing
        // bit, and its Solid_NotPushing tail (with retail's FixBugs=0
        // `move.w #id_Run,obAnim(a1)`) never runs. Same reasoning as the Obj56
        // floating blocks.
        return true;
    }

    @Override
    public boolean isTopSolidOnly() {
        // SolidObject provides all-sides solidity
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Contact state is driven via manual checkpoints in update().
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // out_of_range.w DeleteObject,lblk_origX(a0)
        return !isDestroyed() && isInRangeAt(origX);
    }

    @Override
    public String traceDebugDetails() {
        return String.format(
                "type=%X hw=%d hh=%d stand=%s contact=%d untouched=%s sink=%02X time=%d yv=%04X orig=@%04X,%04X",
                moveType,
                halfWidth,
                halfHeight,
                playerStanding,
                solidContactResult,
                untouched,
                sinkAngle & 0xFF,
                delayTimer,
                yVelocity & 0xFFFF,
                origX & 0xFFFF,
                origY & 0xFFFF);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision box
        ctx.drawRect(x, y, halfWidth + 0x0B, halfHeight, 0.3f, 0.6f, 1.0f);

        // Label with type info
        String label = String.format("LB t%X", moveType);
        if (untouched) {
            label += " UT";
        }
        if (delayTimer > 0) {
            label += String.format(" t=%d", delayTimer);
        }
        ctx.drawWorldLabel(x, y - halfHeight - 8, 0, label, com.openggf.debug.DebugColor.CYAN);
    }

    // ========================================
    // Movement dispatch
    // ========================================

    /**
     * Dispatches to the correct movement handler based on moveType.
     * From disassembly: .index jump table in LBlk_Action.
     */
    private void applyMovement() {
        switch (moveType) {
            case 0x00 -> { /* .type00: stationary (rts) */ }
            case 0x01, 0x03 -> moveTypeWaitForStand();
            case 0x02, 0x06 -> moveTypeFall();
            case 0x04 -> moveTypeRise();
            case 0x05 -> moveTypeWaitForPush();
            case 0x07 -> moveTypeWaterFloat();
        }
    }

    /**
     * Types 01/03: Wait for Sonic to stand on the block, then start a 30-frame timer.
     * When the timer expires, advance to the next subtype (02 or 04).
     * <pre>
     * .type01:
     * .type03:
     *   tst.w   lblk_time(a0)   ; does time remain?
     *   bne.s   .wait01         ; if yes, branch
     *   btst    #3,obStatus(a0) ; is Sonic standing on the object?
     *   beq.s   .donothing01    ; if not, branch
     *   move.w  #30,lblk_time(a0) ; wait for half second
     * .donothing01:
     *   rts
     * .wait01:
     *   subq.w  #1,lblk_time(a0) ; decrement waiting time
     *   bne.s   .donothing01    ; if time remains, branch
     *   addq.b  #1,obSubtype(a0); goto .type02 or .type04
     *   clr.b   lblk_untouched(a0) ; flag block as touched
     * </pre>
     */
    private void moveTypeWaitForStand() {
        if (delayTimer != 0) {
            // .wait01: decrement timer
            delayTimer--;
            if (delayTimer == 0) {
                // Timer expired -> advance subtype
                moveType++;
                untouched = false;
            }
            return;
        }

        // Check if player is standing on this object (obStatus bit 3)
        if (playerStanding) {
            // Start the 30-frame delay
            delayTimer = STAND_DELAY_FRAMES;
        }
    }

    /**
     * Types 02/06: Fall with gravity until hitting the floor, then become stationary.
     * <pre>
     * .type02:
     * .type06:
     *   bsr.w   SpeedToPos
     *   addq.w  #8,obVelY(a0)  ; apply gravity
     *   bsr.w   ObjFloorDist
     *   tst.w   d1             ; has block hit the floor?
     *   bpl.w   .nofloor02     ; if not, branch
     *   addq.w  #1,d1
     *   add.w   d1,obY(a0)    ; snap to floor surface
     *   clr.w   obVelY(a0)    ; stop
     *   clr.b   obSubtype(a0) ; set type to 00 (stationary)
     * </pre>
     */
    private void moveTypeFall() {
        // SpeedToPos: apply Y velocity to position
        applySpeedToPosY();

        // addq.w #8,obVelY(a0) — apply gravity
        yVelocity = (short) (yVelocity + FALL_GRAVITY);

        // ObjFloorDist: check floor from object bottom
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, halfHeight);
        if (floor.foundSurface() && floor.distance() < 0) {
            // Hit floor — snap to surface and stop
            // addq.w #1,d1 / add.w d1,obY(a0) — adjust by (distance + 1)
            y += floor.distance() + 1;
            yVelocity = 0;
            fallMotion.ySub = 0;
            moveType = 0x00; // become stationary
        }
    }

    /**
     * Type 04: Rise with upward acceleration until hitting the ceiling, then become stationary.
     * <pre>
     * .type04:
     *   bsr.w   SpeedToPos
     *   subq.w  #8,obVelY(a0)  ; apply upward acceleration
     *   bsr.w   ObjHitCeiling
     *   tst.w   d1             ; has block hit the ceiling?
     *   bpl.w   .noceiling04   ; if not, branch
     *   sub.w   d1,obY(a0)    ; snap to ceiling surface
     *   clr.w   obVelY(a0)    ; stop
     *   clr.b   obSubtype(a0) ; set type to 00 (stationary)
     * </pre>
     */
    private void moveTypeRise() {
        // SpeedToPos: apply Y velocity to position
        applySpeedToPosY();

        // subq.w #8,obVelY(a0) — apply upward acceleration
        yVelocity = (short) (yVelocity - RISE_ACCEL);

        // ObjHitCeiling: check ceiling from object top
        TerrainCheckResult ceiling = ObjectTerrainUtils.checkCeilingDist(x, y, halfHeight);
        if (ceiling.foundSurface() && ceiling.distance() < 0) {
            // Hit ceiling — snap and stop
            // sub.w d1,obY(a0) — note: subtract because ceiling distance is negative
            y -= ceiling.distance();
            yVelocity = 0;
            fallMotion.ySub = 0;
            moveType = 0x00; // become stationary
        }
    }

    /**
     * Type 05: Wait for Sonic to push (side contact), then advance to type 06 (fall).
     * <pre>
     * .type05:
     *   cmpi.b  #1,objoff_3F(a0) ; is Sonic touching the block from the side?
     *   bne.s   .notouch05       ; if not, branch
     *   addq.b  #1,obSubtype(a0) ; goto .type06
     *   clr.b   lblk_untouched(a0)
     * .notouch05:
     *   rts
     * </pre>
     */
    private void moveTypeWaitForPush() {
        // cmpi.b #1,objoff_3F(a0) — check if player is pushing from the side
        if (solidContactResult == 1) {
            moveType = 0x06; // advance to falling type
            untouched = false;
        }
    }

    /**
     * Type 07: Float with water level (cork block).
     * The block tracks the current water surface level, clamped by floor and ceiling.
     * Movement speed is limited to 2 pixels per frame in either direction.
     * <pre>
     * .type07:
     *   move.w  (v_waterpos1).w,d0
     *   sub.w   obY(a0),d0      ; d0 = waterLevel - blockY
     *   beq.s   .stop07         ; if level with water, stop
     *   bcc.s   .fall07         ; if block above water (d0 > 0), fall
     *   ; Block below water — rise
     *   cmpi.w  #-2,d0
     *   bge.s   .loc_1214E
     *   moveq   #-2,d0          ; clamp to -2 px/frame
     * .loc_1214E:
     *   add.w   d0,obY(a0)      ; rise toward water level
     *   bsr.w   ObjHitCeiling   ; check ceiling
     *   tst.w   d1
     *   bpl.w   .noceiling07
     *   sub.w   d1,obY(a0)      ; snap to ceiling
     * .noceiling07:
     *   rts
     * .fall07:
     *   cmpi.w  #2,d0
     *   ble.s   .loc_1216A
     *   moveq   #2,d0           ; clamp to +2 px/frame
     * .loc_1216A:
     *   add.w   d0,obY(a0)      ; sink toward water level
     *   bsr.w   ObjFloorDist    ; check floor
     *   tst.w   d1
     *   bpl.w   .stop07
     *   addq.w  #1,d1
     *   add.w   d1,obY(a0)      ; snap to floor
     * .stop07:
     *   rts
     * </pre>
     */
    private void moveTypeWaterFloat() {
        int waterLevel = getWaterLevel();
        int d0 = waterLevel - y;

        if (d0 == 0) {
            // Block is level with water — stop
            return;
        }

        if (d0 > 0) {
            // Block is above water — fall (sink) toward water level
            // bcc.s .fall07 (carry clear means positive result)
            if (d0 > WATER_TRACK_MAX_SPEED) {
                d0 = WATER_TRACK_MAX_SPEED;
            }
            y += d0;

            // ObjFloorDist: check floor
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, halfHeight);
            if (floor.foundSurface() && floor.distance() < 0) {
                // addq.w #1,d1 / add.w d1,obY(a0)
                y += floor.distance() + 1;
            }
        } else {
            // Block is below water — rise toward water level
            if (d0 < -WATER_TRACK_MAX_SPEED) {
                d0 = -WATER_TRACK_MAX_SPEED;
            }
            y += d0;

            // ObjHitCeiling: check ceiling
            TerrainCheckResult ceiling = ObjectTerrainUtils.checkCeilingDist(x, y, halfHeight);
            if (ceiling.foundSurface() && ceiling.distance() < 0) {
                // sub.w d1,obY(a0)
                y -= ceiling.distance();
            }
        }
    }

    // ========================================
    // Sink effect (loc_12180)
    // ========================================

    /**
     * Applies the gradual sink effect for untouched blocks.
     * When Sonic stands on an untouched block, the sine angle increases by 4 per frame
     * up to $40, causing the block to gradually sink. When Sonic steps off, the angle
     * decreases by 4 per frame back to 0.
     * <pre>
     * loc_12180:
     *   tst.b   lblk_untouched(a0)  ; has block been touched?
     *   beq.s   locret_121C0        ; if yes, skip
     *   btst    #3,obStatus(a0)     ; is Sonic standing?
     *   bne.s   loc_1219A           ; if yes, increase angle
     *   tst.b   objoff_3E(a0)       ; is angle > 0?
     *   beq.s   locret_121C0        ; if not, skip
     *   subq.b  #4,objoff_3E(a0)   ; decrease angle
     *   bra.s   loc_121A6           ; apply sine offset
     * loc_1219A:
     *   cmpi.b  #$40,objoff_3E(a0) ; at max angle?
     *   beq.s   locret_121C0       ; if yes, skip
     *   addq.b  #4,objoff_3E(a0)  ; increase angle
     * loc_121A6:
     *   move.b  objoff_3E(a0),d0
     *   jsr     (CalcSine).l        ; d0 = sin(angle)
     *   move.w  #$400,d1
     *   muls.w  d1,d0               ; d0 = sin(angle) * $400
     *   swap    d0                   ; d0 >>= 16
     *   add.w   lblk_origY(a0),d0
     *   move.w  d0,obY(a0)
     * </pre>
     */
    private void applySinkEffect() {
        if (!untouched) {
            return;
        }

        if (playerStanding) {
            // loc_1219A: increase angle
            if (sinkAngle >= SINK_MAX_ANGLE) {
                return; // already at max
            }
            sinkAngle += SINK_ANGLE_STEP;
        } else {
            // Not standing — decrease angle
            if (sinkAngle <= 0) {
                return; // no sink offset active
            }
            sinkAngle -= SINK_ANGLE_STEP;
        }

        // loc_121A6: apply sine offset
        // CalcSine returns 8.8 fixed-point sine value
        int sineValue = TrigLookupTable.sinHex(sinkAngle);

        // muls.w d1,d0 -> d0 = sin(angle) * $400 (signed 16x16->32)
        int offset = sineValue * SINK_AMPLITUDE;

        // swap d0 -> d0 >>= 16 (get high word of 32-bit result)
        offset >>= 16;

        // add.w lblk_origY(a0),d0 / move.w d0,obY(a0)
        y = origY + offset;
    }

    private void updateContactState(SolidCheckpointBatch batch) {
        playerStanding = false;
        solidContactResult = 0;
        for (PlayerSolidContactResult result : batch.perPlayer().values()) {
            if (result == null) {
                continue;
            }
            if (result.standingNow()) {
                playerStanding = true;
                solidContactResult = -1;
                return;
            }
            if (result.pushingNow() || result.kind() == com.openggf.game.solid.ContactKind.SIDE) {
                solidContactResult = 1;
            }
        }
    }

    // ========================================
    // Helper methods
    // ========================================

    /**
     * SpeedToPos for Y axis — applies yVelocity to y position using 16.16 fixed-point.
     * Delegates to {@link SubpixelMotion#speedToPosY(SubpixelMotion.State)}.
     */
    private void applySpeedToPosY() {
        if (yVelocity == 0) return;
        fallMotion.y = y;
        fallMotion.yVel = yVelocity;
        SubpixelMotion.speedToPosY(fallMotion);
        y = fallMotion.y;
    }

    /**
     * Gets the current water level Y position from the WaterSystem.
     * Equivalent to ROM's move.w (v_waterpos1).w,d0.
     */
    private int getWaterLevel() {
        if (services().currentLevel() == null) {
            return 0;
        }
        var waterSystem = services().waterSystem();
        int zoneId = services().featureZoneId();
        int actId = services().featureActId();
        return waterSystem.getVisualWaterLevelY(zoneId, actId);
    }
}
