package com.openggf.level.objects;

import com.openggf.game.solid.SolidExecutionRegistry;
import com.openggf.game.PlayableEntity;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Owns the exceptional initial object-dispatch scope and its fixed-slot set. */
final class InitialObjectDispatchController {
    private final ObjectManager objects;
    private final Set<ObjectInstance> fixedObjects =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Scope active;

    InitialObjectDispatchController(ObjectManager objects) {
        this.objects = objects;
    }

    InitialObjectDispatchScope begin(
            int cameraX,
            PlayableEntity player,
            List<? extends PlayableEntity> sidekicks) {
        if (active != null) {
            throw new IllegalStateException("initial Process_Sprites dispatch already active");
        }
        List<? extends PlayableEntity> activeSidekicks = sidekicks != null ? sidekicks : List.of();
        SolidExecutionRegistry registry =
                objects.beginInitialSolidExecution(player, activeSidekicks);
        active = new Scope(cameraX, player, activeSidekicks, registry);
        return active;
    }

    void loadDynamicSlots(InitialObjectDispatchScope scope) {
        Scope dispatch = require(scope);
        objects.runTwoAxisLoadThenExecutePlacement(dispatch.cameraX, true);
        objects.cleanupDestroyedDynamicObjects();
    }

    void processAbsoluteDynamicSlot3(InitialObjectDispatchScope scope) {
        require(scope);
        if (objects.firstDynamicSlot() != 4) {
            throw new IllegalStateException(
                    "initial absolute slot 3 invariant requires managed slots to start at 4");
        }
        for (ObjectInstance instance : objects.getActiveObjects()) {
            if (instance instanceof AbstractObjectInstance object
                    && object.getSlotIndex() == 3) {
                throw new IllegalStateException(
                        "fresh initial Process_Sprites unexpectedly registered absolute slot 3");
            }
        }
    }

    void processDynamicSlots(InitialObjectDispatchScope scope) {
        Scope dispatch = require(scope);
        objects.runExecLoop(
                dispatch.cameraX, dispatch.player, dispatch.sidekicks, false, false);
        objects.flushPostExecDynamicSpawns();
    }

    void freezeCollisionReadView() {
        objects.initialCollisionResponseList().freezePreviousReadView();
        objects.snapshotTouchResponseState(true);
    }

    void resetCollisionBuild() {
        objects.initialCollisionResponseList().resetCurrentBuild();
    }

    void markDynamicCollisionBuildComplete() {
        objects.initialCollisionResponseList().markDynamicBuildComplete();
    }

    void captureCollisionBuild() {
        if (active == null) {
            throw new IllegalStateException("no active initial Process_Sprites dispatch");
        }
        objects.initialCollisionResponseList().captureCompletedBuild();
        active.finished = true;
    }

    void finish(InitialObjectDispatchScope scope) {
        require(scope);
        captureCollisionBuild();
    }

    boolean isActive() {
        return active != null;
    }

    boolean excludesFromDynamicPass(ObjectInstance object) {
        return active != null && fixedObjects.contains(object);
    }

    void registerFixedObject(ObjectInstance object) {
        if (object != null) {
            fixedObjects.add(object);
        }
    }

    void processFixedObject(InitialObjectDispatchScope scope, ObjectInstance object) {
        processFixedObject(require(scope), object);
    }

    void processFixedObject(ObjectInstance object) {
        if (active == null) {
            throw new IllegalStateException("no active initial Process_Sprites dispatch");
        }
        processFixedObject(active, object);
    }

    private void processFixedObject(Scope dispatch, ObjectInstance object) {
        if (object == null || object.isDestroyed() || !fixedObjects.contains(object)) {
            return;
        }
        objects.executeObjectWithSolidContext(
                object, dispatch.player, dispatch.sidekicks, false, false);
    }

    private Scope require(InitialObjectDispatchScope scope) {
        if (!(scope instanceof Scope dispatch) || dispatch != active || dispatch.closed) {
            throw new IllegalArgumentException("scope does not own the active initial dispatch");
        }
        return dispatch;
    }

    private final class Scope implements InitialObjectDispatchScope {
        private final int cameraX;
        private final PlayableEntity player;
        private final List<? extends PlayableEntity> sidekicks;
        private final SolidExecutionRegistry solidExecutionRegistry;
        private boolean finished;
        private boolean closed;

        private Scope(
                int cameraX,
                PlayableEntity player,
                List<? extends PlayableEntity> sidekicks,
                SolidExecutionRegistry solidExecutionRegistry) {
            this.cameraX = cameraX;
            this.player = player;
            this.sidekicks = sidekicks;
            this.solidExecutionRegistry = solidExecutionRegistry;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (!finished) {
                    objects.initialCollisionResponseList().abortCurrentBuild();
                }
            } finally {
                try {
                    solidExecutionRegistry.finishFrame();
                } finally {
                    fixedObjects.clear();
                    active = null;
                }
            }
        }
    }
}
