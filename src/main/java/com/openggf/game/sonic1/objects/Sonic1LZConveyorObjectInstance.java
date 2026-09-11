package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1ConveyorState;
import com.openggf.game.sonic1.Sonic1ObjectPlacement;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
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

import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Object 63 - Platforms on a conveyor belt (LZ).
 * <p>
 * This object has three distinct modes selected by the subtype byte:
 * <ul>
 *   <li><b>Spawner mode</b> (subtype bit 7 set, i.e. >= $80): Reads child platform positions
 *       from hardcoded path tables and spawns individual platform instances. Uses v_obj63
 *       to prevent duplicate spawning. The spawner itself has no visual representation.</li>
 *   <li><b>Platform mode</b> (subtype < $80, subtype != $7F): A top-solid moving platform
 *       that follows waypoints from one of 6 path data tables. Platforms move between
 *       waypoints with velocity-based interpolation, and can reverse direction when
 *       switch $E is triggered (f_conveyrev). Mapping frame 4 (32x16 platform),
 *       palette line 2, priority 4.</li>
 *   <li><b>Wheel mode</b> (subtype == $7F): An animated wheel sprite that cycles through
 *       4 animation frames (0-3) every 4th game frame. Palette line 0, priority 1.
 *       Uses RememberState (persists when off-screen).</li>
 * </ul>
 * <p>
 * Path data tables (LCon_Data): Each of 6 paths defines a closed loop of X/Y waypoints.
 * Subtype bits 4-6 select the path table. Subtype bits 0-3 select the starting waypoint
 * index. The platform interpolates velocity toward each target waypoint using
 * LCon_ChangeDir, then advances to the next waypoint when it arrives.
 * <p>
 * Reference: docs/s1disasm/_incObj/63 LZ Conveyor.asm
 */
public class Sonic1LZConveyorObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable, SolidObjectProvider, SolidObjectListener {

    private static final Logger LOGGER = Logger.getLogger(Sonic1LZConveyorObjectInstance.class.getName());

    // ---- Constants from disassembly ----

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int HALF_WIDTH = 0x10;

    // MvSonicOnPtfm2 hardcodes "subi.w #9,d0" for the ground half-height
    // (docs/s1disasm/_incObj/sub MvSonicOnPtfm.asm). PlatformObject uses obY-8
    // for detection (sub PlatformObject.asm:17); the -1 adjustment in
    // getTopLandingSnapAdjustment() compensates so detection lands at obY-8.
    private static final int HALF_HEIGHT = 9;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PLATFORM_PRIORITY = 4;

    // From disassembly: move.b #1,obPriority(a0) (for wheel subtype 0x7F)
    private static final int WHEEL_PRIORITY = 1;

    // Velocity magnitude for LCon_ChangeDir: move.w #-$100,d2 / move.w #-$100,d3
    private static final int MOVE_SPEED = 0x100;

    // Waypoint step size in bytes: 4 bytes per waypoint (2 words: X, Y)
    private static final int WAYPOINT_STEP = 4;

    // Switch index checked for conveyor reversal: tst.b (f_switch+$E).w
    private static final int REVERSAL_SWITCH_INDEX = 0x0E;

    // Frame mask for wheel animation timing: andi.w #3,d0
    private static final int WHEEL_ANIM_FRAME_MASK = 3;

    // Platform mapping frame index: move.b #4,obFrame(a0)
    private static final int PLATFORM_FRAME = 4;

    // ---- Instance state ----

    /** Object mode: SPAWNER, PLATFORM, or WHEEL. */
    private enum Mode { SPAWNER, PLATFORM, WHEEL }

    private Mode mode;

    // Current position (updated by movement for platforms)
    private int x;
    private int y;

    // Platform mode state
    private int[][] waypoints;       // waypoint array for this platform's path
    private int waypointCount;       // number of waypoints in the path
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
    private int pathIndex;           // subtype bits 4-6 selecting LCon_Data entry
    private int baseX;               // base X for out_of_range check (objoff_30)
    private boolean dirReversed;     // local tracking of f_conveyrev state (objoff_3B)
    private int routine;             // platform routine: 2 = PlatformObject, 4 = ExitPlatform+MvSonicOnPtfm2
    private boolean initialized;     // lazy-init guard for services() deferral

    // Spawner mode state
    private int spawnerSlotIndex;    // v_obj63 slot index (objoff_2F & 0x7F)
    private boolean spawnerDone;     // set after spawning children

    // Platform #0 only: the maker's v_obj63 dedup slot it must clear when it
    // goes out of range, so the spawner re-creates the cluster on re-entry.
    // ROM: the maker reuses its own slot for platform #0 (movea.l a0,a1) leaving
    // objoff_2F holding the maker subtype ($80+); loc_12378 does
    // bclr #0,(v_obj63,objoff_2F&$7F) on that platform's out_of_range delete
    // (docs/s1disasm/_incObj/63 LZ Conveyor.asm:22-28). -1 = not platform #0.
    private int makerDedupSlot = -1;

    // Wheel mode state
    private int wheelFrame;          // current animation frame (0-3)

    /**
     * Creates a conveyor belt object instance.
     *
     * @param spawn the object spawn data from the level
     */
    public Sonic1LZConveyorObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LZConveyor");
        int subtype = spawn.subtype() & 0xFF;

        this.x = spawn.x();
        this.y = spawn.y();

        if ((subtype & 0x80) != 0) {
            // Subtype bit 7 set: spawner mode
            // From disassembly loc_12460: move.b d0,objoff_2F(a0)
            this.mode = Mode.SPAWNER;
            this.spawnerSlotIndex = subtype & 0x7F;
            this.spawnerDone = false;
            this.routine = 0;
        } else if (subtype == 0x7F) {
            // Subtype 0x7F: wheel mode (routine 6)
            // From disassembly: addq.b #4,obRoutine(a0) -> routine 6 immediately
            this.mode = Mode.WHEEL;
            this.wheelFrame = 0;
            this.routine = 6;
        } else {
            // Subtype 0x00-0x7E (excluding 0x7F): platform mode
            this.mode = Mode.PLATFORM;
            this.routine = 2; // starts in PlatformObject routine

            // Parse path index from subtype bits 4-6
            // From disassembly: lsr.w #3,d0 / andi.w #$1E,d0
            this.pathIndex = (subtype >> 4) & 0x07;
            if (pathIndex >= 6) {
                pathIndex = 0; // safety fallback
            }

            // Starting waypoint from subtype bits 0-3
            // From disassembly: andi.w #$F,d1 / lsl.w #2,d1
            int startWaypointIdx = (subtype & 0x0F) * WAYPOINT_STEP;
            this.currentWaypointIdx = startWaypointIdx;

            // Default step: +4 (forward)
            // From disassembly: move.b #4,objoff_3A(a0)
            this.waypointStep = WAYPOINT_STEP;

            // Initialize velocity and subpixel fractions
            this.velX = 0;
            this.velY = 0;
            this.xFrac = 0;
            this.yFrac = 0;
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * Package-private constructor for platforms spawned by the spawner.
     * Creates a platform at the specified position with the given subtype.
     */
    Sonic1LZConveyorObjectInstance(int spawnX, int spawnY, int subtype) {
        this(new ObjectSpawn(spawnX, spawnY,
                Sonic1ObjectIds.LZ_CONVEYOR, subtype, 0, false, 0));
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
            Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
            if (conveyorState.isReversed()) {
                // From disassembly: move.b #1,objoff_3B(a0) / neg.b objoff_3A(a0)
                this.dirReversed = true;
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
                    new Sonic1ObjectPlacement(services().romReader()).loadLzConveyorPath(pathIndex);
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
            LOGGER.log(Level.WARNING, "Failed to load LZ conveyor path data from ROM", e);
            return false;
        }
    }

    /**
     * Clears the maker's {@code v_obj63} dedup latch when platform #0 leaves the
     * camera window, so the spawner re-creates the conveyor cluster on re-entry.
     * <p>
     * ROM models this in {@code loc_12378}: when a conveyor object hits
     * {@code out_of_range}, it reads {@code objoff_2F}; only platform #0 (the
     * maker's reincarnated slot) has it negative, so only platform #0 runs
     * {@code andi.w #$7F,d0 / bclr #0,(v_obj63,d0.w)}
     * (docs/s1disasm/_incObj/63 LZ Conveyor.asm:22-28). Platforms #1..N have
     * {@code objoff_2F=0} and just delete. Without this, the spawner's
     * {@code bset #0,(v_obj63,slot)} latch (set on first spawn) is never cleared,
     * so re-entering the spawner window deletes the maker without re-creating the
     * platforms — the cluster permanently disappears after the first visit
     * (S1 LZ1 group1 conveyor, trace frame 9716: player falls through the empty
     * loop instead of landing on the re-spawned platform at @129D,0455).
     * <p>
     * Mirrors the SBZ spin-conveyor precedent
     * ({@code Sonic1SpinConveyorObjectInstance.onUnload}). Gated to the
     * out_of_range unload path ({@code !isDestroyed()}) so the maker's own
     * self-delete after spawning never clears the latch.
     */
    @Override
    public void onUnload() {
        if (mode == Mode.PLATFORM && makerDedupSlot >= 0 && initialized && !isDestroyed()) {
            Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
            if (conveyorState != null) {
                conveyorState.clearSpawned(makerDedupSlot);
            }
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
            case WHEEL -> updateWheel(vIntRunCount);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (mode == Mode.SPAWNER) {
            return; // spawner has no visual
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_CONVEYOR);
        if (renderer == null) return;

        int frame;
        if (mode == Mode.WHEEL) {
            frame = wheelFrame & WHEEL_ANIM_FRAME_MASK;
            // Wheels use palette 0: make_art_tile(ArtTile_LZ_Conveyor_Belt,0,0)
            renderer.drawFrameIndex(frame, x, y, false, false, 0);
        } else {
            frame = PLATFORM_FRAME;
            // Platforms use palette 2 (sheet default)
            renderer.drawFrameIndex(frame, x, y, false, false);
        }
    }

    @Override
    public SolidObjectParams getSolidParams() {
        if (mode == Mode.PLATFORM) {
            return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
        }
        return null;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // LCon_Platform (routine 2) calls PlatformObject before LCon_Platform_Update
        // which calls SpeedToPos (docs/s1disasm/_incObj/63 LZ Conveyor.asm:149-153,
        // 191-232). Contact must be checked at the pre-move position, matching the
        // same pattern as S1 Obj18 (Sonic1PlatformObjectInstance).
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // PlatformObject (docs/s1disasm/_incObj/sub PlatformObject.asm:21-22) uses
        // UNSIGNED cmpi.w #-16,d0 / blo — rejects d0=0 (exact touch), standable
        // band is d0 in [-16,-1]. Matches Sonic1PlatformObjectInstance.
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // LCon_Platform passes obActWid directly as PlatformObject's d1
        // (docs/s1disasm/_incObj/63 LZ Conveyor.asm:150-152), so collision
        // half-width is already the standable width and must not be narrowed again.
        return true;
    }

    @Override
    public boolean carriesAirborneRiderAfterExitPlatform() {
        // LCon_OnPlatform (routine 4) calls ExitPlatform then unconditionally calls
        // MvSonicOnPtfm2 (docs/s1disasm/_incObj/63 LZ Conveyor.asm:157-164),
        // matching S1 Obj18 behaviour (Sonic1PlatformObjectInstance).
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // PlatformObject builds its entry surface from obY-8 (subq.w #8,d0 at
        // docs/s1disasm/_incObj/sub PlatformObject.asm:17), while MvSonicOnPtfm2
        // uses obY-9 for the riding surface. With HALF_HEIGHT=9 (riding surface),
        // this -1 offset shifts the detection band to obY-8. Matches
        // Sonic1PlatformObjectInstance.getTopLandingSnapAdjustment().
        return -1;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform contact is managed via ObjectManager riding checks.
        // When player stands on us, routine transitions from 2 to 4.
        if (mode == Mode.PLATFORM && contact.standing()) {
            routine = 4; // ExitPlatform + MvSonicOnPtfm2 mode
        }
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return mode == Mode.PLATFORM && !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        if (mode == Mode.WHEEL) {
            return RenderPriority.clamp(WHEEL_PRIORITY);
        }
        return RenderPriority.clamp(PLATFORM_PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        if (isDestroyed()) {
            return false;
        }
        if (mode == Mode.WHEEL) {
            // ROM LCon_Wheel ends with bra.w RememberState
            // (docs/s1disasm/_incObj/63 LZ Conveyor.asm:213-215). RememberState
            // still DELETES the sprite once it leaves the out_of_range window and
            // only preserves the placement's respawn flag — the SST slot frees.
            // Treating it as always-persistent kept every wheel alive (and its
            // dynamic slot pinned) through the end of the act, pushing the LZ3
            // capsule animals above the released-game Pri_EndAct slot-63 scan
            // ceiling. Fall through to the standard remembered-object culling.
            return false;
        }
        if (mode == Mode.SPAWNER) {
            // Spawner is transient - only needs one frame to spawn children
            return !spawnerDone;
        }
        // Platform: out_of_range uses objoff_30 (base X)
        // ROM: out_of_range.s loc_1236A, objoff_30(a0)
        // (docs/s1disasm/_incObj/63 LZ Conveyor.asm, line 10)
        //
        // ROM runs the routine (LabyrinthConvey -> LCon_Index -> LCon_Main) which
        // sets objoff_30 (baseX) BEFORE the out_of_range macro that follows the
        // jsr (docs/s1disasm/_incObj/63 LZ Conveyor.asm:5-13). A freshly spawned
        // platform therefore always has a valid objoff_30 when its first
        // out_of_range check runs. The engine loads baseX lazily in
        // ensureInitialized() on the first update(); until then baseX is the
        // sentinel 0 and must NOT be treated as an off-screen position, or the
        // platform is despawned before it can initialise (it would otherwise
        // self-cull on the spawn frame whenever the spawn pre-pass checks
        // out_of_range before the object's first routine runs).
        if (!initialized) {
            return true;
        }
        return isConveyorInRange(baseX);
    }

    /**
     * out_of_range for the conveyor platform, with the ROM's act-3 left-extension.
     * <p>
     * The base {@code out_of_range} macro keeps an object whose chunk-aligned
     * reference X is within {@code [screenAligned, screenAligned + 640]}
     * ({@code cmpi.w #128+320+192,d0 / bhi exit}). In <b>act 3 only</b>, the
     * LZ Conveyor's out-of-range tail does NOT immediately delete: it runs
     * {@code cmpi.w #-$80,d0 / bhs.s LCon_Display}
     * (docs/s1disasm/_incObj/63 LZ Conveyor.asm:16-20), so a platform whose
     * aligned baseX is up to one chunk (0x80) to the LEFT of the window
     * ({@code d0 in [0xFF80,0xFFFF]}) is kept alive and displayed instead of
     * deleted. Modelled on the ROM act value (not the zone/trace), so it is a
     * ROM-state branch, not a carve-out: the same object in acts 1/2 uses the
     * standard window.
     */
    private boolean isConveyorInRange(int referenceX) {
        // Act 3 only: ROM keeps platforms within one chunk (0x80) to the left of
        // the window (cmpi.w #-$80,d0 / bhs LCon_Display); acts 1/2 use the
        // standard window.
        if (services().currentAct() == ACT3) {
            return isInRangeAtWithLeftExtension(referenceX, 1);
        }
        return isInRangeAt(referenceX);
    }

    // From disassembly: cmpi.b #act3,(v_act).w. Act index is 0-based (act 3 = 2).
    private static final int ACT3 = 2;

    // ---- Spawner mode ----

    /**
     * Spawner routine (subtype bit 7 set).
     * Reads child platform entries from the hardcoded path data and spawns
     * individual platform objects. Each spawner slot is tracked in v_obj63
     * to prevent re-spawning.
     * <p>
     * From disassembly loc_12460:
     * <pre>
     *   move.b  d0,objoff_2F(a0)         ; save spawner slot info
     *   andi.w  #$7F,d0
     *   lea     (v_obj63).w,a2
     *   bset    #0,(a2,d0.w)             ; test-and-set spawned flag
     *   bne.w   DeleteObject             ; already spawned? delete
     *   add.w   d0,d0                    ; d0 = slot * 2
     *   andi.w  #$1E,d0
     *   addi.w  #ObjPosLZPlatform_Index-ObjPos_Index,d0
     *   lea     (ObjPos_Index).l,a2
     *   adda.w  (a2,d0.w),a2             ; a2 -> platform position list
     *   move.w  (a2)+,d1                 ; d1 = count - 1
     *   ...                              ; spawn loop: X, Y, subtype words
     * </pre>
     * <p>
     * Child position data is loaded from the ROM-backed ObjPosLZPlatform_Index
     * table through {@link Sonic1ObjectPlacement}.
     */
    private void updateSpawner() {
        if (spawnerDone) {
            return;
        }
        spawnerDone = true;

        Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);
        if (conveyorState.testAndSetSpawned(spawnerSlotIndex)) {
            // Already spawned - delete self (FixBugs: avoid returning to main loop)
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

        // Spawn child platforms.
        //
        // ROM parity (docs/s1disasm/_incObj/63 LZ Conveyor.asm:95-145): the maker
        // reuses ITS OWN object slot for platform #0 (loc_12460:
        // "movea.l a0,a1 / bra.s LCon_MakePtfms"), keeping obRoutine 0 so that
        // platform #0 does not run its first PlatformObject/SpeedToPos pass until
        // the FOLLOWING frame. Only platforms #1..N are allocated via FindFreeObj
        // (LCon_Loop, line 130, non-FixBugs REV01 path).
        //
        // Reusing the maker's slot is load-bearing for spawn cadence: it places
        // platform #0 in the maker's (high) slot and shifts the FindFreeObj
        // children down by one, so a descending-leg platform lands in a slot
        // BELOW the maker (already processed this frame -> first move next frame)
        // instead of above it (executed same frame). Spawning platform #0 as a
        // fresh FindFreeObj child instead made every belt platform start one
        // executed frame early, leaving ridden platforms a constant 1px ahead of
        // ROM (S1 LZ1 conveyor ride, trace frame 5745: y 0x02ED vs 0x02EE).
        final int makerSlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        final int dedupSlot = spawnerSlotIndex;
        for (int i = 0; i < positionData.length; i++) {
            final int childX = positionData[i][0];
            final int childY = positionData[i][1];
            final int childSubtype = positionData[i][2];
            final boolean reuseMakerSlot = (i == 0);

            spawnFreeChild(() -> {
                Sonic1LZConveyorObjectInstance child =
                        new Sonic1LZConveyorObjectInstance(childX, childY, childSubtype);
                if (reuseMakerSlot && makerSlot >= 0) {
                    // Platform #0 takes the maker's own slot (movea.l a0,a1);
                    // FindFreeObj is only used for platforms #1..N.
                    child.setSlotIndex(makerSlot);
                    // Platform #0 inherits the maker's negative objoff_2F, so it
                    // is the platform that clears the v_obj63 dedup latch on its
                    // out_of_range delete (ROM loc_12378). Platforms #1..N keep
                    // objoff_2F=0 and just delete.
                    child.makerDedupSlot = dedupSlot;
                }
                return child;
            });
        }

        // Spawner itself is consumed after spawning. Its slot was transferred to
        // platform #0 above (slotIndex now -1), so the self-delete does not
        // release that slot from the allocator.
        ObjectLifetimeOps.deleteNoRespawn(this);
    }

    private int[][] loadSpawnerPositionData(int slotIndex) {
        try {
            return new Sonic1ObjectPlacement(services().romReader()).loadLzPlatformChildren(slotIndex);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to load LZ conveyor child positions from ROM", e);
            return null;
        }
    }

    // ---- Platform mode ----

    /**
     * Platform routine: handles waypoint movement, collision, and switch-triggered reversal.
     * <p>
     * Routines 2 and 4 from disassembly:
     * <pre>
     * loc_124B2 (routine 2): PlatformObject + sub_12502
     * loc_124C2 (routine 4): ExitPlatform + sub_12502 + MvSonicOnPtfm2
     * </pre>
     */
    private void updatePlatform(int vIntRunCount, AbstractPlayableSprite player) {
        // Check for player standing (transitions between routine 2 and 4)
        boolean playerRiding = isPlayerRiding();
        if (playerRiding) {
            routine = 4;
        } else {
            routine = 2;
        }

        // Apply waypoint movement (sub_12502)
        applyConveyorMovement();

        updateDynamicSpawn(x, y);
    }

    /**
     * Sub_12502: Check switch $E for reversal, advance waypoints, apply velocity.
     * <p>
     * From disassembly sub_12502:
     * <pre>
     *   tst.b   (f_switch+$E).w           ; switch $E pressed?
     *   beq.s   loc_12520                  ; no -> check waypoint arrival
     *   tst.b   objoff_3B(a0)              ; already reversed?
     *   bne.s   loc_12520                  ; yes -> skip
     *   move.b  #1,objoff_3B(a0)           ; mark reversed
     *   move.b  #1,(f_conveyrev).w         ; set global reversal flag
     *   neg.b   objoff_3A(a0)              ; reverse step direction
     *   bra.s   loc_12534                  ; advance waypoint
     * </pre>
     */
    private void applyConveyorMovement() {
        Sonic1ConveyorState conveyorState = services().gameService(Sonic1ConveyorState.class);

        // Check switch $E for reversal trigger
        if (services().gameService(Sonic1SwitchManager.class).isPressed(REVERSAL_SWITCH_INDEX)
                && !dirReversed) {
            // First time switch $E is triggered
            dirReversed = true;
            conveyorState.setReversed(true);
            waypointStep = -waypointStep; // neg.b objoff_3A(a0)
            advanceWaypoint();
        } else {
            // Check if we've arrived at the target waypoint
            if (x == targetX && y == targetY) {
                advanceWaypoint();
            }
        }

        // Apply velocity to position (SpeedToPos)
        applySpeedToPos();
    }

    /**
     * Advances to the next waypoint and recalculates velocity.
     * <p>
     * From disassembly loc_12534.
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

        // Recalculate velocity
        changeDirection();
    }

    /**
     * LCon_ChangeDir: Calculates velocity components to move from current
     * position toward the target waypoint using the shared dominant-axis algorithm.
     * <p>
     * From disassembly LCon_ChangeDir (lines 226-280).
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
     * SpeedToPos: Applies velocity to position using 16.8 fixed-point arithmetic.
     * <p>
     * From disassembly (sub SpeedToPos.asm):
     * <pre>
     *   move.w  obVelX(a0),d0
     *   ext.l   d0
     *   lsl.l   #8,d0
     *   add.l   d0,obX(a0)    ; adds to 32-bit X (16.16 effectively)
     *   move.w  obVelY(a0),d0
     *   ext.l   d0
     *   lsl.l   #8,d0
     *   add.l   d0,obY(a0)    ; adds to 32-bit Y (16.16 effectively)
     * </pre>
     * <p>
     * On the 68000, obX is a 32-bit field: high word = integer position,
     * low word = subpixel fraction. The velocity is sign-extended to 32 bits
     * and shifted left by 8, giving an effective 8.8 -> 16.16 conversion.
     */
    private void applySpeedToPos() {
        motion.x = x; motion.y = y;
        motion.xSub = xFrac; motion.ySub = yFrac;
        motion.xVel = velX; motion.yVel = velY;
        SubpixelMotion.speedToPos(motion);
        x = motion.x; y = motion.y;
        xFrac = motion.xSub; yFrac = motion.ySub;
    }

    // ---- Wheel mode ----

    /**
     * Wheel routine (subtype 0x7F, routine 6).
     * Animates through 4 wheel frames, advancing every 4th game frame.
     * Direction depends on f_conveyrev global flag.
     * <p>
     * From disassembly loc_124DE:
     * <pre>
     *   move.w  (v_framecount).w,d0
     *   andi.w  #3,d0                  ; every 4th frame
     *   bne.s   loc_124FC              ; not time yet -> skip
     *   moveq   #1,d1                  ; default direction = +1
     *   tst.b   (f_conveyrev).w
     *   beq.s   loc_124F2
     *   neg.b   d1                     ; reversed -> direction = -1
     * loc_124F2:
     *   add.b   d1,obFrame(a0)
     *   andi.b  #3,obFrame(a0)         ; wrap to 0-3
     * </pre>
     */
    private void updateWheel(int vIntRunCount) {
        // Every 4th frame: advance animation
        if ((vIntRunCount & WHEEL_ANIM_FRAME_MASK) == 0) {
            int step = 1;
            if (services().gameService(Sonic1ConveyorState.class).isReversed()) {
                step = -1;
            }
            wheelFrame = (wheelFrame + step) & WHEEL_ANIM_FRAME_MASK;
        }
    }

    // ---- Utility methods ----

    @Override
    public boolean isHighPriority() {
        // Spawner objects need to run immediately to create children
        return mode == Mode.SPAWNER;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (mode == Mode.SPAWNER) {
            return;
        }

        if (mode == Mode.PLATFORM) {
            // Draw solid collision box
            ctx.drawRect(x, y, HALF_WIDTH, HALF_HEIGHT, 0.3f, 0.6f, 1.0f);

            // Draw target waypoint indicator
            if (waypoints != null) {
                ctx.drawRect(targetX, targetY, 3, 3, 1.0f, 0.3f, 0.3f);
            }

            // Label with state info
            String label = String.format("CV wp%d/%d %s",
                    currentWaypointIdx / WAYPOINT_STEP,
                    waypoints != null ? waypoints.length : 0,
                    dirReversed ? "REV" : "FWD");
            ctx.drawWorldLabel(x, y - HALF_HEIGHT - 8, 0, label, com.openggf.debug.DebugColor.CYAN);
        } else if (mode == Mode.WHEEL) {
            // Draw wheel position
            ctx.drawRect(x, y, 0x10, 0x10, 0.5f, 0.5f, 0.2f);
            String label = String.format("WHL f%d", wheelFrame);
            ctx.drawWorldLabel(x, y - 0x10 - 8, 0, label, com.openggf.debug.DebugColor.YELLOW);
        }
    }
}
