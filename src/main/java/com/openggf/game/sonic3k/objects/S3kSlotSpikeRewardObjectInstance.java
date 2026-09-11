package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Slot-machine spike reward child.
 *
 * <p>ROM reference: {@code Obj_SlotSpike}. The object interpolates its position toward the cage
 * center each frame (1/16th of remaining distance), and when its timer expires it drains one
 * bonus-stage ring if any remain, then deletes itself.
 */
public final class S3kSlotSpikeRewardObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int EXPIRY_FRAMES = 0x1E;
    private final S3kSlotStageController controller;
    private int framesRemaining = EXPIRY_FRAMES;
    private boolean active;

    // ROM $34/$38: 32-bit fixed-point position (pixel:16 | sub:16)
    private long currentX32;
    private long currentY32;
    // ROM $3C/$3E: target pixel position (cage center)
    private int targetX;
    private int targetY;
    private boolean suppressObjectManagerUpdate;

    public S3kSlotSpikeRewardObjectInstance(ObjectSpawn spawn, S3kSlotStageController controller) {
        super(spawn, "S3kSlotSpikeReward");
        this.controller = controller;
    }

    private S3kSlotSpikeRewardObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        S3kSlotStageController controller =
                S3kSlotRewindSupport.resolveSlotStageController(ctx.objectServices());
        return controller != null ? new S3kSlotSpikeRewardObjectInstance(ctx.spawn(), controller) : null;
    }

    /** Activates without position tracking (backward-compatible; position stays at spawn). */
    public void activate() {
        activate(spawn.x(), spawn.y(), spawn.x(), spawn.y());
    }

    /**
     * Activates with interpolated movement toward the cage center.
     *
     * <p>ROM reference: {@code Obj_SlotSpike} — spawned at a radial offset, converges on
     * {@code (centerX, centerY)} at 1/16th of the remaining distance each frame.
     */
    public void activate(int spawnX, int spawnY, int centerX, int centerY) {
        active = true;
        framesRemaining = EXPIRY_FRAMES;
        setDestroyed(false);
        this.currentX32 = (long) spawnX << 16;
        this.currentY32 = (long) spawnY << 16;
        this.targetX = centerX;
        this.targetY = centerY;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * Returns the current interpolated X position (pixel units).
     *
     * <p>Only meaningful while active.
     */
    public int getInterpolatedX() {
        return (int) (currentX32 >> 16);
    }

    /**
     * Returns the current interpolated Y position (pixel units).
     *
     * <p>Only meaningful while active.
     */
    public int getInterpolatedY() {
        return (int) (currentY32 >> 16);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (suppressObjectManagerUpdate) {
            return;
        }
        tickSlotRuntime(vIntRunCount, playerEntity);
    }

    public void tickSlotRuntime(int frameCounter, PlayableEntity playerEntity) {
        if (isDestroyed() || !active) {
            return;
        }

        // ROM Obj_SlotSpike interpolation:
        //   d0 = current - target ; asr.l #4,d0 ; sub.l d0,$34
        //   ↔ current += (target - current) >> 4
        long dx = ((long) targetX << 16) - currentX32;
        currentX32 += dx >> 4;
        long dy = ((long) targetY << 16) - currentY32;
        currentY32 += dy >> 4;

        if (--framesRemaining > 0) {
            return;
        }

        if (controller.consumeSpikeSound()) {
            services().playSfx(Sonic3kSfx.SPIKE_HIT.id);
        }
        int carriedRingCount = resolveCarriedRingCount(playerEntity);
        if (controller.consumeRewardRing(carriedRingCount)) {
            if (carriedRingCount > 0) {
                addLiveRings(playerEntity, -1);
            }
            services().addBonusStageRings(-1);
        }
        setDestroyed(true);
        active = false;
    }

    public void suppressObjectManagerUpdate() {
        suppressObjectManagerUpdate = true;
    }

    private int resolveCarriedRingCount(PlayableEntity playerEntity) {
        if (playerEntity instanceof com.openggf.sprites.playable.AbstractPlayableSprite sprite) {
            return sprite.getRingCount();
        }
        return services().levelGamestate() != null ? services().levelGamestate().getRings() : 0;
    }

    private void addLiveRings(PlayableEntity playerEntity, int amount) {
        if (playerEntity instanceof com.openggf.sprites.playable.AbstractPlayableSprite sprite) {
            sprite.addRings(amount);
            return;
        }
        if (services().levelGamestate() != null) {
            services().levelGamestate().addRings(amount);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(0);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!active || isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.SLOT_SPIKE_REWARD);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(0, getInterpolatedX(), getInterpolatedY(), false, false);
    }
}
