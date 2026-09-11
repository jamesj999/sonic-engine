package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindStateful;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K S3KL object $17 - Launch Base ride grapple.
 *
 * <p>ROM reference: {@code Obj_LBZRideGrapple} (sonic3k.asm:52124-52531).
 * The ROM allocates a child multisprite for the chain/handle; this engine
 * instance keeps those child coordinates as local state because the child only
 * renders and supplies the held-player handle position.
 */
public final class LbzRideGrappleInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int ROM_CHILD_SLOT_COUNT = 1;
    private static final int[][] PATH_RANGES = {
            {0x0A08, 0x0C78},
            {0x1208, 0x14F8},
            {0x1A08, 0x1BB8},
            {0x1C48, 0x2078},
            {0x2688, 0x2878},
            {0x2988, 0x2DF8},
            {0x2F88, 0x3178},
            {0x0E68, 0x1098},
            {0x0CE8, 0x1498},
            {0x0E68, 0x1398},
            {0x20E8, 0x2418},
            {0x2B08, 0x2E98},
            {0x39E8, 0x3C98}
    };

    private static final int CHAIN_POINT_COUNT = 6;
    private static final int CHAIN_EXTENSION_MAX = 0x28;
    private static final int CHAIN_LENGTH_STEP = 0x33;
    private static final int SWAY_ACCEL = 0x40;
    private static final int SWAY_DRIVEN_STEP = 0x180;
    private static final int SWAY_DRIVEN_LIMIT = 0x3000;
    private static final int X_ACCEL = 0x20;
    private static final int X_ACCEL_CROSS_ZERO_BONUS = 0x60;
    private static final int GRAB_X_BIAS = 0x10;
    private static final int GRAB_X_RANGE = 0x20;
    private static final int GRAB_Y_BIAS = 0x18;
    private static final int GRAB_Y_RANGE = 0x18;
    private static final int PLAYER_HANG_Y_OFFSET = 0x24;
    private static final int RELEASE_SHORT_COOLDOWN = 0x12;
    private static final int RELEASE_LONG_COOLDOWN = 0x3C;
    private static final int RELEASE_Y_VELOCITY = -0x0380;
    private static final int ROLL_X_RADIUS = 7;
    private static final int ROLL_Y_RADIUS = 0x0E;
    private static final int[] PLAYER_FRAMES = {
            0x91, 0x91, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x92, 0x92, 0x92, 0x92, 0x92, 0x92, 0x91, 0x91
    };

    private final SubpixelMotion.State motion;
    private int originalX;
    private int pathLeft;
    private int pathRight;
    private boolean ejectAtPathEnd;
    private final PlayerState p1 = new PlayerState();
    private final PlayerState p2 = new PlayerState();
    private final int[] chainX = new int[CHAIN_POINT_COUNT];
    private final int[] chainY = new int[CHAIN_POINT_COUNT];

    private int handleX;
    private int handleY;
    private int chainExtension;
    private int angle;
    private int swayVelocity;
    private boolean swayReturning;
    private boolean moving;
    private boolean childSlotReserved;
    private Direction facing = Direction.RIGHT;

    public LbzRideGrappleInstance(ObjectSpawn spawn) {
        super(spawn, "LBZRideGrapple");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.originalX = spawn.x();
        int pathIndex = Math.min(spawn.subtype() & 0x7F, PATH_RANGES.length - 1);
        this.pathLeft = PATH_RANGES[pathIndex][0];
        this.pathRight = PATH_RANGES[pathIndex][1];
        this.ejectAtPathEnd = (spawn.subtype() & 0x80) == 0;
        updateChainCoordinates();
        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        reserveRomChildSlot();
        AbstractPlayableSprite player1 = playerEntity instanceof AbstractPlayableSprite sprite ? sprite : null;
        AbstractPlayableSprite player2 = nativeP2OrNull();

        if (moving) {
            updateMovement(player1, player2);
        }
        updateChainState();

        updateHeldOrCapture(p2, player2);
        updateHeldOrCapture(p1, player1);

        if (!moving && anyGrabbed() && chainExtension == CHAIN_EXTENSION_MAX) {
            moving = true;
        }
        updateDynamicSpawn(motion.x, motion.y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_RIDE_GRAPPLE);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, motion.x, motion.y, false, false);
        for (int i = 0; i < CHAIN_POINT_COUNT; i++) {
            renderer.drawFrameIndex(1, chainX[i], chainY[i], false, false);
        }
        renderer.drawFrameIndex(2, handleX, handleY, false, false);
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x20;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // loc_26588/loc_265A2 delete only when both the moving x_pos and the
        // saved initial x_pos at $38 are outside the coarse $280 window.
        return isOutsideCoarseRange(motion.x, cameraX)
                && isOutsideCoarseRange(originalX, cameraX);
    }

    @Override
    public int getReservedChildSlotCount() {
        return ROM_CHILD_SLOT_COUNT;
    }

    public int getPathLeftForTest() {
        return pathLeft;
    }

    public int getPathRightForTest() {
        return pathRight;
    }

    public boolean isNativeGrabbedForTest(int nativePlayerIndex) {
        return stateFor(nativePlayerIndex).grabbed;
    }

    public int getReleaseCooldownForTest(int nativePlayerIndex) {
        return stateFor(nativePlayerIndex).releaseCooldown;
    }

    private void updateMovement(AbstractPlayableSprite player1, AbstractPlayableSprite player2) {
        SubpixelMotion.moveSprite2(motion);
        if (facing == Direction.LEFT) {
            motion.xVel = signWord(motion.xVel - X_ACCEL);
            if (motion.xVel >= 0) {
                motion.xVel = signWord(motion.xVel - X_ACCEL_CROSS_ZERO_BONUS);
            }
        } else {
            motion.xVel = signWord(motion.xVel + X_ACCEL);
            if (motion.xVel < 0) {
                motion.xVel = signWord(motion.xVel + X_ACCEL_CROSS_ZERO_BONUS);
            }
        }

        if ((motion.x & 0xFFFF) <= pathLeft) {
            motion.x = pathLeft;
            ejectGrabbedAtPathEnd(player1, player2);
            if (motion.xVel < 0) {
                motion.xVel = 0;
            }
        }
        if ((motion.x & 0xFFFF) >= pathRight) {
            motion.x = pathRight;
            ejectGrabbedAtPathEnd(player1, player2);
            if (motion.xVel > 0) {
                motion.xVel = 0;
            }
        }
    }

    private void updateChainState() {
        if (anyGrabbed()) {
            if (chainExtension != CHAIN_EXTENSION_MAX) {
                chainExtension++;
            }
        } else {
            if (chainExtension != 0) {
                chainExtension--;
            }
            if (chainExtension == 0) {
                // loc_2684C runs on every zero-length dispatch, not only the
                // frame where a retracting chain reaches zero.
                angle = 0;
                swayReturning = false;
                swayVelocity = 0;
                motion.xVel = 0;
                moving = false;
            }
        }

        if (motion.xVel == 0) {
            updateIdleSway();
        } else {
            updateDrivenSway();
        }
        updateChainCoordinates();
    }

    private void updateIdleSway() {
        if (!swayReturning) {
            swayVelocity = signWord(swayVelocity + SWAY_ACCEL);
            angle = signWord(angle + swayVelocity);
            if (angle >= 0) {
                swayReturning = true;
            }
            return;
        }
        swayVelocity = signWord(swayVelocity - SWAY_ACCEL);
        angle = signWord(angle + swayVelocity);
        if (angle < 0) {
            swayReturning = false;
        }
    }

    private void updateDrivenSway() {
        int targetDelta = signWord((-motion.xVel << 2) - angle);
        if (targetDelta < 0 && angle > -SWAY_DRIVEN_LIMIT) {
            angle = signWord(angle - SWAY_DRIVEN_STEP);
        } else if (angle < SWAY_DRIVEN_LIMIT) {
            angle = signWord(angle + SWAY_DRIVEN_STEP);
        }
        swayReturning = motion.xVel < 0;
        swayVelocity = 0;
    }

    private void updateChainCoordinates() {
        int linkLength = chainExtension * CHAIN_LENGTH_STEP;
        int angleByte = angleAccumulatorByte();
        int sin = TrigLookupTable.sinHex(angleByte);
        int cos = TrigLookupTable.cosHex(angleByte);
        // sub_2682E copies the parent's complete 16.16 x_pos/y_pos longs before
        // accumulating the sine/cosine deltas. SubpixelMotion stores the same
        // fraction as the high byte of the native low word.
        int x = (motion.x << 16) | ((motion.xSub & 0xFF) << 8);
        int y = (motion.y << 16) | ((motion.ySub & 0xFF) << 8);
        int dx = sin * linkLength;
        int dy = cos * linkLength;
        for (int i = 0; i < CHAIN_POINT_COUNT; i++) {
            chainX[i] = (x >> 16) & 0xFFFF;
            chainY[i] = (y >> 16) & 0xFFFF;
            x += dx;
            y += dy;
        }
        handleX = chainX[CHAIN_POINT_COUNT - 1];
        handleY = chainY[CHAIN_POINT_COUNT - 1];
    }

    private void updateHeldOrCapture(PlayerState state, AbstractPlayableSprite player) {
        if (state.grabbed) {
            updateHeldPlayer(state, player);
            return;
        }
        if (state.releaseCooldown > 0) {
            state.releaseCooldown--;
            return;
        }
        if (isInGrabWindow(player)) {
            capturePlayer(state, player);
        }
    }

    private void updateHeldPlayer(PlayerState state, AbstractPlayableSprite player) {
        if (player == null
                || player.getDead()
                || player.isHurt()
                || player.isDebugMode()
                || (player.hasRenderFlagOnScreenState() && !player.isRenderFlagOnScreen())) {
            // sub_266B0 tests render_flags bit 7 before input and immediately
            // clears object_control/the grab byte when the player is offscreen.
            releaseWithoutLaunch(state, player, RELEASE_LONG_COOLDOWN);
            return;
        }
        if (player.isJumpJustPressed()) {
            int cooldown = hasDirectionalInput(player) ? RELEASE_LONG_COOLDOWN : RELEASE_SHORT_COOLDOWN;
            launchFromJump(state, player, cooldown);
            return;
        }
        if (player.isLeftPressed()) {
            player.setDirection(Direction.LEFT);
        }
        if (player.isRightPressed()) {
            player.setDirection(Direction.RIGHT);
        }
        facing = player.getDirection();
        snapHeldPlayer(player);
        applyHeldPlayerFrame(player);
    }

    private boolean isInGrabWindow(AbstractPlayableSprite player) {
        if (player == null) {
            return false;
        }
        int dx = (player.getCentreX() & 0xFFFF) - (motion.x & 0xFFFF) + GRAB_X_BIAS;
        if (dx < 0 || dx >= GRAB_X_RANGE) {
            return false;
        }
        int dy = (player.getCentreY() & 0xFFFF) - (motion.y & 0xFFFF) - GRAB_Y_BIAS;
        return dy >= 0 && dy < GRAB_Y_RANGE
                && !player.isObjectControlled()
                && !player.isControlLocked()
                && !player.isHurt()
                && !player.getDead()
                && !player.isDebugMode();
    }

    private void capturePlayer(PlayerState state, AbstractPlayableSprite player) {
        facing = player.getDirection();
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        // loc_267B2 captures at the parent SST's x_pos/y_pos. Only the already
        // grabbed path at loc_2673E follows the child multisprite handle.
        NativePositionOps.writeXPosPreserveSubpixel(player, motion.x);
        NativePositionOps.writeYPosPreserveSubpixel(player, motion.y + PLAYER_HANG_Y_OFFSET);
        player.setAnimationId(Sonic3kAnimationIds.HANG2);
        player.setSpindash(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setObjectMappingFrameControl(true);
        applyHeldPlayerFrame(player);
        state.grabbed = true;
    }

    private void snapHeldPlayer(AbstractPlayableSprite player) {
        NativePositionOps.writeXPosPreserveSubpixel(player, handleX);
        NativePositionOps.writeYPosPreserveSubpixel(player, handleY + PLAYER_HANG_Y_OFFSET);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
    }

    private void applyHeldPlayerFrame(AbstractPlayableSprite player) {
        player.setRenderFlips(player.getDirection() == Direction.LEFT, false);
        int playerAngle = angleAccumulatorByte();
        if (player.getDirection() == Direction.LEFT) {
            playerAngle = (-playerAngle) & 0xFF;
        }
        int index = ((playerAngle + 8) & 0xFF) >>> 4;
        player.setMappingFrame(PLAYER_FRAMES[index]);
    }

    private void launchFromJump(PlayerState state, AbstractPlayableSprite player, int cooldown) {
        clearHeldState(state, player, cooldown);
        player.setXSpeed((short) motion.xVel);
        player.setYSpeed((short) RELEASE_Y_VELOCITY);
        player.setAir(true);
        player.setJumping(true);
        player.applyCustomRadii(ROLL_X_RADIUS, ROLL_Y_RADIUS);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setRolling(true);
    }

    private void releaseWithoutLaunch(PlayerState state, AbstractPlayableSprite player, int cooldown) {
        clearHeldState(state, player, cooldown);
    }

    private void ejectGrabbedAtPathEnd(AbstractPlayableSprite player1, AbstractPlayableSprite player2) {
        if (!ejectAtPathEnd) {
            return;
        }
        ejectPlayerAtPathEnd(p1, player1);
        ejectPlayerAtPathEnd(p2, player2);
    }

    private void ejectPlayerAtPathEnd(PlayerState state, AbstractPlayableSprite player) {
        if (!state.grabbed || player == null) {
            return;
        }
        clearHeldState(state, player, RELEASE_LONG_COOLDOWN);
        player.setXSpeed((short) motion.xVel);
        player.setYSpeed((short) 0);
        player.setAir(true);
    }

    private void clearHeldState(PlayerState state, AbstractPlayableSprite player, int cooldown) {
        state.grabbed = false;
        state.releaseCooldown = cooldown;
        if (player != null) {
            ObjectControlState.none().applyTo(player);
            player.setObjectMappingFrameControl(false);
            player.suppressNextJumpPress();
        }
    }

    private boolean anyGrabbed() {
        return p1.grabbed || p2.grabbed;
    }

    private void reserveRomChildSlot() {
        if (childSlotReserved || getSlotIndex() < 0) {
            return;
        }
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        childSlotReserved = true;
        // Obj_LBZRideGrapple allocates its loc_2668E multisprite helper with
        // AllocateObjectAfterCurrent during initialization. Rendering remains
        // parent-local here, but the helper's SST slot must stay occupied until
        // the parent unloads so later allocations see the native slot pressure.
        svc.objectManager().allocateChildSlotsAfter(
                spawn, ROM_CHILD_SLOT_COUNT, getSlotIndex());
    }

    private AbstractPlayableSprite nativeP2OrNull() {
        try {
            PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
            return nativeP2 instanceof AbstractPlayableSprite sprite ? sprite : null;
        } catch (IllegalStateException ignored) {
            return null;
        }
    }

    private PlayerState stateFor(int nativePlayerIndex) {
        return nativePlayerIndex == 0 ? p1 : p2;
    }

    private static boolean hasDirectionalInput(AbstractPlayableSprite player) {
        return player.isUpPressed() || player.isDownPressed() || player.isLeftPressed() || player.isRightPressed();
    }

    private static int signWord(int value) {
        return (short) value;
    }

    private static boolean isOutsideCoarseRange(int x, int cameraX) {
        int objectCoarse = x & 0xFF80;
        int cameraCoarseBack = (cameraX - 0x80) & 0xFF80;
        int distance = (objectCoarse - cameraCoarseBack) & 0xFFFF;
        return distance > 0x280;
    }

    private int angleAccumulatorByte() {
        // ROM: move.b angle(a0),d0 reads the high byte of this big-endian word accumulator.
        return (angle >> 8) & 0xFF;
    }

    private static final class PlayerState implements RewindStateful<PlayerState.Snapshot> {
        boolean grabbed;
        int releaseCooldown;

        @Override
        public Snapshot captureRewindStateValue() {
            return new Snapshot(grabbed, releaseCooldown);
        }

        @Override
        public void restoreRewindStateValue(Snapshot state) {
            grabbed = state.grabbed();
            releaseCooldown = state.releaseCooldown();
        }

        private record Snapshot(boolean grabbed, int releaseCooldown) {
        }
    }
}
