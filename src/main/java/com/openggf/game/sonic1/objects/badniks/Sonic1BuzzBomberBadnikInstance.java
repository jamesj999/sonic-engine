package com.openggf.game.sonic1.objects.badniks;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Buzz Bomber (0x22) - Flying bee Badnik from GHZ/MZ/SYZ.
 * Flies horizontally, detects Sonic's proximity, hovers and fires a missile downward,
 * then resumes flying.
 * <p>
 * Based on docs/s1disasm/_incObj/22 Buzz Bomber.asm.
 * <p>
 * State machine:
 * <ul>
 *   <li>ob2ndRout=0 (.move): Hover, waiting for timer. If near Sonic: fire. Otherwise: start flying.</li>
 *   <li>ob2ndRout=2 (.chknearsonic): Flying, checking proximity. Stop on timer expire or Sonic detect.</li>
 * </ul>
 */
public class Sonic1BuzzBomberBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = 8 (enemy, collision size index 8)
    // Collision size 8: width=$18 (24px), height=$0C (12px)
    private static final int COLLISION_SIZE_INDEX = 0x08;

    // Horizontal velocity: move.w #$400,obVelX(a0)
    private static final int FLY_VELOCITY = 0x400;

    // Timer values (in frames, 60fps)
    private static final int FLY_DURATION = 127;        // buzz_timedelay for flying
    private static final int TURN_DELAY = 59;            // hover time after direction change
    private static final int NEAR_SONIC_DELAY = 29;      // hover time after detecting Sonic
    private static final int POST_FIRE_DELAY = 59;       // hover time after firing missile

    // Proximity detection: bhi.s .notsonic (distance >= $60)
    private static final int SONIC_PROXIMITY = 0x60;     // 96 pixels horizontal distance
    private static final int ON_SCREEN_HALF_WIDTH = 24;  // Buzz_Main: move.b #48/2,obActWid(a0)

    // Missile spawn offsets from disassembly .fire routine
    private static final int MISSILE_Y_OFFSET = 0x1C;    // 28 pixels below Buzz Bomber
    private static final int MISSILE_X_OFFSET = 0x18;    // 24 pixels horizontally (or $14 with FixBugs)

    // Missile velocity: move.w #$200,obVelX(a1) / move.w #$200,obVelY(a1)
    private static final int MISSILE_X_VEL = 0x200;
    private static final int MISSILE_Y_VEL = 0x200;

    // Secondary routine states (ob2ndRout values / 2)
    private static final int STATE_HOVER = 0;
    private static final int STATE_FLY = 1;

    // Buzz status values (buzz_buzzstatus / objoff_34)
    private static final int STATUS_NORMAL = 0;
    private static final int STATUS_FIRED = 1;
    private static final int STATUS_NEAR_SONIC = 2;

    private int secondaryState;
    private int timeDelay;
    private int buzzStatus;
    private int renderedFrame; // Actual frame index for rendering (includes wing cycle)
    private int wingTimer;     // Per-object animation timer (matches ROM's obTimeFrame)
    public Sonic1BuzzBomberBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "BuzzBomber");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // S1: obStatus bit 0 set = facing right
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.secondaryState = STATE_HOVER;
        this.timeDelay = 0;
        this.buzzStatus = STATUS_NORMAL;
        this.wingTimer = 1; // Start with speed=1 (AnimateSprite initial obTimeFrame)
        this.renderedFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (secondaryState) {
            case STATE_HOVER -> updateHover(player);
            case STATE_FLY -> updateFly(player, vIntRunCount);
        }
    }

    /**
     * ob2ndRout=0 (.move): Hover state.
     * Decrement timer; when expired, either fire (if near Sonic) or start flying.
     */
    private void updateHover(AbstractPlayableSprite player) {
        timeDelay--;
        if (timeDelay >= 0) {
            return; // Still waiting
        }

        // Timer expired
        if ((buzzStatus & 2) != 0) {
            // Near Sonic: fire missile
            fireMissile();
        } else {
            // Start flying
            timeDelay = FLY_DURATION;
            xVelocity = facingLeft ? -FLY_VELOCITY : FLY_VELOCITY;
            animFrame = 2; // Flying animation (frames 2-3)
            secondaryState = STATE_FLY;
        }
    }

    /**
     * ob2ndRout=2 (.chknearsonic): Flying state.
     * Move horizontally, check proximity to Sonic.
     */
    private void updateFly(AbstractPlayableSprite player, int vIntRunCount) {
        timeDelay--;
        if (timeDelay < 0) {
            // Timer expired: change direction
            changeDirection();
            return;
        }

        // Apply velocity (SpeedToPos: 16.8 fixed-point)
        currentX += (xVelocity >> 8);

        // Check proximity to Sonic (only if buzzStatus == 0 = normal)
        if (buzzStatus == STATUS_NORMAL && player != null) {
            // ROM: .chknearsonic reads Sonic's post-physics X from (v_player+obX).
            // In the ROM, Sonic's slot (index 0) runs his full physics (SpeedToPos +
            // terrain collision) before other objects execute. Engine: objects run
            // BEFORE player physics (step 2 vs step 3), but LevelManager pre-applies
            // the player's velocity (SpeedToPos) before calling objectManager.update(),
            // so getCentreX() already returns the predicted post-movement position.
            // Do NOT add getXSpeed()>>8 again — that would double-count the velocity.
            int playerX = player.getCentreX();
            int dx = Math.abs(playerX - currentX);
            // Buzz .chknearsonic gates this branch with "tst.b obRender(a0)":
            // docs/s1disasm/.../22, 23 Badnik - Buzz Bomber and Missile.asm:104-109.
            // BuildSprites sets bit 7 from the prior render pass using obActWid
            // (BuildSprites.asm:38-58, 115-116), so use the engine's render-flag
            // equivalent instead of an X-only point check.
            if (dx < SONIC_PROXIMITY && isOnScreenForTouch()) {
                // Near Sonic: stop and prepare to fire
                buzzStatus = STATUS_NEAR_SONIC;
                timeDelay = NEAR_SONIC_DELAY;
                stopAndHover();
            }
        }
    }

    /**
     * .chgdirection: Reset status, flip direction, set turn delay, stop.
     */
    private void changeDirection() {
        buzzStatus = STATUS_NORMAL;
        facingLeft = !facingLeft;
        timeDelay = TURN_DELAY;
        stopAndHover();
    }

    /**
     * .stop: Return to hover state, zero velocity, hovering animation.
     */
    private void stopAndHover() {
        secondaryState = STATE_HOVER;
        xVelocity = 0;
        animFrame = 0; // Hovering animation (frames 0-1)
    }

    /**
     * .fire: Spawn a missile object below the Buzz Bomber.
     * From disassembly: creates id_Missile at Y+$1C, X+/-$18,
     * velocity $200 in both axes.
     */
    private void fireMissile() {
        int missileX = facingLeft
                ? currentX - MISSILE_X_OFFSET
                : currentX + MISSILE_X_OFFSET;
        int missileY = currentY + MISSILE_Y_OFFSET;

        int missileXVel = facingLeft ? -MISSILE_X_VEL : MISSILE_X_VEL;

        final int fMissileX = missileX;
        final int fMissileY = missileY;
        final int fMissileXVel = missileXVel;
        final int fParentSlot = getSlotIndex();
        spawnFreeChild(() -> new Sonic1BuzzBomberMissileInstance(
                fMissileX, fMissileY, fMissileXVel, MISSILE_Y_VEL,
                facingLeft, fParentSlot));

        // Prevent refiring: set buzzStatus = 1
        buzzStatus = STATUS_FIRED;
        timeDelay = POST_FIRE_DELAY;
        animFrame = 4; // Firing animation (frames 4-5)
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // ROM AnimateSprite: obTimeFrame decrements from speed value (1) to 0, then advances.
        // Per-object timer ensures independent wing flap timing for each Buzz Bomber.
        wingTimer--;
        if (wingTimer < 0) {
            wingTimer = 1; // speed=1: each wing frame shows for 2 game frames
            // Toggle between base frame and base+1
            if (renderedFrame == animFrame) {
                renderedFrame = animFrame + 1;
            } else {
                renderedFrame = animFrame;
            }
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    /**
     * ROM equivalent: MarkObjGone keeps objects alive while near the camera.
     * The spawn windowing system checks the original spawn.x() against the camera
     * window, but a flying Buzz Bomber can move far from its spawn. When the camera
     * follows the player backward (away from spawn), the spawn may leave the window
     * even though the Buzz Bomber itself is still on-screen.
     *
     * Return true while our current position is within a generous margin of the
     * camera viewport, preventing the spawn windowing system from removing us
     * prematurely. We will be cleaned up normally when we are truly off-screen.
     */
    @Override
    public boolean isPersistent() {
        // Buzz_Display ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/22, 23 Badnik - Buzz Bomber and Missile.asm:38
        // -> _incObj/sub RememberState.asm:9 -> Macros.asm:278-295).
        return !isDestroyed() && isInRange();
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_WIDTH;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3); // obPriority = 3
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BUZZ_BOMBER);
        if (renderer == null) return;

        // Sprite art faces left by default; flip when facing right (same as S2 Buzzer)
        renderer.drawFrameIndex(renderedFrame, currentX, currentY, !facingLeft, false);
    }
}
