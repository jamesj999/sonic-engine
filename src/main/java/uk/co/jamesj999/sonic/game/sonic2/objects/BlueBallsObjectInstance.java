package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CPZ BlueBalls Object (Obj1D) - Bouncing water droplet hazard.
 * <p>
 * Blue balls that bounce up and down in CPZ tubes. They hurt the player on contact.
 * Multiple balls can be spawned from one object based on the subtype.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 47835-47969
 * <ul>
 *   <li>Obj1D_Init: line 47863 (initialization)</li>
 *   <li>Obj1D_Wait: line 47893 (waiting between bounces)</li>
 *   <li>Obj1D_MoveArc: line 47911 (arc motion)</li>
 *   <li>Obj1D_MoveStraight: line 47929 (straight motion)</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0x1D</td><td>ObjID_BlueBalls</td></tr>
 *   <tr><td>Initial Y Velocity</td><td>-$480 (-1152)</td><td>line 47866</td></tr>
 *   <tr><td>Gravity</td><td>$18 (24)</td><td>line 47917</td></tr>
 *   <tr><td>Wait Duration</td><td>$3B (59 frames)</td><td>line 47899</td></tr>
 *   <tr><td>Collision Flags</td><td>$8B</td><td>line 47880 (HURT category, size 0x0B)</td></tr>
 *   <tr><td>Width Pixels</td><td>$08 (8 px)</td><td>line 47878</td></tr>
 *   <tr><td>X Velocity Delta</td><td>$0B (11)</td><td>line 47887 (arc motion)</td></tr>
 *   <tr><td>X Distance</td><td>$60 (96)</td><td>line 47886 (total arc width)</td></tr>
 *   <tr><td>Sound</td><td>SndID_Gloop (0xDA)</td><td>line 47902</td></tr>
 * </table>
 *
 * <h3>Subtype Format</h3>
 * <ul>
 *   <li>Low nibble (0x0F): Number of additional balls to spawn (0-15)</li>
 *   <li>High nibble (0xF0): Movement type (0x00 = arc, non-zero = straight)</li>
 * </ul>
 *
 * <h3>Art Data</h3>
 * <ul>
 *   <li>Art: ArtNem_CPZDroplet (art/nemesis/CPZ worm enemy.nem)</li>
 *   <li>Art Tile: $043C</li>
 *   <li>Mappings: Map_obj1D (single 2x2 tile frame at -8,-8)</li>
 * </ul>
 *
 * @see SpeedBoosterObjectInstance Another CPZ-specific object
 */
public class BlueBallsObjectInstance extends AbstractObjectInstance implements TouchResponseProvider {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * Initial upward Y velocity = -$480 (-1152 in 8.8 fixed point).
     */
    private static final int INITIAL_Y_VEL = -0x480;

    /**
     * Gravity per frame = $18 (24).
     */
    private static final int GRAVITY = 0x18;

    /**
     * Wait duration between bounces = $3B (59 frames, ~1 second at 60fps).
     */
    private static final int WAIT_DURATION = 0x3B;

    /**
     * X velocity delta per frame for arc motion = $0B (11).
     */
    private static final int ARC_X_VEL_DELTA = 0x0B;

    /**
     * Total X distance for arc motion = $60 (96 pixels).
     */
    private static final int ARC_X_DISTANCE = 0x60;

    /**
     * Sound ID for the "gloop" sound effect (water droplet).
     */
    private static final int SND_ID_GLOOP = 0xDA;

    /**
     * Collision flags = $8B.
     * Category bits = 0x80 = HURT (always damages player).
     * Size index = 0x0B = 11.
     */
    private static final int COLLISION_FLAGS = 0x8B;

    // ========================================================================
    // State Machine
    // The disassembly uses separate wait states for arc and straight motion:
    // - Routine 2 (Wait) ↔ Routine 4 (MoveArc) for arc motion
    // - Routine 6 (Wait) ↔ Routine 8 (MoveStraight) for straight motion
    // ========================================================================

    private static final int STATE_WAIT_ARC = 0;      // Routine 2
    private static final int STATE_MOVE_ARC = 1;      // Routine 4
    private static final int STATE_WAIT_STRAIGHT = 2; // Routine 6
    private static final int STATE_MOVE_STRAIGHT = 3; // Routine 8

    /**
     * Wait timer stagger between siblings = 3 frames.
     * ROM: addq.w #3,d2 at line 47905
     */
    private static final int SIBLING_WAIT_STAGGER = 3;

    // ========================================================================
    // Global State Tracking
    // ========================================================================

    /**
     * Count of active BlueBalls instances. Used for lifecycle tracking.
     */
    private static int activeInstanceCount = 0;

    // ========================================================================
    // Sibling Spawn Tracking (Bug Fix #2)
    // Tracks which spawn locations have already created siblings.
    // Persists across object load/unload cycles within a level.
    // ========================================================================

    /**
     * Registry of spawn locations that have already spawned siblings.
     * Key: (x << 16) | (y & 0xFFFF) to create unique identifier.
     */
    private static final Set<Long> spawnedSiblingLocations = new HashSet<>();

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current X position (subpixels). */
    private int currentX;

    /** Current Y position (subpixels). */
    private int currentY;

    /** Current X velocity (subpixels/frame). */
    private int xVelocity;

    /** Current Y velocity (subpixels/frame). */
    private int yVelocity;

    /** Initial/target Y position (returns here after bounce). */
    private final int initialY;

    /** Initial X position (for straight motion reset). */
    private final int initialX;

    /** X velocity delta direction (+/- ARC_X_VEL_DELTA). */
    private int xVelDelta;

    /** X distance direction (+/- ARC_X_DISTANCE for straight motion). */
    private int xDistance;

    /** Current state (WAIT, MOVE_ARC, MOVE_STRAIGHT). */
    private int state;

    /** Wait timer (counts down during STATE_WAIT). */
    private int waitTimer;

    /** Movement type: true = arc motion, false = straight motion. */
    private final boolean arcMotion;

    /** Whether this ball has already spawned siblings. */
    private boolean hasSpawnedSiblings;

    /** Number of siblings to spawn (from subtype low nibble). */
    private final int siblingCount;

    /** X-flip status (from Sonic's facing direction at spawn time, stored in renderFlags). */
    private final boolean xFlipped;

    public BlueBallsObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.currentX = spawn.x() << 8; // Convert to subpixels
        this.currentY = spawn.y() << 8;
        this.initialX = spawn.x();
        this.initialY = spawn.y();
        this.yVelocity = INITIAL_Y_VEL;
        this.xVelocity = 0;

        // Decode subtype
        int subtype = spawn.subtype();
        this.siblingCount = subtype & 0x0F;
        this.arcMotion = (subtype & 0xF0) == 0;

        // X flip from render flags (bit 0)
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;

        // Set X deltas based on flip direction
        this.xVelDelta = xFlipped ? -ARC_X_VEL_DELTA : ARC_X_VEL_DELTA;
        this.xDistance = xFlipped ? -ARC_X_DISTANCE : ARC_X_DISTANCE;

        // Start in appropriate wait state (not motion state)
        if (arcMotion) {
            this.state = STATE_WAIT_ARC;
        } else {
            this.state = STATE_WAIT_STRAIGHT;
        }
        // Parent starts with waitTimer = 0, which means it fires immediately
        // (ROM: 0 - 1 = -1, which is negative, so skip wait)
        this.waitTimer = 0;
        this.hasSpawnedSiblings = false;

        // Global instance tracking
        activeInstanceCount++;
    }

    /**
     * Constructor for spawned sibling balls.
     * Siblings don't spawn more siblings and have staggered initial wait timers.
     *
     * @param initialWaitTimer Initial countdown timer (3, 6, 9... for siblings)
     */
    private BlueBallsObjectInstance(ObjectSpawn spawn, String name, int yVelocity,
                                    int xVelDelta, int xDistance, boolean arcMotion,
                                    int initialWaitTimer) {
        super(spawn, name);
        this.currentX = spawn.x() << 8;
        this.currentY = spawn.y() << 8;
        this.initialX = spawn.x();
        this.initialY = spawn.y();
        this.yVelocity = yVelocity;
        this.xVelocity = 0;
        this.arcMotion = arcMotion;
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.xVelDelta = xVelDelta;
        this.xDistance = xDistance;
        this.siblingCount = 0; // Siblings don't spawn more
        this.hasSpawnedSiblings = true; // Prevent future spawning
        // Siblings start with staggered wait timer (3, 6, 9...)
        // They wait this many frames before firing
        this.waitTimer = initialWaitTimer;

        // Siblings start in wait state
        if (arcMotion) {
            this.state = STATE_WAIT_ARC;
        } else {
            this.state = STATE_WAIT_STRAIGHT;
        }

        // Global instance tracking
        activeInstanceCount++;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Spawn siblings on first update
        if (!hasSpawnedSiblings && siblingCount > 0) {
            spawnSiblings();
            hasSpawnedSiblings = true;
        }

        // State machine update
        switch (state) {
            case STATE_WAIT_ARC -> updateWaitArc();
            case STATE_MOVE_ARC -> updateMoveArc();
            case STATE_WAIT_STRAIGHT -> updateWaitStraight();
            case STATE_MOVE_STRAIGHT -> updateMoveStraight();
        }
    }

    /**
     * Wait state for arc motion: uses countdown timer like the original ROM.
     * <p>
     * ROM: Obj1D_Wait at routine 2, line 47893
     * <pre>
     * Obj1D_Wait:
     *     subq.w  #1,objoff_32(a0)          ; decrement timer
     *     bpl.s   BranchTo_JmpTo7_MarkObjGone ; if >= 0, stay waiting
     *     addq.b  #2,routine(a0)            ; advance to motion
     *     move.w  #$3B,objoff_32(a0)        ; 59 frames for NEXT cycle
     * </pre>
     * Parent (timer=0): 0-1=-1 (negative) → fires frame 1
     * Sibling 1 (timer=3): fires frame 4
     * Sibling 2 (timer=6): fires frame 7
     */
    private void updateWaitArc() {
        waitTimer--;
        if (waitTimer >= 0) {
            return; // Still waiting
        }
        // Timer went negative - transition to motion
        // Set timer for NEXT cycle (59 frames wait after this bounce completes)
        waitTimer = WAIT_DURATION;
        playGloopSound();
        state = STATE_MOVE_ARC;
    }

    /**
     * Wait state for straight motion: uses countdown timer like the original ROM.
     * <p>
     * ROM: Obj1D_Wait at routine 6, line 47893 (same logic as arc wait)
     */
    private void updateWaitStraight() {
        waitTimer--;
        if (waitTimer >= 0) {
            return; // Still waiting
        }
        // Timer went negative - transition to motion
        // Set timer for NEXT cycle (59 frames wait after this bounce completes)
        waitTimer = WAIT_DURATION;
        playGloopSound();
        state = STATE_MOVE_STRAIGHT;
    }

    /**
     * Arc motion: curved trajectory with X velocity changing each frame.
     * ROM: Obj1D_MoveArc at line 47911
     */
    private void updateMoveArc() {
        // Apply velocities
        currentX += xVelocity;
        currentY += yVelocity;

        // Change X velocity (creates arc curve)
        xVelocity += xVelDelta;

        // Apply gravity
        yVelocity += GRAVITY;

        // Check if we hit "collision" (ROM: bne.s instruction at line 47919)
        // ROM negates xVelDelta when y_vel becomes 0 due to collision
        // We simulate this by reversing at the bottom of arc
        if (yVelocity == 0) {
            xVelDelta = -xVelDelta;
        }

        // Check if returned to initial Y position
        int pixelY = currentY >> 8;
        if (pixelY >= initialY) {
            // Reset for next bounce
            currentY = initialY << 8;
            yVelocity = INITIAL_Y_VEL;
            xVelocity = 0;
            state = STATE_WAIT_ARC; // Return to arc wait state
        }
    }

    /**
     * Straight motion: linear trajectory.
     * ROM: Obj1D_MoveStraight at line 47929
     *
     * <p>Correct behavior cycle:
     * <ol>
     *   <li>Ball at left (initialX) goes UP</li>
     *   <li>Ball reaches apex (y_vel == 0)</li>
     *   <li>X teleports to right (initialX + xDistance)</li>
     *   <li>59-frame delay (wait state) at apex</li>
     *   <li>Ball falls DOWN on right side</li>
     *   <li>Ball reaches bottom, resets to left - NO delay</li>
     *   <li>Ball immediately goes UP again</li>
     *   <li>Loop back to step 2</li>
     * </ol>
     */
    private void updateMoveStraight() {
        // Apply velocities
        currentX += xVelocity;
        currentY += yVelocity;

        // Apply gravity
        yVelocity += GRAVITY;

        // At apex (y_vel == 0): teleport X and ENTER WAIT STATE
        // The delay happens at the top of the arc, not the bottom
        if (yVelocity == 0) {
            currentX = (initialX + xDistance) << 8;
            playGloopSound();
            state = STATE_WAIT_STRAIGHT;  // Delay at apex
            return;  // Don't continue motion this frame
        }

        // Play sound at terminal velocity (ROM: cmpi.w #$180,y_vel)
        if (yVelocity == 0x180) {
            playGloopSound();
        }

        // Reset at bottom - NO DELAY, immediately continue
        int pixelY = currentY >> 8;
        if (pixelY >= initialY) {
            // Reset for next bounce
            currentY = initialY << 8;
            currentX = initialX << 8;
            yVelocity = INITIAL_Y_VEL;
            playGloopSound();
            // NO state change - stay in STATE_MOVE_STRAIGHT for immediate rise
        }
    }

    /**
     * Spawns sibling balls with staggered initial wait timers.
     * ROM: Obj1D_LoadBall loop at line 47870
     * <p>
     * All siblings share the SAME y_vel as the parent (line 47894: move.w y_vel(a0),y_vel(a1)).
     * Initial wait timers are staggered: 3, 6, 9, 12, 15 frames.
     * This creates balls that fire in quick succession (3 frames apart).
     * <p>
     * ROM timing: Parent (timer=0) fires on frame 1, Sibling 1 (timer=3) fires on frame 4, etc.
     * <p>
     * Uses spawn location registry to prevent duplicate siblings when parent is
     * unloaded and reloaded.
     */
    private void spawnSiblings() {
        // Check if siblings were already spawned for this location (Bug Fix #2)
        long spawnKey = ((long) initialX << 16) | (initialY & 0xFFFF);
        if (spawnedSiblingLocations.contains(spawnKey)) {
            return; // Already spawned siblings for this location
        }
        spawnedSiblingLocations.add(spawnKey);

        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        for (int i = 0; i < siblingCount; i++) {
            // Staggered initial wait timer: 3, 6, 9, 12, etc.
            // (i+1) because parent has timer 0 (fires immediately)
            int initialWaitTimer = (i + 1) * SIBLING_WAIT_STAGGER;

            // Create sibling spawn data
            ObjectSpawn siblingSpawn = new ObjectSpawn(
                    initialX, initialY,
                    spawn.objectId(), spawn.subtype(), spawn.renderFlags(),
                    false, spawn.rawYWord()
            );

            // All siblings use the SAME y_vel as parent (-$480)
            // The disassembly does NOT increment velocity per sibling
            BlueBallsObjectInstance sibling = new BlueBallsObjectInstance(
                    siblingSpawn, name, INITIAL_Y_VEL, xVelDelta, xDistance, arcMotion,
                    initialWaitTimer
            );

            levelManager.getObjectManager().addDynamicObject(sibling);
        }
    }

    /**
     * Plays the gloop sound effect ONLY if this object is on-screen.
     * ROM: PlaySoundLocal (s2.asm line 1555) checks render_flags.on_screen
     * before queueing the sound, preventing off-screen objects from
     * playing audio.
     */
    private void playGloopSound() {
        if (!isOnScreen()) {
            return;
        }
        try {
            AudioManager.getInstance().playSfx(SND_ID_GLOOP);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ========================================================================
    // Position Overrides (for collision detection)
    // ========================================================================

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic position for collision detection
        return new ObjectSpawn(
                currentX >> 8,
                currentY >> 8,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord()
        );
    }

    @Override
    public int getX() {
        return currentX >> 8;
    }

    @Override
    public int getY() {
        return currentY >> 8;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
        if (rm == null) {
            return;
        }

        PatternSpriteRenderer renderer = rm.getBlueBallsRenderer();
        if (renderer != null && renderer.isReady()) {
            int drawX = currentX >> 8;
            int drawY = currentY >> 8;
            boolean hFlip = xFlipped;
            boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
            renderer.drawFrameIndex(0, drawX, drawY, hFlip, vFlip);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    // ========================================================================
    // Lifecycle Management
    // ========================================================================

    /**
     * Called when this object is unloaded from the active object list.
     * Decrements the global instance count.
     */
    @Override
    public void onUnload() {
        activeInstanceCount--;
        if (activeInstanceCount < 0) {
            activeInstanceCount = 0;
        }
    }

    // ========================================================================
    // Static Reset (for level transitions)
    // ========================================================================

    /**
     * Resets all global state for BlueBalls objects.
     * Call this when loading a new level or restarting the current level
     * to ensure clean sibling tracking.
     */
    public static void resetGlobalState() {
        activeInstanceCount = 0;
        spawnedSiblingLocations.clear();
    }
}
