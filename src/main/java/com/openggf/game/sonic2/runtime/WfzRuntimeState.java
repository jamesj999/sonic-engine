package com.openggf.game.sonic2.runtime;

import com.openggf.game.zone.ZoneRuntimeState;

public interface WfzRuntimeState extends ZoneRuntimeState {
    String GAME_ID = "s2";

    @Override
    default String gameId() {
        return GAME_ID;
    }

    int bgVscrollFactor();

    int bgXPos();
}
