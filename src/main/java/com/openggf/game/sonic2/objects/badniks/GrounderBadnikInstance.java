package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.GrounderRockProjectile;
import com.openggf.game.sonic2.objects.GrounderWallInstance;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.PatrolMovementHelper;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

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
public class GrounderBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    // Touch collision size index from subObjData. Obj8D_SubObjData (s2.asm:73505)
    //   subObjData Obj8D_MapUnc_36CF0, make_art_tile(...), 1<<level_fg, 5, $10, 2
    // The subObjData macro fields are (mappings, vram, renderflags, priority,
    // width, collision) (s2.macros.asm:231), so collision_flags = 2 (the trailing
    // value); the 5 is the priority and $10 is width_pixels. The previous code
    // used 5 (the priority) as the touch size index, which selected Touch_Sizes
    // entry 5 = {$C,$12} (height 18) instead of the correct entry 2 = {$C,$14}
    // (height 20, s2.asm:85055-85056). The 2px-shorter box left a 1px vertical
    // gap that prevented rolling Tails from landing on / killing the Grounder
    // (S2 ARZ1 trace f2043: the Touch_KillEnemy neg.w y_vel bounce never fired).
    private static final int COLLISION_SIZE_INDEX = 2;

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
    private final AnimationTimer walkAnim = new AnimationTimer(WALK_ANIM_DURATION, 3);

    /**
     * Creates a Grounder badnik.
     *
     * @param spawn         Spawn data from level
     * @param levelManager  Level manager for spawning children
     * @param skipWallSetup True for 0x8E variant (skips wall/rock spawning)
     */
    public GrounderBadnikInstance(ObjectSpawn spawn, boolean skipWallSetup) {
        super(spawn, "Grounder", Sonic2BadnikConfig.DESTRUCTION);
        this.skipWallSetup = skipWallSetup;
        this.state = State.INIT;
        this.activated = false;
        this.pauseTimer = 0;
        this.idleAnimTimer = 0;

        // Initial facing from render_flags
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        return new GrounderBadnikInstance(
                spawn, spawn.objectId() == Sonic2ObjectIds.GROUNDER_IN_WALL2);
    }

    /**
     * Returns whether this Grounder has been activated (for child objects).
     */
    public boolean isActivated() {
        return activated;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
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

        if (floorResult.hasCollision() && floorResult.distance() < 0) {
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
     * ROM: loc_36C64 calls AllocateObject for each Obj8F wall, so each child
     * takes the current lowest free SST slot (docs/s2disasm/s2.asm:73520-73533).
     */
    private void spawnWalls() {
        // Spawn 4 wall pieces at offsets from byte_36CBC
        for (int i = 0; i < 4; i++) {
            int[] offset = GrounderWallInstance.WALL_OFFSETS[i];
            int wallX = currentX + offset[0];
            int wallY = currentY + offset[1];
            int wallIndex = i;
            spawnFreeChild(() -> new GrounderWallInstance(wallX, wallY, wallIndex, this));
        }
    }

    /**
     * Spawns rock projectiles at Grounder's position (called during DETECTION).
     * ROM: loc_36C2C calls AllocateObject for each Obj90 rock, not
     * AllocateObjectAfterCurrent (docs/s2disasm/s2.asm:73497-73516).
     */
    private void spawnRocks() {
        for (int i = 0; i < 5; i++) {
            int rockIndex = i;
            spawnFreeChild(() -> new GrounderRockProjectile(currentX, currentY, rockIndex, this));
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
        // ROM loc_36ADC orients to the CLOSEST of MainCharacter/Sidekick
        // (Obj_GetOrientationToPlayer, s2.asm:72755-72774) and tests the absolute
        // horizontal distance against #$60 (abs.w d2 / cmpi.w #$60,d2 / bls). Use
        // the same closest-player selection rather than only the main character so
        // a leading sidekick can trigger and orient the Grounder.
        int dx = closestPlayerDx();
        if (dx <= DETECTION_RANGE) {
            // Player detected - set flag, spawn rocks, start idle animation
            activated = true;
            spawnRocks();
            // ROM frame budget from detection (routine 2) to the routine-6
            // direction latch:
            //   * Routine 4 (Obj8D_Animate) plays Ani_obj8D_b = dc.b 7,0,1,$FC
            //     (s2.asm:73521-73523). AnimateSprite holds each frame for
            //     (duration+1)=8 frames (s2.asm:30425-30448), so frames 0,1 occupy
            //     8+8 = 16 frames.
            //   * On the 17th frame the $FC end-command runs Anim_End_FC, which
            //     only does addq #2,routine (4->6) without setting any direction
            //     (s2.asm:30476-30482) -- a "dead" frame.
            //   * The following frame routine 6 (loc_36B0E) runs and latches the
            //     orientation/direction.
            // So the direction is latched 16 (hold) + 1 (the $FC advance frame)
            // frames after the animate begins. The IDLE_ANIMATE -> MOVEMENT_SETUP
            // state transition already costs one frame (MOVEMENT_SETUP runs the
            // frame after the timer expires), so seed the timer with 17 to land
            // the latch on the ROM-correct frame (S2 ARZ1 trace: routine 6 logic
            // at f2028 with the player just past the Grounder; the earlier 14/16
            // values latched a frame early while the player was still to the left,
            // sending the Grounder the wrong way).
            idleAnimTimer = (IDLE_ANIM_DURATION + 1) * 2 + 1;
            state = State.IDLE_ANIMATE;
        }
    }

    /**
     * Returns the absolute horizontal distance to the closest player, matching
     * the ROM {@code Obj_GetOrientationToPlayer} selection (closest of
     * MainCharacter/Sidekick by absolute 16-bit signed X distance,
     * s2.asm:72755-72770).
     */
    private int closestPlayerDx() {
        var nearest = services().playerQuery().nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, currentX);
        if (nearest == null || nearest.player() == null) {
            return Integer.MAX_VALUE;
        }
        return nearest.distance();
    }

    /**
     * Returns the closest player (MainCharacter/Sidekick by absolute X distance)
     * or {@code null}, matching {@code Obj_GetOrientationToPlayer}.
     */
    private AbstractPlayableSprite closestPlayer() {
        var nearest = services().playerQuery().nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, currentX);
        return (nearest != null && nearest.player() instanceof AbstractPlayableSprite p) ? p : null;
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

        // ROM loc_36B0E orients to the CLOSEST player via Obj_GetOrientationToPlayer
        // and indexes Obj8D_Directions {-$100, +$100} by d0 (0=player left -> move
        // left, 2=player right -> move right), also setting the x_flip status bit
        // (s2.asm:73296-73310). The engine update loop only passes the main
        // character, so re-derive the orientation against the closest of
        // MainCharacter/Sidekick to match the ROM when a sidekick is leading.
        AbstractPlayableSprite target = closestPlayer();
        if (target == null) {
            target = player;
        }
        if (target != null) {
            facingLeft = target.getCentreX() < currentX;
        }

        // Set velocity
        xVelocity = facingLeft ? -MOVEMENT_VELOCITY : MOVEMENT_VELOCITY;

        // Reset walking animation
        walkAnim.reset();

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
        // Apply velocity and check floor (ObjectMove + ObjCheckFloorDist)
        // Velocity is +/-0x100 (exactly +/-1 pixel per frame), no subpixel accumulation needed
        var result = PatrolMovementHelper.updatePatrol(
                currentX, 0, currentY, xVelocity, Y_RADIUS, -1, 12);
        currentX = result.newX();
        currentY = result.newY();

        if (result.reversed()) {
            // At edge - pause and reverse
            pauseTimer = PAUSE_TIME;
            state = State.ROCK_THROW;
        }
    }

    /**
     * ROCK_THROW state (Routine A):
     * - Wait for pause timer (seeded with 0x3B on entry, loc_36B5C s2.asm:73326-73328)
     * - Then reverse direction and return to MOVEMENT
     *
     * From disassembly loc_36B6A (s2.asm:73332-73335):
     *   subq.b #1,objoff_2A(a0)     ; Decrement timer
     *   bmi.s loc_36B74             ; Reverse only once the timer goes NEGATIVE
     * loc_36B74 (s2.asm:73338-73342):
     *   move.b #8,routine(a0)       ; Back to routine 8 (MOVEMENT)
     *   neg.w x_vel(a0)             ; Reverse velocity
     *   bchg #status.npc.x_flip,status(a0)
     *
     * The ROM seeds objoff_2A = 0x3B (59) and reverses on the frame the
     * decrement produces -1 (bmi). That is 60 decrements: 0x3B -> ... -> 0
     * (all stay paused) -> -1 (reverse). The previous {@code <= 0} test
     * reversed on the 59th decrement (one frame early), resuming the walk a
     * frame too soon and leaving the badnik 1px ahead of ROM Obj8D for the
     * rest of the traversal -- which delayed the Touch_KillEnemy overlap
     * (s2.asm:85296-85329) by one frame (S2 ARZ1 trace f1208). Use
     * {@code < 0} so the engine pauses the full 60 frames like the ROM bmi.
     */
    private void updateRockThrow() {
        pauseTimer--;
        if (pauseTimer < 0) {
            // Reverse direction
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -MOVEMENT_VELOCITY : MOVEMENT_VELOCITY;

            // Reset walking animation
            walkAnim.reset();

            state = State.MOVEMENT;
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        switch (state) {
            case INIT, DETECTION -> {
                // Static frame (hidden or not yet detected)
                animFrame = FRAME_IDLE_1;
            }
            case IDLE_ANIMATE -> {
                // Idle animation: frames 0,1, duration 7
                animFrame = ((vIntRunCount / IDLE_ANIM_DURATION) % 2 == 0) ? FRAME_IDLE_1 : FRAME_IDLE_2;
            }
            case MOVEMENT_SETUP, MOVEMENT, ROCK_THROW -> {
                // Walking animation: frames 2,3,4, duration 3
                walkAnim.tick();
                animFrame = FRAME_WALK_1 + walkAnim.getFrame();
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
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.GROUNDER);
        if (renderer == null) return;

        // Render current animation frame
        // Art faces left by default; flip when facing right
        boolean hFlip = !facingLeft;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
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

        ctx.drawLine(left, top, right, top, r, g, b);
        ctx.drawLine(right, top, right, bottom, r, g, b);
        ctx.drawLine(right, bottom, left, bottom, r, g, b);
        ctx.drawLine(left, bottom, left, top, r, g, b);
    }
}
