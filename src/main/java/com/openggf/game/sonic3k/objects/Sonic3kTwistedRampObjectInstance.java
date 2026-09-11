package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x0E - TwistedRamp (Sonic 3 &amp; Knuckles).
 * <p>
 * An invisible trigger that launches the player upward with a corkscrew flip
 * when they approach at sufficient horizontal speed. Used in AIZ and other zones.
 * <p>
 * ROM reference: Obj_TwistedRamp (sonic3k.asm, sub_24D9A)
 * <p>
 * Behavior:
 * <ul>
 *   <li>Checks both Player 1 and Player 2 (only Player 1 supported here)</li>
 *   <li>Player must NOT be airborne (btst #Status_InAir)</li>
 *   <li>Player must NOT be under object control (tst.b object_control)</li>
 *   <li>X range: player_x + 0x10 must be within [0, 0x20) of ramp X</li>
 *   <li>Y range: player_y must be within [-0x14, +0x20] of ramp Y</li>
 *   <li>Speed gate: |x_vel| &gt;= 0x400 in the direction the ramp faces</li>
 *   <li>Boost: x_vel += 0x400 (right-facing) or -= 0x400 (left-facing)</li>
 *   <li>Launch: y_vel = -0x700, set airborne</li>
 *   <li>Corkscrew flip: flip_angle=1, flip_speed=4, flips_remaining=0</li>
 * </ul>
 * <p>
 * Direction is determined by renderFlags bit 0 (status bit 0 in ROM):
 * 0 = facing right, 1 = facing left.
 */
public class Sonic3kTwistedRampObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    /** Minimum horizontal speed required to trigger (0x400 = 1024 subpixels). */
    private static final int SPEED_THRESHOLD = 0x400;

    /** Horizontal speed boost applied on trigger. */
    private static final int SPEED_BOOST = 0x400;

    /** Vertical launch velocity (negative = upward). */
    private static final short LAUNCH_Y_VEL = (short) -0x700;

    /** X offset added to player position before range check (ROM: addi.w #$10,d0). */
    private static final int X_OFFSET = 0x10;

    /** Width of horizontal trigger window (ROM: cmpi.w #$20,d0). */
    private static final int X_WINDOW = 0x20;

    /** Minimum Y distance (player above ramp, ROM: cmpi.w #-$14,d0). */
    private static final int Y_MIN = -0x14;

    /** Maximum Y distance (player below ramp, ROM: cmpi.w #$20,d0). */
    private static final int Y_MAX = 0x20;

    /** Debug wireframe colour (cyan to distinguish from InvisibleBlock gray). */
    private static final float DEBUG_R = 0.0f;
    private static final float DEBUG_G = 0.8f;
    private static final float DEBUG_B = 0.8f;

    /** true when renderFlags bit 0 is set (ramp faces left). */
    private boolean facingLeft;

    public Sonic3kTwistedRampObjectInstance(ObjectSpawn spawn) {
        super(spawn, "TwistedRamp");
        // ROM: btst #0,status(a0) - bit 0 of render flags determines direction
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public Sonic3kTwistedRampObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic3kTwistedRampObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ObjectServices ctx = tryServices();
        if (ctx != null) {
            List<PlayableEntity> players = ctx.playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
            if (players.isEmpty() && playerEntity == null && !isOnScreenX()) {
                setDestroyedByOffscreen();
                return;
            }
            for (PlayableEntity entity : players) {
                if (entity instanceof AbstractPlayableSprite playable) {
                    checkAndLaunchPlayer(playable);
                }
            }
            return;
        }

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            if (!isOnScreenX()) {
                setDestroyedByOffscreen();
            }
            return;
        }
        checkAndLaunchPlayer(player);
    }

    /**
     * ROM: sub_24D9A - Check proximity, speed, and conditions, then launch.
     */
    private void checkAndLaunchPlayer(AbstractPlayableSprite player) {
        // ROM: btst #Status_InAir,status(a1) / bne locret_24E32
        // Must NOT be airborne
        if (player.getAir()) {
            return;
        }

        // ROM: tst.b object_control(a1) / bne.s locret_24E32
        // Must not be under object control
        if (player.isObjectControlled()) {
            return;
        }

        // --- X range check ---
        // ROM: move.w x_pos(a1),d0 / addi.w #$10,d0 / sub.w x_pos(a0),d0
        // Uses player centre X (ROM x_pos is centre for players)
        int playerX = player.getCentreX();
        int dx = (playerX + X_OFFSET) - spawn.x();

        // ROM: bcs.w locret_24E32 (d0 < 0 after subtraction = carry set)
        if (dx < 0) {
            return;
        }
        // ROM: cmpi.w #$20,d0 / bge.w locret_24E32
        if (dx >= X_WINDOW) {
            return;
        }

        // --- Y range check ---
        // ROM: move.w y_pos(a1),d0 / sub.w y_pos(a0),d0
        int playerY = player.getCentreY();
        int dy = playerY - spawn.y();

        // ROM: cmpi.w #-$14,d0 / blt.s locret_24E32
        if (dy < Y_MIN) {
            return;
        }
        // ROM: cmpi.w #$20,d0 / bgt.s locret_24E32
        if (dy > Y_MAX) {
            return;
        }

        // --- Direction and speed check ---
        short xVel = player.getXSpeed();

        if (!facingLeft) {
            // Facing right: need x_vel >= 0x400
            // ROM: cmpi.w #$400,x_vel(a1) / blt.s locret_24E32
            if (xVel < SPEED_THRESHOLD) {
                return;
            }
            // ROM: addi.w #$400,x_vel(a1)
            player.setXSpeed((short) (xVel + SPEED_BOOST));
        } else {
            // Facing left: need x_vel <= -0x400
            // ROM: cmpi.w #-$400,x_vel(a1) / bgt.s locret_24E32
            if (xVel > -SPEED_THRESHOLD) {
                return;
            }
            // ROM: subi.w #$400,x_vel(a1)
            player.setXSpeed((short) (xVel - SPEED_BOOST));
        }

        // --- Launch ---
        // ROM: move.w #-$700,y_vel(a1)
        player.setYSpeed(LAUNCH_Y_VEL);

        // ROM: bset #Status_InAir,status(a1)
        player.setAir(true);

        // ROM: move.w #1,ground_vel(a1)
        // ground_vel = inertia/gSpeed: set to 1 to indicate direction for flip animation
        player.setGSpeed((short) 1);

        // ROM: move.b #0,anim(a1) - reset to walk animation
        player.setAnimationId(Sonic3kAnimationIds.WALK);

        // ROM: move.b #1,flip_angle(a1) - start flip rotation
        player.setFlipAngle(1);

        // ROM: move.b #0,flips_remaining(a1)
        player.setFlipsRemaining(0);

        // ROM: move.b #4,flip_speed(a1) - rotation speed
        player.setFlipSpeed(4);

        // ROM: move.b #5,flip_type(a1) - corkscrew flip type
        player.setFlipType(5);

        // ROM note: unlike springs (which negate flip_angle for left-facing players),
        // TwistedRamp uses the same positive values for ground_vel, flip_angle,
        // flip_speed, and flips_remaining regardless of facing direction.
        // The direction is already expressed through the boosted x_vel.
    }

    @Override
    public int getPriorityBucket() {
        // Invisible object - use default low priority for debug rendering only
        return RenderPriority.MIN;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj_TwistedRamp is an invisible trigger.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int rampX = spawn.x();
        int rampY = spawn.y();

        // Draw the trigger zone as a wireframe rectangle
        // X range: [rampX - X_OFFSET, rampX - X_OFFSET + X_WINDOW)
        int left = rampX - X_OFFSET;
        int right = left + X_WINDOW;
        int top = rampY + Y_MIN;
        int bottom = rampY + Y_MAX;

        ctx.drawLine(left, top, right, top, DEBUG_R, DEBUG_G, DEBUG_B);
        ctx.drawLine(right, top, right, bottom, DEBUG_R, DEBUG_G, DEBUG_B);
        ctx.drawLine(right, bottom, left, bottom, DEBUG_R, DEBUG_G, DEBUG_B);
        ctx.drawLine(left, bottom, left, top, DEBUG_R, DEBUG_G, DEBUG_B);

        // Draw direction arrow (points in the direction the ramp launches)
        int centerY = rampY;
        if (!facingLeft) {
            // Right-pointing arrow
            ctx.drawLine(rampX - 4, centerY, rampX + 4, centerY, DEBUG_R, DEBUG_G, DEBUG_B);
            ctx.drawLine(rampX + 4, centerY, rampX + 1, centerY - 3, DEBUG_R, DEBUG_G, DEBUG_B);
            ctx.drawLine(rampX + 4, centerY, rampX + 1, centerY + 3, DEBUG_R, DEBUG_G, DEBUG_B);
        } else {
            // Left-pointing arrow
            ctx.drawLine(rampX + 4, centerY, rampX - 4, centerY, DEBUG_R, DEBUG_G, DEBUG_B);
            ctx.drawLine(rampX - 4, centerY, rampX - 1, centerY - 3, DEBUG_R, DEBUG_G, DEBUG_B);
            ctx.drawLine(rampX - 4, centerY, rampX - 1, centerY + 3, DEBUG_R, DEBUG_G, DEBUG_B);
        }
    }
}
