package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.game.sonic2.objects.GrounderRockProjectile;
import uk.co.jamesj999.sonic.game.sonic2.objects.GrounderWallInstance;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.ObjectTerrainUtils;
import uk.co.jamesj999.sonic.physics.TerrainCheckResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Grounder (Obj8D/8E) - Drill badnik from Aquatic Ruin Zone.
 * <p>
 * Behavior from disassembly (s2.asm):
 * <ul>
 *   <li>0x8D variant: Hides behind wall, spawns 4 walls + 5 rocks when player approaches</li>
 *   <li>0x8E variant: Skips wall setup, walks immediately</li>
 * </ul>
 * <p>
 * State machine (matching disassembly routines):
 * <pre>
 * Routine 0: INIT - Initialize, snap to floor, spawn children (0x8D only)
 * Routine 2: DETECTION - Wait for player within 96 pixels
 * Routine 4: IDLE_ANIMATE - Play idle animation before walking
 * Routine 6: MOVEMENT_SETUP - Set direction toward player
 * Routine 8: MOVEMENT - Walk with floor checking
 * Routine A: ROCK_THROW - Pause at edge/wall, then reverse
 * </pre>
 * <p>
 * Animation:
 * <ul>
 *   <li>Idle: Frames 0,1, duration 7</li>
 *   <li>Walking: Frames 2,3,4, duration 3</li>
 * </ul>
 */
public class GrounderBadnikInstance extends AbstractBadnikInstance {

    // Instance counter for debug identification
    private static int instanceCounter = 0;
    private final int instanceId;
    private final int spawnY; // For debug identification

    // Collision size index from subObjData (collision_flags = 5)
    private static final int COLLISION_SIZE_INDEX = 5;

    // Detection range from disassembly (0x60 = 96 pixels)
    private static final int DETECTION_RANGE = 0x60;

    // Movement velocity from disassembly (move.w #$100,x_vel)
    private static final int MOVEMENT_VELOCITY = 0x100;

    // Idle animation wait time from disassembly (moveq #7,d1 for duration)
    private static final int IDLE_ANIM_DURATION = 7;

    // Edge/wall pause time from disassembly (move.b #$3B,objoff_2A)
    private static final int PAUSE_TIME = 0x3B;

    // Y radius from disassembly (move.b #$14,y_radius) - used for floor detection
    private static final int Y_RADIUS = 0x14;

    // Animation frame indices
    private static final int FRAME_IDLE_1 = 0;
    private static final int FRAME_IDLE_2 = 1;
    private static final int FRAME_WALK_1 = 2;
    private static final int FRAME_WALK_2 = 3;
    private static final int FRAME_WALK_3 = 4;

    // Animation duration for walking (duration 3)
    private static final int WALK_ANIM_DURATION = 3;

    /**
     * State machine states matching disassembly routines.
     */
    private enum State {
        INIT,           // Routine 0: Initialize
        DETECTION,      // Routine 2: Wait for player
        IDLE_ANIMATE,   // Routine 4: Idle animation
        MOVEMENT_SETUP, // Routine 6: Set direction
        MOVEMENT,       // Routine 8: Walking
        ROCK_THROW      // Routine A: Pause at edge/wall
    }

    private State state;
    private boolean skipWallSetup; // True for 0x8E variant
    private boolean activated;     // Activation flag for child objects (objoff_2B)
    private int pauseTimer;        // Timer for edge/wall pause (objoff_2A)
    private int idleAnimTimer;     // Timer for idle animation
    private int walkAnimTimer;     // Timer for walking animation
    private int walkAnimFrame;     // Current walking frame (0-2 -> frames 2-4)

    /**
     * Creates a Grounder badnik.
     *
     * @param spawn         Spawn data from level
     * @param levelManager  Level manager for spawning children
     * @param skipWallSetup True for 0x8E variant (skips wall/rock spawning)
     */
    public GrounderBadnikInstance(ObjectSpawn spawn, LevelManager levelManager, boolean skipWallSetup) {
        super(spawn, levelManager, "Grounder");
        this.instanceId = ++instanceCounter;
        this.spawnY = spawn.y();
        this.skipWallSetup = skipWallSetup;
        this.state = State.INIT;
        this.activated = false;
        this.pauseTimer = 0;
        this.idleAnimTimer = 0;
        this.walkAnimTimer = 0;
        this.walkAnimFrame = 0;

        // Initial facing from render_flags
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
    }

    /**
     * Returns whether this Grounder has been activated (for child objects).
     */
    public boolean isActivated() {
        return activated;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case INIT -> updateInit();
            case DETECTION -> updateDetection(player);
            case IDLE_ANIMATE -> updateIdleAnimate();
            case MOVEMENT_SETUP -> updateMovementSetup(player);
            case MOVEMENT -> updateWalking(player);
            case ROCK_THROW -> updateRockThrow();
        }
    }

    /**
     * INIT state (Routine 0):
     * - Snap to floor
     * - If 0x8D, spawn walls and rocks
     * - Transition to DETECTION (or MOVEMENT_SETUP for 0x8E)
     */
    private void updateInit() {
        // Snap to floor - uses y_radius to check from feet
        // ROM: jsr (ObjCheckFloorDist).l / tst.w d1 / bpl.s + / add.w d1,y_pos
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // DEBUG: Log init floor check (only for above-ground Grounder)
        if (spawnY < 900) {
            System.out.println("[G#" + instanceId + " spawnY=" + spawnY + "] INIT: pos=(" + currentX + "," + currentY +
                    ") feetY=" + (currentY + Y_RADIUS) + " hasColl=" + floorResult.hasCollision() +
                    " dist=" + floorResult.distance() + " skipWall=" + skipWallSetup);
        }

        if (floorResult.hasCollision() && floorResult.distance() < 0) {
            if (spawnY < 900) {
                System.out.println("[G#" + instanceId + "] -> Snapping down by " + floorResult.distance());
            }
            currentY += floorResult.distance();
        }

        if (!skipWallSetup) {
            // 0x8D variant: Spawn 4 walls (rocks spawned later during DETECTION)
            spawnWalls();
            state = State.DETECTION;
        } else {
            // 0x8E variant: Skip directly to movement setup
            state = State.MOVEMENT_SETUP;
        }
    }

    /**
     * Spawns wall pieces at offsets relative to Grounder (called during INIT).
     * ROM: loc_36C64 spawns 4 walls via loc_36C78
     */
    private void spawnWalls() {
        // Spawn 4 wall pieces at offsets from byte_36CBC
        for (int i = 0; i < 4; i++) {
            int[] offset = GrounderWallInstance.WALL_OFFSETS[i];
            int wallX = currentX + offset[0];
            int wallY = currentY + offset[1];
            GrounderWallInstance wall = new GrounderWallInstance(wallX, wallY, i, this);
            levelManager.getObjectManager().addDynamicObject(wall);
        }
    }

    /**
     * Spawns rock projectiles at Grounder's position (called during DETECTION).
     * ROM: loc_36C2C spawns 5 rocks via loc_36C40
     */
    private void spawnRocks() {
        for (int i = 0; i < 5; i++) {
            GrounderRockProjectile rock = new GrounderRockProjectile(currentX, currentY, i, this);
            levelManager.getObjectManager().addDynamicObject(rock);
        }
    }

    /**
     * DETECTION state (Routine 2):
     * - Wait for player within 96 pixels (uses bls = branch if lower or same)
     * - When detected: set activation flag, spawn rocks, transition to IDLE_ANIMATE
     *
     * ROM loc_36ADC:
     *   bsr.w   Obj_GetOrientationToPlayer
     *   abs.w   d2
     *   cmpi.w  #$60,d2
     *   bls.s   +                   ; branch if distance <= 96
     *   jmpto   JmpTo39_MarkObjGone
     * +
     *   addq.b  #2,routine(a0)
     *   st.b    objoff_2B(a0)       ; set activation flag
     *   bsr.w   loc_36C2C           ; spawn 5 rocks
     */
    private void updateDetection(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Check horizontal distance to player (ROM uses bls = <=)
        int dx = Math.abs(player.getCentreX() - currentX);
        if (dx <= DETECTION_RANGE) {
            // Player detected - set flag, spawn rocks, start idle animation
            activated = true;
            spawnRocks();
            // Idle animation: 2 frames (0, 1) at duration 7 each = 14 frames total
            idleAnimTimer = IDLE_ANIM_DURATION * 2;
            state = State.IDLE_ANIMATE;
        }
    }

    /**
     * IDLE_ANIMATE state (Routine 4):
     * - Play idle animation (frames 0,1)
     * - After timer expires, transition to MOVEMENT_SETUP
     */
    private void updateIdleAnimate() {
        idleAnimTimer--;
        if (idleAnimTimer <= 0) {
            state = State.MOVEMENT_SETUP;
        }
    }

    /**
     * MOVEMENT_SETUP state (Routine 6):
     * - Set movement direction toward player
     * - Set velocity to +/-0x100
     * - Transition to MOVEMENT
     */
    private void updateMovementSetup(AbstractPlayableSprite player) {
        // For 0x8E variant, need to activate here
        if (!activated) {
            activated = true;
        }

        // Set direction toward player (or keep current facing if no player)
        if (player != null) {
            facingLeft = player.getCentreX() < currentX;
        }

        // Set velocity
        xVelocity = facingLeft ? -MOVEMENT_VELOCITY : MOVEMENT_VELOCITY;

        // Reset walking animation
        walkAnimTimer = 0;
        walkAnimFrame = 0;

        state = State.MOVEMENT;
    }

    /**
     * MOVEMENT state (Routine 8):
     * - Walk with floor checking (uses ObjectMove, NOT ObjectMoveAndFall - no gravity)
     * - Check floor at current position
     * - If floor distance < -1 or >= 12, at edge - transition to ROCK_THROW
     * - Otherwise snap to floor and continue walking
     *
     * From disassembly loc_36B34:
     *   jsrto JmpTo26_ObjectMove          ; Apply x_vel (no gravity)
     *   jsr (ObjCheckFloorDist).l         ; Check floor at current position
     *   cmpi.w #-1,d1                     ; If distance < -1
     *   blt.s loc_36B5C                   ; Go to pause state
     *   cmpi.w #$C,d1                     ; If distance >= 12
     *   bge.s loc_36B5C                   ; Go to pause state
     *   add.w d1,y_pos(a0)                ; Snap to floor
     */
    private void updateWalking(AbstractPlayableSprite player) {
        // Apply velocity (ObjectMove behavior - NO gravity)
        // Velocity is +/-0x100 which is exactly +/-1 pixel per frame in 8.8 fixed point
        currentX += (xVelocity >> 8);

        // Check floor from feet (y + y_radius)
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // Edge detection from disassembly:
        // - If distance < -1: floor is above (inside terrain) or no floor
        // - If distance >= 12 (0xC): floor is too far below (at a ledge)
        int floorDistance = floorResult.hasCollision() ? floorResult.distance() : 100;

        // DEBUG: Only log the above-ground Grounder (spawnY < 900 to filter out underwater one)
        if (spawnY < 900) {
            System.out.println("[G#" + instanceId + " spawnY=" + spawnY + "] walk: pos=(" + currentX + "," + currentY +
                    ") feetY=" + (currentY + Y_RADIUS) + " hasColl=" + floorResult.hasCollision() +
                    " dist=" + floorDistance);
        }

        if (floorDistance < -1 || floorDistance >= 12) {
            // At edge - pause and reverse
            if (spawnY < 900) {
                System.out.println("[G#" + instanceId + "] -> EDGE DETECTED (dist=" + floorDistance + "), pausing");
            }
            pauseTimer = PAUSE_TIME;
            state = State.ROCK_THROW;
        } else {
            // Valid floor - snap to it
            currentY += floorDistance;
        }
    }

    /**
     * ROCK_THROW state (Routine A):
     * - Wait for pause timer (0x3B frames)
     * - Then reverse direction and return to MOVEMENT
     *
     * From disassembly loc_36B6A:
     *   subq.b #1,objoff_2A(a0)     ; Decrement timer
     *   bne.s ...                   ; Continue if not zero
     *   subq.b #2,routine(a0)       ; Go back to routine 8 (MOVEMENT)
     *   bchg #status.npc.x_flip,status(a0)  ; Reverse direction
     *   bra.w loc_36B0E             ; Set new velocity
     */
    private void updateRockThrow() {
        pauseTimer--;
        if (pauseTimer <= 0) {
            // Reverse direction
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -MOVEMENT_VELOCITY : MOVEMENT_VELOCITY;

            // Reset walking animation
            walkAnimTimer = 0;
            walkAnimFrame = 0;

            state = State.MOVEMENT;
        }
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        switch (state) {
            case INIT, DETECTION -> {
                // Static frame (hidden or not yet detected)
                animFrame = FRAME_IDLE_1;
            }
            case IDLE_ANIMATE -> {
                // Idle animation: frames 0,1, duration 7
                animFrame = ((frameCounter / IDLE_ANIM_DURATION) % 2 == 0) ? FRAME_IDLE_1 : FRAME_IDLE_2;
            }
            case MOVEMENT_SETUP, MOVEMENT, ROCK_THROW -> {
                // Walking animation: frames 2,3,4, duration 3
                walkAnimTimer++;
                if (walkAnimTimer >= WALK_ANIM_DURATION) {
                    walkAnimTimer = 0;
                    walkAnimFrame = (walkAnimFrame + 1) % 3;
                }
                animFrame = FRAME_WALK_1 + walkAnimFrame;
            }
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GROUNDER);
        if (renderer == null || !renderer.isReady()) {
            appendDebug(commands);
            return;
        }

        // Render current animation frame
        // Art faces left by default; flip when facing right
        boolean hFlip = !facingLeft;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, false);
    }

    private void appendDebug(List<GLCommand> commands) {
        int halfWidth = 16;
        int halfHeight = 16;
        int left = currentX - halfWidth;
        int right = currentX + halfWidth;
        int top = currentY - halfHeight;
        int bottom = currentY + halfHeight;

        // Green for active, yellow for inactive
        float r = activated ? 0.2f : 0.8f;
        float g = activated ? 0.8f : 0.8f;
        float b = 0.2f;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2, float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }
}
