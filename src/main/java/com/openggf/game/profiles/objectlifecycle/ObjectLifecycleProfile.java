package com.openggf.game.profiles.objectlifecycle;

import java.util.Objects;

public record ObjectLifecycleProfile(
        ObjectLifecycleDestructionMode destructionMode,
        ObjectLifecycleRespawnPolicy respawnPolicy,
        ObjectLifecycleSlotPolicy slotPolicy) {

    public static final ObjectLifecycleProfile LATCHED_DESTRUCTION = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.DESTROY_LATCHED,
            ObjectLifecycleRespawnPolicy.NO_RESPAWN,
            ObjectLifecycleSlotPolicy.RETAIN_CURRENT_SLOT);

    public static final ObjectLifecycleProfile RESPAWNABLE_OFFSCREEN = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.DESTROY_RESPAWNABLE_OFFSCREEN,
            ObjectLifecycleRespawnPolicy.RESPAWN_WHEN_REENTERED,
            ObjectLifecycleSlotPolicy.RETAIN_CURRENT_SLOT);

    public static final ObjectLifecycleProfile DELETE_NO_RESPAWN = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.DELETE_NO_RESPAWN,
            ObjectLifecycleRespawnPolicy.NO_RESPAWN,
            ObjectLifecycleSlotPolicy.RETAIN_CURRENT_SLOT);

    public static final ObjectLifecycleProfile EXPIRE_DYNAMIC = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.EXPIRE_DYNAMIC,
            ObjectLifecycleRespawnPolicy.DYNAMIC_ONLY,
            ObjectLifecycleSlotPolicy.NORMAL_DYNAMIC_ALLOCATION);

    public static final ObjectLifecycleProfile TRANSFER_TO_REPLACEMENT = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.NONE,
            ObjectLifecycleRespawnPolicy.KEEP_ACTIVE_SPAWN_STATE,
            ObjectLifecycleSlotPolicy.TRANSFER_SLOT_TO_REPLACEMENT);

    public static final ObjectLifecycleProfile PARENT_RELEASE_KEEP_CHILDREN = new ObjectLifecycleProfile(
            ObjectLifecycleDestructionMode.NONE,
            ObjectLifecycleRespawnPolicy.KEEP_ACTIVE_SPAWN_STATE,
            ObjectLifecycleSlotPolicy.RELEASE_PARENT_KEEP_CHILDREN);

    public ObjectLifecycleProfile {
        Objects.requireNonNull(destructionMode, "destructionMode");
        Objects.requireNonNull(respawnPolicy, "respawnPolicy");
        Objects.requireNonNull(slotPolicy, "slotPolicy");
    }
}
