package com.openggf.game.save;

import com.openggf.game.GameStateManager;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.level.LevelManager;

/**
 * Snapshot input for save providers.
 */
public final class RuntimeSaveContext {
    private final GameplayModeContext gameplayMode;
    private final SaveSessionContext saveSessionContext;

    private RuntimeSaveContext(GameplayModeContext gameplayMode,
                               SaveSessionContext saveSessionContext) {
        this.gameplayMode = gameplayMode;
        this.saveSessionContext = saveSessionContext;
    }

    public static RuntimeSaveContext forGameplayMode(GameplayModeContext gameplayMode,
                                                     SaveSessionContext saveSessionContext) {
        return new RuntimeSaveContext(gameplayMode, saveSessionContext);
    }

    public GameplayModeContext gameplayMode() {
        return gameplayMode;
    }

    public SaveSessionContext saveSessionContext() {
        return saveSessionContext;
    }

    public boolean hasLiveGameplayState() {
        return gameplayMode != null;
    }

    public LevelManager levelManager() {
        return gameplayMode.getLevelManager();
    }

    public GameStateManager gameState() {
        return gameplayMode.getGameStateManager();
    }
}
