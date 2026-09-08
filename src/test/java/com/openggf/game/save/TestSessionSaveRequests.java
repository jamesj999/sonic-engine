package com.openggf.game.save;

import com.openggf.game.GameModule;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class TestSessionSaveRequests {
    @TempDir
    Path root;

    @Test
    void rootChangeDrainsOldWritesAndShutdownFlushDrainsCurrentWrites() throws Exception {
        String previousRoot = System.getProperty(SavePaths.ROOT_PROPERTY);
        SessionSaveRequests.flushPendingSaves();
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        WorldSession world = mock(WorldSession.class);
        GameModule module = mock(GameModule.class);
        AtomicInteger progress = new AtomicInteger();
        when(world.getGameModule()).thenReturn(module);
        when(world.getSaveSessionContext()).thenReturn(SaveSessionContext.forSlot(
                "s3k", 1, new SelectedTeam("sonic", List.of("tails")), 0, 0));
        when(module.getSaveSnapshotProvider()).thenReturn(
                (reason, runtime) -> Map.of("progress", progress.get()));

        try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            sessions.when(SessionManager::getCurrentWorldSession).thenReturn(world);
            System.setProperty(SavePaths.ROOT_PROPERTY, first.toString());
            for (int value = 1; value <= 20; value++) {
                progress.set(value);
                SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
            }
            System.setProperty(SavePaths.ROOT_PROPERTY, second.toString());
            progress.set(21);
            SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);

            // Read through another manager: it cannot implicitly flush the old
            // owner's futures. Switching roots must already have drained them.
            assertTrue(Files.exists(first.resolve("s3k/slot1.json")));
            assertEquals(20, new SaveManager(first).readSlotSummary("s3k", 1)
                    .payload().get("progress"));

            progress.set(22);
            SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
            SessionSaveRequests.flushPendingSaves();

            assertEquals(22, new SaveManager(second).readSlotSummary("s3k", 1)
                    .payload().get("progress"));
            assertEquals(20, new SaveManager(first).readSlotSummary("s3k", 1)
                    .payload().get("progress"));
        } finally {
            SessionSaveRequests.flushPendingSaves();
            restoreRoot(previousRoot);
        }
    }

    @ParameterizedTest
    @EnumSource(value = SaveReason.class, names = {
            "NEW_SLOT_START", "EXISTING_SLOT_LOAD", "CLEAR_RESTART_COMMIT"})
    void menuCommitIsImmediatelyDurableAndCannotBeOverwrittenByQueuedProgression(
            SaveReason reason) throws Exception {
        String previousRoot = System.getProperty(SavePaths.ROOT_PROPERTY);
        SessionSaveRequests.flushPendingSaves();
        WorldSession world = mock(WorldSession.class);
        GameModule module = mock(GameModule.class);
        AtomicInteger progress = new AtomicInteger();
        when(world.getGameModule()).thenReturn(module);
        when(world.getSaveSessionContext()).thenReturn(SaveSessionContext.forSlot(
                "s3k", 1, new SelectedTeam("sonic", List.of()), 0, 0));
        when(module.getSaveSnapshotProvider()).thenReturn(
                (requested, runtime) -> Map.of("progress", progress.get()));

        try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            sessions.when(SessionManager::getCurrentWorldSession).thenReturn(world);
            System.setProperty(SavePaths.ROOT_PROPERTY, root.toString());
            for (int value = 1; value <= 20; value++) {
                progress.set(value);
                SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
            }
            progress.set(21);

            SessionSaveRequests.requestCurrentSessionSave(reason);

            // A fresh reader cannot flush the session writer's queue: the
            // menu commit itself must have made the latest state durable.
            assertEquals(21, new SaveManager(root).readSlotSummary("s3k", 1)
                    .payload().get("progress"));
            SessionSaveRequests.flushPendingSaves();
            assertEquals(21, new SaveManager(root).readSlotSummary("s3k", 1)
                    .payload().get("progress"));
        } finally {
            SessionSaveRequests.flushPendingSaves();
            restoreRoot(previousRoot);
        }
    }

    @Test
    void absentSessionDoesNotCreateFilesInChangedRoot() {
        String previousRoot = System.getProperty(SavePaths.ROOT_PROPERTY);
        Path unused = root.resolve("unused");
        try (MockedStatic<SessionManager> sessions = mockStatic(SessionManager.class)) {
            System.setProperty(SavePaths.ROOT_PROPERTY, unused.toString());
            SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
            SessionSaveRequests.flushPendingSaves();
            assertFalse(Files.exists(unused));
        } finally {
            restoreRoot(previousRoot);
        }
    }

    private static void restoreRoot(String previous) {
        if (previous == null) {
            System.clearProperty(SavePaths.ROOT_PROPERTY);
        } else {
            System.setProperty(SavePaths.ROOT_PROPERTY, previous);
        }
    }
}
