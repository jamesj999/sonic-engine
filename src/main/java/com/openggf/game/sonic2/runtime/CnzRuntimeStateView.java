package com.openggf.game.sonic2.runtime;

import com.openggf.game.sonic2.events.Sonic2CNZEvents;

import java.util.Objects;

public final class CnzRuntimeStateView implements CnzRuntimeState {
    private final int zoneIndex;
    private final int actIndex;
    private final Sonic2CNZEvents events;

    public CnzRuntimeStateView(int zoneIndex, int actIndex, Sonic2CNZEvents events) {
        this.zoneIndex = zoneIndex;
        this.actIndex = actIndex;
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override
    public int zoneIndex() {
        return zoneIndex;
    }

    @Override
    public int actIndex() {
        return actIndex;
    }

    @Override
    public boolean bossArenaActive() {
        return events.isBossArenaActive();
    }

    @Override
    public boolean bossSpawnPending() {
        return events.isBossSpawnPending();
    }

    @Override
    public boolean bossSpawned() {
        return events.isBossSpawned();
    }

    @Override
    public boolean leftArenaWallPlaced() {
        return events.isLeftArenaWallPlaced();
    }

    @Override
    public boolean rightArenaWallPlaced() {
        return events.isRightArenaWallPlaced();
    }

    @Override
    public int eventRoutine() {
        return events.getEventRoutine();
    }
}
