package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

/**
 * CNZ-local wrapper for the standard upright egg capsule.
 *
 * <p>ROM anchor: {@code Obj_EggCapsule} as used by {@code Obj_CNZEndBoss}.
 *
 * <p>CNZ must not reuse {@link Aiz2EndEggCapsuleInstance} because that class
 * models the camera-relative floating route-8 capsule from AIZ2. It also
 * should not inherit the HCZ-specific geyser follow-up from
 * {@code HczEndBossEggCapsuleInstance}. CNZ instead reuses the shared upright
 * button/open/results behavior and reports completion back to the CNZ boss
 * sequence, which owns the later cannon launch into ICZ1.
 */
public final class CnzEggCapsuleInstance extends AbstractS3kUprightEggCapsuleInstance
        implements SpawnRewindRecreatable {
    public enum CompletionContinuation {
        NONE,
        CNZ_END_BOSS_SEQUENCE
    }

    private CompletionContinuation completionContinuation;
    private boolean resultsCompleteNotified;

    public CnzEggCapsuleInstance(ObjectSpawn spawn) {
        this(spawn, CompletionContinuation.NONE);
    }

    public CnzEggCapsuleInstance(ObjectSpawn spawn, CompletionContinuation completionContinuation) {
        super(spawn, "CNZEggCapsule");
        this.completionContinuation = completionContinuation != null
                ? completionContinuation
                : CompletionContinuation.NONE;
    }

    @Override
    protected void updateAfterResultsStarted(int frameCounter, com.openggf.game.PlayableEntity player) {
        if (services().gameState() != null && services().gameState().isEndOfLevelFlag()) {
            // Obj_LevelResults has already published the global completion
            // state. The boss's loc_6E724 consumes that state directly; this
            // capsule callback only retires the engine continuation.
            notifyResultsComplete();
        }
    }

    @Override
    protected S3kResultsScreenObjectInstance createResultsScreen(PlayerCharacter character, int act) {
        return new CnzResultsScreenObjectInstance(character, act);
    }

    public void forceResultsCompleteForTest() {
        notifyResultsComplete();
    }

    private void notifyResultsComplete() {
        if (resultsCompleteNotified) {
            return;
        }
        resultsCompleteNotified = true;
        if (services().gameState() != null) {
            services().gameState().setEndOfLevelFlag(false);
            services().gameState().setEndOfLevelActive(false);
        }
        if (completionContinuation == CompletionContinuation.CNZ_END_BOSS_SEQUENCE) {
            notifyCnzEndBossSequence();
        }
    }

    private void notifyCnzEndBossSequence() {
        if (services().objectManager() == null) {
            return;
        }
        for (ObjectInstance instance : services().objectManager().getActiveObjects()) {
            if (instance instanceof CnzEndBossInstance boss && !boss.isDestroyed()) {
                boss.onCapsuleResultsComplete();
                return;
            }
        }
    }
}
