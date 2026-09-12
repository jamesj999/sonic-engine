package com.openggf.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPendingModStateEditor {
    @TempDir
    Path temp;

    @Test
    void normalizationRetainsUnknownIdsAndAppendsNewDescriptorsDisabledInStableOrder() {
        ModState persisted = new ModState(1, List.of(
                new ModState.Entry("known-b", true, 4),
                new ModState.Entry("temporarily-missing", true, 8, true, "a".repeat(64))));
        List<ModCatalogEntry> scanned = List.of(descriptor("known-a"), descriptor("known-b"));

        ModState normalized = persisted.normalize(scanned);

        assertEquals(List.of(
                new ModState.Entry("known-b", true, 0),
                new ModState.Entry("temporarily-missing", true, 1, true, "a".repeat(64)),
                new ModState.Entry("known-a", false, 2)), normalized.entries());
    }

    @Test
    void catalogValuesDefensivelyCopyScannedAndEffectiveLists() {
        ModDescriptor enabled = descriptor("enabled");
        ModDescriptor disabled = descriptor("disabled");
        InvalidModEntry invalid = new InvalidModEntry(temp.resolve("bad.jar"), List.of(error("BAD_JAR")));
        RepositoryScanFailure repositoryFailure = new RepositoryScanFailure(temp, List.of(error("BAD_ROOT")));

        java.util.ArrayList<ModCatalogEntry> catalogEntries = new java.util.ArrayList<>(
                List.of(repositoryFailure, disabled, invalid, enabled));
        java.util.ArrayList<ModDescriptor> effectiveEntries = new java.util.ArrayList<>(List.of(enabled));
        ModCatalog catalog = new ModCatalog(catalogEntries, new EffectiveModCatalog(effectiveEntries));
        catalogEntries.clear();
        effectiveEntries.clear();

        assertEquals(4, catalog.scanned().size());
        assertEquals(List.of(enabled), catalog.effective().orderedEnabled());
        assertThrows(UnsupportedOperationException.class,
                () -> catalog.effective().orderedEnabled().add(disabled));
        assertThrows(UnsupportedOperationException.class, () -> catalog.scanned().clear());
        assertTrue(EffectiveModCatalog.EMPTY.orderedEnabled().isEmpty());
    }

    @Test
    void pendingEditsAndCascadesNeverMutateTheEffectiveSnapshotAndReversionClearsRestartFlag()
            throws Exception {
        Path root = temp.toAbsolutePath().normalize();
        ModDescriptor first = descriptor("first");
        ModDescriptor second = descriptor("second");
        List<ModCatalogEntry> scanned = List.of(first, second);
        ModState startup = new ModState(1, List.of(
                new ModState.Entry("first", true, 0),
                new ModState.Entry("second", false, 1)));
        ModCatalog frozen = new ModCatalog(scanned, new EffectiveModCatalog(List.of(first)));
        PendingModStateEditor editor = new PendingModStateEditor(startup, scanned, new ModStateStore(root));

        editor.enable("second");
        editor.move("second", 0);
        assertTrue(editor.restartRequired());
        assertEquals(List.of(first), frozen.effective().orderedEnabled());
        assertEquals(List.of("second", "first"), editor.pendingState().entries().stream()
                .map(ModState.Entry::id).toList());

        editor.setEnabledCascade(List.of("first", "second"), false);
        assertTrue(editor.pendingState().entries().stream().noneMatch(ModState.Entry::enabled));
        assertEquals(List.of(first), frozen.effective().orderedEnabled());

        editor.resetToStartup();
        assertFalse(editor.restartRequired());
        assertEquals(startup, editor.pendingState());
    }

    @Test
    void editorPropagatesSaveResultAndRejectsUnknownEditTargets() throws Exception {
        Path root = temp.toAbsolutePath().normalize();
        ModDescriptor descriptor = descriptor("known");
        ModState startup = new ModState(1, List.of(new ModState.Entry("known", false, 0)));
        PendingModStateEditor editor = new PendingModStateEditor(
                startup, List.of(descriptor), new ModStateStore(root));

        assertThrows(IllegalArgumentException.class, () -> editor.enable("unknown"));
        editor.enable("known");
        assertInstanceOf(ModStateSaveResult.Saved.class, editor.save());
        assertEquals(editor.pendingState(), new ModStateStore(root).load().state());
        assertTrue(Files.exists(root.resolve("modstate.json")));
    }

    @Test
    void staleJarTrustIsClearedInPendingStateAndFreshGrantUsesScannedHash() {
        Path root = temp.toAbsolutePath().normalize();
        ModDescriptor descriptor = codeDescriptor("code", "b".repeat(64));
        ModState startup = new ModState(1, List.of(
                new ModState.Entry("code", true, 0, true, "a".repeat(64))));
        PendingModStateEditor editor = new PendingModStateEditor(
                startup, List.of(descriptor), new ModStateStore(root));

        ModState.Entry revoked = editor.pendingState().entries().getFirst();
        assertFalse(revoked.trusted());
        assertEquals(null, revoked.trustedJarSha256());
        assertFalse(editor.restartRequired(), "revocation is part of boot normalization");

        editor.trust("code");
        ModState.Entry granted = editor.pendingState().entries().getFirst();
        assertTrue(granted.trusted());
        assertEquals(descriptor.sha256(), granted.trustedJarSha256());

        editor.disable("code");
        editor.move("code", 0);
        ModState.Entry disabled = editor.pendingState().entries().getFirst();
        assertFalse(disabled.enabled());
        assertTrue(disabled.trustsSha256(descriptor.sha256()));
    }

    @Test
    void discardRestoresLatestSuccessfulSaveAndRetainsRestartRequirement() {
        var editor = new PendingModStateEditor(ModState.EMPTY, List.of(descriptor("known")),
                new ModStateStore(temp.toAbsolutePath().normalize()));
        assertFalse(editor.dirty());
        editor.enable("known");
        assertTrue(editor.dirty());
        assertInstanceOf(ModStateSaveResult.Saved.class, editor.save());
        assertFalse(editor.dirty());
        assertTrue(editor.restartRequired());
        ModState saved = editor.pendingState();
        editor.disable("known");
        assertTrue(editor.dirty());
        editor.discardDraft();
        assertEquals(saved, editor.pendingState());
        assertFalse(editor.dirty());
        assertTrue(editor.restartRequired());
    }

    @Test
    void failedSaveDoesNotAdvanceDiscardBaseline() throws Exception {
        Path blocked = temp.resolve("file");
        Files.writeString(blocked, "occupied");
        var editor = new PendingModStateEditor(ModState.EMPTY, List.of(descriptor("known")),
                new ModStateStore(blocked.toAbsolutePath().normalize()));
        ModState original = editor.pendingState();
        editor.enable("known");
        assertInstanceOf(ModStateSaveResult.Failed.class, editor.save());
        assertTrue(editor.dirty());
        editor.discardDraft();
        assertEquals(original, editor.pendingState());
        assertFalse(editor.dirty());
    }

    private ModDescriptor descriptor(String id) {
        ModManifest manifest = new ModManifest(1, id, id, new SemanticVersion(1, 0, 0),
                List.of("Author"), "Description", VersionRange.parse("*"), ModType.PATCH,
                "s1", null, List.of(), Map.of(), Map.of(), null, OptionalInt.empty());
        return new ModDescriptor(temp.resolve(id + ".jar"), manifest, "0".repeat(64), false, List.of());
    }

    private ModDescriptor codeDescriptor(String id, String hash) {
        ModManifest manifest = new ModManifest(1, id, id, new SemanticVersion(1, 0, 0),
                List.of("Author"), "Description", VersionRange.parse("*"), ModType.PATCH,
                "s1", "example.Code", List.of(), Map.of(), Map.of(), null, OptionalInt.empty());
        return new ModDescriptor(temp.resolve(id + ".jar"), manifest, hash, true, List.of());
    }

    private static ModFinding error(String code) {
        return new ModFinding(ModFindingSeverity.ERROR, code, "error", null);
    }
}
