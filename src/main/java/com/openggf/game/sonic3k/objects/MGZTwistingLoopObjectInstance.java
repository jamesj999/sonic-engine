package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x50 - MGZ Twisting Loop.
 *
 * <p>Invisible controller for the spiral descent after the top-platform launcher.
 * ROM reference: Obj_MGZTwistingLoop (sonic3k.asm:70187-70387).
 */
public class MGZTwistingLoopObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int CAPTURE_X_BIAS = 0x24;
    private static final int CAPTURE_Y_RANGE = 0x20;
    private static final int ACTIVE_RELEASE_COOLDOWN = 8;
    private static final int CONVEX_RELEASE_FRAMES = 3;
    private static final int JUMP_RELEASE_FRAMES = 8;
    private static final int JUMP_RELEASE_X_VEL = 0x800;
    private static final int JUMP_RELEASE_Y_VEL = -0x200;
    private static final int JUMP_RELEASE_GRAVITY = 0x38;
    private static final int MIN_GROUND_SPEED = 0x400;
    private static final int MAX_GROUND_SPEED = 0x0C00;
    // OpenGGF updates objects before player physics; keep one extra spiral pitch
    // so the visible exit matches the ROM traversal window on live routes.
    private static final int RELEASE_TURN_PITCH = 0x10;
    private static final int DESCENT_PROGRESS_SCALE = 0xC0;
    private static final int ANGLE_PROGRESS_SCALE = 0x155;
    private static final int CAPTURE_ANIMATION = 0;
    private static final int RELEASE_ANIMATION = 0;
    private static final int RELEASE_PREVIOUS_ANIMATION = 1;
    private static final int[] TWIST_FRAMES = {
            0x76, 0x76, 0x77, 0x77, 0x6C, 0x6C, 0x6D, 0x6D, 0x6E, 0x6E, 0x6F, 0x6F,
            0x70, 0x70, 0x71, 0x71, 0x72, 0x72, 0x73, 0x73, 0x74, 0x74, 0x75, 0x75
    };

    private static final class PlayerState {
        boolean active;
        int progressFixed;
        int sidePhaseOffset;
        int releaseFrames;
        int cooldownFrames;
        int convexReleaseFrames;
        int controlGeneration;
        boolean compensateReleaseHandoff;

        void copyFrom(PlayerState state) {
            active = state.active;
            progressFixed = state.progressFixed;
            sidePhaseOffset = state.sidePhaseOffset;
            releaseFrames = state.releaseFrames;
            cooldownFrames = state.cooldownFrames;
            convexReleaseFrames = state.convexReleaseFrames;
            controlGeneration = state.controlGeneration;
            compensateReleaseHandoff = state.compensateReleaseHandoff;
        }

        void reset() {
            active = false;
            progressFixed = 0;
            sidePhaseOffset = 0;
            releaseFrames = 0;
            cooldownFrames = 0;
            convexReleaseFrames = 0;
            controlGeneration = 0;
            compensateReleaseHandoff = false;
        }
    }

    private int centerX;
    private int centerY;
    private int captureThreshold;
    private boolean flipped;
    private final PlayerState player1 = new PlayerState();
    private final PlayerState player2 = new PlayerState();
    private AbstractPlayableSprite nativeP2Owner;
    private final Map<PlayableEntity, PlayerState> extensionPlayerStates = new IdentityHashMap<>();

    public MGZTwistingLoopObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MGZTwistingLoop");
        this.centerX = spawn.x();
        this.centerY = spawn.y();
        this.captureThreshold = (spawn.subtype() & 0xFF) << 4;
        this.flipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public MGZTwistingLoopObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MGZTwistingLoopObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (playerEntity instanceof AbstractPlayableSprite player) {
            processPlayer(vIntRunCount, player, player1);
        }
        ObjectServices svc = tryServices();
        if (svc == null) {
            return;
        }
        AbstractPlayableSprite nativeP2 = nativeP2FromQuery(svc, playerEntity);
        reconcileNativeP2(vIntRunCount, svc, nativeP2);
        if (nativeP2 != null) {
            processPlayer(vIntRunCount, nativeP2, player2);
        }
        updateExtensionPlayers(vIntRunCount, svc, playerEntity, nativeP2);
    }

    private void reconcileNativeP2(int frameCounter, ObjectServices services,
                                   AbstractPlayableSprite currentNativeP2) {
        if (nativeP2Owner == currentNativeP2) {
            return;
        }

        List<PlayableEntity> participants = services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (nativeP2Owner != null) {
            if (containsIdentity(participants, nativeP2Owner)) {
                PlayerState demotedState = new PlayerState();
                demotedState.copyFrom(player2);
                extensionPlayerStates.put(nativeP2Owner, demotedState);
            } else {
                releaseExtensionOwnership(frameCounter, nativeP2Owner, player2);
            }
        }

        PlayerState promotedState = currentNativeP2 == null
                ? null
                : extensionPlayerStates.remove(currentNativeP2);
        player2.reset();
        if (promotedState != null) {
            player2.copyFrom(promotedState);
        }
        nativeP2Owner = currentNativeP2;
    }

    private static boolean containsIdentity(List<PlayableEntity> participants, PlayableEntity candidate) {
        for (PlayableEntity participant : participants) {
            if (participant == candidate) {
                return true;
            }
        }
        return false;
    }

    private void updateExtensionPlayers(int frameCounter, ObjectServices services,
                                        PlayableEntity updatePlayer, AbstractPlayableSprite nativeP2) {
        PlayableEntity queryMain = services.playerQuery().mainPlayerOrNull();
        IdentityHashMap<PlayableEntity, Boolean> live = new IdentityHashMap<>();
        for (PlayableEntity participant : services.playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED)) {
            if (participant == updatePlayer || participant == queryMain || participant == nativeP2
                    || !(participant instanceof AbstractPlayableSprite player)) {
                continue;
            }
            live.put(player, Boolean.TRUE);
            processPlayer(frameCounter, player,
                    extensionPlayerStates.computeIfAbsent(player, ignored -> new PlayerState()));
        }
        extensionPlayerStates.entrySet().removeIf(entry -> {
            if (live.containsKey(entry.getKey())) {
                return false;
            }
            releaseExtensionOwnership(frameCounter, entry.getKey(), entry.getValue());
            return true;
        });
    }

    private void releaseExtensionOwnership(int frameCounter, PlayableEntity entity, PlayerState state) {
        if (entity instanceof AbstractPlayableSprite player
                && (state.active || state.releaseFrames > 0 || state.convexReleaseFrames > 0)) {
            if (hasOwnedControlFingerprint(player, state)) {
                boolean ownsControlPhase = state.active || state.releaseFrames > 0;
                if (ownsControlPhase) {
                    player.setObjectMappingFrameControl(false);
                    player.setSuppressGroundWallCollision(false);
                    player.setControlLocked(false);
                    player.setOnObject(false);
                    player.setHighPriority(false);
                }
                player.setStickToConvex(false);
                if (player.isObjectControlled()) {
                    player.releaseFromObjectControl(frameCounter);
                }
            }
        }
        state.reset();
    }

    private static boolean hasOwnedControlFingerprint(AbstractPlayableSprite player, PlayerState state) {
        if (!hasOwnedControlGeneration(player, state)) {
            return false;
        }
        if (state.active) {
            // Active capture owns direct mapping frames under object_control=$42;
            // player input is deliberately unlocked while native movement remains active.
            return player.isObjectMappingFrameControl();
        }
        if (state.releaseFrames > 0) {
            return player.isObjectControlled() && player.getAnimationId() == RELEASE_ANIMATION;
        }
        return state.convexReleaseFrames > 0 && player.isStickToConvex();
    }

    private static boolean hasOwnedControlGeneration(AbstractPlayableSprite player, PlayerState state) {
        return player != null && player.getObjectControlGeneration() == state.controlGeneration;
    }

    @Override
    public void onUnload() {
        releaseMainOwnership(player1);
        releaseExtensionOwnership(0, nativeP2Owner, player2);
        nativeP2Owner = null;
        player2.reset();
        for (Map.Entry<PlayableEntity, PlayerState> entry : extensionPlayerStates.entrySet()) {
            releaseExtensionOwnership(0, entry.getKey(), entry.getValue());
        }
        extensionPlayerStates.clear();
    }

    private void releaseMainOwnership(PlayerState state) {
        if (!state.active && state.releaseFrames == 0 && state.convexReleaseFrames == 0) {
            return;
        }
        ObjectServices services = tryServices();
        if (services == null) {
            return;
        }
        AbstractPlayableSprite player = services.playerQuery().mainPlayerOrNull() instanceof AbstractPlayableSprite sprite
                ? sprite : null;
        releaseExtensionOwnership(0, player, state);
    }

    private AbstractPlayableSprite nativeP2FromQuery(ObjectServices svc, PlayableEntity updatePlayer) {
        PlayableEntity queryMain = svc.playerQuery().mainPlayerOrNull();
        for (PlayableEntity candidate : svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate == updatePlayer || candidate == queryMain) {
                continue;
            }
            if (candidate instanceof AbstractPlayableSprite sidekick) {
                return sidekick;
            }
        }
        return null;
    }

    private void processPlayer(int frameCounter, AbstractPlayableSprite player, PlayerState state) {
        if (state.releaseFrames > 0) {
            updateReleasedPlayer(frameCounter, player, state);
            return;
        }
        if (state.convexReleaseFrames > 0) {
            if (!hasOwnedControlGeneration(player, state)) {
                state.reset();
                return;
            }
            state.convexReleaseFrames--;
            if (state.convexReleaseFrames == 0 && player != null) {
                player.setStickToConvex(false);
            }
        }
        if (state.cooldownFrames > 0) {
            state.cooldownFrames--;
        }
        if (state.active) {
            updateCapturedPlayer(frameCounter, player, state);
            return;
        }
        if (state.cooldownFrames == 0) {
            tryCapturePlayer(frameCounter, player, state);
        }
    }

    private void tryCapturePlayer(int frameCounter, AbstractPlayableSprite player, PlayerState state) {
        if (player == null || player.getDead() || player.isHurt() || player.isDebugMode()) {
            return;
        }
        if (player.isObjectControlled()) {
            return;
        }
        if (player.wasRecentlyObjectControlled(frameCounter, ACTIVE_RELEASE_COOLDOWN)) {
            return;
        }
        if (player.getAir()) {
            return;
        }
        if ((player.getAngle() & 0x7F) != 0x40) {
            return;
        }

        int range = player.getYRadius() + CAPTURE_X_BIAS;
        int dx = player.getCentreX() - centerX;
        if (dx < -range || dx >= range) {
            return;
        }

        int dy = player.getCentreY() - centerY;
        if (dy < 0 || dy >= CAPTURE_Y_RANGE) {
            return;
        }

        state.active = true;
        state.progressFixed = (dy << 16) | player.getYSubpixelRaw();
        state.sidePhaseOffset = dx < 0 ? 0x80 : 0x00;
        state.releaseFrames = 0;
        state.cooldownFrames = 0;
        state.convexReleaseFrames = 0;
        state.compensateReleaseHandoff = player.getRolling();

        if (player.isOnObject()) {
            ObjectServices svc = tryServices();
            if (svc != null && svc.objectManager() != null) {
                svc.objectManager().clearRidingObject(player);
            }
        }

        player.setControlLocked(false);
        // ROM sets object_control to $42 here (bits 6 and 1). Bit 0 remains
        // clear, so Sonic_Modes still advances native movement before this
        // later object slot overwrites the loop-owned position words.
        ObjectControlState.nativeBits0To6CpuAllowedMovementActive().applyTo(player);
        state.controlGeneration = player.getObjectControlGeneration();
        player.setSuppressGroundWallCollision(true);
        player.setObjectMappingFrameControl(true);
        player.setOnObject(true);
        player.setAir(false);
        player.setPushing(false);
        player.setAnimationId(CAPTURE_ANIMATION);
        player.setHighPriority(false);
        player.setXSpeed((short) 0);

        if (dx < 0) {
            player.setAngle((byte) 0xC0);
            player.setDirection(Direction.LEFT);
            player.setRenderFlips(false, true);
        } else {
            player.setAngle((byte) 0x40);
            player.setDirection(Direction.RIGHT);
            player.setRenderFlips(true, true);
        }
        // Obj_MGZTwistingLoop/loc_33D70 sets render_flags bit 1 for both
        // entry sides; bit 0 above mirrors the right-side spiral mapping.
    }

    private void updateCapturedPlayer(int frameCounter, AbstractPlayableSprite player, PlayerState state) {
        if (!hasOwnedControlGeneration(player, state)) {
            state.reset();
            return;
        }
        if (player == null || player.getDead() || player.isHurt() || player.isDebugMode()) {
            releaseCapturedPlayer(frameCounter, player, state, false);
            return;
        }

        if (player.isJumpPressed()) {
            releaseCapturedPlayer(frameCounter, player, state, true);
            return;
        }

        int releaseThreshold = captureThreshold + (state.compensateReleaseHandoff ? RELEASE_TURN_PITCH : 0);
        // ROM loc_33DFE compares the player's live y_pos word against the
        // controller y_pos. Player physics has already advanced that word for
        // this frame; the private fixed-point progress in (a2) is only used by
        // the later spiral-position calculation.
        int currentPlayerProgress = player.getCentreY() - centerY;
        if (currentPlayerProgress >= releaseThreshold) {
            // The release branch still advances the private loop position and
            // derives one final x_pos from it before clearing object_control.
            // It deliberately leaves the live y_pos written by player physics.
            int ySpeed = updateCapturedGroundMotion(player);
            int releaseProgressFixed = state.progressFixed + ySpeed * DESCENT_PROGRESS_SCALE;
            positionPlayerHorizontally(player, state, releaseProgressFixed >> 16);
            releaseCapturedPlayer(frameCounter, player, state, false);
            return;
        }

        int ySpeed = updateCapturedGroundMotion(player);

        state.progressFixed += ySpeed * DESCENT_PROGRESS_SCALE;
        int progressPixels = state.progressFixed >> 16;
        int phaseBase = ((progressPixels * ANGLE_PROGRESS_SCALE) >> 8) & 0xFF;
        positionPlayerHorizontally(player, state, progressPixels);
        player.setY((short) (centerY + progressPixels - (player.getHeight() / 2)));
        player.setAnimationId(CAPTURE_ANIMATION);
        player.setOnObject(true);
        player.setAir(false);
        player.setHighPriority(phaseBase < 0x80);
        applyTwistFrame(player, phaseBase);
    }

    private void positionPlayerHorizontally(AbstractPlayableSprite player, PlayerState state, int progressPixels) {
        int phaseBase = ((progressPixels * ANGLE_PROGRESS_SCALE) >> 8) & 0xFF;
        int phase = (phaseBase + state.sidePhaseOffset) & 0xFF;
        int cosine = TrigLookupTable.cosHex(phase);
        int horizontalOffset = (cosine >> 3) + ((player.getYRadius() * cosine) >> 8);
        player.setX((short) (centerX + horizontalOffset - (player.getWidth() / 2)));
    }

    private int updateCapturedGroundMotion(AbstractPlayableSprite player) {
        int groundSpeed = player.getGSpeed();
        int speedSign = (groundSpeed < 0) ? -1 : 1;
        int speedMagnitude = Math.abs(groundSpeed);
        if (speedMagnitude < MIN_GROUND_SPEED) {
            speedMagnitude = MIN_GROUND_SPEED;
        }
        if (speedMagnitude >= MAX_GROUND_SPEED) {
            speedMagnitude = MAX_GROUND_SPEED;
            // loc_33E2A/loc_33EDA publish y_vel only when the maximum clamp
            // is taken. Below that threshold the player movement routine's
            // live projected y_vel remains visible to the loop calculation.
            player.setYSpeed((short) MAX_GROUND_SPEED);
        }

        int adjustedGroundSpeed = speedSign * speedMagnitude;
        player.setGSpeed((short) adjustedGroundSpeed);
        player.setXSpeed((short) 0);
        return player.getYSpeed();
    }

    private void applyTwistFrame(AbstractPlayableSprite player, int phaseBase) {
        int frameIndex = (((0x40 - phaseBase) & 0xFF) / 0x0B);
        if (frameIndex < 0) {
            frameIndex = 0;
        } else if (frameIndex >= TWIST_FRAMES.length) {
            frameIndex = TWIST_FRAMES.length - 1;
        }
        player.setMappingFrame(TWIST_FRAMES[frameIndex]);
    }

    private void releaseCapturedPlayer(int frameCounter, AbstractPlayableSprite player, PlayerState state, boolean jumpedOut) {
        state.active = false;
        state.cooldownFrames = ACTIVE_RELEASE_COOLDOWN;
        state.releaseFrames = jumpedOut ? JUMP_RELEASE_FRAMES : 0;
        state.convexReleaseFrames = jumpedOut || !state.compensateReleaseHandoff ? 0 : CONVEX_RELEASE_FRAMES;

        if (player == null) {
            state.compensateReleaseHandoff = false;
            return;
        }

        player.setObjectMappingFrameControl(false);
        player.setSuppressGroundWallCollision(false);
        player.setControlLocked(false);
        if (jumpedOut) {
            ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
            state.controlGeneration = player.getObjectControlGeneration();
        } else if (state.compensateReleaseHandoff) {
            player.deferObjectControlRelease();
        } else {
            player.releaseFromObjectControl(frameCounter);
        }
        short centreXBeforeRelease = player.getCentreX();
        short centreYBeforeRelease = player.getCentreY();

        player.setPushing(false);
        if (flipped) {
            player.setAngle((byte) (player.getAngle() + 0x80));
        }
        player.setRolling(false);
        player.restoreDefaultRadii();
        player.setX((short) (centreXBeforeRelease - (player.getWidth() / 2)));
        player.setY((short) (centreYBeforeRelease - (player.getHeight() / 2)));
        player.setAnimationId(RELEASE_ANIMATION);
        // ROM `move.w #1,anim(a1)` is a big-endian two-byte write:
        // anim=0 and prev_anim=1. It is not an anim=1 assignment.
        player.getAnimationManager().publishPreviousAnimationId(RELEASE_PREVIOUS_ANIMATION);
        player.setHighPriority(false);
        player.setOnObject(false);
        ObjectServices svc = tryServices();
        if (svc != null && svc.objectManager() != null) {
            svc.objectManager().clearRidingObject(player);
        }

        if (jumpedOut) {
            int xVel = player.getCentreX() < centerX ? -JUMP_RELEASE_X_VEL : JUMP_RELEASE_X_VEL;
            player.setAir(true);
            player.setJumping(false);
            player.setXSpeed((short) xVel);
            player.setYSpeed((short) JUMP_RELEASE_Y_VEL);
            player.setDirection(xVel < 0 ? Direction.LEFT : Direction.RIGHT);
            player.setStickToConvex(false);
            player.suppressNextJumpPress();
        } else {
            player.setAir(false);
            player.setStickToConvex(state.compensateReleaseHandoff);
        }
        state.compensateReleaseHandoff = false;
    }

    private void updateReleasedPlayer(int frameCounter, AbstractPlayableSprite player, PlayerState state) {
        if (!hasOwnedControlGeneration(player, state)) {
            state.reset();
            return;
        }

        state.releaseFrames--;
        if (state.releaseFrames == 0) {
            player.releaseFromObjectControl(frameCounter);
            return;
        }

        int nextCenterX = player.getCentreX() + (player.getXSpeed() >> 8);
        int nextCenterY = player.getCentreY() + (player.getYSpeed() >> 8);
        player.setCentreX((short) nextCenterX);
        player.setCentreY((short) nextCenterY);
        player.setYSpeed((short) (player.getYSpeed() + JUMP_RELEASE_GRAVITY));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible controller.
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(centerX - 0x38, centerY, 0x70, CAPTURE_Y_RANGE, 0.3f, 0.8f, 1.0f);
    }

    @Override
    public int getX() {
        return centerX;
    }

    @Override
    public int getY() {
        return centerY;
    }
}
