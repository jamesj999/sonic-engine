package com.openggf.game.sonic2.objects.bosses;

import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreateContext;

final class CpzBossRewindLinks {
    private CpzBossRewindLinks() {
    }

    static Sonic2CPZBossInstance nearestBoss(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, Sonic2CPZBossInstance.class);
    }

    static CPZBossContainer nearestContainer(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, CPZBossContainer.class);
    }

    static CPZBossPipe nearestPipe(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, CPZBossPipe.class);
    }
}
