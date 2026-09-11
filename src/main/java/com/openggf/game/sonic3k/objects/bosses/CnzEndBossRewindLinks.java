package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.ObjectManager;

final class CnzEndBossRewindLinks {
    private CnzEndBossRewindLinks() { }

    static CnzEndBossInstance boss(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectManager() == null) return null;
        return boss(ctx.objectManager());
    }

    static CnzEndBossInstance boss(ObjectManager objectManager) {
        if (objectManager == null) return null;
        return objectManager.activeObjectsOfType(CnzEndBossInstance.class).stream()
                .filter(candidate -> !candidate.isDestroyed())
                .findFirst().orElse(null);
    }

    static CnzEndBossRobotnikShipChild ship(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectManager() == null) return null;
        return ctx.objectManager().activeObjectsOfType(CnzEndBossRobotnikShipChild.class).stream()
                .filter(candidate -> !candidate.isDestroyed())
                .findFirst().orElse(null);
    }
}
