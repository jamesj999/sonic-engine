package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.WaypointPathFollower;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 0x6C - Small platform on pulleys (like at the start of MTZ2).
 * <p>
 * Platforms follow a closed-loop path of waypoints. The path is determined by the upper bits
 * of the subtype, and the starting waypoint within the path is determined by the lower nibble.
 * Each platform moves at a constant speed of 1 pixel/frame along the dominant axis, with
 * proportional velocity on the minor axis.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 54189-54444 (Obj6C code)
 * <p>
 * <b>Subtype encoding (individual platforms, bit 7 clear):</b>
 * <ul>
 *   <li>Bits 4-6: Path table index (subtype >> 4, via off_28252)</li>
 *   <li>Bits 0-3: Starting waypoint (subtype & 0xF) * 4 = byte offset into path</li>
 * </ul>
 * <p>
 * <b>Subtype encoding (parent spawner, bit 7 set):</b>
 * <ul>
 *   <li>Bits 0-6: Child layout table index (subtype & 0x7F, via off_282D6)</li>
 * </ul>
 * <p>
 * <b>Collision:</b> Top-solid platform (JmpTo5_PlatformObject), width_pixels=0x10, d3=8.
 * <p>
 * <b>Art:</b> ArtNem_LavaCup, palette line 3, single 32x16 frame.
 */
public class ConveyorObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    private static final Logger LOGGER = Logger.getLogger(ConveyorObjectInstance.class.getName());

    // From disassembly: move.b #$10,width_pixels(a0)
    private static final int WIDTH_PIXELS = 0x10;

    // From disassembly: moveq #8,d3 (y_radius for PlatformObject)
    private static final int Y_RADIUS = 8;

    // From disassembly: move.b #4,priority(a0)
    private static final int PRIORITY = 4;

    // Velocity magnitude (1 pixel/frame in 8.8 fixed point)
    // From disassembly: move.w #-$100,d2 / move.w #-$100,d3
    private static final int MOVE_SPEED = 0x100;

    // Movement step size for waypoint advancement
    // From disassembly: move.b #4,objoff_3A(a0)
    private static final int WAYPOINT_STEP = 4;

    private static final int STALE_LOGICAL_HORIZONTAL_FRAMES = 3;

    /**
     * Path waypoint tables from off_28252.
     * Each path is an array of (x_offset, y_offset) pairs relative to the base position.
     * Format: first 2 bytes = total byte length, then waypoints in 4-byte groups (x_word, y_word).
     * <p>
     * From disassembly: byte_28258, byte_28282, byte_282AC
     */
    private static final int[][][] PATH_WAYPOINTS = {
            // Path 0 (byte_28258): length=0x28 = 40 bytes = 10 waypoints
            {
                    {0x0000, 0x0000}, {0xFFEA, 0x000A}, {0xFFE0, 0x0020}, {0xFFE0, 0x00E0},
                    {0xFFEA, 0x00F6}, {0x0000, 0x0100}, {0x0016, 0x00F6}, {0x0020, 0x00E0},
                    {0x0020, 0x0020}, {0x0016, 0x000A},
            },
            // Path 1 (byte_28282): length=0x28 = 40 bytes = 10 waypoints
            {
                    {0x0000, 0x0000}, {0xFFEA, 0x000A}, {0xFFE0, 0x0020}, {0xFFE0, 0x0160},
                    {0xFFEA, 0x0176}, {0x0000, 0x0180}, {0x0016, 0x0176}, {0x0020, 0x0160},
                    {0x0020, 0x0020}, {0x0016, 0x000A},
            },
            // Path 2 (byte_282AC): length=0x28 = 40 bytes = 10 waypoints
            {
                    {0x0000, 0x0000}, {0xFFEA, 0x000A}, {0xFFE0, 0x0020}, {0xFFE0, 0x01E0},
                    {0xFFEA, 0x01F6}, {0x0000, 0x0200}, {0x0016, 0x01F6}, {0x0020, 0x01E0},
                    {0x0020, 0x0020}, {0x0016, 0x000A},
            },
    };

    /**
     * Child layout tables from off_282D6.
     * Each entry: {x_offset, y_offset, subtype}
     * <p>
     * From disassembly: byte_282DC, byte_2830E, byte_28340
     */
    private static final int[][][] CHILD_LAYOUTS = {
            // Layout 0 (byte_282DC): 8 children
            {
                    {0x0000, 0x0000, 0x01}, {0xFFE0, 0x003A, 0x03}, {0xFFE0, 0x0080, 0x03},
                    {0xFFE0, 0x00C6, 0x03}, {0x0000, 0x0100, 0x06}, {0x0020, 0x00C6, 0x08},
                    {0x0020, 0x0080, 0x08}, {0x0020, 0x003A, 0x08},
            },
            // Layout 1 (byte_2830E): 8 children
            {
                    {0x0000, 0x0000, 0x11}, {0xFFE0, 0x005A, 0x13}, {0xFFE0, 0x00C0, 0x13},
                    {0xFFE0, 0x0126, 0x13}, {0x0000, 0x0180, 0x16}, {0x0020, 0x0126, 0x18},
                    {0x0020, 0x00C0, 0x18}, {0x0020, 0x005A, 0x18},
            },
            // Layout 2 (byte_28340): 8 children
            {
                    {0x0000, 0x0000, 0x21}, {0xFFE0, 0x007A, 0x23}, {0xFFE0, 0x0100, 0x23},
                    {0xFFE0, 0x0186, 0x23}, {0x0000, 0x0200, 0x26}, {0x0020, 0x0186, 0x28},
                    {0x0020, 0x0100, 0x28}, {0x0020, 0x007A, 0x28},
            },
    };

    // Instance state

    /** Current pixel position. */
    private int x;
    private int y;

    /** Base position (objoff_30, objoff_32) - original spawn position. */
    private int baseX;
    private int baseY;

    /** Target waypoint position (objoff_34, objoff_36). */
    private int targetX;
    private int targetY;

    /** Current waypoint index in bytes (objoff_38). Low byte only. */
    private int waypointOffset;

    /** Total path byte length (objoff_39). High byte of objoff_38 word. */
    private int pathLength;

    /** Waypoint advance direction (objoff_3A): +4 or -4. */
    private int waypointDelta;

    /** Path waypoint data (objoff_3C pointer). */
    private int[][] pathData;

    /** Current subtype byte after parent spawners rewrite themselves into child 0. */
    private int activeSubtype;

    /** True while a bit-7 parent spawner is waiting for its first ExecuteObjects pass. */
    private boolean pendingParentExpansion;

    /** X/Y velocity in 8.8 fixed point. */
    private int xVel;
    private int yVel;

    /** Sub-pixel accumulators for 16.16 fixed point movement. */
    private int xSub;
    private int ySub;

    /** Reusable state for SubpixelMotion calls (avoids per-frame allocation). */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    /** X-flip from status byte. */
    private boolean xFlip;

    /** Collision params: half-width = width_pixels, d3 = 8. */
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(WIDTH_PIXELS, Y_RADIUS, Y_RADIUS);

    public ConveyorObjectInstance(ObjectSpawn spawn, String name) {
        this(spawn, name, spawn.x(), spawn.y());
    }

    /**
     * Constructor with explicit base position for the waypoint path origin.
     * <p>
     * Children spawned by a parent subtype (bit 7 set) inherit the PARENT's
     * x_pos/y_pos as their objoff_30/objoff_32 (path base), not their own spawn
     * position. ROM Obj6C_LoadSubObject (s2.asm:54137-54151) writes the parent's
     * d2/d3 (parent x/y, captured before the loop) into the child's objoff_30/_32
     * even though the child's x_pos/y_pos is set to {@code parent + layoutOffset}.
     * Without this distinction every child orbits around its own offset position
     * instead of the shared parent center, scattering the platforms.
     */
    public ConveyorObjectInstance(ObjectSpawn spawn, String name, int baseX, int baseY) {
        super(spawn, name);
        this.baseX = baseX;
        this.baseY = baseY;
        this.xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.activeSubtype = spawn.subtype() & 0xFF;

        int subtype = activeSubtype;
        if ((subtype & 0x80) != 0) {
            pendingParentExpansion = true;
            pathData = PATH_WAYPOINTS[0];
            pathLength = 0;
            x = spawn.x();
            y = spawn.y();
            targetX = x;
            targetY = y;
            waypointOffset = 0;
            waypointDelta = WAYPOINT_STEP;
            xSub = 0;
            ySub = 0;
            updateDynamicSpawn(x, y);
            return;
        }

        initializePlatformState(subtype, spawn.x(), spawn.y());
    }

    private void initializePlatformState(int subtype, int startX, int startY) {
        // Determine path table from upper bits. ROM (s2.asm:54227-54233):
        //   lsr.w #3,d0 / andi.w #$1E,d0  => word offset into off_28252.
        // As a table index (word entries) that is ((subtype>>3)&0x1E)/2
        //   = (subtype>>4)&0x0F  (full nibble, not masked to 0x07).
        int pathIndex = (subtype >> 4) & 0x0F;
        if (pathIndex >= PATH_WAYPOINTS.length) {
            pathIndex = 0;
        }
        this.pathData = PATH_WAYPOINTS[pathIndex];

        // Path length in bytes = pathData.length * 4
        // From disassembly: move.w (a2)+,objoff_38(a0) reads count word
        // The first word of each path table (e.g. 0x0028) is the byte length
        this.pathLength = pathData.length * 4;

        // Starting waypoint from lower nibble: (subtype & 0xF) * 4
        // From disassembly: andi.w #$F,d1; lsl.w #2,d1
        this.waypointOffset = (subtype & 0x0F) * 4;

        // Waypoint advance direction: +4 normally, -4 if x-flipped
        // From disassembly: move.b #4,objoff_3A(a0); btst status.npc.x_flip; neg.b
        this.waypointDelta = WAYPOINT_STEP;
        if (xFlip) {
            waypointDelta = -waypointDelta;
            // Advance one step and wrap (disassembly lines 54239-54249)
            waypointOffset = wrapWaypointOffset(waypointOffset + waypointDelta);
        }

        // Set initial position to base + waypoint offset
        int wpIndex = waypointOffset / 4;
        this.targetX = baseX + signExtend16(pathData[wpIndex][0]);
        this.targetY = baseY + signExtend16(pathData[wpIndex][1]);

        // Set current position and calculate initial velocity
        this.x = startX;
        this.y = startY;
        this.xSub = 0;
        this.ySub = 0;
        calculateVelocity();

        updateDynamicSpawn(x, y);
    }

    /**
     * Static factory method to spawn children for parent subtypes (bit 7 set).
     * <p>
     * <b>ROM parity:</b> {@code Obj6C_Init} at s2.asm:54269 ({@code loc_28112})
     * reuses the parent slot itself as the FIRST child:
     * {@code movea.l a0,a1} sets {@code a1 = a0} so the first
     * {@code Obj6C_LoadSubObject} pass overwrites the parent's
     * {@code x_pos/y_pos/subtype/objoff_30/objoff_32} with the first child layout
     * entry. The parent's subtype loses bit 7 (it becomes the child's subtype),
     * so subsequent frames run {@code Obj6C_Main} (the parent never re-spawns).
     * <p>
     * The engine mirrors this by returning the first child as the factory's
     * instance (occupying the parent spawn's slot in {@code activeObjects} so
     * placement does not re-trigger the spawn every frame) and spawning the
     * remaining children via {@link ObjectManager#createDynamicObject}.
     *
     * @param spawn The parent spawner's ObjectSpawn
     * @return The first child instance (or an individual platform when bit 7 is clear)
     */
    public static ConveyorObjectInstance createOrSpawnChildren(ObjectSpawn spawn) {
        return new ConveyorObjectInstance(spawn, "Conveyor");
    }

    static ConveyorObjectInstance recreateForRewind(ObjectSpawn spawn, PerObjectRewindSnapshot snapshot) {
        if (snapshot != null && snapshot.objectSubclassExtra() instanceof ConveyorRewindExtra extra) {
            return new ConveyorObjectInstance(spawn, "Conveyor", extra.baseX(), extra.baseY());
        }
        return new ConveyorObjectInstance(spawn, "Conveyor");
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return recreateForRewind(ctx.spawn(), ctx.state());
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState().withObjectSubclassExtra(
                new ConveyorRewindExtra(
                        x,
                        y,
                        baseX,
                        baseY,
                        targetX,
                        targetY,
                        waypointOffset,
                        waypointDelta,
                        xVel,
                        yVel,
                        xSub,
                        ySub,
                        activeSubtype,
                        pendingParentExpansion));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (snapshot.objectSubclassExtra() instanceof ConveyorRewindExtra extra) {
            x = extra.x();
            y = extra.y();
            targetX = extra.targetX();
            targetY = extra.targetY();
            waypointOffset = extra.waypointOffset();
            waypointDelta = extra.waypointDelta();
            xVel = extra.xVel();
            yVel = extra.yVel();
            xSub = extra.xSub();
            ySub = extra.ySub();
            activeSubtype = extra.activeSubtype();
            pendingParentExpansion = extra.pendingParentExpansion();
            if (!pendingParentExpansion) {
                int pathIndex = (activeSubtype >> 4) & 0x0F;
                if (pathIndex >= PATH_WAYPOINTS.length) {
                    pathIndex = 0;
                }
                pathData = PATH_WAYPOINTS[pathIndex];
                pathLength = pathData.length * 4;
            }
            rebuildDynamicSpawn(x, y);
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
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        // Obj6C uses PlatformObject (JmpTo5_PlatformObject) - top-solid only
        return true;
    }

    @Override
    public int staleHorizontalLogicalInputFramesWhileRiding(
            PlayableEntity player, int rideFrames, boolean left, boolean right) {
        /*
         * Obj6C_Main moves the platform, restores the saved pre-move x_pos as d4, then
         * jumps through PlatformObject. When Obj6C has horizontal displacement, that
         * PlatformObject ride step can carry Sonic before Sonic_Move consumes
         * Ctrl_1_Held_Logical for rider acceleration.
         */
        boolean inputOpposesHorizontalCarry = (xVel > 0 && left && !right) || (xVel < 0 && right && !left);
        return inputOpposesHorizontalCarry ? STALE_LOGICAL_HORIZONTAL_FRAMES : 0;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No special contact handling needed
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        if (pendingParentExpansion) {
            expandParentSpawner();
            return;
        }

        // Obj6C_Main (s2.asm:54304-54311): save old x_pos to the stack, run the
        // waypoint check + ObjectMove, then pass the PRE-MOVE x_pos as d4 to
        // JmpTo5_PlatformObject. The shared PlatformObject ride code uses that
        // pre-move x to carry a standing player by the platform's per-frame x
        // displacement (these pulley platforms move diagonally, so the rider
        // must follow horizontally as well as vertically).
        //
        // The engine's top-solid ride carry is owned centrally by the solid
        // system (ObjectManager.SolidContacts / continued-riding carry), which
        // tracks the riding object's frame-to-frame position delta. The capture
        // here mirrors the ROM's stack save so the pre-move x is available if a
        // future change needs to route an explicit horizontal carry through this
        // object; the central solid carry already follows the moving platform.
        int prevX = x;

        // loc_2817E: check if arrived at target, advance waypoint if so
        checkAndAdvanceWaypoint();

        // ObjectMove: apply velocity to position
        applyVelocity();

        // Per-frame horizontal displacement (pre-move x -> post-move x). Mirrors
        // the ROM d4 = pre-move x_pos handed to PlatformObject. Kept as a local
        // so the intent is explicit; rider X is carried by the central solid
        // system, not written directly here (avoids double-applying the delta).
        @SuppressWarnings("unused")
        int frameXDelta = x - prevX;

        // Off-screen despawn check using base position (objoff_30)
        // From disassembly: (objoff_30 & $FF80) - Camera_X_pos_coarse > $280
        if (!isBasePositionOnScreen()) {
            setDestroyed(true);
            return;
        }

        updateDynamicSpawn(x, y);
    }

    /**
     * ROM Obj6C parent spawners do not expand during ObjPosLoad. They first sit
     * in the loaded SST slot, then their routine-0 execution rewrites that same
     * slot into child 0 and allocates the remaining children
     * (docs/s2disasm/s2.asm:54632-54716).
     */
    private void expandParentSpawner() {
        int layoutIndex = activeSubtype & 0x7F;
        if (layoutIndex >= CHILD_LAYOUTS.length) {
            LOGGER.warning("Conveyor parent subtype 0x" + Integer.toHexString(activeSubtype)
                    + " has invalid layout index " + layoutIndex);
            setDestroyed(true);
            return;
        }

        int[][] layout = CHILD_LAYOUTS[layoutIndex];
        int parentX = spawn.x();
        int parentY = spawn.y();
        int parentStatus = spawn.renderFlags();

        int[] firstChild = layout[0];
        int firstX = parentX + signExtend16(firstChild[0]);
        int firstY = parentY + signExtend16(firstChild[1]);
        activeSubtype = firstChild[2] & 0xFF;
        pendingParentExpansion = false;
        initializePlatformState(activeSubtype, firstX, firstY);

        ObjectManager manager = services().objectManager();
        if (manager != null && !ObjectConstructionContext.isRewindActiveRestore()) {
            for (int i = 1; i < layout.length; i++) {
                int[] child = layout[i];
                int childX = parentX + signExtend16(child[0]);
                int childY = parentY + signExtend16(child[1]);
                int childSubtype = child[2] & 0xFF;
                ObjectSpawn childSpawn = new ObjectSpawn(
                        childX, childY,
                        Sonic2ObjectIds.CONVEYOR,
                        childSubtype,
                        parentStatus,
                        false,
                        spawn.rawYWord());
                manager.createDynamicObject(() ->
                        new ConveyorObjectInstance(childSpawn, "Conveyor", parentX, parentY));
            }
        }
    }

    @Override
    protected ObjectSpawn buildSpawnAt(int x, int y) {
        return new ObjectSpawn(x, y, spawn.objectId(), activeSubtype,
                spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord(),
                spawn.layoutIndex());
    }

    /**
     * Check if the base position (objoff_30) is within the despawn range of the camera.
     * Mirrors the ROM's MarkObjGone check with Camera_X_pos_coarse_back:
     * ((baseX & 0xFF80) - ((cameraX - 0x80) & 0xFF80)) <= 0x280.
     */
    private boolean isBasePositionOnScreen() {
        return isInRangeAt(baseX);
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,priority(a0)
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = null;

        if (renderManager != null) {
            renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.MTZ_LAVA_CUP);
        }

        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(0, x, y, false, false);
        }
    }

    /**
     * Check if the platform has arrived at its target waypoint.
     * If so, advance to the next waypoint and recalculate velocity.
     * <p>
     * From disassembly loc_2817E (line 54310-54338):
     * Compares x_pos to target X and y_pos to target Y. If both match,
     * advances the waypoint offset by waypointDelta, wraps around the
     * path length, reads the next waypoint, and recalculates velocity.
     */
    private void checkAndAdvanceWaypoint() {
        if (x != targetX || y != targetY) {
            return;
        }

        // Advance waypoint offset and wrap
        waypointOffset = wrapWaypointOffset(waypointOffset + waypointDelta);

        // Read new target from path data
        int wpIndex = waypointOffset / 4;
        targetX = baseX + signExtend16(pathData[wpIndex][0]);
        targetY = baseY + signExtend16(pathData[wpIndex][1]);

        // Recalculate velocity toward new target
        calculateVelocity();
    }

    /**
     * Calculate velocity to move from current position to target using
     * the shared LCon_ChangeDir dominant-axis algorithm.
     * <p>
     * From disassembly loc_281DA (lines 54345-54398).
     *
     * @see WaypointPathFollower#calculateWaypointVelocity
     */
    private void calculateVelocity() {
        var vel = WaypointPathFollower.calculateWaypointVelocity(x, y, targetX, targetY, MOVE_SPEED);
        xVel = vel.xVel();
        yVel = vel.yVel();
        xSub = vel.xSub();
        ySub = vel.ySub();
    }

    /**
     * Apply velocity to position (ObjectMove equivalent).
     * <p>
     * From disassembly ObjectMove (s2.asm line 29990):
     * Position is stored as 16.16 fixed point (x_pos:x_sub as 32-bit long).
     * Velocity (x_vel) is sign-extended to 32 bits, shifted left 8, then added.
     * <pre>
     * d2 = x_pos:x_sub              ; 32-bit position
     * d0 = ext.l(x_vel) << 8        ; velocity shifted into middle 16 bits
     * d2 += d0                       ; add velocity
     * x_pos:x_sub = d2              ; store back
     * </pre>
     */
    private void applyVelocity() {
        // Delegates to SubpixelMotion.speedToPos for ROM-accurate 16.16 integration
        motion.x = x; motion.y = y;
        motion.xSub = xSub; motion.ySub = ySub;
        motion.xVel = xVel; motion.yVel = yVel;
        SubpixelMotion.speedToPos(motion);
        x = motion.x; y = motion.y;
        xSub = motion.xSub; ySub = motion.ySub;
    }

    /**
     * Wrap waypoint offset around the path length.
     * When advancing past the end, wraps to 0. When going before 0, wraps to end.
     * <p>
     * From disassembly (lines 54319-54327).
     *
     * @see WaypointPathFollower#wrapWaypointIndex
     */
    private int wrapWaypointOffset(int offset) {
        return WaypointPathFollower.wrapWaypointIndex(offset, pathLength, WAYPOINT_STEP);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = WIDTH_PIXELS;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - Y_RADIUS;
        int bottom = y + Y_RADIUS + 1;

        // Green box for platform collision bounds
        ctx.drawLine(left, top, right, top, 0.4f, 0.9f, 0.4f);
        ctx.drawLine(right, top, right, bottom, 0.4f, 0.9f, 0.4f);
        ctx.drawLine(right, bottom, left, bottom, 0.4f, 0.9f, 0.4f);
        ctx.drawLine(left, bottom, left, top, 0.4f, 0.9f, 0.4f);

        // Center cross
        ctx.drawLine(x - 4, y, x + 4, y, 0.4f, 0.9f, 0.4f);
        ctx.drawLine(x, y - 4, x, y + 4, 0.4f, 0.9f, 0.4f);
    }

    /**
     * Sign-extend a 16-bit value stored as int to a signed int.
     */
    private static int signExtend16(int value) {
        return (short) value;
    }

    private record ConveyorRewindExtra(
            int x,
            int y,
            int baseX,
            int baseY,
            int targetX,
            int targetY,
            int waypointOffset,
            int waypointDelta,
            int xVel,
            int yVel,
            int xSub,
            int ySub,
            int activeSubtype,
            boolean pendingParentExpansion
    ) implements PerObjectRewindSnapshot.ObjectSubclassRewindExtra {
    }
}
