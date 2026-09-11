package com.openggf.game.sonic1.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.solid.ContactKind;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectInstance;
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
import com.openggf.physics.Direction;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.NativePositionOps;

import com.openggf.debug.DebugColor;
import java.util.Collection;
import java.util.List;

/**
 * Object 0x33 - Pushable Blocks (MZ, LZ).
 * <p>
 * Solid blocks that Sonic can push horizontally. When pushed off a ledge, they fall with
 * gravity. In MZ Act 1, a specific block sits on the chained stomper and follows its Y.
 * In MZ Acts 2-3, pushing blocks to specific X positions spawns lava geysers.
 * <p>
 * Subtypes (from PushB_Var):
 * <ul>
 *   <li>0: Single block (width=$10, frame 0)</li>
 *   <li>1: Row of 4 blocks (width=$40, frame 1, high priority)</li>
 * </ul>
 * <p>
 * The block has a custom solid collision state machine (obSolid / loc_C186):
 * <ul>
 *   <li>State 0: Solid_ChkEnter - detect push, move block + player 1px/frame</li>
 *   <li>State 2: Platform riding (handled by engine SolidContacts)</li>
 *   <li>State 4: Falling with gravity + floor snap (loc_C1AA)</li>
 *   <li>State 6: SpeedToPos + 16px grid alignment (loc_C1F2)</li>
 * </ul>
 * <p>
 * objoff_32 (inMotion) is ONLY set when landing on lava (tile >= $16A).
 * Push-off-ledge uses states 6 → 4 → 0 without setting objoff_32.
 * <p>
 * Reference: docs/s1disasm/_incObj/33 Pushable Blocks.asm
 */
public class Sonic1PushBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.b #$F,obHeight(a0) / move.b #$F,obWidth(a0)
    private static final int HALF_HEIGHT = 0x0F;
    private static final int HALF_WIDTH_COLLISION = 0x0F;

    // Solid params: d1=obActWid+$B, d2=$10, d3=$11
    // For single block: $10+$B = $1B; for 4-block: $40+$B = $4B
    private static final int SOLID_AIR_HALF_HEIGHT = 0x10;
    private static final int SOLID_GROUND_HALF_HEIGHT = 0x11;

    // From disassembly: move.b #3,obPriority(a0)
    private static final int PRIORITY = 3;

    // Gravity: addi.w #$18,obVelY(a0)
    private static final int FALL_GRAVITY = 0x18;

    // Push velocity when falling off ledge: move.w #$400,obVelX(a0)
    private static final int FALL_X_VELOCITY = 0x400;

    // Player push inertia: move.w #$40,d1 / move.w #-$40,d1
    private static final int PUSH_INERTIA = 0x40;

    // Floor tile threshold for lava: cmpi.w #$16A,d0
    private static final int LAVA_TILE_THRESHOLD = 0x16A;

    // Floor distance threshold for ledge detection: cmpi.w #4,d1
    private static final int LEDGE_DISTANCE_THRESHOLD = 4;

    // Slow sink Y increment: addi.l #$2001,obY(a0)
    private static final int SLOW_SINK_INCREMENT = 0x2001;

    // Slow sink delete threshold: cmpi.b #$A0,obY+3(a0)
    private static final int SLOW_SINK_DELETE_THRESHOLD = 0xA0;

    // MZ Act 1 stomper interaction X range
    private static final int STOMPER_X_MIN = 0xA20;
    private static final int STOMPER_X_MAX = 0xAA1;

    // MZ Act 1 stomper Y offset: subi.w #$1C,d0
    private static final int STOMPER_Y_OFFSET = 0x1C;

    // Lava geyser spawn positions (MZ Act 2)
    private static final int[] LAVA_POS_ACT2 = {0xDD0, 0xCC0, 0xBA0};
    // Lava geyser spawn positions (MZ Act 3)
    private static final int[] LAVA_POS_ACT3 = {0x560, 0x5C0};

    // Dynamic position
    private int x;
    private int y;

    // Saved spawn position (objoff_34, objoff_36) for reset
    private int spawnX;
    private int spawnY;

    // Active width from PushB_Var (obActWid)
    private int activeWidth;

    // Mapping frame (0=single, 1=four)
    private int frameIndex;

    // Routine state (0=init, 2=active, 4=offscreen/reset)
    private int routine;

    // Lava motion flag (objoff_32): ONLY set when landing on lava tile.
    // NOT set for normal push-off-ledge (which uses solidState 6 → 4 → 0).
    private boolean inMotion;

    // X velocity for sliding (obVelX, 16-bit signed subpixels)
    private int xVelocity;

    // Y velocity for falling (obVelY, 16-bit signed subpixels)
    private int yVelocity;

    // Push momentum (objoff_30) - stored when block decelerates on 16px grid
    private int pushMomentum;

    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16.16 SpeedToPos integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    // Solid collision state machine (obSolid): 0/2/4/6
    // 0 = idle (Solid_ChkEnter), 2 = riding, 4 = falling, 6 = aligning
    private int solidState;

    // A state-2 ExitPlatform frame skips Solid_ChkCollision, so the object's
    // native Status_Push bit remains available to the next state-0 checkpoint.
    private boolean pushReleasePendingAfterRideExit;

    // MZ Act 1: whether block is chained to stomper (bit 7 of obSubtype)
    private boolean chainedToStomper;

    // Airborne flag (obStatus bit 1): set by geyser launch, cleared on floor contact.
    // This is separate from solidState (obSolid) — the block remains solid while airborne.
    private boolean airborne;

    // Zone/act info cached on first update
    private int zoneIndex;
    private int actIndex;
    private boolean isLZ;
    private boolean isMZ;

    // Art key for rendering (MZ vs LZ)
    private String artKey;

    // Frame counter when push sound was last triggered.
    // ROM's SMPS driver uses f_push_playing flag: sfx_Push is ignored while already
    // playing, and smpsClearPush at the end of the sound data clears the flag.
    // Sound duration: nD1,$07 + nRst,$02 + nD1,$06 + nRst,$10 = 31 frames at tempo $01.
    private static final int PUSH_SOUND_DURATION = 31;
    private int lastPushSoundFrame = -PUSH_SOUND_DURATION;

    // Set when the ROM's second out_of_range check falls through to DeleteObject.
    // ObjectManager then performs the actual unload so counter-based respawn state
    // is cleared through the normal manager path.
    private boolean deletePending;

    private boolean initialized;

    public Sonic1PushBlockObjectInstance(ObjectSpawn spawn) {
        super(spawn, "PushBlock");

        this.spawnX = spawn.x();
        this.spawnY = spawn.y();
        this.x = spawn.x();
        this.y = spawn.y();

        // PushB_Var: subtype*2 indexes into width/frame pairs
        // dc.b $10, 0    ; subtype 0: single block
        // dc.b $40, 1    ; subtype 1: row of 4
        int subtype = spawn.subtype() & 0xFF;
        if (subtype != 0) {
            this.activeWidth = 0x40;
            this.frameIndex = 1;
        } else {
            this.activeWidth = 0x10;
            this.frameIndex = 0;
        }

        this.routine = 2; // Skip init, go straight to active
        this.solidState = 0;
        this.pushReleasePendingAfterRideExit = false;
        this.inMotion = false;
        // ROM keeps obSubtype intact after init; bit 7 gates the ledge/floor
        // check in the push handler (tst.b obSubtype / bmi.s locret_C2E4).
        // In MZ Act 1 the stomper interaction dynamically manages bit 7 every
        // frame (bclr then conditional bset), so the init value is overwritten.
        // In all other acts/zones bit 7 retains its spawn value.
        this.chainedToStomper = (subtype & 0x80) != 0;
        this.deletePending = false;

        updateDynamicSpawn(x, y);
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        this.zoneIndex = services().romZoneId();
        this.actIndex = services().currentAct();
        this.isLZ = (zoneIndex == Sonic1Constants.ZONE_LZ);
        this.isMZ = (zoneIndex == Sonic1Constants.ZONE_MZ);

        // Art key depends on zone
        this.artKey = isLZ ? ObjectArtKeys.LZ_PUSH_BLOCK : ObjectArtKeys.MZ_PUSH_BLOCK;
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
        deletePending = false;
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case 2 -> updateActive(vIntRunCount, player);
            case 4 -> updateOffscreen();
            default -> { }
        }
        updateDynamicSpawn(x, y);
    }

    /**
     * Routine 2 (loc_BF6E): Main active state.
     * <p>
     * ROM flow:
     * <ol>
     *   <li>If objoff_32 != 0 (lava motion): run loc_C046 physics first</li>
     *   <li>Call loc_C186 state machine (always runs, handles states 0/2/4/6)</li>
     *   <li>MZ Act 1 stomper interaction (non-motion path only)</li>
     *   <li>Lava geyser check (motion path only)</li>
     *   <li>Out-of-range / display</li>
     * </ol>
     * <p>
     * States 0 and 2 are delegated to the engine's SolidContacts system
     * (which calls onSolidContact). States 4 and 6 are handled here directly.
     */
    private void updateActive(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM parity: snapshot the entering solidState so we know which loc_C186
        // branch ROM would have taken this frame. ROM's state-4 (loc_C1AA) and
        // state-6 (loc_C1F2) paths return WITHOUT ever calling Solid_ChkEnter,
        // so the engine must skip the inline solid-contact resolution that frame
        // — otherwise it establishes a riding state one frame too early and the
        // platform-rider carry (processInlineRidingObject's shiftX(deltaX)) fires
        // on the very next frame. ROM's MvSonicOnPtfm only runs once obSolid==2,
        // which is set by Solid_Landed inside Solid_ChkEnter — and that happens
        // on a DIFFERENT frame from the state-4 lava landing.
        //
        // Reference: docs/s1disasm/_incObj/33 Pushable Blocks.asm
        //   loc_C1AA (state 4): bsr SpeedToPos / ObjFloorDist / ... / rts
        //   loc_C1F2 (state 6): bsr SpeedToPos / andi ... / subq #2,obSolid / rts
        //   loc_C218 (state 0): bsr Solid_ChkEnter (the only path that calls it)
        int enteringSolidState = solidState;
        int preMoveX = x;

        if (inMotion) {
            // loc_C046: lava sliding physics (only when objoff_32 != 0)
            if (updateLavaMotion(player)) {
                return; // Block was deleted during slow sink
            }
        }

        // loc_C186 state machine: states 4 and 6 handled directly.
        // State 0 is handled by SolidContacts → onSolidContact → handlePush.
        // The d0 displacement is now carried via SolidContact.sideDistX(),
        // computed at the correct time in each SolidContacts pass.
        switch (solidState) {
            case 0 -> { /* handled by SolidContacts callback */ }
            case 4 -> runState4FallWithGravity();
            case 6 -> runState6AlignTo16px();
        }

        // MZ Act 1 stomper interaction (non-motion path only)
        // cmpi.w #(id_MZ<<8)+0,(v_zone).w
        if (!inMotion && isMZ && actIndex == 0) {
            updateStomperInteraction();
        }

        // PushB_ChkLava (motion path only, after state machine)
        if (inMotion) {
            checkLavaGeyser();
        }

        // State 0 owns Solid_ChkCollision. A top landing changes obSolid to 2,
        // which must remain distinct from the player's Status_OnObj bit: another
        // earlier routine can clear Status_OnObj before this object's later slot,
        // but Obj33 still enters its state-2 ExitPlatform branch and returns
        // without falling through to Solid_ChkCollision/Solid_NoCollision.
        if (enteringSolidState == 0) {
            SolidCheckpointBatch batch = checkpointAll();
            pushReleasePendingAfterRideExit = false;
            if (solidState == 0 && !inMotion) {
                applyPushContacts(batch, vIntRunCount);
            }
            if (solidState == 0
                    && hasStandingContact(batch)
                    && isPlayerRidingThisBlock()) {
                solidState = 2;
            }
        } else if (enteringSolidState == 2) {
            int halfWidth = getSolidParams().halfWidth();
            int relativeX = player.getCentreX() - x;
            boolean nativeStandingBitSurvives = player.isOnObject()
                    && !player.getAir()
                    && relativeX >= -halfWidth
                    && relativeX < halfWidth;
            if (!nativeStandingBitSurvives) {
                // PushB_SolidAction.sonicOnBlock calls ExitPlatform, then tests
                // Sonic's live global Status_OnObj. ExitPlatform clears that bit
                // for air/range exits, then Obj33 returns without a same-slot
                // Solid_ChkCollision, leaving its Status_Push bit for the next
                // state-0 checkpoint.
                player.setOnObject(false);
                if (isPlayerRidingThisBlock() && services().objectManager() != null) {
                    services().objectManager().clearRidingObject(player);
                }
                solidState = 0;
                pushReleasePendingAfterRideExit = true;
            } else {
                // PushB_OnLava saves pre-move X in d4, moves the block, then
                // PushB_SolidAction state 2 calls MvSonicOnPtfm. The native
                // routine deliberately relies only on the global OnObj bit: a
                // later Obj52 may own Sonic's stand-on slot while Obj33 still
                // carries him from its own retained obSolid=2 state.
                int deltaX = x - preMoveX;
                if (deltaX != 0) {
                    player.shiftX(deltaX);
                }
                int centreY = y - getSolidParams().groundHalfHeight() - player.getYRadius();
                NativePositionOps.writeYPosPreserveSubpixel(player, centreY);
            }
        }

        // PushB_Display: first out_of_range on the current obX; if that fails,
        // fall into PushB_ChkWithinOrigin
        // (docs/s1disasm/_incObj/"33 MZ, LZ Pushable Blocks.asm":92-96).
        if (isOutOfRangeCurrentX()) {
            chkWithinOrigin();
        }
    }

    /**
     * ROM {@code PushB_ChkWithinOrigin}
     * (docs/s1disasm/_incObj/"33 MZ, LZ Pushable Blocks.asm":96-113).
     * <p>
     * A second {@code out_of_range} against {@code pblock_origX}: when the ORIGIN is
     * also outside the window the block reaches {@code .deleteAndAllowRespawn}, which
     * clears the respawn bit and calls {@code DeleteObject}. Only an origin still
     * inside the window is snapped home and parked on routine 4
     * ({@code PushB_ChkVisible}, ibid.:116-127), which runs {@code ChkPartiallyVisible}
     * and {@code rts} with no {@code out_of_range} test of its own.
     * <p>
     * Both ROM entry points reach this: {@code PushB_Display}'s fall-through, and
     * {@code PushB_Sunken}'s {@code bra.w} after a block finishes sinking in lava
     * (ibid.:213-219). Taking the routine-4 half unconditionally on the sunken path
     * parks an out-of-range block in a routine that can never delete it, so it holds
     * its SST slot for the rest of the act and shifts every later {@code ObjPosLoad}
     * placement -- and with it the {@code FindFreeObj} slots the Obj37 scattered-ring
     * chain claims, whose {@code d7}-derived bounce phase then diverges.
     */
    private void chkWithinOrigin() {
        if (isOutOfRangeSpawnX()) {
            deletePending = true;
            return;
        }
        handleOutOfRange();
    }

    /**
     * loc_C046: Lava motion physics (only when objoff_32 != 0).
     * <p>
     * Handles SpeedToPos (if solidState < 4), then checks airborne (obStatus bit 1).
     * If airborne: applies SpeedToPos again + gravity + floor detection.
     * If grounded: wall collision when sliding, or slow sink when stopped.
     *
     * @return true if the block was deleted (slow sink threshold reached)
     */
    private boolean updateLavaMotion(AbstractPlayableSprite player) {
        // cmpi.b #4,obSolid(a0) / bhs.s loc_C056
        // States 4/6 handle their own SpeedToPos in the state machine.
        // Also skip when airborne — the airborne path below runs its own SpeedToPos.
        if (solidState < 4 && !airborne) {
            applySpeedToPosX();
            applySpeedToPosY();
        }

        // btst #1,obStatus(a0) / beq.s loc_C0A0
        if (airborne) {
            // Airborne path: SpeedToPos + gravity + floor check
            applySpeedToPosX();
            applySpeedToPosY();

            // addi.w #$18,obVelY(a0)
            yVelocity = (short) (yVelocity + FALL_GRAVITY);

            // jsr (ObjFloorDist).l
            TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, HALF_HEIGHT);

            // tst.w d1 / bpl.s locret_C09E
            if (result.foundSurface() && result.distance() < 0) {
                // Floor hit: snap to surface, clear airborne
                // add.w d1,obY(a0)
                y += result.distance();
                motion.ySub = 0;
                // bclr #1,obStatus(a0)
                airborne = false;
                // clr.w obVelY(a0)
                yVelocity = 0;
            }
            return false;
        }

        // loc_C0A0: ground handling
        if (xVelocity != 0) {
            // Wall collision
            if (xVelocity > 0) {
                // moveq #0,d3 / move.b obActWid(a0),d3 / jsr (ObjHitWallRight).l
                TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(
                        x + activeWidth, y);
                if (wall.foundSurface() && wall.distance() < 0) {
                    xVelocity = 0; // PushB_StopPush
                }
            } else {
                // moveq #0,d3 / move.b obActWid(a0),d3 / not.w d3
                // not.w: d3 = -(activeWidth + 1)
                TerrainCheckResult wall = ObjectTerrainUtils.checkLeftWallDist(
                        x - activeWidth - 1, y);
                if (wall.foundSurface() && wall.distance() < 0) {
                    xVelocity = 0; // PushB_StopPush
                }
            }
        } else {
            // loc_C0D6: slow sink - addi.l #$2001,obY(a0)
            // This is a direct 16.16 add (not a velocity-driven move), so update the
            // accumulator on the State directly.
            int yPos32 = (y << 16) | (motion.ySub & 0xFFFF);
            yPos32 += SLOW_SINK_INCREMENT;
            y = yPos32 >> 16;
            motion.ySub = yPos32 & 0xFFFF;

            // cmpi.b #$A0,obY+3(a0) / bhs.s PushB_Sunken
            if ((motion.ySub & 0xFF) >= SLOW_SINK_DELETE_THRESHOLD) {
                // PushB_Sunken: unlink Sonic, clear the stood-on flag, then
                // bra.w PushB_ChkWithinOrigin -- NOT an unconditional park at
                // the origin (docs/s1disasm/_incObj/"33 MZ, LZ Pushable
                // Blocks.asm":209-219).
                if (player != null) {
                    player.setOnObject(false);
                }
                chkWithinOrigin();
                return true;
            }
        }

        return false;
    }

    /**
     * State 6 (loc_C1F2): Horizontal alignment after push off ledge.
     * <p>
     * Block slides via SpeedToPos at $400 velocity until X aligns to a 16px
     * grid boundary (bits 2-3 of X are zero). Then saves velocity as momentum,
     * stops X movement, and transitions to state 4 (falling).
     * <pre>
     * loc_C1F2:
     *   bsr.w   SpeedToPos
     *   move.w  obX(a0),d0
     *   andi.w  #$C,d0
     *   bne.w   locret_C2E4
     *   andi.w  #-$10,obX(a0)
     *   move.w  obVelX(a0),objoff_30(a0)
     *   clr.w   obVelX(a0)
     *   subq.b  #2,obSolid(a0)
     * </pre>
     */
    private void runState6AlignTo16px() {
        applySpeedToPosX();
        applySpeedToPosY();

        // andi.w #$C,d0 / bne.w locret_C2E4
        if ((x & 0xC) != 0) {
            return; // Not aligned to 16px boundary yet
        }

        // andi.w #-$10,obX(a0) — force-align to 16px grid
        x &= ~0xF;
        motion.xSub = 0;

        // move.w obVelX(a0),objoff_30(a0) — save velocity as push momentum
        pushMomentum = xVelocity;

        // clr.w obVelX(a0)
        xVelocity = 0;

        // subq.b #2,obSolid(a0) — state 6 → state 4
        solidState = 4;
    }

    /**
     * State 4 (loc_C1AA): Falling with gravity after 16px alignment.
     * <p>
     * Applies SpeedToPos (X velocity is 0, Y velocity increases with gravity).
     * When floor is found (d1 < 0), snaps to surface and transitions to state 0.
     * If landed on a lava tile (>= $16A), enters lava motion mode (objoff_32).
     * <pre>
     * loc_C1AA:
     *   bsr.w   SpeedToPos
     *   addi.w  #$18,obVelY(a0)
     *   jsr     (ObjFloorDist).l
     *   tst.w   d1
     *   bpl.w   locret_C1F0
     *   add.w   d1,obY(a0)
     *   clr.w   obVelY(a0)
     *   clr.b   obSolid(a0)
     *   ...lava tile check...
     * </pre>
     */
    private void runState4FallWithGravity() {
        // bsr.w SpeedToPos
        applySpeedToPosX();
        applySpeedToPosY();

        // addi.w #$18,obVelY(a0)
        yVelocity = (short) (yVelocity + FALL_GRAVITY);

        // jsr (ObjFloorDist).l
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, HALF_HEIGHT);

        // tst.w d1 / bpl.w locret_C1F0 — continue falling if d1 >= 0
        if (!result.foundSurface() || result.distance() >= 0) {
            return;
        }

        // Floor hit: add.w d1,obY(a0) — snap to surface
        y += result.distance();
        motion.ySub = 0;

        // clr.w obVelY(a0)
        yVelocity = 0;

        // clr.b obSolid(a0) — transition to state 0
        solidState = 0;
        pushReleasePendingAfterRideExit = false;

        // Check lava tile: move.w (a1),d0 / andi.w #$3FF,d0 / cmpi.w #$16A,d0
        int tileIndex = result.tileIndex() & 0x3FF;
        if (tileIndex >= LAVA_TILE_THRESHOLD) {
            // Landing on lava: enter motion state
            // move.w objoff_30(a0),d0 / asr.w #3,d0 / move.w d0,obVelX(a0)
            xVelocity = (short) (pushMomentum >> 3);
            // move.b #1,objoff_32(a0) — THIS is the only place objoff_32 gets set
            inMotion = true;
            // clr.w obY+2(a0)
            motion.ySub = 0;
        }
    }

    /**
     * MZ Act 1: Position block relative to chained stomper.
     * When block X is between $A20-$AA0, reads the stomper's Y and positions
     * the block 0x1C pixels above it.
     * <pre>
     * move.w obX(a0),d0
     * cmpi.w #$A20,d0 / blo.s loc_BFC6
     * cmpi.w #$AA1,d0 / bhs.s loc_BFC6
     * move.w (v_obj31ypos).w,d0
     * subi.w #$1C,d0
     * move.w d0,obY(a0)
     * </pre>
     */
    private void updateStomperInteraction() {
        // bclr #7,obSubtype(a0)
        chainedToStomper = false;

        if (x < STOMPER_X_MIN || x >= STOMPER_X_MAX) {
            return;
        }

        // Find the chained stomper instance and read its Y position
        // ROM uses v_obj31ypos which is written by the stomper every frame
        if (services().objectManager() == null) {
            return;
        }

        Collection<ObjectInstance> activeObjects = services().objectManager().getActiveObjects();
        for (ObjectInstance obj : activeObjects) {
            ObjectSpawn objSpawn = obj.getSpawn();
            if (objSpawn != null && objSpawn.objectId() == Sonic1ObjectIds.CHAINED_STOMPER) {
                int stomperY = obj.getY();
                // subi.w #$1C,d0 / move.w d0,obY(a0)
                y = stomperY - STOMPER_Y_OFFSET;
                // bset #7,obSubtype(a0)
                chainedToStomper = true;
                break;
            }
        }
    }

    /**
     * Routine 4 (loc_C02C): Block is offscreen, waiting to re-enter.
     * When partially visible again, resets to active state.
     * <pre>
     * bsr.w ChkPartiallyVisible / beq.s locret_C044
     * move.b #2,obRoutine(a0)
     * clr.b objoff_32(a0)
     * clr.w obVelX(a0)
     * clr.w obVelY(a0)
     * </pre>
     */
    private void updateOffscreen() {
        if (isOnScreen(activeWidth + 128)) {
            routine = 2;
            inMotion = false;
            airborne = false;
            xVelocity = 0;
            yVelocity = 0;
            solidState = 0;
            pushReleasePendingAfterRideExit = false;
        }
    }

    /**
     * Handle going out of range.
     * <pre>
     * loc_BFE6:
     *   out_of_range.s loc_C016,objoff_34(a0)
     *   move.w objoff_34(a0),obX(a0)
     *   move.w objoff_36(a0),obY(a0)
     *   move.b #4,obRoutine(a0)
     * </pre>
     */
    private void handleOutOfRange() {
        // Reset to spawn position
        x = spawnX;
        y = spawnY;
        routine = 4;
        inMotion = false;
        airborne = false;
        solidState = 0;
        pushReleasePendingAfterRideExit = false;
        xVelocity = 0;
        yVelocity = 0;
        motion.xSub = 0;
        motion.ySub = 0;
        pushMomentum = 0;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // loc_C218: Push only handled in state 0, when NOT in motion (objoff_32).
        // tst.b objoff_32(a0) / beq.s loc_C230 / bra.w locret_C2E4
        if (solidState != 0 || inMotion || player == null) {
            return;
        }

        // ROM: The push handler at loc_C230 checks d0 (the pre-correction X
        // displacement from Solid_ChkEnter), NOT the pushing status flag.
        // SolidObject's Solid_Centre only sets the pushing flag when grounded
        // (btst #1,obStatus / bne Solid_SideAir), but d0 retains its value
        // regardless of air state. Use contact.sideDistX() which carries the
        // displacement from SolidContacts — this is d0 in ROM terms.
        if (contact.touchSide()) {
            handlePush(player, contact.sideDistX(), frameCounter);
        }
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // Push/fall movement rebuilds dynamicSpawn, but obStatus belongs to the
        // same live Obj33 SST until its next Solid_ChkEnter call.
        return true;
    }

    @Override
    public boolean preservesNativePushLatchAcrossSkippedSolidCheckpoints() {
        // PushB_OnLava can carry the object's native push bit across movement
        // frames for which the engine has no immediately preceding checkpoint.
        // State 2 also skips Solid_ChkCollision on its ExitPlatform release
        // frame; the following state-0 checkpoint still owns the retained bit.
        // Outside those states an ordinary state-0 block checkpoints every
        // frame, so a stale engine latch must not synthesize a later write.
        return inMotion || pushReleasePendingAfterRideExit;
    }

    /**
     * Custom push handler (loc_C230 in loc_C186/Solid_ChkEnter).
     * <p>
     * When Sonic pushes the block:
     * <ol>
     *   <li>Check player facing direction matches push direction (obStatus bit 0)</li>
     *   <li>Check wall collision in push direction (ObjHitWallRight/Left)</li>
     *   <li>Move block 1 pixel</li>
     *   <li>Move player 1 pixel</li>
     *   <li>Set player inertia to +/-$40, zero velocity</li>
     *   <li>Play push sound</li>
     *   <li>Check for ledge (floor distance > 4) → solidState 6</li>
     * </ol>
     * <pre>
     * loc_C230:
     *   tst.w   d0              ; d0 = displacement from Solid_ChkEnter
     *   beq.w   locret_C2E4     ; exactly at center → don't push
     *   bmi.s   loc_C268        ; d0 < 0 → player is right of block
     *   btst    #0,obStatus(a1) ; check player facing (bit 0 = facing left)
     *   bne.w   locret_C2E4     ; facing left → can't push right
     *   ...
     * </pre>
     */
    private void handlePush(AbstractPlayableSprite player, int d0, int frameCounter) {
        // d0 = pre-correction X displacement from SolidContacts (ROM's d0 from
        // Solid_ChkEnter). SolidContacts computes this from the uncorrected player
        // position before applying the correction, matching the ROM where d0 is
        // preserved through SolidObject's return.
        // With gSpeed=$40 (~0.25px/frame) the player needs ~4 frames to move 1px
        // into the block, matching the ROM's push cadence.
        if (d0 == 0) {
            return;
        }

        // d0 > 0 → player LEFT of block → push RIGHT
        // d0 < 0 → player RIGHT of block → push LEFT
        boolean pushRight = d0 > 0;

        if (pushRight) {
            // btst #0,obStatus(a1) / bne.w locret_C2E4
            // Player must be facing right (bit 0 clear = not facing left)
            if (player.getDirection() == Direction.LEFT) {
                return;
            }

            // ObjHitWallRight: moveq #0,d3 / move.b obActWid(a0),d3
            TerrainCheckResult wallResult = ObjectTerrainUtils.checkRightWallDist(
                    x + activeWidth, y);
            if (wallResult.foundSurface() && wallResult.distance() < 0) {
                return; // Wall blocks movement
            }

            // addi.l #$10000,obX(a0) — move block 1 pixel right
            x += 1;
            // moveq #1,d0 / add.w d0,obX(a1) — move player 1 pixel right
            // Use move() not setCentreX() to preserve subpixels (ROM's add.w only
            // changes the pixel word, subpixel byte is untouched).
            player.move((short) 256, (short) 0);
            // move.w #$40,d1 / move.w d1,obInertia(a1)
            player.setGSpeed((short) PUSH_INERTIA);
            // move.w #0,obVelX(a1)
            player.setXSpeed((short) 0);
        } else {
            // btst #0,obStatus(a1) / beq.s locret_C2E4
            // Player must be facing left (bit 0 set)
            if (player.getDirection() != Direction.LEFT) {
                return;
            }

            // ObjHitWallLeft: moveq #0,d3 / move.b obActWid(a0),d3 / not.w d3
            // not.w d3: d3 = -(activeWidth + 1), so check at x - activeWidth - 1
            TerrainCheckResult wallResult = ObjectTerrainUtils.checkLeftWallDist(
                    x - activeWidth - 1, y);
            if (wallResult.foundSurface() && wallResult.distance() < 0) {
                return;
            }

            // subi.l #$10000,obX(a0) — move block 1 pixel left
            x -= 1;
            // moveq #-1,d0 / add.w d0,obX(a1) — move player 1 pixel left
            // Use move() not setCentreX() to preserve subpixels (ROM's add.w only
            // changes the pixel word, subpixel byte is untouched).
            player.move((short) -256, (short) 0);
            // move.w #-$40,d1 / move.w d1,obInertia(a1)
            player.setGSpeed((short) -PUSH_INERTIA);
            // move.w #0,obVelX(a1)
            player.setXSpeed((short) 0);
        }

        // Play push sound: move.w #sfx_Push,d0 / jsr (QueueSound2).l
        // ROM's SMPS driver has special handling for sfx_Push: if f_push_playing is
        // already set, the queue request is silently ignored. The flag is cleared by
        // smpsClearPush at the end of the sound data (~31 frames). This prevents the
        // sound from restarting on every push frame while still allowing it to loop
        // naturally when the player continues pushing.
        if (frameCounter - lastPushSoundFrame >= PUSH_SOUND_DURATION) {
            try {
                services().playSfx(Sonic1Sfx.PUSH.id);
            } catch (Exception e) {
                // Prevent audio failure from breaking game logic
            }
            lastPushSoundFrame = frameCounter;
        }

        // tst.b obSubtype(a0) / bmi.s locret_C2E4
        // If chained to stomper (bit 7 set), skip ledge check
        if (chainedToStomper) {
            updateDynamicSpawn(x, y);
            return;
        }

        // jsr (ObjFloorDist).l — check for ledge
        // cmpi.w #4,d1 / ble.s loc_C2E0
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(x, y, HALF_HEIGHT);
        if (!floorResult.foundSurface() || floorResult.distance() > LEDGE_DISTANCE_THRESHOLD) {
            // Block pushed off edge — enter state 6 (alignment sliding)
            // move.w #$400,obVelX(a0) / tst.w d0 / bpl.s / neg.w obVelX
            xVelocity = pushRight ? FALL_X_VELOCITY : -FALL_X_VELOCITY;
            yVelocity = 0;
            // move.b #6,obSolid(a0)
            solidState = 6;
            // NOTE: objoff_32 is NOT set here. Only set when landing on lava.
        } else {
            // add.w d1,obY(a0) — snap block to floor
            y += floorResult.distance();
        }

        updateDynamicSpawn(x, y);
    }

    protected SolidCheckpointBatch checkpointAll() {
        return services().solidExecution().resolveSolidNowAll();
    }

    protected boolean isPlayerRidingThisBlock() {
        var objectManager = services().objectManager();
        return objectManager != null && objectManager.isAnyPlayerRiding(this);
    }

    private void applyPushContacts(SolidCheckpointBatch batch, int vIntRunCount) {
        for (PlayableEntity entity : batch.perPlayer().keySet()) {
            if (!(entity instanceof AbstractPlayableSprite player)) {
                continue;
            }
            PlayerSolidContactResult result = batch.perPlayer().get(entity);
            if (result == null || result.kind() != ContactKind.SIDE) {
                continue;
            }
            handlePush(player, result.sideDistX(), vIntRunCount);
        }
    }

    /**
     * PushB_ChkLava: Check if block is at a lava geyser spawn position.
     * <p>
     * In MZ Act 2, at X=$DD0/$CC0/$BA0, spawns geyser at X-$20.
     * In MZ Act 3, at X=$560/$5C0, spawns geyser at X+$20.
     * <p>
     * Note: GeyserMaker (id_GeyserMaker = 0x4C) is not yet implemented.
     * This method is a placeholder that will work once the geyser object is added.
     */
    private void checkLavaGeyser() {
        if (!isMZ) {
            return;
        }

        int xOffset;
        boolean match = false;

        // cmpi.w #(id_MZ<<8)+1,(v_zone).w — MZ Act 2
        if (actIndex == 1) {
            xOffset = -0x20;
            for (int pos : LAVA_POS_ACT2) {
                if (x == pos) {
                    match = true;
                    break;
                }
            }
        }
        // cmpi.w #(id_MZ<<8)+2,(v_zone).w — MZ Act 3
        else if (actIndex == 2) {
            xOffset = 0x20;
            for (int pos : LAVA_POS_ACT3) {
                if (x == pos) {
                    match = true;
                    break;
                }
            }
        } else {
            return;
        }

        if (!match) {
            return;
        }

        // No de-duplication here. PushB_SpawnLavaGeysers runs every frame from
        // PushB_LavaPlatform and spawns a GeyserMaker whenever obX equals one of
        // the hardcoded X-positions exactly (docs/s1disasm/_incObj/33 MZ, LZ
        // Pushable Blocks.asm:229-269). A block drifting on lava moves at
        // pblock_lavaspeed>>3 = +/-$80 (half a pixel per frame, same file
        // lines 157-159/329-331), so obX holds each integer value for two
        // consecutive frames and the ROM spawns a maker on both of them. The
        // only thing that stops it is FindFreeObj failing.

        // PushB_LoadLava: spawn GeyserMaker object
        // _move.b #id_GeyserMaker,obID(a1)
        if (services().objectManager() == null) {
            return;
        }
        // move.w obX(a0),obX(a1) / add.w d2,obX(a1)
        // move.w obY(a0),obY(a1) / addi.w #$10,obY(a1)
        // move.l a0,objoff_3C(a1)
        spawnFreeChild(() -> new Sonic1LavaGeyserMakerObjectInstance(
                x + xOffset, y + 0x10, 0, this));
    }

    /**
     * Called by GeyserMaker to launch this block upward.
     * ROM: bset #1,obStatus(a1) / move.w #-$580,obVelY(a1)
     * Sets the block's airborne flag (obStatus bit 1) with the given upward velocity.
     * <p>
     * Important: this does NOT change solidState (obSolid). The block remains solid
     * so Sonic can continue riding it while it rises. The airborne physics (gravity +
     * floor snap) are handled by updateLavaMotion's airborne path.
     *
     * @param velY upward velocity in subpixels (negative = upward)
     */
    void applyLavaGeyserLaunch(int velY) {
        // bset #1,obStatus(a1) -> airborne flag (separate from obSolid)
        airborne = true;
        // move.w #-$580,obVelY(a1) -- a plain velocity store, no subpixel touch
        // (docs/s1disasm/_incObj/4C, 4D MZ Lava Geyser and Maker.asm:83-87).
        // PushB_OnLava's airborne branch already runs SpeedToPos before the
        // +$18 gravity add (same order as loc_C056), so no phase compensation
        // is required here.
        yVelocity = (short) velY;
    }

    /**
     * SpeedToPos for X axis: position += velocity * 256.
     * <pre>
     * move.l obX(a0),d2 / ext.l d0 / asl.l #8,d0 / add.l d0,d2 / move.l d2,obX(a0)
     * </pre>
     */
    private void applySpeedToPosX() {
        if (xVelocity == 0) return;
        // SpeedToPos (X-only, 16.16 fixed-point). Inlined to avoid touching ySub/yVel.
        int xVel32 = (int) (short) xVelocity;
        int x32 = (x << 16) | (motion.xSub & 0xFFFF);
        x32 += xVel32 << 8;
        x = x32 >> 16;
        motion.xSub = x32 & 0xFFFF;
    }

    /**
     * SpeedToPos for Y axis.
     */
    private void applySpeedToPosY() {
        if (yVelocity == 0) return;
        // SpeedToPos (Y-only, 16.16 fixed-point) using shared helper.
        motion.y = y;
        motion.yVel = yVelocity;
        SubpixelMotion.speedToPosY(motion);
        y = motion.y;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // d1 = obActWid + $B
        int halfWidth = activeWidth + 0x0B;
        return SolidObjectParams.of(halfWidth, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    /**
     * The block's ROM {@code obActWid}, read out of {@code PushB_Var}.
     *
     * <p>{@code PushB_Main} indexes the subtype into {@code PushB_Var} and stores
     * the first byte of the pair straight into {@code obActWid} -- {@code #32/2}
     * = 16 for the 1x1 block, {@code #128/2} = 64 for the 4x1
     * (docs/s1disasm/_incObj/33 MZ, LZ Pushable Blocks.asm:22-24,48-49). This is
     * the same {@link #activeWidth} the class already derives for the collision
     * width, which {@code PushB_Action} forms by padding it
     * ({@code addi.w #sonic_solid_width,d1}) without writing the padded value
     * back to {@code obActWid}.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers want the raw byte -- {@code BuildSprites}' horizontal
     * cull (docs/s1disasm/_inc/BuildSprites.asm:49-58) and {@code Sonic_Balance}
     * (docs/s1disasm/_incObj/01 Sonic.asm:423). {@link #isTopSolidOnly()} is
     * false for this class, so the balance accessor does inherit this one rather
     * than intercepting at {@code getSolidParams().halfWidth()}.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return activeWidth;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // Solid_ChkEnter rejects positions beyond the right edge with BHI, so
        // equality remains a valid side contact for loc_C230's push-status path.
        return SolidRoutineProfile.fullSolid(false, true, false);
    }

    @Override
    public boolean isTopSolidOnly() {
        // Push blocks have full solidity (sides, top, bottom)
        return false;
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // Solid_ChkEnter push cadence depends on accumulating subpixels while d0 == 0.
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid in states 0 (idle/pushable) and 2 (player riding on top).
        // NOT solid in states 4 (falling) and 6 (sliding to alignment) — the
        // ROM's loc_C186 doesn't call Solid_ChkEnter during those states.
        return routine == 2 && solidState < 4;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isHighPriority() {
        return frameIndex == 1;
    }

    @Override
    public boolean isPersistent() {
        // Obj33 has NO camera-window persistence rule. Its only lifetime rule is
        // PushB_Display's pair of out_of_range checks -- current obX, then
        // pblock_origX -- reaching .deleteAndAllowRespawn / DeleteObject
        // (docs/s1disasm/_incObj/"33 MZ, LZ Pushable Blocks.asm":92-113).
        // Routine 4 (PushB_ChkVisible, ibid.:116-127) runs ChkPartiallyVisible
        // and rts without ANY out_of_range test, so a block parked at its origin
        // survives indefinitely off-screen. deletePending is therefore the sole
        // gate: it is latched only when updateActive has just failed BOTH checks.
        //
        // A camera-window term here (previously isOnScreenX(320)) is an invented
        // rule with more left slack than the ROM's chunk-aligned window, and it
        // kept blocks alive past the frame ROM freed their SST slot -- which
        // desynced every subsequent MZ2 ObjPosLoad placement by one slot.
        return !isDestroyed() && !deletePending;
    }

    /**
     * ROM parity: PushB_Action runs PushB_SolidAction and the MZ1 stomper
     * alignment BEFORE reaching PushB_Display's out_of_range
     * (docs/s1disasm/_incObj/"33 MZ, LZ Pushable Blocks.asm":66-93), so the
     * check must observe the position this frame's routine produced. The
     * counter lane honours this flag at ObjectManager.java:914-934, which also
     * lets the deletePending latch set at the tail of updateActive be consumed
     * on the same frame instead of one frame late.
     */
    @Override
    public boolean checksOutOfRangeAfterRoutine() {
        return true;
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return deletePending ? spawnX : x;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (routine == 4) {
            return; // Don't render when in offscreen/reset state
        }

        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;

        renderer.drawFrameIndex(frameIndex, x, y, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (routine == 4) {
            return;
        }

        int halfWidth = activeWidth + 0x0B;

        // Draw solid collision bounds
        float r = (solidState >= 4) ? 1.0f : inMotion ? 0.8f : 0.0f;
        float g = (solidState >= 4) ? 0.2f : inMotion ? 0.4f : 0.8f;
        float b = (solidState >= 4) ? 0.2f : inMotion ? 0.0f : 0.2f;
        ctx.drawRect(x, y, halfWidth, SOLID_AIR_HALF_HEIGHT, r, g, b);

        // Draw spawn position marker
        if (x != spawnX || y != spawnY) {
            ctx.drawLine(spawnX - 4, spawnY, spawnX + 4, spawnY, 0.5f, 0.5f, 0.5f);
            ctx.drawLine(spawnX, spawnY - 4, spawnX, spawnY + 4, 0.5f, 0.5f, 0.5f);
        }

        // Label with state info
        String stateLabel;
        if (inMotion) {
            stateLabel = String.format("PushBlk:LAVA vx=%d", xVelocity);
        } else if (solidState == 6) {
            stateLabel = String.format("PushBlk:ALIGN vx=%d", xVelocity);
        } else if (airborne) {
            stateLabel = String.format("PushBlk:AIRBORNE vy=%d", yVelocity);
        } else if (solidState == 4) {
            stateLabel = String.format("PushBlk:FALL vy=%d", yVelocity);
        } else if (chainedToStomper) {
            stateLabel = "PushBlk:CHAINED";
        } else {
            stateLabel = "PushBlk:IDLE";
        }
        ctx.drawWorldLabel(x, y, -2, stateLabel, DebugColor.ORANGE);
    }

    private boolean isOutOfRangeCurrentX() {
        return !isInRangeAt(x);
    }

    private boolean isOutOfRangeSpawnX() {
        return !isInRangeAt(spawnX);
    }
}
