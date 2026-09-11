package com.openggf.game.zone;

public enum NoOpZoneRuntimeState implements ZoneRuntimeState {
    INSTANCE;

    @Override public String gameId() { return "none"; }
    @Override public int zoneIndex() { return -1; }
    @Override public int actIndex() { return -1; }
}
