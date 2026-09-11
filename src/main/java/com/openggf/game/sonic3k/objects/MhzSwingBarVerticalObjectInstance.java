package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S3K SKL object $0C - MHZ vertical swing bar.
 *
 * <p>ROM reference: {@code Obj_MHZSwingBarVertical/sub_3F0D8}. This ports
 * the route-critical side grab, per-player hang state, climb animation, and
 * horizontal auto-release path.
 */
public final class MhzSwingBarVerticalObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_MHZSwingBarVertical} is installed from the S3K object pointer table at
     * {@code $0003F05A} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:83549).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0003}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0003;
    }

    private static final int PRIORITY_BUCKET = 1;
    private static final int HALF_WIDTH = 4;
    private static final int HALF_HEIGHT = 0x20;
    private static final int SOLID_GROUND_HALF_HEIGHT = 0x21;
    private static final int MIN_GRAB_SPEED = 0x0400;
    private static final int GRAB_SIDE_OFFSET = 0x12;
    private static final int GRAB_X_RANGE = 0x18;
    private static final int GRAB_Y_BIAS = 0x20;
    private static final int GRAB_Y_RANGE = 0x40;
    private static final int INITIAL_PHASE = 8;
    private static final int AUTO_RELEASE_PHASE = 0xF8;
    private static final int NORMAL_RELEASE_Y_SPEED = -0x0500;
    private static final int UNDERWATER_RELEASE_Y_SPEED = -0x0200;
    private static final int AUTO_RELEASE_X_SPEED = 0x1000;
    private static final int AUTO_RELEASE_MOVE_LOCK = 15;
    private static final int GRAB_FRAME = 0x62;
    private static final int SONIC_AUTO_RELEASE_FRAME = 0x24;
    private static final int TAILS_AUTO_RELEASE_FRAME = 0x0E;

    private final Map<AbstractPlayableSprite, BarState> playerStates = new IdentityHashMap<>();

    public MhzSwingBarVerticalObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZSwingBarVertical");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player1 = playerEntity instanceof AbstractPlayableSprite player ? player : null;
        if (player1 != null) {
            updatePlayer(player1);
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        for (PlayableEntity participant : services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (participant != playerEntity && participant instanceof AbstractPlayableSprite sprite) {
                updatePlayer(sprite);
            }
        }
        requestForcedScrollWhilePrimaryHanging(services, player1);
    }

    /**
     * ROM {@code loc_3F0CC} (sonic3k.asm:83575-83577): after both players are
     * processed, if Player 1 is hanging ({@code $30(a0)}) the bar writes
     * {@code Scroll_force_positions} plus the bar's own {@code x_pos} into
     * {@code Scroll_forced_X_pos} and {@code Player_1+y_pos} into
     * {@code Scroll_forced_Y_pos}. Note the forced Y is Player 1's Y, not the
     * bar's, so the camera tracks the climbing player vertically while pinning to
     * the bar horizontally.
     */
    private void requestForcedScrollWhilePrimaryHanging(ObjectServices services, AbstractPlayableSprite player1) {
        if (player1 == null) {
            return;
        }
        BarState state = playerStates.get(player1);
        if (state == null || !state.hanging) {
            return;
        }
        Camera camera = services.camera();
        if (camera != null) {
            camera.requestForcedScroll(spawn.x(), player1.getCentreY());
        }
    }

    private void updatePlayer(AbstractPlayableSprite player) {
        BarState state = playerStates.computeIfAbsent(player, ignored -> new BarState());
        if (state.hanging) {
            updateHangingPlayer(player, state);
            return;
        }
        if (state.cooldown > 0) {
            state.cooldown--;
            return;
        }
        tryGrab(player, state);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, SOLID_GROUND_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_VERTICAL);
        if (renderer != null) {
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
        }
    }

    @Override
    public String traceDebugDetails() {
        long hanging = playerStates.values().stream().filter(state -> state.hanging).count();
        return super.traceDebugDetails() + " hanging=" + hanging;
    }

    /**
     * Whether {@code player} is currently hanging on this bar. Used by rewind
     * coverage tests to assert the per-player hang state survives keyframe
     * capture/restore.
     */
    public boolean isPlayerHanging(AbstractPlayableSprite player) {
        BarState state = playerStates.get(player);
        return state != null && state.hanging;
    }

    private void tryGrab(AbstractPlayableSprite player, BarState state) {
        int xSpeed = player.getXSpeed();
        int sideOffset;
        if (xSpeed < 0) {
            if (xSpeed > -MIN_GRAB_SPEED || !isInLeftGrabWindow(player)) {
                return;
            }
            sideOffset = -GRAB_SIDE_OFFSET;
        } else {
            if (xSpeed < MIN_GRAB_SPEED || !isInRightGrabWindow(player)) {
                return;
            }
            sideOffset = GRAB_SIDE_OFFSET;
        }
        if (!isInVerticalGrabRange(player) || player.isHurt() || player.getDead()
                || player.isObjectControlled() || player.getAir()) {
            return;
        }
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        boolean wasRolling = player.getRolling();
        int correctedCentreY = player.getCentreY() + player.getYRadius() - player.getStandYRadius();
        player.setRolling(false);
        if (wasRolling) {
            // ROM loc_3F2BE (sonic3k.asm:83752-83754): add.w d0,y_pos(a1) touches only the pixel word,
            // leaving y_sub untouched.
            NativePositionOps.writeYPosPreserveSubpixel(player, correctedCentreY);
        }
        // ROM loc_3F316 (sonic3k.asm:83757-83759): move.w d0,x_pos(a1) touches only the pixel word,
        // leaving x_sub untouched.
        NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x() + sideOffset);
        player.setRenderFlips(sideOffset < 0, false);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setObjectMappingFrameControl(true);
        player.setMappingFrame(GRAB_FRAME);

        state.hanging = true;
        state.phase = INITIAL_PHASE;

        ObjectServices objectServices = tryServices();
        if (objectServices != null) {
            objectServices.playSfx(Sonic3kSfx.GRAB.id);
        }
    }

    private void updateHangingPlayer(AbstractPlayableSprite player, BarState state) {
        if (player.isJumpJustPressed()) {
            releaseWithJump(player, state);
            return;
        }
        if ((state.phase & 0xFF) == AUTO_RELEASE_PHASE) {
            releaseHorizontally(player, state);
            return;
        }
        state.phase = (state.phase + 8) & 0xFF;
        // ROM sub_3F11C (sonic3k.asm:83625-83626): add.w x_pos(a0),d1 / move.w d1,x_pos(a1) writes only
        // the pixel word each climb frame, leaving x_sub untouched.
        NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x() + hangingXOffsetFor(state.phase, player.getRenderHFlip()));
        player.setMappingFrame(hangingFrameFor(state.phase));
    }

    private void releaseWithJump(AbstractPlayableSprite player, BarState state) {
        player.setYSpeed((short) (player.isInWater() ? UNDERWATER_RELEASE_Y_SPEED : NORMAL_RELEASE_Y_SPEED));
        state.hanging = false;
        state.cooldown = player.isInWater() ? 60 : 30;
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setAir(true);
        player.setJumping(true);
        player.setRolling(true);
        player.setRollingJump(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setFlipAngle(0);
    }

    private void releaseHorizontally(AbstractPlayableSprite player, BarState state) {
        boolean launchLeft = player.getRenderHFlip();
        int xSpeed = launchLeft ? -AUTO_RELEASE_X_SPEED : AUTO_RELEASE_X_SPEED;
        state.hanging = false;
        state.cooldown = 8;
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        // ROM loc_3F1C8 (sonic3k.asm:83669): move.w x_pos(a0),x_pos(a1) touches only the pixel word,
        // leaving x_sub untouched.
        NativePositionOps.writeXPosPreserveSubpixel(player, spawn.x());
        player.setXSpeed((short) xSpeed);
        player.setRenderFlips(launchLeft, player.getRenderVFlip());
        player.setMoveLockTimer(AUTO_RELEASE_MOVE_LOCK);
        player.setGSpeed((short) xSpeed);
        player.setJumping(false);
        player.setSpindash(false);
        player.setRollingJump(false);
        player.setDoubleJumpFlag(0);
        player.setFlipAngle(0);
        player.setMappingFrame(isTailsCharacter(player) ? TAILS_AUTO_RELEASE_FRAME : SONIC_AUTO_RELEASE_FRAME);
    }

    private boolean isInLeftGrabWindow(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() + 0x28;
        return relX >= 0 && relX < GRAB_X_RANGE;
    }

    private boolean isInRightGrabWindow(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() - 0x10;
        return relX >= 0 && relX < GRAB_X_RANGE;
    }

    private boolean isInVerticalGrabRange(AbstractPlayableSprite player) {
        int relY = player.getCentreY() - spawn.y() + GRAB_Y_BIAS;
        return relY >= 0 && relY < GRAB_Y_RANGE;
    }

    private static boolean isTailsCharacter(AbstractPlayableSprite player) {
        return player.getCode().toLowerCase(Locale.ROOT).contains("tails");
    }

    private static int hangingFrameFor(int phase) {
        int index = ((phase & 0xFF) >> 4) & 0x0F;
        return RAW_ANIMATION[index] & 0xFF;
    }

    private static int hangingXOffsetFor(int phase, boolean flipped) {
        int index = ((phase & 0xFF) >> 4) & 0x0F;
        int offset = HANG_X_OFFSETS[index];
        return flipped ? -offset : offset;
    }

    private static final int[] RAW_ANIMATION = {
            0x5C, 0x5C, 0x5D, 0x5D, 0x5D, 0x5E, 0x5E, 0x5E,
            0x5F, 0x5F, 0x60, 0x60, 0x60, 0x61, 0x61, 0x61
    };
    private static final int[] HANG_X_OFFSETS = {
            0x12, 0x12, 0x04, 0x04, 0x04, -0x0A, -0x0A, -0x0A,
            -0x12, -0x12, -0x12, -0x12, -0x12, 0x0A, 0x0A, 0x0A
    };

    private static final class BarState {
        private boolean hanging;
        private int phase;
        private int cooldown;
    }
}
