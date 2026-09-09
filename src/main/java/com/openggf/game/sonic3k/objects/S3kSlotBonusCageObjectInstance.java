package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotRomData;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;

import static com.openggf.physics.TrigLookupTable.cosHex;
import static com.openggf.physics.TrigLookupTable.sinHex;

/**
 * Slot-machine bonus cage.
 *
 * <p>ROM reference: {@code sub_4C014} / {@code loc_4BF62}, lines 99308-99557.
 */
public final class S3kSlotBonusCageObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int CAPTURE_HALF_SPAN = 0x18;
    private static final int CAPTURE_SPAN = 0x30;
    private static final int MAX_ACTIVE_REWARDS = 0x10;
    private static final int SPIKE_PENALTY_BUDGET = 0x64;
    private static final int RING_ANGLE_INCREMENT = 0x89;
    private static final int SPIKE_ANGLE_INCREMENT = 0x90;

    private static final short SNAP_X = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X;
    private static final short SNAP_Y = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y;
    private final S3kSlotStageController controller;

    private int cageState;
    private int waitTimer;
    private int rewardAngle;
    private int rewardsToSpawn;
    private boolean spawnRings;
    private int sfxCounter;
    private boolean payoutInitialized;

    private short currentX = SNAP_X;
    private short currentY = SNAP_Y;
    private int mappingFrame;
    private int animFrameTimer = 1;
    private int armDelayFrames;
    private boolean suppressObjectManagerUpdate;

    public S3kSlotBonusCageObjectInstance(ObjectSpawn spawn, S3kSlotStageController controller) {
        super(spawn, "S3kSlotBonusCage");
        this.controller = controller;
    }

    private S3kSlotBonusCageObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        S3kSlotStageController controller =
                S3kSlotRewindSupport.resolveSlotStageController(ctx.objectServices());
        return controller != null ? new S3kSlotBonusCageObjectInstance(ctx.spawn(), controller) : null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (suppressObjectManagerUpdate) {
            return;
        }
        tickSlotRuntime(vIntRunCount, playerEntity);
    }

    /**
     * {@code Obj_SlotBonus} owns its whole lifetime: the live routine
     * {@code loc_4BF9A} (sonic3k.asm:99324-99560) contains no {@code out_of_range}
     * macro, no {@code MarkObjGone} and no {@code Delete_Current_Sprite} on any
     * path, so the cage can never unload while the bonus stage is running -- it is
     * torn down only when the stage itself ends. Taking the shared camera-relative
     * unload instead frees the cage's SST slot, and because the cage sits in the
     * lowest dynamic slot the ROM keeps occupied, the next {@code AllocateObject}
     * (sonic3k.asm:37911-37914, forward scan from the first slot) hands that slot
     * to a freshly spawned {@code Obj_SlotRing}. A ring landing at or below the
     * cage's slot has already been passed by the ascending object walk, so it
     * loses the routine-0 tick it should have run on its own spawn frame and its
     * {@code $40} countdown reaches zero one frame late.
     */
    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    public void tickSlotRuntime(int frameCounter, PlayableEntity playerEntity) {
        tickSlotRuntime(frameCounter, playerEntity,
                playerEntity instanceof AbstractPlayableSprite player ? player.getCentreX() : SNAP_X,
                playerEntity instanceof AbstractPlayableSprite player ? player.getCentreY() : SNAP_Y);
    }

    public void tickSlotRuntime(int frameCounter, PlayableEntity playerEntity, int playerOriginX, int playerOriginY) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        updateAnimatedPosition(playerOriginX, playerOriginY);
        tickAnimation();

        if (player.isDebugMode()) {
            return;
        }

        switch (cageState) {
            case 0 -> updateCapture(player);
            case 1 -> updateSpawnRewards(player, frameCounter);
            case 2 -> updateRelease(player);
            case 3 -> updateCooldown();
            default -> {
            }
        }
    }

    public void suppressObjectManagerUpdate() {
        suppressObjectManagerUpdate = true;
    }

    private void updateCapture(AbstractPlayableSprite player) {
        if (armDelayFrames > 0) {
            armDelayFrames--;
            return;
        }
        if (player.isObjectControlled() || !isWithinCaptureRange(player)) {
            return;
        }

        // ROM loc_4C026 (sonic3k.asm:99395-99396) writes the capture position with
        // move.w #$460,x_pos(a1) / move.w #$430,y_pos(a1) -- a word-sized store that
        // only overwrites the pixel half of the 32-bit x_pos/y_pos, leaving the
        // subpixel half (x_sub/y_sub, 2 bytes further into the same long -- see
        // MoveSprite2's own comment at sonic3k.asm:36057) exactly as this frame's
        // own ground/air movement (sub_4BABC/sub_4BCB0 + MoveSprite2, already run
        // earlier in the same object dispatch) left it. setCentreX/setCentreY would
        // zero that subpixel fraction, which measurably diverges the trace (e.g.
        // TestS3kSlotsBonusTraceReplay frame 47: expected x_sub/y_sub 0x8800/0xE000
        // vs a wrongly-zeroed 0x0000/0x0000).
        NativePositionOps.writeXPosPreserveSubpixel(player, SNAP_X);
        NativePositionOps.writeYPosPreserveSubpixel(player, SNAP_Y);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setControlLocked(true);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAir(true);
        player.setOnObject(false);
        if (controller.isOptionCycleResolved()) {
            controller.restartCaptureCycleIfResolved();
        }
        spawnRings = false;
        rewardsToSpawn = 0;
        rewardAngle = 0;
        sfxCounter = 0;
        payoutInitialized = false;
        controller.setPaletteCycleEnabled(true);

        waitTimer = 0x78;
        cageState = 1;
    }

    private boolean isWithinCaptureRange(AbstractPlayableSprite player) {
        return isWithinCaptureSpan(player.getCentreX() - currentX)
                && isWithinCaptureSpan(player.getCentreY() - currentY);
    }

    /**
     * ROM {@code loc_4C026} (sonic3k.asm:99385-99394) tests each axis with
     * {@code sub.w x_pos(a0),d0 / addi.w #$18,d0 / cmpi.w #$30,d0 / bhs skip} --
     * a biased *unsigned* window, so the accepted range is the half-open
     * {@code [-$18, +$18)}, not the symmetric {@code |d| < $18} an abs()
     * comparison gives. The two differ on exactly one value, {@code d == -$18},
     * which the ROM captures and abs() rejects.
     */
    private static boolean isWithinCaptureSpan(int delta) {
        return Integer.compareUnsigned((delta + CAPTURE_HALF_SPAN) & 0xFFFF, CAPTURE_SPAN) < 0;
    }

    private void updateSpawnRewards(AbstractPlayableSprite player, int frameCounter) {
        // ROM's hold state (loc_4C0AA/loc_4C172, sonic3k.asm:99416-99509) never
        // writes x_pos/y_pos/x_vel/y_vel for the captured player again once
        // object_control is set by loc_4C026 -- the player's own routine already
        // skips ground/air movement entirely while object-controlled (loc_4BA62,
        // sonic3k.asm:98751-98752 tst.b object_control(a0)/bne). Re-snapping the
        // pixel position here every frame (as this used to) stomped the subpixel
        // fraction captureSlotOriginFromPlayer/syncPlayerToSlotOrigin otherwise
        // preserve, re-zeroing it on the very next tick after the capture fix
        // above and reproducing the same trace divergence one frame later.
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        if (!payoutInitialized) {
            if (!controller.isOptionCycleResolved()) {
                sfxCounter++;
                if ((sfxCounter & 0x0F) == 0) {
                    services().playSfx(Sonic3kSfx.SLOT_MACHINE.id);
                }
                return;
            }
            int payout = controller.beginCapturePayout();
            spawnRings = payout >= 0;
            rewardsToSpawn = spawnRings ? payout : SPIKE_PENALTY_BUDGET;
            rewardAngle = 0;
            payoutInitialized = true;
        }

        if ((frameCounter & 1) != 0 && controller.activeRewardObjects() < MAX_ACTIVE_REWARDS && rewardsToSpawn > 0) {
            int centerX = currentX;
            int centerY = currentY;
            if (spawnRings) {
                int spawnX = centerX + (cosHex(rewardAngle) >> 1);
                int spawnY = centerY + (sinHex(rewardAngle) >> 1);
                controller.queueRingRewardAt(spawnX, spawnY, centerX, centerY);
                rewardAngle = (rewardAngle + RING_ANGLE_INCREMENT) & 0xFF;
            } else {
                int spawnX = centerX + (cosHex(rewardAngle) >> 1);
                int spawnY = centerY + (sinHex(rewardAngle) >> 1);
                controller.queueSpikeRewardAt(spawnX, spawnY, centerX, centerY);
                rewardAngle = (rewardAngle + SPIKE_ANGLE_INCREMENT) & 0xFF;
            }
            rewardsToSpawn--;
        }

        if (rewardsToSpawn <= 0 && controller.activeRewardObjects() <= 0) {
            waitTimer = 8;
            cageState = 2;
            controller.setPaletteCycleEnabled(false);
        } else if (!spawnRings) {
            // loc_4C16C also runs on even frames, at the child limit, and while
            // the last spikes are travelling. loc_4C23E (ejection) bypasses it.
            controller.advanceSpikePayout();
        }
    }

    private void updateRelease(AbstractPlayableSprite player) {
        if ((controller.angle() & 0x3C) != 0) {
            return;
        }

        // ROM loc_4C250 (sonic3k.asm:99533-99552): GetSineCosine returns sin in
        // d0 / cos in d1 (sonic3k.asm:3014-3015), and the launch write order is
        // `move.w d0,x_vel(a1)` then `move.w d1,y_vel(a1)` -- i.e. x_vel is the
        // SINE term and y_vel is the COSINE term. Swapping these (cos->x,
        // sin->y, matching sub_4BBB2's jump-launch convention instead) produced
        // x_speed=-0x400/y_speed=0x0000 at the release angle used by
        // TestS3kSlotsBonusTraceReplay frame 332, where ROM expects the
        // opposite: x_speed=0x0000/y_speed=-0x400.
        int angle = controller.angle() & 0xFC;
        short vx = (short) (TrigLookupTable.sinHex(angle) * 4);
        short vy = (short) (TrigLookupTable.cosHex(angle) * 4);
        player.setXSpeed(vx);
        player.setYSpeed(vy);
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);
        player.setAir(true);
        controller.negateScalar();
        controller.endCapturePayout();

        waitTimer = 8;
        cageState = 3;
    }

    private void updateCooldown() {
        if (waitTimer > 0) {
            waitTimer--;
            return;
        }

        cageState = 0;
        rewardsToSpawn = 0;
        rewardAngle = 0;
        sfxCounter = 0;
        payoutInitialized = false;
        controller.setPaletteCycleEnabled(false);
    }

    private void updateAnimatedPosition(int playerX, int playerY) {
        int angle = controller.angle() & 0xFC;
        int sin = sinHex(angle);
        int cos = cosHex(angle);
        int dx = SNAP_X - playerX;
        int dy = SNAP_Y - playerY;
        int x = (((dx * cos) - (dy * sin)) >> 8) + playerX;
        int y = (((dx * sin) + (dy * cos)) >> 8) + playerY;
        currentX = (short) x;
        currentY = (short) y;
    }

    private void tickAnimation() {
        if (--animFrameTimer >= 0) {
            return;
        }
        animFrameTimer = 1;
        mappingFrame++;
        if (mappingFrame >= 6) {
            mappingFrame = 0;
        }
    }

    public int cageStateForTest() {
        return cageState;
    }

    public void suppressInitialCaptureOnce() {
        armDelayFrames = 1;
    }

    public boolean spawnsRingsForTest() {
        return spawnRings;
    }

    public int pendingRewardsForTest() {
        return rewardsToSpawn;
    }

    public short getCurrentX() {
        return currentX;
    }

    public short getCurrentY() {
        return currentY;
    }

    public int getMappingFrame() {
        return mappingFrame;
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
        // Slots uses the machine face visual, not the unused cage art.
    }
}
