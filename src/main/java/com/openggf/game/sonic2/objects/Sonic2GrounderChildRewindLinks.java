package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreateContext;

final class Sonic2GrounderChildRewindLinks {
    private Sonic2GrounderChildRewindLinks() {
    }

    static GrounderBadnikInstance nearestGrounder(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, GrounderBadnikInstance.class);
    }
}
