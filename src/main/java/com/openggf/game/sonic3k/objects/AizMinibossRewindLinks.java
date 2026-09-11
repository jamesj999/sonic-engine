package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.boss.AbstractBossInstance;

final class AizMinibossRewindLinks {
    private static final int[][] BARREL_OFFSETS = {
            {0, -0x20}, {9, -0x1C}, {0x12, -0x18}
    };

    private AizMinibossRewindLinks() {
    }

    static AizMinibossInstance nearestBoss(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, AizMinibossInstance.class);
    }

    static AbstractBossInstance nearestSharedBoss(RewindRecreateContext ctx) {
        AizMinibossInstance fightBoss = nearestBoss(ctx);
        AizMinibossCutsceneInstance cutsceneBoss =
                RewindRecreateObjectLinks.nearestLiveObject(ctx, AizMinibossCutsceneInstance.class);
        if (fightBoss == null) {
            return cutsceneBoss;
        }
        if (cutsceneBoss == null) {
            return fightBoss;
        }
        ObjectSpawn spawn = ctx.spawn();
        if (spawn == null) {
            return fightBoss;
        }
        long fightDistance = distanceSquared(spawn.x(), spawn.y(), fightBoss.getX(), fightBoss.getY());
        long cutsceneDistance = distanceSquared(
                spawn.x(), spawn.y(), cutsceneBoss.getX(), cutsceneBoss.getY());
        return cutsceneDistance < fightDistance ? cutsceneBoss : fightBoss;
    }

    static AizMinibossFlameBarrelChild nearestBarrel(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, AizMinibossFlameBarrelChild.class);
    }

    static AizMinibossBarrelShotChild nearestBarrelShot(RewindRecreateContext ctx) {
        return RewindRecreateObjectLinks.nearestLiveObject(ctx, AizMinibossBarrelShotChild.class);
    }

    static int nearestBarrelIndex(RewindRecreateContext ctx, AbstractBossInstance boss) {
        ObjectSpawn spawn = ctx.spawn();
        if (spawn == null || boss == null) {
            return 0;
        }
        int bestIndex = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int index = 0; index < BARREL_OFFSETS.length; index++) {
            int xOffset = BARREL_OFFSETS[index][0];
            int yOffset = BARREL_OFFSETS[index][1];
            long normalDistance = distanceSquared(spawn.x(), spawn.y(),
                    boss.getX() + xOffset, boss.getY() + yOffset);
            long flippedDistance = distanceSquared(spawn.x(), spawn.y(),
                    boss.getX() - xOffset, boss.getY() + yOffset);
            long distance = Math.min(normalDistance, flippedDistance);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static long distanceSquared(int x1, int y1, int x2, int y2) {
        long dx = x1 - x2;
        long dy = y1 - y2;
        return dx * dx + dy * dy;
    }
}
