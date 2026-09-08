package com.openggf.game.save;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestSaveManager {

    @TempDir
    Path root;

    @Test
    void malformedFile_isRenamedToCorrupt() throws Exception {
        Path slot = root.resolve("s3k").resolve("slot1.json");
        Files.createDirectories(slot.getParent());
        Files.writeString(slot, "{ not-json");
        SaveManager manager = new SaveManager(root);
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);
        assertEquals(SaveSlotState.EMPTY, summary.state());
        assertTrue(Files.exists(slot.resolveSibling("slot1.json.corrupt")));
    }

    @Test
    void malformedFile_preservesExistingCorruptArtifacts() throws Exception {
        Path slot = root.resolve("s3k").resolve("slot1.json");
        Path corrupt = slot.resolveSibling("slot1.json.corrupt");
        Path nextCorrupt = slot.resolveSibling("slot1.json.corrupt.1");
        Files.createDirectories(slot.getParent());
        Files.writeString(slot, "{ not-json");
        Files.writeString(corrupt, "old");
        SaveManager manager = new SaveManager(root);

        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);

        assertEquals(SaveSlotState.EMPTY, summary.state());
        assertEquals("old", Files.readString(corrupt));
        assertEquals("{ not-json", Files.readString(nextCorrupt));
    }

    @Test
    void transientReadIOException_leavesSaveInPlace() throws Exception {
        Path slot = root.resolve("s3k").resolve("slot1.json");
        Files.createDirectories(slot.getParent());
        Files.writeString(slot, "valid bytes temporarily locked by another process");
        SaveManager manager = new SaveManager(root, file -> {
            throw new java.io.IOException("sharing violation");
        });

        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);

        assertEquals(SaveSlotState.UNAVAILABLE, summary.state());
        assertTrue(Files.exists(slot), "transient I/O must not quarantine the original save");
        assertFalse(Files.exists(slot.resolveSibling("slot1.json.corrupt")));
        assertFalse(summary.isLoadable());
        assertFalse(summary.hasRecoverablePayload());
    }

    @Test
    void hashMismatch_keepsPayloadForRecoveryButIsNotLoadable() throws Exception {
        SaveManager manager = new SaveManager(root);
        manager.writeSlot("s3k", 1, Map.of("zone", 0, "act", 0));
        Path slot = root.resolve("s3k").resolve("slot1.json");
        Files.writeString(slot, Files.readString(slot).replace("\"hash\":\"", "\"hash\":\"broken"));
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);
        assertEquals(SaveSlotState.HASH_WARNING, summary.state());
        assertFalse(summary.payload().isEmpty());
        assertFalse(summary.isLoadable());
        assertTrue(summary.hasRecoverablePayload());
    }

    @Test
    void writeAndRead_roundTrips() throws Exception {
        SaveManager manager = new SaveManager(root);
        Map<String, Object> payload = Map.of("zone", 2, "act", 1, "emeralds", 3);
        manager.writeSlot("s3k", 1, payload);
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(1, summary.slot());
        assertEquals(2, summary.payload().get("zone"));
        assertEquals(1, summary.payload().get("act"));
        assertEquals(3, summary.payload().get("emeralds"));
    }

    @Test
    void readMissingSlot_returnsEmpty() throws Exception {
        SaveManager manager = new SaveManager(root);
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 5);
        assertEquals(SaveSlotState.EMPTY, summary.state());
        assertEquals(5, summary.slot());
        assertTrue(summary.payload().isEmpty());
    }

    @Test
    void wrongGame_quarantinesFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        manager.writeSlot("s2", 1, Map.of("zone", 0));
        Path s2File = root.resolve("s2").resolve("slot1.json");
        Path s3kDir = root.resolve("s3k");
        Files.createDirectories(s3kDir);
        Files.copy(s2File, s3kDir.resolve("slot1.json"));
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);
        assertEquals(SaveSlotState.EMPTY, summary.state());
        assertTrue(Files.exists(s3kDir.resolve("slot1.json.corrupt")));
    }

    @Test
    void structurallyInvalidPayload_quarantinesFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        manager.writeSlot("s3k", 1, Map.of(
                "zone", 99,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", java.util.List.of(),
                "lives", 3,
                "chaosEmeralds", java.util.List.of(),
                "superEmeralds", java.util.List.of(),
                "clear", false
        ));
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1, new com.openggf.game.sonic3k.dataselect.S3kDataSelectProfile());
        assertEquals(SaveSlotState.EMPTY, summary.state());
        assertTrue(Files.exists(root.resolve("s3k").resolve("slot1.json.corrupt")));
    }

    @Test
    void noSaveSession_requestSaveDoesNotWriteFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        SaveSessionContext ctx = SaveSessionContext.noSave("s3k",
                new SelectedTeam("sonic", java.util.List.of()), 0, 0);
        ctx.requestSave(SaveReason.PROGRESSION_SAVE,
                RuntimeSaveContext.forGameplayMode(null, ctx),
                (reason, runtime) -> java.util.Map.of("zone", 0, "act", 1),
                manager);
        assertTrue(Files.notExists(root.resolve("s3k").resolve("slot1.json")));
    }

    @Test
    void selectedTeamReplacementIsDurableButLaunchTeamOverrideIsNot() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam original = new SelectedTeam("sonic", java.util.List.of("tails"));
        SaveSnapshotProvider teamSnapshot = (reason, runtime) -> java.util.Map.of(
                "mainCharacter", runtime.saveSessionContext().selectedTeam().mainCharacter(),
                "sidekicks", runtime.saveSessionContext().selectedTeam().sidekicks());

        SaveSessionContext sanitized = SaveSessionContext.forSlot("s2", 1, original, 0, 0)
                .withSelectedTeam(new SelectedTeam("knuckles", java.util.List.of()));
        sanitized.requestSave(SaveReason.NEW_SLOT_START,
                RuntimeSaveContext.forGameplayMode(null, sanitized), teamSnapshot, manager);

        SaveSessionContext forcedLaunch = SaveSessionLaunchTeamAccess.withLaunchTeam(
                SaveSessionContext.forSlot("s2", 2, original, 0, 0),
                new com.openggf.game.GameplayLaunchTeam(
                        com.openggf.game.CharacterKey.TAILS, java.util.List.of()));
        forcedLaunch.requestSave(SaveReason.NEW_SLOT_START,
                RuntimeSaveContext.forGameplayMode(null, forcedLaunch), teamSnapshot, manager);

        assertEquals("knuckles", manager.readSlotSummary("s2", 1).payload()
                .get("mainCharacter"));
        assertEquals("sonic", manager.readSlotSummary("s2", 2).payload()
                .get("mainCharacter"));
        assertEquals(java.util.List.of("tails"), manager.readSlotSummary("s2", 2).payload()
                .get("sidekicks"));
    }

    @Test
    void asyncProgressionSavePreservesDurableTeamAndClearFlag() throws Exception {
        SaveManager manager = new SaveManager(root);
        SelectedTeam selected = new SelectedTeam("sonic", java.util.List.of("tails"));
        SaveSessionContext launch = SaveSessionLaunchTeamAccess.withLaunchTeam(
                SaveSessionContext.forSlot("s3k", 1, selected, 0, 0),
                new com.openggf.game.GameplayLaunchTeam(
                        com.openggf.game.CharacterKey.KNUCKLES, java.util.List.of()));
        launch.markClear();

        launch.requestSaveAsync(SaveReason.PROGRESSION_SAVE,
                RuntimeSaveContext.forGameplayMode(null, launch),
                (reason, runtime) -> Map.of(
                        "mainCharacter", runtime.saveSessionContext().selectedTeam().mainCharacter(),
                        "sidekicks", runtime.saveSessionContext().selectedTeam().sidekicks(),
                        "clear", runtime.saveSessionContext().isClear()), manager);
        manager.flushPendingWrites();

        Map<String, Object> payload = new SaveManager(root).readSlotSummary("s3k", 1).payload();
        assertEquals("sonic", payload.get("mainCharacter"));
        assertEquals(java.util.List.of("tails"), payload.get("sidekicks"));
        assertEquals(true, payload.get("clear"));
    }

    @Test
    void multipleSlots_independentReadWrite() throws Exception {
        SaveManager manager = new SaveManager(root);
        manager.writeSlot("s3k", 1, Map.of("zone", 0));
        manager.writeSlot("s3k", 2, Map.of("zone", 3));
        SaveSlotSummary s1 = manager.readSlotSummary("s3k", 1);
        SaveSlotSummary s2 = manager.readSlotSummary("s3k", 2);
        assertEquals(0, s1.payload().get("zone"));
        assertEquals(3, s2.payload().get("zone"));
    }

    @Test
    void writeSlot_publishesAtomicallyAndLeavesNoTempArtifact() throws Exception {
        SaveManager manager = new SaveManager(root);

        manager.writeSlot("s3k", 1, Map.of("zone", 4, "act", 1));

        Path slotDir = root.resolve("s3k");
        Path slot = slotDir.resolve("slot1.json");
        // A successful write must leave a valid, loadable slot...
        assertTrue(Files.exists(slot), "slot file must exist after a successful write");
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 1);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(4, summary.payload().get("zone"));
        // ...and never leave a half-written sibling temp file behind.
        assertNoTempArtifacts(slotDir);
    }

    @Test
    void writeSlot_failedWriteLeavesPriorSlotIntactAndNoPartialFile() throws Exception {
        SaveManager manager = new SaveManager(root);
        manager.writeSlot("s3k", 1, Map.of("zone", 4, "act", 1));
        Path slotDir = root.resolve("s3k");
        Path slot = slotDir.resolve("slot1.json");
        String before = Files.readString(slot);

        // A self-referential structure cannot be serialized by Jackson, so the
        // write fails before the atomic publish. The prior slot must survive
        // untouched and no partial/temp file may be left in its place.
        java.util.List<Object> cyclic = new java.util.ArrayList<>();
        cyclic.add(cyclic);
        Map<String, Object> poison = Map.of("zone", 9, "bad", cyclic);

        assertThrows(Exception.class, () -> manager.writeSlot("s3k", 1, poison),
                "an unserializable payload must fail the write");

        assertTrue(Files.exists(slot), "prior valid slot must remain after a failed write");
        assertEquals(before, Files.readString(slot),
                "failed write must not mutate the previously published slot");
        assertNoTempArtifacts(slotDir);
    }

    private static void assertNoTempArtifacts(Path slotDir) throws Exception {
        try (var stream = Files.list(slotDir)) {
            var leftovers = stream
                    .filter(p -> p.getFileName().toString().contains(".tmp"))
                    .toList();
            assertTrue(leftovers.isEmpty(),
                    "no temp publish artifacts may remain, found: " + leftovers);
        }
    }

    @Test
    void writeSlotAsync_landsBeforeTheNextReadAndAfterFlush() throws Exception {
        SaveManager manager = new SaveManager(root);
        Path slot = root.resolve("s3k").resolve("slot2.json");

        manager.writeSlotAsync("s3k", 2, Map.of("zone", 0, "act", 1, "lives", 3));
        // readSlotSummary flushes the writer first, so a read never observes a
        // save that was issued but not yet on disk.
        SaveSlotSummary summary = manager.readSlotSummary("s3k", 2);
        assertEquals(SaveSlotState.VALID, summary.state());
        assertEquals(1, summary.payload().get("act"));
        assertTrue(Files.exists(slot));

        manager.writeSlotAsync("s3k", 2, Map.of("zone", 1, "act", 0, "lives", 3));
        manager.flushPendingWrites();
        assertEquals(1, manager.readSlotSummary("s3k", 2).payload().get("zone"),
                "writes for one slot must complete in submission order");
        assertFalse(Files.list(slot.getParent()).anyMatch(p -> p.toString().endsWith(".tmp")),
                "no temp file left behind");
    }
}
