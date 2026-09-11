package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreateContext;

final class AizIntroRewindLinks {
    private AizIntroRewindLinks() {
    }

    static AizPlaneIntroInstance liveIntroParent(RewindRecreateContext ctx) {
        AizPlaneIntroInstance active = AizPlaneIntroInstance.getActiveIntroInstance();
        ObjectManager objectManager = ctx.objectServices() != null
                ? ctx.objectServices().objectManager()
                : null;
        if (objectManager == null) {
            return active;
        }
        AizPlaneIntroInstance onlyLive = null;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AizPlaneIntroInstance candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (candidate == active) {
                return candidate;
            }
            if (onlyLive != null) {
                return null;
            }
            onlyLive = candidate;
        }
        if (active == null && onlyLive != null) {
            AizPlaneIntroInstance.adoptActiveIntroInstance(onlyLive);
            return onlyLive;
        }
        return null;
    }

    static AizIntroPlaneChild liveIntroPlane(RewindRecreateContext ctx) {
        ObjectManager objectManager = ctx.objectManager();
        if (objectManager == null && ctx.objectServices() != null) {
            objectManager = ctx.objectServices().objectManager();
        }
        if (objectManager == null) {
            return null;
        }
        AizIntroPlaneChild onlyLive = null;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AizIntroPlaneChild candidate) || candidate.isDestroyed()) {
                continue;
            }
            if (onlyLive != null) {
                return null;
            }
            onlyLive = candidate;
        }
        return onlyLive;
    }
}
