package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3K SKL object $0B - MHZ horizontal swing bar.
 *
 * <p>ROM reference: {@code Obj_MHZSwingBarHorizontal/sub_3ED6E}. This ports
 * the route-critical grab window, hanging object-control state, and jump
 * release path for the horizontal bar.
 */
public final class MhzSwingBarHorizontalObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int PRIORITY_BUCKET = 5;
    private static final int HALF_WIDTH = 0x20;
    private static final int HALF_HEIGHT = 4;
    private static final int GRAB_X_BIAS = 0x16;
    private static final int GRAB_X_RANGE = 0x2C;
    private static final int GRAB_Y_OFFSET = 0x14;
    private static final int GRAB_Y_RANGE = 0x10;
    private static final int NORMAL_RELEASE_Y_SPEED = -0x0500;
    private static final int UNDERWATER_RELEASE_Y_SPEED = -0x0200;
    private static final int HANG_FRAME = 0x94;
    private static final int FAST_HANG_FRAME = 0x95;
    private static final int FAST_FALL_THRESHOLD = 0x0400;
    private static final int DOWNWARD_AUTO_RELEASE_PHASE = 0x05;
    private static final int UPWARD_AUTO_RELEASE_PHASE = 0x28;
    private static final int FAST_PHASE_STEP = 0x0C;
    private static final int AUTO_RELEASE_COOLDOWN = 8;
    private static final int INPUT_PAGE_TOGGLE_PERIOD = 7;

    private final Map<AbstractPlayableSprite, HangState> hangingPlayers = new IdentityHashMap<>();

    public MhzSwingBarHorizontalObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZSwingBarHorizontal");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            updatePlayer(player);
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
    }

    private void updatePlayer(AbstractPlayableSprite player) {
        HangState state = hangingPlayers.get(player);
        if (state != null) {
            updateHangingPlayer(player, state);
            return;
        }
        state = hangStates.get(player);
        if (state != null && state.cooldown > 0) {
            state.cooldown--;
            return;
        }
        tryGrab(player);
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
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_HORIZONTAL);
        if (renderer != null) {
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), false, false);
        }
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails() + " hanging=" + hangingPlayers.size();
    }

    /**
     * Whether {@code player} is currently hanging on this bar. Used by rewind
     * coverage tests to assert the per-player hang state survives keyframe
     * capture/restore.
     */
    public boolean isPlayerHanging(AbstractPlayableSprite player) {
        return hangingPlayers.containsKey(player);
    }

    private void tryGrab(AbstractPlayableSprite player) {
        if (!isInGrabWindow(player) || player.isHurt() || player.getDead() || player.isObjectControlled()) {
            return;
        }
        int incomingYSpeed = player.getYSpeed();
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setRenderFlips(false, false);
        // ROM loc_3EEDA (sonic3k.asm:83448-83450): move.w d0,y_pos(a1) touches only the pixel word,
        // leaving y_sub untouched.
        NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y() + GRAB_Y_OFFSET);
        player.setAnimationId(Sonic3kAnimationIds.WALK);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setObjectMappingFrameControl(true);

        HangState state = new HangState();
        state.storedIncomingYVelocity = incomingYSpeed;
        if (incomingYSpeed <= -FAST_FALL_THRESHOLD) {
            state.animationPhase = 0x20;
            state.framePage = 0x10;
            player.setMappingFrame(FAST_HANG_FRAME);
        } else if (incomingYSpeed >= FAST_FALL_THRESHOLD) {
            state.animationPhase = 0x21;
            state.framePage = 0x10;
            player.setMappingFrame(FAST_HANG_FRAME);
        } else {
            player.setMappingFrame(HANG_FRAME);
        }
        hangStates.put(player, state);
        hangingPlayers.put(player, state);
    }

    private void updateHangingPlayer(AbstractPlayableSprite player, HangState state) {
        applyHorizontalInput(player, state);
        if (player.isJumpJustPressed()) {
            releaseWithJump(player);
            return;
        }
        if ((state.animationPhase & 0xFF) == UPWARD_AUTO_RELEASE_PHASE) {
            releaseAutomatically(player, amplifiedStoredYVelocity(state.storedIncomingYVelocity),
                    Sonic3kAnimationIds.SPRING);
            // ROM loc_3EE7A (sonic3k.asm:83389-83390): move.w #$10<<8,anim(a1) writes anim and
            // prev_anim as one word, landing prev_anim on 0 so Animate_Sonic
            // (sonic3k.asm:24739-24749) sees the mismatch and restarts the script. The grab path
            // (sonic3k.asm:83451) writes the anim byte alone, leaving prev_anim stale at the
            // pre-grab animation; a player who was already spring-jumping into the bar otherwise
            // keeps the expired script state and never publishes mapping frame $8E.
            player.getAnimationManager().publishPreviousAnimationId(0);
            return;
        }
        if ((state.animationPhase & 0xFF) == DOWNWARD_AUTO_RELEASE_PHASE) {
            releaseAutomatically(player, state.storedIncomingYVelocity, Sonic3kAnimationIds.WALK);
            return;
        }
        advancePhase(state);
        // ROM sub_3EFBA (sonic3k.asm:83533-83534): add.w y_pos(a0),d1 / move.w d1,y_pos(a1) writes only
        // the pixel word each hang frame, leaving y_sub untouched.
        NativePositionOps.writeYPosPreserveSubpixel(player, spawn.y() + hangingYOffsetFor(state.animationPhase, state.framePage));
        player.setMappingFrame(hangingFrameFor(state.animationPhase, state.framePage));
    }

    private void applyHorizontalInput(AbstractPlayableSprite player, HangState state) {
        // ROM sub_3ED6E (sonic3k.asm:83326, 83340): subq.w/addq.w #1,x_pos(a1) modify only the pixel
        // word, leaving x_sub untouched.
        if (player.isLeftPressed() && player.getCentreX() > spawn.x() - GRAB_X_BIAS) {
            NativePositionOps.writeXPosPreserveSubpixel(player, player.getCentreX() - 1);
            tickInputFramePage(state);
        }
        if (player.isRightPressed() && player.getCentreX() < spawn.x() + 0x15) {
            NativePositionOps.writeXPosPreserveSubpixel(player, player.getCentreX() + 1);
            tickInputFramePage(state);
        }
    }

    private void releaseWithJump(AbstractPlayableSprite player) {
        player.setYSpeed((short) (player.isInWater() ? UNDERWATER_RELEASE_Y_SPEED : NORMAL_RELEASE_Y_SPEED));
        releaseCommon(player);
    }

    private void releaseCommon(AbstractPlayableSprite player) {
        HangState state = hangingPlayers.remove(player);
        if (state != null) {
            state.cooldown = player.isInWater() ? 60 : 30;
        }
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setAir(true);
        player.setJumping(true);
        player.setRolling(true);
        player.setRollingJump(false);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setFlipAngle(0);
    }

    private void releaseAutomatically(AbstractPlayableSprite player, int yVelocity, Sonic3kAnimationIds animation) {
        HangState state = hangingPlayers.remove(player);
        if (state != null) {
            state.cooldown = AUTO_RELEASE_COOLDOWN;
        }
        player.setYSpeed((short) yVelocity);
        ObjectControlState.none().applyTo(player);
        player.setObjectMappingFrameControl(false);
        player.setAir(true);
        player.setJumping(false);
        player.setSpindash(false);
        player.setAnimationId(animation);
        player.setRollingJump(false);
        player.setDoubleJumpFlag(0);
        player.setFlipAngle(0);
    }

    private boolean isInGrabWindow(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() + GRAB_X_BIAS;
        if (relX < 0 || relX >= GRAB_X_RANGE) {
            return false;
        }
        int minimumY = spawn.y() + GRAB_Y_OFFSET;
        int maximumY = minimumY + GRAB_Y_RANGE;
        int playerY = player.getCentreY();
        return playerY > minimumY && playerY <= maximumY;
    }

    private static int hangingFrameFor(int animationPhase, int framePage) {
        int index = ((animationPhase & 0xFF) >> 4) + framePage;
        return RAW_ANIMATION[Math.min(index, RAW_ANIMATION.length - 1)] & 0xFF;
    }

    private static int hangingYOffsetFor(int animationPhase, int framePage) {
        int index = ((animationPhase & 0xFF) >> 4) + framePage;
        return HANG_Y_OFFSETS[Math.min(index & 0x0F, HANG_Y_OFFSETS.length - 1)];
    }

    private static void advancePhase(HangState state) {
        int storedYVelocity = state.storedIncomingYVelocity;
        if (storedYVelocity <= -FAST_FALL_THRESHOLD || storedYVelocity >= FAST_FALL_THRESHOLD) {
            state.animationPhase = (state.animationPhase + FAST_PHASE_STEP) & 0xFF;
        }
    }

    private static void tickInputFramePage(HangState state) {
        state.inputFrameTimer--;
        if (state.inputFrameTimer < 0) {
            state.inputFrameTimer = INPUT_PAGE_TOGGLE_PERIOD;
            state.framePage = (state.framePage + 0x10) & 0x10;
        }
    }

    private static int amplifiedStoredYVelocity(int yVelocity) {
        return yVelocity + (yVelocity >> 1);
    }

    private static final int[] RAW_ANIMATION = {
            0x94, 0x63, 0x64, 0x64, 0x65, 0x65, 0x65, 0x66,
            0x66, 0x66, 0x66, 0x67, 0x67, 0x67, 0x68, 0x68,
            0x95, 0x63, 0x64, 0x64, 0x65, 0x65, 0x65, 0x66,
            0x66, 0x66, 0x66, 0x67, 0x67, 0x67, 0x68, 0x68
    };
    private static final int[] HANG_Y_OFFSETS = {
            0x14, 0x14, 0x0B, 0x0B, -0x0F, -0x0F, -0x0F, -0x14,
            -0x14, -0x14, -0x14, -0x0C, -0x0C, -0x0C, -0x02, -0x02
    };

    private static final class HangState {
        private int animationPhase;
        private int framePage;
        private int cooldown;
        private int storedIncomingYVelocity;
        private int inputFrameTimer;
    }

    private final Map<AbstractPlayableSprite, HangState> hangStates = new IdentityHashMap<>();
}
