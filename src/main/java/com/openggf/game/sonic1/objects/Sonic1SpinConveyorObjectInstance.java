package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1ConveyorState;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.WaypointPathFollower;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Object 0x6F - Spinning platforms that move around a conveyor belt (SBZ).
 * <p>
 * This object manages spinning disc platforms that travel along waypoint paths in
 * Scrap Brain Zone. It shares the same art (Map_Spin, Nem_SpinPform) and v_obj63
 * spawner tracking as Object 0x69 (SBZ Spinning Platforms) and 0x63 (LZ Conveyor).
 * <p>
 * Two modes are selected by subtype bit 7:
 * <ul>
 *   <li><b>Spawner mode</b> (subtype bit 7 set): Reads child platform positions from
 *       ROM ObjPosSBZPlatform_Index data and spawns individual
 *       platform instances. Uses v_obj63 to prevent duplicate spawning.</li>
 *   <li><b>Platform mode</b> (subtype &lt; 0x80): A spinning disc that follows waypoints
 *       from one of 6 path tables. Solid only when flat (obFrame == 0, mapping frame 0).
 *       When spinning, detaches any riding player. Uses LCon_ChangeDir velocity
 *       interpolation and SpeedToPos movement. Checks f_conveyrev at init time only.</li>
 * </ul>
 * <p>
 * Animation reference: docs/s1disasm/_anim/SBZ Spin Platform Conveyor.asm (Ani_SpinConvey)
 * <p>
 * ROM reference: docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm
 */
public class Sonic1SpinConveyorObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable, SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(Sonic1SpinConveyorObjectInstance.class.getName());

    // ---- Constants from disassembly ----

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int HALF_WIDTH = 0x10;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int RENDER_PRIORITY = 4;

    // Velocity magnitude for LCon_ChangeDir: move.w #-$100,d2 / move.w #-$100,d3
    private static final int MOVE_SPEED = 0x100;

    // Waypoint step size in bytes: 4 bytes per waypoint (2 words: X, Y)
    private static final int WAYPOINT_STEP = 4;

    // Solid object params from disassembly loc_163D8 routine 2:
    //   move.w #$1B,d1 / move.w #7,d2 / move.w d2,d3 / addq.w #1,d3
    private static final SolidObjectParams SOLID_PARAMS = new SolidObjectParams(0x1B, 0x07, 0x08);

    // ---- Animation data from Ani_SpinConvey ----
    // .spin: dc.b 0, 0, 1, 2, 3, 4, $43, $42, $41, $40, $61, $62, $63, $64, $23, $22, $21, 0, afEnd
    // Frame duration 0 means "advance every frame"
    // afEnd = loop back to start
    private static final int SPIN_ANIM_DURATION = 0;
    private static final int[] SPIN_RAW_SEQUENCE = {
            0, 1, 2, 3, 4,
            0x43, 0x42, 0x41, 0x40,
            0x61, 0x62, 0x63, 0x64,
            0x23, 0x22, 0x21, 0
    };
    // .still: dc.b $F, 0, afEnd
    // Frame duration $F = stays on frame 0 indefinitely (afEnd loops back)
    private static final int STILL_ANIM_DURATION = 0x0F;

    // ---- Instance state ----

    private enum Mode { SPAWNER, PLATFORM }

    private Mode mode;

    // Current position (updated by movement for platforms)
    private int x;
    private int y;

    // Platform mode state
    private int[][] waypoints;       // waypoint array for this platform's path
    private int waypointCount;       // number of waypoints * WAYPOINT_STEP (byte count for wrap check)
    private int currentWaypointIdx;  // current waypoint byte offset (objoff_38), always multiple of 4
    private int waypointStep;        // +4 forward, -4 reverse (objoff_3A)
    private int targetX;             // current target X (objoff_34)
    private int targetY;             // current target Y (objoff_36)
    private int velX;                // X velocity (obVelX)
    private int velY;                // Y velocity (obVelY)
    private int xFrac;               // X subpixel fractional (obX+2 low word)
    private int yFrac;               // Y subpixel fractional (obY+2 low word)

    /** Reusable state for SubpixelMotion calls (avoids per-frame allocation). */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int pathIndex;           // subtype bits 4-6 selecting SpinC_Data entry
    private int baseX;               // base X for out_of_range check (objoff_30)

    // Animation state
    // obAnim: 0 = .spin (rotating), 1 = .still (flat)
    private int animationId;
    private int animFrameIndex;
    private int animTimer;
    private int mappingFrame;

    // Track whether the disc is currently flat/solid (obFrame == 0)
    private boolean solidActive;

    // Spawner mode state
    private int spawnerSlotIndex;    // v_obj63 slot index (objoff_2F & 0x7F)
    private boolean spawnerDone;
    private boolean initialized;     // lazy-init guard for services() deferral

    /**
     * True on the one platform that occupies the group spawner's own SST slot,
     * i.e. the ROM's {@code spinc_groupid} (objoff_2F) still carrying the
     * spawner subtype with bit 7 set. Only that platform runs the
     * {@code bclr #0,(v_obj63,slot)} in {@code SpinConvey}'s despawn path
     * (docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:30-34); every
     * other platform has objoff_2F = 0 from cleared object RAM, so
     * {@code bpl.s .delete} sends it straight to DeleteObject.
     */
    private boolean spawnerOwnsGroupLatch;

    /**
     * Creates a spin conveyor object instance.
     *
     * @param spawn the object spawn data from the level
     */
    public Sonic1SpinConveyorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SpinConveyor");
        int subtype = spawn.subtype() & 0xFF;

        this.x = spawn.x();
        this.y = spawn.y();

        if ((subtype & 0x80) != 0) {
            // Subtype bit 7 set: spawner mode
            // From disassembly loc_16380: move.b d0,objoff_2F(a0)
            this.mode = Mode.SPAWNER;
            this.spawnerSlotIndex = subtype & 0x7F;
            this.spawnerDone = false;
        } else {
            configureAsPlatform(subtype);
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * Applies the ROM's {@code SpinC_Main} platform branch to this SST slot
     * (docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:63-133).
     * <p>
     * Factored out of the constructor because the ROM reaches this branch twice
     * for the same slot: once for a platform placed directly by the level's
     * objpos data, and once for the spawner's own slot, which
     * {@code SpinC_Main_Spawner} re-uses as its group's first platform
     * ({@code movea.l a0,a1 / bra.s .makePlatform}, same file lines 200-201).
     * That slot is left with {@code obRoutine} 0 and re-enters
     * {@code SpinC_Main} as a platform on its next execution.
     */
    private void configureAsPlatform(int subtype) {
        {
            // Platform mode (subtype 0x00-0x7F)
            this.mode = Mode.PLATFORM;

            // addq.b #2,obRoutine(a0) -> routine 2

            // Parse path index from subtype bits 4-6
            // From disassembly: lsr.w #3,d0 / andi.w #$1E,d0
            this.pathIndex = (subtype >> 4) & 0x07;
            if (pathIndex >= 6) {
                pathIndex = 0;
            }

            // Starting waypoint from subtype bits 0-3
            // From disassembly: andi.w #$F,d1 / lsl.w #2,d1
            int startWaypointIdx = (subtype & 0x0F) * WAYPOINT_STEP;
            this.currentWaypointIdx = startWaypointIdx;

            // Default step: +4 (forward)
            // From disassembly: move.b #4,objoff_3A(a0)
            this.waypointStep = WAYPOINT_STEP;

            // Set initial animation based on starting waypoint position
            // From disassembly loc_16356-loc_16378:
            //   tst.w d1 / bne.s loc_1636C -> if starting at waypoint 0: obAnim = 1 (.still)
            //   cmpi.w #8,d1 / bne.s loc_16378 -> if starting at waypoint 2: obAnim = 0 (.spin)
            if (startWaypointIdx == 0) {
                // tst.w d1 / bne.s -> taken when d1 == 0: move.b #1,obAnim(a0)
                animationId = 1; // .still
            } else if (startWaypointIdx == 8) {
                // cmpi.w #8,d1 -> move.b #0,obAnim(a0)
                animationId = 0; // .spin
            }
            // For other starting positions, obAnim stays at 0 (RAM zeroed = .spin)

            this.animFrameIndex = 0;
            this.animTimer = 0;
            this.mappingFrame = 0;
            this.solidActive = true;

            // Initialize velocity and subpixel fractions
            this.velX = 0;
            this.velY = 0;
            this.xFrac = 0;
            this.yFrac = 0;
        }
    }

    /**
     * Package-private constructor for platforms spawned by the spawner.
     */
    Sonic1SpinConveyorObjectInstance(int spawnX, int spawnY, int subtype) {
        this(new ObjectSpawn(spawnX, spawnY,
                Sonic1ObjectIds.SBZ_SPIN_CONVEYOR, subtype, 0, false, 0));
    }

    /**
     * Lazy initialization: check f_conveyrev and finalize platform state on first update.
     * Moved out of constructor to avoid calling services() during construction.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (mode == Mode.PLATFORM) {
            if (!loadPathData()) {
                ObjectLifetimeOps.deleteNoRespawn(this);
                return;
            }

            // Check f_conveyrev at init time
            // From disassembly: tst.b (f_conveyrev).w / beq.s loc_16356
            Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
            if (conveyorState.isReversed()) {
                // From disassembly: move.b #1,objoff_3B(a0) / neg.b objoff_3A(a0)
                this.waypointStep = -WAYPOINT_STEP;

                // Advance to next waypoint in reverse direction
                currentWaypointIdx = WaypointPathFollower.wrapWaypointIndex(
                        currentWaypointIdx + waypointStep, waypointCount, WAYPOINT_STEP);

                // Re-set target from adjusted waypoint
                int wpArrayIdx = currentWaypointIdx / WAYPOINT_STEP;
                if (wpArrayIdx >= 0 && wpArrayIdx < waypoints.length) {
                    targetX = waypoints[wpArrayIdx][0];
                    targetY = waypoints[wpArrayIdx][1];
                }
            }

            // Calculate initial velocity toward target (bsr.w LCon_ChangeDir)
            changeDirection();
        }
    }

    private boolean loadPathData() {
        try {
            Sonic1ObjectPlacement.ConveyorPathData data =
                    new Sonic1ObjectPlacement(services().romReader()).loadSbzSpinConveyorPath(pathIndex);
            if (data == null) {
                return false;
            }
            this.waypoints = data.waypoints();
            this.waypointCount = waypoints.length * WAYPOINT_STEP;
            this.baseX = data.baseX();

            int wpArrayIdx = currentWaypointIdx / WAYPOINT_STEP;
            if (wpArrayIdx >= 0 && wpArrayIdx < waypoints.length) {
                targetX = waypoints[wpArrayIdx][0];
                targetY = waypoints[wpArrayIdx][1];
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load SBZ spin-conveyor path data from ROM", e);
            return false;
        }
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
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (mode) {
            case SPAWNER -> updateSpawner();
            case PLATFORM -> updatePlatform(vIntRunCount, player);
        }
    }

    // ========================================
    // Spawner mode
    // ========================================

    /**
     * Spawner routine (subtype bit 7 set).
     * Reads child platform entries from the hardcoded ObjPosSBZPlatform position
     * data and spawns individual spinning platform objects. Each spawner slot is
     * tracked in v_obj63 to prevent re-spawning.
     * <p>
     * From disassembly loc_16380:
     * <pre>
     *   move.b  d0,objoff_2F(a0)         ; save spawner slot info
     *   andi.w  #$7F,d0
     *   lea     (v_obj63).w,a2
     *   bset    #0,(a2,d0.w)             ; test-and-set spawned flag
     *   beq.s   loc_1639A               ; not previously spawned -> continue
     *   jmp     (DeleteObject).l          ; already spawned -> delete
     * loc_1639A:
     *   add.w   d0,d0                    ; d0 = slot * 2
     *   andi.w  #$1E,d0
     *   addi.w  #ObjPosSBZPlatform_Index-ObjPos_Index,d0
     *   lea     (ObjPos_Index).l,a2
     *   adda.w  (a2,d0.w),a2             ; a2 -> platform position list
     *   move.w  (a2)+,d1                 ; d1 = count - 1
     * </pre>
     */
    private void updateSpawner() {
        if (spawnerDone) {
            return;
        }
        spawnerDone = true;

        Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
        if (conveyorState.testAndSetSpawned(spawnerSlotIndex)) {
            // Already spawned - delete self
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }

        // Get platform position data for this spawner slot
        int[][] positionData = loadSpawnerPositionData(spawnerSlotIndex);
        if (positionData == null) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }

        if (services().objectManager() == null) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }

        if (positionData.length == 0) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }

        // Spawn child platforms
        // From disassembly SpinC_LoadPform loop:
        //   _move.b #id_SpinConvey,obID(a1)
        //   move.w  (a2)+,obX(a1)
        //   move.w  (a2)+,obY(a1)
        //   move.w  (a2)+,d0
        //   move.b  d0,obSubtype(a1)
        //
        // The FIRST entry is written into the spawner's OWN slot: the ROM enters
        // the write block via `movea.l a0,a1 / bra.s .makePlatform`
        // (docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:200-201) and
        // only calls FindFreeObj for entries 2..N (`.loopMakePlatforms`, line
        // 206). The spawner slot therefore stays occupied and becomes platform
        // #0, and only N-1 fresh slots are allocated. Allocating a slot for
        // every entry and freeing the spawner's instead leaves the group holding
        // a different set of SST slots, which de-phases every slot-indexed ROM
        // behaviour downstream (ExecuteObjects' ascending walk, and the
        // `(v_vblank_byte + 127 - slot) & 3` floor probe in RLoss_Bounce,
        // docs/s1disasm/_incObj/"25, 37 Rings.asm":334-339).
        for (int i = 1; i < positionData.length; i++) {
            final int childX = positionData[i][0];
            final int childY = positionData[i][1];
            final int childSubtype = positionData[i][2];

            spawnFreeChild(() -> new Sonic1SpinConveyorObjectInstance(
                    childX, childY, childSubtype));
        }

        // Re-use this slot as the group's first platform, exactly as the ROM
        // does. obRoutine is left at 0, so the ROM re-runs SpinC_Main here on
        // the slot's next execution; the `addq.l #4,sp / rts` at the end of
        // SpinC_Main_Spawner (line 226) exits without running platform logic
        // this frame, which is what returning from updateSpawner() does here.
        //
        // objoff_2F (spinc_groupid) keeps the spawner's subtype with bit 7 set,
        // because .makePlatform never writes it. That is the ROM reason only
        // THIS platform clears the v_obj63 latch on despawn
        // (SpinConvey .despawn: `bpl.s .delete`, lines 30-34) -- the children
        // allocated from cleared object RAM have objoff_2F = 0 and take the
        // plain delete path. See onUnload().
        this.spawnerOwnsGroupLatch = true;
        this.initialized = false;
        this.x = positionData[0][0];
        this.y = positionData[0][1];
        configureAsPlatform(positionData[0][2]);
        updateDynamicSpawn(x, y);
    }

    private int[][] loadSpawnerPositionData(int slotIndex) {
        try {
            return new Sonic1ObjectPlacement(services().romReader()).loadSbzPlatformChildren(slotIndex);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load SBZ spin-conveyor child positions from ROM", e);
            return null;
        }
    }

    // ========================================
    // Platform mode
    // ========================================

    /**
     * Platform routine (routine 2) from disassembly loc_163D8.
     * <p>
     * Sequence per frame:
     * <ol>
     *   <li>Animate sprite (Ani_SpinConvey)</li>
     *   <li>If flat (obFrame == 0): apply movement, then SolidObject</li>
     *   <li>If spinning (obFrame != 0): detach player if riding, then movement only</li>
     * </ol>
     * <p>
     * From disassembly loc_163D8:
     * <pre>
     *   lea     (Ani_SpinConvey).l,a1
     *   jsr     (AnimateSprite).l
     *   tst.b   obFrame(a0)
     *   bne.s   loc_16404              ; spinning -> not solid
     *   ... movement ... SolidObject
     * loc_16404:
     *   btst    #3,obStatus(a0)
     *   beq.s   loc_16420
     *   ... detach player ...
     * loc_16420:
     *   bra.w   loc_16424              ; movement only
     * </pre>
     */
    private void updatePlatform(int vIntRunCount, AbstractPlayableSprite player) {
        // Step 1: Animate
        animate();

        // Step 2: Check if flat (solid) or spinning (not solid)
        boolean wasSolid = solidActive;
        solidActive = (mappingFrame == 0);

        if (solidActive) {
            // Flat frame: apply movement, then SolidObject interaction
            // From disassembly: move.w obX(a0),-(sp) / bsr.w loc_16424 / ... / SolidObject
            applyConveyorMovement();
        } else {
            // Spinning: detach player if riding
            // From disassembly loc_16404:
            //   btst #3,obStatus(a0) / beq.s loc_16420
            //   lea (v_player).w,a1
            //   bclr #3,obStatus(a1) / bclr #3,obStatus(a0) / clr.b obSolid(a0)
            if (wasSolid) {
                detachRidingPlayer(player);
            }
            // Then apply movement (loc_16420 -> loc_16424)
            applyConveyorMovement();
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * Animate the spinning platform using Ani_SpinConvey sequences.
     * <p>
     * From docs/s1disasm/_anim/SBZ Spin Platform Conveyor.asm:
     * <pre>
     *   Ani_SpinConvey:
     *     .spin:  dc.b 0, 0,1,2,3,4, $43,$42,$41,$40, $61,$62,$63,$64, $23,$22,$21, 0, afEnd
     *     .still: dc.b $F, 0, afEnd
     * </pre>
     * <p>
     * Animation 0 (.spin): frame duration 0 (every frame), 17-frame rotation cycle, loops (afEnd).
     * Animation 1 (.still): frame duration $F, holds on frame 0, loops (afEnd).
     */
    private void animate() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }

        if (animationId == 1) {
            // .still: duration $F, frame sequence is just {0}, loops via afEnd
            animTimer = STILL_ANIM_DURATION;
            mappingFrame = 0;
            // animFrameIndex stays at 0 - always showing flat
        } else {
            // .spin: duration 0, full rotation cycle, loops via afEnd
            animTimer = SPIN_ANIM_DURATION;

            if (animFrameIndex >= SPIN_RAW_SEQUENCE.length) {
                // afEnd: loop back to start
                animFrameIndex = 0;
            }

            int rawFrame = SPIN_RAW_SEQUENCE[animFrameIndex];
            // andi.b #$1F,d0 -> extract mapping frame index (bits 0-4)
            mappingFrame = rawFrame & 0x1F;
            animFrameIndex++;
        }
    }

    /**
     * Apply conveyor movement: check waypoint arrival, advance, and apply velocity.
     * <p>
     * From disassembly loc_16424 (inlined from LZ Conveyor sub_12502, but without
     * switch-reversal logic - SBZ conveyor does not check f_switch+$E at runtime):
     * <pre>
     *   move.w  obX(a0),d0
     *   cmp.w   objoff_34(a0),d0
     *   bne.s   loc_16484                ; not at target X -> skip
     *   move.w  obY(a0),d0
     *   cmp.w   objoff_36(a0),d0
     *   bne.s   loc_16484                ; not at target Y -> skip
     *   ... advance waypoint ...
     *   bsr.w   LCon_ChangeDir
     * loc_16484:
     *   jmp     (SpeedToPos).l
     * </pre>
     */
    private void applyConveyorMovement() {
        // Check if we've arrived at the target waypoint
        if (x == targetX && y == targetY) {
            advanceWaypoint();
        }

        // Apply velocity to position (SpeedToPos)
        applySpeedToPos();
    }

    /**
     * Advances to the next waypoint and recalculates velocity.
     * Also updates animation: at waypoint 0 -> .still, at waypoint 2 -> .spin.
     * <p>
     * From disassembly (matching loc_16456 through loc_16480).
     *
     * @see WaypointPathFollower#wrapWaypointIndex
     */
    private void advanceWaypoint() {
        int nextIdx = WaypointPathFollower.wrapWaypointIndex(
                currentWaypointIdx + waypointStep, waypointCount, WAYPOINT_STEP);

        currentWaypointIdx = nextIdx;

        // Update target from new waypoint
        int wpArrayIdx = nextIdx / WAYPOINT_STEP;
        if (wpArrayIdx >= 0 && wpArrayIdx < waypoints.length) {
            targetX = waypoints[wpArrayIdx][0];
            targetY = waypoints[wpArrayIdx][1];
        }

        // Update animation based on waypoint position
        // From disassembly:
        //   tst.w d1 / bne.s loc_16474 -> if at waypoint 0: move.b #1,obAnim(a0)
        //   cmpi.w #8,d1 / bne.s loc_16480 -> if at waypoint 2: move.b #0,obAnim(a0)
        if (nextIdx == 0) {
            setAnimation(1); // .still (flat, solid)
        } else if (nextIdx == 8) {
            setAnimation(0); // .spin (rotating, not solid)
        }

        // Recalculate velocity
        changeDirection();
    }

    /**
     * Sets the animation ID, resetting animation state if changed.
     * Matches AnimateSprite behavior: obAnim != obPrevAni triggers reset.
     */
    private void setAnimation(int newAnimId) {
        if (animationId != newAnimId) {
            animationId = newAnimId;
            animFrameIndex = 0;
            animTimer = 0;
            // Reset to frame 0 immediately
            mappingFrame = 0;
        }
    }

    /**
     * LCon_ChangeDir: Calculates velocity components to move from current
     * position toward the target waypoint using the shared dominant-axis algorithm.
     * <p>
     * From disassembly LCon_ChangeDir (shared with LZ Conveyor, lines 226-280
     * in docs/s1disasm/_incObj/63 LZ Conveyor.asm).
     *
     * @see WaypointPathFollower#calculateWaypointVelocity
     */
    private void changeDirection() {
        var vel = WaypointPathFollower.calculateWaypointVelocity(x, y, targetX, targetY, MOVE_SPEED);
        velX = vel.xVel();
        velY = vel.yVel();
        xFrac = vel.xSub();
        yFrac = vel.ySub();
    }

    /**
     * SpeedToPos: Applies velocity to position using 16.16 fixed-point arithmetic.
     * <p>
     * From disassembly (SpeedToPos.asm):
     * <pre>
     *   move.w  obVelX(a0),d0
     *   ext.l   d0
     *   lsl.l   #8,d0
     *   add.l   d0,obX(a0)    ; adds to 32-bit X (16.16)
     *   move.w  obVelY(a0),d0
     *   ext.l   d0
     *   lsl.l   #8,d0
     *   add.l   d0,obY(a0)    ; adds to 32-bit Y (16.16)
     * </pre>
     */
    private void applySpeedToPos() {
        motion.x = x; motion.y = y;
        motion.xSub = xFrac; motion.ySub = yFrac;
        motion.xVel = velX; motion.yVel = velY;
        SubpixelMotion.speedToPos(motion);
        x = motion.x; y = motion.y;
        xFrac = motion.xSub; yFrac = motion.ySub;
    }

    /**
     * Detach player if riding this object, matching the disassembly's loc_16404:
     * <pre>
     *   btst    #3,obStatus(a0)
     *   beq.s   loc_16420
     *   lea     (v_player).w,a1
     *   bclr    #3,obStatus(a1)
     *   bclr    #3,obStatus(a0)
     *   clr.b   obSolid(a0)
     * </pre>
     */
    private void detachRidingPlayer(AbstractPlayableSprite player) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null && objectManager.isAnyPlayerRiding(this)) {
            objectManager.clearRidingObject(player);
        }
    }

    // ========================================
    // Rendering
    // ========================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (mode == Mode.SPAWNER) {
            return;
        }

        // Uses same art as Object 0x69 spinner: Map_Spin, ArtTile_SBZ_Spinning_Platform, palette 0
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_SPINNING_PLATFORM);
        if (renderer == null) return;

        // Extract flip flags from the current raw animation frame byte.
        // AnimateSprite Anim_Next: bits 5-6 encode hFlip/vFlip
        int rawFrame = getCurrentRawFrame();
        boolean hFlip = (rawFrame & 0x20) != 0; // bit 5 -> hFlip
        boolean vFlip = (rawFrame & 0x40) != 0; // bit 6 -> vFlip
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, vFlip);
    }

    /**
     * Returns the raw frame byte for the currently displayed animation frame.
     * Used to extract flip flags for rendering.
     */
    private int getCurrentRawFrame() {
        if (animationId == 1) {
            // .still: always frame 0, no flips
            return 0;
        }
        // .spin: animFrameIndex points to the NEXT frame; current is at index-1
        if (animFrameIndex > 0 && animFrameIndex <= SPIN_RAW_SEQUENCE.length) {
            return SPIN_RAW_SEQUENCE[animFrameIndex - 1];
        }
        return 0;
    }

    // ========================================
    // Solid object interface
    // ========================================

    @Override
    public SolidObjectParams getSolidParams() {
        if (mode == Mode.PLATFORM) {
            return SOLID_PARAMS;
        }
        return null;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return mode == Mode.PLATFORM && solidActive;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Standard SolidObject handling via the engine's SolidContacts system.
        // The disassembly uses SolidObject (not PlatformObject), which provides
        // top, left, right, and bottom collision. The engine handles this automatically.
    }

    // ========================================
    // Persistence and priority
    // ========================================

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(RENDER_PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        if (isDestroyed()) {
            return false;
        }
        if (mode == Mode.SPAWNER) {
            return !spawnerDone;
        }
        // Platform: out_of_range.s uses objoff_30 (base X)
        // Act 3 has a wider range check: cmpi.w #-$80,d0 / bhs.s SpinC_Display
        //
        // ROM runs SpinC_Main (which sets objoff_30/baseX) before the out_of_range
        // macro that follows the jsr (docs/s1disasm/_incObj/6F SBZ Spin Platform
        // Conveyor.asm:5-13,53-54). The engine loads baseX lazily in
        // ensureInitialized() on the first update(); until then baseX is the
        // sentinel 0 and must not be treated as off-screen, else the platform is
        // despawned on its spawn frame before it can initialise (matching the LZ
        // conveyor fix in Sonic1LZConveyorObjectInstance.isPersistent()).
        if (!initialized) {
            return true;
        }
        // Act 3 only: ROM keeps platforms within one chunk (0x80) to the left of
        // the window (cmpi.w #-$80,d0 / bhs.s SpinC_Display,
        // docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:17-21); acts
        // 1/2 use the standard window. Modelled on the ROM act value, not the
        // zone/trace.
        if (services().currentAct() == ACT3) {
            return isInRangeAtWithLeftExtension(baseX, 1);
        }
        return isBaseXOnScreen(baseX);
    }

    // From disassembly: cmpi.b #act3,(v_act).w. Act index is 0-based (act 3 = 2).
    private static final int ACT3 = 2;

    /**
     * Check if the object is within out-of-range distance from camera.
     * Matches the S1 out_of_range.s macro with objoff_30 as the reference X.
     */
    private boolean isBaseXOnScreen(int objectX) {
        return isInRangeAt(objectX);
    }

    @Override
    public boolean isHighPriority() {
        return mode == Mode.SPAWNER;
    }

    /**
     * Clears the spawner's {@code v_obj63} latch when this platform's cluster
     * leaves the camera window, so the spawner re-spawns the cluster when the
     * camera returns.
     * <p>
     * ROM models this through {@code SpinC_OutOfRange}'s
     * {@code bclr #0,(v_obj63,slot)}
     * (docs/s1disasm/_incObj/6F SBZ Spin Platform Conveyor.asm:24-28): when a
     * SpinConvey object goes out of range it clears its spawner slot's latch so
     * a later re-load of that spawner re-creates the children. All of a
     * spawner's children share the same {@code SpinC_Data} group baseX
     * ({@code objoff_30}, e.g. group3 = 0xF80), so they leave the window
     * together; clearing the latch on a platform's out-of-range unload matches
     * the observed ROM re-spawn (the SBZ1 group3 cluster vanishes ~f3550-3650
     * and reappears in fresh slots by f3750). Each spawner slot maps 1:1 to its
     * children's {@code pathIndex} (spawner subtype 0x80+N spawns children with
     * subtype 0xN0-0xN3, so {@code (subtype>>4)&7 == N}).
     * <p>
     * Gated to PLATFORM mode and the out-of-range unload path only: the
     * spawner's own self-delete after spawning ({@code spawnerDone}) must not
     * clear the latch, or the cluster would re-spawn every frame. The
     * {@code unloadedByOutOfRange} flag is set by {@link #isPersistent()}
     * returning false purely due to leaving the window (not destruction).
     */
    @Override
    public void onUnload() {
        if (mode == Mode.PLATFORM && spawnerOwnsGroupLatch && initialized && !isDestroyed()) {
            // Reached here via the counter-based out_of_range unload path
            // (ObjectManager calls onUnload() before freeing the slot). A
            // platform reaching out_of_range is the ROM SpinC_OutOfRange case.
            Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
            if (conveyorState != null) {
                // ROM: andi.w #$7F,d0 on spinc_groupid, i.e. the spawner's own
                // slot index, not the platform's path index.
                conveyorState.clearSpawned(spawnerSlotIndex);
            }
        }
    }

    // ========================================
    // Debug rendering
    // ========================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (mode == Mode.SPAWNER) {
            return;
        }

        float r, g, b;
        if (solidActive) {
            r = 0.2f; g = 0.8f; b = 0.2f; // green = solid (flat)
        } else {
            r = 0.8f; g = 0.2f; b = 0.2f; // red = non-solid (spinning)
        }
        ctx.drawRect(x, y, SOLID_PARAMS.halfWidth(), SOLID_PARAMS.groundHalfHeight(), r, g, b);

        // Draw target waypoint indicator
        if (waypoints != null) {
            ctx.drawRect(targetX, targetY, 3, 3, 1.0f, 0.3f, 0.3f);
        }

        String state = solidActive ? "SOLID" : "SPIN";
        String label = String.format("0x6F wp%d/%d f%d %s",
                currentWaypointIdx / WAYPOINT_STEP,
                waypoints != null ? waypoints.length : 0,
                mappingFrame,
                state);
        ctx.drawWorldLabel(x, y - SOLID_PARAMS.groundHalfHeight() - 8, 0, label, DebugColor.CYAN);
    }
}
