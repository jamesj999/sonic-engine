package com.openggf.game.recording;

import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.GameMode;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserRecordingSessionLauncher {
    @TempDir
    Path tempDir;

    @Test
    void beginRecordingRejectsNonLevelModeBeforeRestarting() {
        Fixture fixture = new Fixture();
        fixture.mode = GameMode.TITLE_SCREEN;

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                fixture.launcher()::beginRecordingFromCurrentLevel);

        assertTrue(thrown.getMessage().contains("LEVEL"));
        assertEquals(0, fixture.restarts.size());
    }

    @Test
    void beginRecordingRejectsTraceOrTestModeBeforeRestarting() {
        Fixture fixture = new Fixture();
        fixture.traceOrTestModeActive = true;

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                fixture.launcher()::beginRecordingFromCurrentLevel);

        assertTrue(thrown.getMessage().contains("Trace"));
        assertEquals(0, fixture.restarts.size());
    }

    @Test
    void captureCurrentLaunchContextDelegatesToCurrentRuntimeContext() {
        Fixture fixture = new Fixture();
        fixture.liveContext = new RecordingLaunchContext(
                "s2", 3, 1, "tails", List.of("sonic", "knuckles"),
                false, "current-act-fresh-start");

        RecordingLaunchContext context = fixture.launcher().captureCurrentLaunchContext();

        assertEquals(fixture.liveContext, context);
    }

    @Test
    void beginRecordingRestartsFreshAndArmsFrameZeroSessionAtFormattedPath() {
        Fixture fixture = new Fixture();
        fixture.now = Instant.parse("2026-06-29T15:04:05Z");
        fixture.liveContext = new RecordingLaunchContext(
                "s3k", 0, 1, "sonic", List.of("tails"),
                true, "current-act-fresh-start");

        UserRecordingSession session = fixture.launcher().beginRecordingFromCurrentLevel();

        assertEquals(List.of(fixture.liveContext), fixture.restarts);
        assertEquals(0, session.currentMovieFrame());
        assertEquals(tempDir.resolve("recordings").resolve("s3k")
                .resolve("s3k-0-1-2026-06-29-150405.bk2"), session.outputBk2Path());
    }

    @Test
    void beginPlaybackUsesManifestLaunchContextNotCurrentLiveContext() throws Exception {
        Fixture fixture = new Fixture();
        fixture.liveContext = new RecordingLaunchContext(
                "s1", 0, 0, "sonic", List.of(),
                false, "current-act-fresh-start");
        RecordingLaunchContext manifestContext = new RecordingLaunchContext(
                "s3k", 5, 2, "knuckles", List.of("tails"),
                true, "current-act-fresh-start");
        Path bk2 = writeRecording("manifest-context.bk2", manifestContext, 2);
        UserRecordingEntry entry = new UserRecordingEntry(
                bk2, "manifest-context", null, 2, fixture.now,
                RecordingVersionWarning.NONE, "");
        UserRecordingPlaybackOptions options = new UserRecordingPlaybackOptions(1, true, false);

        UserRecordingPlaybackState state = fixture.launcher().beginPlayback(entry, options);

        assertEquals(UserRecordingPlaybackState.PLAYING, state);
        assertEquals(List.of(manifestContext), fixture.restarts);
        assertNotNull(fixture.startedMovie);
        assertEquals(2, fixture.startedMovie.getFrameCount());
        assertEquals(0, fixture.startedOffset);
        assertNotNull(fixture.observer);
        assertSame(options, fixture.launcher().activePlaybackOptions());
    }

    @Test
    void beginPlaybackDoesNotRestartWhenMovieLoadFails() throws Exception {
        Fixture fixture = new Fixture();
        RecordingLaunchContext manifestContext = new RecordingLaunchContext(
                "s3k", 5, 2, "knuckles", List.of("tails"),
                true, "current-act-fresh-start");
        Path bk2 = writeRecordingZip("missing-input-log.bk2", manifestContext, 2, false, true);
        UserRecordingEntry entry = new UserRecordingEntry(
                bk2, "missing-input-log", null, 2, fixture.now,
                RecordingVersionWarning.NONE, "");

        IOException thrown = assertThrows(IOException.class,
                () -> fixture.launcher().beginPlayback(entry, new UserRecordingPlaybackOptions(1, true, false)));

        assertTrue(thrown.getMessage().contains("Input Log"));
        assertEquals(0, fixture.restarts.size());
        assertEquals(-1, fixture.startedOffset);
        assertNull(fixture.startedMovie);
        assertNull(fixture.observer);
    }

    @Test
    void beginPlaybackAllowsMissingDesyncLiteSidecar() throws Exception {
        Fixture fixture = new Fixture();
        RecordingLaunchContext manifestContext = new RecordingLaunchContext(
                "s2", 3, 1, "tails", List.of("sonic"),
                false, "current-act-fresh-start");
        Path bk2 = writeRecordingZip("missing-sidecar.bk2", manifestContext, 2, true, false);
        UserRecordingEntry entry = new UserRecordingEntry(
                bk2, "missing-sidecar", null, 2, fixture.now,
                RecordingVersionWarning.NONE, "");

        UserRecordingPlaybackState state = fixture.launcher()
                .beginPlayback(entry, new UserRecordingPlaybackOptions(1, true, false));

        assertEquals(UserRecordingPlaybackState.PLAYING, state);
        assertEquals(List.of(manifestContext), fixture.restarts);
        assertNotNull(fixture.startedMovie);
        assertEquals(2, fixture.startedMovie.getFrameCount());
        assertNotNull(fixture.observer);
        assertNotNull(fixture.launcher().activeVerifier());
        assertEquals("missing-sidecar", fixture.launcher().activeVerifier().result().status());
        assertEquals(0, fixture.launcher().activeVerifier().result().comparedFrames());
    }

    @Test
    void beginPlaybackReportsUnsupportedSidecarSchemaWithoutBlockingPlayback() throws Exception {
        Fixture fixture = new Fixture();
        RecordingLaunchContext manifestContext = new RecordingLaunchContext(
                "s2", 3, 1, "tails", List.of("sonic"),
                false, "current-act-fresh-start");
        Path bk2 = writeRecordingZip("unsupported-sidecar.bk2", manifestContext, 2, true, true,
                new UserRecordingSidecarMetadata(
                        UserRecordingSidecarMetadata.CURRENT_DESYNC_LITE_SCHEMA_VERSION + 1,
                        "every-frame",
                        null));
        UserRecordingEntry entry = new UserRecordingEntry(
                bk2, "unsupported-sidecar", null, 2, fixture.now,
                RecordingVersionWarning.NONE, "");

        UserRecordingPlaybackState state = fixture.launcher()
                .beginPlayback(entry, new UserRecordingPlaybackOptions(1, true, false));

        assertEquals(UserRecordingPlaybackState.PLAYING, state);
        assertEquals(List.of(manifestContext), fixture.restarts);
        assertNotNull(fixture.startedMovie);
        assertNotNull(fixture.observer);
        assertEquals("schema-unsupported", fixture.launcher().activeVerifier().result().status());
    }

    private Path writeRecording(String fileName, RecordingLaunchContext context, int frameCount) throws Exception {
        Path path = tempDir.resolve("fixtures").resolve(fileName);
        UserRecordingWriter.write(
                path,
                manifest(fileName, context, frameCount),
                java.util.stream.IntStream.range(0, frameCount)
                        .mapToObj(frame -> new RecordedFrameInput(
                                frame,
                                frame == 0 ? AbstractPlayableSprite.INPUT_RIGHT : 0,
                                0,
                                false,
                                0,
                                0,
                                false))
                        .toList(),
                java.util.stream.IntStream.range(0, frameCount)
                        .mapToObj(frame -> new DesyncLiteFrame(
                                frame, 0, 0, 0, 0, 0, 0, 0,
                                0, 0, 0, 0, 0, 0, 0))
                        .toList());
        return path;
    }

    private Path writeRecordingZip(String fileName, RecordingLaunchContext context,
            int frameCount, boolean includeInputLog, boolean includeSidecar) throws Exception {
        return writeRecordingZip(fileName, context, frameCount, includeInputLog, includeSidecar,
                UserRecordingSidecarMetadata.everyFrame());
    }

    private Path writeRecordingZip(String fileName, RecordingLaunchContext context,
            int frameCount, boolean includeInputLog, boolean includeSidecar,
            UserRecordingSidecarMetadata sidecarMetadata) throws Exception {
        Path path = tempDir.resolve("fixtures").resolve(fileName);
        Files.createDirectories(path.getParent());
        try (OutputStream out = Files.newOutputStream(path);
                ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writeEntry(zip, "Header.txt", "Author: OpenGGF\nGameName: " + context.gameId() + "\n");
            if (includeInputLog) {
                writeEntry(zip, "Input Log.txt", inputLog(frameCount));
            }
            writeEntry(zip, "OpenGGF/manifest.json",
                    UserRecordingJson.writeManifest(manifest(fileName, context, frameCount, sidecarMetadata)));
            if (includeSidecar) {
                writeEntry(zip, "OpenGGF/desync-lite.jsonl", "{}\n");
            }
        }
        return path;
    }

    private static UserRecordingManifest manifest(String fileName, RecordingLaunchContext context, int frameCount) {
        return manifest(fileName, context, frameCount, UserRecordingSidecarMetadata.everyFrame());
    }

    private static UserRecordingManifest manifest(String fileName, RecordingLaunchContext context, int frameCount,
            UserRecordingSidecarMetadata sidecarMetadata) {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                fileName,
                new BuildIdentity("0.6.prerelease", "task9", false),
                context,
                sidecarMetadata,
                new RecordingDeterminismMetadata(0, null),
                "A",
                frameCount,
                UserRecordingStopReason.USER_STOPPED,
                Instant.parse("2026-06-29T14:30:22Z"));
    }

    private static String inputLog(int frameCount) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Input]\n");
        builder.append("LogKey:").append(UserRecordingWriter.LOG_KEY).append('\n');
        for (int frame = 0; frame < frameCount; frame++) {
            builder.append('|')
                    .append(frame == 0 ? "...RA..." : "........")
                    .append('|')
                    .append("........")
                    .append('|')
                    .append('\n');
        }
        builder.append("[/Input]\n");
        return builder.toString();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private final class Fixture implements UserRecordingSessionLauncher.GameLoopAdapter,
            UserRecordingSessionLauncher.LaunchContextSource,
            UserRecordingSessionLauncher.TraceModeGuard,
            UserRecordingSessionLauncher.PlaybackAdapter {
        GameMode mode = GameMode.LEVEL;
        boolean traceOrTestModeActive;
        Instant now = Instant.parse("2026-06-29T12:00:00Z");
        RecordingLaunchContext liveContext = new RecordingLaunchContext(
                "s2", 1, 0, "sonic", List.of("tails"),
                true, "current-act-fresh-start");
        final java.util.ArrayList<RecordingLaunchContext> restarts = new java.util.ArrayList<>();
        Bk2Movie startedMovie;
        int startedOffset = -1;
        PlaybackDebugManager.PlaybackFrameObserver observer;
        UserRecordingSessionLauncher launcher;

        UserRecordingSessionLauncher launcher() {
            if (launcher == null) {
                launcher = new UserRecordingSessionLauncher(
                        this,
                        this,
                        this,
                        this,
                        tempDir,
                        () -> now,
                        ZoneOffset.UTC);
            }
            return launcher;
        }

        @Override
        public GameMode currentGameMode() {
            return mode;
        }

        @Override
        public void restartFromRecordingLaunchContext(RecordingLaunchContext context) {
            restarts.add(context);
        }

        @Override
        public RecordingLaunchContext captureCurrentLaunchContext() {
            return liveContext;
        }

        @Override
        public boolean isTraceOrTestModeActive() {
            return traceOrTestModeActive;
        }

        @Override
        public void startSession(Bk2Movie movie, int startOffsetIndex) {
            startedMovie = movie;
            startedOffset = startOffsetIndex;
        }

        @Override
        public void setFrameObserver(PlaybackDebugManager.PlaybackFrameObserver observer) {
            this.observer = observer;
        }

        @Override
        public void endSession() {
            startedMovie = null;
            startedOffset = -1;
            observer = null;
        }
    }
}
