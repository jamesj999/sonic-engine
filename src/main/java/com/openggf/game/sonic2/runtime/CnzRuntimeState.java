package com.openggf.game.sonic2.runtime;

import com.openggf.game.zone.ZoneRuntimeState;

public interface CnzRuntimeState extends ZoneRuntimeState {
    String GAME_ID = "s2";

    @Override
    default String gameId() {
        return GAME_ID;
    }

    boolean bossArenaActive();

    boolean bossSpawnPending();

    boolean bossSpawned();

    boolean leftArenaWallPlaced();

    boolean rightArenaWallPlaced();

    int eventRoutine();
}
