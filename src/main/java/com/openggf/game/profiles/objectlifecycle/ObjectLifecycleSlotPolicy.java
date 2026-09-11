package com.openggf.game.profiles.objectlifecycle;

public enum ObjectLifecycleSlotPolicy {
    RETAIN_CURRENT_SLOT,
    DETACH_SOURCE_SLOT,
    TRANSFER_SLOT_TO_REPLACEMENT,
    NORMAL_DYNAMIC_ALLOCATION,
    RELEASE_PARENT_KEEP_CHILDREN,
    FREE_RESERVED_CHILD_SLOT
}
