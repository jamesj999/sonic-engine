package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

/**
 * Floating upside-down egg prison used by the AIZ2 post-boss cutscene.
 */
public class Aiz2EndEggCapsuleInstance extends AbstractS3kFloatingEndEggCapsuleInstance
        implements SpawnCoordinateRewindRecreatable {
    private static final int RESULTS_OWNER_TAILS_ENDING_POSE_ENTRY = 1;

    private boolean tailsEndingPoseApplied;
    private boolean tailsEndingPoseObjectControlLocked;
    private int tailsOpenControllerLockDelay;
    private int resultsActiveWaitEntries;

    public Aiz2EndEggCapsuleInstance(int initialX, int initialY) {
        super(initialX, initialY, "AIZ2EndEggCapsule");
    }

    private Aiz2EndEggCapsuleInstance(int initialX, int initialY, boolean routeInitPending) {
        super(initialX, initialY, "AIZ2EndEggCapsule", routeInitPending);
    }

    private Aiz2EndEggCapsuleInstance() {
        this(0, 0);
    }

    public static Aiz2EndEggCapsuleInstance createForCamera(int cameraX, int cameraY) {
        return new Aiz2EndEggCapsuleInstance(cameraX + X_OFFSET, cameraY + Y_START_OFFSET, true);
    }

    @Override
    protected AbstractObjectInstance createCapsuleAnimal(ObjectSpawn spawn, int delay, int artVariant, int index) {
        return new HighPriorityAnimal(spawn, delay, artVariant);
    }

    @Override
    protected void onParentOpen() {
        // sub_865DE runs from the parent slot after the current player/CPU
        // dispatch. Publish its signed Ctrl_2 lock on the next capsule entry.
        tailsOpenControllerLockDelay = 1;
    }

    @Override
    protected void onBeforeCapsuleUpdate() {
        if (Aiz2BossEndSequenceState.tickTailsControlRelease()) {
            releaseTailsControlNow();
        }
        if (tailsOpenControllerLockDelay <= 0) {
            return;
        }
        tailsOpenControllerLockDelay--;
        if (tailsOpenControllerLockDelay > 0) {
            return;
        }
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                && sidekick.getCpuController() != null) {
            sidekick.getCpuController().setController2SignedLocked(true);
        }
    }

    @Override
    protected void onResultsComplete() {
        Aiz2BossEndSequenceState.releaseEggCapsule();
        // ROM: The capsule stays visible while Sonic walks right and Knuckles
        // does his cutscene. It leaves only when camera scroll or zone transition
        // removes it from the active scene.
    }

    @Override
    protected ObjectPlayerParticipationPolicy resultsLockParticipationPolicy() {
        // AIZ route 0 sub_868F8 applies Set_PlayerEndingPose to Player_1 when
        // results start; Player_2 is handled later by Check_TailsEndPose after
        // its own eligibility gate (sonic3k.asm:181900-181939).
        return ObjectPlayerParticipationPolicy.MAIN_ONLY_NATIVE;
    }

    @Override
    protected boolean defersButtonEligibilityCreatedByParentMotion() {
        // The route-8 parent and button child are distinct native dispatches.
        // Preserve that boundary when the parent's horizontal step is what
        // first moves the button range over a player.
        return true;
    }

    @Override
    protected boolean shouldStartResults(AbstractPlayableSprite player) {
        // sub_868F8 only rejects a dead/airborne/non-playable routine. It then
        // calls Set_PlayerEndingPose, which owns the velocity clears itself.
        return !player.getAir() && !player.getDead();
    }

    @Override
    protected AbstractObjectInstance createResultsScreen() {
        return new Aiz2ResultsScreenObjectInstance(getPlayerCharacter(), services().currentAct());
    }

    @Override
    protected void onResultsActiveWait() {
        resultsActiveWaitEntries++;
        advanceTailsEndingPoseCheck(false);
    }

    @Override
    protected void onEndingPoseLockClear() {
        advanceTailsEndingPoseCheck(true);
    }

    private void restoreNativePlayerControls() {
        for (var candidate : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            if (!(candidate instanceof AbstractPlayableSprite player)) {
                continue;
            }
            // ROM loc_7D078 calls Restore_PlayerControl/2 from this capsule
            // owner after both player animation slots have run. The routine
            // writes anim and prev_anim together as $0505, then clears the
            // animation frame/timer while leaving the displayed mapping intact
            // for this frame (sonic3k.asm:166696-166703,180361-180370).
            ObjectControlState.none().applyTo(player);
            player.setAir(false);
            player.setForcedAnimationId(-1);
            player.setAnimationId(Sonic3kAnimationIds.WAIT);
            player.getAnimationManager().publishPreviousAnimationId(
                    Sonic3kAnimationIds.WAIT.id());
            player.setAnimationFrameIndex(0);
            player.setAnimationTick(0);
        }
    }

    private void advanceTailsEndingPoseCheck(boolean force) {
        if (tailsEndingPoseApplied) {
            return;
        }
        if (!force && resultsActiveWaitEntries < RESULTS_OWNER_TAILS_ENDING_POSE_ENTRY) {
            return;
        }
        // ROM Check_TailsEndPose clears Ctrl_2_locked when Tails is eligible for
        // the ending pose, then latches parent $38 bit 7 so it runs once
        // (sonic3k.asm:181919-181939). Obj_EggCapsule routine $0C calls this
        // while Obj_LevelResults/_unkFAA8 is still active, before End_of_level_flag
        // is set on results exit (sonic3k.asm:181670-181672,62693-62705).
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                && sidekick.getCpuController() != null) {
            if (sidekick.isPreventTailsRespawn()
                    || sidekick.getAir()
                    || sidekick.getDead()) {
                return;
            }
            tailsEndingPoseApplied = true;
            tailsEndingPoseObjectControlLocked = true;
            boolean wasAir = sidekick.getAir();
            boolean wasOnObject = sidekick.isOnObject();
            sidekick.getCpuController().setController2SignedLocked(false);
            // Check_TailsEndPose runs from the capsule's later SST slot. It
            // clears Ctrl_2_locked and tail-calls Set_PlayerEndingPose, but
            // neither routine writes Ctrl_2_logical; retain the word already
            // published by Player_2 this frame. The following Tails_Control
            // dispatch copies raw Ctrl_2 before object_control=$81 blocks CPU
            // steering (sonic3k.asm:181924-181944,181982-181992,
            // 26195-26212).
            lockForResults(sidekick);
            sidekick.setAir(wasAir);
            sidekick.setOnObject(wasOnObject);
        }
    }

    private void releaseTailsControlNow() {
        restoreNativePlayerControls();
        if (services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick) {
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().setController2SignedLocked(false);
                sidekick.getCpuController().mirrorRawController2LogicalForEndingPose();
            }
            sidekick.setControlLocked(false);
        }
        tailsEndingPoseObjectControlLocked = false;
    }

    private static final class HighPriorityAnimal extends EggPrisonAnimalInstance {
        HighPriorityAnimal(ObjectSpawn spawn, int delay, int artVariant) {
            super(spawn, delay, artVariant);
        }

        private HighPriorityAnimal(ObjectSpawn spawn) {
            this(spawn, 0, 0);
        }

        @Override
        public boolean isHighPriority() {
            return true;
        }
    }

    private static final class Aiz2ResultsScreenObjectInstance extends S3kResultsScreenObjectInstance {
        Aiz2ResultsScreenObjectInstance(PlayerCharacter character, int act) {
            super(character, act);
        }

        private Aiz2ResultsScreenObjectInstance() {
            super(true);
        }

        @Override
        public Aiz2ResultsScreenObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            return ObjectConstructionContext.construct(ctx.objectServices(),
                    Aiz2ResultsScreenObjectInstance::new);
        }

        @Override
        protected boolean shouldRestorePlayerControlsOnExit() {
            // ROM Obj_LevelResultsWait2 clears _unkFAA8 and deletes itself
            // (sonic3k.asm:62693-62705). The AIZ2 owner at loc_7D078 performs
            // Restore_PlayerControl/2 after Check_TailsEndPose observes that
            // flag clear (sonic3k.asm:166696-166703).
            return false;
        }

        @Override
        protected boolean skipsSameFrameUpdateAfterSpawn() {
            // AIZ2's sub_868F8 publishes Obj_LevelResults through the general
            // AllocateObject path, so its first Obj_LevelResultsInit dispatch
            // remains on the following ExecuteObjects pass (sonic3k.asm:
            // 181976-181996). The signpost-owned results path uses the
            // after-current allocation contract instead.
            return true;
        }

        @Override
        protected void onResultsChildrenRetired() {
            // Obj_LevelResultsWait2 clears _unkFAA8 as soon as the final
            // results child has left the screen.  The AIZ2 controller is an
            // earlier SST slot, so it consumes this release on the following
            // object pass while the results owner performs its final delete.
            Aiz2BossEndSequenceState.releaseEggCapsule();
        }

        @Override
        protected void onExitReady() {
            super.onExitReady();
        }
    }
}
