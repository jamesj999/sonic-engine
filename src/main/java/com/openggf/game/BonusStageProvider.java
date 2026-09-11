package com.openggf.game;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

import java.util.Objects;
import java.util.function.Function;

/**
 * Coordinator interface for bonus stage lifecycle.
 * Unlike special stages (which own their own rendering), bonus stages use
 * the normal level pipeline. This interface manages entry/exit state,
 * not frame updates or rendering.
 * Accessed via {@link GameServices#bonusStage()}.
 */
public interface BonusStageProvider {
    boolean hasBonusStages();

    /**
     * Whether held-key live rewind is supported while this bonus stage is
     * active. True only for stages whose per-frame simulation is fully
     * captured by the standard rewind adapters and faithfully reproduced by
     * the LevelFrameStep re-simulation stepper (Gumball / Pachinko). Stages
     * with a dedicated, not-yet-snapshotted runtime (Slot Machine) return
     * false so rewind stays disengaged for them.
     */
    default boolean supportsRewind() {
        return false;
    }

    /**
     * Describes an object that the owning game must inject when a bonus-stage
     * layout does not contain its ROM bootstrap object.
     */
    default BootstrapObject bootstrapObject(BonusStageType type) {
        return null;
    }

    BonusStageType selectBonusStage(int ringCount);
    void onEnter(BonusStageType type, BonusStageState savedState);
    void onExit();
    void onFrameUpdate();
    default void onDeferredSetupComplete() {}
    default boolean updateDuringLevelFrame() { return false; }
    default boolean suppressesDefaultCameraStep() { return false; }
    default boolean hasCompletedExitFadeToBlack() { return false; }
    default BonusStageType getActiveType() { return BonusStageType.NONE; }
    boolean isStageComplete();
    void requestExit();
    BonusStageRewards getRewards();
    int getZoneId(BonusStageType type);
    int getMusicId(BonusStageType type);
    BonusStageState getSavedState();

    /** Accumulate rings. ROM equivalent: add.w d0,(Saved_ring_count).w */
    default void addRings(int count) {}

    /** Accumulate lives. ROM equivalent: addq.b #1,(Life_count).w */
    default void addLife() {}

    /** Record shield awarded during bonus stage. */
    default void setAwardedShield(ShieldType type) {}

    record BootstrapObject(
            ObjectSpawn spawn,
            Class<? extends ObjectInstance> objectType,
            Function<ObjectSpawn, ? extends ObjectInstance> factory) {

        public BootstrapObject {
            Objects.requireNonNull(spawn, "spawn");
            Objects.requireNonNull(objectType, "objectType");
            Objects.requireNonNull(factory, "factory");
        }

        public boolean matches(ObjectInstance object) {
            return objectType.isInstance(object);
        }

        public ObjectInstance create() {
            return factory.apply(spawn);
        }
    }

    record BonusStageRewards(
            int rings, int lives,
            boolean shield, boolean fireShield,
            boolean lightningShield, boolean bubbleShield
    ) {
        public static BonusStageRewards none() {
            return new BonusStageRewards(0, 0, false, false, false, false);
        }
    }
}
