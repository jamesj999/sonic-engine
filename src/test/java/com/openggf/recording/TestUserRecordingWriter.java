package com.openggf.game.recording;

import com.openggf.control.InputActionMasks;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserRecordingWriter {
    @TempDir
    Path tempDir;

    @Test
    void writesBk2WithOpenGgfEntriesAndLoadsWithBk2MovieLoader() throws Exception {
        Path bk2Path = tempDir.resolve("s3k-aiz1-test.bk2");
        UserRecordingManifest manifest = sampleManifest(2);
        List<RecordedFrameInput> inputs = List.of(
                new RecordedFrameInput(
                        0,
                        AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                        InputActionMasks.ACTION_A,
                        false,
                        0,
                        0,
                        false),
                new RecordedFrameInput(
                        1,
                        0,
                        0,
                        true,
                        AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_JUMP,
                        InputActionMasks.ACTION_A,
                        true));

        UserRecordingWriter.write(bk2Path, manifest, inputs, sidecarFrames(2));

        try (ZipFile zip = new ZipFile(bk2Path.toFile())) {
            assertNotNull(zip.getEntry("Header.txt"));
            assertNotNull(zip.getEntry("Input Log.txt"));
            assertNotNull(zip.getEntry("OpenGGF/manifest.json"));
            assertNotNull(zip.getEntry("OpenGGF/desync-lite.jsonl"));
        }

        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);

        assertEquals(2, movie.getFrameCount());
        assertEquals(UserRecordingWriter.LOG_KEY, movie.getLogKey());
        assertEquals("OpenGGF", movie.getHeaderMetadata().get("Author"));
        assertEquals("s3k", movie.getHeaderMetadata().get("GameName"));
        assertEquals("2", movie.getHeaderMetadata().get("Frames"));
        assertTrue((movie.getFrame(0).p1InputMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0);
        assertEquals(InputActionMasks.ACTION_A, movie.getFrame(0).p1ActionMask());
        assertTrue(movie.getFrame(1).p1StartPressed());
        assertTrue((movie.getFrame(1).p2InputMask() & AbstractPlayableSprite.INPUT_LEFT) != 0);
        assertEquals(InputActionMasks.ACTION_A, movie.getFrame(1).p2ActionMask());
        assertTrue(movie.getFrame(1).p2StartPressed());
    }

    @Test
    void writesP1BAndRoundTripsWithBk2MovieLoader() throws Exception {
        Path bk2Path = tempDir.resolve("p1-b-action.bk2");
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(
                0,
                AbstractPlayableSprite.INPUT_JUMP,
                InputActionMasks.ACTION_B,
                false,
                0,
                0,
                false));

        UserRecordingWriter.write(bk2Path, sampleManifest(1), inputs, sidecarFrames(1));

        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);
        assertEquals(InputActionMasks.ACTION_B, movie.getFrame(0).p1ActionMask());
        assertEquals(0, movie.getFrame(0).p2ActionMask());
    }

    @Test
    void writesP2CAndRoundTripsWithBk2MovieLoader() throws Exception {
        Path bk2Path = tempDir.resolve("p2-c-action.bk2");
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(
                0,
                0,
                0,
                false,
                AbstractPlayableSprite.INPUT_JUMP,
                InputActionMasks.ACTION_C,
                false));

        UserRecordingWriter.write(bk2Path, sampleManifest(1), inputs, sidecarFrames(1));

        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);
        assertEquals(0, movie.getFrame(0).p1ActionMask());
        assertEquals(InputActionMasks.ACTION_C, movie.getFrame(0).p2ActionMask());
    }

    @Test
    void rejectsActionMasksWithBitsOutsideActionAllBeforeWriting() {
        Path bk2Path = tempDir.resolve("invalid-action-bit.bk2");
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(0, 0, 0x08, false, 0, 0, false));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(1), inputs, sidecarFrames(1)));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void rejectsManifestFrameCountThatDoesNotMatchInputCountBeforeWriting() {
        Path bk2Path = tempDir.resolve("bad-frame-count.bk2");
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(0, 0, 0, false, 0, 0, false));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(2), inputs, sidecarFrames(1)));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void rejectsRecordedFrameInputsThatDoNotMatchSequentialTimelineBeforeWriting() {
        Path bk2Path = tempDir.resolve("bad-input-timeline.bk2");
        List<RecordedFrameInput> inputs = List.of(
                new RecordedFrameInput(0, 0, 0, false, 0, 0, false),
                new RecordedFrameInput(2, 0, 0, false, 0, 0, false));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(2), inputs, sidecarFrames(2)));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void rejectsEmptySidecarFramesForNonzeroInputsBeforeWriting() {
        Path bk2Path = tempDir.resolve("empty-sidecar-nonzero-input.bk2");
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(0, 0, 0, false, 0, 0, false));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(1), inputs, List.of()));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void rejectsSidecarFrameCountThatDoesNotMatchInputCountBeforeWriting() {
        Path bk2Path = tempDir.resolve("bad-sidecar-count.bk2");
        List<RecordedFrameInput> inputs = List.of(
                new RecordedFrameInput(0, 0, 0, false, 0, 0, false),
                new RecordedFrameInput(1, 0, 0, false, 0, 0, false));
        List<DesyncLiteFrame> sidecarFrames = List.of(sidecarFrame(0));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(2), inputs, sidecarFrames));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void rejectsSidecarFramesThatDoNotMatchInputTimelineBeforeWriting() {
        Path bk2Path = tempDir.resolve("bad-sidecar-timeline.bk2");
        List<RecordedFrameInput> inputs = List.of(
                new RecordedFrameInput(0, 0, 0, false, 0, 0, false),
                new RecordedFrameInput(1, 0, 0, false, 0, 0, false));
        List<DesyncLiteFrame> sidecarFrames = List.of(sidecarFrame(0), sidecarFrame(3));

        assertThrows(IllegalArgumentException.class,
                () -> UserRecordingWriter.write(bk2Path, sampleManifest(2), inputs, sidecarFrames));
        assertFalse(Files.exists(bk2Path));
    }

    @Test
    void preservesPreExistingDeterministicTmpSibling() throws Exception {
        Path bk2Path = tempDir.resolve("with-sentinel.bk2");
        Path deterministicTmp = tempDir.resolve("with-sentinel.bk2.tmp");
        Files.writeString(deterministicTmp, "sentinel", StandardCharsets.UTF_8);
        List<RecordedFrameInput> inputs = List.of(new RecordedFrameInput(0, 0, 0, false, 0, 0, false));

        UserRecordingWriter.write(bk2Path, sampleManifest(1), inputs, sidecarFrames(1));

        assertTrue(Files.exists(bk2Path));
        assertTrue(Files.exists(deterministicTmp));
        assertEquals("sentinel", Files.readString(deterministicTmp, StandardCharsets.UTF_8));
    }

    private static List<DesyncLiteFrame> sidecarFrames(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(TestUserRecordingWriter::sidecarFrame)
                .toList();
    }

    private static DesyncLiteFrame sidecarFrame(int frame) {
        return new DesyncLiteFrame(frame, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static UserRecordingManifest sampleManifest(int frameCount) {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                "s3k-aiz1-test",
                new BuildIdentity("0.6.prerelease", "abcdef123", false),
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
}
