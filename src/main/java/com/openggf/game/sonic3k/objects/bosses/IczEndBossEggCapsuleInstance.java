package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.objects.AbstractS3kUprightEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import com.openggf.level.objects.AbstractResultsScreen;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;

/**
 * Fixed-position ICZ2 post-boss egg capsule spawned by {@link IczEndBossInstance}.
 *
 * <p>ROM anchor: {@code Obj_EggCapsule} spawned by {@code Obj_ICZEndBoss} at
 * {@code x_pos=$4560, y_pos=$06A3}. ICZ uses the shared upright capsule route:
 * body solid {@code d1=$2B,d2=$18,d3=$18} plus top-button child
 * {@code d1=$1B,d2=4,d3=6}.
 */
public final class IczEndBossEggCapsuleInstance extends AbstractS3kUprightEggCapsuleInstance
        implements SpawnCoordinateRewindRecreatable {
    private static final int RESULTS_CHILD_RETIRE_DISPATCHES = 1;
    private static final int FINAL_CAMERA_MAX_X = 0x47C0;

    public IczEndBossEggCapsuleInstance(int x, int y) {
        super(x, y, "ICZEggCapsule");
    }

    private IczEndBossEggCapsuleInstance() {
        this(0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!isResultsStarted() && services().camera() != null) {
            services().camera().setMinX(services().camera().getX());
        }
        super.update(vIntRunCount, player);
    }

    @Override
    protected S3kResultsScreenObjectInstance createResultsScreen(PlayerCharacter character, int act) {
        return new IczEndBossResultsScreenObjectInstance(character, act);
    }

    @Override
    protected boolean nativeResultsRunsInAllocationPass() {
        // ICZ's capsule is slot 10 and AllocateObject selects slot 11, so
        // Obj_LevelResultsInit runs later in the same Process_Sprites pass.
        return true;
    }

    /** Retained {@code loc_71DE2} owner folded into ICZ's results object. */
    private static final class IczEndBossResultsScreenObjectInstance
            extends S3kResultsScreenObjectInstance {
        private IczEndBossResultsScreenObjectInstance(PlayerCharacter character, int act) {
            super(character, act);
        }

        private IczEndBossResultsScreenObjectInstance() {
            super(true);
        }

        @Override
        protected int additionalChildRetireDispatches() {
            // The embedded engine elements finish thirteen owner entries before
            // native Obj_LevelResults' final child SST clears _unkFAA8.
            return RESULTS_CHILD_RETIRE_DISPATCHES;
        }

        @Override
        protected void onExitReady() {
            var camera = services().camera();
            var level = services().currentLevel();
            if (camera != null) {
                camera.setMinX(camera.getX());
                if (level != null) {
                    camera.setMaxYTarget((short) level.getMaxY());
                }
                int x = camera.getX() & 0xFFFF;
                int y = camera.getY() & 0xFFFF;
                HczEndBossGradualMaxXExtender extender = spawnFreeChild(
                        () -> new HczEndBossGradualMaxXExtender(x, y, FINAL_CAMERA_MAX_X));
                if (extender != null && extender.getSlotIndex() >= 0
                        && services().objectManager()
                                .reservedSlotWaitsForNextObjectPass(extender.getSlotIndex())) {
                    // Native allocates slot 11 after the retained boss in slot 5,
                    // so it consumes the helper's zero-motion $4000 entry on this
                    // pass. The consolidated results owner can allocate into an
                    // already-visited hole; seed only that otherwise-missed entry.
                    extender.dispatchCreation();
                }
            }
            super.onExitReady();
        }

        @Override
        protected boolean shouldRestoreCameraBoundsOnExit(int zone, int act) {
            // loc_71DE2 restores only target max Y and starts Child6_IncLevX;
            // it does not copy the level's broad camera bounds directly.
            return false;
        }

        @Override
        protected boolean shouldPublishWaitAnimationOnControlRestore() {
            return true;
        }

        @Override
        public AbstractResultsScreen recreateForRewind(RewindRecreateContext ctx) {
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    IczEndBossResultsScreenObjectInstance::new);
        }
    }
}
