package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreateContext;

final class HczEndBossRewindLinks {
    private HczEndBossRewindLinks() {
    }

    static HczEndBossInstance nearestBoss(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, HczEndBossInstance.class);
    }

    static HczEndBossTurbine nearestTurbine(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, HczEndBossTurbine.class);
    }
}
