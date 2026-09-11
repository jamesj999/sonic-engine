package com.openggf.game;

import com.openggf.level.Level;

/**
 * Optional hook for game-level palette writes that are part of ROM level load
 * state and need ownership metadata after zone-scoped registries reset.
 */
public interface LevelLoadPaletteOverrideProvider {
    void applyLevelLoadPaletteOverrides(Level level, int zone, int act);
}
