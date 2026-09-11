package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.BreathingBubbleInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ChopChop (0x91) - Piranha/shark Badnik from Aquatic Ruin Zone.
 * A 4-state patrolling badnik that detects the player and charges at them.
 *
 * Behavior from disassembly (obj91.asm lines 73137-73302):
 * 1. PATROLLING: Move at speed 0x40, switch direction every 512 frames,
 *    spawn bubbles every 80 frames
 * 2. WAITING: Stop for 16 frames when player detected (mouth animation)
 * 3. CHARGING: Move toward player at 2 pixels/frame horizontal. Vertical speed
 *    (0.5 px/frame down) is added ONLY when the player is OUTSIDE the narrow
 *    +-0x10px vertical band at the Waiting->Charge transition; if the player is
 *    level with the ChopChop, it charges purely horizontally and holds its y_pos
 *    (s2.asm:73664-73684, Obj91_MoveTowardsPlayer). The velocities are latched
 *    ONCE at that transition, not recomputed per frame.
 *
 * Player detection (Obj91_TestCharacterPos, s2.asm:73716-73747):
 * - Horizontal range: 32 <= distance < 160 pixels (0x20-0xA0)
 * - Vertical range: -32 to +31 pixels (asymmetric, +-0x20 band)
 * - Only attacks if already moving toward the player
 */
public class ChopChopBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    // Collision size from subObjData in disassembly
    private static final int COLLISION_SIZE_INDEX = 0x02;

    // Movement constants from disassembly
    private static final int PATROL_SPEED = 0x40;           // 64 subpixels/frame (move.w #$40,x_vel(a0))
    private static final int MOVE_TIMER_INIT = 0x200;       // 512 frames (move.w #$200,objoff_36(a0))
    private static final int BUBBLE_TIMER_BYTE_AFTER_WORD_RESET = 0x00;
    private static final int WAIT_TIME = 0x10;              // 16 frames (move.b #$10,anim_frame_duration(a0))
    private static final int CHARGE_SPEED_X = 2;            // 2 pixels/frame
    private static final int CHARGE_SPEED_Y_SUBPIXEL = 0x80; // 0.5 pixels/frame (addi.w #$80,y_pos(a0))

    // Detection ranges from disassembly
    private static final int DETECTION_RANGE_MIN = 0x20;    // 32 pixels (subi.w #$20,d0)
    private static final int DETECTION_RANGE_MAX = 0xA0;    // 160 pixels (cmpi.w #$A0,d0)
    private static final int DETECTION_RANGE_V = 0x20;      // Vertical offset for asymmetric range check

    // Animation constants
    private static final int ANIM_DELAY = 4;                // 4 ticks per frame

    // States matching disassembly's routine labels
    private enum State {
        PATROLLING,  // Loc_363F2 - normal movement
        WAITING,     // Loc_36468 - pause before attack
        CHARGING     // Loc_3648A - attack run
    }

    private State state;
    private int moveTimer;           // objoff_36 - frames until direction switch
    private int bubbleTimer;         // Obj91_bubble_timer - frames until next small bubble
    private int waitTimer;           // anim_frame_duration - frames until charge
    private int xSubpixel;           // Subpixel accumulator for x movement (ObjectMove 16.16 carry)
    private int ySubpixel;           // Subpixel accumulator for y movement during charge
    private boolean chargeLatched;   // Have charge velocities been latched (Obj91_MoveTowardsPlayer)?
    private int startX;              // Initial X position for direction reference
    private boolean initFrame;        // ROM Obj91_Init returns before Obj91_Main can move

    public ChopChopBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "ChopChop", Sonic2BadnikConfig.DESTRUCTION);
        this.state = State.PATROLLING;
        this.moveTimer = MOVE_TIMER_INIT;
        this.bubbleTimer = 0;
        this.waitTimer = 0;
        this.xSubpixel = 0;
        this.ySubpixel = 0;
        this.chargeLatched = false;
        this.startX = spawn.x();
        this.initFrame = true;

        // Initial facing based on render_flags (status.npc.x_flip bit)
        // From disassembly: if x_flip bit is SET, velocity stays positive (moving RIGHT)
        // if x_flip bit is CLEAR, velocity is negated (moving LEFT)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;  // x_flip=1 means facing RIGHT, so facingLeft = false

        // Set initial velocity based on facing direction
        xVelocity = facingLeft ? -PATROL_SPEED : PATROL_SPEED;
    }

    @Override
    public ChopChopBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new ChopChopBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case PATROLLING -> updatePatrolling(vIntRunCount, player);
            case WAITING -> updateWaiting(vIntRunCount, player);
            case CHARGING -> updateCharging(player);
        }
    }

    /**
     * Patrolling state (Loc_363F2):
     * - Move horizontally at patrol speed
     * - Switch direction every 512 frames
     * - Check for player detection
     */
    private void updatePatrolling(int vIntRunCount, AbstractPlayableSprite player) {
        if (initFrame) {
            // Obj91_Init (s2.asm:73672-73684) sets timers/x_vel and returns.
            // Obj91_Main's ObjectMove starts on the following ExecuteObjects pass.
            initFrame = false;
            return;
        }

        // Apply velocity via ObjectMove-style subpixel integration.
        // Obj91_Main calls JmpTo26_ObjectMove (s2.asm:73698), and ObjectMove
        // integrates x_pos(32) += x_vel<<8 (s2.asm:30185-30199): the velocity's
        // low byte accumulates into the sub-pixel and carries into the whole
        // pixel. PATROL_SPEED=0x40 = 0.25px/frame, so a plain `>>8` would
        // discard it entirely and the badnik would never advance.
        applyXVelocitySubpixel();

        // ROM Obj91_Main pre-decrements the byte at Obj91_bubble_timer and
        // calls Obj91_MakeBubble when it reaches zero (s2.asm:73687-73769).
        bubbleTimer = (bubbleTimer - 1) & 0xFF;
        if (bubbleTimer == 0) {
            spawnPatrolBubble();
        }

        // Decrement direction switch timer
        moveTimer--;
        if (moveTimer <= 0) {
            // Switch direction
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -PATROL_SPEED : PATROL_SPEED;
            moveTimer = MOVE_TIMER_INIT;
        }

        // Check for player detection
        if (player != null && detectPlayer(player)) {
            // Player detected - transition to waiting state
            state = State.WAITING;
            waitTimer = WAIT_TIME;
            xVelocity = 0; // Stop moving
            chargeLatched = false; // charge velocities re-latched at Waiting->Charge
        }

    }

    private void spawnPatrolBubble() {
        // Obj91_MakeBubble resets the word at objoff_2C with move.w #$50, but
        // Obj91_Main decrements the byte at objoff_2C. On 68K that byte is the
        // word's high byte, so the next byte countdown starts from 0, not 0x50.
        bubbleTimer = BUBBLE_TIMER_BYTE_AFTER_WORD_RESET;
        int bubbleX = currentX + (facingLeft ? 0x14 : -0x14);
        int bubbleY = currentY + 6;
        spawnFreeChild(() -> new BreathingBubbleInstance(
                bubbleX,
                bubbleY,
                !facingLeft,
                -1,
                ObjectArtKeys.BUBBLES,
                null,
                3,
                -0x88,
                false));
    }

    /**
     * Waiting state (Obj91_Waiting, s2.asm:73658-73661):
     *   subq.b #1,Obj91_move_timer(a0)
     *   bmi.s  Obj91_MoveTowardsPlayer   ; branch when wait time is over
     *   bra.w  Obj91_Animate
     * The ROM waits while the byte timer is >= 0 and only crosses into
     * Obj91_MoveTowardsPlayer when the decrement makes it negative.
     */
    private void updateWaiting(int vIntRunCount, AbstractPlayableSprite player) {
        waitTimer--;
        if (waitTimer < 0) {
            // Obj91_MoveTowardsPlayer (s2.asm:73664-73675) runs ONCE at the
            // Waiting->Charge transition. It latches x_vel and (conditionally)
            // y_vel, then control falls into Obj91_Charge which from then on
            // only re-integrates those latched velocities via ObjectMove.
            latchChargeVelocities(player);
            state = State.CHARGING;
        }
    }

    /**
     * Obj91_MoveTowardsPlayer (s2.asm:73664-73684) — latch charge velocities ONCE.
     *
     * Horizontal: x_vel = Obj91_HorizontalSpeeds[d0>>1] = -2/+2 whole pixels,
     * i.e. +-0x200 in 16.8 (d0 carries +2 when the player is to the object's
     * left, from Obj_GetOrientationToPlayer s2.asm:72772-72774).
     *
     * Vertical (the load-bearing gate): d3 = y_pos(a0) - y_pos(player); the ROM
     * does:
     *   addi.w #$10,d3
     *   cmpi.w #$20,d3
     *   blo.s  +              ; SKIP the vertical-speed write
     *   ... move.b VerticalSpeeds,1+y_vel ...
     *   +:
     * The `blo` is taken (skipping the write) when (d3 + 0x10) u< 0x20, i.e.
     * when the closest character is INSIDE the narrow band (~0x10px above to
     * ~0xF px below the object). So the vertical speed ($80 down) is written
     * ONLY when the player is OUTSIDE that band; when the player is level with
     * the ChopChop, y_vel stays 0 and it charges horizontally holding its y_pos.
     * Verified against this trace: at the Waiting->Charge frame the closest
     * character is at d3=-5 (inside the band) so ROM leaves y_pos=0x538 fixed
     * for the whole charge. Both Obj91_VerticalSpeeds entries are $80, so the
     * band gate alone decides whether any vertical motion happens at all.
     */
    private void latchChargeVelocities(AbstractPlayableSprite player) {
        chargeLatched = true;
        ySubpixel = 0;

        // Horizontal speed selection: aim at the closest character. Use the same
        // sign convention as detectPlayer: player to the object's left -> move
        // left. facingLeft was already aligned during detection, but re-derive
        // from the live orientation so the latch matches Obj_GetOrientationToPlayer.
        if (player != null) {
            facingLeft = currentX > player.getCentreX();
        }
        xVelocity = facingLeft ? -(CHARGE_SPEED_X << 8) : (CHARGE_SPEED_X << 8);

        // Vertical band gate (s2.asm:73669-73673). d3 = obj_y - player_y.
        // ROM writes the vertical speed only when the player is OUTSIDE the band:
        //   addi.w #$10,d3 / cmpi.w #$20,d3 / blo + (blo SKIPS the write).
        // So vertical motion happens when (d3 + 0x10) u>= 0x20.
        boolean verticalSpeed = false;
        if (player != null) {
            int d3 = currentY - player.getCentreY();
            int banded = (d3 + 0x10) & 0xFFFF;        // addi.w #$10,d3 (16-bit)
            verticalSpeed = banded >= 0x20;           // cmpi.w #$20,d3 ; NOT blo
        }
        // Obj91_VerticalSpeeds (s2.asm:73682-73684): both entries are $80 (down).
        yVelocity = verticalSpeed ? CHARGE_SPEED_Y_SUBPIXEL : 0;
    }

    /**
     * Charging state (Obj91_Charge, s2.asm:73687-73688):
     *   jsrto JmpTo26_ObjectMove
     * Each charge frame just re-integrates the latched x_vel/y_vel via ObjectMove
     * (s2.asm:30185-30199, x_pos(32) += x_vel<<8). The velocities were fixed once
     * in Obj91_MoveTowardsPlayer; they are NOT recomputed per frame. In
     * particular y_vel is 0 unless the band gate fired at the transition, so a
     * ChopChop that latched no vertical speed holds its y_pos for the charge.
     */
    private void updateCharging(AbstractPlayableSprite player) {
        // Defensive: if somehow charging without a latch (e.g. restored state),
        // latch from the current orientation before integrating.
        if (!chargeLatched) {
            latchChargeVelocities(player);
        }
        applyXVelocitySubpixel();

        // y_vel low-byte ($80) accumulates into the sub-pixel and carries into a
        // whole pixel; y_vel = 0 means no vertical motion at all.
        ySubpixel += (yVelocity & 0xFF);
        currentY += (yVelocity >> 8) + (ySubpixel >> 8);
        ySubpixel &= 0xFF;
    }

    /**
     * ObjectMove X integration (s2.asm:30185-30199): x_pos(32) += x_vel&lt;&lt;8.
     * Reproduced as a 16:8 accumulator — the low byte of x_vel accumulates into
     * the sub-pixel and carries into the whole pixel. xVelocity is signed where
     * 0x100 = 1px/frame.
     */
    private void applyXVelocitySubpixel() {
        int total = xSubpixel + (xVelocity & 0xFF);
        currentX += (xVelocity >> 8) + (total >> 8);
        xSubpixel = total & 0xFF;
    }

    /**
     * Player detection logic from disassembly (lines 73247-73280):
     * - Player must be 32 <= distance < 160 pixels away horizontally
     * - Player must be within asymmetric vertical range (-32 to +31 pixels)
     * - ChopChop must be moving toward the player (check actual velocity)
     */
    private boolean detectPlayer(AbstractPlayableSprite player) {
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // Calculate horizontal distance (object - player, matching disassembly order)
        int dx = currentX - playerX;
        int absDx = Math.abs(dx);

        // Check horizontal range: cmpi.w #$20/blo rejects below 0x20;
        // cmpi.w #$A0/blo accepts only values below 0xA0.
        if (absDx < DETECTION_RANGE_MIN || absDx >= DETECTION_RANGE_MAX) {
            return false;
        }

        // Check vertical range using disassembly's asymmetric check:
        // addi.w #$20,d3 ; cmpi.w #$40,d3 ; bhs -> don't charge
        // This means: (object_y - player_y + 0x20) must be < 0x40
        // Equivalent to player being between -32 and +31 pixels vertically
        int dy = currentY - playerY;
        int adjustedDy = dy + DETECTION_RANGE_V;
        if (adjustedDy < 0 || adjustedDy >= 0x40) {
            return false;
        }

        // Check if moving toward player (use actual velocity, not facingLeft flag)
        // dx > 0 means player is to the LEFT, so we need negative velocity (moving left)
        // dx < 0 means player is to the RIGHT, so we need positive velocity (moving right)
        if (dx > 0) {
            // Player to the left - must be moving left (negative velocity)
            if (xVelocity >= 0) {
                return false;
            }
        } else {
            // Player to the right - must be moving right (positive velocity)
            if (xVelocity <= 0) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation based on state and frame counter
        // Frame 0 = mouth closed, Frame 1 = mouth open
        switch (state) {
            case PATROLLING -> {
                // Slow animation during patrol
                animFrame = ((vIntRunCount >> 3) & 1); // Toggle every 8 frames
            }
            case WAITING -> {
                // Fast mouth animation during wait
                animFrame = ((vIntRunCount >> 2) & 1); // Toggle every 4 frames
            }
            case CHARGING -> {
                // Mouth open during charge
                animFrame = 1;
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

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CHOP_CHOP);
        if (renderer == null) return;

        // Render current animation frame
        // Art faces left by default; flip when facing right
        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
