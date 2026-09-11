package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.RewindRecreateContext;

/**
 * CNZ2 results variant retained by {@code Obj_CNZEndBoss}.
 *
 * <p>ROM {@code loc_6E724} waits for the results object to clear
 * {@code _unkFAA8}, then restores player control and starts the gradual camera
 * boundary changes itself. The generic results exit must therefore leave both
 * owners untouched.
 */
final class CnzResultsScreenObjectInstance extends S3kResultsScreenObjectInstance {
    CnzResultsScreenObjectInstance(PlayerCharacter character, int act) {
        super(character, act);
    }

    private CnzResultsScreenObjectInstance() {
        super(true);
    }

    @Override
    public CnzResultsScreenObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                CnzResultsScreenObjectInstance::new);
    }

    @Override
    protected boolean shouldRestorePlayerControlsOnExit() {
        return false;
    }

    @Override
    protected boolean shouldRestoreCameraBoundsOnExit(int zone, int act) {
        return false;
    }

    @Override
    protected void applyCameraFollowExitState(Camera camera, boolean lbzAct2PostBossHandoff) {
        // loc_6E724 owns the camera state until the cannon handoff completes.
    }
}
