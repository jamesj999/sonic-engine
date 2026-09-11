package com.openggf.level.objects;

/**
 * Marks a runtime-created object whose absolute fixed SST slot has a
 * game-owned execution pass outside {@link ObjectManager}'s dynamic fallback.
 */
public interface FixedRuntimeObjectInstance {
    static boolean ownsFixedPass(
            AbstractObjectInstance instance, ObjectSlotLayout slotLayout) {
        return instance instanceof FixedRuntimeObjectInstance
                && instance.getSlotIndex() >= 0
                && instance.getSlotIndex() < slotLayout.firstDynamicSlot();
    }
}
