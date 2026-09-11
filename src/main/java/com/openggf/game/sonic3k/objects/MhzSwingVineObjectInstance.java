package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.PostPlayerUpdateHook;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

/**
 * S3K SKL object $10 - MHZ swing vine.
 *
 * <p>ROM reference: {@code Obj_MHZSwingVine} / {@code loc_2267E} through
 * {@code loc_22B68}. This models the route-critical handle grab, hang, and
 * jump-release behavior; visual child segments use the MHZ misc PLC art.
 */
public final class MhzSwingVineObjectInstance extends AbstractObjectInstance
        implements PostPlayerUpdateHook, SpawnRewindRecreatable {
    private static final int PRIORITY_BUCKET_LOW = 4; // priority $200
    private static final int PRIORITY_BUCKET_HIGH = 5; // priority $280
    private static final int HANDLE_Y_OFFSET = 0x10;
    private static final int PLAYER_HANG_Y_OFFSET = 0x14;
    private static final int GRAB_HALF_WIDTH = 0x10;
    private static final int GRAB_HEIGHT = 0x28;
    private static final int SLOW_GRAB_HEIGHT = 0x18;
    private static final int RELEASE_DELAY = 0x3C;

    private static final int[] MODE0_PLAYER_FRAMES = {
            0x91, 0x91, 0x90, 0x90, 0x90, 0x90, 0x90, 0x90,
            0x92, 0x92, 0x92, 0x92, 0x92, 0x92, 0x91, 0x91
    };

    private static final int[] MODE1_PLAYER_FRAMES = {
            0x78, 0x78, 0x7F, 0x7F, 0x7E, 0x7E, 0x7D, 0x7D,
            0x7C, 0x7C, 0x7B, 0x7B, 0x7A, 0x7A, 0x79, 0x79
    };

    private static final int[] MODE1_PLAYER_OFFSETS = {
            0, 0x18,
            -0x12, 0x13,
            -0x18, 0,
            -0x12, -0x13,
            0, -0x18,
            0x12, -0x13,
            0x18, 0,
            0x12, 0x13
    };

    private enum RootState {
        WAIT_FOR_GRAB,
        SWINGING,
        RETURNING
    }

    private static final class PlayerState {
        int grabFlag;
        int releaseDelay;
        boolean pendingJumpRelease;
        AbstractPlayableSprite player;
    }

    private final PlayerState p1 = new PlayerState();
    private final PlayerState p2 = new PlayerState();

    private RootState rootState = RootState.WAIT_FOR_GRAB;
    private int rootAngle;
    private int root3A;
    private int returnAccumulator;
    private int returnVelocity;
    private int returnDamping;
    private int returnPhase;
    private int handleMode;
    private int handleX;
    private int handleY;
    private int prevHandleX;
    private int prevHandleY;
    private int priorityBucket = PRIORITY_BUCKET_LOW;

    public MhzSwingVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZSwingVine");
        handleX = spawn.x();
        handleY = spawn.y() + HANDLE_Y_OFFSET;
        prevHandleX = handleX;
        prevHandleY = handleY;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Self-contained: rebuilds from the captured spawn (same path the deleted
     * dynamic restore path used). The object's own scalar fields are reapplied by the standard scalar-restore
     * pass (Phase-2 codec-deletion batch 2).
     */

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        updateRootState();
        updateHandlePosition();
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite playable ? playable : null;
        updatePlayer(p1, player);
        updatePlayer(p2, firstTrackedSidekick());
        updateDynamicSpawn(spawn.x(), spawn.y());
        requestForcedScrollWhileSwinging();
    }

    /**
     * ROM {@code loc_226F2} (sonic3k.asm:47072-47074): while the swing root
     * routine {@code loc_226B0} runs and Player 1 is grabbed ({@code $32(a1)} on
     * the handle), the vine writes {@code Scroll_force_positions} plus the vine's
     * own {@code x_pos}/{@code y_pos} into {@code Scroll_forced_X_pos}/{@code
     * Scroll_forced_Y_pos} so the camera tracks the vine anchor instead of the
     * hanging player for that frame. The setter lives in the swing routine, so it
     * does not fire during a stationary (mode-0) slow-grab hang.
     */
    private void requestForcedScrollWhileSwinging() {
        if (rootState != RootState.SWINGING || p1.grabFlag == 0) {
            return;
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        Camera camera = services.camera();
        if (camera != null) {
            camera.requestForcedScroll(spawn.x(), spawn.y());
        }
    }

    @Override
    public void updatePostPlayer(int frameCounter, PlayableEntity playerEntity) {
        releasePending(p1);
        releasePending(p2);
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 0x40;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return 0x40;
    }

    @Override
    public boolean isPersistent() {
        // loc_22824 applies the root's coarse-X tail even while either handle
        // grab byte is set (sonic3k.asm:47164-47192).
        return false;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        // The root x_pos is the fixed vine anchor and the ROM uses the native
        // Camera_X_pos_coarse_back/$280 range rather than a viewport margin.
        int coarseBack = (cameraX - 0x80) & 0xFF80;
        int distance = (((spawn.x() & 0xFFFF) & 0xFF80) - coarseBack) & 0xFFFF;
        return distance > 0x280;
    }

    @Override
    public void onUnload() {
        clearPlayerControl(p1);
        clearPlayerControl(p2);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_VINE);
        if (renderer == null) {
            return;
        }

        renderer.drawFrameIndex(0x23, spawn.x(), spawn.y(), false, false);
        renderer.drawFrameIndex(swingSegmentFrame(), spawn.x(), spawn.y(), false, false);
        renderer.drawFrameIndex(0x22, handleX, handleY, false, false);
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " state=" + rootState
                + " angle=$" + Integer.toHexString(rootAngle & 0xFFFF).toUpperCase()
                + " handleMode=" + handleMode
                + " grabbed=" + (p1.grabFlag != 0 || p2.grabFlag != 0);
    }

    private void updateRootState() {
        if (rootState == RootState.WAIT_FOR_GRAB && anyFastGrabbed()) {
            rootState = RootState.SWINGING;
            handleMode = 1;
            rootAngle = 0;
            root3A = 0x800;
            return;
        }
        if (rootState == RootState.RETURNING) {
            if (anyFastGrabbed()) {
                rootState = RootState.SWINGING;
                handleMode = 1;
                root3A = 0x800;
                returnDamping = 0;
                returnVelocity = 0;
                returnAccumulator = 0;
                returnPhase = 0;
            } else {
                updateReturningState();
            }
            return;
        }
        if (rootState != RootState.SWINGING) {
            return;
        }

        int d0 = root3A;
        int d1 = Math.abs(signedAngleByte(rootAngle));
        d0 -= d1 + d1;
        rootAngle = asSigned16(rootAngle - d0);
        priorityBucket = signedAngleByte(rootAngle) < 0 ? PRIORITY_BUCKET_HIGH : PRIORITY_BUCKET_LOW;
        // ROM loc_226C2 (asm 47056-47061): every SWINGING tick plays sfx_GroundSlide
        // once the updated angle byte lands in the $40-$47 band.
        if ((angleByte(rootAngle) & 0xF8) == 0x40) {
            playSfx(Sonic3kSfx.GROUND_SLIDE.id);
        }
        if (!anyGrabbed() && (((angleByte(rootAngle) + 8) & 0xFF) < 0x10)) {
            rootState = RootState.RETURNING;
            handleMode = 2;
            returnAccumulator = 0;
            returnVelocity = -0x300;
            returnDamping = 0x1000;
            returnPhase = 0;
        }
    }

    private void updateReturningState() {
        int step = (returnDamping >> 8) & 0xFF;
        int velocity = returnVelocity;
        boolean crossed;
        if (returnPhase == 0) {
            velocity += step;
            returnVelocity = velocity;
            returnAccumulator = asSigned16(returnAccumulator + velocity);
            crossed = returnAccumulator >= 0;
            if (crossed) {
                returnPhase = 1;
            }
        } else {
            velocity -= step;
            returnVelocity = velocity;
            returnAccumulator = asSigned16(returnAccumulator + velocity);
            crossed = returnAccumulator <= 0;
            if (crossed) {
                returnPhase = 0;
            }
        }

        if (crossed) {
            returnVelocity = asSigned16(returnVelocity - (returnVelocity >> 4));
            if (returnDamping == 0x0C00) {
                rootState = RootState.WAIT_FOR_GRAB;
                handleMode = 0;
                rootAngle = 0;
                root3A = 0;
                returnAccumulator = 0;
                returnVelocity = 0;
                returnDamping = 0;
                returnPhase = 0;
                return;
            }
            returnDamping = Math.max(0x0C00, returnDamping - 0x40);
        }

        rootAngle = returnAccumulator;
        root3A = returnAccumulator >> 3;
        priorityBucket = returnAccumulator < 0 ? PRIORITY_BUCKET_HIGH : PRIORITY_BUCKET_LOW;
    }

    private void updateHandlePosition() {
        int oldX = handleX;
        int oldY = handleY;
        int angle = (angleByte(rootAngle) + 4) & 0xF8;
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);

        handleX = spawn.x() + ((-sin + 8) >> 4);
        handleY = spawn.y() + ((cos + 8) >> 4);
        if (handleX != oldX) {
            prevHandleX = oldX;
        }
        if (handleY != oldY) {
            prevHandleY = oldY;
        }
    }

    private void updatePlayer(PlayerState state, AbstractPlayableSprite player) {
        state.player = player;
        if (state.grabFlag != 0) {
            updateGrabbedPlayer(state, player);
            return;
        }
        if (state.releaseDelay > 0) {
            state.releaseDelay--;
            if (state.releaseDelay > 0) {
                return;
            }
        }
        if (player == null || !canGrab(player)) {
            return;
        }

        boolean fastGrab = player.getXSpeed() >= 0x400;
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, handleX);
        NativePositionOps.writeYPosPreserveSubpixel(player, handleY + PLAYER_HANG_Y_OFFSET);
        player.setAnimationId(Sonic3kAnimationIds.HANG2);
        player.setForcedAnimationId(Sonic3kAnimationIds.HANG2);
        player.setObjectMappingFrameControl(true);
        player.setSpindash(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        if (player.isCpuControlled()) {
            player.setControlLocked(true);
        }
        player.setRenderFlips(player.getDirection() == Direction.LEFT, false);
        state.grabFlag = fastGrab ? 0x81 : 1;
        // ROM loc_22B3C/22B06 (asm 47472-47494): the slow-grab path returns via the
        // object_control(a1) guard before reaching "jsr Play_SFX"; only a fast grab
        // (x_vel >= $400) falls through to play sfx_Grab.
        if (fastGrab) {
            playSfx(Sonic3kSfx.GRAB.id);
        }
        holdPlayer(state, player);
    }

    private void updateGrabbedPlayer(PlayerState state, AbstractPlayableSprite player) {
        if (player == null || !renderFlagsOnScreen(player) || player.isHurt() || player.getDead() || player.isDebugMode()) {
            if (player != null) {
                clearControlImmediate(player);
            }
            state.grabFlag = 0;
            state.pendingJumpRelease = false;
            state.releaseDelay = RELEASE_DELAY;
            return;
        }
        if (player.isJumpJustPressed()) {
            state.pendingJumpRelease = true;
            releasePending(state);
            return;
        }
        holdPlayer(state, player);
    }

    private void holdPlayer(PlayerState state, AbstractPlayableSprite player) {
        if (handleMode == 0) {
            NativePositionOps.writeXPosPreserveSubpixel(player, handleX);
            NativePositionOps.writeYPosPreserveSubpixel(player, handleY + PLAYER_HANG_Y_OFFSET);
            int angle = angleByte(rootAngle);
            if (player.getDirection() == Direction.LEFT) {
                angle = (-angle) & 0xFF;
            }
            int index = ((angle + 8) & 0xFF) >> 4;
            player.setForcedAnimationId(Sonic3kAnimationIds.HANG2);
            player.setMappingFrame(MODE0_PLAYER_FRAMES[index]);
        } else {
            int angle = angleByte(rootAngle);
            if (player.getDirection() == Direction.LEFT) {
                angle = (-angle) & 0xFF;
            }
            int index = (((angle + 0x10) & 0xFF) >> 5) & 0x7;
            int tableIndex = index << 1;
            int offsetX = MODE1_PLAYER_OFFSETS[tableIndex];
            int offsetY = MODE1_PLAYER_OFFSETS[tableIndex + 1];
            if (player.getDirection() == Direction.LEFT) {
                offsetX = -offsetX;
            }
            player.setAnimationId(Sonic3kAnimationIds.WALK);
            player.setForcedAnimationId(Sonic3kAnimationIds.WALK);
            player.setMappingFrame(MODE1_PLAYER_FRAMES[tableIndex]);
            NativePositionOps.writeXPosPreserveSubpixel(player, handleX + offsetX);
            NativePositionOps.writeYPosPreserveSubpixel(player, handleY + offsetY);
        }
        player.setRenderFlips(player.getDirection() == Direction.LEFT, false);
        state.player = player;
    }

    private void releasePending(PlayerState state) {
        if (!state.pendingJumpRelease) {
            return;
        }
        state.pendingJumpRelease = false;
        AbstractPlayableSprite player = state.player;
        if (player == null || state.grabFlag == 0) {
            return;
        }

        clearControlImmediate(player);
        state.grabFlag = 0;
        state.releaseDelay = RELEASE_DELAY;
        if (handleMode == 1) {
            int angle = angleByte(rootAngle);
            int sin = TrigLookupTable.sinHex(angle);
            int cos = TrigLookupTable.cosHex(angle);
            // ROM loc_229B6: asl#2; move; asl#1; add = *$C
            player.setXSpeed((short) (cos * 0xC));
            player.setYSpeed((short) (sin * 0xC));
        } else {
            player.setXSpeed((short) ((handleX - prevHandleX) << 7));
            player.setYSpeed((short) (((handleY - prevHandleY) << 7) - 0x380));
            if (player.isLeftPressed()) {
                player.setXSpeed((short) -0x200);
            }
            if (player.isRightPressed()) {
                player.setXSpeed((short) 0x200);
            }
        }

        player.setAir(true);
        player.setJumping(true);
        player.applyRollingRadii(false);
        int centreX = player.getCentreX();
        int centreY = player.getCentreY();
        player.setRolling(true);
        NativePositionOps.writeXPosPreserveSubpixel(player, centreX);
        NativePositionOps.writeYPosPreserveSubpixel(player, centreY);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
    }

    private boolean canGrab(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - handleX;
        if (dx < -GRAB_HALF_WIDTH || dx >= GRAB_HALF_WIDTH) {
            return false;
        }
        int dy = player.getCentreY() - handleY;
        if (dy < 0 || dy >= GRAB_HEIGHT) {
            return false;
        }
        if (player.getXSpeed() < 0x400 && dy >= SLOW_GRAB_HEIGHT) {
            return false;
        }
        return !player.isObjectControlled()
                && !player.isControlLocked()
                && !player.isHurt()
                && !player.getDead()
                && !player.isDebugMode();
    }

    private static boolean renderFlagsOnScreen(AbstractPlayableSprite player) {
        return !player.hasRenderFlagOnScreenState() || player.isRenderFlagOnScreen();
    }

    private void playSfx(int soundId) {
        ObjectServices services = tryServices();
        if (services != null) {
            services.playSfx(soundId);
        }
    }

    private boolean anyGrabbed() {
        return p1.grabFlag != 0 || p2.grabFlag != 0;
    }

    private boolean anyFastGrabbed() {
        return (p1.grabFlag & 0x80) != 0 || (p2.grabFlag & 0x80) != 0;
    }

    private AbstractPlayableSprite firstTrackedSidekick() {
        ObjectServices services = tryServices();
        if (services == null) {
            return null;
        }
        return services.playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick ? sidekick : null;
    }

    private int swingSegmentFrame() {
        return ((angleByte(rootAngle) + 4) & 0xFF) >> 3;
    }

    private static void clearPlayerControl(PlayerState state) {
        if (state.player != null && state.grabFlag != 0) {
            AizVineHandleLogic.clearPlayerControl(state.player);
        }
        state.grabFlag = 0;
        state.pendingJumpRelease = false;
    }

    private static void clearControlImmediate(AbstractPlayableSprite player) {
        player.setObjectMappingFrameControl(false);
        player.setForcedAnimationId(-1);
        player.setControlLocked(false);
        ObjectControlState.none().applyTo(player);
        player.suppressNextJumpPress();
    }

    private static int angleByte(int angleWord) {
        return (angleWord >> 8) & 0xFF;
    }

    private static int signedAngleByte(int angleWord) {
        int value = angleByte(angleWord);
        return value >= 0x80 ? value - 0x100 : value;
    }

    private static int asSigned16(int value) {
        value &= 0xFFFF;
        return value >= 0x8000 ? value - 0x10000 : value;
    }
}
