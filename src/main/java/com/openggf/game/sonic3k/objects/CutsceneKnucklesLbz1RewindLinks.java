package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.RewindRecreateContext;

final class CutsceneKnucklesLbz1RewindLinks {
    private CutsceneKnucklesLbz1RewindLinks() {
    }

    static CutsceneKnucklesLbz1Instance singleLiveParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null) {
            return null;
        }
        ObjectManager objectManager = ctx.objectServices().objectManager();
        if (objectManager == null) {
            return null;
        }
        CutsceneKnucklesLbz1Instance found = null;
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (object.getClass() != CutsceneKnucklesLbz1Instance.class || object.isDestroyed()) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = (CutsceneKnucklesLbz1Instance) object;
        }
        return found;
    }
}
