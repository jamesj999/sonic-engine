package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;

/** Upright MGZ Knuckles capsule which releases the retained boss waiter. */
public final class MgzEndBossKnuxEggCapsuleInstance extends AbstractS3kUprightEggCapsuleInstance
        implements SpawnRewindRecreatable {
    private boolean completionSent;
    private MgzEndBossKnuxInstance owner;

    public MgzEndBossKnuxEggCapsuleInstance(ObjectSpawn spawn) {
        super(spawn, "MGZEndBossKnuxEggCapsule");
    }

    MgzEndBossKnuxEggCapsuleInstance(MgzEndBossKnuxInstance owner, ObjectSpawn spawn) {
        this(spawn);
        this.owner = owner;
    }

    MgzEndBossKnuxInstance ownerForTesting() { return owner; }

    @Override
    protected boolean locksNativeP2CpuOnOpen() {
        // ROM sub_865DE exempts Current_zone=2 (MGZ) from Ctrl_2_locked.
        return false;
    }

    @Override
    protected void updateAfterResultsStarted(int frameCounter, PlayableEntity player) {
        if (!completionSent && services().gameState() != null
                && services().gameState().isEndOfLevelFlag()) {
            completionSent = true;
            // The results object has finished. This is the engine equivalent
            // of the capsule clearing _unkFAA8 before the retained boss owner
            // executes loc_6C8F4's Restore_PlayerControl continuation.
            services().gameState().setEndOfLevelFlag(false);
            services().gameState().setEndOfLevelActive(false);
            signalOwnerResultsComplete();
        }
    }

    private void signalOwnerResultsComplete() {
        if (owner != null && !owner.isDestroyed()) owner.signalResultsComplete();
    }

    @Override
    public com.openggf.level.objects.AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        MgzEndBossKnuxInstance restored = RewindRecreateObjectLinks.nearestLiveObject(
                ctx, MgzEndBossKnuxInstance.class);
        return new MgzEndBossKnuxEggCapsuleInstance(restored, ctx.spawn());
    }
}
