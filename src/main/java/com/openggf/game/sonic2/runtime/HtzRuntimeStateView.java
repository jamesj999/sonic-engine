package com.openggf.game.sonic2.runtime;

import com.openggf.game.sonic2.events.Sonic2HTZEvents;

import java.util.Objects;

public final class HtzRuntimeStateView implements HtzRuntimeState {
    private final int zoneIndex;
    private final int actIndex;
    private final Sonic2HTZEvents events;

    public HtzRuntimeStateView(int zoneIndex, int actIndex, Sonic2HTZEvents events) {
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
    public boolean earthquakeActive() {
        return events.isEarthquakeActive();
    }

    @Override
    public boolean requiresFullWidthBgTilemap() {
        return events.isEarthquakeActive();
    }

    @Override
    public int cameraBgYOffset() {
        return events.getCameraBgYOffset();
    }

    @Override
    public int cameraBgXOffset() {
        return events.getHtzBgXOffset();
    }

    @Override
    public int bgVerticalShift() {
        return events.getHtzBgVerticalShift();
    }
}
