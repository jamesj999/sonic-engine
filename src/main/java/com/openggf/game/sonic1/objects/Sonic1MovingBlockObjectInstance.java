package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.SolidCheckpointBatch;

import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 52 - Moving platform blocks (MZ, LZ, SBZ).
 * <p>
 * Top-solid platform blocks with multiple zone-specific visual appearances
 * and movement subtypes. Used extensively in Marble Zone, Labyrinth Zone,
 * and Scrap Brain Zone.
 * <p>
 * Subtype high nybble selects visual appearance (via MBlock_Var table):
 * <ul>
 *   <li>0x0x: width=$10, frame 0 (MZ 32x16 block / LZ 32x16 block)</li>
 *   <li>0x1x: width=$20, frame 1 (MZ 64x16 double block)</li>
 *   <li>0x2x: width=$20, frame 2 (SBZ 64x16 short blocks)</li>
 *   <li>0x3x: width=$40, frame 3 (SBZ 128x16 wide block)</li>
 *   <li>0x4x: width=$30, frame 4 (MZ 96x16 triple block)</li>
 * </ul>
 * <p>
 * Subtype low nybble selects movement behavior:
 * <ul>
 *   <li>0x00: Stationary</li>
 *   <li>0x01: Horizontal oscillation (v_oscillate+$E, amplitude $60)</li>
 *   <li>0x02: Wait for player to stand on, then advance to type 03</li>
 *   <li>0x03: Slide right until hitting a wall, then become stationary</li>
 *   <li>0x04: Same as type 02 (shared jump table entry)</li>
 *   <li>0x05: Slide right until hitting a wall, then advance to type 06 (fall)</li>
 *   <li>0x06: Fall with gravity until floor, then become stationary</li>
 *   <li>0x07: Switch-activated - wait for switch 02, then change to type 04</li>
 *   <li>0x08: Vertical oscillation (v_oscillate+$1E, amplitude $80)</li>
 *   <li>0x09: Same as type 02 (shared jump table entry)</li>
 *   <li>0x0A: Shuttle back and forth at 8px/frame, 5-second pause at end</li>
 * </ul>
 * <p>
 * Zone-specific art:
 * <ul>
 *   <li>MZ: Nem_MzBlock (ArtTile_MZ_Block=$2B8, palette 2)</li>
 *   <li>LZ: Nem_LzBlock3 (ArtTile_LZ_Moving_Block=$3BC, palette 2) + obHeight=7</li>
 *   <li>SBZ subtype $28: Nem_Stomper (ArtTile_SBZ_Moving_Block_Short=$2C0, palette 1)</li>
 *   <li>SBZ other: Nem_SlideFloor (ArtTile_SBZ_Moving_Block_Long=$460, palette 2)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/52 Moving Blocks.asm
 */
public class Sonic1MovingBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // ---- MBlock_Var table: {obActWid, obFrame} indexed by (subtype >> 4) ----
    // From disassembly: dc.b $10, 0 / dc.b $20, 1 / dc.b $20, 2 / dc.b $40, 3 / dc.b $30, 4
    private static final int[][] MBLOCK_VAR = {
            {0x10, 0}, // subtypes 0x0x
            {0x20, 1}, // subtypes 0x1x
            {0x20, 2}, // subtypes 0x2x
            {0x40, 3}, // subtypes 0x3x
            {0x30, 4}, // subtypes 0x4x
    };

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Platform surface half-height for solid collision
    private static final int HALF_HEIGHT = 0x08;
    // ROM: MvSonicOnPtfm2 hardcodes "subi.w #9,d0" for riding Y snap.
    // The standing calculation is objY - 9 - yRadius, NOT objY - HALF_HEIGHT - yRadius.
    private static final int MV_SONIC_OFFSET = 9;

    // Type 01: move.w #$60,d1 — horizontal oscillation amplitude
    private static final int TYPE01_AMPLITUDE = 0x60;
    // Type 01: v_oscillate+$E — oscillator index (oscillator 3)
    private static final int TYPE01_OSC_INDEX = 0x0C;

    // Type 03/05: gravity for falling (addi.w #$18,obVelY)
    private static final int FALL_GRAVITY = 0x18;

    // Type 08: move.w #$80,d1 — vertical oscillation amplitude
    private static final int TYPE08_AMPLITUDE = 0x80;
    // Type 08: v_oscillate+$1E — oscillator index (oscillator 7)
    private static final int TYPE08_OSC_INDEX = 0x1C;

    // Type 0A: moveq #8,d1 — shuttle movement speed per frame
    private static final int TYPE0A_SPEED = 8;
    // Type 0A: move.w #300,objoff_34(a0) — 5-second delay at endpoint (300 frames)
    private static final int TYPE0A_DELAY = 300;

    // Zone IDs for art selection
    private int zoneIndex;

    // Dynamic position
    private int x;
    private int y;

    // Saved base positions (mblock_origX = objoff_30, mblock_origY = objoff_32)
    private int origX;
    private int origY;

    // Movement subtype (low nybble of obSubtype, may change during gameplay)
    private int moveType;

    // Visual properties
    private int activeWidth;   // obActWid
    private int mappingFrame;  // obFrame

    // Routine state: 2 = MBlock_Platform, 4 = MBlock_StandOn
    // In routine 4, the player is standing on the platform
    private boolean playerStanding;

    // Y velocity for type 06 (falling)
    private int yVelocity;
    // 16.16 subpixel state for SpeedToPos Y position updates during falling.
    private final SubpixelMotion.State fallMotion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    // Type 0A state: timer countdown at endpoint (objoff_34)
    private int shuttleTimer;
    // Type 0A state: whether moving back to origin (objoff_36)
    private boolean shuttleReturning;

    // Which art key to use for this zone
    private String artKey;

    private boolean initialized;

    public Sonic1MovingBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MovingBlock");

        int fullSubtype = spawn.subtype() & 0xFF;

        // MBlock_Var lookup: lsr.w #3,d0 / andi.w #$1E,d0
        int varIndex = (fullSubtype >> 4) & 0x0F;
        if (varIndex >= MBLOCK_VAR.length) {
            varIndex = 0;
        }
        this.activeWidth = MBLOCK_VAR[varIndex][0];
        this.mappingFrame = MBLOCK_VAR[varIndex][1];

        // andi.b #$F,obSubtype(a0) — mask to low nybble for movement type
        this.moveType = fullSubtype & 0x0F;

        this.x = spawn.x();
        this.y = spawn.y();
        this.origX = spawn.x();
        this.origY = spawn.y();

        this.playerStanding = false;
        this.yVelocity = 0;
        this.shuttleTimer = 0;
        this.shuttleReturning = false;

        updateDynamicSpawn(x, y);
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        this.zoneIndex = services().romZoneId();
        // Select art key based on zone
        this.artKey = selectArtKey(spawn.subtype() & 0xFF);
    }

    /**
     * Selects the correct art key based on zone and subtype.
     * <p>
     * MZ: Nem_MzBlock -> MZ_MOVING_BLOCK (same art as push/smash blocks)
     * LZ: Nem_LzBlock3 -> LZ_MOVING_BLOCK (separate art)
     * SBZ $28: Nem_Stomper -> SBZ_MOVING_BLOCK_SHORT
     * SBZ other: Nem_SlideFloor -> SBZ_MOVING_BLOCK_LONG
     */
    private String selectArtKey(int fullSubtype) {
        if (zoneIndex == Sonic1Constants.ZONE_LZ) {
            return ObjectArtKeys.LZ_MOVING_BLOCK;
        }
        if (zoneIndex == Sonic1Constants.ZONE_SBZ) {
            if (fullSubtype == 0x28) {
                return ObjectArtKeys.SBZ_MOVING_BLOCK_SHORT;
            }
            return ObjectArtKeys.SBZ_MOVING_BLOCK_LONG;
        }
        // MZ and any other zone use MZ block art
        return ObjectArtKeys.MZ_MOVING_BLOCK;
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
        ensureInitialized();
        // ROM order:
        // - routine 2: MBlock_Move, then PlatformObject
        // - routine 4: ExitPlatform, save X, MBlock_Move, then MvSonicOnPtfm2
        // So the platform moves first, and the standing latch that gates
        // type 02/04/09 advancement is effectively the prior frame's state.
        applyMovement();

        updateDynamicSpawn(x, y);

        SolidCheckpointBatch batch = checkpointAll();
        playerStanding = hasStandingContact(batch);
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(activeWidth, HALF_HEIGHT, MV_SONIC_OFFSET);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM MBlock_Platform (routine 2) passes `move.b obActWid(a0),d1` directly
        // into PlatformObject, whose X-range landing check uses that d1 as the full
        // landing half-width (`add.w d1,d0 / bmi Plat_Exit; add.w d1,d1 / cmp d1,d0
        // / bhs Plat_Exit` -> land range [objX-obActWid, objX+obActWid),
        // docs/s1disasm/_incObj/52 Moving Blocks.asm:57-61; sub PlatformObject.asm:27-34).
        // obActWid (0x10 here) is already the standable half-width, so it must NOT
        // receive the generic SolidObjectFull `-$B` narrowing (which would shrink the
        // landing width to obActWid-0xB=0x5). Without this, a player landing more than
        // 5px from the block centre was rejected -- MZ3 f8646: a falling rolling player
        // at relX 0xC from the rightward moving block (Obj 0x52 @0x1289) was outside the
        // narrowed 0x5 landing width, so resolveContact returned no contact and he fell
        // through. Same obActWid-direct landing-width pattern as the swing platform and
        // CollapsingFloor PlatformObject paths.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM MBlock_Platform routes the landing through PlatformObject ->
        // Plat_NoXCheck_AltY, whose land band is gated by an UNSIGNED
        // `cmpi.w #-16,d0 / blo Plat_Exit` (docs/s1disasm/_incObj/sub
        // PlatformObject.asm:51-52). That rejects the exact-touch case d0 = 0
        // (0x0000 <u 0xFFF0): the standable band is d0 in [-16,-1] (strict
        // penetration -- the feet must be at least 1px below the surface). The
        // engine's default accepts d0 = 0 (S3K SolidObjectTop_1P semantics), so a
        // player whose feet land exactly on the surface seated ONE FRAME EARLY vs
        // ROM. MZ3 f8973: falling-right onto the moving block (Obj 0x52 @0x13CF,
        // d0 = 0, feet 0x07B0 == surface 0x07B0) the engine grounded at f8973 while
        // ROM stays airborne and lands at f8974 (d0 <= -1). Mirrors the same
        // PlatformObject-family override on Obj 18 / Obj 53 / Obj 5A / Obj 63 / Obj
        // 6C / Obj 1A. Object-local to Obj 0x52.
        return true;
    }

    @Override
    public boolean carriesAirborneRiderAfterExitPlatform() {
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // ROM Obj52 evaluates its solid helper at a different point depending on
        // whether the player is already standing on the block:
        //   - Routine 2 (MBlock_Platform, no rider): MBlock_Move runs FIRST, then
        //     PlatformObject, so a fresh landing observes the block's POST-move
        //     x_pos (docs/s1disasm/_incObj/52 Moving Blocks.asm:55-61).
        //   - Routine 4 (MBlock_StandOn, rider): ExitPlatform runs FIRST (the
        //     walk-off bounds check), then MBlock_Move, then MvSonicOnPtfm2 (same
        //     file:64-83). ExitPlatform therefore observes the block's PRE-move
        //     x_pos (docs/s1disasm/_incObj/sub ExitPlatform.asm:24-29).
        // The engine moves the block in update() before the solid checkpoint, so
        // the continued-ride bounds check must use the pre-update x to match
        // ExitPlatform's pre-move evaluation; the fresh-landing PlatformObject
        // check keeps the post-move x. Without the pre-move bounds for the rider,
        // an oscillating block carried the rider one frame too long at the right
        // edge (MZ2 f8661: ROM clears Status_OnObj at f8660 and goes airborne at
        // f8661; the engine walked off one frame late and re-landed instead of
        // ever going airborne). The carry delta (currentX - ridingX) is unchanged
        // because it is still measured against the same pre-move ridingX.
        ObjectManager objectManager = services().objectManager();
        return objectManager != null && objectManager.isRidingObject(player, this);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing state is driven via manual checkpoints in update().
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
        // out_of_range.w DeleteObject,mblock_origX(a0)
        return !isDestroyed() && isInRangeAt(origX);
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return origX;
    }

    @Override
    public boolean clearsRespawnStateOnCounterBasedOutOfRange() {
        // docs/s1disasm/s1disasm/_incObj/52 Moving Blocks.asm:
        // MBlock_ChkDel uses `out_of_range.w DeleteObject,mblock_origX(a0)`.
        // It does not call RememberState, so bit 7 in v_objstate remains set.
        return false;
    }

    // ========================================
    // Movement dispatch (MBlock_Move)
    // ========================================

    /**
     * Dispatches to the correct movement handler based on moveType.
     * From disassembly: MBlock_TypeIndex jump table.
     */
    private void applyMovement() {
        switch (moveType) {
            case 0x00 -> { /* MBlock_Type00: stationary (rts) */ }
            case 0x01 -> moveType01HorizontalOscillation();
            case 0x02, 0x04, 0x09 -> moveType02WaitForStand();
            case 0x03 -> moveType03SlideRight();
            case 0x05 -> moveType05SlideRightThenFall();
            case 0x06 -> moveType06Falling();
            case 0x07 -> moveType07SwitchActivated();
            case 0x08 -> moveType08VerticalOscillation();
            case 0x0A -> moveType0AShuttle();
        }
    }

    /**
     * MBlock_Type01: Horizontal oscillation using v_oscillate+$E.
     * <pre>
     * MBlock_Type01:
     *   move.b  (v_oscillate+$E).w,d0
     *   move.w  #$60,d1
     *   btst    #0,obStatus(a0)
     *   beq.s   loc_FF26
     *   neg.w   d0
     *   add.w   d1,d0
     * loc_FF26:
     *   move.w  mblock_origX(a0),d1
     *   sub.w   d0,d1
     *   move.w  d1,obX(a0)
     * </pre>
     */
    private void moveType01HorizontalOscillation() {
        // move.b (v_oscillate+$E).w,d0 — unsigned byte, d0.w = 0x00XX
        int d0 = OscillationManager.getByte(TYPE01_OSC_INDEX) & 0xFF;
        // btst #0,obStatus(a0) — status bit 0 = x-flip flag (from spawn renderFlags bit 0)
        boolean flipped = (spawn.renderFlags() & 1) != 0;
        if (flipped) {
            // neg.w d0 / add.w d1,d0 — 16-bit arithmetic
            d0 = (short) ((-d0 + TYPE01_AMPLITUDE) & 0xFFFF);
        }
        // move.w mblock_origX(a0),d1 / sub.w d0,d1 — 16-bit subtraction
        x = origX - (short) d0;
    }

    /**
     * MBlock_Type02 / Type04 / Type09: Wait for player to stand on, then advance subtype.
     * <pre>
     * MBlock_Type02:
     *   cmpi.b  #4,obRoutine(a0)
     *   bne.s   MBlock_02_Wait
     *   addq.b  #1,obSubtype(a0)
     * MBlock_02_Wait:
     *   rts
     * </pre>
     */
    private void moveType02WaitForStand() {
        if (playerStanding) {
            // addq.b #1,obSubtype(a0)
            moveType++;
        }
    }

    /**
     * MBlock_Type03: Slide right until wall hit, then become stationary.
     * <pre>
     * MBlock_Type03:
     *   moveq   #0,d3
     *   move.b  obActWid(a0),d3
     *   bsr.w   ObjHitWallRight
     *   tst.w   d1
     *   bmi.s   MBlock_03_End
     *   addq.w  #1,obX(a0)
     *   move.w  obX(a0),mblock_origX(a0)
     *   rts
     * MBlock_03_End:
     *   clr.b   obSubtype(a0)
     *   rts
     * </pre>
     */
    private void moveType03SlideRight() {
        // ObjHitWallRight: check wall from right edge
        TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(
                x + activeWidth, y);
        if (wall.foundSurface() && wall.distance() < 0) {
            // Hit a wall — become stationary
            moveType = 0x00;
            return;
        }
        // Move right 1 pixel per frame
        x++;
        origX = x;
    }

    /**
     * MBlock_Type05: Slide right until wall hit, then advance to falling (type 06).
     * <pre>
     * MBlock_Type05:
     *   moveq   #0,d3
     *   move.b  obActWid(a0),d3
     *   bsr.w   ObjHitWallRight
     *   tst.w   d1
     *   bmi.s   MBlock_05_End
     *   addq.w  #1,obX(a0)
     *   move.w  obX(a0),mblock_origX(a0)
     *   rts
     * MBlock_05_End:
     *   addq.b  #1,obSubtype(a0)
     *   rts
     * </pre>
     */
    private void moveType05SlideRightThenFall() {
        TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(
                x + activeWidth, y);
        if (wall.foundSurface() && wall.distance() < 0) {
            // Hit a wall — advance to falling
            moveType = 0x06;
            return;
        }
        x++;
        origX = x;
    }

    /**
     * MBlock_Type06: Falling with gravity until floor, then become stationary.
     * <pre>
     * MBlock_Type06:
     *   bsr.w   SpeedToPos
     *   addi.w  #$18,obVelY(a0)
     *   bsr.w   ObjFloorDist
     *   tst.w   d1
     *   bpl.w   locret_FFA0
     *   add.w   d1,obY(a0)
     *   clr.w   obVelY(a0)
     *   clr.b   obSubtype(a0)
     * </pre>
     */
    private void moveType06Falling() {
        // SpeedToPos: apply Y velocity to position
        applySpeedToPosY();

        // addi.w #$18,obVelY(a0) — apply gravity
        yVelocity = (short) (yVelocity + FALL_GRAVITY);

        // ObjFloorDist: check floor from object bottom
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, HALF_HEIGHT);
        if (floor.foundSurface() && floor.distance() < 0) {
            // Hit floor — snap to surface and stop
            y += floor.distance();
            yVelocity = 0;
            fallMotion.ySub = 0;
            moveType = 0x00;
        }
    }

    /**
     * MBlock_Type07: Switch-activated — wait for switch 02, then change to type 04.
     * <p>
     * NOTE: This type manipulates the stack in the ROM (addq.l #4,sp) to skip
     * back to MBlock_ChkDel instead of returning normally through MBlock_StandOn.
     * In the engine, we handle this by checking out-of-range directly instead.
     * <pre>
     * MBlock_Type07:
     *   tst.b   (f_switch+2).w
     *   beq.s   MBlock_07_ChkDel
     *   subq.b  #3,obSubtype(a0)   ; type 7 - 3 = type 4
     * MBlock_07_ChkDel:
     *   addq.l  #4,sp              ; skip return to MBlock_StandOn
     *   out_of_range.w DeleteObject,mblock_origX(a0)
     *   rts
     * </pre>
     */
    private void moveType07SwitchActivated() {
        // f_switch+2 = switch index 2
        if (services().gameService(Sonic1SwitchManager.class).isPressed(2)) {
            // subq.b #3,obSubtype(a0) — type 7 becomes type 4 (wait-for-stand)
            moveType = 0x04;
        }
        // The stack manipulation (addq.l #4,sp) in the ROM makes this type
        // skip the MvSonicOnPtfm2 call. In our engine, the platform physics
        // are handled by the ObjectManager/SolidContacts system, so this
        // difference is automatically managed.
    }

    /**
     * MBlock_Type08: Vertical oscillation using v_oscillate+$1E.
     * <pre>
     * MBlock_Type08:
     *   move.b  (v_oscillate+$1E).w,d0
     *   move.w  #$80,d1
     *   btst    #0,obStatus(a0)
     *   beq.s   loc_FFE2
     *   neg.w   d0
     *   add.w   d1,d0
     * loc_FFE2:
     *   move.w  mblock_origY(a0),d1
     *   sub.w   d0,d1
     *   move.w  d1,obY(a0)
     * </pre>
     */
    private void moveType08VerticalOscillation() {
        // move.b (v_oscillate+$1E).w,d0 — unsigned byte, d0.w = 0x00XX
        int d0 = OscillationManager.getByte(TYPE08_OSC_INDEX) & 0xFF;
        // btst #0,obStatus(a0) — status bit 0 = x-flip flag
        boolean flipped = (spawn.renderFlags() & 1) != 0;
        if (flipped) {
            // neg.w d0 / add.w d1,d0 — 16-bit arithmetic
            d0 = (short) ((-d0 + TYPE08_AMPLITUDE) & 0xFFFF);
        }
        // move.w mblock_origY(a0),d1 / sub.w d0,d1 — 16-bit subtraction
        y = origY - (short) d0;
    }

    /**
     * MBlock_Type0A: Shuttle movement — moves forward, pauses 5 seconds, returns.
     * <pre>
     * MBlock_Type0A:
     *   moveq   #0,d3
     *   move.b  obActWid(a0),d3
     *   add.w   d3,d3            ; distance = 2 * activeWidth
     *   moveq   #8,d1            ; speed = 8 px/frame
     *   btst    #0,obStatus(a0)
     *   beq.s   loc_10004
     *   neg.w   d1               ; reverse direction if flipped
     *   neg.w   d3               ; reverse distance if flipped
     * </pre>
     */
    private void moveType0AShuttle() {
        // add.w d3,d3 — distance = activeWidth * 2
        int distance = activeWidth * 2;
        // moveq #8,d1 — speed = 8 px/frame
        int speed = TYPE0A_SPEED;
        boolean flipped = (spawn.renderFlags() & 1) != 0;
        if (flipped) {
            // neg.w d1 / neg.w d3
            speed = -speed;
            distance = -distance;
        }

        // tst.w objoff_36(a0) / bne.s MBlock_0A_Back
        if (shuttleReturning) {
            // MBlock_0A_Back: move back toward origin
            int offset = (short) ((x - origX) & 0xFFFF);
            if (offset == 0) {
                // MBlock_0A_Reset: back at origin
                shuttleReturning = false;
                // subq.b #1,obSubtype(a0) — decrement type (0A -> 09)
                // Type 09 maps to Type02 (wait-for-stand), making this a one-shot shuttle
                moveType--;
                return;
            }
            // sub.w d1,obX(a0) — move back toward origin
            x -= speed;
            return;
        }

        // Forward movement: check if at endpoint
        // move.w obX(a0),d0 / sub.w mblock_origX(a0),d0 / cmp.w d3,d0
        int offset = (short) ((x - origX) & 0xFFFF);
        if (offset == (short) distance) {
            // MBlock_0A_Wait: at endpoint, count down timer
            // subq.w #1,objoff_34(a0) / bne.s locret
            shuttleTimer--;
            if (shuttleTimer <= 0) {
                // move.w #1,objoff_36(a0) — set returning flag
                shuttleReturning = true;
            }
            return;
        }

        // Not at endpoint: move forward
        // add.w d1,obX(a0) — move platform
        x += speed;
        // move.w #300,objoff_34(a0) — set time delay to 5 seconds
        shuttleTimer = TYPE0A_DELAY;
    }

    // ========================================
    // Physics helpers
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

}
