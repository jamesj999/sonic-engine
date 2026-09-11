package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.RewindRecreateContext;

/**
 * MGZ2 level-results variant.
 *
 * <p>ROM: the MGZ floating capsule starts {@code Obj_LevelResults} from
 * {@code sub_86984} while {@code Flying_carrying_Sonic_flag} can still be set.
 * The results object must therefore leave Sonic/Tails' carry control intact;
 * {@code loc_6D104}'s palette fade and level transition run after results.
 */
public class Mgz2ResultsScreenObjectInstance extends S3kResultsScreenObjectInstance {

    private boolean allocatedFromFlyingCarry;

    public Mgz2ResultsScreenObjectInstance(PlayerCharacter character, int act) {
        this(character, act, false);
    }

    Mgz2ResultsScreenObjectInstance(PlayerCharacter character, int act,
                                    boolean allocatedFromFlyingCarry) {
        super(character, act);
        this.allocatedFromFlyingCarry = allocatedFromFlyingCarry;
    }

    // Probe-only constructor used by RewindRecreatable generic recreate.
    private Mgz2ResultsScreenObjectInstance() {
        super(true);
    }

    @Override
    public Mgz2ResultsScreenObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                Mgz2ResultsScreenObjectInstance::new);
    }

    @Override
    protected boolean shouldRestorePlayerControlsOnExit() {
        return false;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        // MGZ's sub_86984 uses AllocateObject for Obj_LevelResults. The native
        // allocation lands in a lower free SST slot than the capsule, so its
        // Obj_LevelResultsInit entry cannot run until the next ExecuteObjects
        // pass (sonic3k.asm:182027-182046).
        return true;
    }

    @Override
    protected boolean shouldDeferInitialResultsArtLoadDispatch() {
        // sub_86984 allocates Obj_LevelResults through the
        // Flying_carrying_Sonic_flag branch. Preserve that allocation-time
        // ROM state across the lower-slot delay before Obj_LevelResultsInit.
        return allocatedFromFlyingCarry;
    }

    @Override
    protected boolean shouldRestoreCameraBoundsOnExit(int zone, int act) {
        // ROM loc_6C8F4 retains the MGZ boss camera boundary and hands the
        // post-results flight to Scroll_lock instead of restoring level bounds
        // (sonic3k.asm:143186-143199).
        return false;
    }

    @Override
    protected void applyCameraFollowExitState(Camera camera, boolean lbzAct2PostBossHandoff) {
        // ROM loc_6C8F4 writes Scroll_lock=1 at the MGZ results exit while the
        // carried Sonic/Tails fly-off and palette transition remain active.
        camera.setScrollLocked(true);
    }
}
