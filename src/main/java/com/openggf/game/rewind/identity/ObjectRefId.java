package com.openggf.game.rewind.identity;

import java.util.Objects;

public record ObjectRefId(int slotIndex, int generation, int spawnId, int dynamicId, ObjectRefKind kind) {
    public ObjectRefId {
        Objects.requireNonNull(kind, "kind");
    }

    public static ObjectRefId layout(int slotIndex, int generation, int spawnId) {
        return new ObjectRefId(slotIndex, generation, spawnId, -1, ObjectRefKind.LAYOUT);
    }

    public static ObjectRefId dynamic(int slotIndex, int generation, int dynamicId) {
        return new ObjectRefId(slotIndex, generation, -1, dynamicId, ObjectRefKind.DYNAMIC);
    }

    public static ObjectRefId child(int slotIndex, int generation, int spawnId, int dynamicId) {
        return new ObjectRefId(slotIndex, generation, spawnId, dynamicId, ObjectRefKind.CHILD);
    }

    /**
     * Mints a spawn-stable object id for use during rewind identity registration.
     *
     * <p>The id encodes the spawn's layout index plus a per-spawn instance counter that
     * makes multiple live objects derived from the same spawn point distinct. Re-simulation
     * re-mints identical ids in spawn order, so the id is stable across capture→restore→
     * re-sim cycles without depending on the slot index (which may differ after restore).
     *
     * @param spawn           the spawn that produced (or is associated with) this object
     * @param instanceCounter a 0-based counter distinguishing multiple instances from
     *                        the same spawn; 0 for the first/only instance
     */
    public static ObjectRefId forObject(SpawnRefId spawn, int instanceCounter) {
        return new ObjectRefId(-1, 0, spawn.layoutIndex(), instanceCounter, ObjectRefKind.DYNAMIC);
    }
}
