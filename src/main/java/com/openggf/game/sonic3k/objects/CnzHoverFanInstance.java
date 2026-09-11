package com.openggf.game.sonic3k.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x46 - CNZ Hover Fan ({@code Obj_CNZHoverFan}).
 * <p>
 * ROM behavior:
 * <ul>
 *   <li>{@code Map_CNZHoverFan} with {@code ArtTile_CNZMisc+$97}</li>
 *   <li>Uses the sign bit of the subtype to select the active fan path</li>
 *   <li>Initial mapping frame comes from subtype bits 4-6 when the sign bit is set</li>
 *   <li>Applies the ROM lift window from {@code sub_30F84}</li>
 *   <li>Sets {@code air}, zeroes {@code y_vel}, seeds {@code ground_vel = 1}</li>
 *   <li>Only seeds flip motion once per player, matching the {@code flip_angle} gate</li>
 * </ul>
 *
 * <p>Like the ROM object, this instance keeps center coordinates in world space.
 * The visible sheet is drawn directly at the spawn center, and the lift window is
 * calculated from the same center-position deltas used by the original routine.
 */
public final class CnzHoverFanInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int PRIORITY = 0x280;

    // ROM: move.b #$10,width_pixels(a0) / move.b #$10,height_pixels(a0)
    private static final int HALF_WIDTH = 0x10;
    private static final int HALF_HEIGHT = 0x10;

    private static final int FLIP_INITIAL = 1;
    private static final int FLIP_SPEED = 8;
    private static final int FLIPS_REMAINING = 0x7F;
    private static final int X_OSC_OFFSET = 0x0C;    // ROM Oscillating_table+$0E, minus control word.
    private static final int LIFT_OSC_OFFSET = 0x14; // ROM Oscillating_table+$16, minus control word.

    private int subtype;
    private boolean activeVariant;
    private boolean xFlipped;
    private int initialFrame;
    private int xWindowMin;
    private int xWindowMax;
    private int liftWindowMin;
    private int liftWindowMax;
    private int baseX;
    private int baseY;
    private int currentX;
    private int renderFrame;

    @Override
    public int getExecutionSlotIndex() {
        ObjectServices svc = tryServices();
        if (svc == null || spawn.layoutIndex() < 0 || !activeVariant) {
            return super.getExecutionSlotIndex();
        }
        return resolveActiveVariantExecutionSlot(
                svc.objectManager().activeObjectsOfType(CnzHoverFanInstance.class));
    }

    int resolveActiveVariantExecutionSlot(List<CnzHoverFanInstance> activeFans) {
        // Active fans run loc_31E36/sub_31E96 in native SST order. Each fan writes y_pos before
        // the next fan tests its influence band, so adjacent overlapping fans must retain their
        // layout-derived order when dynamic slot ownership reverses them. Static loc_31E68 fans
        // keep their owned slots; their ROM path does not perform this lift operation.
        int ownSlot = getSlotIndex();
        if (ownSlot < 0 || activeFans == null || !activeVariant) {
            return ownSlot;
        }
        for (CnzHoverFanInstance other : activeFans) {
            if (other == null || other == this || !other.activeVariant
                    || other.spawn.layoutIndex() < 0
                    || Math.abs(other.getSlotIndex() - ownSlot) != 1
                    || !influenceWindowsOverlap(other)) {
                continue;
            }
            boolean slotOrder = ownSlot < other.getSlotIndex();
            boolean layoutOrder = spawn.layoutIndex() < other.spawn.layoutIndex();
            if (slotOrder != layoutOrder) {
                return other.getSlotIndex();
            }
        }
        return ownSlot;
    }

    private boolean influenceWindowsOverlap(CnzHoverFanInstance other) {
        int thisX = resolveCurrentX();
        int otherX = other.resolveCurrentX();
        int thisLeft = thisX - xWindowMin;
        int thisRight = thisLeft + xWindowMax - 1;
        int otherLeft = otherX - other.xWindowMin;
        int otherRight = otherLeft + other.xWindowMax - 1;
        if (thisRight < otherLeft || otherRight < thisLeft) {
            return false;
        }

        int osc = OscillationManager.getByte(LIFT_OSC_OFFSET);
        int thisTop = baseY - osc - liftWindowMin;
        int thisBottom = baseY - osc + 0x2F;
        int otherTop = other.baseY - osc - other.liftWindowMin;
        int otherBottom = other.baseY - osc + 0x2F;
        return thisBottom >= otherTop && otherBottom >= thisTop;
    }

    public CnzHoverFanInstance(ObjectSpawn spawn) {
        super(spawn, "CNZHoverFan");
        this.subtype = spawn.subtype();
        this.activeVariant = (subtype & 0x80) != 0;
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.initialFrame = activeVariant ? ((subtype & 0x70) >> 4) : 0;
        this.xWindowMin = (subtype & 0x70) + 0x18;
        this.xWindowMax = xWindowMin << 1;
        this.liftWindowMin = ((subtype & 0x0F) + 4) << 4;
        this.liftWindowMax = liftWindowMin + 0x30;
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentX = baseX;
        this.renderFrame = initialFrame;
    }

    @Override
    public CnzHoverFanInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CnzHoverFanInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        currentX = resolveCurrentX();
        updateDynamicSpawn(currentX, baseY);

        List<PlayableEntity> participants = services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        if (playerEntity instanceof AbstractPlayableSprite player && !participants.contains(player)) {
            ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
            withUpdatePlayer.add(player);
            withUpdatePlayer.addAll(participants);
            participants = withUpdatePlayer;
        }

        boolean captured = false;
        for (PlayableEntity participant : participants) {
            if (participant instanceof AbstractPlayableSprite sprite) {
                captured |= tryCapture(sprite);
            }
        }

        if (captured && ((vIntRunCount + 1) & 0x1F) == 0) {
            try {
                services().playSfx(Sonic3kSfx.HOVERPAD.id);
            } catch (Exception ignored) {
                // Audio is unavailable in some test setups.
            }
        }
    }

    private int resolveCurrentX() {
        if (!activeVariant || !xFlipped) {
            return baseX;
        }

        // ROM: loc_30F12 -> Oscillating_table+$0E - $30 + saved X.
        // OscillationManager stores the table after the ROM's 2-byte control word.
        return baseX + OscillationManager.getByte(X_OSC_OFFSET) - 0x30;
    }

    private boolean tryCapture(AbstractPlayableSprite player) {
        if (player == null || player.isObjectControlled() || player.isHurt() || player.getDead()) {
            return false;
        }

        if (!isWithinXWindow(player)) {
            return false;
        }

        int adjustedBand = rawLiftBand(player);
        if (adjustedBand < 0 || adjustedBand >= liftWindowMax) {
            return false;
        }

        // ROM sub_31E96:
        //   sub.w $36,d1
        //   bcs.s loc_31EDE
        //   not.w d1
        //   add.w d1,d1
        adjustedBand = (short) ((adjustedBand - liftWindowMin) & 0xFFFF);
        if (adjustedBand >= 0) {
            adjustedBand = ~adjustedBand;
            adjustedBand = (adjustedBand + adjustedBand) & 0xFFFF;
        }

        adjustedBand = (short) ((adjustedBand + liftWindowMin) & 0xFFFF);
        adjustedBand = (short) -adjustedBand;
        adjustedBand >>= 4;

        player.setCentreYPreserveSubpixel((short) (player.getCentreY() + adjustedBand));
        player.setAir(true);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 1);
        player.setRollingJump(false);
        player.setDoubleJumpFlag(0);
        player.setJumping(false);

        if (player.getFlipAngle() == 0) {
            player.setFlipAngle(FLIP_INITIAL);
            player.setAnimationId(0);
            player.setFlipsRemaining(FLIPS_REMAINING);
            player.setFlipSpeed(FLIP_SPEED);
        }

        return true;
    }

    private boolean isWithinXWindow(AbstractPlayableSprite player) {
        int dx = player.getCentreX() - currentX;
        int xBand = dx + xWindowMin;
        return xBand >= 0 && xBand < xWindowMax;
    }

    private int rawLiftBand(AbstractPlayableSprite player) {
        int osc = OscillationManager.getByte(LIFT_OSC_OFFSET);
        return player.getCentreY() - baseY + osc + liftWindowMin;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = getRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_HOVER_FAN);
        if (renderer != null && renderer.isReady()) {
            boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
            renderer.drawFrameIndex(renderFrame, currentX, baseY, hFlip, vFlip);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public int getOutOfRangeReferenceX() {
        // Active moving hover fans end loc_31E36 by feeding the saved spawn X
        // from $30(a0) to Sprite_OnScreen_Test2 after writing the live
        // oscillating x_pos (docs/skdisasm/sonic3k.asm:67291,67327-67332,
        // 67349-67350). Static fans have currentX == baseX and use the same
        // Delete_Sprite_If_Not_In_Range result.
        return baseX;
    }

    int getRenderFrameForTest() {
        return renderFrame;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("sub=%02X active=%s flip=%s base=%04X,%04X curX=%04X frame=%02X ref=%04X",
                subtype & 0xFF,
                activeVariant,
                xFlipped,
                baseX & 0xFFFF,
                baseY & 0xFFFF,
                currentX & 0xFFFF,
                renderFrame & 0xFF,
                getOutOfRangeReferenceX() & 0xFFFF);
    }
}
