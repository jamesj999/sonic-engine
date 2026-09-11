package com.openggf.game.recording;

import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserRecordingCatalog {
    @TempDir
    Path tempDir;

    @Test
    void scansWriterProducedBk2sNewestFirstWithFrameCountsAndNoOfficialWarning() throws Exception {
        BuildIdentity identity = new BuildIdentity("0.6.1", "", false);
        Path older = writeRecording("older.bk2", manifest("older", identity, 1));
        Path newer = writeRecording("newer.bk2", manifest("newer", identity, 3));
        setModifiedAt(older, "2026-06-29T12:00:00Z");
        setModifiedAt(newer, "2026-06-29T13:00:00Z");

        List<UserRecordingEntry> entries = UserRecordingCatalog.scan(tempDir, "s3k", identity);

        assertEquals(2, entries.size());
        assertEquals(newer, entries.get(0).path());
        assertEquals("newer", entries.get(0).displayName());
        assertEquals(3, entries.get(0).frameCount());
        assertEquals(RecordingVersionWarning.NONE, entries.get(0).versionWarning());
        assertTrue(entries.get(0).isLoadable());
        assertEquals(older, entries.get(1).path());
    }

    @Test
    void defaultStaticScanUsesRecordingsDirectoryWhenGameDirectoryIsAbsent() throws Exception {
        List<UserRecordingEntry> entries = UserRecordingCatalog.scan(
                "__missing_recording_catalog_test_game__",
                new BuildIdentity("0.6.1", "", false));

        assertTrue(entries.isEmpty());
    }

    @Test
    void officialBaseVersionMismatchWarns() throws Exception {
        Path bk2 = writeRecording(
                "official-mismatch.bk2",
                manifest("official-mismatch", new BuildIdentity("0.6.0", "", false), 1));

        UserRecordingEntry entry = singleEntry(new BuildIdentity("0.6.1", "", false));

        assertEquals(bk2, entry.path());
        assertEquals(RecordingVersionWarning.OFFICIAL_VERSION_MISMATCH, entry.versionWarning());
        assertTrue(entry.isLoadable());
    }

    @Test
    void prereleaseSameBaseAndCommitHasNoWarning() throws Exception {
        BuildIdentity identity = new BuildIdentity("0.6.prerelease", "abcdef123", false);
        writeRecording("prerelease-same.bk2", manifest("prerelease-same", identity, 1));

        UserRecordingEntry entry = singleEntry(identity);

        assertEquals(RecordingVersionWarning.NONE, entry.versionWarning());
    }

    @Test
    void prereleaseDifferentCommitWarns() throws Exception {
        writeRecording(
                "prerelease-different.bk2",
                manifest("prerelease-different", new BuildIdentity("0.6.prerelease", "abcdef123", false), 1));

        UserRecordingEntry entry = singleEntry(new BuildIdentity("0.6.prerelease", "987654321", false));

        assertEquals(RecordingVersionWarning.PRERELEASE_BUILD_MISMATCH, entry.versionWarning());
    }

    @Test
    void dirtyBuildWarnsBeforeCompatibilityChecks() throws Exception {
        writeRecording(
                "dirty.bk2",
                manifest("dirty", new BuildIdentity("0.6.prerelease", "abcdef123", true), 1));

        UserRecordingEntry entry = singleEntry(new BuildIdentity("0.6.prerelease", "abcdef123", false));

        assertEquals(RecordingVersionWarning.DIRTY_BUILD, entry.versionWarning());
    }

    @Test
    void malformedBk2ProducesNonLoadableEntryWithoutAbortingScan() throws Exception {
        BuildIdentity identity = new BuildIdentity("0.6.1", "", false);
        Path malformed = recordingDir().resolve("malformed.bk2");
        Files.writeString(malformed, "not a zip", StandardCharsets.UTF_8);

        List<UserRecordingEntry> entries = UserRecordingCatalog.scan(tempDir, "s3k", identity);

        assertEquals(1, entries.size());
        UserRecordingEntry entry = entries.get(0);
        assertEquals(malformed, entry.path());
        assertEquals("malformed", entry.displayName());
        assertFalse(entry.isLoadable());
        assertEquals(RecordingVersionWarning.MISSING_METADATA, entry.versionWarning());
        assertNotNull(entry.loadError());
        assertTrue(entry.loadError().contains("malformed.bk2"));
    }

    @Test
    void missingEngineIdentityMetadataWarnsButCatalogEntryRemainsLoadable() throws Exception {
        BuildIdentity identity = new BuildIdentity("0.6.1", "", false);
        UserRecordingManifest manifest = manifest("missing-engine-identity", identity, 1);
        Path bk2 = writeRecording("missing-engine-identity.bk2", manifest);
        replaceManifestEntry(bk2, withoutEngineIdentity(UserRecordingJson.writeManifest(manifest)));

        UserRecordingEntry entry = singleEntry(identity);

        assertEquals(bk2, entry.path());
        assertEquals("missing-engine-identity", entry.displayName());
        assertEquals(1, entry.frameCount());
        assertEquals(RecordingVersionWarning.MISSING_METADATA, entry.versionWarning());
        assertTrue(entry.isLoadable());
        assertTrue(entry.loadError() == null || entry.loadError().isBlank());
    }

    private UserRecordingEntry singleEntry(BuildIdentity currentIdentity) throws Exception {
        List<UserRecordingEntry> entries = UserRecordingCatalog.scan(tempDir, "s3k", currentIdentity);
        assertEquals(1, entries.size());
        return entries.get(0);
    }

    private Path writeRecording(String fileName, UserRecordingManifest manifest) throws Exception {
        Path bk2 = recordingDir().resolve(fileName);
        int frameCount = manifest.frameCount();
        UserRecordingWriter.write(bk2, manifest, inputs(frameCount), sidecarFrames(frameCount));
        return bk2;
    }

    private Path recordingDir() throws Exception {
        Path dir = tempDir.resolve("recordings").resolve("s3k");
        Files.createDirectories(dir);
        return dir;
    }

    private static void setModifiedAt(Path path, String instant) throws Exception {
        Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.from(Instant.parse(instant)));
    }

    private static UserRecordingManifest manifest(String movieName, BuildIdentity identity, int frameCount) {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                movieName,
                identity,
                new RecordingLaunchContext(
                        "s3k",
                        0,
                        1,
                        "sonic",
                        List.of("tails"),
                        true,
                        "current-act-fresh-start"),
                UserRecordingSidecarMetadata.everyFrame(),
                new RecordingDeterminismMetadata(0, null),
                "A",
                frameCount,
                UserRecordingStopReason.USER_STOPPED,
                Instant.parse("2026-06-29T14:30:22Z"));
    }

    private static List<RecordedFrameInput> inputs(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(frame -> new RecordedFrameInput(frame, 0, 0, false, 0, 0, false))
                .toList();
    }

    private static List<DesyncLiteFrame> sidecarFrames(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(TestUserRecordingCatalog::sidecarFrame)
                .toList();
    }

    private static DesyncLiteFrame sidecarFrame(int frame) {
        return new DesyncLiteFrame(frame, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static String withoutEngineIdentity(String manifestJson) {
        return manifestJson.replaceFirst("(?s)\\s+\"engineIdentity\"\\s*:\\s*\\{.*?\\},", "");
    }

    private static void replaceManifestEntry(Path bk2, String manifestJson) throws Exception {
        Path tmp = Files.createTempFile(bk2.getParent(), "manifest-rewrite-", ".bk2");
        try (ZipFile original = new ZipFile(bk2.toFile());
                ZipOutputStream rewritten = new ZipOutputStream(Files.newOutputStream(tmp), StandardCharsets.UTF_8)) {
            var entries = original.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                rewritten.putNextEntry(new ZipEntry(entry.getName()));
                if ("OpenGGF/manifest.json".equals(entry.getName())) {
                    rewritten.write(manifestJson.getBytes(StandardCharsets.UTF_8));
                } else {
                    try (var input = original.getInputStream(entry)) {
                        input.transferTo(rewritten);
                    }
                }
                rewritten.closeEntry();
            }
        }
        Files.move(tmp, bk2, StandardCopyOption.REPLACE_EXISTING);
    }
}
