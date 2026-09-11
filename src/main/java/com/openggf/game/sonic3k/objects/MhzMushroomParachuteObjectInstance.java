package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K SKL object $12 - MHZ mushroom parachute.
 *
 * <p>ROM reference: {@code Obj_MHZMushroomParachute}. This ports the route
 * critical player grab/carry/release path from {@code sub_3F5AA/sub_3F5C2}
 * plus the falling motion setup from {@code loc_3F500 -> loc_3F51C}.
 */
public final class MhzMushroomParachuteObjectInstance extends AbstractObjectInstance
        implements MhzUpdraftCarrier, SpawnRewindRecreatable {
    private static final int PLAYER_Y_OFFSET = 0x25;
    private static final int GRAB_X_BIAS = 0x10;
    private static final int GRAB_X_RANGE = 0x20;
    private static final int GRAB_Y_RANGE = 0x18;
    private static final int INITIAL_GROUND_VELOCITY = 0x0200;
    private static final int INITIAL_FALL_Y_VELOCITY = 0x0300;
    private static final int TARGET_Y_VELOCITY = 0x0080;
    private static final int Y_VELOCITY_STEP = 0x0020;
    private static final int RELEASE_X_VELOCITY = 0x0200;
    private static final int RELEASE_Y_VELOCITY = -0x0380;
    private static final int INVALID_RELEASE_Y_VELOCITY = -0x0100;
    private static final int OFFSCREEN_SENTINEL_X = 0x7F00;
    private static final int SHORT_RELEASE_COOLDOWN = 0x12;
    private static final int LONG_RELEASE_COOLDOWN = 0x3C;
    private static final int ROLL_X_RADIUS = 7;
    private static final int ROLL_Y_RADIUS = 0x0E;
    private static final int WALL_SENSOR_X_RADIUS = 0x20;
    private static final int[] CARRIED_PLAYER_FRAMES = {
            0xE4, 0xE5, 0xE6, 0xE6, 0xE7, 0xE6, 0xE6, 0xE5,
            0xE4, 0xE5, 0xE6, 0xE6, 0xE7, 0xE6, 0xE6, 0xE5
    };

    private final SubpixelMotion.State motion;
    private int angle;
    private int groundVelocity = INITIAL_GROUND_VELOCITY;
    private int releaseCooldown;
    private int nativeP2ReleaseCooldown;
    private int carriedXVelocity;
    private int carriedYVelocity;
    private int priorityBucket = 5;
    private boolean grabbed;
    private boolean nativeP2Grabbed;
    private AbstractPlayableSprite grabbedPlayer;
    private AbstractPlayableSprite nativeP2GrabbedPlayer;
    private boolean falling;

    public MhzMushroomParachuteObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZMushroomParachute");
        this.angle = (spawn.subtype() & 0xFF) == 0 ? 0 : 0x80;
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        ObjectPlayerQuery query = playerQuery(playerEntity);
        AbstractPlayableSprite nativeP2 = query.nativeP2OrNull() instanceof AbstractPlayableSprite sprite
                ? sprite
                : nativeP2GrabbedPlayer;
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite ? sprite : grabbedPlayer;
        boolean wasGrabbed = grabbed;
        updatePlayerGrab(nativeP2, PlayerSlot.NATIVE_P2);
        updatePlayerGrab(player, PlayerSlot.P1);
        if (!wasGrabbed && grabbed && !falling) {
            falling = true;
            motion.yVel = INITIAL_FALL_Y_VELOCITY;
            updateDynamicSpawn(motion.x, motion.y);
            return;
        }
        if (falling) {
            updateParachuteMotion();
            snapGrabbedPlayersToParachute();
            storeCarriedVelocity();
            handleFallingOffscreenCleanup();
        }
        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PARACHUTE);
        if (renderer != null) {
            renderer.drawFrameIndex(0, motion.x, motion.y, false, false);
        }
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " angle=" + angle
                + " xVel=" + motion.xVel
                + " yVel=" + motion.yVel
                + " grabbed=" + grabbed
                + " nativeP2Grabbed=" + nativeP2Grabbed
                + " cooldown=" + releaseCooldown;
    }

    private void updatePlayerGrab(AbstractPlayableSprite player, PlayerSlot slot) {
        if (isGrabbed(slot)) {
            updateGrabbedPlayer(slot);
            return;
        }
        int cooldown = releaseCooldown(slot);
        if (cooldown > 0) {
            setReleaseCooldown(slot, cooldown - 1);
            return;
        }
        if (player == null || !isInGrabWindow(player)) {
            return;
        }
        grabPlayer(player, slot);
    }

    private boolean isInGrabWindow(AbstractPlayableSprite player) {
        int dx = (player.getCentreX() & 0xFFFF) - motion.x + GRAB_X_BIAS;
        int dy = (player.getCentreY() & 0xFFFF) - motion.y - PLAYER_Y_OFFSET;
        return dx >= 0 && dx < GRAB_X_RANGE
                && dy >= 0 && dy < GRAB_Y_RANGE
                && !player.isObjectControlled()
                && !player.isHurt()
                && !player.getDead()
                && !player.isDebugMode()
                && player.getYSpeed() > 0;
    }

    private void grabPlayer(AbstractPlayableSprite player, PlayerSlot slot) {
        setGrabbedPlayer(slot, player);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, motion.x);
        NativePositionOps.writeYPosPreserveSubpixel(player, motion.y + PLAYER_Y_OFFSET);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        player.setSpindash(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setObjectMappingFrameControl(true);
        player.setHighPriority(false);
        player.setOnObject(false);
        player.setLatchedSolidObject(spawn.objectId(), this);
        priorityBucket = 1;
        syncPlayerVelocity(player);
        syncCarriedPlayerFrameAndFacing(player);
        ObjectServices objectServices = tryServices();
        if (objectServices != null) {
            objectServices.playSfx(Sonic3kSfx.GRAB.id);
        }
    }

    private void updateGrabbedPlayer(PlayerSlot slot) {
        AbstractPlayableSprite player = grabbedPlayer(slot);
        if (player == null) {
            releaseGrabbedPlayer(slot, null, false);
            return;
        }
        if (player.getDead() || player.isDebugMode()) {
            player.setYSpeed((short) INVALID_RELEASE_Y_VELOCITY);
            releaseGrabbedPlayer(slot, player, false);
            return;
        }
        if (player.hasRenderFlagOnScreenState() && !player.isRenderFlagOnScreen()) {
            player.setYSpeed((short) INVALID_RELEASE_Y_VELOCITY);
            releaseGrabbedPlayer(slot, player, false);
            return;
        }
        if (!carriedVelocityMatchesParachute(player)) {
            player.setYSpeed((short) INVALID_RELEASE_Y_VELOCITY);
            releaseGrabbedPlayer(slot, player, false);
            return;
        }
        if (player.isJumpJustPressed()) {
            releaseGrabbedPlayer(slot, player, true);
            return;
        }
        snapGrabbedPlayerToParachute(player);
    }

    private void releaseGrabbedPlayer(PlayerSlot slot, AbstractPlayableSprite player, boolean jumpRelease) {
        if (player != null) {
            ObjectControlState.none().applyTo(player);
            player.setObjectMappingFrameControl(false);
            player.setLatchedSolidObjectId(0);
            if (jumpRelease) {
                if (player.isLeftPressed()) {
                    player.setXSpeed((short) -RELEASE_X_VELOCITY);
                } else if (player.isRightPressed()) {
                    player.setXSpeed((short) RELEASE_X_VELOCITY);
                }
                player.setYSpeed((short) RELEASE_Y_VELOCITY);
                player.setAir(true);
                player.setJumping(true);
                player.setRolling(true);
                player.applyCustomRadii(ROLL_X_RADIUS, ROLL_Y_RADIUS);
                player.setAnimationId(Sonic3kAnimationIds.ROLL);
                setReleaseCooldown(slot, hasDirectionalInput(player) ? LONG_RELEASE_COOLDOWN : SHORT_RELEASE_COOLDOWN);
            } else {
                setReleaseCooldown(slot, LONG_RELEASE_COOLDOWN);
            }
        }
        clearGrabbedPlayer(slot);
    }

    private static boolean hasDirectionalInput(AbstractPlayableSprite player) {
        return player.isUpPressed() || player.isDownPressed() || player.isLeftPressed() || player.isRightPressed();
    }

    private boolean carriedVelocityMatchesParachute(AbstractPlayableSprite player) {
        return player.getXSpeed() == (short) carriedXVelocity
                && player.getYSpeed() == (short) carriedYVelocity;
    }

    private void updateParachuteMotion() {
        updateAngle();
        int cosine = TrigLookupTable.cosHex(angle);
        motion.xVel = (cosine * groundVelocity) >> 8;
        if (motion.yVel >= TARGET_Y_VELOCITY) {
            motion.yVel -= Y_VELOCITY_STEP;
        } else {
            motion.yVel += Y_VELOCITY_STEP;
        }
        SubpixelMotion.moveSprite2(motion);
        resolveWallCollision();
    }

    /**
     * Port of ROM {@code sub_3F7AE}: two horizontal terrain sensors at the parachute's
     * left/right x_radius ($20) that push x_pos out of a wall. Runs after
     * {@link SubpixelMotion#moveSprite2} applies the frame's velocity, per
     * {@code loc_3F51C} (sub_3F7E2 -> MoveSprite2 -> sub_3F7AE -> sub_3F5AA).
     */
    private void resolveWallCollision() {
        TerrainCheckResult leftWall = ObjectTerrainUtils.checkLeftWallDist(motion.x - WALL_SENSOR_X_RADIUS, motion.y);
        if (leftWall.foundSurface() && leftWall.distance() < 0) {
            // ROM: sub.w d1,x_pos(a0) — a negative left-wall distance pushes x_pos away from
            // the wall (increases it), unlike the right-wall branch below.
            motion.x -= leftWall.distance();
        }
        TerrainCheckResult rightWall = ObjectTerrainUtils.checkRightWallDist(motion.x + WALL_SENSOR_X_RADIUS, motion.y);
        if (rightWall.foundSurface() && rightWall.distance() < 0) {
            motion.x += rightWall.distance();
        }
    }

    private void storeCarriedVelocity() {
        carriedXVelocity = motion.xVel;
        carriedYVelocity = motion.yVel;
    }

    private void handleFallingOffscreenCleanup() {
        if (isOnScreen()) {
            return;
        }
        motion.x = OFFSCREEN_SENTINEL_X;
        if (grabbedPlayer != null) {
            ObjectControlState.none().applyTo(grabbedPlayer);
            grabbedPlayer.setObjectMappingFrameControl(false);
            grabbedPlayer.setLatchedSolidObjectId(0);
        }
        if (nativeP2GrabbedPlayer != null) {
            ObjectControlState.none().applyTo(nativeP2GrabbedPlayer);
            nativeP2GrabbedPlayer.setObjectMappingFrameControl(false);
            nativeP2GrabbedPlayer.setLatchedSolidObjectId(0);
        }
        grabbed = false;
        grabbedPlayer = null;
        nativeP2Grabbed = false;
        nativeP2GrabbedPlayer = null;
    }

    private void updateAngle() {
        int next = angle;
        if (grabbedPlayer != null && grabbedPlayer.isLeftPressed() && next != 0x80) {
            // ROM sub_3F7E2 (loc_3F800): force the angle non-negative before +2.
            if ((byte) next < 0) {
                next = (-next) & 0xFF;
            }
            next = (next + 2) & 0xFF;
        } else if (grabbedPlayer != null && grabbedPlayer.isRightPressed() && next != 0) {
            // ROM sub_3F7E2 (loc_3F814): force the angle non-positive before +2.
            if ((byte) next >= 0) {
                next = (-next) & 0xFF;
            }
            next = (next + 2) & 0xFF;
        } else {
            int absolute = next & 0x7F;
            if (absolute != 0) {
                next = (next + 2) & 0xFF;
            }
        }
        angle = next;
    }

    /** Test seam: runs one steering tick without the full falling-frame update. */
    void tickSteeringForTest() {
        updateAngle();
    }

    /** Test seam: reads the current steering angle byte. */
    int angleForTest() {
        return angle;
    }

    private void snapGrabbedPlayersToParachute() {
        snapGrabbedPlayerToParachute(nativeP2GrabbedPlayer);
        snapGrabbedPlayerToParachute(grabbedPlayer);
    }

    private void snapGrabbedPlayerToParachute(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        NativePositionOps.writeXPosPreserveSubpixel(player, motion.x);
        NativePositionOps.writeYPosPreserveSubpixel(player, motion.y + PLAYER_Y_OFFSET);
        syncPlayerVelocity(player);
        syncCarriedPlayerFrameAndFacing(player);
    }

    private void syncPlayerVelocity(AbstractPlayableSprite player) {
        player.setXSpeed((short) motion.xVel);
        player.setYSpeed((short) motion.yVel);
    }

    private void syncCarriedPlayerFrameAndFacing(AbstractPlayableSprite player) {
        boolean facingLeft = (byte) ((angle + 0x40) & 0xFF) < 0;
        player.setDirection(facingLeft ? Direction.LEFT : Direction.RIGHT);
        player.setRenderFlips(facingLeft, false);
        player.setMappingFrame(CARRIED_PLAYER_FRAMES[(angle >> 4) & 0xF]);
    }

    @Override
    public boolean isCarrying(AbstractPlayableSprite player) {
        return falling && ((grabbed && grabbedPlayer == player)
                || (nativeP2Grabbed && nativeP2GrabbedPlayer == player));
    }

    @Override
    public void moveByUpdraftLift(int lift) {
        motion.y += lift;
        updateDynamicSpawn(motion.x, motion.y);
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity playerEntity) {
        ObjectServices objectServices = tryServices();
        if (objectServices == null) {
            return new ObjectPlayerQuery(() -> playerEntity, List::of);
        }
        ObjectPlayerQuery query = objectServices.playerQuery();
        return new ObjectPlayerQuery(() -> playerEntity, query::sidekicks);
    }

    private boolean isGrabbed(PlayerSlot slot) {
        return slot == PlayerSlot.P1 ? grabbed : nativeP2Grabbed;
    }

    private AbstractPlayableSprite grabbedPlayer(PlayerSlot slot) {
        return slot == PlayerSlot.P1 ? grabbedPlayer : nativeP2GrabbedPlayer;
    }

    private void setGrabbedPlayer(PlayerSlot slot, AbstractPlayableSprite player) {
        if (slot == PlayerSlot.P1) {
            grabbed = true;
            grabbedPlayer = player;
        } else {
            nativeP2Grabbed = true;
            nativeP2GrabbedPlayer = player;
        }
    }

    private void clearGrabbedPlayer(PlayerSlot slot) {
        if (slot == PlayerSlot.P1) {
            grabbed = false;
            grabbedPlayer = null;
        } else {
            nativeP2Grabbed = false;
            nativeP2GrabbedPlayer = null;
        }
    }

    private int releaseCooldown(PlayerSlot slot) {
        return slot == PlayerSlot.P1 ? releaseCooldown : nativeP2ReleaseCooldown;
    }

    private void setReleaseCooldown(PlayerSlot slot, int value) {
        if (slot == PlayerSlot.P1) {
            releaseCooldown = value;
        } else {
            nativeP2ReleaseCooldown = value;
        }
    }

    private enum PlayerSlot {
        NATIVE_P2,
        P1
    }
}
