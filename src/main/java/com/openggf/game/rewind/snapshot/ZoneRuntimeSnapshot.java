package com.openggf.game.rewind.snapshot;

/**
 * Snapshot of {@link com.openggf.game.zone.ZoneRuntimeRegistry} per-frame state.
 * Per-zone runtime state is captured polymorphically via
 * {@link com.openggf.game.zone.ZoneRuntimeState#captureBytes()} /
 * {@link com.openggf.game.zone.ZoneRuntimeState#restoreBytes(byte[])}.
 * The state identity fields prevent restoring bytes into the wrong zone runtime
 * implementation after a session transition.
 */
public record ZoneRuntimeSnapshot(
        String stateType,
        String gameId,
        int zoneIndex,
        int actIndex,
        byte[] stateBytes) {

    public ZoneRuntimeSnapshot {
        stateType = stateType != null ? stateType : "";
        gameId = gameId != null ? gameId : "";
        stateBytes = stateBytes != null ? stateBytes.clone() : new byte[0];
    }

    /**
     * Legacy constructor for tests and deserializers that only need an empty
     * no-op snapshot.
     */
    public ZoneRuntimeSnapshot(byte[] stateBytes) {
        this("", "", -1, -1, stateBytes);
    }

    @Override
    public byte[] stateBytes() {
        return stateBytes.clone();
    }
}
