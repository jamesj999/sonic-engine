package com.openggf.game;

import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;

public final class BonusStageBootstrapInstaller {
    private BonusStageBootstrapInstaller() {
    }

    public static ObjectSpawn resolveSpawn(
            BonusStageProvider provider, BonusStageType type) {
        BonusStageProvider.BootstrapObject bootstrap =
                provider != null ? provider.bootstrapObject(type) : null;
        return bootstrap != null ? bootstrap.spawn() : null;
    }

    public static void ensurePresent(
            BonusStageProvider provider,
            BonusStageType type,
            ObjectManager objectManager) {
        BonusStageProvider.BootstrapObject bootstrap =
                provider != null ? provider.bootstrapObject(type) : null;
        if (bootstrap == null || objectManager == null) {
            return;
        }
        boolean present = objectManager.getActiveObjects().stream()
                .anyMatch(bootstrap::matches);
        if (!present) {
            objectManager.createDynamicObject(bootstrap::create);
        }
    }
}
