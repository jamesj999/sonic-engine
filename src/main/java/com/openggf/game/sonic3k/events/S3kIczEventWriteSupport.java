package com.openggf.game.sonic3k.events;

import com.openggf.level.objects.ObjectServices;

/**
 * Object-side write helpers for ICZ level-event state, routed through the
 * {@link IczObjectEventBridge} exposed by the level-event provider.
 */
public final class S3kIczEventWriteSupport {
    private S3kIczEventWriteSupport() {
    }

    public static void triggerScreenShake(ObjectServices services, int frames) {
        IczObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.triggerScreenShake(frames);
        }
    }

    private static IczObjectEventBridge bridgeOrNull(ObjectServices services) {
        Object provider = services.levelEventProvider();
        return provider instanceof IczObjectEventBridge bridge ? bridge : null;
    }
}
