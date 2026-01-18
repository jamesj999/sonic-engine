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

import java.util.List;

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
        // Parent ball starts with waitTimer = 0, which is immediately checked
        if (arcMotion) {
            this.state = STATE_WAIT_ARC;
        } else {
            this.state = STATE_WAIT_STRAIGHT;
        }
        this.waitTimer = 0; // Parent starts with 0, will transition immediately
        this.hasSpawnedSiblings = false;
    }

    /**
     * Constructor for spawned sibling balls.
     * Siblings don't spawn more siblings and have staggered wait timers.
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
        this.waitTimer = initialWaitTimer; // Staggered wait timer

        // Siblings start in wait state with their staggered timer
        if (arcMotion) {
            this.state = STATE_WAIT_ARC;
        } else {
            this.state = STATE_WAIT_STRAIGHT;
        }
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
     * Wait state for arc motion: countdown timer, then transition to MoveArc.
     * ROM: Obj1D_Wait at routine 2, line 47893
     */
    private void updateWaitArc() {
        if (waitTimer > 0) {
            waitTimer--;
            return;
        }

        // Transition to arc movement
        waitTimer = WAIT_DURATION;
        playGloopSound();
        state = STATE_MOVE_ARC;
    }

    /**
     * Wait state for straight motion: countdown timer, then transition to MoveStraight.
     * ROM: Obj1D_Wait at routine 6, line 47893
     */
    private void updateWaitStraight() {
        if (waitTimer > 0) {
            waitTimer--;
            return;
        }

        // Transition to straight movement
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
     */
    private void updateMoveStraight() {
        // Apply velocities
        currentX += xVelocity;
        currentY += yVelocity;

        // Apply gravity
        yVelocity += GRAVITY;

        // Check for y_vel collision (ROM: bne.s at line 47935)
        // When collision occurs, X is set to initial + distance
        if (yVelocity == 0) {
            currentX = (initialX + xDistance) << 8;
        }

        // Play sound at terminal velocity (ROM: cmpi.w #$180,y_vel)
        if (yVelocity == 0x180) {
            playGloopSound();
        }

        // Check if returned to initial Y position
        int pixelY = currentY >> 8;
        if (pixelY >= initialY) {
            // Reset for next bounce
            currentY = initialY << 8;
            currentX = initialX << 8;
            yVelocity = INITIAL_Y_VEL;
            playGloopSound();
            state = STATE_WAIT_STRAIGHT; // Return to straight wait state
        }
    }

    /**
     * Spawns sibling balls at staggered Y velocities and wait timers.
     * ROM: Obj1D_LoadBall loop at line 47870
     * <p>
     * ROM sets wait timer (objoff_32) with: move.w d2,objoff_32(a1) then addq.w #3,d2
     * So siblings get timers: 3, 6, 9, 12, 15, etc. (parent gets 0)
     */
    private void spawnSiblings() {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        int currentYVel = INITIAL_Y_VEL;
        int velIncrement = 0x80; // Velocity increment per sibling

        for (int i = 0; i < siblingCount; i++) {
            currentYVel += velIncrement;

            // Staggered wait timer: 3, 6, 9, 12, etc.
            // (i+1) because parent already took timer 0
            int siblingWaitTimer = (i + 1) * SIBLING_WAIT_STAGGER;

            // Create sibling spawn data
            ObjectSpawn siblingSpawn = new ObjectSpawn(
                    initialX, initialY,
                    spawn.objectId(), spawn.subtype(), spawn.renderFlags(),
                    false, spawn.rawYWord()
            );

            BlueBallsObjectInstance sibling = new BlueBallsObjectInstance(
                    siblingSpawn, name, currentYVel, xVelDelta, xDistance, arcMotion,
                    siblingWaitTimer
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
}
