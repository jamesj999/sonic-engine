package com.openggf.level.rewind;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelTilemapSnapshot;
import com.openggf.level.LevelTilemapManager;

import java.util.Objects;

/** Rewind adapter for the history-dependent persistent Plane B nametable. */
public final class LevelTilemapRewindAdapter
        implements RewindSnapshottable<LevelTilemapSnapshot> {
    public static final String KEY = "level-tilemap";

    private final LevelTilemapManager manager;

    public LevelTilemapRewindAdapter(LevelTilemapManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public LevelTilemapSnapshot capture() {
        return manager.capturePersistentBgNametableSnapshot();
    }

    @Override
    public void restore(LevelTilemapSnapshot snapshot) {
        manager.restorePersistentBgNametableSnapshot(snapshot);
    }

    @Override
    public void resetForMissingSnapshot() {
        manager.restorePersistentBgNametableSnapshot(LevelTilemapSnapshot.invalid());
    }
}
