package com.openggf.game.sonic2.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * WFZ Speed Launcher (Object 0xC0) - catapult platform from Wing Fortress Zone.
 * <p>
 * A solid platform that, when stood upon, rapidly accelerates toward a destination
 * position and then launches the player into the air with the platform's velocity.
 * After launching, the platform slowly returns to its home position.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 80215-80381 (ObjC0)
 * <p>
 * <h3>Subtype Format</h3>
 * The subtype byte specifies the travel distance: {@code distance = subtype * 16} pixels.
 * <p>
 * The x_flip render flag determines direction:
 * <ul>
 *   <li>x_flip = 0: Platform travels LEFT from home to destination</li>
 *   <li>x_flip = 1: Platform travels RIGHT from home to destination</li>
 * </ul>
 * <p>
 * <h3>State Machine (routine_secondary)</h3>
 * <table border="1">
 *   <tr><th>State</th><th>Name</th><th>Behavior</th></tr>
 *   <tr><td>0</td><td>Idle</td><td>Waits for player to stand on platform</td></tr>
 *   <tr><td>2</td><td>Accelerating</td><td>Platform accelerates toward destination, syncing standing players</td></tr>
 *   <tr><td>4</td><td>Returning</td><td>Platform moves 4px/frame back to home, resets to Idle on arrival</td></tr>
 * </table>
 * <p>
 * <h3>Launch Mechanics (loc_3C020)</h3>
 * When the platform reaches its destination, standing players receive:
 * <ul>
 *   <li>x_vel = platform's current x_vel (full catapult speed)</li>
 *   <li>y_vel = -$400 (upward launch)</li>
 *   <li>in_air flag set (player becomes airborne)</li>
 * </ul>
 */
public class SpeedLauncherObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants (s2.asm lines 80259-80373)
    // ========================================================================

    // Initial velocity when player stands on platform
    // ROM: move.w #$C00,x_vel(a0)
    private static final int INITIAL_X_VELOCITY = 0xC00;

    // Acceleration per frame during movement
    // ROM: move.w #$80,objoff_30(a0)
    private static final int ACCELERATION = 0x80;

    // Launch Y velocity applied to player
    // ROM: move.w #-$400,y_vel(a1)
    private static final int LAUNCH_Y_VELOCITY = -0x400;

    // Return speed (pixels per frame toward home)
    // ROM: moveq #4,d1
    private static final int RETURN_SPEED = 4;

    // PlatformObject parameters from SubObjData
    // ROM: move.w #$10,d1 (half-width), move.w #$11,d3 (half-height for ground)
    private static final int PLATFORM_HALF_WIDTH = 0x10;
    private static final int PLATFORM_HALF_HEIGHT_AIR = 0x11;
    private static final int PLATFORM_HALF_HEIGHT_GROUND = 0x11;

    // ========================================================================
    // State Machine Constants
    // ========================================================================

    private static final int STATE_IDLE = 0;
    private static final int STATE_ACCELERATING = 2;
    private static final int STATE_RETURNING = 4;
    private static final ObjectPlayerParticipationPolicy PLAYER_PARTICIPATION =
            ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED;

    // ========================================================================
    // Instance State
    // ========================================================================

    // Current X position (changes during movement)
    private int currentX;
    // Home X position (objoff_34 in ROM)
    private int homeX;
    // Destination X position (objoff_32 in ROM)
    private int destX;
    // Current X velocity (8.8 fixed point)
    private int xVel;
    // Acceleration value (signed, direction-dependent) (objoff_30 in ROM)
    private int accel;
    // Whether the object has x_flip set
    private boolean xFlipped;
    // Current state machine state (routine_secondary in ROM)
    private int state;
    // Sub-pixel X accumulator for ObjectMove
    private int subPixelX;
    private final Set<PlayableEntity> standingPlayers =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<PlayableEntity> accelerationRiders =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private final SolidObjectParams solidParams;

    public SpeedLauncherObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.homeX = spawn.x();
        this.currentX = homeX;
        this.subPixelX = homeX << 8;
        this.state = STATE_IDLE;
        this.xVel = 0;

        // ROM: ObjC0_Init (s2.asm lines 80227-80240)
        // distance = subtype * 16 (lsl.w #4,d0)
        int distance = (spawn.subtype() & 0xFF) << 4;

        // ROM: btst #status.npc.x_flip,status(a0) / bne.s + / neg.w d0
        // If NOT flipped, negate distance (platform moves LEFT)
        if (!xFlipped) {
            distance = -distance;
        }

        // ROM: move.w d1,objoff_34(a0) / add.w d1,d0 / move.w d0,objoff_32(a0)
        this.destX = homeX + distance;

        // Acceleration direction matches movement direction
        if (!xFlipped) {
            this.accel = -ACCELERATION;
        } else {
            this.accel = ACCELERATION;
        }

        this.solidParams = SolidObjectParams.of(
                PLATFORM_HALF_WIDTH, PLATFORM_HALF_HEIGHT_AIR, PLATFORM_HALF_HEIGHT_GROUND);
    }

    @Override
    public SpeedLauncherObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpeedLauncherObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM: ObjC0_Main (s2.asm lines 80242-80251)
        // Process state machine, then PlatformObject + MarkObjGone
        switch (state) {
            case STATE_IDLE -> updateIdle(player);
            case STATE_ACCELERATING -> updateAccelerating(player);
            case STATE_RETURNING -> updateReturning();
        }
    }

    /**
     * State 0: Idle - wait for player to stand on platform.
     * ROM: loc_3BF66 (s2.asm lines 80259-80284)
     * <p>
     * The actual standing detection is handled by the SolidObjectProvider/SolidObjectListener
     * system. This state just checks if the platform is being stood on via onSolidContact.
     */
    private void updateIdle(AbstractPlayableSprite player) {
        AbstractPlayableSprite rider = firstStandingPlayer(player);
        if (rider != null) {
            onStandingIdle(rider);
        }
    }

    private AbstractPlayableSprite firstStandingPlayer(AbstractPlayableSprite player) {
        for (PlayableEntity participant : playerParticipants(player)) {
            if (standingPlayers.contains(participant)) {
                return (AbstractPlayableSprite) participant;
            }
        }

        return null;
    }

    private void forEachStandingPlayer(AbstractPlayableSprite main, java.util.function.Consumer<AbstractPlayableSprite> action) {
        for (PlayableEntity participant : playerParticipants(main)) {
            if (standingPlayers.contains(participant) || accelerationRiders.contains(participant)) {
                action.accept((AbstractPlayableSprite) participant);
            }
        }
    }

    private List<PlayableEntity> playerParticipants(AbstractPlayableSprite updatePlayer) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(PLAYER_PARTICIPATION);
        if (updatePlayer != null && !participants.contains(updatePlayer)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(updatePlayer);
            withUpdatePlayer.addAll(participants);
            return withUpdatePlayer;
        }
        return participants;
    }

    private boolean wasStandingAtStateEntry(AbstractPlayableSprite player) {
        return player != null && standingPlayers.contains(player);
    }

    /**
     * State 2: Accelerating - platform accelerates toward destination.
     * ROM: loc_3BFD8 (s2.asm lines 80299-80327)
     */
    private void updateAccelerating(AbstractPlayableSprite player) {
        // ROM: move.w objoff_30(a0),d0 / add.w d0,x_vel(a0)
        xVel += accel;

        // ROM: jsrto JmpTo26_ObjectMove
        // ObjectMove: x_pos += x_vel (8.8 fixed point)
        subPixelX += xVel;
        currentX = subPixelX >> 8;

        // ROM: Check if reached destination
        // move.w objoff_32(a0),d0 / sub.w x_pos(a0),d0
        int delta = destX - currentX;
        // ROM: btst #status.npc.x_flip / beq.s + / neg.w d0
        if (xFlipped) {
            delta = -delta;
        }

        // ROM: tst.w d0 / bpl.s loc_3C034
        // bpl branches when delta >= 0, meaning platform has reached/overshot destination.
        // When delta < 0, platform hasn't reached destination yet -> sync standing players.
        if (delta >= 0) {
            // Reached destination - advance to returning state and launch players
            launchAtDestination(player);
        } else {
            // Still moving - sync standing players' positions
            syncStandingPlayers(player);
        }
    }

    /**
     * Transition to state 4 (returning), snap to destination, and launch standing players.
     * ROM: loc_3C034 (s2.asm lines 80336-80353)
     */
    private void launchAtDestination(AbstractPlayableSprite player) {
        // ROM: addq.b #2,routine_secondary(a0)
        state = STATE_RETURNING;

        // ROM: move.w objoff_32(a0),x_pos(a0) - snap to destination
        currentX = destX;
        subPixelX = destX << 8;

        // ROM: Check standing_mask and launch each standing player
        forEachStandingPlayer(player, this::launchPlayerIfStanding);
        standingPlayers.clear();
        accelerationRiders.clear();
    }

    /**
     * Launch a player if they are standing on this platform.
     * ROM: loc_3C020 (s2.asm lines 80329-80333)
     */
    private void launchPlayerIfStanding(AbstractPlayableSprite player) {
        if (!wasStandingAtStateEntry(player) && !accelerationRiders.contains(player)) {
            return;
        }

        // ROM: move.w x_vel(a0),x_vel(a1) - copy platform velocity to player
        player.setXSpeed((short) xVel);
        // ROM: move.w #-$400,y_vel(a1) - upward launch
        player.setYSpeed((short) LAUNCH_Y_VELOCITY);
        // ROM: bset #status.player.in_air,status(a1)
        player.setAir(true);
    }

    /**
     * Sync standing players' positions during acceleration phase.
     * ROM: loc_3BFB4 (s2.asm lines 80287-80296)
     * <p>
     * Clears player inertia and X velocity, moves player to platform X,
     * and sets player facing direction opposite to platform movement.
     */
    private void syncStandingPlayers(AbstractPlayableSprite player) {
        // The SolidContacts system handles keeping the player on the platform surface.
        // The ROM explicitly syncs player X to platform X and clears their velocity.
        forEachStandingPlayer(player, this::syncPlayer);
    }

    /**
     * State 4: Returning - platform moves back to home position at 4px/frame.
     * ROM: loc_3C062 (s2.asm lines 80354-80373)
     */
    private void updateReturning() {
        // ROM: move.w x_pos(a0),d0
        int pos = currentX;

        // ROM: moveq #4,d1
        int step = RETURN_SPEED;

        // ROM: tst.w objoff_30(a0) / spl d2 / bmi.s + / neg.w d1
        // If acceleration is positive (xFlipped=true), step is -4 (move left toward home)
        // If acceleration is negative (xFlipped=false), step is +4 (move right toward home)
        boolean accelPositive = accel > 0;
        if (!accelPositive) {
            // accel is negative (not flipped), bmi branches, skip neg.
            // step stays +4 (move right toward home)
        } else {
            // accel is positive (flipped), bmi doesn't branch
            // neg.w d1 -> step = -4 (move left toward home)
            step = -step;
        }

        // ROM: add.w d1,d0
        pos += step;

        // ROM: cmp.w objoff_34(a0),d0 / bhs.s +
        // Check if we've reached or passed home (unsigned comparison)
        boolean reachedHome;
        if (accelPositive) {
            // Moving left (step=-4): reached home when pos <= homeX
            // ROM uses unsigned cmp: bhs when pos >= home (unsigned)
            // For the case where pos decreases past home:
            // d2 starts as 0xFF (spl set because accel positive)
            // If pos >= home (unsigned bhs): skip not.b, d2 stays 0xFF -> bne continues
            // If pos < home: not.b d2 -> 0x00 -> don't bne -> reset
            reachedHome = (pos & 0xFFFF) < (homeX & 0xFFFF);
        } else {
            // Moving right (step=+4): reached home when pos >= homeX
            // d2 starts as 0x00 (spl clear because accel negative)
            // If pos >= home (unsigned bhs): skip not.b, d2 stays 0x00 -> don't bne -> reset
            // If pos < home: not.b d2 -> 0xFF -> bne continues
            reachedHome = (pos & 0xFFFF) >= (homeX & 0xFFFF);
        }

        if (reachedHome) {
            // ROM: clr.b routine_secondary(a0) / move.w objoff_34(a0),d0
            state = STATE_IDLE;
            pos = homeX;
            xVel = 0;
        }

        // ROM: move.w d0,x_pos(a0)
        currentX = pos;
        subPixelX = pos << 8;
    }

    // ========================================================================
    // SolidObjectProvider / SolidObjectListener
    // ========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return solidParams;
    }

    @Override
    public boolean isTopSolidOnly() {
        // PlatformObject is top-solid only (player can only stand on top)
        return true;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!contact.standing()) {
            return;
        }

        switch (state) {
            case STATE_IDLE, STATE_ACCELERATING -> standingPlayers.add(player);
        }
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (state == STATE_ACCELERATING) {
            return;
        }
        standingPlayers.remove(player);
    }

    /**
     * Player just started standing on the platform while idle.
     * ROM: loc_3BF66 (s2.asm lines 80259-80284)
     * <p>
     * Triggers the catapult: sets initial velocity, acceleration, and syncs player.
     */
    private void onStandingIdle(AbstractPlayableSprite player) {
        // ROM: addq.b #2,routine_secondary(a0)
        state = STATE_ACCELERATING;
        accelerationRiders.clear();

        // ROM: move.w #$C00,x_vel(a0)
        xVel = INITIAL_X_VELOCITY;

        // ROM: btst #status.npc.x_flip / bne.s + / neg.w x_vel(a0)
        if (!xFlipped) {
            xVel = -xVel;
        }

        // ROM: jsrto JmpTo26_ObjectMove - first movement step
        subPixelX += xVel;
        currentX = subPixelX >> 8;

        // ROM: Sync both standing players (loc_3BFB4)
        syncPlayer(player);

        for (PlayableEntity participant : playerParticipants(player)) {
            if (participant != player && participant.isOnObject()) {
                syncPlayer((AbstractPlayableSprite) participant);
            }
        }
    }

    /**
     * Sync player to platform position during movement.
     * ROM: loc_3BFB4 (s2.asm lines 80287-80296)
     */
    private void syncPlayer(AbstractPlayableSprite player) {
        // ROM: clr.w inertia(a1) / clr.w x_vel(a1)
        player.setGSpeed((short) 0);
        player.setXSpeed((short) 0);

        // ROM: move.w x_pos(a0),x_pos(a1)
        player.setCentreXPreserveSubpixel((short) currentX);
        accelerationRiders.add(player);
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            objectManager.refreshRidingTrackingPosition(this);
        }

        // ROM: Set player facing direction
        // bclr #status.player.x_flip,status(a1) - clear flip
        // btst #status.npc.x_flip,status(a0)
        // bne.s + (if object IS flipped, leave player flip cleared = face right)
        // bset #status.player.x_flip,status(a1) (if object NOT flipped, set flip = face left)
        if (!xFlipped) {
            // Object not flipped -> player faces left (toward movement direction)
            player.setDirection(Direction.LEFT);
        } else {
            // Object flipped -> player faces right (toward movement direction)
            player.setDirection(Direction.RIGHT);
        }
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.WFZ_LAUNCH_CATAPULT);
        if (renderer == null) return;

        // ROM: ObjC0_MapUnc_3C098 - single frame (frame 0)
        // SubObjData: render_flags.level_fg set, priority 4
        renderer.drawFrameIndex(0, currentX, spawn.y(), xFlipped, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw platform solid collision box
        ctx.drawRect(currentX, spawn.y(), PLATFORM_HALF_WIDTH, PLATFORM_HALF_HEIGHT_GROUND,
                0.0f, 1.0f, 0.5f);

        // Draw home position marker
        ctx.drawRect(homeX, spawn.y(), 2, 2, 1.0f, 1.0f, 0.0f);

        // Draw destination position marker
        ctx.drawRect(destX, spawn.y(), 2, 2, 1.0f, 0.0f, 0.0f);

        // Draw travel line
        int minX = Math.min(homeX, destX);
        int maxX = Math.max(homeX, destX);
        ctx.drawRect((minX + maxX) / 2, spawn.y() + PLATFORM_HALF_HEIGHT_GROUND + 2,
                (maxX - minX) / 2, 1, 0.5f, 0.5f, 0.5f);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: SubObjData priority = 4
        return RenderPriority.clamp(4);
    }
}
