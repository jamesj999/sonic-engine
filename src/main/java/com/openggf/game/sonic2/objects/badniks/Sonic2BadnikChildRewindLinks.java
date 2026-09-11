package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreateContext;

final class Sonic2BadnikChildRewindLinks {
    private Sonic2BadnikChildRewindLinks() {
    }

    static BalkiryBadnikInstance nearestBalkiry(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, BalkiryBadnikInstance.class);
    }

    static RexonBadnikInstance nearestRexon(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, RexonBadnikInstance.class);
    }

    static ShellcrackerBadnikInstance nearestShellcracker(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, ShellcrackerBadnikInstance.class);
    }

    static SlicerBadnikInstance nearestSlicer(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, SlicerBadnikInstance.class);
    }

    static SolBadnikInstance nearestSol(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, SolBadnikInstance.class);
    }
}
