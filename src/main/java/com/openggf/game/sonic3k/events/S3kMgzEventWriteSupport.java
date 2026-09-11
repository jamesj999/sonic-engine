package com.openggf.game.sonic3k.events;

import com.openggf.level.objects.ObjectServices;

public final class S3kMgzEventWriteSupport {
    private S3kMgzEventWriteSupport() {
    }

    public static void triggerBossCollapseHandoff(ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof MgzObjectEventBridge bridge) {
            bridge.triggerBossCollapseHandoff();
        }
    }

    public static void completeDrillingRobotnikFlee(ObjectServices services) {
        Object provider = services.levelEventProvider();
        if (provider instanceof MgzObjectEventBridge bridge) {
            bridge.completeDrillingRobotnikFlee();
        }
    }
}
