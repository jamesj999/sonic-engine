package com.openggf.level.objects;

import com.openggf.level.objects.boss.BossChildComponent;

import java.util.Objects;

/**
 * Named lifecycle operations for object destruction, respawn, and slot transfer.
 */
public final class ObjectLifetimeOps {
    private ObjectLifetimeOps() {
    }

    public static void destroyLatched(AbstractObjectInstance instance) {
        markDestroyedNoRespawn(instance);
    }

    public static void destroyBossChildLatched(BossChildComponent component) {
        BossChildComponent checked = Objects.requireNonNull(component, "component");
        checked.setDestroyed(false);
        checked.setDestroyed(true);
    }

    public static void destroyRespawnableOffscreen(AbstractObjectInstance instance) {
        Objects.requireNonNull(instance, "instance").setDestroyedByOffscreen();
    }

    public static void deleteNoRespawn(AbstractObjectInstance instance) {
        markDestroyedNoRespawn(instance);
    }

    public static void expireDynamic(AbstractObjectInstance instance) {
        markDestroyedNoRespawn(instance);
    }

    public static int detachSlotForTransfer(AbstractObjectInstance instance) {
        Objects.requireNonNull(instance, "instance");
        int slot = instance.getSlotIndex();
        instance.setSlotIndex(-1);
        return slot;
    }

    public static void clearPreviousManagerSlot(AbstractObjectInstance instance) {
        Objects.requireNonNull(instance, "instance").setSlotIndex(-1);
    }

    public static void addDynamicAtReservedSlot(ObjectManager objectManager,
                                                ObjectInstance object,
                                                int reservedSlot) {
        Objects.requireNonNull(objectManager, "objectManager");
        Objects.requireNonNull(object, "object");
        objectManager.addDynamicObjectAtSlot(object, reservedSlot);
    }

    public static void addReplacementAtTransferredSlot(ObjectManager objectManager,
                                                       ObjectInstance replacement,
                                                       int transferredSlot) {
        Objects.requireNonNull(objectManager, "objectManager");
        Objects.requireNonNull(replacement, "replacement");
        if (transferredSlot >= 0) {
            objectManager.addDynamicObjectAtSlot(replacement, transferredSlot);
        } else {
            objectManager.addDynamicObject(replacement);
        }
    }

    public static int assignFindNextFreeChildSlot(ObjectManager objectManager,
                                                  AbstractObjectInstance child,
                                                  int predecessorSlot) {
        Objects.requireNonNull(objectManager, "objectManager");
        Objects.requireNonNull(child, "child");
        if (predecessorSlot < 0) {
            return -1;
        }
        int slot = objectManager.allocateSlotAfter(predecessorSlot);
        if (slot >= 0) {
            child.setSlotIndex(slot);
        }
        return slot;
    }

    public static int reserveFindNextFreeChildSlot(ObjectManager objectManager, int predecessorSlot) {
        Objects.requireNonNull(objectManager, "objectManager");
        return predecessorSlot < 0 ? -1 : objectManager.allocateSlotAfter(predecessorSlot);
    }

    public static void markSpawnRemembered(ObjectManager objectManager, ObjectSpawn spawn) {
        if (objectManager != null && spawn != null) {
            objectManager.markRemembered(spawn);
        }
    }

    public static void removeSpawnFromActive(ObjectManager objectManager, ObjectSpawn spawn) {
        if (objectManager != null && spawn != null) {
            objectManager.removeFromActiveSpawns(spawn);
        }
    }

    public static void releaseSpawnForRespawn(ObjectManager objectManager,
                                              ObjectInstance transformedInstance,
                                              ObjectSpawn spawn) {
        if (objectManager != null && transformedInstance != null && spawn != null) {
            objectManager.releaseSpawnForRespawn(transformedInstance, spawn);
        }
    }

    public static void releaseParentSlotKeepingChildren(ObjectManager objectManager,
                                                       AbstractObjectInstance parent) {
        if (objectManager != null && parent != null) {
            objectManager.releaseParentSlot(parent);
        }
    }

    public static void freeReservedChildSlot(ObjectManager objectManager, ObjectSpawn parentSpawn, int childIndex) {
        if (objectManager != null && parentSpawn != null) {
            objectManager.freeReservedChildSlot(parentSpawn, childIndex);
        }
    }

    private static void markDestroyedNoRespawn(AbstractObjectInstance instance) {
        AbstractObjectInstance checked = Objects.requireNonNull(instance, "instance");
        checked.setDestroyed(false);
        checked.setDestroyed(true);
    }
}
