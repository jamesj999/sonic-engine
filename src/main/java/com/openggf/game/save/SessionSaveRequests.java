package com.openggf.game.save;

import com.openggf.game.session.SessionManager;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * Shared helper for exact runtime save requests. Callers use original gameplay
 * write points and this helper handles the active-slot no-op behavior.
 */
public final class SessionSaveRequests {
    private static final Logger LOGGER = Logger.getLogger(SessionSaveRequests.class.getName());
    private static final SaveManager SAVE_MANAGER = new SaveManager(Path.of("saves"));

    private SessionSaveRequests() {
    }

    public static void requestCurrentSessionSave(SaveReason reason) {
        var worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession == null || worldSession.getSaveSessionContext() == null) {
            return;
        }
        try {
            worldSession.getSaveSessionContext().requestSaveAsync(
                    reason,
                    RuntimeSaveContext.forGameplayMode(
                            SessionManager.getCurrentGameplayMode(),
                            worldSession.getSaveSessionContext()),
                    worldSession.getGameModule().getSaveSnapshotProvider(),
                    SAVE_MANAGER);
        } catch (IOException e) {
            LOGGER.warning("Failed to write save: " + e.getMessage());
        }
    }

    /** Waits for in-game saves still queued on the save-writer thread; for shutdown. */
    public static void flushPendingSaves() {
        SAVE_MANAGER.flushPendingWrites();
    }
}
