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
    private static Path saveRoot;
    private static SaveManager saveManager;

    private SessionSaveRequests() {
    }

    public static void requestCurrentSessionSave(SaveReason reason) {
        var worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession == null || worldSession.getSaveSessionContext() == null) {
            return;
        }
        try {
            var context = worldSession.getSaveSessionContext();
            var runtime = RuntimeSaveContext.forGameplayMode(
                    SessionManager.getCurrentGameplayMode(), context);
            var snapshots = worldSession.getGameModule().getSaveSnapshotProvider();
            SaveManager manager = currentSaveManager();
            switch (reason) {
                case NEW_SLOT_START, EXISTING_SLOT_LOAD, CLEAR_RESTART_COMMIT -> {
                    // A menu launch commits its slot before returning. Older
                    // gameplay writes must finish before this synchronous write
                    // so they cannot later overwrite the committed launch state.
                    manager.flushPendingWrites();
                    context.requestSave(reason, runtime, snapshots, manager);
                }
                case SPECIAL_STAGE_SAVE, PROGRESSION_SAVE, LIVES_CONTINUES_SAVE ->
                        context.requestSaveAsync(reason, runtime, snapshots, manager);
            }
        } catch (IOException e) {
            LOGGER.warning("Failed to write save: " + e.getMessage());
        }
    }

    /** Waits for in-game saves still queued on the save-writer thread; for shutdown. */
    public static synchronized void flushPendingSaves() {
        if (saveManager != null) {
            saveManager.flushPendingWrites();
        }
    }

    private static synchronized SaveManager currentSaveManager() {
        Path root = SavePaths.root().toAbsolutePath().normalize();
        if (!root.equals(saveRoot)) {
            // Keep async writes attached to their owner, including when a test
            // or embedded host changes the process save-root policy.
            flushPendingSaves();
            saveRoot = root;
            saveManager = new SaveManager(root);
        }
        return saveManager;
    }
}
