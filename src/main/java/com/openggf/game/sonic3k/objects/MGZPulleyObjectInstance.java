package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.GroundMode;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Object 0x5A - MGZ Pulley.
 *
 * <p>ROM: {@code Obj_MGZPulley} (sonic3k.asm:71117-71473).
 * The parent object renders the pulley wheel while a child multisprite renders
 * the hanging chain. Players are captured by proximity at the pulley handle,
 * the extension retracts while anyone is hanging, and jump releases the player
 * with the ROM's fixed launch speeds.
 */
public class MGZPulleyObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable {

    private static final String ART_KEY = Sonic3kObjectArtKeys.MGZ_PULLEY;

    // ROM: priority = $280
    private static final int PRIORITY_BUCKET = 5;
    // ROM child priority = $300
    private static final int CHAIN_PRIORITY_BUCKET = 6;

    // ROM: move.b #$20,width_pixels(a0); move.b #$20,height_pixels(a0)
    private static final int HALF_WIDTH = 0x20;
    private static final int HALF_HEIGHT = 0x20;

    // ROM: move.w #-$400,x_vel(a1); move.w #-$600,y_vel(a1)
    private static final int LAUNCH_X_SPEED = 0x400;
    private static final int LAUNCH_Y_SPEED = 0x600;

    // ROM: move.w #$10,$34(a0) and move.b #60,2(a2)
    private static final int LAUNCH_RECOVERY_FRAMES = 0x10;
    private static final int REGRAB_COOLDOWN_FRAMES = 60;
    private static final int RECOVERY_EXPAND_CUTOFF = 0x0C;

    private static final int BODY_FRAME_IDLE = 0;
    private static final int BODY_FRAME_MOVING = 4;
    private static final int WHEEL_FRAME_COUNT = 4;
    private static final int CHAIN_SEGMENT_FRAME = 5;
    private static final int CHAIN_TERMINAL_FRAME = 6;
    private static final int CHAIN_SEGMENT_CAP = 8;
    private static final int CHAIN_STEP_X = 0x18;
    private static final int CHAIN_STEP_Y = 0x30;
    private static final int CHAIN_X_OFFSET = 0x0C;
    private static final int CHAIN_Y_OFFSET = 0x08;

    private static final int HANDLE_X_OFFSET = 0x26;
    private static final int HANDLE_Y_OFFSET = 0x2E;
    private static final int HANDLE_HALF_RANGE = 0x0C;
    private static final int HANDLE_RELEASE_BONUS = 0x08;

    private static final int PLAYER_SLOT_COUNT = 2;
    private static final int PLAYER_HANG_ANIM = Sonic3kAnimationIds.GET_UP.id();

    private static final class ExtensionGrabState {
        boolean grabbed;
        int releaseCooldown;
    }

    private int anchorX;
    private int anchorY;
    private boolean flipped;
    private int targetExtension;
    private final PulleyChainChild chainChild;
    private final AbstractPlayableSprite[] grabbedPlayers = new AbstractPlayableSprite[PLAYER_SLOT_COUNT];
    private final boolean[] grabbed = new boolean[PLAYER_SLOT_COUNT];
    private final int[] releaseCooldown = new int[PLAYER_SLOT_COUNT];
    private AbstractPlayableSprite nativeP2Owner;
    private final Map<PlayableEntity, ExtensionGrabState> extensionGrabStates = new IdentityHashMap<>();

    private int currentExtension;
    private int launchRecovery;
    private int mappingFrame = BODY_FRAME_IDLE;
    private int wheelFrame;
    private int wheelAnimTimer;

    public MGZPulleyObjectInstance(ObjectSpawn spawn) {
        this(spawn, true);
    }

    private MGZPulleyObjectInstance(ObjectSpawn spawn, boolean spawnChain) {
        super(spawn, "MGZPulley");
        this.anchorX = spawn.x();
        this.anchorY = spawn.y();
        this.flipped = (spawn.renderFlags() & 0x01) != 0;
        this.targetExtension = (spawn.subtype() & 0xFF) << 3;
        this.currentExtension = targetExtension;
        this.chainChild = spawnChain
                ? spawnChild(() -> new PulleyChainChild(
                        new ObjectSpawn(anchorX, anchorY, spawn.objectId(), spawn.subtype(),
                                spawn.renderFlags(), false, 0),
                        this))
                : null;
    }

    @Override
    public MGZPulleyObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MGZPulleyObjectInstance(ctx.spawn(), false);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        NativePlayerSlots slots = NativePlayerSlots.resolve(services().playerQuery(), playerEntity);
        reconcileNativeP2(slots.player(1), vIntRunCount);
        tickReleaseCooldowns();
        updateExtensionAndFrame();
        updatePlayerSlot(slots.player(0), 0, vIntRunCount);
        updatePlayerSlot(slots.player(1), 1, vIntRunCount);
        updateExtensionPlayers(slots, vIntRunCount);
        updateDynamicSpawn(anchorX, anchorY);
    }

    @Override
    public void onUnload() {
        cleanupForRemoval();
    }

    @Override
    public void setDestroyed(boolean destroyed) {
        if (destroyed && !isDestroyed()) {
            cleanupForRemoval();
        }
        super.setDestroyed(destroyed);
    }

    private void cleanupForRemoval() {
        releaseAllGrabbedPlayers();
        if (chainChild != null) {
            chainChild.setDestroyed(true);
        }
    }

    private void tickReleaseCooldowns() {
        for (int i = 0; i < PLAYER_SLOT_COUNT; i++) {
            if (releaseCooldown[i] > 0) {
                releaseCooldown[i]--;
            }
        }
        for (ExtensionGrabState state : extensionGrabStates.values()) {
            if (state.releaseCooldown > 0) {
                state.releaseCooldown--;
            }
        }
    }

    private void releaseAllGrabbedPlayers() {
        for (int i = 0; i < PLAYER_SLOT_COUNT; i++) {
            if (grabbed[i] || grabbedPlayers[i] != null) {
                releasePlayer(grabbedPlayers[i], i, 0, false);
            }
        }
        for (Map.Entry<PlayableEntity, ExtensionGrabState> entry : extensionGrabStates.entrySet()) {
            if (entry.getKey() instanceof AbstractPlayableSprite player && entry.getValue().grabbed) {
                releaseExtensionPlayer(player, entry.getValue(), 0, false);
            }
        }
        extensionGrabStates.clear();
    }

    private void updateExtensionAndFrame() {
        int motionDirection = 0;
        // ROM tests only $38(a0), the P1 grab byte, even though the adjacent
        // $39(a0) byte independently tracks P2.  Therefore a P2-only rider
        // does not hold the pulley retracted: loc_34900 takes the ordinary
        // target-extension path while sub_349A2 still carries P2 at the moving
        // handle (sonic3k.asm:71178-71224,71242-71345).
        boolean primaryPlayerGrabbed = grabbed[0];

        if (!primaryPlayerGrabbed) {
            if (currentExtension < targetExtension) {
                currentExtension += 2;
                motionDirection = -1;
            } else if (currentExtension > targetExtension) {
                currentExtension -= 2;
                motionDirection = 1;
            }
        } else if (launchRecovery == 0) {
            if (currentExtension != 0) {
                currentExtension -= 4;
                if (currentExtension < 0) {
                    currentExtension = 0;
                }
                motionDirection = 1;
            }
        } else {
            launchRecovery--;
            if (launchRecovery >= RECOVERY_EXPAND_CUTOFF) {
                int expandedTarget = targetExtension + HANDLE_RELEASE_BONUS;
                if (currentExtension < expandedTarget) {
                    currentExtension += 2;
                    if (currentExtension > expandedTarget) {
                        currentExtension = expandedTarget;
                    }
                    motionDirection = -1;
                }
            }
        }

        if (motionDirection != 0) {
            wheelAnimTimer--;
            if (wheelAnimTimer < 0) {
                wheelAnimTimer = 1;
                wheelFrame = (wheelFrame + motionDirection) & (WHEEL_FRAME_COUNT - 1);
            }
        }

        mappingFrame = motionDirection == 0 ? BODY_FRAME_IDLE : BODY_FRAME_MOVING;
    }

    private void updatePlayerSlot(AbstractPlayableSprite player, int slot, int vIntRunCount) {
        AbstractPlayableSprite tracked = grabbedPlayers[slot];
        if (grabbed[slot]) {
            updateGrabbedPlayer(tracked != null ? tracked : player, slot, vIntRunCount);
            return;
        }
        if (player == null || releaseCooldown[slot] > 0) {
            return;
        }
        tryCapturePlayer(player, slot);
    }

    private void updateExtensionPlayers(NativePlayerSlots nativeSlots, int frameCounter) {
        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        IdentityHashMap<PlayableEntity, Boolean> liveExtensions = new IdentityHashMap<>();
        for (PlayableEntity participant : participants) {
            if (participant == nativeSlots.p1() || participant == nativeSlots.p2()
                    || !(participant instanceof AbstractPlayableSprite player)) {
                continue;
            }
            liveExtensions.put(player, Boolean.TRUE);
            ExtensionGrabState state = extensionGrabStates.computeIfAbsent(player, ignored -> new ExtensionGrabState());
            if (state.grabbed) {
                updateGrabbedExtensionPlayer(player, state, frameCounter);
            } else if (state.releaseCooldown == 0) {
                tryCaptureExtensionPlayer(player, state);
            }
        }
        extensionGrabStates.entrySet().removeIf(entry -> {
            if (liveExtensions.containsKey(entry.getKey())) {
                return false;
            }
            if (entry.getKey() instanceof AbstractPlayableSprite player && entry.getValue().grabbed) {
                releaseExtensionPlayer(player, entry.getValue(), frameCounter, false);
            }
            return true;
        });
    }

    private void reconcileNativeP2(AbstractPlayableSprite currentNativeP2, int frameCounter) {
        if (nativeP2Owner == currentNativeP2) {
            return;
        }

        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (nativeP2Owner != null) {
            ExtensionGrabState demotedState = new ExtensionGrabState();
            demotedState.grabbed = grabbed[1];
            demotedState.releaseCooldown = releaseCooldown[1];
            clearNativeP2State();
            if (containsIdentity(participants, nativeP2Owner)) {
                extensionGrabStates.put(nativeP2Owner, demotedState);
            } else if (demotedState.grabbed) {
                releaseExtensionPlayer(nativeP2Owner, demotedState, frameCounter, false);
            }
        } else {
            clearNativeP2State();
        }

        ExtensionGrabState promotedState = currentNativeP2 == null
                ? null
                : extensionGrabStates.remove(currentNativeP2);
        if (promotedState != null) {
            grabbed[1] = promotedState.grabbed;
            releaseCooldown[1] = promotedState.releaseCooldown;
            grabbedPlayers[1] = promotedState.grabbed ? currentNativeP2 : null;
        }
        nativeP2Owner = currentNativeP2;
    }

    private void clearNativeP2State() {
        grabbed[1] = false;
        grabbedPlayers[1] = null;
        releaseCooldown[1] = 0;
    }

    private static boolean containsIdentity(List<PlayableEntity> participants, PlayableEntity candidate) {
        for (PlayableEntity participant : participants) {
            if (participant == candidate) {
                return true;
            }
        }
        return false;
    }

    private void tryCaptureExtensionPlayer(AbstractPlayableSprite player, ExtensionGrabState state) {
        if (player.isObjectControlled() || player.isControlLocked() || player.getDead() || player.isDebugMode()) {
            return;
        }
        int handleX = computeHandleX();
        int handleY = computeHandleY();
        int dx = player.getCentreX() - handleX;
        int dy = player.getCentreY() - handleY;
        int approachSpeed = flipped ? -player.getXSpeed() : player.getXSpeed();
        if (dx < -HANDLE_HALF_RANGE || dx >= HANDLE_HALF_RANGE
                || dy < -HANDLE_HALF_RANGE || dy >= HANDLE_HALF_RANGE || approachSpeed >= 0) {
            return;
        }
        state.grabbed = true;
        applyCapturedPlayerState(player, handleX, handleY);
        launchRecovery = LAUNCH_RECOVERY_FRAMES;
        services().playSfx(Sonic3kSfx.PULLEY_GRAB.id);
    }

    private void updateGrabbedExtensionPlayer(AbstractPlayableSprite player, ExtensionGrabState state, int frameCounter) {
        if (player.getDead() || player.isDebugMode() || player.isHurt() || isPlayerOffScreen(player)) {
            releaseExtensionPlayer(player, state, frameCounter, false);
        } else if (player.isJumpPressed()) {
            releaseExtensionPlayer(player, state, frameCounter, true);
        } else {
            applyCapturedPlayerState(player, computeHandleX(), computeHandleY());
        }
    }

    private void releaseExtensionPlayer(AbstractPlayableSprite player, ExtensionGrabState state,
                                        int frameCounter, boolean launch) {
        state.grabbed = false;
        state.releaseCooldown = REGRAB_COOLDOWN_FRAMES;
        boolean stillOwned = ownsPulleyControl(player);
        if (stillOwned) {
            player.releaseFromObjectControl(frameCounter);
            player.setOnObject(false);
            player.setControlLocked(false);
            clearRidingObject(player);
        }
        if (launch && stillOwned) {
            applyLaunch(player);
        }
    }

    private void tryCapturePlayer(AbstractPlayableSprite player, int slot) {
        if (player.isObjectControlled() || player.isControlLocked()) {
            return;
        }
        if (player.getDead() || player.isDebugMode()) {
            return;
        }

        int handleX = computeHandleX();
        int handleY = computeHandleY();
        int dx = player.getCentreX() - handleX;
        if (dx < -HANDLE_HALF_RANGE || dx >= HANDLE_HALF_RANGE) {
            return;
        }
        int dy = player.getCentreY() - handleY;
        if (dy < -HANDLE_HALF_RANGE || dy >= HANDLE_HALF_RANGE) {
            return;
        }

        int approachSpeed = player.getXSpeed();
        if (flipped) {
            approachSpeed = -approachSpeed;
        }
        if (approachSpeed >= 0) {
            return;
        }

        capturePlayer(player, slot, handleX, handleY);
    }

    private void capturePlayer(AbstractPlayableSprite player, int slot, int handleX, int handleY) {
        grabbed[slot] = true;
        grabbedPlayers[slot] = player;

        applyCapturedPlayerState(player, handleX, handleY);

        launchRecovery = LAUNCH_RECOVERY_FRAMES;
        services().playSfx(Sonic3kSfx.PULLEY_GRAB.id);
    }

    private void applyCapturedPlayerState(AbstractPlayableSprite player, int handleX, int handleY) {
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, handleX);
        NativePositionOps.writeYPosPreserveSubpixel(player, handleY);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setAnimationId(PLAYER_HANG_ANIM);
        player.setDirection(flipped ? Direction.LEFT : Direction.RIGHT);

    }

    private void updateGrabbedPlayer(AbstractPlayableSprite player, int slot, int vIntRunCount) {
        if (player == null || player.getDead() || player.isDebugMode() || player.isHurt()
                || isPlayerOffScreen(player)) {
            releasePlayer(player, slot, vIntRunCount, false);
            return;
        }

        if (player.isJumpPressed()) {
            releasePlayer(player, slot, vIntRunCount, true);
            return;
        }

        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, computeHandleX());
        NativePositionOps.writeYPosPreserveSubpixel(player, computeHandleY());
        player.setDirection(flipped ? Direction.LEFT : Direction.RIGHT);
    }

    private boolean isPlayerOffScreen(AbstractPlayableSprite player) {
        // ROM sub_349BA consumes the sign bit already published in the
        // playable's render_flags byte, not a fresh point-in-camera test
        // (sonic3k.asm:71246-71249). BuildSprites keeps a player visible across
        // its width/height margin, which matters while a pulley carries P2
        // just below the nominal 224px viewport.
        if (player.hasRenderFlagOnScreenState()) {
            return !player.isRenderFlagOnScreen();
        }
        return services().camera() != null
                && !services().camera().isVisibleForRenderFlag(player);
    }

    private void releasePlayer(AbstractPlayableSprite player, int slot, int frameCounter, boolean launch) {
        grabbed[slot] = false;
        grabbedPlayers[slot] = null;
        releaseCooldown[slot] = REGRAB_COOLDOWN_FRAMES;

        if (player == null) {
            return;
        }

        boolean stillOwned = ownsPulleyControl(player);
        if (stillOwned) {
            player.setOnObject(false);
            player.setControlLocked(false);
            player.releaseFromObjectControl(frameCounter);
            clearRidingObject(player);
        }

        if (!launch || !stillOwned) {
            return;
        }
        applyLaunch(player);
    }

    private boolean ownsPulleyControl(AbstractPlayableSprite player) {
        return player.isObjectControlled()
                && player.isObjectControlSuppressesMovement()
                && (player.getAnimationId() == PLAYER_HANG_ANIM
                    || player.getDead() || player.isHurt() || player.isDebugMode());
    }

    private void applyLaunch(AbstractPlayableSprite player) {
        player.setXSpeed((short) (flipped ? LAUNCH_X_SPEED : -LAUNCH_X_SPEED));
        player.setYSpeed((short) -LAUNCH_Y_SPEED);
        player.setGSpeed((short) 0);
        player.setAir(true);
        player.applyRollingRadii(false);
        player.setRollingFlagPreserveRadii(true);
        player.setGroundMode(GroundMode.GROUND);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        player.setDirection(flipped ? Direction.LEFT : Direction.RIGHT);
    }

    private void clearRidingObject(AbstractPlayableSprite player) {
        if (services().objectManager() != null) {
            services().objectManager().clearRidingObject(player);
        }
    }

    private int computeHandleX() {
        int handleX = anchorX - HANDLE_X_OFFSET;
        int xAdjust = currentExtension >> 1;
        if (flipped) {
            handleX += HANDLE_X_OFFSET * 2;
            xAdjust = -xAdjust;
        }
        return handleX - xAdjust;
    }

    private int computeHandleY() {
        return anchorY + HANDLE_Y_OFFSET + currentExtension;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    @Override
    public int getX() {
        return anchorX;
    }

    @Override
    public int getY() {
        return anchorY;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ART_KEY);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(wheelFrame, anchorX, anchorY, flipped, false);
        if (mappingFrame == BODY_FRAME_MOVING) {
            renderer.drawFrameIndex(BODY_FRAME_MOVING, anchorX, anchorY, flipped, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (ctx == null) {
            return;
        }
        ctx.drawRect(anchorX, anchorY, HALF_WIDTH, HALF_HEIGHT, 0.9f, 0.7f, 0.1f);
        ctx.drawLine(anchorX, anchorY, computeHandleX(), computeHandleY(), 0.9f, 0.7f, 0.1f);
    }

    private static final class PulleyChainChild extends AbstractObjectInstance implements RewindRecreatable {
        private final MGZPulleyObjectInstance parent;

        PulleyChainChild(ObjectSpawn spawn, MGZPulleyObjectInstance parent) {
            super(spawn, "MGZPulleyChain");
            this.parent = parent;
        }

        @Override
        public PulleyChainChild recreateForRewind(RewindRecreateContext ctx) {
            MGZPulleyObjectInstance restoredParent =
                    RewindRecreateObjectLinks.nearestLiveObject(ctx, MGZPulleyObjectInstance.class);
            return new PulleyChainChild(ctx.spawn(), restoredParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return parent.anchorX;
        }

        @Override
        public int getY() {
            return parent.anchorY;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(CHAIN_PRIORITY_BUCKET);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(ART_KEY);
            if (renderer == null) {
                return;
            }

            int distance = parent.currentExtension + 0x18;
            int segmentCount = distance / CHAIN_STEP_Y;
            int remainder = distance % CHAIN_STEP_Y;
            if (segmentCount == CHAIN_SEGMENT_CAP) {
                remainder = CHAIN_STEP_Y;
            } else {
                segmentCount++;
            }

            int x = parent.anchorX + (parent.flipped ? CHAIN_X_OFFSET : -CHAIN_X_OFFSET);
            int y = parent.anchorY - CHAIN_Y_OFFSET + remainder;
            int xAdjust = remainder >> 1;
            x += parent.flipped ? xAdjust : -xAdjust;

            for (int i = 0; i < segmentCount; i++) {
                int frame = (i == segmentCount - 1) ? CHAIN_TERMINAL_FRAME : CHAIN_SEGMENT_FRAME;
                renderer.drawFrameIndex(frame, x, y, parent.flipped, false);
                x += parent.flipped ? CHAIN_STEP_X : -CHAIN_STEP_X;
                y += CHAIN_STEP_Y;
            }
        }

        @Override
        public void appendDebugRenderCommands(DebugRenderContext ctx) {
            if (ctx == null) {
                return;
            }
            ctx.drawLine(parent.anchorX, parent.anchorY, parent.computeHandleX(),
                    parent.computeHandleY(), 0.4f, 0.8f, 0.9f);
        }
    }
}
