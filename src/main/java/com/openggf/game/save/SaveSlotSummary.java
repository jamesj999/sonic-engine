package com.openggf.game.save;

import java.util.Map;

public record SaveSlotSummary(int slot, SaveSlotState state, Map<String, Object> payload) {

    public static SaveSlotSummary empty(int slot) {
        return new SaveSlotSummary(slot, SaveSlotState.EMPTY, Map.of());
    }

    public static SaveSlotSummary unavailable(int slot) {
        return new SaveSlotSummary(slot, SaveSlotState.UNAVAILABLE, Map.of());
    }

    public boolean isLoadable() {
        return state == SaveSlotState.VALID;
    }

    public boolean hasRecoverablePayload() {
        return state == SaveSlotState.VALID || state == SaveSlotState.HASH_WARNING;
    }
}
