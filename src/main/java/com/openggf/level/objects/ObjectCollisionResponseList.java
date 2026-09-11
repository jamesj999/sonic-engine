package com.openggf.level.objects;

import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

final class ObjectCollisionResponseList {
    private static final int S3K_MAX_ENTRIES = 0x7E / 2;

    private final List<ObjectInstance> previousObjects = new ArrayList<>();
    private final List<ObjectInstance> currentObjects = new ArrayList<>();
    private final List<ObjectRefId> restoredPreviousOrder = new ArrayList<>();
    private final List<ObjectRefId> restoredCurrentOrder = new ArrayList<>();
    private final Map<ObjectRefId, ObjectInstance> restoredObjects = new HashMap<>();
    private boolean usePrevious;
    private int currentBuildCursor;
    private ObjectManagerSnapshot.CollisionBuildStage buildStage =
            ObjectManagerSnapshot.CollisionBuildStage.IDLE;

    void setUsePrevious(boolean usePrevious) {
        this.usePrevious = usePrevious;
    }

    boolean usesPrevious() {
        return usePrevious;
    }

    List<ObjectInstance> touchResponseObjects(List<ObjectInstance> currentObjects) {
        return usePrevious ? previousObjects : currentObjects;
    }

    void freezePreviousReadView() {
        usePrevious = true;
        buildStage = ObjectManagerSnapshot.CollisionBuildStage.PREVIOUS_READ_FROZEN;
    }

    void resetCurrentBuild() {
        currentObjects.clear();
        restoredCurrentOrder.clear();
        currentBuildCursor = 0;
        buildStage = ObjectManagerSnapshot.CollisionBuildStage.CURRENT_BUILD_RESET;
    }

    void abortCurrentBuild() {
        // The dispatch scope is closed by the caller, but the partially built ROM
        // list remains represented runtime state. Rewind may capture this seam and
        // must retain both its ordered ids and its build cursor/stage.
    }

    void markDynamicBuildComplete() {
        buildStage = ObjectManagerSnapshot.CollisionBuildStage.DYNAMIC_BUILD_COMPLETE;
    }

    void addToCurrentBuild(ObjectInstance instance) {
        if (isEligible(instance) && currentObjects.size() < S3K_MAX_ENTRIES) {
            currentObjects.add(instance);
            currentBuildCursor++;
            buildStage = ObjectManagerSnapshot.CollisionBuildStage.CURRENT_BUILDING;
        }
    }

    void captureCompletedBuild() {
        previousObjects.clear();
        previousObjects.addAll(currentObjects);
        restoredPreviousOrder.clear();
        restoredCurrentOrder.clear();
        restoredObjects.clear();
        usePrevious = true;
        buildStage = ObjectManagerSnapshot.CollisionBuildStage.COMPLETED;
    }

    void captureCompletedBuild(List<ObjectInstance> objects) {
        resetCurrentBuild();
        for (ObjectInstance instance : objects) {
            addToCurrentBuild(instance);
        }
        captureCompletedBuild();
    }

    List<ObjectInstance> playerReadView() {
        return previousObjects;
    }

    List<ObjectInstance> currentBuildView() {
        return currentObjects;
    }

    ObjectManagerSnapshot.CollisionResponseState captureRewindState(
            Function<ObjectInstance, ObjectRefId> encoder) {
        return new ObjectManagerSnapshot.CollisionResponseState(
                encode(previousObjects, encoder),
                encode(currentObjects, encoder),
                usePrevious,
                currentBuildCursor,
                buildStage);
    }

    void restoreRewindState(
            ObjectManagerSnapshot.CollisionResponseState state,
            Function<ObjectRefId, ObjectInstance> resolver) {
        previousObjects.clear();
        currentObjects.clear();
        restoredPreviousOrder.clear();
        restoredCurrentOrder.clear();
        restoredObjects.clear();
        if (state == null) {
            usePrevious = false;
            currentBuildCursor = 0;
            buildStage = ObjectManagerSnapshot.CollisionBuildStage.IDLE;
            return;
        }
        restoredPreviousOrder.addAll(state.previousCollisionObjectIds());
        restoredCurrentOrder.addAll(state.currentCollisionBuildObjectIds());
        resolveKnown(restoredPreviousOrder, resolver);
        resolveKnown(restoredCurrentOrder, resolver);
        rebuildRestoredLists();
        usePrevious = state.usePreviousCollisionList();
        currentBuildCursor = state.currentCollisionBuildCursor();
        buildStage = state.collisionBuildStage();
    }

    void bindRestoredObject(ObjectRefId id, ObjectInstance object) {
        if (id == null || object == null) {
            throw new IllegalArgumentException("restored collision responder identity is required");
        }
        if (restoredPreviousOrder.contains(id) || restoredCurrentOrder.contains(id)) {
            restoredObjects.put(id, object);
            rebuildRestoredLists();
        }
    }

    void captureForNextFrame(List<ObjectInstance> currentObjects) {
        boolean selectedReadView = usePrevious;
        captureCompletedBuild(currentObjects);
        usePrevious = selectedReadView;
    }

    private static boolean isEligible(ObjectInstance instance) {
        // Add_SpriteToCollisionResponseList has no camera-range gate; the
        // object routine decides whether to publish, and this helper only
        // enforces the native $7E-byte list capacity
        // (docs/skdisasm/sonic3k.asm:21200-21210).
        return instance instanceof TouchResponseProvider
                && !instance.isDestroyed()
                && instance.publishesTouchResponseListEntryThisFrame();
    }

    private static List<ObjectRefId> encode(
            List<ObjectInstance> objects,
            Function<ObjectInstance, ObjectRefId> encoder) {
        List<ObjectRefId> ids = new ArrayList<>(objects.size());
        for (ObjectInstance object : objects) {
            ObjectRefId id = encoder.apply(object);
            if (id == null) {
                throw new IllegalStateException(
                        "collision response publisher has no rewind identity: "
                                + object.getClass().getName());
            }
            ids.add(id);
        }
        return ids;
    }

    private void resolveKnown(
            List<ObjectRefId> ids,
            Function<ObjectRefId, ObjectInstance> resolver) {
        for (ObjectRefId id : ids) {
            ObjectInstance object = resolver.apply(id);
            if (object != null) {
                restoredObjects.put(id, object);
            }
        }
    }

    private void rebuildRestoredLists() {
        previousObjects.clear();
        currentObjects.clear();
        appendResolved(restoredPreviousOrder, previousObjects);
        appendResolved(restoredCurrentOrder, currentObjects);
    }

    private void appendResolved(List<ObjectRefId> order, List<ObjectInstance> target) {
        for (ObjectRefId id : order) {
            ObjectInstance object = restoredObjects.get(id);
            if (object != null) {
                target.add(object);
            }
        }
    }
}
