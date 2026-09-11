package com.openggf.game.save;

import com.openggf.game.sonic1.dataselect.S1SaveSnapshotProvider;
import com.openggf.game.sonic2.dataselect.S2SaveSnapshotProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that donated S1 and S2 saves on the S3K data select screen
 * write into their own game save roots, round-trip correctly, and that
 * no-save sessions produce no slot files.
 */
class TestDonatedSaveSession {

    @TempDir
    Path root;

    @Test
    void donatedS1NewSlotWritesIntoS1SaveRoot() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam team = new SelectedTeam("sonic", List.of());
        SaveSessionContext session = SaveSessionContext.forSlot("s1", 1, team, 0, 0);

        S1SaveSnapshotProvider snapshot = new S1SaveSnapshotProvider();
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);
        session.requestSave(SaveReason.NEW_SLOT_START, ctx, snapshot, manager);

        assertTrue(Files.exists(root.resolve("s1").resolve("slot1.json")),
                "S1 save should appear under saves/s1/");
        SaveSlotSummary summary = manager.readSlotSummary("s1", 1);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(0, summary.payload().get("zone"));
        assertEquals("sonic", summary.payload().get("mainCharacter"));
    }

    @Test
    void donatedS2NewSlotWritesIntoS2SaveRoot() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam team = new SelectedTeam("sonic", List.of("tails"));
        SaveSessionContext session = SaveSessionContext.forSlot("s2", 3, team, 0, 0);

        S2SaveSnapshotProvider snapshot = new S2SaveSnapshotProvider();
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);
        session.requestSave(SaveReason.NEW_SLOT_START, ctx, snapshot, manager);

        assertTrue(Files.exists(root.resolve("s2").resolve("slot3.json")),
                "S2 save should appear under saves/s2/");
        SaveSlotSummary summary = manager.readSlotSummary("s2", 3);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(0, summary.payload().get("zone"));
        assertEquals("sonic", summary.payload().get("mainCharacter"));
        assertEquals(List.of("tails"), summary.payload().get("sidekicks"));
    }

    @Test
    void donatedS1NoSaveDoesNotWriteAnySlotFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam team = new SelectedTeam("sonic", List.of());
        SaveSessionContext session = SaveSessionContext.noSave("s1", team, 0, 0);

        S1SaveSnapshotProvider snapshot = new S1SaveSnapshotProvider();
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);
        session.requestSave(SaveReason.NEW_SLOT_START, ctx, snapshot, manager);

        assertFalse(Files.exists(root.resolve("s1")),
                "No-save session should not create any save directory");
    }

    @Test
    void donatedS2NoSaveDoesNotWriteAnySlotFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam team = new SelectedTeam("sonic", List.of("tails"));
        SaveSessionContext session = SaveSessionContext.noSave("s2", team, 0, 0);

        S2SaveSnapshotProvider snapshot = new S2SaveSnapshotProvider();
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);
        session.requestSave(SaveReason.NEW_SLOT_START, ctx, snapshot, manager);

        assertFalse(Files.exists(root.resolve("s2")),
                "No-save session should not create any save directory");
    }

    @Test
    void donatedS1LoadSlotPreservesHostPayload() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam team = new SelectedTeam("sonic", List.of());

        // Write an initial save
        Map<String, Object> initialPayload = Map.of(
                "zone", 3, "act", 0,
                "mainCharacter", "sonic", "sidekicks", List.of(),
                "lives", 5, "chaosEmeralds", List.of(0, 1, 2, 3), "clear", false,
                "progressCode", 4, "clearState", 0);
        manager.writeSlot("s1", 2, initialPayload);

        // Read it back and verify the payload survived
        SaveSlotSummary summary = manager.readSlotSummary("s1", 2);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(3, summary.payload().get("zone"));
        assertEquals(5, summary.payload().get("lives"));
        assertEquals(List.of(0, 1, 2, 3), summary.payload().get("chaosEmeralds"));
    }

    @Test
    void donatedS2ClearRestartPreservesHostPayload() throws Exception {
        SaveManager manager = new SaveManager(root);

        // Write a clear save
        Map<String, Object> clearPayload = Map.of(
                "zone", 10, "act", 0,
                "mainCharacter", "sonic", "sidekicks", List.of("tails"),
                "lives", 7, "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6), "clear", true,
                "progressCode", 11, "clearState", 1);
        manager.writeSlot("s2", 1, clearPayload);

        SaveSlotSummary summary = manager.readSlotSummary("s2", 1);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(true, summary.payload().get("clear"));
        assertEquals(1, summary.payload().get("clearState"));
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), summary.payload().get("chaosEmeralds"));
    }

    @Test
    void s1SnapshotProvider_capturesExpectedFields() {
        SelectedTeam team = new SelectedTeam("knuckles", List.of());
        SaveSessionContext session = SaveSessionContext.forSlot("s1", 1, team, 2, 0);
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);

        Map<String, Object> payload = new S1SaveSnapshotProvider().capture(
                SaveReason.NEW_SLOT_START, ctx);

        assertEquals(2, payload.get("zone"));
        assertEquals(0, payload.get("act"));
        assertEquals("knuckles", payload.get("mainCharacter"));
        assertEquals(3, payload.get("lives")); // default
        assertEquals(List.of(), payload.get("chaosEmeralds")); // default
        assertFalse(payload.containsKey("emeraldCount"));
        assertEquals(false, payload.get("clear"));
        assertEquals(3, payload.get("progressCode")); // zone + 1
    }

    @Test
    void s2SnapshotProvider_capturesExpectedFields() {
        SelectedTeam team = new SelectedTeam("sonic", List.of("tails"));
        SaveSessionContext session = SaveSessionContext.forSlot("s2", 5, team, 4, 1);
        RuntimeSaveContext ctx = RuntimeSaveContext.forGameplayMode(null, session);

        Map<String, Object> payload = new S2SaveSnapshotProvider().capture(
                SaveReason.NEW_SLOT_START, ctx);

        assertEquals(4, payload.get("zone"));
        assertEquals(1, payload.get("act"));
        assertEquals("sonic", payload.get("mainCharacter"));
        assertEquals(List.of("tails"), payload.get("sidekicks"));
        assertEquals(3, payload.get("lives")); // default
        assertEquals(List.of(), payload.get("chaosEmeralds")); // default
        assertFalse(payload.containsKey("emeraldCount"));
        assertEquals(5, payload.get("progressCode")); // zone + 1
    }
}
