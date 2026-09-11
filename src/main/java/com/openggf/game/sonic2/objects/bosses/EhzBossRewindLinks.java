package com.openggf.game.sonic2.objects.bosses;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.RewindRecreateContext;

final class EhzBossRewindLinks {
    private EhzBossRewindLinks() {
    }

    static Sonic2EHZBossInstance requireNearestBoss(RewindRecreateContext ctx, String childName) {
        Sonic2EHZBossInstance boss = nearestBoss(ctx);
        if (boss == null) {
            throw new IllegalStateException("Cannot recreate " + childName + ": no live EHZ boss exists");
        }
        return boss;
    }

    private static Sonic2EHZBossInstance nearestBoss(RewindRecreateContext ctx) {
        if (ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            throw new IllegalStateException("Cannot recreate EHZ boss child without ObjectManager services");
        }
        int childX = ctx.spawn().x();
        int childY = ctx.spawn().y();
        Sonic2EHZBossInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance object : ctx.objectServices().objectManager().getActiveObjects()) {
            if (object instanceof Sonic2EHZBossInstance boss && !boss.isDestroyed()) {
                long dx = (long) boss.getX() - childX;
                long dy = (long) boss.getY() - childY;
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = boss;
                }
            }
        }
        return best;
    }
}
