package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public class S3kSlotStageController {
    private final S3kSlotStageState stageState;
    private byte[] activeLayout;         // layout reference for collision checks during physics

    public S3kSlotStageController() {
        this(S3kSlotStageState.bootstrap());
    }

    public S3kSlotStageController(S3kSlotStageState stageState) {
        this.stageState = stageState;
    }

    public void bootstrap() {
        stageState.setStatTable(0);
        stageState.setScalarIndex1(0x40);
        stageState.setScalarIndex2(0);
        stageState.setPaletteCycleEnabled(false);
        stageState.clearCollision();
        stageState.setBounceTimer(0);
        stageState.resetRewardState();
        activeLayout = null;
    }

    /** ROM loc_4C16C: advance the shared word on every non-ejecting spike payout update. */
    public void advanceSpikePayout() {
        stageState.setScalarIndex2((stageState.scalarIndex2() + 1) & 0xFFFF);
    }

    /** ROM Obj_SlotSpike: unsigned word threshold, consumed at impact rather than spawn. */
    public boolean consumeSpikeSound() {
        if (stageState.scalarIndex2() < 5) {
            return false;
        }
        stageState.setScalarIndex2(0);
        return true;
    }

    /** Per-frame rotation: Stat_table += SStage_scalar_index_1 (lines 98776-98778). */
    public void tick() {
        stageState.setStatTable((stageState.rawStatTable() + stageState.scalarIndex1()) & 0xFFFF);
    }

    /** ROM loc_4BA80: accelerated rotation while the player is object-controlled. */
    public void tickObjectControlled() {
        stageState.setStatTable((stageState.rawStatTable() + (stageState.scalarIndex1() << 4)) & 0xFFFF);
    }

    public int scalarIndex() {
        return stageState.scalarIndex1();
    }

    public void setScalarIndex(int value) {
        stageState.setScalarIndex1(value);
    }

    /** ROM: neg.w (SStage_scalar_index_1).w — used by spike tile and cage release. */
    public void negateScalar() {
        stageState.negateScalarIndex1();
    }

    /** ROM $3A(a0): Set bounce timer on landing (sub_4BCB0 line 99028/99046). */
    public void setBounceTimer(int frames) {
        stageState.setBounceTimer(frames);
    }

    /** Tick bounce timer. Returns true if timer was active and just expired. */
    public boolean tickBounceTimer() {
        return stageState.tickBounceTimer();
    }

    public int bounceTimer() {
        return stageState.bounceTimer();
    }

    /** Set the active layout for collision checks during player physics. */
    public void setActiveLayout(byte[] layout) {
        this.activeLayout = layout;
    }

    public byte[] activeLayout() {
        return activeLayout;
    }

    /** ROM $30(a0): Store tile ID from last collision for tile interaction dispatch. */
    public void setLastCollision(int tileId, int layoutIndex) {
        stageState.setLastCollision(tileId, layoutIndex);
    }

    public void clearLastCollision() {
        stageState.clearCollision();
    }

    public int lastCollisionTileId() {
        return stageState.lastCollisionTileId();
    }

    public int lastCollisionIndex() {
        return stageState.lastCollisionIndex();
    }

    public void tickPlayer(S3kSlotBonusPlayer player, boolean left, boolean right,
                           boolean jump, int frameCounter) {
        // Input is handled by S3kSlotBonusPlayer.applyGroundMotion().
        player.setAngle((byte) stageState.angle());

        if (jump && player instanceof AbstractPlayableSprite sprite
                && sprite.isJumpJustPressed() && !sprite.getAir()) {
            int angle = (-((stageState.angle() & 0xFC)) - 0x40) & 0xFF;
            sprite.setXSpeed((short) ((TrigLookupTable.cosHex(angle) * 0x680) >> 8));
            sprite.setYSpeed((short) ((TrigLookupTable.sinHex(angle) * 0x680) >> 8));
            sprite.setAir(true);
            if (GameServices.audio() != null) {
                GameServices.audio().playSfx(Sonic3kSfx.JUMP.id);
            }
        }
    }

    /**
     * Returns the angle byte of Stat_table. The ROM reads it with
     * {@code move.b (Stat_table).w,d0} (e.g. sonic3k.asm:99325), which on a big-endian 68k
     * takes the <em>high</em> byte of the 16-bit accumulator; the low byte is the sub-angle
     * fraction that the per-frame {@code Stat_table += SStage_scalar_index_1}
     * (sonic3k.asm:98781-98783) advances. (This javadoc previously said "low byte" —
     * {@link S3kSlotStageState#angle()} was and remains correct.)
     */
    public int angle() {
        return stageState.angle();
    }

    /** Returns the full 16-bit Stat_table value (for exit sequence scalar math). */
    public int rawStatTable() {
        return stageState.rawStatTable();
    }

    public boolean isReelsFrozen() {
        return stageState.reelsFrozen();
    }

    public boolean isOptionCycleResolved() {
        return stageState.optionCycleState() == 0x18;
    }

    public void restartCaptureCycleIfResolved() {
        if (!isOptionCycleResolved()) {
            return;
        }
        stageState.setOptionCycleState(0x08);
        stageState.setOptionCycleCountdown(0);
        stageState.setOptionCycleSpinCycleCounter(0);
        stageState.setOptionCycleResolvedDisplayTimer(0);
        stageState.setOptionCycleLockProgress(0);
        stageState.setOptionCycleLastPrize(Integer.MIN_VALUE);
        stageState.setOptionCycleOffsets(0, 0, 0);
        stageState.setOptionCycleActiveReelIndex(0);
        stageState.setOptionCycleReelSubstates(0, 0, 0);
        stageState.setOptionCycleReelVelocities(0, 0, 0);
    }

    public void setPaletteCycleEnabled(boolean enabled) {
        stageState.setPaletteCycleEnabled(enabled);
    }

    public boolean isPaletteCycleEnabled() {
        return stageState.paletteCycleEnabled();
    }

    public void latchResolvedPrize(int prize, int cycleId) {
        if (cycleId == stageState.latchedPrizeCycleId()) {
            return;
        }
        stageState.setLatchedPrize(prize);
        stageState.setLatchedPrizeCycleId(cycleId);
    }

    public void latchResolvedPrizeForCapture(int prize) {
        stageState.setLatchedPrize(prize);
        stageState.setLatchedPrizeCycleId(stageState.latchedPrizeCycleId() + 1);
    }

    public int beginCapturePayout() {
        stageState.setReelsFrozen(true);
        return stageState.latchedPrize();
    }

    public void endCapturePayout() {
        stageState.setReelsFrozen(false);
        stageState.setLatchedPrize(0);
    }

    public void onRewardSpawned() {
        stageState.incrementActiveRewardObjects();
    }

    public void onRewardExpired() {
        stageState.decrementActiveRewardObjects();
    }

    public int activeRewardObjects() {
        return stageState.activeRewardObjects();
    }

    public void addRewardRing() {
        stageState.incrementRewardCounter();
    }

    public void queueRingReward() {
        stageState.enqueueRingRewardPosition(new int[0]);
    }

    public void queueRingRewardAt(int spawnX, int spawnY, int targetX, int targetY) {
        stageState.enqueueRingRewardPosition(new int[]{spawnX, spawnY, targetX, targetY});
    }

    public void queueSpikeReward() {
        stageState.enqueueSpikeRewardPosition(new int[0]);
    }

    public void queueSpikeRewardAt(int spawnX, int spawnY, int targetX, int targetY) {
        stageState.enqueueSpikeRewardPosition(new int[]{spawnX, spawnY, targetX, targetY});
    }

    public int[] consumePendingRingReward() {
        if (!stageState.hasPendingRingRewardPositions()) {
            return null;
        }
        int[] pos = stageState.pollRingRewardPosition();
        return pos != null ? pos : new int[0];
    }

    public int[] consumePendingSpikeReward() {
        if (!stageState.hasPendingSpikeRewardPositions()) {
            return null;
        }
        int[] pos = stageState.pollSpikeRewardPosition();
        return pos != null ? pos : new int[0];
    }

    public boolean consumeRewardRing() {
        return stageState.decrementRewardCounter();
    }

    public boolean consumeRewardRing(int carriedRingCount) {
        if (carriedRingCount + stageState.rewardCounter() <= 0) {
            return false;
        }
        if (stageState.rewardCounter() > 0) {
            return stageState.decrementRewardCounter();
        }
        stageState.decrementRewardCounterUnchecked();
        return true;
    }

    public int rewardCount() {
        return stageState.rewardCounter();
    }

    S3kSlotStageState stageStateForTest() {
        return stageState;
    }
}
