package com.openggf.level;

import com.openggf.game.rewind.RewindSnapshottable;

/** Internal bridge for registering the level-owned lost-ring rewind adapter. */
public final class LevelLostRingSpawnRewindAccess {
    private LevelLostRingSpawnRewindAccess() {
    }

    public static RewindSnapshottable<?> create(LevelManager manager) {
        return manager.createLostRingSpawnRewindAdapterInternal();
    }
}
