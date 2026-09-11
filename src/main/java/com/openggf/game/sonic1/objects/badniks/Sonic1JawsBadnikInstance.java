package com.openggf.game.sonic1.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Jaws (0x2C) - Aquatic fish Badnik from Labyrinth Zone.
 * Swims horizontally, reversing direction on a timer derived from its subtype.
 * <p>
 * Based on docs/s1disasm/_incObj/2C Jaws.asm.
 * <p>
 * Routine index:
 * <ul>
 *   <li>0 (Jaws_Main): Initialization - set art, collision, calculate turn delay
 *       from subtype (subtype * 64 - 1), set initial velocity (-$40 left or +$40 right
 *       based on obStatus bit 0), then fall through to Jaws_Turn.</li>
 *   <li>2 (Jaws_Turn): Main loop - decrement turn timer, reverse direction and
 *       reset animation when timer expires, animate via Ani_Jaws, apply velocity
 *       via SpeedToPos, use RememberState for persistence.</li>
 * </ul>
 * <p>
 * Animation (Ani_Jaws):
 * <ul>
 *   <li>0 (.swim): frames 0, 1, 2, 3 at speed 7 (8 ticks per frame), looping</li>
 * </ul>
 * <p>
 * Mapping frames (Map_Jaws_internal):
 * <ul>
 *   <li>Frame 0 (.open1): body mouth open + tail normal</li>
 *   <li>Frame 1 (.shut1): body mouth shut + tail normal</li>
 *   <li>Frame 2 (.open2): body mouth open + tail vFlipped</li>
 *   <li>Frame 3 (.shut2): body mouth shut + tail vFlipped</li>
 * </ul>
 */
public class Sonic1JawsBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: move.b #$A,obColType(a0) (enemy, collision size index $A)
    private static final int COLLISION_SIZE_INDEX = 0x0A;

    // From disassembly: move.w #-$40,obVelX(a0) - base horizontal swim speed
    private static final int SWIM_VELOCITY = 0x40;

    // Animation speed from _anim/Jaws.asm: dc.b 7, 0, 1, 2, 3, afEnd
    // Speed value 7 means display each frame for 8 ticks (speed + 1)
    private static final int ANIM_SPEED = 7 + 1;

    // Number of frames in the swim animation cycle
    private static final int ANIM_FRAME_COUNT = 4;

    private int turnTimeCount;       // jaws_timecount (objoff_30): current countdown timer
    private int turnTimeDelay;       // jaws_timedelay (objoff_32): reload value for timer
    private final SubpixelMotion.State motionState; // Subpixel position/velocity state
    private int animTickCounter;     // Ticks within current animation frame
    private int prevAnim;            // obPrevAni: tracks animation reset trigger

    public Sonic1JawsBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Jaws");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.animTickCounter = 0;
        this.prevAnim = 0;

        // Jaws_Main: moveq #0,d0 / move.b obSubtype(a0),d0 / lsl.w #6,d0 / subq.w #1,d0
        // Turn delay = subtype * 64 - 1
        int subtype = spawn.subtype() & 0xFF;
        int delay = (subtype << 6) - 1;
        this.turnTimeDelay = delay;
        this.turnTimeCount = delay;

        // Jaws_Main: move.w #-$40,obVelX(a0) - default: swim left
        // btst #0,obStatus(a0) / beq.s Jaws_Turn - if facing left (bit 0 clear), keep left
        // neg.w obVelX(a0) - if facing right (bit 0 set), negate to swim right
        boolean statusBit0 = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !statusBit0;
        this.xVelocity = -SWIM_VELOCITY;
        if (statusBit0) {
            this.xVelocity = SWIM_VELOCITY;
        }
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Jaws_Turn (Routine 2):

        // subq.w #1,jaws_timecount(a0)
        turnTimeCount--;

        // bpl.s .animate - if time remains, skip turn
        if (turnTimeCount < 0) {
            // Timer expired: reverse direction
            // move.w jaws_timedelay(a0),jaws_timecount(a0) - reset timer
            turnTimeCount = turnTimeDelay;

            // neg.w obVelX(a0) - reverse velocity
            xVelocity = -xVelocity;

            // bchg #0,obStatus(a0) - toggle facing direction
            facingLeft = !facingLeft;

            // move.b #1,obPrevAni(a0) - force animation reset
            // In the ROM, writing a non-zero value to obPrevAni causes AnimateSprite
            // to detect a change and reset the animation counter on the next frame.
            prevAnim = 1;
            animTickCounter = 0;
        }

        // SpeedToPos: apply velocity to X position with subpixel precision
        motionState.x = currentX;
        motionState.xVel = xVelocity;
        SubpixelMotion.moveX(motionState);
        currentX = motionState.x;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // AnimateSprite: advance swim animation
        animTickCounter++;
    }

    /**
     * Returns the mapping frame index based on current animation tick counter.
     * From _anim/Jaws.asm:
     * <pre>
     * .swim: dc.b 7, 0, 1, 2, 3, afEnd
     * </pre>
     * 4-frame cycle at speed 7 (8 ticks per frame): frames 0, 1, 2, 3.
     */
    private int getMappingFrame() {
        int step = (animTickCounter / ANIM_SPEED) % ANIM_FRAME_COUNT;
        return step; // 0=open1, 1=shut1, 2=open2, 3=shut2
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
        // docs/s1disasm/s1disasm/_incObj/2C Badnik - Jaws.asm:
        // Jaws_Turn ends with SpeedToPos then RememberState. The shared S1
        // counter-based unload path performs that exact out_of_range check, so
        // do not add a wider isOnScreenX margin here.
        return false;
    }

    @Override
    public int getPriorityBucket() {
        // From disassembly: move.b #4,obPriority(a0)
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.JAWS);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // Jaws art faces left by default. H-flip when facing right (not facingLeft).
        // From disassembly: ori.b #4,obRender(a0) - render flag bit 2 = use X position for flip
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle
        ctx.drawRect(currentX, currentY, 16, 16, 1f, 1f, 0f);

        // Cyan velocity arrow
        if (xVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, currentY, 0f, 1f, 1f);
        }

        // Yellow text label: name + frame + facing + timer
        String dir = facingLeft ? "L" : "R";
        String label = name + " f" + getMappingFrame() + " " + dir + " t" + turnTimeCount;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);
    }
}
