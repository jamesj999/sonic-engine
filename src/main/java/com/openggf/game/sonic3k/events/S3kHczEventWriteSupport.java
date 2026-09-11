package com.openggf.game.sonic3k.events;

import com.openggf.level.objects.ObjectServices;

public final class S3kHczEventWriteSupport {
    private S3kHczEventWriteSupport() {
    }

    public static void setBossFlag(ObjectServices services, boolean value) {
        Object provider = services.levelEventProvider();
        if (provider instanceof HczObjectEventBridge bridge) {
            bridge.setHczBossFlag(value);
        }
    }
}
