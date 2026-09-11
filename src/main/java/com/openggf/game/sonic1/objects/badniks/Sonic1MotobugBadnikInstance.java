package com.openggf.game.sonic1.objects.badniks;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.PatrolMovementHelper;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Motobug (0x40) - Ladybug Badnik from Green Hill Zone.
 * Walks along terrain, pauses, reverses direction. Spawns exhaust smoke
 * child objects every 15 frames during movement.
 * <p>
 * Based on docs/s1disasm/_incObj/40 Moto Bug.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Moto_Main): Initialization - ObjectFall + ObjFloorDist until floor found</li>
 *   <li>2 (Moto_Action): Main behavior with secondary routines:
 *     <ul>
 *       <li>ob2ndRout=0 (.move): Pause timer, then start walking</li>
 *       <li>ob2ndRout=2 (.findfloor): Walk with terrain following + smoke spawning</li>
 *     </ul>
 *   </li>
 *   <li>4 (Moto_Animate): Smoke trail objects - animation only</li>
 *   <li>6 (Moto_Delete): Cleanup</li>
 * </ul>
 */
public class Sonic1MotobugBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = $C (enemy, collision size index $C)
    private static final int COLLISION_SIZE_INDEX = 0x0C;

    // From disassembly: obHeight = $E, obWidth = 8
    private static final int Y_RADIUS = 0x0E;

    // Walking velocity: move.w #-$100,obVelX(a0)
    private static final int WALK_VELOCITY = 0x100;

    // Pause duration: move.w #59,.time(a0)
    private static final int PAUSE_DURATION = 59;

    // Smoke spawn interval: move.b #$F,.smokedelay(a0)
    private static final int SMOKE_DELAY = 0x0F;

    // Floor detection thresholds from .findfloor:
    // cmpi.w #-8,d1 / blt.s .pause / cmpi.w #$C,d1 / bge.s .pause
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Secondary routine states
    private static final int STATE_MOVE = 0;       // ob2ndRout=0: paused
    private static final int STATE_FINDFLOOR = 1;   // ob2ndRout=2: walking

    private int secondaryState;
    private int pauseTimer;        // .time (objoff_30)
    private int smokeDelay;        // .smokedelay (objoff_33)
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int fallVelocity;      // obVelY during initialization
    private boolean initialized;

    public Sonic1MotobugBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Motobug");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // S1: obStatus bit 0 set = facing right (xFlip)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.secondaryState = STATE_MOVE;
        this.pauseTimer = 0;
        this.smokeDelay = 0;
        this.fallVelocity = 0;
        this.initialized = false;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialize();
            return;
        }

        switch (secondaryState) {
            case STATE_MOVE -> updateMove();
            case STATE_FINDFLOOR -> updateFindFloor();
        }
    }

    /**
     * Routine 0: Moto_Main - ObjectFall + ObjFloorDist until floor is found.
     * Falls under gravity until feet intersect terrain (distance < 0).
     */
    private void initialize() {
        // ObjectFall: apply velocity to position, then add gravity for next frame
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = fallVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        fallVelocity = motion.yVel;

        // ObjFloorDist: check floor from feet
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // ROM: tst.w d1 / bpl.s .notonfloor
        if (floorResult.foundSurface() && floorResult.distance() < 0) {
            currentY += floorResult.distance();  // add.w d1,obY(a0)
            fallVelocity = 0;                     // move.w #0,obVelY(a0)
            initialized = true;                   // addq.b #2,obRoutine(a0)
            // bchg #0,obStatus(a0) - toggle facing direction on init
            facingLeft = !facingLeft;
        }
    }

    /**
     * ob2ndRout=0 (.move): Decrement pause timer. When expired, start walking.
     * ROM: subq.w #1,.time(a0) / bpl.s .wait
     * Then sets velocity, toggles direction, advances to .findfloor.
     */
    private void updateMove() {
        pauseTimer--;
        if (pauseTimer >= 0) {
            return; // bpl.s .wait
        }

        // Timer expired: start walking
        // addq.b #2,ob2ndRout(a0)
        secondaryState = STATE_FINDFLOOR;

        // move.w #-$100,obVelX(a0) - default: move left
        xVelocity = -WALK_VELOCITY;

        // move.b #1,obAnim(a0)
        animFrame = 1;

        // bchg #0,obStatus(a0) / bne.s .wait / neg.w obVelX(a0)
        // bchg tests OLD bit, toggles it. bne branches if OLD bit was SET (now clear).
        // OLD bit SET → now CLEAR → bne taken → keep negative velocity (walk left)
        // OLD bit CLEAR → now SET → bne not taken → negate velocity (walk right)
        facingLeft = !facingLeft;
        if (!facingLeft) {
            xVelocity = -xVelocity; // neg.w obVelX(a0)
        }
    }

    /**
     * ob2ndRout=2 (.findfloor): Walk with terrain following.
     * SpeedToPos → ObjFloorDist → check range → spawn smoke.
     * If floor distance outside [-8, $C), pause.
     */
    private void updateFindFloor() {
        // SpeedToPos + ObjFloorDist via PatrolMovementHelper
        var patrol = PatrolMovementHelper.updatePatrol(
                currentX, motion.xSub, currentY, xVelocity, Y_RADIUS, FLOOR_MIN_DIST, FLOOR_MAX_DIST);
        currentX = patrol.newX();
        motion.xSub = patrol.newXSub();
        currentY = patrol.newY();

        if (patrol.reversed()) {
            returnToPause();
            return;
        }

        // Smoke trail spawning
        // subq.b #1,.smokedelay(a0) / bpl.s .nosmoke
        smokeDelay--;
        if (smokeDelay < 0) {
            // move.b #$F,.smokedelay(a0)
            smokeDelay = SMOKE_DELAY;
            spawnSmoke();
        }
    }

    /**
     * Spawns an exhaust smoke child object at the Motobug's current position.
     * ROM: _move.b #id_MotoBug,obID(a1) with obAnim(a1) = 2
     * The smoke reuses the Motobug object type but with non-zero obAnim,
     * causing it to skip to Moto_Animate (animation-only routine).
     */
    private void spawnSmoke() {
        spawnFreeChild(() -> new Sonic1MotobugSmokeInstance(
                currentX, currentY, facingLeft));
    }

    /**
     * Return to pause state.
     * ROM: subq.b #2,ob2ndRout(a0) / move.w #59,.time(a0) / move.w #0,obVelX(a0) / move.b #0,obAnim(a0)
     */
    private void returnToPause() {
        secondaryState = STATE_MOVE;
        pauseTimer = PAUSE_DURATION;
        xVelocity = 0;
        animFrame = 0;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation is driven by animFrame set in state machine.
        // Walking animation cycles through frames 0→1→0→2 at speed 7.
        // Standing uses frame 2 at speed $F.
        // The actual mapping frame selection is done in appendRenderCommands.
        if (secondaryState == STATE_FINDFLOOR) {
            animTimer++;
            // Walk animation speed: dc.b 7, 0, 1, 0, 2, afEnd
            // Duration = speed + 1 = 8 frames per step, 4 steps in sequence
            if (animTimer >= 32) { // Full cycle: 4 frames × 8 ticks
                animTimer = 0;
            }
        }
    }

    /**
     * Returns the mapping frame index for the current animation state.
     * Walk animation (anim 1): frames 0, 1, 0, 2 at speed 7 (8 ticks each)
     * Stand animation (anim 0): frame 2 at speed $F
     */
    private int getMappingFrame() {
        if (secondaryState == STATE_FINDFLOOR) {
            // Walk sequence: 0, 1, 0, 2 - each frame displayed for 8 ticks
            int step = (animTimer / 8) % 4;
            return switch (step) {
                case 0 -> 0;  // frame 0
                case 1 -> 1;  // frame 1
                case 2 -> 0;  // frame 0
                case 3 -> 2;  // frame 2
                default -> 0;
            };
        }
        // Stand: frame 2
        return 2;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        if (isDestroyed()) {
            return false;
        }
        // Moto_Main (routine 0) ends in a bare `rts`: while the Motobug is still
        // falling to find its floor it never reaches RememberState, so the ROM
        // cannot delete it for being off-screen and its SST slot stays occupied
        // (docs/s1disasm/_incObj/40 Badnik - Moto Bug.asm:29-52).
        if (!initialized) {
            return true;
        }
        // Moto_Action (routine 2) falls through into RememberState, whose
        // out_of_range macro deletes the object once its chunk-aligned X leaves
        // the [camera-128, camera-128 + 0x280] window
        // (docs/s1disasm/_incObj/40 Badnik - Moto Bug.asm:72 ->
        // _incObj/sub RememberState.asm:9 -> Macros.asm:278-295). The prior
        // symmetric isOnScreenX(160) gate both freed the slot ~160px too early
        // on the right and held it ~32px too long on the left.
        return isInRangeAt(getX());
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // obPriority = 4
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MOTOBUG);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // S1 convention: default sprite art faces left, hFlip = true when facing right
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }
}
