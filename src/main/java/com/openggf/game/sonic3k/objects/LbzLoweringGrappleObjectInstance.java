package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3K S3KL object $1F - Launch Base Zone Act 2 lowering grapple.
 *
 * <p>ROM reference: {@code Obj_LBZLoweringGrapple} and {@code sub_290F2}
 * ({@code sonic3k.asm:56687-56849}).
 */
public final class LbzLoweringGrappleObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {
    private static final int PLAYER_SLOT_COUNT = 2;
    private static final int STEP_PIXELS = 2; // ROM $3A.
    private static final int SUBTYPE_DISTANCE_MASK = 0x7F;
    private static final int SUBTYPE_START_LOWERED = 0x80;
    private static final int PLAYER_Y_OFFSET = 0x94;
    private static final int CAPTURE_Y_CHECK_OFFSET = 0x88;
    private static final int CAPTURE_X_OFFSET = 0x10;
    private static final int CAPTURE_X_RANGE = 0x20;
    private static final int CAPTURE_Y_RANGE = 0x18;
    private static final int NEUTRAL_RELEASE_COOLDOWN = 0x12;
    private static final int DIRECTIONAL_RELEASE_COOLDOWN = 0x3C;
    private static final int RELEASE_X_SPEED = 0x0200;
    private static final int RELEASE_Y_SPEED = -0x0380;
    private static final int WIDTH_PIXELS = 0x10;
    private static final int HEIGHT_PIXELS = 0x80;
    private static final int PRIORITY_BUCKET = 1; // ROM priority $80.
    private static final int PALETTE_LINE = 2;

    @RewindTransient(reason = "Constructor-derived from spawn x.")
    private final int anchorX;
    @RewindTransient(reason = "Constructor-derived from spawn y.")
    private final int baseY;
    @RewindTransient(reason = "Constructor-derived from spawn subtype.")
    private final int targetExtension;
    @RewindTransient(reason = "Constructor-derived from spawn subtype.")
    private final boolean startLoweredMode;
    private final boolean[] grabbed = new boolean[PLAYER_SLOT_COUNT];
    private final int[] cooldown = new int[PLAYER_SLOT_COUNT];
    private PlayableEntity player1Owner;
    private PlayableEntity player2Owner;
    private final Map<PlayableEntity, PlayerState> extensionStates = new IdentityHashMap<>();

    private int currentExtension;
    private int currentY;
    private int mappingFrame;

    public LbzLoweringGrappleObjectInstance(ObjectSpawn spawn) {
        super(spawn, "LBZLoweringGrapple");
        this.anchorX = spawn.x();
        this.baseY = spawn.y();
        this.targetExtension = (spawn.subtype() & SUBTYPE_DISTANCE_MASK) << 3;
        this.startLoweredMode = (spawn.subtype() & SUBTYPE_START_LOWERED) != 0;
        this.currentExtension = startLoweredMode ? targetExtension : 0;
        updatePositionAndFrame();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        List<PlayableEntity> participants = participants(playerEntity);
        bindNativeOwners(participants);
        releaseOmittedExtensions(participants, vIntRunCount);
        updateExtension();
        updatePlayerSlot(asSprite(participants, 0), 0, vIntRunCount);
        updatePlayerSlot(asSprite(participants, 1), 1, vIntRunCount);
        for (int i = 2; i < participants.size(); i++) {
            if (participants.get(i) instanceof AbstractPlayableSprite player) {
                updateExtensionPlayer(player, vIntRunCount);
            }
        }
        updateDynamicSpawn(anchorX, currentY);
    }

    private void updateExtensionPlayer(AbstractPlayableSprite player, int frameCounter) {
        PlayerState state = extensionStates.computeIfAbsent(player, ignored -> new PlayerState());
        boolean savedGrabbed = grabbed[1]; int savedCooldown = cooldown[1];
        grabbed[1] = state.grabbed; cooldown[1] = state.cooldown;
        updatePlayerSlot(player, 1, frameCounter);
        state.grabbed = grabbed[1]; state.cooldown = cooldown[1];
        grabbed[1] = savedGrabbed; cooldown[1] = savedCooldown;
    }

    private List<PlayableEntity> participants(PlayableEntity fallback) {
        List<PlayableEntity> players = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (fallback == null || players.contains(fallback)) return players;
        ArrayList<PlayableEntity> result = new ArrayList<>(players.size() + 1);
        result.add(fallback); result.addAll(players); return result;
    }

    private AbstractPlayableSprite asSprite(List<PlayableEntity> players, int index) {
        return index < players.size() && players.get(index) instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    private void bindNativeOwners(List<PlayableEntity> players) {
        player1Owner = bindOwner(player1Owner, players.isEmpty() ? null : players.get(0), 0);
        player2Owner = bindOwner(player2Owner, players.size() > 1 ? players.get(1) : null, 1);
    }

    private PlayableEntity bindOwner(PlayableEntity previous, PlayableEntity current, int slot) {
        if (previous == current) return current;
        if (previous == null && current != null && !extensionStates.containsKey(current)) return current;
        if (previous != null) extensionStates.put(previous, new PlayerState(grabbed[slot], cooldown[slot]));
        PlayerState restored = current == null ? null : extensionStates.remove(current);
        grabbed[slot] = restored != null && restored.grabbed;
        cooldown[slot] = restored == null ? 0 : restored.cooldown;
        return current;
    }

    private void releaseOmittedExtensions(List<PlayableEntity> players, int frameCounter) {
        for (PlayableEntity omitted : List.copyOf(extensionStates.keySet())) {
            if (players.stream().noneMatch(live -> live == omitted)) {
                PlayerState state = extensionStates.remove(omitted);
                if (state.grabbed && omitted instanceof AbstractPlayableSprite player) {
                    releaseOwnedControl(player, frameCounter);
                }
            }
        }
    }

    @Override
    public void onUnload() {
        releaseOwner(player1Owner, grabbed[0]);
        releaseOwner(player2Owner, grabbed[1]);
        extensionStates.forEach((player, state) -> releaseOwner(player, state.grabbed));
        extensionStates.clear(); grabbed[0] = grabbed[1] = false;
    }

    private void releaseOwner(PlayableEntity owner, boolean isGrabbed) {
        if (isGrabbed && owner instanceof AbstractPlayableSprite player) releaseOwnedControl(player, 0);
    }

    private void releaseOwnedControl(AbstractPlayableSprite player, int frameCounter) {
        if (player.isObjectControlled() && player.getAnimationId() == Sonic3kAnimationIds.HANG2.id()) {
            player.releaseFromObjectControl(frameCounter);
        }
    }

    private static final class PlayerState {
        boolean grabbed; int cooldown;
        PlayerState() { }
        PlayerState(boolean grabbed, int cooldown) { this.grabbed = grabbed; this.cooldown = cooldown; }
    }

    @Override
    public int getX() {
        return anchorX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return WIDTH_PIXELS;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return HEIGHT_PIXELS;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_LOWERING_GRAPPLE);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, anchorX, currentY, false, false, PALETTE_LINE);
        }
    }

    private void updateExtension() {
        boolean p1Grabbed = grabbed[0];
        boolean shouldExtend = startLoweredMode ? !p1Grabbed : p1Grabbed;
        if (shouldExtend) {
            if (currentExtension < targetExtension) {
                currentExtension = Math.min(targetExtension, currentExtension + STEP_PIXELS);
                updatePositionAndFrame();
            }
        } else if (currentExtension > 0) {
            currentExtension = Math.max(0, currentExtension - STEP_PIXELS);
            updatePositionAndFrame();
        }
    }

    private void updatePositionAndFrame() {
        currentY = baseY + currentExtension;
        mappingFrame = currentExtension == 0 ? 0 : (currentExtension >> 4) + 1;
    }

    private void updatePlayerSlot(AbstractPlayableSprite player, int slot, int vIntRunCount) {
        if (grabbed[slot]) {
            updateGrabbedPlayer(player, slot, vIntRunCount);
            return;
        }

        if (cooldown[slot] > 0) {
            cooldown[slot]--;
            if (cooldown[slot] > 0) {
                return;
            }
        }
        if (player != null) {
            tryCapturePlayer(player, slot);
        }
    }

    private void updateGrabbedPlayer(AbstractPlayableSprite player, int slot, int vIntRunCount) {
        if (player == null || !isCaptureEligible(player)) {
            releaseInvalidPlayer(player, slot, vIntRunCount);
            return;
        }

        int input = player.getLogicalInputState();
        // ROM sub_290F2 receives Ctrl_1/2_logical: the low byte is the A/B/C
        // press edge, while directional release speed comes from held bits.
        if (player.isLogicalJumpPressActive()) {
            releaseJumpingPlayer(player, slot, vIntRunCount, input);
            return;
        }

        NativePositionOps.writeYPosPreserveSubpixel(player, currentY + PLAYER_Y_OFFSET);
    }

    private void tryCapturePlayer(AbstractPlayableSprite player, int slot) {
        if (!isCaptureEligible(player) || player.isObjectControlled()) {
            return;
        }
        int dx = unsigned16(player.getCentreX() - anchorX + CAPTURE_X_OFFSET);
        if (dx >= CAPTURE_X_RANGE) {
            return;
        }
        int dy = unsigned16(player.getCentreY() - currentY - CAPTURE_Y_CHECK_OFFSET);
        if (dy >= CAPTURE_Y_RANGE) {
            return;
        }

        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, anchorX);
        NativePositionOps.writeYPosPreserveSubpixel(player, currentY + PLAYER_Y_OFFSET);
        player.setAnimationId(Sonic3kAnimationIds.HANG2.id());
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        grabbed[slot] = true;
        services().playSfx(Sonic3kSfx.SWITCH.id);
    }

    private boolean isCaptureEligible(AbstractPlayableSprite player) {
        return !player.getDead() && !player.isDebugMode() && !player.isHurt();
    }

    private void releaseInvalidPlayer(AbstractPlayableSprite player, int slot, int vIntRunCount) {
        clearGrab(player, slot, vIntRunCount, DIRECTIONAL_RELEASE_COOLDOWN);
    }

    private void releaseJumpingPlayer(AbstractPlayableSprite player, int slot, int vIntRunCount, int input) {
        boolean left = (input & AbstractPlayableSprite.INPUT_LEFT) != 0;
        boolean right = (input & AbstractPlayableSprite.INPUT_RIGHT) != 0;
        int releaseCooldown = (left || right) ? DIRECTIONAL_RELEASE_COOLDOWN : NEUTRAL_RELEASE_COOLDOWN;
        clearGrab(player, slot, vIntRunCount, releaseCooldown);

        if (left) {
            player.setXSpeed((short) -RELEASE_X_SPEED);
        }
        if (right) {
            player.setXSpeed((short) RELEASE_X_SPEED);
        }
        player.setYSpeed((short) RELEASE_Y_SPEED);
        player.setAir(true);
        player.setJumping(true);
        player.setRolling(true);
        player.setRollingJump(false);
        player.setFlipAngle(0);
        player.setAnimationId(Sonic3kAnimationIds.ROLL.id());
        // Obj_LBZLoweringGrapple runs after the native player slot. The jump
        // press that releases the handle has therefore already missed
        // Sonic_ShieldMoves for this frame; when our consolidated frame runs
        // the object before player movement, hide that same edge from the
        // airborne ability pass while preserving the held jump state.
        player.suppressNextJumpPress();
    }

    private void clearGrab(AbstractPlayableSprite player, int slot, int vIntRunCount, int releaseCooldown) {
        grabbed[slot] = false;
        cooldown[slot] = releaseCooldown;
        if (player == null) {
            return;
        }
        player.releaseFromObjectControl(vIntRunCount);
    }

    private static int unsigned16(int value) {
        return value & 0xFFFF;
    }

    int targetExtensionForTesting() {
        return targetExtension;
    }

    int currentExtensionForTesting() {
        return currentExtension;
    }

    int mappingFrameForTesting() {
        return mappingFrame;
    }

    boolean grabbedForTesting(int slot) {
        return grabbed[slot];
    }

    int cooldownForTesting(int slot) {
        return cooldown[slot];
    }

    String artKeyForTesting() {
        return Sonic3kObjectArtKeys.LBZ_LOWERING_GRAPPLE;
    }

}
