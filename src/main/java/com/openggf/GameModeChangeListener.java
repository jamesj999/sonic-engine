package com.openggf;

import com.openggf.game.GameMode;

/**
 * Callback interface for game mode changes.
 */
public interface GameModeChangeListener {
    void onGameModeChanged(GameMode oldMode, GameMode newMode);
}
