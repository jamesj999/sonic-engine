package com.openggf.level.objects;

import java.util.ArrayList;
import java.util.List;

final class ObjectManagerRuntimeState {
    private final List<ObjectInstance> postExecDynamicSpawns = new ArrayList<>();
    private int ringFloorCheckCounterPhase;
    private boolean inheritedRingCounterPhase;

    void clearPostExecDynamicSpawns() {
        postExecDynamicSpawns.clear();
    }

    void queueDynamicObjectAfterExec(ObjectInstance object) {
        if (object != null) {
            postExecDynamicSpawns.add(object);
        }
    }

    List<ObjectInstance> drainPostExecDynamicSpawns() {
        if (postExecDynamicSpawns.isEmpty()) {
            return List.of();
        }
        List<ObjectInstance> queued = List.copyOf(postExecDynamicSpawns);
        postExecDynamicSpawns.clear();
        return queued;
    }

    void initRingFloorCheckCounterPhase(int phase) {
        ringFloorCheckCounterPhase = phase;
        inheritedRingCounterPhase = false;
    }

    void inheritRingFloorCheckCounterPhase(int phase) {
        ringFloorCheckCounterPhase = phase;
        inheritedRingCounterPhase = true;
    }

    int ringFloorCheckCounterPhase() {
        return ringFloorCheckCounterPhase;
    }

    boolean hasInheritedRingCounterPhase() {
        return inheritedRingCounterPhase;
    }
}
