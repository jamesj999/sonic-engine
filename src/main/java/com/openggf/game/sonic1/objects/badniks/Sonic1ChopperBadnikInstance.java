package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Chopper (0x2B) - Fish Badnik from Green Hill Zone.
 * Leaps vertically out of water/ground, snapping its jaw. Uses simple
 * gravity-based oscillation between its spawn point and peak height.
 * <p>
 * Based on docs/s1disasm/_incObj/2B Chopper.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Chop_Main): Initialization - set art, collision, initial upward velocity,
 *       save origin Y, then fall through to Chop_ChgSpeed</li>
 *   <li>2 (Chop_ChgSpeed): Main loop - animate, apply gravity, bounce at origin,
 *       switch animation based on height relative to (origY - 0xC0)</li>
 * </ul>
 * <p>
 * Animation:
 * <ul>
 *   <li>0 (slow): frames 0,1 at speed 7 - used when below threshold and ascending</li>
 *   <li>1 (fast): frames 0,1 at speed 3 - used when above threshold</li>
 *   <li>2 (still): frame 0 at speed 7 - used when below threshold and descending</li>
 * </ul>
 */
public class Sonic1ChopperBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = $9 (enemy, collision size index $9)
    private static final int COLLISION_SIZE_INDEX = 0x09;

    // From disassembly: move.w #-$700,obVelY(a0)
    private static final int INITIAL_Y_VELOCITY = -0x700;

    // From disassembly: addi.w #$18,obVelY(a0)
    private static final int GRAVITY = 0x18;

    // From disassembly: subi.w #$C0,d0 (threshold = origY - 0xC0)
    private static final int ANIM_THRESHOLD_OFFSET = 0xC0;

    // Animation IDs from _anim/Chopper.asm
    private static final int ANIM_SLOW = 0;
    private static final int ANIM_FAST = 1;
    private static final int ANIM_STILL = 2;

    // Animation speeds from _anim/Chopper.asm: slow=7, fast=3, still=7
    private static final int ANIM_SPEED_SLOW = 7 + 1;   // 8 ticks per frame
    private static final int ANIM_SPEED_FAST = 3 + 1;   // 4 ticks per frame
    private static final int ANIM_SPEED_STILL = 7 + 1;  // 8 ticks per frame

    private int origY;                // chop_origY (objoff_30): saved spawn Y position
    private int yVelocity;            // obVelY: current vertical velocity (subpixels)
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int currentAnim;          // Current animation index (0=slow, 1=fast, 2=still)
    private int animTickCounter;      // Ticks within current animation frame

    public Sonic1ChopperBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Chopper");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.origY = spawn.y();
        // Chopper doesn't use facing direction from spawn flags
        this.facingLeft = false;

        // Chop_Main: move.w #-$700,obVelY(a0)
        this.yVelocity = INITIAL_Y_VELOCITY;
        this.currentAnim = ANIM_FAST;
        this.animTickCounter = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Chop_ChgSpeed: SpeedToPos - apply velocity to Y position, then add gravity
        // (matches ROM order: move with current velocity, then add gravity for next frame).
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = yVelocity;
        SubpixelMotion.speedToPosY(motion);
        currentY = motion.y;
        yVelocity += GRAVITY;

        // cmp.w obY(a0),d0 / bhs.s .chganimation
        // Has Chopper fallen back to or below its original position?
        if (currentY >= origY) {
            // move.w d0,obY(a0) - snap only the high position word to origin.
            // SpeedToPos uses obY as a 32-bit value; the ROM write leaves the
            // low word intact for the next leap.
            currentY = origY;
            // move.w #-$700,obVelY(a0) - reset velocity for next jump
            yVelocity = INITIAL_Y_VELOCITY;
        }

        // .chganimation: determine animation based on height and velocity
        updateAnimationState();
    }

    /**
     * Animation state selection from Chop_ChgSpeed.
     * <pre>
     * move.b  #1,obAnim(a0)       ; default: fast animation
     * subi.w  #$C0,d0             ; d0 = origY - $C0 (threshold)
     * cmp.w   obY(a0),d0          ; is Chopper above threshold?
     * bhs.s   .nochg              ; if above (Y <= threshold), keep fast
     * move.b  #0,obAnim(a0)       ; below threshold: slow animation
     * tst.w   obVelY(a0)          ; check velocity direction
     * bmi.s   .nochg              ; if ascending (vel < 0), keep slow
     * move.b  #2,obAnim(a0)       ; descending: stationary animation
     * </pre>
     */
    private void updateAnimationState() {
        int threshold = origY - ANIM_THRESHOLD_OFFSET;
        int newAnim;

        if (currentY <= threshold) {
            // Above threshold: fast chomping
            newAnim = ANIM_FAST;
        } else if (yVelocity < 0) {
            // Below threshold, ascending: slow chomping
            newAnim = ANIM_SLOW;
        } else {
            // Below threshold, descending or at peak: stationary (mouth shut)
            newAnim = ANIM_STILL;
        }

        if (newAnim != currentAnim) {
            currentAnim = newAnim;
            animTickCounter = 0;
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animTickCounter++;
    }

    /**
     * Returns the mapping frame index based on current animation state and tick counter.
     * From _anim/Chopper.asm:
     * <pre>
     * .slow:   dc.b 7, 0, 1, afEnd       ; frames 0,1 at speed 7
     * .fast:   dc.b 3, 0, 1, afEnd       ; frames 0,1 at speed 3
     * .still:  dc.b 7, 0, afEnd          ; frame 0 only at speed 7
     * </pre>
     */
    private int getMappingFrame() {
        return switch (currentAnim) {
            case ANIM_FAST -> {
                // 2-frame cycle: 0, 1 at speed 3 (4 ticks each)
                int step = (animTickCounter / ANIM_SPEED_FAST) % 2;
                yield step; // 0 = mouthshut, 1 = mouthopen
            }
            case ANIM_SLOW -> {
                // 2-frame cycle: 0, 1 at speed 7 (8 ticks each)
                int step = (animTickCounter / ANIM_SPEED_SLOW) % 2;
                yield step;
            }
            case ANIM_STILL -> 0; // Always frame 0 (mouth shut)
            default -> 0;
        };
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
        // Chop_Main ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/2B Badnik - Chopper.asm:11 ->
        // _incObj/sub RememberState.asm:9 -> Macros.asm:278-295).
        return !isDestroyed() && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        // obPriority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.CHOPPER);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // Chopper faces left by default in art; no facing direction used
        renderer.drawFrameIndex(frame, currentX, currentY, false, false);
    }
}
