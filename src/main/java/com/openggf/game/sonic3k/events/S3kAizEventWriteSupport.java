package com.openggf.game.sonic3k.events;

import com.openggf.level.objects.ObjectServices;

public final class S3kAizEventWriteSupport {
    private S3kAizEventWriteSupport() {
    }

    public static void setBossFlag(ObjectServices services, boolean value) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.setBossFlag(value);
        }
    }

    public static void setEventsFg5(ObjectServices services, boolean value) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.setEventsFg5(value);
        }
    }

    public static void triggerScreenShake(ObjectServices services, int frames) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.triggerScreenShake(frames);
        }
    }

    public static void onBattleshipComplete(ObjectServices services) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.onBattleshipComplete();
        }
    }

    public static void onBossSmallComplete(ObjectServices services) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        if (bridge != null) {
            bridge.onBossSmallComplete();
        }
    }

    public static boolean isFireTransitionActive(ObjectServices services) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        return bridge != null && bridge.isFireTransitionActive();
    }

    public static boolean isAct2TransitionRequested(ObjectServices services) {
        AizObjectEventBridge bridge = bridgeOrNull(services);
        return bridge != null && bridge.isAct2TransitionRequested();
    }

    private static AizObjectEventBridge bridgeOrNull(ObjectServices services) {
        Object provider = services.levelEventProvider();
        return provider instanceof AizObjectEventBridge bridge ? bridge : null;
    }
}
