package com.openggf.capture;

import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every codec {@link CaptureCodecs} advertises as lossless must actually
 * reproduce the submitted pixels.
 *
 * <p>This is measured, not assumed. The obvious H.264 and H.265 lossless
 * settings — {@code -crf 0} and {@code -x265-params lossless=1} over
 * {@code yuv444p} — are lossless in the codec's own colour space and still do
 * not return the submitted RGB, because the conversion into and out of YUV is
 * not reversible. A reasonable implementation reaching for those flags would
 * quietly break the guarantee the feature is documented on, and no
 * command-construction test would notice.
 */
class TestCaptureCodecLosslessness {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 48;
    private static final int FRAMES = 8;

    @ParameterizedTest
    @ValueSource(strings = {"ffv1", "h264", "h265"})
    void codecsAdvertisedAsLosslessReturnTheSubmittedPixels(String codecName)
            throws Exception {
        Path ffmpeg = requireFfmpeg();
        CaptureCodecs.Codec codec = CaptureCodecs.video(codecName);
        assertTrue(codec.lossless(), codecName + " is advertised as lossless");

        byte[] source = randomRgba();
        Path directory = TestTempFiles.createTempDirectory("codec-lossless-");
        Path encoded = directory.resolve("out.mkv");

        // The same vflip the recorder applies, so the comparison is against
        // what the pipeline is supposed to produce rather than raw input.
        List<String> encode = new ArrayList<>(List.of(
                ffmpeg.toString(), "-v", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", WIDTH + "x" + HEIGHT, "-r", "60", "-i", "pipe:0",
                "-vf", "vflip"));
        encode.addAll(codec.arguments());
        encode.addAll(List.of("-an", encoded.toString()));

        runWithStdin(encode, source);
        byte[] decoded = decodeToRgb(ffmpeg, encoded);
        byte[] expected = expectedRgb(ffmpeg, source);

        assertEquals(expected.length, decoded.length,
                codecName + " decoded a different frame count or size");
        assertArrayEquals(expected, decoded,
                codecName + " is advertised as lossless but did not return the"
                        + " submitted pixels");
    }

    /**
     * Pins the reason the H.264 and H.265 arguments look the way they do. If
     * someone "simplifies" them to the conventional yuv444p form, the test
     * above fails; this one explains why by showing that form is genuinely
     * lossy on the same data.
     */
    @Test
    void theConventionalYuv444LosslessSettingIsNotByteExactForRgb()
            throws Exception {
        Path ffmpeg = requireFfmpeg();
        byte[] source = randomRgba();
        Path directory = TestTempFiles.createTempDirectory("codec-yuv444-");
        Path encoded = directory.resolve("yuv444.mkv");

        runWithStdin(List.of(ffmpeg.toString(), "-v", "error", "-y",
                "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", WIDTH + "x" + HEIGHT, "-r", "60", "-i", "pipe:0",
                "-vf", "vflip",
                "-c:v", "libx264", "-crf", "0", "-pix_fmt", "yuv444p",
                "-an", encoded.toString()), source);

        assertFalse(java.util.Arrays.equals(
                        expectedRgb(ffmpeg, source), decodeToRgb(ffmpeg, encoded)),
                "if -crf 0 over yuv444p has become byte-exact for RGB input,"
                        + " the RGB-native encoders in CaptureCodecs can be"
                        + " reconsidered");
    }

    @Test
    void audioCodecsDeclareWhetherTheyPreserveTheSubmittedSamples() {
        assertTrue(CaptureCodecs.audio("flac").lossless());
        assertEquals(List.of("-c:a", "pcm_s24le"),
                CaptureCodecs.audio("pcm-s24le").arguments());
        assertTrue(CaptureCodecs.audio("pcm-s24le").lossless());
        assertFalse(CaptureCodecs.audio("aac").lossless(),
                "aac is lossy and must say so, so the UI can warn");
        assertFalse(CaptureCodecs.audio("mp3").lossless());
    }

    @Test
    void unknownCodecNamesFailAtConfigurationRatherThanSilently() {
        assertThrows(IllegalArgumentException.class, () -> CaptureCodecs.video("vp9"));
        assertThrows(IllegalArgumentException.class, () -> CaptureCodecs.audio("opus"));
        assertThrows(IllegalArgumentException.class, () -> CaptureCodecs.video(null));
    }

    @Test
    void codecNamesAreAcceptedRegardlessOfCaseAndSurroundingSpace() {
        assertEquals("h264", CaptureCodecs.video("  H264 ").name());
        assertEquals("flac", CaptureCodecs.audio("FLAC").name());
    }

    // ---- helpers ----

    private static Path requireFfmpeg() {
        var found = FfmpegEncoder.findFfmpeg();
        Assumptions.assumeTrue(found.isPresent(), "ffmpeg is not on PATH");
        return found.orElseThrow();
    }

    private static byte[] randomRgba() {
        // Deterministic, and random data is the worst case for any lossy path.
        byte[] source = new byte[WIDTH * HEIGHT * 4 * FRAMES];
        new Random(20260725L).nextBytes(source);
        return source;
    }

    /** What the pipeline should produce: the submitted frames, vflipped, as RGB. */
    private static byte[] expectedRgb(Path ffmpeg, byte[] source) throws Exception {
        return runWithStdin(List.of(ffmpeg.toString(), "-v", "error",
                "-f", "rawvideo", "-pix_fmt", "rgba",
                "-s", WIDTH + "x" + HEIGHT, "-r", "60", "-i", "pipe:0",
                "-vf", "vflip", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"),
                source);
    }

    private static byte[] decodeToRgb(Path ffmpeg, Path media) throws Exception {
        return runWithStdin(List.of(ffmpeg.toString(), "-v", "error",
                "-i", media.toString(),
                "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"), new byte[0]);
    }

    private static byte[] runWithStdin(List<String> command, byte[] stdin)
            throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread writer = new Thread(() -> {
            try (var out = process.getOutputStream()) {
                out.write(stdin);
            } catch (IOException ignored) {
                // ffmpeg closes early on some paths; the exit code is the signal.
            }
        });
        writer.start();
        byte[] output;
        try (InputStream in = process.getInputStream()) {
            output = in.readAllBytes();
        }
        writer.join();
        assertEquals(0, process.waitFor(), "ffmpeg failed: " + String.join(" ", command));
        return output;
    }
}
