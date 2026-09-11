package com.openggf.capture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestFfmpegCommandTemplate {

    private static final Map<String, String> VALUES = Map.of(
            "width", "320", "height", "224", "fps", "60",
            "output", "/tmp/a b/out.mkv");

    @Test
    void unsetOrDefaultKeepsTheEnginesCommand() {
        assertTrue(FfmpegCommandTemplate.usesBuiltIn(null));
        assertTrue(FfmpegCommandTemplate.usesBuiltIn("default"));
        assertTrue(FfmpegCommandTemplate.usesBuiltIn("  DEFAULT  "));
        assertFalse(FfmpegCommandTemplate.usesBuiltIn("-c:v ffv1"));
        assertFalse(FfmpegCommandTemplate.usesBuiltIn(""));
    }

    /**
     * "Use the engine's command" and "do not run this pass" must not collapse
     * into one another: an unset key is the former, an emptied key the latter.
     */
    @Test
    void anEmptiedPassIsSkippedButAnUnsetOneIsNot() {
        assertTrue(FfmpegCommandTemplate.skipsPass(""));
        assertTrue(FfmpegCommandTemplate.skipsPass("   "),
                "a key left as blank whitespace means the same as empty");
        assertFalse(FfmpegCommandTemplate.skipsPass(null));
        assertFalse(FfmpegCommandTemplate.skipsPass("default"));
        assertFalse(FfmpegCommandTemplate.skipsPass("-c:a flac"));
    }

    @Test
    void placeholdersExpandAndArgumentsSplitOnWhitespace() {
        assertEquals(List.of("-s", "320x224", "-r", "60"),
                FfmpegCommandTemplate.expand("-s {width}x{height} -r {fps}", VALUES));
    }

    @Test
    void quotingKeepsAPathWithSpacesAsOneArgument() {
        assertEquals(List.of("-i", "/tmp/a b/out.mkv"),
                FfmpegCommandTemplate.expand("-i \"{output}\"", VALUES));
        assertEquals(List.of("-i", "/tmp/a b/out.mkv"),
                FfmpegCommandTemplate.expand("-i '{output}'", VALUES));
    }

    @Test
    void repeatedWhitespaceDoesNotProduceEmptyArguments() {
        assertEquals(List.of("-an", "-y"),
                FfmpegCommandTemplate.expand("   -an\t\t-y  ", VALUES));
    }

    /**
     * A mistyped placeholder would otherwise reach ffmpeg as a literal
     * filename and fail much later with an unrelated-looking message.
     */
    @Test
    void aMistypedPlaceholderFailsImmediatelyAndNamesWhatIsAvailable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> FfmpegCommandTemplate.expand("-s {widht}", VALUES));
        assertTrue(failure.getMessage().contains("{widht}"), failure.getMessage());
        assertTrue(failure.getMessage().contains("width"),
                "the message should list what is available: " + failure.getMessage());
    }

    @Test
    void malformedTemplatesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FfmpegCommandTemplate.expand("-s {width", VALUES));
        assertThrows(IllegalArgumentException.class,
                () -> FfmpegCommandTemplate.expand("-i \"{output}", VALUES));
    }

    /** The first pass is where frames are encoded; skipping it is meaningless. */
    @Test
    void theFirstPassCannotBeSkipped() {
        FfmpegEncoder encoder = new FfmpegEncoder("ffmpeg", 1);
        assertThrows(IllegalArgumentException.class,
                () -> encoder.setCommandOverrides("", "default"));
        encoder.setCommandOverrides("default", "");
    }

    @Test
    void theContainerSetsTheRecordingExtension() {
        var recorder = new CaptureRecorder(new FfmpegEncoder("ffmpeg", 1),
                BackpressurePolicy.BLOCK, 8, java.nio.file.Path.of("out"),
                "live", "20260725-120000-000", "mp4");
        assertTrue(recorder.outputFile().getFileName().toString().endsWith(".mp4"),
                recorder.outputFile().toString());
    }

    @Test
    void theContainerDefaultsToMkvAndToleratesALeadingDotOrCase() {
        assertTrue(recorderWith(null, "mkv").endsWith(".mkv"));
        assertTrue(recorderWith(".MP4", "x").endsWith(".mp4"),
                "a leading dot and upper case are the obvious ways to write this");
    }

    /** A bad extension would otherwise become part of the filename. */
    @Test
    void anInvalidContainerIsRejectedBeforeRecordingStarts() {
        assertThrows(IllegalArgumentException.class, () -> recorderWith("", "x"));
        assertThrows(IllegalArgumentException.class, () -> recorderWith("  ", "x"));
        assertThrows(IllegalArgumentException.class, () -> recorderWith("../evil", "x"));
        assertThrows(IllegalArgumentException.class, () -> recorderWith("mp4 -y", "x"));
    }

    private static String recorderWith(String container, String unused) {
        var recorder = container == null
                ? new CaptureRecorder(new FfmpegEncoder("ffmpeg", 1),
                        BackpressurePolicy.BLOCK, 8, java.nio.file.Path.of("out"),
                        "live", "t")
                : new CaptureRecorder(new FfmpegEncoder("ffmpeg", 1),
                        BackpressurePolicy.BLOCK, 8, java.nio.file.Path.of("out"),
                        "live", "t", container);
        return recorder.outputFile().getFileName().toString();
    }

    @Test
    void selectedCodecsReachTheBuiltInCommands() {
        assertTrue(FfmpegEncoder.phase1Command("ffmpeg", java.nio.file.Path.of("v.mkv"),
                        320, 224, 60, 1, CaptureCodecs.video("h264"))
                .containsAll(List.of("libx264rgb", "-crf", "0")));
        assertTrue(FfmpegEncoder.phase2MuxCommand("ffmpeg", java.nio.file.Path.of("v.mkv"),
                        java.nio.file.Path.of("a.raw"), 48000, java.nio.file.Path.of("o.mkv"),
                        CaptureCodecs.audio("mp3"))
                .contains("libmp3lame"));
    }
}
