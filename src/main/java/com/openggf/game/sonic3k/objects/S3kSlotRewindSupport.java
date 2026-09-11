package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.level.objects.ObjectServices;

final class S3kSlotRewindSupport {

    private S3kSlotRewindSupport() {
    }

    static S3kSlotStageController resolveSlotStageController(ObjectServices objectServices) {
        if (objectServices == null
                || !(objectServices.bonusStageProviderOrNull()
                        instanceof Sonic3kBonusStageCoordinator coordinator)) {
            return null;
        }
        S3kSlotBonusStageRuntime runtime = coordinator.activeSlotRuntime();
        return runtime != null ? runtime.stageController() : null;
    }
}
