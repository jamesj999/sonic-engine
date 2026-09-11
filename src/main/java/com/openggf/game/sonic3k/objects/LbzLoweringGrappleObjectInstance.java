package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

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

        updateExtension();
        NativePlayerSlots slots = nativePlayerSlots(playerEntity);
        updatePlayerSlot(slots.player(0), 0, vIntRunCount);
        updatePlayerSlot(slots.player(1), 1, vIntRunCount);
        updateDynamicSpawn(anchorX, currentY);
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

    private NativePlayerSlots nativePlayerSlots(PlayableEntity updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        PlayableEntity main = query.mainPlayerOrNull();
        if (!(main instanceof AbstractPlayableSprite) && updatePlayer instanceof AbstractPlayableSprite) {
            main = updatePlayer;
        }

        AbstractPlayableSprite p1 = (main instanceof AbstractPlayableSprite sprite) ? sprite : null;
        AbstractPlayableSprite p2 = null;
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (candidate == main || !(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            p2 = sprite;
            break;
        }
        if (p2 == p1) {
            p2 = null;
        }
        return new NativePlayerSlots(p1, p2);
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

    private record NativePlayerSlots(AbstractPlayableSprite p1, AbstractPlayableSprite p2) {
        private AbstractPlayableSprite player(int slot) {
            return switch (slot) {
                case 0 -> p1;
                case 1 -> p2;
                default -> null;
            };
        }
    }
}
