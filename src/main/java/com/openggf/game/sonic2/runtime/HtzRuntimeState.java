package com.openggf.game.sonic2.runtime;

import com.openggf.game.zone.ZoneRuntimeState;

public interface HtzRuntimeState extends ZoneRuntimeState {
    String GAME_ID = "s2";

    @Override
    default String gameId() {
        return GAME_ID;
    }

    boolean earthquakeActive();

    int cameraBgYOffset();

    int cameraBgXOffset();

    int bgVerticalShift();
}
