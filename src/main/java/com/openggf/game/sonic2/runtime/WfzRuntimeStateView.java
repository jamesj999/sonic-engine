package com.openggf.game.sonic2.runtime;

import com.openggf.game.sonic2.events.Sonic2WFZEvents;

import java.util.Objects;

public final class WfzRuntimeStateView implements WfzRuntimeState {
    private final int zoneIndex;
    private final int actIndex;
    private final Sonic2WFZEvents events;

    public WfzRuntimeStateView(int zoneIndex, int actIndex, Sonic2WFZEvents events) {
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
    public int bgVscrollFactor() {
        return events.getBgYPos();
    }

    @Override
    public int bgXPos() {
        return events.getBgXPos();
    }
}
